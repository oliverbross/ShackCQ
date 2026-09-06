package app.shackcq.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal const val SOTA_LIVE_APPROVED = true
internal const val SOTA_CLUSTER_HOST = "cluster.sota.org.uk"
internal const val SOTA_CLUSTER_PORT = 7300

internal const val SOTA_SUMMITS_URL = "https://www.sotadata.org.uk/summitslist.csv"
internal const val WWFF_SPOTS_URL = "https://spots.wwff.co/static/spots.json"
internal const val WWFF_AGENDAS_URL = "https://spots.wwff.co/static/agendas_active.json"
internal const val WWFF_ALL_AGENDAS_URL = "https://spots.wwff.co/static/agendas.json"
internal const val PORTABLE_USER_AGENT = "ShackCQ/0.1 (Android Portable Chase; https://github.com/oliverbross/ShackCQ)"

internal enum class PortableProgram(val label: String) { POTA("POTA"), SOTA("SOTA"), WWFF("WWFF") }
internal enum class PortableFeedKind(val label: String) {
    LOADING("LOADING"), LIVE("LIVE"), CACHED("CACHED"), STALE("STALE"), OFFLINE("OFFLINE"),
    FAILED("REFRESH FAILED"), EMPTY("NO ACTIVE SPOTS"), UNAVAILABLE("UNAVAILABLE")
}
internal enum class PortableSort(val label: String) {
    RECOMMENDED("Recommended"), NEWEST("Newest"), FREQUENCY("Frequency"), DISTANCE("Distance"), REFERENCE("Reference")
}

internal data class PortableReference(
    val program: PortableProgram,
    val code: String,
    val name: String = "",
    val association: String = "",
    val region: String = "",
    val altitudeM: Int? = null,
    val points: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val grid: String = "",
    val activeAgenda: String = "",
)

internal data class PortableSpot(
    val id: String,
    val programs: Set<PortableProgram>,
    val callsign: String,
    val frequencyHz: Long,
    val mode: String,
    val references: List<PortableReference>,
    val spottedAt: Long,
    val expiresAt: Long,
    val source: String,
    val spotter: String = "",
    val comments: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val invalid: Boolean = false,
    val qrt: Boolean = false,
    val test: Boolean = false,
) {
    val band get() = bandForFrequency(frequencyHz)
    val primary get() = references.first()
    fun activeAt(now: Long) = !invalid && !qrt && !test && frequencyHz > 0 && references.isNotEmpty() && now < expiresAt
}

internal data class PortableWorkedState(
    val referenceWorked: Boolean = false,
    val bandWorked: Boolean = false,
    val modeWorked: Boolean = false,
    val callWorked: Boolean = false,
    val workedToday: Boolean = false,
) {
    fun labels(program: PortableProgram) = buildList {
        when {
            workedToday -> add("WORKED TODAY")
            !referenceWorked -> add(when (program) { PortableProgram.POTA -> "NEW PARK"; PortableProgram.SOTA -> "NEW SUMMIT"; PortableProgram.WWFF -> "NEW REFERENCE" })
            !bandWorked -> add("NEW ON BAND")
            !modeWorked -> add("NEW MODE")
            else -> add("WORKED")
        }
        if (callWorked && !workedToday) add("CALL WORKED")
    }
}

internal data class PortableOpportunity(
    val spot: PortableSpot,
    val worked: Map<PortableProgram, PortableWorkedState>,
    val score: Int,
    val reasons: List<String>,
    val distanceKm: Double? = null,
    val bearingDegrees: Int? = null,
)

internal data class PortableLogDraft(
    val token: Long,
    val callsign: String,
    val frequencyHz: Long,
    val mode: String,
    val potaRef: String = "",
    val sotaRef: String = "",
    val wwffRef: String = "",
    val referenceNames: String = "",
    val comment: String = "",
    val propagationMode: String = "",
    val satelliteName: String = "",
    val satelliteMode: String = "",
    val frequencyRxHz: Long = 0,
    val observerGrid: String = "",
)

internal data class SotaSummit(
    val code: String,
    val association: String,
    val region: String,
    val name: String,
    val altitudeM: Int?,
    val altitudeFt: Int?,
    val points: Int?,
    val bonusPoints: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val grid: String,
    val validFrom: String,
    val validTo: String,
    val distanceKm: Double? = null,
    val bearingDegrees: Int? = null,
) {
    val active: Boolean get() = validTo.isBlank() || runCatching { java.time.LocalDate.parse(validTo) >= java.time.LocalDate.now(ZoneOffset.UTC) }.getOrDefault(true)
}

internal fun PotaSpot.toPortable() = PortableSpot(
    id = "POTA:$id", programs = setOf(PortableProgram.POTA), callsign = callsign, frequencyHz = frequencyHz, mode = mode,
    references = listOf(PortableReference(PortableProgram.POTA, reference, parkName, region = location, latitude = latitude, longitude = longitude, grid = grid)),
    spottedAt = spottedAt, expiresAt = expiresAt, source = source.ifBlank { "POTA" }, spotter = spotter, comments = comments,
    latitude = latitude, longitude = longitude, invalid = invalid, qrt = qrt,
)

internal fun parseSotaSpots(raw: String, summits: Map<String, SotaSummit> = emptyMap(), now: Long = Instant.now().epochSecond): List<PortableSpot> {
    val array = JSONArray(raw)
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val callsign = row.text("activatorCallsign", "ActivatorCallsign", "callsign").portableCall()
            if (callsign.isBlank() || callsign == "DEPRECATED") continue
            val association = row.text("associationCode", "AssociationCode").portableText(16).uppercase(Locale.US)
            val rawCode = row.text("summitCode", "SummitCode").portableText(28).uppercase(Locale.US).replace(" ", "")
            val code = normalizeSotaReference(if ('/' in rawCode) rawCode else if (association.isNotBlank()) "$association/$rawCode" else rawCode)
            val frequencyHz = row.value("frequency", "Frequency")?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.times(1_000_000)?.toLong()
                ?.takeIf { it in 100_000L..1_000_000_000L } ?: 0L
            val spottedAt = parsePortableTime(row.text("timeStamp", "timestamp", "TimeStamp")) ?: continue
            val type = row.text("type", "Type").trim().uppercase(Locale.US)
            val comments = row.text("comments", "Comments").portableText(500)
            val summit = summits[code]
            val details = row.text("summitName", "SummitName", "summitDetails", "SummitDetails").portableText(220)
            val name = row.text("summitName", "SummitName").portableText(160).ifBlank { summit?.name ?: details.substringBefore(',').trim() }
            val altitude = row.int("AltM", "altM") ?: summit?.altitudeM ?: Regex("(\\d+)m(?:,|$)").find(details)?.groupValues?.get(1)?.toIntOrNull()
            val points = row.int("points", "Points") ?: summit?.points ?: Regex("(\\d+) points?", RegexOption.IGNORE_CASE).find(details)?.groupValues?.get(1)?.toIntOrNull()
            val latitude = row.double("latitude", "Latitude")?.takeIf { it in -90.0..90.0 } ?: summit?.latitude
            val longitude = row.double("longitude", "Longitude")?.takeIf { it in -180.0..180.0 } ?: summit?.longitude
            val future = spottedAt > now + 5 * 60
            add(PortableSpot(
                id = "SOTA:${row.text("id", "ID").ifBlank { "$callsign|$code|$spottedAt" }}", programs = setOf(PortableProgram.SOTA), callsign = callsign,
                frequencyHz = frequencyHz, mode = row.text("mode", "Mode").portableText(20).uppercase(Locale.US),
                references = listOf(PortableReference(PortableProgram.SOTA, code, name, summit?.association ?: association, summit?.region.orEmpty(), altitude, points, latitude, longitude, summit?.grid.orEmpty())),
                spottedAt = spottedAt, expiresAt = spottedAt + 60 * 60, source = "SOTAwatch", spotter = row.text("callsign", "Callsign").portableCall(), comments = comments,
                latitude = latitude, longitude = longitude, invalid = code.isBlank() || frequencyHz == 0L || future,
                qrt = type == "QRT" || containsQrt(comments), test = type == "TEST",
            ))
        }
    }.filterNot { it.test }.distinctBy { listOf(it.callsign, it.primary.code, it.frequencyHz.toString(), modeFamily(it.mode)).joinToString("|") }
        .sortedByDescending(PortableSpot::spottedAt)
}

internal fun parseSotaClusterLine(
    raw: String,
    summits: Map<String, SotaSummit> = emptyMap(),
    now: Long = Instant.now().epochSecond,
): PortableSpot? {
    if (!raw.startsWith("DX de ", ignoreCase = true)) return null
    val colon = raw.indexOf(':', startIndex = 6)
    if (colon < 0) return null
    val spotter = raw.substring(6, colon).trim().portableCall()
    val tokens = raw.substring(colon + 1).trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.size < 4) return null
    val frequencyHz = tokens[0].replace(',', '.').toDoubleOrNull()?.times(1_000)?.toLong()
        ?.takeIf { it in 100_000L..1_000_000_000L } ?: return null
    val callsign = tokens[1].portableCall().takeIf(String::isNotBlank) ?: return null
    val referenceIndex = tokens.indexOfFirst { normalizeSotaReference(it).isNotBlank() }
    if (referenceIndex < 2) return null
    val code = normalizeSotaReference(tokens[referenceIndex])
    val timeIndex = tokens.indexOfLast { Regex("^[0-2][0-9][0-5][0-9]Z$", RegexOption.IGNORE_CASE).matches(it) }
    if (timeIndex <= referenceIndex) return null
    val hhmm = tokens[timeIndex].dropLast(1)
    val hour = hhmm.take(2).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = hhmm.takeLast(2).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val nowUtc = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC)
    var observed = nowUtc.toLocalDate().atTime(hour, minute).toEpochSecond(ZoneOffset.UTC)
    if (observed > now + 5 * 60) observed -= 86_400
    val comments = tokens.subList(referenceIndex + 1, timeIndex).joinToString(" ").portableText(320)
    val summit = summits[code]
    val mode = when {
        Regex("\\bCW\\b", RegexOption.IGNORE_CASE).containsMatchIn(comments) -> "CW"
        Regex("\\b(SSB|USB|LSB)\\b", RegexOption.IGNORE_CASE).containsMatchIn(comments) -> "SSB"
        Regex("\\bFM\\b", RegexOption.IGNORE_CASE).containsMatchIn(comments) -> "FM"
        Regex("\\b(FT8|FT4|RTTY|DIGI(?:TAL)?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(comments) -> "DIGITAL"
        else -> "UNKNOWN"
    }
    return PortableSpot(
        id = "SOTA-CLUSTER:$callsign|$code|$frequencyHz|$observed",
        programs = setOf(PortableProgram.SOTA), callsign = callsign, frequencyHz = frequencyHz, mode = mode,
        references = listOf(PortableReference(PortableProgram.SOTA, code, summit?.name.orEmpty(), summit?.association.orEmpty(), summit?.region.orEmpty(),
            summit?.altitudeM, summit?.points, summit?.latitude, summit?.longitude, summit?.grid.orEmpty())),
        spottedAt = observed, expiresAt = observed + 60 * 60, source = "SOTA Cluster $SOTA_CLUSTER_HOST:$SOTA_CLUSTER_PORT",
        spotter = spotter, comments = comments, latitude = summit?.latitude, longitude = summit?.longitude,
        invalid = observed > now + 5 * 60, qrt = containsQrt(comments),
    )
}

private data class WwffAgenda(val call: String, val reference: String, val start: Long, val end: Long, val details: String)

internal fun parseWwffSpots(raw: String, agendaRaw: String = "[]", now: Long = Instant.now().epochSecond): List<PortableSpot> {
    val agendas = runCatching { JSONArray(agendaRaw) }.getOrDefault(JSONArray()).let { array -> buildList {
        for (index in 0 until array.length()) (array.optJSONObject(index) ?: continue).let { row ->
            val start = parsePortableTime(row.text("utc_start"), spaceUtc = true) ?: 0
            val end = parsePortableTime(row.text("utc_end"), spaceUtc = true) ?: 0
            if (start <= now && end >= now) add(WwffAgenda(row.text("activator_call").portableCall(), normalizeWwffReference(row.text("reference")), start, end,
                listOf(row.text("band"), row.text("mode"), row.text("remarks")).filter(String::isNotBlank).joinToString(" · ").portableText(320)))
        }
    } }
    val array = JSONArray(raw)
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val call = row.text("activator").portableCall(); val reference = normalizeWwffReference(row.text("reference"))
            if (call.isBlank()) continue
            val frequencyHz = row.value("frequency_khz")?.toString()?.toDoubleOrNull()?.times(1_000)?.toLong()?.takeIf { it in 100_000L..1_000_000_000L } ?: 0L
            val spottedAt = row.long("spot_time") ?: parsePortableTime(row.text("spot_time_formatted"), spaceUtc = true) ?: continue
            val remarks = row.text("remarks").portableText(500)
            val agenda = agendas.firstOrNull { it.call == call && it.reference == reference }
            val expiresAt = minOf(spottedAt + 60 * 60, agenda?.end?.takeIf { it > spottedAt } ?: Long.MAX_VALUE)
            val latitude = row.double("latitude")?.takeIf { it in -90.0..90.0 }; val longitude = row.double("longitude")?.takeIf { it in -180.0..180.0 }
            add(PortableSpot(
                id = "WWFF:${row.text("id").ifBlank { "$call|$reference|$spottedAt" }}", programs = setOf(PortableProgram.WWFF), callsign = call, frequencyHz = frequencyHz,
                mode = row.text("mode").portableText(20).uppercase(Locale.US), references = listOf(PortableReference(PortableProgram.WWFF, reference,
                    row.text("reference_name").portableText(180), latitude = latitude, longitude = longitude, activeAgenda = agenda?.details.orEmpty())),
                spottedAt = spottedAt, expiresAt = expiresAt, source = "WWFF Spotline", spotter = row.text("spotter").portableCall(), comments = remarks,
                latitude = latitude, longitude = longitude, invalid = reference.isBlank() || frequencyHz == 0L || spottedAt > now + 5 * 60, qrt = containsQrt(remarks),
            ))
        }
    }.distinctBy { listOf(it.callsign, it.primary.code, it.frequencyHz.toString(), modeFamily(it.mode)).joinToString("|") }.sortedByDescending(PortableSpot::spottedAt)
}

internal fun normalizeSotaReference(raw: String): String = raw.portableText(28).uppercase(Locale.US).replace(" ", "")
    .takeIf { Regex("^[A-Z0-9]{1,8}/[A-Z0-9]{1,8}-[A-Z0-9]{1,8}$").matches(it) }.orEmpty()

internal fun normalizeWwffReference(raw: String): String = raw.portableText(24).uppercase(Locale.US).replace(" ", "")
    .takeIf { Regex("^[A-Z0-9]{1,10}FF-[A-Z0-9]{1,10}$").matches(it) }.orEmpty()

internal fun groupPortableSpots(spots: List<PortableSpot>): List<PortableSpot> {
    val remaining = spots.sortedByDescending(PortableSpot::spottedAt).toMutableList(); val result = mutableListOf<PortableSpot>()
    while (remaining.isNotEmpty()) {
        val seed = remaining.removeAt(0)
        val tolerance = if (modeFamily(seed.mode) in setOf("CW", "DIGITAL", "FT8", "FT4", "RTTY")) 250L else 1_000L
        val matches = remaining.filter { other -> other.callsign == seed.callsign && modeFamily(other.mode) == modeFamily(seed.mode) &&
            abs(other.frequencyHz - seed.frequencyHz) <= tolerance && abs(other.spottedAt - seed.spottedAt) <= 180 &&
            other.references.all { it.code.isNotBlank() } && other.programs.intersect(seed.programs).isEmpty() }
        matches.forEach(remaining::remove)
        val grouped = listOf(seed) + matches
        result += if (matches.isEmpty()) seed else seed.copy(
            id = grouped.joinToString("+") { it.id }, programs = grouped.flatMap { it.programs }.toSet(), references = grouped.flatMap { it.references }.distinctBy { it.program to it.code },
            comments = grouped.map(PortableSpot::comments).filter(String::isNotBlank).distinct().joinToString(" · ").take(500),
            latitude = grouped.mapNotNull(PortableSpot::latitude).firstOrNull(), longitude = grouped.mapNotNull(PortableSpot::longitude).firstOrNull(),
            source = grouped.joinToString(" + ") { it.source }, expiresAt = grouped.minOf(PortableSpot::expiresAt), qrt = grouped.any(PortableSpot::qrt), invalid = grouped.any(PortableSpot::invalid),
        )
    }
    return result
}

internal fun workedStateFor(spot: PortableSpot, reference: PortableReference, qsos: List<Qso>, now: Long): PortableWorkedState {
    val startOfDay = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    fun Qso.reference() = when (reference.program) { PortableProgram.POTA -> normalizePotaReference(potaRef); PortableProgram.SOTA -> normalizeSotaReference(sotaRef); PortableProgram.WWFF -> normalizeWwffReference(wwffRef) }
    val rows = qsos.filter { it.reference() == reference.code }
    val family = modeFamily(spot.mode)
    return PortableWorkedState(rows.isNotEmpty(), rows.any { it.band.ifBlank { bandForFrequency(it.frequencyHz) } == spot.band },
        rows.any { modeFamily(it.submode.ifBlank { it.mode }) == family }, qsos.any { it.callsign.equals(spot.callsign, true) },
        rows.any { it.callsign.equals(spot.callsign, true) && it.band.ifBlank { bandForFrequency(it.frequencyHz) } == spot.band && modeFamily(it.submode.ifBlank { it.mode }) == family && it.createdAt >= startOfDay })
}

internal fun rankPortableSpot(spot: PortableSpot, qsos: List<Qso>, now: Long, radioFrequencyHz: Long = 0, station: GeoPoint? = null): PortableOpportunity {
    val worked = spot.references.associate { it.program to workedStateFor(spot, it, qsos, now) }
    val reasons = mutableListOf<String>(); var score = 0
    worked.forEach { (program, state) -> when { !state.referenceWorked -> { score += 45; reasons += "new ${when (program) { PortableProgram.POTA -> "park"; PortableProgram.SOTA -> "summit"; PortableProgram.WWFF -> "reference" }}" }; !state.bandWorked -> { score += 28; reasons += "new on ${spot.band}" }; !state.modeWorked -> { score += 18; reasons += "new ${modeFamily(spot.mode)} mode" } }; if (!state.workedToday) score += 10 else score -= 45 }
    val age = (now - spot.spottedAt).coerceAtLeast(0); if (age <= 300) { score += 15; reasons += "fresh spot" } else if (age <= 900) score += 7
    if (radioFrequencyHz > 0 && bandForFrequency(radioFrequencyHz) == spot.band) { score += 8; reasons += "current radio band" }
    if (spot.programs.size > 1) { score += 12; reasons += "multi-program activation" }
    if (!spot.activeAt(now)) score -= 150
    val point = if (spot.latitude != null && spot.longitude != null) GeoPoint(spot.latitude, spot.longitude) else null
    val distance = if (station != null && point != null) distanceKm(station, point) else null
    val bearing = if (station != null && point != null) initialBearingDegrees(station, point) else null
    return PortableOpportunity(spot, worked, score, reasons.distinct().ifEmpty { listOf("active portable spot") }, distance, bearing)
}

internal fun rankPortableSpot(spot:PortableSpot,worked:Map<PortableProgram,PortableWorkedState>,now:Long,radioFrequencyHz:Long=0,station:GeoPoint?=null):PortableOpportunity{
    val reasons=mutableListOf<String>();var score=0
    worked.forEach{(program,state)->when{!state.referenceWorked->{score+=45;reasons+="new ${when(program){PortableProgram.POTA->"park";PortableProgram.SOTA->"summit";PortableProgram.WWFF->"reference"}}"};!state.bandWorked->{score+=28;reasons+="new on ${spot.band}"};!state.modeWorked->{score+=18;reasons+="new ${modeFamily(spot.mode)} mode"}};if(!state.workedToday)score+=10 else score-=45}
    val age=(now-spot.spottedAt).coerceAtLeast(0);if(age<=300){score+=15;reasons+="fresh spot"}else if(age<=900)score+=7
    if(radioFrequencyHz>0&&bandForFrequency(radioFrequencyHz)==spot.band){score+=8;reasons+="current radio band"};if(spot.programs.size>1){score+=12;reasons+="multi-program activation"};if(!spot.activeAt(now))score-=150
    val point=if(spot.latitude!=null&&spot.longitude!=null)GeoPoint(spot.latitude,spot.longitude)else null
    return PortableOpportunity(spot,worked,score,reasons.distinct().ifEmpty{listOf("active portable spot")},if(station!=null&&point!=null)distanceKm(station,point)else null,if(station!=null&&point!=null)initialBearingDegrees(station,point)else null)
}

internal fun sortedPortable(rows: List<PortableOpportunity>, sort: PortableSort): List<PortableOpportunity> = when (sort) {
    PortableSort.RECOMMENDED -> rows.sortedWith(compareByDescending<PortableOpportunity> { it.score }.thenByDescending { it.spot.spottedAt }.thenBy { it.spot.id })
    PortableSort.NEWEST -> rows.sortedWith(compareByDescending<PortableOpportunity> { it.spot.spottedAt }.thenBy { it.spot.id })
    PortableSort.FREQUENCY -> rows.sortedWith(compareBy<PortableOpportunity> { it.spot.frequencyHz }.thenBy { it.spot.id })
    PortableSort.DISTANCE -> rows.sortedWith(compareBy<PortableOpportunity> { it.distanceKm ?: Double.MAX_VALUE }.thenBy { it.spot.id })
    PortableSort.REFERENCE -> rows.sortedWith(compareBy<PortableOpportunity> { it.spot.primary.code }.thenBy { it.spot.id })
}

internal fun portableCatCommands(spot: PortableSpot): List<String> = buildList {
    if (spot.frequencyHz <= 0) return@buildList
    add("FA%011d;".format(Locale.US, spot.frequencyHz))
    when (modeFamily(spot.mode)) { "CW" -> add("MD3;"); "SSB" -> when (spot.mode.uppercase(Locale.US)) { "USB" -> add("MD2;"); "LSB" -> add("MD1;") }; "FM" -> add("MD4;"); "AM" -> add("MD5;") }
}

internal fun executePortableTune(connected: Boolean, spot: PortableSpot, send: (String) -> Unit): Boolean {
    if (!connected) return false
    val commands = portableCatCommands(spot); if (commands.isEmpty()) return false
    commands.forEach(send); return true
}

internal fun toPortableLogDraft(spot: PortableSpot, token: Long = System.nanoTime()) = PortableLogDraft(
    token, spot.callsign, spot.frequencyHz, spot.mode,
    potaRef = spot.references.firstOrNull { it.program == PortableProgram.POTA }?.code.orEmpty(),
    sotaRef = spot.references.firstOrNull { it.program == PortableProgram.SOTA }?.code.orEmpty(),
    wwffRef = spot.references.firstOrNull { it.program == PortableProgram.WWFF }?.code.orEmpty(),
    referenceNames = spot.references.mapNotNull { it.name.takeIf(String::isNotBlank)?.let { name -> "${it.program.label}: $name" } }.joinToString(" · ").take(240),
    comment = listOf("Portable Chase via ${spot.source}", spot.comments).filter(String::isNotBlank).joinToString(" · ").take(500),
)

private fun JSONObject.key(name: String) = keys().asSequence().firstOrNull { it.equals(name, true) }
private fun JSONObject.value(vararg names: String): Any? = names.firstNotNullOfOrNull { name -> key(name)?.let { opt(it).takeUnless { value -> value == JSONObject.NULL } } }
private fun JSONObject.text(vararg names: String) = value(*names)?.toString().orEmpty()
private fun JSONObject.long(vararg names: String) = value(*names)?.toString()?.toLongOrNull()
private fun JSONObject.int(vararg names: String) = value(*names)?.toString()?.toDoubleOrNull()?.toInt()
private fun JSONObject.double(vararg names: String) = value(*names)?.toString()?.toDoubleOrNull()
private fun String.portableText(max: Int) = replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ").replace(Regex("\\s+"), " ").trim().take(max).takeUnless { it.equals("null", true) }.orEmpty()
private fun String.portableCall() = portableText(28).uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '/' || it == '-' }
private fun containsQrt(value: String) = Regex("(^|\\W)QRT(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(value)
private fun parsePortableTime(raw: String, spaceUtc: Boolean = false): Long? {
    val value = raw.trim(); if (value.isBlank()) return null
    return runCatching { Instant.parse(if (value.endsWith("Z")) value else if (spaceUtc) value.replace(' ', 'T') + "Z" else value + "Z").epochSecond }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
}
