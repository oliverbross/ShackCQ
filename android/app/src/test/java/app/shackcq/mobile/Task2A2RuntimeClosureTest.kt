package app.shackcq.mobile

import app.shackcq.mobile.hamclock.HamClockMapLayerId
import app.shackcq.mobile.hamclock.HamClockMapPreference
import app.shackcq.mobile.hamclock.HamClockMapSelection
import app.shackcq.mobile.hamclock.defaultHamClockMapLayers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class Task2A2RuntimeClosureTest {
    @Test
    fun sharedCompoundRadioCommandRetainsEveryFieldOutsideHomeReview() {
        val raw = "FA00014074000;MD6;BW3000;"
        val parsed = parseGeneralRadioCommand(raw)

        assertEquals(raw, parsed.raw)
        assertEquals(14_074_000L, parsed.frequencyHz)
        assertTrue(parsed.raw.contains("MD6;"))
        assertTrue(parsed.raw.contains("BW3000;"))
    }

    @Test
    fun homeReceiveRequestHasOneReviewAndCancelDispatchesNothing() {
        val review = HomeReceiveTuneReview(14_074_000L, "FT8", "Home DX spot", "Receive only")
        val pending = listOf(review).single()
        val cancelled = decideHomeReceiveTune(pending, confirm = false)

        assertNull(cancelled.pending)
        assertNull(cancelled.dispatch)
    }

    @Test
    fun homeSatelliteCalculationAdvancesEpochWithoutProviderFetch() {
        val entry = SatelliteCatalogueEntry(25544, "ISS",
            SatelliteElements("TLE", "ISS", "one", "two"), 1)
        var propagations = 0
        fun calculate(epoch: Long) = calculateHomeSatellitePositions(listOf(entry), SatelliteObserver(0.0, 0.0),
            epoch, stale = false) { _, _, at ->
            propagations += 1
            SatelliteNativeResult.Success(OrbitalPoint(at, at.toDouble(), 20.0, 400.0, 0.0, 10.0, 0.0, 0.0))
        }

        val first = calculate(1_000).single()
        val second = calculate(1_045).single()
        assertEquals(2, propagations)
        assertEquals(1_045L, second.generatedAtEpoch)
        assertTrue(first.latitude != second.latitude)
    }

    @Test
    fun homeRefreshScopeNeverStartsLegacySatelliteWork() {
        assertFalse(NeuralDxRefreshScope.HOME.includesLegacySatelliteWork())
        assertFalse(NeuralDxRefreshScope.FULL_DX.includesLegacySatelliteWork())
    }

    @Test
    fun pskMapReferenceUsesReportIdentityInsteadOfDxSpotIdentity() {
        val report = SignalReport("VK3ABC", "QF22", -37.5, 145.0, 14_074_000L, "20m", "FT8", -12, 700, 1234)
        val point = requireNotNull(hamClockPskMapPoint(report, app.shackcq.mobile.hamclock.HamClockUnitSystem.METRIC))

        assertEquals(HamClockMapSelection.PSK_REPORT, point.selection)
        assertEquals(signalReportReference(report), point.contextId)
        assertFalse(point.contextId == report.callsign)
    }

    @Test
    fun visibleLayerHeaderCountsAllFourTruthCategories() {
        val visibleIds = setOf(HamClockMapLayerId.DE_STATION, HamClockMapLayerId.DX_SPOTS,
            HamClockMapLayerId.PSK_REPORTER, HamClockMapLayerId.MOON)
        val preference = HamClockMapPreference(layers = defaultHamClockMapLayers().map { it.copy(visible = it.id in visibleIds) })
        val statuses = mapOf(
            HamClockMapLayerId.DE_STATION to status(HamClockMapSourceState.CURRENT),
            HamClockMapLayerId.DX_SPOTS to status(HamClockMapSourceState.ERROR),
            HamClockMapLayerId.PSK_REPORTER to status(HamClockMapSourceState.EMPTY),
            HamClockMapLayerId.MOON to status(HamClockMapSourceState.UNAVAILABLE),
        )

        assertEquals(HamClockVisibleStatusCounts(1, 1, 1, 1), hamClockVisibleStatusCounts(preference, statuses))
    }

    @Test
    fun lateCurrentStyleSuccessClearsTimeoutButObsoleteSuccessDoesNot() {
        assertEquals(true to "", hamClockLateStyleSuccess(4, 4, "timed out"))
        assertEquals(false to "timed out", hamClockLateStyleSuccess(5, 4, "timed out"))
    }

    @Test
    fun ssnFailurePreservesCorePublicationAndParserChoosesLatestValidMonth() = runBlocking {
        var corePublished = false
        var ssnError = ""
        completeSolarRefresh(
            publishCore = { corePublished = true },
            refreshOptionalSunspot = { error("optional SSN offline") },
            reportSunspotFailure = { ssnError = it },
        )
        val parsed = parseLatestNoaaSunspot("""[
            {"time-tag":"2026-06","observed_swpc_ssn":70.0},
            {"time-tag":"2026-04","observed_swpc_ssn":60.0},
            {"time-tag":"2099-01","observed_swpc_ssn":900.0},
            {"time-tag":"2026-07","observed_swpc_ssn":72.5},
            {"time-tag":"2026-08","observed_swpc_ssn":1001.0}
        ]""", YearMonth.of(2026, 8))

        assertTrue(corePublished)
        assertTrue(ssnError.contains("optional SSN offline"))
        assertEquals(72.5f, parsed.first)
        assertEquals("2026-07", parsed.second)
    }

    private fun status(state: HamClockMapSourceState) = HamClockMapSourceStatus(state, 1, "test")
}
