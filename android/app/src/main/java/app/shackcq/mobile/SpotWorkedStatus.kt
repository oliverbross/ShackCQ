package app.shackcq.mobile

import java.util.Locale

data class SpotLogIdentity(
    val id: String,
    val callsign: String,
    val dxcc: String,
    val country: String,
    val band: String,
    val mode: String,
)

internal fun spotLogIdentity(id: String, callsign: String, dxcc: String, country: String, band: String, mode: String) = SpotLogIdentity(
    id = id, callsign = callsign, dxcc = dxcc, country = country, band = band, mode = mode,
)

internal fun AndroidDXSpot.toSpotLogIdentity(entity: AndroidCtyRecord?): SpotLogIdentity = spotLogIdentity(
    id, callsign, entity?.dxcc.orEmpty().ifBlank { dxcc }, entity?.country.orEmpty().ifBlank { country }, band, mode,
)

internal fun SignalReport.toSpotLogIdentity(entity: AndroidCtyRecord?): SpotLogIdentity = spotLogIdentity(
    signalReportReference(this), callsign, entity?.dxcc.orEmpty(), entity?.country.orEmpty(), band, mode,
)

data class WorkedDimensions(
    val any: Boolean = false,
    val confirmedAny: Boolean = false,
    val bands: Set<String> = emptySet(),
    val confirmedBands: Set<String> = emptySet(),
    val bandModes: Set<String> = emptySet(),
    val confirmedBandModes: Set<String> = emptySet(),
)

data class SpotLogStatus(val callStatus: String, val dxccStatus: String)

fun canonicalSpotMode(mode: String): String = when (val value = mode.trim().uppercase(Locale.US)) {
    "USB", "LSB", "PHONE", "SSB" -> "SSB"
    "CW-R", "CWR", "CW" -> "CW"
    "MFSK" -> "DATA"
    else -> value
}

fun classifySpotStatus(identity: SpotLogIdentity, call: WorkedDimensions, entity: WorkedDimensions): SpotLogStatus {
    val band = identity.band.trim().uppercase(Locale.US)
    val bandMode = "$band|${canonicalSpotMode(identity.mode)}"
    val callStatus = when {
        !call.any -> "NC"
        band !in call.bands -> "NB"
        bandMode !in call.bandModes -> "NM"
        bandMode in call.confirmedBandModes -> "C"
        else -> "W"
    }
    val dxccStatus = when {
        !entity.any -> "ATNO"
        band !in entity.bands -> if (entity.confirmedAny) "C/NB" else "W/NB"
        band in entity.confirmedBands -> "C"
        else -> "W"
    }
    return SpotLogStatus(callStatus, dxccStatus)
}
