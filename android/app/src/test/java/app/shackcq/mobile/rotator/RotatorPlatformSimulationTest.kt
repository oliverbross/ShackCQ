package app.shackcq.mobile.rotator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RotatorPlatformSimulationTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    @Test fun closeIsIdempotent() = runBlocking { val driver = FakeDriver(profile()); driver.close(); driver.close(); assertEquals(2, driver.closeCount) }
    @Test fun oneMovementAuthorityPerPhysicalDevice() {
        runBlocking {
            val p1 = profile("11111111-1111-1111-1111-111111111111"); val p2 = profile("22222222-2222-2222-2222-222222222222")
            val store = RotatorProfileStore(RotatorSettingsDocument(profiles = listOf(p1, p2))); val registry = RotatorBackendRegistry().apply { register(RotatorBackend.NATIVE) { FakeDriver(it) } }
            val controller = RotatorPlatformController(store, registry) { now }; controller.connect(p1.id)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { controller.connect(p2.id) } }
        }
    }
    @Test fun satelliteUsesSuppliedAzElOnly() {
        val caps = RotatorCapabilitySnapshot(mapOf(RotatorCapability.AZIMUTH to CapabilitySupport.SUPPORTED, RotatorCapability.ELEVATION to CapabilitySupport.SUPPORTED))
        val sample = SatellitePointingSample("s", now, 123.0, 45.0, now.minusSeconds(1), now.plusSeconds(10), now.plusSeconds(10))
        val decision = RotatorSatelliteTrackingEngine.decide(SatelliteTrackingSession("s", profile().id, now, sample.aos, sample.los), sample,
            FakeDriver(profile()).state, caps, now, true)
        assertEquals(123.0, decision.azimuthDeg!!, 0.0); assertEquals(45.0, decision.elevationDeg!!, 0.0)
    }
    @Test fun satelliteTrackingStopsOnBackgroundDisconnectOrStale() {
        val caps = RotatorCapabilitySnapshot(mapOf(RotatorCapability.AZIMUTH to CapabilitySupport.SUPPORTED, RotatorCapability.ELEVATION to CapabilitySupport.SUPPORTED))
        val session = SatelliteTrackingSession("s", profile().id, now, now.minusSeconds(1), now.plusSeconds(10))
        assertEquals(RotatorDecisionKind.STOP, RotatorSatelliteTrackingEngine.decide(session, null, FakeDriver(profile()).state, caps, now, true).kind)
        assertEquals(RotatorDecisionKind.STOP, RotatorSatelliteTrackingEngine.decide(session, null, FakeDriver(profile()).state, caps, now, false).kind)
    }
    @Test fun deterministicFullSimulationMovesOnceStopsAndDisarms() = runBlocking {
        val p = profile(); val store = RotatorProfileStore(RotatorSettingsDocument(profiles = listOf(p), bandAssignments = listOf(RotatorBandAssignment(null, "20m", p.id, RotatorBandPolicy.AUTO_SELECTED_TARGET))))
        val fake = FakeDriver(p); val registry = RotatorBackendRegistry().apply { register(RotatorBackend.NATIVE) { fake } }
        val controller = RotatorPlatformController(store, registry) { now }; assertTrue(controller.connect(p.id).connected)
        controller.armAutomation(); assertTrue(controller.automationSession().armed)
        assertTrue(controller.submit(p.id, RotatorAction.MOVE_ABSOLUTE, 100.0)); assertEquals(1, fake.moveCount)
        assertTrue(controller.submit(p.id, RotatorAction.STOP)); assertEquals(1, fake.stopCount); assertFalse(controller.automationSession().armed)
    }

    private fun profile(id: String = "11111111-1111-1111-1111-111111111111") = RotatorDeviceProfile(id, "ARCO", RotatorBackend.NATIVE,
        RotatorProtocolKind.GS232, RotatorTransportKind.TCP, tcp = TcpSettings("192.0.2.20", 23, lanOptIn = true))
    private class FakeDriver(override val profile: RotatorDeviceProfile) : RotatorDriver {
        override val capabilities = RotatorCapabilitySnapshot(mapOf(RotatorCapability.ABSOLUTE_MOVE to CapabilitySupport.SUPPORTED, RotatorCapability.STOP to CapabilitySupport.SUPPORTED))
        var state = RotatorStateSnapshot(profile.id, profile.name, profile.backend, profile.protocol, profile.transport, true, true, 10.0,
            limits = profile.limits, lastUpdate = Instant.parse("2026-08-23T00:00:00Z")); var moveCount = 0; var stopCount = 0; var closeCount = 0
        override suspend fun connect(readOnlyProbe: Boolean) = state
        override suspend fun poll(generation: Long) = state.copy(generation = generation).also { state = it }
        override suspend fun move(azimuthDeg: Double, elevationDeg: Double?, generation: Long) = true.also { moveCount++ }
        override suspend fun stop(generation: Long) = true.also { stopCount++ }
        override suspend fun park(generation: Long) = false
        override suspend fun close() { closeCount++ }
    }
}
