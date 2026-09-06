package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import java.time.Instant

class NeuralDxRulesTest {
    @Test fun unifiedObservationBandsMapCanonicallyAndDirectTuneStopsAtSixMetres() {
        assertEquals(
            listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m",
                "4m", "2m", "70cm", "23cm", "3cm"),
            insightBands,
        )
        assertEquals("4m", bandForFrequency(70_200_000))
        assertEquals("2m", bandForFrequency(144_300_000))
        assertEquals("70cm", bandForFrequency(432_200_000))
        assertEquals("23cm", bandForFrequency(1_296_200_000))
        assertEquals("3cm", bandForFrequency(10_489_860_000))
        listOf(71_000_000L, 100_000_000L, 148_000_000L, 222_000_000L, 450_000_000L,
            902_000_000L, 1_300_000_000L, 2_300_000_000L, 10_500_000_000L).forEach {
            assertTrue(bandForFrequency(it).isBlank())
        }
        assertTrue(dxDirectTuneAvailable(54_000_000))
        assertFalse(dxDirectTuneAvailable(70_200_000))
        assertFalse(dxDirectTuneAvailable(144_300_000))
        assertFalse(dxDirectTuneAvailable(10_489_860_000))
        assertEquals("3cm · QO-100", dxDisplayBand("3cm", 10_489_860_000, ""))
        assertEquals("3cm · QO-100", dxDisplayBand("3cm", 10_200_000_000, "QO100 beacon"))
        assertEquals("3cm", dxDisplayBand("3cm", 10_200_000_000, "ordinary activity"))
    }

    @Test fun currentOpportunitiesAreRankedEvidenceNotPredictions() {
        fun spot(id: String, call: String, score: Int, confidence: Int, epoch: Long, band: String = "20m", mode: String = "FT8") = AndroidDXSpot(
            id, call, "W1AW", 14_074_000, epoch, band, mode, "Test Country", "EU",
            14, 28, 48.0, 17.0, "", score, confidence, score + 1, false,
            false, false, false, false, false, false, 0, 0, "", "Evidence for $call",
        )
        val input = (0..13).map { index -> spot("id-$index", "CALL$index", 90 - index, 40 + index, 1_000L + index) } +
            spot("low", "LOW1", 44, 99, 2_000) +
            spot("duplicate", "CALL0", 89, 88, 3_000)

        val opportunities = buildCurrentOpportunities(input)

        assertEquals(12, opportunities.size)
        assertEquals("CALL0", opportunities.first().callsign)
        assertEquals(90, opportunities.first().priority)
        assertEquals(40, opportunities.first().evidenceScore)
        assertEquals(1_000, opportunities.first().observedEpoch)
        assertEquals("Evidence for CALL0", opportunities.first().reason)
        assertEquals(91, opportunities.first().samples)
        assertEquals(1, opportunities.count { it.callsign == "CALL0" && it.band == "20m" && it.mode == "FT8" })
        assertFalse(opportunities.any { it.callsign == "LOW1" })
        assertEquals(opportunities.map { it.priority }.sortedDescending(), opportunities.map { it.priority })
        val fields = NeuralCurrentOpportunity::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.any { it in setOf("probability", "model", "startEpoch", "endEpoch", "measuredReliability") })

        val highBand = buildCurrentOpportunities(
            listOf(spot("3cm", "SAT1", 80, 70, 4_000, band = "3cm", mode = "FT4").copy(frequencyHz = 10_489_860_000))
        ).single()
        assertEquals("3cm", highBand.band)
        assertEquals(80, highBand.priority)
        assertEquals(70, highBand.evidenceScore)
    }

    @Test fun maidenheadUsesCellCentreAndRejectsInvalidInput() {
        val point = maidenheadCenter("JN88wt")
        assertNotNull(point)
        assertTrue(point!!.latitude in 48.0..49.0)
        assertTrue(point.longitude in 17.0..18.0)
        assertNull(maidenheadCenter("ZZ99"))
        assertNull(maidenheadCenter("JN8"))
    }

    @Test fun dxDistanceUsesConfiguredAndCallbookGrids() {
        val distance = dxDistanceKm("JN88TQ", "FN31PR")
        assertNotNull(distance)
        assertTrue(distance!! in 6_500..7_500)
        assertNull(dxDistanceKm("", "FN31PR"))
        assertNull(dxDistanceKm("JN88TQ", "", "0", "0"))
    }

    @Test fun tropoHeuristicNeverFabricatesMissingMeasurements() {
        assertEquals(null to null, tropoIndex(null, 4.0, 80, 0.0))
        val (index, risk) = tropoIndex(20.0, 19.0, 90, 20.0)
        assertTrue(index!! >= 7)
        assertEquals("HIGH", risk)
    }

    @Test fun briefingExtractionFindsRadioCallsignsWithoutYearNoise() {
        assertEquals(listOf("OM0RX", "K1ABC/P", "VK3YW"),
            extractCallsigns("OM0RX works K1ABC/P and VK3YW during 2026"))
    }

    @Test fun briefingTextDecodesNamedDecimalAndHexEntities() {
        assertEquals("DX – AO-7 & QO-100 — active",
            decodeHtmlText("<b>DX</b> &#8211; AO-7 &amp; QO-100 &#x2014; active"))
    }

    @Test fun amsatFallbackParsesThreeLineElements() {
        val rows = parseTleOrbitRecords(
            """AO-07
                |1 07530U 74089B   26228.29930727 -.00000049  00000-0 -13279-4 0  9993
                |2 07530 101.9919 242.1742 0012360  18.4320 352.6587 12.53699110368054
            """.trimMargin()
        )
        assertEquals(1, rows.size)
        assertEquals(7530, rows.single().norad)
        assertEquals("AO-07", rows.single().name)
        assertEquals(0.001236, rows.single().eccentricity, 0.0000001)
        assertEquals(12.5369911, rows.single().meanMotion, 0.0000001)
    }

    @Test fun mapFootprintsRemainGeographicAndSplitAtDateLine() {
        val circle = geodesicCircle(0.0, 179.0, 1_000.0)
        assertEquals(73, circle.size)
        assertTrue(circle.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        val segments = splitAtDateline(circle)
        assertTrue(segments.size >= 2)
        assertTrue(segments.all { segment -> segment.zipWithNext().all { (a, b) -> kotlin.math.abs(a.longitude - b.longitude) <= 180.0 } })
        val polygons = splitPolygonAtDateline(circle)
        assertTrue(polygons.size >= 2)
        assertTrue(polygons.all { polygon ->
            polygon.size >= 3 &&
                polygon.all { it.longitude in -180.0..180.0 } &&
                polygon.zipWithNext().all { (a, b) -> kotlin.math.abs(a.longitude - b.longitude) <= 180.0 }
        })
    }

    @Test fun greatCircleSpotPathsKeepEndpointsAndSplitAtDateLine() {
        val from = LatLng(35.0, 170.0)
        val to = LatLng(37.0, -175.0)
        val route = greatCirclePath(from, to)
        assertEquals(33, route.size)
        assertEquals(from.latitude, route.first().latitude, 0.000001)
        assertEquals(from.longitude, route.first().longitude, 0.000001)
        assertEquals(to.latitude, route.last().latitude, 0.000001)
        assertEquals(to.longitude, route.last().longitude, 0.000001)
        val segments = splitAtDateline(route)
        assertTrue(segments.size >= 2)
        assertTrue(segments.all { segment ->
            segment.zipWithNext().all { (a, b) -> kotlin.math.abs(a.longitude - b.longitude) <= 180.0 }
        })
    }

    @Test fun homeMapUsesOnlyMatchingEnrichedSpots() {
        fun spot(id: String, latitude: Double = 0.0, longitude: Double = 0.0) = AndroidDXSpot(
            id, "K1ABC", "W1AW", 14_074_000, 1, "20m", "FT8", "", "",
            0, 0, latitude, longitude, "", 0, 0, 0, false,
            false, false, false, false, false, false, 0, 0, "", "",
        )
        val live = listOf(spot("current").copy(comment = "fresh", watchlisted = true, score = 99), spot("new"))
        val merged = mergeEnrichedSpots(live, listOf(spot("current", 41.0, -72.0).copy(comment = "stale"), spot("stale", 5.0, 6.0)))
        assertEquals(listOf("current", "new"), merged.map(AndroidDXSpot::id))
        assertEquals(41.0, merged.first().latitude, 0.0)
        assertEquals(0.0, merged.last().latitude, 0.0)
        assertEquals("fresh", merged.first().comment)
        assertTrue(merged.first().watchlisted)
        assertEquals(99, merged.first().score)
    }

    @Test fun greylineRemainsDrawableAtEquinox() {
        val points = terminatorPoints(Instant.parse("2026-03-20T14:46:00Z"))
        assertEquals(182, points.size)
        assertTrue(points.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        assertEquals(2, points.map { it.longitude }.distinct().size)
        assertEquals(-90.0, points.minOf { it.latitude }, 0.0)
        assertEquals(90.0, points.maxOf { it.latitude }, 0.0)
    }
}
