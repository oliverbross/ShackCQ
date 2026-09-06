package app.shackcq.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class DigiIssPass(val name: String, val aosEpoch: Long, val losEpoch: Long, val maximumElevation: Double)

data class DigiDependencies(
    val database: QsoDatabase,
    val mutations: QsoMutationCoordinator,
    val cty: CtyController,
    val radioState: () -> RadioState,
    val stationProfile: () -> String = { "" },
    val stationLocation: () -> String = { "" },
    val operatorCallsign: () -> String = { "" },
    val activationContext: () -> Pair<String, String> = { "" to "" },
    val needsByCallsign: () -> Map<String, List<String>> = { emptyMap() },
    val liveSpots: () -> List<AndroidDXSpot> = { emptyList() },
    val onOpenLogbook: (String) -> Unit = {},
    val onOpenDx: (String) -> Unit = {},
    val onOpenCallbook: (String) -> Unit = {},
    val nextIssPass: () -> DigiIssPass? = { null },
    val requestIssReceiveReview: () -> Unit = {},
)

enum class DigiCapabilityStatus { RX_AND_TX_VERIFIED, RX_VERIFIED_TX_MANUAL, MANUAL_ONLY, UNAVAILABLE_ENGINE, HIDDEN_UNTIL_VERIFIED }
enum class DigiSequencerSupport { FT8_FT4, MANUAL, NONE }
enum class DigiWaterfallBehavior { AUDIO_OFFSET, MARK_SPACE_CENTER, CARRIER, CW_PITCH, VIEW_ONLY }

data class DigiModeCapability(
    val mode: DigiMode,
    val stableId: String = mode.name.lowercase(Locale.US),
    val family: String = mode.family,
    val periodMillis: Long = mode.slotMillis,
    val rxEngine: Boolean,
    val txEngine: Boolean,
    val fixtureStatus: DigiCapabilityStatus,
    val sequencer: DigiSequencerSupport,
    val waterfall: DigiWaterfallBehavior,
    val audioSpanHz: IntRange,
    val defaultDialChannelsHz: List<Long>,
    val maximumTxMillis: Long,
    val adifMode: String,
    val adifSubmode: String = "",
    val reason: String = "",
) {
    val visible get() = fixtureStatus != DigiCapabilityStatus.HIDDEN_UNTIL_VERIFIED && fixtureStatus != DigiCapabilityStatus.UNAVAILABLE_ENGINE
    val sendEnabled get() = visible && txEngine && fixtureStatus != DigiCapabilityStatus.MANUAL_ONLY
}

object DigiCapabilities {
    val all: List<DigiModeCapability> = DigiMode.entries.map { mode ->
        val fullyVerified = mode in setOf(DigiMode.FT8, DigiMode.FT4)
        val manualBoundary = mode !in setOf(DigiMode.FT8, DigiMode.FT4)
        val behavior = when (mode) {
            DigiMode.RTTY -> DigiWaterfallBehavior.MARK_SPACE_CENTER
            DigiMode.PSK31 -> DigiWaterfallBehavior.CARRIER
            DigiMode.CW -> DigiWaterfallBehavior.CW_PITCH
            DigiMode.SSTV -> DigiWaterfallBehavior.VIEW_ONLY
            else -> DigiWaterfallBehavior.AUDIO_OFFSET
        }
        val adif = when (mode) {
            DigiMode.PSK31 -> "PSK"
            DigiMode.SSTV -> "SSTV"
            else -> mode.family
        }
        val submode = when {
            mode == DigiMode.PSK31 -> "PSK31"
            mode.label != adif && mode.family in setOf("FST4", "Q65", "MSK144", "JT65") -> mode.label
            else -> ""
        }
        DigiModeCapability(
            mode = mode,
            rxEngine = true,
            txEngine = mode.txSupported,
            fixtureStatus = if (fullyVerified) DigiCapabilityStatus.RX_AND_TX_VERIFIED else DigiCapabilityStatus.RX_VERIFIED_TX_MANUAL,
            sequencer = if (fullyVerified) DigiSequencerSupport.FT8_FT4 else if (manualBoundary) DigiSequencerSupport.MANUAL else DigiSequencerSupport.NONE,
            waterfall = behavior,
            audioSpanHz = when (mode) { DigiMode.CW -> 300..1_200; DigiMode.SSTV -> 0..3_000; else -> 0..4_000 },
            defaultDialChannelsHz = defaultDialChannels(mode),
            maximumTxMillis = when { mode.slotMillis > 0 -> mode.slotMillis; mode == DigiMode.SSTV -> 600_000; else -> 300_000 },
            adifMode = adif,
            adifSubmode = submode,
            reason = if (manualBoundary && mode.isSlotted) "Automatic sequencing is verified only for FT8 and FT4." else "",
        )
    }
    private val byMode = all.associateBy(DigiModeCapability::mode)
    fun forMode(mode: DigiMode): DigiModeCapability = requireNotNull(byMode[mode])

    private fun defaultDialChannels(mode: DigiMode): List<Long> = when (mode) {
        DigiMode.FT8 -> listOf(1_840_000, 3_573_000, 7_074_000, 10_136_000, 14_074_000, 18_100_000, 21_074_000, 24_915_000, 28_074_000, 50_313_000)
        DigiMode.FT4 -> listOf(3_575_000, 7_047_500, 10_140_000, 14_080_000, 18_104_000, 21_140_000, 24_919_000, 28_180_000)
        DigiMode.WSPR -> listOf(1_836_600, 3_568_600, 7_038_600, 10_138_700, 14_095_600, 18_104_600, 21_094_600, 24_924_600, 28_124_600)
        DigiMode.SSTV -> listOf(3_730_000, 7_171_000, 14_230_000, 21_340_000, 28_680_000)
        else -> emptyList()
    }
}

enum class DigiAudioHealthState { NOT_SELECTED, PERMISSION_REQUIRED, INITIALIZING, LIVE, NO_CAPTURE, SILENT, LOW_LEVEL, CLIPPING, ROUTE_LOST, OVERRUN, ERROR }
data class DigiAudioHealth(
    val state: DigiAudioHealthState = DigiAudioHealthState.NOT_SELECTED,
    val routeIdentity: String = "",
    val source: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val rms: Float = 0f,
    val peak: Float = 0f,
    val clippedFraction: Float = 0f,
    val framesReceived: Long = 0,
    val droppedFrames: Long = 0,
    val lastFrameAgeMillis: Long = Long.MAX_VALUE,
    val nativeDecodeMillis: Long = 0,
    val audioOwner: String = "",
    val detail: String = "",
)

enum class DigiWaterfallState { LIVE, PAUSED, SCROLLBACK }
enum class DigiOperatingView { CLASSIC, ROSTER }
enum class DigiAnalysisWindow { FAST, BALANCED, SHARP }
enum class SstvReceiveHealthState { STOPPED, STARTED_NO_AUDIO_YET, NO_CAPTURE, SILENT, LISTENING, UNSUPPORTED_VIS, DECODING, COMPLETE, ERROR }
data class SstvReceiveHealth(
    val state: SstvReceiveHealthState = SstvReceiveHealthState.STOPPED,
    val lastSampleAgeMillis: Long = Long.MAX_VALUE, val peak: Float = 0f, val lastVis: String = "",
    val mode: String = "", val line: Int = -1, val totalLines: Int = 0, val decodedCount: Int = 0,
    val lastCompletedEpoch: Long = 0,
)
data class DigiWaterfallSettings(
    val palette: String = "NEXUS",
    val lowHz: Float = 0f,
    val highHz: Float = 3_000f,
    val floor: Float = 0.08f,
    val gain: Float = 1f,
    val contrast: Float = 1f,
    val window: DigiAnalysisWindow = DigiAnalysisWindow.BALANCED,
)

data class DigiSettingsDocument(
    val version: Int = 1,
    val selectedMode: String = DigiMode.CW.name,
    val hiddenModeIds: Set<String> = emptySet(),
    val waterfall: DigiWaterfallSettings = DigiWaterfallSettings(),
    val rxOffsets: Map<String, Float> = emptyMap(),
    val txOffsets: Map<String, Float> = emptyMap(),
    val ftTxParity: Int = 0,
    val holdTx: Boolean = false,
    val ftAutoSequence: Boolean = true,
    val ftAutoCq: Boolean = false,
    val ftAutoCqLimit: Int = 3,
    val ftRetryLimit: Int = 3,
    val ftAutoLog: Boolean = false,
    val rttyCarrierHz: Float = 1_000f,
    val rttyReverse: Boolean = false,
    val rttyMacros: List<String> = listOf("CQ CQ DE {MYCALL} K", "{DXCALL} DE {MYCALL} 599 599 K", "TU 73 DE {MYCALL} SK"),
    val rttyHelper: Boolean = true,
    val pskCarrierHz: Float = 1_000f,
    val pskMacros: List<String> = listOf("CQ CQ DE {MYCALL} K", "{DXCALL} DE {MYCALL} 599 K", "TU 73"),
    val pskContinuousPreference: Boolean = false,
    val cwPitchHz: Float = 700f,
    val cwWpm: Int = 20,
    val sstvMode: Int = 2,
    val sstvAutoArm: Boolean = true,
    val sstvCallsignOverlay: Boolean = true,
    val galleryQuotaMb: Int = 100,
    val decodeRetentionDays: Int = 7,
    val rawRecording: Boolean = false,
    val resumeExactRouteRx: Boolean = true,
    val udpEnabled: Boolean = false,
    val udpHost: String = "127.0.0.1",
    val udpPort: Int = 2237,
    val companionMode: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version); put("selectedMode", selectedMode); put("hiddenModeIds", JSONArray(hiddenModeIds.toList())); put("ftTxParity", ftTxParity); put("holdTx", holdTx)
        put("ftAutoSequence", ftAutoSequence); put("ftAutoCq", ftAutoCq); put("ftAutoCqLimit", ftAutoCqLimit); put("ftRetryLimit", ftRetryLimit); put("ftAutoLog", ftAutoLog)
        put("rttyCarrierHz", rttyCarrierHz); put("rttyReverse", rttyReverse); put("rttyMacros", JSONArray(rttyMacros)); put("rttyHelper", rttyHelper)
        put("pskCarrierHz", pskCarrierHz); put("pskMacros", JSONArray(pskMacros)); put("pskContinuousPreference", pskContinuousPreference); put("cwPitchHz", cwPitchHz); put("cwWpm", cwWpm)
        put("sstvMode", sstvMode); put("sstvAutoArm", sstvAutoArm); put("sstvCallsignOverlay", sstvCallsignOverlay); put("galleryQuotaMb", galleryQuotaMb)
        put("decodeRetentionDays", decodeRetentionDays); put("rawRecording", rawRecording); put("resumeExactRouteRx", resumeExactRouteRx)
        put("udpEnabled", udpEnabled); put("udpHost", udpHost); put("udpPort", udpPort); put("companionMode", companionMode)
        put("rxOffsets", JSONObject(rxOffsets)); put("txOffsets", JSONObject(txOffsets))
        put("waterfall", JSONObject().apply { put("palette", waterfall.palette); put("lowHz", waterfall.lowHz); put("highHz", waterfall.highHz); put("floor", waterfall.floor); put("gain", waterfall.gain); put("contrast", waterfall.contrast); put("window", waterfall.window.name) })
    }.toString()

    companion object {
        fun parse(value: String?): DigiSettingsDocument {
            val root = runCatching { JSONObject(value.orEmpty()) }.getOrNull() ?: return DigiSettingsDocument()
            fun floats(name: String) = root.optJSONObject(name)?.let { obj -> obj.keys().asSequence().associateWith { obj.optDouble(it).toFloat() } }.orEmpty()
            fun strings(name: String, fallback: List<String>) = root.optJSONArray(name)?.stringList() ?: fallback
            val wf = root.optJSONObject("waterfall")
            return DigiSettingsDocument(
                version = 1,
                selectedMode = root.optString("selectedMode", DigiMode.CW.name),
                hiddenModeIds = strings("hiddenModeIds", emptyList()).toSet(),
                waterfall = DigiWaterfallSettings(
                    palette = wf?.optString("palette", "NEXUS") ?: "NEXUS",
                    lowHz = wf?.optDouble("lowHz", 0.0)?.toFloat() ?: 0f,
                    highHz = wf?.optDouble("highHz", 3_000.0)?.toFloat() ?: 3_000f,
                    floor = wf?.optDouble("floor", .08)?.toFloat() ?: .08f,
                    gain = wf?.optDouble("gain", 1.0)?.toFloat() ?: 1f,
                    contrast = wf?.optDouble("contrast", 1.0)?.toFloat() ?: 1f,
                    window = runCatching { DigiAnalysisWindow.valueOf(wf?.optString("window").orEmpty()) }.getOrDefault(DigiAnalysisWindow.BALANCED),
                ),
                rxOffsets = floats("rxOffsets"), txOffsets = floats("txOffsets"),
                ftTxParity = if (root.has("ftTxParity")) root.optInt("ftTxParity").coerceIn(0, 1) else when (root.optString("txPeriod", "AUTO").uppercase(Locale.US)) {
                    "EVEN", "FIRST", "0" -> 0
                    "ODD", "SECOND", "1" -> 1
                    else -> if (root.optBoolean("txFirst", true)) 0 else 1
                },
                holdTx = root.optBoolean("holdTx"),
                ftAutoSequence = root.optBoolean("ftAutoSequence", true),
                ftAutoCq = effectiveAutoCq(root.optBoolean("ftAutoSequence", true), root.optBoolean("ftAutoCq")),
                ftAutoCqLimit = root.optInt("ftAutoCqLimit", 3).coerceIn(1, 20),
                ftRetryLimit = root.optInt("ftRetryLimit", 3).coerceIn(0, 10), ftAutoLog = root.optBoolean("ftAutoLog"),
                rttyCarrierHz = root.optDouble("rttyCarrierHz", 1_000.0).toFloat(),
                rttyReverse = root.optBoolean("rttyReverse"), rttyMacros = strings("rttyMacros", DigiSettingsDocument().rttyMacros),
                rttyHelper = root.optBoolean("rttyHelper", true), pskCarrierHz = root.optDouble("pskCarrierHz", 1_000.0).toFloat(),
                pskMacros = strings("pskMacros", DigiSettingsDocument().pskMacros), pskContinuousPreference = root.optBoolean("pskContinuousPreference"),
                cwPitchHz = root.optDouble("cwPitchHz", 700.0).toFloat(), cwWpm = root.optInt("cwWpm", 20).coerceIn(8, 45),
                sstvMode = root.optInt("sstvMode", 2), sstvAutoArm = root.optBoolean("sstvAutoArm", true), sstvCallsignOverlay = root.optBoolean("sstvCallsignOverlay", true),
                galleryQuotaMb = root.optInt("galleryQuotaMb", 100).coerceIn(25, 250), decodeRetentionDays = root.optInt("decodeRetentionDays", 7).coerceIn(1, 30),
                rawRecording = root.optBoolean("rawRecording"), resumeExactRouteRx = root.optBoolean("resumeExactRouteRx", true),
                udpEnabled = root.optBoolean("udpEnabled"), udpHost = root.optString("udpHost", "127.0.0.1"),
                udpPort = root.optInt("udpPort", 2237).coerceIn(1, 65535), companionMode = root.optBoolean("companionMode"),
            )
        }
    }
}

enum class FtMessageKind { CQ, GRID, REPORT, R_REPORT, RRR, RR73, FINAL_73, FREE }
data class DigiFtMessage(val raw: String, val from: String = "", val to: String = "", val grid: String = "", val report: String = "", val kind: FtMessageKind = FtMessageKind.FREE)

object DigiFtParser {
    private val call = Regex("^(?=.*[A-Z])(?=.*\\d)[A-Z0-9]{1,8}(?:/[A-Z0-9]{1,8})?$")
    private val grid = Regex("^[A-R]{2}[0-9]{2}(?:[A-X]{2})?$")
    private val report = Regex("^(?:R)?[+-][0-9]{2}$")
    fun baseCall(value: String): String {
        val parts = value.trim().uppercase(Locale.US).split('/').filter(String::isNotBlank)
        return parts.maxByOrNull { part -> part.count(Char::isDigit) * 10 + part.length }.orEmpty()
    }
    fun parse(raw: String): DigiFtMessage {
        val words = raw.trim().uppercase(Locale.US).split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return DigiFtMessage(raw)
        if (words.first() == "CQ") {
            val from = words.drop(1).firstOrNull(call::matches).orEmpty()
            val locator = words.lastOrNull(grid::matches).orEmpty()
            return DigiFtMessage(raw, from = from, grid = locator, kind = FtMessageKind.CQ)
        }
        val to = words.getOrNull(0).takeIf { it != null && call.matches(it) }.orEmpty()
        val from = words.getOrNull(1).takeIf { it != null && call.matches(it) }.orEmpty()
        val tail = words.drop(2)
        val locator = tail.firstOrNull(grid::matches).orEmpty()
        val signal = tail.firstOrNull(report::matches).orEmpty()
        val kind = when {
            tail.any { it == "RR73" } -> FtMessageKind.RR73
            tail.any { it == "RRR" } -> FtMessageKind.RRR
            tail.any { it == "73" } -> FtMessageKind.FINAL_73
            signal.startsWith("R") -> FtMessageKind.R_REPORT
            signal.isNotBlank() -> FtMessageKind.REPORT
            locator.isNotBlank() -> FtMessageKind.GRID
            else -> FtMessageKind.FREE
        }
        return DigiFtMessage(raw, from, to, locator, signal, kind)
    }
}

data class DigiDecodeEvent(
    val id: String,
    val sessionId: String,
    val epoch: Long,
    val mode: String,
    val slotStartMillis: Long,
    val decodeSource: DigiDecodeSource,
    val exactSlotTiming: Boolean,
    val dialFrequencyHz: Long,
    val snr: Float,
    val dt: Float,
    val audioHz: Float,
    val text: String,
    val callsign: String = "",
    val grid: String = "",
    val country: String = "",
    val continent: String = "",
    val distanceKm: Double = 0.0,
    val bearingDegrees: Double = 0.0,
    val worked: Boolean = false,
    val confirmed: Boolean = false,
    val needs: List<String> = emptyList(),
    val watchlisted: Boolean = false,
)

enum class DigiDecodeSource { LIVE_CAPTURE, REDECODE_LIVE_SLOT, REFERENCE_RECORDING, COMPANION }

fun DigiDecodeEvent.automaticFtEligible(
    activeMode: String,
    activeDialFrequencyHz: Long,
    activeSessionId: String,
    activeCapturedSlotMillis: Long,
): Boolean {
    if (!exactSlotTiming || mode != activeMode || dialFrequencyHz != activeDialFrequencyHz || callsign.isBlank()) return false
    return when (decodeSource) {
        DigiDecodeSource.LIVE_CAPTURE -> sessionId == activeSessionId
        DigiDecodeSource.REDECODE_LIVE_SLOT -> slotStartMillis == activeCapturedSlotMillis
        DigiDecodeSource.REFERENCE_RECORDING, DigiDecodeSource.COMPANION -> false
    }
}

data class DigiQsoDraft(
    val callsign: String, val grid: String = "", val sentReport: String = "", val receivedReport: String = "",
    val startEpoch: Long, val endEpoch: Long, val dialFrequencyHz: Long, val band: String,
    val mode: String, val submode: String = "", val audioFrequencyHz: Float = 0f,
    val stationCallsign: String, val stationProfile: String = "", val stationLocation: String = "", val stationGrid: String = "",
    val operatorCallsign: String = "", val activationContext: String = "", val contestId: String = "", val comment: String = "ShackCQ Digi",
)

data class DigiDiagnostic(val epoch: Long, val state: String, val detail: String)

fun JSONArray.stringList(): List<String> = buildList { repeat(length()) { add(optString(it)) } }
