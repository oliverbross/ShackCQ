package app.shackcq.mobile.hamclock

import app.shackcq.mobile.GeoPoint
import app.shackcq.mobile.SignalDirection
import app.shackcq.mobile.SignalReport
import app.shackcq.mobile.bandForFrequency
import app.shackcq.mobile.maidenheadCenter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

enum class HamClockRbnSourceKind { RBN }
enum class HamClockRbnSourceState { DISABLED, DISCONNECTED, CONNECTING, CURRENT, EMPTY, STALE, ERROR }

internal data class HamClockRbnSourceSnapshot(
    val state: HamClockRbnSourceState = HamClockRbnSourceState.DISABLED,
    val latestRbnEpoch: Long = 0,
    val rawBoundedCount: Int = 0,
    val filteredCount: Int = 0,
    val sourceEndpoint: String = "",
    val error: String = "",
)

internal enum class HamClockGeometryAccuracy { EXACT, APPROXIMATE, UNAVAILABLE }
internal data class HamClockResolvedEndpoint(
    val point: GeoPoint? = null,
    val source: String = "UNAVAILABLE",
    val accuracy: HamClockGeometryAccuracy = HamClockGeometryAccuracy.UNAVAILABLE,
)

internal data class HamClockRbnObservation(
    val id: String,
    val sourceKind: HamClockRbnSourceKind = HamClockRbnSourceKind.RBN,
    val skimmerCall: String,
    val dxCall: String,
    val skimmerPoint: GeoPoint? = null,
    val dxPoint: GeoPoint? = null,
    val skimmerGeometry: String = "UNRESOLVED",
    val dxGeometry: String = "UNRESOLVED",
    val frequencyHz: Long,
    val band: String,
    val mode: String,
    val snr: Int?,
    val wpm: Int?,
    val bps: Int?,
    val cq: Boolean,
    val test: Boolean,
    val observedEpoch: Long,
    val receivedEpoch: Long = observedEpoch,
    val rawComment: String,
)

/** Conservative identity used only for same-station RBN matching, never fuzzy substring matching. */
internal fun normalizedHamCallIdentity(value: String): String {
    val portable = setOf("P", "M", "MM", "AM", "QRP", "QRPP")
    val parts = value.trim().uppercase(Locale.US).replace(Regex("-#?$"), "")
        .split('/').map { it.replace(Regex("[^A-Z0-9]"), "") }.filter(String::isNotBlank)
    return parts.filterNot { it in portable }.filter { it.any(Char::isDigit) }
        .maxByOrNull(String::length).orEmpty()
}

internal fun normalizedSkimmerIdentity(value: String): String = value.trim().uppercase(Locale.US)
    .removeSuffix("-#").removeSuffix("#").trimEnd('-')

internal fun rbnModeMatches(
    row: HamClockRbnObservation,
    preference: HamClockRbnPreference,
    currentStationCall: String,
    watchlist: Set<String>,
): Boolean = when (preference.viewMode) {
    HamClockRbnMode.WHO_HEARS_ME -> normalizedHamCallIdentity(currentStationCall).let { own ->
        own.isNotBlank() && normalizedHamCallIdentity(row.dxCall) == own
    }
    HamClockRbnMode.SKIMMER_VIEW -> normalizedSkimmerIdentity(preference.skimmerCall).let { selected ->
        selected.isNotBlank() && normalizedSkimmerIdentity(row.skimmerCall) == selected
    }
    HamClockRbnMode.WATCHLIST -> normalizedHamCallIdentity(row.dxCall) in watchlist.map(::normalizedHamCallIdentity)
    HamClockRbnMode.ALL_RBN -> true
}

internal fun resolveRbnEndpoint(
    callsign: String,
    streamPoint: GeoPoint?,
    currentStationCall: String,
    currentStationPoint: GeoPoint?,
    cachedGrid: String?,
    ctyPoint: GeoPoint?,
): HamClockResolvedEndpoint = when {
    streamPoint != null -> HamClockResolvedEndpoint(streamPoint, "STREAM GRID", HamClockGeometryAccuracy.EXACT)
    currentStationPoint != null && normalizedHamCallIdentity(callsign) == normalizedHamCallIdentity(currentStationCall) ->
        HamClockResolvedEndpoint(currentStationPoint, "CURRENT STATION GRID", HamClockGeometryAccuracy.EXACT)
    !cachedGrid.isNullOrBlank() -> maidenheadCenter(cachedGrid)?.let {
        HamClockResolvedEndpoint(it, "CACHED CALLBOOK GRID", HamClockGeometryAccuracy.EXACT)
    } ?: HamClockResolvedEndpoint()
    ctyPoint != null -> HamClockResolvedEndpoint(ctyPoint, "CTY PREFIX CENTROID", HamClockGeometryAccuracy.APPROXIMATE)
    else -> HamClockResolvedEndpoint()
}

/** One resolved observation view shared by Home map rendering and DX evidence details. */
internal fun resolveRbnObservationView(
    row: HamClockRbnObservation,
    currentStationCall: String,
    currentStationPoint: GeoPoint?,
    cachedGrid: (String) -> String?,
    ctyPoint: (String) -> GeoPoint?,
): HamClockRbnObservation {
    fun endpoint(callsign: String, streamPoint: GeoPoint?) = resolveRbnEndpoint(
        callsign, streamPoint, currentStationCall, currentStationPoint,
        cachedGrid(normalizedHamCallIdentity(callsign)), ctyPoint(normalizedHamCallIdentity(callsign)),
    )
    val dx = endpoint(row.dxCall, row.dxPoint)
    val skimmer = endpoint(row.skimmerCall, row.skimmerPoint)
    return row.copy(
        dxPoint = dx.point,
        skimmerPoint = skimmer.point,
        dxGeometry = "${dx.source} · ${dx.accuracy}",
        skimmerGeometry = "${skimmer.source} · ${skimmer.accuracy}",
    )
}

internal fun parseRbnClusterLine(line: String, nowEpoch: Long = Instant.now().epochSecond): HamClockRbnObservation? {
    val match = Regex("^DX\\s+de\\s+([^:]+):\\s*([0-9]+(?:\\.[0-9]+)?)\\s+([A-Z0-9/]+)\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE)).find(line.trim()) ?: return null
    val skimmer = match.groupValues[1].trim().uppercase(Locale.US)
    if (!skimmer.contains("#") && !skimmer.contains("SKIM", true)) return null
    val frequencyHz = (match.groupValues[2].toDoubleOrNull()?.times(1_000.0))?.toLong() ?: return null
    val dx = match.groupValues[3].uppercase(Locale.US)
    val comment = match.groupValues[4].trim().take(160)
    val mode = Regex("\\b(CW|RTTY|PSK|FT8|FT4|WSPR|BPSK|QPSK)\\b", RegexOption.IGNORE_CASE)
        .find(comment)?.value?.uppercase(Locale.US) ?: "CW"
    val snr = Regex("([+-]?\\d+)\\s*dB\\b", RegexOption.IGNORE_CASE).find(comment)?.groupValues?.get(1)?.toIntOrNull()
    val wpm = Regex("(\\d+)\\s*WPM\\b", RegexOption.IGNORE_CASE).find(comment)?.groupValues?.get(1)?.toIntOrNull()
    val bps = Regex("(\\d+)\\s*BPS\\b", RegexOption.IGNORE_CASE).find(comment)?.groupValues?.get(1)?.toIntOrNull()
    val lineEpoch = Regex("\\b([01]\\d|2[0-3])([0-5]\\d)Z\\s*$", RegexOption.IGNORE_CASE)
        .find(comment)?.let { clock ->
            val hour = clock.groupValues[1].toInt()
            val minute = clock.groupValues[2].toInt()
            val today = LocalDate.ofEpochDay(Math.floorDiv(nowEpoch, 86_400L))
            var candidate = today.atTime(hour, minute).toEpochSecond(ZoneOffset.UTC)
            if (candidate > nowEpoch + 300L) candidate -= 86_400L
            candidate
        } ?: nowEpoch
    val identity = "$skimmer|$dx|$frequencyHz|$mode|${lineEpoch / 10L}"
    return HamClockRbnObservation(identity, skimmerCall = skimmer, dxCall = dx, frequencyHz = frequencyHz,
        band = bandForFrequency(frequencyHz), mode = mode, snr = snr, wpm = wpm, bps = bps,
        cq = Regex("\\bCQ\\b", RegexOption.IGNORE_CASE).containsMatchIn(comment),
        test = Regex("\\b(TEST|BEACON)\\b", RegexOption.IGNORE_CASE).containsMatchIn(comment),
        observedEpoch = lineEpoch, receivedEpoch = nowEpoch, rawComment = comment)
}

internal fun boundedRbnObservations(
    rows: List<HamClockRbnObservation>,
    preference: HamClockRbnPreference,
    watchlist: Set<String>,
    currentStationCall: String = "",
    nowEpoch: Long = Instant.now().epochSecond,
): List<HamClockRbnObservation> {
    if (!preference.enabled) return emptyList()
    val cutoff = nowEpoch - preference.windowMinutes * 60L
    val bands = preference.bands.map { it.uppercase(Locale.US) }.toSet()
    val modes = preference.modes.map { it.uppercase(Locale.US) }.toSet()
    val normalizedWatchlist = watchlist.map(::normalizedHamCallIdentity).filter(String::isNotBlank).toSet()
    val deduped = LinkedHashMap<String, HamClockRbnObservation>()
    rows.asSequence().filter { it.observedEpoch >= cutoff }
        .filter { rbnModeMatches(it, preference, currentStationCall, normalizedWatchlist) }
        .filter { bands.isEmpty() || it.band.uppercase(Locale.US) in bands }
        .filter { modes.isEmpty() || it.mode.uppercase(Locale.US) in modes }
        .filter { preference.minimumSnr == null || (it.snr != null && it.snr >= preference.minimumSnr) }
        .filter { preference.skimmerCall.isBlank() || it.skimmerCall.contains(preference.skimmerCall, true) }
        .filter { preference.dxCall.isBlank() || it.dxCall.contains(preference.dxCall, true) }
        .filter { !preference.watchlistOnly || normalizedHamCallIdentity(it.dxCall) in normalizedWatchlist }
        .sortedByDescending(HamClockRbnObservation::observedEpoch)
        .forEach { row -> deduped.putIfAbsent("${row.skimmerCall}|${row.dxCall}|${row.frequencyHz / 100}|${row.mode}|${row.observedEpoch / 10}", row) }
    return deduped.values.take(preference.maximumRows).toList()
}

internal fun boundedRbnObservations(rows: List<HamClockRbnObservation>, preference: HamClockRbnPreference,
    watchlist: Set<String>, nowEpoch: Long): List<HamClockRbnObservation> =
    boundedRbnObservations(rows, preference, watchlist, "", nowEpoch)

enum class HamClockWsprRegionalState { DISABLED, UNAVAILABLE_POLICY }

internal data class HamClockWsprSnapshot(
    val callsign: String = "",
    val reports: List<SignalReport> = emptyList(),
    val beingHeardState: HamClockFeedState = HamClockFeedState.UNAVAILABLE,
    val hearingState: HamClockFeedState = HamClockFeedState.UNAVAILABLE,
    val fetchedEpoch: Long = 0,
    val regionalState: HamClockWsprRegionalState = HamClockWsprRegionalState.DISABLED,
    val error: String = "",
    val sourceState: HamClockFeedState = combinedSignalFeedState(beingHeardState, hearingState),
)

internal fun combinedSignalFeedState(first: HamClockFeedState, second: HamClockFeedState): HamClockFeedState = when {
    first == HamClockFeedState.UNAVAILABLE && second == HamClockFeedState.UNAVAILABLE -> HamClockFeedState.UNAVAILABLE
    first == HamClockFeedState.STALE && second == HamClockFeedState.STALE -> HamClockFeedState.STALE
    first == HamClockFeedState.DEGRADED || second == HamClockFeedState.DEGRADED -> HamClockFeedState.DEGRADED
    first == HamClockFeedState.UNAVAILABLE || second == HamClockFeedState.UNAVAILABLE -> HamClockFeedState.DEGRADED
    first == HamClockFeedState.STALE || second == HamClockFeedState.STALE -> HamClockFeedState.DEGRADED
    first == HamClockFeedState.LIVE && second == HamClockFeedState.LIVE -> HamClockFeedState.LIVE
    else -> HamClockFeedState.CACHED
}

internal class HamClockWsprRepository(private val pskReporter: PskReporterRepository) {
    @Volatile private var lastLoaded = PskReporterSnapshot()

    fun refreshPersonal(callsign: String, station: GeoPoint?, preference: HamClockWsprPreference,
        force: Boolean = false, nowEpoch: Long = Instant.now().epochSecond): HamClockWsprSnapshot {
        if (!preference.personalEnabled || callsign.isBlank()) return HamClockWsprSnapshot(
            callsign = callsign.trim().uppercase(Locale.US), regionalState = regionalState(preference))
        val loaded = pskReporter.refresh(callsign, station, preference.windowMinutes, force, mode = "WSPR", nowEpoch = nowEpoch)
        lastLoaded = loaded
        return projectPersonal(loaded, preference, station)
    }

    fun reprojectPersonal(preference: HamClockWsprPreference, station: GeoPoint? = null): HamClockWsprSnapshot =
        projectPersonal(lastLoaded, preference, station)

    private fun projectPersonal(loaded: PskReporterSnapshot, preference: HamClockWsprPreference,
        station: GeoPoint?): HamClockWsprSnapshot {
        val reports = loaded.reports.asSequence().map { report ->
            val distance = station?.let { local -> report.latitude?.let { latitude -> report.longitude?.let { longitude ->
                greatCircleDistanceKm(local, GeoPoint(latitude, longitude)).roundToInt()
            } } }
            report.copy(distanceKm = distance)
        }
            .filter { preference.direction == HamClockPskDirection.BOTH ||
                preference.direction == HamClockPskDirection.MUTUAL && it.mutual ||
                preference.direction == HamClockPskDirection.BEING_HEARD && it.direction == SignalDirection.BEING_HEARD ||
                preference.direction == HamClockPskDirection.HEARING && it.direction == SignalDirection.HEARING }
            .filter { preference.band == "ALL" || it.band.equals(preference.band, true) }
            .filter { preference.minimumSnr == null || (it.snr != null && it.snr >= preference.minimumSnr) }
            .take(preference.maximumPaths).toList()
        return HamClockWsprSnapshot(loaded.callsign, reports, loaded.beingHeard.state, loaded.hearing.state,
            maxOf(loaded.beingHeard.fetchedEpoch, loaded.hearing.fetchedEpoch), regionalState(preference),
            listOf(loaded.beingHeard.error, loaded.hearing.error).filter(String::isNotBlank).joinToString(" · "))
    }

    private fun regionalState(preference: HamClockWsprPreference) =
        if (preference.regionalEnabled) HamClockWsprRegionalState.UNAVAILABLE_POLICY else HamClockWsprRegionalState.DISABLED
}

internal data class HamClockIbpBeacon(
    val callsign: String,
    val grid: String,
    val point: GeoPoint,
    val locationLabel: String,
    val slotIndex: Int,
)
internal data class HamClockIbpTransmission(val beacon: HamClockIbpBeacon, val band: String, val frequencyHz: Long,
    val slot: Int, val slotStartEpoch: Long, val slotEndEpoch: Long)
internal data class HamClockIbpSchedule(val cycleStartEpoch: Long, val slot: Int, val transmissions: List<HamClockIbpTransmission>,
    val manifestVersion: String, val manifestHash: String)
internal data class HamClockIbpObservedEvidence(
    val beacon: HamClockIbpBeacon,
    val source: String,
    val band: String,
    val mode: String,
    val frequencyHz: Long,
    val receiver: String,
    val snr: Int?,
    val observedEpoch: Long,
)

private val ibpManifestRows = listOf(
    Triple("4U1UN", "FN30AS", "New York City"), Triple("VE8AT", "CP38GH", "Inuvik, NT"),
    Triple("W6WX", "CM97BD", "Mt. Umunhum"), Triple("KH6RS", "BL10TS", "Maui"),
    Triple("ZL6B", "RE78TW", "Masterton"), Triple("VK6RBP", "OF87AV", "Rolystone"),
    Triple("JA2IGY", "PM84JK", "Mt. Asama"), Triple("RR9O", "NO14KX", "Novosibirsk"),
    Triple("VR2B", "OL72BG", "Hong Kong"), Triple("4S7B", "MJ96WV", "Colombo, Sri Lanka"),
    Triple("ZS6DN", "KG33XI", "Pretoria"), Triple("5Z4B", "KI88HR", "Kikuyu"),
    Triple("4X6TU", "KM72JB", "Tel Aviv"), Triple("OH2B", "KP20EH", "Lohja"),
    Triple("CS3B", "IM12JT", "São Jorge"), Triple("LU4AA", "GF05TJ", "Buenos Aires"),
    Triple("OA4B", "FH17MW", "Lima"), Triple("YV5B", "FK60ND", "Caracas"),
)
internal val hamClockIbpManifest: List<HamClockIbpBeacon> = ibpManifestRows.mapIndexed { index, (call, grid, label) ->
    HamClockIbpBeacon(call, grid, requireNotNull(maidenheadCenter(grid)), label, index)
}
internal const val HAMCLOCK_IBP_MANIFEST_VERSION = "NCDXF-2026-08-20"
internal const val HAMCLOCK_IBP_MANIFEST_HASH = "c5a6333fca305bf35c4e9ded6a3c0885b0b217a6513b263f78923a34931fdc41"
internal const val HAMCLOCK_IBP_MANIFEST_SOURCE = "https://www.ncdxf.org/beacon/"
internal const val HAMCLOCK_IBP_MANIFEST_REVIEW_DATE = "2026-08-20"

internal fun hamClockIbpManifestHash(rows: List<HamClockIbpBeacon> = hamClockIbpManifest): String =
    MessageDigest.getInstance("SHA-256").digest(rows.joinToString("|") { "${it.callsign}:${it.grid}" }.toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun hamClockIbpSchedule(nowEpoch: Long = Instant.now().epochSecond): HamClockIbpSchedule {
    val cycleStart = nowEpoch - Math.floorMod(nowEpoch, 180L)
    val slot = ((nowEpoch - cycleStart) / 10L).toInt()
    val bands = listOf("20m" to 14_100_000L, "17m" to 18_110_000L, "15m" to 21_150_000L,
        "12m" to 24_930_000L, "10m" to 28_200_000L)
    val offsets = listOf(0, 17, 16, 15, 14)
    val transmissions = bands.mapIndexed { index, (band, frequency) ->
        val beacon = hamClockIbpManifest[(slot + offsets[index]) % hamClockIbpManifest.size]
        HamClockIbpTransmission(beacon, band, frequency, slot, cycleStart + slot * 10L, cycleStart + (slot + 1) * 10L)
    }
    return HamClockIbpSchedule(cycleStart, slot, transmissions, HAMCLOCK_IBP_MANIFEST_VERSION, HAMCLOCK_IBP_MANIFEST_HASH)
}

internal fun nextHamClockIbpTransmission(callsign: String, nowEpoch: Long = Instant.now().epochSecond): HamClockIbpTransmission? =
    (0..18).asSequence().map { hamClockIbpSchedule(nowEpoch + it * 10L) }
        .flatMap { it.transmissions.asSequence() }.firstOrNull { it.beacon.callsign.equals(callsign, true) }

internal fun observedIbpEvidence(evidence: List<HamClockBandEvidence>): List<HamClockIbpObservedEvidence> {
    val sites = hamClockIbpManifest.associateBy { it.callsign }
    return evidence.asSequence().filter { it.source in setOf("CLUSTER", "RBN") }
        .mapNotNull { row -> sites[row.callsign.uppercase(Locale.US)]?.let { site ->
            HamClockIbpObservedEvidence(site, row.source, row.band, row.mode, row.frequencyHz, row.receiver, row.snr, row.observedEpoch)
        } }.sortedByDescending(HamClockIbpObservedEvidence::observedEpoch).take(60).toList()
}

enum class HamClockEvidenceAvailability { CURRENT, DEGRADED, STALE, ERROR, UNAVAILABLE, DISABLED }
data class HamClockBandEvidence(val source: String, val band: String, val mode: String, val callsign: String,
    val receiver: String = "", val snr: Int? = null, val observedEpoch: Long, val frequencyHz: Long = 0L,
    val id: String = "$source|$callsign|$receiver|$frequencyHz|$observedEpoch")
data class HamClockBandHistoricalRow(val band: String, val modeFamily: String, val qsoCount: Int,
    val uniqueCalls: Int, val confirmedCount: Int, val comparableWindowCount: Int,
    val periodStartEpoch: Long, val periodEndEpoch: Long)
data class HamClockBandHealthRow(val band: String, val state: String, val observations: Int, val uniqueCalls: Int,
    val uniqueReceivers: Int, val medianSnr: Int?, val trend: String, val sourceCount: Int,
    val callDiversity: Double, val receiverDiversity: Double, val confidence: String,
    val historicalObservations: Int = 0, val reasons: List<String>)
data class HamClockBandHealthSnapshot(
    val preference: HamClockBandHealthPreference = HamClockBandHealthPreference(),
    val sourceStates: Map<String, HamClockEvidenceAvailability> = emptyMap(),
    val rows: List<HamClockBandHealthRow> = emptyList(),
    val historical: List<HamClockBandHistoricalRow> = emptyList(),
    val contributors: Map<String, List<HamClockBandContributor>> = emptyMap(),
    val contributingObservationIds: Map<String, List<String>> = emptyMap(),
    val generatedEpoch: Long = 0,
    val message: String = "",
)

data class HamClockBandContributor(
    val id: String,
    val source: String,
    val sourceReferenceId: String,
    val callsign: String,
    val receiver: String,
    val band: String,
    val mode: String,
    val frequencyHz: Long,
    val snr: Int?,
    val observedEpoch: Long,
)

internal data class HamClockBandHealthComputation(
    val rows: List<HamClockBandHealthRow>,
    val contributors: Map<String, List<HamClockBandContributor>>,
)

private fun contributorFor(row: HamClockBandEvidence): HamClockBandContributor {
    val prefix = row.source.lowercase(Locale.US)
    val stableId = if (row.id.startsWith("$prefix:") || row.id.startsWith("${row.source}|")) row.id else "$prefix:${row.id}"
    val sourceReference = when {
        row.id.startsWith("$prefix:") -> row.id.removePrefix("$prefix:")
        else -> row.id
    }
    return HamClockBandContributor(stableId, row.source, sourceReference, row.callsign, row.receiver,
        row.band, row.mode, row.frequencyHz, row.snr, row.observedEpoch)
}

private fun computeHamClockBandHealthAccepted(
    evidence: List<HamClockBandEvidence>,
    availability: Map<String, HamClockEvidenceAvailability>,
    preference: HamClockBandHealthPreference,
    nowEpoch: Long,
): HamClockBandHealthComputation {
    val contributors = linkedMapOf<String, List<HamClockBandContributor>>()
    val liveSources = preference.enabledSources.filter { it != "QSO_HISTORY" &&
        availability[it] in setOf(HamClockEvidenceAvailability.CURRENT, HamClockEvidenceAvailability.DEGRADED,
            HamClockEvidenceAvailability.STALE)
    }
    val cutoff = nowEpoch - preference.windowMinutes * 60L
    val rows = preference.visibleBands.sortedBy(::bandSortKey).map { band ->
        val historical = evidence.count { it.source == "QSO_HISTORY" && it.band.equals(band, true) }
        if (liveSources.isEmpty()) {
            contributors[band] = emptyList()
            return@map HamClockBandHealthRow(band, "NO LIVE EVIDENCE", 0, 0, 0, null,
                "UNKNOWN", 0, 0.0, 0.0, "NONE", historical,
                listOf("All selected live evidence sources are disabled or unavailable"))
        }
        val repeats = mutableMapOf<String, Int>()
        val uniqueEvents = LinkedHashMap<String, HamClockBandEvidence>()
        evidence.asSequence().filter { it.source in liveSources && it.band.equals(band, true) && it.observedEpoch >= cutoff }
            .filter { preference.mode == "ALL" || it.mode.equals(preference.mode, true) }
            .sortedByDescending(HamClockBandEvidence::observedEpoch)
            .forEach { row -> uniqueEvents.putIfAbsent(
                "${row.callsign.uppercase(Locale.US)}|${row.receiver.uppercase(Locale.US)}|${row.band}|${row.mode}|${row.observedEpoch / 30L}", row) }
        val accepted = uniqueEvents.values.asSequence().filter {
            val key = "${it.callsign.uppercase(Locale.US)}|${it.receiver.uppercase(Locale.US)}"
            (repeats.getOrDefault(key, 0) < 3).also { keep -> if (keep) repeats[key] = repeats.getOrDefault(key, 0) + 1 }
        }.toList()
        contributors[band] = accepted.map(::contributorFor)
        val calls = accepted.map { it.callsign }.filter(String::isNotBlank).toSet().size
        val receivers = accepted.map { it.receiver }.filter(String::isNotBlank).toSet().size
        val sources = accepted.map { it.source }.toSet().size
        val median = accepted.mapNotNull { it.snr }.sorted().let { values -> values.takeIf(List<Int>::isNotEmpty)?.get(values.size / 2) }
        val midpoint = nowEpoch - preference.windowMinutes * 30L
        val older = accepted.count { it.observedEpoch < midpoint }; val newer = accepted.size - older
        val trend = when { newer > older * 3 / 2 -> "RISING"; older > newer * 3 / 2 -> "FALLING"; else -> "STEADY" }
        val selectedSources = preference.enabledSources.mapNotNull { source -> availability[source]?.let { source to it } }
        val hasCurrent = selectedSources.any { it.second == HamClockEvidenceAvailability.CURRENT }
        val hasStale = selectedSources.any { it.second == HamClockEvidenceAvailability.STALE }
        val hasDegraded = selectedSources.any { it.second == HamClockEvidenceAvailability.DEGRADED }
        val hasError = selectedSources.any { it.second == HamClockEvidenceAvailability.ERROR }
        val degraded = hasDegraded || (hasCurrent && (hasStale || hasError))
        val hasUsableSource = hasCurrent || hasStale || hasDegraded
        val confidence = when {
            !hasUsableSource || degraded -> if (accepted.isNotEmpty()) "LOW" else "NONE"
            accepted.size >= 12 && sources >= 2 -> "HIGH"
            accepted.size >= 4 -> "MEDIUM"
            accepted.isNotEmpty() -> "LOW"
            else -> "NONE"
        }
        val activity = when { accepted.size >= 20 -> "HIGH ACTIVITY"; accepted.size >= 8 -> "GOOD ACTIVITY"; accepted.size >= 4 -> "ACTIVE"; accepted.isNotEmpty() -> "QUIET"; else -> "NO RECENT EVIDENCE" }
        val state = when { degraded -> "DEGRADED SOURCES"; !hasCurrent && hasStale -> "STALE EVIDENCE"; else -> activity }
        val sourceReasons = selectedSources.mapNotNull { (source, sourceState) ->
            if (sourceState in setOf(HamClockEvidenceAvailability.CURRENT, HamClockEvidenceAvailability.DISABLED)) null
            else "$source ${sourceState.name.lowercase(Locale.US)}"
        }
        val reasons = (if (accepted.isEmpty()) listOf("No selected observations inside the ${preference.windowMinutes} minute window")
            else listOf("${accepted.size} capped observations from $sources source${if (sources == 1) "" else "s"}",
                "$calls unique calls · $receivers unique receivers")) + sourceReasons
        HamClockBandHealthRow(band, state, accepted.size, calls, receivers, median, trend, sources,
            if (accepted.isEmpty()) 0.0 else calls.toDouble() / accepted.size,
            if (accepted.isEmpty()) 0.0 else receivers.toDouble() / accepted.size,
            confidence, historical, reasons)
    }
    return HamClockBandHealthComputation(rows, contributors)
}

internal fun computeHamClockBandHealth(
    evidence: List<HamClockBandEvidence>,
    availability: Map<String, HamClockEvidenceAvailability>,
    preference: HamClockBandHealthPreference,
    nowEpoch: Long = Instant.now().epochSecond,
): List<HamClockBandHealthRow> {
    return computeHamClockBandHealthAccepted(evidence, availability, preference, nowEpoch).rows
}

internal fun computeHamClockBandHealthSnapshot(
    evidence: List<HamClockBandEvidence>,
    availability: Map<String, HamClockEvidenceAvailability>,
    preference: HamClockBandHealthPreference,
    historical: List<HamClockBandHistoricalRow>,
    nowEpoch: Long = Instant.now().epochSecond,
): HamClockBandHealthSnapshot {
    val computed = computeHamClockBandHealthAccepted(evidence, availability, preference, nowEpoch)
    val rows = computed.rows.map { row ->
        row.copy(historicalObservations = historical.filter { it.band.equals(row.band, true) &&
            (preference.mode == "ALL" || it.modeFamily.equals(preference.mode, true)) }.sumOf(HamClockBandHistoricalRow::comparableWindowCount))
    }
    val ids = computed.contributors.mapValues { (_, value) -> value.map(HamClockBandContributor::id) }
    return HamClockBandHealthSnapshot(preference, availability.toMap(), rows, historical, computed.contributors, ids, nowEpoch)
}

private fun bandSortKey(band: String): Int = listOf("2190m", "630m", "160m", "80m", "60m", "40m", "30m",
    "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm", "23cm")
    .indexOf(if (band.equals("2200m", true)) "2190m" else band.lowercase(Locale.US)).let { if (it < 0) 999 else it }

private fun greatCircleDistanceKm(a: GeoPoint, b: GeoPoint): Double {
    val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
    val dp = p2 - p1; val dl = Math.toRadians(b.longitude - a.longitude)
    val h = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) + kotlin.math.cos(p1) * kotlin.math.cos(p2) *
        kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
    return 6371.0088 * 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
}
