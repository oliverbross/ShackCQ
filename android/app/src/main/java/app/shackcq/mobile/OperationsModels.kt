package app.shackcq.mobile

import app.shackcq.mobile.hamclock.HamClockContest
import app.shackcq.mobile.hamclock.HamClockDxpedition
import app.shackcq.mobile.hamclock.HamClockFeed
import app.shackcq.mobile.hamclock.HamClockFeedState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.math.floor

internal enum class OperationsRefreshState(val label: String) {
    CURRENT("CURRENT"), STALE("STALE"), OFFLINE_CACHE("OFFLINE CACHE"), EMPTY("EMPTY FROM PROVIDER"), ERROR("ERROR")
}

internal data class OperationsCacheMetadata(
    val source: String,
    val state: OperationsRefreshState,
    val lastSuccessEpoch: Long = 0,
    val error: String = "",
)

internal data class DxCalendarItem(
    val id: String,
    val callsign: String,
    val entity: String,
    val startEpoch: Long?,
    val endEpoch: Long?,
    val status: String,
    val dateText: String,
    val bands: Set<String>,
    val modes: Set<String>,
    val qsl: String,
    val information: String,
    val provider: String,
    val sourceUrl: String,
)

internal data class ContestCalendarItem(
    val id: String,
    val name: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val mode: String,
    val provider: String,
    val sourceUrl: String,
    val contestId: String = deterministicContestId(name),
)

internal data class ActivationPlan(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val program: String = "GENERAL",
    val references: List<String> = emptyList(),
    val grid: String,
    val latitude: Double,
    val longitude: Double,
    val startEpoch: Long,
    val durationMinutes: Int = 120,
    val notes: String = "",
    val createdAt: Long = Instant.now().epochSecond,
    val updatedAt: Long = Instant.now().epochSecond,
)

internal enum class ActivationCatalogSort(val label: String) { DISTANCE("DISTANCE"), NAME("NAME"), REFERENCE("REFERENCE") }

internal data class ActivationCatalogReference(
    val program: String,
    val reference: String,
    val name: String,
    val grid: String,
    val point: GeoPoint,
    val active: Boolean,
    val source: String,
    val distanceKm: Double = 0.0,
    val bearingDegrees: Int = 0,
)

internal fun nearbyActivationReferences(
    origin: GeoPoint,
    radiusKm: Double,
    rows: List<ActivationCatalogReference>,
    enabledPrograms: Set<String>,
    sort: ActivationCatalogSort,
    limit: Int = 500,
): List<ActivationCatalogReference> {
    val normalizedPrograms = enabledPrograms.map { it.uppercase(Locale.US) }.toSet()
    val prepared = rows.asSequence()
        .filter { it.program.uppercase(Locale.US) in normalizedPrograms }
        .distinctBy { "${it.program.uppercase(Locale.US)}:${it.reference.uppercase(Locale.US)}" }
        .map { it.copy(distanceKm = distanceKm(origin, it.point), bearingDegrees = initialBearingDegrees(origin, it.point)) }
        .filter { it.distanceKm <= radiusKm }
    val comparator = when (sort) {
        ActivationCatalogSort.DISTANCE -> compareBy<ActivationCatalogReference> { it.distanceKm }.thenBy { it.reference }
        ActivationCatalogSort.NAME -> compareBy<ActivationCatalogReference> { it.name.uppercase(Locale.US) }.thenBy { it.reference }
        ActivationCatalogSort.REFERENCE -> compareBy<ActivationCatalogReference> { it.reference.uppercase(Locale.US) }
    }
    return prepared.sortedWith(comparator).take(limit.coerceIn(1, 1_000)).toList()
}

internal data class GridCell(val south: Double, val west: Double, val north: Double, val east: Double)

internal fun <T> operationsMetadata(feed: HamClockFeed<List<T>>): OperationsCacheMetadata {
    val state = when {
        feed.value.isEmpty() && feed.state == HamClockFeedState.UNAVAILABLE -> OperationsRefreshState.ERROR
        feed.value.isEmpty() -> OperationsRefreshState.EMPTY
        feed.state == HamClockFeedState.LIVE || feed.state == HamClockFeedState.CACHED -> OperationsRefreshState.CURRENT
        feed.state == HamClockFeedState.STALE && feed.error.isNotBlank() -> OperationsRefreshState.OFFLINE_CACHE
        feed.state == HamClockFeedState.STALE -> OperationsRefreshState.STALE
        else -> OperationsRefreshState.ERROR
    }
    return OperationsCacheMetadata(feed.source, state, feed.fetchedAtEpoch, feed.error)
}

internal fun dxCalendarItems(feed: HamClockFeed<List<HamClockDxpedition>>): List<DxCalendarItem> = feed.value.map { row ->
    DxCalendarItem(row.callsign, row.callsign, row.entity, row.startEpoch, row.endEpoch, row.status.name.replace('_', ' '),
        row.dateText, row.bands, row.modes, row.qsl, row.information, feed.source, row.sourceUrl)
}

internal fun contestCalendarItems(feed: HamClockFeed<List<HamClockContest>>): List<ContestCalendarItem> = feed.value.map { row ->
    ContestCalendarItem(row.id, row.name, row.startEpoch, row.endEpoch, row.mode, feed.source, row.url)
}

internal fun deterministicContestId(name: String): String {
    val normalized = name.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ").trim()
    return mapOf(
        "CQ WORLD WIDE DX CONTEST CW" to "CQ-WW-CW",
        "CQ WORLD WIDE DX CONTEST SSB" to "CQ-WW-SSB",
        "CQ WORLDWIDE DX CONTEST CW" to "CQ-WW-CW",
        "CQ WORLDWIDE DX CONTEST SSB" to "CQ-WW-SSB",
        "CQ WW CW" to "CQ-WW-CW",
        "CQ WW SSB" to "CQ-WW-SSB",
        "ARRL FIELD DAY" to "ARRL-FD",
        "ARRL DX CONTEST CW" to "ARRL-DX-CW",
        "ARRL DX CONTEST SSB" to "ARRL-DX-SSB",
        "IARU HF WORLD CHAMPIONSHIP" to "IARU-HF",
    )[normalized].orEmpty()
}

internal fun contestGroup(item: ContestCalendarItem, nowEpoch: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (nowEpoch in item.startEpoch..item.endEpoch) return "ACTIVE NOW"
    val today = Instant.ofEpochSecond(nowEpoch).atZone(zone).toLocalDate()
    val start = Instant.ofEpochSecond(item.startEpoch).atZone(zone).toLocalDate()
    val days = start.toEpochDay() - today.toEpochDay()
    return when {
        days == 0L -> "TODAY"
        start.dayOfWeek.value >= 6 && days in 1..7 -> "THIS WEEKEND"
        days in 1..7 -> "NEXT 7 DAYS"
        else -> "LATER"
    }
}

internal fun maidenheadGrid(latitude: Double, longitude: Double, precision: Int = 6): String {
    val length = precision.coerceIn(2, 8).let { it - it % 2 }
    var lon = (longitude.coerceIn(-180.0, 179.999999) + 180.0)
    var lat = (latitude.coerceIn(-90.0, 89.999999) + 90.0)
    val out = StringBuilder()
    out.append(('A'.code + floor(lon / 20).toInt()).toChar())
    out.append(('A'.code + floor(lat / 10).toInt()).toChar())
    lon %= 20.0; lat %= 10.0
    if (length >= 4) { out.append(floor(lon / 2).toInt()); out.append(floor(lat).toInt()); lon %= 2.0; lat %= 1.0 }
    if (length >= 6) { out.append(('A'.code + floor(lon / (2.0 / 24)).toInt()).toChar()); out.append(('A'.code + floor(lat / (1.0 / 24)).toInt()).toChar()) }
    return out.toString()
}

internal fun maidenheadCell(raw: String): GridCell? {
    val grid = raw.trim().uppercase(Locale.US)
    if (!Regex("^[A-R]{2}(?:[0-9]{2}(?:[A-X]{2})?)?$").matches(grid)) return null
    var west = (grid[0] - 'A') * 20.0 - 180.0; var south = (grid[1] - 'A') * 10.0 - 90.0
    var width = 20.0; var height = 10.0
    if (grid.length >= 4) { west += (grid[2] - '0') * 2.0; south += (grid[3] - '0'); width = 2.0; height = 1.0 }
    if (grid.length >= 6) { width /= 24.0; height /= 24.0; west += (grid[4] - 'A') * width; south += (grid[5] - 'A') * height }
    return GridCell(south, west, south + height, west + width)
}

internal fun encodeActivationPlan(plan: ActivationPlan): String = JSONObject()
    .put("id", plan.id).put("title", plan.title).put("program", plan.program).put("references", JSONArray(plan.references))
    .put("grid", plan.grid).put("latitude", plan.latitude).put("longitude", plan.longitude).put("start", plan.startEpoch)
    .put("duration", plan.durationMinutes).put("notes", plan.notes).put("created", plan.createdAt).put("updated", plan.updatedAt).toString()

internal fun decodeActivationPlan(raw: String): ActivationPlan {
    val row = JSONObject(raw); val refs = row.optJSONArray("references") ?: JSONArray()
    return ActivationPlan(row.getString("id"), row.getString("title"), row.optString("program", "GENERAL"),
        buildList { for (i in 0 until refs.length()) refs.optString(i).takeIf(String::isNotBlank)?.let(::add) },
        row.getString("grid"), row.getDouble("latitude"), row.getDouble("longitude"), row.getLong("start"),
        row.optInt("duration", 120), row.optString("notes"), row.optLong("created"), row.optLong("updated"))
}

internal fun activationPlanIcs(plan: ActivationPlan): String {
    fun stamp(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
    fun safe(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//ShackCQ//Operations Planner//EN\r\nBEGIN:VEVENT\r\nUID:${plan.id}@shackcq\r\nDTSTAMP:${stamp(Instant.now().epochSecond)}\r\nDTSTART:${stamp(plan.startEpoch)}\r\nDTEND:${stamp(plan.startEpoch + plan.durationMinutes * 60L)}\r\nSUMMARY:${safe(plan.title)}\r\nDESCRIPTION:${safe(listOf(plan.program, plan.references.joinToString(" "), plan.grid, plan.notes).filter(String::isNotBlank).joinToString(" · "))}\r\nGEO:${plan.latitude};${plan.longitude}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
}

internal fun activationPlanSummary(plan: ActivationPlan): String = buildString {
    append(plan.title).append(" · ").append(plan.program).append(" · ").append(plan.grid)
    if (plan.references.isNotEmpty()) append(" · ").append(plan.references.joinToString())
    append(" · ").append(Instant.ofEpochSecond(plan.startEpoch).atZone(ZoneId.systemDefault()))
}

internal fun potaSetupForActivationPlan(plan: ActivationPlan, callsign: String) = PotaActivationSetup(
    stationCallsign = callsign,
    operatorCallsign = callsign,
    references = plan.references,
    primaryReference = plan.references.firstOrNull().orEmpty(),
    stationGrid = plan.grid,
    location = plan.title,
    notes = plan.notes,
    startAt = plan.startEpoch,
    boundaryAcknowledged = false,
)
