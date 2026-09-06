package app.shackcq.mobile.dxchaser

import java.util.Locale

const val DX_CHASER_SCORE_VERSION = "ShackCQ DX Chaser Score v1"
const val DX_CHASER_CONTRACT_VERSION = 1

enum class DxChaserMode { ASSIST, CHASE_SESSION, DRY_RUN }
enum class DxChaserProfile { BALANCED, DXCC_FIRST, BAND_SLOTS, GRIDS, WATCHLIST, LOCAL_SIGNAL }
enum class DxChaserSessionState {
    DISABLED, MONITORING, TARGET_SELECTED, PREPARE_CALL_PENDING, WAITING_FOR_SEQUENCE_START,
    CALLING, WAITING_FOR_RESPONSE, ENGAGED, WAITING_FOR_QSO_RESULT, COOLDOWN, PAUSED,
    COMPLETE, FAILED, STOPPED,
}
enum class DxChaserDecodeSource { LIVE_CAPTURE, REDECODE_LIVE_SLOT, REFERENCE_RECORDING, COMPANION, HISTORY, LEGACY_TIMING }
enum class DxChaserMessageType { CQ, DIRECTED_CQ, ADDRESSED_TO_OPERATOR, BYSTANDER, OTHER }
enum class DxChaserNeedState { UNAVAILABLE, NEEDED, NOT_NEEDED, WORKED, CONFIRMED }
enum class DxChaserNeedDimension {
    ATNO, DXCC, BAND_ENTITY, MODE, BAND_MODE_SLOT, GRID, CQ_ZONE, ITU_ZONE, WPX,
    IOTA, POTA, SOTA, WWFF,
}
enum class DxChaserEvidenceKind { CLUSTER, RBN, PSK, PERSONAL_WSPR, BAND_HEALTH, NEURAL_CURRENT, EMPIRICAL_OUTLOOK }
enum class DxChaserEvidenceState { CURRENT, CACHED, STALE, DEGRADED, UNAVAILABLE, DISABLED }
enum class DxChaserRarityOrigin { UNAVAILABLE, USER_IMPORTED, REVIEWED_BUNDLED }
enum class DxChaserPriorityTier { CRITICAL, HIGH, MEDIUM, LOW, INELIGIBLE }
enum class DxChaserActionType {
    NONE, SHOW_RECOMMENDATION, PREPARE_FT_CALL, REQUEST_RECEIVE_BAND_REVIEW, OPEN_DX_DETAILS,
    OPEN_LOGBOOK_HISTORY, ADD_WATCH, REMOVE_WATCH, RECORD_DRY_RUN, STOP_CHASE,
}

data class DxChaserSafetySnapshot(
    val radioConnected: Boolean = false,
    val receiveActive: Boolean = false,
    val routeHealthy: Boolean = false,
    val digiAudioHealthy: Boolean = false,
    val txActive: Boolean = false,
    val sequenceActive: Boolean = false,
    val foreground: Boolean = true,
    val digiModeEligible: Boolean = true,
    val localModemAuthority: Boolean = true,
    val txEnabledByOperator: Boolean = true,
    val contestCompatible: Boolean = true,
    val keyerIdle: Boolean = true,
    val rxConfirmed: Boolean = true,
) {
    val preparationPermitted: Boolean get() = radioConnected && receiveActive && routeHealthy &&
        digiAudioHealthy && !txActive && !sequenceActive && foreground && digiModeEligible &&
        localModemAuthority && txEnabledByOperator && contestCompatible && keyerIdle && rxConfirmed
}

data class DxChaserContestOpportunity(
    val validBandMode: Boolean? = null,
    val duplicate: Boolean? = null,
    val newMultipliers: Set<String> = emptySet(),
    val workedMultipliers: Set<String> = emptySet(),
    val unknownMultipliers: Set<String> = emptySet(),
    val expectedExchangeHint: String = "",
    val claimedBy: String? = null,
)

data class DxChaserContestSnapshot(
    val activeSessionId: String = "",
    val running: Boolean = false,
    val digitalCompatible: Boolean = true,
    val opportunities: Map<String, DxChaserContestOpportunity> = emptyMap(),
)

data class DxChaserNeedFacts(
    val states: Map<DxChaserNeedDimension, DxChaserNeedState> = emptyMap(),
    val worked: Boolean = false,
    val confirmed: Boolean = false,
    val watchlisted: Boolean = false,
) {
    fun state(dimension: DxChaserNeedDimension) = states[dimension] ?: DxChaserNeedState.UNAVAILABLE
}

data class DxChaserLocalDecode(
    val id: String,
    val sessionId: String,
    val slotIdentity: String,
    val slotStartMillis: Long,
    val epochSeconds: Long,
    val source: DxChaserDecodeSource,
    val exactSlotTiming: Boolean,
    val mode: String,
    val band: String,
    val dialFrequencyHz: Long,
    val audioFrequencyHz: Int,
    val callsign: String,
    val baseCallsign: String = callsign.substringAfterLast('/').uppercase(Locale.US),
    val grid: String = "",
    val entity: String = "",
    val snr: Int,
    val message: String,
    val messageType: DxChaserMessageType,
    val decodeCount: Int = 1,
    val stableAudioFrequency: Boolean = true,
    val engagedWithOtherStation: Boolean = false,
    val stationProfileId: String,
    val radioIdentity: String,
    val needs: DxChaserNeedFacts = DxChaserNeedFacts(),
)

data class DxChaserProviderEvidence(
    val id: String,
    val callsign: String,
    val band: String,
    val kind: DxChaserEvidenceKind,
    val state: DxChaserEvidenceState,
    val observedEpochSeconds: Long,
    val sourceLabel: String,
    val spotterRegion: String = "",
    val supportLabel: String = "",
    val frequencyHz: Long? = null,
)

data class DxChaserRarity(
    val entityId: String,
    val rank: Int? = null,
    val tier: Int? = null,
    val origin: DxChaserRarityOrigin = DxChaserRarityOrigin.UNAVAILABLE,
    val sourceLabel: String = "",
    val sourceDate: String = "",
    val digest: String = "",
)

data class DxChaserInputSnapshot(
    val generation: Long,
    val foreground: Boolean,
    val nowEpochSeconds: Long,
    val stationProfileId: String,
    val stationCallsign: String,
    val stationGrid: String,
    val radioIdentity: String,
    val radioFamily: String,
    val receiveFrequencyHz: Long,
    val band: String,
    val mode: String,
    val digiSessionId: String,
    val capturedSlotStartMillis: Long,
    val safety: DxChaserSafetySnapshot,
    val localDecodes: List<DxChaserLocalDecode>,
    val evidence: List<DxChaserProviderEvidence> = emptyList(),
    val rarity: Map<String, DxChaserRarity> = emptyMap(),
    val cooldowns: List<DxChaserCooldownSnapshot> = emptyList(),
    val receivableBands: Set<String> = emptySet(),
    val contest: DxChaserContestSnapshot = DxChaserContestSnapshot(),
)

data class DxChaserPriorityBreakdown(
    val modelVersion: String = DX_CHASER_SCORE_VERSION,
    val value: Int,
    val localQuality: Int,
    val currentObservedSupport: Int,
    val futureEmpiricalSupport: Int,
    val historicalPersonalValue: Int,
    val rarity: Int,
    val penalties: Int,
    val total: Int,
    val reasons: List<String>,
    val penaltyReasons: List<String>,
)

data class DxChaserCandidateSnapshot(
    val callsign: String,
    val baseCallsign: String,
    val entity: String,
    val grid: String,
    val band: String,
    val mode: String,
    val dialFrequencyHz: Long,
    val audioFrequencyHz: Int,
    val localDecodeId: String,
    val slotIdentity: String,
    val snr: Int,
    val decodeCount: Int,
    val localDecodeAgeSeconds: Long,
    val eligible: Boolean,
    val ineligibleReasons: List<String>,
    val priorityTier: DxChaserPriorityTier,
    val breakdown: DxChaserPriorityBreakdown,
    val watchlisted: Boolean,
    val worked: Boolean,
    val confirmed: Boolean,
    val needReasons: List<String>,
    val currentEvidenceLabel: String,
    val outlookLabel: String,
    val rarity: DxChaserRarity,
    val cooldownActive: Boolean = false,
)

data class DxChaserTargetSnapshot(
    val candidate: DxChaserCandidateSnapshot,
    val selectedEpochSeconds: Long,
    val attempts: Int = 0,
    val engaged: Boolean = false,
    val lastHeardEpochSeconds: Long = 0,
    val timeoutEpochSeconds: Long,
)

data class DxChaserCooldownSnapshot(
    val baseCallsign: String,
    val band: String,
    val mode: String,
    val reason: String,
    val expiresEpochSeconds: Long,
)

data class DxChaserCrossBandOpportunity(
    val callsign: String,
    val band: String,
    val suggestedDialFrequencyHz: Long? = null,
    val sourceCount: Int,
    val spotterGeography: List<String>,
    val needReason: String,
    val currentEvidence: String,
    val outlook: String,
    val ageSeconds: Long,
    val confidenceLabel: String,
)

data class DxChaserActionIntent(
    val type: DxChaserActionType,
    val sessionId: String,
    val generation: Long,
    val callsign: String = "",
    val baseCallsign: String = "",
    val grid: String = "",
    val band: String = "",
    val mode: String = "",
    val dialFrequencyHz: Long? = null,
    val audioFrequencyHz: Int? = null,
    val slotIdentity: String = "",
    val localDecodeId: String = "",
    val score: Int? = null,
    val reason: String,
)

data class DxChaserIntegrationEvent(
    val sessionId: String,
    val generation: Long,
    val type: String,
    val detail: String = "",
    val epochSeconds: Long,
)

data class DxChaserSessionSnapshot(
    val id: String = "",
    val mode: DxChaserMode = DxChaserMode.ASSIST,
    val state: DxChaserSessionState = DxChaserSessionState.DISABLED,
    val profile: DxChaserProfile = DxChaserProfile.BALANCED,
    val startedEpochSeconds: Long = 0,
    val endsEpochSeconds: Long = 0,
    val target: DxChaserTargetSnapshot? = null,
    val attemptedTargets: Int = 0,
    val completedQsos: Int = 0,
    val failures: Int = 0,
    val stopReason: String = "",
    val pendingIntent: DxChaserActionIntent? = null,
)

data class DxChaserReadOnlySnapshot(
    val contractVersion: Int = DX_CHASER_CONTRACT_VERSION,
    val generatedEpochSeconds: Long = 0,
    val generation: Long = 0,
    val session: DxChaserSessionSnapshot = DxChaserSessionSnapshot(),
    val rankedCandidates: List<DxChaserCandidateSnapshot> = emptyList(),
    val currentTarget: DxChaserTargetSnapshot? = null,
    val engagedCall: String = "",
    val cooldowns: List<DxChaserCooldownSnapshot> = emptyList(),
    val crossBandOpportunities: List<DxChaserCrossBandOpportunity> = emptyList(),
    val providerFreshness: Map<String, DxChaserEvidenceState> = emptyMap(),
    val safety: DxChaserSafetySnapshot = DxChaserSafetySnapshot(),
    val contest: DxChaserContestSnapshot = DxChaserContestSnapshot(),
    val databaseCounts: Map<String, Long> = emptyMap(),
    val settingsDigest: String = "",
    val lastAction: DxChaserActionIntent? = null,
    val lastEngineError: String = "",
)
