package app.shackcq.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.time.YearMonth
import app.shackcq.mobile.hamclock.HamClockRbnObservation
import app.shackcq.mobile.hamclock.HamClockRbnPreference
import app.shackcq.mobile.hamclock.HamClockRbnSourceSnapshot
import app.shackcq.mobile.hamclock.HamClockRbnSourceState
import app.shackcq.mobile.hamclock.boundedRbnObservations
import app.shackcq.mobile.hamclock.parseRbnClusterLine

data class AndroidDXSpot(
    val id: String, val callsign: String, val spotter: String, val frequencyHz: Long,
    val receivedEpoch: Long, val band: String, val mode: String, val country: String, val continent: String,
    val cqZone: Int, val ituZone: Int, val latitude: Double, val longitude: Double, val comment: String,
    val score: Int, val confidence: Int, val samples: Int, val watchlisted: Boolean,
    val workedCountry: Boolean, val workedCall: Boolean, val workedBand: Boolean,
    val workedMode: Boolean, val workedBandMode: Boolean, val recentDupe: Boolean,
    val distanceKm: Int, val bearingDegrees: Int, val pathState: String, val reason: String,
    val workedIndexComplete: Boolean = false,
    val dxcc: String = "",
)

data class AndroidWorkedLog(
    val loaded: Boolean = false, val complete: Boolean = false, val cells: Int = 0,
    val records: Int = 0, val accepted: Int = 0, val rejected: Int = 0, val truncated: Int = 0,
)

internal fun decodeWorkedLog(root: JSONObject): AndroidWorkedLog {
    val row = root.optJSONObject("workedLog") ?: return AndroidWorkedLog()
    val loaded = row.optBoolean("loaded", false)
    return AndroidWorkedLog(
        loaded = loaded, complete = loaded && row.optBoolean("complete", false),
        cells = row.optInt("cells"), records = row.optInt("records"), accepted = row.optInt("accepted"),
        rejected = row.optInt("rejected"), truncated = row.optInt("truncated"),
    )
}

internal fun decodeDxSpot(row: JSONObject): AndroidDXSpot {
    val frequency = row.optLong("frequencyHz")
    val workedIndexComplete = row.optBoolean("workedIndexComplete", false)
    val reason = row.optString("reason").let {
        if (!workedIndexComplete && it == "NEW ENTITY IN LOGBOOK") "FRESH CLUSTER ACTIVITY" else it
    }
    return AndroidDXSpot("${row.optString("callsign")}-$frequency-${row.optLong("receivedEpoch")}",
        row.optString("callsign"), row.optString("spotter"), frequency, row.optLong("receivedEpoch"),
        row.optString("band"), row.optString("mode"), row.optString("country"), row.optString("continent"),
        row.optInt("cqZone"), row.optInt("ituZone"), row.optDouble("latitude"), row.optDouble("longitude"),
        row.optString("comment"), row.optInt("score"), row.optInt("confidence"), row.optInt("samples"),
        row.optBoolean("watchlisted"), row.optBoolean("workedCountry"), row.optBoolean("workedCall"),
        row.optBoolean("workedBand"), row.optBoolean("workedMode"), row.optBoolean("workedBandMode"),
        row.optBoolean("recentDupe"), row.optInt("distanceKm"), row.optInt("bearingDegrees"),
        row.optString("pathState"), reason, workedIndexComplete)
}

data class AndroidSolar(val valid: Boolean = false, val flux: Float = 0f, val aIndex: Float = 0f,
    val kpIndex: Float = 0f, val observedEpoch: Long = 0L)

data class AndroidDXBand(val band: String, val spots5m: Int, val spots60m: Int, val uniqueCalls: Int,
    val surgePercent: Int, val surge: Boolean)
data class AndroidDXRegion(val region: String, val spots15m: Int, val spots60m: Int,
    val uniqueCalls: Int, val activityPercent: Int, val anomaly: Boolean)

enum class ClusterConnectionState { DISABLED, DISCONNECTED, CONNECTING, CONNECTED, RETRYING, ERROR }
data class ClusterConnectionTruth(
    val state: ClusterConnectionState = ClusterConnectionState.DISCONNECTED,
    val activeEndpoint: String = "",
    val connectedSinceEpoch: Long = 0,
    val stateChangedEpoch: Long = Instant.now().epochSecond,
    val latestSpotEpoch: Long = 0,
    val error: String = "",
)

data class ClusterDiagnostics(
    val receivedLines: Long = 0,
    val parsedSpotLines: Long = 0,
    val rejectedLines: Long = 0,
    val rejectedByReason: Map<String, Long> = emptyMap(),
    val acceptedSpots: Long = 0,
    val lastLineEpoch: Long = 0,
    val historyPending: Boolean = false,
    val historyCount: Int = 50,
    val historyRequestedEpoch: Long = 0,
    val historyAcceptedLines: Int = 0,
    val historyStatus: String = "No history request",
)

internal fun shouldRunRbnMaintenance(
    foreground: Boolean,
    rbnEnabled: Boolean,
    clusterState: ClusterConnectionState,
): Boolean = foreground && rbnEnabled && clusterState != ClusterConnectionState.DISABLED

internal data class FeatureHttpResponse(val status: Int, val body: ByteArray, val contentType: String,
    val effectiveUrl: String)
internal fun interface FeatureHttpTransport { fun get(url: String, maximumBytes: Int): FeatureHttpResponse }
internal class FeatureUrlConnectionTransport : FeatureHttpTransport {
    override fun get(url: String, maximumBytes: Int): FeatureHttpResponse {
        var target = URL(url)
        repeat(4) { redirects ->
            require(target.protocol.equals("https", true)) { "NOAA transport requires HTTPS" }
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "ShackCQ-Android/0.1 NOAA")
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirects < 3) { "NOAA redirect limit exceeded" }
                    target = URL(target, connection.getHeaderField("Location") ?: error("NOAA redirect omitted Location"))
                    require(target.protocol.equals("https", true)) { "NOAA redirected outside HTTPS" }
                    return@repeat
                }
                if (connection.contentLengthLong > maximumBytes) error("NOAA response is too large")
                val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBoundedBytes(maximumBytes + 1) } ?: ByteArray(0)
                if (bytes.size > maximumBytes) error("NOAA response is too large")
                return FeatureHttpResponse(status, bytes, connection.contentType.orEmpty(), target.toString())
            } finally { connection.disconnect() }
        }
        error("NOAA redirect limit exceeded")
    }
}

internal fun boundedFeatureText(transport: FeatureHttpTransport, url: String, maximumBytes: Int): String {
    require(url.startsWith("https://")) { "NOAA transport requires HTTPS" }
    val response = transport.get(url, maximumBytes)
    require(response.effectiveUrl.startsWith("https://")) { "NOAA redirected outside HTTPS" }
    require(response.status in 200..299) { "NOAA returned HTTP ${response.status}" }
    require(response.contentType.isBlank() || response.contentType.contains("json", true)) { "NOAA returned unexpected content type" }
    require(response.body.size <= maximumBytes) { "NOAA response is too large" }
    return response.body.toString(Charsets.UTF_8)
}

internal fun parseNoaaSummaryValue(text: String, keys: List<String>): Float? {
    val parsed = JSONTokener(text).nextValue()
    val rows = when(parsed) { is JSONObject -> listOf(parsed); is JSONArray -> (0 until parsed.length()).mapNotNull(parsed::optJSONObject); else -> emptyList() }
    rows.asReversed().forEach { root ->
        val names = root.keys().asSequence().toList()
        keys.forEach { key -> names.firstOrNull { it.equals(key, true) }?.let { actual -> root.optString(actual).toFloatOrNull()?.let { return it } } }
    }
    return null
}

internal fun parseLatestNoaaSunspot(
    body: String,
    currentMonth: YearMonth = YearMonth.now(java.time.Clock.systemUTC()),
): Pair<Float, String> {
    val rows = JSONArray(body)
    val latest = (0 until rows.length()).asSequence().mapNotNull(rows::optJSONObject).mapNotNull { row ->
        val month = runCatching { YearMonth.parse(row.optString("time-tag")) }.getOrNull() ?: return@mapNotNull null
        val value = row.optDouble("observed_swpc_ssn", Double.NaN)
        if (!value.isFinite() || value !in 0.0..1000.0 || month.isAfter(currentMonth)) null
        else Triple(month, value.toFloat(), row.getString("time-tag"))
    }.maxByOrNull { it.first } ?: error("NOAA returned no usable observed sunspot number")
    return latest.second to latest.third
}

internal suspend fun completeSolarRefresh(
    publishCore: suspend () -> Unit,
    refreshOptionalSunspot: suspend () -> Unit,
    reportSunspotFailure: (String) -> Unit,
) {
    publishCore()
    runCatching { refreshOptionalSunspot() }.onFailure { error ->
        reportSunspotFailure(error.message.orEmpty()
            .replace(Regex("https?://\\S+|(?i)(token|key|password)=[^\\s]+"), "[redacted]").take(120))
    }
}

class FeatureController internal constructor(private val context: Context, private val http: FeatureHttpTransport = FeatureUrlConnectionTransport()) {
    private val nativeSession = FeatureNativeSession()
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private var workedSyncJob: Job? = null
    private var workedFingerprint: WorkedFingerprint? = null
    private var clusterSocket: Socket? = null
    private var clusterGeneration = 0
    private var historyResponseJob: Job? = null
    private val prefs = context.getSharedPreferences("dx_cluster", Context.MODE_PRIVATE)
    private val rbnBuffer = ArrayDeque<HamClockRbnObservation>()
    private var rbnPreference = HamClockRbnPreference(enabled = false)
    private var rbnPublishJob: Job? = null
    private var rbnMaintenanceJob: Job? = null
    private var rbnMaintenanceGeneration = 0
    private var rbnStationCall = ""
    private var foreground = true
    @Volatile private var closed = false
    private var lastRbnObservedEpoch = 0L
    @Volatile private var rbnDirty = false

    var clusterHost by mutableStateOf(prefs.getString("host", ShackCQDefaults.CLUSTER_HOST) ?: ShackCQDefaults.CLUSTER_HOST)
    var clusterPort by mutableStateOf(prefs.getInt("port", ShackCQDefaults.CLUSTER_PORT))
    var fallbackHost by mutableStateOf(prefs.getString("fallback_host", "") ?: "")
    var fallbackPort by mutableStateOf(prefs.getInt("fallback_port", 7300))
    var fallback2Host by mutableStateOf(prefs.getString("fallback2_host", "") ?: "")
    var fallback2Port by mutableStateOf(prefs.getInt("fallback2_port", 7300))
    var clusterCallsign by mutableStateOf(prefs.getString("callsign", ShackCQDefaults.CLUSTER_LOGIN) ?: ShackCQDefaults.CLUSTER_LOGIN)
    var watchlistText by mutableStateOf(prefs.getString("watchlist", "") ?: ""); private set

    var clusterStatus by mutableStateOf("DX cluster disconnected"); private set
    var clusterConnection by mutableStateOf(ClusterConnectionTruth()); private set
    var clusterDiagnostics by mutableStateOf(ClusterDiagnostics()); private set
    var spots by mutableStateOf(emptyList<AndroidDXSpot>()); private set
    var liveSpots by mutableStateOf(emptyList<AndroidDXSpot>()); private set
    var watchSpots by mutableStateOf(emptyList<AndroidDXSpot>()); private set
    var dxBands by mutableStateOf(emptyList<AndroidDXBand>()); private set
    var dxRegions by mutableStateOf(emptyList<AndroidDXRegion>()); private set
    var dxTimeline by mutableStateOf(emptyList<List<Int>>()); private set
    var dxWorld by mutableStateOf(emptyList<List<Int>>()); private set
    var dxSummary by mutableStateOf("No live DX data"); private set
    var solar by mutableStateOf(AndroidSolar()); private set
    var sunspotNumber by mutableStateOf(prefs.getFloat("noaa_sunspot_number", Float.NaN).takeIf(Float::isFinite)); private set
    var sunspotObservedMonth by mutableStateOf(prefs.getString("noaa_sunspot_month", "").orEmpty()); private set
    var sunspotError by mutableStateOf(""); private set
    var solarError by mutableStateOf(""); private set
    var learnedSpots by mutableStateOf(0); private set
    var duplicateSpots by mutableStateOf(0); private set
    var newestSpotEpoch by mutableStateOf(0L); private set
    var workedLog by mutableStateOf(AndroidWorkedLog()); private set

    private data class WorkedFingerprint(
        val changeToken: Long, val authority: LogMode, val stationId: String, val ctyRevision: Long,
    )
    var requestedSpotId by mutableStateOf<String?>(null); private set
    var requestedSpotRequiresReceiveReview by mutableStateOf(false); private set
    internal var rbnObservations by mutableStateOf(emptyList<HamClockRbnObservation>()); private set
    var latestRbnEpoch by mutableStateOf(0L); private set
    internal var rbnSourceSnapshot by mutableStateOf(HamClockRbnSourceSnapshot()); private set

    init {
        val defaults = prefs.edit()
        if (!prefs.contains("host")) defaults.putString("host", clusterHost)
        if (!prefs.contains("port")) defaults.putInt("port", clusterPort)
        if (!prefs.contains("callsign")) defaults.putString("callsign", clusterCallsign)
        defaults.apply()
        nativeSession.setWatchlist(watchlistText)
        scheduleRbnPublish()
    }

    fun setWatchlist(value: String) {
        watchlistText = value.lineSequence().flatMap { it.split(',', ' ', ';').asSequence() }
            .map(String::trim).filter(String::isNotBlank).map(String::uppercase).distinct().take(32).joinToString("\n")
        prefs.edit().putString("watchlist", watchlistText).apply()
        nativeSession.setWatchlist(watchlistText)
        scheduleRbnPublish(immediate = true)
    }

    fun requestSpot(id: String, requireReceiveReview: Boolean = false) {
        requestedSpotId = id
        requestedSpotRequiresReceiveReview = requireReceiveReview
    }
    fun consumeRequestedSpot() { requestedSpotId = null; requestedSpotRequiresReceiveReview = false }

    fun applyRbnPreference(value: HamClockRbnPreference, currentStationCall: String = "") {
        rbnPreference = value
        rbnStationCall = currentStationCall.trim().uppercase()
        if (!value.enabled) {
            synchronized(rbnBuffer) { rbnBuffer.clear() }
            rbnObservations = emptyList()
        }
        scheduleRbnPublish(immediate = true)
        updateRbnMaintenance(runImmediate = value.enabled)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        updateRbnMaintenance(runImmediate = value)
    }

    fun connectConfiguredCluster() {
        if (clusterHost.isNotBlank() && clusterPort in 1..65535 && clusterCallsign.isNotBlank()) {
            connectCluster(clusterHost, clusterPort, clusterCallsign,
                fallbackHost, fallbackPort, fallback2Host, fallback2Port)
        }
    }

    fun saveClusterConfiguration(host: String, port: Int, callsign: String,
        fallbackHost: String = "", fallbackPort: Int = ShackCQDefaults.CLUSTER_PORT,
        fallback2Host: String = "", fallback2Port: Int = ShackCQDefaults.CLUSTER_PORT) {
        clusterHost = host.trim().lowercase(); clusterPort = port.coerceIn(1, 65535)
        clusterCallsign = callsign.trim().uppercase()
        this.fallbackHost = fallbackHost.trim().lowercase(); this.fallbackPort = fallbackPort.coerceIn(1, 65535)
        this.fallback2Host = fallback2Host.trim().lowercase(); this.fallback2Port = fallback2Port.coerceIn(1, 65535)
        prefs.edit().putString("host", clusterHost).putInt("port", clusterPort).putString("callsign", clusterCallsign)
            .putString("fallback_host", this.fallbackHost).putInt("fallback_port", this.fallbackPort)
            .putString("fallback2_host", this.fallback2Host).putInt("fallback2_port", this.fallback2Port).apply()
    }

    fun connectCluster(host: String, port: Int, callsign: String,
        fallbackHost: String = "", fallbackPort: Int = 7300,
        fallback2Host: String = "", fallback2Port: Int = 7300) {
        if (clusterConnection.state in setOf(ClusterConnectionState.CONNECTING, ClusterConnectionState.RETRYING,
                ClusterConnectionState.CONNECTED)) return
        val normalizedCallsign = callsign.trim().uppercase()
        if (!normalizedCallsign.matches(Regex("[A-Z0-9/]{3,20}"))) {
            scope.launch { publishCluster("Enter a valid operator callsign", ClusterConnectionState.DISCONNECTED) }
            return
        }
        saveClusterConfiguration(host, port, callsign, fallbackHost, fallbackPort, fallback2Host, fallback2Port)
        disconnectCluster(); val generation = ++clusterGeneration
        val endpoints = listOf(clusterHost to clusterPort, this.fallbackHost to this.fallbackPort,
            this.fallback2Host to this.fallback2Port).filter { it.first.isNotBlank() && it.second in 1..65535 }.distinct()
        scope.launch {
            var endpointIndex = 0
            var reconnectAttempt = 0
            while (generation == clusterGeneration && endpoints.isNotEmpty()) {
                val (endpointHost, endpointPort) = endpoints[endpointIndex]
                publishCluster("Connecting to $endpointHost:$endpointPort…", ClusterConnectionState.CONNECTING,
                    "$endpointHost:$endpointPort")
                try {
                    val socket = Socket(); socket.connect(InetSocketAddress(endpointHost, endpointPort), 12_000); clusterSocket = socket
                    val output = socket.getOutputStream()
                    if (clusterCallsign.isNotBlank()) {
                        output.write((clusterCallsign + "\r\n").toByteArray()); output.flush()
                    }
                    reconnectAttempt = 0
                    publishCluster("Connected to $endpointHost:$endpointPort", ClusterConnectionState.CONNECTED,
                        "$endpointHost:$endpointPort", connectedSince = Instant.now().epochSecond)
                    BufferedReader(InputStreamReader(socket.getInputStream())).useLines { lines ->
                        lines.forEach { line ->
                            if (generation != clusterGeneration) return@useLines
                            val lineEpoch = Instant.now().epochSecond
                            val rbn = parseRbnClusterLine(line)
                            rbn?.let {
                                lastRbnObservedEpoch = maxOf(lastRbnObservedEpoch, it.observedEpoch)
                                synchronized(rbnBuffer) {
                                    rbnBuffer.addLast(it)
                                    while (rbnBuffer.size > 1_000) rbnBuffer.removeFirst()
                                }
                                scheduleRbnPublish()
                            }
                            // An RBN line is already represented by the typed RBN observation path.
                            // Do not also ingest it as a generic DX-cluster spot.
                            val nativeGeneration = nativeSession.generation()
                            val accepted = rbn == null && nativeSession.ingestClusterLine(line, lineEpoch)
                            withContext(Dispatchers.Main) {
                                if (!nativeSession.isCurrent(nativeGeneration)) return@withContext
                                val previous = clusterDiagnostics
                                clusterDiagnostics = previous.copy(
                                    receivedLines = previous.receivedLines + 1,
                                    parsedSpotLines = previous.parsedSpotLines + if (accepted || rbn != null) 1 else 0,
                                    rejectedLines = previous.rejectedLines + if (!accepted && rbn == null) 1 else 0,
                                    rejectedByReason = if (!accepted && rbn == null) previous.rejectedByReason +
                                        ("unrecognised" to (previous.rejectedByReason["unrecognised"] ?: 0L) + 1) else previous.rejectedByReason,
                                    acceptedSpots = previous.acceptedSpots + if (accepted) 1 else 0,
                                    lastLineEpoch = lineEpoch,
                                    historyAcceptedLines = previous.historyAcceptedLines + if (previous.historyPending && accepted) 1 else 0,
                                )
                            }
                            if (accepted) {
                                refreshDX()
                                if (clusterDiagnostics.historyPending) completeHistoryAfterIdle()
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (generation == clusterGeneration) publishCluster("$endpointHost failed · trying next cluster",
                        ClusterConnectionState.ERROR, "$endpointHost:$endpointPort", safeFeatureError(error))
                } finally { clusterSocket?.close(); clusterSocket = null }
                if (generation != clusterGeneration) return@launch
                endpointIndex = (endpointIndex + 1) % endpoints.size
                val next = endpoints[endpointIndex]
                val waitSeconds = minOf(30, 1 shl minOf(reconnectAttempt, 4))
                reconnectAttempt++
                publishCluster("Trying ${next.first}:${next.second} in ${waitSeconds}s…",
                    ClusterConnectionState.RETRYING, "${next.first}:${next.second}")
                delay(waitSeconds * 1_000L)
            }
        }
    }

    fun disconnectCluster(disabled: Boolean = false) {
        clusterGeneration++; historyResponseJob?.cancel(); clusterSocket?.close(); clusterSocket = null
        clusterStatus = if (disabled) "DX cluster disabled" else "DX cluster disconnected"
        clusterConnection = ClusterConnectionTruth(if (disabled) ClusterConnectionState.DISABLED else ClusterConnectionState.DISCONNECTED,
            stateChangedEpoch = Instant.now().epochSecond, latestSpotEpoch = newestSpotEpoch)
        clusterDiagnostics = clusterDiagnostics.copy(historyPending = false,
            historyStatus = if (disabled) "Cluster disabled" else "No history request")
        scheduleRbnPublish(immediate = true)
        updateRbnMaintenance()
    }

    fun requestClusterHistory(count: Int) {
        val bounded = count.coerceIn(1, 500)
        if (clusterConnection.state != ClusterConnectionState.CONNECTED || clusterDiagnostics.historyPending) return
        scope.launch {
            val socket = clusterSocket
            if (socket == null || socket.isClosed) return@launch
            runCatching {
                socket.getOutputStream().apply { write("SH/DX $bounded\r\n".toByteArray()); flush() }
                withContext(Dispatchers.Main) {
                    clusterDiagnostics = clusterDiagnostics.copy(historyPending = true, historyCount = bounded,
                        historyRequestedEpoch = Instant.now().epochSecond, historyAcceptedLines = 0,
                        historyStatus = "Waiting for SH/DX $bounded response…")
                }
                historyResponseJob?.cancel()
                historyResponseJob = scope.launch {
                    delay(12_000)
                    withContext(Dispatchers.Main) {
                        if (clusterDiagnostics.historyPending) clusterDiagnostics = clusterDiagnostics.copy(
                            historyPending = false, historyStatus = "SH/DX response timed out")
                    }
                }
            }.onFailure { error -> withContext(Dispatchers.Main) {
                clusterDiagnostics = clusterDiagnostics.copy(historyPending = false,
                    historyStatus = "SH/DX failed · ${safeFeatureError(error)}")
            } }
        }
    }

    private fun completeHistoryAfterIdle() {
        historyResponseJob?.cancel()
        historyResponseJob = scope.launch {
            delay(1_500)
            withContext(Dispatchers.Main) {
                if (clusterDiagnostics.historyPending) clusterDiagnostics = clusterDiagnostics.copy(
                    historyPending = false,
                    historyStatus = "SH/DX complete · ${clusterDiagnostics.historyAcceptedLines} accepted")
            }
        }
    }

    fun postSpot(callsign: String, frequencyKHz: Double, comment: String) {
        val call = callsign.trim().uppercase()
        if (call.isBlank() || frequencyKHz !in 100.0..10_500_000.0) return
        val safeComment = comment.replace(Regex("[\\r\\n;]"), " ").trim().take(80)
        scope.launch {
            val socket = clusterSocket
            if (socket == null || socket.isClosed) {
                publishCluster("Cannot send spot · cluster is not connected")
            } else runCatching {
                val line = "DX %.1f %s %s\r\n".format(java.util.Locale.US, frequencyKHz, call, safeComment)
                socket.getOutputStream().apply { write(line.toByteArray()); flush() }
                publishCluster("Spot sent · $call")
            }.onFailure { publishCluster("Spot send failed · connection retained for retry") }
        }
    }

    fun refreshSolar() {
        scope.launch {
            try {
                val flux = summaryValue("https://services.swpc.noaa.gov/products/summary/10cm-flux.json", listOf("Flux", "flux"))
                val geomagnetic = summaryText("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
                val kp = parseNoaaSummaryValue(geomagnetic, listOf("Kp", "kp_index", "KpIndex")) ?: error("Unexpected NOAA Kp response")
                val a = parseNoaaSummaryValue(geomagnetic, listOf("a_running", "A", "a_index")) ?: error("Unexpected NOAA A response")
                completeSolarRefresh(
                    publishCore = {
                        if (!nativeSession.setSolar(flux, a, kp, Instant.now().epochSecond)) return@completeSolarRefresh
                        refreshDX()
                    },
                    refreshOptionalSunspot = { refreshSunspotNumber() },
                    reportSunspotFailure = { sunspotError = it.ifBlank { "NOAA SSN unavailable; retained last monthly value" } },
                )
                solarError = ""
            } catch (error: Exception) {
                solarError = safeFeatureError(error).ifBlank { "NOAA solar data unavailable · retained last values" }
            }
        }
    }

    private fun refreshSunspotNumber() {
        val body = summaryText("https://services.swpc.noaa.gov/json/solar-cycle/observed-solar-cycle-indices.json")
        val latest = parseLatestNoaaSunspot(body)
        sunspotNumber = latest.first
        sunspotObservedMonth = latest.second
        sunspotError = ""
        prefs.edit().putFloat("noaa_sunspot_number", sunspotNumber!!)
            .putString("noaa_sunspot_month", sunspotObservedMonth).apply()
    }

    fun close() {
        if (closed) return
        closed = true
        rbnMaintenanceGeneration++
        rbnMaintenanceJob?.cancel()
        rbnMaintenanceJob = null
        disconnectCluster()
        scope.cancel()
        nativeSession.close()
    }

    fun startWorkedLogSync(database: QsoDatabase, wavelog: WavelogController, cty: CtyController) {
        if (workedSyncJob?.isActive == true) return
        workedSyncJob = scope.launch {
            while (isActive) {
                val authority = wavelog.logMode
                val stationId = if (authority == LogMode.WAVELOG) wavelog.stationId else ""
                val fingerprint = WorkedFingerprint(database.changeToken(), authority, stationId, cty.dataRevision)
                if (fingerprint != workedFingerprint) {
                    try {
                        val ctyText = cty.nativeCtyText()
                        val hasSelectedAuthority = authority != LogMode.WAVELOG || stationId.isNotBlank()
                        val rows = if (hasSelectedAuthority) {
                            database.workedLog(stationId.takeIf { authority == LogMode.WAVELOG }, cty::country)
                        } else emptyList()
                        require(fingerprint.ctyRevision == 0L || ctyText != null)
                        if (!nativeSession.synchronizeWorkedLog(ctyText, rows, hasSelectedAuthority)) return@launch
                        if (!refreshDX()) return@launch
                        workedFingerprint = fingerprint
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Retry without accepting the fingerprint; an unselected Wavelog authority stays unloaded.
                    }
                }
                delay(2_000)
            }
        }
    }

    private suspend fun refreshDX(): Boolean {
        val nativeGeneration = nativeSession.generation()
        val json = nativeSession.snapshot(Instant.now().epochSecond) ?: return false
        val root = JSONObject(json)
        fun loadSpots(name: String) = buildList {
            val rows = root.optJSONArray(name)
            if (rows != null) for (index in 0 until rows.length()) {
                add(decodeDxSpot(rows.getJSONObject(index)))
            }
        }
        val loaded = loadSpots("opportunities")
        val live = loadSpots("liveSpots")
        val watched = loadSpots("watchActivity")
        val bands = buildList {
            root.optJSONArray("bands")?.let { rows -> for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                add(AndroidDXBand(row.optString("band"), row.optInt("spots5m"), row.optInt("spots60m"),
                    row.optInt("uniqueCalls"), row.optInt("surgePercent"), row.optBoolean("surge")))
            } }
        }
        val regions = buildList {
            root.optJSONArray("regions")?.let { rows -> for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                add(AndroidDXRegion(row.optString("region"), row.optInt("spots15m"), row.optInt("spots60m"),
                    row.optInt("uniqueCalls"), row.optInt("activityPercent"), row.optBoolean("anomaly")))
            } }
        }
        fun matrix(name: String) = buildList {
            root.optJSONArray(name)?.let { rows -> for (rowIndex in 0 until rows.length()) {
                val row = rows.getJSONArray(rowIndex)
                add(List(row.length()) { column -> row.optInt(column) })
            } }
        }
        val summary = "${root.optInt("spots5m")} / 5m · ${root.optInt("spots60m")} / 60m · ${root.optInt("watchlistHits")} watch"
        val solarRow = root.optJSONObject("solar")
        val parsedSolar = AndroidSolar(solarRow?.optBoolean("valid") == true, solarRow?.optDouble("flux")?.toFloat() ?: 0f,
            solarRow?.optDouble("aIndex")?.toFloat() ?: 0f, solarRow?.optDouble("kpIndex")?.toFloat() ?: 0f,
            Instant.now().epochSecond)
        withContext(Dispatchers.Main) {
            if (!nativeSession.isCurrent(nativeGeneration)) return@withContext
            spots = loaded; liveSpots = live; watchSpots = watched; dxBands = bands; dxRegions = regions
            dxTimeline = matrix("bandTimeline"); dxWorld = matrix("worldGrid"); dxSummary = summary; solar = parsedSolar
            learnedSpots = root.optInt("learnedSpots"); duplicateSpots = root.optInt("duplicateSpots")
            newestSpotEpoch = root.optLong("newestSpotEpoch")
            workedLog = decodeWorkedLog(root)
            clusterConnection = clusterConnection.copy(latestSpotEpoch = newestSpotEpoch)
        }
        return nativeSession.isCurrent(nativeGeneration)
    }

    @Synchronized private fun scheduleRbnPublish(immediate: Boolean = false) {
        rbnDirty = true
        if (rbnPublishJob?.isActive == true) return
        rbnPublishJob = scope.launch {
            try {
                var first = true
                do {
                    rbnDirty = false
                    if (!immediate || !first) delay(250)
                    first = false
                    publishRbn()
                } while (rbnDirty)
            } finally {
                synchronized(this@FeatureController) {
                    rbnPublishJob = null
                    if (rbnDirty) scheduleRbnPublish()
                }
            }
        }
    }

    @Synchronized private fun updateRbnMaintenance(runImmediate: Boolean = false) {
        val required = !closed && shouldRunRbnMaintenance(foreground, rbnPreference.enabled, clusterConnection.state)
        if (!required) {
            rbnMaintenanceGeneration++
            rbnMaintenanceJob?.cancel()
            rbnMaintenanceJob = null
            return
        }
        if (runImmediate) scheduleRbnPublish(immediate = true)
        if (rbnMaintenanceJob?.isActive == true) return
        val generation = ++rbnMaintenanceGeneration
        rbnMaintenanceJob = scope.launch {
            try {
                while (true) { delay(2_000); scheduleRbnPublish(immediate = true) }
            } finally {
                synchronized(this@FeatureController) {
                    if (rbnMaintenanceGeneration == generation) rbnMaintenanceJob = null
                }
            }
        }
    }

    private suspend fun publishRbn() {
        val now = Instant.now().epochSecond
        val watchlist = watchlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
        val cutoff = now - rbnPreference.windowMinutes * 60L
        val buffered = synchronized(rbnBuffer) {
            val retained = rbnBuffer.filter { it.observedEpoch >= cutoff }
            rbnBuffer.clear(); rbnBuffer.addAll(retained)
            rbnBuffer.toList()
        }
        val bounded = boundedRbnObservations(buffered, rbnPreference, watchlist, rbnStationCall, now)
        val sourceState = when {
            !rbnPreference.enabled || clusterConnection.state == ClusterConnectionState.DISABLED -> HamClockRbnSourceState.DISABLED
            clusterConnection.state == ClusterConnectionState.CONNECTING || clusterConnection.state == ClusterConnectionState.RETRYING -> HamClockRbnSourceState.CONNECTING
            clusterConnection.state == ClusterConnectionState.ERROR -> HamClockRbnSourceState.ERROR
            clusterConnection.state != ClusterConnectionState.CONNECTED -> HamClockRbnSourceState.DISCONNECTED
            lastRbnObservedEpoch > 0 && lastRbnObservedEpoch < cutoff -> HamClockRbnSourceState.STALE
            lastRbnObservedEpoch == 0L -> HamClockRbnSourceState.EMPTY
            else -> HamClockRbnSourceState.CURRENT
        }
        val sourceSnapshot = HamClockRbnSourceSnapshot(sourceState, lastRbnObservedEpoch, buffered.size,
            bounded.size, clusterConnection.activeEndpoint, clusterConnection.error
                .replace(Regex("https?://\\S+|(?i)(token|key|password)=[^\\s]+"), "[redacted]").take(160))
        withContext(Dispatchers.Main) {
            rbnObservations = bounded
            latestRbnEpoch = lastRbnObservedEpoch
            rbnSourceSnapshot = sourceSnapshot
        }
    }

    private fun summaryValue(url: String, keys: List<String>): Float {
        return parseNoaaSummaryValue(summaryText(url), keys)
            ?: error("Unexpected NOAA response")
    }

    private fun summaryText(url: String): String {
        val maximum = if (url.contains("observed-solar-cycle")) 1_500_000 else 256_000
        return boundedFeatureText(http, url, maximum)
    }

    private suspend fun publishCluster(value: String, state: ClusterConnectionState = clusterConnection.state,
        endpoint: String = clusterConnection.activeEndpoint, error: String = "", connectedSince: Long = clusterConnection.connectedSinceEpoch) {
        withContext(Dispatchers.Main) {
            clusterStatus = value
            clusterConnection = ClusterConnectionTruth(state, endpoint,
                if (state == ClusterConnectionState.CONNECTED) connectedSince else 0,
                Instant.now().epochSecond, newestSpotEpoch, error.take(160))
        }
        updateRbnMaintenance()
        scheduleRbnPublish(immediate = true)
    }
}

private fun safeFeatureError(error: Throwable): String = error.message.orEmpty()
    .replace(Regex("https?://\\S+|(?i)(token|key|password)=[^\\s]+"), "[redacted]").take(160)
