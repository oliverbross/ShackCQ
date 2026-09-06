package app.shackcq.mobile

import org.json.JSONObject

internal data class SatelliteElements(
    val format: String,
    val name: String,
    val elementOne: String,
    val elementTwo: String = "",
    val fetchedAt: Long = 0,
    val source: String = "MANUAL",
)

internal data class SatelliteObserver(val latitudeDeg: Double, val longitudeDeg: Double, val altitudeKm: Double = 0.0)
internal data class SatelliteElementInspection(val noradId: Long, val elementEpoch: Long)
internal data class OrbitalPoint(
    val epoch: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeKm: Double,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val rangeRateKmS: Double,
)
internal data class OrbitalPass(
    val aos: Long,
    val tca: Long,
    val los: Long,
    val maximumElevationDeg: Double,
    val aosAzimuthDeg: Double,
    val losAzimuthDeg: Double,
    val alreadyActive: Boolean,
) { val durationSeconds: Long get() = (los - aos).coerceAtLeast(0) }

internal sealed interface SatelliteNativeResult<out T> {
    data class Success<T>(val value: T) : SatelliteNativeResult<T>
    data class Error(val code: String) : SatelliteNativeResult<Nothing>
}

internal object NativeSatellite {
    private const val DEFAULT_MAX_AGE_SECONDS = 14L * 24 * 60 * 60
    init { System.loadLibrary("shackcq") }

    private external fun propagateNative(format: String, name: String, elementOne: String, elementTwo: String,
        epoch: Long, maxAge: Long, latitude: Double, longitude: Double, altitude: Double): String
    private external fun passesNative(format: String, name: String, elementOne: String, elementTwo: String,
        start: Long, end: Long, maxAge: Long, latitude: Double, longitude: Double, altitude: Double,
        horizon: Double, minimumPeak: Double, step: Int, maximumPasses: Int): String
    private external fun samplesNative(format: String, name: String, elementOne: String, elementTwo: String,
        start: Long, end: Long, maxAge: Long, latitude: Double, longitude: Double, altitude: Double,
        step: Int, maximumSamples: Int, kind: Int): String
    private external fun dopplerNative(frequency: Double, rangeRate: Double): Double
    private external fun inspectNative(format: String, name: String, elementOne: String, elementTwo: String): String

    fun inspect(elements: SatelliteElements): SatelliteNativeResult<SatelliteElementInspection> =
        parse(inspectNative(elements.format, elements.name, elements.elementOne, elements.elementTwo)) { root ->
            SatelliteElementInspection(root.getLong("norad_id"), root.getLong("element_epoch"))
        }

    fun propagate(elements: SatelliteElements, observer: SatelliteObserver, epoch: Long, maximumAgeSeconds: Long = DEFAULT_MAX_AGE_SECONDS): SatelliteNativeResult<OrbitalPoint> =
        parsePoint(propagateNative(elements.format, elements.name, elements.elementOne, elements.elementTwo, epoch, maximumAgeSeconds,
            observer.latitudeDeg, observer.longitudeDeg, observer.altitudeKm))

    fun passes(elements: SatelliteElements, observer: SatelliteObserver, start: Long, end: Long,
        horizonDeg: Double = 0.0, minimumPeakDeg: Double = 0.0, coarseStepSeconds: Int = 30,
        maximumPasses: Int = 24, maximumAgeSeconds: Long = DEFAULT_MAX_AGE_SECONDS): SatelliteNativeResult<List<OrbitalPass>> =
        parse(passesNative(elements.format, elements.name, elements.elementOne, elements.elementTwo, start, end, maximumAgeSeconds,
            observer.latitudeDeg, observer.longitudeDeg, observer.altitudeKm, horizonDeg, minimumPeakDeg, coarseStepSeconds, maximumPasses)) { root ->
            val rows = root.getJSONArray("passes")
            buildList { for (index in 0 until rows.length()) rows.getJSONObject(index).let { row -> add(OrbitalPass(
                row.getLong("aos"), row.getLong("tca"), row.getLong("los"), row.getDouble("maximum_elevation_deg"),
                row.getDouble("aos_azimuth_deg"), row.getDouble("los_azimuth_deg"), row.optBoolean("already_active"),
            )) } }
        }

    fun samples(elements: SatelliteElements, observer: SatelliteObserver, start: Long, end: Long, stepSeconds: Int,
        maximumSamples: Int, sky: Boolean, maximumAgeSeconds: Long = DEFAULT_MAX_AGE_SECONDS): SatelliteNativeResult<List<OrbitalPoint>> =
        parse(samplesNative(elements.format, elements.name, elements.elementOne, elements.elementTwo, start, end, maximumAgeSeconds,
            observer.latitudeDeg, observer.longitudeDeg, observer.altitudeKm, stepSeconds, maximumSamples, if (sky) 1 else 0)) { root ->
            val rows = root.getJSONArray("samples")
            buildList { for (index in 0 until rows.length()) add(point(rows.getJSONObject(index))) }
        }

    fun shiftedFrequencyHz(nominalFrequencyHz: Long, rangeRateKmS: Double): Long =
        dopplerNative(nominalFrequencyHz.toDouble(), rangeRateKmS).toLong()

    private fun parsePoint(raw: String) = parse(raw) { point(it.getJSONObject("point")) }
    private fun point(row: JSONObject) = OrbitalPoint(
        row.getLong("epoch"), row.optDouble("latitude_deg"), row.optDouble("longitude_deg"), row.optDouble("altitude_km"),
        row.getDouble("azimuth_deg"), row.getDouble("elevation_deg"), row.getDouble("range_km"), row.getDouble("range_rate_km_s"),
    )
    private fun <T> parse(raw: String, value: (JSONObject) -> T): SatelliteNativeResult<T> = runCatching {
        val root = JSONObject(raw)
        if (!root.optBoolean("ok")) SatelliteNativeResult.Error(root.optJSONObject("error")?.optString("code").orEmpty().ifBlank { "NATIVE_ERROR" })
        else SatelliteNativeResult.Success(value(root))
    }.getOrElse { SatelliteNativeResult.Error("INVALID_NATIVE_PAYLOAD") }
}
