// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class TciAcceptanceState {
    UNVERIFIED,
    READ_ONLY_ACCEPTED,
    SAFE_SETTERS_ACCEPTED,
    TX_AUDIO_LOOPBACK_ACCEPTED,
    PTT_ACCEPTED,
    TUNE_ACCEPTED,
    RF_ACCEPTED;

    fun permits(required: TciAcceptanceState): Boolean = ordinal >= required.ordinal
}

enum class TciTxMachineState {
    RX_IDLE, TX_PREPARING, TX_ARMED, TX_ACTIVE, TUNE_ACTIVE, TX_STOPPING,
    RX_RECOVERY, RX_UNCONFIRMED, FAULT,
}

enum class TciTxSource { DIGI, VOICE_MACRO, CW_AUDIO_KEYER, SSTV, CONTEST, DEBUG_BENCH }

enum class TciReadbackTruth { CONFIRMED, PENDING, UNKNOWN, UNAVAILABLE_PROTOCOL, DIALECT_SPECIFIC }

data class TciAcceptanceEvidence(
    val profileId: RadioProfileId,
    val deviceIdentity: String,
    val from: TciAcceptanceState,
    val to: TciAcceptanceState,
    val operatorConfirmed: Boolean,
    val rxConfirmed: Boolean,
    val demoNoRadio: Boolean = false,
    val recordedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    init { require(deviceIdentity.length in 8..512 && recordedAtEpochMillis > 0) }
}

data class TciTxSettings(
    val perModeLevels: Map<String, Float> = emptyMap(),
    val maxDrivePercent: Int = 35,
    val maxTuneDrivePercent: Int = 10,
    val maxTuneDurationMillis: Long = 10_000,
    val swrAbort: Double = 3.0,
    val alcAbort: Double = 0.95,
    val txAudioRate: Int = 48_000,
    val monitorEnabled: Boolean = false,
    val allowSplitChangesDuringTx: Boolean = false,
    val diagnostics: Boolean = false,
) {
    init {
        require(maxDrivePercent in 0..100 && maxTuneDrivePercent in 0..25)
        require(maxTuneDurationMillis in 500..30_000)
        require(swrAbort in 1.1..10.0 && alcAbort in 0.1..1.0)
        require(txAudioRate in setOf(8_000, 12_000, 24_000, 48_000))
        require(perModeLevels.values.all { it.isFinite() && it in 0.0F..2.0F })
    }

    fun levelFor(mode: String): Float = (perModeLevels[mode.uppercase()] ?: 1.0F).coerceIn(0.0F, 2.0F)
}

data class TciTxReadback(
    val connected: Boolean = false,
    val transmitting: Boolean? = null,
    val tuning: Boolean? = null,
    val txEnabled: Boolean? = null,
    val receiver: Int? = null,
    val rxFrequencyHz: Long? = null,
    val txFrequencyHz: Long? = null,
    val txVfo: String? = null,
    val split: Boolean? = null,
    val xitEnabled: Boolean? = null,
    val xitOffsetHz: Long? = null,
    val mode: String? = null,
    val txFilterHz: Int? = null,
    val drivePercent: Int? = null,
    val tuneDrivePercent: Int? = null,
    val forwardPowerWatts: Double? = null,
    val peakPowerWatts: Double? = null,
    val reflectedPowerWatts: Double? = null,
    val swr: Double? = null,
    val alc: Double? = null,
    val txAudioRate: Int? = null,
    val monitorEnabled: Boolean? = null,
    val sourceAgeMillis: Long? = null,
    val sequence: Long = 0,
)

data class TciTxIntent(
    val owner: String,
    val source: TciTxSource,
    val mode: String,
    val mono: FloatArray,
    val sampleRate: Int,
    val receiver: Int,
    val expectedFrequencyHz: Long? = null,
    val drivePercent: Int? = null,
    val foregroundValid: () -> Boolean = { true },
) {
    init {
        require(owner.isNotBlank() && owner.length <= 80)
        require(mode.isNotBlank() && mode.length <= 24)
        require(sampleRate in 8_000..192_000 && receiver in 0..7)
        require(mono.isNotEmpty() && mono.size <= sampleRate * 45)
        require(drivePercent == null || drivePercent in 0..100)
    }
}

data class TciTxSnapshot(
    val state: TciTxMachineState = TciTxMachineState.RX_IDLE,
    val acceptance: TciAcceptanceState = TciAcceptanceState.UNVERIFIED,
    val profileId: String? = null,
    val deviceIdentity: String? = null,
    val owner: String? = null,
    val source: TciTxSource? = null,
    val receiver: Int? = null,
    val rxFrequencyHz: Long? = null,
    val txFrequencyHz: Long? = null,
    val txVfo: String? = null,
    val split: Boolean? = null,
    val xitEnabled: Boolean? = null,
    val xitOffsetHz: Long? = null,
    val mode: String? = null,
    val txFilterHz: Int? = null,
    val drivePercent: Int? = null,
    val tuneDrivePercent: Int? = null,
    val forwardPowerWatts: Double? = null,
    val peakPowerWatts: Double? = null,
    val reflectedPowerWatts: Double? = null,
    val swr: Double? = null,
    val alc: Double? = null,
    val txAudioRate: Int? = null,
    val txAudioReady: Boolean = false,
    val monitorEnabled: Boolean? = null,
    val sourceAgeMillis: Long? = null,
    val readback: TciReadbackTruth = TciReadbackTruth.UNKNOWN,
    val interlock: String? = null,
    val elapsedMillis: Long = 0,
    val watchdogMillis: Long? = null,
    val queueDepth: Int = 0,
    val underruns: Long = 0,
    val overruns: Long = 0,
    val frames: Long = 0,
    val pttLatencyMillis: Long? = null,
    val rxRecoveryLatencyMillis: Long? = null,
    val frameJitterMillis: Double? = null,
    val rms: Double? = null,
    val peak: Double? = null,
    val clippedSamples: Long = 0,
    val demoNoRadio: Boolean = false,
)

interface TciTransmitAdapter {
    val deviceIdentity: String
    val isDemoNoRadio: Boolean get() = false
    fun readback(): TciTxReadback
    fun sendText(command: String): Boolean
    fun sendBinary(frame: ByteArray): Boolean
}

fun interface TciAudioFrameBuilder {
    fun build(mono: FloatArray, sourceRate: Int, targetRate: Int, receiver: Int,
        targetFrameOffset: Long, requestedValues: Int, level: Float): ByteArray
}

fun interface TciCommandBuilder {
    fun build(kind: Int, receiver: Int, channel: Int, number: Long, text: String): String
}

class TciTransmitAuthority(
    private val frameBuilder: TciAudioFrameBuilder = TciAudioFrameBuilder(NativeTci::buildTxAudio),
    private val commandBuilder: TciCommandBuilder = TciCommandBuilder(NativeTci::buildCommand),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val session = Mutex()
    private val chrono = ArrayBlockingQueue<Int>(8)
    private val forcedStop = AtomicBoolean(false)
    private val globalStopGeneration = AtomicLong(0)
    @Volatile private var adapter: TciTransmitAdapter? = null
    @Volatile private var settings = TciTxSettings()
    @Volatile private var storedAcceptance = TciAcceptanceState.UNVERIFIED
    @Volatile private var acceptedIdentity: String? = null
    @Volatile var snapshot = TciTxSnapshot(); private set

    fun attach(profileId: String, profileIdentity: String?, acceptance: TciAcceptanceState,
        acceptanceIdentity: String?, value: TciTransmitAdapter, txSettings: TciTxSettings) {
        val identityMatches = profileIdentity != null && profileIdentity == value.deviceIdentity &&
            acceptanceIdentity == value.deviceIdentity
        adapter = value
        settings = txSettings
        storedAcceptance = if (value.isDemoNoRadio) TciAcceptanceState.RF_ACCEPTED
            else if (identityMatches) acceptance else TciAcceptanceState.UNVERIFIED
        acceptedIdentity = if (identityMatches) acceptanceIdentity else null
        chrono.clear(); forcedStop.set(false)
        snapshot = TciTxSnapshot(
            acceptance = storedAcceptance,
            profileId = profileId,
            deviceIdentity = value.deviceIdentity,
            demoNoRadio = value.isDemoNoRadio,
        )
        publishReadback(value.readback())
    }

    fun detach(reason: String = "TCI transport detached") {
        globalStop(reason)
        adapter = null
        chrono.clear()
    }

    fun onChrono(requestedValues: Int) {
        if (requestedValues <= 0 || requestedValues > 16_384 || requestedValues % 2 != 0) {
            interlock("MALFORMED_TX_CHRONO")
            return
        }
        if (!chrono.offer(requestedValues)) {
            chrono.poll(); chrono.offer(requestedValues)
            snapshot = snapshot.copy(overruns = snapshot.overruns + 1, queueDepth = chrono.size)
        } else snapshot = snapshot.copy(queueDepth = chrono.size)
    }

    fun publishReadback(value: TciTxReadback) {
        snapshot = snapshot.copy(
            rxFrequencyHz = value.rxFrequencyHz,
            txFrequencyHz = value.txFrequencyHz,
            txVfo = value.txVfo,
            split = value.split,
            xitEnabled = value.xitEnabled,
            xitOffsetHz = value.xitOffsetHz,
            mode = value.mode ?: snapshot.mode,
            txFilterHz = value.txFilterHz,
            drivePercent = value.drivePercent,
            tuneDrivePercent = value.tuneDrivePercent,
            forwardPowerWatts = value.forwardPowerWatts,
            peakPowerWatts = value.peakPowerWatts,
            reflectedPowerWatts = value.reflectedPowerWatts,
            swr = value.swr,
            alc = value.alc,
            txAudioRate = value.txAudioRate,
            monitorEnabled = value.monitorEnabled,
            sourceAgeMillis = value.sourceAgeMillis,
            readback = when {
                value.transmitting != null || value.tuning != null -> TciReadbackTruth.CONFIRMED
                value.connected -> TciReadbackTruth.PENDING
                else -> TciReadbackTruth.UNKNOWN
            },
        )
    }

    suspend fun transmit(intent: TciTxIntent): Boolean = withContext(Dispatchers.IO) { session.withLock {
        val tx = adapter ?: return@withLock reject("NO_TCI_TRANSPORT")
        if (snapshot.state == TciTxMachineState.RX_UNCONFIRMED) return@withLock reject("RX_UNCONFIRMED")
        if (!tx.isDemoNoRadio && !storedAcceptance.permits(TciAcceptanceState.PTT_ACCEPTED))
            return@withLock reject("PTT_NOT_ACCEPTED")
        if (acceptedIdentity != null && acceptedIdentity != tx.deviceIdentity) return@withLock reject("DEVICE_IDENTITY_CHANGED")
        if (!intent.foregroundValid()) return@withLock reject("FOREGROUND_OR_CONTEXT_INVALID")
        val startReadback = tx.readback()
        if (!startReadback.connected || startReadback.transmitting != false || startReadback.tuning == true)
            return@withLock reject("RX_SAFE_START_NOT_CONFIRMED")
        if (intent.mono.any { !it.isFinite() }) return@withLock reject("NON_FINITE_TX_AUDIO")

        val level = settings.levelFor(intent.mode)
        val drive = (intent.drivePercent ?: settings.maxDrivePercent).coerceAtMost(settings.maxDrivePercent)
        val start = nowMillis()
        val generation = globalStopGeneration.get()
        forcedStop.set(false); chrono.clear()
        val (rms, peak, clipped) = audioMetrics(intent.mono, level)
        snapshot = snapshot.copy(
            state = TciTxMachineState.TX_PREPARING, owner = intent.owner, source = intent.source,
            receiver = intent.receiver, mode = intent.mode, txAudioRate = settings.txAudioRate,
            txAudioReady = true, drivePercent = drive, interlock = null, elapsedMillis = 0,
            rms = rms, peak = peak, clippedSamples = clipped, watchdogMillis = 45_000,
        )
        if (!send(tx, NativeTci.AUDIO_RATE, intent.receiver, settings.txAudioRate.toLong()) ||
            !send(tx, NativeTci.DRIVE, intent.receiver, drive.toLong()) ||
            !send(tx, NativeTci.TX_SENSORS, intent.receiver, 1) ||
            !send(tx, NativeTci.MONITOR_ENABLE, intent.receiver, if (settings.monitorEnabled) 1 else 0) ||
            !send(tx, NativeTci.AUDIO_START, intent.receiver, 0)) return@withLock failAndRecover(tx, intent.receiver, "TX_PREPARE_WRITE_FAILED")
        snapshot = snapshot.copy(state = TciTxMachineState.TX_ARMED)
        val pttRequested = nowMillis()
        if (!send(tx, NativeTci.TRX, intent.receiver, 1)) return@withLock failAndRecover(tx, intent.receiver, "PTT_WRITE_FAILED")
        if (!awaitTruth(tx, 2_000) { it.transmitting == true && it.tuning != true })
            return@withLock failAndRecover(tx, intent.receiver, "PTT_READBACK_TIMEOUT")
        snapshot = snapshot.copy(state = TciTxMachineState.TX_ACTIVE,
            pttLatencyMillis = nowMillis() - pttRequested)

        val targetFrames = (intent.mono.size.toLong() * settings.txAudioRate + intent.sampleRate - 1) / intent.sampleRate
        var frameOffset = 0L
        var consecutiveUnderruns = 0
        var lastFrameAt = nowMillis()
        var jitterTotal = 0.0
        var frameCount = 0L
        while (frameOffset < targetFrames) {
            if (forcedStop.get() || globalStopGeneration.get() != generation)
                return@withLock recoverRx(tx, intent.receiver, "GLOBAL_STOP")
            if (!intent.foregroundValid()) return@withLock failAndRecover(tx, intent.receiver, "ROUTE_OR_CONTEXT_LOST")
            val readback = tx.readback(); publishReadback(readback)
            interlockReason(readback, intent)?.let { return@withLock failAndRecover(tx, intent.receiver, it) }
            var requested = chrono.poll()
            if (requested == null) {
                repeat(15) { if (requested == null) { pause(10); requested = chrono.poll() } }
            }
            if (requested == null) {
                requested = 2_048
                consecutiveUnderruns++
                snapshot = snapshot.copy(underruns = snapshot.underruns + 1)
                if (consecutiveUnderruns >= 3)
                    return@withLock failAndRecover(tx, intent.receiver, "TX_AUDIO_UNDERRUN")
            } else consecutiveUnderruns = 0
            val values = requested!!
            val frame = frameBuilder.build(intent.mono, intent.sampleRate, settings.txAudioRate,
                intent.receiver, frameOffset, values, level)
            if (frame.isEmpty() || !tx.sendBinary(frame)) return@withLock failAndRecover(tx, intent.receiver, "TX_AUDIO_SEND_FAILED")
            val now = nowMillis(); val expected = values / 2.0 * 1_000.0 / settings.txAudioRate
            jitterTotal += abs((now - lastFrameAt) - expected); lastFrameAt = now
            frameOffset += values / 2; frameCount++
            snapshot = snapshot.copy(frames = snapshot.frames + 1, queueDepth = chrono.size,
                elapsedMillis = now - start, frameJitterMillis = jitterTotal / frameCount)
            if (now - start > 45_000) return@withLock failAndRecover(tx, intent.receiver, "TX_WATCHDOG")
        }
        recoverRx(tx, intent.receiver, null)
    } }

    suspend fun tune(owner: String, receiver: Int): Boolean = withContext(Dispatchers.IO) { session.withLock {
        val tx = adapter ?: return@withLock reject("NO_TCI_TRANSPORT")
        if (snapshot.state == TciTxMachineState.RX_UNCONFIRMED) return@withLock reject("RX_UNCONFIRMED")
        if (!tx.isDemoNoRadio && !storedAcceptance.permits(TciAcceptanceState.TUNE_ACCEPTED))
            return@withLock reject("TUNE_NOT_ACCEPTED")
        val initial = tx.readback()
        if (!initial.connected || initial.transmitting != false || initial.tuning == true)
            return@withLock reject("RX_SAFE_START_NOT_CONFIRMED")
        val generation = globalStopGeneration.get(); forcedStop.set(false)
        snapshot = snapshot.copy(state = TciTxMachineState.TX_PREPARING, owner = owner,
            source = null, receiver = receiver, interlock = null,
            tuneDrivePercent = settings.maxTuneDrivePercent, watchdogMillis = settings.maxTuneDurationMillis)
        if (!send(tx, NativeTci.TUNE_DRIVE, receiver, settings.maxTuneDrivePercent.toLong()) ||
            !send(tx, NativeTci.TX_SENSORS, receiver, 1) || !send(tx, NativeTci.TUNE, receiver, 1))
            return@withLock failAndRecover(tx, receiver, "TUNE_WRITE_FAILED")
        if (!awaitTruth(tx, 2_000) { it.tuning == true })
            return@withLock failAndRecover(tx, receiver, "TUNE_READBACK_TIMEOUT")
        val started = nowMillis(); snapshot = snapshot.copy(state = TciTxMachineState.TUNE_ACTIVE)
        while (nowMillis() - started < settings.maxTuneDurationMillis) {
            if (forcedStop.get() || globalStopGeneration.get() != generation)
                return@withLock recoverRx(tx, receiver, "GLOBAL_STOP")
            val value = tx.readback(); publishReadback(value)
            interlockReason(value, null)?.let { return@withLock failAndRecover(tx, receiver, it) }
            snapshot = snapshot.copy(elapsedMillis = nowMillis() - started)
            pause(25)
        }
        recoverRx(tx, receiver, "TUNE_WATCHDOG")
    } }

    fun globalStop(reason: String = "GLOBAL_STOP"): Boolean {
        forcedStop.set(true); globalStopGeneration.incrementAndGet(); chrono.clear()
        val tx = adapter
        val receiver = snapshot.receiver ?: tx?.readback()?.receiver ?: 0
        val sent = tx?.sendText(command(NativeTci.SAFE_STOP, receiver)) ?: true
        val readback = tx?.readback()
        val rxConfirmed = readback == null || (readback.connected && readback.transmitting == false && readback.tuning != true)
        snapshot = snapshot.copy(state = if (rxConfirmed) TciTxMachineState.RX_IDLE else TciTxMachineState.RX_RECOVERY, interlock = reason,
            txAudioReady = false, queueDepth = 0)
        return sent
    }

    suspend fun requestRxAndRecheck(): Boolean = withContext(Dispatchers.IO) { session.withLock {
        val tx = adapter ?: return@withLock false
        recoverRx(tx, snapshot.receiver ?: tx.readback().receiver ?: 0, null)
    } }

    private suspend fun recoverRx(tx: TciTransmitAdapter, receiver: Int, reason: String?): Boolean {
        snapshot = snapshot.copy(state = TciTxMachineState.TX_STOPPING, interlock = reason, txAudioReady = false)
        tx.sendText(command(NativeTci.AUDIO_STOP, receiver))
        tx.sendText(command(NativeTci.TRX, receiver, number = 0))
        tx.sendText(command(NativeTci.TUNE, receiver, number = 0))
        val requested = nowMillis(); snapshot = snapshot.copy(state = TciTxMachineState.RX_RECOVERY)
        val confirmed = awaitTruth(tx, 2_500) { it.connected && it.transmitting == false && it.tuning != true }
        snapshot = snapshot.copy(
            state = if (confirmed) TciTxMachineState.RX_IDLE else TciTxMachineState.RX_UNCONFIRMED,
            owner = null, source = null, receiver = null,
            readback = if (confirmed) TciReadbackTruth.CONFIRMED else TciReadbackTruth.UNKNOWN,
            rxRecoveryLatencyMillis = nowMillis() - requested,
            interlock = if (confirmed) reason else "RX_UNCONFIRMED${reason?.let { " · $it" } ?: ""}",
        )
        return confirmed && reason == null
    }

    private suspend fun failAndRecover(tx: TciTransmitAdapter, receiver: Int, reason: String): Boolean {
        snapshot = snapshot.copy(state = TciTxMachineState.FAULT, interlock = reason)
        recoverRx(tx, receiver, reason)
        return false
    }

    private suspend fun awaitTruth(tx: TciTransmitAdapter, timeoutMillis: Long,
        predicate: (TciTxReadback) -> Boolean): Boolean {
        val end = nowMillis() + timeoutMillis
        while (nowMillis() <= end) {
            val value = tx.readback(); publishReadback(value)
            if (predicate(value)) return true
            if (!value.connected) return false
            pause(10)
        }
        return false
    }

    private fun interlockReason(value: TciTxReadback, intent: TciTxIntent?): String? = when {
        !value.connected -> "TCI_DISCONNECTED"
        value.transmitting == false && value.tuning != true && snapshot.state == TciTxMachineState.TX_ACTIVE -> "TX_READBACK_DROPPED"
        value.swr != null && value.swr > settings.swrAbort -> "SWR_ABORT"
        value.alc != null && value.alc > settings.alcAbort -> "ALC_ABORT"
        value.reflectedPowerWatts != null && value.forwardPowerWatts != null &&
            value.reflectedPowerWatts > max(1.0, value.forwardPowerWatts * 0.25) -> "REFLECTED_POWER_ABORT"
        intent?.expectedFrequencyHz != null && value.txFrequencyHz != null &&
            abs(value.txFrequencyHz - intent.expectedFrequencyHz) > 1L -> "TX_FREQUENCY_DRIFT"
        intent != null && value.mode != null && !modeEquivalent(value.mode, intent.mode) -> "TX_MODE_DRIFT"
        else -> null
    }

    private fun send(tx: TciTransmitAdapter, kind: Int, receiver: Int, number: Long): Boolean =
        tx.sendText(command(kind, receiver, number = number))

    private fun command(kind: Int, receiver: Int, channel: Int = 0, number: Long = 0, text: String = ""): String =
        commandBuilder.build(kind, receiver, channel, number, text)

    private fun reject(reason: String): Boolean {
        snapshot = snapshot.copy(interlock = reason)
        return false
    }

    private fun interlock(reason: String) {
        snapshot = snapshot.copy(state = TciTxMachineState.FAULT, interlock = reason)
        globalStop(reason)
    }

    private fun modeEquivalent(actual: String, expected: String): Boolean {
        val a = actual.uppercase(); val e = expected.uppercase()
        return a == e || (a == "DIGU" && e in setOf("FT8", "FT4", "FT2", "FST4", "Q65", "JT65", "MSK144", "RTTY", "BPSK31"))
    }

    private fun audioMetrics(samples: FloatArray, level: Float): Triple<Double, Double, Long> {
        var sum = 0.0; var peak = 0.0; var clipped = 0L
        samples.forEach { source ->
            val value = abs(source.toDouble() * level)
            sum += value * value; peak = max(peak, value); if (value >= 0.98) clipped++
        }
        return Triple(sqrt(sum / samples.size), peak.coerceAtMost(0.98), clipped)
    }
}
