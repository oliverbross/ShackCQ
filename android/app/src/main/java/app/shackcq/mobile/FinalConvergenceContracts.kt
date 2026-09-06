package app.shackcq.mobile

import java.util.concurrent.atomic.AtomicLong

data class ContextValue<T>(val value: T, val source: String)

data class OperatingContextSnapshot(
    val generation: Long = 0,
    val stationProfileId: ContextValue<String> = ContextValue("", "none"),
    val stationCallsign: ContextValue<String> = ContextValue("", "none"),
    val operatorCallsign: ContextValue<String> = ContextValue("", "none"),
    val stationGrid: ContextValue<String> = ContextValue("", "none"),
    val stationLocation: ContextValue<String> = ContextValue("", "none"),
    val wavelogBindingId: ContextValue<String> = ContextValue("", "none"),
    val wavelogRemoteStationId: ContextValue<String> = ContextValue("", "none"),
    val activationProgram: ContextValue<String> = ContextValue("", "none"),
    val activationReference: ContextValue<String> = ContextValue("", "none"),
    val activationSession: ContextValue<String> = ContextValue("", "none"),
    val radioProfileId: ContextValue<String> = ContextValue("", "none"),
    val radioBackendKind: ContextValue<String> = ContextValue("", "none"),
    val radioCapabilityRevision: ContextValue<String> = ContextValue("", "none"),
    val radioFamily: ContextValue<String> = ContextValue("", "none"),
    val radioModel: ContextValue<String> = ContextValue("", "none"),
    val radioIdentity: ContextValue<String> = ContextValue("", "none"),
    val connected: ContextValue<Boolean> = ContextValue(false, "none"),
    val receiveFrequencyHz: ContextValue<Long> = ContextValue(0, "none"),
    val transmitFrequencyHz: ContextValue<Long?> = ContextValue(null, "none"),
    val receiveBandwidthHz: ContextValue<Int> = ContextValue(0, "none"),
    val band: ContextValue<String> = ContextValue("", "none"),
    val mode: ContextValue<String> = ContextValue("", "none"),
    val submode: ContextValue<String> = ContextValue("", "none"),
    val split: ContextValue<Boolean> = ContextValue(false, "none"),
    val radioState: ContextValue<String> = ContextValue("RX", "none"),
    val selectedDxTarget: ContextValue<String> = ContextValue("", "none"),
    val selectedContestId: ContextValue<String> = ContextValue("", "none"),
    val selectedSatelliteNorad: ContextValue<Long?> = ContextValue(null, "none"),
    val rotatorProfileId: ContextValue<String> = ContextValue("", "none"),
    val rotatorConnected: ContextValue<Boolean> = ContextValue(false, "none"),
    val rotatorAzimuthDeg: ContextValue<Double?> = ContextValue(null, "none"),
    val rotatorElevationDeg: ContextValue<Double?> = ContextValue(null, "none"),
    val rotatorMovement: ContextValue<String> = ContextValue("UNKNOWN", "none"),
    val rotatorAutomationArmed: ContextValue<Boolean> = ContextValue(false, "none"),
    val networkAvailable: ContextValue<Boolean> = ContextValue(false, "none"),
    val foreground: ContextValue<Boolean> = ContextValue(false, "none"),
    val qsoDatabaseRevision: ContextValue<Long> = ContextValue(0, "none"),
    val providerGeneration: ContextValue<Long> = ContextValue(0, "none"),
)

class OperatingContextAuthority {
    private val issuedGeneration = AtomicLong(0)
    @Volatile var snapshot = OperatingContextSnapshot()
        private set

    fun beginUpdate(): Long = issuedGeneration.incrementAndGet()

    @Synchronized
    fun publish(generation: Long, candidate: OperatingContextSnapshot): Boolean {
        if (generation < issuedGeneration.get() || generation <= snapshot.generation) return false
        snapshot = candidate.copy(generation = generation)
        return true
    }
}

enum class WorkspaceDestination {
    HOME, RADIO, DIGI, DX_CHASER, CONTEST, BAND_MAPS, PANADAPTER, EQ, LOGBOOK, PROGRESS, SYNC, PRESETS,
    DX, CALLBOOK, PORTABLE, OPERATIONS, SATELLITE, ROTATOR, GROUPS_IO, SETTINGS
}

data class WorkspaceAction(
    val destination: WorkspaceDestination,
    val callsign: String = "",
    val grid: String = "",
    val frequencyHz: Long? = null,
    val band: String = "",
    val mode: String = "",
    val submode: String = "",
    val qsoId: String = "",
    val portableProgram: String = "",
    val portableReference: String = "",
    val activationSession: String = "",
    val noradId: Long? = null,
    val contestId: String = "",
    val groupsIoGroupId: Long? = null,
    val groupsIoTopicId: Long? = null,
    val groupsIoMessageNumber: Long? = null,
    val wavelogBindingId: String = "",
    val wavelogOutboxId: String = "",
    val wavelogConflictId: String = "",
    val rfEvidenceId: String = "",
    val outlookWindowMinutes: Int? = null,
    val outlookRegion: String = "",
    val presetId: String = "",
    val expectedContextGeneration: Long? = null,
    val source: String,
    val reason: String,
)

internal data class WorkspaceRoute(
    val action: WorkspaceAction,
    val requiresExactSelection: Boolean,
    val receiveReview: HomeReceiveTuneReview? = null,
    val mayKeyPtt: Boolean = false,
    val mayStartTune: Boolean = false,
    val mayChangeTransmitFrequency: Boolean = false,
    val mayArmTransmit: Boolean = false,
    val mayPostOrLog: Boolean = false,
)

internal object WorkspaceActionRouter {
    fun resolve(action: WorkspaceAction, currentContextGeneration: Long): WorkspaceRoute? {
        require(action.source.isNotBlank() && action.reason.isNotBlank())
        require(action.frequencyHz == null || action.frequencyHz in 100_000L..77_000_000_000L)
        if (action.expectedContextGeneration?.let { it != currentContextGeneration } == true) return null
        val exact = action.callsign.isNotBlank() || action.band.isNotBlank() || action.qsoId.isNotBlank() ||
            action.noradId != null || action.contestId.isNotBlank() ||
            action.groupsIoMessageNumber != null || action.wavelogOutboxId.isNotBlank() ||
            action.wavelogConflictId.isNotBlank() || action.rfEvidenceId.isNotBlank()
        val review = action.frequencyHz?.let {
            HomeReceiveTuneReview(it, action.mode.ifBlank { null }, action.source,
                "${action.reason}. Review receive frequency only; no CAT command has been sent.")
        }
        return WorkspaceRoute(action, exact, review)
    }
}
