package app.shackcq.mobile.contest

import java.time.Instant

@JvmInline value class ContestDefinitionId(val value: String)
@JvmInline value class ContestRuleVersion(val value: String)
@JvmInline value class ContestSessionId(val value: String)

data class ContestOfficialSource(
    val url: String,
    val edition: String,
    val retrievedOn: String,
    val sha256: String,
)

enum class ContestTruth { VALID, INVALID, INCOMPLETE, UNKNOWN, REVIEW_REQUIRED }
enum class ContestMode { CW, SSB, DIGITAL, MIXED }
enum class ContestBand(val label: String) {
    B160("160M"), B80("80M"), B40("40M"), B20("20M"), B15("15M"), B10("10M"), B6("6M"), B2("2M")
}
enum class ContestEntryRegion { WVE, OCEANIA, DX, WORLDWIDE, IARU_REGION_2 }
enum class ContestSessionState { DRAFT, READY, RUNNING, PAUSED, STOPPED, CLOSED }
enum class ContestOperatingRole { RUN, SEARCH_AND_POUNCE }
enum class ContestDupeScope { ONCE_PER_CONTEST, ONCE_PER_BAND, ONCE_PER_MODE, ONCE_PER_BAND_MODE, CUSTOM_PERIOD }
enum class ContestDupeState { NEW, DUPLICATE, OVERRIDDEN, UNKNOWN }
enum class ContestMultiplierType { CQ_ZONE, ITU_ZONE, DXCC, PREFIX, STATE_PROVINCE, ARRL_SECTION, HQ_SOCIETY }
enum class ContestScoreStatus { CURRENT, RECALCULATING, STALE, REVIEW_REQUIRED }

data class ContestCategory(
    val operator: String = "SINGLE-OP",
    val assisted: String = "NON-ASSISTED",
    val band: String = "ALL",
    val mode: ContestMode = ContestMode.MIXED,
    val power: String = "LOW",
    val station: String = "FIXED",
    val transmitter: String = "ONE",
    val overlay: String = "",
)

enum class ContestExchangeField {
    RST, SERIAL, CQ_ZONE, ITU_ZONE, STATE_PROVINCE, ARRL_SECTION, POWER, CLASS,
    HQ_ABBREVIATION, MEMBER_SOCIETY, NAME, GRID, TEXT
}

data class ContestExchangeValue(val field: ContestExchangeField, val value: String, val source: String = "RECEIVED")
data class ContestValidationIssue(val truth: ContestTruth, val field: ContestExchangeField?, val reason: String)

data class ContestEntityInfo(
    val country: String = "",
    val dxcc: String = "",
    val continent: String = "",
    val cqZone: String = "",
    val ituZone: String = "",
    val stateProvince: String = "",
    val arrlSection: String = "",
    val wpxPrefix: String = "",
    val isWve: Boolean? = null,
    val isOceania: Boolean? = null,
    val hqSociety: String = "",
)

data class ContestQsoDraft(
    val qsoId: String,
    val callsign: String,
    val createdAt: Long,
    val frequencyHz: Long,
    val band: ContestBand,
    val mode: ContestMode,
    val rstSent: String,
    val rstReceived: String,
    val sent: Map<ContestExchangeField, String> = emptyMap(),
    val received: Map<ContestExchangeField, String> = emptyMap(),
    val station: ContestEntityInfo = ContestEntityInfo(),
    val worked: ContestEntityInfo = ContestEntityInfo(),
    val explicitDupeOverride: Boolean = false,
    val networkOriginId: String = "",
)

data class ContestPointResult(val truth: ContestTruth, val points: Int?, val reason: String)
data class ContestMultiplierResult(
    val type: ContestMultiplierType,
    val key: String,
    val state: ContestTruth,
    val isNew: Boolean,
    val reason: String,
)
data class ContestQsoEvaluation(
    val validity: ContestTruth,
    val points: ContestPointResult,
    val multipliers: List<ContestMultiplierResult>,
    val dupe: ContestDupeState,
    val exportWarnings: List<String> = emptyList(),
)

data class ContestRateSnapshot(
    val lastQsoIntervalSeconds: Long? = null,
    val last10MinutesPerHour: Double = 0.0,
    val last60MinutesPerHour: Double = 0.0,
    val best60MinutesPerHour: Double = 0.0,
    val pointsPerHour: Double = 0.0,
    val multipliersPerHour: Double = 0.0,
    val buckets: List<Int> = emptyList(),
)

data class ContestScoreSnapshot(
    val acceptedQsos: Int = 0,
    val scoredQsos: Int = 0,
    val duplicates: Int = 0,
    val zeroPointValidQsos: Int = 0,
    val reviewQsos: Int = 0,
    val points: Int = 0,
    val multipliers: Map<ContestMultiplierType, Int> = emptyMap(),
    val claimedScore: Long = 0,
    val bandModeBreakdown: Map<String, Int> = emptyMap(),
    val rate: ContestRateSnapshot = ContestRateSnapshot(),
    val calculationVersion: String = "1",
    val generatedAt: Long = Instant.now().epochSecond,
    val status: ContestScoreStatus = ContestScoreStatus.CURRENT,
)

data class ContestSession(
    val id: ContestSessionId,
    val definitionId: ContestDefinitionId,
    val ruleVersion: ContestRuleVersion,
    val name: String,
    val utcStart: Long,
    val utcEnd: Long,
    val stationCallsign: String,
    val stationGrid: String,
    val station: ContestEntityInfo,
    val category: ContestCategory,
    val operators: List<String>,
    val initialSerial: Int = 1,
    val role: ContestOperatingRole = ContestOperatingRole.RUN,
    val state: ContestSessionState = ContestSessionState.DRAFT,
    val networkArmed: Boolean = false,
    val keyerArmed: Boolean = false,
    val score: ContestScoreSnapshot = ContestScoreSnapshot(),
)

enum class ContestSerialState { RESERVED, COMMITTED, RELEASED, CONFLICT }
data class ContestSerialReservation(
    val id: String,
    val sessionId: ContestSessionId,
    val serial: Int,
    val owner: String,
    val reservedAt: Long,
    val state: ContestSerialState,
    val qsoId: String? = null,
)

data class ContestClaimSnapshot(val callsign: String, val station: String, val expiresAt: Long)
data class ContestOpportunityInput(
    val session: ContestSession,
    val callsign: String,
    val band: ContestBand,
    val mode: ContestMode,
    val entity: ContestEntityInfo,
    val priorQsos: List<ContestQsoDraft>,
    val claims: List<ContestClaimSnapshot> = emptyList(),
    val evaluatedAt: Long,
)
data class ContestOpportunityState(
    val validBandMode: ContestTruth,
    val dupe: ContestDupeState,
    val newMultipliers: Set<ContestMultiplierType>,
    val workedMultipliers: Set<ContestMultiplierType>,
    val unknownMultipliers: Set<ContestMultiplierType>,
    val expectedExchangeHint: String,
    val hintSource: String,
    val claimedBy: String? = null,
    val priorityReasons: List<String> = emptyList(),
)

enum class ContestExportState { VALID, VALID_WITH_WARNINGS, BLOCKED }
data class ContestExportIssue(val truth: ContestTruth, val field: String, val reason: String)
data class ContestExportResult(val state: ContestExportState, val issues: List<ContestExportIssue>, val lines: Sequence<String>)
