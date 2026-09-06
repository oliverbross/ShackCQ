package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookColumnsTest {
    @Test fun missingOrEmptyPreferenceShowsEveryColumn() {
        assertEquals(LogbookColumn.entries, decodeLogbookColumns(null))
        assertEquals(LogbookColumn.entries, decodeLogbookColumns(""))
    }

    @Test fun savedColumnsRestoreInCanonicalTableOrder() {
        val decoded = decodeLogbookColumns("LOTW,DATE_TIME,CALLSIGN,UNKNOWN")
        assertEquals(listOf(LogbookColumn.DATE_TIME, LogbookColumn.CALLSIGN, LogbookColumn.LOTW), decoded)
        assertEquals("DATE_TIME,CALLSIGN,LOTW", encodeLogbookColumns(decoded))
    }

    @Test fun corruptPreferenceFallsBackToVisibleTable() {
        assertTrue(decodeLogbookColumns("UNKNOWN_ONLY").isNotEmpty())
    }

    @Test fun logbookDensityKeepsTouchTargetAndIncreasesRowTypeThirtyPercent() {
        assertEquals(48, LOGBOOK_HEADER_HEIGHT_DP)
        assertEquals(48, LOGBOOK_ROW_HEIGHT_DP)
        assertEquals(18f * 1.3f, LOGBOOK_ROW_FONT_SP, 0.001f)
    }

    @Test fun compactStatusColumnsTrackTheirShortHeaders() {
        assertEquals(64, LogbookColumn.RST_SENT.width)
        assertEquals(64, LogbookColumn.RST_RECEIVED.width)
        assertEquals(56, LogbookColumn.QSL.width)
        assertEquals(64, LogbookColumn.EQSL.width)
        assertEquals(64, LogbookColumn.LOTW.width)
        assertEquals(92, LogbookColumn.CLUBLOG.width)
        assertEquals(56, LogbookColumn.QRZ.width)
    }

    @Test fun dxccCountryAppearsImmediatelyAfterFrequency() {
        assertEquals(LogbookColumn.DXCC, LogbookColumn.entries[LogbookColumn.FREQUENCY.ordinal + 1])
        assertEquals("DXCC / COUNTRY", LogbookColumn.DXCC.label)
    }

    @Test fun dxccCountryMigrationAddsColumnToExistingSavedLayoutInCanonicalOrder() {
        val migrated = ensureDxccCountryColumn(listOf(LogbookColumn.DATE_TIME, LogbookColumn.FREQUENCY, LogbookColumn.GRID))
        assertEquals(listOf(LogbookColumn.DATE_TIME, LogbookColumn.FREQUENCY, LogbookColumn.DXCC, LogbookColumn.GRID), migrated)
    }

    @Test fun previousQsoAndLiveSpotTypeScaleMatchesTheTabletBrief() {
        assertEquals(.63f, PREVIOUS_QSO_DIALOG_WIDTH_FRACTION, .001f)
        assertEquals(20f * 2f * .75f, PREVIOUS_QSO_TITLE_FONT_SP, .001f)
        assertEquals(15f * 2f * .75f, PREVIOUS_QSO_SUMMARY_FONT_SP, .001f)
        assertEquals(12f * 2f * .75f, PREVIOUS_QSO_BODY_FONT_SP, .001f)
        assertEquals(11f * 1.2f, LIVE_SPOT_HEADER_FONT_SP, .001f)
        assertEquals(12f * 1.2f, LIVE_SPOT_ROW_FONT_SP, .001f)
    }
}
