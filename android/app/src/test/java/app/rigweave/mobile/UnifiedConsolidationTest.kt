package app.rigweave.mobile

import app.rigweave.mobile.hamclock.HamClockFeedState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedConsolidationTest {
    private fun source(path: String) = File("src/main/$path").readText()

    @Test fun providerStateAdapterPreservesDegradedTruth() {
        assertEquals(NeuralProviderState.LIVE, neuralProviderState(HamClockFeedState.LIVE))
        assertEquals(NeuralProviderState.CACHED, neuralProviderState(HamClockFeedState.CACHED))
        assertEquals(NeuralProviderState.STALE, neuralProviderState(HamClockFeedState.DEGRADED))
        assertEquals(NeuralProviderState.UNAVAILABLE, neuralProviderState(HamClockFeedState.UNAVAILABLE))
    }

    @Test fun directTuneGuardStopsAboveSixMetres() {
        assertTrue(dxDirectTuneAvailable(54_000_000))
        assertFalse(dxDirectTuneAvailable(54_000_001))
    }

    @Test fun unifiedBandContractRetainsShfAndRejectsGaps() {
        assertEquals("3cm", bandForFrequency(10_489_700_000))
        assertEquals("", bandForFrequency(2_400_000_000))
        assertEquals(16, insightBands.size)
    }

    @Test fun currentOpportunitiesRemainNativeScoreOrderedAndBounded() {
        val rows = (1..14).map { index -> AndroidDXSpot(
            id = "$index", callsign = "K$index", spotter = "SPOT", frequencyHz = 14_074_000,
            receivedEpoch = index.toLong(), band = "20m", mode = "FT8", country = "", continent = "",
            cqZone = 0, ituZone = 0, latitude = 0.0, longitude = 0.0, comment = "", score = 60 - index,
            confidence = 50, samples = 1, watchlisted = false, workedCountry = false, workedCall = false,
            workedBand = false, workedMode = false, workedBandMode = false, recentDupe = false,
            distanceKm = 0, bearingDegrees = 0, pathState = "", reason = "",
        ) }
        val result = buildCurrentOpportunities(rows)
        assertEquals(12, result.size)
        assertTrue(result.zipWithNext().all { (left, right) -> left.priority >= right.priority })
    }

    @Test fun noRefreshScopeStartsLegacySatelliteWork() {
        assertFalse(NeuralDxRefreshScope.HOME.includesLegacySatelliteWork())
        assertFalse(NeuralDxRefreshScope.FULL_DX.includesLegacySatelliteWork())
        assertTrue(NeuralDxRefreshScope.FULL_DX.stopsLegacySatelliteTicker())
    }

    @Test fun neuralDelegatesPskToSharedRepository() {
        val neural = source("java/app/rigweave/mobile/NeuralDxController.kt")
        assertTrue(neural.contains("publicProviders.pskReporter.refresh"))
        assertFalse(neural.contains("retrieve.pskreporter.info"))
    }

    @Test fun regionalWsprHasNoProductionRequestPath() {
        val neural = source("java/app/rigweave/mobile/NeuralDxController.kt")
        assertFalse(neural.contains("db1.wspr.live"))
        assertTrue(neural.contains("Regional WSPR.live unavailable by policy"))
    }

    @Test fun neuralAndOperationsShareSatelliteOwner() {
        val neural = source("java/app/rigweave/mobile/NeuralDxController.kt")
        assertTrue(neural.contains("private val satelliteOwner: SatelliteOperationsController"))
        assertFalse(neural.contains("celestrak.org"))
    }

    @Test fun legacySatelliteTickerCannotStart() {
        val neural = source("java/app/rigweave/mobile/NeuralDxController.kt")
        assertTrue(neural.contains("private fun ensureSatelliteTicker(point: GeoPoint) = Unit"))
        assertFalse(neural.contains("private var satelliteJob"))
    }

    @Test fun lightningRetainsQthSafeIdempotentShutdown() {
        val neural = source("java/app/rigweave/mobile/NeuralDxController.kt")
        assertTrue(neural.contains("if (closed) return"))
        assertTrue(neural.contains("activeLightningSocket === socket"))
        assertTrue(neural.contains("closeActiveLightningSocket()"))
    }

    @Test fun qsoProjectionAndGroupsIoRemainSeparated() {
        val qso = source("java/app/rigweave/mobile/QsoDatabase.kt")
        val main = source("java/app/rigweave/mobile/MainActivity.kt")
        val groups = source("java/app/rigweave/mobile/groupsio/GroupsIoFeature.kt")
        assertTrue(qso.contains("SQLiteOpenHelper(context, databaseName, null, 17)"))
        assertTrue(qso.contains("fun shared(context:Context):QsoDatabase"))
        assertTrue(main.contains("QsoDatabase.shared(context)"))
        assertFalse(main.contains("database.close()"))
        assertTrue(qso.contains("QsoProjectionStore.createSchema"))
        assertTrue(groups.contains("rigweave-groupsio.sqlite"))
        assertFalse(qso.contains("groupsio"))
    }

    @Test fun finishLineTruthBoundariesRemainPresent() {
        val propagation = File("../../core/src/propagation/p533.cpp").readText()
        val screen = source("java/app/rigweave/mobile/hamclock/finishline/HamClockShackDisplay.kt")
        assertTrue(propagation.contains("LICENSE_BLOCKED"))
        assertTrue(screen.contains("Dialog(exit"))
    }
}
