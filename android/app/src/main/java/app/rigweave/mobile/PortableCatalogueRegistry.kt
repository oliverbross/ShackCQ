package app.rigweave.mobile

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

internal enum class PortableCatalogueState { AVAILABLE, OFFLINE_CACHE, USER_IMPORT, PROVIDER_BLOCKED, LICENCE_BLOCKED, UNAVAILABLE }
internal enum class PortableCatalogueProgram(val label: String) {
    IOTA("IOTA"), WWBOTA("WWBOTA"), WWFF("WWFF"), CASTLES("Castles"), LIGHTHOUSES("Lighthouses")
}

internal data class PortableCatalogueStatus(
    val programme: PortableCatalogueProgram,
    val state: PortableCatalogueState,
    val updatedAt: Long = 0,
    val rowCount: Int = 0,
    val source: String,
    val digest: String = "",
    val reason: String = "",
    val busy: Boolean = false,
)

internal data class PortableCataloguePlace(
    val programme: PortableCatalogueProgram,
    val reference: String,
    val name: String,
    val dxcc: String = "",
    val entity: String = "",
    val region: String = "",
    val latitudeMin: Double? = null,
    val latitudeMax: Double? = null,
    val longitudeMin: Double? = null,
    val longitudeMax: Double? = null,
    val members: List<String> = emptyList(),
    val officialUrl: String = "",
)

internal class PortableCatalogueRegistry(context: Context) : AutoCloseable {
    private data class LastGood(
        val rows: Map<PortableCatalogueProgram, List<PortableCataloguePlace>>,
        val statuses: Map<PortableCatalogueProgram, PortableCatalogueStatus>,
    )
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences("portable-catalogues-v1", Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "portable-catalogues").apply { mkdirs() }
    private val iotaFile = AtomicFile(File(root, "iota-fulllist.json"))
    private val wwffFile = AtomicFile(File(root, "wwff-directory.csv"))
    private val importFiles = PortableCatalogueProgram.entries.associateWith { AtomicFile(File(root, "${it.name.lowercase()}.csv")) }
    private var rows = emptyMap<PortableCatalogueProgram, List<PortableCataloguePlace>>()
    var statuses by mutableStateOf(defaultStatuses())
        private set
    var results by mutableStateOf<List<PortableCataloguePlace>>(emptyList())
        private set

    init {
        statuses = statuses.mapValues { (programme, status) ->
            if (programme in setOf(PortableCatalogueProgram.IOTA, PortableCatalogueProgram.WWFF))
                status.copy(busy = true, reason = "Loading app-private catalogue…")
            else status
        }
        scope.launch {
            val restored = withContext(Dispatchers.IO) { loadLastGood() }
            rows = restored.rows
            statuses = restored.statuses
            if (restored.rows[PortableCatalogueProgram.IOTA].isNullOrEmpty()) refreshIota()
            if (restored.rows[PortableCatalogueProgram.WWFF].isNullOrEmpty()) refreshWwff()
        }
    }

    fun refreshIota() {
        if (statuses.getValue(PortableCatalogueProgram.IOTA).busy) return
        updateStatus(PortableCatalogueProgram.IOTA) { it.copy(busy = true, reason = "Downloading official IOTA JSON") }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { downloadIota() } }
                .onSuccess { parsed ->
                    rows = rows + (PortableCatalogueProgram.IOTA to parsed)
                    val bytes = iotaFile.readFully()
                    updateStatus(PortableCatalogueProgram.IOTA) { current -> current.copy(
                        state = PortableCatalogueState.AVAILABLE, updatedAt = Instant.now().epochSecond,
                        rowCount = parsed.size, digest = sha256(bytes), reason = "Official daily JSON · offline last-good ready", busy = false) }
                }
                .onFailure { error ->
                    updateStatus(PortableCatalogueProgram.IOTA) { current -> current.copy(
                        state = if (current.rowCount > 0) PortableCatalogueState.OFFLINE_CACHE else PortableCatalogueState.UNAVAILABLE,
                        reason = "IOTA refresh failed; ${if (current.rowCount > 0) "last-good retained" else "no cache"}: ${error.message.orEmpty().take(100)}", busy = false) }
                }
        }
    }

    fun refreshWwff() {
        if (statuses.getValue(PortableCatalogueProgram.WWFF).busy) return
        updateStatus(PortableCatalogueProgram.WWFF) { it.copy(busy = true, reason = "Downloading official nightly WWFF CSV") }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { downloadWwff() } }
                .onSuccess { parsed ->
                    rows = rows + (PortableCatalogueProgram.WWFF to parsed)
                    val bytes = wwffFile.readFully()
                    updateStatus(PortableCatalogueProgram.WWFF) { current -> current.copy(
                        state = PortableCatalogueState.AVAILABLE, updatedAt = prefs.getLong("WWFF_updated", Instant.now().epochSecond),
                        rowCount = parsed.size, digest = sha256(bytes),
                        reason = "Official nightly CSV · app-private last-good ready", busy = false,
                    ) }
                }
                .onFailure { error ->
                    updateStatus(PortableCatalogueProgram.WWFF) { current -> current.copy(
                        state = if (current.rowCount > 0) PortableCatalogueState.OFFLINE_CACHE else PortableCatalogueState.UNAVAILABLE,
                        reason = "WWFF refresh failed; ${if (current.rowCount > 0) "last-good retained" else "no cache"}: ${error.message.orEmpty().take(100)}",
                        busy = false,
                    ) }
                }
        }
    }

    fun importAuthorised(programme: PortableCatalogueProgram, data: ByteArray, displayName: String) {
        require(programme != PortableCatalogueProgram.IOTA) { "IOTA uses its official download" }
        require(data.size in 1..32_000_000) { "Import must be between 1 byte and 32 MB" }
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { parsePortableCatalogueCsv(programme, data.toString(Charsets.UTF_8)) } }
                .onSuccess { parsed ->
                    withContext(Dispatchers.IO) { writeAtomic(importFiles.getValue(programme), data) }
                    rows = rows + (programme to parsed)
                    val now = Instant.now().epochSecond
                    prefs.edit().putLong("${programme.name}_updated", now).putString("${programme.name}_name", displayName.take(120)).apply()
                    updateStatus(programme) { it.copy(state = PortableCatalogueState.USER_IMPORT, updatedAt = now,
                        rowCount = parsed.size, digest = sha256(data), reason = "User-selected authorised file · app-private last-good") }
                }
                .onFailure { error -> updateStatus(programme) { it.copy(reason = "Import rejected: ${error.message.orEmpty().take(120)}") } }
        }
    }

    fun search(programme: PortableCatalogueProgram, query: String) {
        val terms = normalized(query).split(' ').filter(String::isNotBlank)
        results = rows[programme].orEmpty().asSequence().filter { row ->
            val haystack = normalized(listOf(row.reference, row.name, row.dxcc, row.entity, row.region).joinToString(" "))
            terms.all(haystack::contains)
        }.take(500).toList()
    }

    fun all(programme: PortableCatalogueProgram): List<PortableCataloguePlace> = rows[programme].orEmpty()

    override fun close() = scope.cancel()

    private fun defaultStatuses() = mapOf(
        PortableCatalogueProgram.IOTA to PortableCatalogueStatus(PortableCatalogueProgram.IOTA, PortableCatalogueState.UNAVAILABLE,
            source = IOTA_DEVELOPERS, reason = "Official catalogue not downloaded"),
        PortableCatalogueProgram.WWBOTA to PortableCatalogueStatus(PortableCatalogueProgram.WWBOTA, PortableCatalogueState.PROVIDER_BLOCKED,
            source = "https://wwbota.org/", reason = "Automated cache and redistribution terms not established; lawful user import only"),
        PortableCatalogueProgram.WWFF to PortableCatalogueStatus(PortableCatalogueProgram.WWFF, PortableCatalogueState.UNAVAILABLE,
            source = WWFF_DIRECTORY, reason = "Official nightly CSV not downloaded"),
        PortableCatalogueProgram.CASTLES to PortableCatalogueStatus(PortableCatalogueProgram.CASTLES, PortableCatalogueState.PROVIDER_BLOCKED,
            source = "https://wcagroup.org/", reason = "No reviewed stable licensed coordinate download contract"),
        PortableCatalogueProgram.LIGHTHOUSES to PortableCatalogueStatus(PortableCatalogueProgram.LIGHTHOUSES, PortableCatalogueState.PROVIDER_BLOCKED,
            source = "https://arlhs.com/", reason = "No reviewed stable licensed bulk API/download contract"),
    )

    private fun loadLastGood(): LastGood {
        val loaded = mutableMapOf<PortableCatalogueProgram, List<PortableCataloguePlace>>()
        var restoredStatuses = defaultStatuses()
        fun restore(programme: PortableCatalogueProgram, transform: (PortableCatalogueStatus) -> PortableCatalogueStatus) {
            restoredStatuses = restoredStatuses + (programme to transform(restoredStatuses.getValue(programme)))
        }
        if (iotaFile.baseFile.exists()) runCatching { iotaFile.readFully() }.mapCatching(::parseIota).onSuccess { parsed ->
            loaded[PortableCatalogueProgram.IOTA] = parsed
            restore(PortableCatalogueProgram.IOTA) { it.copy(state = PortableCatalogueState.OFFLINE_CACHE,
                updatedAt = prefs.getLong("IOTA_updated", iotaFile.baseFile.lastModified() / 1000), rowCount = parsed.size,
                digest = sha256(iotaFile.readFully()), reason = "Official IOTA app-private last-good") }
        }
        if (wwffFile.baseFile.exists()) runCatching { wwffFile.readFully() }
            .mapCatching { parsePortableCatalogueCsv(PortableCatalogueProgram.WWFF, it.toString(Charsets.UTF_8)) }
            .onSuccess { parsed ->
                loaded[PortableCatalogueProgram.WWFF] = parsed
                restore(PortableCatalogueProgram.WWFF) { it.copy(state = PortableCatalogueState.OFFLINE_CACHE,
                    updatedAt = prefs.getLong("WWFF_updated", wwffFile.baseFile.lastModified() / 1000), rowCount = parsed.size,
                    digest = sha256(wwffFile.readFully()), reason = "Official WWFF app-private last-good") }
            }
        PortableCatalogueProgram.entries.filter { it != PortableCatalogueProgram.IOTA }.forEach { programme ->
            val file = importFiles.getValue(programme)
            if (file.baseFile.exists() && programme !in loaded) runCatching { file.readFully() }.mapCatching { parsePortableCatalogueCsv(programme, it.toString(Charsets.UTF_8)) }.onSuccess { parsed ->
                loaded[programme] = parsed
                restore(programme) { it.copy(state = PortableCatalogueState.USER_IMPORT,
                    updatedAt = prefs.getLong("${programme.name}_updated", file.baseFile.lastModified() / 1000), rowCount = parsed.size,
                    digest = sha256(file.readFully()), reason = "User-selected authorised file · app-private last-good") }
            }
        }
        return LastGood(loaded, restoredStatuses)
    }

    private fun downloadIota(): List<PortableCataloguePlace> {
        val uri = URI(IOTA_FULL_LIST)
        require(uri.scheme == "https" && uri.host == "www.iota-world.org")
        val connection = URL(IOTA_FULL_LIST).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json,application/octet-stream")
        connection.setRequestProperty("User-Agent", "RigWeave/1 portable-catalogue OM0RX")
        prefs.getString("IOTA_etag", "")?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-None-Match", it) }
        prefs.getString("IOTA_modified", "")?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-Modified-Since", it) }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && iotaFile.baseFile.exists()) return parseIota(iotaFile.readFully())
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { boundedRead(it, 8_000_000) }
            val parsed = parseIota(bytes)
            writeAtomic(iotaFile, bytes)
            val now = Instant.now().epochSecond
            prefs.edit().putLong("IOTA_updated", now).putString("IOTA_etag", connection.getHeaderField("ETag").orEmpty())
                .putString("IOTA_modified", connection.getHeaderField("Last-Modified").orEmpty()).apply()
            return parsed
        } finally { connection.disconnect() }
    }

    private fun downloadWwff(): List<PortableCataloguePlace> {
        val uri = URI(WWFF_DIRECTORY)
        require(uri.scheme == "https" && uri.host == "wwff.co" && uri.path == "/wwff-data/wwff_directory.csv")
        val connection = URL(WWFF_DIRECTORY).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 60_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "text/csv,text/plain")
        connection.setRequestProperty("User-Agent", "RigWeave/1 portable-catalogue OM0RX")
        prefs.getString("WWFF_etag", "")?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-None-Match", it) }
        prefs.getString("WWFF_modified", "")?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-Modified-Since", it) }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && wwffFile.baseFile.exists()) {
                return parsePortableCatalogueCsv(PortableCatalogueProgram.WWFF, wwffFile.readFully().toString(Charsets.UTF_8))
            }
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { boundedRead(it, 32_000_000) }
            val parsed = parsePortableCatalogueCsv(PortableCatalogueProgram.WWFF, bytes.toString(Charsets.UTF_8))
            require(parsed.size in 30_000..150_000) { "WWFF directory row count is invalid" }
            writeAtomic(wwffFile, bytes)
            val now = Instant.now().epochSecond
            prefs.edit().putLong("WWFF_updated", now).putString("WWFF_etag", connection.getHeaderField("ETag").orEmpty())
                .putString("WWFF_modified", connection.getHeaderField("Last-Modified").orEmpty()).apply()
            return parsed
        } finally { connection.disconnect() }
    }

    private fun parseIota(bytes: ByteArray): List<PortableCataloguePlace> {
        require(bytes.size in 2..8_000_000) { "IOTA payload size is invalid" }
        val array = JSONArray(bytes.toString(Charsets.UTF_8)); require(array.length() in 100..5_000) { "IOTA group count is invalid" }
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val reference = row.getString("refno").uppercase(Locale.US)
            require(reference.matches(Regex("[A-Z]{2}-\\d{3}"))) { "Invalid IOTA reference" }
            val subgroups = row.optJSONArray("sub_groups") ?: JSONArray()
            val islands = buildList { for (s in 0 until subgroups.length()) {
                val entries = subgroups.getJSONObject(s).optJSONArray("islands") ?: JSONArray()
                for (i in 0 until entries.length()) entries.getJSONObject(i).optString("island_name").takeIf(String::isNotBlank)?.let(::add)
            } }.distinct().take(200)
            fun coordinate(name: String) = row.optString(name).toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
            PortableCataloguePlace(PortableCatalogueProgram.IOTA, reference, row.getString("name"), row.optString("dxcc_num"),
                region = row.optString("grp_region"), latitudeMin = coordinate("latitude_min")?.takeIf { it in -90.0..90.0 },
                latitudeMax = coordinate("latitude_max")?.takeIf { it in -90.0..90.0 }, longitudeMin = coordinate("longitude_min"),
                longitudeMax = coordinate("longitude_max"), members = islands,
                officialUrl = "https://www.iota-world.org/islands-on-the-air/iota-groups-islands.html")
        }
    }

    private fun updateStatus(programme: PortableCatalogueProgram, transform: (PortableCatalogueStatus) -> PortableCatalogueStatus) {
        statuses = statuses + (programme to transform(statuses.getValue(programme)))
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); output.flush(); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private fun boundedRead(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(32_768); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= maxBytes) { "Provider response exceeds ${maxBytes / 1_000_000} MB" }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ").trim()

    companion object {
        const val IOTA_DEVELOPERS = "https://www.iota-world.org/iota-directory/developers?format=html"
        const val IOTA_FULL_LIST = "https://www.iota-world.org/islands-on-the-air/downloads/download-file.html?path=fulllist.json"
        const val WWFF_DIRECTORY = "https://wwff.co/wwff-data/wwff_directory.csv"
    }
}

internal fun parsePortableCatalogueCsv(programme: PortableCatalogueProgram, text: String): List<PortableCataloguePlace> {
    val lines = text.lineSequence().filter(String::isNotBlank).iterator()
    require(lines.hasNext()) { "CSV is empty" }
    val header = portableCsvLine(lines.next()).map { normalizedPortableCatalogue(it).replace(" ", "_").lowercase(Locale.US) }
    fun index(vararg names: String) = names.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } }
    val referenceIndex = index("reference", "ref", "code") ?: error("CSV needs a reference/ref/code column")
    val nameIndex = index("name", "title") ?: error("CSV needs a name/title column")
    val entityIndex = index("country", "entity")
    val dxccIndex = index("dxccenum", "dxcc_enum", "dxcc")
    val regionIndex = index("region", "state", "county")
    val latitudeIndex = index("lat", "latitude")
    val longitudeIndex = index("lon", "long", "longitude")
    val websiteIndex = index("website", "url")
    val result = ArrayList<PortableCataloguePlace>()
    while (lines.hasNext()) {
        require(result.size < 250_000) { "CSV row count is invalid" }
        val values = portableCsvLine(lines.next())
        val reference = values.getOrNull(referenceIndex)?.trim().orEmpty().uppercase(Locale.US)
        val name = values.getOrNull(nameIndex)?.trim().orEmpty()
        if (reference.isBlank() || name.isBlank()) continue
        val latitude = latitudeIndex?.let(values::getOrNull)?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val longitude = longitudeIndex?.let(values::getOrNull)?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        val providerUrl = websiteIndex?.let(values::getOrNull).orEmpty().trim().takeIf { it.startsWith("https://") }.orEmpty()
        result += PortableCataloguePlace(
            programme, reference.take(80), name.take(240),
            dxcc = dxccIndex?.let(values::getOrNull).orEmpty().take(40),
            entity = entityIndex?.let(values::getOrNull).orEmpty().take(120),
            region = regionIndex?.let(values::getOrNull).orEmpty().take(120),
            latitudeMin = latitude, latitudeMax = latitude, longitudeMin = longitude, longitudeMax = longitude,
            officialUrl = providerUrl.ifBlank { if (programme == PortableCatalogueProgram.WWFF) "https://wwff.co/directory/" else "" },
        )
    }
    require(result.isNotEmpty()) { "CSV contains no valid catalogue rows" }
    return result
}

private fun portableCsvLine(line: String): List<String> {
    val result = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var index = 0
    while (index < line.length) { val char = line[index]
        when { char == '"' && quoted && line.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> { result += field.toString(); field.clear() }
            else -> field.append(char) }
        index++
    }
    result += field.toString(); return result
}

private fun normalizedPortableCatalogue(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ").trim()
