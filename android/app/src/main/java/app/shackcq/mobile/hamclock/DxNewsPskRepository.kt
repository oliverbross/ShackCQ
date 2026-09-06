package app.shackcq.mobile.hamclock

import app.shackcq.mobile.GeoPoint
import app.shackcq.mobile.SignalDirection
import app.shackcq.mobile.SignalReport
import app.shackcq.mobile.bandForFrequency
import app.shackcq.mobile.extractCallsigns
import app.shackcq.mobile.maidenheadCenter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class DxNewsItem(
    val title: String,
    val link: String,
    val published: String = "",
    val summary: String = "",
    val callsigns: List<String> = emptyList(),
    val imageUrl: String = "",
    val id: String = "",
    val publishedEpoch: Long = 0,
    val activityEndEpoch: Long? = null,
    val sourceId: String = "",
    val sourceLabel: String = "",
    val sourceHomeUrl: String = "",
    val bands: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    val entity: String = "",
)

internal data class DxNewsSource(
    val id: String,
    val name: String,
    val site: String,
    val items: List<DxNewsItem> = emptyList(),
    val updatedEpoch: Long = 0,
    val stale: Boolean = false,
    val error: String = "",
    val state: HamClockFeedState = HamClockFeedState.UNAVAILABLE,
)

internal data class DxNewsSnapshot(
    val sources: List<DxNewsSource> = emptyList(),
    val merged: List<DxNewsItem> = emptyList(),
    val fetchedEpoch: Long = 0,
    val manualCooldownSeconds: Long = 0,
) {
    val error: String get() = sources.map(DxNewsSource::error).filter(String::isNotBlank).joinToString(" · ")
}

internal class DxNewsRepository(
    cacheDirectory: File,
    private val http: HamClockHttpClient,
    private val coalescer: HamClockInFlightCoalescer,
) {
    private val dxWorldCache = HamClockLastGoodCache(cacheDirectory, "dx-news-dxworld")
    @Volatile private var lastManualEpoch = 0L

    fun refresh(
        ng3k: HamClockFeed<List<HamClockDxpedition>>?,
        force: Boolean = false,
        nowEpoch: Long = Instant.now().epochSecond,
    ): DxNewsSnapshot {
        val remaining = (MANUAL_SECONDS - (nowEpoch - lastManualEpoch)).coerceAtLeast(0)
        val manualBlocked = force && lastManualEpoch > 0 && remaining > 0
        val dxWorld = if (manualBlocked) cachedDxWorld(nowEpoch, "Manual refresh available in ${remaining}s")
        else {
            if (force) lastManualEpoch = nowEpoch
            coalescer.run("dx-news-dxworld") { refreshDxWorld(force = force, nowEpoch = nowEpoch) }
        }
        val dxNews = DxNewsSource(
            "dxnews", "DXNews.com", DXNEWS_HOME, error = "UNAVAILABLE · no stable direct structured contract",
            state = HamClockFeedState.UNAVAILABLE,
        )
        val ng3kSource = ng3kSource(ng3k, nowEpoch)
        val sources = listOf(dxWorld, dxNews, ng3kSource)
        return DxNewsSnapshot(sources, mergeDxNews(sources.flatMap(DxNewsSource::items), nowEpoch),
            sources.maxOfOrNull(DxNewsSource::updatedEpoch) ?: 0, if (manualBlocked) remaining else 0)
    }

    private fun cachedDxWorld(nowEpoch: Long, error: String): DxNewsSource {
        val saved = dxWorldCache.read() ?: return DxNewsSource("dxworld", "DX-World", DXWORLD_HOME,
            error = error, state = HamClockFeedState.UNAVAILABLE)
        val state = if (nowEpoch - saved.fetchedAtEpoch < TTL_SECONDS) HamClockFeedState.CACHED else HamClockFeedState.STALE
        return DxNewsSource("dxworld", "DX-World", DXWORLD_HOME, decodeNews(saved.body), saved.fetchedAtEpoch,
            state == HamClockFeedState.STALE, error, state)
    }

    private fun refreshDxWorld(force: Boolean, nowEpoch: Long): DxNewsSource {
        val saved = dxWorldCache.read()
        fun cached(entry: HamClockCacheEntry, state: HamClockFeedState, error: String = "") =
            DxNewsSource("dxworld", "DX-World", DXWORLD_HOME, decodeNews(entry.body), entry.fetchedAtEpoch,
                state == HamClockFeedState.STALE, error, state)
        if (!force && saved != null && nowEpoch - saved.fetchedAtEpoch < TTL_SECONDS) {
            return cached(saved, HamClockFeedState.CACHED)
        }
        return try {
            val response = try {
                http.get(HamClockHttpRequest(DXWORLD_FEED, "application/rss+xml, application/xml, text/xml",
                    MAX_NEWS_BYTES, saved?.etag.orEmpty(), saved?.lastModified.orEmpty()))
            } catch (_: HamClockNotModified) {
                val entry = requireNotNull(saved)
                dxWorldCache.write(entry.body, nowEpoch, entry.etag, entry.lastModified)
                return cached(entry.copy(fetchedAtEpoch = nowEpoch), HamClockFeedState.LIVE)
            }
            require(response.contentType.isBlank() || response.contentType.contains("xml", true) ||
                response.contentType.contains("rss", true)) { "DX-World returned unexpected content type" }
            val rows = parseDxWorldRss(response.body, nowEpoch)
            require(rows.isNotEmpty()) { "DX-World contained no usable RSS items" }
            val body = encodeNews(rows)
            dxWorldCache.write(body, nowEpoch, response.etag, response.lastModified)
            DxNewsSource("dxworld", "DX-World", DXWORLD_HOME, rows, nowEpoch, state = HamClockFeedState.LIVE)
        } catch (error: Exception) {
            if (saved != null) cached(saved, HamClockFeedState.STALE, providerError(error))
            else DxNewsSource("dxworld", "DX-World", DXWORLD_HOME, error = providerError(error),
                state = HamClockFeedState.UNAVAILABLE)
        }
    }

    private fun ng3kSource(feed: HamClockFeed<List<HamClockDxpedition>>?, nowEpoch: Long): DxNewsSource {
        if (feed == null) return DxNewsSource("ng3k", "NG3K ADXO", NG3K_HOME,
            error = "NG3K shared cache has not refreshed", state = HamClockFeedState.UNAVAILABLE)
        val rows = feed.value.map { row ->
            DxNewsItem(
                title = listOf(row.callsign, row.entity).filter(String::isNotBlank).joinToString(" · "),
                link = row.sourceUrl.ifBlank { NG3K_HOME }, published = row.dateText,
                summary = row.information, callsigns = listOf(row.callsign),
                id = "ng3k:${row.callsign}:${row.startEpoch ?: 0}", publishedEpoch = row.startEpoch ?: 0,
                activityEndEpoch = row.endEpoch, sourceId = "ng3k", sourceLabel = "NG3K ADXO",
                sourceHomeUrl = NG3K_HOME, bands = row.bands, modes = row.modes, entity = row.entity,
            )
        }
        return DxNewsSource("ng3k", "NG3K ADXO", NG3K_HOME, rows, feed.fetchedAtEpoch,
            feed.state == HamClockFeedState.STALE, feed.error, feed.state)
    }

    private companion object {
        const val DXWORLD_FEED = "https://www.dx-world.net/feed/"
        const val DXWORLD_HOME = "https://www.dx-world.net/"
        const val DXNEWS_HOME = "https://dxnews.com/"
        const val NG3K_HOME = "https://www.ng3k.com/Misc/adxo.html"
        const val MAX_NEWS_BYTES = 1_000_000
        const val TTL_SECONDS = 30 * 60L
        const val MANUAL_SECONDS = 10 * 60L
    }
}

internal fun parseDxWorldRss(body: String, nowEpoch: Long): List<DxNewsItem> {
    require(!body.contains("<html", true)) { "DX-World returned HTML instead of RSS" }
    return Regex("<item\\b[\\s\\S]*?</item>", RegexOption.IGNORE_CASE).findAll(body).mapNotNull { match ->
        val block = match.value
        val title = xmlText(block, "title").take(180)
        val link = xmlText(block, "link").takeIf(::isHttpsUrl).orEmpty()
        if (title.length < 4 || link.isBlank()) return@mapNotNull null
        val published = xmlText(block, "pubDate")
        val epoch = parseNewsEpoch(published) ?: return@mapNotNull null
        val summary = stripMarkup(xmlText(block, "description")).take(480)
        val calls = extractNewsCallsigns("$title $summary")
        DxNewsItem(title, link, published, summary, calls,
            id = "dxworld:${canonicalArticleUrl(link).hashCode()}", publishedEpoch = epoch,
            sourceId = "dxworld", sourceLabel = "DX-World", sourceHomeUrl = "https://www.dx-world.net/",
            bands = extractBands("$title $summary"), modes = extractModes("$title $summary"))
    }.distinctBy { canonicalArticleUrl(it.link) }.take(80).toList()
}

internal fun mergeDxNews(items: List<DxNewsItem>, nowEpoch: Long, newsWindowHours: Int = 24): List<DxNewsItem> {
    val fresh = items.filter { item ->
        if (item.sourceId == "ng3k") item.publishedEpoch > 0 && (item.activityEndEpoch == null || item.activityEndEpoch >= nowEpoch)
        else item.publishedEpoch in (nowEpoch - newsWindowHours.coerceIn(24, 72) * 3600L)..(nowEpoch + 300)
    }.sortedByDescending(DxNewsItem::publishedEpoch)
    val kept = mutableListOf<DxNewsItem>()
    fresh.forEach { candidate ->
        val duplicate = kept.any { existing ->
            canonicalArticleUrl(existing.link).isNotBlank() && canonicalArticleUrl(existing.link) == canonicalArticleUrl(candidate.link) ||
                existing.sourceId == candidate.sourceId && normalizedNewsTitle(existing.title) == normalizedNewsTitle(candidate.title) ||
                existing.callsigns.intersect(candidate.callsigns.toSet()).isNotEmpty() &&
                    kotlin.math.abs(existing.publishedEpoch - candidate.publishedEpoch) <= 72 * 3600L &&
                    titleSimilarity(existing.title, candidate.title) >= .72
        }
        if (!duplicate) kept += candidate
    }
    return kept.take(40)
}

internal fun extractNewsCallsigns(text: String): List<String> {
    val denied = setOf("NEWS", "DXCC", "QSL", "IOTA", "POTA", "SOTA", "WWFF", "CQ", "UTC", "TEST", "RADIO")
    return extractCallsigns(text).asSequence().map { it.uppercase(Locale.US) }
        .filter { it !in denied && Regex("^(?:[A-Z0-9]{1,4}/)?[A-Z0-9]{1,3}\\d[A-Z]{1,4}(?:/[A-Z0-9]{1,4})?$").matches(it) }
        .distinct().take(8).toList()
}

private fun normalizedNewsTitle(value: String) = stripMarkup(value).lowercase(Locale.US)
    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

private fun titleSimilarity(a: String, b: String): Double {
    val left = normalizedNewsTitle(a).split(' ').filter { it.length > 2 }.toSet()
    val right = normalizedNewsTitle(b).split(' ').filter { it.length > 2 }.toSet()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    return left.intersect(right).size.toDouble() / left.union(right).size
}

private fun canonicalArticleUrl(value: String): String = runCatching {
    val uri = URI(value)
    if (!uri.scheme.equals("https", true)) return@runCatching ""
    URI("https", uri.authority?.lowercase(Locale.US), uri.path?.trimEnd('/').orEmpty(), null, null).toString()
}.getOrDefault("")

private fun isHttpsUrl(value: String) = runCatching { URI(value).scheme.equals("https", true) }.getOrDefault(false)
private fun xmlText(block: String, tag: String) = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
    .find(block)?.groupValues?.get(1).orEmpty().removePrefix("<![CDATA[").removeSuffix("]]>").let(::stripMarkup)
private fun stripMarkup(value: String) = value.replace(Regex("<[^>]+>"), " ")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    .replace(Regex("\\s+"), " ").trim()
private fun parseNewsEpoch(value: String): Long? = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
private fun extractBands(value: String) = Regex("\\b(?:2200|630|160|80|60|40|30|20|17|15|12|10|8|6|4|2)m\\b", RegexOption.IGNORE_CASE)
    .findAll(value).map { it.value.lowercase(Locale.US) }.toSet()
private fun extractModes(value: String) = Regex("\\b(CW|SSB|FT8|FT4|RTTY|PSK31|PSK|FM|AM|DIGI)\\b", RegexOption.IGNORE_CASE)
    .findAll(value).map { it.value.uppercase(Locale.US) }.toSet()

private fun encodeNews(rows: List<DxNewsItem>) = JSONArray(rows.map { row -> JSONObject()
    .put("title", row.title).put("link", row.link).put("published", row.published).put("summary", row.summary)
    .put("calls", JSONArray(row.callsigns)).put("id", row.id).put("epoch", row.publishedEpoch)
    .put("source", row.sourceId).put("label", row.sourceLabel).put("home", row.sourceHomeUrl)
    .put("bands", JSONArray(row.bands.toList())).put("modes", JSONArray(row.modes.toList())) }).toString()

private fun decodeNews(body: String): List<DxNewsItem> = buildList {
    val rows = JSONArray(body)
    for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { row ->
        fun strings(name: String) = row.optJSONArray(name)?.let { values ->
            buildList { for (i in 0 until values.length()) values.optString(i).takeIf(String::isNotBlank)?.let(::add) }
        }.orEmpty()
        add(DxNewsItem(row.optString("title"), row.optString("link"), row.optString("published"),
            row.optString("summary"), strings("calls"), id = row.optString("id"), publishedEpoch = row.optLong("epoch"),
            sourceId = row.optString("source"), sourceLabel = row.optString("label"), sourceHomeUrl = row.optString("home"),
            bands = strings("bands").toSet(), modes = strings("modes").toSet()))
    }
}

internal data class PskDirectionFeed(
    val direction: SignalDirection,
    val reports: List<SignalReport> = emptyList(),
    val state: HamClockFeedState = HamClockFeedState.UNAVAILABLE,
    val fetchedEpoch: Long = 0,
    val error: String = "",
)

internal data class PskReporterSnapshot(
    val callsign: String = "",
    val beingHeard: PskDirectionFeed = PskDirectionFeed(SignalDirection.BEING_HEARD),
    val hearing: PskDirectionFeed = PskDirectionFeed(SignalDirection.HEARING),
) {
    val reports: List<SignalReport> get() = markMutual(beingHeard.reports, hearing.reports)
    val sourceState: HamClockFeedState get() = combinedSignalFeedState(beingHeard.state, hearing.state)
}

internal class PskReporterRepository(
    private val cacheDirectory: File,
    private val http: HamClockHttpClient,
    private val coalescer: HamClockInFlightCoalescer,
) {
    private val lastManual = mutableMapOf<String, Long>()
    private val backoffUntil = mutableMapOf<String, Long>()

    fun reproject(snapshot: PskReporterSnapshot, station: GeoPoint?): PskReporterSnapshot {
        fun project(feed: PskDirectionFeed) = feed.copy(reports = feed.reports.map { report ->
            val distance = station?.let { local -> report.latitude?.let { latitude -> report.longitude?.let { longitude ->
                greatCircleKm(local, GeoPoint(latitude, longitude)).roundToInt()
            } } }
            report.copy(distanceKm = distance)
        })
        return snapshot.copy(beingHeard = project(snapshot.beingHeard), hearing = project(snapshot.hearing))
    }

    fun refresh(callsign: String, station: GeoPoint?, windowMinutes: Int, force: Boolean = false, mode: String = "",
        nowEpoch: Long = Instant.now().epochSecond): PskReporterSnapshot {
        val call = callsign.trim().uppercase(Locale.US)
        require(call.isNotBlank()) { "Station callsign is required" }
        val window = windowMinutes.takeIf { it in setOf(2, 5, 10, 15, 30, 60, 120) } ?: 15
        val normalizedMode = mode.trim().uppercase(Locale.US).take(12)
        return coalescer.run("psk:$call:$window:$normalizedMode") {
            PskReporterSnapshot(call,
                refreshDirection(SignalDirection.BEING_HEARD, call, station, window, force, nowEpoch, normalizedMode),
                refreshDirection(SignalDirection.HEARING, call, station, window, force, nowEpoch, normalizedMode))
        }
    }

    private fun refreshDirection(direction: SignalDirection, call: String, station: GeoPoint?, window: Int,
        force: Boolean, nowEpoch: Long, mode: String): PskDirectionFeed {
        val safeCall = call.replace(Regex("[^A-Z0-9]"), "_")
        val modeKey = mode.ifBlank { "all" }.lowercase(Locale.US)
        val key = "${direction.name}:$safeCall:$window:$modeKey"
        val cache = HamClockLastGoodCache(cacheDirectory, "psk-${direction.name.lowercase()}-$safeCall-$window-$modeKey")
        val saved = cache.read()
        fun cached(entry: HamClockCacheEntry, state: HamClockFeedState, error: String = "") =
            PskDirectionFeed(direction, decodePskReports(entry.body), state, entry.fetchedAtEpoch, error)
        val manualAllowed = force && nowEpoch - (lastManual[key] ?: 0) >= MANUAL_SECONDS
        if (manualAllowed) lastManual[key] = nowEpoch
        if (force && !manualAllowed && (lastManual[key] ?: 0) > 0) {
            val remaining = (MANUAL_SECONDS - (nowEpoch - (lastManual[key] ?: 0))).coerceAtLeast(1)
            return if (saved != null) cached(saved, HamClockFeedState.STALE, "Manual refresh available in ${remaining}s")
            else PskDirectionFeed(direction, error = "Manual refresh available in ${remaining}s")
        }
        if (nowEpoch < (backoffUntil[key] ?: 0)) {
            return if (saved != null) cached(saved, HamClockFeedState.STALE, "Provider backoff active")
            else PskDirectionFeed(direction, error = "Provider backoff active")
        }
        if ((!force || !manualAllowed) && saved != null && nowEpoch - saved.fetchedAtEpoch < TTL_SECONDS) {
            return cached(saved, HamClockFeedState.CACHED)
        }
        return try {
            val parameter = if (direction == SignalDirection.BEING_HEARD) "senderCallsign" else "receiverCallsign"
            val callback = "rwPsk"
            val query = "$parameter=${URLEncoder.encode(call, "UTF-8")}&flowStartSeconds=-${window * 60}" +
                "&rptlimit=500&rronly=1&noactive=1&nolocator=1" +
                mode.takeIf(String::isNotBlank)?.let { "&mode=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty() +
                "&callback=$callback"
            val response = http.get(HamClockHttpRequest("https://retrieve.pskreporter.info/query?$query",
                "application/javascript, application/json, text/javascript, text/plain", MAX_PSK_BYTES))
            require(response.contentType.isBlank() || response.contentType.contains("json", true) ||
                response.contentType.contains("javascript", true) || response.contentType.contains("text/plain", true)) {
                "PSK Reporter returned unexpected content type"
            }
            val rows = parsePskReporterPayload(response.body, callback, direction, call, station, nowEpoch, window)
            val body = encodePskReports(rows)
            cache.write(body, nowEpoch, response.etag, response.lastModified)
            PskDirectionFeed(direction, rows, HamClockFeedState.LIVE, nowEpoch)
        } catch (error: Exception) {
            if (error is HamClockHttpException && error.retryAfterSeconds != null) {
                backoffUntil[key] = nowEpoch + error.retryAfterSeconds.coerceIn(60, 3600)
            }
            if (saved != null) cached(saved, HamClockFeedState.STALE, providerError(error))
            else PskDirectionFeed(direction, error = providerError(error))
        }
    }

    private companion object {
        const val MAX_PSK_BYTES = 2_000_000
        const val TTL_SECONDS = 5 * 60L
        const val MANUAL_SECONDS = 5 * 60L
    }
}

internal fun parsePskReporterPayload(body: String, callback: String, direction: SignalDirection,
    localCallsign: String, station: GeoPoint?, nowEpoch: Long, windowMinutes: Int): List<SignalReport> {
    val wrapper = Regex("^\\s*${Regex.escape(callback)}\\s*\\(([\\s\\S]*)\\)\\s*;?\\s*$")
        .matchEntire(body) ?: throw IllegalArgumentException("Malformed PSK Reporter callback")
    val root = JSONObject(wrapper.groupValues[1])
    val raw = root.opt("receptionReport")
    val rows = when (raw) { is JSONArray -> raw; is JSONObject -> JSONArray().put(raw); else -> JSONArray() }
    val earliest = nowEpoch - windowMinutes * 60L - 120
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val sender = row.optString("senderCallsign").trim().uppercase(Locale.US)
            val receiver = row.optString("receiverCallsign").trim().uppercase(Locale.US)
            val epoch = row.optLong("flowStartSeconds")
            val frequency = row.optDouble("frequency", Double.NaN).takeIf(Double::isFinite)?.toLong() ?: 0
            val mode = row.optString("mode").trim().uppercase(Locale.US).take(20)
            if (!validPskCall(sender) || !validPskCall(receiver) || epoch !in earliest..(nowEpoch + 60) ||
                frequency !in 100_000L..100_000_000_000L || mode.isBlank()) continue
            if (direction == SignalDirection.BEING_HEARD && sender != localCallsign ||
                direction == SignalDirection.HEARING && receiver != localCallsign) continue
            val senderGrid = row.optString("senderLocator").trim().uppercase(Locale.US).take(12)
            val receiverGrid = row.optString("receiverLocator").trim().uppercase(Locale.US).take(12)
            val remoteCall = if (direction == SignalDirection.BEING_HEARD) receiver else sender
            val remoteGrid = if (direction == SignalDirection.BEING_HEARD) receiverGrid else senderGrid
            val remotePoint = maidenheadCenter(remoteGrid)
            val band = bandForFrequency(frequency)
            if (band.isBlank()) continue
            val snr = row.optString("sNR").toIntOrNull()?.takeIf { it in -100..100 }
            val report = SignalReport(remoteCall, remoteGrid, remotePoint?.latitude, remotePoint?.longitude,
                frequency, band, mode, snr, null, epoch,
                direction, localCallsign, sender, senderGrid, receiver, receiverGrid, mutual = false)
            add(report)
        }
    }.groupBy { listOf(it.direction.name, it.callsign, it.band, it.mode).joinToString("|") }
        .values.mapNotNull { it.maxByOrNull(SignalReport::epoch) }.sortedByDescending(SignalReport::epoch).take(500)
}

internal fun markMutual(beingHeard: List<SignalReport>, hearing: List<SignalReport>): List<SignalReport> {
    val heardKeys = beingHeard.mapTo(hashSetOf()) { "${it.callsign.uppercase(Locale.US)}|${it.band}" }
    val hearingKeys = hearing.mapTo(hashSetOf()) { "${it.callsign.uppercase(Locale.US)}|${it.band}" }
    val mutual = heardKeys.intersect(hearingKeys)
    return (beingHeard + hearing).map { it.copy(mutual = "${it.callsign.uppercase(Locale.US)}|${it.band}" in mutual) }
        .sortedByDescending(SignalReport::epoch)
}

internal fun filterPskReports(reports: List<SignalReport>, preference: HamClockPskPreference,
    nowEpoch: Long = Instant.now().epochSecond): List<SignalReport> {
    if (!preference.enabled) return emptyList()
    val query = preference.filter.callQuery.trim().uppercase(Locale.US)
    return reports.asSequence().filter { report ->
        nowEpoch - report.epoch <= preference.windowMinutes * 60L &&
            when (preference.direction) {
                HamClockPskDirection.BEING_HEARD -> report.direction == SignalDirection.BEING_HEARD
                HamClockPskDirection.HEARING -> report.direction == SignalDirection.HEARING
                HamClockPskDirection.BOTH -> true
                HamClockPskDirection.MUTUAL -> report.mutual
            } &&
            (preference.filter.bands.isEmpty() || report.band.uppercase(Locale.US) in preference.filter.bands) &&
            (preference.filter.modes.isEmpty() || report.mode.uppercase(Locale.US) in preference.filter.modes) &&
            (preference.filter.continents.isEmpty() || report.continent.uppercase(Locale.US) in preference.filter.continents) &&
            (query.isBlank() || report.callsign.contains(query, true)) &&
            (preference.filter.minimumSnr == null || (report.snr ?: Int.MIN_VALUE) >= preference.filter.minimumSnr)
    }.take(preference.maximumReports).toList()
}

private fun validPskCall(value: String) = value.length in 3..24 && value.any(Char::isDigit) &&
    Regex("^[A-Z0-9]+(?:/[A-Z0-9]+)*$").matches(value)
private fun greatCircleKm(a: GeoPoint, b: GeoPoint): Double {
    val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
    val dp = p2 - p1; val dl = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 6371.0088 * 2 * atan2(sqrt(h), sqrt(1 - h))
}

private fun encodePskReports(rows: List<SignalReport>) = JSONArray(rows.map { row -> JSONObject()
    .put("call", row.callsign).put("locator", row.locator).put("lat", row.latitude).put("lon", row.longitude)
    .put("frequency", row.frequencyHz).put("band", row.band).put("mode", row.mode).put("snr", row.snr)
    .put("epoch", row.epoch).put("direction", row.direction.name)
    .put("local", row.localCallsign).put("sender", row.senderCallsign).put("sender_grid", row.senderLocator)
    .put("receiver", row.receiverCallsign).put("receiver_grid", row.receiverLocator).put("mutual", row.mutual)
    .put("continent", row.continent) }).toString()

private fun decodePskReports(body: String): List<SignalReport> = buildList {
    val rows = JSONArray(body)
    for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { row ->
        add(SignalReport(row.optString("call"), row.optString("locator"), row.optionalDouble("lat"), row.optionalDouble("lon"),
            row.optLong("frequency"), row.optString("band"), row.optString("mode"),
            row.optInt("snr").takeIf { row.has("snr") && !row.isNull("snr") },
            row.optInt("distance").takeIf { row.has("distance") && !row.isNull("distance") }, row.optLong("epoch"),
            enumValues<SignalDirection>().firstOrNull { it.name == row.optString("direction") } ?: SignalDirection.BEING_HEARD,
            row.optString("local"), row.optString("sender"), row.optString("sender_grid"), row.optString("receiver"),
            row.optString("receiver_grid"), row.optBoolean("mutual"), row.optString("continent")))
    }
}
private fun JSONObject.optionalDouble(name: String) = if (has(name) && !isNull(name)) optDouble(name).takeIf(Double::isFinite) else null
