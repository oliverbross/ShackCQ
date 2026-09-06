package app.shackcq.mobile

import app.shackcq.mobile.hamclock.ContestCalendarProvider
import app.shackcq.mobile.hamclock.HamClockFeedState
import app.shackcq.mobile.hamclock.HamClockHttpClient
import app.shackcq.mobile.hamclock.HamClockHttpResponse
import app.shackcq.mobile.hamclock.parseContestCalendarRss
import app.shackcq.mobile.hamclock.parseNg3kDxpeditions
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset

class OperationsPlannerTest {
    @Test fun dxCalendarHandlesCrossYearRange() {
        val now = Instant.parse("2026-12-20T00:00:00Z").epochSecond
        val row = parseNg3kDxpeditions("Dec 28-Jan 4 DXCC: Island Callsign: ZL9XY QSL: LOTW Info: 20m CW", now).single()
        assertEquals(2026, Instant.ofEpochSecond(row.startEpoch!!).atZone(ZoneOffset.UTC).year)
        assertEquals(2027, Instant.ofEpochSecond(row.endEpoch!!).atZone(ZoneOffset.UTC).year)
    }

    @Test fun contestHandlesMidnightAndYearRollover() {
        val now = Instant.parse("2026-12-20T00:00:00Z").epochSecond
        val row = parseContestCalendarRss("<item><title>New Year</title><description>2200Z, Dec 31 to 0200Z, Jan 1</description></item>", now).single()
        assertTrue(row.endEpoch > row.startEpoch)
        assertEquals(2027, Instant.ofEpochSecond(row.endEpoch).atZone(ZoneOffset.UTC).year)
    }

    @Test fun failedRefreshPreservesLastGoodContestCache() {
        val dir = Files.createTempDirectory("operations-cache").toFile(); var fail = false
        val provider = ContestCalendarProvider(dir, HamClockHttpClient {
            if (fail) error("offline") else HamClockHttpResponse("<item><title>Cache CW</title><description>0000Z, Aug 19 to 2359Z, Aug 20</description></item>")
        })
        val now = Instant.parse("2026-08-19T12:00:00Z").epochSecond
        assertEquals(HamClockFeedState.LIVE, provider.refresh(true, now).state)
        fail = true
        val fallback = provider.refresh(true, now + 1)
        assertEquals(HamClockFeedState.STALE, fallback.state)
        assertEquals("Cache CW", fallback.value.single().name)
    }

    @Test fun activationPlanRoundTrips() {
        val plan = ActivationPlan(title = "Park", program = "POTA", references = listOf("AU-1234"), grid = "JN88TQ",
            latitude = 48.7, longitude = 16.6, startEpoch = 1_787_000_000, notes = "portable")
        assertEquals(plan, decodeActivationPlan(encodeActivationPlan(plan)))
    }

    @Test fun potaHandoffPreservesPlanButNeverAcknowledgesOrStarts() {
        val plan = ActivationPlan(title = "Park", program = "POTA", references = listOf("AU-1234"), grid = "JN88TQ",
            latitude = 48.7, longitude = 16.6, startEpoch = 1_787_000_000)
        val setup = potaSetupForActivationPlan(plan, "OM0RX")
        assertEquals(plan.references, setup.references)
        assertEquals(plan.startEpoch, setup.startAt)
        assertFalse(setup.boundaryAcknowledged)
    }
}
