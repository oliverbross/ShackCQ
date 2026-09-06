package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import org.json.JSONObject

class FastEntryTest {
    private val defaults = FastEntryDefaults(LocalDate.parse("2026-08-19"), "OM0RX", "OM0RX", "7", "JN88TQ")
    @Test fun inheritsContextTimeShorthandAndDefaultReports() {
        val result = FastEntryParser.parse("20m ssb\n2134 4W7EST\n6 LA8AJA 47 46", defaults)
        assertTrue(result.errors.toString(), result.errors.isEmpty()); assertEquals(2, result.rows.size); assertEquals("59", result.rows[0].qso.rstSent)
        assertEquals("LA8AJA", result.rows[1].qso.callsign); assertEquals("47", result.rows[1].qso.rstSent)
        assertEquals(36, java.time.Instant.ofEpochSecond(result.rows[1].qso.createdAt).atZone(ZoneOffset.UTC).minute)
    }
    @Test fun appliesDayTimezoneReferencesAndArbitraryAdif() {
        val result = FastEntryParser.parse("TIMEZONE +2\ndate 2026-08-19\n14.060 cw\n1400 OM0RX/P OM-0001 <my_pota_ref:OM-0002> <APP_VENDOR:abc> [73]\nday ++\n1 VK8ABC #PH57 <sat_name:QO-100>", defaults)
        assertTrue(result.errors.toString(), result.errors.isEmpty()); assertEquals("OM-0001", result.rows[0].qso.potaRef)
        assertEquals("abc", result.rows[0].qso.extraAdifFields["APP_VENDOR"]); assertEquals("QO-100", result.rows[1].qso.extraAdifFields["SAT_NAME"])
        assertEquals(12, java.time.Instant.ofEpochSecond(result.rows[0].qso.createdAt).atZone(ZoneOffset.UTC).hour)
        assertEquals(21, java.time.Instant.ofEpochSecond(result.rows[1].qso.createdAt).atZone(ZoneOffset.UTC).dayOfMonth)
    }
    @Test fun reportsLineSpecificMissingContextWithoutCreatingHiddenRows() {
        val result = FastEntryParser.parse("2134 OM0RX", defaults); assertEquals(0, result.rows.size)
        assertEquals(setOf("Band or frequency is required", "Mode is required"), result.errors.map { it.message }.toSet())
    }

    @Test fun sharedGoldenCorpusMatchesCanonicalRowsOnAndroid() {
        val root = JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream("wavelog/fast_entry_golden.json"))
            .bufferedReader().use { it.readText() })
        val cases = root.getJSONArray("cases")
        repeat(cases.length()) { index ->
            val fixture = cases.getJSONObject(index)
            val parsed = FastEntryParser.parse(fixture.getString("input"), defaults.copy(date = LocalDate.parse(fixture.getString("date"))))
            val expectedErrors = fixture.getJSONArray("errors")
            assertEquals(fixture.getString("name"), expectedErrors.length(), parsed.errors.size)
            val expectedRows = fixture.getJSONArray("rows")
            assertEquals(fixture.getString("name"), expectedRows.length(), parsed.rows.size)
            repeat(expectedRows.length()) { rowIndex ->
                val expected = expectedRows.getJSONObject(rowIndex); val actual = parsed.rows[rowIndex].qso
                assertEquals(expected.getString("call"), actual.callsign); assertEquals(expected.getString("band"), actual.band)
                assertEquals(expected.getString("mode"), actual.mode); assertEquals(expected.getString("submode"), actual.submode)
                assertEquals(expected.getString("rst_sent"), actual.rstSent); assertEquals(expected.getString("utc"), Instant.ofEpochSecond(actual.createdAt).toString())
                if (expected.has("pota")) assertEquals(expected.getString("pota"), actual.potaRef)
                if (expected.has("wwff")) assertEquals(expected.getString("wwff"), actual.wwffRef)
                if (expected.has("contest")) assertEquals(expected.getString("contest"), actual.contestId)
                if (expected.has("satellite")) assertEquals(expected.getString("satellite"), actual.extraAdifFields["SAT_NAME"])
                if (expected.has("comment")) assertEquals(expected.getString("comment"), actual.comment)
            }
        }
    }
}
