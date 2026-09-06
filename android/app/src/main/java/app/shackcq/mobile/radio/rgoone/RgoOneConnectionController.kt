package app.shackcq.mobile.radio.rgoone

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class RgoOnePollCadence { FAST, MEDIUM, SLOW }
enum class RgoOneDispatchResult { SENT, DENIED, REVIEW_REQUIRED, INVALID, NOT_CONNECTED }

data class RgoOneDiagnosticEvent(val epochMillis: Long, val kind: String, val detail: String)

class RgoOneDiagnostics(private val maximumEvents: Int = 100) {
    private val events = ArrayDeque<RgoOneDiagnosticEvent>()
    @Synchronized fun record(event: RgoOneDiagnosticEvent) {
        events += event.copy(detail = event.detail.replace(Regex("(?i)(SN|ID)[0-9A-F ]{8,}"), "SN[redacted]"))
        while (events.size > maximumEvents) events.removeFirst()
    }
    @Synchronized fun snapshot(): List<RgoOneDiagnosticEvent> = events.toList()
    @Synchronized fun supportBundle(serialOptIn: Boolean = false): Map<String, Any> = mapOf(
        "event_count" to events.size,
        "events" to events.map { mapOf("time" to it.epochMillis, "kind" to it.kind, "detail" to it.detail) },
        "serial_identity" to if (serialOptIn) "hashed-only" else "excluded",
    )
}

class RgoOneConnectionController(
    private val serial: RgoOneSerialPort,
    private val usbIdentity: RgoOneUsbIdentityPort = RgoOneUsbIdentityPort { null },
    private val safety: RgoOneSafetyPort = RgoOneSafetyPort { _, _ -> RgoOneSafetyDecision.REVIEW_REQUIRED },
    private val clock: RgoOneClock = RgoOneClock(System::currentTimeMillis),
    private val diagnostics: RgoOneDiagnostics = RgoOneDiagnostics(),
) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "shackcq-rgo-one-cat").apply { isDaemon = true } }
    private val ioLock = Any()
    private val pollActive = RgoOnePollCadence.entries.associateWith { AtomicBoolean(false) }
    private val pollTasks = mutableListOf<ScheduledFuture<*>>()
    private var transportOpen = false
    private var closed = false
    private var hasConnectedOnce = false
    private var lastSettings = RgoOneSettingsDocument()
    private var decoder = RgoOneProtocolDecoder()
    @Volatile private var state = RgoOneRadioSnapshot()

    fun snapshot(): RgoOneRadioSnapshot = state
    fun diagnosticSnapshot(): List<RgoOneDiagnosticEvent> = diagnostics.snapshot()

    @Synchronized fun connect(settingsDocument: RgoOneSettingsDocument): Boolean {
        if (closed || transportOpen) return false
        val settings = settingsDocument.safeRestore().copy(
            writesConfirmed = settingsDocument.writesConfirmed,
            memoryWriteEnabled = settingsDocument.memoryWriteEnabled,
        )
        val identity = usbIdentity.resolve(serial.stableIdentity)
        val generation = settings.generation.takeUnless { it == RgoOneGeneration.UNKNOWN } ?: identity?.generation ?: RgoOneGeneration.UNKNOWN
        val confirmed = settings.generation != RgoOneGeneration.UNKNOWN || identity?.generationConfirmed == true
        val modules = RgoOneCapabilityResolver.initialModules(settings, identity)
        val config = transportConfig(settings) ?: run {
            state = state.copy(connectionState = RgoOneConnectionState.DEGRADED, status = "TTL framing is not documented; confirmation required")
            return false
        }
        state = RgoOneRadioSnapshot(connectionState = RgoOneConnectionState.CONNECTING, stableTransportIdentity = serial.stableIdentity,
            generation = generation, generationConfirmed = confirmed, modules = modules, status = "Connecting")
        synchronized(ioLock) { transportOpen = serial.open(config) }
        if (!transportOpen) {
            state = state.copy(connectionState = RgoOneConnectionState.DISCONNECTED, status = "Transport open failed")
            return false
        }
        lastSettings = settings
        hasConnectedOnce = true
        state = state.copy(connected = true, connectionState = RgoOneConnectionState.CONNECTED, status = if (confirmed) "Connected" else "Connected read-only; generation confirmation required",
            capabilities = RgoOneCapabilityResolver.resolve(generation, confirmed, null, modules))
        diagnostics.record(RgoOneDiagnosticEvent(clock.epochMillis(), "OPEN", serial.stableIdentity))
        if (generation == RgoOneGeneration.V6 && confirmed) {
            val model = exchange("ID;")
            if (model !is RgoOneProtocolResponse.ModelId || model.value != "006") {
                state = state.copy(generation = RgoOneGeneration.UNKNOWN, generationConfirmed = false, stale = true,
                    connectionState = RgoOneConnectionState.DEGRADED, status = "V6 identity was not proven")
                return true
            }
            exchange("FW;")
            pollOnce(RgoOnePollCadence.FAST)
            pollOnce(RgoOnePollCadence.MEDIUM)
            pollOnce(RgoOnePollCadence.SLOW)
            startPolling()
        }
        return true
    }

    private fun transportConfig(settings: RgoOneSettingsDocument): RgoOneSerialConfig? = when (settings.transport) {
        RgoOneTransportType.USB_CAT -> RgoOneSerialConfig(RgoOneTransportType.USB_CAT)
        RgoOneTransportType.TTL_SERIAL -> settings.ttlFraming?.let { RgoOneSerialConfig(RgoOneTransportType.TTL_SERIAL, settings.ttlBaud, it) }
    }

    private fun startPolling() {
        pollTasks += scheduler.scheduleWithFixedDelay({ pollOnce(RgoOnePollCadence.FAST) }, lastSettings.fastPollMillis, lastSettings.fastPollMillis, TimeUnit.MILLISECONDS)
        pollTasks += scheduler.scheduleWithFixedDelay({ pollOnce(RgoOnePollCadence.MEDIUM) }, lastSettings.mediumPollMillis, lastSettings.mediumPollMillis, TimeUnit.MILLISECONDS)
        pollTasks += scheduler.scheduleWithFixedDelay({ pollOnce(RgoOnePollCadence.SLOW) }, lastSettings.slowPollMillis, lastSettings.slowPollMillis, TimeUnit.MILLISECONDS)
    }

    fun pollOnce(cadence: RgoOnePollCadence): Boolean {
        if (!state.connected || !state.generationConfirmed || state.generation != RgoOneGeneration.V6) return false
        val active = pollActive.getValue(cadence)
        if (!active.compareAndSet(false, true)) return false
        return try {
            commandsFor(cadence).distinct().forEach(::exchange)
            true
        } finally {
            active.set(false)
        }
    }

    private fun commandsFor(cadence: RgoOnePollCadence): List<String> = when (cadence) {
        RgoOnePollCadence.FAST -> listOf("FA;", "FB;", "FR;", "FT;", "MD;", "FS;", "SM0;")
        RgoOnePollCadence.MEDIUM -> listOf("GT;", "RT;", "XT;", "RG;", "PC;", "KS;", "MG;", "PA;", "RA;", "NB;")
        RgoOnePollCadence.SLOW -> listOf("FW;", "AC;", RgoOneCommandBuilder.extendedMenuRead(42)!!)
    }

    fun dispatch(action: RgoOneAction): RgoOneDispatchResult {
        if (!state.connected) return RgoOneDispatchResult.NOT_CONNECTED
        if (!RgoOneCommandPolicy.permits(action, state, lastSettings)) return RgoOneDispatchResult.DENIED
        if (actionSatisfied(action, state)) return RgoOneDispatchResult.SENT
        val safetyDecision = if (action.actionClass == RgoOneActionClass.READ_ONLY) RgoOneSafetyDecision.ALLOW_ONCE else safety.review(action, state)
        if (safetyDecision == RgoOneSafetyDecision.DENY) return RgoOneDispatchResult.DENIED
        if (safetyDecision == RgoOneSafetyDecision.REVIEW_REQUIRED) return RgoOneDispatchResult.REVIEW_REQUIRED
        val command = RgoOneCommandBuilder.forAction(action) ?: return RgoOneDispatchResult.INVALID
        val noResponse = action is RgoOneAction.ClearRit || action is RgoOneAction.NudgeRit || action is RgoOneAction.Receive ||
            action is RgoOneAction.Transmit || action is RgoOneAction.Tune || action is RgoOneAction.WriteMemory
        if (noResponse) {
            val sent = synchronized(ioLock) { transportOpen && serial.write(command.toByteArray(Charsets.US_ASCII)) }
            diagnostics.record(RgoOneDiagnosticEvent(clock.epochMillis(), if (sent) "WRITE" else "WRITE_FAILED", command.take(2)))
            return if (sent) RgoOneDispatchResult.SENT else RgoOneDispatchResult.INVALID
        }
        return if (exchange(command) is RgoOneProtocolResponse.Malformed) RgoOneDispatchResult.INVALID else RgoOneDispatchResult.SENT
    }

    private fun actionSatisfied(action: RgoOneAction, snapshot: RgoOneRadioSnapshot): Boolean = when (action) {
        is RgoOneAction.SetFrequency -> if (action.vfo == RgoOneVfo.A) snapshot.vfoAHz == action.frequencyHz else snapshot.vfoBHz == action.frequencyHz
        is RgoOneAction.SelectRxVfo -> snapshot.rxVfo == action.vfo
        is RgoOneAction.SelectTxVfo -> snapshot.txVfo == action.vfo
        is RgoOneAction.SetMode -> snapshot.mode == action.mode
        is RgoOneAction.SetAgc -> snapshot.agc == action.agc
        is RgoOneAction.SetToggle -> when (action.command.uppercase()) {
            "FS" -> snapshot.fineTune == action.enabled
            "NB" -> snapshot.noiseBlanker == action.enabled
            "RT" -> snapshot.ritEnabled == action.enabled
            "XT" -> snapshot.xitEnabled == action.enabled
            else -> false
        }
        is RgoOneAction.SetLevel -> when (action.command.uppercase()) {
            "KS" -> snapshot.keyerSpeedWpm == action.value
            "MG" -> snapshot.micGain == action.value
            "PC" -> snapshot.txPowerWatts == action.value
            "RG" -> snapshot.rfGain == action.value
            else -> false
        }
        else -> false
    }

    private fun exchange(command: String): RgoOneProtocolResponse {
        if (!transportOpen || command.length > 64 || !command.endsWith(';')) return RgoOneProtocolResponse.Malformed("transport unavailable")
        val expected = command.take(2)
        val bytes = synchronized(ioLock) {
            serial.exchange(command.toByteArray(Charsets.US_ASCII), RgoOneProtocol.MAX_FRAME_BYTES, 1_000)
        } ?: return RgoOneProtocolResponse.Malformed("no response")
        val frames = decoder.accept(bytes)
        val response = frames.firstOrNull()?.let { RgoOneProtocolParser.parse(it, expected) }
            ?: RgoOneProtocolResponse.Malformed("incomplete response")
        if (response is RgoOneProtocolResponse.Malformed) diagnostics.record(RgoOneDiagnosticEvent(clock.epochMillis(), "PROTOCOL", "$expected:${response.reason}"))
        else state = RgoOneSnapshotReducer.apply(state, response, clock.epochMillis())
        return response
    }

    @Synchronized fun disconnect() {
        pollTasks.forEach { it.cancel(true) }
        pollTasks.clear()
        synchronized(ioLock) {
            if (transportOpen) serial.close()
            transportOpen = false
        }
        decoder.reset()
        state = state.copy(connected = false, stale = true, connectionState = RgoOneConnectionState.DISCONNECTED, status = "Disconnected")
    }

    @Synchronized fun reconnect(): Boolean {
        if (closed || !hasConnectedOnce) return false
        val identity = state.stableTransportIdentity
        state = state.copy(stale = true, connectionState = RgoOneConnectionState.RECONNECTING, status = "Reconnecting")
        disconnect()
        if (serial.stableIdentity != identity) {
            state = state.copy(connectionState = RgoOneConnectionState.DEGRADED, status = "Stable transport identity changed")
            return false
        }
        return connect(lastSettings)
    }

    @Synchronized override fun close() {
        if (closed) return
        disconnect()
        closed = true
        scheduler.shutdownNow()
        state = state.copy(connectionState = RgoOneConnectionState.CLOSED, status = "Closed")
    }
}
