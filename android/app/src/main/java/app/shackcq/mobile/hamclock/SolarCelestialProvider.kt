package app.shackcq.mobile.hamclock

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

internal class SolarCelestialProvider(
    cacheDirectory: File,
    private val http: HamClockHttpClient = HamClockUrlConnectionClient(),
    private val coalescer: HamClockInFlightCoalescer = HamClockInFlightCoalescer(),
) {
    private val cache = HamClockLastGoodCache(cacheDirectory, "goes-xray")

    fun cached(
        latitude: Double,
        longitude: Double,
        nowEpoch: Long = Instant.now().epochSecond,
    ): HamClockFeed<HamClockSolarCelestialSnapshot> {
        val entry = cache.read()
        val xray = entry?.let { runCatching { xrayFromJson(it.body) }.getOrNull() } ?: HamClockXraySeries()
        val value = solarCelestialSnapshot(latitude, longitude, nowEpoch, xray)
        return if (entry == null) HamClockFeed(value, HamClockFeedState.UNAVAILABLE, SOURCES, error = "No saved GOES X-ray data; celestial calculations remain available")
        else HamClockFeed(value, if (nowEpoch - entry.fetchedAtEpoch <= STALE_SECONDS) HamClockFeedState.CACHED else HamClockFeedState.STALE,
            SOURCES, entry.fetchedAtEpoch)
    }

    fun refresh(
        latitude: Double,
        longitude: Double,
        force: Boolean = false,
        nowEpoch: Long = Instant.now().epochSecond,
    ): HamClockFeed<HamClockSolarCelestialSnapshot> {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Station coordinates are invalid" }
        val xray = refreshXray(force, nowEpoch)
        return HamClockFeed(
            solarCelestialSnapshot(latitude, longitude, nowEpoch, xray.value),
            xray.state,
            xray.source,
            xray.fetchedAtEpoch,
            xray.error,
        )
    }

    private fun refreshXray(
        force: Boolean,
        nowEpoch: Long,
    ): HamClockFeed<HamClockXraySeries> = coalescer.run("solar-goes-xray") {
        val saved = cache.read()
        if (!force && saved != null && nowEpoch - saved.fetchedAtEpoch < TTL_SECONDS) {
            val xray = runCatching { xrayFromJson(saved.body) }.getOrNull()
            if (xray != null) return@run HamClockFeed(
                xray, HamClockFeedState.CACHED, SOURCES, saved.fetchedAtEpoch
            )
        }
        try {
            val response = try {
                http.get(HamClockHttpRequest(XRAY_URL, "application/json", MAX_BYTES, saved?.etag.orEmpty(), saved?.lastModified.orEmpty()))
            } catch (_: HamClockNotModified) {
                val entry = requireNotNull(saved)
                val xray = xrayFromJson(entry.body)
                cache.write(entry.body, nowEpoch, entry.etag, entry.lastModified)
                return@run HamClockFeed(xray, HamClockFeedState.LIVE, SOURCES, nowEpoch)
            }
            val xray = parseGoesXray(response.body, nowEpoch)
            require(xray.points.isNotEmpty()) { "NOAA returned no usable primary GOES X-ray observations" }
            cache.write(xrayToJson(xray), nowEpoch, response.etag, response.lastModified)
            HamClockFeed(xray, HamClockFeedState.LIVE, SOURCES, nowEpoch)
        } catch (error: Exception) {
            val fallback = saved?.let { runCatching { xrayFromJson(it.body) }.getOrNull() }
            if (fallback != null) HamClockFeed(
                fallback, HamClockFeedState.STALE, SOURCES, saved.fetchedAtEpoch, providerError(error)
            ) else HamClockFeed(
                HamClockXraySeries(), HamClockFeedState.UNAVAILABLE, SOURCES,
                error = providerError(error) + "; celestial calculations remain available"
            )
        }
    }

    private companion object {
        const val XRAY_URL = "https://services.swpc.noaa.gov/json/goes/primary/xrays-3-day.json"
        const val SOURCES = "NOAA SWPC GOES · NASA SDO · local astronomy"
        const val MAX_BYTES = 5_000_000
        const val TTL_SECONDS = 5 * 60L
        const val STALE_SECONDS = 6 * 60 * 60L
    }
}

internal fun parseGoesXray(json: String, nowEpoch: Long): HamClockXraySeries {
    val cutoff = nowEpoch - 50 * 60 * 60
    val array = JSONArray(json)
    val points = buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            if (row.optString("energy") != "0.1-0.8nm") continue
            val epoch = runCatching { Instant.parse(row.optString("time_tag")).epochSecond }.getOrNull() ?: continue
            val flux = row.optDouble("flux", Double.NaN)
            if (epoch < cutoff || epoch > nowEpoch + 300 || !flux.isFinite() || flux <= 0.0) continue
            add(HamClockXrayPoint(epoch, flux, row.optInt("satellite").takeIf { row.has("satellite") }))
        }
    }.distinctBy(HamClockXrayPoint::epoch).sortedBy(HamClockXrayPoint::epoch).takeLast(3_100)
    return HamClockXraySeries(points, goesClass(points.lastOrNull()?.fluxWattsPerSquareMetre),
        goesClass(points.maxOfOrNull(HamClockXrayPoint::fluxWattsPerSquareMetre)))
}

internal fun moonSnapshot(nowEpoch: Long): HamClockMoonSnapshot {
    val synodicDays = 29.530588853
    val referenceNewMoon = Instant.parse("2000-01-06T18:14:00Z").epochSecond
    val elapsedDays = (nowEpoch - referenceNewMoon) / 86_400.0
    val phase = ((elapsedDays / synodicDays) - floor(elapsedDays / synodicDays)).let { if (it < 0) it + 1 else it }
    val illumination = (1.0 - cos(2.0 * PI * phase)) / 2.0
    val name = when {
        phase < 0.03125 || phase >= 0.96875 -> HamClockMoonPhaseName.NEW
        phase < 0.21875 -> HamClockMoonPhaseName.WAXING_CRESCENT
        phase < 0.28125 -> HamClockMoonPhaseName.FIRST_QUARTER
        phase < 0.46875 -> HamClockMoonPhaseName.WAXING_GIBBOUS
        phase < 0.53125 -> HamClockMoonPhaseName.FULL
        phase < 0.71875 -> HamClockMoonPhaseName.WANING_GIBBOUS
        phase < 0.78125 -> HamClockMoonPhaseName.LAST_QUARTER
        else -> HamClockMoonPhaseName.WANING_CRESCENT
    }
    return HamClockMoonSnapshot(phase, illumination, phase * synodicDays, name)
}

internal fun sunTimes(date: LocalDate, latitude: Double, longitude: Double): HamClockSunTimes {
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
    val rise = solarEventUtcHours(date, latitude, longitude, sunrise = true)
    val set = solarEventUtcHours(date, latitude, longitude, sunrise = false)
    val state = when {
        rise == SolarEvent.POLAR_NIGHT || set == SolarEvent.POLAR_NIGHT -> HamClockDaylightState.POLAR_NIGHT
        rise == SolarEvent.MIDNIGHT_SUN || set == SolarEvent.MIDNIGHT_SUN -> HamClockDaylightState.MIDNIGHT_SUN
        else -> HamClockDaylightState.NORMAL
    }
    val midnight = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    return HamClockSunTimes(date.toString(), (rise as? SolarEvent.Time)?.hours?.let { midnight + (it * 3600).toLong() },
        (set as? SolarEvent.Time)?.hours?.let { midnight + (it * 3600).toLong() }, state)
}

internal fun solarImageMetadata(nowEpoch: Long): List<HamClockSolarImage> {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(nowEpoch)).replace(Regex("\\.\\d+Z$"), "Z")
    fun helioviewer(layer: String) = "https://api.helioviewer.org/v2/takeScreenshot/?date=" +
        URLEncoder.encode(timestamp, Charsets.UTF_8.name()) + "&imageScale=9.6&layers=" +
        URLEncoder.encode(layer, Charsets.UTF_8.name()) + "&events=&eventLabels=false&display=true&watermark=false&width=256&height=256&x0=0&y0=0"
    fun aia(channel: String, wavelength: Int, title: String) = HamClockSolarImage(channel, title, wavelength,
        "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_256_${channel}.jpg",
        listOf("https://sdowww.lmsal.com/sdomedia/SunInTime/mostrecent/t${channel}.jpg",
            helioviewer("[SDO,AIA,AIA,$wavelength,1,100]")), "NASA SDO/AIA; LMSAL and Helioviewer fallback")
    return listOf(
        aia("0193", 193, "Corona"), aia("0304", 304, "Chromosphere"),
        aia("0171", 171, "Quiet corona"), aia("0094", 94, "Flaring regions"),
        HamClockSolarImage("HMIIC", "Visible continuum", null,
            "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_256_HMIIC.jpg",
            listOf("https://soho.nascom.nasa.gov/data/realtime/hmi_igr/512/latest.jpg",
                helioviewer("[SDO,HMI,HMI,continuum,1,100]")), "NASA SDO/HMI; SOHO and Helioviewer fallback"),
    )
}

private fun solarCelestialSnapshot(latitude: Double, longitude: Double, nowEpoch: Long, xray: HamClockXraySeries) =
    HamClockSolarCelestialSnapshot(solarImageMetadata(nowEpoch), xray, moonSnapshot(nowEpoch),
        sunTimes(Instant.ofEpochSecond(nowEpoch).atZone(ZoneOffset.UTC).toLocalDate(), latitude, longitude))

private sealed interface SolarEvent {
    data class Time(val hours: Double) : SolarEvent
    data object MIDNIGHT_SUN : SolarEvent
    data object POLAR_NIGHT : SolarEvent
}

private fun solarEventUtcHours(date: LocalDate, latitude: Double, longitude: Double, sunrise: Boolean): SolarEvent {
    val day = date.dayOfYear.toDouble()
    val longitudeHour = longitude / 15.0
    val approximate = day + ((if (sunrise) 6.0 else 18.0) - longitudeHour) / 24.0
    val meanAnomaly = 0.9856 * approximate - 3.289
    var trueLongitude = meanAnomaly + 1.916 * sin(degrees(meanAnomaly)) + 0.020 * sin(degrees(2 * meanAnomaly)) + 282.634
    trueLongitude = normalizeDegrees(trueLongitude)
    var rightAscension = Math.toDegrees(atan(0.91764 * tan(degrees(trueLongitude))))
    rightAscension = normalizeDegrees(rightAscension)
    rightAscension += floor(trueLongitude / 90.0) * 90.0 - floor(rightAscension / 90.0) * 90.0
    rightAscension /= 15.0
    val sinDeclination = 0.39782 * sin(degrees(trueLongitude))
    val cosDeclination = cos(asin(sinDeclination))
    val denominator = cosDeclination * cos(degrees(latitude))
    if (kotlin.math.abs(denominator) < 1e-12) return if (sinDeclination * sin(degrees(latitude)) > 0) SolarEvent.MIDNIGHT_SUN else SolarEvent.POLAR_NIGHT
    val cosHour = (cos(degrees(90.833)) - sinDeclination * sin(degrees(latitude))) / denominator
    if (cosHour > 1.0) return SolarEvent.POLAR_NIGHT
    if (cosHour < -1.0) return SolarEvent.MIDNIGHT_SUN
    val hour = (if (sunrise) 360.0 - Math.toDegrees(acos(cosHour)) else Math.toDegrees(acos(cosHour))) / 15.0
    val localMean = hour + rightAscension - 0.06571 * approximate - 6.622
    return SolarEvent.Time(normalizeHours(localMean - longitudeHour))
}

private fun degrees(value: Double) = Math.toRadians(value)
private fun normalizeDegrees(value: Double) = ((value % 360.0) + 360.0) % 360.0
private fun normalizeHours(value: Double) = ((value % 24.0) + 24.0) % 24.0

private fun goesClass(flux: Double?): String {
    if (flux == null || !flux.isFinite() || flux <= 0) return "—"
    val (letter, base) = when {
        flux >= 1e-4 -> "X" to 1e-4
        flux >= 1e-5 -> "M" to 1e-5
        flux >= 1e-6 -> "C" to 1e-6
        flux >= 1e-7 -> "B" to 1e-7
        else -> "A" to 1e-8
    }
    return "$letter${"%.1f".format(Locale.US, flux / base)}"
}

private fun xrayToJson(series: HamClockXraySeries) = JSONArray(series.points.map { point ->
    JSONObject().put("epoch", point.epoch).put("flux", point.fluxWattsPerSquareMetre).put("satellite", point.satellite)
}).toString()

private fun xrayFromJson(body: String): HamClockXraySeries {
    val array = JSONArray(body)
    val points = buildList {
        for (index in 0 until array.length()) {
            val row = array.getJSONObject(index)
            add(HamClockXrayPoint(row.getLong("epoch"), row.getDouble("flux"),
                row.optInt("satellite").takeIf { row.has("satellite") && !row.isNull("satellite") }))
        }
    }.sortedBy(HamClockXrayPoint::epoch)
    return HamClockXraySeries(points, goesClass(points.lastOrNull()?.fluxWattsPerSquareMetre),
        goesClass(points.maxOfOrNull(HamClockXrayPoint::fluxWattsPerSquareMetre)))
}
