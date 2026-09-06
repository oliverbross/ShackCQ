package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OvernightScaleSatelliteTest {
    private val elements = SatelliteElements("TLE", "AO-91", "1 line", "2 line", 1_700_000_000, "TEST")
    private val satellite = SatelliteCatalogueEntry(43017, "AO-91", elements, 1_700_000_000)
    private val transponder = SatelliteTransponder(
        "ao91-fm", 43017, "FM repeater", 435_250_000, null, 145_960_000, null,
        "FM", "FM", false, true, "active", "2026-08-19T00:00:00Z",
    )
    private val pass = OrbitalPass(1_700_000_100, 1_700_000_300, 1_700_000_500, 42.0, 120.0, 300.0, false)

    @Test fun cacheTruthDistinguishesCurrentStaleOfflineEmptyAndError() {
        assertEquals(SatelliteCacheState.CURRENT, satelliteCacheState(10, "", 100, 95, 10))
        assertEquals(SatelliteCacheState.STALE, satelliteCacheState(10, "", 100, 80, 10))
        assertEquals(SatelliteCacheState.OFFLINE_CACHE, satelliteCacheState(10, "TIMEOUT", 100, 95, 10))
        assertEquals(SatelliteCacheState.EMPTY, satelliteCacheState(0, "", 100, 95, 10))
        assertEquals(SatelliteCacheState.ERROR, satelliteCacheState(0, "INVALID", 100, 95, 10))
    }

    @Test fun qo100PointingFromJn88tqMatchesTheDocumentedReference() {
        val look = geostationaryLookAngle(requireNotNull(maidenheadCenter("JN88TQ")))
        assertTrue(look.visible)
        assertEquals(169.0, look.azimuthDeg, 0.6)
        assertEquals(33.5, look.elevationDeg, 0.6)
    }

    @Test fun satelliteDraftCarriesReviewableAdifAndPassContext() {
        val draft = satelliteFastEntryDraft(SatellitePassRow(satellite, pass, transponder), "JN88TQ")
        listOf("<PROP_MODE:SAT>", "<SAT_NAME:AO-91>", "<SAT_MODE:FM>", "<MODE:FM>",
            "<MY_GRIDSQUARE:JN88TQ>", "<BAND:70cm>", "<FREQ:435.250000>", "<BAND_RX:2m>", "<FREQ_RX:145.960000>",
            "RX PREVIEW 145.960000 FM",
            "AOS 1700000100", "TCA 1700000300", "LOS 1700000500").forEach { assertTrue(it, it in draft) }
    }

    @Test fun fastEntryParsesSatelliteDraftWithoutCreatingAnAutomaticQso() {
        val draft = satelliteFastEntryDraft(SatellitePassRow(satellite, pass, transponder))
        val defaults = FastEntryDefaults(LocalDate.parse("2026-08-19"), "OM0RX", "OM0RX", "7", "JN88TQ")
        assertTrue(FastEntryParser.parse(draft, defaults).rows.isEmpty())
        val parsed = FastEntryParser.parse(draft + "1200 OM0RX/P 59 59", defaults)
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        assertEquals("SAT", parsed.rows.single().qso.propagationMode)
        assertEquals("AO-91", parsed.rows.single().qso.extraAdifFields["SAT_NAME"])
        assertEquals("FM", parsed.rows.single().qso.extraAdifFields["SAT_MODE"])
        assertEquals(435_250_000, parsed.rows.single().qso.frequencyHz)
        assertEquals("70cm", parsed.rows.single().qso.band)
        assertEquals(145_960_000, parsed.rows.single().qso.frequencyRxHz)
        assertEquals("2m", parsed.rows.single().qso.bandRx)
    }

    @Test fun realCelesTrakOffsetlessEpochPreservesCompleteCsvAndRejectsHtml() {
        val header = "OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID,ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT"
        val row = "OSCAR 7 (AO-7),1974-089B,2026-08-19T00:23:27.496608,12.53699154,.00123316,101.9919,244.9197,13.2706,15.2415,0,U,123456,999,36839,-.5025725E-5,-.47E-6,0"
        val parsed = parseCelesTrakCsvPayload("$header\n$row", 123).single()
        assertEquals(123456, parsed.noradId)
        assertEquals(1787099007, parsed.elementEpoch)
        assertEquals(row, parsed.elements.elementOne)
        assertTrue(runCatching { parseCelesTrakCsvPayload("<html>error</html>", 123) }.isFailure)
    }

    @Test fun needsRefreshIdentityChangesWhenSpotContentChangesAtSameCountAndTime() {
        val first = PortableSpot("1", setOf(PortableProgram.POTA), "K1ABC", 14_074_000, "FT8",
            listOf(PortableReference(PortableProgram.POTA, "US-0001")), 100, 1_000, "TEST")
        val changed = first.copy(callsign = "K2XYZ")
        assertTrue(progressSpotIdentity(emptyList(), listOf(first)) != progressSpotIdentity(emptyList(), listOf(changed)))
    }

    @Test fun satelliteLogbookFiltersAreDeterministic() {
        val rows = listOf(
            qso("sat", "AO-91", "FM", "SAT"),
            qso("other", "QO-100", "SSB", "SAT"),
            qso("hf", "", "", ""),
        )
        assertEquals(listOf("sat"), filterLogbook(rows, LogbookFilter(propagation = "SAT", satellite = "AO-91", satelliteMode = "FM")).map(Qso::id))
    }

    @Test fun satelliteIntelligenceCountsCallsGridsBandsAndConfirmedMatrix() {
        val rows = listOf(
            qso("one", "AO-91", "FM", "SAT").copy(callsign = "K1ABC", grid = "FN31", myGrid = "JN88TQ", band = "2m", lotwReceived = "Y"),
            qso("two", "AO-91", "FM", "SAT").copy(callsign = "K2XYZ", grid = "FN20", myGrid = "JN98AA", band = "2m"),
        )
        val result = buildProgressSnapshot(rows, ProgressFilters(allStations = true), now = 1_700_000_900)
        assertEquals(2, result.satellite.qsos)
        assertEquals(2, result.satellite.uniqueCalls)
        assertEquals(2, result.satellite.grids)
        assertEquals(2, result.satellite.ownGrids)
        assertEquals(mapOf("2m" to 2), result.satellite.byBand)
        assertEquals(1, result.satellite.workedConfirmed.getValue("AO-91").confirmed)
    }

    private fun qso(id: String, satellite: String, satelliteMode: String, propagation: String) = Qso(
        id = id, callsign = "TEST", frequencyHz = 145_960_000, mode = "FM", rstSent = "59", rstReceived = "59",
        createdAt = 1_700_000_000, propagationMode = propagation,
        extraAdifFields = buildMap { if (satellite.isNotBlank()) put("SAT_NAME", satellite); if (satelliteMode.isNotBlank()) put("SAT_MODE", satelliteMode) },
    )
}
