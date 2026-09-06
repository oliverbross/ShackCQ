package app.shackcq.mobile.rotator

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RotatorAutomationTest {
    private val now = Instant.parse("2026-08-23T00:10:00Z")
    private val profileId = "11111111-1111-1111-1111-111111111111"
    private val assignment = RotatorBandAssignment("radio", "20m", profileId, RotatorBandPolicy.AUTO_SELECTED_TARGET)
    private val context = OperatingContextSnapshot("station", "radio", "20m", 7, true)
    private val target = RotatorTargetIntent("intent", 7, RotatorTargetSource.DX_DETAIL, "station", radioProfileId = "radio", bandId = "20m",
        shortPathAzimuthDeg = 100.0, longPathAzimuthDeg = 280.0, createdAt = now.minusSeconds(30), expiresAt = now.plusSeconds(60), reason = "selected", operatorSelected = true)
    private val state = RotatorStateSnapshot(profileId, "ARCO", RotatorBackend.NATIVE, RotatorProtocolKind.GS232, RotatorTransportKind.TCP,
        true, true, 10.0, limits = RotatorLimits(), lastUpdate = now.minusSeconds(1), generation = 7)
    private val caps = RotatorCapabilitySnapshot(mapOf(RotatorCapability.ABSOLUTE_MOVE to CapabilitySupport.SUPPORTED))
    private fun input(session: RotatorAutomationSession = RotatorAutomationSession(true, now.minusSeconds(10)), stateValue: RotatorStateSnapshot = state,
        targetValue: RotatorTargetIntent? = target, transmitting: Boolean = false, planned: PlannedHeading? = PlannedHeading(true, 100.0, reason = "safe")) =
        RotatorAutomationInput(assignment, session, context, transmitting, stateValue, caps, targetValue, now, planned = planned)

    @Test fun missingPositionBlocksAutomaticMove() { assertEquals(RotatorDecisionKind.REJECT, RotatorAutomationEngine.decide(input(stateValue = state.copy(azimuthDeg = null))).kind) }
    @Test fun automationDefaultsUnarmedAfterRestore() { assertFalse(RotatorAutomationSession().armed); assertEquals(RotatorDecisionKind.REJECT, RotatorAutomationEngine.decide(input(RotatorAutomationSession())).kind) }
    @Test fun providerLiveSpotAloneCannotMove() { assertEquals(RotatorDecisionKind.REJECT, RotatorAutomationEngine.decide(input(targetValue = target.copy(operatorSelected = false))).kind) }
    @Test fun explicitSelectedTargetPlusArmMayMove() { assertEquals(RotatorDecisionKind.MOVE, RotatorAutomationEngine.decide(input()).kind) }
    @Test fun txDefaultBlocksNewMovement() { assertEquals(RotatorDecisionKind.REJECT, RotatorAutomationEngine.decide(input(transmitting = true)).kind) }
    @Test fun manualOverrideHoldsAutomation() { assertEquals(RotatorDecisionKind.REJECT, RotatorAutomationEngine.decide(input(session = RotatorAutomationSession(true, now.minusSeconds(10), manualOverrideUntil = now.plusSeconds(5)))).kind) }
    @Test fun dwellAndDeadbandPreventChatter() {
        assertEquals(RotatorDecisionKind.WAIT, RotatorAutomationEngine.decide(input(session = RotatorAutomationSession(true, now.minusMillis(500)))).kind)
        assertEquals(RotatorDecisionKind.NO_ACTION, RotatorAutomationEngine.decide(input(stateValue = state.copy(azimuthDeg = 98.0))).kind)
    }
    @Test fun conflictingSatelliteSessionBlocksTerrestrialAuto() { assertEquals(RotatorDecisionKind.REJECT, RotatorAutomationEngine.decide(input(session = RotatorAutomationSession(true, now.minusSeconds(10), satelliteSessionActive = true))).kind) }
}
