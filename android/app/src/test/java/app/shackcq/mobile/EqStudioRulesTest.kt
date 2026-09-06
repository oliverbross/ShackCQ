package app.shackcq.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

class EqStudioRulesTest {
    @Test fun curvesRequireExactlyEightBoundedIntegerBands() {
        assertEquals(EqCurve.FLAT, EqCurve.of(List(8) { 0 }))
        listOf(emptyList(), List(7) { 0 }, List(9) { 0 }, listOf(-17, 0, 0, 0, 0, 0, 0, 0),
            listOf(17, 0, 0, 0, 0, 0, 0, 0)).forEach { assertTrue(runCatching { EqCurve.of(it) }.isFailure) }
    }

    @Test fun txFormatterIsExactSignedFixedWidthAndBounded() {
        assertEquals("TE-16+00+16-02+03+00+01-01;", formatTxEqCommand(EqCurve.of(listOf(-16, 0, 16, -2, 3, 0, 1, -1))))
        assertEquals(27, formatTxEqCommand(EqCurve.FLAT).length)
    }

    @Test fun contextsAreSplitAwareAndNeverInventAvailability() {
        assertEquals(EqContext.TX_WIDEBAND, resolveEqContext(EqPath.TX, EqModeState("USB", "USB", 1, true, true)).first)
        assertEquals(EqContext.TX_SSB, resolveEqContext(EqPath.TX, EqModeState("USB", "LSB", 1, true, false)).first)
        assertEquals(EqContext.TX_INACTIVE, resolveEqContext(EqPath.TX, EqModeState("USB", "DATA", 1, true, false)).first)
        assertEquals(EqContext.RX_DATA, resolveEqContext(EqPath.RX, EqModeState("DATA", "USB", 0, false, false)).first)
        assertTrue(resolveEqContext(EqPath.TX, EqModeState("USB", "LSB", 1, true, false)).second.contains("VFO B"))
    }

    @Test fun dbFixtureRequiresExpectedBandExactGainAndCompleteFrame() {
        assertEquals(-16, parseEqDbResponse("IFjunk;DB0.05 -16;", 0).gainDb)
        assertEquals(0, parseEqDbResponse("DB1.60 +0;", 5).gainDb)
        assertEquals(8, parseEqDbResponse("DB3.20 +8;", 7).gainDb)
        assertTrue(runCatching { parseEqDbResponse("DB 100 +08;", 0) }.isFailure)
        assertTrue(runCatching { parseEqDbResponse("DB 50 +17;", 0) }.isFailure)
        assertTrue(runCatching { parseEqDbResponse("DB 50 +08", 0) }.isFailure)
    }

    @Test fun menuReadUsesSwitchMapAndAlwaysCloses() = runBlocking {
        val io = FakeEqIo(intArrayOf(-4, -3, -2, -1, 0, 1, 2, 3))
        val result = io.readKx3Eq(EqPath.RX, "KX3", "RV1.00", EqContext.RX_VOICE, "VFO A USB")
        assertEquals(listOf(-4, -3, -2, -1, 0, 1, 2, 3), result.snapshot.curve.values)
        assertEquals(KX3_EQ_SWITCH_COMMANDS, io.writes.filter { it.startsWith("SWT") })
        assertEquals("MN255;", io.writes.last())
        val failed = FakeEqIo(IntArray(8), failDbBand = 3)
        assertTrue(runCatching { failed.readKx3Eq(EqPath.RX, "KX3", null, EqContext.RX_VOICE, "USB") }.isFailure)
        assertEquals("MN255;", failed.writes.last())
    }

    @Test fun txWriteIsOneShotThenMenuReadbackAndNeverTransmits() = runBlocking {
        val original = EqCurve.FLAT
        val target = EqCurve.of(listOf(0, 1, 2, 3, 2, 1, 0, -1))
        val io = FakeEqIo(original.values.toIntArray())
        val result = io.applyKx3Eq(target, snapshot(EqPath.TX, original))
        assertTrue(result.failedBands.isEmpty())
        assertEquals(1, io.writes.count { it.startsWith("TE") })
        assertFalse(eqTraceContainsTransmissionCommand(result.trace))
        assertFalse(io.writes.any { it in setOf("TX;", "SWT16;", "SWH16;", "SWH35;") })
    }

    @Test fun rxWriteStepsOnlyTheRequiredDelta() = runBlocking {
        val original = EqCurve.FLAT
        val target = original.withBand(0, 2).withBand(1, -1)
        val io = FakeEqIo(original.values.toIntArray())
        val result = io.applyKx3Eq(target, snapshot(EqPath.RX, original))
        assertTrue(result.failedBands.isEmpty())
        assertEquals(2, io.writes.count { it == "UP;" })
        assertEquals(1, io.writes.count { it == "DN;" })
        assertFalse(io.writes.contains("SWH35;"))
    }

    @Test fun flatDspIsUnityAndBandBoostMovesCorrectDirection() {
        val rate = 48_000
        val tone = ShortArray(rate) { (12_000 * kotlin.math.sin(2.0 * Math.PI * 800 * it / rate)).toInt().toShort() }
        val flat = applyApproximateKx3Eq(tone, rate, EqCurve.FLAT)
        assertTrue(flat.indices.maxOf { abs(flat[it] - tone[it] / 32768.0) } < 1e-9)
        val boosted = applyApproximateKx3Eq(tone, rate, EqCurve.FLAT.withBand(4, 6))
        val cut = applyApproximateKx3Eq(tone, rate, EqCurve.FLAT.withBand(4, -6))
        assertTrue(rms(boosted) > rms(flat)); assertTrue(rms(cut) < rms(flat))
        assertFalse(boosted.any { it.isNaN() || it.isInfinite() })
    }

    @Test fun hardwareCaptureUsesDraftMinusVerifiedBaselineAndSafetyGain() {
        val baseline = EqCurve.of(List(8) { 4 })
        val samples = ShortArray(48_000) { if (it % 2 == 0) 32_000 else -32_000 }
        val capture = capture(samples, EqCaptureSource.KX3_OUTPUT, baseline)
        assertEquals(List(8) { 0 }, previewGainsFor(capture, baseline))
        assertEquals(List(8) { 2 }, previewGainsFor(capture, EqCurve.of(List(8) { 6 })))
        assertEquals(List(8) { -32 }, previewGainsFor(capture(samples, EqCaptureSource.KX3_OUTPUT, EqCurve.of(List(8) { 16 })),
            EqCurve.of(List(8) { -16 })))
        val preview = buildEqPreview(capture, EqCurve.of(List(8) { 16 }), false)
        assertTrue(preview.after.maxOf { abs(it.toInt()) } <= (Short.MAX_VALUE * .95).toInt())
        assertTrue(preview.safetyReductionDb < 0)
    }

    @Test fun matchedPreviewRmsIsCloseAndSilenceSpectrumIsFinite() {
        val samples = ShortArray(48_000) { (8_000 * kotlin.math.sin(2.0 * Math.PI * 1000 * it / 48_000)).toInt().toShort() }
        val preview = buildEqPreview(capture(samples, EqCaptureSource.RAW_REFERENCE, null), EqCurve.FLAT.withBand(5, 6), true)
        val before = rms(preview.before); val after = rms(preview.after)
        assertTrue(abs(before - after) / before < .05)
        assertTrue(analyzeEqCapture(ShortArray(20), 48_000).bandEnergyDb.all { it.isFinite() })
    }

    @Test fun assistantIsConservativeSmoothedAndNeverAppliesAnything() {
        val baseline = EqCurve.FLAT
        val metrics = EqAudioMetrics(-6f, -20f, 14f, -50f, 0, 8f, listOf(-35f, -30f, -24f, -20f, -18f, -23f, -28f, -35f))
        val suggestion = suggestEqCurve(baseline, metrics, EqIntent.DX_PILEUP)
        suggestion.curve.values.forEach { assertTrue(it in -6..4) }
        suggestion.curve.values.zipWithNext().forEach { assertTrue(abs(it.first - it.second) <= 6) }
        assertNotEquals(baseline, suggestion.curve)
        val low = suggestEqCurve(baseline, metrics.copy(usableSpeechSeconds = .2f, activeRmsDbfs = -60f), EqIntent.CLEAR_SSB)
        assertEquals(baseline, low.curve)
    }

    private fun snapshot(path: EqPath, curve: EqCurve) = EqSnapshot(path,
        if (path == EqPath.RX) EqContext.RX_VOICE else EqContext.TX_SSB, curve, "KX3", "RV1.00", "test", Instant.EPOCH)

    private fun capture(samples: ShortArray, source: EqCaptureSource, baseline: EqCurve?): EqCapture = EqCapture(samples, 48_000, source,
        EqContext.TX_SSB, baseline?.let { snapshot(EqPath.TX, it) }, "fixture", "MONO", Instant.EPOCH, EqInputProcessing.OFF,
        analyzeEqCapture(samples, 48_000))

    private fun rms(values: DoubleArray) = sqrt(values.sumOf { it * it } / values.size)
    private fun rms(values: ShortArray) = sqrt(values.sumOf { it.toDouble() * it } / values.size)

    private class FakeEqIo(values: IntArray, private val failDbBand: Int? = null) : EqCatIo {
        private val current = values.copyOf()
        val writes = mutableListOf<String>()
        private var selectedBand = 0
        override suspend fun query(command: String, expectedPrefix: String, timeoutMillis: Long): String = when (command) {
            "TQ;" -> "TQ0;"
            "MN;" -> "MN255;"
            "DB;" -> if (selectedBand == failDbBand) "DB MALFORMED;" else "DB ${frequency(selectedBand)} ${"%+03d".format(current[selectedBand])};"
            else -> error("unexpected query $command")
        }
        override suspend fun write(command: String) {
            writes += command
            KX3_EQ_SWITCH_COMMANDS.indexOf(command).takeIf { it >= 0 }?.let { selectedBand = it }
            if (command == "UP;") current[selectedBand]++
            if (command == "DN;") current[selectedBand]--
            if (command.startsWith("TE")) Regex("[+-]\\d{2}").findAll(command.removePrefix("TE")).map { it.value.toInt() }
                .forEachIndexed { index, value -> current[index] = value }
        }
        override suspend fun pause(milliseconds: Long) = Unit
        private fun frequency(index: Int): String = when (val hz = EQ_FREQUENCIES_HZ[index]) {
            1_600, 2_400, 3_200 -> "${hz / 1000.0}K"; else -> hz.toString()
        }
    }
}
