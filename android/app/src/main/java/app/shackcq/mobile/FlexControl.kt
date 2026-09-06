package app.shackcq.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class FlexDisplayMode { ATTACH, SHACKCQ_CLIENT }

class FlexOwnedObjects {
    private val pans = mutableSetOf<Long>()
    private val waterfalls = mutableSetOf<Long>()
    private val slices = mutableSetOf<Int>()
    private val streams = mutableSetOf<Long>()

    @Synchronized fun ownPan(id: Long) { if (id != 0L) pans += id }
    @Synchronized fun ownWaterfall(id: Long) { if (id != 0L) waterfalls += id }
    @Synchronized fun ownSlice(id: Int) { if (id >= 0) slices += id }
    @Synchronized fun ownStream(id: Long) { if (id != 0L) streams += id }
    @Synchronized fun mayRemovePan(id: Long) = id in pans
    @Synchronized fun mayRemoveWaterfall(id: Long) = id in waterfalls
    @Synchronized fun mayRemoveSlice(id: Int) = id in slices
    @Synchronized fun mayRemoveStream(id: Long) = id in streams
    @Synchronized fun clear() { pans.clear(); waterfalls.clear(); slices.clear(); streams.clear() }
}

object FlexCommands {
    private fun hexId(value: Long): String? = value.takeIf { it != 0L }?.let { "0x${it.toString(16).uppercase()}" }
    private fun slice(value: Int): Int? = value.takeIf { it >= 0 }
    private fun percent(value: Int): Int = value.coerceIn(0, 100)
    private fun quoted(value: String): String? = value.takeIf { it.isNotBlank() && it.length <= 64 && '\n' !in it && '\r' !in it }
        ?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.let { "\"$it\"" }

    fun sessionIdentity(guiClientId: UUID): List<String> = listOf(
        "name ShackCQ",
        "client program ShackCQ",
        "client gui $guiClientId",
        "client station ShackCQ",
    )

    fun responseFailure(code: Long): String = when (code) {
        0x50000009L -> "No foundation receiver is available for a new panadapter"
        else -> "Flex command failed: 0x${code.toString(16)}"
    }

    fun subscriptions(): List<String> = listOf(
        "sub radio all", "sub client all", "sub slice all", "sub pan all", "sub waterfall all",
        "sub meter all", "sub stream all", "sub interlock all", "sub transmit all", "sub atu all",
        "sub cwx all", "sub profile all",
    )
    fun createPanafall(centerHz: Long): String? = centerHz.takeIf { it in 100_000..77_000_000_000 }
        ?.let { "display pan create freq=${"%.6f".format(java.util.Locale.US, it / 1_000_000.0)} x=1024 y=700" }
    fun configurePan(panId: Long, width: Int, height: Int, fps: Int, minDbm: Int, maxDbm: Int): List<String> =
        hexId(panId)?.let { pan ->
            listOf(
                "display pan set $pan xpixels=${width.coerceIn(320, 2048)} ypixels=${height.coerceIn(160, 1200)}",
                "display pan set $pan min_dbm=${minDbm.coerceIn(-180, -20)} max_dbm=${maxDbm.coerceIn(-160, 20)}",
                "display pan set $pan fps=${fps.coerceIn(5, 30)}",
            )
        }.orEmpty()
    fun createSlice(panId: Long, frequencyHz: Long): String? = hexId(panId)?.takeIf { frequencyHz in 100_000..77_000_000_000 }
        ?.let { "slice create pan=$it freq=${"%.6f".format(java.util.Locale.US, frequencyHz / 1_000_000.0)}" }
    fun removeSlice(index: Int, owned: FlexOwnedObjects): String? = slice(index)?.takeIf(owned::mayRemoveSlice)?.let { "slice remove $it" }
    fun removePan(id: Long, owned: FlexOwnedObjects): String? = id.takeIf(owned::mayRemovePan)?.let { "display pan remove ${hexId(it)}" }
    fun removeWaterfall(id: Long, owned: FlexOwnedObjects): String? = id.takeIf(owned::mayRemoveWaterfall)?.let { "display waterfall remove ${hexId(it)}" }
    fun removeStream(id: Long, owned: FlexOwnedObjects): String? = id.takeIf(owned::mayRemoveStream)?.let { "stream remove ${hexId(it)}" }

    fun frequency(slice: Int, hz: Long): String? = slice(slice)?.takeIf { hz in 100_000..77_000_000_000 }
        ?.let { "slice tune $it ${"%.6f".format(java.util.Locale.US, hz / 1_000_000.0)}" }
    fun mode(slice: Int, mode: String): String? = slice(slice)?.let { index ->
        mode.uppercase().takeIf { it in setOf("LSB", "USB", "DSB", "CW", "CWL", "CWU", "FM", "NFM", "AM", "SAM", "DIGL", "DIGU", "RTTY") }
            ?.let { "slice set $index mode=$it" }
    }
    fun filter(letter: String, low: Int, high: Int): String? =
        letter.takeIf { it.length == 1 && it[0].isUpperCase() && low < high && low in -12_000..12_000 && high in -12_000..12_000 }
            ?.let { "filt $it $low $high" }
    fun audio(slice: Int, gain: Int, pan: Int, muted: Boolean): List<String> = slice(slice)?.let {
        listOf("audio client 0 slice $it gain ${percent(gain)}", "audio client 0 slice $it pan ${pan.coerceIn(0, 100)}", "audio client 0 slice $it mute ${if (muted) 1 else 0}")
    }.orEmpty()
    fun agc(slice: Int, mode: String, threshold: Int): List<String> = slice(slice)?.let { index ->
        val normalized = mode.uppercase().takeIf { it in setOf("OFF", "SLOW", "MED", "FAST") } ?: return emptyList()
        listOf("slice set $index agc_mode=$normalized", "slice set $index agc_threshold=${threshold.coerceIn(0, 100)}")
    }.orEmpty()
    fun rit(slice: Int, enabled: Boolean, offsetHz: Int): String? = slice(slice)?.let {
        "slice set $it rit_on=${if (enabled) 1 else 0} rit_freq=${offsetHz.coerceIn(-99_999, 99_999)}"
    }
    fun xit(slice: Int, enabled: Boolean, offsetHz: Int): String? = slice(slice)?.let {
        "slice set $it xit_on=${if (enabled) 1 else 0} xit_freq=${offsetHz.coerceIn(-99_999, 99_999)}"
    }
    fun rxAntenna(slice: Int, antenna: String): String? = slice(slice)?.let { index ->
        antenna.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,16}")) }?.let { "slice set $index rxant=$it" }
    }
    fun txSlice(slice: Int): String? = slice(slice)?.let { "slice set $it tx=1" }
    fun lock(slice: Int, locked: Boolean): String? = slice(slice)?.let { "slice set $it lock=${if (locked) 1 else 0}" }
    fun rfPower(watts: Int) = "transmit set rfpower=${watts.coerceIn(0, 100)}"
    fun tunePower(watts: Int) = "transmit set tunepower=${watts.coerceIn(0, 100)}"
    fun micLevel(value: Int) = "transmit set mic_level=${percent(value)}"
    fun processor(enabled: Boolean, level: String): String? =
        level.uppercase().takeIf { it in setOf("NOR", "DX", "DX+") }?.let { "transmit set speech_processor_enable=${if (enabled) 1 else 0} speech_processor_level=$it" }
    fun txFilter(low: Int, high: Int): String? = (low in 0..10_000 && high in 0..10_000 && low < high).takeIf { it }
        ?.let { "transmit set filter_low=$low filter_high=$high" }
    fun vox(enabled: Boolean, level: Int, delayMs: Int) =
        "transmit set vox_enable=${if (enabled) 1 else 0} vox_level=${percent(level)} vox_delay=${delayMs.coerceIn(0, 2000)}"
    fun monitor(enabled: Boolean, level: Int) = "transmit set mon_available=1 mon=${if (enabled) 1 else 0} mon_gain_cw=${percent(level)}"
    fun loadProfile(kind: String, name: String): String? {
        val profile = kind.lowercase().takeIf { it in setOf("global", "tx", "mic") } ?: return null
        return quoted(name)?.let { "profile $profile load $it" }
    }
    fun requestProfiles() = listOf("profile global info", "profile tx info", "profile mic info")
    fun createRxAudio(compression: String = "opus"): String? =
        compression.lowercase().takeIf { it in setOf("opus", "none") }?.let { "stream create type=remote_audio_rx compression=$it" }
    fun createTxAudio() = "stream create type=remote_audio_tx compression=opus"
    fun mox(enabled: Boolean) = "xmit ${if (enabled) 1 else 0}"
    fun tune(enabled: Boolean) = "transmit tune ${if (enabled) 1 else 0}"
    fun cwx(text: String): String? = text.takeIf { it.isNotBlank() && it.length <= 128 && '\n' !in it && '\r' !in it }
        ?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.let { "cwx send \"$it\"" }
    fun cwxClear() = "cwx clear"
    fun atuStart() = "atu start"
}

enum class FlexTxState { DISABLED, READY, ARMED, KEYING, TRANSMITTING, TUNING, STOPPING, FAULT }

data class FlexTxEligibility(
    val connected: Boolean = false,
    val stationCallsign: String = "",
    val txSliceIndex: Int? = null,
    val txFrequencyHz: Long = 0,
    val txMode: String = "",
    val powerWatts: Int = 0,
    val txAntenna: String = "",
    val interlockReady: Boolean = false,
) {
    val ready get() = connected && stationCallsign.isNotBlank() && txSliceIndex != null &&
        txFrequencyHz in 100_000..77_000_000_000 && txMode.isNotBlank() && txAntenna.isNotBlank() && interlockReady
}

class FlexTxController(
    private val scope: CoroutineScope,
    private val command: (String) -> Boolean,
    private val releaseAudio: () -> Unit,
) {
    private val mutex = Mutex()
    private var watchdog: Job? = null
    private var pendingTune = false
    var eligibility = FlexTxEligibility()
        private set
    var state = FlexTxState.DISABLED
        private set
    var rxUnconfirmed = false
        private set
    var fault: String? = null
        private set

    fun updateEligibility(value: FlexTxEligibility) {
        eligibility = value
        if (!value.ready && state in setOf(FlexTxState.READY, FlexTxState.ARMED)) clearGate()
    }

    fun enableForSession(acknowledgement: String): Boolean {
        if (acknowledgement != "ENABLE FLEX TRANSMIT FOR THIS SESSION" || !eligibility.ready || state != FlexTxState.DISABLED) return false
        state = FlexTxState.READY
        fault = null
        return true
    }

    fun arm(): Boolean {
        if (state != FlexTxState.READY || !eligibility.ready) return false
        state = FlexTxState.ARMED
        return true
    }

    suspend fun startMox(maxDurationMs: Long = 180_000): Boolean = mutex.withLock {
        if (state != FlexTxState.ARMED || !eligibility.ready) return@withLock false
        pendingTune = false
        state = FlexTxState.KEYING
        if (!command(FlexCommands.mox(true))) {
            fail("MOX command failed")
            return@withLock false
        }
        startWatchdog(maxDurationMs)
        true
    }

    suspend fun startTune(maxDurationMs: Long = 15_000): Boolean = mutex.withLock {
        if (state != FlexTxState.ARMED || !eligibility.ready) return@withLock false
        pendingTune = true
        state = FlexTxState.KEYING
        if (!command(FlexCommands.tune(true))) {
            fail("TUNE command failed")
            return@withLock false
        }
        startWatchdog(maxDurationMs.coerceAtMost(30_000))
        true
    }

    suspend fun sendCwx(text: String, maxDurationMs: Long = 180_000): Boolean = mutex.withLock {
        val cwx = FlexCommands.cwx(text) ?: return@withLock false
        if (state != FlexTxState.ARMED || !eligibility.ready) return@withLock false
        pendingTune = false
        state = FlexTxState.KEYING
        if (!command(cwx)) {
            fail("CWX command failed")
            return@withLock false
        }
        startWatchdog(maxDurationMs)
        true
    }

    suspend fun stop(reason: String = "operator") = mutex.withLock {
        stopLocked(reason)
    }

    private fun startWatchdog(durationMs: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(durationMs.coerceIn(1_000, 180_000))
            mutex.withLock { stopLocked("watchdog") }
        }
    }

    private fun stopLocked(reason: String) {
        watchdog?.cancel()
        watchdog = null
        val wasActive = state in setOf(FlexTxState.KEYING, FlexTxState.TRANSMITTING, FlexTxState.TUNING, FlexTxState.STOPPING)
        if (wasActive) state = FlexTxState.STOPPING
        releaseAudio()
        command(FlexCommands.cwxClear())
        command(FlexCommands.tune(false))
        val requestedRx = command(FlexCommands.mox(false))
        pendingTune = false
        rxUnconfirmed = wasActive
        if (!wasActive) state = if (eligibility.ready) FlexTxState.READY else FlexTxState.DISABLED
        if (wasActive && !requestedRx) fault = "RX UNCONFIRMED ($reason)"
    }

    fun observedTransmit(transmitting: Boolean) {
        if (transmitting && state == FlexTxState.KEYING) state = if (pendingTune) FlexTxState.TUNING else FlexTxState.TRANSMITTING
        if (!transmitting && state in setOf(FlexTxState.TRANSMITTING, FlexTxState.TUNING, FlexTxState.STOPPING)) {
            watchdog?.cancel()
            watchdog = null
            state = if (eligibility.ready) FlexTxState.READY else FlexTxState.DISABLED
            rxUnconfirmed = false
            pendingTune = false
        }
    }

    fun clearGate() {
        watchdog?.cancel()
        watchdog = null
        releaseAudio()
        pendingTune = false
        state = FlexTxState.DISABLED
    }

    fun connectionLost() {
        val wasArmedOrActive = state !in setOf(FlexTxState.DISABLED, FlexTxState.READY)
        watchdog?.cancel()
        watchdog = null
        releaseAudio()
        pendingTune = false
        rxUnconfirmed = wasArmedOrActive
        fault = if (wasArmedOrActive) "RX UNCONFIRMED (network lost)" else null
        state = FlexTxState.DISABLED
    }

    private fun fail(message: String) {
        releaseAudio()
        command(FlexCommands.tune(false))
        command(FlexCommands.mox(false))
        pendingTune = false
        fault = message
        rxUnconfirmed = true
        state = FlexTxState.FAULT
    }
}
