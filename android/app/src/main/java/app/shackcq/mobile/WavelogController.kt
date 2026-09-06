package app.shackcq.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class LogMode { LOCAL, WAVELOG }
const val WAVELOG_SYNC_PAGE_SIZE = 1_000
const val NTP_SYNC_INTERVAL_MILLIS = 60L * 60L * 1_000L
internal fun ntpSyncDue(nowMillis: Long, lastSuccessMillis: Long): Boolean =
    lastSuccessMillis <= 0L || nowMillis - lastSuccessMillis >= NTP_SYNC_INTERVAL_MILLIS

data class AndroidWavelogStation(
    val id: String, val name: String, val callsign: String, val grid: String, val city: String,
    val country: String, val dxcc: String, val cqZone: String, val ituZone: String, val state: String,
    val iota: String, val sotaRef: String, val wwffRef: String, val potaRef: String, val active: Boolean,
) { val label get() = listOf(name, callsign, grid).filter { it.isNotBlank() }.joinToString(" · ") }

internal fun encodeWavelogStations(stations: List<AndroidWavelogStation>): String = JSONArray().apply {
    stations.forEach { station -> put(JSONObject()
        .put("id", station.id).put("name", station.name).put("callsign", station.callsign).put("grid", station.grid)
        .put("city", station.city).put("country", station.country).put("dxcc", station.dxcc)
        .put("cq_zone", station.cqZone).put("itu_zone", station.ituZone).put("state", station.state)
        .put("iota", station.iota).put("sota", station.sotaRef).put("wwff", station.wwffRef)
        .put("pota", station.potaRef).put("active", station.active)) }
}.toString()

internal fun decodeWavelogStations(value: String?): List<AndroidWavelogStation> = runCatching {
    val rows = JSONArray(value ?: "[]")
    List(rows.length()) { index -> rows.getJSONObject(index).let { row -> AndroidWavelogStation(
        row.optString("id"), row.optString("name"), row.optString("callsign"), row.optString("grid"),
        row.optString("city"), row.optString("country"), row.optString("dxcc"), row.optString("cq_zone"),
        row.optString("itu_zone"), row.optString("state"), row.optString("iota"), row.optString("sota"),
        row.optString("wwff"), row.optString("pota"), row.optBoolean("active")) } }
}.getOrDefault(emptyList())

class WavelogController(private val context: Context, private val database: QsoDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("wavelog", Context.MODE_PRIVATE)
    private val queueFile = File(context.filesDir, "wavelog-queue.json")
    private val syncMutex = Mutex()
    private val ntpMutex = Mutex()
    private val queueLock = Any()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (logMode == LogMode.WAVELOG) syncTwoWay()
            scope.launch { synchronizeTime(force = false) }
        }
    }

    var baseURL by mutableStateOf(prefs.getString("base_url", ShackCQDefaults.WAVELOG_BASE_URL) ?: ShackCQDefaults.WAVELOG_BASE_URL); private set
    var stationId by mutableStateOf(prefs.getString("station_id", "") ?: ""); private set
    var apiKey by mutableStateOf(decrypt(prefs.getString("api_key", "") ?: "")); private set
    var ntpServer by mutableStateOf(prefs.getString("ntp_server", "time.google.com") ?: "time.google.com"); private set
    private var ntpOffsetMillis = prefs.getLong("ntp_offset_ms", 0L)
    private var lastNtpSuccessMillis = prefs.getLong("ntp_last_success_ms", 0L)
    private var lastNtpAttemptMillis = lastNtpSuccessMillis
    var logMode by mutableStateOf(runCatching { LogMode.valueOf(prefs.getString("log_mode", "LOCAL")!!) }.getOrDefault(LogMode.LOCAL)); private set
    var stations by mutableStateOf(decodeWavelogStations(prefs.getString("stations_json", null))); private set
    var status by mutableStateOf(if (stations.isEmpty()) "Wavelog not configured" else "${stations.size} saved Wavelog stations restored"); private set
    var timeStatus by mutableStateOf(if (lastNtpSuccessMillis > 0L) "Automatic NTP active · hourly when online" else "Automatic NTP pending · hourly when online"); private set
    var pendingCount by mutableStateOf(loadQueue()?.length() ?: 0); private set
    var syncPages by mutableStateOf(0); private set

    val selectedStation get() = stations.firstOrNull { it.id == stationId }
    val configured get() = baseURL.isNotBlank() && apiKey.isNotBlank() && stationId.toLongOrNull() != null
    val apiGeneration get() = if (apiKey.startsWith("wl2_")) WavelogApiGeneration.V2 else WavelogApiGeneration.LEGACY
    val legacyConfigured get() = configured && apiGeneration == WavelogApiGeneration.LEGACY
    fun synchronizedNow(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() + ntpOffsetMillis)

    init {
        if (!prefs.contains("base_url")) prefs.edit().putString("base_url", baseURL).apply()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        scope.launch {
            delay(1_000)
            if (logMode == LogMode.WAVELOG && apiGeneration == WavelogApiGeneration.LEGACY) runTwoWay()
            synchronizeTime(force = false)
            while (isActive) {
                delay(60_000)
                if (logMode == LogMode.WAVELOG && apiGeneration == WavelogApiGeneration.LEGACY && (pendingCount > 0 || legacyConfigured)) runTwoWay()
                synchronizeTime(force = false)
            }
        }
    }

    fun updateBaseURL(value: String) { baseURL = value; prefs.edit().putString("base_url", value).apply() }
    fun setStation(value: String) {
        stationId = value; prefs.edit().putString("station_id", value).apply()
        if (logMode == LogMode.WAVELOG && apiGeneration == WavelogApiGeneration.LEGACY) syncTwoWay()
    }
    fun updateApiKey(value: String) { apiKey = value; prefs.edit().putString("api_key", encrypt(value)).apply() }
    fun updateNtpServer(value: String) { ntpServer = value.trim(); prefs.edit().putString("ntp_server", ntpServer).apply() }
    fun updateLogMode(value: LogMode) {
        logMode = value; prefs.edit().putString("log_mode", value.name).apply()
        status = if (value == LogMode.LOCAL) "Local ADIF logging selected" else "Wavelog two-way logging selected"
        if (value == LogMode.WAVELOG && apiGeneration == WavelogApiGeneration.LEGACY) syncTwoWay()
    }

    fun enqueue(id: String, adif: String) {
        if (apiGeneration == WavelogApiGeneration.V2) {
            val store = WavelogSyncStore(database)
            val binding = store.configuredBinding()
            val qso = database.qso(id)
            if (binding != null && qso != null && binding.state != WavelogBindingState.READ_ONLY)
                store.enqueue(binding.id, id, WavelogOperation.CREATE, WavelogCanonicalizer.fromAdif(adif),
                    state = if (binding.state == WavelogBindingState.PAUSED)
                        WavelogOutboxState.PAUSED else WavelogOutboxState.PENDING)
            status = if (binding == null) "API v2 QSO remains local · bind a station in Sync Hub" else "API v2 QSO queued durably"
            return
        }
        synchronized(queueLock) {
            val queue = loadQueue() ?: run {
                status = "Legacy Wavelog queue is unreadable and was preserved for recovery"
                return
            }
            if ((0 until queue.length()).any { queue.getJSONObject(it).optString("id") == id }) return
            queue.put(JSONObject().put("id", id).put("adif", adif).put("state", "pending").put("attempts", 0)); writeJson(queueFile, queue)
            pendingCount = queue.length()
        }
        if (logMode == LogMode.WAVELOG) syncTwoWay()
    }

    fun loadStations() = scope.launch {
        if (baseURL.isBlank() || apiKey.isBlank()) { publish("Wavelog URL and API key are required"); return@launch }
        try {
            val rows = JSONArray(request("station_info/" + encodePath(apiKey), "GET", null))
            val loaded = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index); val id = row.optString("station_id")
                    if (id.toLongOrNull() != null) add(AndroidWavelogStation(
                        id, row.optString("station_profile_name"), row.optString("station_callsign"),
                        row.optString("station_gridsquare"), row.optString("station_city"), row.optString("station_country"),
                        row.optString("station_dxcc"), row.optString("station_cq"), row.optString("station_itu"),
                        row.optString("station_state"), row.optString("station_iota"), row.optString("station_sota"),
                        row.optString("station_wwff"), row.optString("station_pota"), truth(row.opt("station_active"))))
                }
            }
            withContext(Dispatchers.Main) {
                stations = loaded
                prefs.edit().putString("stations_json", encodeWavelogStations(loaded)).apply()
                if (stationId.isBlank() || loaded.none { it.id == stationId })
                    (loaded.firstOrNull { it.active } ?: loaded.firstOrNull())?.let { setStation(it.id) }
                status = if (loaded.isEmpty()) "No Wavelog stations available" else "${loaded.size} Wavelog stations loaded"
            }
        } catch (error: Exception) { publish("Load stations failed: " + error.message) }
    }

    fun testConnection() = scope.launch {
        if (baseURL.isBlank() || apiKey.isBlank()) { publish("Wavelog URL and API key are required"); return@launch }
        try {
            val body = request("version", "POST", JSONObject().put("key", apiKey))
            require(body.isNotBlank() && !body.contains("unauthorized", true) && !body.contains("invalid", true))
            publish("Wavelog connection passed")
        } catch (error: Exception) { publish("Wavelog test failed: ${error.message}") }
    }

    fun checkTime() = scope.launch { synchronizeTime(force = true) }

    private suspend fun synchronizeTime(force: Boolean) = ntpMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force) {
            if (connectivity.activeNetwork == null || !ntpSyncDue(now, lastNtpAttemptMillis)) return@withLock
            lastNtpAttemptMillis = now
        }
        try {
            val host = ntpServer.ifBlank { "time.google.com" }; val request = ByteArray(48); request[0] = 0x1B
            val address = InetAddress.getByName(host); val sentAt = System.currentTimeMillis()
            val ntpSentSeconds = sentAt / 1_000L + 2_208_988_800L
            val ntpSentFraction = ((sentAt % 1_000L) * 0x1_0000_0000L / 1_000L)
            for (index in 0..3) {
                request[40 + index] = (ntpSentSeconds ushr (24 - index * 8)).toByte()
                request[44 + index] = (ntpSentFraction ushr (24 - index * 8)).toByte()
            }
            val response = ByteArray(48)
            val socket = DatagramSocket().apply { soTimeout = 8_000 }
            socket.use {
                it.connect(address, 123)
                it.send(DatagramPacket(request, request.size))
                val packet = DatagramPacket(response, response.size)
                it.receive(packet)
                require(packet.length == 48) { "NTP response length is invalid" }
            }
            val receivedAt = System.currentTimeMillis()
            val leap = (response[0].toInt() ushr 6) and 0x03
            val version = (response[0].toInt() ushr 3) and 0x07
            val mode = response[0].toInt() and 0x07
            val stratum = response[1].toInt() and 0xff
            require(leap != 3 && version >= 3 && mode in setOf(4, 5) && stratum in 1..15) { "NTP response header is invalid" }
            require(response.copyOfRange(24, 32).contentEquals(request.copyOfRange(40, 48))) { "NTP response did not match this request" }
            fun unsigned(offset: Int) = response[offset].toLong() and 0xff
            val seconds = (unsigned(40) shl 24) or (unsigned(41) shl 16) or (unsigned(42) shl 8) or unsigned(43)
            val fraction = (unsigned(44) shl 24) or (unsigned(45) shl 16) or (unsigned(46) shl 8) or unsigned(47)
            require(seconds > 2_208_988_800L) { "NTP transmit timestamp is invalid" }
            val serverMillis = (seconds - 2_208_988_800L) * 1_000L + (fraction * 1_000L / 0x1_0000_0000L)
            val drift = serverMillis - ((sentAt + receivedAt) / 2L); val absolute = kotlin.math.abs(drift)
            require(absolute <= 86_400_000L) { "NTP offset exceeds the 24-hour safety bound" }
            ntpOffsetMillis = drift
            lastNtpSuccessMillis = System.currentTimeMillis()
            prefs.edit().putLong("ntp_offset_ms", drift).putLong("ntp_last_success_ms", lastNtpSuccessMillis).apply()
            publishTime(if (absolute <= 1_000) "NTP synchronized · $host · drift ${absolute} ms · automatic hourly sync active" else
                "NTP synchronized · app clock corrected ${drift} ms · automatic hourly sync active; enable Android automatic time for the system clock")
        } catch (error: Exception) { publishTime("NTP check failed: ${error.message}") }
    }

    fun syncQueue() = syncTwoWay()
    fun fullSync() = scope.launch { syncMutex.withLock { syncQueueInternal(); pullRemote(reset = true) } }
    fun syncTwoWay() { scope.launch { runTwoWay() } }

    private suspend fun runTwoWay() = syncMutex.withLock {
        if (logMode != LogMode.WAVELOG || apiGeneration != WavelogApiGeneration.LEGACY) return@withLock
        syncQueueInternal()
        if (configured) pullRemote(reset = false)
    }

    private suspend fun syncQueueInternal() {
        if (!configured) { publish("Wavelog not configured; local QSOs remain safely queued"); return }
        val queue = synchronized(queueLock) { loadQueue() } ?: run {
            publish("Legacy Wavelog queue is unreadable and was preserved for recovery"); return
        }
        val uploaded = mutableSetOf<String>()
        for (index in 0 until queue.length()) {
            val item = queue.getJSONObject(index)
            if (item.optString("state", "pending") != "pending") continue
            val markedSending = synchronized(queueLock) {
                val current = loadQueue() ?: return@synchronized false
                val currentItem = (0 until current.length()).map { current.getJSONObject(it) }
                    .firstOrNull { it.optString("id") == item.optString("id") }
                    ?: return@synchronized false
                currentItem.put("state", "sending").put("attempts", item.optInt("attempts") + 1)
                writeJson(queueFile, current)
                true
            }
            if (!markedSending) { publish("Legacy Wavelog queue changed unexpectedly; sync stopped safely"); return }
            try {
                val payload = JSONObject().put("key", apiKey).put("station_profile_id", stationId.toLong())
                    .put("type", "adif").put("string", item.getString("adif"))
                request("qso", "POST", payload); database.markSynced(item.getString("id")); uploaded += item.getString("id")
            } catch (error: Exception) {
                synchronized(queueLock) {
                    val current = loadQueue() ?: return@synchronized
                    (0 until current.length()).map { current.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == item.optString("id") }
                        ?.put("state", "inspect")?.put("last_error", (error.message ?: error.javaClass.simpleName).take(160))
                    writeJson(queueFile, current)
                }
            }
        }
        val remaining = synchronized(queueLock) {
            val current = loadQueue() ?: return@synchronized null
            val kept = JSONArray()
            for (index in 0 until current.length()) current.getJSONObject(index).let { if (it.optString("id") !in uploaded) kept.put(it) }
            writeJson(queueFile, kept); kept
        } ?: run { publish("Legacy Wavelog queue became unreadable; sync stopped without overwriting it"); return }
        withContext(Dispatchers.Main) {
            pendingCount = remaining.length()
            status = if (pendingCount == 0) "Wavelog uploads synchronized · checking remote changes" else "$pendingCount QSOs remain safely queued"
        }
    }

    private suspend fun pullRemote(reset: Boolean) {
        val station = stationId.toLongOrNull() ?: return
        var cursor = if (reset) 0L else prefs.getLong("cursor_$stationId", 0L)
        var pages = 0; var added = 0
        try {
            for (page in 0 until 256) {
                val payload = JSONObject().put("key", apiKey).put("station_id", station)
                    .put("fetchfromid", cursor).put("limit", WAVELOG_SYNC_PAGE_SIZE)
                    .put("output_format", "json").put("fields", JSONArray(contactFields))
                val root = JSONObject(request("get_contacts_adif", "POST", payload))
                val exported = integer(root, setOf("exported_qsos", "exported_records", "exportedRecords"))
                    ?: error("Missing exported QSO count")
                val next = integer(root, setOf("lastfetchedid", "lastFetchedId", "last_fetched_id"))
                    ?: error("Missing pagination cursor")
                if (exported == 0L) break
                if (next <= cursor) error("Invalid Wavelog cursor")
                val rows = contactRows(root)
                database.transaction {
                    rows.forEach { row ->
                        fun field(name: String): String {
                            val key = row.keys().asSequence().firstOrNull { it.equals(name, true) } ?: return ""
                            return row.optString(key).takeUnless { it.equals("null", true) } ?: ""
                        }
                        val remoteId = field("COL_PRIMARY_KEY").ifBlank {
                            listOf(field("CALL"), field("QSO_DATE"), field("TIME_ON"), field("BAND"), field("MODE")).joinToString("-")
                        }
                        database.qsoFromFields(::field, remoteId, stationId)?.let { remote ->
                            val stationRow = selectedStation
                            val complete = remote.copy(stationLocation = stationRow?.name.orEmpty(), myCountry = remote.myCountry.ifBlank { stationRow?.country.orEmpty() },
                                myDxcc = remote.myDxcc.ifBlank { stationRow?.dxcc.orEmpty() }, myCqZone = remote.myCqZone.ifBlank { stationRow?.cqZone.orEmpty() },
                                myItuZone = remote.myItuZone.ifBlank { stationRow?.ituZone.orEmpty() }, myState = remote.myState.ifBlank { stationRow?.state.orEmpty() })
                            if (database.mergeRemote(complete)) added++
                        }
                    }
                }
                cursor = next; pages = page + 1
                prefs.edit().putLong("cursor_$stationId", cursor).apply()
                publish("Two-way sync page $pages · $added new local QSOs")
            }
            withContext(Dispatchers.Main) {
                syncPages = pages
                status = "Two-way sync complete · $added downloaded · ${pendingCount} queued"
            }
        } catch (error: Exception) { publish("Two-way sync paused: ${error.message}; local data is safe") }
    }

    fun close() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
    }

    private fun request(resource: String, method: String, payload: JSONObject?): String {
        val connection = URL(apiRoot() + "/" + resource).openConnection() as HttpURLConnection
        connection.requestMethod = method; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        if (payload != null) {
            connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code")
        return text
    }

    private fun apiRoot(): String {
        var value = baseURL.trim().replace(Regex("/+$"), "")
        if (!value.contains("://")) value = "https://$value"
        if (value.startsWith("http://")) value = "https://" + value.removePrefix("http://")
        return when {
            value.endsWith("/index.php/api") || value.endsWith("/api") -> value
            value.endsWith("/index.php") -> "$value/api"
            else -> "$value/index.php/api"
        }
    }

    private suspend fun publish(value: String) = withContext(Dispatchers.Main) { status = value }
    private suspend fun publishTime(value: String) = withContext(Dispatchers.Main) { timeStatus = value }
    private fun loadQueue(): JSONArray? {
        if (!queueFile.exists()) return JSONArray()
        return runCatching {
            AtomicFile(queueFile).openRead().bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
        }.getOrNull()
    }
    private fun writeJson(file: File, value: Any) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8)); stream.flush(); atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream); throw error
        }
    }
    private fun contactRows(node: Any): List<JSONObject> {
        val output = mutableListOf<JSONObject>()
        fun visit(value: Any?) { when (value) {
            is JSONArray -> {
                val objects = (0 until value.length()).mapNotNull { value.opt(it) as? JSONObject }
                if (objects.any { row -> row.keys().asSequence().any { it.equals("CALL", true) } }) output += objects
                else (0 until value.length()).forEach { visit(value.opt(it)) }
            }
            is JSONObject -> value.keys().forEach { visit(value.opt(it)) }
        } }
        visit(node); return output
    }
    private fun integer(node: Any?, keys: Set<String>): Long? { when (node) {
        is JSONObject -> { node.keys().forEach { key ->
            if (key in keys) return node.optString(key).toLongOrNull()
            integer(node.opt(key), keys)?.let { return it }
        } }
        is JSONArray -> for (index in 0 until node.length()) integer(node.opt(index), keys)?.let { return it }
    }; return null }
    private fun truth(value: Any?) = when (value) {
        is Boolean -> value; is Number -> value.toInt() != 0; else -> value.toString().lowercase() in listOf("1", "true")
    }
    private fun encodePath(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    private fun encrypt(value: String): String = if (value.isEmpty()) "" else runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret())
        Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault("")
    private fun decrypt(value: String): String = if (value.isEmpty()) "" else runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP); val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }.getOrDefault("")

    companion object {
        private const val keyAlias = "app.shackcq.mobile.wavelog"
        private val contactFields = listOf("CALL", "NAME", "BAND", "MODE", "SUBMODE",
            "DXCC", "COUNTRY", "CONT", "CQZ", "ITUZ", "STATE", "CNTY", "DARC_DOK", "EMAIL",
            "QSO_DATE", "TIME_ON", "QSO_DATE_OFF", "TIME_OFF", "FREQ", "FREQ_RX", "DISTANCE", "CONTEST_ID",
            "BAND_RX", "RST_SENT", "RST_RCVD", "TX_PWR", "GRIDSQUARE", "QTH", "IOTA", "SOTA_REF", "WWFF_REF", "POTA_REF",
            "STATION_CALLSIGN", "OPERATOR", "MY_GRIDSQUARE", "MY_COUNTRY", "MY_DXCC", "MY_CQ_ZONE", "MY_ITU_ZONE", "MY_STATE",
            "MY_IOTA", "MY_SOTA_REF", "MY_WWFF_REF", "MY_POTA_REF", "RIG", "PROP_MODE", "ANT_PATH", "COMMENT", "NOTES",
            "QSL_SENT", "QSL_RCVD", "QSL_SENT_VIA", "QSL_RCVD_VIA", "QSL_VIA", "QSLMSG",
            "LOTW_QSL_SENT", "LOTW_QSL_RCVD", "EQSL_QSL_SENT", "EQSL_QSL_RCVD",
            "CLUBLOG_QSO_UPLOAD_STATUS", "QRZCOM_QSO_UPLOAD_STATUS")
    }
}
