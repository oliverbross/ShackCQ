package app.shackcq.mobile

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

internal enum class ProgressPeriod(val label: String) {
    DAYS_30("30 days"), DAYS_90("90 days"), MONTHS_12("12 months"), YEAR("This year"), LAST_YEAR("Last year"), ALL("All time")
}
internal data class ProgressPeriodBounds(val fromEpoch: Long?, val toEpochExclusive: Long?, val label: String)
internal fun progressPeriodBounds(period: ProgressPeriod, now: Long = Instant.now().epochSecond): ProgressPeriodBounds {
    val today = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate()
    val thisYear = today.withDayOfYear(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    return when (period) {
        ProgressPeriod.DAYS_30 -> ProgressPeriodBounds(now - 30 * 86_400L, null, "Last 30 days")
        ProgressPeriod.DAYS_90 -> ProgressPeriodBounds(now - 90 * 86_400L, null, "Last 90 days")
        ProgressPeriod.MONTHS_12 -> ProgressPeriodBounds(Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).minusMonths(12).toEpochSecond(), null, "Last 12 months")
        ProgressPeriod.YEAR -> ProgressPeriodBounds(thisYear, null, "${today.year}-01-01 UTC – now")
        ProgressPeriod.LAST_YEAR -> {
            val start = today.minusYears(1).withDayOfYear(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            ProgressPeriodBounds(start, thisYear, "${today.year - 1}-01-01 – ${today.year}-01-01 UTC")
        }
        ProgressPeriod.ALL -> ProgressPeriodBounds(null, null, "All time")
    }
}
internal enum class ProgressMode(val label: String) { ALL("All"), CW("CW"), PHONE("Phone"), DIGITAL("Digital") }
internal data class ProgressFilters(
    val allStations: Boolean = false, val stationProfileId: String = "", val stationCallsign: String = "",
    val period: ProgressPeriod = ProgressPeriod.ALL, val band: String = "", val mode: ProgressMode = ProgressMode.ALL,
    val submode: String = "", val operator: String = "", val confirmationSource: String = "",
    val portableProgram: String = "", val includeConflicted: Boolean = false, val includeDeleted: Boolean = false,
    val bands: Set<String> = emptySet(), val modeFamilies: Set<ProgressMode> = emptySet(),
    val submodes: Set<String> = emptySet(), val operators: Set<String> = emptySet(),
    val confirmationSources: Set<String> = emptySet(), val portablePrograms: Set<String> = emptySet(),
) {
    fun selectedBands() = bands.ifEmpty { band.takeIf(String::isNotBlank)?.let(::setOf).orEmpty() }
    fun selectedModeFamilies() = modeFamilies.filterNot { it == ProgressMode.ALL }.toSet()
        .ifEmpty { mode.takeIf { it != ProgressMode.ALL }?.let(::setOf).orEmpty() }
    fun selectedSubmodes() = submodes.ifEmpty { submode.takeIf(String::isNotBlank)?.let(::setOf).orEmpty() }
    fun selectedOperators() = operators.ifEmpty { operator.takeIf(String::isNotBlank)?.let(::setOf).orEmpty() }
    fun selectedConfirmationSources() = confirmationSources.ifEmpty { confirmationSource.takeIf(String::isNotBlank)?.let(::setOf).orEmpty() }
    fun selectedPortablePrograms() = portablePrograms.ifEmpty { portableProgram.takeIf(String::isNotBlank)?.let(::setOf).orEmpty() }
}

internal enum class ProgressGoalMetric(val label: String) {
    TOTAL_QSOS("Total QSOs"), DXCC_WORKED("Unique DXCC-style worked"),
    DXCC_CONFIRMED("LoTW/QSL-confirmed DXCC-style"), QRP_DXCC("QRP DXCC-style worked"),
    POTA_HUNTED("POTA parks hunted locally"), POTA_ACTIVATED("POTA parks activated locally"),
    POTA_SUCCESSFUL("Successful local POTA activations"), P2P("P2P QSOs"),
    SOTA_HUNTED("SOTA summits worked locally"), WWFF_HUNTED("WWFF references worked locally"),
    STATES("U.S. states worked locally"), CQ_ZONES("CQ zones worked locally"),
}
internal data class ProgressGoal(
    val id: String, val metric: ProgressGoalMetric, val target: Int, val name: String = metric.label,
    val band: String = "", val mode: ProgressMode = ProgressMode.ALL, val deadline: String = "",
)
internal data class GoalProgress(val goal: ProgressGoal, val current: Int) {
    val remaining get() = (goal.target - current).coerceAtLeast(0)
    val percent get() = if (goal.target <= 0) 0 else (current * 100 / goal.target).coerceIn(0, 100)
}
internal data class ProgressCoverage(val available: Int, val total: Int) { val label get() = "Based on $available of $total QSOs" }
internal data class ProgressCount(val worked: Int, val confirmed: Int)
internal data class ProgressBucket(val label: String, val count: Int)
internal data class ProgressHeatCell(val day: Int, val hour: Int, val count: Int)
internal data class ProgressContactPoint(
    val grid: String,
    val latitude: Double,
    val longitude: Double,
    val dxcc: String = "",
    val country: String = "",
)
internal data class ConfirmationProgress(val confirmed: Int, val total: Int) {
    val percent get() = if (total == 0) null else confirmed * 100.0 / total
}
internal data class GeographyProgress(val code: String, val label: String, val count: ProgressCount)
internal data class BestDxContact(val callsign: String, val country: String, val distanceKm: Double, val band: String, val mode: String)
internal enum class AwardKind(val label: String, val rule: String, val filterKey: String) {
    DXCC("DXCC", "Unique DXCC entities worked; local confirmation is paper QSL or LoTW.", "dxcc"),
    WAZ("CQ / WAZ", "CQ zones 1–40 worked.", "cqzone"),
    ITU("ITU Zones", "ITU zones 1–90 worked.", "ituzone"),
    WAC("WAC", "Six populated continental regions worked.", "continent"),
    WAS("WAS", "The 50 U.S. states worked; territories and D.C. are shown separately, not counted.", "state"),
    WPX("WPX", "Unique worked callsign prefixes derived from the logged callsign.", "wpx"),
    IOTA("IOTA", "Unique logged IOTA references worked.", "iota"),
    POTA("POTA", "Unique hunted POTA references in the local log.", "pota"),
    SOTA("SOTA", "Unique SOTA summit references worked.", "sota"),
    WWFF("WWFF", "Unique WWFF references worked.", "wwff"),
    QRP("QRP variants", "DXCC and continental units worked with known transmit power from 1–5 W.", "dxcc"),
}
internal data class AwardUnit(val code: String, val label: String, val qsos: Int, val confirmed: Boolean)
internal data class AwardEstimate(
    val kind: AwardKind, val count: ProgressCount, val target: Int? = null,
    val units: List<AwardUnit> = emptyList(), val missing: List<String> = emptyList(),
    val byBand: Map<String, ProgressCount> = emptyMap(), val byMode: Map<String, ProgressCount> = emptyMap(),
    val coverage: ProgressCoverage = ProgressCoverage(0, 0), val warning: String = "",
)
internal data class SatelliteAnalytics(
    val qsos: Int = 0, val satellites: Int = 0, val grids: Int = 0, val confirmed: Int = 0,
    val bySatellite: Map<String, Int> = emptyMap(), val byMode: Map<String, Int> = emptyMap(),
    val uniqueCalls: Int = 0, val ownGrids: Int = 0, val byBand: Map<String, Int> = emptyMap(),
    val workedConfirmed: Map<String, ProgressCount> = emptyMap(), val recentActivity: Map<String, Int> = emptyMap(),
)
internal data class AntennaAnalytics(
    val path: String, val qsos: Int, val confirmed: Int, val averageDistanceKm: Double?, val bestDistanceKm: Double?,
)
internal enum class NeedTarget { DX, PORTABLE, LOGBOOK }
internal data class ProgressNeed(
    val id: String, val title: String, val detail: String, val reasons: List<String>, val priority: Int,
    val target: NeedTarget, val dxSpot: AndroidDXSpot? = null, val portableSpot: PortableSpot? = null,
)
internal data class ActivationSummary(
    val sessionId: String, val ownParks: Set<String>, val qsos: Int, val uniqueCalls: Int, val p2p: Int,
    val startedAt: Long, val endedAt: Long, val bestDistanceKm: Double?,
) {
    val durationMinutes get() = if (qsos > 1) ((endedAt - startedAt).coerceAtLeast(0) / 60) else 0
    val qsoRate get() = if (durationMinutes > 0) qsos * 60.0 / durationMinutes else null
}
internal data class PortableProgress(
    val potaHunted: Set<String> = emptySet(), val potaActivated: Set<String> = emptySet(),
    val sotaHunted: Set<String> = emptySet(), val wwffHunted: Set<String> = emptySet(),
    val sotaAssociations: Set<String> = emptySet(), val sotaRegions: Set<String> = emptySet(),
    val p2pQsos: Int = 0, val successfulActivations: Int = 0, val nextPotaMilestone: Int = 10,
    val bestRoverDay: Pair<String, Int>? = null, val activations: List<ActivationSummary> = emptyList(),
    val portableByBand: Map<String, Int> = emptyMap(), val portableByMode: Map<String, Int> = emptyMap(),
)
internal data class ProgressSnapshot(
    val filteredQsos: List<Qso> = emptyList(), val totalQsos: Int = 0, val uniqueCalls: Int = 0,
    val dxcc: ProgressCount = ProgressCount(0, 0), val countries: Int = 0, val grids: Int = 0,
    val longestDistanceKm: Double? = null, val qrpQsos: Int = 0, val syncAttention: Int = 0,
    val coverage: Map<String, ProgressCoverage> = emptyMap(), val activity: List<ProgressBucket> = emptyList(),
    val bands: Map<String, Int> = emptyMap(), val modes: Map<String, Int> = emptyMap(),
    val heatmap: List<ProgressHeatCell> = emptyList(), val distance: List<ProgressBucket> = emptyList(),
    val contacts: List<ProgressContactPoint> = emptyList(), val needs: List<ProgressNeed> = emptyList(),
    val unconfirmedDxcc: List<Pair<String, List<Qso>>> = emptyList(),
    val states: ProgressCount = ProgressCount(0, 0), val zones: ProgressCount = ProgressCount(0, 0),
    val dxccByMode: Map<String, ProgressCount> = emptyMap(), val dxccByBand: Map<String, ProgressCount> = emptyMap(),
    val qrpDxcc: Int = 0, val portable: PortableProgress = PortableProgress(), val goals: List<GoalProgress> = emptyList(),
    val operators: Map<String, Int> = emptyMap(), val years: Map<String, Int> = emptyMap(),
    val months: Map<String, Int> = emptyMap(), val confirmations: Map<String, Int> = emptyMap(),
    val satellite: SatelliteAnalytics = SatelliteAnalytics(), val antennas: List<AntennaAnalytics> = emptyList(),
    val activeDays: Int = 0, val averageDistanceKm: Double? = null, val unconfirmedDxccCount: Int = 0,
    val continents: Map<String, ProgressCount> = emptyMap(), val cqZones: Map<String, ProgressCount> = emptyMap(),
    val ituZones: Map<String, ProgressCount> = emptyMap(), val gridsConfirmed: Int = 0,
    val geography: List<GeographyProgress> = emptyList(), val bestDx: List<BestDxContact> = emptyList(),
    val submodes: Map<String, Int> = emptyMap(), val recentDays: Map<String, Int> = emptyMap(),
    val localHeatmap: List<ProgressHeatCell> = emptyList(),
    val confirmationDetails: Map<String, ConfirmationProgress> = emptyMap(),
    val stationProfiles: Map<String, Int> = emptyMap(), val stationCallsigns: Map<String, Int> = emptyMap(),
    val radios: Map<String, Int> = emptyMap(), val awards: Map<AwardKind, AwardEstimate> = emptyMap(),
    val invalidContactGrids: Int = 0,
    val detailed: Boolean = false,
)

internal val canonicalUsStates = setOf(
    "AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID","IL","IN","IA","KS","KY","LA","ME","MD","MA",
    "MI","MN","MS","MO","MT","NE","NV","NH","NJ","NM","NY","NC","ND","OH","OK","OR","PA","RI","SC","SD","TN",
    "TX","UT","VT","VA","WA","WV","WI","WY",
)
internal fun progressModeFamily(raw: String): String = when (modeFamily(raw)) {
    "CW" -> "CW"; "SSB", "FM", "AM" -> "PHONE"; "FT8", "FT4", "RTTY", "DIGITAL" -> "DIGITAL"; else -> "OTHER"
}
internal fun isAwardConfirmed(qso: Qso) =
    qso.lotwReceived.trim().uppercase(Locale.US) in setOf("Y", "V") ||
        qso.qslReceived.trim().uppercase(Locale.US) in setOf("Y", "V")
internal fun qsoBand(qso: Qso) = qso.band.trim().lowercase(Locale.US).ifBlank { bandForFrequency(qso.frequencyHz) }
internal fun qsoMode(qso: Qso) = progressModeFamily(qso.submode.ifBlank { qso.mode })

internal fun filterProgressQsos(qsos: List<Qso>, filters: ProgressFilters, now: Long): List<Qso> {
    val bands = filters.selectedBands().map { it.lowercase(Locale.US) }.toSet()
    val modes = filters.selectedModeFamilies().map { it.name }.toSet()
    val submodes = filters.selectedSubmodes().map { it.uppercase(Locale.US) }.toSet()
    val operators = filters.selectedOperators().map { it.uppercase(Locale.US) }.toSet()
    val confirmations = filters.selectedConfirmationSources()
    val programmes = filters.selectedPortablePrograms()
    val bounds = progressPeriodBounds(filters.period, now)
    val start = bounds.fromEpoch ?: Long.MIN_VALUE
    return qsos.filter { qso ->
        val stationMatches = filters.allStations ||
            filters.stationProfileId.isNotBlank() && qso.stationProfileId == filters.stationProfileId ||
            filters.stationProfileId.isBlank() && filters.stationCallsign.isNotBlank() &&
                qso.stationCallsign.equals(filters.stationCallsign, true) ||
            filters.stationProfileId.isBlank() && filters.stationCallsign.isBlank()
        stationMatches && qso.createdAt >= start && (bounds.toEpochExclusive == null || qso.createdAt < bounds.toEpochExclusive) &&
            (bands.isEmpty() || qsoBand(qso) in bands) &&
            (modes.isEmpty() || qsoMode(qso) in modes) &&
            (submodes.isEmpty() || qso.submode.ifBlank { qso.mode }.uppercase(Locale.US) in submodes) &&
            (operators.isEmpty() || qso.operatorCallsign.uppercase(Locale.US) in operators) &&
            (confirmations.isEmpty() || confirmations.any { progressConfirmationMatches(qso, it) }) &&
            (programmes.isEmpty() || programmes.any { progressPortableMatches(qso, it) }) &&
            (filters.includeConflicted || !qso.syncState.equals("conflict", true)) &&
            (filters.includeDeleted || qso.syncState.lowercase(Locale.US) !in setOf("tombstone", "remote_deleted"))
    }
}

private fun progressConfirmationMatches(qso: Qso, source: String): Boolean = when (source.uppercase(Locale.US)) {
    "PAPER", "QSL" -> qso.qslReceived.isReceived()
    "LOTW" -> qso.lotwReceived.isReceived()
    "EQSL" -> qso.eqslReceived.isReceived()
    "QRZ" -> qso.qrzReceived.isReceived()
    "CLUBLOG" -> qso.clublogReceived.isReceived()
    "DCL" -> qso.dclReceived.isReceived()
    "AWARD" -> isAwardConfirmed(qso)
    "UNCONFIRMED" -> !isAwardConfirmed(qso)
    else -> true
}
private fun String.isReceived() = trim().uppercase(Locale.US) in setOf("Y", "V")
private fun progressPortableMatches(qso: Qso, program: String): Boolean = when (program.uppercase(Locale.US)) {
    "POTA" -> qso.potaRef.isNotBlank() || qso.potaRefs.isNotEmpty() || qso.myPotaRef.isNotBlank() || qso.myPotaRefs.isNotEmpty()
    "SOTA" -> qso.sotaRef.isNotBlank() || qso.mySotaRef.isNotBlank()
    "WWFF" -> qso.wwffRef.isNotBlank() || qso.myWwffRef.isNotBlank()
    "IOTA" -> qso.iota.isNotBlank() || qso.myIota.isNotBlank()
    else -> true
}

internal fun progressLogbookFilter(filters: ProgressFilters, now: Long = Instant.now().epochSecond): LogbookFilter {
    val bounds = progressPeriodBounds(filters.period, now)
    return LogbookFilter(
        fromEpochSeconds = bounds.fromEpoch,
        toEpochSecondsExclusive = bounds.toEpochExclusive,
        stationProfile = filters.stationProfileId.takeUnless { filters.allStations }.orEmpty(),
        stationCallsign = filters.stationCallsign.takeIf { !filters.allStations && filters.stationProfileId.isBlank() }.orEmpty(),
        band = filters.selectedBands().singleOrNull().orEmpty(),
        modeFamily = filters.selectedModeFamilies().singleOrNull()?.name.orEmpty(),
        submode = filters.selectedSubmodes().singleOrNull().orEmpty(),
        operator = filters.selectedOperators().singleOrNull().orEmpty(),
        confirmationSource = filters.selectedConfirmationSources().singleOrNull().orEmpty(),
        portableProgram = filters.selectedPortablePrograms().singleOrNull().orEmpty(),
        recordVisibility = when { filters.includeDeleted -> "ALL"; filters.includeConflicted -> "ACTIVE_AND_CONFLICTS"; else -> "ACTIVE" },
    )
}

private fun normalizedRefs(primary: String, values: List<String>, normalizer: (String) -> String) =
    (values + primary).map(normalizer).filter(String::isNotBlank).toSet()
private fun progressDxcc(qso: Qso, lookup: (String) -> AndroidCtyRecord?) =
    qso.dxcc.trim().uppercase(Locale.US).ifBlank { lookup(qso.callsign)?.dxcc.orEmpty().trim().uppercase(Locale.US) }
private fun nextPotaMilestone(value: Int): Int =
    listOf(10,20,30,40,50,75,100,200,300,400,500,600,700,800,900,1_000).firstOrNull { it > value }
        ?: ((value / 500) + 1) * 500
private fun activityBuckets(rows: List<Qso>, period: ProgressPeriod): List<ProgressBucket> =
    rows.groupingBy { qso ->
        val date = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC).toLocalDate()
        when (period) {
            ProgressPeriod.DAYS_30, ProgressPeriod.DAYS_90 -> date.toString()
            ProgressPeriod.MONTHS_12, ProgressPeriod.YEAR, ProgressPeriod.LAST_YEAR -> "%04d-%02d".format(date.year, date.monthValue)
            ProgressPeriod.ALL -> date.withDayOfMonth(1).toString().take(7)
        }
    }.eachCount().toSortedMap().map { ProgressBucket(it.key, it.value) }
private fun distanceBuckets(rows: List<Qso>): List<ProgressBucket> {
    val counts = IntArray(5)
    rows.map(Qso::distanceKm).filter { it > 0 }.forEach {
        counts[when { it < 500 -> 0; it < 2_000 -> 1; it < 5_000 -> 2; it < 10_000 -> 3; else -> 4 }]++
    }
    return listOf("<500","500–2k","2–5k","5–10k","10k+").mapIndexed { index, label -> ProgressBucket(label, counts[index]) }
}
private fun portableProgress(rows: List<Qso>, sotaSummits: Map<String, SotaSummit>): PortableProgress {
    val potaHunted = rows.flatMap { normalizedRefs(it.potaRef, it.potaRefs, ::normalizePotaReference) }.toSet()
    val potaActivated = rows.flatMap { normalizedRefs(it.myPotaRef, it.myPotaRefs, ::normalizePotaReference) }.toSet()
    val sotaHunted = rows.map { normalizeSotaReference(it.sotaRef) }.filter(String::isNotBlank).toSet()
    val wwffHunted = rows.map { normalizeWwffReference(it.wwffRef) }.filter(String::isNotBlank).toSet()
    val portableRows = rows.filter { it.potaRef.isNotBlank() || it.potaRefs.isNotEmpty() || it.sotaRef.isNotBlank() ||
        it.wwffRef.isNotBlank() || it.myPotaRef.isNotBlank() || it.myPotaRefs.isNotEmpty() }
    val sessions = rows.filter { it.activationSessionId.isNotBlank() }.groupBy(Qso::activationSessionId).map { (id, qsos) ->
        ActivationSummary(id, qsos.flatMap { normalizedRefs(it.myPotaRef, it.myPotaRefs, ::normalizePotaReference) }.toSet(),
            qsos.size, qsos.map { it.callsign.uppercase(Locale.US) }.distinct().size,
            qsos.count { normalizedRefs(it.myPotaRef,it.myPotaRefs,::normalizePotaReference).isNotEmpty() &&
                normalizedRefs(it.potaRef,it.potaRefs,::normalizePotaReference).isNotEmpty() },
            qsos.minOf(Qso::createdAt), qsos.maxOf(Qso::createdAt), qsos.map(Qso::distanceKm).filter { it > 0 }.maxOrNull())
    }.sortedByDescending(ActivationSummary::startedAt)
    val perParkDay = rows.flatMap { qso ->
        normalizedRefs(qso.myPotaRef,qso.myPotaRefs,::normalizePotaReference).map { ref ->
            Triple(ref, Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString(), qso.id)
        }
    }.groupBy { it.first to it.second }
    val rover = perParkDay.keys.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.toPair()
    val joinedSummits = sotaHunted.mapNotNull(sotaSummits::get)
    return PortableProgress(potaHunted,potaActivated,sotaHunted,wwffHunted,
        joinedSummits.map(SotaSummit::association).filter(String::isNotBlank).toSet(),
        joinedSummits.map(SotaSummit::region).filter(String::isNotBlank).toSet(),
        rows.count { normalizedRefs(it.myPotaRef,it.myPotaRefs,::normalizePotaReference).isNotEmpty() &&
            normalizedRefs(it.potaRef,it.potaRefs,::normalizePotaReference).isNotEmpty() },
        perParkDay.count { it.value.map { row -> row.third }.distinct().size >= 10 }, nextPotaMilestone(potaHunted.size),
        rover,sessions,portableRows.groupingBy(::qsoBand).eachCount().filterKeys(String::isNotBlank),
        portableRows.groupingBy(::qsoMode).eachCount())
}
internal fun metricValue(metric: ProgressGoalMetric, s: ProgressSnapshot) = when (metric) {
    ProgressGoalMetric.TOTAL_QSOS -> s.totalQsos; ProgressGoalMetric.DXCC_WORKED -> s.dxcc.worked
    ProgressGoalMetric.DXCC_CONFIRMED -> s.dxcc.confirmed; ProgressGoalMetric.QRP_DXCC -> s.qrpDxcc
    ProgressGoalMetric.POTA_HUNTED -> s.portable.potaHunted.size; ProgressGoalMetric.POTA_ACTIVATED -> s.portable.potaActivated.size
    ProgressGoalMetric.POTA_SUCCESSFUL -> s.portable.successfulActivations; ProgressGoalMetric.P2P -> s.portable.p2pQsos
    ProgressGoalMetric.SOTA_HUNTED -> s.portable.sotaHunted.size; ProgressGoalMetric.WWFF_HUNTED -> s.portable.wwffHunted.size
    ProgressGoalMetric.STATES -> s.states.worked; ProgressGoalMetric.CQ_ZONES -> s.zones.worked
}

internal fun wpxPrefix(rawCallsign: String): String {
    val parts = rawCallsign.uppercase(Locale.US).trim().split('/').filter(String::isNotBlank)
    val base = parts.firstOrNull { it.any(Char::isDigit) && it.any(Char::isLetter) }.orEmpty()
    val portableDigit = parts.drop(1).firstOrNull { it.length == 1 && it[0].isDigit() }
    if (portableDigit != null) return base.takeWhile(Char::isLetter).ifBlank { base.take(1) } + portableDigit
    val digit = base.indexOfFirst(Char::isDigit)
    return if (digit < 0) "" else base.take(digit + 1)
}

private fun awardEstimate(
    kind: AwardKind,
    rows: List<Qso>,
    unitsFor: (Qso) -> Set<String>,
    labels: Map<String, String> = emptyMap(),
    universe: Set<String>? = null,
    target: Int? = universe?.size,
    warning: String = "",
): AwardEstimate {
    val unitsByQso = rows.associateWith { unitsFor(it).map(String::uppercase).filter(String::isNotBlank).toSet() }
    val worked = unitsByQso.values.flatten().toSet()
    val confirmed = unitsByQso.filterKeys(::isAwardConfirmed).values.flatten().toSet()
    fun scopedCounts(grouped: Map<String, List<Qso>>) = grouped.mapValues { (_, scoped) ->
        val scopedWorked = scoped.flatMap { unitsByQso.getValue(it) }.toSet()
        ProgressCount(scopedWorked.size, scoped.filter(::isAwardConfirmed).flatMap { unitsByQso.getValue(it) }.toSet().size)
    }.filterKeys(String::isNotBlank)
    val unitRows = worked.sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }).map { code ->
        AwardUnit(code, labels[code].orEmpty().ifBlank { code }, unitsByQso.count { code in it.value }, code in confirmed)
    }
    val available = unitsByQso.count { it.value.isNotEmpty() }
    return AwardEstimate(kind, ProgressCount(worked.size, confirmed.size), target, unitRows,
        universe?.minus(worked).orEmpty().sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }),
        scopedCounts(rows.groupBy(::qsoBand)), scopedCounts(rows.groupBy(::qsoMode)),
        ProgressCoverage(available, rows.size), warning)
}

private fun buildAwardEstimates(
    rows: List<Qso>,
    dxccFor: (Qso) -> String,
    countryFor: (Qso) -> String,
    continentFor: (Qso) -> String,
    cqFor: (Qso) -> String,
    ituFor: (Qso) -> String,
): Map<AwardKind, AwardEstimate> {
    val dxccLabels = rows.mapNotNull { qso -> dxccFor(qso).takeIf(String::isNotBlank)?.let { it.uppercase(Locale.US) to countryFor(qso) } }.toMap()
    val continents = setOf("AF", "AS", "EU", "NA", "OC", "SA")
    fun single(value: String) = value.trim().uppercase(Locale.US).takeIf(String::isNotBlank)?.let(::setOf).orEmpty()
    fun pota(qso: Qso) = normalizedRefs(qso.potaRef, qso.potaRefs, ::normalizePotaReference)
    val qrp = rows.filter { it.txPowerW in 1..5 }
    return linkedMapOf(
        AwardKind.DXCC to awardEstimate(AwardKind.DXCC, rows, { single(dxccFor(it)) }, dxccLabels, target = 100),
        AwardKind.WAZ to awardEstimate(AwardKind.WAZ, rows, { single(cqFor(it).toIntOrNull()?.takeIf { zone -> zone in 1..40 }?.toString().orEmpty()) }, universe = (1..40).map(Int::toString).toSet()),
        AwardKind.ITU to awardEstimate(AwardKind.ITU, rows, { single(ituFor(it).toIntOrNull()?.takeIf { zone -> zone in 1..90 }?.toString().orEmpty()) }, universe = (1..90).map(Int::toString).toSet()),
        AwardKind.WAC to awardEstimate(AwardKind.WAC, rows, { single(continentFor(it)).intersect(continents) }, universe = continents),
        AwardKind.WAS to awardEstimate(AwardKind.WAS, rows, { single(it.state).intersect(canonicalUsStates) }, universe = canonicalUsStates),
        AwardKind.WPX to awardEstimate(AwardKind.WPX, rows, { single(wpxPrefix(it.callsign)) }, warning = "WPX is a practical callsign-prefix estimate; special-event and portable-prefix adjudication may differ."),
        AwardKind.IOTA to awardEstimate(AwardKind.IOTA, rows, { single(it.iota) }, warning = "No worldwide IOTA denominator is bundled."),
        AwardKind.POTA to awardEstimate(AwardKind.POTA, rows, ::pota, warning = "Local hunted references; verify official programme credit in POTA."),
        AwardKind.SOTA to awardEstimate(AwardKind.SOTA, rows, { single(normalizeSotaReference(it.sotaRef)) }, warning = "Local summit references; official points and validity are not claimed."),
        AwardKind.WWFF to awardEstimate(AwardKind.WWFF, rows, { single(normalizeWwffReference(it.wwffRef)) }, warning = "No worldwide WWFF denominator is bundled."),
        AwardKind.QRP to awardEstimate(AwardKind.QRP, qrp, { single(dxccFor(it)) }, dxccLabels, target = 100,
            warning = "Based only on QSOs with recorded transmit power from 1–5 W; ${rows.count { it.txPowerW > 0 }} of ${rows.size} QSOs have power data."),
    )
}

internal fun buildProgressSnapshot(
    qsos: List<Qso>, filters: ProgressFilters, goals: List<ProgressGoal> = emptyList(),
    dxSpots: List<AndroidDXSpot> = emptyList(), portableSpots: List<PortableSpot> = emptyList(),
    sotaSummits: Map<String, SotaSummit> = emptyMap(),
    syncAttention: Int = 0, now: Long = Instant.now().epochSecond,
    ctyLookup: (String) -> AndroidCtyRecord? = { null },
): ProgressSnapshot {
    val rows = filterProgressQsos(qsos, filters, now)
    val resolvedByQso = rows.associateWith { qso -> if (qso.dxcc.isBlank()) ctyLookup(qso.callsign) else null }
    fun dxccFor(qso: Qso) = qso.dxcc.trim().uppercase(Locale.US).ifBlank { resolvedByQso[qso]?.dxcc.orEmpty().trim().uppercase(Locale.US) }
    fun countryFor(qso: Qso) = qso.country.trim().ifBlank { resolvedByQso[qso]?.country.orEmpty().trim() }
    fun continentFor(qso: Qso) = qso.continent.trim().uppercase(Locale.US).ifBlank { resolvedByQso[qso]?.continent.orEmpty().trim().uppercase(Locale.US) }
    fun cqFor(qso: Qso) = qso.cqZone.trim().ifBlank { resolvedByQso[qso]?.cqZone.orEmpty().trim() }
    fun ituFor(qso: Qso) = qso.ituZone.trim().ifBlank { resolvedByQso[qso]?.ituZone.orEmpty().trim() }
    val dxccByQso = rows.associateWith(::dxccFor)
    val workedDxcc = dxccByQso.values.filter(String::isNotBlank).toSet()
    val confirmedDxcc = dxccByQso.filter { isAwardConfirmed(it.key) }.values.filter(String::isNotBlank).toSet()
    val states = rows.map { it.state.trim().uppercase(Locale.US) }.filter(canonicalUsStates::contains).toSet()
    val confirmedStates = rows.filter(::isAwardConfirmed).map { it.state.trim().uppercase(Locale.US) }.filter(canonicalUsStates::contains).toSet()
    val zones = rows.mapNotNull { cqFor(it).toIntOrNull()?.takeIf { z -> z in 1..40 } }.toSet()
    val confirmedZones = rows.filter(::isAwardConfirmed).mapNotNull { cqFor(it).toIntOrNull()?.takeIf { z -> z in 1..40 } }.toSet()
    val byMode = listOf("CW","PHONE","DIGITAL").associateWith { family ->
        val familyRows = rows.filter { qsoMode(it) == family }
        ProgressCount(familyRows.map { dxccByQso.getValue(it) }.filter(String::isNotBlank).distinct().size,
            familyRows.filter(::isAwardConfirmed).map { dxccByQso.getValue(it) }.filter(String::isNotBlank).distinct().size)
    }
    val byBand = rows.map(::qsoBand).filter { it.isNotBlank() && it != "60m" }.distinct().associateWith { band ->
        val bandRows = rows.filter { qsoBand(it) == band }
        ProgressCount(bandRows.map { dxccByQso.getValue(it) }.filter(String::isNotBlank).distinct().size,
            bandRows.filter(::isAwardConfirmed).map { dxccByQso.getValue(it) }.filter(String::isNotBlank).distinct().size)
    }
    val portable = portableProgress(rows, sotaSummits)
    val awards = buildAwardEstimates(rows, ::dxccFor, ::countryFor, ::continentFor, ::cqFor, ::ituFor)
    val awardDxcc = awards.getValue(AwardKind.DXCC)
    val awardWaz = awards.getValue(AwardKind.WAZ)
    val awardItu = awards.getValue(AwardKind.ITU)
    val needs = buildList {
        dxSpots.forEach { spot ->
            val resolved = ctyLookup(spot.callsign)
            val dxcc = resolved?.dxcc.orEmpty().trim().uppercase(Locale.US)
            if (dxcc.isBlank()) return@forEach
            val reasons = buildList {
                if (dxcc !in awardDxcc.units.map(AwardUnit::code)) add("NEEDED DXCC · ${spot.band.uppercase(Locale.US)} ${progressModeFamily(spot.mode)}") else {
                    if (awardDxcc.units.firstOrNull { it.code == dxcc }?.confirmed == false) add("UNCONFIRMED DXCC")
                    val entityRows = rows.filter { dxccByQso[it] == dxcc }
                    if (entityRows.none { qsoBand(it) == spot.band.lowercase(Locale.US) }) add("NEEDED DXCC · ${spot.band.uppercase(Locale.US)}")
                    if (entityRows.none { qsoMode(it) == progressModeFamily(spot.mode) }) add("NEEDED DXCC · ${progressModeFamily(spot.mode)}")
                }
                resolved?.cqZone?.toIntOrNull()?.takeIf { it in 1..40 && awardWaz.units.none { unit -> unit.code == it.toString() } }?.let { add("NEEDED CQ ZONE · $it") }
                resolved?.ituZone?.toIntOrNull()?.takeIf { it in 1..90 && awardItu.units.none { unit -> unit.code == it.toString() } }?.let { add("NEEDED ITU ZONE · $it") }
            }
            if (reasons.isNotEmpty()) add(ProgressNeed("dx:${spot.id}",spot.callsign,
                "${resolved?.country.orEmpty()} · ${spot.band} · ${spot.mode}",reasons,reasons.size*20+spot.score.coerceIn(0,20),
                NeedTarget.DX,dxSpot=spot))
        }
        portableSpots.filter { it.activeAt(now) }.forEach { spot ->
            val reasons = spot.references.mapNotNull { ref -> when (ref.program) {
                PortableProgram.POTA -> "NEW POTA REFERENCE".takeIf { normalizePotaReference(ref.code) !in portable.potaHunted }
                PortableProgram.SOTA -> "NEW SOTA SUMMIT".takeIf { normalizeSotaReference(ref.code) !in portable.sotaHunted }
                PortableProgram.WWFF -> "NEW WWFF REFERENCE".takeIf { normalizeWwffReference(ref.code) !in portable.wwffHunted }
            } }.distinct()
            if (reasons.isNotEmpty()) add(ProgressNeed("portable:${spot.id}",spot.callsign,
                "${spot.primary.code} · ${spot.band} · ${spot.mode}",reasons,reasons.size*20,
                NeedTarget.PORTABLE,portableSpot=spot))
        }
    }.sortedByDescending(ProgressNeed::priority)
    val coverage = linkedMapOf(
        "DXCC" to rows.count { dxccFor(it).isNotBlank() }, "CQ zone" to rows.count { cqFor(it).toIntOrNull()?.let { zone -> zone in 1..40 } == true },
        "ITU zone" to rows.count { ituFor(it).toIntOrNull()?.let { zone -> zone in 1..90 } == true },
        "U.S. state" to rows.count { it.state.uppercase(Locale.US) in canonicalUsStates },
        "Grid" to rows.count { maidenheadCenter(it.grid) != null }, "Distance" to rows.count { it.distanceKm > 0 },
        "TX power" to rows.count { it.txPowerW > 0 },
        "Station profile" to rows.count { it.stationProfileId.isNotBlank() || it.stationCallsign.isNotBlank() },
        "Portable reference" to rows.count { it.potaRef.isNotBlank() || it.potaRefs.isNotEmpty() || it.sotaRef.isNotBlank() || it.wwffRef.isNotBlank() },
    ).mapValues { ProgressCoverage(it.value,rows.size) }
    val contacts = rows.mapNotNull { q -> maidenheadCenter(q.grid)?.let {
        ProgressContactPoint(q.grid.uppercase(Locale.US), it.latitude, it.longitude, dxccFor(q), countryFor(q))
    } }
        .distinctBy(ProgressContactPoint::grid)
    val base = ProgressSnapshot(rows,rows.size,rows.map { it.callsign.uppercase(Locale.US) }.filter(String::isNotBlank).distinct().size,
        ProgressCount(workedDxcc.size,confirmedDxcc.size),
        rows.map(::countryFor).map(String::trim).filter(String::isNotBlank).distinctBy { it.uppercase(Locale.US) }.size,
        rows.map(Qso::grid).map(String::trim).filter(String::isNotBlank).distinctBy { it.uppercase(Locale.US) }.size,
        rows.map(Qso::distanceKm).filter { it > 0 }.maxOrNull(),rows.count { it.txPowerW in 1..5 },syncAttention,
        coverage,activityBuckets(rows,filters.period),rows.groupingBy(::qsoBand).eachCount().filterKeys(String::isNotBlank),
        rows.groupingBy(::qsoMode).eachCount(),
        rows.groupingBy { val d=Instant.ofEpochSecond(it.createdAt).atZone(ZoneOffset.UTC); d.dayOfWeek.value-1 to d.hour }
            .eachCount().map { ProgressHeatCell(it.key.first,it.key.second,it.value) },
        distanceBuckets(rows),contacts,needs,
        dxccByQso.filterValues(String::isNotBlank).entries.groupBy { it.value }.filter { it.key !in confirmedDxcc }
            .map { it.key to it.value.map { entry -> entry.key } }.sortedBy { it.first },
        ProgressCount(states.size,confirmedStates.size),ProgressCount(zones.size,confirmedZones.size),byMode,byBand,
        rows.filter { it.txPowerW in 1..5 }.map { dxccByQso.getValue(it) }.filter(String::isNotBlank).distinct().size,portable)
    fun received(value: String) = value.trim().uppercase(Locale.US) in setOf("Y", "V")
    val satelliteRows = rows.filter { qso -> qso.band.equals("SAT", true) || qso.propagationMode.equals("SAT", true) ||
        qso.extraAdifFields["SAT_NAME"].orEmpty().isNotBlank() }
    val satellite = SatelliteAnalytics(
        qsos = satelliteRows.size,
        satellites = satelliteRows.map { it.extraAdifFields["SAT_NAME"].orEmpty().uppercase(Locale.US) }.filter(String::isNotBlank).distinct().size,
        grids = satelliteRows.map(Qso::grid).filter(String::isNotBlank).distinctBy { it.uppercase(Locale.US) }.size,
        confirmed = satelliteRows.count(::isAwardConfirmed),
        bySatellite = satelliteRows.map { it.extraAdifFields["SAT_NAME"].orEmpty().uppercase(Locale.US).ifBlank { "UNKNOWN" } }.groupingBy { it }.eachCount(),
        byMode = satelliteRows.map { it.extraAdifFields["SAT_MODE"].orEmpty().uppercase(Locale.US).ifBlank { it.submode.ifBlank { it.mode } } }.groupingBy { it }.eachCount(),
        uniqueCalls = satelliteRows.map(Qso::callsign).filter(String::isNotBlank).distinctBy { it.uppercase(Locale.US) }.size,
        ownGrids = satelliteRows.map(Qso::myGrid).filter(String::isNotBlank).distinctBy { it.uppercase(Locale.US) }.size,
        byBand = satelliteRows.map(::qsoBand).filter(String::isNotBlank).groupingBy { it }.eachCount(),
        workedConfirmed = satelliteRows.groupBy { it.extraAdifFields["SAT_NAME"].orEmpty().uppercase(Locale.US).ifBlank { "UNKNOWN" } }
            .mapValues { (_, values) -> ProgressCount(values.size, values.filter(::isAwardConfirmed).map(Qso::grid).filter(String::isNotBlank).distinct().size) },
        recentActivity = satelliteRows.groupingBy { Instant.ofEpochSecond(it.createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString() }.eachCount(),
    )
    val antennas = rows.filter { it.antennaPath.isNotBlank() }.groupBy { it.antennaPath.trim().uppercase(Locale.US) }.map { (path, values) ->
        val distances = values.map(Qso::distanceKm).filter { it > 0 }
        AntennaAnalytics(path, values.size, values.count(::isAwardConfirmed), distances.average().takeUnless(Double::isNaN), distances.maxOrNull())
    }.sortedByDescending(AntennaAnalytics::qsos)
    fun groupedProgress(values: (Qso) -> String, valid: (String) -> Boolean = String::isNotBlank) = rows
        .map { it to values(it).trim().uppercase(Locale.US) }.filter { valid(it.second) }.groupBy(Pair<Qso, String>::second)
        .mapValues { (_, pairs) -> ProgressCount(pairs.size, pairs.count { isAwardConfirmed(it.first) }) }.toSortedMap()
    val geography = dxccByQso.filterValues(String::isNotBlank).entries.groupBy(Map.Entry<Qso, String>::value).map { (code, entries) ->
        GeographyProgress(code, entries.map { countryFor(it.key) }.firstOrNull(String::isNotBlank).orEmpty().ifBlank { "Country unavailable" },
            ProgressCount(entries.size, entries.count { isAwardConfirmed(it.key) }))
    }.sortedByDescending { it.count.worked }
    val distances = rows.map(Qso::distanceKm).filter { it > 0 }
    val localZone = ZoneId.systemDefault()
    val confirmationDetails = linkedMapOf(
        "Paper QSL" to rows.count { received(it.qslReceived) }, "LoTW" to rows.count { received(it.lotwReceived) },
        "eQSL" to rows.count { received(it.eqslReceived) }, "QRZ" to rows.count { received(it.qrzReceived) },
        "Club Log" to rows.count { received(it.clublogReceived) }, "DCL" to rows.count { received(it.dclReceived) },
    ).mapValues { ConfirmationProgress(it.value, rows.size) }
    return base.copy(
        goals = goals.take(4).map { GoalProgress(it,metricValue(it.metric,base)) },
        operators = rows.map { it.operatorCallsign.trim().uppercase(Locale.US).ifBlank { "UNKNOWN" } }.groupingBy { it }.eachCount(),
        years = rows.groupingBy { Instant.ofEpochSecond(it.createdAt).atZone(ZoneOffset.UTC).year.toString() }.eachCount().toSortedMap(),
        months = rows.groupingBy { Instant.ofEpochSecond(it.createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString().take(7) }.eachCount().toSortedMap(),
        confirmations = confirmationDetails.mapValues { it.value.confirmed },
        satellite = satellite, antennas = antennas,
        activeDays = rows.map { Instant.ofEpochSecond(it.createdAt).atZone(ZoneOffset.UTC).toLocalDate() }.distinct().size,
        averageDistanceKm = distances.average().takeUnless(Double::isNaN), unconfirmedDxccCount = workedDxcc.size - confirmedDxcc.size,
        continents = groupedProgress(::continentFor),
        cqZones = groupedProgress(::cqFor) { it.toIntOrNull() in 1..40 },
        ituZones = groupedProgress(::ituFor) { it.toIntOrNull() in 1..90 },
        gridsConfirmed = rows.filter(::isAwardConfirmed).map { it.grid.trim().uppercase(Locale.US) }.filter(String::isNotBlank).distinct().size,
        geography = geography,
        bestDx = rows.filter { it.distanceKm > 0 }.sortedByDescending(Qso::distanceKm).take(20).map {
            BestDxContact(it.callsign, countryFor(it), it.distanceKm, qsoBand(it), it.submode.ifBlank { it.mode })
        },
        submodes = rows.map { it.submode.ifBlank { it.mode }.trim().uppercase(Locale.US) }.filter(String::isNotBlank).groupingBy { it }.eachCount(),
        recentDays = rows.groupingBy { Instant.ofEpochSecond(it.createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString() }.eachCount().toSortedMap().toList().takeLast(31).toMap(),
        localHeatmap = rows.groupingBy { val d = Instant.ofEpochSecond(it.createdAt).atZone(localZone); d.dayOfWeek.value - 1 to d.hour }
            .eachCount().map { ProgressHeatCell(it.key.first, it.key.second, it.value) },
        confirmationDetails = confirmationDetails,
        stationProfiles = rows.map { it.stationProfileId.trim().ifBlank { "UNKNOWN" } }.groupingBy { it }.eachCount(),
        stationCallsigns = rows.map { it.stationCallsign.trim().uppercase(Locale.US).ifBlank { "UNKNOWN" } }.groupingBy { it }.eachCount(),
        radios = rows.map { it.radioModel.trim().uppercase(Locale.US).ifBlank { "UNKNOWN" } }.groupingBy { it }.eachCount(),
        awards = awards,
    )
}
