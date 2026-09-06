package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookFiltersTest {
    private val target = Qso(
        id = "target", callsign = "OM0RX", frequencyHz = 14_060_000, mode = "CW", submode = "CW",
        rstSent = "599", rstReceived = "579", createdAt = 200, country = "Slovakia", band = "20m",
        grid = "JN98", iota = "EU-001", sotaRef = "OM/ZA-001", wwffRef = "OMFF-0001", potaRef = "OM-0001",
        comment = "Summit contact", notes = "Strong signal", operatorCallsign = "OM0RX", dxcc = "504",
        continent = "EU", region = "Eastern Europe", state = "ZA", propagationMode = "F2", county = "ZILINA",
        dok = "A01", contestId = "CQ-WW-CW", distanceKm = 1_250.0, durationSeconds = 3_600,
        qslSent = "Y", qslReceived = "Y", qslMethod = "D", qslReceivedMethod = "B", qslVia = "OM0RX",
        lotwSent = "Y", lotwReceived = "Y", clublogSent = "Y", clublogReceived = "Y",
        eqslSent = "Y", eqslReceived = "Y", dclSent = "Y", dclReceived = "Y",
        qrzSent = "Y", qrzReceived = "Y", qslImages = "front.jpg")
    private val other = Qso("other", "VK3TEST", 7_100_000, "SSB", "59", "59", 100,
        country = "Australia", band = "40m", grid = "QF22", dxcc = "150", continent = "OC", state = "VIC")
    private val records = listOf(other, target)

    private fun assertOnlyTarget(filter: LogbookFilter) =
        assertEquals(listOf("target"), filterLogbook(records, filter).map { it.id })

    @Test fun everyGeneralFilterIsApplied() {
        listOf(
            LogbookFilter(fromEpochSeconds = 150, toEpochSecondsExclusive = 250),
            LogbookFilter(callsign = "0rx"), LogbookFilter(dxcc = "504"), LogbookFilter(state = "za"),
            LogbookFilter(grid = "jn98"), LogbookFilter(mode = "CW"), LogbookFilter(band = "20m"),
            LogbookFilter(propagation = "F2"), LogbookFilter(county = "zil"), LogbookFilter(dok = "A01"),
            LogbookFilter(sota = "ZA-001"), LogbookFilter(pota = "OM-0001"), LogbookFilter(iota = "EU-001"),
            LogbookFilter(wwff = "OMFF"), LogbookFilter(operator = "OM0RX"), LogbookFilter(contest = "CQ-WW"),
            LogbookFilter(continent = "EU"), LogbookFilter(comment = "summit contact"), LogbookFilter(notes = "strong signal"),
            LogbookFilter(distance = ">1000"), LogbookFilter(duration = ">=60"),
        ).forEach(::assertOnlyTarget)
        assertEquals(listOf("other"), filterLogbook(records, LogbookFilter(toEpochSecondsExclusive = 150)).map { it.id })
    }

    @Test fun commentNotesAndEveryWorkedPotaReferenceRemainDistinct() {
        val multi = target.copy(potaRefs = listOf("OM-0001", "OM-9999"))
        assertEquals(listOf("target"), filterLogbook(listOf(other, multi), LogbookFilter(comment = "summit")).map(Qso::id))
        assertEquals(listOf("target"), filterLogbook(listOf(other, multi), LogbookFilter(notes = "strong")).map(Qso::id))
        assertTrue(filterLogbook(listOf(other, multi), LogbookFilter(comment = "strong")).isEmpty())
        assertEquals(listOf("target"), filterLogbook(listOf(other, multi), LogbookFilter(pota = "9999")).map(Qso::id))
    }

    @Test fun everyQslFilterIsApplied() {
        listOf(
            LogbookFilter(qslSent = "Y"), LogbookFilter(qslReceived = "Y"),
            LogbookFilter(qslSentMethod = "D"), LogbookFilter(qslReceivedMethod = "B"),
            LogbookFilter(lotwSent = "Y"), LogbookFilter(lotwReceived = "Y"),
            LogbookFilter(clublogSent = "Y"), LogbookFilter(clublogReceived = "Y"),
            LogbookFilter(eqslSent = "Y"), LogbookFilter(eqslReceived = "Y"),
            LogbookFilter(dclSent = "Y"), LogbookFilter(dclReceived = "Y"),
            LogbookFilter(qrzSent = "Y"), LogbookFilter(qrzReceived = "Y"),
            LogbookFilter(qslVia = "0rx"), LogbookFilter(qslImages = "Y"),
        ).forEach(::assertOnlyTarget)
        assertEquals(listOf("other"), filterLogbook(records, LogbookFilter(qslImages = "N")).map { it.id })
    }

    @Test fun sortingDirectionAndResultLimitWork() {
        assertEquals(listOf("other", "target"), filterLogbook(records, LogbookFilter(
            sort = LogbookSort.TIME, direction = LogbookSortDirection.ASCENDING)).map { it.id })
        assertEquals(listOf("target"), filterLogbook(records, LogbookFilter(
            sort = LogbookSort.TIME, direction = LogbookSortDirection.DESCENDING, limit = 1)).map { it.id })
        assertEquals(listOf("target", "other"), filterLogbook(records, LogbookFilter(
            sort = LogbookSort.FREQUENCY, direction = LogbookSortDirection.DESCENDING)).map { it.id })
    }

    @Test fun numericExpressionsSupportThresholdComparisonsAndRanges() {
        assertTrue(numericMatches(500.0, "500")); assertTrue(numericMatches(500.0, ">=500"))
        assertTrue(numericMatches(500.0, "<501")); assertTrue(numericMatches(500.0, "400-600"))
        assertTrue(numericMatches(500.0, "400..600")); assertFalse(numericMatches(500.0, ">500"))
        assertFalse(numericMatches(500.0, "invalid"))
    }

    @Test fun interactivePagingUsesOnlySupportedSizesAndNeverExceedsTwoHundredFifty() {
        assertEquals(listOf(25, 50, 100, 200, 250), LOGBOOK_PAGE_SIZES)
        assertEquals(50, normalizedLogbookPageSize(65_000))
        assertEquals(50, normalizedLogbookPageSize(50))
        assertEquals(3, logbookPageCount(101, 50))
        assertEquals(1, logbookPageCount(0, 1_000))
        assertEquals(1_000, WAVELOG_SYNC_PAGE_SIZE)
    }
}
