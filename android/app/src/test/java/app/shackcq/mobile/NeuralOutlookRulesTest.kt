package app.shackcq.mobile

import app.shackcq.mobile.hamclock.HamClockMapLayerId
import app.shackcq.mobile.hamclock.HamClockModuleRenderer
import app.shackcq.mobile.hamclock.hamClockMapLayerRegistry
import app.shackcq.mobile.hamclock.hamClockModuleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NeuralOutlookRulesTest {
    @Test fun canonicalBandsRemainCompleteAndOrdered() {
        assertEquals(listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m",
            "6m", "4m", "2m", "70cm", "23cm", "3cm"), NEURAL_OUTLOOK_BANDS)
    }

    @Test fun utcMatchingUsesTargetQuarterHourAndWrapsMidnight() {
        val target = Instant.parse("2026-08-21T00:00:00Z").epochSecond
        assertEquals(1, utcQuarterHourDistance(target, target - 15 * 60))
        assertEquals(2, utcQuarterHourDistance(target, target + 30 * 60))
        assertEquals(24, utcQuarterHourDistance(target, target + 6 * 60 * 60))
    }

    @Test fun insufficientBaselineNeverBecomesAQuietClaim() {
        assertEquals(OutlookLabel.INSUFFICIENT_EVIDENCE, ShackCQEmpiricalOutlookV1.label(99, false, false))
        assertEquals(OutlookLabel.DEGRADED, ShackCQEmpiricalOutlookV1.label(80, true, true))
        assertEquals(OutlookLabel.STRONG, ShackCQEmpiricalOutlookV1.label(80, true, false))
    }

    @Test fun qsoHistoryCannotChangeLiveContextSupport() {
        val base = NeuralOutlookInput("station", "OM0RX", "JN88TQ", 1, emptyList(), emptyMap(), sfi = 130.0)
        assertEquals(ShackCQEmpiricalOutlookV1.contextAdjustment(base, "20m"),
            ShackCQEmpiricalOutlookV1.contextAdjustment(base.copy(qsoSummary = NeuralLogSummary(qsos = 1_000_000)), "20m"))
    }

    @Test fun calibrationGateKeepsPercentagesNullUntilBothThresholdsPass() {
        assertNull(calibratedOutlookRate(39, 20, 10))
        assertNull(calibratedOutlookRate(80, 14, 10))
        assertEquals(65, calibratedOutlookRate(80, 15, 10))
    }

    @Test fun providerOutageIsUnverifiableAndValidAbsenceIsMiss() {
        assertEquals(OutlookVerification.UNVERIFIABLE, outlookVerification(0, 0, false))
        assertEquals(OutlookVerification.MISS, outlookVerification(0, 0, true))
        assertEquals(OutlookVerification.HIT, outlookVerification(2, 1, true))
        assertEquals(OutlookVerification.HIT, outlookVerification(1, 2, true))
    }

    @Test fun terrestrialAndMicrowaveBandsRequireRealEvidence() {
        assertFalse(outlookBandSupported("2m", 20, 1, 3))
        assertTrue(outlookBandSupported("2m", 8, 2, 1))
        assertFalse(outlookBandSupported("3cm", 40, 3, 4))
        assertFalse(outlookBandSupported("3cm", 40, 5, 1))
        assertTrue(outlookBandSupported("3cm", 16, 4, 2))
    }

    @Test fun persistenceRequiresSupportedGlobalObservedEvidence() {
        val eligible = forecast()
        assertTrue(outlookPersistenceEligible(eligible))
        assertFalse(outlookPersistenceEligible(eligible.copy(label = OutlookLabel.INSUFFICIENT_EVIDENCE)))
        assertFalse(outlookPersistenceEligible(eligible.copy(row = 2, column = 4)))
        assertFalse(outlookPersistenceEligible(eligible.copy(contributingSources = emptySet())))
        assertFalse(outlookPersistenceEligible(eligible.copy(band = "2m", currentObservations = 1)))
    }

    @Test fun persistenceSlotsDeduplicateFiveMinuteCalculations() {
        val first = Instant.parse("2026-08-21T10:01:00Z").epochSecond
        val second = Instant.parse("2026-08-21T10:14:59Z").epochSecond
        assertEquals(Instant.parse("2026-08-21T10:15:00Z").epochSecond, outlookPersistenceSlot(first))
        assertEquals(outlookPersistenceSlot(first), outlookPersistenceSlot(second))
    }

    @Test fun exactEvidenceKeysAreUnionedAndBounded() {
        assertEquals("A,B,C", mergeOutlookKeys("C,A,A", "B,C"))
        val many = (30 downTo 1).joinToString(",") { "K%02d".format(it) }
        val merged = mergeOutlookKeys(many).split(',')
        assertEquals(NEURAL_OUTLOOK_KEY_CAP, merged.size)
        assertEquals(merged.distinct(), merged)
        assertEquals(merged.sorted(), merged)
    }

    @Test fun verificationUsesExactCallUnionAndContributingCoverage() {
        assertEquals(OutlookVerification.MISS, verifyOutlookEvidence(
            mapOf("CLUSTER" to setOf("A")), setOf("CLUSTER"), setOf("CLUSTER")))
        assertEquals(OutlookVerification.UNVERIFIABLE, verifyOutlookEvidence(
            mapOf("CLUSTER" to setOf("A")), setOf("CLUSTER"), setOf("PSK_REPORTER")))
        assertEquals(OutlookVerification.HIT, verifyOutlookEvidence(
            mapOf("CLUSTER" to setOf("A"), "PSK_REPORTER" to setOf("A")),
            setOf("CLUSTER", "PSK_REPORTER"), setOf("CLUSTER")))
    }

    @Test fun selectedCalibrationUsesExactWindowAndBand() {
        val values = listOf(
            forecast().copy(window = OutlookWindow.MINUTES_30, band = "10m", calibrationSamples = 31),
            forecast().copy(window = OutlookWindow.MINUTES_60, band = "20m", calibrationSamples = 62),
        )
        assertEquals(62, selectedOutlookForecast(values, OutlookWindow.MINUTES_60, "20m")?.calibrationSamples)
        assertNull(selectedOutlookForecast(values, OutlookWindow.MINUTES_120, "20m"))
    }

    @Test fun homeAndMapRegistriesShareOneBoundedNonCatOutlookContract() {
        assertEquals(HamClockModuleRenderer.NEURAL_OUTLOOK,
            hamClockModuleRegistry.single { it.id == app.shackcq.mobile.hamclock.HamClockPanelId.NEURAL_OUTLOOK }.renderer)
        val layer = hamClockMapLayerRegistry.single { it.id == HamClockMapLayerId.NEURAL_OUTLOOK }
        assertEquals(72, layer.maximumObjectCount)
        assertFalse(layer.defaultVisible)
        assertFalse(OutlookForecast::class.java.declaredFields.any { it.name.contains("frequency", true) || it.name.contains("cat", true) })
    }

    private fun forecast() = OutlookForecast(
        id = "test", window = OutlookWindow.MINUTES_60, targetStartEpoch = 1_000L, targetEndEpoch = 4_600L,
        band = "20m", modeFamily = "DIGITAL", row = -1, column = -1, supportScore = 60,
        label = OutlookLabel.FAVOURABLE, confidence = OutlookConfidence.MEDIUM, calibratedHitRate = null,
        calibrationSamples = 0, sourceCount = 1, baselineSamples = 8, reasons = listOf("test"), generatedEpoch = 900L,
        currentObservations = 1, contributingSources = setOf("CLUSTER"),
    )
}
