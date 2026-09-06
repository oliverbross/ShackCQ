package app.shackcq.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PortableModelsTest {
    private val now = 1_787_012_800L

    @Test fun parsesCurrentAndLegacySotaShapesAndFiltersSpecialRecords() {
        val fixture = checkNotNull(javaClass.getResource("/sota/current-spots-sanitized.json")).readText()
        val current = parseSotaSpots(fixture, now = now)
        assertEquals(1, current.size); assertEquals("ZL3/CB-551", current.single().primary.code); assertEquals(146_500_000L, current.single().frequencyHz)
        assertEquals(1206, current.single().primary.altitudeM); assertEquals(4, current.single().primary.points)
        val raw = """[
          {"id":1,"timeStamp":"2026-08-18T00:10:00","activatorCallsign":"OM0RX/P","frequency":7.032,"mode":"CW","summitCode":"OM/ZA-001","summitName":"Velky Krivan","type":null},
          {"id":2,"timeStamp":"2026-08-18T00:10:00","activatorCallsign":"K1TEST","frequency":"14.062","mode":"CW","associationCode":"W1","summitCode":"AM-001","type":"TEST"},
          {"id":3,"timeStamp":"2026-08-18T00:10:00","activatorCallsign":"K1QRT","frequency":"14.062","mode":"CW","associationCode":"W1","summitCode":"AM-002","type":"QRT"}
        ]"""
        val rows = parseSotaSpots(raw, now = now)
        assertEquals(2, rows.size); assertEquals("OM/ZA-001", rows.first().primary.code); assertFalse(rows.last().activeAt(now))
    }

    @Test fun joinsSummitCatalogueWithoutOverwritingLiveValues() {
        val summit = SotaSummit("OM/ZA-001", "Slovakia", "Zilina", "Catalogue name", 1709, 5607, 10, 3, 49.18, 19.03, "JN99", "2006-01-01", "")
        val raw = """[{"id":1,"timeStamp":"2026-08-18T00:10:00","activatorCallsign":"OM0RX/P","frequency":"7.032","mode":"CW","summitCode":"OM/ZA-001","summitName":"Live name","AltM":1710,"points":8}]"""
        val spot = parseSotaSpots(raw, mapOf(summit.code to summit), now).single()
        assertEquals("Live name", spot.primary.name); assertEquals(1710, spot.primary.altitudeM); assertEquals("Zilina", spot.primary.region); assertEquals(49.18, spot.latitude!!, .001)
    }

    @Test fun parsesWwffDecimalKHzQrtFutureAndAgendaEnrichment() {
        val agendas = """[{"id":1,"activator_call":"OM0RX/P","reference":"OMFF-0001","utc_start":"2026-08-17 23:00:00","utc_end":"2026-08-18 01:00:00","band":"40m","mode":"CW","remarks":"scheduled"}]"""
        val raw = """[
          {"id":1,"activator":"OM0RX/P","frequency_khz":7032.5,"mode":"CW","reference":"OMFF-0001","reference_name":"Protected area","remarks":"CQ","spotter":"OM1AAA","latitude":48.1,"longitude":17.1,"spot_time":1787011800},
          {"id":2,"activator":"K1QRT","frequency_khz":14062,"mode":"CW","reference":"KFF-0001","remarks":"now qRt thanks","spot_time":1787011800},
          {"id":3,"activator":"K1FUT","frequency_khz":14062,"mode":"CW","reference":"KFF-0002","remarks":"","spot_time":1787020000}
        ]"""
        val rows = parseWwffSpots(raw, agendas, now)
        val active = rows.first { it.callsign == "OM0RX/P" }; assertEquals(7_032_500L, active.frequencyHz); assertTrue(active.primary.activeAgenda.contains("scheduled"))
        assertTrue(rows.first { it.callsign == "K1QRT" }.qrt); assertTrue(rows.first { it.callsign == "K1FUT" }.invalid)
        assertEquals(3, rows.size) // agenda never creates a frequency-less row
    }

    @Test fun groupingIsConservativeAndLoggerMapsAllOtherStationFields() {
        val p = spot(PortableProgram.POTA, "AU-0001", 14_062_000, now - 60)
        val s = spot(PortableProgram.SOTA, "VK3/VC-001", 14_062_200, now - 100)
        val w = spot(PortableProgram.WWFF, "VKFF-0123", 14_064_000, now - 80)
        val grouped = groupPortableSpots(listOf(p, s, w))
        assertEquals(2, grouped.size)
        val multi = grouped.first { it.programs.size == 2 }; val draft = toPortableLogDraft(multi, 7)
        assertEquals("AU-0001", draft.potaRef); assertEquals("VK3/VC-001", draft.sotaRef); assertEquals("", draft.wwffRef)
        val commands = mutableListOf<String>(); assertFalse(executePortableTune(false, multi, commands::add)); assertTrue(commands.isEmpty())
    }

    @Test fun workedStateUsesProgrammeSpecificFieldsAndRankingIsDeterministic() {
        val spot = spot(PortableProgram.SOTA, "OM/ZA-001", 7_032_000, now - 60)
        val qsos = listOf(Qso("1", "OM0RX/P", 14_062_000, "SSB", "59", "59", now - 86_400, band = "20m", sotaRef = "OM/ZA-001", potaRef = "OM-9999"))
        val first = rankPortableSpot(spot, qsos, now, 7_050_000); val second = rankPortableSpot(spot, qsos, now, 7_050_000)
        assertTrue(first.worked.getValue(PortableProgram.SOTA).referenceWorked); assertFalse(first.worked.getValue(PortableProgram.SOTA).bandWorked)
        assertEquals(first.score, second.score); assertEquals(first.reasons, second.reasons)
    }

    @Test fun portableMapLabelCarriesCallReferenceAndLocationAtTheCoordinate() {
        val live = spot(PortableProgram.POTA, "AU-0001", 14_062_000, now - 60).copy(
            references = listOf(PortableReference(PortableProgram.POTA, "AU-0001", "Blue Mountains", latitude = -33.7, longitude = 150.3)),
            latitude = -33.7, longitude = 150.3,
        )
        val json = portableLabelGeoJson(listOf(PortableOpportunity(live, emptyMap(), 0, emptyList())), live.id)
        assertTrue(json.contains("OM0RX/P")); assertTrue(json.contains("POTA AU-0001")); assertTrue(json.contains("Blue Mountains"))
        assertTrue(json.contains("150.3")); assertTrue(json.contains("-33.7"))
    }

    private fun spot(program: PortableProgram, reference: String, frequency: Long, time: Long) = PortableSpot(
        "$program:$reference", setOf(program), "OM0RX/P", frequency, "CW", listOf(PortableReference(program, reference, "Place")), time, time + 3600, program.label
    )
}

class SotaCatalogueRulesTest {
    @Test fun parsesHeaderByNameWithTitleAndUtf8() {
        val file = File.createTempFile("sota", ".csv")
        try {
            file.writeText("SOTA Summits List (Date=17/08/2026)\nSummitName,RegionName,SummitCode,AssociationName,Latitude,Longitude,ValidFrom,ValidTo\nŽelezná,Žilina,OM/ZA-001,Slovakia,49.1,19.0,2006-01-01,\n")
            Utf8CsvReader(file).use { csv -> csv.nextRow(); val header = csv.nextRow()!!.mapIndexed { i, value -> normalizedHeader(value) to i }.toMap(); val row = csv.nextRow()!!; assertEquals("OM/ZA-001", row[header.getValue("summitcode")]); assertEquals("Železná", row[header.getValue("summitname")]) }
        } finally { file.delete() }
    }

    @Test fun failedOrCancelledSwapRetainsPreviousDatabase() {
        val dir = createTempDir(prefix = "sota-db-")
        try { val active = File(dir, "active.sqlite").apply { writeText("known-good") }; val invalid = File(dir, "invalid.sqlite").apply { writeText("bad") }; assertFalse(activateSotaCatalogue(invalid, active, false)); assertEquals("known-good", active.readText()); val staged = File(dir, "staged.sqlite").apply { writeText("validated") }; assertTrue(activateSotaCatalogue(staged, active, true)); assertEquals("validated", active.readText()) } finally { dir.deleteRecursively() }
    }
}
