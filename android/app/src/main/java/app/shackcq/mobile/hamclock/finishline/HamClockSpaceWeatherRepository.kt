package app.shackcq.mobile.hamclock.finishline

import app.shackcq.mobile.hamclock.HamClockHttpClient
import app.shackcq.mobile.hamclock.HamClockHttpRequest
import app.shackcq.mobile.hamclock.HamClockLastGoodCache
import app.shackcq.mobile.hamclock.HamClockUrlConnectionClient
import app.shackcq.mobile.hamclock.providerError
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal enum class HamClockProviderTruth { CURRENT, CACHED, STALE, ERROR, UNAVAILABLE }

internal data class HamClockSpaceWeatherSnapshot(
    val solarWindSpeedKmS: Double? = null,
    val imfBzNt: Double? = null,
    val protonFluxPfu: Double? = null,
    val radiationState: String = "",
    val alerts: List<String> = emptyList(),
    val observedAtEpoch: Long = 0,
    val fetchedAtEpoch: Long = 0,
    val truth: HamClockProviderTruth = HamClockProviderTruth.UNAVAILABLE,
    val error: String = "",
) {
    val source = "NOAA SWPC solar-wind, GOES proton, and alert products"
}

internal data class HamClockAuroraCell(val latitude: Double, val longitude: Double, val probability: Int)

internal data class HamClockAuroraSnapshot(
    val cells: List<HamClockAuroraCell> = emptyList(),
    val forecastAtEpoch: Long = 0,
    val observedAtEpoch: Long = 0,
    val fetchedAtEpoch: Long = 0,
    val truth: HamClockProviderTruth = HamClockProviderTruth.UNAVAILABLE,
    val error: String = "",
) {
    val source = "NOAA SWPC OVATION Aurora Forecast"
}

internal class HamClockSpaceWeatherRepository(
    cacheDirectory: File,
    private val http: HamClockHttpClient = HamClockUrlConnectionClient(),
) {
    private val weatherCache = HamClockLastGoodCache(cacheDirectory, "finishline-space-weather")
    private val auroraCache = HamClockLastGoodCache(cacheDirectory, "finishline-aurora")

    fun cached(nowEpoch: Long = Instant.now().epochSecond): Pair<HamClockSpaceWeatherSnapshot, HamClockAuroraSnapshot> =
        cachedWeather(nowEpoch) to cachedAurora(nowEpoch)

    fun refresh(nowEpoch: Long = Instant.now().epochSecond): Pair<HamClockSpaceWeatherSnapshot, HamClockAuroraSnapshot> =
        refreshWeather(nowEpoch) to refreshAurora(nowEpoch)

    private fun refreshWeather(nowEpoch: Long): HamClockSpaceWeatherSnapshot = runCatching {
        val plasma = http.get(HamClockHttpRequest(PLASMA_URL, "application/json", 1_500_000)).body
        val magnetic = http.get(HamClockHttpRequest(MAG_URL, "application/json", 1_500_000)).body
        val protons = http.get(HamClockHttpRequest(PROTON_URL, "application/json", 3_000_000)).body
        val alerts = http.get(HamClockHttpRequest(ALERTS_URL, "application/json", 1_500_000)).body
        val combined = JSONObject().put("plasma", JSONArray(plasma)).put("magnetic", JSONArray(magnetic))
            .put("protons", JSONArray(protons)).put("alerts", JSONArray(alerts))
        val parsed = parseSpaceWeather(combined, nowEpoch)
        weatherCache.write(combined.toString(), nowEpoch, "", "")
        parsed.copy(fetchedAtEpoch = nowEpoch, truth = HamClockProviderTruth.CURRENT)
    }.getOrElse { failure ->
        val cached = cachedWeather(nowEpoch)
        if (cached.truth != HamClockProviderTruth.UNAVAILABLE) cached.copy(error = providerError(failure))
        else HamClockSpaceWeatherSnapshot(truth = HamClockProviderTruth.ERROR, error = providerError(failure))
    }

    private fun refreshAurora(nowEpoch: Long): HamClockAuroraSnapshot = runCatching {
        val body = http.get(HamClockHttpRequest(AURORA_URL, "application/json", 5_000_000)).body
        val parsed = parseAurora(JSONObject(body), nowEpoch)
        require(parsed.cells.isNotEmpty()) { "NOAA returned no bounded aurora cells" }
        auroraCache.write(body, nowEpoch, "", "")
        parsed.copy(fetchedAtEpoch = nowEpoch, truth = HamClockProviderTruth.CURRENT)
    }.getOrElse { failure ->
        val cached = cachedAurora(nowEpoch)
        if (cached.truth != HamClockProviderTruth.UNAVAILABLE) cached.copy(error = providerError(failure))
        else HamClockAuroraSnapshot(truth = HamClockProviderTruth.ERROR, error = providerError(failure))
    }

    private fun cachedWeather(nowEpoch: Long): HamClockSpaceWeatherSnapshot = weatherCache.read()?.let { entry ->
        runCatching { parseSpaceWeather(JSONObject(entry.body), entry.fetchedAtEpoch) }.getOrNull()?.copy(
            fetchedAtEpoch = entry.fetchedAtEpoch,
            truth = if (nowEpoch - entry.fetchedAtEpoch <= WEATHER_TTL_SECONDS) HamClockProviderTruth.CACHED else HamClockProviderTruth.STALE,
        )
    } ?: HamClockSpaceWeatherSnapshot()

    private fun cachedAurora(nowEpoch: Long): HamClockAuroraSnapshot = auroraCache.read()?.let { entry ->
        runCatching { parseAurora(JSONObject(entry.body), entry.fetchedAtEpoch) }.getOrNull()?.copy(
            fetchedAtEpoch = entry.fetchedAtEpoch,
            truth = if (nowEpoch - entry.fetchedAtEpoch <= AURORA_TTL_SECONDS) HamClockProviderTruth.CACHED else HamClockProviderTruth.STALE,
        )
    } ?: HamClockAuroraSnapshot()

    private companion object {
        const val PLASMA_URL = "https://services.swpc.noaa.gov/products/summary/solar-wind-speed.json"
        const val MAG_URL = "https://services.swpc.noaa.gov/products/summary/solar-wind-mag-field.json"
        const val PROTON_URL = "https://services.swpc.noaa.gov/json/goes/primary/integral-protons-3-day.json"
        const val ALERTS_URL = "https://services.swpc.noaa.gov/products/alerts.json"
        const val AURORA_URL = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json"
        const val WEATHER_TTL_SECONDS = 15 * 60L
        const val AURORA_TTL_SECONDS = 30 * 60L
    }
}

internal fun parseSpaceWeather(root: JSONObject, fetchedAtEpoch: Long): HamClockSpaceWeatherSnapshot {
    fun latestSummary(rows: JSONArray, field: String): Pair<Long, Double>? {
        require(rows.length() in 1..120) { "NOAA summary size is invalid" }
        return (0 until rows.length()).asSequence().mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val value = row.optDouble(field).takeIf(Double::isFinite) ?: return@mapNotNull null
            val time = row.optString("time_tag")
            val epoch = runCatching { Instant.parse(time.replace(' ', 'T') + if (time.endsWith("Z")) "" else "Z").epochSecond }.getOrNull()
                ?: return@mapNotNull null
            epoch to value
        }.maxByOrNull(Pair<Long, Double>::first)
    }
    val plasma = latestSummary(root.getJSONArray("plasma"), "proton_speed")
    val magnetic = latestSummary(root.getJSONArray("magnetic"), "bz_gsm")
    val protonRows = root.getJSONArray("protons")
    require(protonRows.length() <= 20_000) { "NOAA proton response is too large" }
    val proton = (0 until protonRows.length()).asSequence().mapNotNull { index ->
        val row = protonRows.optJSONObject(index) ?: return@mapNotNull null
        if (!row.optString("energy").contains(">=10")) return@mapNotNull null
        val flux = row.optDouble("flux").takeIf(Double::isFinite) ?: return@mapNotNull null
        val epoch = runCatching { Instant.parse(row.getString("time_tag")).epochSecond }.getOrNull() ?: return@mapNotNull null
        epoch to flux
    }.maxByOrNull(Pair<Long, Double>::first)
    val alertRows = root.getJSONArray("alerts")
    require(alertRows.length() <= 2_000) { "NOAA alert response is too large" }
    val alerts = (0 until alertRows.length()).asSequence().mapNotNull { alertRows.optJSONObject(it) }
        .map { it.optString("message").lineSequence().firstOrNull(String::isNotBlank).orEmpty().take(180) }
        .filter(String::isNotBlank).distinct().take(8).toList()
    val flux = proton?.second
    return HamClockSpaceWeatherSnapshot(
        solarWindSpeedKmS = plasma?.second, imfBzNt = magnetic?.second, protonFluxPfu = flux,
        radiationState = when { flux == null -> ""; flux >= 100_000 -> "S5"; flux >= 10_000 -> "S4"; flux >= 1_000 -> "S3"; flux >= 100 -> "S2"; flux >= 10 -> "S1"; else -> "Below S1" },
        alerts = alerts, observedAtEpoch = listOfNotNull(plasma?.first, magnetic?.first, proton?.first).maxOrNull() ?: 0,
        fetchedAtEpoch = fetchedAtEpoch,
    )
}

internal fun parseAurora(root: JSONObject, fetchedAtEpoch: Long): HamClockAuroraSnapshot {
    val coordinates = root.getJSONArray("coordinates")
    require(coordinates.length() in 1..200_000) { "NOAA aurora geometry size is invalid" }
    val stride = (coordinates.length() / 720).coerceAtLeast(1)
    val cells = buildList {
        for (index in 0 until coordinates.length() step stride) {
            val row = coordinates.optJSONArray(index) ?: continue
            val longitude = row.optDouble(0)
            val latitude = row.optDouble(1)
            val probability = row.optInt(2)
            if (longitude.isFinite() && longitude in -180.0..360.0 && latitude.isFinite() && latitude in -90.0..90.0 && probability in 1..100) {
                add(HamClockAuroraCell(latitude, if (longitude > 180) longitude - 360 else longitude, probability))
                if (size == 720) break
            }
        }
    }
    fun epoch(key: String) = runCatching { Instant.parse(root.optString(key).replace(' ', 'T') + if (root.optString(key).endsWith("Z")) "" else "Z").epochSecond }.getOrDefault(0)
    return HamClockAuroraSnapshot(cells, epoch("Forecast Time"), epoch("Observation Time"), fetchedAtEpoch)
}
