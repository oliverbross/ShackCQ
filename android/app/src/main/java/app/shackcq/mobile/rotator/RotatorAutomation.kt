package app.shackcq.mobile.rotator

import java.time.Instant

data class RotatorAutomationInput(
    val assignment: RotatorBandAssignment?,
    val session: RotatorAutomationSession,
    val context: OperatingContextSnapshot,
    val transmitting: Boolean,
    val state: RotatorStateSnapshot,
    val capabilities: RotatorCapabilitySnapshot,
    val target: RotatorTargetIntent?,
    val now: Instant,
    val lastCommandAt: Instant? = null,
    val config: RotatorAutomationConfig = RotatorAutomationConfig(),
    val planned: PlannedHeading? = null,
)

object RotatorAutomationEngine {
    fun decide(input: RotatorAutomationInput): RotatorDecision {
        val assignment = input.assignment ?: return reject("no band assignment")
        if (assignment.policy == RotatorBandPolicy.OFF || assignment.policy == RotatorBandPolicy.MANUAL) return noAction("manual policy")
        val target = input.target ?: return noAction("no selected target")
        if (!target.operatorSelected) return reject("target was not explicitly selected")
        if (assignment.policy == RotatorBandPolicy.PROMPT) return RotatorDecision(RotatorDecisionKind.SHOW_PROMPT, "operator review required",
            target.shortPathAzimuthDeg, target.elevationDeg)
        if (assignment.policy == RotatorBandPolicy.SATELLITE_SESSION && !input.session.satelliteSessionActive) return reject("satellite session not active")
        if (!input.session.armed) return reject("automation session is unarmed")
        if (!input.context.foreground) return reject("application is backgrounded")
        if (target.stationProfileId != input.context.stationProfileId || target.radioProfileId != input.context.radioProfileId ||
            target.bandId != input.context.bandId || target.generation != input.context.generation) return reject("operating context generation mismatch")
        if (target.expiresAt.isBefore(input.now) || target.createdAt.isAfter(input.now)) return reject("target expired or has a future timestamp")
        if (!input.state.isFresh(input.now, input.config.positionStaleMs)) return reject("position missing or stale")
        if (!input.capabilities.supports(RotatorCapability.ABSOLUTE_MOVE)) return reject("absolute movement capability unavailable")
        if (input.transmitting && assignment.txPolicy == MovementDuringTxPolicy.BLOCK_NEW_MOVE) return reject("radio is transmitting")
        if (input.session.satelliteSessionActive && assignment.policy != RotatorBandPolicy.SATELLITE_SESSION) return reject("satellite session owns movement")
        if (input.session.manualOverrideUntil?.isAfter(input.now) == true) return reject("manual override holdoff active")
        val armedAt = input.session.armedAt ?: return reject("arm timestamp unavailable")
        if (input.now.toEpochMilli() - armedAt.toEpochMilli() < input.config.targetStabilityDwellMs) return wait("target dwell not met")
        if (input.lastCommandAt != null && input.now.toEpochMilli() - input.lastCommandAt.toEpochMilli() < input.config.commandCooldownMs) return wait("command cooldown active")
        val planned = input.planned ?: return reject("safe path not resolved")
        if (!planned.accepted) return RotatorDecision(if (planned.requiresConfirmation) RotatorDecisionKind.SHOW_PROMPT else RotatorDecisionKind.REJECT,
            planned.reason, planned.azimuthDeg, planned.elevationDeg, planned.requiresConfirmation)
        val current = requireNotNull(input.state.azimuthDeg)
        val targetAz = requireNotNull(planned.azimuthDeg)
        val delta = kotlin.math.abs(targetAz - current)
        if (delta < input.config.minimumAngleDeltaDeg) return noAction("inside movement deadband")
        if (delta > input.config.maximumAutomaticMoveDeg) return reject("automatic move exceeds configured maximum")
        return RotatorDecision(RotatorDecisionKind.MOVE, "all automatic movement gates passed", targetAz, planned.elevationDeg)
    }
    private fun reject(reason: String) = RotatorDecision(RotatorDecisionKind.REJECT, reason)
    private fun wait(reason: String) = RotatorDecision(RotatorDecisionKind.WAIT, reason)
    private fun noAction(reason: String) = RotatorDecision(RotatorDecisionKind.NO_ACTION, reason)
}

data class SatelliteTrackingSession(
    val id: String, val profileId: String, val startedAt: Instant, val aos: Instant, val los: Instant,
    val minimumDeltaDeg: Double = 1.0, val updateCadenceMs: Long = 1_000,
    val parkAfterLos: Boolean = false, val lastCommandAt: Instant? = null,
    val lastAzimuthDeg: Double? = null, val lastElevationDeg: Double? = null,
)

object RotatorSatelliteTrackingEngine {
    fun decide(session: SatelliteTrackingSession, sample: SatellitePointingSample?, state: RotatorStateSnapshot,
               capabilities: RotatorCapabilitySnapshot, now: Instant, foreground: Boolean): RotatorDecision {
        if (!foreground) return RotatorDecision(RotatorDecisionKind.STOP, "background ends satellite tracking")
        if (!state.connected) return RotatorDecision(RotatorDecisionKind.STOP, "disconnect ends satellite tracking")
        if (sample == null || sample.ephemerisExpiresAt.isBefore(now)) return RotatorDecision(RotatorDecisionKind.STOP, "ephemeris missing or stale")
        if (now.isBefore(session.aos) || now.isAfter(session.los)) return RotatorDecision(RotatorDecisionKind.NO_ACTION, "outside pass bounds")
        if (!capabilities.supports(RotatorCapability.AZIMUTH) || !capabilities.supports(RotatorCapability.ELEVATION)) return RotatorDecision(RotatorDecisionKind.REJECT, "azimuth/elevation capability required")
        if (session.lastCommandAt != null && now.toEpochMilli() - session.lastCommandAt.toEpochMilli() < session.updateCadenceMs) return RotatorDecision(RotatorDecisionKind.WAIT, "tracking cadence")
        val deltaAz = session.lastAzimuthDeg?.let { RotatorGeometry.angularDistance(it, sample.azimuthDeg) } ?: Double.MAX_VALUE
        val deltaEl = session.lastElevationDeg?.let { kotlin.math.abs(it - sample.elevationDeg) } ?: Double.MAX_VALUE
        if (maxOf(deltaAz, deltaEl) < session.minimumDeltaDeg) return RotatorDecision(RotatorDecisionKind.NO_ACTION, "tracking hysteresis")
        return RotatorDecision(RotatorDecisionKind.MOVE, "supplied satellite sample", sample.azimuthDeg, sample.elevationDeg)
    }
}
