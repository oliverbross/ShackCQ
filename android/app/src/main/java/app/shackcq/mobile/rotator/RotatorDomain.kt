package app.shackcq.mobile.rotator

import java.time.Instant
import java.util.UUID

const val ROTATOR_SETTINGS_VERSION = 1
const val MAX_ROTATOR_PROFILES = 32
const val MAX_ROTATOR_BAND_POLICIES = 256
const val MAX_ROTATOR_PRESETS = 20
const val MAX_ROTATOR_FORBIDDEN_SECTORS = 16
const val MAX_ROTATOR_TARGETS = 50
const val MAX_ROTATOR_DIAGNOSTIC_EVENTS = 200
const val MAX_ROTATOR_RESPONSE_BYTES = 4 * 1024

enum class RotatorCapability {
    AZIMUTH, ELEVATION, POSITION_QUERY, ABSOLUTE_MOVE, CONTINUOUS_JOG, STOP, PARK,
    SPEED, LIMITS, WRAP_OVER_360, FLIP_OVER, PRESETS, MOVEMENT_STATE, ERROR_STATE,
}

enum class CapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN, ERROR }

data class RotatorCapabilitySnapshot(
    val values: Map<RotatorCapability, CapabilitySupport> = emptyMap(),
    val provenance: String = "unprobed",
) {
    fun supports(capability: RotatorCapability): Boolean =
        values[capability] == CapabilitySupport.SUPPORTED
}

enum class RotatorMovementState {
    UNKNOWN, IDLE, MOVING_CW, MOVING_CCW, MOVING_UP, MOVING_DOWN, MOVING_AZ_EL,
    STOPPING, PARKING, FAULT,
}

enum class RotatorBackend { NATIVE, REMOTE_ROTCTLD, EMBEDDED_HAMLIB }
enum class RotatorTransportKind { SERIAL, TCP, ROTCTLD, EMBEDDED_HAMLIB }
enum class RotatorProtocolKind { ARCO_COMPATIBLE, GS232, DCU_ROTOREZ, EASYCOMM, SPID_ROT1, SPID_ROT2, ROTCTLD, HAMLIB }
enum class HeadingOffsetOwner { NONE, SHACKCQ, ROTATOR_CONTROLLER }
enum class RotatorBandPolicy { OFF, MANUAL, PROMPT, AUTO_SELECTED_TARGET, SATELLITE_SESSION }
enum class HeadingMode { SHORT_PATH, LONG_PATH }
enum class MovementDuringTxPolicy { BLOCK_NEW_MOVE, STOP_IF_POSSIBLE, ALLOW_EXISTING_MOVE }
enum class ForbiddenSectorPolicy { REJECT, REQUIRE_CONFIRMATION }
enum class RotatorAction {
    CONNECT, DISCONNECT, MOVE_ABSOLUTE, JOG, STOP, PARK, SELECT_PRESET,
    SET_AUTOMATION_ARMED, START_SATELLITE_TRACK, STOP_SATELLITE_TRACK,
}
enum class RotatorActionClass { READ_ONLY, SAFE_CONFIGURATION, PHYSICAL_MOTION, EMERGENCY_STOP, DANGEROUS_CONFIGURATION }
enum class RotatorDecisionKind { NO_ACTION, SHOW_PROMPT, MOVE, STOP, REJECT, WAIT }
enum class RotatorTargetSource {
    MANUAL_HEADING, CALLSIGN_GRID, DX_DETAIL, BAND_MAP_SELECTED, NEURAL_SELECTED,
    DX_CHASER_ENGAGED, CONTEST_SP, PORTABLE_ACTIVATION, OPERATIONS_PLANNER,
    SATELLITE_PASS, QO100_FIXED,
}

data class RotatorLimits(
    val azMin: Double = 0.0,
    val azMax: Double = 360.0,
    val elMin: Double = 0.0,
    val elMax: Double = 90.0,
) {
    init {
        require(azMin.isFinite() && azMax.isFinite() && azMin < azMax)
        require(elMin.isFinite() && elMax.isFinite() && elMin <= elMax)
        require(azMax - azMin <= 720.0)
        require(elMin >= -90.0 && elMax <= 180.0)
    }

    fun contains(azimuth: Double, elevation: Double?): Boolean =
        azimuth in azMin..azMax && (elevation == null || elevation in elMin..elMax)
}

data class ForbiddenSector(
    val startDeg: Double,
    val endDeg: Double,
    val reason: String,
    val policy: ForbiddenSectorPolicy = ForbiddenSectorPolicy.REJECT,
) {
    init {
        require(startDeg.isFinite() && endDeg.isFinite())
        require(reason.isNotBlank() && reason.length <= 160)
    }
}

data class RotatorPreset(
    val name: String,
    val azimuthDeg: Double,
    val elevationDeg: Double? = null,
    val bandId: String? = null,
) {
    init { require(name.isNotBlank() && name.length <= 48) }
}

data class SerialSettings(
    val stableIdentityHash: String,
    val baud: Int = 9600,
    val dataBits: Int = 8,
    val parity: String = "N",
    val stopBits: Int = 1,
    val dtr: Boolean = false,
    val rts: Boolean = false,
    val readTimeoutMs: Int = 1_500,
    val writeTimeoutMs: Int = 1_500,
) {
    init {
        require(stableIdentityHash.matches(Regex("[a-fA-F0-9]{16,128}")))
        require(baud in 300..921_600 && dataBits in 5..8 && parity in setOf("N", "E", "O"))
        require(stopBits in 1..2 && readTimeoutMs in 100..10_000 && writeTimeoutMs in 100..10_000)
    }
}

data class TcpSettings(
    val host: String,
    val port: Int,
    val connectTimeoutMs: Int = 2_000,
    val readTimeoutMs: Int = 1_500,
    val lanOptIn: Boolean = false,
) {
    init {
        require(host.isNotBlank() && host.length <= 253 && !host.any { it.isWhitespace() || it == '/' })
        require(port in 1..65535)
        require(connectTimeoutMs in 100..10_000 && readTimeoutMs in 100..10_000)
    }
}

data class RotatorDeviceProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val backend: RotatorBackend,
    val protocol: RotatorProtocolKind,
    val transport: RotatorTransportKind,
    val serial: SerialSettings? = null,
    val tcp: TcpSettings? = null,
    val hamlibModelId: Int? = null,
    val hamlibSerial: SerialSettings? = null,
    val hamlibTcp: TcpSettings? = null,
    val connectOnForeground: Boolean = false,
    val pollIntervalMs: Int = 1_000,
    val limits: RotatorLimits = RotatorLimits(),
    val parkAzimuthDeg: Double? = null,
    val parkElevationDeg: Double? = null,
    val headingOffsetOwner: HeadingOffsetOwner = HeadingOffsetOwner.NONE,
    val calibrationOffsetDeg: Double = 0.0,
    val allowFlipOver: Boolean = false,
    val forbiddenSectors: List<ForbiddenSector> = emptyList(),
    val capabilityOverrides: Map<RotatorCapability, CapabilitySupport> = emptyMap(),
    val capabilityOverrideProvenance: String? = null,
    val presets: List<RotatorPreset> = emptyList(),
) {
    init {
        require(UUID.fromString(id).toString() == id.lowercase())
        require(name.isNotBlank() && name.length <= 64)
        require(pollIntervalMs in 250..10_000)
        require(calibrationOffsetDeg in -180.0..180.0)
        require(forbiddenSectors.size <= MAX_ROTATOR_FORBIDDEN_SECTORS)
        require(presets.size <= MAX_ROTATOR_PRESETS)
        require((transport == RotatorTransportKind.SERIAL) == (serial != null))
        require((transport == RotatorTransportKind.TCP || transport == RotatorTransportKind.ROTCTLD) == (tcp != null))
        require(transport != RotatorTransportKind.ROTCTLD || protocol == RotatorProtocolKind.ROTCTLD)
        require(backend != RotatorBackend.EMBEDDED_HAMLIB || hamlibModelId != null)
        require(backend == RotatorBackend.EMBEDDED_HAMLIB || (hamlibSerial == null && hamlibTcp == null))
        require(hamlibSerial == null || hamlibTcp == null)
        require(parkAzimuthDeg == null || limits.contains(parkAzimuthDeg, parkElevationDeg))
        if (headingOffsetOwner == HeadingOffsetOwner.ROTATOR_CONTROLLER) require(calibrationOffsetDeg == 0.0)
    }
}

data class RotatorBandAssignment(
    val radioProfileId: String? = null,
    val bandId: String,
    val rotatorProfileId: String,
    val policy: RotatorBandPolicy = RotatorBandPolicy.OFF,
    val headingMode: HeadingMode = HeadingMode.SHORT_PATH,
    val offsetDeg: Double = 0.0,
    val bidirectional: Boolean = false,
    val txPolicy: MovementDuringTxPolicy = MovementDuringTxPolicy.BLOCK_NEW_MOVE,
) {
    init { require(bandId.isNotBlank() && offsetDeg in -180.0..180.0) }
}

data class RotatorSettingsDocument(
    val version: Int = ROTATOR_SETTINGS_VERSION,
    val profiles: List<RotatorDeviceProfile> = emptyList(),
    val bandAssignments: List<RotatorBandAssignment> = emptyList(),
    val reducedMotion: Boolean = false,
) {
    init {
        require(version == ROTATOR_SETTINGS_VERSION)
        require(profiles.size <= MAX_ROTATOR_PROFILES && profiles.map { it.id }.toSet().size == profiles.size)
        require(bandAssignments.size <= MAX_ROTATOR_BAND_POLICIES)
        require(bandAssignments.all { a -> profiles.any { it.id == a.rotatorProfileId } })
    }

    fun restoredSafe(): RotatorSettingsDocument = copy()
}

data class RotatorStateSnapshot(
    val profileId: String,
    val displayName: String,
    val backend: RotatorBackend,
    val protocol: RotatorProtocolKind,
    val transport: RotatorTransportKind,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val azimuthDeg: Double? = null,
    val elevationDeg: Double? = null,
    val targetAzimuthDeg: Double? = null,
    val targetElevationDeg: Double? = null,
    val movement: RotatorMovementState = RotatorMovementState.UNKNOWN,
    val speed: Double? = null,
    val parked: Boolean? = null,
    val limits: RotatorLimits,
    val lastUpdate: Instant? = null,
    val lastSanitizedError: String? = null,
    val generation: Long = 0,
) {
    fun isFresh(now: Instant, thresholdMs: Long): Boolean =
        connected && azimuthDeg != null && lastUpdate != null && !lastUpdate.isAfter(now) &&
            now.toEpochMilli() - lastUpdate.toEpochMilli() <= thresholdMs
}

data class RotatorTargetIntent(
    val intentId: String,
    val generation: Long,
    val source: RotatorTargetSource,
    val stationProfileId: String,
    val stationGrid: String? = null,
    val stationLatitude: Double? = null,
    val stationLongitude: Double? = null,
    val radioProfileId: String? = null,
    val bandId: String,
    val callsign: String? = null,
    val targetGrid: String? = null,
    val targetLatitude: Double? = null,
    val targetLongitude: Double? = null,
    val shortPathAzimuthDeg: Double,
    val longPathAzimuthDeg: Double,
    val elevationDeg: Double? = null,
    val createdAt: Instant,
    val expiresAt: Instant,
    val reason: String,
    val operatorSelected: Boolean,
)

data class OperatingContextSnapshot(
    val stationProfileId: String,
    val radioProfileId: String?,
    val bandId: String,
    val generation: Long,
    val foreground: Boolean,
)

data class RotatorAutomationSession(
    val armed: Boolean = false,
    val armedAt: Instant? = null,
    val pendingIntentId: String? = null,
    val lockedIntentId: String? = null,
    val manualOverrideUntil: Instant? = null,
    val satelliteSessionActive: Boolean = false,
) {
    fun cleared(): RotatorAutomationSession = RotatorAutomationSession()
}

data class RotatorAutomationConfig(
    val minimumAngleDeltaDeg: Double = 3.0,
    val targetStabilityDwellMs: Long = 2_000,
    val commandCooldownMs: Long = 10_000,
    val targetExpiryMs: Long = 300_000,
    val positionStaleMs: Long = 5_000,
    val maximumAutomaticMoveDeg: Double = 180.0,
) {
    init {
        require(minimumAngleDeltaDeg in 0.1..30.0)
        require(targetStabilityDwellMs in 0..60_000 && commandCooldownMs in 0..300_000)
        require(targetExpiryMs in 5_000..3_600_000 && positionStaleMs in 500..60_000)
        require(maximumAutomaticMoveDeg in 1.0..360.0)
    }
}

data class RotatorDecision(
    val kind: RotatorDecisionKind,
    val reason: String,
    val azimuthDeg: Double? = null,
    val elevationDeg: Double? = null,
    val requiresConfirmation: Boolean = false,
)

data class RotatorDiagnosticEvent(val at: Instant, val state: String, val detail: String)
data class RotatorDiagnosticsSnapshot(
    val pollAgeMs: Long?, val commands: Long, val responses: Long, val timeouts: Long,
    val capabilityDigest: String, val settingsDigest: String, val events: List<RotatorDiagnosticEvent>,
)
data class RotatorHealthSnapshot(
    val configuredDeviceCount: Int, val connectedDeviceCount: Int, val activeProfileId: String?,
    val backend: RotatorBackend?, val protocol: RotatorProtocolKind?, val positionFresh: Boolean,
    val movement: RotatorMovementState, val automationPolicy: RotatorBandPolicy?, val automationArmed: Boolean,
    val satelliteSession: Boolean, val lastCommandAgeMs: Long?, val timeoutCount: Long,
    val errorCount: Long, val settingsDigest: String,
)

data class RotatorHamlibModelDescriptor(val id: Int, val manufacturer: String, val model: String, val status: String)
data class RotatorHamlibCapabilitySnapshot(val modelId: Int, val capabilities: RotatorCapabilitySnapshot)
data class RotatorHamlibSession(val id: String, val modelId: Int)

interface RotatorHamlibPort {
    suspend fun enumerateModels(): List<RotatorHamlibModelDescriptor>
    suspend fun capabilities(modelId: Int): RotatorHamlibCapabilitySnapshot
    suspend fun open(profile: RotatorDeviceProfile, readOnly: Boolean = false): RotatorHamlibSession
    suspend fun close(session: RotatorHamlibSession)
    suspend fun poll(session: RotatorHamlibSession): RotatorStateSnapshot
    suspend fun setPosition(session: RotatorHamlibSession, azimuthDeg: Double, elevationDeg: Double?): Boolean
    suspend fun stop(session: RotatorHamlibSession): Boolean
    suspend fun park(session: RotatorHamlibSession): Boolean
}

interface RotatorOperatingContextPort { fun snapshot(): OperatingContextSnapshot }
interface RotatorTargetSourcePort { fun selectedTarget(): RotatorTargetIntent?; fun candidates(): List<RotatorTargetIntent> }
interface RotatorReviewPort { fun review(target: RotatorTargetIntent, decision: RotatorDecision) }
interface RotatorRadioStatePort { fun isTransmitting(radioProfileId: String?): Boolean }
interface RotatorSatellitePort { fun sample(sessionId: String, at: Instant): SatellitePointingSample? }
interface RotatorReadOnlyPort { fun states(): List<RotatorStateSnapshot>; fun health(): RotatorHealthSnapshot }
interface RotatorActionPort { suspend fun submit(profileId: String, action: RotatorAction, azimuthDeg: Double? = null, elevationDeg: Double? = null): Boolean }
interface RotatorPhysicalAuthorityPort {
    fun acquire(identity: String, owner: String): Boolean
    fun release(identity: String, owner: String)
}

data class SatellitePointingSample(
    val sessionId: String, val at: Instant, val azimuthDeg: Double, val elevationDeg: Double,
    val aos: Instant, val los: Instant, val ephemerisExpiresAt: Instant,
)
