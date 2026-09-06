package app.shackcq.mobile

import app.shackcq.mobile.hamclock.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Task2B2CPreAcceptanceHardeningTest {
    private val history = listOf(HamClockBandHistoricalRow("20m", "CW", 12, 8, 3, 2, 1, 2))

    @Test fun unchangedRevisionStationAndUtcHourLoadsHistoricalAggregateOnce() {
        val cache = BandHistoryCache()
        val key = BandHistoryCacheKey(7, "station-1", "OM0RX", 99)
        var queries = 0
        repeat(4) { assertEquals(history, cache.getOrLoad(key) { queries++; history }) }
        assertEquals(1, queries)
    }

    @Test fun activeRefreshRetainsOnlyLatestPendingRequest() {
        val queue = LatestWinsRequestQueue<String>()
        assertEquals("first", queue.submit("first"))
        assertEquals(null, queue.submit("middle"))
        assertEquals(null, queue.submit("latest"))
        assertEquals("latest", queue.complete())
        assertEquals(null, queue.complete())
    }

    @Test fun completedFailedSlotAllowsIdenticalRequestToRetry() {
        val queue = LatestWinsRequestQueue<String>()
        assertEquals("same-key", queue.submit("same-key"))
        assertEquals(null, queue.complete())
        assertEquals("same-key", queue.submit("same-key"))
    }

    @Test fun degradedDirectionRetainsUsableEvidenceAndIsNotFullyCurrent() {
        val preference = HamClockBandHealthPreference(visibleBands = setOf("20m"), enabledSources = setOf("PSK"))
        val snapshot = computeHamClockBandHealthSnapshot(
            listOf(HamClockBandEvidence("PSK", "20m", "FT8", "VK3ABC", "OM0RX", -12, 995,
                id = "psk:usable")), mapOf("PSK" to HamClockEvidenceAvailability.DEGRADED), preference, emptyList(), 1_000)
        assertEquals(1, snapshot.rows.single().observations)
        assertEquals("DEGRADED SOURCES", snapshot.rows.single().state)
        assertEquals("LOW", snapshot.rows.single().confidence)
        val feed = PskReporterSnapshot("OM0RX",
            PskDirectionFeed(SignalDirection.BEING_HEARD, state = HamClockFeedState.LIVE),
            PskDirectionFeed(SignalDirection.HEARING, state = HamClockFeedState.STALE))
        assertEquals(NeuralSignalSourceState.DEGRADED, neuralSignalSourceState(feed, true))
    }

    @Test fun contributorReferencesAreExactlyAcceptedDeduplicatedAndCappedRows() {
        val rows = listOf(
            HamClockBandEvidence("RBN", "20m", "CW", "VK9DX", "K1ABC-#", observedEpoch = 700, id = "rbn:r1"),
            HamClockBandEvidence("RBN", "20m", "CW", "VK9DX", "K1ABC-#", observedEpoch = 740, id = "rbn:r2"),
            HamClockBandEvidence("RBN", "20m", "CW", "VK9DX", "K1ABC-#", observedEpoch = 780, id = "rbn:r3"),
            HamClockBandEvidence("RBN", "20m", "CW", "VK9DX", "K1ABC-#", observedEpoch = 820, id = "rbn:r4"),
            HamClockBandEvidence("CLUSTER", "20m", "CW", "VK9DX", "K1ABC-#", observedEpoch = 825, id = "cluster:c1"),
            HamClockBandEvidence("WSPR", "20m", "WSPR", "VK3ABC", "OM0RX", observedEpoch = 990, id = "wspr:w1"),
            HamClockBandEvidence("QSO_HISTORY", "20m", "CW", "OLD", observedEpoch = 990, id = "qso:1"))
        val preference = HamClockBandHealthPreference(60, "ALL", setOf("RBN", "CLUSTER", "WSPR", "QSO_HISTORY"), setOf("20m"))
        val snapshot = computeHamClockBandHealthSnapshot(rows, mapOf(
            "RBN" to HamClockEvidenceAvailability.CURRENT,
            "CLUSTER" to HamClockEvidenceAvailability.CURRENT,
            "WSPR" to HamClockEvidenceAvailability.DISABLED), preference, emptyList(), 1_000)
        val contributors = snapshot.contributors.getValue("20m")
        assertEquals(snapshot.rows.single().observations, contributors.size)
        assertEquals(3, contributors.size)
        assertTrue(contributors.all { it.id.startsWith(it.source.lowercase() + ":") })
        assertFalse(contributors.any { it.source in setOf("WSPR", "QSO_HISTORY") || it.id == "rbn:r4" })
        assertEquals(contributors.map { it.id }, snapshot.contributingObservationIds.getValue("20m"))
    }

    @Test fun progressRfEvidenceDestinationIsObservations() {
        assertEquals(NeuralDxPage.OBSERVATIONS, dxRfEvidenceDestination())
    }

    @Test fun homeAndDxResolverUseTheSameResolvedObservationView() {
        val raw = HamClockRbnObservation("row", skimmerCall = "K1ABC-#", dxCall = "OM0RX",
            frequencyHz = 14_025_000, band = "20m", mode = "CW", snr = -5, wpm = 28, bps = null,
            cq = true, test = false, observedEpoch = 1_000, rawComment = "CQ")
        val station = GeoPoint(48.5, 17.5)
        fun resolve() = resolveRbnObservationView(raw, "OM0RX", station,
            { call -> if (call == "K1ABC") "FN31" else null }, { null })
        val home = resolve(); val dx = resolve()
        assertEquals(home, dx)
        assertEquals("CURRENT STATION GRID · EXACT", dx.dxGeometry)
        assertEquals("CACHED CALLBOOK GRID · EXACT", dx.skimmerGeometry)
        assertEquals("K1ABC-#", dx.skimmerCall)
    }

    @Test fun rbnMaintenanceRunsOnlyForegroundEnabledAndNotIntentionallyDisabled() {
        assertTrue(shouldRunRbnMaintenance(true, true, ClusterConnectionState.CONNECTED))
        assertFalse(shouldRunRbnMaintenance(false, true, ClusterConnectionState.CONNECTED))
        assertFalse(shouldRunRbnMaintenance(true, false, ClusterConnectionState.CONNECTED))
        assertFalse(shouldRunRbnMaintenance(true, true, ClusterConnectionState.DISABLED))
    }
}
