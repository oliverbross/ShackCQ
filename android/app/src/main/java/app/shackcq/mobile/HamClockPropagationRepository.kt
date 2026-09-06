package app.shackcq.mobile

import android.content.Context
import app.shackcq.mobile.hamclock.HamClockInFlightCoalescer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant

internal data class HamClockPropagationBand(
    val band: String,
    val frequencyMHz: Double,
    val reliability: Int,
    val snr: String,
    val status: String,
)

internal enum class HamClockPropagationState { LIVE, CACHED, STALE, UNAVAILABLE }

internal data class HamClockPropagationSnapshot(
    val model: String = "",
    val engine: String = "",
    val source: String = "",
    val mufMHz: Double? = null,
    val lufMHz: Double? = null,
    val distanceKm: Int? = null,
    val bands: List<HamClockPropagationBand> = emptyList(),
    val fetchedAt: Long = 0,
    val state: HamClockPropagationState = HamClockPropagationState.UNAVAILABLE,
    val error: String = "",
) {
    val available get() = bands.isNotEmpty()
    val cached get() = state == HamClockPropagationState.CACHED || state == HamClockPropagationState.STALE
    val authoritative get() = model.contains("P.533", true) || engine.contains("p533", true) || engine == "rest"
}

internal class HamClockPropagationRepository(context: Context) {
    private val cache = File(context.applicationContext.filesDir, "hamclock-propagation.json")

    suspend fun prediction(de: GeoPoint, dx: GeoPoint, mode: String, powerW: Int,
        antenna: String = "isotropic", force: Boolean = false): HamClockPropagationSnapshot = withContext(Dispatchers.IO) {
        val now = Instant.now().epochSecond
        val key = listOf("%.2f".format(de.latitude), "%.2f".format(de.longitude), "%.2f".format(dx.latitude),
            "%.2f".format(dx.longitude), mode.uppercase(), powerW.coerceIn(1, 1_500), antenna).joinToString("|")
        val previous = readCache()?.takeIf { it.first == key }?.second
        if (!force && previous != null && now - previous.fetchedAt < 10 * 60) {
            return@withContext previous.copy(state = HamClockPropagationState.CACHED)
        }
        runCatching {
            requests.run("propagation:$key") {
                val query = linkedMapOf(
                    "deLat" to de.latitude.toString(), "deLon" to de.longitude.toString(),
                    "dxLat" to dx.latitude.toString(), "dxLon" to dx.longitude.toString(),
                    "mode" to mode.uppercase(), "power" to powerW.coerceIn(1, 1_500).toString(), "antenna" to antenna,
                ).entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
                val connection = URL("https://openhamclock.com/api/propagation?$query").openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000; connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "ShackCQ/0.1 Android OpenHamClock integration")
                connection.setRequestProperty("Accept", "application/json")
                try {
                    require(connection.responseCode in 200..299) { "Propagation HTTP ${connection.responseCode}" }
                    require(connection.contentLengthLong <= MAX_RESPONSE_BYTES) { "Propagation response is too large" }
                    val bytes = connection.inputStream.use { it.readBoundedBytes(MAX_RESPONSE_BYTES + 1) }
                    require(bytes.size <= MAX_RESPONSE_BYTES) { "Propagation response is too large" }
                    val raw = bytes.toString(Charsets.UTF_8)
                    parseHamClockPropagation(raw, now).also { writeCache(key, raw, now) }
                } finally {
                    connection.disconnect()
                }
            }
        }.getOrElse { failure ->
            previous?.copy(state = HamClockPropagationState.STALE, error = "Refresh failed · ${failure.message ?: "unavailable"}")
                ?: HamClockPropagationSnapshot(error = failure.message ?: "Propagation unavailable")
        }
    }

    private fun writeCache(key: String, raw: String, fetchedAt: Long) {
        val temp = File(cache.parentFile, "${cache.name}.tmp")
        temp.writeText(JSONObject().put("key", key).put("fetched", fetchedAt).put("payload", JSONObject(raw)).toString())
        if (!temp.renameTo(cache)) { cache.writeText(temp.readText()); temp.delete() }
    }

    private fun readCache(): Pair<String, HamClockPropagationSnapshot>? = runCatching {
        val root = JSONObject(cache.readText())
        root.getString("key") to parseHamClockPropagation(root.getJSONObject("payload").toString(), root.getLong("fetched"))
    }.getOrNull()

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_000_000
        val requests = HamClockInFlightCoalescer()
    }
}

internal fun parseHamClockPropagation(raw: String, fetchedAt: Long): HamClockPropagationSnapshot {
    require(raw.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Propagation response is too large" }
    val root = JSONObject(raw)
    val rows = root.getJSONArray("currentBands")
    require(rows.length() in 1..64) { "Propagation response contained an invalid band count" }
    val bands = buildList(rows.length()) {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val band = row.getString("band").trim()
            val frequency = row.getDouble("freq")
            val reliability = row.getInt("reliability")
            val snr = row.getString("snr").trim()
            val status = row.getString("status").trim()
            require(band.isNotEmpty() && band.length <= 16) { "Propagation band is invalid" }
            require(frequency.isFinite() && frequency in 0.01..1_000.0) { "Propagation frequency is invalid" }
            require(reliability in 0..100) { "Propagation reliability is invalid" }
            require(snr.length <= 32 && status.isNotEmpty() && status.length <= 32) { "Propagation band labels are invalid" }
            add(HamClockPropagationBand(band, frequency, reliability, snr, status))
        }
    }
    val itu = root.optJSONObject("iturhfprop")
    return HamClockPropagationSnapshot(
        model = root.optString("model"),
        engine = root.optString("engine").ifBlank { if (itu?.optBoolean("available") == true) "rest" else "heuristic" },
        source = root.optString("dataSource"),
        mufMHz = root.optionalDouble("muf"), lufMHz = root.optionalDouble("luf"),
        distanceKm = root.optInt("distance").takeIf { it > 0 }, bands = bands, fetchedAt = fetchedAt,
        state = HamClockPropagationState.LIVE,
    )
}

private fun JSONObject.optionalDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf(Double::isFinite) else null
