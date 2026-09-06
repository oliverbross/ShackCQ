package app.shackcq.mobile

enum class LogbookSort { TIME, CALLSIGN, NAME, COUNTRY, DXCC, MODE, SUBMODE, BAND, FREQUENCY, GRID, DISTANCE, DURATION }
enum class LogbookSortDirection { DESCENDING, ASCENDING }

val LOGBOOK_PAGE_SIZES = listOf(25, 50, 100, 200, 250)

fun normalizedLogbookPageSize(value: Int): Int = value.takeIf { it in LOGBOOK_PAGE_SIZES } ?: 50

fun logbookPageCount(total: Int, pageSize: Int): Int =
    maxOf(1, (total + normalizedLogbookPageSize(pageSize) - 1) / normalizedLogbookPageSize(pageSize))

data class LogbookFilter(
    val fromEpochSeconds: Long? = null,
    val toEpochSecondsExclusive: Long? = null,
    val callsign: String = "",
    val stationProfile: String = "",
    val stationCallsign: String = "",
    val provenance: String = "",
    val name: String = "",
    val qth: String = "",
    val email: String = "",
    val dxcc: String = "",
    val country: String = "",
    val state: String = "",
    val grid: String = "",
    val cqZone: String = "",
    val ituZone: String = "",
    val mode: String = "",
    val modeFamily: String = "",
    val submode: String = "",
    val band: String = "",
    val frequency: String = "",
    val frequencyRx: String = "",
    val bandRx: String = "",
    val propagation: String = "",
    val county: String = "",
    val dok: String = "",
    val sota: String = "",
    val pota: String = "",
    val iota: String = "",
    val wwff: String = "",
    val operator: String = "",
    val radioModel: String = "",
    val contest: String = "",
    val continent: String = "",
    val satellite: String = "",
    val satelliteMode: String = "",
    val orbit: String = "",
    val comment: String = "",
    val qslMessage: String = "",
    val notes: String = "",
    val distance: String = "",
    val duration: String = "",
    val qslSent: String = "",
    val qslReceived: String = "",
    val qslSentMethod: String = "",
    val qslReceivedMethod: String = "",
    val lotwSent: String = "",
    val lotwReceived: String = "",
    val clublogSent: String = "",
    val clublogReceived: String = "",
    val eqslSent: String = "",
    val eqslReceived: String = "",
    val dclSent: String = "",
    val dclReceived: String = "",
    val qrzSent: String = "",
    val qrzReceived: String = "",
    val qslVia: String = "",
    val qslImages: String = "",
    val recordState: String = "",
    val duplicateState: String = "",
    val syncRelation: String = "",
    val confirmationSource: String = "",
    val portableProgram: String = "",
    val callsignPrefix: String = "",
    val txPower: String = "",
    val recordVisibility: String = "",
    val sort: LogbookSort = LogbookSort.TIME,
    val direction: LogbookSortDirection = LogbookSortDirection.DESCENDING,
    val limit: Int = 50,
) : java.io.Serializable

fun filterLogbook(records: List<Qso>, filter: LogbookFilter): List<Qso> {
    val matching = records.asSequence().filter { qso ->
        (filter.fromEpochSeconds == null || qso.createdAt >= filter.fromEpochSeconds) &&
            (filter.toEpochSecondsExclusive == null || qso.createdAt < filter.toEpochSecondsExclusive) &&
            textMatches(qso.callsign, filter.callsign) && textMatches(qso.stationProfileId, filter.stationProfile) &&
            textMatches(qso.stationCallsign, filter.stationCallsign) && provenanceMatches(qso, filter.provenance) &&
            textMatches(qso.name, filter.name) && textMatches(qso.qth, filter.qth) && textMatches(qso.email, filter.email) &&
            textMatches(qso.dxcc, filter.dxcc) && textMatches(qso.country, filter.country) &&
            textMatches(qso.state, filter.state) && textMatches(qso.grid, filter.grid) &&
            textMatches(qso.cqZone, filter.cqZone) && textMatches(qso.ituZone, filter.ituZone) &&
            choiceMatches(qso.mode, filter.mode) && (filter.modeFamily.isBlank() || qsoMode(qso) == filter.modeFamily.uppercase()) &&
            choiceMatches(qso.submode, filter.submode) &&
            choiceMatches(qso.band, filter.band) && numericMatches(qso.frequencyHz / 1_000_000.0, filter.frequency) &&
            numericMatches(qso.frequencyRxHz / 1_000_000.0, filter.frequencyRx) && choiceMatches(qso.bandRx, filter.bandRx) &&
            choiceMatches(qso.propagationMode, filter.propagation) && textMatches(qso.county, filter.county) &&
            textMatches(qso.dok, filter.dok) && textMatches(qso.sotaRef, filter.sota) &&
            (filter.pota.isBlank() || (qso.potaRefs + qso.potaRef).any { textMatches(it, filter.pota) }) && textMatches(qso.iota, filter.iota) &&
            textMatches(qso.wwffRef, filter.wwff) && textMatches(qso.operatorCallsign, filter.operator) && textMatches(qso.radioModel, filter.radioModel) &&
            textMatches(qso.contestId, filter.contest) && choiceMatches(qso.continent, filter.continent) &&
            textMatches(qso.extraAdifFields["SAT_NAME"].orEmpty(), filter.satellite) &&
            textMatches(qso.extraAdifFields["SAT_MODE"].orEmpty(), filter.satelliteMode) &&
            textMatches(qso.extraAdifFields["ORBIT"].orEmpty(), filter.orbit) &&
            textMatches(qso.comment, filter.comment) && textMatches(qso.qslMessage, filter.qslMessage) &&
            textMatches(qso.notes, filter.notes) &&
            numericMatches(qso.distanceKm, filter.distance) && numericMatches(qso.durationSeconds / 60.0, filter.duration) &&
            statusMatches(qso.qslSent, filter.qslSent) && statusMatches(qso.qslReceived, filter.qslReceived) &&
            choiceMatches(qso.qslMethod, filter.qslSentMethod) && choiceMatches(qso.qslReceivedMethod, filter.qslReceivedMethod) &&
            statusMatches(qso.lotwSent, filter.lotwSent) && statusMatches(qso.lotwReceived, filter.lotwReceived) &&
            statusMatches(qso.clublogSent, filter.clublogSent) && statusMatches(qso.clublogReceived, filter.clublogReceived) &&
            statusMatches(qso.eqslSent, filter.eqslSent) && statusMatches(qso.eqslReceived, filter.eqslReceived) &&
            statusMatches(qso.dclSent, filter.dclSent) && statusMatches(qso.dclReceived, filter.dclReceived) &&
            statusMatches(qso.qrzSent, filter.qrzSent) && statusMatches(qso.qrzReceived, filter.qrzReceived) &&
            textMatches(qso.qslVia, filter.qslVia) && presenceMatches(qso.qslImages, filter.qslImages) &&
            recordStateMatches(qso, filter.recordState) && syncRelationMatches(qso, filter.syncRelation) &&
            confirmationSourceMatches(qso, filter.confirmationSource) && progressPortableMatchesForLogbook(qso, filter.portableProgram) &&
            (filter.callsignPrefix.isBlank() || wpxPrefix(qso.callsign) == filter.callsignPrefix.uppercase()) &&
            numericMatches(qso.txPowerW.toDouble(), filter.txPower) && visibilityMatches(qso, filter.recordVisibility) &&
            (filter.duplicateState.isBlank() || filter.duplicateState.equals("CANDIDATE", true) && records.any { other ->
                other.id != qso.id && other.callsign.equals(qso.callsign, true) && other.frequencyHz == qso.frequencyHz &&
                    other.mode.equals(qso.mode, true) && kotlin.math.abs(other.createdAt - qso.createdAt) <= 15
            })
    }.toList()

    val comparator = when (filter.sort) {
        LogbookSort.TIME -> compareBy<Qso> { it.createdAt }
        LogbookSort.CALLSIGN -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.callsign }
        LogbookSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        LogbookSort.COUNTRY -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.country }
        LogbookSort.DXCC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.dxcc }
        LogbookSort.MODE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.mode }
        LogbookSort.SUBMODE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.submode }
        LogbookSort.BAND -> compareBy<Qso> { bandRank(it.band) }.thenBy { it.frequencyHz }
        LogbookSort.FREQUENCY -> compareBy<Qso> { it.frequencyHz }
        LogbookSort.GRID -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.grid }
        LogbookSort.DISTANCE -> compareBy<Qso> { it.distanceKm }
        LogbookSort.DURATION -> compareBy<Qso> { it.durationSeconds }
    }
    val ordered = matching.sortedWith(if (filter.direction == LogbookSortDirection.DESCENDING) comparator.reversed() else comparator)
    return ordered.take(filter.limit.coerceIn(1, 250))
}

fun activeLogbookFilterCount(filter: LogbookFilter): Int = listOf(
    filter.fromEpochSeconds, filter.toEpochSecondsExclusive, filter.callsign, filter.stationProfile, filter.stationCallsign,
    filter.provenance, filter.name, filter.qth, filter.email, filter.dxcc, filter.country, filter.state, filter.grid,
    filter.cqZone, filter.ituZone, filter.mode, filter.modeFamily, filter.submode, filter.band, filter.frequency, filter.frequencyRx,
    filter.bandRx, filter.propagation, filter.county, filter.dok, filter.sota, filter.pota, filter.iota,
    filter.wwff, filter.operator, filter.radioModel, filter.contest, filter.continent, filter.satellite, filter.satelliteMode, filter.orbit,
    filter.comment, filter.qslMessage, filter.notes, filter.distance, filter.duration,
    filter.qslSent, filter.qslReceived, filter.qslSentMethod, filter.qslReceivedMethod, filter.lotwSent,
    filter.lotwReceived, filter.clublogSent, filter.clublogReceived, filter.eqslSent, filter.eqslReceived,
    filter.dclSent, filter.dclReceived, filter.qrzSent, filter.qrzReceived, filter.qslVia, filter.qslImages,
    filter.recordState, filter.duplicateState, filter.syncRelation, filter.confirmationSource, filter.portableProgram,
    filter.callsignPrefix, filter.txPower, filter.recordVisibility,
).count { value -> value != null && value.toString().isNotBlank() }

/** Shared contract for Progress/Analytics deep links into Advanced Logbook. */
fun logbookFilterForDimension(key: String, value: String, base: LogbookFilter = LogbookFilter()): LogbookFilter =
    when (key.trim().lowercase()) {
        "callsign", "call" -> base.copy(callsign = value)
        "dxcc" -> base.copy(dxcc = value)
        "country" -> base.copy(country = value)
        "continent" -> base.copy(continent = value)
        "band" -> base.copy(band = value)
        "mode" -> base.copy(mode = value)
        "modefamily" -> base.copy(modeFamily = value)
        "submode" -> base.copy(submode = value)
        "grid" -> base.copy(grid = value)
        "state" -> base.copy(state = value)
        "cqzone", "cq" -> base.copy(cqZone = value)
        "ituzone", "itu" -> base.copy(ituZone = value)
        "operator" -> base.copy(operator = value)
        "stationprofile" -> base.copy(stationProfile = value)
        "stationcallsign" -> base.copy(stationCallsign = value)
        "radio" -> base.copy(radioModel = value)
        "wpx", "prefix" -> base.copy(callsignPrefix = value)
        "qrp" -> base.copy(txPower = "1..5")
        "pota" -> base.copy(pota = value)
        "sota" -> base.copy(sota = value)
        "wwff" -> base.copy(wwff = value)
        "iota" -> base.copy(iota = value)
        "contest", "contestid" -> base.copy(contest = value)
        else -> base
    }

private fun textMatches(actual: String, expected: String) = expected.isBlank() ||
    expected.trim() == "*" && actual.isNotBlank() || actual.contains(expected.trim(), ignoreCase = true)
private fun choiceMatches(actual: String, expected: String) = expected.isBlank() || actual.equals(expected, ignoreCase = true)
private fun provenanceMatches(qso: Qso, expected: String) = when (expected.uppercase()) {
    "LOCAL" -> qso.remoteId.isBlank()
    "REMOTE", "LINKED" -> qso.remoteId.isNotBlank()
    else -> true
}
private fun recordStateMatches(qso: Qso, expected: String): Boolean {
    val invalid = qso.callsign.isBlank() || qso.frequencyHz <= 0 || qso.mode.isBlank() || qso.createdAt <= 0
    return when (expected.uppercase()) { "INCOMPLETE", "INVALID" -> invalid; "VALID" -> !invalid; else -> true }
}
private fun syncRelationMatches(qso: Qso, expected: String) = when (expected.uppercase()) {
    "LOCAL_ONLY" -> qso.remoteId.isBlank() && qso.syncState.equals("local", true)
    "LINKED" -> qso.remoteId.isNotBlank()
    "OUTBOX" -> qso.syncState.lowercase() in setOf("pending", "queued", "retry", "failed")
    "CONFLICT" -> qso.syncState.equals("conflict", true)
    "ATTENTION" -> qso.syncState.lowercase() in setOf("pending", "queued", "retry", "failed", "conflict")
    "TOMBSTONE", "REMOTE_DELETED" -> qso.syncState.lowercase() in setOf("tombstone", "remote_deleted")
    else -> true
}

private fun confirmationSourceMatches(qso: Qso, source: String): Boolean {
    fun received(value: String) = value.trim().uppercase() in setOf("Y", "V")
    return when (source.uppercase()) {
        "PAPER", "QSL" -> received(qso.qslReceived)
        "LOTW" -> received(qso.lotwReceived)
        "EQSL" -> received(qso.eqslReceived)
        "QRZ" -> received(qso.qrzReceived)
        "CLUBLOG" -> received(qso.clublogReceived)
        "DCL" -> received(qso.dclReceived)
        "AWARD" -> isAwardConfirmed(qso)
        "UNCONFIRMED" -> !isAwardConfirmed(qso)
        else -> true
    }
}

private fun progressPortableMatchesForLogbook(qso: Qso, program: String) = when (program.uppercase()) {
    "POTA" -> qso.potaRef.isNotBlank() || qso.potaRefs.isNotEmpty() || qso.myPotaRef.isNotBlank() || qso.myPotaRefs.isNotEmpty()
    "SOTA" -> qso.sotaRef.isNotBlank() || qso.mySotaRef.isNotBlank()
    "WWFF" -> qso.wwffRef.isNotBlank() || qso.myWwffRef.isNotBlank()
    "IOTA" -> qso.iota.isNotBlank() || qso.myIota.isNotBlank()
    "ANY" -> qso.potaRef.isNotBlank() || qso.potaRefs.isNotEmpty() || qso.myPotaRef.isNotBlank() || qso.myPotaRefs.isNotEmpty() ||
        qso.sotaRef.isNotBlank() || qso.mySotaRef.isNotBlank() || qso.wwffRef.isNotBlank() || qso.myWwffRef.isNotBlank()
    else -> true
}

private fun visibilityMatches(qso: Qso, visibility: String) = when (visibility.uppercase()) {
    "ACTIVE" -> qso.syncState.lowercase() !in setOf("conflict", "tombstone", "remote_deleted")
    "ACTIVE_AND_CONFLICTS" -> qso.syncState.lowercase() !in setOf("tombstone", "remote_deleted")
    "DELETED" -> qso.syncState.lowercase() in setOf("tombstone", "remote_deleted")
    else -> true
}

private fun statusMatches(actual: String, expected: String): Boolean {
    if (expected.isBlank()) return true
    val normalized = actual.trim().uppercase()
    return when (expected.uppercase()) {
        "Y" -> normalized in setOf("Y", "YES", "S", "SENT", "UPLOADED", "1", "TRUE")
        "N" -> normalized.isBlank() || normalized in setOf("N", "NO", "0", "FALSE")
        else -> normalized == expected.uppercase()
    }
}

private fun presenceMatches(actual: String, expected: String): Boolean = when (expected.uppercase()) {
    "Y" -> actual.isNotBlank() && actual.uppercase() !in setOf("N", "NO", "0", "FALSE")
    "N" -> actual.isBlank() || actual.uppercase() in setOf("N", "NO", "0", "FALSE")
    else -> true
}

internal fun numericMatches(actual: Double, expression: String): Boolean {
    val raw = expression.trim().replace(",", ".")
    if (raw.isBlank() || raw == "*") return true
    Regex("^(-?\\d+(?:\\.\\d+)?)\\s*(?:\\.\\.|-)\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(raw)?.let {
        val low = it.groupValues[1].toDouble(); val high = it.groupValues[2].toDouble()
        return actual in minOf(low, high)..maxOf(low, high)
    }
    val match = Regex("^(>=|<=|>|<|=)?\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(raw) ?: return false
    val value = match.groupValues[2].toDouble()
    return when (match.groupValues[1]) {
        ">" -> actual > value; ">=" -> actual >= value; "<" -> actual < value; "<=" -> actual <= value
        "=" -> actual == value; else -> actual >= value
    }
}

private fun bandRank(band: String) = listOf("2190m", "630m", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m")
    .indexOf(canonicalSpotBand(band)).let { if (it < 0) Int.MAX_VALUE else it }
