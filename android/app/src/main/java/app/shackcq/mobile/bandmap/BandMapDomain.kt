// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class BandMapSource { DX_CLUSTER, RBN, PSK_REPORTER, PERSONAL_WSPR, POTA, SOTA, WWFF, N1MM_NETWORK, LOCAL_MARK }
internal enum class BandMapModeFamily { CW, PHONE, DIGI, FM, AM, UNKNOWN }
internal enum class BandMapAgeState { CURRENT, AGING, STALE, EXPIRED, PINNED_STALE }
internal enum class BandMapLayoutMode { MULTI_VERTICAL, MULTI_HORIZONTAL, GRID_OVERVIEW, SINGLE_EXPANDED }
internal enum class BandMapDirection { LOW_TO_HIGH, HIGH_TO_LOW }
internal enum class BandMapSegmentProfile { WHOLE, CW_DISPLAY, PHONE_DISPLAY, DIGI_DISPLAY, CUSTOM }
internal enum class BandMapIaruRegion { REGION_1, REGION_2, REGION_3, UNKNOWN }
internal enum class BandMapJurisdiction { STATION_PROFILE, IARU_REGION_1, IARU_REGION_2, IARU_REGION_3, REVIEWED_NATIONAL_PLAN, CUSTOM_PLAN }
internal enum class BandMapLabelMetadata { AGE, CALL_STATUS, DXCC_STATUS, BEARING, DISTANCE, MODE, SPOTTER, SOURCE, SNR }
internal enum class BandMapOperatingSegmentKind { CW, DATA, PHONE, FM_REPEATER, BEACON_SATELLITE, CUSTOM }
internal enum class BandMapEvidenceKind { CURRENT_OBSERVED, EMPIRICAL_OUTLOOK, HISTORICAL_PERSONAL }
internal enum class BandMapEvidenceStatus { POSITIVE, NEUTRAL, NEGATIVE, UNKNOWN, UNAVAILABLE }
internal enum class BandMapNeedTruth { NEEDED, WORKED, CONFIRMED, UNKNOWN }
internal enum class BandMapTraversal { FREQUENCY, PRIORITY, CONTEST, WATCHLIST }
internal enum class BandMapMarkKind { WATCH, PIN, HIDE }

internal data class BandMapBand(
    val name: String,
    val lowerHz: Long,
    val upperHz: Long,
)

internal val bandMapBands = listOf(
    BandMapBand("2190m", 135_700, 137_800), BandMapBand("630m", 472_000, 479_000),
    BandMapBand("560m", 501_000, 504_000), BandMapBand("160m", 1_800_000, 2_000_000),
    BandMapBand("80m", 3_500_000, 4_000_000), BandMapBand("60m", 5_250_000, 5_450_000),
    BandMapBand("40m", 7_000_000, 7_300_000), BandMapBand("30m", 10_100_000, 10_150_000),
    BandMapBand("20m", 14_000_000, 14_350_000), BandMapBand("17m", 18_068_000, 18_168_000),
    BandMapBand("15m", 21_000_000, 21_450_000), BandMapBand("12m", 24_890_000, 24_990_000),
    BandMapBand("10m", 28_000_000, 29_700_000), BandMapBand("8m", 40_000_000, 45_000_000),
    BandMapBand("6m", 50_000_000, 54_000_000), BandMapBand("5m", 54_000_000, 60_000_000),
    BandMapBand("4m", 69_900_000, 70_500_000), BandMapBand("2m", 144_000_000, 148_000_000),
    BandMapBand("1.25m", 219_000_000, 225_000_000), BandMapBand("70cm", 420_000_000, 450_000_000),
    BandMapBand("33cm", 902_000_000, 928_000_000), BandMapBand("23cm", 1_240_000_000, 1_300_000_000),
    BandMapBand("13cm", 2_300_000_000, 2_450_000_000), BandMapBand("9cm", 3_300_000_000, 3_500_000_000),
    BandMapBand("6cm", 5_650_000_000, 5_925_000_000), BandMapBand("3cm", 10_000_000_000, 10_500_000_000),
    BandMapBand("1.25cm", 24_000_000_000, 24_250_000_000), BandMapBand("6mm", 47_000_000_000, 47_200_000_000),
    BandMapBand("4mm", 75_500_000_000, 81_500_000_000), BandMapBand("2.5mm", 122_250_000_000, 123_000_000_000),
    BandMapBand("2mm", 134_000_000_000, 149_000_000_000), BandMapBand("1mm", 241_000_000_000, 250_000_000_000),
)

internal val bandMapVisibleBands = listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm", "23cm")

internal fun BandMapIaruRegion.description() = when (this) {
    BandMapIaruRegion.REGION_1 -> "Region 1 — Europe, Africa, Middle East and northern Asia"
    BandMapIaruRegion.REGION_2 -> "Region 2 — the Americas"
    BandMapIaruRegion.REGION_3 -> "Region 3 — the rest of Asia and the Pacific"
    BandMapIaruRegion.UNKNOWN -> "Use station profile — regional guidance unresolved"
}

internal data class BandMapOperatingSegment(
    val band: String,
    val kind: BandMapOperatingSegmentKind,
    val lowerHz: Long,
    val upperHz: Long,
    val label: String,
    val pattern: String,
)

internal data class BandMapDisplayPlan(
    val region: BandMapIaruRegion,
    val source: String = "ShackCQ reviewed IARU display guidance 2026-08",
    val regulatoryAuthority: Boolean = false,
    val segments: List<BandMapOperatingSegment>,
    val uncertainty: String = "Operating guidance only; 60m allocations are channelised and country-specific in many administrations.",
)

internal object BandMapDisplayPlans {
    fun forBand(band: String, region: BandMapIaruRegion): BandMapDisplayPlan {
        val definition = bandMapBands.first { it.name == band }
        val width = definition.upperHz - definition.lowerHz
        val split = when (region) {
            BandMapIaruRegion.REGION_1 -> listOf(0L, 18L, 38L, 100L)
            BandMapIaruRegion.REGION_2 -> listOf(0L, 25L, 45L, 100L)
            BandMapIaruRegion.REGION_3 -> listOf(0L, 20L, 40L, 100L)
            BandMapIaruRegion.UNKNOWN -> listOf(0L, 20L, 40L, 100L)
        }
        fun at(percent: Long) = definition.lowerHz + width * percent / 100L
        val fmShare = band in setOf("10m", "6m", "4m", "2m", "70cm", "23cm")
        val phoneUpper = if (fmShare) at(82L) else at(split[3])
        val rows = buildList {
            add(BandMapOperatingSegment(band, BandMapOperatingSegmentKind.CW, at(split[0]), at(split[1]), "CW", "solid"))
            add(BandMapOperatingSegment(band, BandMapOperatingSegmentKind.DATA, at(split[1]), at(split[2]), "DATA", "diagonal"))
            add(BandMapOperatingSegment(band, BandMapOperatingSegmentKind.PHONE, at(split[2]), phoneUpper, "SSB / PHONE", "dots"))
            if (fmShare) add(BandMapOperatingSegment(band, BandMapOperatingSegmentKind.FM_REPEATER, phoneUpper, at(100L), "FM / REPEATER", "crosshatch"))
        }
        return BandMapDisplayPlan(region, segments = rows)
    }
}

internal data class BandMapSegment(
    val band: String,
    val label: String = "Whole band",
    val lowerHz: Long = bandMapBands.firstOrNull { it.name == band }?.lowerHz ?: 0,
    val upperHz: Long = bandMapBands.firstOrNull { it.name == band }?.upperHz ?: Long.MAX_VALUE,
) {
    init { require(lowerHz >= 0 && upperHz > lowerHz) }
    val spanHz: Long get() = upperHz - lowerHz
}

internal data class BandMapViewport(val lowerHz: Long, val upperHz: Long) {
    init { require(lowerHz >= 0 && upperHz > lowerHz) }
    val spanHz: Long get() = upperHz - lowerHz
    fun asSegment(band: String) = BandMapSegment(band, "Visible viewport", lowerHz, upperHz)
}

internal fun bandMapDisplaySegment(band: String, profile: BandMapSegmentProfile): BandMapSegment {
    val definition = bandMapBands.first { it.name == band }
    val width = definition.upperHz - definition.lowerHz
    return when (profile) {
        BandMapSegmentProfile.WHOLE, BandMapSegmentProfile.CUSTOM -> BandMapSegment(band)
        BandMapSegmentProfile.CW_DISPLAY -> BandMapSegment(band, "CW display slice", definition.lowerHz, definition.lowerHz + width * 20 / 100)
        BandMapSegmentProfile.DIGI_DISPLAY -> BandMapSegment(band, "Digi display slice", definition.lowerHz + width * 10 / 100, definition.lowerHz + width * 40 / 100)
        BandMapSegmentProfile.PHONE_DISPLAY -> BandMapSegment(band, "Phone display slice", definition.lowerHz + width * 30 / 100, definition.upperHz)
    }
}

internal data class BandMapSourceObservation(
    val source: BandMapSource,
    val sourceId: String,
    val sourceLabel: String,
    val displayCallsign: String,
    val frequencyHz: Long,
    val observedEpoch: Long,
    val spotterCallsign: String = "",
    val spotterDxcc: String = "",
    val spotterContinent: String = "",
    val spotterCqZone: Int? = null,
    val spotterItuZone: Int? = null,
    val mode: String = "",
    val submode: String = "",
    val snr: Int? = null,
    val comment: String = "",
    val portableProgram: String = "",
    val portableReference: String = "",
    val targetDxcc: String = "",
    val targetContinent: String = "",
    val targetCqZone: Int? = null,
    val targetItuZone: Int? = null,
    val targetGrid: String = "",
    val distanceKm: Int? = null,
    val bearingDegrees: Int? = null,
)

internal data class BandMapEvidence(
    val kind: BandMapEvidenceKind,
    val status: BandMapEvidenceStatus,
    val source: String,
    val observedEpoch: Long = 0,
    val explanation: String = "",
    val providerHealthy: Boolean = true,
)

internal data class BandMapNeedState(
    val entity: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val band: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val mode: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val bandMode: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val grid: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val cqZone: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val ituZone: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val wpx: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val portableReference: BandMapNeedTruth = BandMapNeedTruth.UNKNOWN,
    val missingReasons: List<String> = emptyList(),
)

internal data class BandMapContestState(
    val active: Boolean = false,
    val validBandMode: Boolean? = null,
    val duplicate: Boolean? = null,
    val newMultipliers: Set<String> = emptySet(),
    val estimatedPoints: Int? = null,
    val expectedExchange: String = "",
    val claimedBy: String = "",
    val claimExpiresEpoch: Long = 0,
    val explanations: List<String> = emptyList(),
)

internal data class BandMapChaserState(
    val available: Boolean = false,
    val eligible: Boolean? = null,
    val priorityTier: String = "",
    val suppliedScore: Int? = null,
    val positiveReasons: List<String> = emptyList(),
    val penalties: List<String> = emptyList(),
    val ineligibilityReason: String = "",
    val currentTarget: Boolean = false,
    val engagedTarget: Boolean = false,
    val cooldownUntilEpoch: Long = 0,
    val currentEvidenceLabel: String = "",
    val outlookLabel: String = "",
    val generatedEpoch: Long = 0,
    val generation: Long = 0,
)

internal data class BandMapMark(
    val callsign: String,
    val band: String,
    val frequencyHz: Long,
    val kinds: Set<BandMapMarkKind>,
    val createdEpoch: Long,
    val note: String = "",
)

internal data class BandMapSpot(
    val id: String,
    val callsign: String,
    val displayCallsign: String,
    val frequencyHz: Long,
    val band: String,
    val modeFamily: BandMapModeFamily,
    val submode: String,
    val observations: List<BandMapSourceObservation>,
    val oldestObservationEpoch: Long,
    val newestObservationEpoch: Long,
    val need: BandMapNeedState = BandMapNeedState(),
    val contest: BandMapContestState = BandMapContestState(),
    val chaser: BandMapChaserState = BandMapChaserState(),
    val evidence: List<BandMapEvidence> = emptyList(),
    val marked: Set<BandMapMarkKind> = emptySet(),
) {
    val sources get() = observations.map { it.source }.toSet()
    val spotters get() = observations.map { it.spotterCallsign }.filter(String::isNotBlank).toSet()
    val portablePrograms get() = observations.map { it.portableProgram }.filter(String::isNotBlank).toSet()
}

internal data class BandMapPriorityComponent(val key: String, val value: Int, val explanation: String)
internal data class BandMapRankedSpot(
    val spot: BandMapSpot,
    val score: Int,
    val components: List<BandMapPriorityComponent>,
) {
    val explanation: String = components.filter { it.value != 0 }.sortedByDescending { abs(it.value) }
        .take(4).joinToString(" · ") { it.explanation }.ifBlank { "No active priority components" }
}

internal data class BandMapRankingWeights(
    val watch: Int = 30, val pin: Int = 12, val neededEntity: Int = 28, val neededSlot: Int = 18,
    val contestMultiplier: Int = 24, val contestNonDupe: Int = 8, val chaserPriority: Int = 14,
    val currentEvidence: Int = 10, val outlook: Int = 5, val diversity: Int = 4,
    val freshness: Int = 8, val duplicatePenalty: Int = -25, val stalePenalty: Int = -12,
) {
    init { listOf(watch, pin, neededEntity, neededSlot, contestMultiplier, contestNonDupe, chaserPriority,
        currentEvidence, outlook, diversity, freshness, duplicatePenalty, stalePenalty).forEach { require(it in -100..100) } }
}

internal data class BandMapFilter(
    val bands: Set<String> = setOf("40m", "20m", "15m", "10m"),
    val segments: List<BandMapSegment> = bands.map { BandMapSegment(it) },
    val modes: Set<BandMapModeFamily> = emptySet(),
    val sources: Set<BandMapSource> = emptySet(),
    val maximumAgeSeconds: Long = 3_600,
    val minimumSpotters: Int = 0,
    val minimumSourceDiversity: Int = 0,
    val spotterContinents: Set<String> = emptySet(),
    val targetContinents: Set<String> = emptySet(),
    val requiredNeeds: Set<String> = emptySet(),
    val contestOnly: Boolean = false,
    val multipliersOnly: Boolean = false,
    val hideDuplicates: Boolean = false,
    val chaserEligibleOnly: Boolean = false,
    val portablePrograms: Set<String> = emptySet(),
    val evidenceStatuses: Set<BandMapEvidenceStatus> = emptySet(),
    val search: String = "",
    val showUnknown: Boolean = true,
    val showStale: Boolean = true,
)

internal data class BandMapPreset(
    val id: String,
    val label: String,
    val filter: BandMapFilter,
    val layout: BandMapLayoutMode,
    val weights: BandMapRankingWeights = BandMapRankingWeights(),
    val builtIn: Boolean = false,
)

internal val builtInBandMapPresets = listOf(
    BandMapPreset("all-current", "All current", BandMapFilter(), BandMapLayoutMode.MULTI_VERTICAL, builtIn = true),
    BandMapPreset("needed", "Needed DX", BandMapFilter(requiredNeeds = setOf("ENTITY", "BAND_MODE")), BandMapLayoutMode.MULTI_VERTICAL, builtIn = true),
    BandMapPreset("contest", "Contest S&P", BandMapFilter(contestOnly = true, hideDuplicates = true), BandMapLayoutMode.MULTI_HORIZONTAL, builtIn = true),
    BandMapPreset("chaser", "DX Chaser context", BandMapFilter(chaserEligibleOnly = true), BandMapLayoutMode.GRID_OVERVIEW, builtIn = true),
    BandMapPreset("portable", "Portable activators", BandMapFilter(portablePrograms = setOf("POTA", "SOTA", "WWFF")), BandMapLayoutMode.MULTI_VERTICAL, builtIn = true),
    BandMapPreset("rf-now", "RF evidence now", BandMapFilter(evidenceStatuses = setOf(BandMapEvidenceStatus.POSITIVE)), BandMapLayoutMode.GRID_OVERVIEW, builtIn = true),
    BandMapPreset("watch", "Watchlist", BandMapFilter(), BandMapLayoutMode.SINGLE_EXPANDED, builtIn = true),
)

internal object BandMapSpotCanonicalizer {
    fun callsign(value: String): String = value.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9/\\-]"), "")
    fun band(raw: String, frequencyHz: Long): String {
        val normalized = raw.trim().lowercase(Locale.US).let { if (it == "2200m") "2190m" else it }
        return bandMapBands.firstOrNull { it.name == normalized }?.name
            ?: bandMapBands.firstOrNull { frequencyHz in it.lowerHz..it.upperHz }?.name.orEmpty()
    }
    fun mode(value: String, frequencyHz: Long): Pair<BandMapModeFamily, String> {
        val supplied = value.trim().uppercase(Locale.US)
        val family = when {
            supplied in setOf("CW", "CWR") -> BandMapModeFamily.CW
            supplied in setOf("SSB", "USB", "LSB") -> BandMapModeFamily.PHONE
            supplied in setOf("FM", "NFM", "WFM") -> BandMapModeFamily.FM
            supplied == "AM" -> BandMapModeFamily.AM
            supplied in setOf("FT8", "FT4", "RTTY", "PSK", "PSK31", "WSPR", "JT65", "DIGI", "DATA") -> BandMapModeFamily.DIGI
            supplied.isNotBlank() -> BandMapModeFamily.UNKNOWN
            frequencyHz > 0 && frequencyHz % 1_000L in 0L..500L -> BandMapModeFamily.UNKNOWN
            else -> BandMapModeFamily.UNKNOWN
        }
        return family to supplied
    }

    fun observation(input: BandMapSourceObservation): BandMapSourceObservation? {
        val call = callsign(input.displayCallsign)
        if (call.isBlank() || input.frequencyHz <= 0 || input.observedEpoch <= 0) return null
        return input.copy(displayCallsign = call, comment = input.comment.replace(Regex("[\\r\\n]"), " ").take(160))
    }
}

internal object BandMapAging {
    private val currentSeconds = mapOf(
        BandMapSource.RBN to 180L, BandMapSource.PSK_REPORTER to 900L, BandMapSource.PERSONAL_WSPR to 1_200L,
        BandMapSource.POTA to 900L, BandMapSource.SOTA to 900L, BandMapSource.WWFF to 900L,
        BandMapSource.DX_CLUSTER to 600L, BandMapSource.N1MM_NETWORK to 300L, BandMapSource.LOCAL_MARK to 0L,
    )
    private val expirySeconds = currentSeconds.mapValues { (_, value) -> maxOf(900L, value * 4) }

    fun state(spot: BandMapSpot, nowEpoch: Long): BandMapAgeState {
        val age = (nowEpoch - spot.newestObservationEpoch).coerceAtLeast(0)
        val current = spot.sources.maxOfOrNull { currentSeconds[it] ?: 300L } ?: 300L
        val expiry = spot.sources.maxOfOrNull { expirySeconds[it] ?: 1_200L } ?: 1_200L
        return when {
            age <= current -> BandMapAgeState.CURRENT
            age <= expiry / 2 -> BandMapAgeState.AGING
            age <= expiry -> BandMapAgeState.STALE
            BandMapMarkKind.PIN in spot.marked -> BandMapAgeState.PINNED_STALE
            else -> BandMapAgeState.EXPIRED
        }
    }
}

internal class BandMapSpotIndex(private val maximumObservations: Int = 20_000) {
    private val tolerances = mapOf(
        BandMapModeFamily.CW to 400L, BandMapModeFamily.DIGI to 80L, BandMapModeFamily.PHONE to 2_500L,
        BandMapModeFamily.FM to 5_000L, BandMapModeFamily.AM to 5_000L, BandMapModeFamily.UNKNOWN to 1_000L,
    )

    fun coalesce(rows: List<BandMapSourceObservation>, marks: List<BandMapMark> = emptyList()): List<BandMapSpot> {
        val canonical = rows.takeLast(maximumObservations).asSequence().mapNotNull(BandMapSpotCanonicalizer::observation)
            .sortedWith(compareBy<BandMapSourceObservation> { BandMapSpotCanonicalizer.callsign(it.displayCallsign) }
                .thenBy { it.frequencyHz }.thenBy { it.observedEpoch }.thenBy { it.source.name }).toList()
        val groups = mutableListOf<MutableList<BandMapSourceObservation>>()
        val buckets = mutableMapOf<String, MutableList<Int>>()
        canonical.forEach { row ->
            val call = BandMapSpotCanonicalizer.callsign(row.displayCallsign)
            val band = BandMapSpotCanonicalizer.band("", row.frequencyHz)
            val family = BandMapSpotCanonicalizer.mode(row.mode, row.frequencyHz).first
            val tolerance = tolerances[family] ?: 1_000L
            val frequencyBucket = row.frequencyHz / tolerance
            val prefix = "$call|$band|${family.name}|"
            val targetIndex = (-1L..1L).asSequence().flatMap { offset -> buckets[prefix + (frequencyBucket + offset)].orEmpty().asSequence() }
                .distinct().filter { index ->
                    val last = groups[index].last()
                    abs(last.frequencyHz - row.frequencyHz) <= tolerance && abs(last.observedEpoch - row.observedEpoch) <= 900
                }.maxOrNull()
            if (targetIndex == null) {
                groups += mutableListOf(row)
                buckets.getOrPut(prefix + frequencyBucket) { mutableListOf() } += groups.lastIndex
            } else {
                groups[targetIndex] += row
                buckets.getOrPut(prefix + frequencyBucket) { mutableListOf() }.let { if (targetIndex !in it) it += targetIndex }
            }
        }
        return groups.map { group ->
            val newest = group.maxBy(BandMapSourceObservation::observedEpoch)
            val call = BandMapSpotCanonicalizer.callsign(newest.displayCallsign)
            val band = BandMapSpotCanonicalizer.band("", newest.frequencyHz)
            val (family, submode) = BandMapSpotCanonicalizer.mode(newest.mode, newest.frequencyHz)
            val markKinds = marks.filter { it.callsign == call && it.band == band && abs(it.frequencyHz - newest.frequencyHz) <= (tolerances[family] ?: 1_000L) }
                .flatMap { it.kinds }.toSet()
            BandMapSpot(
                id = "$call|$band|${family.name}|${newest.frequencyHz}", callsign = call,
                displayCallsign = newest.displayCallsign, frequencyHz = newest.frequencyHz, band = band,
                modeFamily = family, submode = submode, observations = group.sortedByDescending { it.observedEpoch },
                oldestObservationEpoch = group.minOf { it.observedEpoch }, newestObservationEpoch = newest.observedEpoch,
                marked = markKinds,
            )
        }.sortedWith(compareBy<BandMapSpot> { bandMapBands.indexOfFirst { band -> band.name == it.band }.let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .thenBy(BandMapSpot::frequencyHz).thenBy(BandMapSpot::callsign))
    }
}

internal object BandMapFilterEngine {
    fun visible(spots: List<BandMapSpot>, filter: BandMapFilter, nowEpoch: Long): List<BandMapSpot> = spots.filter { spot ->
        val age = BandMapAging.state(spot, nowEpoch)
        val needle = filter.search.trim().uppercase(Locale.US)
        val segmentVisible = filter.segments.filter { it.band == spot.band }.let { rows -> rows.isEmpty() || rows.any { spot.frequencyHz in it.lowerHz..it.upperHz } }
        val needMatch = filter.requiredNeeds.isEmpty() || filter.requiredNeeds.any {
            when (it) { "ENTITY" -> spot.need.entity == BandMapNeedTruth.NEEDED; "BAND" -> spot.need.band == BandMapNeedTruth.NEEDED
                "MODE" -> spot.need.mode == BandMapNeedTruth.NEEDED; "BAND_MODE" -> spot.need.bandMode == BandMapNeedTruth.NEEDED
                "GRID" -> spot.need.grid == BandMapNeedTruth.NEEDED; "CQ" -> spot.need.cqZone == BandMapNeedTruth.NEEDED
                "ITU" -> spot.need.ituZone == BandMapNeedTruth.NEEDED; "PORTABLE" -> spot.need.portableReference == BandMapNeedTruth.NEEDED
                else -> filter.showUnknown && spot.need.missingReasons.isNotEmpty() }
        }
        val evidenceMatch = filter.evidenceStatuses.isEmpty() || spot.evidence.any { it.status in filter.evidenceStatuses }
        val spotterContinentMatch = filter.spotterContinents.isEmpty() || spot.observations.any { it.spotterContinent in filter.spotterContinents }
        val targetContinentMatch = filter.targetContinents.isEmpty() || spot.observations.any { it.targetContinent in filter.targetContinents }
        (filter.bands.isEmpty() || spot.band in filter.bands) && segmentVisible &&
            (filter.modes.isEmpty() || spot.modeFamily in filter.modes) &&
            (filter.sources.isEmpty() || spot.sources.any { it in filter.sources }) &&
            spot.newestObservationEpoch >= nowEpoch - filter.maximumAgeSeconds &&
            spot.spotters.size >= filter.minimumSpotters && spot.sources.size >= filter.minimumSourceDiversity &&
            spotterContinentMatch && targetContinentMatch && needMatch && evidenceMatch &&
            (!filter.contestOnly || spot.contest.active) && (!filter.multipliersOnly || spot.contest.newMultipliers.isNotEmpty()) &&
            (!filter.hideDuplicates || spot.contest.duplicate != true) && (!filter.chaserEligibleOnly || spot.chaser.eligible == true) &&
            (filter.portablePrograms.isEmpty() || spot.portablePrograms.any { it in filter.portablePrograms }) &&
            (needle.isBlank() || needle in spot.callsign || spot.observations.any { needle in it.comment.uppercase(Locale.US) || needle in it.portableReference.uppercase(Locale.US) }) &&
            BandMapMarkKind.HIDE !in spot.marked && (filter.showStale || age !in setOf(BandMapAgeState.STALE, BandMapAgeState.PINNED_STALE)) &&
            age != BandMapAgeState.EXPIRED
    }
}

internal object BandMapPriorityEngine {
    fun rank(spots: List<BandMapSpot>, weights: BandMapRankingWeights, nowEpoch: Long): List<BandMapRankedSpot> = spots.map { spot ->
        val components = buildList {
            if (BandMapMarkKind.WATCH in spot.marked) add(BandMapPriorityComponent("watch", weights.watch, "watchlisted"))
            if (BandMapMarkKind.PIN in spot.marked) add(BandMapPriorityComponent("pin", weights.pin, "pinned"))
            if (spot.need.entity == BandMapNeedTruth.NEEDED) add(BandMapPriorityComponent("needed-entity", weights.neededEntity, "needed entity"))
            if (spot.need.bandMode == BandMapNeedTruth.NEEDED) add(BandMapPriorityComponent("needed-slot", weights.neededSlot, "needed band-mode slot"))
            if (spot.contest.newMultipliers.isNotEmpty()) add(BandMapPriorityComponent("contest-multiplier", weights.contestMultiplier, "new contest multiplier"))
            if (spot.contest.active && spot.contest.duplicate == false) add(BandMapPriorityComponent("contest-nondupe", weights.contestNonDupe, "valid contest non-dupe"))
            if (spot.contest.duplicate == true) add(BandMapPriorityComponent("duplicate", weights.duplicatePenalty, "contest duplicate"))
            if (spot.chaser.eligible == true) add(BandMapPriorityComponent("chaser", weights.chaserPriority, "DX Chaser ${spot.chaser.priorityTier.ifBlank { "eligible" }}"))
            if (spot.evidence.any { it.kind == BandMapEvidenceKind.CURRENT_OBSERVED && it.status == BandMapEvidenceStatus.POSITIVE })
                add(BandMapPriorityComponent("current-evidence", weights.currentEvidence, "current observed evidence"))
            if (spot.evidence.any { it.kind == BandMapEvidenceKind.EMPIRICAL_OUTLOOK && it.status == BandMapEvidenceStatus.POSITIVE })
                add(BandMapPriorityComponent("outlook", weights.outlook, "positive empirical outlook"))
            if (spot.sources.size > 1) add(BandMapPriorityComponent("diversity", weights.diversity * (spot.sources.size - 1).coerceAtMost(4), "${spot.sources.size} independent source types"))
            if (BandMapAging.state(spot, nowEpoch) == BandMapAgeState.CURRENT) add(BandMapPriorityComponent("fresh", weights.freshness, "fresh observation"))
            if (BandMapAging.state(spot, nowEpoch) in setOf(BandMapAgeState.STALE, BandMapAgeState.PINNED_STALE)) add(BandMapPriorityComponent("stale", weights.stalePenalty, "stale observation"))
        }
        BandMapRankedSpot(spot, components.sumOf { it.value }, components)
    }.sortedWith(compareByDescending<BandMapRankedSpot> { it.score }.thenBy { it.spot.frequencyHz }.thenBy { it.spot.callsign })
}

internal data class BandMapTick(val frequencyHz: Long, val position: Float, val major: Boolean)
internal data class BandMapPlacedSpot(val id: String, val primary: Float, val lane: Int, val memberIds: List<String> = listOf(id)) {
    val stackCount: Int get() = memberIds.size
}

internal object BandMapLayoutEngine {
    fun coordinate(frequencyHz: Long, segment: BandMapSegment, direction: BandMapDirection = BandMapDirection.LOW_TO_HIGH): Float {
        val fraction = ((frequencyHz - segment.lowerHz).toDouble() / (segment.upperHz - segment.lowerHz).toDouble()).coerceIn(0.0, 1.0)
        return (if (direction == BandMapDirection.LOW_TO_HIGH) fraction else 1.0 - fraction).toFloat()
    }

    fun ticks(segment: BandMapSegment, pixels: Int): List<BandMapTick> {
        val target = (pixels / 90).coerceIn(2, 20)
        val raw = (segment.upperHz - segment.lowerHz).toDouble() / target
        val magnitude = listOf(10L, 25L, 50L).asSequence().flatMap { base -> generateSequence(base) { it * 10 }.take(12) }
            .filter { it >= raw }.minOrNull() ?: 1_000_000_000L
        val first = ((segment.lowerHz + magnitude - 1) / magnitude) * magnitude
        return generateSequence(first) { it + magnitude }.takeWhile { it <= segment.upperHz }.mapIndexed { index, frequency ->
            BandMapTick(frequency, coordinate(frequency, segment), index % 5 == 0)
        }.toList().take(64)
    }

    fun place(spots: List<BandMapSpot>, segment: BandMapSegment, pixels: Int, minimumSpacing: Int = 22): List<BandMapPlacedSpot> {
        val ordered = spots.filter { it.frequencyHz in segment.lowerHz..segment.upperHz }
            .sortedWith(compareBy<BandMapSpot>(BandMapSpot::frequencyHz).thenBy(BandMapSpot::callsign))
        val laneLast = IntArray(6) { Int.MIN_VALUE / 2 }
        val laneOutput = IntArray(6) { -1 }
        val result = mutableListOf<BandMapPlacedSpot>()
        ordered.forEach { spot ->
            val primary = (coordinate(spot.frequencyHz, segment) * pixels).roundToInt()
            val lane = laneLast.indexOfFirst { primary - it >= minimumSpacing }
            if (lane >= 0) {
                laneLast[lane] = primary
                laneOutput[lane] = result.size
                result += BandMapPlacedSpot(spot.id, primary.toFloat() / pixels.coerceAtLeast(1), lane)
            } else {
                val index = laneOutput[5]
                if (index >= 0) result[index] = result[index].copy(memberIds = (result[index].memberIds + spot.id).take(20))
            }
        }
        return result
    }

    fun fitVerticalLabels(
        placements: List<BandMapPlacedSpot>,
        heightPx: Float,
        labelHeightPx: Float,
        topPx: Float,
        bottomInsetPx: Float,
    ): List<BandMapPlacedSpot> {
        if (placements.isEmpty()) return emptyList()
        val bottomPx = (heightPx - bottomInsetPx - labelHeightPx).coerceAtLeast(topPx)
        val capacity = (((bottomPx - topPx) / labelHeightPx.coerceAtLeast(1f)).toInt() + 1).coerceAtLeast(1)
        val ordered = placements.sortedWith(compareBy<BandMapPlacedSpot>(BandMapPlacedSpot::primary).thenBy(BandMapPlacedSpot::id))
        if (ordered.size <= capacity) return ordered
        val available = (bottomPx - topPx).coerceAtLeast(1f)
        val bins = List(capacity) { mutableListOf<BandMapPlacedSpot>() }
        ordered.forEach { placed ->
            val desired = (placed.primary * heightPx).coerceIn(topPx, bottomPx)
            val bin = (((desired - topPx) / available) * capacity).toInt().coerceIn(0, capacity - 1)
            bins[bin] += placed
        }
        return bins.filter(MutableList<BandMapPlacedSpot>::isNotEmpty).map { group ->
            val representative = group.first()
            representative.copy(
                primary = group.map(BandMapPlacedSpot::primary).average().toFloat(),
                lane = 0,
                memberIds = group.flatMap(BandMapPlacedSpot::memberIds).distinct().take(40),
            )
        }
    }

    fun resolveVerticalLabels(
        placements: List<BandMapPlacedSpot>,
        heightPx: Float,
        labelHeightPx: Float,
        topPx: Float,
        bottomInsetPx: Float = 0f,
    ): Map<String, Float> {
        val bottomPx = (heightPx - bottomInsetPx - labelHeightPx).coerceAtLeast(topPx)
        val positions = mutableMapOf<String, Float>()
        var nextY = topPx
        placements.sortedBy(BandMapPlacedSpot::primary).forEach { placed ->
            val desired = (placed.primary * heightPx).coerceIn(topPx, bottomPx)
            val resolved = maxOf(desired, nextY).coerceAtMost(bottomPx)
            positions[placed.id] = resolved
            nextY = resolved + labelHeightPx
        }
        var previousY = bottomPx
        placements.sortedByDescending(BandMapPlacedSpot::primary).forEach { placed ->
            val resolved = minOf(positions[placed.id] ?: previousY, previousY).coerceAtLeast(topPx)
            positions[placed.id] = resolved
            previousY = resolved - labelHeightPx
        }
        return positions
    }
}
