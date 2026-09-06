package app.shackcq.mobile.hamclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HamClockPublicProvidersTest {
    @Test fun contestRssNormalizesStatusModeAndDates() {
        val now = Instant.parse("2026-08-19T12:00:00Z").epochSecond
        val rows = parseContestCalendarRss(
            """<rss><channel>
                |<item><title><![CDATA[Example CW Sprint]]></title><link>https://example.test/cw</link><description>0000Z, Aug 19 to 2359Z, Aug 19</description></item>
                |<item><title>Digital Test</title><link>https://example.test/digi</link><description>1300Z-1500Z, Aug 20</description></item>
                |<item><title>Old Phone</title><description>0000Z-0100Z, Aug 1</description></item>
                |</channel></rss>""".trimMargin(), now)
        assertEquals(2, rows.size)
        assertEquals(HamClockContestStatus.ACTIVE, rows[0].status)
        assertEquals("CW", rows[0].mode)
        assertEquals(HamClockContestStatus.UPCOMING, rows[1].status)
        assertEquals("Digital", rows[1].mode)
    }

    @Test fun contestRssHandlesDecemberToJanuaryRollover() {
        val now = Instant.parse("2026-12-20T00:00:00Z").epochSecond
        val row = parseContestCalendarRss("""<item><title>New Year Contest</title>
            |<description>2200Z, Dec 31 to 0200Z, Jan 1</description></item>""".trimMargin(), now).single()
        assertEquals(2026, Instant.ofEpochSecond(row.startEpoch).atZone(ZoneOffset.UTC).year)
        assertEquals(2027, Instant.ofEpochSecond(row.endEpoch).atZone(ZoneOffset.UTC).year)
    }

    @Test fun ng3kParserExtractsOperatingCallsAndSchedule() {
        val now = Instant.parse("2026-08-19T12:00:00Z").epochSecond
        val html = """<html><body>
            |Aug 18-25, 2026 DXCC: Example Island Callsign: AB1 QSL: M0QSL Info: Team active as AB1CD and as AB1EF on 40m 20m CW FT8
            |Sep 2-9, 2026 DXCC: Other Place Callsign: ZL9XY QSL: LOTW Info: 20m SSB
            |</body></html>""".trimMargin()
        val rows = parseNg3kDxpeditions(html, now)
        assertEquals(listOf("AB1CD", "AB1EF", "ZL9XY"), rows.map(HamClockDxpedition::callsign))
        assertEquals(HamClockDxpeditionStatus.ACTIVE, rows.first().status)
        assertEquals(setOf("40m", "20m"), rows.first().bands)
        assertEquals(setOf("CW", "FT8"), rows.first().modes)
        assertEquals(HamClockDxpeditionStatus.UPCOMING, rows.last().status)
    }

    @Test fun goesParserKeepsPrimaryLongChannelAndComputesClass() {
        val now = Instant.parse("2026-08-19T12:00:00Z").epochSecond
        val rows = """[
            |{"time_tag":"2026-08-19T11:58:00Z","satellite":19,"flux":2.5e-6,"energy":"0.1-0.8nm"},
            |{"time_tag":"2026-08-19T11:59:00Z","satellite":19,"flux":1.2e-5,"energy":"0.1-0.8nm"},
            |{"time_tag":"2026-08-19T11:59:00Z","satellite":19,"flux":9.0e-4,"energy":"0.05-0.4nm"}
            |]""".trimMargin()
        val series = parseGoesXray(rows, now)
        assertEquals(2, series.points.size)
        assertEquals("M1.2", series.currentClass)
        assertEquals("M1.2", series.peakClass)
    }

    @Test fun moonAndSunCalculationsRemainAvailableOffline() {
        val fullEpoch = Instant.parse("2000-01-21T12:35:00Z").epochSecond
        val moon = moonSnapshot(fullEpoch)
        assertEquals(HamClockMoonPhaseName.FULL, moon.name)
        assertTrue(moon.illumination > 0.99)

        val sun = sunTimes(LocalDate.of(2026, 3, 20), 40.7128, -74.0060)
        assertEquals(HamClockDaylightState.NORMAL, sun.state)
        assertNotNull(sun.sunriseEpoch)
        assertNotNull(sun.sunsetEpoch)
        val riseHour = Instant.ofEpochSecond(sun.sunriseEpoch!!).atZone(ZoneOffset.UTC).hour
        val setHour = Instant.ofEpochSecond(sun.sunsetEpoch!!).atZone(ZoneOffset.UTC).hour
        assertTrue(riseHour in 10..11)
        assertTrue(setHour in 22..23)

        val arctic = sunTimes(LocalDate.of(2026, 6, 21), 80.0, 0.0)
        assertEquals(HamClockDaylightState.MIDNIGHT_SUN, arctic.state)
    }

    @Test fun failedRefreshReturnsValidatedLastGoodContestCache() {
        val directory = Files.createTempDirectory("hamclock-provider-test").toFile()
        var fail = false
        val client = HamClockHttpClient {
            if (fail) error("network down")
            HamClockHttpResponse("""<item><title>Cache Test CW</title><link>https://example.test</link>
                |<description>0000Z, Aug 19 to 2359Z, Aug 20</description></item>""".trimMargin())
        }
        val provider = ContestCalendarProvider(directory, client)
        val now = Instant.parse("2026-08-19T12:00:00Z").epochSecond
        assertEquals(HamClockFeedState.LIVE, provider.refresh(force = true, nowEpoch = now).state)
        fail = true
        val fallback = provider.refresh(force = true, nowEpoch = now + 60)
        assertEquals(HamClockFeedState.STALE, fallback.state)
        assertEquals("Cache Test CW", fallback.value.single().name)
        assertTrue(fallback.error.contains("network down"))
    }

    @Test fun solarImageMetadataHasPrimaryAndIndependentFallbacks() {
        val images = solarImageMetadata(Instant.parse("2026-08-19T12:00:00Z").epochSecond)
        assertEquals(setOf("0193", "0304", "0171", "0094", "HMIIC"), images.map { it.channel }.toSet())
        assertTrue(images.all { it.primaryUrl.startsWith("https://sdo.gsfc.nasa.gov/") })
        assertTrue(images.all { it.fallbackUrls.isNotEmpty() && it.fallbackUrls.all { url -> url.startsWith("https://") } })
    }
}
