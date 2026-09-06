package app.shackcq.mobile

import kotlin.math.abs

enum class PanadapterLifecycle { STOPPED, STARTING, LIVE, ROUTE_LOST, REPLAY, ERROR }
enum class PanadapterWindow(val nativeValue: Int) { BLACKMAN_HARRIS(0), HANN(1), NUTTALL(2), RECTANGULAR(3), FLAT_TOP(4) }
enum class PanadapterPalette { FLIGHTLINE, VIRIDIS, GREYSCALE }
enum class PanadapterFormatState {
    ROUTE_UNPROVEN,
    ROUTE_PROVEN_FORMAT_PENDING,
    TRUE_96K_STEREO,
    TRUE_48K_STEREO,
    RESAMPLED_48_TO_96,
    OTHER_RESAMPLED_PATH,
    UNSUPPORTED_MONO_OR_CONVERTED_CHANNEL_PATH,
    ROUTE_LOST,
}
enum class PanadapterIqState { UNVERIFIED, CHANNELS_HEALTHY_ORIENTATION_UNVERIFIED, MIRROR_IMAGES_DOMINANT, VERIFIED_UNCALIBRATED, CALIBRATED, INVALID }
enum class PanadapterCalibrationState { UNCALIBRATED, DEVICE_BOUND, INVALID_FOR_PATH }
enum class PanadapterDisplayState { UNAVAILABLE, HEALTHY, SATURATED, INSUFFICIENT_VALID_BINS }

fun effectivePanadapterCenter(radio: RadioState, nowMonotonicMs: Long): Long {
    val age = if (radio.updatedMonotonicMs > 0) nowMonotonicMs - radio.updatedMonotonicMs else Long.MAX_VALUE
    return if (radio.connected && age in 0 until 2_500) radio.effectiveRxHz.takeIf { it > 0 } ?: radio.frequencyHz else 0L
}

fun canPanadapterQsy(radio: RadioState, liveCenterHz: Long): Boolean =
    radio.connected && radio.model == "KX3" && !radio.transmitting && liveCenterHz > 0

fun panadapterCaptureRadioBlocker(radio: RadioState): String? = when {
    !radio.connected -> null
    radio.model != "KX3" -> "Connected CAT radio is not a KX3; disconnect CAT to use standalone relative I/Q"
    radio.transmitting -> "Receive I/Q cannot start while the KX3 is transmitting"
    else -> null
}

data class PanadapterSettings(
    val requestedRate: Int = 48_000,
    val allow48kFallback: Boolean = true,
    val fftSize: Int = 4_096,
    val overlapPercent: Int = 50,
    val window: PanadapterWindow = PanadapterWindow.BLACKMAN_HARRIS,
    val displayFloorDb: Float = -120f,
    val displayTopDb: Float = -20f,
    val attack: Float = 0.78f,
    val release: Float = 0.16f,
    val averageFrames: Int = 2,
    val peakHold: Boolean = false,
    val peakDecayDbPerSecond: Float = 0f,
    val genericKx3Flatness: Boolean = false,
    val swapIq: Boolean = false,
    val invertI: Boolean = false,
    val invertQ: Boolean = false,
    val conjugate: Boolean = false,
    val iTrim: Float = 1f,
    val qTrim: Float = 1f,
    val zoomDecimation: Int = 1,
    val zoomOffsetHz: Float = 0f,
    val waterfallRows: Int = 384,
    val waterfallMinDb: Float = -110f,
    val waterfallMaxDb: Float = -55f,
    val waterfallGamma: Float = 0.85f,
    val waterfallLineRate: Int = 25,
    val palette: PanadapterPalette = PanadapterPalette.FLIGHTLINE,
    val showSpots: Boolean = true,
    val showFloor: Boolean = true,
    val centerMaskBins: Int = 0,
    val keepScreenAwake: Boolean = false,
    val qsyStepHz: Int = 10,
    val iqAReal: Float = 1f,
    val iqAImag: Float = 0f,
    val iqBReal: Float = 0f,
    val iqBImag: Float = 0f,
    val iqCorrectionEnabled: Boolean = false,
    val calibrationDeviceKey: String = "",
    val calibrationRate: Int = 0,
    val autoLevel: Boolean = true,
    val levelAttack: Float = .35f,
    val levelRelease: Float = .08f,
    val waterfallAverageFrames: Int = 2,
    val measuredFlatnessEnabled: Boolean = false,
    val measuredFlatnessOffsetsCsv: String = "",
    val measuredFlatnessGainsCsv: String = "",
    val measuredFlatnessDeviceKey: String = "",
    val measuredFlatnessRate: Int = 0,
    val measuredFlatnessRadio: String = "",
    val measuredFlatnessEpochMs: Long = 0,
    val levelCalibrationEnabled: Boolean = false,
    val dbfsToDbmOffset: Float = 0f,
    val levelCalibrationFrequencyHz: Long = 0,
    val levelCalibrationDeviceKey: String = "",
    val levelCalibrationRate: Int = 0,
    val levelCalibrationRadio: String = "",
    val levelCalibrationBand: String = "",
    val levelCalibrationEpochMs: Long = 0,
    val levelCalibrationUncertaintyDb: Float = 0f,
    val levelCalibrationNotes: String = "",
) {
    fun validated(): PanadapterSettings = copy(
        requestedRate = if (requestedRate == 96_000) 96_000 else 48_000,
        fftSize = fftSize.takeIf { it in setOf(1_024, 2_048, 4_096, 8_192) } ?: 4_096,
        overlapPercent = overlapPercent.takeIf { it in setOf(25, 50, 75) } ?: 50,
        displayFloorDb = displayFloorDb.coerceIn(-140f, -40f),
        displayTopDb = displayTopDb.coerceIn(displayFloorDb + 20f, 20f),
        attack = attack.coerceIn(.01f, 1f), release = release.coerceIn(.001f, 1f),
        averageFrames = averageFrames.coerceIn(1, 64),
        zoomDecimation = zoomDecimation.takeIf { it in setOf(1, 2, 4, 8) } ?: 1,
        waterfallRows = waterfallRows.coerceIn(128, 768),
        waterfallLineRate = waterfallLineRate.coerceIn(5, 30),
        waterfallGamma = waterfallGamma.coerceIn(.25f, 3f),
        centerMaskBins = centerMaskBins.coerceIn(0, 8),
        qsyStepHz = qsyStepHz.takeIf { it in setOf(1, 10, 20, 50, 100) } ?: 10,
        iTrim = iTrim.coerceIn(.25f, 4f), qTrim = qTrim.coerceIn(.25f, 4f),
        levelAttack = levelAttack.coerceIn(.01f, 1f), levelRelease = levelRelease.coerceIn(.005f, 1f),
        waterfallAverageFrames = waterfallAverageFrames.coerceIn(1, 32),
        dbfsToDbmOffset = dbfsToDbmOffset.coerceIn(-200f, 200f),
        levelCalibrationUncertaintyDb = levelCalibrationUncertaintyDb.coerceIn(0f, 30f),
    )

    fun encode(): String = listOf(
        "v=2", "rate=$requestedRate", "fallback=$allow48kFallback", "fft=$fftSize", "overlap=$overlapPercent", "window=${window.name}",
        "floor=$displayFloorDb", "top=$displayTopDb", "attack=$attack", "release=$release", "average=$averageFrames",
        "peak_hold=$peakHold", "peak_decay=$peakDecayDbPerSecond", "flatness=$genericKx3Flatness", "swap=$swapIq",
        "invert_i=$invertI", "invert_q=$invertQ", "conjugate=$conjugate", "i_trim=$iTrim", "q_trim=$qTrim",
        "zoom=$zoomDecimation", "zoom_offset=$zoomOffsetHz", "wf_rows=$waterfallRows", "wf_min=$waterfallMinDb", "wf_max=$waterfallMaxDb",
        "wf_gamma=$waterfallGamma", "wf_rate=$waterfallLineRate", "palette=${palette.name}", "spots=$showSpots", "floor_line=$showFloor",
        "center_mask=$centerMaskBins", "awake=$keepScreenAwake", "qsy_step=$qsyStepHz", "iq_ar=$iqAReal", "iq_ai=$iqAImag",
        "iq_br=$iqBReal", "iq_bi=$iqBImag", "iq_enabled=$iqCorrectionEnabled",
        "cal_device=$calibrationDeviceKey", "cal_rate=$calibrationRate",
        "auto_level=$autoLevel", "level_attack=$levelAttack", "level_release=$levelRelease", "wf_average=$waterfallAverageFrames",
        "measured_flatness=$measuredFlatnessEnabled", "flat_offsets=$measuredFlatnessOffsetsCsv", "flat_gains=$measuredFlatnessGainsCsv",
        "flat_device=$measuredFlatnessDeviceKey", "flat_rate=$measuredFlatnessRate", "flat_radio=$measuredFlatnessRadio", "flat_epoch=$measuredFlatnessEpochMs",
        "level_enabled=$levelCalibrationEnabled", "level_offset=$dbfsToDbmOffset", "level_frequency=$levelCalibrationFrequencyHz",
        "level_device=$levelCalibrationDeviceKey", "level_rate=$levelCalibrationRate", "level_radio=$levelCalibrationRadio",
        "level_band=$levelCalibrationBand", "level_epoch=$levelCalibrationEpochMs", "level_uncertainty=$levelCalibrationUncertaintyDb",
        "level_notes=${levelCalibrationNotes.replace(';', ',').replace('=', ':')}",
    ).joinToString(";")

    companion object {
        fun decode(value: String?): PanadapterSettings = runCatching {
            val row = value?.split(';')?.mapNotNull { field -> field.indexOf('=').takeIf { it > 0 }?.let { field.substring(0, it) to field.substring(it + 1) } }?.toMap()
                ?: return@runCatching PanadapterSettings()
            val version = row["v"]?.toIntOrNull() ?: 1
            require(version in 1..2)
            fun int(name: String, fallback: Int) = row[name]?.toIntOrNull() ?: fallback
            fun float(name: String, fallback: Float) = row[name]?.toFloatOrNull() ?: fallback
            fun bool(name: String, fallback: Boolean = false) = row[name]?.toBooleanStrictOrNull() ?: fallback
            val decoded = PanadapterSettings(
                int("rate", 48_000), bool("fallback", true), int("fft", 4_096), int("overlap", 50),
                enumValueOf<PanadapterWindow>(row["window"] ?: PanadapterWindow.BLACKMAN_HARRIS.name),
                float("floor", -120f), float("top", -20f), float("attack", .78f), float("release", .16f), int("average", 2),
                bool("peak_hold"), float("peak_decay", 0f), bool("flatness", false), bool("swap"), bool("invert_i"), bool("invert_q"), bool("conjugate"),
                float("i_trim", 1f), float("q_trim", 1f), int("zoom", 1), float("zoom_offset", 0f), int("wf_rows", 384),
                float("wf_min", -120f), float("wf_max", -45f), float("wf_gamma", .85f), int("wf_rate", 25),
                enumValueOf<PanadapterPalette>(row["palette"] ?: PanadapterPalette.FLIGHTLINE.name),
                bool("spots", true), bool("floor_line", true), int("center_mask", 0), bool("awake"), int("qsy_step", 10),
                float("iq_ar", 1f), float("iq_ai", 0f), float("iq_br", 0f), float("iq_bi", 0f), bool("iq_enabled"),
                row["cal_device"] ?: "", int("cal_rate", 0),
                bool("auto_level", true), float("level_attack", .35f), float("level_release", .08f), int("wf_average", 2),
                bool("measured_flatness"), row["flat_offsets"] ?: "", row["flat_gains"] ?: "",
                row["flat_device"] ?: "", int("flat_rate", 0), row["flat_radio"] ?: "", row["flat_epoch"]?.toLongOrNull() ?: 0,
                bool("level_enabled"), float("level_offset", 0f), row["level_frequency"]?.toLongOrNull() ?: 0,
                row["level_device"] ?: "", int("level_rate", 0), row["level_radio"] ?: "", row["level_band"] ?: "",
                row["level_epoch"]?.toLongOrNull() ?: 0, float("level_uncertainty", 0f), row["level_notes"] ?: "",
            ).validated()
            if (version == 1) decoded.copy(
                genericKx3Flatness = false,
                autoLevel = true,
                waterfallMinDb = -110f,
                waterfallMaxDb = -55f,
            ).validated() else decoded
        }.getOrDefault(PanadapterSettings())
    }
}

data class PanadapterFlatnessPoint(val offsetHz: Float, val correctionDb: Float)

fun parseMeasuredFlatness(settings: PanadapterSettings): List<PanadapterFlatnessPoint> {
    val offsets = settings.measuredFlatnessOffsetsCsv.split(',').mapNotNull { it.trim().toFloatOrNull() }
    val gains = settings.measuredFlatnessGainsCsv.split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (offsets.size != gains.size || offsets.size !in 2..16) return emptyList()
    val points = offsets.zip(gains).map { (offset, gain) ->
        PanadapterFlatnessPoint(abs(offset), gain.coerceIn(-20f, 20f))
    }.sortedBy { it.offsetHz }
    if (points.first().offsetHz != 0f || points.zipWithNext().any { it.first.offsetHz >= it.second.offsetHz }) return emptyList()
    return points
}

fun measuredFlatnessCorrection(points: List<PanadapterFlatnessPoint>, offsetHz: Float): Float {
    if (points.isEmpty()) return 0f
    val x = abs(offsetHz)
    if (x >= points.last().offsetHz) return points.last().correctionDb
    val upper = points.indexOfFirst { it.offsetHz >= x }.coerceAtLeast(1)
    val low = points[upper - 1]; val high = points[upper]
    val fraction = (x - low.offsetHz) / (high.offsetHz - low.offsetHz)
    return low.correctionDb + fraction * (high.correctionDb - low.correctionDb)
}

data class PanadapterLevelCalibrationCandidate(
    val knownDbm: Float,
    val measuredDbfs: Float,
    val offsetDb: Float,
    val uncertaintyDb: Float,
    val notes: String,
)

data class PanadapterRouteProof(
    val requestedDevice: String = "None",
    val preferredAccepted: Boolean = false,
    val actualDevice: String = "None",
    val requestedRate: Int = 0,
    val configuredRate: Int = 0,
    val configuredChannels: Int = 0,
    val encoding: Int = 0,
    val bufferFrames: Int = 0,
    val clientFormat: String = "Unavailable",
    val deviceFormat: String = "Unavailable",
    val audioSource: Int = 0,
    val sessionId: Int = 0,
    val clientRate: Int = 0,
    val clientChannels: Int = 0,
    val clientChannelMask: Int = 0,
    val clientEncoding: Int = 0,
    val deviceRate: Int = 0,
    val deviceChannels: Int = 0,
    val deviceChannelMask: Int = 0,
    val deviceEncoding: Int = 0,
    val activeConfigurationAvailable: Boolean = false,
    val activeDevice: String = "None",
    val clientSilenced: Boolean = false,
    val clientEffects: String = "Unavailable",
    val deviceEffects: String = "Unavailable",
    val stateOverride: PanadapterFormatState? = null,
) {
    val state: PanadapterFormatState get() = stateOverride ?: classifyPanadapterFormat(this)
    val routeVerified: Boolean get() = preferredAccepted && requestedDevice == actualDevice && requestedDevice == activeDevice
    val verified: Boolean get() = state == PanadapterFormatState.TRUE_48K_STEREO || state == PanadapterFormatState.TRUE_96K_STEREO
    val physicalRate: Int get() = if (verified) deviceRate else 0
    val conversionPresent: Boolean get() = clientRate != deviceRate || clientChannels != deviceChannels || clientEncoding != deviceEncoding
}

internal fun classifyPanadapterFormat(proof: PanadapterRouteProof): PanadapterFormatState {
    if (proof.stateOverride != null) return proof.stateOverride
    if (!proof.preferredAccepted || proof.requestedDevice != proof.actualDevice ||
        proof.requestedDevice != proof.activeDevice) return PanadapterFormatState.ROUTE_UNPROVEN
    if (!proof.activeConfigurationAvailable || proof.clientRate <= 0 || proof.deviceRate <= 0)
        return PanadapterFormatState.ROUTE_PROVEN_FORMAT_PENDING
    if (proof.clientChannels != 2 || proof.deviceChannels != 2 || proof.configuredChannels != 2 ||
        proof.clientEncoding != proof.deviceEncoding)
        return PanadapterFormatState.UNSUPPORTED_MONO_OR_CONVERTED_CHANNEL_PATH
    if (proof.clientRate == 96_000 && proof.deviceRate == 48_000)
        return PanadapterFormatState.RESAMPLED_48_TO_96
    if (proof.clientRate != proof.deviceRate)
        return PanadapterFormatState.OTHER_RESAMPLED_PATH
    return when (proof.deviceRate) {
        96_000 -> PanadapterFormatState.TRUE_96K_STEREO
        48_000 -> PanadapterFormatState.TRUE_48K_STEREO
        else -> PanadapterFormatState.OTHER_RESAMPLED_PATH
    }
}

data class PanadapterDisplayMetrics(
    val rawFloorDb: Float = Float.NaN,
    val stabilizedFloorDb: Float = Float.NaN,
    val spectrumFloorDb: Float = -110f,
    val spectrumTopDb: Float = -55f,
    val waterfallBlackDb: Float = -108f,
    val waterfallTopDb: Float = -58f,
    val fractionBelowBlack: Float = 0f,
    val usefulColorFraction: Float = 0f,
    val waterfallSaturatedFraction: Float = 0f,
    val validBinFraction: Float = 0f,
    val validBinCount: Int = 0,
    val medianDb: Float = Float.NaN,
    val highPercentileDb: Float = Float.NaN,
    val peakToFloorDb: Float = Float.NaN,
    val inBandToInvalidPowerDb: Float = Float.NaN,
    val combSpacingHz: Float = Float.NaN,
    val combPersistence: Float = 0f,
    val mirrorRejectionDb: Float = Float.NaN,
    val mirrorPairCount: Int = 0,
    val state: PanadapterDisplayState = PanadapterDisplayState.UNAVAILABLE,
)

data class PanadapterFrame(
    val sequence: Long,
    val inputFrames: Long,
    val transforms: Long,
    val discontinuities: Long,
    val sampleRate: Int,
    val effectiveSampleRate: Int,
    val fftSize: Int,
    val hopSize: Int,
    val zoomDecimation: Int,
    val zoomOffsetHz: Float,
    val enbwBins: Float,
    val rbwHz: Float,
    val peakDb: Float,
    val floorDb: Float,
    val iRmsDb: Float,
    val qRmsDb: Float,
    val iqCorrelation: Float,
    val clippedFraction: Float,
    val duplicateCorrelation: Float,
    val validStereo: Boolean,
    val trace: FloatArray,
    val waterfall: FloatArray,
    val peakHold: FloatArray,
    val validMask: BooleanArray = BooleanArray(trace.size) { true },
)

data class PanadapterMarker(val frequencyHz: Long, val levelDb: Float, val callsign: String? = null)
data class PanadapterQsy(val vfo: Int, val previousHz: Long, val requestedHz: Long, val observedRevision: Long)
data class PanadapterCalibrationCandidate(
    val bReal: Float,
    val bImag: Float,
    val knownOffsetHz: Float,
    val rejectionBeforeDb: Float,
    val rejectionAfterDb: Float? = null,
    val measuredOffsetHz: Float = Float.NaN,
    val axisErrorHz: Float = Float.NaN,
    val desiredLevelDb: Float = Float.NaN,
    val imageLevelDb: Float = Float.NaN,
    val gainImbalanceDb: Float = Float.NaN,
    val phaseErrorDegrees: Float = Float.NaN,
    val dcSpurRelativeFloorDb: Float = Float.NaN,
)

data class PanadapterSpurCapture(
    val stage: String,
    val capturedEpochMs: Long,
    val formatState: PanadapterFormatState,
    val physicalRate: Int,
    val floorDb: Float,
    val combSpacingHz: Float,
    val combPersistence: Float,
    val saturatedFraction: Float,
)

data class PassbandEdges(val lowOffsetHz: Float, val highOffsetHz: Float)

internal fun panadapterPassband(state: RadioState): PassbandEdges {
    val width = state.bandwidthHz.coerceAtLeast(100).toFloat()
    val center = state.ifShiftHz.takeIf { it >= 0 }?.toFloat() ?: when (state.mode) {
        "CW", "CWR" -> 600f
        "AM" -> 0f
        else -> 1500f
    }
    return when (state.mode) {
        "LSB", "CWR" -> PassbandEdges(-center - width / 2f, -center + width / 2f)
        "USB", "CW", "DATA", "DATA-R" -> PassbandEdges(center - width / 2f, center + width / 2f)
        else -> PassbandEdges(-width / 2f, width / 2f)
    }
}

internal fun roundedPanadapterFrequency(value: Long, step: Int): Long = ((value + step / 2L) / step) * step

internal fun markerLevel(frame: PanadapterFrame?, centerHz: Long, frequencyHz: Long): Float {
    frame ?: return -140f
    val span = frame.effectiveSampleRate.toFloat()
    val normalized = (frequencyHz - centerHz) / span + .5f
    val index = (normalized * frame.fftSize).toInt().coerceIn(0, frame.fftSize - 1)
    return frame.trace[index]
}

internal fun isMaterialCenterChange(previous: Long, next: Long, rbwHz: Float): Boolean =
    previous > 0 && next > 0 && abs(next - previous) > maxOf(10f, rbwHz).toLong()
