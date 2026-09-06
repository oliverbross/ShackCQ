package app.shackcq.mobile

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class PanadapterPixelBucket(val meanDb: Float, val highDb: Float, val displayDb: Float)

/** Exact non-overlapping bin reduction shared by spectrum and waterfall presentation. */
internal fun reducePanadapterBuckets(values: FloatArray, firstBin: Int, lastBinExclusive: Int, pixels: Int): List<PanadapterPixelBucket> {
    if (values.isEmpty() || pixels <= 0) return emptyList()
    val first = firstBin.coerceIn(0, values.size)
    val last = lastBinExclusive.coerceIn(first, values.size)
    if (last <= first) return emptyList()
    return List(pixels) { pixel ->
        val start = first + ((last - first).toLong() * pixel / pixels).toInt()
        val end = first + ((last - first).toLong() * (pixel + 1) / pixels).toInt()
        val boundedEnd = max(start + 1, end).coerceAtMost(last)
        var linear = 0.0
        var high = -140f
        for (index in start until boundedEnd) {
            linear += 10.0.pow(values[index] / 10.0)
            high = max(high, values[index])
        }
        val meanDb = (10.0 * log10((linear / (boundedEnd - start)).coerceAtLeast(1.0e-14))).toFloat()
        // A bounded blend preserves a one-bin CW signal without turning every noise bucket into its maximum.
        val displayDb = 10.0 * log10(0.75 * 10.0.pow(meanDb / 10.0) + 0.25 * 10.0.pow(high / 10.0))
        PanadapterPixelBucket(meanDb, high, displayDb.toFloat())
    }
}

internal data class PanadapterAnalysisResult(
    val metrics: PanadapterDisplayMetrics,
    val validMask: BooleanArray,
)

/** Stable valid-band, robust-level, occupancy, and periodic-spur analysis; called off the UI thread. */
internal class PanadapterDisplayAnalyzer {
    private var profile = FloatArray(0)
    private var mask = BooleanArray(0)
    private var frames = 0
    private var stabilizedFloor = Float.NaN
    private var waterfallBlack = Float.NaN
    private var waterfallTop = Float.NaN
    private var lastCombSpacing = Float.NaN
    private var combPersistence = 0f

    fun reset() {
        profile = FloatArray(0); mask = BooleanArray(0); frames = 0
        stabilizedFloor = Float.NaN; waterfallBlack = Float.NaN; waterfallTop = Float.NaN
        lastCombSpacing = Float.NaN; combPersistence = 0f
    }

    fun analyze(values: FloatArray, sampleRate: Int, settings: PanadapterSettings): PanadapterAnalysisResult {
        if (values.isEmpty() || sampleRate <= 0) return PanadapterAnalysisResult(PanadapterDisplayMetrics(), BooleanArray(0))
        if (profile.size != values.size) {
            profile = values.copyOf(); mask = BooleanArray(values.size) { true }; frames = 0
        }
        frames++
        values.indices.forEach { index -> profile[index] += .015f * (values[index] - profile[index]) }
        val edgeGuard = max(4, values.size / 50)
        mask.fill(true)
        for (index in 0 until edgeGuard) { mask[index] = false; mask[mask.lastIndex - index] = false }
        val dcGuard = max(3, settings.centerMaskBins)
        val center = values.size / 2
        for (index in (center - dcGuard).coerceAtLeast(0)..(center + dcGuard).coerceAtMost(mask.lastIndex)) mask[index] = false

        // Only stable, contiguous edge cliffs are removed. Signals inside the passband cannot make this mask breathe.
        if (frames >= 60) {
            val reference = percentile(profile.filterIndexed { index, _ -> index in edgeGuard until values.size - edgeGuard }.toFloatArray(), .50f)
            val cliff = reference - 18f
            var left = edgeGuard
            while (left < center - dcGuard && profile[left] < cliff) { mask[left] = false; left++ }
            var right = values.size - edgeGuard - 1
            while (right > center + dcGuard && profile[right] < cliff) { mask[right] = false; right-- }
        }

        val valid = values.filterIndexed { index, value -> mask[index] && value.isFinite() }.toFloatArray()
        if (valid.size < values.size / 4) {
            return PanadapterAnalysisResult(PanadapterDisplayMetrics(validBinCount = valid.size,
                validBinFraction = valid.size.toFloat() / values.size,
                state = PanadapterDisplayState.INSUFFICIENT_VALID_BINS), mask.copyOf())
        }
        valid.sort()
        val rawFloor = percentile(valid, .35f)
        stabilizedFloor = if (!stabilizedFloor.isFinite()) rawFloor else {
            val alpha = if (rawFloor > stabilizedFloor) .18f else .018f
            stabilizedFloor + alpha * (rawFloor - stabilizedFloor)
        }
        val median = percentile(valid, .50f)
        val high = percentile(valid, .985f)
        val peak = valid.last()
        val spectrumFloor = (stabilizedFloor - 6f).coerceIn(-140f, -35f)
        val spectrumTop = max(stabilizedFloor + 46f, high + 5f).coerceIn(spectrumFloor + 40f, 10f)
        val targetBlack = (stabilizedFloor + 1.5f).coerceIn(-140f, -35f)
        val targetTop = max(targetBlack + 48f, high + 4f).coerceAtMost(10f)
        waterfallBlack = approach(waterfallBlack, targetBlack, .04f, .012f)
        waterfallTop = approach(waterfallTop, targetTop, .10f, .025f).coerceAtLeast(waterfallBlack + 40f)
        val below = valid.count { it < waterfallBlack }.toFloat() / valid.size
        val saturated = valid.count { it >= waterfallTop }.toFloat() / valid.size
        val useful = 1f - below - saturated

        val invalid = values.filterIndexed { index, value -> !mask[index] && value.isFinite() }.toFloatArray()
        val invalidMedian = if (invalid.isEmpty()) Float.NaN else percentile(invalid.sortedArray(), .50f)
        val inBandRatio = if (invalidMedian.isFinite()) median - invalidMedian else Float.NaN
        val comb = detectComb(values, mask, stabilizedFloor, sampleRate)
        val mirror = detectMirrorImages(values, mask, stabilizedFloor)
        if (comb.isFinite()) {
            val matching = lastCombSpacing.isFinite() && abs(comb - lastCombSpacing) <= sampleRate.toFloat() / values.size * 2f
            combPersistence = if (matching) (combPersistence + .08f).coerceAtMost(1f) else .1f
            lastCombSpacing = comb
        } else combPersistence *= .94f
        val state = if (saturated > .02f || useful < .15f) PanadapterDisplayState.SATURATED else PanadapterDisplayState.HEALTHY
        return PanadapterAnalysisResult(PanadapterDisplayMetrics(
            rawFloorDb = rawFloor, stabilizedFloorDb = stabilizedFloor,
            spectrumFloorDb = spectrumFloor, spectrumTopDb = spectrumTop,
            waterfallBlackDb = waterfallBlack, waterfallTopDb = waterfallTop,
            fractionBelowBlack = below, usefulColorFraction = useful,
            waterfallSaturatedFraction = saturated,
            validBinFraction = valid.size.toFloat() / values.size, validBinCount = valid.size,
            medianDb = median, highPercentileDb = high, peakToFloorDb = peak - stabilizedFloor,
            inBandToInvalidPowerDb = inBandRatio, combSpacingHz = lastCombSpacing,
            combPersistence = combPersistence, mirrorRejectionDb = mirror.first,
            mirrorPairCount = mirror.second, state = state,
        ), mask.copyOf())
    }

    private fun approach(value: Float, target: Float, attack: Float, release: Float): Float {
        if (!value.isFinite()) return target
        val alpha = if (target > value) attack else release
        return value + alpha * (target - value)
    }

    private fun percentile(sorted: FloatArray, fraction: Float): Float {
        if (sorted.isEmpty()) return Float.NaN
        return sorted[((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun detectComb(values: FloatArray, valid: BooleanArray, floor: Float, sampleRate: Int): Float {
        val peaks = ArrayList<Int>()
        for (index in 2 until values.size - 2) {
            if (valid[index] && values[index] > floor + 12f && values[index] >= values[index - 1] && values[index] > values[index + 1]) peaks += index
        }
        if (peaks.size < 5) return Float.NaN
        val gaps = peaks.zipWithNext { a, b -> b - a }.filter { it > 1 }.sorted()
        if (gaps.size < 4) return Float.NaN
        val medianGap = gaps[gaps.size / 2]
        val consistent = gaps.count { abs(it - medianGap) <= 1 }
        return if (consistent >= 4) medianGap * sampleRate.toFloat() / values.size else Float.NaN
    }

    private fun detectMirrorImages(values: FloatArray, valid: BooleanArray, floor: Float): Pair<Float, Int> {
        val centre = values.size / 2
        val rejection = ArrayList<Float>()
        for (offset in 4 until centre - 4) {
            val positive = centre + offset
            val negative = centre - offset
            if (positive !in values.indices || negative !in values.indices || !valid[positive] || !valid[negative]) continue
            val positivePeak = values[positive] >= values[positive - 1] && values[positive] > values[positive + 1]
            val negativePeak = values[negative] >= values[negative - 1] && values[negative] > values[negative + 1]
            if ((positivePeak || negativePeak) && max(values[positive], values[negative]) > floor + 12f) {
                rejection += abs(values[positive] - values[negative])
            }
        }
        if (rejection.isEmpty()) return Float.NaN to 0
        rejection.sort()
        return rejection[rejection.size / 2] to rejection.size
    }
}
