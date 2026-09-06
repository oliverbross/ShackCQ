package app.shackcq.mobile

import app.shackcq.mobile.hamclock.DxNewsItem
import app.shackcq.mobile.hamclock.DxNewsRepository
import app.shackcq.mobile.hamclock.HamClockClusterPreference
import app.shackcq.mobile.hamclock.HamClockFeedState
import app.shackcq.mobile.hamclock.HamClockHttpClient
import app.shackcq.mobile.hamclock.HamClockHttpResponse
import app.shackcq.mobile.hamclock.HamClockInFlightCoalescer
import app.shackcq.mobile.hamclock.HamClockPskDirection
import app.shackcq.mobile.hamclock.HamClockPskPreference
import app.shackcq.mobile.hamclock.HamClockSettingsCodec
import app.shackcq.mobile.hamclock.HamClockSettingsDocument
import app.shackcq.mobile.hamclock.HamClockSpotFilter
import app.shackcq.mobile.hamclock.HamClockUserSettings
import app.shackcq.mobile.hamclock.filterPskReports
import app.shackcq.mobile.hamclock.markMutual
import app.shackcq.mobile.hamclock.mergeDxNews
import app.shackcq.mobile.hamclock.parsePskReporterPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class Task2B1DxNewsPskTest {
    @Test
    fun typedConnectedClusterWithNoVisibleSpotsIsEmptyAndFiltersAreBounded() {
        val truth = ClusterConnectionTruth(ClusterConnectionState.CONNECTED, "cluster.example:7300",
            900, 900, 995)
        assertEquals(HamClockMapSourceState.EMPTY, clusterMapState(truth, 0, 30, 1_000))
        val preference = HamClockClusterPreference(windowMinutes = 10, maximumSpots = 1,
            filter = HamClockSpotFilter(bands = setOf("20M"), modes = setOf("FT8"), continents = setOf("OC"), callQuery = "VK"))
        val rows = filterClusterPresentation(listOf(spot("VK3ABC", 995), spot("W1AW", 994)), preference, 1_000)
        assertEquals(listOf("VK3ABC"), rows.map(AndroidDXSpot::callsign))
    }

    @Test
    fun homeScopeExplicitlyStopsLegacySatelliteTicker() {
        assertTrue(NeuralDxRefreshScope.HOME.stopsLegacySatelliteTicker())
        assertTrue(NeuralDxRefreshScope.FULL_DX.stopsLegacySatelliteTicker())
    }

    @Test
    fun exactSignalRequestResolvesAndExpiredRequestIsConsumedOnce() {
        val report = report("VK3ABC", SignalDirection.BEING_HEARD, "20m", 100)
        val found = consumeSignalRequest(signalReportReference(report), listOf(report))
        assertEquals(report, found.report)
        assertEquals("", found.message)
        val expired = consumeSignalRequest("missing", listOf(report))
        assertNull(expired.report)
        assertTrue(expired.message.contains("consumed"))
    }

    @Test
    fun noaaReaderRejectsNonHttpsNon2xxWrongTypeAndOversize() {
        val ok = FeatureHttpTransport { url, _ -> FeatureHttpResponse(200, "{}".toByteArray(), "application/json", url) }
        assertEquals("{}", boundedFeatureText(ok, "https://services.swpc.noaa.gov/test", 8))
        assertTrue(runCatching { boundedFeatureText(ok, "http://services.swpc.noaa.gov/test", 8) }.isFailure)
        assertTrue(runCatching { boundedFeatureText(FeatureHttpTransport { url, _ -> FeatureHttpResponse(500, byteArrayOf(), "application/json", url) }, "https://services.swpc.noaa.gov/test", 8) }.isFailure)
        assertTrue(runCatching { boundedFeatureText(FeatureHttpTransport { url, _ -> FeatureHttpResponse(200, byteArrayOf(), "text/html", url) }, "https://services.swpc.noaa.gov/test", 8) }.isFailure)
        assertTrue(runCatching { boundedFeatureText(FeatureHttpTransport { url, _ -> FeatureHttpResponse(200, ByteArray(9), "application/json", url) }, "https://services.swpc.noaa.gov/test", 8) }.isFailure)
    }

    @Test
    fun newsMergeKeepsDistinctStoriesForOneCallAndRemovesTrueDuplicates() {
        val now = 1_000_000L
        val first = news("a", "https://example.test/a", "VK9DX begins activity", now, "VK9DX")
        val duplicate = news("b", "https://example.test/a?utm_source=x", "VK9DX begins activity", now - 10, "VK9DX")
        val distinct = news("c", "https://example.test/c", "VK9DX antenna failure delays 40m", now - 20, "VK9DX")
        val noCall = news("d", "https://example.test/d", "Propagation bulletin", now - 30, "")
        val merged = mergeDxNews(listOf(first, duplicate, distinct, noCall), now)
        assertEquals(setOf("a", "c", "d"), merged.map(DxNewsItem::id).toSet())
    }

    @Test
    fun malformedDxWorldCannotReplaceLastGoodNews() {
        val directory = Files.createTempDirectory("shackcq-news-test").toFile()
        try {
            val now = 1_700_000_000L
            val published = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC))
            val valid = "<rss><channel><item><title>VK9DX – Island</title><link>https://www.dx-world.net/vk9dx/</link><pubDate>$published</pubDate><description>20m FT8</description></item></channel></rss>"
            val responses = ArrayDeque(listOf(
                HamClockHttpResponse(valid, contentType = "application/rss+xml", effectiveUrl = "https://www.dx-world.net/feed/"),
                HamClockHttpResponse("<html>error</html>", contentType = "application/rss+xml", effectiveUrl = "https://www.dx-world.net/feed/"),
            ))
            val repo = DxNewsRepository(directory, HamClockHttpClient { responses.removeFirst() }, HamClockInFlightCoalescer())
            val first = repo.refresh(null, nowEpoch = now).sources.first { it.id == "dxworld" }
            val fallback = repo.refresh(null, force = true, nowEpoch = now + 601).sources.first { it.id == "dxworld" }
            assertEquals(1, first.items.size)
            assertEquals(1, fallback.items.size)
            assertEquals(HamClockFeedState.STALE, fallback.state)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun directPskDirectionsPreserveSenderAndReceiverSemantics() {
        val now = 1_000L
        val body = "rwPsk({\"receptionReport\":[{\"senderCallsign\":\"OM0RX\",\"senderLocator\":\"JN88TQ\",\"receiverCallsign\":\"VK3ABC\",\"receiverLocator\":\"QF22\",\"frequency\":14074000,\"flowStartSeconds\":990,\"mode\":\"FT8\",\"sNR\":\"-12\"}]});"
        val heard = parsePskReporterPayload(body, "rwPsk", SignalDirection.BEING_HEARD, "OM0RX", null, now, 2).single()
        val hearingBody = "rwPsk({\"receptionReport\":[{\"senderCallsign\":\"VK3ABC\",\"senderLocator\":\"QF22\",\"receiverCallsign\":\"OM0RX\",\"receiverLocator\":\"JN88TQ\",\"frequency\":14074000,\"flowStartSeconds\":990,\"mode\":\"FT8\",\"sNR\":\"-12\"}]});"
        val hearing = parsePskReporterPayload(hearingBody, "rwPsk", SignalDirection.HEARING, "OM0RX", null, now, 2).single()
        assertEquals("OM0RX", heard.senderCallsign); assertEquals("VK3ABC", heard.receiverCallsign)
        assertEquals("VK3ABC", hearing.senderCallsign); assertEquals("OM0RX", hearing.receiverCallsign)
        assertEquals("VK3ABC", heard.callsign); assertEquals("VK3ABC", hearing.callsign)
    }

    @Test
    fun mutualRequiresSameRemoteCallAndBand() {
        val heard = listOf(report("VK3ABC", SignalDirection.BEING_HEARD, "20m", 100))
        val hearing = listOf(report("VK3ABC", SignalDirection.HEARING, "40m", 101),
            report("VK3ABC", SignalDirection.HEARING, "20m", 102))
        val marked = markMutual(heard, hearing)
        assertTrue(marked.filter { it.band == "20m" }.all(SignalReport::mutual))
        assertFalse(marked.first { it.band == "40m" }.mutual)
    }

    @Test
    fun pskSettingsRoundTripAndDisabledPreferenceStopsPresentation() {
        val preference = HamClockPskPreference(enabled = false, direction = HamClockPskDirection.MUTUAL,
            windowMinutes = 120, refreshSeconds = 300, maximumReports = 500,
            filter = HamClockSpotFilter(bands = setOf("20M"), modes = setOf("FT8"), continents = setOf("OC"), callQuery = "VK", minimumSnr = -15))
        val decoded = HamClockSettingsCodec.decode(HamClockSettingsCodec.encode(
            HamClockSettingsDocument(settings = HamClockUserSettings(pskReporter = preference))))
        assertEquals(preference, decoded.settings.pskReporter)
        assertTrue(filterPskReports(listOf(report("VK3ABC", SignalDirection.BEING_HEARD, "20m", Instant.now().epochSecond)), preference).isEmpty())
    }

    private fun report(call: String, direction: SignalDirection, band: String, epoch: Long) = SignalReport(call, "QF22",
        -37.5, 145.0, if (band == "20m") 14_074_000 else 7_074_000, band, "FT8", -10, 1_000, epoch,
        direction, "OM0RX", if (direction == SignalDirection.BEING_HEARD) "OM0RX" else call, "JN88TQ",
        if (direction == SignalDirection.BEING_HEARD) call else "OM0RX", "QF22")

    private fun news(id: String, link: String, title: String, epoch: Long, call: String) = DxNewsItem(title, link,
        publishedEpoch = epoch, callsigns = call.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(), id = id,
        sourceId = "dxworld", sourceLabel = "DX-World", sourceHomeUrl = "https://www.dx-world.net/")

    private fun spot(call: String, epoch: Long) = AndroidDXSpot(call, call, "SPOTTER", 14_074_000, epoch, "20m", "FT8",
        "Australia", "OC", 30, 55, -37.5, 145.0, "", 0, 0, 1, false,
        false, false, false, false, false, false, 100, 0, "", "")
}
