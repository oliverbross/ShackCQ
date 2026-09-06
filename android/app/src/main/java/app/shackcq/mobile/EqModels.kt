package app.shackcq.mobile

import java.time.Instant

const val EQ_BAND_COUNT = 8
val EQ_FREQUENCIES_HZ = intArrayOf(50, 100, 200, 400, 800, 1_600, 2_400, 3_200)

enum class EqPath { RX, TX }

enum class EqContext(val label: String, val writable: Boolean = true) {
    RX_VOICE("VOICE"),
    RX_CW("CW"),
    RX_DATA("UNAVAILABLE IN DATA", false),
    TX_SSB("SSB"),
    TX_WIDEBAND("WIDEBAND — ESSB/AM/FM"),
    TX_INACTIVE("NOT ACTIVE IN CW/DATA", false),
    UNKNOWN("UNKNOWN", false),
}

class EqCurve private constructor(private val storage: IntArray) {
    val values: List<Int> get() = storage.toList()
    operator fun get(index: Int): Int = storage[index]
    fun changedBands(other: EqCurve): List<Int> = storage.indices.filter { storage[it] != other.storage[it] }
    fun plusDelta(delta: EqCurve): EqCurve = of(storage.indices.map { (storage[it] + delta[it]).coerceIn(-16, 16) })
    fun deltaFrom(baseline: EqCurve): EqCurve = of(storage.indices.map { (storage[it] - baseline[it]).coerceIn(-16, 16) })
    fun deltaValuesFrom(baseline: EqCurve): List<Int> = storage.indices.map { storage[it] - baseline[it] }
    fun withBand(index: Int, value: Int): EqCurve = of(storage.copyOf().apply { this[index] = value }.toList())
    override fun equals(other: Any?): Boolean = other is EqCurve && storage.contentEquals(other.storage)
    override fun hashCode(): Int = storage.contentHashCode()
    override fun toString(): String = storage.joinToString(prefix = "[", postfix = "]") { "%+d".format(it) }

    companion object {
        val FLAT = EqCurve(IntArray(EQ_BAND_COUNT))
        fun of(values: List<Int>): EqCurve {
            require(values.size == EQ_BAND_COUNT) { "An EQ curve must contain exactly eight bands" }
            require(values.all { it in -16..16 }) { "EQ gains must be within -16..+16 dB" }
            return EqCurve(values.toIntArray())
        }
    }
}

data class EqSnapshot(
    val path: EqPath,
    val context: EqContext,
    val curve: EqCurve,
    val model: String,
    val firmware: String?,
    val contextSource: String,
    val verifiedAt: Instant = Instant.now(),
)

enum class EqCaptureSource(val label: String, val hardwareBaseline: Boolean) {
    RAW_REFERENCE("RAW MIC / REFERENCE", false),
    KX3_OUTPUT("KX3 OUTPUT / CURRENT RADIO BASELINE", true),
    SECOND_RECEIVER("SECOND RECEIVER / OFF-AIR BASELINE", true),
}

enum class EqInputProcessing(val label: String) {
    OFF("INPUT PROCESSING OFF"), PARTIAL("PARTIALLY DISABLED"), UNKNOWN("DEVICE PROCESSING UNKNOWN")
}

data class EqAudioMetrics(
    val peakDbfs: Float,
    val activeRmsDbfs: Float,
    val crestDb: Float,
    val noiseFloorDbfs: Float,
    val clippedSamples: Int,
    val usableSpeechSeconds: Float,
    val bandEnergyDb: List<Float>,
) {
    val qualityLabel: String get() = when {
        clippedSamples > 0 -> "CLIPPING"
        usableSpeechSeconds < 1f -> "TOO LITTLE SPEECH"
        activeRmsDbfs < -38f -> "TOO QUIET"
        noiseFloorDbfs > -30f -> "HIGH BACKGROUND NOISE"
        else -> "VALID REFERENCE"
    }
}

data class EqCapture(
    val samples: ShortArray,
    val sampleRate: Int,
    val source: EqCaptureSource,
    val context: EqContext,
    val baseline: EqSnapshot?,
    val inputDevice: String,
    val channel: String,
    val capturedAt: Instant,
    val processing: EqInputProcessing,
    val metrics: EqAudioMetrics,
)

enum class EqIntent(val label: String) {
    NATURAL("Natural"), CLEAR_SSB("Clear SSB"), DX_PILEUP("DX / Pileup"),
    WIDEBAND_FIDELITY("Wideband Fidelity"), CLEAR_VOICE("Clear Voice"),
    SPEECH_CLARITY("Speech Clarity"), CW_FOCUS("CW Focus"),
}

data class EqSuggestion(val curve: EqCurve, val confidence: String, val rationale: List<String>)

data class EqProfile(
    val id: String,
    val name: String,
    val path: EqPath,
    val context: EqContext,
    val curve: EqCurve,
    val audioChain: String = "",
    val intent: EqIntent? = null,
    val notes: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val radioModel: String = "",
    val firmware: String? = null,
    val inputDevice: String = "",
)

enum class EqOperationState { DISCONNECTED, STALE, READING, LIVE_VERIFIED, DRAFT_CHANGED, APPLYING, VERIFYING, FAILED }

data class EqModeState(
    val receiveMode: String,
    val transmitMode: String,
    val transmitVfo: Int,
    val split: Boolean,
    val essb: Boolean,
)

fun resolveEqContext(path: EqPath, mode: EqModeState): Pair<EqContext, String> {
    val active = if (path == EqPath.RX) mode.receiveMode else mode.transmitMode
    val normalized = active.uppercase()
    val source = if (path == EqPath.TX) {
        "${if (mode.split) "Split TX VFO ${if (mode.transmitVfo == 1) "B" else "A"}" else "TX VFO A"} · $normalized${if (mode.essb) " · ESSB" else ""}"
    } else "Receive VFO A · $normalized"
    val context = when (path) {
        EqPath.RX -> when {
            normalized.startsWith("CW") -> EqContext.RX_CW
            normalized in setOf("DATA", "DATA-R", "RTTY", "RTTY-R") -> EqContext.RX_DATA
            normalized in setOf("USB", "LSB", "AM", "FM") -> EqContext.RX_VOICE
            else -> EqContext.UNKNOWN
        }
        EqPath.TX -> when {
            normalized in setOf("CW", "CW-R", "DATA", "DATA-R", "RTTY", "RTTY-R") -> EqContext.TX_INACTIVE
            normalized in setOf("AM", "FM") || mode.essb -> EqContext.TX_WIDEBAND
            normalized in setOf("USB", "LSB") -> EqContext.TX_SSB
            else -> EqContext.UNKNOWN
        }
    }
    return context to source
}
