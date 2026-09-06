package app.shackcq.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.shackcq.mobile.hamclock.DxNewsItem
import app.shackcq.mobile.hamclock.DxNewsSource
import app.shackcq.mobile.hamclock.DxNewsSnapshot
import app.shackcq.mobile.hamclock.HamClockDxpedition
import app.shackcq.mobile.hamclock.HamClockFeed
import app.shackcq.mobile.hamclock.HamClockFeedState
import app.shackcq.mobile.hamclock.HamClockPskDirection
import app.shackcq.mobile.hamclock.HamClockPskPreference
import app.shackcq.mobile.hamclock.HamClockPublicProviders
import app.shackcq.mobile.hamclock.HamClockWsprPreference
import app.shackcq.mobile.hamclock.HamClockWsprSnapshot
import app.shackcq.mobile.hamclock.PskReporterSnapshot
import app.shackcq.mobile.hamclock.filterPskReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.*

internal fun shouldStartForegroundWork(foreground: Boolean, alreadyActive: Boolean = false): Boolean =
    foreground && !alreadyActive

enum class NeuralDxPage(val label: String) {
    COCKPIT("Cockpit"), MAP("Map"), INSIGHT("Insight & Outlook"), WORLD("World"),
    BRIEFING("Briefing"), OBSERVATIONS("RF Evidence"), SATELLITES("Satellites"), HISTORY("History"), WEATHER("Weather")
}

enum class NeuralDxRefreshScope { HOME, FULL_DX }

internal fun NeuralDxRefreshScope.includesLegacySatelliteWork(): Boolean = false
internal fun NeuralDxRefreshScope.stopsLegacySatelliteTicker(): Boolean = true

data class NeuralWeather(
    val available: Boolean = false, val updatedEpoch: Long = 0, val temperatureC: Double? = null,
    val pressureHpa: Double? = null, val humidityPercent: Int? = null, val windKmh: Double? = null,
    val windDirection: Int? = null, val precipitationMm: Double? = null, val weatherCode: Int? = null,
    val cape: Double? = null, val temperature850C: Double? = null, val wind300Kmh: Double? = null,
    val wind300Direction: Int? = null, val tropoIndex: Int? = null, val ductingRisk: String? = null,
    val pressureTrend: String = "—", val source: String = "Open-Meteo", val error: String = "",
    val status: NeuralProviderStatus = NeuralProviderStatus("Open-Meteo", NeuralProviderState.UNAVAILABLE),
)

data class WsprBandActivity(val band: String, val spots: Int, val averageSnr: Double?, val averageDistanceKm: Int?)
data class NeuralWspr(val available: Boolean = false, val updatedEpoch: Long = 0,
    val hf: List<WsprBandActivity> = emptyList(), val vhf: List<WsprBandActivity> = emptyList(), val error: String = "",
    val status: NeuralProviderStatus = NeuralProviderStatus("wspr.live", NeuralProviderState.UNAVAILABLE))

internal typealias BriefingItem = DxNewsItem
internal typealias BriefingSource = DxNewsSource

data class NeuralCurrentOpportunity(
    val callsign: String, val country: String, val band: String, val mode: String,
    val priority: Int, val evidenceScore: Int, val reason: String, val observedEpoch: Long, val samples: Int,
)

internal fun buildCurrentOpportunities(spots: List<AndroidDXSpot>): List<NeuralCurrentOpportunity> = spots.asSequence()
    .filter { it.score >= 45 }
    .sortedWith(compareByDescending<AndroidDXSpot> { it.score }.thenByDescending { it.receivedEpoch })
    .distinctBy { Triple(it.callsign, it.band, it.mode) }
    .take(12)
    .map { spot -> NeuralCurrentOpportunity(
        spot.callsign, spot.country, spot.band, spot.mode, spot.score, spot.confidence,
        spot.reason, spot.receivedEpoch, spot.samples,
    ) }
    .toList()
data class NeuralWorldCell(val row: Int, val column: Int, val latitude: Double, val longitude: Double,
    val observed: Int, val expected: Double?, val anomalyRatio: Double?, val confidence: String,
    val calls: List<String>, val greyline: Boolean, val baselineSamples: Int, val sourceCount: Int,
    val anomalyLabel: String)
data class NeuralInsight(val generatedEpoch: Long = 0, val title: String = "DX analysis is waiting for data",
    val report: String = "", val bullets: List<String> = emptyList(), val recommendations: List<String> = emptyList(),
    val log: NeuralLogSummary = NeuralLogSummary(), val source: String = "LOCAL", val error: String = "")

data class OrbitRecord(
    val norad: Int, val name: String, val epoch: Long, val inclination: Double, val raan: Double,
    val eccentricity: Double, val argumentPerigee: Double, val meanAnomaly: Double, val meanMotion: Double,
)

internal fun decodeHtmlText(value: String): String {
    var text = value.replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#39;", "'").replace("&nbsp;", " ").replace("&ndash;", "–")
        .replace("&mdash;", "—").replace("&lt;", "<").replace("&gt;", ">")
    text = Regex("&#(\\d+);").replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf(Character::isValidCodePoint)
            ?.let { String(Character.toChars(it)) } ?: match.value
    }
    text = Regex("&#x([0-9a-fA-F]+);").replace(text) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf(Character::isValidCodePoint)
            ?.let { String(Character.toChars(it)) } ?: match.value
    }
    return text.replace(Regex("\\s+"), " ").trim()
}

internal fun parseTleOrbitRecords(text: String): List<OrbitRecord> {
    val lines = text.lines().map(String::trimEnd)
    val records = mutableListOf<OrbitRecord>()
    for (index in 0 until lines.lastIndex) {
        val line1 = lines[index].trim()
        if (!line1.startsWith("1 ")) continue
        val line2Index = (index + 1 until minOf(lines.size, index + 4)).firstOrNull { lines[it].trim().startsWith("2 ") } ?: continue
        val line2 = lines[line2Index].trim()
        runCatching {
            require(line1.length >= 32 && line2.length >= 63)
            val norad = line1.substring(2, 7).trim().toInt()
            val twoDigitYear = line1.substring(18, 20).trim().toInt()
            val year = if (twoDigitYear >= 57) 1900 + twoDigitYear else 2000 + twoDigitYear
            val day = line1.substring(20, 32).trim().toDouble()
            val epoch = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + ((day - 1.0) * 86_400.0).roundToLong()
            val name = (index - 1 downTo maxOf(0, index - 5)).map { lines[it].trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("1 ") && !it.startsWith("2 ") && it.length <= 80 }
                ?.removePrefix("0 ")?.trim().orEmpty().ifBlank { "SAT-$norad" }
            records += OrbitRecord(norad, name, epoch, line2.substring(8, 16).trim().toDouble(),
                line2.substring(17, 25).trim().toDouble(), "0.${line2.substring(26, 33).trim()}".toDouble(),
                line2.substring(34, 42).trim().toDouble(), line2.substring(43, 51).trim().toDouble(),
                line2.substring(52, 63).trim().toDouble())
        }
    }
    return records.distinctBy(OrbitRecord::norad).sortedBy(OrbitRecord::name)
}
data class SatellitePosition(val norad: Int, val name: String, val latitude: Double, val longitude: Double,
    val altitudeKm: Double, val azimuth: Double, val elevation: Double, val rangeKm: Double,
    val footprintKm: Double, val visible: Boolean, val updatedEpoch: Long)
data class SatellitePass(val norad: Int, val name: String, val aosEpoch: Long, val tcaEpoch: Long,
    val losEpoch: Long, val maxElevation: Double, val aosAzimuth: Double, val losAzimuth: Double)
data class SatelliteTransmitter(val description: String, val uplink: String, val downlink: String,
    val mode: String, val status: String)
data class BeaconReception(val callsign: String, val band: String, val frequencyHz: Long, val country: String,
    val ageMinutes: Int, val known: Boolean, val spotter: String)
data class BeaconReference(val callsign: String, val band: String, val frequencyMHz: Double, val locator: String,
    val lastReport: String, val distanceKm: Int? = null, val bearing: String = "", val inTypicalRange: Boolean = false)
data class LightningStrike(val epoch: Long, val latitude: Double, val longitude: Double, val distanceKm: Double,
    val bearingDegrees: Int, val bearing: String)
data class NeuralLightning(val connected: Boolean = false, val updatedEpoch: Long = 0,
    val strikes: List<LightningStrike> = emptyList(), val source: String = "Blitzortung community MQTT", val error: String = "",
    val status: NeuralProviderStatus = NeuralProviderStatus("Blitzortung community MQTT", NeuralProviderState.UNAVAILABLE))
enum class SignalDirection { BEING_HEARD, HEARING }
data class SignalReport(val callsign:String,val locator:String,val latitude:Double?,val longitude:Double?,val frequencyHz:Long,
    val band:String,val mode:String,val snr:Int?,val distanceKm:Int?,val epoch:Long,
    val direction: SignalDirection = SignalDirection.BEING_HEARD, val localCallsign: String = "",
    val senderCallsign: String = localCallsign, val senderLocator: String = "",
    val receiverCallsign: String = callsign, val receiverLocator: String = locator, val mutual: Boolean = false,
    val continent: String = "")
internal fun signalReportReference(report: SignalReport): String =
    listOf(report.direction.name, report.callsign.uppercase(Locale.US), report.epoch.toString(), report.frequencyHz.toString(),
        report.locator.uppercase(Locale.US)).joinToString("|")
internal data class ConsumedSignalRequest(val report: SignalReport?, val message: String)
internal fun consumeSignalRequest(referenceId: String, reports: List<SignalReport>): ConsumedSignalRequest {
    val report = reports.firstOrNull { signalReportReference(it) == referenceId }
    return ConsumedSignalRequest(report, if (report == null) "PSK report expired or filtered; request consumed" else "")
}
internal enum class NeuralSignalSourceState { CURRENT, DEGRADED, ERROR, EMPTY }
internal fun neuralProviderState(state: HamClockFeedState): NeuralProviderState = when (state) {
    HamClockFeedState.LIVE -> NeuralProviderState.LIVE
    HamClockFeedState.CACHED -> NeuralProviderState.CACHED
    HamClockFeedState.STALE, HamClockFeedState.DEGRADED -> NeuralProviderState.STALE
    HamClockFeedState.UNAVAILABLE -> NeuralProviderState.UNAVAILABLE
}
internal fun NeuralProviderState.toHamClockFeedState(): HamClockFeedState = when (this) {
    NeuralProviderState.LIVE -> HamClockFeedState.LIVE
    NeuralProviderState.CACHED -> HamClockFeedState.CACHED
    NeuralProviderState.STALE -> HamClockFeedState.STALE
    NeuralProviderState.UNAVAILABLE -> HamClockFeedState.UNAVAILABLE
}
internal fun neuralSignalSourceState(snapshot: PskReporterSnapshot, hasRows: Boolean): NeuralSignalSourceState {
    val states = listOf(snapshot.beingHeard.state, snapshot.hearing.state)
    val unavailable = states.count { it == HamClockFeedState.UNAVAILABLE }
    val error = snapshot.beingHeard.error.isNotBlank() || snapshot.hearing.error.isNotBlank()
    return when {
        unavailable == 2 && hasRows -> NeuralSignalSourceState.DEGRADED
        unavailable == 2 && error -> NeuralSignalSourceState.ERROR
        unavailable == 1 || states.any { it in setOf(HamClockFeedState.DEGRADED, HamClockFeedState.STALE) } -> NeuralSignalSourceState.DEGRADED
        hasRows || states.any { it in setOf(HamClockFeedState.LIVE, HamClockFeedState.CACHED) } -> NeuralSignalSourceState.CURRENT
        else -> NeuralSignalSourceState.EMPTY
    }
}

internal fun dxRfEvidenceDestination(): NeuralDxPage = NeuralDxPage.OBSERVATIONS
internal data class NeuralMySignal(val available:Boolean=false,val callsign:String="",val fetchedEpoch:Long=0,
    val reports:List<SignalReport> = emptyList(),val source:String="PSK Reporter",val error:String="",
    val beingHeardState: HamClockFeedState = HamClockFeedState.UNAVAILABLE,
    val hearingState: HamClockFeedState = HamClockFeedState.UNAVAILABLE,
    val beingHeardCount: Int = 0, val hearingCount: Int = 0,
    val sourceState: NeuralSignalSourceState = NeuralSignalSourceState.EMPTY,
    val status: NeuralProviderStatus = NeuralProviderStatus("PSK Reporter", NeuralProviderState.UNAVAILABLE))

internal data class GeoPoint(val latitude: Double, val longitude: Double)

internal fun maidenheadCenter(raw: String): GeoPoint? {
    val grid = raw.filterNot(Char::isWhitespace).uppercase(Locale.US)
    if (grid.length !in setOf(4, 6, 8) || grid[0] !in 'A'..'R' || grid[1] !in 'A'..'R' ||
        grid[2] !in '0'..'9' || grid[3] !in '0'..'9' ||
        (grid.length >= 6 && (grid[4] !in 'A'..'X' || grid[5] !in 'A'..'X')) ||
        (grid.length == 8 && (grid[6] !in '0'..'9' || grid[7] !in '0'..'9'))) return null
    var lon = (grid[0] - 'A') * 20.0 - 180.0 + (grid[2] - '0') * 2.0
    var lat = (grid[1] - 'A') * 10.0 - 90.0 + (grid[3] - '0')
    var lonSize = 2.0; var latSize = 1.0
    if (grid.length >= 6) {
        lonSize /= 24.0; latSize /= 24.0
        lon += (grid[4] - 'A') * lonSize; lat += (grid[5] - 'A') * latSize
    }
    if (grid.length == 8) {
        lonSize /= 10.0; latSize /= 10.0
        lon += (grid[6] - '0') * lonSize; lat += (grid[7] - '0') * latSize
    }
    return GeoPoint(lat + latSize / 2.0, lon + lonSize / 2.0)
}

internal fun dxDistanceKm(
    stationGrid: String,
    remoteGrid: String,
    remoteLatitude: String = "",
    remoteLongitude: String = "",
): Int? {
    val origin = maidenheadCenter(stationGrid) ?: return null
    val target = maidenheadCenter(remoteGrid) ?: run {
        val latitude = remoteLatitude.toDoubleOrNull()
        val longitude = remoteLongitude.toDoubleOrNull()
        if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            (latitude == 0.0 && longitude == 0.0)) return null
        GeoPoint(latitude, longitude)
    }
    val p1 = Math.toRadians(origin.latitude)
    val p2 = Math.toRadians(target.latitude)
    val deltaLatitude = p2 - p1
    val deltaLongitude = Math.toRadians(target.longitude - origin.longitude)
    val haversine = sin(deltaLatitude / 2).pow(2) + cos(p1) * cos(p2) * sin(deltaLongitude / 2).pow(2)
    return (6371.0 * 2 * atan2(sqrt(haversine), sqrt(1 - haversine))).roundToInt()
}

internal fun tropoIndex(surface: Double?, at850: Double?, humidity: Int?, cape: Double?): Pair<Int?, String?> {
    if (surface == null || at850 == null) return null to null
    val inversion = 8.5 - (surface - at850)
    var score = when { inversion >= 6 -> 4; inversion >= 3 -> 2; inversion >= 0 -> 1; else -> 0 }
    if ((humidity ?: 0) >= 80) score += 2
    if (cape != null && cape < 100) score += 1
    val index = min(score * 10 / 7, 10)
    return index to when { index >= 7 -> "HIGH"; index >= 4 -> "MODERATE"; else -> "LOW" }
}

internal fun decodeNeuralWeather(text: String): NeuralWeather {
    val root = JSONObject(text)
    val current = root.getJSONObject("current")
    val hourly = root.getJSONObject("hourly")
    val times = hourly.getJSONArray("time")
    require(times.length() > 0) { "Weather hourly data is empty" }
    val currentTime = current.getString("time")
    val index = (0 until times.length()).firstOrNull { times.getString(it) == currentTime }
        ?: error("Weather current hour is missing")
    fun number(name: String): Double? = hourly.getJSONArray(name).optionalFiniteDouble(index)
    val temp = current.optionalFiniteDouble("temperature_2m")
    val humidity = current.optionalFiniteDouble("relative_humidity_2m")?.roundToInt()
    val cape = number("cape")
    val at850 = number("temperature_850hPa")
    val tropo = tropoIndex(temp, at850, humidity, cape)
    val pressures = hourly.getJSONArray("pressure_msl")
    val trend = if (index >= 2) {
        val delta = pressures.getDouble(index) - pressures.getDouble(index - 2)
        when {
            delta <= -2.0 -> "FALLING FAST"
            delta <= -0.6 -> "FALLING"
            delta >= 2.0 -> "RISING FAST"
            delta >= 0.6 -> "RISING"
            else -> "STEADY"
        }
    } else "—"
    return NeuralWeather(
        available = true,
        temperatureC = temp,
        pressureHpa = current.optionalFiniteDouble("pressure_msl"),
        humidityPercent = humidity,
        windKmh = current.optionalFiniteDouble("wind_speed_10m"),
        windDirection = current.optionalFiniteDouble("wind_direction_10m")?.roundToInt(),
        precipitationMm = current.optionalFiniteDouble("precipitation"),
        weatherCode = current.optionalFiniteDouble("weather_code")?.roundToInt(),
        cape = cape,
        temperature850C = at850,
        wind300Kmh = number("wind_speed_300hPa"),
        wind300Direction = number("wind_direction_300hPa")?.roundToInt(),
        tropoIndex = tropo.first,
        ductingRisk = tropo.second,
        pressureTrend = trend,
    )
}

internal fun decodeNeuralWspr(text: String): NeuralWspr {
    val data = JSONObject(text).getJSONArray("data")
    val hf = mutableListOf<WsprBandActivity>()
    val vhf = mutableListOf<WsprBandActivity>()
    for (index in 0 until data.length()) {
        val row = data.getJSONObject(index)
        val band = normalizeNeuralWsprBand(row.get("band").toString())
        val item = WsprBandActivity(
            band,
            row.getInt("spot_count"),
            row.optionalFiniteDouble("avg_snr"),
            row.optionalFiniteDouble("avg_dist")?.roundToInt(),
        )
        if (band in setOf("6m", "4m", "2m", "70cm", "23cm")) vhf += item else hf += item
    }
    return NeuralWspr(available = true, hf = hf, vhf = vhf)
}

internal fun normalizeNeuralWsprBand(raw: String): String = when (raw.trim().lowercase()) {
        "-1" -> "2190m"; "0" -> "630m"; "1" -> "160m"; "3" -> "80m"; "5" -> "60m"; "7" -> "40m"
    "10" -> "30m"; "14" -> "20m"; "18" -> "17m"; "21" -> "15m"; "24" -> "12m"; "28" -> "10m"
    "50" -> "6m"; "70" -> "4m"; "144" -> "2m"; "432" -> "70cm"; else -> raw
}

private fun JSONObject.optionalFiniteDouble(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf(Double::isFinite) else null

private fun JSONArray.optionalFiniteDouble(index: Int): Double? =
    if (index in 0 until length() && !isNull(index)) optDouble(index).takeIf(Double::isFinite) else null

internal fun dxDirectTuneAvailable(frequencyHz: Long): Boolean =
    frequencyHz in 1_000_000L..54_000_000L

internal fun dxDisplayBand(band: String, frequencyHz: Long, comment: String): String {
    if (band != "3cm") return band
    val qo100 = frequencyHz in 10_489_500_000L..10_490_000_000L ||
        comment.contains("QO-100", ignoreCase = true) || comment.contains("QO100", ignoreCase = true)
    return if (qo100) "3cm · QO-100" else band
}

internal fun extractCallsigns(text: String): List<String> = Regex("(?<![A-Z0-9/])(?:(?:[A-Z0-9]{1,4})/)?(?:[A-Z]{1,2}|[0-9][A-Z])[0-9][A-Z0-9]{1,4}(?:/[A-Z0-9]{1,4})?(?![A-Z0-9/])")
    .findAll(text.uppercase(Locale.US)).map { it.value }.filterNot { it in setOf("2024", "2025", "2026") }.distinct().take(24).toList()

internal data class SpotHistorySummary(
    val callsign: String,
    val observations: Int,
    val firstEpoch: Long,
    val lastEpoch: Long,
    val bands: List<String>,
    val modes: List<String>,
    val spotters: Int,
    val countries: List<String>,
)

internal class NeuralDxStore(context: Context, databaseName: String = "neural-dx.sqlite") :
    SQLiteOpenHelper(context, databaseName, null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE spot(id TEXT PRIMARY KEY, ts INTEGER NOT NULL, call TEXT NOT NULL, spotter TEXT NOT NULL,
            frequency_hz INTEGER NOT NULL, band TEXT NOT NULL, mode TEXT NOT NULL, country TEXT NOT NULL,
            dxcc TEXT NOT NULL DEFAULT '', continent TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
            score INTEGER NOT NULL, confidence INTEGER NOT NULL DEFAULT 0, samples INTEGER NOT NULL DEFAULT 0,
            watchlisted INTEGER NOT NULL, comment TEXT NOT NULL, reason TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX spot_ts_idx ON spot(ts DESC)")
        db.execSQL("CREATE INDEX spot_band_ts_idx ON spot(band,ts DESC)")
        db.execSQL("CREATE INDEX spot_call_ts_idx ON spot(call,ts DESC)")
        db.execSQL("CREATE INDEX spot_dxcc_band_ts_idx ON spot(dxcc,band,ts DESC)")
        createNeuralOutlookSchema(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE spot ADD COLUMN dxcc TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN confidence INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE spot ADD COLUMN samples INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE spot ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE spot SET updated_at=ts WHERE updated_at=0")
            db.execSQL("CREATE INDEX IF NOT EXISTS spot_dxcc_band_ts_idx ON spot(dxcc,band,ts DESC)")
            db.execSQL("DROP TABLE IF EXISTS prediction_result")
        }
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS prediction_result")
            createNeuralOutlookSchema(db)
        }
        if (oldVersion in 4 until 5) upgradeNeuralOutlookSchemaV5(db)
    }

    fun ingest(rows: List<AndroidDXSpot>): List<AndroidDXSpot> {
        val inserted = mutableListOf<AndroidDXSpot>(); val db = writableDatabase
        db.beginTransaction()
        try {
            val updatedAt = Instant.now().epochSecond
            rows.forEach { row ->
                val values = ContentValues().apply {
                    put("id", row.id); put("ts", row.receivedEpoch); put("call", row.callsign); put("spotter", row.spotter)
                    put("frequency_hz", row.frequencyHz); put("band", row.band); put("mode", row.mode); put("country", row.country)
                    put("dxcc", row.dxcc); put("continent", row.continent); put("latitude", row.latitude); put("longitude", row.longitude)
                    put("score", row.score); put("confidence", row.confidence); put("samples", row.samples)
                    put("watchlisted", if (row.watchlisted) 1 else 0); put("comment", row.comment); put("reason", row.reason)
                    put("updated_at", updatedAt)
                }
                if (db.insertWithOnConflict("spot", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted += row
                } else {
                    val updates = ContentValues().apply {
                        put("ts", row.receivedEpoch); put("call", row.callsign); put("spotter", row.spotter)
                        put("frequency_hz", row.frequencyHz); put("band", row.band); put("mode", row.mode)
                        put("score", row.score); put("confidence", row.confidence); put("samples", row.samples)
                        put("watchlisted", if (row.watchlisted) 1 else 0); put("reason", row.reason); put("updated_at", updatedAt)
                        if (row.country.isNotBlank()) put("country", row.country)
                        if (row.dxcc.isNotBlank()) put("dxcc", row.dxcc)
                        if (row.continent.isNotBlank()) put("continent", row.continent)
                        if (row.latitude in -90.0..90.0 && row.longitude in -180.0..180.0 &&
                            (row.latitude != 0.0 || row.longitude != 0.0)) {
                            put("latitude", row.latitude); put("longitude", row.longitude)
                        }
                        if (row.comment.isNotBlank()) put("comment", row.comment)
                    }
                    db.update("spot", updates, "id=?", arrayOf(row.id))
                }
            }
            val rowCount = db.rawQuery("SELECT COUNT(*) FROM spot", emptyArray()).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (rowCount > 510_000L) db.execSQL(
                "DELETE FROM spot WHERE rowid IN (SELECT rowid FROM spot ORDER BY ts DESC LIMIT -1 OFFSET 500000)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return inserted
    }

    fun searchHistory(query: String): List<SpotHistorySummary> {
        val callsign = query.trim().uppercase(Locale.US)
        if (callsign.length < 2) return emptyList()
        val rows = mutableListOf<SpotHistorySummary>()
        readableDatabase.rawQuery("""SELECT call,COUNT(*),MIN(ts),MAX(ts),GROUP_CONCAT(DISTINCT band),
            GROUP_CONCAT(DISTINCT mode),COUNT(DISTINCT spotter),GROUP_CONCAT(DISTINCT country)
            FROM spot WHERE call=? GROUP BY call LIMIT 1""",
            arrayOf(callsign)).use { cursor ->
            while (cursor.moveToNext()) rows += SpotHistorySummary(
                cursor.getString(0), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3),
                cursor.getString(4).orEmpty().split(',').filter(String::isNotBlank).sorted(),
                cursor.getString(5).orEmpty().split(',').filter(String::isNotBlank).sorted(), cursor.getInt(6),
                cursor.getString(7).orEmpty().split(',').filter(String::isNotBlank).distinct().sorted(),
            )
        }
        return rows
    }

    fun bandActivity(hours: Int = 24): Map<String, Int> {
        val out = linkedMapOf<String, Int>(); val since = Instant.now().epochSecond - hours * 3600L
        readableDatabase.rawQuery("SELECT band,COUNT(*) FROM spot WHERE ts>=? GROUP BY band ORDER BY COUNT(*) DESC",
            arrayOf(since.toString())).use { while (it.moveToNext()) out[it.getString(0)] = it.getInt(1) }
        return out
    }

    fun heatmap6m(): List<List<Int>> {
        val out = List(7) { MutableList(24) { 0 } }; val since = Instant.now().epochSecond - 7L * 86400L
        readableDatabase.rawQuery("SELECT ts FROM spot WHERE ts>=? AND band='6m'", arrayOf(since.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val z = Instant.ofEpochSecond(cursor.getLong(0)).atZone(ZoneOffset.UTC)
                val day = ((z.dayOfWeek.value - 1) % 7); out[day][z.hour]++
            }
        }
        return out
    }

    fun worldCells(windowMinutes: Int, band: String, stationKey: String = ""): List<NeuralWorldCell> {
        if (stationKey.isBlank()) return emptyList()
        val now = Instant.now().epochSecond; val currentSince = now - windowMinutes * 60L
        val historySince = now - 56L * 86400L
        data class Bucket(var current: Int = 0, var sourceCount: Int = 0, val historical: MutableMap<Long, Int> = linkedMapOf())
        val buckets = mutableMapOf<Pair<Int, Int>, Bucket>()
        val bandSql = if (band == "ALL") " AND band<>'*'" else " AND band=?"
        val args = mutableListOf(stationKey, NEURAL_OUTLOOK_SHARED_HISTORY_KEY, historySince.toString(), now.toString()).apply {
            if (band != "ALL") add(band)
        }.toTypedArray()
        readableDatabase.rawQuery("""SELECT bucket_start,region_row,region_col,SUM(observation_count),COUNT(DISTINCT source) FROM evidence_bucket
            WHERE station_key IN (?,?) AND bucket_start>=? AND bucket_start<=? AND region_row BETWEEN 0 AND 5
            AND region_col BETWEEN 0 AND 11$bandSql GROUP BY bucket_start,region_row,region_col""", args).use { c ->
            while (c.moveToNext()) {
                val epoch = c.getLong(0); val slot = ((epoch / 900L) % 96L).toInt()
                if (utcQuarterHourDistance(epoch, now) > 2 && epoch < currentSince) continue
                val value = buckets.getOrPut(c.getInt(1) to c.getInt(2)) { Bucket() }
                if (epoch >= currentSince) { value.current += c.getInt(3); value.sourceCount = max(value.sourceCount, c.getInt(4)) }
                else value.historical[epoch] = (value.historical[epoch] ?: 0) + c.getInt(3)
            }
        }
        return buckets.map { (key, value) ->
            val samples = value.historical.size
            val expected = if (samples > 0) value.historical.values.sum().toDouble() / samples else null
            val ratio = expected?.takeIf { samples >= 8 && it >= .25 }?.let { value.current / it }
            val lat = 75.0 - key.first * 30.0 - 15.0; val lon = -180.0 + key.second * 30.0 + 15.0
            val label = when {
                ratio == null -> "INSUFFICIENT BASELINE"
                ratio >= 1.8 -> "STRONG ANOMALY"
                ratio >= 1.25 -> "ABOVE NORMAL"
                ratio <= 0.65 -> "BELOW NORMAL"
                else -> "NORMAL RANGE"
            }
            NeuralWorldCell(key.first, key.second, lat, lon, value.current, expected, ratio,
                when { samples >= 24 -> "HIGH"; samples >= 8 -> "MEDIUM"; else -> "LOW" }, emptyList(), isGreyline(lon, now),
                samples, value.sourceCount, label)
        }.filter { it.observed > 0 || it.expected != null }.sortedByDescending { it.anomalyRatio ?: it.observed.toDouble() }.take(72)
    }

    fun recentBandCount(band: String, minutes: Int): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM spot WHERE band=? AND ts>=?", arrayOf(band, (Instant.now().epochSecond - minutes * 60L).toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    internal fun outlookReadableDatabase(): SQLiteDatabase = readableDatabase
    internal fun outlookWritableDatabase(): SQLiteDatabase = writableDatabase

    companion object {
        private fun isGreyline(longitude: Double, epoch: Long): Boolean {
            val utc = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC)
            val localSolarHour = (utc.hour + utc.minute / 60.0 + longitude / 15.0 + 24.0) % 24.0
            return localSolarHour < 1.0 || localSolarHour > 23.0 || localSolarHour in 5.0..7.0 || localSolarHour in 17.0..19.0
        }
    }
}

class NeuralDxController internal constructor(private val context: Context, private val database: QsoDatabase,
    private val publicProviders: HamClockPublicProviders,
    private val satelliteOwner: SatelliteOperationsController,
    initialGrid: String = "") {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val store = NeuralDxStore(context)
    internal val outlook = NeuralOutlookController(store)
    private val prefs = context.getSharedPreferences("neural-dx-v12", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "neural-dx-cache").apply { mkdirs() }
    private var refreshJob: Job? = null
    private var lightningJob: Job? = null
    private var lightningPoint: GeoPoint? = null
    @Volatile private var activeLightningSocket: Socket? = null
    @Volatile private var closed = false
    private var pskPreference = HamClockPskPreference()
    private var pskSnapshot = PskReporterSnapshot()
    private var pskJob: Job? = null
    private var pskGeneration = 0
    private var lastPskCall = ""
    private var lastPskPoint: GeoPoint? = null
    private var lastPskAttemptEpoch = 0L
    private var wsprPreference = HamClockWsprPreference(personalEnabled = false)
    private var wsprJob: Job? = null
    private var wsprGeneration = 0
    private var lastWsprCall = ""
    private var lastWsprPoint: GeoPoint? = null
    private var lastWsprAttemptEpoch = 0L
    private var foreground = true
    private var dxpeditionFeed: HamClockFeed<List<HamClockDxpedition>>? = null
    private var ctyController: CtyController? = null
    private val lightningBuffer = ArrayDeque<LightningStrike>()
    private var lastIngestSpots = emptyList<AndroidDXSpot>()
    private var lastIngestStation: String? = null
    private var lastCtyRevision = Long.MIN_VALUE

    private val initialBeacon = loadBeaconReference()
    private val initialPoint = maidenheadCenter(initialGrid)

    var weather by mutableStateOf(initialPoint?.let(::loadWeatherCache) ?: NeuralWeather()); private set
    var wspr by mutableStateOf(NeuralWspr(status = NeuralProviderStatus("Personal WSPR via PSK Reporter",
        NeuralProviderState.UNAVAILABLE, detail = "Regional WSPR.live is unavailable by policy"))); private set
    internal var wsprPersonal by mutableStateOf(HamClockWsprSnapshot()); private set
    internal var briefing by mutableStateOf(emptyList<BriefingSource>()); private set
    internal var dxNewsSnapshot by mutableStateOf(DxNewsSnapshot()); private set
    val satelliteCatalogue: List<OrbitRecord> get() = satelliteOwner.elements.rows.map { row ->
        OrbitRecord(row.noradId.toInt(), row.name, row.elementEpoch, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
    val satelliteStatus: NeuralProviderStatus get() = satelliteOwner.elements.metadata.let { metadata ->
        NeuralProviderStatus(metadata.source, when (metadata.state) {
            SatelliteCacheState.CURRENT -> NeuralProviderState.LIVE
            SatelliteCacheState.OFFLINE_CACHE -> NeuralProviderState.CACHED
            SatelliteCacheState.STALE -> NeuralProviderState.STALE
            SatelliteCacheState.EMPTY, SatelliteCacheState.ERROR -> NeuralProviderState.UNAVAILABLE
        }, metadata.fetchedAt, detail = metadata.lastError)
    }
    val satellites: List<SatellitePosition> get() = satelliteOwner.hamClockPositions.map { row ->
        val footprint = 6378.137 * acos((6378.137 / (6378.137 + max(0.0, row.altitudeKm))).coerceIn(-1.0, 1.0))
        SatellitePosition(row.noradId.toInt(), row.name, row.latitude, row.longitude, row.altitudeKm,
            row.azimuthDeg, row.elevationDeg, row.rangeKm, footprint, row.elevationDeg > 0, row.generatedAtEpoch)
    }
    val passes: List<SatellitePass> get() = satelliteOwner.passes.map { row -> SatellitePass(
        row.satellite.noradId.toInt(), row.satellite.name, row.pass.aos, row.pass.tca, row.pass.los,
        row.pass.maximumElevationDeg, row.pass.aosAzimuthDeg, row.pass.losAzimuthDeg)
    }
    val transmitters: Map<Int, List<SatelliteTransmitter>> get() = satelliteOwner.elements.rows.associate { entry ->
        entry.noradId.toInt() to satelliteOwner.transpondersFor(entry.noradId).map { row ->
            SatelliteTransmitter(row.description, frequencyLabel(row.uplinkLowHz ?: 0),
                frequencyLabel(row.downlinkLowHz ?: 0), row.mode, row.providerStatus)
        }
    }
    var insight by mutableStateOf(NeuralInsight()); private set
    var currentOpportunities by mutableStateOf(emptyList<NeuralCurrentOpportunity>()); private set
    var world by mutableStateOf(emptyList<NeuralWorldCell>()); private set
    var heatmap6m by mutableStateOf(List(7) { List(24) { 0 } }); private set
    var bandActivity by mutableStateOf(emptyMap<String, Int>()); private set
    var beacons by mutableStateOf(emptyList<BeaconReception>()); private set
    var beaconReference by mutableStateOf(initialBeacon.first); private set
    var beaconProviderStatus by mutableStateOf(initialBeacon.second); private set
    var lightning by mutableStateOf(NeuralLightning()); private set
    internal var mySignal by mutableStateOf(loadMySignalCache()); private set
    var requestedSignalReportId by mutableStateOf<String?>(null); private set
    var requestedPage by mutableStateOf<NeuralDxPage?>(null); private set
    var requestedRfEvidenceId by mutableStateOf<String?>(null); private set
    var requestedBandEvidence by mutableStateOf<String?>(null); private set
    var signalSelectionMessage by mutableStateOf(""); private set
    val alerts = mutableStateListOf<String>()
    var status by mutableStateOf("Neural DX cache ready"); private set
    var providerStatuses by mutableStateOf(emptyList<NeuralProviderStatus>()); private set
    var refreshing by mutableStateOf(false); private set
    var lastRefreshEpoch by mutableStateOf(0L); private set
    var worldWindowMinutes by mutableStateOf(180); private set
    var worldBand by mutableStateOf("ALL"); private set
    val followedNorads: List<Int> get() = satelliteOwner.favourites.map(Long::toInt)
    var notificationsEnabled by mutableStateOf(prefs.getBoolean("notifications", false)); private set
    var ntfyUrl by mutableStateOf(prefs.getString("ntfy_url", "") ?: ""); private set
    var ntfyToken by mutableStateOf(readSecret("ntfy_token")); private set
    var perplexityKey by mutableStateOf(readSecret("perplexity_key")); private set
    var briefingDxMode by mutableStateOf(prefs.getBoolean("briefing_dx_mode", true)); private set
    var briefingOrder by mutableStateOf(loadBriefingOrder()); private set
    var enrichedSpots by mutableStateOf(emptyList<AndroidDXSpot>()); private set
    internal var historyResults by mutableStateOf(emptyList<SpotHistorySummary>()); private set
    internal var historySearching by mutableStateOf(false); private set
    private var historySearchGeneration = 0

    init { createNotificationChannel() }

    internal fun searchSpotHistory(query: String) {
        val generation = ++historySearchGeneration
        if (query.trim().length < 2) { historyResults = emptyList(); historySearching = false; return }
        historySearching = true
        scope.launch {
            val rows = store.searchHistory(query)
            withContext(Dispatchers.Main) {
                if (generation == historySearchGeneration) {
                    historyResults = rows
                    historySearching = false
                }
            }
        }
    }

    fun ingest(spots: List<AndroidDXSpot>, stationId: String?, cty: CtyController, stationCall: String = "") {
        ctyController = cty
        if (spots.isEmpty()) return
        if (spots == lastIngestSpots && stationId == lastIngestStation && cty.dataRevision == lastCtyRevision) return
        lastIngestSpots = spots; lastIngestStation = stationId; lastCtyRevision = cty.dataRevision
        scope.launch {
            val resolved = spots.map { row -> row to cty.lookup(row.callsign) }
            val enriched = resolved.map { (row, entity) -> entity?.let { row.copy(
                country = entity.country.ifBlank { row.country }, continent = entity.continent.ifBlank { row.continent },
                dxcc = entity.dxcc.ifBlank { row.dxcc },
                cqZone = entity.cqZone.toIntOrNull() ?: row.cqZone, ituZone = entity.ituZone.toIntOrNull() ?: row.ituZone,
                latitude = entity.latitude.takeUnless { it == 0.0 } ?: row.latitude,
                longitude = entity.longitude.takeUnless { it == 0.0 } ?: row.longitude) } ?: row }
            withContext(Dispatchers.Main) { enrichedSpots = enriched }
            val fresh = store.ingest(enriched)
            val statuses = database.spotStatuses(enriched.map { it.toSpotLogIdentity(null) }, stationId)
            fresh.forEach { spot ->
                val state = statuses[spot.id]
                if (spot.watchlisted) deliverAlert("Watchlist · ${spot.callsign}", "${spot.band} ${spot.mode} · ${spot.country}", "watch:${spot.callsign}")
                if (state?.dxccStatus == "ATNO") deliverAlert("New DXCC · ${spot.country}", "${spot.callsign} on ${spot.band} ${spot.mode}", "dxcc:${spot.country}:${spot.band}")
            }
            if (store.recentBandCount("6m", 10) >= 8) deliverAlert("6m opening", "${store.recentBandCount("6m", 10)} spots in the last 10 minutes", "surge:6m")
            publishDerived(enriched, stationId, stationCall)
        }
    }

    fun setWorldFilter(windowMinutes: Int, band: String) {
        worldWindowMinutes = windowMinutes.coerceIn(15, 360); worldBand = band
        scope.launch { val rows = store.worldCells(worldWindowMinutes, worldBand, outlook.snapshot.stationKey); withContext(Dispatchers.Main) { world = rows } }
    }

    fun saveSettings(notifications: Boolean, ntfy: String, token: String, perplexity: String, dxMode: Boolean) {
        notificationsEnabled = notifications; ntfyUrl = ntfy.trim(); ntfyToken = token.trim(); perplexityKey = perplexity.trim()
        briefingDxMode = dxMode
        prefs.edit().putBoolean("notifications", notifications).putString("ntfy_url", ntfyUrl)
            .putString("ntfy_token", encrypt(ntfyToken)).putString("perplexity_key", encrypt(perplexityKey))
            .putBoolean("briefing_dx_mode", dxMode).apply()
    }

    fun setFollowed(norad: Int, followed: Boolean) {
        if ((norad.toLong() in satelliteOwner.favourites) != followed) satelliteOwner.toggleFavourite(norad.toLong())
    }

    fun requestSignalReport(referenceId: String) { requestedSignalReportId = referenceId }
    fun requestPage(page: NeuralDxPage) { requestedPage = page }
    fun requestBandEvidence(band: String) {
        requestedBandEvidence = band.takeIf(String::isNotBlank)
        requestedPage = dxRfEvidenceDestination()
    }
    fun requestRfEvidence(referenceId: String) { requestedRfEvidenceId = referenceId }
    fun consumeRequestedRfEvidence() { requestedRfEvidenceId = null }
    fun consumeRequestedPage() { requestedPage = null }
    fun consumeRequestedBandEvidence() { requestedBandEvidence = null }
    fun consumeRequestedSignalReport(message: String = "") {
        requestedSignalReportId = null
        signalSelectionMessage = message.take(160)
    }

    fun stopLegacySatelliteTicker() = Unit
    fun setSatelliteWorkspaceActive(active: Boolean, grid: String) = satelliteOwner.setNeuralActive(active, grid)

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        outlook.setForeground(value)
        if (!value) {
            refreshJob?.cancel(); refreshJob = null
            pskGeneration++; pskJob?.cancel(); pskJob = null
            wsprGeneration++; wsprJob?.cancel(); wsprJob = null
            lightningJob?.cancel(); lightningJob = null
        } else {
            lightningPoint?.let(::ensureLightning)
        }
    }

    internal fun updateDxNewsCalendar(feed: HamClockFeed<List<HamClockDxpedition>>) {
        dxpeditionFeed = feed
        scope.launch { refreshBriefing(false) }
    }

    internal fun ensureDxNews() {
        if (!foreground) return
        scope.launch { refreshBriefing(false) }
    }

    internal fun applyPskPreference(value: HamClockPskPreference) {
        val providerChanged = pskPreference.enabled != value.enabled || pskPreference.windowMinutes != value.windowMinutes
        pskPreference = value
        if (!value.enabled) {
            pskGeneration++; pskJob?.cancel(); pskJob = null
            pskSnapshot = PskReporterSnapshot(lastPskCall)
            publishPskSnapshot(pskSnapshot)
        } else if (foreground && providerChanged && lastPskCall.isNotBlank()) {
            val generation = ++pskGeneration
            pskJob?.cancel()
            pskJob = scope.launch { runCatching { refreshMySignal(lastPskCall, lastPskPoint, false, generation) } }
        } else publishPskSnapshot(pskSnapshot)
    }

    internal fun applyWsprPreference(value: HamClockWsprPreference) {
        val providerChanged = wsprPreference.personalEnabled != value.personalEnabled ||
            wsprPreference.windowMinutes != value.windowMinutes
        wsprPreference = value
        if (!value.personalEnabled) {
            wsprGeneration++; wsprJob?.cancel(); wsprJob = null
            publishWsprSnapshot(HamClockWsprSnapshot(lastWsprCall,
                regionalState = if (value.regionalEnabled)
                    app.shackcq.mobile.hamclock.HamClockWsprRegionalState.UNAVAILABLE_POLICY
                else app.shackcq.mobile.hamclock.HamClockWsprRegionalState.DISABLED))
        } else if (foreground && providerChanged && lastWsprCall.isNotBlank()) {
            lastWsprAttemptEpoch = 0
            refreshWspr(lastWsprCall, "", false)
        } else publishWsprSnapshot(publicProviders.wspr.reprojectPersonal(value, lastWsprPoint))
    }

    /** Reprojects retained PSK/WSPR reports locally; this method never performs HTTP work. */
    internal fun updateSignalGeometry(stationGrid: String) {
        val point = maidenheadCenter(stationGrid)
        lastPskPoint = point
        lastWsprPoint = point
        pskSnapshot = publicProviders.pskReporter.reproject(pskSnapshot, point)
        publishPskSnapshot(pskSnapshot)
        publishWsprSnapshot(publicProviders.wspr.reprojectPersonal(wsprPreference, point))
    }

    fun refreshWspr(call: String, grid: String, force: Boolean = false) {
        if (!shouldStartForegroundWork(foreground)) return
        val normalized = call.trim().uppercase(Locale.US)
        updateSignalGeometry(grid)
        val point = lastWsprPoint
        lastWsprCall = normalized
        lastWsprPoint = point
        if (!wsprPreference.personalEnabled || normalized.isBlank()) {
            applyWsprPreference(wsprPreference)
            return
        }
        val now = Instant.now().epochSecond
        if (!force && now - lastWsprAttemptEpoch < 300L) {
            publishWsprSnapshot(publicProviders.wspr.reprojectPersonal(wsprPreference, point))
            return
        }
        lastWsprAttemptEpoch = now
        val generation = ++wsprGeneration
        wsprJob?.cancel()
        wsprJob = scope.launch {
            val loaded = publicProviders.wspr.refreshPersonal(normalized, point, wsprPreference, force)
            if (generation == wsprGeneration) withContext(Dispatchers.Main) { publishWsprSnapshot(loaded) }
        }
    }

    private fun publishWsprSnapshot(snapshot: HamClockWsprSnapshot) {
        wsprPersonal = snapshot
        val rows = snapshot.reports.groupBy(SignalReport::band).map { (band, reports) ->
            WsprBandActivity(band, reports.size, reports.mapNotNull(SignalReport::snr).average().takeIf(Double::isFinite),
                reports.mapNotNull(SignalReport::distanceKm).average().takeIf(Double::isFinite)?.roundToInt())
        }
        val vhfBands = setOf("6m", "4m", "2m", "70cm", "23cm")
        wspr = NeuralWspr(
            available = snapshot.beingHeardState != HamClockFeedState.UNAVAILABLE ||
                snapshot.hearingState != HamClockFeedState.UNAVAILABLE,
            updatedEpoch = snapshot.fetchedEpoch,
            hf = rows.filter { it.band !in vhfBands },
            vhf = rows.filter { it.band in vhfBands },
            error = snapshot.error,
            status = NeuralProviderStatus(
                "PSK Reporter · personal WSPR",
                neuralProviderState(if (snapshot.beingHeardState != HamClockFeedState.UNAVAILABLE)
                    snapshot.beingHeardState else snapshot.hearingState),
                updatedEpoch = snapshot.fetchedEpoch,
                detail = listOf(snapshot.error, "Regional WSPR.live unavailable by policy")
                    .filter(String::isNotBlank).joinToString(" · "),
            ),
        )
    }

    fun refreshPsk(call: String, grid: String, force: Boolean = false) {
        if (!shouldStartForegroundWork(foreground)) return
        val normalized = call.trim().uppercase(Locale.US)
        updateSignalGeometry(grid)
        if (!pskPreference.enabled || normalized.isBlank()) { publishPskSnapshot(PskReporterSnapshot(normalized)); return }
        val now = Instant.now().epochSecond
        if (!force && now - lastPskAttemptEpoch < pskPreference.refreshSeconds) {
            publishPskSnapshot(pskSnapshot)
            return
        }
        lastPskAttemptEpoch = now
        val point = lastPskPoint
        val generation = ++pskGeneration
        pskJob?.cancel()
        pskJob = scope.launch { runCatching { refreshMySignal(call, point, force, generation) }
            .onFailure { error -> withContext(Dispatchers.Main) { mySignal = mySignal.copy(error = safeError(error)) } } }
    }

    fun clearPskDisplay() {
        pskGeneration++; pskJob?.cancel(); pskJob = null
        pskSnapshot = PskReporterSnapshot(lastPskCall)
        publishPskSnapshot(pskSnapshot)
        consumeRequestedSignalReport("PSK display cleared")
    }

    fun moveBriefingSource(id: String, direction: Int) {
        val order = briefingOrder.toMutableList(); val from = order.indexOf(id); val to = (from + direction).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return
        order.add(to, order.removeAt(from)); briefingOrder = order
        prefs.edit().putString("briefing_order", order.joinToString(",")).apply()
        briefing = order.mapNotNull { key -> briefing.firstOrNull { it.id == key } } + briefing.filter { it.id !in order }
    }

    fun refresh(call: String, grid: String, stationId: String?, live: List<AndroidDXSpot>, force: Boolean = false,
        refreshScope: NeuralDxRefreshScope = NeuralDxRefreshScope.FULL_DX) {
        if (!shouldStartForegroundWork(foreground, refreshJob?.isActive == true)) return
        if (refreshScope.stopsLegacySatelliteTicker()) stopLegacySatelliteTicker()
        refreshJob = scope.launch {
            withContext(Dispatchers.Main) { refreshing = true; status = "Refreshing Neural DX sources…" }
            val point = maidenheadCenter(grid)
            try {
                val requested = mutableListOf<NeuralProviderStatus>()
                if (point == null) {
                    requested += NeuralProviderStatus(
                        "QTH-dependent providers",
                        NeuralProviderState.UNAVAILABLE,
                        detail = "Set a valid station gridsquare",
                    )
                }
                else {
                    ensureLightning(point)
                    requested += refreshWeather(point, force)
                    requested += refreshBeaconReference(point, force)
                    requested += lightning.status
                }
                refreshBriefing(force)
                val log = database.neuralLogSummary(stationId)
                val localInsight = buildInsight(call, log, live)
                val finalInsight = if (perplexityKey.isNotBlank()) runCatching { enrichInsight(localInsight) }.getOrElse { localInsight.copy(error = "AI provider unavailable; local analysis shown") } else localInsight
                val derivedOpportunities = buildCurrentOpportunities(live)
                val derivedWorld = store.worldCells(worldWindowMinutes, worldBand, outlook.snapshot.stationKey)
                val derivedBands = store.bandActivity(); val derivedHeatmap = store.heatmap6m()
                val derivedBeacons = buildBeaconReception(live)
                withContext(Dispatchers.Main) {
                    insight = finalInsight; currentOpportunities = derivedOpportunities; world = derivedWorld; bandActivity = derivedBands
                    heatmap6m = derivedHeatmap; beacons = derivedBeacons; lastRefreshEpoch = Instant.now().epochSecond
                    providerStatuses = requested
                    status = neuralProviderSummary(requested)
                }
            } finally { withContext(Dispatchers.Main) { refreshing = false } }
        }
    }

    fun refreshSatelliteTransmitters(norad: Int) { satelliteOwner.selectNorad(norad.toLong()) }

    fun testNtfy() = scope.launch { deliverAlert("ShackCQ Neural DX", "Notification test successful", "test:${Instant.now().epochSecond}", force = true) }
    @Synchronized fun close() {
        if (closed) return
        closed = true
        closeActiveLightningSocket()
        refreshJob?.cancel(); pskJob?.cancel(); wsprJob?.cancel(); lightningJob?.cancel()
        outlook.close(); scope.cancel(); store.close()
    }

    @Synchronized private fun registerLightningSocket(socket: Socket) {
        if (closed) {
            socket.close()
            throw CancellationException("Neural DX controller closed")
        }
        activeLightningSocket = socket
    }

    @Synchronized private fun clearLightningSocket(socket: Socket) {
        if (activeLightningSocket === socket) activeLightningSocket = null
    }

    @Synchronized private fun closeActiveLightningSocket() {
        runCatching { activeLightningSocket?.close() }
        activeLightningSocket = null
    }

    private suspend fun publishDerived(rows: List<AndroidDXSpot>, stationId: String?, stationCall: String) {
        val opportunities = buildCurrentOpportunities(rows); val w = store.worldCells(worldWindowMinutes, worldBand, outlook.snapshot.stationKey)
        val b = store.bandActivity(); val h = store.heatmap6m(); val be = buildBeaconReception(rows)
        val currentInsight = buildInsight(stationCall, database.neuralLogSummary(stationId), rows)
        withContext(Dispatchers.Main) { currentOpportunities = opportunities; world = w; bandActivity = b; heatmap6m = h; beacons = be; insight = currentInsight }
    }

    private fun ensureLightning(point: GeoPoint) {
        if (!shouldStartForegroundWork(foreground)) return
        if (lightningJob?.isActive == true && lightningPoint?.let { greatCircleKm(it, point) < 5.0 } == true) return
        closeActiveLightningSocket()
        lightningJob?.cancel(); lightningPoint = point
        lightningJob = scope.launch {
            while (isActive) {
                try {
                    listenForLightning(point)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        val retained = lightning.strikes
                        val state = if (retained.isEmpty()) NeuralProviderState.UNAVAILABLE else NeuralProviderState.STALE
                        val updated = retained.maxOfOrNull(LightningStrike::epoch) ?: lightning.updatedEpoch
                        val detail = safeError(error)
                        lightning = lightning.copy(
                            connected = false,
                            updatedEpoch = updated,
                            error = detail,
                            status = NeuralProviderStatus("Blitzortung community MQTT", state, updatedEpoch = updated, detail = detail),
                        )
                        updateRequestedProviderStatus(lightning.status)
                    }
                    delay(30_000)
                }
            }
        }
    }

    private suspend fun listenForLightning(point: GeoPoint) {
        Socket().use { socket ->
            registerLightningSocket(socket)
            try {
                socket.connect(InetSocketAddress("blitzortung.ha.sed.pl",1883),15_000);socket.soTimeout=20_000
                val input=BufferedInputStream(socket.getInputStream());val output=BufferedOutputStream(socket.getOutputStream())
                mqttWrite(output,0x10,mqttConnectPayload("ShackCQ-${System.currentTimeMillis().toString(16).takeLast(10)}"))
                val connAck=mqttRead(input);require(connAck.first ushr 4==2&&connAck.second.size>=2&&connAck.second[1].toInt()==0){"Lightning broker rejected connection"}
                val topics=lightningGeohashNeighbors(point).map{"blitzortung/1.1/${it.toList().joinToString("/")}/#"}
                val subscribe=ByteArrayOutputStream().apply{write(0);write(1);topics.forEach{topic->writeMqttString(topic);write(0)}}.toByteArray()
                mqttWrite(output,0x82,subscribe)
                val connectedEpoch = Instant.now().epochSecond
                withContext(Dispatchers.Main) {
                    lightning = lightning.copy(
                        connected = true,
                        updatedEpoch = connectedEpoch,
                        error = "",
                        status = NeuralProviderStatus("Blitzortung community MQTT", NeuralProviderState.LIVE, connectedEpoch),
                    )
                    updateRequestedProviderStatus(lightning.status)
                }
                while(currentCoroutineContext().isActive){
                    try {
                        val packet=mqttRead(input);if(packet.first ushr 4==3) consumeLightningPublish(packet.first,packet.second,point)
                    } catch(timeout:SocketTimeoutException){mqttWrite(output,0xC0,byteArrayOf())}
                }
            } finally {
                clearLightningSocket(socket)
            }
        }
    }

    private fun consumeLightningPublish(header:Int,body:ByteArray,point:GeoPoint){
        if(body.size<3)return;val topicLength=((body[0].toInt() and 255) shl 8)+(body[1].toInt() and 255)
        var offset=2+topicLength;if((header and 0x06)!=0)offset+=2;if(offset>=body.size)return
        val row=runCatching{JSONObject(body.copyOfRange(offset,body.size).toString(Charsets.UTF_8))}.getOrNull()?:return
        val lat=row.optionalFiniteDouble("lat")?:return;val lon=row.optionalFiniteDouble("lon")?:return;val target=GeoPoint(lat,lon)
        val distance=greatCircleKm(point,target);if(distance>300)return
        val rawTime=row.optDouble("time",0.0);val epoch=when{rawTime>1e14->(rawTime/1e9).toLong();rawTime>1e11->(rawTime/1000).toLong();rawTime>1e9->rawTime.toLong();else->Instant.now().epochSecond}
        val bearing=initialBearing(point,target);val strike=LightningStrike(epoch,lat,lon,(distance*10).roundToInt()/10.0,bearing.roundToInt(),compass(bearing))
        val snapshot=synchronized(lightningBuffer){lightningBuffer.addLast(strike);val cutoff=Instant.now().epochSecond-3600;while(lightningBuffer.firstOrNull()?.epoch?.let{it<cutoff}==true)lightningBuffer.removeFirst();while(lightningBuffer.size>500)lightningBuffer.removeFirst();lightningBuffer.toList().asReversed()}
        val updated = Instant.now().epochSecond
        scope.launch(Dispatchers.Main){
            lightning=NeuralLightning(
                connected = true,
                updatedEpoch = updated,
                strikes = snapshot,
                status = NeuralProviderStatus("Blitzortung community MQTT", NeuralProviderState.LIVE, updatedEpoch = updated),
            )
            updateRequestedProviderStatus(lightning.status)
        }
    }

    private fun mqttConnectPayload(clientId:String):ByteArray=ByteArrayOutputStream().apply{
        writeMqttString("MQTT");write(4);write(2);write(0);write(60);writeMqttString(clientId)
    }.toByteArray()
    private fun mqttWrite(output:BufferedOutputStream,header:Int,payload:ByteArray){output.write(header);var n=payload.size;do{var digit=n%128;n/=128;if(n>0)digit= digit or 128;output.write(digit)}while(n>0);output.write(payload);output.flush()}
    private fun mqttRead(input:BufferedInputStream):Pair<Int,ByteArray>{val header=input.read();if(header<0)error("Lightning broker closed connection");var multiplier=1;var remaining=0;var loops=0;do{val digit=input.read();if(digit<0)error("Incomplete MQTT packet");remaining+=(digit and 127)*multiplier;multiplier*=128;loops++;require(loops<=4){"Invalid MQTT length"}}while((digit and 128)!=0);require(remaining<=1_000_000){"MQTT packet too large"};return header to input.readExactBytes(remaining)}
    private fun ByteArrayOutputStream.writeMqttString(value:String){val bytes=value.toByteArray();write((bytes.size ushr 8) and 255);write(bytes.size and 255);write(bytes)}
    private fun lightningGeohashNeighbors(point:GeoPoint):Set<String>{val latBits=7;val lonBits=8;val latStep=180.0/(1 shl latBits);val lonStep=360.0/(1 shl lonBits);return buildSet{for(dLat in -1..1)for(dLon in -1..1)add(geohash((point.latitude+dLat*latStep).coerceIn(-89.999,89.999),((point.longitude+dLon*lonStep+180)%360+360)%360-180,3))}}
    private fun geohash(latitude:Double,longitude:Double,precision:Int):String{val alphabet="0123456789bcdefghjkmnpqrstuvwxyz";var minLat=-90.0;var maxLat=90.0;var minLon=-180.0;var maxLon=180.0;var even=true;var bit=0;var value=0;val out=StringBuilder();while(out.length<precision){val mid=if(even)(minLon+maxLon)/2 else(minLat+maxLat)/2;val high=if(even)longitude>mid else latitude>mid;if(high){value=value or (16 shr bit);if(even)minLon=mid else minLat=mid}else if(even)maxLon=mid else maxLat=mid;even=!even;if(bit<4)bit++ else{out.append(alphabet[value]);bit=0;value=0}};return out.toString()}

    private suspend fun refreshWeather(point: GeoPoint, force: Boolean): NeuralProviderStatus {
        val cache = cacheFile("weather-${neuralPointCacheKey(point)}.json")
        val result = loadNeuralProvider(cache, "Open-Meteo", 30 * 60L, 3_000_000, force, fetch = {
            val params = "latitude=${point.latitude}&longitude=${point.longitude}" +
                "&current=temperature_2m,pressure_msl,relative_humidity_2m,wind_speed_10m,wind_direction_10m,precipitation,weather_code" +
                "&hourly=cape,temperature_850hPa,wind_speed_300hPa,wind_direction_300hPa,pressure_msl&forecast_days=1&timezone=UTC"
            readUrl("https://api.open-meteo.com/v1/forecast?$params")
        }, decode = ::decodeNeuralWeather)
        val parsed = result.value?.copy(
            updatedEpoch = result.status.updatedEpoch,
            error = result.status.detail,
            status = result.status,
        ) ?: NeuralWeather(error = result.status.detail, status = result.status)
        withContext(Dispatchers.Main) { weather = parsed }
        return result.status
    }

    private fun refreshMySignal(call: String, point: GeoPoint?, force: Boolean, generation: Int = pskGeneration) {
        val normalized = call.trim().uppercase(Locale.US)
        lastPskCall = normalized; lastPskPoint = point
        if (normalized.isBlank() || !pskPreference.enabled) {
            pskSnapshot = PskReporterSnapshot(normalized)
            publishPskSnapshot(pskSnapshot)
            return
        }
        val loaded = publicProviders.pskReporter.refresh(normalized, point, pskPreference.windowMinutes, force)
        if (generation != pskGeneration) return
        pskSnapshot = loaded
        publishPskSnapshot(pskSnapshot)
    }

    private fun refreshBriefing(force: Boolean) {
        val sharedFeed = publicProviders.dxpeditions.refresh(force)
        dxpeditionFeed = sharedFeed
        val snapshot = publicProviders.dxNews.refresh(sharedFeed, force)
        val ordered = briefingOrder.mapNotNull { key -> snapshot.sources.firstOrNull { it.id == key } } +
            snapshot.sources.filter { it.id !in briefingOrder }
        scope.launch(Dispatchers.Main) { dxNewsSnapshot = snapshot.copy(sources = ordered); briefing = ordered }
    }

    private fun publishPskSnapshot(snapshot: PskReporterSnapshot) {
        val enriched = snapshot.reports.map { report ->
            val distance = lastPskPoint?.let { local ->
                report.latitude?.let { latitude -> report.longitude?.let { longitude ->
                    greatCircleKm(local, GeoPoint(latitude, longitude)).roundToInt()
                } }
            }
            report.copy(continent = ctyController?.lookup(report.callsign)?.continent.orEmpty(), distanceKm = distance)
        }
        val rows = filterPskReports(enriched, pskPreference)
        val error = listOf(snapshot.beingHeard.error, snapshot.hearing.error).filter(String::isNotBlank).joinToString(" · ")
        val sourceState = neuralSignalSourceState(snapshot, rows.isNotEmpty())
        scope.launch(Dispatchers.Main) {
            mySignal = NeuralMySignal(
                available = snapshot.beingHeard.state != HamClockFeedState.UNAVAILABLE || snapshot.hearing.state != HamClockFeedState.UNAVAILABLE,
                callsign = snapshot.callsign,
                fetchedEpoch = maxOf(snapshot.beingHeard.fetchedEpoch, snapshot.hearing.fetchedEpoch),
                reports = rows,
                error = error,
                beingHeardState = snapshot.beingHeard.state,
                hearingState = snapshot.hearing.state,
                beingHeardCount = snapshot.beingHeard.reports.size,
                hearingCount = snapshot.hearing.reports.size,
                sourceState = sourceState,
                status = NeuralProviderStatus(
                    "PSK Reporter",
                    when (sourceState) {
                        NeuralSignalSourceState.CURRENT -> NeuralProviderState.LIVE
                        NeuralSignalSourceState.DEGRADED -> NeuralProviderState.STALE
                        NeuralSignalSourceState.ERROR, NeuralSignalSourceState.EMPTY -> NeuralProviderState.UNAVAILABLE
                    },
                    updatedEpoch = maxOf(snapshot.beingHeard.fetchedEpoch, snapshot.hearing.fetchedEpoch),
                    detail = error,
                ),
            )
        }
    }

    private suspend fun refreshSatellites(point: GeoPoint, force: Boolean): NeuralProviderStatus {
        satelliteOwner.refresh(force)
        return satelliteStatus
    }

    private fun fetchOrbitCatalogue(): String {
        error("Legacy Neural satellite transport disabled; SatelliteProviderRepository is authoritative")
    }

    private fun ensureSatelliteTicker(point: GeoPoint) = Unit

    private fun buildInsight(call: String, log: NeuralLogSummary, live: List<AndroidDXSpot>): NeuralInsight {
        val now = Instant.now().epochSecond; val hot = live.sortedByDescending { it.score }.take(5)
        val activeBands = bandActivity.entries.sortedByDescending { it.value }.take(4)
        val solarSentence = "Live cluster has ${live.size} ranked calls; ${activeBands.joinToString { "${it.key} ${it.value}" }.ifBlank { "history is still learning" }}."
        val logSentence = "Configured log: ${log.qsos} QSOs, ${log.calls} calls, ${log.dxccs} DXCC (${log.confirmedDxccs} confirmed by QSL/LoTW)."
        val weatherSentence = if (weather.available) "Weather: ${weather.temperatureC?.roundToInt()}°C, ${weather.pressureHpa?.roundToInt()} hPa, tropo ${weather.ductingRisk ?: "unknown"}." else "Weather context unavailable."
        val recommendations = buildList {
            hot.forEach { add("${it.callsign} · ${it.band} ${it.mode} · score ${it.score} · ${it.country}") }
            if (hot.isEmpty()) add("Keep the cluster connected while the local model builds a sample history.")
        }
        return NeuralInsight(now, "Tactical DX report for ${call.ifBlank { "this station" }}",
            "$solarSentence $logSentence $weatherSentence", listOf(logSentence, solarSentence, weatherSentence), recommendations, log)
    }

    private fun enrichInsight(base: NeuralInsight): NeuralInsight {
        val payload = JSONObject().put("model", "sonar-pro").put("max_tokens", 300).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are a concise amateur-radio propagation analyst. Never invent missing measurements."))
            .put(JSONObject().put("role", "user").put("content", base.report + " Opportunities: " + base.recommendations.joinToString())))
        val text = readUrl("https://api.perplexity.ai/chat/completions", 1_000_000, "POST", payload.toString(),
            mapOf("Authorization" to "Bearer $perplexityKey", "Content-Type" to "application/json"))
        val report = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        return if (report.isBlank()) base else base.copy(report = report, source = "PERPLEXITY SONAR-PRO + LOCAL")
    }

    private suspend fun refreshBeaconReference(point: GeoPoint, force: Boolean): NeuralProviderStatus {
        val file = cacheFile("beacons.csv")
        val result = loadNeuralProvider(file, "DL0TUD beacon list", 30L * 24L * 3600L, 4_000_000, force, fetch = {
            readUrl("https://dl0tud.tu-dresden.de/beacons/csv.php", 4_000_000)
        }, decode = { text -> parseBeaconCsv(text).also { require(it.size >= 10) { "Beacon reference response was incomplete" } } })
        val parsed = result.value
        if (parsed == null) {
            val visibleStatus = if (beaconReference.isNotEmpty()) {
                NeuralProviderStatus("DL0TUD beacon list", NeuralProviderState.STALE,
                    beaconProviderStatus.updatedEpoch, detail = result.status.detail)
            } else result.status
            withContext(Dispatchers.Main) { beaconProviderStatus = visibleStatus }
            return visibleStatus
        }
        val ranged = parsed.map { row ->
            val location = maidenheadCenter(row.locator)
            if (location == null) row else {
                val distance = greatCircleKm(point, location).roundToInt()
                row.copy(distanceKm = distance, bearing = compass(initialBearing(point, location)),
                    inTypicalRange = distance <= beaconRangeKm(row.band))
            }
        }.sortedWith(compareBy<BeaconReference> { it.distanceKm ?: Int.MAX_VALUE }.thenBy { it.band }.thenBy { it.callsign })
        atomicWriteNeuralText(cacheFile("beacons.json"), JSONArray(ranged.map { beaconToJson(it) }).toString(), result.status.updatedEpoch)
        withContext(Dispatchers.Main) {
            beaconReference = ranged
            beaconProviderStatus = result.status
        }
        return result.status
    }

    private fun parseBeaconCsv(text: String): List<BeaconReference> {
        val lines = text.lineSequence().filter(String::isNotBlank).toList(); if (lines.isEmpty()) return emptyList()
        val header = parseSemicolonRow(lines.first()).map { it.trim().lowercase(Locale.US) }
        fun index(name: String) = header.indexOf(name)
        val callIndex=index("call"); val freqIndex=index("qrg"); val locatorIndex=index("loc")
        val remarkIndex=index("rem"); val dateIndex=index("date")
        if (callIndex < 0 || freqIndex < 0 || locatorIndex < 0) return emptyList()
        val qrt = listOf("qrt", "license cancelled", "not qrv", "dismantled", "off air", "switched off", "permanently off")
        val byCall = linkedMapOf<String, BeaconReference>()
        lines.drop(1).forEach { line ->
            val cells=parseSemicolonRow(line); fun cell(i:Int)=cells.getOrNull(i)?.trim().orEmpty()
            val call=cell(callIndex).uppercase(Locale.US).replace(" ",""); val locator=cell(locatorIndex).uppercase(Locale.US).take(6)
            val mhz=cell(freqIndex).replace(',','.').toDoubleOrNull(); val remark=cell(remarkIndex); val date=cell(dateIndex)
            val band=mhz?.let(::beaconBand).orEmpty()
            if(call.isBlank()||mhz==null||locator.length<4||band.isBlank()||qrt.any{remark.contains(it,true)}) return@forEach
            val row=BeaconReference(call,band,mhz,locator,date)
            if(byCall[call]?.lastReport.orEmpty() < date) byCall[call]=row
        }
        return byCall.values.sortedWith(compareBy<BeaconReference>{it.band}.thenBy{it.frequencyMHz})
    }

    private fun parseSemicolonRow(line: String): List<String> {
        val out=mutableListOf<String>(); val cell=StringBuilder(); var quoted=false; var i=0
        while(i<line.length){val c=line[i];when{c=='"'&&quoted&&i+1<line.length&&line[i+1]=='"'->{cell.append('"');i++};c=='"'->quoted=!quoted;c==';'&&!quoted->{out+=cell.toString();cell.clear()};else->cell.append(c)};i++}
        out+=cell.toString();return out
    }

    private fun beaconBand(mhz: Double): String = when(mhz){in 50.0..54.0->"6m";in 70.0..71.0->"4m";in 144.0..148.0->"2m";in 430.0..440.0->"70cm";in 1240.0..1300.0->"23cm";in 2300.0..2450.0->"13cm";in 3300.0..3500.0->"9cm";in 5650.0..5850.0->"6cm";in 10000.0..10500.0->"3cm";in 24000.0..24250.0->"12mm";in 47000.0..47200.0->"6mm";in 75500.0..81000.0->"4mm";else->""}
    private fun beaconRangeKm(band:String)=mapOf("6m" to 3000,"4m" to 1500,"2m" to 800,"70cm" to 500,"23cm" to 300,"13cm" to 200,"9cm" to 150,"6cm" to 100,"3cm" to 80,"12mm" to 50,"6mm" to 30,"4mm" to 20)[band]?:1000
    private fun beaconToJson(row:BeaconReference)=JSONObject().put("call",row.callsign).put("band",row.band).put("frequency",row.frequencyMHz).put("locator",row.locator).put("report",row.lastReport).put("distance",row.distanceKm).put("bearing",row.bearing).put("range",row.inTypicalRange)

    private fun buildBeaconReception(rows: List<AndroidDXSpot>): List<BeaconReception> {
        val now = Instant.now().epochSecond
        val known = beaconReference.associateBy { it.callsign.substringBefore('/') }
        return rows.filter { it.band in setOf("6m", "4m", "2m", "70cm", "23cm", "13cm") &&
            (known.containsKey(it.callsign.substringBefore('/')) || it.callsign.contains("/B") || it.comment.contains("BEACON", true)) }
            .map { BeaconReception(it.callsign, it.band, it.frequencyHz, it.country,
                ((now - it.receivedEpoch).coerceAtLeast(0) / 60).toInt(), known.containsKey(it.callsign.substringBefore('/')), it.spotter) }
            .distinctBy { it.callsign }.take(40)
    }

    private fun deliverAlert(title: String, message: String, key: String, force: Boolean = false) {
        val cooldownKey = "alert_${key.hashCode()}"; val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(cooldownKey, 0L) < 15 * 60_000L) return
        prefs.edit().putLong(cooldownKey, now).apply()
        scope.launch(Dispatchers.Main) { alerts.add(0, "$title · $message"); while (alerts.size > 20) alerts.removeAt(alerts.lastIndex) }
        if (notificationsEnabled && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(key.hashCode(), NotificationCompat.Builder(context, NOTIFICATION_CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
        }
        if (ntfyUrl.startsWith("https://")) scope.launch { runCatching {
            readUrl(ntfyUrl, 100_000, "POST", message, buildMap { put("Title", title); put("Tags", "radio")
                if (ntfyToken.isNotBlank()) put("Authorization", "Bearer $ntfyToken") })
        } }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "Neural DX alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun parseBriefing(text: String, base: String): List<BriefingItem> {
        val blocks = Regex("<item[\\s\\S]*?</item>", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()
        val candidates = if (blocks.isNotEmpty()) blocks else Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
            .findAll(text).map { "<link>${it.groupValues[1]}</link><title>${it.groupValues[2]}</title>" }.toList()
        return candidates.mapNotNull { block ->
            val title = xmlValue(block, "title").htmlText(); if (title.length < 4) return@mapNotNull null
            var link = xmlValue(block, "link").htmlText().ifBlank { Regex("href=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1).orEmpty() }
            if (link.startsWith('/')) link = URL(URL(base), link).toString()
            val summary = (xmlValue(block, "description").ifBlank { title }).htmlText().take(360)
            val image = sequenceOf(
                Regex("<media:(?:content|thumbnail)[^>]+url=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1),
                Regex("<enclosure[^>]+url=[\"']([^\"']+)[\"'][^>]+type=[\"']image/", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1),
                Regex("<img[^>]+src=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1),
            ).filterNotNull().firstOrNull().orEmpty().let { raw ->
                when { raw.startsWith("https://", true) -> raw; raw.startsWith('/') -> runCatching { URL(URL(base), raw).toString() }.getOrDefault(""); else -> "" }
            }
            BriefingItem(title.take(180), link, xmlValue(block, "pubDate").htmlText(), summary, extractCallsigns("$title $summary"), image)
        }.distinctBy { it.title }.take(20)
    }

    private fun parseOrbits(text: String): List<OrbitRecord> {
        val rows = JSONArray(text); val out = mutableListOf<OrbitRecord>()
        for (i in 0 until rows.length()) runCatching {
            val r = rows.getJSONObject(i); val norad = r.optInt("NORAD_CAT_ID"); if (norad <= 0) return@runCatching
            val epoch = Instant.parse(r.getString("EPOCH")).epochSecond
            out += OrbitRecord(norad, r.optString("OBJECT_NAME", "SAT-$norad"), epoch, r.getDouble("INCLINATION"),
                r.getDouble("RA_OF_ASC_NODE"), r.getDouble("ECCENTRICITY"), r.getDouble("ARG_OF_PERICENTER"),
                r.getDouble("MEAN_ANOMALY"), r.getDouble("MEAN_MOTION"))
        }
        return out.distinctBy { it.norad }.sortedBy { it.name }
    }

    private fun satellitePosition(orbit: OrbitRecord, epoch: Long, observer: GeoPoint): SatellitePosition {
        val mu = 398600.4418; val earth = 6378.137; val n = orbit.meanMotion * 2.0 * Math.PI / 86400.0
        val a = cbrt(mu / (n * n)); val mean = Math.toRadians(orbit.meanAnomaly) + n * (epoch - orbit.epoch)
        var eccentric = mean
        repeat(8) { eccentric -= (eccentric - orbit.eccentricity * sin(eccentric) - mean) / (1 - orbit.eccentricity * cos(eccentric)) }
        val xOrb = a * (cos(eccentric) - orbit.eccentricity); val yOrb = a * sqrt(1 - orbit.eccentricity.pow(2)) * sin(eccentric)
        val arg = Math.toRadians(orbit.argumentPerigee); val inc = Math.toRadians(orbit.inclination); val raan = Math.toRadians(orbit.raan)
        val x1 = xOrb * cos(arg) - yOrb * sin(arg); val y1 = xOrb * sin(arg) + yOrb * cos(arg)
        val xEci = x1 * cos(raan) - y1 * cos(inc) * sin(raan); val yEci = x1 * sin(raan) + y1 * cos(inc) * cos(raan); val zEci = y1 * sin(inc)
        val theta = gmst(epoch); val x = xEci * cos(theta) + yEci * sin(theta); val y = -xEci * sin(theta) + yEci * cos(theta); val z = zEci
        val lon = Math.toDegrees(atan2(y, x)); val radius = sqrt(x*x + y*y + z*z); val lat = Math.toDegrees(asin(z / radius)); val alt = radius - earth
        val obsLat = Math.toRadians(observer.latitude); val obsLon = Math.toRadians(observer.longitude)
        val ox = earth * cos(obsLat) * cos(obsLon); val oy = earth * cos(obsLat) * sin(obsLon); val oz = earth * sin(obsLat)
        val dx=x-ox; val dy=y-oy; val dz=z-oz
        val east=-sin(obsLon)*dx+cos(obsLon)*dy; val north=-sin(obsLat)*cos(obsLon)*dx-sin(obsLat)*sin(obsLon)*dy+cos(obsLat)*dz
        val up=cos(obsLat)*cos(obsLon)*dx+cos(obsLat)*sin(obsLon)*dy+sin(obsLat)*dz; val range=sqrt(east*east+north*north+up*up)
        val az=(Math.toDegrees(atan2(east,north))+360)%360; val el=Math.toDegrees(asin(up/range)); val footprint=earth*acos((earth/(earth+max(0.0,alt))).coerceIn(-1.0,1.0))
        return SatellitePosition(orbit.norad, orbit.name, lat, lon, alt, az, el, range, footprint, el > 0, epoch)
    }

    private fun calculatePasses(orbit: OrbitRecord, observer: GeoPoint, start: Long, hours: Int): List<SatellitePass> {
        val out=mutableListOf<SatellitePass>(); var active=false; var aos=0L; var aosAz=0.0; var best=-90.0; var tca=0L; var previous=satellitePosition(orbit,start,observer)
        var t=start+60; val end=start+hours*3600L
        while(t<=end){ val p=satellitePosition(orbit,t,observer)
            if(!active && p.elevation>=0 && previous.elevation<0){ active=true; aos=t; aosAz=p.azimuth; best=p.elevation; tca=t }
            if(active && p.elevation>best){best=p.elevation;tca=t}
            if(active && p.elevation<0){out+=SatellitePass(orbit.norad,orbit.name,aos,tca,t,best,aosAz,p.azimuth);active=false}
            previous=p;t+=60
        }
        return out
    }

    private fun fetchTransmitters(norad: Int): List<SatelliteTransmitter> {
        return satelliteOwner.transpondersFor(norad.toLong()).map { row ->
            SatelliteTransmitter(row.description, frequencyLabel(row.uplinkLowHz ?: 0),
                frequencyLabel(row.downlinkLowHz ?: 0), row.mode, row.providerStatus)
        }
    }

    private fun readUrl(url: String, limit: Int = 3_000_000, method: String = "GET", body: String? = null,
        headers: Map<String,String> = emptyMap()): String {
        require(url.startsWith("https://"))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method; connection.connectTimeout=15_000; connection.readTimeout=30_000
            connection.instanceFollowRedirects=true; connection.setRequestProperty("User-Agent","ShackCQ-NeuralDX/12.1 Android")
            headers.forEach(connection::setRequestProperty)
            if(body!=null){connection.doOutput=true;connection.outputStream.use{it.write(body.toByteArray())}}
            val code=connection.responseCode; if(code !in 200..299) error("HTTP $code")
            if(connection.contentLengthLong>limit) error("Response too large")
            val bytes=connection.inputStream.use{it.readBoundedBytes(limit+1)}; if(bytes.size>limit) error("Response too large")
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun briefingSources() = listOf(
        Triple("dxworld", "DX-World", "https://www.dx-world.net/feed/"),
        Triple("dxnews", "DXNews", "https://dxnews.com/"),
        Triple("ng3k", "NG3K ADXO", "https://www.ng3k.com/Misc/adxoplain.html"),
        Triple("qo100", "QO-100 DX Club", "https://qo100dx.club/news"),
    )

    private fun loadWeatherCache(point: GeoPoint): NeuralWeather {
        val result = loadNeuralProvider(cacheFile("weather-${neuralPointCacheKey(point)}.json"), "Open-Meteo",
            30 * 60L, 3_000_000, false, fetch = null, decode = ::decodeNeuralWeather)
        return result.value?.copy(updatedEpoch = result.status.updatedEpoch, error = result.status.detail, status = result.status)
            ?: NeuralWeather(error = result.status.detail, status = result.status)
    }

    private fun loadBriefingCache(): List<BriefingSource> {
        val combined = loadCombinedBriefingCache().associateBy(BriefingSource::id)
        val loaded = briefingSources().map { (id, name, url) ->
            val result = loadNeuralProvider(cacheFile("brief-$id.txt"), name, 12 * 3600L, 2_000_000, false,
                fetch = null,
                decode = { text -> parseBriefing(text, url).also { require(it.isNotEmpty()) { "No valid briefing items" } } },
            )
            if (result.value != null) {
                BriefingSource(id, name, URL(url).host, result.value, result.status.updatedEpoch,
                    stale = result.status.state == NeuralProviderState.STALE, error = result.status.detail,
                    state = result.status.state.toHamClockFeedState())
            } else {
                combined[id] ?: BriefingSource(id, name, URL(url).host,
                    error = result.status.detail, state = result.status.state.toHamClockFeedState())
            }
        }
        val order = loadBriefingOrder()
        return order.mapNotNull { id -> loaded.firstOrNull { it.id == id } } + loaded.filter { it.id !in order }
    }

    private fun loadCombinedBriefingCache(): List<BriefingSource> = runCatching {
        val file=cacheFile("briefing.json"); if(!file.exists()) return@runCatching emptyList()
        val rows=JSONArray(file.readText());buildList{for(i in 0 until rows.length()){val r=rows.getJSONObject(i);val items=r.optJSONArray("items")?:JSONArray()
            val updated=r.optLong("updated");val name=r.optString("name");val storedState=runCatching{NeuralProviderState.valueOf(r.optString("state"))}.getOrDefault(NeuralProviderState.STALE)
            val state=if(storedState==NeuralProviderState.UNAVAILABLE)storedState else NeuralProviderState.STALE
            val providerStatus=NeuralProviderStatus(name,state,updated,r.optLong("expires"),r.optString("detail"))
            add(BriefingSource(r.optString("id"),name,r.optString("site"),buildList{for(j in 0 until items.length()){val q=items.getJSONObject(j);add(BriefingItem(q.optString("title"),q.optString("link"),q.optString("published"),q.optString("summary"),extractCallsigns(q.optString("title")+" "+q.optString("summary")),q.optString("image")))}},updated,
                stale = providerStatus.state == NeuralProviderState.STALE, error = providerStatus.detail,
                state = providerStatus.state.toHamClockFeedState()))}}
    }.getOrDefault(emptyList())

    private fun loadOrbitCache(): NeuralProviderResult<List<OrbitRecord>> = loadNeuralProvider(
        cacheFile("satellites.json"), "CelesTrak / AMSAT", 2 * 3600L, 5_000_000, false, fetch = null,
        decode = { text -> parseOrbits(text).also { require(it.isNotEmpty()) { "No valid orbital elements" } } },
    )

    private fun loadMySignalCache():NeuralMySignal=runCatching{val f=cacheFile("my-signal.json");if(!f.exists())return@runCatching NeuralMySignal();val root=JSONObject(f.readText());val rows=root.getJSONArray("reports");val updated=root.optLong("fetched").takeIf{it>0}?:f.lastModified()/1000;val providerStatus=NeuralProviderStatus("PSK Reporter",NeuralProviderState.STALE,updated,detail="Stored display awaiting callsign and QTH revalidation");NeuralMySignal(true,root.optString("call"),updated,buildList{for(i in 0 until rows.length()){val r=rows.getJSONObject(i);add(SignalReport(r.optString("call"),r.optString("locator"),r.optionalFiniteDouble("lat"),r.optionalFiniteDouble("lon"),r.optLong("frequency"),r.optString("band"),r.optString("mode"),r.optInt("snr").takeIf{r.has("snr")&&!r.isNull("snr")},r.optInt("distance").takeIf{r.has("distance")&&!r.isNull("distance")},r.optLong("epoch")))}} ,status=providerStatus,error=providerStatus.detail)}.getOrDefault(NeuralMySignal())
    private fun loadBeaconReference(): Pair<List<BeaconReference>,NeuralProviderStatus> = runCatching {
        val file=cacheFile("beacons.json");if(!file.exists()) return@runCatching emptyList<BeaconReference>() to NeuralProviderStatus("DL0TUD beacon list",NeuralProviderState.UNAVAILABLE,detail="No cached data")
        val rows=JSONArray(file.readText());val values=buildList{for(i in 0 until rows.length()){val r=rows.getJSONObject(i);add(BeaconReference(
            r.optString("call"),r.optString("band"),r.optDouble("frequency"),r.optString("locator"),r.optString("report"),
            r.optInt("distance").takeIf{r.has("distance")&&!r.isNull("distance")},r.optString("bearing"),r.optBoolean("range")))}}
        val updated=file.lastModified()/1000;values to NeuralProviderStatus("DL0TUD beacon list",NeuralProviderState.STALE,updated,detail="Stored display awaiting QTH recalculation")
    }.getOrElse { emptyList<BeaconReference>() to NeuralProviderStatus("DL0TUD beacon list",NeuralProviderState.UNAVAILABLE,detail=safeError(it)) }
    private fun loadBriefingOrder(): List<String> {
        val canonical=listOf("dxworld","dxnews","ng3k")
        val saved=prefs.getString("briefing_order","").orEmpty().split(',').filter{it in canonical}.distinct()
        return saved + canonical.filter{it !in saved}
    }
    private fun sourceToJson(s:BriefingSource)=JSONObject().put("id",s.id).put("name",s.name).put("site",s.site).put("updated",s.updatedEpoch).put("state",s.state.name).put("detail",s.error).put("items",JSONArray(s.items.map{JSONObject().put("title",it.title).put("link",it.link).put("published",it.published).put("summary",it.summary).put("image",it.imageUrl)}))
    private fun orbitToJson(r:OrbitRecord)=JSONObject().put("NORAD_CAT_ID",r.norad).put("OBJECT_NAME",r.name)
        .put("EPOCH",Instant.ofEpochSecond(r.epoch).toString()).put("INCLINATION",r.inclination).put("RA_OF_ASC_NODE",r.raan)
        .put("ECCENTRICITY",r.eccentricity).put("ARG_OF_PERICENTER",r.argumentPerigee).put("MEAN_ANOMALY",r.meanAnomaly).put("MEAN_MOTION",r.meanMotion)
    private fun signalToJson(r:SignalReport)=JSONObject().put("call",r.callsign).put("locator",r.locator).put("lat",r.latitude).put("lon",r.longitude).put("frequency",r.frequencyHz).put("band",r.band).put("mode",r.mode).put("snr",r.snr).put("distance",r.distanceKm).put("epoch",r.epoch)
    private fun cacheFile(name:String)=File(cacheDir,name)
    private fun xmlValue(block:String,tag:String)=Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>",RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1).orEmpty().removePrefix("<![CDATA[").removeSuffix("]]>")
    private fun String.htmlText()=decodeHtmlText(this)
    private fun updateRequestedProviderStatus(updated: NeuralProviderStatus) {
        if (providerStatuses.none { it.source == updated.source }) return
        providerStatuses = providerStatuses.map { if (it.source == updated.source) updated else it }
        status = neuralProviderSummary(providerStatuses)
    }
    private fun safeError(error:Throwable)=when(error){is java.net.SocketTimeoutException->"Timed out";is java.net.UnknownHostException->"Offline";else->error.message?.takeIf{!it.contains("http",true)}?:"Unavailable"}
    private fun readSecret(name: String): String {
        val stored = prefs.getString(name, "").orEmpty()
        if (stored.isBlank()) return ""
        if (stored.startsWith("v1:")) return decrypt(stored.removePrefix("v1:"))
        // One-time migration from early development builds that stored these optional values as plain text.
        prefs.edit().putString(name, encrypt(stored)).apply()
        return stored
    }
    private fun secret(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(SECRET_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(SECRET_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String = if (value.isBlank()) "" else runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret())
        "v1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault("")
    private fun decrypt(value: String): String = if (value.isBlank()) "" else runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP); require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }.getOrDefault("")
    private fun decodeIds(raw:String?)=raw?.split(',')?.mapNotNull(String::toIntOrNull)?.distinct()?.take(24)?.takeIf(List<Int>::isNotEmpty)?:listOf(25544,39444,43017,27607,44909,24278,43678)
    private fun frequencyLabel(hz:Long)=if(hz<=0)"—" else "%.6f MHz".format(Locale.US,hz/1_000_000.0)
    private fun frequencyBand(hz:Long)=bandForFrequency(hz).ifBlank{"—"}
    private fun greatCircleKm(a:GeoPoint,b:GeoPoint):Double{val p1=Math.toRadians(a.latitude);val p2=Math.toRadians(b.latitude);val dp=p2-p1;val dl=Math.toRadians(b.longitude-a.longitude);val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return 6371.0*2*atan2(sqrt(h),sqrt(1-h))}
    private fun initialBearing(a:GeoPoint,b:GeoPoint):Double{val p1=Math.toRadians(a.latitude);val p2=Math.toRadians(b.latitude);val dl=Math.toRadians(b.longitude-a.longitude);return(Math.toDegrees(atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)))+360)%360}
    private fun compass(degrees:Double)=listOf("N","NE","E","SE","S","SW","W","NW")[((degrees+22.5)/45).toInt()%8]
    private fun gmst(epoch:Long):Double{val jd=epoch/86400.0+2440587.5;val d=jd-2451545.0;return Math.toRadians((280.46061837+360.98564736629*d)%360.0)}

    companion object {
        private const val NOTIFICATION_CHANNEL="neural_dx_alerts"
        private const val SECRET_ALIAS="app.shackcq.mobile.neural_dx"
    }
}
