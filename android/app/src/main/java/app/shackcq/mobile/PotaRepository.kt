package app.shackcq.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.max

internal data class PotaParkMetadata(
    val ready: Boolean = false,
    val rowCount: Int = 0,
    val downloadedAt: Long = 0,
    val importedAt: Long = 0,
    val etag: String = "",
    val lastModified: String = "",
    val sha256: String = "",
    val sourceBytes: Long = 0,
    val updateAvailable: Boolean = false,
    val failure: String = "",
) {
    val stale: Boolean get() = ready && importedAt > 0 && Instant.now().epochSecond - importedAt > 14 * 86_400L
}

internal class PotaController(context: Context, private val qsoDatabase: QsoDatabase) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("shackcq-pota", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logRepository=PortableLogRepository(qsoDatabase)
    private val spotMutex = Mutex()
    private val parkMutex = Mutex()
    private val cacheFile = File(appContext.filesDir, "pota-spots.json")
    private val parksFile = File(appContext.filesDir, "pota-parks.sqlite")
    private val stagingCsv = File(appContext.cacheDir, "pota-parks-download.csv.part")
    private val stagingDb = File(appContext.filesDir, "pota-parks.sqlite.staging")

    var spots by mutableStateOf<List<PotaSpot>>(emptyList()); private set
    var feedKind by mutableStateOf(PotaFeedKind.LOADING); private set
    var fetchedAt by mutableLongStateOf(0); private set
    var feedError by mutableStateOf(""); private set
    var parkMetadata by mutableStateOf(loadParkMetadata()); private set
    var parkProgress by mutableIntStateOf(0); private set
    var parkBusy by mutableStateOf(false); private set
    var parkResults by mutableStateOf<List<PotaPark>>(emptyList()); private set
    var lastQsoRevision by mutableLongStateOf(qsoDatabase.changeToken()); private set
    private var workedStates by mutableStateOf<Map<String,PotaWorkedState>>(emptyMap())
    private var cancelParkImport = false

    init {
        val backup = File(parksFile.path + ".previous")
        if (!parksFile.exists() && backup.exists()) backup.renameTo(parksFile)
        parkMetadata = loadParkMetadata()
        loadCachedSpots()
    }

    fun close() { cancelParkImport = true; scope.cancel() }

    fun refreshSpots() { scope.launch { refreshSpotsNow() } }

    suspend fun refreshSpotsNow(now: Long = Instant.now().epochSecond) = spotMutex.withLock {
        feedError = ""
        if (spots.isEmpty()) feedKind = PotaFeedKind.LOADING
        val result = withContext(Dispatchers.IO) { fetchText(POTA_SPOT_URL, 2) }
        result.fold(onSuccess = { response ->
            runCatching { parsePotaSpots(response.body, now).also { require(it.isNotEmpty()) { "POTA returned no usable spot records" } } }.fold(onSuccess = { normalized ->
                spots = normalized; fetchedAt = now; feedKind = PotaFeedKind.LIVE;refreshWorkedStates(now)
                prefs.edit().putLong("spots_fetched_at", now).putString("spots_etag", response.etag).putString("spots_modified", response.lastModified).apply()
                runCatching {
                    val temp = File(cacheFile.parentFile, cacheFile.name + ".part")
                    temp.writeText(response.body, Charsets.UTF_8)
                    if (!temp.renameTo(cacheFile)) { cacheFile.writeText(response.body, Charsets.UTF_8); temp.delete() }
                }
            }, onFailure = { failSpots("POTA response could not be parsed", now) })
        }, onFailure = { failSpots(it.message ?: "POTA refresh failed", now) })
    }

    fun markForegroundAge(now: Long = Instant.now().epochSecond) {
        if (feedKind == PotaFeedKind.LIVE && fetchedAt > 0 && now - fetchedAt > 90) feedKind = PotaFeedKind.CACHED
        if (qsoDatabase.changeToken() != lastQsoRevision) notifyQsoChanged()
    }

    fun notifyQsoChanged() { lastQsoRevision = qsoDatabase.changeToken();refreshWorkedStates() }

    fun opportunities(now: Long, radioFrequencyHz: Long, stationGrid: String): List<PotaOpportunity> {
        val station = maidenheadCenter(stationGrid)
        return spots.map { spot -> rankPotaSpot(spot, workedStates[spot.id]?:PotaWorkedState(), now, radioFrequencyHz, station) }
    }

    private fun refreshWorkedStates(now:Long=Instant.now().epochSecond){val rows=spots;scope.launch{workedStates=withContext(Dispatchers.IO){logRepository.potaStates(rows,now)}}}

    fun searchParks(query: String, location: String = "", stationGrid: String = "", nearby: Boolean = false) {
        scope.launch {
            parkResults = withContext(Dispatchers.IO) {
                if (!parkMetadata.ready || !parksFile.exists()) emptyList()
                else queryParks(query, location, maidenheadCenter(stationGrid), nearby)
            }
        }
    }

    suspend fun nearbyParks(stationGrid: String, radiusKm: Double, limit: Int = 1_000): CatalogueRows<PotaPark> = withContext(Dispatchers.IO) {
        if (!parkMetadata.ready || !parksFile.exists()) return@withContext CatalogueRows(emptyList(), 0)
        val database = SQLiteDatabase.openDatabase(parksFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val invalid = try {
            database.rawQuery("SELECT COUNT(*) FROM parks WHERE latitude IS NULL OR longitude IS NULL", null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } finally { database.close() }
        CatalogueRows(queryParks("", "", maidenheadCenter(stationGrid), nearby = true, radiusKm = radiusKm, resultLimit = limit), invalid)
    }

    fun updateParks() {
        if (parkBusy) return
        cancelParkImport = false
        scope.launch { updateParksNow() }
    }

    fun cancelParkUpdate() { cancelParkImport = true }

    fun checkParksOncePerForegroundDay() {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        if (prefs.getString("park_check_day", "") == today || parkBusy) return
        prefs.edit().putString("park_check_day", today).apply()
        scope.launch {
            val changed = withContext(Dispatchers.IO) { runCatching { remoteCatalogueChanged() }.getOrDefault(false) }
            if (changed) parkMetadata = parkMetadata.copy(updateAvailable = true)
        }
    }

    private suspend fun updateParksNow() = parkMutex.withLock {
        parkBusy = true; parkProgress = 0
        try {
            val download = withContext(Dispatchers.IO) { downloadCatalogue() }
            if (cancelParkImport) throw InterruptedException("Park update cancelled")
            val imported = withContext(Dispatchers.IO) { importCatalogue(download) }
            parkMetadata = imported
            saveParkMetadata(imported)
            parkResults = emptyList()
        } catch (error: Throwable) {
            val message = when (error) {
                is InterruptedException -> "Park update cancelled — previous database retained"
                else -> "Park update failed — previous database retained: ${error.message ?: "unknown error"}"
            }
            parkMetadata = parkMetadata.copy(ready = parksFile.exists() && parkMetadata.rowCount > 0, failure = message)
            saveParkMetadata(parkMetadata)
        } finally {
            stagingCsv.delete(); stagingDb.delete(); File(stagingDb.path + "-journal").delete()
            parkBusy = false; parkProgress = 0; cancelParkImport = false
        }
    }

    private data class HttpText(val body: String, val etag: String, val lastModified: String)
    private data class ParkDownload(val bytes: Long, val etag: String, val lastModified: String, val sha256: String, val epoch: Long)

    private fun fetchText(url: String, attempts: Int): Result<HttpText> {
        var failure: Throwable? = null
        repeat(attempts.coerceIn(1, 2)) { attempt ->
            try {
                val connection = openConnection(url).apply {
                    prefs.getString("spots_etag", "")?.takeIf(String::isNotBlank)?.let { setRequestProperty("If-None-Match", it) }
                    prefs.getString("spots_modified", "")?.takeIf(String::isNotBlank)?.let { setRequestProperty("If-Modified-Since", it) }
                }
                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && cacheFile.exists())
                        return Result.success(HttpText(cacheFile.readText(), connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty()))
                    if (connection.responseCode !in 200..299) throw IllegalStateException("POTA service returned HTTP ${connection.responseCode}")
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    return Result.success(HttpText(body, connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty()))
                } finally { connection.disconnect() }
            } catch (error: Throwable) { failure = error; if (attempt == 0) SystemClock.sleep(350) }
        }
        return Result.failure(failure ?: IllegalStateException("POTA refresh failed"))
    }

    private fun failSpots(message: String, now: Long) {
        feedError = message
        feedKind = if (spots.isNotEmpty()) PotaFeedKind.CACHED else if (fetchedAt > 0) PotaFeedKind.OFFLINE else PotaFeedKind.FAILED
        if (fetchedAt == 0L) fetchedAt = now
    }

    private fun loadCachedSpots() {
        fetchedAt = prefs.getLong("spots_fetched_at", 0)
        if (!cacheFile.exists()) { feedKind = PotaFeedKind.LOADING; return }
        scope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { parsePotaSpots(cacheFile.readText(Charsets.UTF_8)) } }
            cached.onSuccess {
                spots = it; feedKind = if (it.isEmpty()) PotaFeedKind.LOADING else PotaFeedKind.CACHED
            }.onFailure { feedKind = PotaFeedKind.FAILED; feedError = "Saved POTA snapshot is unreadable" }
        }
    }

    private fun openConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 20_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", POTA_USER_AGENT); setRequestProperty("Accept", "application/json,text/csv,*/*")
    }

    private fun downloadCatalogue(): ParkDownload {
        val connection = openConnection(POTA_PARK_URL).apply { readTimeout = 90_000 }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("catalogue returned HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(stagingCsv), 128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelParkImport) throw InterruptedException("cancelled")
                        val read = input.read(buffer); if (read < 0) break
                        output.write(buffer, 0, read); digest.update(buffer, 0, read); bytes += read
                        parkProgress = if (total > 0) (bytes * 45 / total).toInt().coerceIn(0, 45) else 0
                    }
                }
            }
            if (bytes < 100_000) throw IllegalStateException("catalogue download was unexpectedly small")
            return ParkDownload(bytes, connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty(), digest.digest().joinToString("") { "%02x".format(it) }, Instant.now().epochSecond)
        } finally { connection.disconnect() }
    }

    private fun importCatalogue(download: ParkDownload): PotaParkMetadata {
        stagingDb.delete(); File(stagingDb.path + "-journal").delete()
        val db = SQLiteDatabase.openOrCreateDatabase(stagingDb, null)
        var imported = 0; var rejected = 0
        val references = HashSet<String>(100_000)
        try {
            db.execSQL("CREATE TABLE parks(reference TEXT PRIMARY KEY, name TEXT NOT NULL, name_norm TEXT NOT NULL, active INTEGER NOT NULL, entity_id TEXT NOT NULL, location TEXT NOT NULL, location_norm TEXT NOT NULL, latitude REAL, longitude REAL, grid TEXT NOT NULL)")
            db.beginTransaction()
            Utf8CsvReader(stagingCsv).use { csv ->
                val header = csv.nextRow()?.mapIndexed { index, value -> normalizedHeader(value) to index }?.toMap()
                    ?: throw IllegalStateException("catalogue header is missing")
                val required = listOf("reference", "name", "active", "entityid", "locationdesc", "latitude", "longitude", "grid")
                if (!required.all(header::containsKey)) throw IllegalStateException("catalogue headers changed: missing ${required.filterNot(header::containsKey).joinToString()}")
                fun List<String>.field(name: String) = getOrNull(header.getValue(name)).orEmpty().trim()
                while (true) {
                    if (cancelParkImport) throw InterruptedException("cancelled")
                    val row = csv.nextRow() ?: break
                    val reference = normalizePotaReference(row.field("reference")); val name = row.field("name").take(240)
                    val latitudeRaw = row.field("latitude"); val longitudeRaw = row.field("longitude")
                    val latitude = latitudeRaw.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                    val longitude = longitudeRaw.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                    if (reference.isBlank() || name.isBlank()) { rejected++; continue }
                    if ((latitudeRaw.isNotBlank() && latitude == null) || (longitudeRaw.isNotBlank() && longitude == null)) { rejected++; continue }
                    if (!references.add(reference)) throw IllegalStateException("catalogue contains duplicate reference $reference")
                    try {
                        db.execSQL("INSERT INTO parks VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(reference, name, name.uppercase(Locale.US),
                            if (row.field("active").equals("1") || row.field("active").equals("true", true)) 1 else 0,
                            row.field("entityid").take(32), row.field("locationdesc").take(100), row.field("locationdesc").uppercase(Locale.US).take(100),
                            latitude, longitude, row.field("grid").uppercase(Locale.US).take(8)))
                        imported++
                    } catch (_: Throwable) { rejected++ }
                    if (imported % 2_000 == 0) parkProgress = 45 + (imported / 2_500).coerceAtMost(48)
                }
            }
            if (imported < 1_000) throw IllegalStateException("catalogue validation found only $imported parks")
            if (rejected > max(50, imported / 20)) throw IllegalStateException("catalogue validation rejected $rejected of ${imported + rejected} rows")
            db.execSQL("CREATE INDEX parks_name_idx ON parks(name_norm)")
            db.execSQL("CREATE INDEX parks_location_idx ON parks(location_norm)")
            db.execSQL("CREATE INDEX parks_coordinates_idx ON parks(latitude,longitude)")
            db.setTransactionSuccessful()
        } finally { if (db.inTransaction()) db.endTransaction(); db.close() }
        val verify = SQLiteDatabase.openDatabase(stagingDb.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val count = verify.rawQuery("SELECT COUNT(*) FROM parks", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val sample = verify.rawQuery("SELECT reference FROM parks ORDER BY reference LIMIT 1", null).use { it.moveToFirst() && it.getString(0).isNotBlank() }
            if (count != imported || !sample) throw IllegalStateException("staged catalogue could not be reopened and sampled")
        } finally { verify.close() }
        if (!activatePotaCatalogue(stagingDb, parksFile, valid = true)) throw IllegalStateException("could not activate staged park database")
        parkProgress = 100
        return PotaParkMetadata(true, imported, download.epoch, Instant.now().epochSecond, download.etag, download.lastModified,
            download.sha256, download.bytes, updateAvailable = false, failure = "")
    }

    private fun queryParks(query: String, location: String, station: GeoPoint?, nearby: Boolean, radiusKm: Double? = null, resultLimit: Int = 100): List<PotaPark> {
        val db = SQLiteDatabase.openDatabase(parksFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val q = query.trim().uppercase(Locale.US); val loc = location.trim().uppercase(Locale.US)
            val clauses = mutableListOf("1=1"); val args = mutableListOf<String>()
            if (q.isNotBlank()) { clauses += "(reference LIKE ? OR name_norm LIKE ?)"; args += "$q%"; args += "%$q%" }
            if (loc.isNotBlank()) { clauses += "location_norm LIKE ?"; args += "%$loc%" }
            if (nearby && station != null) {
                val latDelta = radiusKm?.div(110.574)?.coerceAtLeast(.1) ?: 15.0
                val longitudeScale = kotlin.math.cos(Math.toRadians(station.latitude)).coerceAtLeast(.05)
                val lonDelta = radiusKm?.div(111.320 * longitudeScale)?.coerceAtMost(180.0) ?: 20.0
                clauses += "latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?"
                args += (station.latitude - latDelta).coerceAtLeast(-90.0).toString(); args += (station.latitude + latDelta).coerceAtMost(90.0).toString()
                args += (station.longitude - lonDelta).coerceAtLeast(-180.0).toString(); args += (station.longitude + lonDelta).coerceAtMost(180.0).toString()
            }
            val limit = if (nearby) 5_000 else resultLimit.coerceIn(1, 500)
            val rows = buildList {
                db.rawQuery("SELECT reference,name,active,entity_id,location,latitude,longitude,grid FROM parks WHERE ${clauses.joinToString(" AND ")} ORDER BY reference LIMIT $limit", args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val lat = if (cursor.isNull(5)) null else cursor.getDouble(5); val lon = if (cursor.isNull(6)) null else cursor.getDouble(6)
                        val point = if (lat != null && lon != null) GeoPoint(lat, lon) else null
                        add(PotaPark(cursor.getString(0), cursor.getString(1), cursor.getInt(2) == 1, cursor.getString(3), cursor.getString(4), lat, lon, cursor.getString(7),
                            if (station != null && point != null) distanceKm(station, point) else null,
                            if (station != null && point != null) initialBearingDegrees(station, point) else null))
                    }
                }
            }
            return if (nearby) rows.asSequence()
                .filter { radiusKm == null || (it.distanceKm != null && it.distanceKm <= radiusKm) }
                .sortedWith(compareBy<PotaPark> { it.distanceKm ?: Double.MAX_VALUE }.thenBy(PotaPark::reference))
                .take(resultLimit.coerceIn(1, 1_000)).toList() else rows
        } finally { db.close() }
    }

    private fun remoteCatalogueChanged(): Boolean {
        if (!parkMetadata.ready) return false
        val connection = openConnection(POTA_PARK_URL).apply { requestMethod = "HEAD"; parkMetadata.etag.takeIf(String::isNotBlank)?.let { setRequestProperty("If-None-Match", it) }; parkMetadata.lastModified.takeIf(String::isNotBlank)?.let { setRequestProperty("If-Modified-Since", it) } }
        return try { when (connection.responseCode) { HttpURLConnection.HTTP_NOT_MODIFIED -> false; in 200..299 -> connection.getHeaderField("ETag").orEmpty() != parkMetadata.etag || connection.getHeaderField("Last-Modified").orEmpty() != parkMetadata.lastModified; else -> false } } finally { connection.disconnect() }
    }

    private fun loadParkMetadata(): PotaParkMetadata = runCatching {
        val row = JSONObject(prefs.getString("park_metadata", "{}") ?: "{}")
        PotaParkMetadata(parksFile.exists() && row.optInt("rows") > 0, row.optInt("rows"), row.optLong("downloaded"), row.optLong("imported"),
            row.optString("etag"), row.optString("modified"), row.optString("sha256"), row.optLong("bytes"), row.optBoolean("update"), row.optString("failure"))
    }.getOrDefault(PotaParkMetadata())

    private fun saveParkMetadata(meta: PotaParkMetadata) {
        val row = JSONObject().put("rows", meta.rowCount).put("downloaded", meta.downloadedAt).put("imported", meta.importedAt).put("etag", meta.etag)
            .put("modified", meta.lastModified).put("sha256", meta.sha256).put("bytes", meta.sourceBytes).put("update", meta.updateAvailable).put("failure", meta.failure)
        prefs.edit().putString("park_metadata", row.toString()).apply()
    }
}

internal fun normalizedHeader(value: String) = value.removePrefix("\uFEFF").lowercase(Locale.US).filter(Char::isLetterOrDigit)

internal fun activatePotaCatalogue(staging: File, active: File, valid: Boolean): Boolean {
    if (!valid || !staging.exists()) return false
    val backup = File(active.path + ".previous")
    if (!active.exists() && backup.exists()) backup.renameTo(active)
    backup.delete()
    if (active.exists() && !active.renameTo(backup)) return false
    if (!staging.renameTo(active)) { backup.renameTo(active); return false }
    backup.delete()
    return true
}

internal fun PotaPark.matchesSearch(query: String, locationFilter: String = ""): Boolean {
    val q = query.trim().uppercase(Locale.US); val locationQuery = locationFilter.trim().uppercase(Locale.US)
    return (q.isBlank() || reference.uppercase(Locale.US).startsWith(q) || name.uppercase(Locale.US).contains(q)) &&
        (locationQuery.isBlank() || location.uppercase(Locale.US).contains(locationQuery))
}

internal fun sortNearbyParks(rows: List<PotaPark>): List<PotaPark> = rows.sortedWith(compareBy<PotaPark> { it.distanceKm ?: Double.MAX_VALUE }.thenBy(PotaPark::reference))

internal class Utf8CsvReader(file: File) : AutoCloseable {
    private val reader = PushbackReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1)
    fun nextRow(): List<String>? {
        val fields = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var saw = false
        while (true) {
            val value = reader.read()
            if (value < 0) { if (!saw && field.isEmpty() && fields.isEmpty()) return null; fields += field.toString(); return fields }
            saw = true; val char = value.toChar()
            if (quoted) {
                if (char == '"') { val next = reader.read(); if (next == '"'.code) field.append('"') else { quoted = false; if (next >= 0) reader.unread(next) } }
                else field.append(char)
            } else when (char) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(char)
                ',' -> { fields += field.toString(); field.clear() }
                '\n' -> { fields += field.toString().trimEnd('\r'); return fields }
                else -> field.append(char)
            }
        }
    }
    override fun close() = reader.close()
}
