package app.shackcq.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

internal enum class SatelliteCacheState { CURRENT, STALE, OFFLINE_CACHE, EMPTY, ERROR }
internal data class SatelliteProviderMetadata(
    val source: String,
    val fetchedAt: Long,
    val dataEpoch: Long? = null,
    val state: SatelliteCacheState,
    val lastError: String = "",
    val manualOverride: Boolean = false,
)
internal data class SatelliteCatalogueEntry(
    val noradId: Long,
    val name: String,
    val elements: SatelliteElements,
    val elementEpoch: Long,
    val manual: Boolean = false,
)
internal data class SatelliteTransponder(
    val id: String,
    val noradId: Long,
    val description: String,
    val uplinkLowHz: Long?,
    val uplinkHighHz: Long?,
    val downlinkLowHz: Long?,
    val downlinkHighHz: Long?,
    val mode: String,
    val uplinkMode: String,
    val inverted: Boolean,
    val alive: Boolean,
    val providerStatus: String,
    val updated: String,
    val manual: Boolean = false,
)
internal data class AmsatStatusSummary(
    val name: String,
    val displayName: String,
    val counts: Map<String, Int>,
    val latestReportEpoch: Long?,
    val latestReporters: List<String> = emptyList(),
    val timeline: List<AmsatStatusReport> = emptyList(),
)
internal data class AmsatStatusReport(val status: String, val reportedEpoch: Long, val callsign: String, val grid: String = "")
internal data class SatelliteTimer(
    val id: String,
    val satellite: String,
    val label: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val functional: Boolean,
)
internal data class SatelliteProviderData<T>(val rows: List<T>, val metadata: SatelliteProviderMetadata)

internal fun satelliteCacheState(count: Int, error: String, now: Long, fetched: Long, ttl: Long): SatelliteCacheState = when {
    count == 0 && error.isNotBlank() -> SatelliteCacheState.ERROR
    count == 0 -> SatelliteCacheState.EMPTY
    error.isNotBlank() -> SatelliteCacheState.OFFLINE_CACHE
    now - fetched > ttl -> SatelliteCacheState.STALE
    else -> SatelliteCacheState.CURRENT
}

internal fun localSatelliteTimerMetadata(now: Long) = SatelliteProviderMetadata(
    source = "Local SGP4 pass timers · no external provider required",
    fetchedAt = now,
    state = SatelliteCacheState.CURRENT,
)

internal class SatelliteProviderRepository(context: Context) {
    companion object {
        const val CELESTRAK_URL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=AMATEUR&FORMAT=CSV"
        const val SATNOGS_URL = "https://db.satnogs.org/api/transmitters/?format=json&service=Amateur&alive=true"
        const val AMSAT_URL = "https://www.amsat.org/status/api/v1/reports.php?hours=24&limit=500"
        const val SATNOGS_ATTRIBUTION = "SatNOGS DB data · CC-BY-SA-4.0 · Libre Space Foundation/community contributors"
        private const val CELESTRAK_AUTO_TTL = 6 * 60 * 60L
        private const val CELESTRAK_MANUAL_COOLDOWN = 2 * 60 * 60L
        private const val SATNOGS_TTL = 24 * 60 * 60L
        private const val AMSAT_TTL = 15 * 60L
        private const val MAX_BODY = 5 * 1024 * 1024
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("satellite_providers_v1", Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, "satellite-providers").apply { mkdirs() }

    fun elements(now: Long = Instant.now().epochSecond): SatelliteProviderData<SatelliteCatalogueEntry> {
        val provider = parseElementCache(cache("celestrak.json")).toMutableList()
        val manual = parseManualElements()
        val byId = provider.associateBy(SatelliteCatalogueEntry::noradId).toMutableMap()
        manual.forEach { byId[it.noradId] = it }
        return SatelliteProviderData(byId.values.sortedBy(SatelliteCatalogueEntry::name), metadata("celestrak", "CelesTrak GP AMATEUR CSV", CELESTRAK_AUTO_TTL, provider.size, now,
            provider.maxOfOrNull(SatelliteCatalogueEntry::elementEpoch), manual.isNotEmpty()))
    }

    fun refreshElements(manual: Boolean = false, now: Long = Instant.now().epochSecond): SatelliteProviderData<SatelliteCatalogueEntry> {
        val last = prefs.getLong("celestrak_fetched", 0)
        val minimum = if (manual) CELESTRAK_MANUAL_COOLDOWN else CELESTRAK_AUTO_TTL
        if (now - last < minimum) return elements(now)
        val response = get(CELESTRAK_URL, "celestrak")
        if (response.notModified) markSuccess("celestrak", now, response)
        else if (response.error.isBlank()) runCatching { parseCelesTrakCsvPayload(response.body, now) }
            .onSuccess { rows ->
                require(rows.isNotEmpty()) { "zero valid rows" }
                writeAtomic("celestrak.json", JSONArray(rows.map(::elementJson)).toString())
                markSuccess("celestrak", now, response)
            }.onFailure { markError("celestrak", safeError(it)) }
        else markError("celestrak", response.error)
        return elements(now)
    }

    fun saveManualElements(entry: SatelliteCatalogueEntry): Boolean {
        if (!entry.manual || entry.noradId !in 1..999_999_999 || entry.elementEpoch <= 0 ||
            !entry.elements.elementOne.startsWith("1 ") || !entry.elements.elementTwo.startsWith("2 ")) return false
        val rows = parseManualElements().filterNot { it.noradId == entry.noradId } + entry
        prefs.edit().putString("manual_elements", JSONArray(rows.map(::elementJson)).toString()).apply()
        return true
    }

    fun removeManualElements(noradId: Long) {
        val rows = parseManualElements().filterNot { it.noradId == noradId }
        prefs.edit().putString("manual_elements", JSONArray(rows.map(::elementJson)).toString()).apply()
    }

    fun transponders(now: Long = Instant.now().epochSecond): SatelliteProviderData<SatelliteTransponder> {
        val provider = parseTransponders(cache("satnogs.json"))
        val manual = parseTransponders(prefs.getString("manual_transponders", "[]").orEmpty(), manual = true)
        val merged = (provider + manual).associateBy(SatelliteTransponder::id).values.sortedWith(compareBy({ it.noradId }, { it.downlinkLowHz ?: Long.MAX_VALUE }))
        return SatelliteProviderData(merged, metadata("satnogs", "SatNOGS DB · CC-BY-SA-4.0", SATNOGS_TTL, provider.size, now, manualOverride = manual.isNotEmpty()))
    }

    fun refreshTransponders(force: Boolean = false, now: Long = Instant.now().epochSecond): SatelliteProviderData<SatelliteTransponder> {
        if (!force && now - prefs.getLong("satnogs_fetched", 0) < SATNOGS_TTL) return transponders(now)
        val response = get(SATNOGS_URL, "satnogs")
        if (response.notModified) markSuccess("satnogs", now, response)
        else if (response.error.isBlank()) runCatching {
            val rows = parseTransponders(response.body).take(2_000)
            require(rows.isNotEmpty()) { "zero valid rows" }
            JSONArray(rows.map(::transponderJson)).toString()
        }.onSuccess { writeAtomic("satnogs.json", it); markSuccess("satnogs", now, response) }
            .onFailure { markError("satnogs", safeError(it)) }
        else markError("satnogs", response.error)
        return transponders(now)
    }

    fun saveManualTransponder(row: SatelliteTransponder) {
        require(row.noradId > 0 && listOfNotNull(row.uplinkLowHz, row.downlinkLowHz).any(::validFrequency))
        val rows = parseTransponders(prefs.getString("manual_transponders", "[]").orEmpty(), manual = true)
            .filterNot { it.id == row.id } + row.copy(manual = true)
        prefs.edit().putString("manual_transponders", JSONArray(rows.map(::transponderJson)).toString()).apply()
    }

    fun removeManualTransponder(id: String) {
        val rows = parseTransponders(prefs.getString("manual_transponders", "[]").orEmpty(), manual = true).filterNot { it.id == id }
        prefs.edit().putString("manual_transponders", JSONArray(rows.map(::transponderJson)).toString()).apply()
    }

    fun amsatStatus(now: Long = Instant.now().epochSecond): SatelliteProviderData<AmsatStatusSummary> {
        val rows = parseAmsat(cache("amsat.json"))
        return SatelliteProviderData(rows, metadata("amsat", "AMSAT Satellite Status API v1 · read-only", AMSAT_TTL, rows.size, now,
            rows.mapNotNull(AmsatStatusSummary::latestReportEpoch).maxOrNull()))
    }

    fun refreshAmsat(force: Boolean = false, now: Long = Instant.now().epochSecond): SatelliteProviderData<AmsatStatusSummary> {
        if (!force && now - prefs.getLong("amsat_fetched", 0) < AMSAT_TTL) return amsatStatus(now)
        val response = get(AMSAT_URL, "amsat")
        if (response.notModified) markSuccess("amsat", now, response)
        else if (response.error.isBlank()) runCatching {
            val rows = parseAmsat(response.body)
            require(rows.isNotEmpty()) { "zero valid rows" }
            response.body
        }.onSuccess { writeAtomic("amsat.json", it); markSuccess("amsat", now, response) }
            .onFailure { markError("amsat", safeError(it)) }
        else markError("amsat", response.error)
        return amsatStatus(now)
    }

    fun timers(now: Long = Instant.now().epochSecond): SatelliteProviderData<SatelliteTimer> {
        return SatelliteProviderData(emptyList(), localSatelliteTimerMetadata(now))
    }

    fun refreshTimers(force: Boolean = false, now: Long = Instant.now().epochSecond): SatelliteProviderData<SatelliteTimer> {
        return timers(now)
    }

    private data class HttpResult(val body: String = "", val etag: String = "", val modified: String = "", val notModified: Boolean = false, val error: String = "")
    private fun get(rawUrl: String, key: String): HttpResult = runCatching {
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json,text/csv;q=0.9")
        connection.setRequestProperty("User-Agent", "ShackCQ/${BuildConfig.VERSION_NAME}")
        prefs.getString("${key}_etag", "")?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-None-Match", it) }
        prefs.getString("${key}_modified", "")?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-Modified-Since", it) }
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_NOT_MODIFIED) HttpResult(notModified = true, etag = connection.getHeaderField("ETag").orEmpty(), modified = connection.getHeaderField("Last-Modified").orEmpty())
        else if (status != HttpURLConnection.HTTP_OK) HttpResult(error = "HTTP_$status")
        else HttpResult(connection.inputStream.use(::readBounded), connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty())
    }.getOrElse { HttpResult(error = it.javaClass.simpleName.uppercase(Locale.US).take(40)) }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val read = input.read(buffer); if (read < 0) break; require(output.size() + read <= MAX_BODY) { "response too large" }; output.write(buffer, 0, read) }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parseElementCache(raw: String): List<SatelliteCatalogueEntry> = runCatching {
        val rows = JSONArray(raw)
        buildList { for (index in 0 until rows.length()) elementFromJson(rows.getJSONObject(index), false)?.let(::add) }
    }.getOrDefault(emptyList())
    private fun parseManualElements(): List<SatelliteCatalogueEntry> = runCatching {
        val rows = JSONArray(prefs.getString("manual_elements", "[]"))
        buildList { for (index in 0 until rows.length()) elementFromJson(rows.getJSONObject(index), true)?.let(::add) }
    }.getOrDefault(emptyList())
    private fun elementFromJson(o: JSONObject, manual: Boolean)=runCatching{SatelliteCatalogueEntry(o.getLong("norad"),o.getString("name"),SatelliteElements(o.getString("format"),o.getString("name"),o.getString("one"),o.optString("two"),o.optLong("fetched"),if(manual)"MANUAL" else "CELESTRAK"),o.getLong("epoch"),manual)}.getOrNull()
    private fun elementJson(e:SatelliteCatalogueEntry)=JSONObject().put("norad",e.noradId).put("name",e.name).put("format",e.elements.format).put("one",e.elements.elementOne).put("two",e.elements.elementTwo).put("fetched",e.elements.fetchedAt).put("epoch",e.elementEpoch)

    private fun parseTransponders(raw: String, manual: Boolean = false): List<SatelliteTransponder> = runCatching {
        val root = raw.trim(); val rows = if (root.startsWith("[")) JSONArray(root) else JSONObject(root).optJSONArray("results") ?: JSONArray()
        buildList { for(i in 0 until rows.length()) { val o=rows.optJSONObject(i)?:continue; val norad=o.optLong("norad_cat_id"); val up=o.nullableLong("uplink_low");val down=o.nullableLong("downlink_low")
            if(norad<=0 || !listOfNotNull(up,down).any(::validFrequency) || o.optBoolean("frequency_violation"))continue
            add(SatelliteTransponder(o.optString("uuid").ifBlank{"manual-$norad-$i"},norad,o.optString("description"),up,o.nullableLong("uplink_high"),down,o.nullableLong("downlink_high"),o.optString("mode"),o.optString("uplink_mode"),o.optBoolean("invert"),o.optBoolean("alive",true),o.optString("status"),o.optString("updated"),manual))
        } }
    }.getOrDefault(emptyList())
    private fun validFrequency(value:Long)=value in 100_000L..40_000_000_000L
    private fun transponderJson(e:SatelliteTransponder)=JSONObject().put("uuid",e.id).put("norad_cat_id",e.noradId).put("description",e.description).put("uplink_low",e.uplinkLowHz).put("uplink_high",e.uplinkHighHz).put("downlink_low",e.downlinkLowHz).put("downlink_high",e.downlinkHighHz).put("mode",e.mode).put("uplink_mode",e.uplinkMode).put("invert",e.inverted).put("alive",e.alive).put("status",e.providerStatus).put("updated",e.updated)

    private fun parseAmsat(raw:String):List<AmsatStatusSummary> = runCatching {
        data class Aggregate(val display:String,val counts:MutableMap<String,Int>,var latest:Long?=null,
            val reporters:MutableList<Pair<Long,String>> = mutableListOf(),val timeline:MutableList<AmsatStatusReport> = mutableListOf())
        val rows=JSONObject(raw).getJSONArray("data");val grouped=mutableMapOf<String,Aggregate>()
        for(i in 0 until rows.length()){
            val o=rows.getJSONObject(i);val name=o.getString("name");val value=grouped.getOrPut(name){Aggregate(o.optString("satellite_display_name",name),mutableMapOf())}
            val report=o.getString("report");if(report in setOf("Heard","Crew Active","Telemetry Only","Not Heard"))value.counts[report]=(value.counts[report]?:0)+o.optInt("report_count",1).coerceAtLeast(1)
            val epoch=parseInstant(o.optString("reported_time").ifBlank{o.optString("latest_reported_time")});if(epoch!=null){value.latest=maxOf(value.latest?:0,epoch).takeIf{it>0};val call=o.optString("callsign").trim();val grid=o.optString("grid_square").trim();if(call.isNotBlank())value.reporters+=epoch to listOf(call,grid,report).filter(String::isNotBlank).joinToString(" · ");value.timeline+=AmsatStatusReport(report,epoch,call,grid)}
        }
        grouped.map{AmsatStatusSummary(it.key,it.value.display,it.value.counts,it.value.latest,it.value.reporters.sortedByDescending(Pair<Long,String>::first).map(Pair<Long,String>::second).distinct().take(5),it.value.timeline.sortedByDescending(AmsatStatusReport::reportedEpoch).distinctBy{r->listOf(r.reportedEpoch,r.callsign,r.status)}.take(12))}.sortedBy(AmsatStatusSummary::displayName)
    }.getOrDefault(emptyList())
    private fun metadata(key:String,source:String,ttl:Long,count:Int,now:Long,dataEpoch:Long?=null,manualOverride:Boolean=false):SatelliteProviderMetadata{val fetched=prefs.getLong("${key}_fetched",0);val error=prefs.getString("${key}_error","").orEmpty();return SatelliteProviderMetadata(source,fetched,dataEpoch,satelliteCacheState(count,error,now,fetched,ttl),error,manualOverride)}
    private fun markSuccess(key:String,now:Long,response:HttpResult){prefs.edit().putLong("${key}_fetched",now).putString("${key}_error","").apply{if(response.etag.isNotBlank())putString("${key}_etag",response.etag);if(response.modified.isNotBlank())putString("${key}_modified",response.modified)}.apply()}
    private fun markError(key:String,error:String){prefs.edit().putString("${key}_error",error.take(100)).apply()}
    private fun cache(name:String)=runCatching{File(directory,name).takeIf(File::isFile)?.readText().orEmpty()}.getOrDefault("")
    private fun writeAtomic(name:String,value:String){val target=File(directory,name);val temporary=File(directory,".$name.tmp");temporary.writeText(value);require(temporary.renameTo(target)||run{target.delete();temporary.renameTo(target)})}
    private fun parseInstant(value:String)=runCatching{Instant.parse(value).epochSecond}.getOrNull()
    private fun safeError(error:Throwable)=error.message.orEmpty().lowercase(Locale.US).let{when{it.contains("zero")->"EMPTY_RESPONSE";it.contains("large")->"RESPONSE_TOO_LARGE";else->error.javaClass.simpleName.uppercase(Locale.US).take(40)}}
    private fun JSONObject.nullableLong(key:String)=if(!has(key)||isNull(key))null else optLong(key).takeIf{it>0}
}

internal fun parseSatelliteUtc(value: String): Long? {
    val candidate = value.trim()
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})?$").matches(candidate)) return null
    return runCatching { OffsetDateTime.parse(candidate).toInstant().epochSecond }.getOrNull()
        ?: runCatching { LocalDateTime.parse(candidate).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
}

internal fun parseCelesTrakCsvPayload(raw: String, fetchedAt: Long): List<SatelliteCatalogueEntry> {
    require(raw.isNotBlank() && !raw.trimStart().startsWith("<"))
    val lines = raw.lineSequence().filter(String::isNotBlank).toList(); require(lines.size > 1)
    val header = satelliteCsvRow(lines.first()); val index = header.mapIndexed { i, value -> value to i }.toMap()
    val required = listOf("OBJECT_NAME", "EPOCH", "MEAN_MOTION", "ECCENTRICITY", "INCLINATION", "NORAD_CAT_ID")
    require(required.all(index::containsKey))
    val seen = mutableSetOf<Long>()
    return lines.drop(1).mapNotNull { line -> runCatching {
        val fields = satelliteCsvRow(line); fun field(name: String) = fields.getOrNull(index.getValue(name)).orEmpty().trim()
        val id = field("NORAD_CAT_ID").toLong(); require(id in 1..999_999_999 && seen.add(id))
        require(field("MEAN_MOTION").toDouble() in 0.01..20.0)
        require(field("ECCENTRICITY").toDouble() in 0.0..<1.0)
        require(field("INCLINATION").toDouble() in 0.0..180.0)
        val epoch = parseSatelliteUtc(field("EPOCH")) ?: error("invalid epoch")
        SatelliteCatalogueEntry(id, field("OBJECT_NAME").ifBlank { id.toString() }, SatelliteElements("CSV", field("OBJECT_NAME"), line, fetchedAt = fetchedAt, source = "CELESTRAK"), epoch)
    }.getOrNull() }.also { require(it.isNotEmpty()) }
}

private fun satelliteCsvRow(line: String): List<String> { val out=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var i=0;while(i<line.length){val ch=line[i];when{ch=='"'&&quoted&&i+1<line.length&&line[i+1]=='"'->{cell.append('"');i++};ch=='"'->quoted=!quoted;ch==','&&!quoted->{out+=cell.toString();cell.clear()};else->cell.append(ch)};i++};out+=cell.toString();return out }
