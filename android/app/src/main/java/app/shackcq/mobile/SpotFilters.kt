package app.shackcq.mobile

import java.util.Locale

internal enum class SpotFilterDimension(val title: String) {
    BAND("Band"), MODE("Mode"), CALL_STATUS("CS"), DXCC_STATUS("DS")
}

internal data class SpotFilters(
    val bands: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    val callStatuses: Set<String> = emptySet(),
    val dxccStatuses: Set<String> = emptySet(),
) {
    fun selected(dimension: SpotFilterDimension): Set<String> = when (dimension) {
        SpotFilterDimension.BAND -> bands
        SpotFilterDimension.MODE -> modes
        SpotFilterDimension.CALL_STATUS -> callStatuses
        SpotFilterDimension.DXCC_STATUS -> dxccStatuses
    }

    fun withSelection(dimension: SpotFilterDimension, values: Set<String>): SpotFilters = when (dimension) {
        SpotFilterDimension.BAND -> copy(bands = values)
        SpotFilterDimension.MODE -> copy(modes = values)
        SpotFilterDimension.CALL_STATUS -> copy(callStatuses = values)
        SpotFilterDimension.DXCC_STATUS -> copy(dxccStatuses = values)
    }

    fun count(dimension: SpotFilterDimension): Int = selected(dimension).size
}

internal val spotBandOptions = listOf(
    "2190m", "630m", "560m", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m",
    "8m", "6m", "5m", "4m", "2m", "1.25m", "70cm", "33cm", "23cm", "13cm", "9cm", "6cm", "3cm",
    "1.25cm", "6mm", "4mm", "2.5mm", "2mm", "1mm", "submm", "sat",
)
internal val hamClockHomeBandOptions = listOf(
    "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m",
    "8m", "6m", "5m", "4m", "2m", "1.25m", "70cm", "33cm", "23cm", "13cm", "sat",
)
internal val hamClockHomeBandPresets = listOf(
    "HF" to setOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m"),
    "LOW HF" to setOf("160m", "80m", "60m", "40m", "30m"),
    "HIGH HF" to setOf("20m", "17m", "15m", "12m", "10m"),
)
internal val spotModeOptions = listOf("CW", "SSB", "FT8", "FT4", "RTTY", "PSK", "DATA", "FM", "AM")
internal val spotCallStatusOptions = listOf("NC", "NB", "NM", "W", "C")
internal val spotDxccStatusOptions = listOf("ATNO", "W/NB", "C/NB", "W", "C")

internal fun canonicalSpotBand(value: String): String = when (value.trim().lowercase(Locale.US)) {
    "2200m" -> "2190m"
    else -> value.trim().lowercase(Locale.US)
}

internal fun spotMatchesFilters(spot: AndroidDXSpot, status: SpotLogStatus?, filters: SpotFilters): Boolean {
    val band = canonicalSpotBand(spot.band)
    val mode = canonicalSpotMode(spot.mode)
    return (filters.bands.isEmpty() || filters.bands.any { canonicalSpotBand(it) == band }) &&
        (filters.modes.isEmpty() || mode in filters.modes.map(::canonicalSpotMode)) &&
        (filters.callStatuses.isEmpty() || status?.callStatus in filters.callStatuses) &&
        (filters.dxccStatuses.isEmpty() || status?.dxccStatus in filters.dxccStatuses)
}

internal fun spotMatchesSearch(
    spot: AndroidDXSpot,
    entityCountry: String,
    entityDxcc: String,
    query: String,
): Boolean {
    val needle = query.trim().uppercase(Locale.US)
    if (needle.isEmpty()) return true
    return listOf(spot.callsign, spot.country, entityCountry, entityDxcc)
        .any { needle in it.uppercase(Locale.US) }
}
