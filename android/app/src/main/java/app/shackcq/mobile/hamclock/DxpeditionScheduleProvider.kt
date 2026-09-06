package app.shackcq.mobile.hamclock

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

internal class DxpeditionScheduleProvider(
    cacheDirectory: File,
    private val http: HamClockHttpClient = HamClockUrlConnectionClient(),
    private val coalescer: HamClockInFlightCoalescer = HamClockInFlightCoalescer(),
) {
    private val cache = HamClockLastGoodCache(cacheDirectory, "dxpeditions-ng3k")

    fun cached(nowEpoch: Long = Instant.now().epochSecond): HamClockFeed<List<HamClockDxpedition>> =
        cache.read()?.let { entry ->
            runCatching { dxpeditionsFromJson(entry.body, nowEpoch) }.getOrNull()?.let { rows ->
                HamClockFeed(rows, if (nowEpoch - entry.fetchedAtEpoch <= STALE_SECONDS) HamClockFeedState.CACHED else HamClockFeedState.STALE,
                    SOURCE, entry.fetchedAtEpoch)
            }
        } ?: HamClockFeed(emptyList(), HamClockFeedState.UNAVAILABLE, SOURCE, error = "No saved DXpedition schedule")

    fun refresh(force: Boolean = false, nowEpoch: Long = Instant.now().epochSecond): HamClockFeed<List<HamClockDxpedition>> =
        coalescer.run("dxpedition-schedule") {
        val saved = cache.read()
        if (!force && saved != null && nowEpoch - saved.fetchedAtEpoch < TTL_SECONDS) {
            val rows = runCatching { dxpeditionsFromJson(saved.body, nowEpoch) }.getOrNull()
            if (rows != null) return@run HamClockFeed(rows, HamClockFeedState.CACHED, SOURCE, saved.fetchedAtEpoch)
        }
        try {
            val response = try {
                http.get(HamClockHttpRequest(URL, "text/html, text/plain", MAX_BYTES, saved?.etag.orEmpty(), saved?.lastModified.orEmpty()))
            } catch (_: HamClockNotModified) {
                val entry = requireNotNull(saved)
                val rows = dxpeditionsFromJson(entry.body, nowEpoch)
                cache.write(entry.body, nowEpoch, entry.etag, entry.lastModified)
                return@run HamClockFeed(rows, HamClockFeedState.LIVE, SOURCE, nowEpoch)
            }
            val rows = parseNg3kDxpeditions(response.body, nowEpoch)
            require(rows.isNotEmpty()) { "NG3K contained no usable DXpedition entries" }
            cache.write(dxpeditionsToJson(rows), nowEpoch, response.etag, response.lastModified)
            HamClockFeed(rows, HamClockFeedState.LIVE, SOURCE, nowEpoch)
        } catch (error: Exception) {
            val fallback = saved?.let { runCatching { dxpeditionsFromJson(it.body, nowEpoch) }.getOrNull() }
            if (fallback != null) HamClockFeed(fallback, HamClockFeedState.STALE, SOURCE, saved.fetchedAtEpoch, providerError(error))
            else HamClockFeed(emptyList(), HamClockFeedState.UNAVAILABLE, SOURCE, error = providerError(error))
        }
        }

    private companion object {
        const val URL = "https://www.ng3k.com/Misc/adxoplain.html"
        const val SOURCE = "NG3K ADXO"
        const val MAX_BYTES = 2_000_000
        const val TTL_SECONDS = 30 * 60L
        const val STALE_SECONDS = 7 * 86_400L
    }
}

internal fun parseNg3kDxpeditions(html: String, nowEpoch: Long): List<HamClockDxpedition> {
    val text = html
        .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style\\b[^>]*>[\\s\\S]*?</style\\s*>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ").ng3kText()
    val entryPattern = Regex(
        "((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2}[^D]*?DXCC:[^·]+?)(?=(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2}\\s*[-–]|$)",
        setOf(RegexOption.IGNORE_CASE),
    )
    val today = Instant.ofEpochSecond(nowEpoch).atZone(ZoneOffset.UTC).toLocalDate()
    return entryPattern.findAll(text).flatMap { match ->
        parseNg3kEntry(match.value, today).asSequence()
    }.filter { row -> row.endEpoch == null || row.endEpoch >= today.minusDays(14).atStartOfDay(ZoneOffset.UTC).toEpochSecond() }
        .distinctBy { it.callsign }
        .sortedWith(compareBy<HamClockDxpedition>({ it.status.ordinal }, { it.startEpoch ?: Long.MAX_VALUE }, { it.callsign }))
        .take(80)
        .toList()
}

private fun String.ng3kText(): String = replace("&amp;", "&").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }
    .replace(Regex("\\s+"), " ").trim()

private fun parseNg3kEntry(entry: String, today: LocalDate): List<HamClockDxpedition> {
    if (listOf("Last updated", "Copyright", "Expired Announcements", "Table Version", "About ADXO").any(entry::contains)) return emptyList()
    val entity = Regex("DXCC:\\s*(.+?)\\s*(?=Callsign:|QSL:|Source:|Info:|$)", RegexOption.IGNORE_CASE)
        .find(entry)?.groupValues?.get(1)?.trim().orEmpty()
    var publishedCall = Regex("Callsign:\\s*([A-Z0-9/]+)", RegexOption.IGNORE_CASE).find(entry)?.groupValues?.get(1)?.uppercase(Locale.US)
    if (publishedCall.isNullOrBlank()) publishedCall = Regex("\\b([A-Z]{1,2}\\d[A-Z0-9]*[A-Z](?:/[A-Z0-9]+)?)\\b")
        .find(entry)?.groupValues?.get(1)?.uppercase(Locale.US)
    if (publishedCall.isNullOrBlank() || publishedCall.length < 3 || publishedCall in setOf("DXCC", "QSL", "INFO", "SOURCE")) return emptyList()
    val qsl = Regex("QSL:\\s*([A-Za-z0-9/.-]+)", RegexOption.IGNORE_CASE).find(entry)?.groupValues?.get(1).orEmpty()
    val information = Regex("Info:\\s*(.+)", RegexOption.IGNORE_CASE).find(entry)?.groupValues?.get(1)?.trim().orEmpty().take(600)
    val dateText = Regex("^([A-Za-z]{3}\\s+\\d{1,2}(?:\\s*[-–]\\s*(?:[A-Za-z]{3}\\s+)?\\d{1,2})?(?:,\\s*\\d{4})?)", RegexOption.IGNORE_CASE)
        .find(entry)?.groupValues?.get(1).orEmpty()
    val dates = parseNg3kDateRange(dateText, today)
    val status = when {
        dates == null -> HamClockDxpeditionStatus.UNDATED
        !today.isBefore(dates.first) && !today.isAfter(dates.second) -> HamClockDxpeditionStatus.ACTIVE
        today.isAfter(dates.second) -> HamClockDxpeditionStatus.RECENTLY_ENDED
        else -> HamClockDxpeditionStatus.UPCOMING
    }
    val bands = Regex("\\b(?:2200|630|160|80|60|40|30|20|17|15|12|10|8|6|4|2)m\\b", RegexOption.IGNORE_CASE)
        .findAll(entry).map { it.value.lowercase(Locale.US) }.toSet()
    val modes = Regex("\\b(CW|SSB|FT8|FT4|RTTY|PSK|FM|AM|DIGI)\\b").findAll(entry).map { it.value }.toSet()
    val operatingCalls = Regex("\\bas\\s+([A-Z0-9]+(?:/[A-Z0-9]+)*)\\b").findAll(information)
        .map { it.groupValues[1].uppercase(Locale.US) }.filter(::looksLikeCallsign).distinct().toList()
        .ifEmpty { listOf(publishedCall) }
    return operatingCalls.map { call ->
        HamClockDxpedition(call, entity, dates?.first?.atStartOfDay(ZoneOffset.UTC)?.toEpochSecond(),
            dates?.second?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toEpochSecond()?.minus(1), status, dateText,
            bands, modes, qsl, information, "https://www.ng3k.com/Misc/adxo.html")
    }
}

private fun looksLikeCallsign(value: String) =
    Regex("^(?:[A-Z0-9]{1,4}/)?[A-Z0-9]{1,3}\\d[A-Z]{1,4}(?:/[A-Z0-9]{1,4})?$").matches(value)

private fun parseNg3kDateRange(value: String, today: LocalDate): Pair<LocalDate, LocalDate>? {
    val match = Regex("([A-Za-z]{3})\\s+(\\d{1,2})(?:,?\\s*(\\d{4}))?(?:\\s*[-–]\\s*([A-Za-z]{3})?\\s*(\\d{1,2})(?:,?\\s*(\\d{4}))?)?", RegexOption.IGNORE_CASE)
        .find(value) ?: return null
    val startMonth = ng3kMonthNumber(match.groupValues[1]) ?: return null
    val endMonth = match.groupValues[4].takeIf(String::isNotBlank)?.let(::ng3kMonthNumber) ?: startMonth
    val explicitEndYear = match.groupValues[6].toIntOrNull()
    var startYear = match.groupValues[3].toIntOrNull() ?: explicitEndYear ?: today.year
    var endYear = explicitEndYear ?: startYear
    val startDay = match.groupValues[2].toInt()
    val endDay = match.groupValues[5].toIntOrNull() ?: startDay
    var start = runCatching { LocalDate.of(startYear, startMonth, startDay) }.getOrNull() ?: return null
    var end = runCatching { LocalDate.of(endYear, endMonth, endDay) }.getOrNull() ?: return null
    if (end < start) {
        if (explicitEndYear != null && match.groupValues[3].isBlank()) {
            startYear--
            start = LocalDate.of(startYear, startMonth, startDay)
        } else {
            endYear++
            end = LocalDate.of(endYear, endMonth, endDay)
        }
    }
    // Year-less entries around New Year belong to the nearest plausible season.
    if (match.groupValues[3].isBlank() && explicitEndYear == null && end < today.minusMonths(2)) {
        start = start.plusYears(1); end = end.plusYears(1)
    }
    return start to end
}

private fun ng3kMonthNumber(value: String): Int? =
    listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .indexOf(value.lowercase(Locale.US)).takeIf { it >= 0 }?.plus(1)

private fun dxpeditionsToJson(rows: List<HamClockDxpedition>) = JSONArray(rows.map { row ->
    JSONObject().put("call", row.callsign).put("entity", row.entity).put("start", row.startEpoch).put("end", row.endEpoch)
        .put("date", row.dateText).put("bands", JSONArray(row.bands.toList())).put("modes", JSONArray(row.modes.toList()))
        .put("qsl", row.qsl).put("info", row.information).put("url", row.sourceUrl)
}).toString()

private fun dxpeditionsFromJson(body: String, nowEpoch: Long): List<HamClockDxpedition> {
    val todayStart = Instant.ofEpochSecond(nowEpoch).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    val recentStart = Instant.ofEpochSecond(nowEpoch).atZone(ZoneOffset.UTC).toLocalDate().minusDays(14).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    val array = JSONArray(body)
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.getJSONObject(index)
            val start = row.optLong("start").takeIf { row.has("start") && !row.isNull("start") }
            val end = row.optLong("end").takeIf { row.has("end") && !row.isNull("end") }
            if (end != null && end < recentStart) continue
            val status = when { start == null || end == null -> HamClockDxpeditionStatus.UNDATED; nowEpoch in start..end -> HamClockDxpeditionStatus.ACTIVE; end < todayStart -> HamClockDxpeditionStatus.RECENTLY_ENDED; else -> HamClockDxpeditionStatus.UPCOMING }
            fun strings(name: String): Set<String> = row.optJSONArray(name)?.let { values ->
                buildSet { for (i in 0 until values.length()) values.optString(i).takeIf(String::isNotBlank)?.let(::add) }
            }.orEmpty()
            add(HamClockDxpedition(row.getString("call"), row.optString("entity"), start, end, status, row.optString("date"),
                strings("bands"), strings("modes"), row.optString("qsl"), row.optString("info"), row.optString("url")))
        }
    }.sortedWith(compareBy<HamClockDxpedition>({ it.status.ordinal }, { it.startEpoch ?: Long.MAX_VALUE }))
}
