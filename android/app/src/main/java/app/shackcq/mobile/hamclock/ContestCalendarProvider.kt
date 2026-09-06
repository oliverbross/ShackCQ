package app.shackcq.mobile.hamclock

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

internal class ContestCalendarProvider(
    cacheDirectory: File,
    private val http: HamClockHttpClient = HamClockUrlConnectionClient(),
    private val coalescer: HamClockInFlightCoalescer = HamClockInFlightCoalescer(),
) {
    private val cache = HamClockLastGoodCache(cacheDirectory, "contests-wa7bnm")

    fun cached(nowEpoch: Long = Instant.now().epochSecond): HamClockFeed<List<HamClockContest>> =
        cache.read()?.let { entry ->
            runCatching { contestsFromJson(entry.body, nowEpoch) }.getOrNull()?.let { contests ->
                HamClockFeed(contests, cacheState(entry.fetchedAtEpoch, nowEpoch), SOURCE, entry.fetchedAtEpoch)
            }
        } ?: HamClockFeed(emptyList(), HamClockFeedState.UNAVAILABLE, SOURCE, error = "No saved contest calendar")

    fun refresh(force: Boolean = false, nowEpoch: Long = Instant.now().epochSecond): HamClockFeed<List<HamClockContest>> =
        coalescer.run("contest-calendar") {
        val saved = cache.read()
        if (!force && saved != null && nowEpoch - saved.fetchedAtEpoch < TTL_SECONDS) {
            val rows = runCatching { contestsFromJson(saved.body, nowEpoch) }.getOrNull()
            if (rows != null) return@run HamClockFeed(rows, HamClockFeedState.CACHED, SOURCE, saved.fetchedAtEpoch)
        }
        try {
            val response = try {
                http.get(HamClockHttpRequest(URL, "application/rss+xml, application/xml, text/xml", MAX_BYTES,
                    saved?.etag.orEmpty(), saved?.lastModified.orEmpty()))
            } catch (_: HamClockNotModified) {
                val entry = requireNotNull(saved)
                val rows = contestsFromJson(entry.body, nowEpoch)
                cache.write(entry.body, nowEpoch, entry.etag, entry.lastModified)
                return@run HamClockFeed(rows, HamClockFeedState.LIVE, SOURCE, nowEpoch)
            }
            val contests = parseContestCalendarRss(response.body, nowEpoch)
            require(contests.isNotEmpty()) { "Contest calendar contained no usable entries" }
            val body = contestsToJson(contests)
            cache.write(body, nowEpoch, response.etag, response.lastModified)
            HamClockFeed(contests, HamClockFeedState.LIVE, SOURCE, nowEpoch)
        } catch (error: Exception) {
            val fallback = saved?.let { runCatching { contestsFromJson(it.body, nowEpoch) }.getOrNull() }
            if (fallback != null) HamClockFeed(fallback, HamClockFeedState.STALE, SOURCE, saved.fetchedAtEpoch, providerError(error))
            else HamClockFeed(emptyList(), HamClockFeedState.UNAVAILABLE, SOURCE, error = providerError(error))
        }
        }

    private fun cacheState(fetched: Long, now: Long) =
        if (now - fetched <= STALE_SECONDS) HamClockFeedState.CACHED else HamClockFeedState.STALE

    private companion object {
        const val URL = "https://www.contestcalendar.com/calendar.rss"
        const val SOURCE = "WA7BNM Contest Calendar"
        const val MAX_BYTES = 1_500_000
        const val TTL_SECONDS = 30 * 60L
        const val STALE_SECONDS = 7 * 86_400L
    }
}

internal fun parseContestCalendarRss(xml: String, nowEpoch: Long): List<HamClockContest> {
    val now = Instant.ofEpochSecond(nowEpoch)
    val nowYear = now.atZone(ZoneOffset.UTC).year
    return Regex("<item\\b[^>]*>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE).findAll(xml)
        .mapNotNull { match ->
            val item = match.groupValues[1]
            val name = xmlTag(item, "title").xmlText()
            val description = xmlTag(item, "description").xmlText()
            if (name.isBlank() || description.isBlank()) return@mapNotNull null
            val dates = parseContestDateRange(description, nowYear, nowEpoch) ?: return@mapNotNull null
            if (dates.second < nowEpoch) return@mapNotNull null
            val mode = contestMode(name)
            HamClockContest(
                id = (name.uppercase(Locale.US) + ':' + dates.first).hashCode().toUInt().toString(16),
                name = name.take(160),
                startEpoch = dates.first,
                endEpoch = dates.second,
                mode = mode,
                status = if (nowEpoch in dates.first..dates.second) HamClockContestStatus.ACTIVE else HamClockContestStatus.UPCOMING,
                url = xmlTag(item, "link").xmlText().takeIf { it.startsWith("https://") }.orEmpty(),
            )
        }
        .distinctBy { it.id }
        .sortedWith(compareByDescending<HamClockContest> { it.status == HamClockContestStatus.ACTIVE }.thenBy(HamClockContest::startEpoch))
        .take(40)
        .toList()
}

private fun parseContestDateRange(description: String, currentYear: Int, nowEpoch: Long): Pair<Long, Long>? {
    val range = Regex("(\\d{4})Z,\\s*([A-Za-z]{3})\\s+(\\d{1,2})\\s+to\\s+(\\d{4})Z,\\s*([A-Za-z]{3})\\s+(\\d{1,2})", RegexOption.IGNORE_CASE)
        .find(description)
    if (range != null) {
        val startMonth = monthNumber(range.groupValues[2]) ?: return null
        val endMonth = monthNumber(range.groupValues[5]) ?: return null
        var startYear = chooseContestYear(currentYear, startMonth, range.groupValues[3].toInt(), nowEpoch)
        var endYear = startYear
        if (endMonth < startMonth) endYear++
        val start = utcEpoch(startYear, startMonth, range.groupValues[3].toInt(), range.groupValues[1]) ?: return null
        var end = utcEpoch(endYear, endMonth, range.groupValues[6].toInt(), range.groupValues[4]) ?: return null
        if (end <= start) end += 86_400
        return start to end
    }
    val sameDay = Regex("(\\d{4})Z\\s*[-–]\\s*(\\d{4})Z,\\s*([A-Za-z]{3})\\s+(\\d{1,2})", RegexOption.IGNORE_CASE)
        .find(description) ?: return null
    val month = monthNumber(sameDay.groupValues[3]) ?: return null
    val day = sameDay.groupValues[4].toInt()
    val year = chooseContestYear(currentYear, month, day, nowEpoch)
    val start = utcEpoch(year, month, day, sameDay.groupValues[1]) ?: return null
    var end = utcEpoch(year, month, day, sameDay.groupValues[2]) ?: return null
    if (end <= start) end += 86_400
    return start to end
}

private fun chooseContestYear(currentYear: Int, month: Int, day: Int, nowEpoch: Long): Int {
    // The WA7BNM feed is a current-year rolling calendar. Only the Dec/Jan
    // boundary is ambiguous; treating every past month as next year preserves
    // expired feed items as false future contests.
    val currentMonth = Instant.ofEpochSecond(nowEpoch).atZone(ZoneOffset.UTC).monthValue
    return when {
        currentMonth >= 11 && month <= 2 -> currentYear + 1
        currentMonth <= 2 && month >= 11 -> currentYear - 1
        else -> currentYear
    }
}

private fun utcEpoch(year: Int, month: Int, day: Int, hhmm: String): Long? = runCatching {
    require(hhmm.length == 4)
    LocalDateTime.of(year, month, day, hhmm.substring(0, 2).toInt(), hhmm.substring(2).toInt()).toEpochSecond(ZoneOffset.UTC)
}.getOrNull()

private fun monthNumber(value: String): Int? = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    .indexOf(value.lowercase(Locale.US)).takeIf { it >= 0 }?.plus(1)

private fun contestMode(name: String): String {
    val value = name.lowercase(Locale.US)
    return when {
        "rtty" in value -> "RTTY"
        "ft8" in value || "ft4" in value || "digital" in value || "digi" in value -> "Digital"
        "cw" in value || "morse" in value -> "CW"
        "ssb" in value || "phone" in value || "sideband" in value -> "SSB"
        "vhf" in value || "uhf" in value -> "VHF/UHF"
        else -> "Mixed"
    }
}

private fun xmlTag(item: String, tag: String): String =
    Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1).orEmpty()
        .removePrefix("<![CDATA[").removeSuffix("]]>")

private fun String.xmlText(): String {
    var text = replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
    text = Regex("&#(\\d+);").replace(text) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }
    return text.replace(Regex("\\s+"), " ").trim()
}

private fun contestsToJson(rows: List<HamClockContest>) = JSONArray(rows.map { row ->
    JSONObject().put("id", row.id).put("name", row.name).put("start", row.startEpoch).put("end", row.endEpoch)
        .put("mode", row.mode).put("url", row.url)
}).toString()

private fun contestsFromJson(body: String, nowEpoch: Long): List<HamClockContest> {
    val array = JSONArray(body)
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.getJSONObject(index)
            val start = row.getLong("start")
            val end = row.getLong("end")
            if (end < nowEpoch) continue
            add(HamClockContest(row.getString("id"), row.getString("name"), start, end, row.optString("mode", "Mixed"),
                if (nowEpoch in start..end) HamClockContestStatus.ACTIVE else HamClockContestStatus.UPCOMING,
                row.optString("url")))
        }
    }.sortedWith(compareByDescending<HamClockContest> { it.status == HamClockContestStatus.ACTIVE }.thenBy(HamClockContest::startEpoch))
}
