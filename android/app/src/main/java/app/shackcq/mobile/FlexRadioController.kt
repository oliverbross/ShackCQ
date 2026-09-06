package app.shackcq.mobile

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

enum class FlexConnectionState(val label: String) {
    IDLE("NO LAN RADIOS"), SEARCHING("SEARCHING LAN"), SIGNED_OUT("SIGNED OUT"), SIGNING_IN("SIGNING IN"),
    DISCOVERING_WAN("DISCOVERING SMARTLINK RADIOS"), CONNECTING("CONNECTING"), VALIDATING("VALIDATING WAN SESSION"),
    WAITING_HANDLE("WAITING FOR FLEX HANDLE"), CONNECTED("CONNECTED"), NO_STATION("NO GUI STATION"),
    NO_SLICE("NO EXISTING FLEX SLICE"), LOST("CONNECTION LOST"), RECONNECTING("RECONNECTING"),
    CERTIFICATE_CHANGED("SMARTLINK RADIO CERTIFICATE CHANGED"),
    OWNER_CONFIG_REQUIRED("OWNER CONFIGURATION VALUES REQUIRED")
}

class FlexRadioController(
    context: Context,
    private val preferredStation: () -> String,
    private val saveStation: (String) -> Unit,
    private val manualAddress: () -> String,
) : RadioBackend {
    override val capabilities = setOf(
        RadioCapability.RECEIVE_STATE, RadioCapability.RECEIVE_TUNE, RadioCapability.FILTER,
        RadioCapability.PANADAPTER, RadioCapability.MACROS, RadioCapability.TRANSMIT,
    )
    private val native = NativeHandleOwner(
        initialHandle = NativeCore.flexCreate().also { check(it != 0L) { "Native Flex parser unavailable" } },
        destroyHandle = NativeCore::flexDestroy,
    )
    private val controllerJob = SupervisorJob()
    private val scope = CoroutineScope(controllerJob + Dispatchers.IO)
    private val sequence = AtomicInteger(1)
    private val guiClientId = UUID.randomUUID()
    private val pendingReplies = ConcurrentHashMap<Int, (Long, String) -> Unit>()
    private val statusFramer = FlexStatusFramer()
    private var socket: Socket? = null
    private var reader: Job? = null
    private var keepalive: Job? = null
    private var discovery: Job? = null
    private var smartLinkDiscovery: Job? = null
    private var reconnect: Job? = null
    @Volatile private var closed = false
    private var operatorEstablished = false
    private var lastTarget: FlexDiscovery? = null
    private val smartLinkConfig = SmartLinkConfig.issued()
    private val refreshStore = SmartLinkRefreshStore(context)
    private val trustStore = SmartLinkTrustStore(context)
    var pendingCertificateChange by mutableStateOf<SmartLinkCertificateChanged?>(null); private set
    private var authSession: SmartLinkAuthSession? = null
    private var accessToken: String? = null
    private val refreshMutex = Mutex()
    private var broker: SmartLinkBrokerClient? = null
    private val owned = FlexOwnedObjects()
    private val meterBank = FlexMeterBank()
    private val networkAudio = FlexAudioEngine()
    private var audioRoutes: AudioMonitorController? = null
    private var voiceMacroStore: VoiceMacroStore? = null
    private var rxAudioStreamId: Long = 0
    private val streamSession = FlexStreamSession(::sendBody, ::handleVitaEvent) { message ->
        detail = message
        if (message.contains("timed out", true)) connectionState = FlexConnectionState.LOST
    }
    private val extendedTracker = FlexExtendedStateTracker(
        { id, kind -> streamSession.register(id, kind) },
        meterBank,
    )
    private val micTx = FlexMicTxEngine(context, scope, streamSession::send) { message ->
        detail = message
        scope.launch { stopTransmit("microphone or route failure") }
    }
    private var remoteTxStreamId = 0L
    val tx = FlexTxController(scope, ::sendBody, ::releaseFlexTxAudio)
    private var currentUdpMode = FlexUdpMode.LAN
    private var currentRadioHost = ""
    val radios = mutableStateListOf<FlexDiscovery>()
    val smartLinkRadios = mutableStateListOf<SmartLinkRadio>()
    var snapshot by mutableStateOf(FlexSnapshot()); private set
    var extended by mutableStateOf(FlexExtendedSnapshot()); private set
    var spectrum by mutableStateOf<FlexSpectrumFrame?>(null); private set
    val waterfallRows = mutableStateListOf<FlexWaterfallRow>()
    var meters by mutableStateOf<Map<String, Float>>(emptyMap()); private set
    var displayMode by mutableStateOf(FlexDisplayMode.ATTACH); private set
    var rxAudioEnabled by mutableStateOf(false); private set
    var selectedSliceIndex by mutableStateOf<Int?>(null); private set
    var selectedTxSliceIndex by mutableStateOf<Int?>(null); private set
    var connectionState by mutableStateOf(FlexConnectionState.IDLE); private set
    var detail by mutableStateOf("FlexRadio is not connected"); private set
    var lastDisconnectReason by mutableStateOf(""); private set
    val smartLinkConfigured get() = smartLinkConfig.complete
    val smartLinkSignedIn get() = accessToken != null
    val manualTarget get() = manualFlexDiscovery(manualAddress())
    override val state get() = snapshot.toRadioState(selectedSliceIndex)

    init {
        if (smartLinkConfigured) refreshStore.load()?.let { stored -> scope.launch {
            refreshMutex.withLock {
                SmartLinkAuth.refresh(smartLinkConfig, stored)?.let { tokens ->
                    accessToken = tokens.idToken; refreshStore.save(tokens.refreshToken)
                    withContext(Dispatchers.Main) { connectionState = FlexConnectionState.DISCOVERING_WAN; discoverSmartLinkRadios() }
                }
            }
        } }
    }

    fun beginSmartLinkSignIn(): Uri? {
        if (!smartLinkConfigured) { connectionState = FlexConnectionState.OWNER_CONFIG_REQUIRED; return null }
        val session = SmartLinkAuth.begin(smartLinkConfig) ?: return null
        authSession = session; connectionState = FlexConnectionState.SIGNING_IN; return session.authorizationUri
    }

    fun completeSmartLinkSignIn(redirect: Uri) {
        val session = authSession ?: return
        val tokens = SmartLinkAuth.validateRedirect(smartLinkConfig, session, redirect) ?: run { detail = "SmartLink redirect validation failed"; connectionState = FlexConnectionState.SIGNED_OUT; return }
        authSession = null
        accessToken = tokens.idToken
        refreshStore.save(tokens.refreshToken)
        detail = "SmartLink sign-in complete"
        connectionState = FlexConnectionState.DISCOVERING_WAN
        discoverSmartLinkRadios()
    }

    fun logout() {
        scope.launch {
            disconnectSession()
            smartLinkDiscovery?.cancel(); smartLinkDiscovery = null
            accessToken = null; authSession = null; broker?.close(); broker = null
            smartLinkRadios.clear(); refreshStore.clear()
            connectionState = FlexConnectionState.SIGNED_OUT; detail = "SmartLink signed out"
        }
    }

    private fun discoverSmartLinkRadios() {
        val token = accessToken ?: return
        smartLinkDiscovery?.cancel()
        smartLinkDiscovery = scope.launch {
            val result = runCatching {
                broker?.close(); SmartLinkBrokerClient(smartLinkConfig).also { broker = it }.connectAndList(token)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { smartLinkRadios.clear(); smartLinkRadios.addAll(it); detail = "${it.size} SmartLink radio(s) available" }
                    .onFailure {
                        broker?.close(); broker = null
                        detail = it.message ?: "SmartLink broker discovery failed"; connectionState = FlexConnectionState.LOST
                    }
            }
        }
    }

    fun refreshSmartLinkRadios() {
        if (accessToken == null) {
            detail = "Sign in to SmartLink before refreshing the radio directory"
            connectionState = FlexConnectionState.SIGNED_OUT
            return
        }
        connectionState = FlexConnectionState.DISCOVERING_WAN
        detail = "Waiting up to 30 seconds for a SmartLink radio directory update"
        discoverSmartLinkRadios()
    }

    suspend fun connectSmartLink(radio: SmartLinkRadio): Boolean {
        disconnectSession(); connectionState = FlexConnectionState.CONNECTING; detail = "Requesting ${radio.nickname.ifBlank { radio.model }} through SmartLink"
        return try {
            val endpoint = withContext(Dispatchers.IO) { broker?.request(radio) ?: error("SmartLink broker did not provide a direct endpoint") }
            connectionState = FlexConnectionState.VALIDATING
            val connected = withContext(Dispatchers.IO) { connectValidatedWan(endpoint, trustStore) }
            establishCommandChannel(connected, operatorInitiated = true, FlexUdpMode.SMARTLINK, endpoint.host)
        } catch (error: Exception) {
            disconnectSession()
            if (error is SmartLinkCertificateChanged) {
                pendingCertificateChange = error
                detail = if (error.expectedFingerprint.isBlank())
                    "Review and explicitly trust the selected radio certificate before first connection."
                else "The selected radio presented a different TLS certificate. Review before connecting."
                connectionState = FlexConnectionState.CERTIFICATE_CHANGED
            } else {
                detail = error.message ?: "SmartLink connection failed"
                connectionState = FlexConnectionState.LOST
            }
            false
        }
    }

    fun resolveCertificateChange(accept: Boolean) {
        val change = pendingCertificateChange ?: return
        pendingCertificateChange = null
        if (!accept) {
            connectionState = FlexConnectionState.LOST
            detail = if (change.expectedFingerprint.isBlank()) "SmartLink radio certificate was not trusted"
                else "SmartLink radio certificate change rejected"
            return
        }
        trustStore.save(change.endpoint, change.observedFingerprint)
        scope.launch {
            connectionState = FlexConnectionState.CONNECTING
            try {
                val connected = withContext(Dispatchers.IO) { connectValidatedWan(change.endpoint, trustStore) }
                establishCommandChannel(connected, operatorInitiated = true, FlexUdpMode.SMARTLINK, change.endpoint.host)
            } catch (error: Exception) {
                disconnectSession()
                detail = error.message ?: "SmartLink reconnect after certificate approval failed"
                connectionState = FlexConnectionState.LOST
            }
        }
    }

    fun discoverLan(seconds: Int = 4) {
        discovery?.cancel(); radios.clear(); connectionState = FlexConnectionState.SEARCHING
        discovery = scope.launch {
            val seen = linkedMapOf<String, Pair<FlexDiscovery, Long>>()
            try {
                DatagramSocket(null).use { udp ->
                    udp.reuseAddress = true; udp.broadcast = true; udp.soTimeout = 350
                    udp.bind(InetSocketAddress(4992))
                    val deadline = android.os.SystemClock.elapsedRealtime() + seconds.coerceIn(1, 10) * 1_000L
                    while (isActive && android.os.SystemClock.elapsedRealtime() < deadline) {
                        val buffer = ByteArray(4096); val packet = DatagramPacket(buffer, buffer.size)
                        try { udp.receive(packet); parseFlexDiscovery(buffer.copyOf(packet.length))?.let { radio ->
                            seen[radio.serial.ifBlank { radio.ip }] = radio to android.os.SystemClock.elapsedRealtime()
                            val fresh = seen.values.filter { android.os.SystemClock.elapsedRealtime() - it.second < 4_000 }.map { it.first }
                            withContext(Dispatchers.Main) { radios.clear(); radios.addAll(fresh) }
                        } } catch (_: SocketTimeoutException) { }
                    }
                }
                withContext(Dispatchers.Main) { connectionState = if (radios.isEmpty()) FlexConnectionState.IDLE else FlexConnectionState.SIGNED_OUT }
            } catch (error: Exception) {
                if (error !is CancellationException) withContext(Dispatchers.Main) { detail = error.message ?: "LAN discovery failed"; connectionState = FlexConnectionState.LOST }
            }
        }
    }

    suspend fun connectLan(radio: FlexDiscovery, operatorInitiated: Boolean = true): Boolean {
        if (closed) return false
        lastTarget = radio
        disconnectSession()
        connectionState = FlexConnectionState.CONNECTING; detail = "Connecting to ${radio.nickname.ifBlank { radio.model }}"
        return try {
            val connected = withContext(Dispatchers.IO) { Socket().apply { connect(InetSocketAddress(radio.ip, radio.port), 5_000); soTimeout = 750 } }
            establishCommandChannel(connected, operatorInitiated, FlexUdpMode.LAN, radio.ip)
        } catch (error: Exception) {
            disconnectSession()
            detail = error.message ?: "Flex connection failed"; connectionState = FlexConnectionState.LOST; false
        }
    }

    private suspend fun establishCommandChannel(
        connected: Socket,
        operatorInitiated: Boolean,
        udpMode: FlexUdpMode,
        radioHost: String,
    ): Boolean {
        socket = connected; connectionState = FlexConnectionState.WAITING_HANDLE
        currentUdpMode = udpMode
        currentRadioHost = radioHost
        reader = scope.launch { readLoop(connected) }
        val handleDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (snapshot.handle == 0L && android.os.SystemClock.elapsedRealtime() < handleDeadline) delay(100)
        if (snapshot.handle == 0L) error("Flex connected without a nonzero client handle")
        val bootstrap = FlexCommands.sessionIdentity(guiClientId) + FlexCommands.subscriptions() + FlexCommands.requestProfiles()
        if (!withContext(Dispatchers.IO) { bootstrap.all(::sendBody) }) error("Flex session bootstrap could not be sent")
        if (!withContext(Dispatchers.IO) { streamSession.start(udpMode, snapshot.handle, radioHost) }) error("Flex UDP stream registration could not start")
        keepalive = scope.launch { while (isActive) { delay(5_000); sendBody(NativeCore.flexKeepalive()) } }
        updateSelection()
        connectionState = effectiveState()
        detail = "Flex command channel established"
        if (operatorInitiated) operatorEstablished = true
        return true
    }

    override suspend fun connect(): Boolean = lastTarget?.let { connectLan(it) } ?: false

    private suspend fun readLoop(connected: Socket) {
        val buffer = ByteArray(8192)
        var closeReason = "Flex command channel closed by the radio"
        try {
            while (scope.isActive && !connected.isClosed) {
                val count = try { connected.getInputStream().read(buffer) } catch (_: SocketTimeoutException) { continue }
                if (count < 0) break
                val chunk = buffer.copyOf(count)
                statusFramer.feed(chunk).mapNotNull(::parseFlexProtocolLine).forEach(::handleProtocolLine)
                val nativeGeneration = native.generation()
                val nativeState = native.withHandle { handle ->
                    NativeCore.flexFeed(handle, chunk)
                    NativeCore.flexState(handle)
                } ?: break
                if (!native.isCurrent(nativeGeneration)) break
                snapshot = parseFlexSnapshot(nativeState)
                extended = extendedTracker.snapshot()
                updateTxEligibility()
                updateSelection()
                connectionState = effectiveState()
            }
        } catch (_: CancellationException) { throw CancellationException() }
        catch (error: Exception) { closeReason = "Flex reader stopped: ${error.javaClass.simpleName}${error.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}" }
        finally { if (socket === connected) {
            tx.connectionLost()
            if (!connected.isClosed) runCatching { connected.close() }
            socket = null; connectionState = FlexConnectionState.LOST; lastDisconnectReason = closeReason; detail = closeReason
            scheduleReconnect()
        } }
    }

    private fun scheduleReconnect() {
        if (closed) return
        val target = lastTarget ?: return
        if (!operatorEstablished || reconnect?.isActive == true) return
        reconnect = scope.launch {
            repeat(3) { attempt ->
                delay((attempt + 1) * 1_000L); connectionState = FlexConnectionState.RECONNECTING
                if (connectLan(target, operatorInitiated = false)) return@launch
            }
            connectionState = FlexConnectionState.LOST; detail = "Reconnect stopped after 3 bounded attempts"
        }
    }

    private fun effectiveState(): FlexConnectionState = when {
        snapshot.handle == 0L -> FlexConnectionState.WAITING_HANDLE
        snapshot.clients.none { it.gui && it.connected } -> FlexConnectionState.NO_STATION
        snapshot.selected(selectedSliceIndex) == null -> FlexConnectionState.NO_SLICE
        else -> FlexConnectionState.CONNECTED
    }

    private fun updateSelection() {
        if (selectedTxSliceIndex != null && snapshot.slices.none { it.index == selectedTxSliceIndex && it.inUse }) {
            selectedTxSliceIndex = null
            tx.clearGate()
        }
        if (snapshot.selected(selectedSliceIndex) != null) return
        val station = preferredStation()
        val stationHandles = snapshot.clients.filter { it.connected && it.station == station }.map { it.handle }.toSet()
        val compatible = snapshot.slices.filter { it.inUse && !it.tx && (station.isBlank() || it.clientHandle in stationHandles) }
        selectedSliceIndex = when { compatible.size == 1 -> compatible.single().index; else -> null }
    }

    private fun handleProtocolLine(line: FlexProtocolLine) {
        when (line) {
            is FlexProtocolLine.Reply -> pendingReplies.remove(line.sequence)?.invoke(line.code, line.body)
            is FlexProtocolLine.Status -> extendedTracker.apply(line.body)
            else -> Unit
        }
    }

    private fun handleVitaEvent(event: FlexVitaEvent) {
        when (event) {
            is FlexVitaEvent.Spectrum -> spectrum = event.value
            is FlexVitaEvent.Waterfall -> {
                waterfallRows += event.value
                while (waterfallRows.size > 180) waterfallRows.removeAt(0)
            }
            is FlexVitaEvent.Meters -> {
                meterBank.apply(event.values)
                meters = meterBank.snapshot()
            }
            is FlexVitaEvent.FloatAudio, is FlexVitaEvent.OpusAudio -> if (rxAudioEnabled) networkAudio.accept(event)
        }
    }

    private fun updateTxEligibility() {
        val selected = snapshot.slices.firstOrNull { it.index == selectedTxSliceIndex && it.inUse && it.tx }
        tx.updateEligibility(
            FlexTxEligibility(
                connected = snapshot.connected && snapshot.handle != 0L,
                stationCallsign = snapshot.callsign,
                txSliceIndex = selected?.index,
                txFrequencyHz = selected?.frequencyHz ?: 0,
                txMode = selected?.mode.orEmpty(),
                powerWatts = extended.transmit.rfPower,
                txAntenna = extended.transmit.txAntenna,
                interlockReady = extended.transmit.interlockReady,
            )
        )
        tx.observedTransmit(extended.transmit.mox || extended.transmit.tune)
    }

    fun selectStation(station: String) { saveStation(station); selectedSliceIndex = null; updateSelection(); connectionState = effectiveState() }
    fun selectSlice(index: Int) { selectedSliceIndex = snapshot.slices.firstOrNull { it.index == index && it.inUse && !it.tx }?.index; connectionState = effectiveState() }
    fun requestTxSlice(index: Int, confirmed: Boolean): Boolean {
        if (!confirmed || tx.state !in setOf(FlexTxState.DISABLED, FlexTxState.READY)) return false
        val candidate = snapshot.slices.firstOrNull { it.index == index && it.inUse } ?: return false
        if (candidate.clientHandle != snapshot.handle && !owned.mayRemoveSlice(index)) return false
        val body = FlexCommands.txSlice(index) ?: return false
        if (!sendBody(body)) return false
        selectedTxSliceIndex = index
        tx.clearGate()
        return true
    }

    @Synchronized private fun sendBody(body: String): Boolean = sendBody(body, null)

    @Synchronized private fun sendBody(body: String, reply: ((Long, String) -> Unit)?): Boolean {
        if (body.isBlank()) return false
        val active = socket ?: return false
        val seq = sequence.getAndUpdate { if (it == Int.MAX_VALUE) 1 else it + 1 }
        reply?.let { pendingReplies[seq] = it }
        return runCatching {
            active.getOutputStream().write("C$seq|$body\n".toByteArray(Charsets.US_ASCII))
            active.getOutputStream().flush()
            true
        }.getOrElse { error ->
            pendingReplies.remove(seq)
            detail = "Flex command send failed: ${error.javaClass.simpleName}${error.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            false
        }
    }

    override suspend fun tune(request: ReceiveTuneRequest): Boolean {
        val slice = snapshot.selected(selectedSliceIndex) ?: return false
        val frequency = NativeCore.flexFrequency(slice.index, request.frequencyHz)
        if (!sendBody(frequency)) return false
        val mode = request.mode?.takeIf(String::isNotBlank) ?: return true
        return sendBody(NativeCore.flexMode(slice.index, mode))
    }

    suspend fun setFilter(lowHz: Int, highHz: Int): Boolean = snapshot.selected(selectedSliceIndex)?.let {
        sendBody(NativeCore.flexFilter(it.letter, lowHz, highHz))
    } ?: false

    fun attachAudioRoutes(routes: AudioMonitorController) {
        audioRoutes = routes
    }

    fun attachVoiceMacroStore(store: VoiceMacroStore) {
        voiceMacroStore = store
    }

    fun setDigitalRxSink(sink: ((samples: FloatArray, sampleRate: Int, channels: Int) -> Unit)?) {
        networkAudio.pcmObserver = sink
    }

    fun chooseDisplayMode(mode: FlexDisplayMode) {
        if (mode == displayMode) return
        if (tx.state !in setOf(FlexTxState.DISABLED, FlexTxState.READY)) return
        displayMode = mode
        if (mode == FlexDisplayMode.ATTACH) removeOwnedDisplay()
    }

    suspend fun createShackCQDisplay(centerHz: Long): Boolean = withContext(Dispatchers.IO) {
        if (displayMode != FlexDisplayMode.SHACKCQ_CLIENT || !snapshot.hasCommandChannel) {
            detail = "A live Flex command channel is required to create a panafall"
            return@withContext false
        }
        if (extended.pans.count { it.clientHandle == snapshot.handle } >= extended.capabilities.maxPanadapters) {
            detail = "This FlexRadio has no free panadapter capacity"
            return@withContext false
        }
        detail = "Requesting a ShackCQ panafall from the FlexRadio"
        val create = FlexCommands.createPanafall(centerHz) ?: return@withContext false
        val sent = sendBody(create) { code, body ->
            if (code != 0L) {
                detail = FlexCommands.responseFailure(code)
                return@sendBody
            }
            val ids = extractFlexIds(body)
            val pan = flexFields(body)["pan"]?.let(::parseFlexNumber) ?: ids.firstOrNull() ?: return@sendBody
            val waterfall = flexFields(body)["waterfall"]?.let(::parseFlexNumber) ?: ids.getOrNull(1)
            owned.ownPan(pan)
            waterfall?.let(owned::ownWaterfall)
            detail = "Flex panafall created"
            FlexCommands.configurePan(pan, 1024, 700, 20, -130, -30).forEach(::sendBody)
            FlexCommands.createSlice(pan, centerHz)?.let { create ->
                sendBody(create) { sliceCode, sliceBody ->
                    if (sliceCode == 0L) extractFlexIds(sliceBody).firstOrNull()?.toInt()?.let(owned::ownSlice)
                }
            }
        }
        if (!sent && detail == "Requesting a ShackCQ panafall from the FlexRadio") detail = "Flex command channel could not send the panafall request"
        sent
    }

    fun removeOwnedDisplay() {
        extended.streams.map { it.id }.filter(owned::mayRemoveStream).forEach { FlexCommands.removeStream(it, owned)?.let(::sendBody) }
        extended.waterfalls.map { it.id }.filter(owned::mayRemoveWaterfall).forEach { FlexCommands.removeWaterfall(it, owned)?.let(::sendBody) }
        snapshot.slices.map { it.index }.filter(owned::mayRemoveSlice).forEach { FlexCommands.removeSlice(it, owned)?.let(::sendBody) }
        extended.pans.map { it.id }.filter(owned::mayRemovePan).forEach { FlexCommands.removePan(it, owned)?.let(::sendBody) }
    }

    fun enableRxAudio(): Boolean {
        if (rxAudioEnabled || snapshot.handle == 0L) return rxAudioEnabled
        val routes = audioRoutes
        if (routes != null && !routes.acquireAudio(AudioOwners.FLEX_RX_AUDIO, pauseMonitor = true)) {
            detail = "Another audio operation owns the playback route"
            return false
        }
        networkAudio.start()
        val command = FlexCommands.createRxAudio("opus") ?: return false
        val sent = sendBody(command) { code, body ->
            if (code != 0L) {
                disableRxAudio()
                detail = "Flex RX audio create failed: 0x${code.toString(16)}"
                return@sendBody
            }
            extractFlexIds(body).firstOrNull()?.let { id ->
                rxAudioStreamId = id
                owned.ownStream(id)
                streamSession.register(id, FlexStreamKind.OPUS_AUDIO)
            }
        }
        if (!sent) {
            networkAudio.close()
            routes?.releaseAudio(AudioOwners.FLEX_RX_AUDIO)
            return false
        }
        rxAudioEnabled = true
        return true
    }

    fun disableRxAudio() {
        if (rxAudioStreamId != 0L) FlexCommands.removeStream(rxAudioStreamId, owned)?.let(::sendBody)
        rxAudioStreamId = 0
        rxAudioEnabled = false
        networkAudio.close()
        audioRoutes?.releaseAudio(AudioOwners.FLEX_RX_AUDIO)
    }

    fun setSliceAudio(gain: Int, pan: Int, muted: Boolean): Boolean {
        val slice = snapshot.selected(selectedSliceIndex) ?: return false
        return FlexCommands.audio(slice.index, gain, pan, muted).all(::sendBody)
    }

    fun setAgc(mode: String, threshold: Int): Boolean {
        val slice = snapshot.selected(selectedSliceIndex) ?: return false
        return FlexCommands.agc(slice.index, mode, threshold).all(::sendBody)
    }

    fun setRit(enabled: Boolean, offsetHz: Int): Boolean = snapshot.selected(selectedSliceIndex)
        ?.let { FlexCommands.rit(it.index, enabled, offsetHz) }?.let(::sendBody) ?: false

    fun setXit(enabled: Boolean, offsetHz: Int): Boolean = snapshot.selected(selectedSliceIndex)
        ?.let { FlexCommands.xit(it.index, enabled, offsetHz) }?.let(::sendBody) ?: false

    fun setRxAntenna(antenna: String): Boolean = snapshot.selected(selectedSliceIndex)
        ?.let { FlexCommands.rxAntenna(it.index, antenna) }?.let(::sendBody) ?: false

    fun setSliceLock(locked: Boolean): Boolean = snapshot.selected(selectedSliceIndex)
        ?.let { FlexCommands.lock(it.index, locked) }?.let(::sendBody) ?: false

    fun loadProfile(kind: String, name: String): Boolean = FlexCommands.loadProfile(kind, name)?.let(::sendBody) ?: false

    fun enableTransmitForSession(acknowledgement: String): Boolean = tx.enableForSession(acknowledgement)
    fun armTransmit(): Boolean = tx.arm()
    suspend fun startMox(): Boolean = tx.startMox()
    fun startMicrophoneTx(): Boolean {
        if (tx.state != FlexTxState.ARMED || !tx.eligibility.ready) return false
        disableRxAudio()
        val routes = audioRoutes
        if (routes != null && !routes.acquireAudio(AudioOwners.FLEX_MIC_TX, pauseMonitor = true)) {
            detail = "Another audio operation owns the microphone route"
            return false
        }
        return sendBody(FlexCommands.createTxAudio()) { code, body ->
            val id = extractFlexIds(body).firstOrNull()
            if (code != 0L || id == null) {
                routes?.releaseAudio(AudioOwners.FLEX_MIC_TX)
                detail = "Flex remote microphone stream creation failed"
                return@sendBody
            }
            remoteTxStreamId = id
            owned.ownStream(id)
            scope.launch {
                if (!tx.startMox() || !micTx.start(id)) {
                    detail = micTx.error ?: "Flex microphone TX could not start"
                    tx.stop("microphone start failure")
                }
            }
        }
    }
    fun startVoiceMacroTx(slot: Int): Boolean {
        if (tx.state != FlexTxState.ARMED || !tx.eligibility.ready) return false
        val pcm = runCatching { voiceMacroStore?.read(slot) }.getOrNull() ?: return false
        disableRxAudio()
        val routes = audioRoutes
        if (routes != null && !routes.acquireAudio(AudioOwners.FLEX_VOICE_TX, pauseMonitor = true)) {
            detail = "Another audio operation owns the voice-macro route"
            return false
        }
        return sendBody(FlexCommands.createTxAudio()) { code, body ->
            val id = extractFlexIds(body).firstOrNull()
            if (code != 0L || id == null) {
                routes?.releaseAudio(AudioOwners.FLEX_VOICE_TX)
                detail = "Flex remote voice stream creation failed"
                return@sendBody
            }
            remoteTxStreamId = id
            owned.ownStream(id)
            scope.launch {
                if (!tx.startMox() || !micTx.startVoiceMacro(id, pcm)) {
                    detail = micTx.error ?: "Flex voice macro could not start"
                    tx.stop("voice macro start failure")
                    return@launch
                }
                delay(pcm.durationMillis + 500L)
                stopTransmit("voice macro complete")
            }
        }
    }
    fun startDigitalTx(pcm: CanonicalVoicePcm): Boolean {
        if (tx.state != FlexTxState.ARMED || !tx.eligibility.ready || pcm.samples.isEmpty()) return false
        disableRxAudio()
        val routes = audioRoutes
        if (routes != null && !routes.acquireAudio(AudioOwners.FLEX_DIGI_TX, pauseMonitor = true)) {
            detail = "Another audio operation owns the digital transmit route"
            return false
        }
        return sendBody(FlexCommands.createTxAudio()) { code, body ->
            val id = extractFlexIds(body).firstOrNull()
            if (code != 0L || id == null) {
                routes?.releaseAudio(AudioOwners.FLEX_DIGI_TX)
                detail = "Flex digital transmit stream creation failed"
                return@sendBody
            }
            remoteTxStreamId = id
            owned.ownStream(id)
            scope.launch {
                if (!tx.startMox() || !micTx.startVoiceMacro(id, pcm)) {
                    detail = micTx.error ?: "Flex digital transmit could not start"
                    tx.stop("digital transmit start failure")
                }
            }
        }
    }
    suspend fun startTune(): Boolean {
        disableRxAudio()
        val routes = audioRoutes
        if (routes != null && !routes.acquireAudio(AudioOwners.FLEX_TUNE, pauseMonitor = true)) return false
        return tx.startTune().also { if (!it) routes?.releaseAudio(AudioOwners.FLEX_TUNE) }
    }
    suspend fun sendCwx(text: String): Boolean {
        disableRxAudio()
        val routes = audioRoutes
        if (routes != null && !routes.acquireAudio(AudioOwners.FLEX_CW_TX, pauseMonitor = true)) return false
        return tx.sendCwx(text).also { if (!it) routes?.releaseAudio(AudioOwners.FLEX_CW_TX) }
    }
    suspend fun stopTransmit(reason: String = "operator") {
        releaseFlexTxAudio()
        tx.stop(reason)
    }
    suspend fun onForegroundChanged(foreground: Boolean) {
        if (foreground) return
        if (tx.state != FlexTxState.DISABLED) tx.stop("app background")
        tx.clearGate()
    }
    fun streamPacketCount(): Long = streamSession.engine.packetCount
    fun streamSequenceGaps(): Long = streamSession.engine.sequenceGaps
    fun availableVoiceMacros(): List<VoiceMacroSlot> = voiceMacroStore?.slots.orEmpty()

    private fun releaseFlexTxAudio() {
        micTx.close()
        if (remoteTxStreamId != 0L) FlexCommands.removeStream(remoteTxStreamId, owned)?.let(::sendBody)
        remoteTxStreamId = 0
        audioRoutes?.releaseAudio(AudioOwners.FLEX_MIC_TX)
        audioRoutes?.releaseAudio(AudioOwners.FLEX_VOICE_TX)
        audioRoutes?.releaseAudio(AudioOwners.FLEX_CW_TX)
        audioRoutes?.releaseAudio(AudioOwners.FLEX_TUNE)
        audioRoutes?.releaseAudio(AudioOwners.FLEX_DIGI_TX)
    }

    private fun extractFlexIds(body: String): List<Long> = Regex("0x[0-9A-Fa-f]+|(?<![A-Za-z0-9])\\d+")
        .findAll(body).mapNotNull { parseFlexNumber(it.value) }.filter { it != 0L }.toList()

    private suspend fun disconnectSession() {
        if (tx.state !in setOf(FlexTxState.DISABLED, FlexTxState.READY)) tx.stop("disconnect")
        tx.clearGate()
        disableRxAudio()
        streamSession.stop()
        val activeSocket = socket
        socket = null
        keepalive?.cancel(); reader?.cancel(); keepalive = null; reader = null
        pendingReplies.clear()
        runCatching { activeSocket?.close() }
        snapshot = FlexSnapshot(); extended = FlexExtendedSnapshot(); spectrum = null; waterfallRows.clear(); meters = emptyMap()
        selectedSliceIndex = null; selectedTxSliceIndex = null; owned.clear(); extendedTracker.clear()
    }

    override suspend fun disconnect() {
        operatorEstablished = false; reconnect?.cancel(); reconnect = null; disconnectSession()
        connectionState = if (smartLinkConfigured) FlexConnectionState.SIGNED_OUT else FlexConnectionState.OWNER_CONFIG_REQUIRED
        detail = "FlexRadio disconnected"
    }

    override fun close() {
        if (closed) return
        closed = true
        operatorEstablished = false; discovery?.cancel(); smartLinkDiscovery?.cancel(); reconnect?.cancel(); keepalive?.cancel(); reader?.cancel(); broker?.close(); broker = null
        tx.connectionLost(); tx.clearGate(); disableRxAudio(); streamSession.close()
        runCatching { socket?.close() }; socket = null; controllerJob.cancel(); native.close()
    }
}
