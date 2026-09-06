package app.shackcq.mobile

import kotlin.math.abs
import kotlin.math.roundToInt

fun suggestEqCurve(baseline: EqCurve, metrics: EqAudioMetrics, intent: EqIntent, cwPitchHz: Int? = null): EqSuggestion {
    if (metrics.qualityLabel != "VALID REFERENCE") return EqSuggestion(baseline, "LOW",
        listOf("No boost suggested: ${metrics.qualityLabel.lowercase()} prevents a reliable starting point."))
    val target = when (intent) {
        EqIntent.NATURAL -> intArrayOf(-2, -1, 0, 0, 0, 0, -1, -2)
        EqIntent.CLEAR_SSB -> intArrayOf(-4, -3, -1, 0, 1, 2, 2, 1)
        EqIntent.DX_PILEUP -> intArrayOf(-6, -5, -2, 0, 2, 3, 4, 3)
        EqIntent.WIDEBAND_FIDELITY -> intArrayOf(-1, 0, 0, 0, 0, 1, 1, 0)
        EqIntent.CLEAR_VOICE, EqIntent.SPEECH_CLARITY -> intArrayOf(-4, -3, -1, 0, 1, 2, 2, 0)
        EqIntent.CW_FOCUS -> IntArray(8) { band ->
            val pitch = cwPitchHz ?: 600
            when {
                EQ_FREQUENCIES_HZ[band] == EQ_FREQUENCIES_HZ.minBy { abs(it - pitch) } -> 3
                EQ_FREQUENCIES_HZ[band] < pitch / 2 || EQ_FREQUENCIES_HZ[band] > pitch * 2 -> -3
                else -> 0
            }
        }
    }
    val mid = metrics.bandEnergyDb.slice(2..5).average().toFloat()
    val proposed = IntArray(8) { band ->
        val measuredShape = metrics.bandEnergyDb[band] - mid
        val correction = ((target[band] - measuredShape) * if (target[band] > measuredShape) .45f else .65f).roundToInt()
            .coerceIn(-6, 4)
        (baseline[band] + correction).coerceIn(-16, 16)
    }
    repeat(2) {
        for (band in 1 until 7) {
            val neighborhood = ((proposed[band - 1] + proposed[band] * 2 + proposed[band + 1]) / 4f).roundToInt()
            proposed[band] = neighborhood.coerceIn(baseline[band] - 6, baseline[band] + 4).coerceIn(-16, 16)
        }
    }
    val changed = proposed.indices.filter { proposed[it] != baseline[it] }
    val rationale = if (changed.isEmpty()) listOf("The measured shape is already close to this conservative target.") else listOf(
        "Changes are limited to +4/−6 dB from the verified baseline.",
        "Adjacent bands are smoothed and boosts are penalised more than cuts.",
        if (intent == EqIntent.CW_FOCUS) "Focus follows the configured ${cwPitchHz ?: 600} Hz CW pitch; bandwidth and APF remain separate." else "Energy is normalised to the useful speech mid-band, not absolute recording level.",
    )
    return EqSuggestion(EqCurve.of(proposed.toList()), if (metrics.usableSpeechSeconds >= 4f) "MEDIUM" else "LOW", rationale)
}
