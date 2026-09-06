package app.shackcq.mobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class EqPreview(
    val before: ShortArray,
    val after: ShortArray,
    val waveform: List<Float>,
    val beforeSpectrum: List<Float>,
    val afterSpectrum: List<Float>,
    val responseDb: List<Float>,
    val safetyReductionDb: Float,
    val loudnessMatched: Boolean,
)

private data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun process(input: DoubleArray): DoubleArray {
        val output = DoubleArray(input.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in input.indices) {
            val y = b0 * input[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            output[i] = y; x2 = x1; x1 = input[i]; y2 = y1; y1 = y
        }
        return output
    }
}

private fun peaking(sampleRate: Int, frequency: Int, gainDb: Int, q: Double = 1.15): Biquad {
    val a = 10.0.pow(gainDb / 40.0)
    val omega = 2.0 * PI * frequency / sampleRate
    val alpha = sin(omega) / (2.0 * q)
    val a0 = 1.0 + alpha / a
    return Biquad((1.0 + alpha * a) / a0, (-2.0 * cos(omega)) / a0,
        (1.0 - alpha * a) / a0, (-2.0 * cos(omega)) / a0, (1.0 - alpha / a) / a0)
}

fun applyApproximateKx3Eq(samples: ShortArray, sampleRate: Int, curve: EqCurve): DoubleArray {
    return applyApproximateKx3Eq(samples, sampleRate, curve.values)
}

fun applyApproximateKx3Eq(samples: ShortArray, sampleRate: Int, gains: List<Int>): DoubleArray {
    require(gains.size == EQ_BAND_COUNT && gains.all { it in -32..32 })
    var output = DoubleArray(samples.size) { samples[it] / 32768.0 }
    EQ_FREQUENCIES_HZ.indices.forEach { band -> if (gains[band] != 0) output = peaking(sampleRate, EQ_FREQUENCIES_HZ[band], gains[band]).process(output) }
    return output
}

fun previewGainsFor(capture: EqCapture, draft: EqCurve): List<Int> =
    if (capture.source.hardwareBaseline) draft.deltaValuesFrom(requireNotNull(capture.baseline) { "Hardware-baseline capture requires a verified radio curve" }.curve) else draft.values

fun buildEqPreview(capture: EqCapture, draft: EqCurve, loudnessMatch: Boolean = true): EqPreview {
    val beforeDouble = DoubleArray(capture.samples.size) { capture.samples[it] / 32768.0 }
    val previewGains = previewGainsFor(capture, draft)
    var afterDouble = applyApproximateKx3Eq(capture.samples, capture.sampleRate, previewGains)
    if (loudnessMatch) {
        val beforeRms = activeRms(beforeDouble)
        val afterRms = activeRms(afterDouble)
        if (afterRms > 1e-9) {
            val scale = beforeRms / afterRms
            afterDouble = DoubleArray(afterDouble.size) { afterDouble[it] * scale }
        }
    }
    val peak = afterDouble.maxOfOrNull(::abs) ?: 0.0
    val safety = if (peak > 0.94) 0.94 / peak else 1.0
    if (safety < 1.0) afterDouble = DoubleArray(afterDouble.size) { afterDouble[it] * safety }
    val reduction = if (safety < 1.0) (20 * log10(safety)).toFloat() else 0f
    return EqPreview(toShort(beforeDouble), toShort(afterDouble), waveform(capture.samples),
        averagedSpectrum(beforeDouble, capture.sampleRate), averagedSpectrum(afterDouble, capture.sampleRate),
        responseCurve(capture.sampleRate, previewGains), reduction, loudnessMatch)
}

private fun toShort(samples: DoubleArray) = ShortArray(samples.size) { (samples[it] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }

private fun activeRms(samples: DoubleArray): Double {
    if (samples.isEmpty()) return 0.0
    val frame = 480.coerceAtMost(samples.size)
    val energies = samples.asList().chunked(frame).map { row -> row.sumOf { it * it } / row.size }.filter { it > 1e-8 }
    if (energies.isEmpty()) return 0.0
    val gate = energies.sorted()[energies.size / 3]
    return sqrt(energies.filter { it >= gate }.average())
}

fun analyzeEqCapture(samples: ShortArray, sampleRate: Int): EqAudioMetrics {
    if (samples.isEmpty()) return EqAudioMetrics(-120f, -120f, 0f, -120f, 0, 0f, List(8) { -120f })
    val doubles = DoubleArray(samples.size) { samples[it] / 32768.0 }
    val peak = doubles.maxOf(::abs).coerceAtLeast(1e-9)
    val frame = (sampleRate / 50).coerceAtLeast(64)
    val frameRms = doubles.asList().chunked(frame).map { row -> sqrt(row.sumOf { it * it } / row.size) }
    val noise = frameRms.sorted()[max(0, frameRms.size / 10 - 1)].coerceAtLeast(1e-9)
    val threshold = max(noise * 3.0, 10.0.pow(-42.0 / 20.0))
    val active = frameRms.filter { it >= threshold }
    val rms = (active.average().takeUnless(Double::isNaN) ?: 1e-9).coerceAtLeast(1e-9)
    return EqAudioMetrics((20 * log10(peak)).toFloat(), (20 * log10(rms)).toFloat(),
        (20 * log10(peak / rms)).toFloat(), (20 * log10(noise)).toFloat(), doubles.count { abs(it) >= .999 },
        active.size * frame.toFloat() / sampleRate, bandEnergy(doubles, sampleRate))
}

private fun waveform(samples: ShortArray, columns: Int = 240): List<Float> {
    if (samples.isEmpty()) return emptyList()
    val width = max(1, samples.size / columns)
    return samples.asList().chunked(width).take(columns).map { row -> row.maxOf { abs(it.toInt()) } / 32768f }
}

private fun bandEnergy(samples: DoubleArray, sampleRate: Int): List<Float> = EQ_FREQUENCIES_HZ.map { center ->
    goertzelDb(samples, sampleRate, center)
}

private fun goertzelDb(samples: DoubleArray, sampleRate: Int, frequency: Int): Float {
    val limit = minOf(samples.size, 16_384)
    if (limit == 0) return -120f
    val coefficient = 2.0 * cos(2.0 * PI * frequency / sampleRate)
    var s0: Double; var s1 = 0.0; var s2 = 0.0
    for (i in 0 until limit) { s0 = samples[i] + coefficient * s1 - s2; s2 = s1; s1 = s0 }
    val power = (s1 * s1 + s2 * s2 - coefficient * s1 * s2).coerceAtLeast(1e-12) / (limit * limit)
    return (10 * log10(power)).toFloat().coerceAtLeast(-120f)
}

private fun averagedSpectrum(samples: DoubleArray, sampleRate: Int, points: Int = 96): List<Float> {
    if (samples.isEmpty()) return emptyList()
    return List(points) { index ->
        val ratio = index.toDouble() / (points - 1)
        val frequency = 50.0 * (4000.0 / 50.0).pow(ratio)
        goertzelDb(samples, sampleRate, frequency.toInt())
    }
}

private fun responseCurve(sampleRate: Int, gains: List<Int>, points: Int = 96): List<Float> {
    val impulse = ShortArray(16_384).apply { this[0] = Short.MAX_VALUE }
    val response = applyApproximateKx3Eq(impulse, sampleRate, gains)
    val raw = averagedSpectrum(response, sampleRate, points)
    val zero = raw.maxOrNull() ?: 0f
    return raw.map { it - zero }
}
