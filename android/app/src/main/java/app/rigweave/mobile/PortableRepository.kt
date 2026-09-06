package app.rigweave.mobile

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal data class ProviderStatus(
    val kind: PortableFeedKind = PortableFeedKind.LOADING,
    val fetchedAt: Long = 0,
    val count: Int = 0,
    val error: String = "",
)

internal data class CatalogueRows<T>(val rows: List<T>, val invalidCoordinates: Int)

internal data class SotaCatalogueMetadata(
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
) { val stale get() = ready && importedAt > 0 && Instant.now().epochSecond - importedAt > 14 * 86_400L }

internal class PortableController(context: Context, private val qsoDatabase: QsoDatabase) {
    var requestedSpotId by mutableStateOf<String?>(null); private set

    fun requestSpot(id: String) { requestedSpotId = id }
    fun consumeRequestedSpot() { requestedSpotId = null }
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("rigweave-portable", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wwffMutex = Mutex()
    private val wwffCache = File(appContext.filesDir, "wwff-spots.json")
    private val wwffAgendaCache = File(appContext.filesDir, "wwff-agendas.json")
    private val clusterPrefs = appContext.getSharedPreferences("dx_cluster", Context.MODE_PRIVATE)
    private val logRepository=PortableLogRepository(qsoDatabase)
    val pota = PotaController(appContext, qsoDatabase)
    val sotaCatalogue = SotaCatalogue(appContext)
    val catalogues = PortableCatalogueRegistry(appContext)

    var sotaSpots by mutableStateOf<List<PortableSpot>>(emptyList()); private set
    var wwffSpots by mutableStateOf<List<PortableSpot>>(emptyList()); private set
    var sotaStatus by mutableStateOf(ProviderStatus(PortableFeedKind.UNAVAILABLE)); private set
    var wwffStatus by mutableStateOf(ProviderStatus()); private set
    var lastQsoRevision by mutableLongStateOf(qsoDatabase.changeToken()); private set
    var rankedOpportunities by mutableStateOf<List<PortableOpportunity>>(emptyList()); private set
    private var opportunityJob: Job? = null
    private var opportunityKey: Any? = null
    private var sotaClusterSocket: Socket? = null
    private var sotaClusterJob: Job? = null
    private var sotaClusterGeneration = 0
    private var sotaClusterActive = false
    private var sotaClusterLogin = ""
    private val resolvedSummits = ConcurrentHashMap<String, SotaSummit>()

    init { loadWwffCache() }

    fun close() { stopSotaCluster(); scope.cancel(); pota.close(); sotaCatalogue.close(); catalogues.close() }
    fun refreshAll() { pota.refreshSpots(); refreshWwff(); ensureSotaCluster(); resolveSotaSpots();
        if (catalogues.statuses.getValue(PortableCatalogueProgram.IOTA).rowCount == 0) catalogues.refreshIota()
        if (catalogues.statuses.getValue(PortableCatalogueProgram.WWFF).rowCount == 0) catalogues.refreshWwff() }
    fun refreshWwff() { scope.launch { refreshWwffNow() } }
    fun notifyQsoChanged() { lastQsoRevision=qsoDatabase.changeToken();opportunityKey=null;pota.notifyQsoChanged() }

    fun markForegroundAge(now: Long = Instant.now().epochSecond) {
        pota.markForegroundAge(now)
        if (wwffStatus.kind == PortableFeedKind.LIVE && now - wwffStatus.fetchedAt > 90) wwffStatus = wwffStatus.copy(kind = PortableFeedKind.CACHED)
        if (wwffStatus.fetchedAt > 0 && now - wwffStatus.fetchedAt > 60 * 60) wwffStatus = wwffStatus.copy(kind = PortableFeedKind.STALE)
        if (qsoDatabase.changeToken() != lastQsoRevision) notifyQsoChanged()
        if (sotaStatus.kind == PortableFeedKind.LIVE && now - sotaStatus.fetchedAt > 90) sotaStatus = sotaStatus.copy(kind = PortableFeedKind.CACHED)
        if (sotaStatus.fetchedAt > 0 && now - sotaStatus.fetchedAt > 60 * 60) sotaStatus = sotaStatus.copy(kind = PortableFeedKind.STALE)
        ensureSotaCluster()
    }

    fun setSotaClusterActive(active: Boolean) {
        sotaClusterActive = active
        if (active) ensureSotaCluster() else stopSotaCluster()
    }

    fun providerStatus(program: PortableProgram): ProviderStatus = when (program) {
        PortableProgram.POTA -> ProviderStatus(
            when (pota.feedKind) { PotaFeedKind.LOADING -> PortableFeedKind.LOADING; PotaFeedKind.LIVE -> PortableFeedKind.LIVE; PotaFeedKind.CACHED -> PortableFeedKind.CACHED; PotaFeedKind.OFFLINE -> PortableFeedKind.OFFLINE; PotaFeedKind.FAILED -> PortableFeedKind.FAILED },
            pota.fetchedAt, pota.spots.size, pota.feedError)
        PortableProgram.SOTA -> sotaStatus
        PortableProgram.WWFF -> wwffStatus
    }

    fun refreshOpportunities(now: Long, radioFrequencyHz: Long, stationGrid: String) {
        val key = listOf(lastQsoRevision, now / 15, radioFrequencyHz, stationGrid,
            pota.spots.size, pota.spots.maxOfOrNull(PotaSpot::spottedAt),
            sotaSpots.size, sotaSpots.maxOfOrNull(PortableSpot::spottedAt),
            wwffSpots.size, wwffSpots.maxOfOrNull(PortableSpot::spottedAt))
        if (key == opportunityKey) return
        opportunityKey = key
        opportunityJob?.cancel()
        val raw = pota.spots.map(PotaSpot::toPortable) + sotaSpots + wwffSpots
        opportunityJob = scope.launch {
            val worked=withContext(Dispatchers.IO){logRepository.states(raw,now)}
            rankedOpportunities = withContext(Dispatchers.Default) {
                val station = maidenheadCenter(stationGrid)
                groupPortableSpots(raw).map { rankPortableSpot(it, worked[it.id].orEmpty(), now, radioFrequencyHz, station) }
            }
        }
    }

    fun recentWwff(query: String): List<PortableReference> {
        val q = query.trim().uppercase(Locale.US)
        return wwffSpots.flatMap(PortableSpot::references).filter { it.program == PortableProgram.WWFF &&
            (q.isBlank() || it.code.contains(q) || it.name.uppercase(Locale.US).contains(q)) }.distinctBy(PortableReference::code).take(100)
    }

    private fun configuredClusterLogin(): String = clusterPrefs.getString("callsign", "").orEmpty().trim().uppercase(Locale.US)

    private fun ensureSotaCluster() {
        if (!sotaClusterActive) return
        val login = configuredClusterLogin()
        if (login.isBlank()) {
            stopSotaCluster()
            sotaStatus = ProviderStatus(PortableFeedKind.UNAVAILABLE, error = "Configure a DX-cluster username in Settings")
            return
        }
        if (sotaClusterJob?.isActive == true && login == sotaClusterLogin) return
        stopSotaCluster(updateStatus = false)
        sotaClusterLogin = login
        val generation = ++sotaClusterGeneration
        sotaStatus = ProviderStatus(if (sotaSpots.isEmpty()) PortableFeedKind.LOADING else PortableFeedKind.CACHED,
            sotaStatus.fetchedAt, sotaSpots.size, "Connecting to $SOTA_CLUSTER_HOST:$SOTA_CLUSTER_PORT")
        sotaClusterJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (sotaClusterActive && generation == sotaClusterGeneration) {
                try {
                    val socket = Socket().apply { connect(InetSocketAddress(SOTA_CLUSTER_HOST, SOTA_CLUSTER_PORT), 12_000); keepAlive = true }
                    sotaClusterSocket = socket
                    val output = socket.getOutputStream()
                    output.write((login + "\r\n").toByteArray(Charsets.UTF_8)); output.flush()
                    delay(1_500)
                    if (!sotaClusterActive || generation != sotaClusterGeneration) break
                    output.write("sh/dx 50\r\n".toByteArray(Charsets.UTF_8)); output.flush()
                    withContext(Dispatchers.Main.immediate) {
                        sotaStatus = ProviderStatus(PortableFeedKind.LIVE, Instant.now().epochSecond, sotaSpots.size,
                            "Receive-only $SOTA_CLUSTER_HOST:$SOTA_CLUSTER_PORT")
                    }
                    attempt = 0
                    BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                        while (sotaClusterActive && generation == sotaClusterGeneration) {
                            val line = reader.readLine() ?: break
                            val provisional = parseSotaClusterLine(line) ?: continue
                            val code = provisional.primary.code
                            val summit = resolvedSummits[code] ?: sotaCatalogue.lookup(setOf(code))[code]?.also { resolvedSummits[code] = it }
                            val spot = parseSotaClusterLine(line, summit?.let { mapOf(code to it) }.orEmpty()) ?: continue
                            withContext(Dispatchers.Main.immediate) { publishSotaClusterSpot(spot) }
                        }
                    }
                } catch (error: Throwable) {
                    if (sotaClusterActive && generation == sotaClusterGeneration) withContext(Dispatchers.Main.immediate) {
                        sotaStatus = ProviderStatus(if (sotaSpots.isEmpty()) PortableFeedKind.FAILED else PortableFeedKind.CACHED,
                            sotaStatus.fetchedAt, sotaSpots.size, "SOTA cluster reconnecting: ${error.message?.take(100) ?: "connection failed"}")
                    }
                } finally {
                    runCatching { sotaClusterSocket?.close() }; sotaClusterSocket = null
                }
                if (!sotaClusterActive || generation != sotaClusterGeneration) break
                delay(minOf(30, 1 shl minOf(attempt++, 4)) * 1_000L)
            }
        }
    }

    private fun publishSotaClusterSpot(spot: PortableSpot) {
        val key: (PortableSpot) -> String = { "${it.callsign}|${it.primary.code}|${it.frequencyHz}" }
        sotaSpots = (listOf(spot) + sotaSpots).distinctBy(key).sortedByDescending(PortableSpot::spottedAt).take(250)
        sotaStatus = ProviderStatus(PortableFeedKind.LIVE, maxOf(sotaStatus.fetchedAt, spot.spottedAt),
            sotaSpots.count { it.activeAt(Instant.now().epochSecond) }, "Receive-only $SOTA_CLUSTER_HOST:$SOTA_CLUSTER_PORT")
        opportunityKey = null
    }

    private fun resolveSotaSpots() {
        val current = sotaSpots
        val references = current.map { it.primary.code }.filter(String::isNotBlank).toSet()
        if (!sotaCatalogue.metadata.ready || references.isEmpty()) return
        scope.launch {
            val summits = withContext(Dispatchers.IO) { sotaCatalogue.lookup(references) }
            resolvedSummits.putAll(summits)
            sotaSpots = current.map { spot ->
                val summit = summits[spot.primary.code] ?: return@map spot
                spot.copy(references = listOf(PortableReference(PortableProgram.SOTA, summit.code, summit.name, summit.association, summit.region,
                    summit.altitudeM, summit.points, summit.latitude, summit.longitude, summit.grid)), latitude = summit.latitude, longitude = summit.longitude)
            }
            opportunityKey = null
        }
    }

    private fun stopSotaCluster(updateStatus: Boolean = true) {
        sotaClusterGeneration++
        sotaClusterJob?.cancel(); sotaClusterJob = null
        runCatching { sotaClusterSocket?.close() }; sotaClusterSocket = null
        if (updateStatus) sotaStatus = ProviderStatus(if (sotaSpots.isEmpty()) PortableFeedKind.UNAVAILABLE else PortableFeedKind.CACHED,
            sotaStatus.fetchedAt, sotaSpots.size, if (sotaSpots.isEmpty()) "SOTA cluster inactive" else "SOTA cluster paused · cached spots retained")
    }

    private suspend fun refreshWwffNow(now: Long = Instant.now().epochSecond) = wwffMutex.withLock {
        if (wwffStatus.fetchedAt > 0 && now - wwffStatus.fetchedAt < 30) return@withLock
        if (wwffSpots.isEmpty()) wwffStatus = ProviderStatus(PortableFeedKind.LOADING)
        val result = withContext(Dispatchers.IO) {
            capturePortableProviderPair(
                first = { fetch(WWFF_SPOTS_URL, "wwff_spots") },
                second = { fetch(WWFF_AGENDAS_URL, "wwff_agendas") },
            )
        }
        result.fold(onSuccess = { (spots, agendas) ->
            runCatching { parseWwffSpots(spots.body, agendas.body, now) }.fold(onSuccess = { normalized ->
                wwffSpots = normalized
                wwffStatus = ProviderStatus(if (normalized.any { it.activeAt(now) }) PortableFeedKind.LIVE else PortableFeedKind.EMPTY, now, normalized.count { it.activeAt(now) })
                cacheAtomically(wwffCache, spots.body); cacheAtomically(wwffAgendaCache, agendas.body)
                prefs.edit().putLong("wwff_fetched", now).putString("wwff_spots_etag", spots.etag).putString("wwff_spots_modified", spots.modified)
                    .putString("wwff_agendas_etag", agendas.etag).putString("wwff_agendas_modified", agendas.modified).apply()
            }, onFailure = { failWwff("WWFF response could not be parsed", now) })
        }, onFailure = { failWwff(it.message ?: "WWFF refresh failed", now) })
    }

    private data class HttpText(val body: String, val etag: String, val modified: String)

    private fun fetch(url: String, key: String): HttpText {
        var failure: Throwable? = null
        repeat(2) { attempt ->
            try {
                val connection = openConnection(url).apply {
                    prefs.getString("${key}_etag", "")?.takeIf(String::isNotBlank)?.let { setRequestProperty("If-None-Match", it) }
                    prefs.getString("${key}_modified", "")?.takeIf(String::isNotBlank)?.let { setRequestProperty("If-Modified-Since", it) }
                }
                try {
                    val cache = if (key == "wwff_spots") wwffCache else wwffAgendaCache
                    if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && cache.exists()) return HttpText(cache.readText(), connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty())
                    if (connection.responseCode !in 200..299) error("WWFF returned HTTP ${connection.responseCode}")
                    return HttpText(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty())
                } finally { connection.disconnect() }
            } catch (error: Throwable) { failure = error; if (attempt == 0) SystemClock.sleep(350) }
        }
        throw failure ?: IllegalStateException("WWFF refresh failed")
    }

    private fun openConnection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 20_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", PORTABLE_USER_AGENT); setRequestProperty("Accept", "application/json,*/*")
    }

    private fun failWwff(message: String, now: Long) {
        wwffStatus = ProviderStatus(if (wwffSpots.isNotEmpty()) PortableFeedKind.CACHED else PortableFeedKind.FAILED,
            wwffStatus.fetchedAt.takeIf { it > 0 } ?: now, wwffSpots.count { it.activeAt(now) }, message)
    }

    private fun loadWwffCache() {
        if (!wwffCache.exists()) return
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { parseWwffSpots(wwffCache.readText(), wwffAgendaCache.takeIf(File::exists)?.readText().orEmpty().ifBlank { "[]" }) }
            }
            cached.onSuccess { wwffSpots = it; wwffStatus = ProviderStatus(PortableFeedKind.CACHED, prefs.getLong("wwff_fetched", 0), it.size) }
                .onFailure { wwffStatus = ProviderStatus(PortableFeedKind.FAILED, error = "Saved WWFF snapshot is unreadable") }
        }
    }

    private fun cacheAtomically(file: File, body: String) {
        runCatching { val part = File(file.path + ".part"); part.writeText(body); if (!part.renameTo(file)) { file.writeText(body); part.delete() } }
    }
}

internal suspend fun <A, B> capturePortableProviderPair(
    first: suspend () -> A,
    second: suspend () -> B,
): Result<Pair<A, B>> = supervisorScope {
    val firstResult = async { first() }
    val secondResult = async { second() }
    runCatching { firstResult.await() to secondResult.await() }
}

internal class SotaCatalogue(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("rigweave-sota-catalogue", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val activeDb = File(appContext.filesDir, "sota-summits.sqlite")
    private val stagingDb = File(appContext.filesDir, "sota-summits.sqlite.staging")
    private val stagingCsv = File(appContext.cacheDir, "sota-summits.csv.part")
    private var cancel = false

    var metadata by mutableStateOf(loadMetadata()); private set
    var progress by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var results by mutableStateOf<List<SotaSummit>>(emptyList()); private set

    init { val backup = File(activeDb.path + ".previous"); if (!activeDb.exists() && backup.exists()) backup.renameTo(activeDb); metadata = loadMetadata() }
    fun close() { cancel = true; scope.cancel() }
    fun update() { if (!busy) { cancel = false; scope.launch { updateNow() } } }
    fun cancelUpdate() { cancel = true }
    fun search(query: String, association: String = "", region: String = "", stationGrid: String = "", nearby: Boolean = false) {
        scope.launch { results = withContext(Dispatchers.IO) { if (!metadata.ready || !activeDb.exists()) emptyList() else query(query, association, region, maidenheadCenter(stationGrid), nearby) } }
    }
    suspend fun nearbySummits(stationGrid: String, radiusKm: Double, limit: Int = 1_000): CatalogueRows<SotaSummit> = withContext(Dispatchers.IO) {
        if (!metadata.ready || !activeDb.exists()) return@withContext CatalogueRows(emptyList(), 0)
        val database = SQLiteDatabase.openDatabase(activeDb.path, null, SQLiteDatabase.OPEN_READONLY)
        val invalid = try {
            database.rawQuery("SELECT COUNT(*) FROM summits WHERE latitude IS NULL OR longitude IS NULL", null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } finally { database.close() }
        CatalogueRows(query("", "", "", maidenheadCenter(stationGrid), nearby = true, radiusKm = radiusKm, resultLimit = limit), invalid)
    }
    fun lookup(references: Set<String>): Map<String, SotaSummit> = if (!metadata.ready || references.isEmpty() || !activeDb.exists()) emptyMap() else runCatching {
        val db = SQLiteDatabase.openDatabase(activeDb.path, null, SQLiteDatabase.OPEN_READONLY)
        try { references.chunked(200).flatMap { chunk -> db.rawQuery("SELECT code,association,region,name,alt_m,alt_ft,points,bonus,latitude,longitude,grid,valid_from,valid_to FROM summits WHERE code IN (${chunk.joinToString { "?" }})", chunk.toTypedArray()).use(::readSummits) }.associateBy(SotaSummit::code) } finally { db.close() }
    }.getOrDefault(emptyMap())

    fun checkOncePerForegroundDay() {
        val today = LocalDate.now(ZoneOffset.UTC).toString(); if (prefs.getString("check_day", "") == today || busy || !metadata.ready) return
        prefs.edit().putString("check_day", today).apply()
        scope.launch { val changed = withContext(Dispatchers.IO) { runCatching { remoteChanged() }.getOrDefault(false) }; if (changed) metadata = metadata.copy(updateAvailable = true) }
    }

    private suspend fun updateNow() = mutex.withLock {
        busy = true; progress = 0
        try {
            val download = withContext(Dispatchers.IO) { download() }
            if (cancel) throw InterruptedException("cancelled")
            val imported = withContext(Dispatchers.IO) { import(download) }
            metadata = imported; saveMetadata(imported); results = emptyList()
        } catch (error: Throwable) {
            val message = if (error is InterruptedException) "Summit update cancelled — previous database retained" else "Summit update failed — previous database retained: ${error.message ?: "unknown error"}"
            metadata = metadata.copy(ready = activeDb.exists() && metadata.rowCount > 0, failure = message); saveMetadata(metadata)
        } finally { stagingCsv.delete(); stagingDb.delete(); File(stagingDb.path + "-journal").delete(); busy = false; progress = 0; cancel = false }
    }

    private data class Download(val bytes: Long, val etag: String, val modified: String, val sha: String, val epoch: Long)
    private fun connection() = (URL(SOTA_SUMMITS_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 120_000; instanceFollowRedirects = true; setRequestProperty("User-Agent", PORTABLE_USER_AGENT); setRequestProperty("Accept", "text/csv,*/*")
    }
    private fun download(): Download {
        val connection = connection()
        try {
            if (connection.responseCode !in 200..299) error("SOTA catalogue returned HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0; val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
            BufferedInputStream(connection.inputStream, 128 * 1024).use { input -> BufferedOutputStream(FileOutputStream(stagingCsv), 128 * 1024).use { output ->
                val buffer = ByteArray(128 * 1024); while (true) { if (cancel) throw InterruptedException("cancelled"); val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); digest.update(buffer, 0, read); bytes += read; progress = if (total > 0) (bytes * 45 / total).toInt().coerceIn(0, 45) else 0 }
            } }
            if (bytes < 1_000_000) error("summit list was unexpectedly small")
            return Download(bytes, connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty(), digest.digest().joinToString("") { "%02x".format(it) }, Instant.now().epochSecond)
        } finally { connection.disconnect() }
    }

    private fun import(download: Download): SotaCatalogueMetadata {
        stagingDb.delete(); val db = SQLiteDatabase.openOrCreateDatabase(stagingDb, null); var imported = 0; var rejected = 0; val seen = HashSet<String>(200_000)
        try {
            db.execSQL("CREATE TABLE summits(code TEXT PRIMARY KEY, association TEXT NOT NULL, association_norm TEXT NOT NULL, region TEXT NOT NULL, region_norm TEXT NOT NULL, name TEXT NOT NULL, name_norm TEXT NOT NULL, alt_m INTEGER, alt_ft INTEGER, points INTEGER, bonus INTEGER, latitude REAL, longitude REAL, grid TEXT NOT NULL, valid_from TEXT NOT NULL, valid_to TEXT NOT NULL)")
            db.beginTransaction(); Utf8CsvReader(stagingCsv).use { csv ->
                var header: Map<String, Int>? = null
                repeat(5) { if (header == null) { val candidate = csv.nextRow() ?: return@repeat; val mapped = candidate.mapIndexed { index, value -> normalizedHeader(value) to index }.toMap(); if (mapped.containsKey("summitcode")) header = mapped } }
                val h = header ?: error("summit catalogue header is missing")
                val required = listOf("summitcode", "associationname", "regionname", "summitname", "longitude", "latitude", "validfrom", "validto")
                if (!required.all(h::containsKey)) error("summit headers changed: missing ${required.filterNot(h::containsKey).joinToString()}")
                fun List<String>.field(name: String) = h[name]?.let(::getOrNull).orEmpty().trim()
                while (true) {
                    if (cancel) throw InterruptedException("cancelled"); val row = csv.nextRow() ?: break
                    val code = normalizeSotaReference(row.field("summitcode")); val name = row.field("summitname").take(240)
                    val latRaw = row.field("latitude"); val lonRaw = row.field("longitude"); val lat = latRaw.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }; val lon = lonRaw.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                    if (code.isBlank() || name.isBlank() || (latRaw.isNotBlank() && lat == null) || (lonRaw.isNotBlank() && lon == null)) { rejected++; continue }
                    if (!seen.add(code)) error("summit list contains duplicate $code")
                    try {
                        db.execSQL("INSERT INTO summits VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(code, row.field("associationname").take(160), row.field("associationname").uppercase(Locale.US).take(160), row.field("regionname").take(160), row.field("regionname").uppercase(Locale.US).take(160), name, name.uppercase(Locale.US), row.field("altm").toIntOrNull(), row.field("altft").toIntOrNull(), row.field("points").toIntOrNull(), row.field("bonuspoints").toIntOrNull(), lat, lon, row.field("gridref1").ifBlank { row.field("gridref2") }.uppercase(Locale.US).take(12), row.field("validfrom").take(16), row.field("validto").take(16)))
                        imported++
                    } catch (_: Throwable) { rejected++ }
                    if (imported % 2_000 == 0) progress = 45 + (imported / 2_500).coerceAtMost(48)
                }
            }
            if (imported < 10_000) error("catalogue validation found only $imported summits")
            if (rejected > max(100, imported / 20)) error("catalogue validation rejected $rejected of ${imported + rejected} rows")
            db.execSQL("CREATE INDEX summits_name_idx ON summits(name_norm)"); db.execSQL("CREATE INDEX summits_association_idx ON summits(association_norm)"); db.execSQL("CREATE INDEX summits_region_idx ON summits(region_norm)"); db.execSQL("CREATE INDEX summits_coordinates_idx ON summits(latitude,longitude)"); db.setTransactionSuccessful()
        } finally { if (db.inTransaction()) db.endTransaction(); db.close() }
        val verify = SQLiteDatabase.openDatabase(stagingDb.path, null, SQLiteDatabase.OPEN_READONLY)
        try { val count = verify.rawQuery("SELECT COUNT(*) FROM summits", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }; val sample = verify.rawQuery("SELECT code FROM summits ORDER BY code LIMIT 1", null).use { it.moveToFirst() && it.getString(0).isNotBlank() }; if (count != imported || !sample) error("staged summit catalogue could not be reopened and sampled") } finally { verify.close() }
        if (!activateSotaCatalogue(stagingDb, activeDb, true)) error("could not activate staged summit database")
        progress = 100; return SotaCatalogueMetadata(true, imported, download.epoch, Instant.now().epochSecond, download.etag, download.modified, download.sha, download.bytes)
    }

    private fun query(query: String, association: String, region: String, station: GeoPoint?, nearby: Boolean, radiusKm: Double? = null, resultLimit: Int = 100): List<SotaSummit> {
        val db = SQLiteDatabase.openDatabase(activeDb.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val q = query.trim().uppercase(Locale.US); val a = association.trim().uppercase(Locale.US); val r = region.trim().uppercase(Locale.US); val clauses = mutableListOf("1=1"); val args = mutableListOf<String>()
            if (q.isNotBlank()) { clauses += "(code LIKE ? OR name_norm LIKE ?)"; args += "$q%"; args += "%$q%" }; if (a.isNotBlank()) { clauses += "association_norm LIKE ?"; args += "%$a%" }; if (r.isNotBlank()) { clauses += "region_norm LIKE ?"; args += "%$r%" }
            if (nearby && station != null) {
                val latDelta = radiusKm?.div(110.574)?.coerceAtLeast(.1) ?: 15.0
                val longitudeScale = kotlin.math.cos(Math.toRadians(station.latitude)).coerceAtLeast(.05)
                val lonDelta = radiusKm?.div(111.320 * longitudeScale)?.coerceAtMost(180.0) ?: 20.0
                clauses += "latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?"
                args += (station.latitude - latDelta).coerceAtLeast(-90.0).toString(); args += (station.latitude + latDelta).coerceAtMost(90.0).toString()
                args += (station.longitude - lonDelta).coerceAtLeast(-180.0).toString(); args += (station.longitude + lonDelta).coerceAtMost(180.0).toString()
            }
            val order = if (nearby && station != null) {
                args += station.latitude.toString(); args += station.latitude.toString(); args += station.longitude.toString(); args += station.longitude.toString()
                "((latitude - ?) * (latitude - ?) + (longitude - ?) * (longitude - ?)), code"
            } else "code"
            val rows = db.rawQuery("SELECT code,association,region,name,alt_m,alt_ft,points,bonus,latitude,longitude,grid,valid_from,valid_to FROM summits WHERE ${clauses.joinToString(" AND ")} ORDER BY $order LIMIT ${if (nearby) 5_000 else resultLimit.coerceIn(1, 500)}", args.toTypedArray()).use(::readSummits).map { summit ->
                val point = if (summit.latitude != null && summit.longitude != null) GeoPoint(summit.latitude, summit.longitude) else null
                summit.copy(distanceKm = if (station != null && point != null) distanceKm(station, point) else null, bearingDegrees = if (station != null && point != null) initialBearingDegrees(station, point) else null)
            }
            return if (nearby) rows.asSequence()
                .filter { radiusKm == null || (it.distanceKm != null && it.distanceKm <= radiusKm) }
                .sortedWith(compareBy<SotaSummit> { it.distanceKm ?: Double.MAX_VALUE }.thenBy(SotaSummit::code))
                .take(resultLimit.coerceIn(1, 1_000)).toList() else rows
        } finally { db.close() }
    }

    private fun readSummits(cursor: android.database.Cursor): List<SotaSummit> = buildList { while (cursor.moveToNext()) add(SotaSummit(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), if (cursor.isNull(4)) null else cursor.getInt(4), if (cursor.isNull(5)) null else cursor.getInt(5), if (cursor.isNull(6)) null else cursor.getInt(6), if (cursor.isNull(7)) null else cursor.getInt(7), if (cursor.isNull(8)) null else cursor.getDouble(8), if (cursor.isNull(9)) null else cursor.getDouble(9), cursor.getString(10), cursor.getString(11), cursor.getString(12))) }
    private fun remoteChanged(): Boolean { val connection = connection().apply { requestMethod = "HEAD"; metadata.etag.takeIf(String::isNotBlank)?.let { setRequestProperty("If-None-Match", it) }; metadata.lastModified.takeIf(String::isNotBlank)?.let { setRequestProperty("If-Modified-Since", it) } }; return try { when (connection.responseCode) { HttpURLConnection.HTTP_NOT_MODIFIED -> false; in 200..299 -> connection.getHeaderField("ETag").orEmpty() != metadata.etag || connection.getHeaderField("Last-Modified").orEmpty() != metadata.lastModified; else -> false } } finally { connection.disconnect() } }
    private fun loadMetadata(): SotaCatalogueMetadata = runCatching { val row = JSONObject(prefs.getString("metadata", "{}") ?: "{}"); SotaCatalogueMetadata(activeDb.exists() && row.optInt("rows") > 0, row.optInt("rows"), row.optLong("downloaded"), row.optLong("imported"), row.optString("etag"), row.optString("modified"), row.optString("sha256"), row.optLong("bytes"), row.optBoolean("update"), row.optString("failure")) }.getOrDefault(SotaCatalogueMetadata())
    private fun saveMetadata(meta: SotaCatalogueMetadata) { prefs.edit().putString("metadata", JSONObject().put("rows", meta.rowCount).put("downloaded", meta.downloadedAt).put("imported", meta.importedAt).put("etag", meta.etag).put("modified", meta.lastModified).put("sha256", meta.sha256).put("bytes", meta.sourceBytes).put("update", meta.updateAvailable).put("failure", meta.failure).toString()).apply() }
}

internal fun activateSotaCatalogue(staging: File, active: File, valid: Boolean): Boolean {
    if (!valid || !staging.exists()) return false
    val backup = File(active.path + ".previous"); if (!active.exists() && backup.exists()) backup.renameTo(active); backup.delete()
    if (active.exists() && !active.renameTo(backup)) return false
    if (!staging.renameTo(active)) { backup.renameTo(active); return false }
    backup.delete(); return true
}
