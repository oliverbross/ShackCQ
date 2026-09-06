package app.shackcq.mobile.rotator

import org.junit.Assert.*
import org.junit.Test

class RotatorGeometryTest {
    @Test fun rangePreservesOver360Target() {
        assertEquals(listOf(90.0, 450.0), RotatorGeometry.equivalentHeadings(90.0, RotatorLimits(0.0, 450.0)))
        val plan = RotatorGeometry.plan(430.0, 90.0, null, RotatorLimits(0.0, 450.0), emptyList(), 0.0, HeadingOffsetOwner.NONE, false, false, false)
        assertEquals(450.0, plan.azimuthDeg!!, 0.0)
    }
    @Test fun northCrossingChoosesValidShortestRepresentation() {
        val plan = RotatorGeometry.plan(358.0, 2.0, null, RotatorLimits(0.0, 450.0), emptyList(), 0.0, HeadingOffsetOwner.NONE, false, false, false)
        assertEquals(362.0, plan.azimuthDeg!!, 0.0)
    }
    @Test fun forbiddenSectorRejectsPath() {
        val plan = RotatorGeometry.plan(0.0, 100.0, null, RotatorLimits(), listOf(ForbiddenSector(40.0, 60.0, "tower")), 0.0, HeadingOffsetOwner.NONE, false, false, false)
        assertFalse(plan.accepted); assertFalse(plan.requiresConfirmation)
    }
    @Test fun perBandOffsetAppliesExactlyOnce() {
        val plan = RotatorGeometry.plan(0.0, 100.0, null, RotatorLimits(), emptyList(), 15.0, HeadingOffsetOwner.SHACKCQ, false, false, false)
        assertEquals(115.0, plan.azimuthDeg!!, 0.0)
    }
    @Test fun controllerOwnedOffsetPreventsShackCQOffset() {
        val plan = RotatorGeometry.plan(0.0, 100.0, null, RotatorLimits(), emptyList(), 15.0, HeadingOffsetOwner.ROTATOR_CONTROLLER, false, false, false)
        assertEquals(100.0, plan.azimuthDeg!!, 0.0)
    }
    @Test fun bidirectionalMayChooseAlternate() {
        val plan = RotatorGeometry.plan(275.0, 100.0, null, RotatorLimits(), emptyList(), 0.0, HeadingOffsetOwner.NONE, true, false, false)
        assertEquals(280.0, plan.azimuthDeg!!, 0.0); assertTrue(plan.usedBidirectionalAlternative)
    }
    @Test fun flipOverRequiresCapabilityAndExplicitSetting() {
        val denied = RotatorGeometry.plan(10.0, 20.0, 120.0, RotatorLimits(0.0, 360.0, 0.0, 90.0), emptyList(), 0.0, HeadingOffsetOwner.NONE, false, true, false)
        val allowed = RotatorGeometry.plan(10.0, 20.0, 120.0, RotatorLimits(0.0, 360.0, 0.0, 90.0), emptyList(), 0.0, HeadingOffsetOwner.NONE, false, true, true)
        assertFalse(denied.accepted); assertTrue(allowed.accepted); assertTrue(allowed.usedFlipOver)
    }
    @Test fun longPathIsExplicitAndBearingUsesMaidenhead() {
        val result = RotatorGeometry.bearing(RotatorGeometry.maidenhead("JN88TQ"), RotatorGeometry.maidenhead("FN31PR"))
        assertEquals(RotatorGeometry.normalize360(result.shortPathDeg + 180.0), result.longPathDeg, 0.0001); assertTrue(result.distanceKm > 1_000)
    }
}
