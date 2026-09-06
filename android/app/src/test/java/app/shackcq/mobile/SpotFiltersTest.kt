package app.shackcq.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotFiltersTest {
    private val spot = AndroidDXSpot(
        id = "1", callsign = "OM0RX", spotter = "W3LPL", frequencyHz = 14_074_000,
        receivedEpoch = 1, band = "20m", mode = "USB", country = "Slovakia", continent = "EU",
        cqZone = 15, ituZone = 28, latitude = 0.0, longitude = 0.0, comment = "",
        score = 0, confidence = 0, samples = 1, watchlisted = false,
        workedCountry = false, workedCall = false, workedBand = false, workedMode = false,
        workedBandMode = false, recentDupe = false, distanceKm = 0, bearingDegrees = 0,
        pathState = "", reason = "",
    )

    @Test fun emptySelectionsMeanAll() {
        assertTrue(spotMatchesFilters(spot, SpotLogStatus("NB", "C"), SpotFilters()))
    }

    @Test fun categoriesCombineAndSelectionsWithinCategoryCombineOr() {
        val filters = SpotFilters(
            bands = setOf("20m", "40m"), modes = setOf("SSB", "CW"),
            callStatuses = setOf("NB"), dxccStatuses = setOf("C"),
        )
        assertTrue(spotMatchesFilters(spot, SpotLogStatus("NB", "C"), filters))
        assertFalse(spotMatchesFilters(spot, SpotLogStatus("NC", "C"), filters))
        assertFalse(spotMatchesFilters(spot.copy(band = "15m"), SpotLogStatus("NB", "C"), filters))
    }

    @Test fun searchMatchesCallOrResolvedEntity() {
        assertTrue(spotMatchesSearch(spot, "Slovak Republic", "504", "om0"))
        assertTrue(spotMatchesSearch(spot, "Slovak Republic", "504", "slovak rep"))
        assertTrue(spotMatchesSearch(spot, "Slovak Republic", "504", "504"))
        assertFalse(spotMatchesSearch(spot, "Slovak Republic", "504", "Japan"))
    }

    @Test fun bandChoicesCoverLfThroughSatelliteAndNormalizeTheLegacyAlias() {
        assertTrue(setOf("2190m", "630m", "160m", "6m", "4m", "2m", "1.25m", "70cm", "23cm", "3cm", "6mm", "sat")
            .all { it in spotBandOptions })
        assertEquals(spotBandOptions.size, spotBandOptions.distinct().size)
        assertEquals("2190m", canonicalSpotBand("2200m"))
        assertEquals("2190m", canonicalSpotBand("2190M"))
    }

    @Test fun hamClockBandsAndCustomStatusColoursRemainBoundedAndValid() {
        assertEquals("160m", hamClockHomeBandOptions.first())
        assertEquals("sat", hamClockHomeBandOptions.last())
        assertTrue("13cm" in hamClockHomeBandOptions)
        assertFalse(setOf("2190m", "630m", "560m", "9cm", "6cm", "3cm", "submm")
            .any { it in hamClockHomeBandOptions })
        assertEquals(setOf("HF", "LOW HF", "HIGH HF"), hamClockHomeBandPresets.map { it.first }.toSet())
        assertEquals(setOf("160m", "80m", "60m", "40m", "30m"),
            hamClockHomeBandPresets.first { it.first == "LOW HF" }.second)
        assertEquals(setOf("20m", "17m", "15m", "12m", "10m"),
            hamClockHomeBandPresets.first { it.first == "HIGH HF" }.second)
        assertEquals(0xFF43C7D9.toInt(), parseSpotColourHex("#43c7d9"))
        assertEquals("43C7D9", spotColourHex(0xFF43C7D9.toInt()))
        assertEquals(null, parseSpotColourHex("12345"))
        assertEquals(null, parseSpotColourHex("GG0000"))
    }
}
