// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class QmxConnectionController(
    private val serial: QmxSerialPort,
    private val usbIdentity: QmxUsbIdentityPort,
    private val audio: QmxUacAudioPort,
    private val clock: QmxClock,
    private val decoder: QmxProtocolDecoder = QmxProtocolDecoder(),
) : AutoCloseable {
    private val transactions = ReentrantLock()
    private val queue = QmxCommandQueue()
    private val successfulReadbacks = linkedSetOf<QmxReadback>()
    private val unsupportedReadbacks = linkedSetOf<QmxReadback>()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var boundDeviceDigest: String? = null
    @Volatile private var profile: QmxUsbCompositeProfile? = null
    @Volatile private var lastPollNanos = 0L
    @Volatile private var lastFastPollNanos = Long.MIN_VALUE
    @Volatile private var lastMediumPollNanos = Long.MIN_VALUE
    @Volatile private var lastSlowPollNanos = Long.MIN_VALUE
    @Volatile var snapshot = QmxRadioSnapshot()
        private set

    fun attach(composite: QmxUsbCompositeProfile): Boolean = transactions.withLock {
        if (closed) return false
        val liveIdentity = usbIdentity.currentIdentity() ?: return false
        if (liveIdentity.stableDeviceDigest != composite.stableDeviceDigest) return false
        if (boundDeviceDigest != null && boundDeviceDigest != composite.stableDeviceDigest) return false
        boundDeviceDigest = composite.stableDeviceDigest
        profile = composite
        generation++
        successfulReadbacks.clear()
        unsupportedReadbacks.clear()
        queue.clear()
        decoder.reset()
        snapshot = QmxRadioSnapshot(
            generation = generation,
            connected = true,
            model = composite.model,
            menuTerminalAvailable = if (composite.cdcInterfaceCount > 1) QmxTriState.TRUE else QmxTriState.FALSE,
        )
        startupHandshake(generation)
        refreshCapabilities()
        refreshReadiness()
        snapshot.connected
    }

    private fun startupHandshake(expectedGeneration: Long) {
        execute(QmxCommandBuilder.query("VN", QmxReadback.VN), expectedGeneration)
        execute(QmxCommandBuilder.query("ID", QmxReadback.ID), expectedGeneration)
        iqAndVoxHandshake(expectedGeneration)
        listOf(
            QmxCommandBuilder.query("FA", QmxReadback.FA), QmxCommandBuilder.query("FB", QmxReadback.FB),
            QmxCommandBuilder.query("MD", QmxReadback.MD), QmxCommandBuilder.query("FW", QmxReadback.FW),
            QmxCommandBuilder.query("IF", QmxReadback.IF_STATE), QmxCommandBuilder.query("AG", QmxReadback.AG),
            QmxCommandBuilder.query("RG", QmxReadback.RG), QmxCommandBuilder.query("RT", QmxReadback.RIT),
            QmxCommandBuilder.query("SP", QmxReadback.SPLIT), QmxCommandBuilder.query("FR", QmxReadback.SPLIT),
            QmxCommandBuilder.query("FT", QmxReadback.SPLIT), QmxCommandBuilder.query("SM", QmxReadback.SM),
            QmxCommandBuilder.query("PC", QmxReadback.PC), QmxCommandBuilder.query("SW", QmxReadback.SW),
            QmxCommandBuilder.query("TQ", QmxReadback.TX_STATE),
            QmxCommandBuilder.menuQuery("CW|CW offset", QmxReadback.CW_OFFSET),
            QmxCommandBuilder.menuQuery("System config|GPS & Ser. ports|GPS source", QmxReadback.GPS_SOURCE),
        ).forEach { execute(it, expectedGeneration) }
    }

    private fun iqAndVoxHandshake(expectedGeneration: Long) {
        for (attempt in 0 until 4) {
            if (expectedGeneration != generation || closed) return
            execute(QmxCommand("Q9 1;", QmxCommandClass.SAFE_CONTROL, "iq-handshake"), expectedGeneration)
            clock.sleepUntilMonotonic(clock.monotonicNanos() + 150_000_000L)
            val response = execute(QmxCommandBuilder.query("Q9", QmxReadback.Q9), expectedGeneration)
            if (response is QmxResponse.IqMode && response.enabled) break
            if (attempt < 3) clock.sleepUntilMonotonic(clock.monotonicNanos() + 300_000_000L)
        }
        execute(QmxCommand("Q3 0;", QmxCommandClass.SAFE_CONTROL, "vox-safety"), expectedGeneration)
        clock.sleepUntilMonotonic(clock.monotonicNanos() + 150_000_000L)
        execute(QmxCommandBuilder.query("Q3", QmxReadback.Q3), expectedGeneration)
    }

    fun submit(action: QmxRadioAction): Boolean {
        if (closed || !snapshot.connected) return false
        val commands = when (action) {
            is QmxRadioAction.SetFrequency -> listOf(QmxCommandBuilder.frequency(action.hertz)) + QmxCommandBuilder.rit(0)
            is QmxRadioAction.SetMode -> listOf(QmxCommandBuilder.mode(action.mode))
            is QmxRadioAction.SetFilter -> listOf(QmxCommandBuilder.filter(action.hertz))
            is QmxRadioAction.SetAfGain -> listOf(QmxCommandBuilder.afGain(action.quarterDbSteps))
            is QmxRadioAction.SetRfGain -> listOf(QmxCommandBuilder.rfGain(action.decibels))
            is QmxRadioAction.SetRit -> QmxCommandBuilder.rit(action.hertz)
            QmxRadioAction.ClearRit -> QmxCommandBuilder.rit(0)
            is QmxRadioAction.SetSplit -> listOf(QmxCommandBuilder.split(action.enabled))
            QmxRadioAction.RequestEmergencyReceive,
            QmxRadioAction.RequestSWRProtectionTuneConfirmation,
            QmxRadioAction.RequestTransmitConfirmation -> return false
        }
        return queue.enqueue(commands)
    }

    fun enqueuePolls(nowNanos: Long = clock.monotonicNanos()) {
        if (closed || !snapshot.connected) return
        if (elapsed(nowNanos, lastFastPollNanos, 200_000_000L)) {
            enqueueSupported(QmxReadback.FA, "FA")
            enqueueSupported(QmxReadback.MD, "MD")
            enqueueSupported(QmxReadback.TX_STATE, "TQ")
            lastFastPollNanos = nowNanos
        }
        if (elapsed(nowNanos, lastMediumPollNanos, 1_000_000_000L)) {
            listOf(
                QmxReadback.FW to "FW", QmxReadback.AG to "AG", QmxReadback.RG to "RG",
                QmxReadback.RIT to "RT", QmxReadback.SPLIT to "SP", QmxReadback.SM to "SM",
                QmxReadback.PC to "PC", QmxReadback.SW to "SW",
            ).forEach { (readback, code) -> enqueueSupported(readback, code) }
            lastMediumPollNanos = nowNanos
        }
        if (elapsed(nowNanos, lastSlowPollNanos, 10_000_000_000L)) {
            enqueueSupported(QmxReadback.VN, "VN")
            if (QmxReadback.CW_OFFSET in successfulReadbacks) queue.enqueue(QmxCommandBuilder.menuQuery("CW|CW offset", QmxReadback.CW_OFFSET))
            if (QmxReadback.GPS_SOURCE in successfulReadbacks) queue.enqueue(QmxCommandBuilder.menuQuery("System config|GPS & Ser. ports|GPS source", QmxReadback.GPS_SOURCE))
            lastSlowPollNanos = nowNanos
        }
    }

    fun drain(maximumCommands: Int = 16): Int = transactions.withLock {
        if (closed || !snapshot.connected) return 0
        val expectedGeneration = generation
        var count = 0
        while (count < maximumCommands) {
            val command = queue.next() ?: break
            execute(command, expectedGeneration)
            if (expectedGeneration != generation) break
            count++
        }
        refreshCapabilities()
        refreshReadiness()
        count
    }

    private fun enqueueSupported(readback: QmxReadback, code: String) {
        if (readback in successfulReadbacks) queue.enqueue(QmxCommandBuilder.query(code, readback))
    }

    private fun execute(command: QmxCommand, expectedGeneration: Long): QmxResponse? {
        if (closed || expectedGeneration != generation) return null
        val attempts = if (command.mayRetry) 2 else 1
        repeat(attempts) { attempt ->
            val raw = runCatching { serial.exchange(command.text, command.timeoutMillis) }.getOrElse { error ->
                if (attempt + 1 == attempts) setError(error.message ?: "CAT transaction failed")
                return@repeat
            }
            if (closed || expectedGeneration != generation) return null
            if (raw.isBlank()) {
                if (command.commandClass != QmxCommandClass.QUERY) return null
                if (attempt + 1 == attempts) setError("CAT query timed out")
                return@repeat
            }
            // QMX asynchronously echoes writes. Only a response to an explicit query
            // is evidence; accepting a Q9 write echo would falsely claim IQ readiness.
            if (command.commandClass != QmxCommandClass.QUERY) return null
            val response = decoder.feed(raw.toByteArray(Charsets.US_ASCII)).lastOrNull()
            if (response is QmxResponse.Unsupported) {
                command.expectedReadback?.let { unsupportedReadbacks += it; successfulReadbacks -= it }
                return response
            }
            if (response is QmxResponse.Malformed) {
                setError(response.reason)
                return response
            }
            if (response != null && response !is QmxResponse.Unrecognised) {
                command.expectedReadback?.let { successfulReadbacks += it; unsupportedReadbacks -= it }
                apply(response, command.expectedReadback)
                lastPollNanos = clock.monotonicNanos()
                return response
            }
        }
        return null
    }

    private fun apply(response: QmxResponse, expectedReadback: QmxReadback?) {
        snapshot = when (response) {
            is QmxResponse.Frequency -> if (response.vfo == QmxVfo.A) snapshot.copy(vfoAHz = response.hertz) else snapshot.copy(vfoBHz = response.hertz)
            is QmxResponse.Mode -> snapshot.copy(mode = response.mode)
            is QmxResponse.Filter -> snapshot.copy(filterHz = response.hertz)
            is QmxResponse.AfGain -> snapshot.copy(afGainDb = response.decibels, afGainNativeQuarterDb = response.quarterDbSteps)
            is QmxResponse.RfGain -> snapshot.copy(rfGainDb = response.decibels)
            is QmxResponse.RitEnabled -> snapshot.copy(ritHz = if (response.enabled) snapshot.ritHz ?: 0 else 0)
            is QmxResponse.Split -> snapshot.copy(split = if (response.enabled) QmxTriState.TRUE else QmxTriState.FALSE)
            is QmxResponse.ReceiveVfo -> snapshot.copy(receiveVfo = response.vfo)
            is QmxResponse.TransmitVfo -> snapshot.copy(transmitVfo = response.vfo)
            is QmxResponse.Firmware -> snapshot.copy(firmware = response.value)
            is QmxResponse.IqMode -> snapshot.copy(iqModeEnabled = if (response.enabled) QmxTriState.TRUE else QmxTriState.FALSE)
            is QmxResponse.Vox -> snapshot.copy(voxDisabled = if (response.enabled) QmxTriState.FALSE else QmxTriState.TRUE)
            is QmxResponse.Power -> snapshot.copy(powerWatts = response.watts)
            is QmxResponse.Swr -> snapshot.copy(swr = response.ratio)
            is QmxResponse.SignalMeter -> snapshot.copy(sMeter = response.value)
            is QmxResponse.TransmitState -> snapshot.copy(txState = response.state)
            is QmxResponse.MenuValue -> when (expectedReadback) {
                QmxReadback.CW_OFFSET -> snapshot.copy(cwOffsetHz = response.value.trim().toIntOrNull()?.takeIf { it in 500..1_000 })
                QmxReadback.GPS_SOURCE -> snapshot.copy(gpsSource = if (response.value.trim().equals("QMX+ Internal", ignoreCase = true)) QmxGpsSource.INTERNAL else QmxGpsSource.NONE)
                else -> snapshot
            }
            is QmxResponse.Malformed -> snapshot.copy(lastSanitizedError = response.reason.take(160))
            is QmxResponse.IfState, is QmxResponse.ModelId, is QmxResponse.Unrecognised, is QmxResponse.Unsupported -> snapshot
        }
    }

    private fun refreshCapabilities() {
        val composite = profile ?: return
        val capabilities = QmxCapabilityResolver.resolve(QmxCapabilityEvidence(
            model = composite.model,
            firmware = snapshot.firmware,
            successfulReadbacks = successfulReadbacks,
            unsupportedReadbacks = unsupportedReadbacks,
            cdcInterfaceCount = composite.cdcInterfaceCount,
        ))
        snapshot = snapshot.copy(capabilities = capabilities)
    }

    private fun refreshReadiness() {
        val route = audio.currentRoute()
        val exactRoute = boundDeviceDigest != null && route.stableDeviceDigest == boundDeviceDigest && route.ready && route.channels == 2
        val age = if (lastPollNanos > 0) ((clock.monotonicNanos() - lastPollNanos) / 1_000_000L).coerceAtLeast(0) else null
        snapshot = snapshot.copy(
            ready = snapshot.connected && snapshot.vfoAHz != null && snapshot.mode != QmxMode.UNKNOWN &&
                snapshot.iqModeEnabled == QmxTriState.TRUE && snapshot.voxDisabled == QmxTriState.TRUE && exactRoute,
            sourceAgeMillis = age,
        )
    }

    fun routeLost(reason: String = "selected QMX USB/UAC route lost") = transactions.withLock {
        if (closed) return
        generation++
        queue.clear()
        decoder.reset()
        profile = null
        lastPollNanos = 0
        snapshot = QmxRadioSnapshot(generation = generation, lastSanitizedError = reason.take(160))
    }

    fun diagnostics(): QmxDiagnostics {
        val composite = profile
        val route = audio.currentRoute()
        return QmxDiagnostics(
            snapshot.model, snapshot.firmware?.toString(), composite?.cdcInterfaceCount ?: 0,
            snapshot.connected, snapshot.iqModeEnabled == QmxTriState.TRUE,
            if (route.ready && route.stableDeviceDigest == boundDeviceDigest) "EXACT_QMX_UAC" else "UNAVAILABLE",
            snapshot.sourceAgeMillis, snapshot.capabilities.digest(), snapshot.powerWatts, snapshot.swr,
            snapshot.lastSanitizedError,
        )
    }

    private fun setError(value: String) { snapshot = snapshot.copy(lastSanitizedError = value.take(160)) }
    private fun elapsed(now: Long, previous: Long, period: Long) = previous == Long.MIN_VALUE || now - previous >= period

    override fun close() = transactions.withLock {
        if (closed) return
        closed = true
        generation++
        queue.clear()
        decoder.reset()
        profile = null
        snapshot = QmxRadioSnapshot(generation = generation)
        serial.close()
    }
}
