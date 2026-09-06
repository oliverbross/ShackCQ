package app.shackcq.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val POTA_SPOT_URL = "https://api.pota.app/spot/activator"
internal const val POTA_PARK_URL = "https://pota.app/all_parks_ext.csv"
internal const val POTA_USER_AGENT = "ShackCQ/0.1 (Android POTA Chase; https://github.com/oliverbross/ShackCQ)"
internal const val POTA_USEFUL_SPOT_SECONDS = 30 * 60L

internal enum class PotaFeedKind { LOADING, LIVE, CACHED, OFFLINE, FAILED }
internal enum class PotaSort(val label: String) { RECOMMENDED("Recommended"), NEWEST("Newest"), FREQUENCY("Frequency"), DISTANCE("Distance"), PARK("Park") }

internal data class PotaSpot(
    val id: String,
    val callsign: String,
    val frequencyHz: Long,
    val mode: String,
    val reference: String,
    val parkName: String,
    val location: String,
    val grid: String,
    val latitude: Double?,
    val longitude: Double?,
    val spottedAt: Long,
    val expiresAt: Long,
    val source: String,
    val spotter: String,
    val comments: String,
    val invalid: Boolean,
    val qrt: Boolean,
) {
    val band: String get() = bandForFrequency(frequencyHz)
    fun activeAt(now: Long): Boolean = !invalid && !qrt && frequencyHz > 0 && reference.isNotBlank() && now < expiresAt
}

internal data class PotaPark(
    val reference: String,
    val name: String,
    val active: Boolean,
    val entityId: String,
    val location: String,
    val latitude: Double?,
    val longitude: Double?,
    val grid: String,
    val distanceKm: Double? = null,
    val bearingDegrees: Int? = null,
)

internal data class PotaWorkedState(
    val parkWorked: Boolean = false,
    val bandWorked: Boolean = false,
    val modeWorked: Boolean = false,
    val callWorked: Boolean = false,
    val workedToday: Boolean = false,
) {
    val labels: List<String> get() = buildList {
        when {
            workedToday -> add("WORKED TODAY")
            !parkWorked -> add("NEW PARK")
            !bandWorked -> add("NEW ON BAND")
            !modeWorked -> add("NEW MODE")
            else -> add("WORKED")
        }
        if (callWorked && !workedToday) add("CALL WORKED")
    }
}

internal data class PotaOpportunity(
    val spot: PotaSpot,
    val worked: PotaWorkedState,
    val score: Int,
    val reasons: List<String>,
    val distanceKm: Double? = null,
    val bearingDegrees: Int? = null,
)

internal data class PotaLogDraft(
    val token: Long,
    val callsign: String,
    val frequencyHz: Long,
    val mode: String,
    val potaRef: String,
    val parkName: String,
    val location: String,
    val comment: String,
)

internal fun parsePotaSpots(raw: String, now: Long = Instant.now().epochSecond): List<PotaSpot> {
    val array = JSONArray(raw)
    val parsed = buildList {
        for (index in 0 until array.length()) parsePotaSpot(array.optJSONObject(index) ?: continue, now)?.let(::add)
    }
    return parsed.groupBy { listOf(it.callsign, it.reference, it.frequencyHz.toString(), modeFamily(it.mode)).joinToString("|") }
        .values.mapNotNull { rows -> rows.maxByOrNull(PotaSpot::spottedAt) }
        .sortedByDescending(PotaSpot::spottedAt)
}

private fun parsePotaSpot(row: JSONObject, now: Long): PotaSpot? {
    val call = clean(row.optString("activator"), 24).uppercase(Locale.US)
        .filter { it.isLetterOrDigit() || it == '/' || it == '-' }
    if (call.isBlank()) return null
    val frequencyHz = row.optString("frequency").trim().replace(',', '.').toDoubleOrNull()?.times(1_000.0)?.toLong()
        ?.takeIf { it in 100_000L..1_000_000_000L } ?: 0L
    val reference = normalizePotaReference(row.optString("reference"))
    val spottedAt = parseUtc(row.optString("spotTime")) ?: now
    val expiresAt = parsePotaExpiry(row.opt("expire"), spottedAt) ?: (spottedAt + POTA_USEFUL_SPOT_SECONDS)
    val comments = clean(row.optString("comments"), 320)
    val latitude = finiteCoordinate(row.opt("latitude"), -90.0, 90.0)
    val longitude = finiteCoordinate(row.opt("longitude"), -180.0, 180.0)
    val explicitInvalid = when (val value = row.opt("invalid")) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> false
    }
    val serverId = clean(row.opt("spotId")?.toString().orEmpty(), 40)
    return PotaSpot(
        id = serverId.ifBlank { "$call|$reference|$frequencyHz|$spottedAt" }, callsign = call,
        frequencyHz = frequencyHz, mode = clean(row.optString("mode"), 20).uppercase(Locale.US), reference = reference,
        parkName = clean(row.optString("parkName").takeUnless { it.equals("null", true) }.orEmpty(), 160)
            .ifBlank { clean(row.optString("name"), 160) }, location = clean(row.optString("locationDesc"), 80),
        grid = clean(row.optString("grid6"), 8).ifBlank { clean(row.optString("grid4"), 8) }.uppercase(Locale.US),
        latitude = latitude, longitude = longitude, spottedAt = spottedAt, expiresAt = expiresAt,
        source = clean(row.optString("source"), 48), spotter = clean(row.optString("spotter"), 32), comments = comments,
        invalid = explicitInvalid || frequencyHz == 0L || reference.isBlank(),
        qrt = Regex("(^|\\W)QRT(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(comments),
    )
}

internal fun normalizePotaReference(raw: String): String {
    val value = clean(raw, 20).uppercase(Locale.US).replace(" ", "")
    return value.takeIf { Regex("^[A-Z0-9]{1,6}-[A-Z0-9]{1,10}$").matches(it) }.orEmpty()
}

internal fun modeFamily(raw: String): String = when (raw.trim().uppercase(Locale.US).replace('_', '-')) {
    "CW", "CW-R", "CWR" -> "CW"
    "SSB", "USB", "LSB", "PHONE" -> "SSB"
    "FT8" -> "FT8"
    "FT4" -> "FT4"
    "RTTY" -> "RTTY"
    "FM", "NFM" -> "FM"
    "AM" -> "AM"
    "PSK", "PSK31", "JS8", "JS8CALL", "MFSK", "OLIVIA", "DIGITAL", "DATA" -> "DIGITAL"
    else -> raw.trim().uppercase(Locale.US).ifBlank { "UNKNOWN" }
}

internal fun workedStateFor(spot: PotaSpot, qsos: List<Qso>, now: Long = Instant.now().epochSecond): PotaWorkedState {
    val startOfDay = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val parkRows = qsos.filter { normalizePotaReference(it.potaRef) == spot.reference }
    val family = modeFamily(spot.mode)
    return PotaWorkedState(
        parkWorked = parkRows.isNotEmpty(),
        bandWorked = parkRows.any { it.band.ifBlank { bandForFrequency(it.frequencyHz) } == spot.band },
        modeWorked = parkRows.any { modeFamily(it.submode.ifBlank { it.mode }) == family },
        callWorked = qsos.any { it.callsign.equals(spot.callsign, true) },
        workedToday = parkRows.any { it.callsign.equals(spot.callsign, true) &&
            it.band.ifBlank { bandForFrequency(it.frequencyHz) } == spot.band && modeFamily(it.submode.ifBlank { it.mode }) == family && it.createdAt >= startOfDay },
    )
}

internal fun rankPotaSpot(spot: PotaSpot, worked: PotaWorkedState, now: Long, radioFrequencyHz: Long = 0,
    station: GeoPoint? = null): PotaOpportunity {
    val reasons = mutableListOf<String>()
    var score = 0
    if (!worked.parkWorked) { score += 45; reasons += "new park" }
    else if (!worked.bandWorked) { score += 28; reasons += "new on ${spot.band}" }
    else if (!worked.modeWorked) { score += 18; reasons += "new ${modeFamily(spot.mode)} mode" }
    if (!worked.workedToday) { score += 10; reasons += "not worked today" } else score -= 45
    val age = (now - spot.spottedAt).coerceAtLeast(0)
    if (age <= 5 * 60) { score += 15; reasons += "fresh spot" } else if (age <= 15 * 60) score += 7
    if (radioFrequencyHz > 0 && bandForFrequency(radioFrequencyHz) == spot.band) { score += 8; reasons += "current radio band" }
    if (spot.qrt || now >= spot.expiresAt) score -= 100
    if (spot.invalid || spot.frequencyHz <= 0) score -= 200
    val point = if (spot.latitude != null && spot.longitude != null) GeoPoint(spot.latitude, spot.longitude) else null
    val distance = if (station != null && point != null) distanceKm(station, point) else null
    val bearing = if (station != null && point != null) initialBearingDegrees(station, point) else null
    return PotaOpportunity(spot, worked, score, reasons.ifEmpty { listOf("active POTA spot") }, distance, bearing)
}

internal fun sortedPota(rows: List<PotaOpportunity>, sort: PotaSort): List<PotaOpportunity> = when (sort) {
    PotaSort.RECOMMENDED -> rows.sortedWith(compareByDescending<PotaOpportunity> { it.score }.thenByDescending { it.spot.spottedAt }.thenBy { it.spot.id })
    PotaSort.NEWEST -> rows.sortedWith(compareByDescending<PotaOpportunity> { it.spot.spottedAt }.thenBy { it.spot.id })
    PotaSort.FREQUENCY -> rows.sortedWith(compareBy<PotaOpportunity> { it.spot.frequencyHz }.thenBy { it.spot.id })
    PotaSort.DISTANCE -> rows.sortedWith(compareBy<PotaOpportunity> { it.distanceKm ?: Double.MAX_VALUE }.thenBy { it.spot.id })
    PotaSort.PARK -> rows.sortedWith(compareBy<PotaOpportunity> { it.spot.reference }.thenBy { it.spot.id })
}

internal fun potaCatCommands(spot: PotaSpot): List<String> = buildList {
    if (spot.frequencyHz <= 0) return@buildList
    add("FA%011d;".format(Locale.US, spot.frequencyHz))
    when (modeFamily(spot.mode)) {
        "CW" -> add("MD3;")
        "SSB" -> when (spot.mode.uppercase(Locale.US)) { "USB" -> add("MD2;"); "LSB" -> add("MD1;") }
        "FM" -> add("MD4;")
        "AM" -> add("MD5;")
    }
}

internal fun executePotaTune(connected: Boolean, spot: PotaSpot, send: (String) -> Unit): Boolean {
    if (!connected) return false
    val commands = potaCatCommands(spot)
    if (commands.isEmpty()) return false
    commands.forEach(send)
    return true
}

internal fun toPotaLogDraft(spot: PotaSpot, token: Long = System.nanoTime()) = PotaLogDraft(
    token, spot.callsign, spot.frequencyHz, spot.mode, spot.reference, spot.parkName, spot.location,
    listOf(spot.source.takeIf(String::isNotBlank)?.let { "POTA spot via $it" }, spot.comments.takeIf(String::isNotBlank)).filterNotNull().joinToString(" · ").take(320),
)

private fun parseUtc(raw: String): Long? = runCatching { Instant.parse(if (raw.endsWith("Z")) raw else "${raw}Z").epochSecond }.getOrNull()
    ?: runCatching { LocalDateTime.parse(raw).toEpochSecond(ZoneOffset.UTC) }.getOrNull()

private fun parsePotaExpiry(raw: Any?, spottedAt: Long): Long? = when (raw) {
    is Number -> raw.toLong().let { if (it > 1_000_000_000L) it else if (it in 1..86_400) spottedAt + it else null }
    is String -> raw.toLongOrNull()?.let { if (it > 1_000_000_000L) it else if (it in 1..86_400) spottedAt + it else null } ?: parseUtc(raw)
    else -> null
}

private fun finiteCoordinate(raw: Any?, min: Double, max: Double): Double? = when (raw) {
    is Number -> raw.toDouble(); is String -> raw.toDoubleOrNull(); else -> null
}?.takeIf { it.isFinite() && it in min..max }

private fun clean(raw: String, max: Int): String {
    if (raw.trim().equals("null", true)) return ""
    return raw.replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ").replace(Regex("\\s+"), " ").trim().take(max)
}

internal fun distanceKm(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latitude); val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1; val dLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 6_371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
}

internal fun initialBearingDegrees(from: GeoPoint, to: GeoPoint): Int {
    val lat1 = Math.toRadians(from.latitude); val lat2 = Math.toRadians(to.latitude); val dLon = Math.toRadians(to.longitude - from.longitude)
    return ((Math.toDegrees(atan2(sin(dLon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon))) + 360) % 360).toInt()
}
