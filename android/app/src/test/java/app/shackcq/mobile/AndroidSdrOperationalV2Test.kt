package app.shackcq.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSdrOperationalV2Test {
    @Test fun recordPolicyClampsEveryStorageAndDurationBound() {
        val policy = RecordOnHitPolicy(-1, 99, 0, 1, Long.MAX_VALUE).validated()
        assertEquals(0, policy.preRollSeconds)
        assertEquals(30, policy.postRollSeconds)
        assertEquals(1, policy.maximumDurationSeconds)
        assertEquals(1L shl 20, policy.dailyBytes)
        assertEquals(2L shl 30, policy.totalBytes)
    }

    @Test fun latestWriteWinsIsGenerationSafeAndNeverReplaysStaleValues() {
        val queue = LatestTciWriteQueue()
        val first = queue.offer("0:frequency", 4, "vfo:0,0,14070000;")
        val last = queue.offer("0:frequency", 4, "vfo:0,0,14074000;")
        assertNull(queue.takeIfLatest(first, 4))
        assertNull(queue.takeIfLatest(last, 5))
        val current = queue.offer("0:frequency", 5, "vfo:0,0,14075000;")
        assertEquals("vfo:0,0,14075000;", queue.takeIfLatest(current, 5))
        assertEquals(0, queue.size())
    }

    @Test fun linkedModesTargetOnlyThePeerAndNeverCreateTransmitActions() {
        val receivers = listOf(
            TciReceiverSnapshot("tci:0", 0, active = true),
            TciReceiverSnapshot("tci:1", 1),
        )
        val frequency = linkedReceiverActions(ReceiverLinkMode.SAME_FREQUENCY_COMPARE, 0, 14_074_000, null, receivers)
        assertEquals(1, frequency.size)
        assertEquals(1, frequency.single().targetReceiver)
        assertEquals(RadioActionClass.SAFE_SET, frequency.single().actionClass)
        assertTrue(linkedReceiverActions(ReceiverLinkMode.INDEPENDENT, 0, 14_074_000, "DIGU", receivers).isEmpty())
        val mode = linkedReceiverActions(ReceiverLinkMode.MODE_LINKED, 0, null, "DIGU", receivers).single()
        assertEquals("mode", mode.name)
        assertTrue((frequency + mode).none { it.actionClass in setOf(RadioActionClass.TRANSMIT, RadioActionClass.TUNE) })
    }

    @Test fun stereoSplitMixesAAndBIntoIndependentBoundedChannels() {
        val settings = RxMixerSettings(RxMixerMode.STEREO_SPLIT,
            ReceiverMixSettings(gain = 1f, pan = -1f), ReceiverMixSettings(gain = 1f, pan = 1f), master = 1f)
        val mixed = mixTciStereo(floatArrayOf(.5f, 2f), floatArrayOf(-.25f, -2f), settings)
        assertArrayEquals(floatArrayOf(.25f, -.125f, 1f, -1f), mixed, .0001f)
        assertTrue(mixed.all { it in -1f..1f })
    }

    @Test fun mixerMuteSoloCrossfadeAndReceiverModesRemainDeterministic() {
        val source = floatArrayOf(.5f, -.5f)
        val onlyA = mixTciStereo(source, floatArrayOf(1f, 1f), RxMixerSettings(mode = RxMixerMode.RECEIVER_A, master = 1f))
        assertArrayEquals(floatArrayOf(.5f, 0f, -.5f, 0f), onlyA, .0001f)
        val soloB = mixTciStereo(source, source, RxMixerSettings(mode = RxMixerMode.MIX,
            receiverA = ReceiverMixSettings(solo = false), receiverB = ReceiverMixSettings(solo = true, pan = 0f), master = 1f, crossfade = 1f))
        assertArrayEquals(floatArrayOf(.25f, .25f, -.25f, -.25f), soloB, .0001f)
    }

    @Test fun resamplingRunsOnlyWhenRatesDifferAndIsBounded() {
        val source = floatArrayOf(0f, 1f, 0f, -1f)
        assertTrue(resampleTciAudio(source, 48_000, 48_000) === source)
        val doubled = resampleTciAudio(source, 24_000, 48_000)
        assertEquals(8, doubled.size)
        assertTrue(doubled.all(Float::isFinite))
    }

    @Test fun digitTuningAcceptsOnlyBoundedReceiveFrequencies() {
        assertEquals(14_074_000L, frequencyFromDigits("14.074.000"))
        assertNull(frequencyFromDigits("99"))
        assertEquals(14_075_000L, adjustFrequencyDigit(14_074_000, 1_000, 1))
        assertEquals(100_000L, adjustFrequencyDigit(100_000, 1_000_000, -1))
    }

    @Test fun skimmerCandidatesStayInsideCallingSegmentsAndLaneCaps() {
        val trace = FloatArray(1_024) { -120f }
        listOf(512, 516, 520, 524, 528, 532).forEachIndexed { index, bin -> trace[bin] = -50f + index }
        val frame = frame(trace, center = 14_072_500, rate = 20_000)
        val candidates = skimmerCandidates(frame, 14_072_500, SkimmerMode.PSK31, 4)
        assertTrue(candidates.size <= 4)
        assertTrue(candidates.all { it.frequencyHz in 14_070_000L..14_075_000L })
        assertTrue(candidates.all { !it.confirmed && it.callLikeToken == null })
        assertTrue(skimmerCandidates(frame, 14_050_000, SkimmerMode.PSK31, 4).isEmpty())
    }

    @Test fun falsePositiveFlatNoiseNeverBecomesAConfirmedStation() {
        val frame = frame(FloatArray(2_048) { -95f }, center = 14_072_500, rate = 10_000)
        assertTrue(skimmerCandidates(frame, 14_072_500, SkimmerMode.PSK31).isEmpty())
    }

    @Test fun iqLaneConversionIsFiniteAndBounded() {
        val iq = FloatArray(9_600) { index -> if (index % 2 == 0) .5f else -.25f }
        val audio = iqLaneAudio(iq, 96_000, 14_071_000, 14_070_000, 48_000)
        assertEquals(2_400, audio.size)
        assertTrue(audio.all(Float::isFinite))
    }

    @Test fun scanBankValidationAndTxLevelsAreSafe() {
        val bank = ScanBank("x", "", memories = List(2_100) { ScanMemory(1, "invented", 1) },
            thresholdDb = 40f, dwellMillis = 1, recordOnHit = RecordOnHitMode.IQ).validated()
        assertEquals(2_000, bank.memories.size)
        assertEquals(100L, bank.dwellMillis)
        assertEquals(0f, bank.thresholdDb)
        assertTrue(bank.memories.all { it.frequencyHz >= 100_000 && it.filterHz >= 50 })
        val inherited = resolveTxAudioLevel("USB", 2f, emptyMap())
        assertTrue(inherited.inherited)
        assertEquals(1f, inherited.level)
        val override = resolveTxAudioLevel("DIGU", .5f, mapOf("DIGU" to -.4f))
        assertFalse(override.inherited)
        assertEquals(0f, override.level)
        assertFalse(TxCalibrationSnapshot("USB", .5f, false, "fake", "fake").sendEnabled)
    }

    private fun frame(trace: FloatArray, center: Long, rate: Int) = PanadapterFrame(
        sequence = 1, inputFrames = 1, transforms = 1, discontinuities = 0, sampleRate = rate,
        effectiveSampleRate = rate, fftSize = trace.size, hopSize = trace.size / 2, zoomDecimation = 1,
        zoomOffsetHz = 0f, enbwBins = 1f, rbwHz = rate.toFloat() / trace.size, peakDb = trace.maxOrNull() ?: -120f,
        floorDb = -120f, iRmsDb = -30f, qRmsDb = -30f, iqCorrelation = 0f, clippedFraction = 0f,
        duplicateCorrelation = 0f, validStereo = true, trace = trace, waterfall = trace.copyOf(), peakHold = trace.copyOf(),
    )
}
