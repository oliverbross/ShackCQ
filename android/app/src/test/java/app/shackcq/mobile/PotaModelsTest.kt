package app.shackcq.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PotaModelsTest {
    private val now = 1_800_000_000L

    @Test fun parsesNormalizesExpiresAndDeduplicatesLiveSpots() {
        val raw = """[
          {"spotId":1,"activator":"vk3abc/p","frequency":"14062.5","mode":"cw","reference":"au-0001","spotTime":"2027-01-15T08:00:00","expire":1800,"name":"Murray, River National Park","locationDesc":"AU-VIC","grid6":"QF22ab","latitude":-37.8,"longitude":144.9,"comments":"First","source":"POTA"},
          {"spotId":2,"activator":"VK3ABC/P","frequency":"14062.5","mode":"CW","reference":"AU-0001","spotTime":"2027-01-15T08:01:00","expire":1800,"parkName":null,"name":"Murray River National Park","comments":"Newest","source":"RBN"}
        ]"""
        val rows = parsePotaSpots(raw, now)
        assertEquals(1, rows.size)
        assertEquals("VK3ABC/P", rows.single().callsign)
        assertEquals(14_062_500L, rows.single().frequencyHz)
        assertEquals("AU-0001", rows.single().reference)
        assertEquals("Murray River National Park", rows.single().parkName)
        assertEquals("Newest", rows.single().comments)
        assertEquals(rows.single().spottedAt + 1800, rows.single().expiresAt)
    }

    @Test fun toleratesMissingOptionalsAndMarksInvalidQrtAndExpired() {
        val raw = """[
          {"activator":"OM0RX","frequency":"0","mode":null,"reference":"bad ref","spotTime":"2027-01-15T08:00:00","comments":"Now QrT, thanks"},
          {"activator":"K1ABC","frequency":"7044","mode":"CW","reference":"US-1234","spotTime":"2027-01-15T08:00:00","expire":60}
        ]"""
        val rows = parsePotaSpots(raw, now)
        assertEquals(2, rows.size)
        val bad = rows.first { it.callsign == "OM0RX" }
        assertTrue(bad.invalid); assertTrue(bad.qrt); assertEquals(0L, bad.frequencyHz); assertEquals("", bad.reference); assertEquals("", bad.mode)
        val expired = rows.first { it.callsign == "K1ABC" }
        assertFalse(expired.activeAt(expired.expiresAt))
    }

    @Test fun calculatesWorkedLabelsAndDeterministicRanking() {
        val spot = spot(reference = "AU-0001", callsign = "VK3ABC", mode = "CW", frequency = 14_062_000)
        val old = Qso("1", "VK3ABC", 7_044_000, "SSB", "59", "59", now - 86_400,
            band = "40m", potaRef = "AU-0001")
        val state = workedStateFor(spot, listOf(old), now)
        assertTrue(state.parkWorked); assertFalse(state.bandWorked); assertFalse(state.modeWorked); assertTrue(state.callWorked); assertFalse(state.workedToday)
        assertEquals(listOf("NEW ON BAND", "CALL WORKED"), state.labels)
        val first = rankPotaSpot(spot, state, now, radioFrequencyHz = 14_100_000)
        val second = rankPotaSpot(spot, state, now, radioFrequencyHz = 14_100_000)
        assertEquals(first.score, second.score); assertEquals(first.reasons, second.reasons)
        assertTrue(first.reasons.contains("current radio band"))
        val qrt = rankPotaSpot(spot.copy(qrt = true), state, now)
        assertTrue(qrt.score < first.score)
    }

    @Test fun tuneAndLogUsesOtherStationPotaAndOfflineSendsNothing() {
        val spot = spot(reference = "OM-0042", callsign = "OM0ABC", mode = "USB", frequency = 14_244_000)
        val draft = toPotaLogDraft(spot, 7)
        assertEquals("OM-0042", draft.potaRef)
        assertFalse(draft.comment.isBlank())
        val commands = mutableListOf<String>()
        assertFalse(executePotaTune(false, spot, commands::add)); assertTrue(commands.isEmpty())
        assertTrue(executePotaTune(true, spot, commands::add))
        assertEquals(listOf("FA00014244000;", "MD2;"), commands)
    }

    private fun spot(reference: String, callsign: String, mode: String, frequency: Long) = PotaSpot(
        "spot", callsign, frequency, mode, reference, "Park", "AU-VIC", "QF22", -37.8, 144.9,
        now - 120, now + 1200, "POTA", "N0CALL", "signal good", false, false)
}

class PotaCatalogueRulesTest {
    @Test fun parsesHeaderByNameAndQuotedUtf8Csv() {
        val file = File.createTempFile("pota", ".csv")
        try {
            file.writeText("name,grid,reference,longitude,active,locationDesc,latitude,entityId\n\"Parc, Železná\",JN88,OM-0001,17.1,1,SK,-48.2,123\n")
            Utf8CsvReader(file).use { reader ->
                val header = reader.nextRow()!!.mapIndexed { index, value -> normalizedHeader(value) to index }.toMap()
                val row = reader.nextRow()!!
                assertEquals("OM-0001", row[header.getValue("reference")])
                assertEquals("Parc, Železná", row[header.getValue("name")])
            }
        } finally { file.delete() }
    }

    @Test fun failedValidationRetainsPreviousCatalogueAndValidSwapActivatesStaging() {
        val dir = createTempDir(prefix = "pota-db-")
        try {
            val active = File(dir, "active.sqlite").apply { writeText("known-good") }
            val invalid = File(dir, "invalid.sqlite").apply { writeText("bad") }
            assertFalse(activatePotaCatalogue(invalid, active, valid = false)); assertEquals("known-good", active.readText())
            val staged = File(dir, "staged.sqlite").apply { writeText("validated") }
            assertTrue(activatePotaCatalogue(staged, active, valid = true)); assertEquals("validated", active.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun searchMatchesReferenceAndNameAndNearbySortsUnknownLast() {
        val near = PotaPark("AU-0001", "Kakadu National Park", true, "1", "AU-NT", -12.5, 132.5, "PH57", 12.0, 90)
        val far = PotaPark("OM-0002", "Železná Park", true, "2", "SK", 48.0, 17.0, "JN88", 700.0, 270)
        val unknown = PotaPark("US-0003", "Unknown", true, "3", "US", null, null, "")
        assertTrue(near.matchesSearch("AU-")); assertTrue(far.matchesSearch("železná", "sk"))
        assertEquals(listOf(near, far, unknown), sortNearbyParks(listOf(unknown, far, near)))
    }
}
