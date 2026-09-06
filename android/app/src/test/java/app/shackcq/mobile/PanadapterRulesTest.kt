package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanadapterRulesTest {
    @Test fun settingsRoundTripAndValidationAreVersionedAndBounded() {
        val settings = PanadapterSettings(fftSize = 8_192, overlapPercent = 75, zoomDecimation = 8,
            requestedRate = 48_000, swapIq = true, iqCorrectionEnabled = true, iqBReal = -.08f,
            autoLevel = true, levelAttack = .22f, waterfallAverageFrames = 7,
            measuredFlatnessEnabled = true, measuredFlatnessOffsetsCsv = "0,12000,48000",
            measuredFlatnessGainsCsv = "0,1,4", measuredFlatnessDeviceKey = "usb-audio",
            measuredFlatnessRate = 48_000, measuredFlatnessRadio = "KX3", measuredFlatnessEpochMs = 42,
            levelCalibrationEnabled = true, dbfsToDbmOffset = 31.5f,
            levelCalibrationFrequencyHz = 14_074_000, levelCalibrationDeviceKey = "usb-audio")
        assertEquals(settings.validated(), PanadapterSettings.decode(settings.encode()))
        val invalid = PanadapterSettings(fftSize = 32_768, overlapPercent = 10,
            requestedRate = 44_100, zoomDecimation = 3, qsyStepHz = 7).validated()
        assertEquals(4_096, invalid.fftSize)
        assertEquals(50, invalid.overlapPercent)
        assertEquals(48_000, invalid.requestedRate)
        assertEquals(1, invalid.zoomDecimation)
        assertEquals(10, invalid.qsyStepHz)
    }

    @Test fun newDefaultIs48kAndExplicitRatesRoundTrip() {
        assertEquals(48_000, PanadapterSettings().requestedRate)
        assertEquals(48_000, PanadapterSettings.decode(null).requestedRate)
        assertEquals(48_000, PanadapterSettings.decode("v=2;fft=4096").requestedRate)
        assertEquals(48_000, PanadapterSettings.decode(PanadapterSettings(requestedRate = 48_000).encode()).requestedRate)
        assertEquals(96_000, PanadapterSettings.decode(PanadapterSettings(requestedRate = 96_000).encode()).requestedRate)
    }

    @Test fun staleCatCenterIsHiddenAndQsyIsBlocked() {
        val live = RadioState(connected = true, model = "KX3", frequencyHz = 14_074_000,
            effectiveRxHz = 14_074_000, updatedMonotonicMs = 10_000)
        assertEquals(14_074_000, effectivePanadapterCenter(live, 11_000))
        assertTrue(canPanadapterQsy(live, effectivePanadapterCenter(live, 11_000)))
        assertEquals(0, effectivePanadapterCenter(live, 13_000))
        assertFalse(canPanadapterQsy(live, effectivePanadapterCenter(live, 13_000)))
    }

    @Test fun standaloneStereoIqCaptureDoesNotRequireCat() {
        assertEquals(null, panadapterCaptureRadioBlocker(RadioState(connected = false)))
        assertEquals(null, panadapterCaptureRadioBlocker(RadioState(connected = true, model = "KX3")))
        assertEquals("Receive I/Q cannot start while the KX3 is transmitting",
            panadapterCaptureRadioBlocker(RadioState(connected = true, model = "KX3", transmitting = true)))
        assertEquals("Connected CAT radio is not a KX3; disconnect CAT to use standalone relative I/Q",
            panadapterCaptureRadioBlocker(RadioState(connected = true, model = "OTHER")))
    }

    @Test fun legacyDisplayDefaultsMigrateToRfHonestSettings() {
        val migrated = PanadapterSettings.decode(
            "v=1;rate=96000;flatness=true;auto_level=false;wf_min=-120;wf_max=-45",
        )
        assertFalse(migrated.genericKx3Flatness)
        assertTrue(migrated.autoLevel)
        assertEquals(-110f, migrated.waterfallMinDb)
        assertEquals(-55f, migrated.waterfallMaxDb)
        assertTrue(migrated.encode().startsWith("v=2;"))
    }

    @Test fun measuredFlatnessIsValidatedSymmetricAndInterpolated() {
        val settings = PanadapterSettings(measuredFlatnessOffsetsCsv = "0,12000,48000",
            measuredFlatnessGainsCsv = "0,1,4")
        val points = parseMeasuredFlatness(settings)
        assertEquals(3, points.size)
        assertEquals(2f, measuredFlatnessCorrection(points, 24_000f), .001f)
        assertEquals(2f, measuredFlatnessCorrection(points, -24_000f), .001f)
        assertTrue(parseMeasuredFlatness(settings.copy(measuredFlatnessOffsetsCsv = "100,200")).isEmpty())
    }

    @Test fun routeProofRequiresExactPreferredStereoProductionFormat() {
        fun proof(clientRate: Int = 96_000, deviceRate: Int = 96_000, actual: String = "fingerprint",
            clientChannels: Int = 2, deviceChannels: Int = 2, available: Boolean = true) = PanadapterRouteProof(
            requestedDevice = "fingerprint", preferredAccepted = true, actualDevice = actual,
            requestedRate = 96_000, configuredRate = clientRate, configuredChannels = clientChannels,
            encoding = 2, bufferFrames = 4096, clientRate = clientRate, clientChannels = clientChannels,
            clientEncoding = 2, deviceRate = deviceRate, deviceChannels = deviceChannels,
            deviceEncoding = 2, activeConfigurationAvailable = available, activeDevice = actual)
        assertEquals(PanadapterFormatState.TRUE_96K_STEREO, proof().state)
        assertEquals(PanadapterFormatState.RESAMPLED_48_TO_96, proof(deviceRate = 48_000).state)
        assertEquals(PanadapterFormatState.TRUE_48K_STEREO, proof(clientRate = 48_000, deviceRate = 48_000).state)
        assertEquals(PanadapterFormatState.ROUTE_UNPROVEN, proof(actual = "other").state)
        assertEquals(PanadapterFormatState.ROUTE_PROVEN_FORMAT_PENDING, proof(available = false).state)
        assertEquals(PanadapterFormatState.UNSUPPORTED_MONO_OR_CONVERTED_CHANNEL_PATH,
            proof(clientChannels = 1, deviceChannels = 1).state)
        assertTrue(proof().verified)
        assertFalse(proof(deviceRate = 48_000).verified)
    }

    @Test fun validBandAnalysisRejectsDarkEdgesAndFlagsSaturation() {
        val analyzer = PanadapterDisplayAnalyzer()
        val values = FloatArray(1024) { index -> if (index in 256 until 768) -78f else -138f }
        // Stable profile detection deliberately requires multiple frames so signals cannot move the mask.
        var result = analyzer.analyze(values, 96_000, PanadapterSettings())
        repeat(70) { result = analyzer.analyze(values, 96_000, PanadapterSettings()) }
        assertTrue(result.metrics.validBinFraction in .45f..0.55f)
        assertTrue(result.metrics.stabilizedFloorDb > -90f)
        assertTrue(result.metrics.inBandToInvalidPowerDb > 40f)
        assertFalse(result.validMask[100])
        assertTrue(result.validMask[500])
    }

    @Test fun nonOverlappingReductionPreservesWeakAndStrongNarrowSignals() {
        val values = FloatArray(16) { -100f }
        values[3] = -94f
        values[12] = -60f
        val buckets = reducePanadapterBuckets(values, 0, values.size, 4)
        assertEquals(4, buckets.size)
        assertTrue(buckets[0].displayDb > -99f)
        assertTrue(buckets[3].highDb > -61f)
        assertTrue(buckets[1].highDb < -99f)
    }

    @Test fun periodicCombIsReportedWithoutSuppressingItsPeaks() {
        val analyzer = PanadapterDisplayAnalyzer()
        val values = FloatArray(1_024) { -100f }
        for (index in 64 until values.size - 64 step 64) values[index] = -60f
        var result = analyzer.analyze(values, 48_000, PanadapterSettings())
        repeat(70) { result = analyzer.analyze(values, 48_000, PanadapterSettings()) }
        assertEquals(3_000f, result.metrics.combSpacingHz, 50f)
        assertTrue(result.metrics.combPersistence > .5f)
        assertEquals(-60f, values[512], .001f)
    }

    @Test fun strongSymmetricPeaksAreReportedAsDominantMirrorImages() {
        val analyzer = PanadapterDisplayAnalyzer()
        val values = FloatArray(1_024) { -100f }
        for (offset in 64..384 step 64) {
            values[512 - offset] = -60f
            values[512 + offset] = -60.2f
        }
        var result = analyzer.analyze(values, 48_000, PanadapterSettings())
        repeat(70) { result = analyzer.analyze(values, 48_000, PanadapterSettings()) }
        assertTrue(result.metrics.mirrorPairCount >= 6)
        assertTrue(result.metrics.mirrorRejectionDb < 1f)
    }

    @Test fun passbandUsesModeDirectionBandwidthAndShift() {
        val usb = panadapterPassband(RadioState(mode = "USB", bandwidthHz = 2_700, ifShiftHz = 1_500))
        assertEquals(150f, usb.lowOffsetHz)
        assertEquals(2_850f, usb.highOffsetHz)
        val lsb = panadapterPassband(RadioState(mode = "LSB", bandwidthHz = 2_700, ifShiftHz = 1_500))
        assertEquals(-2_850f, lsb.lowOffsetHz)
        assertEquals(-150f, lsb.highOffsetHz)
        val am = panadapterPassband(RadioState(mode = "AM", bandwidthHz = 6_000, ifShiftHz = -1))
        assertEquals(-3_000f, am.lowOffsetHz)
        assertEquals(3_000f, am.highOffsetHz)
    }

    @Test fun tuneRoundingAndWaterfallReframeThresholdAreDeterministic() {
        assertEquals(14_074_120L, roundedPanadapterFrequency(14_074_116L, 10))
        assertFalse(isMaterialCenterChange(14_074_000, 14_074_005, 12f))
        assertTrue(isMaterialCenterChange(14_074_000, 14_074_050, 12f))
    }
}
