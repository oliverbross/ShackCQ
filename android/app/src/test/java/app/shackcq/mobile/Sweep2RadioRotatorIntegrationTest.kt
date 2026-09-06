package app.shackcq.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sweep2RadioRotatorIntegrationTest {
    @Test fun legacyAndFutureProfileMigrationIsStableAndFailSafe() {
        assertEquals(RadioProfileCatalog.KX2.id, RadioProfileCatalog.migrate(null, "ELECRAFT_KX2"))
        assertEquals(RadioProfileCatalog.FLEX.id, RadioProfileCatalog.migrate(null, "FLEXRADIO"))
        assertEquals(RadioProfileCatalog.UNKNOWN.id, RadioProfileCatalog.migrate("future.radio.v99", null))
        assertTrue(RadioProfileCatalog.UNKNOWN.readOnly)
        assertNull(RadioProfileCatalog.find(RadioProfileCatalog.UNKNOWN.id))
    }

    @Test fun selectionClosesPreviousBackendBeforeCreatingNext() = runBlocking {
        val events = mutableListOf<String>()
        val factory = RadioBackendFactory { profile -> FakeBackend(profile, events) }
        val controller = RadioPlatformController(mapOf(RadioBackendKind.NATIVE_QMX to factory))
        assertTrue(controller.select(RadioProfileCatalog.QMX, connectAfterSelection = true))
        assertTrue(controller.select(RadioProfileCatalog.QMX_PLUS, connectAfterSelection = true))
        assertEquals(listOf("create:QMX", "connect:QMX", "receive:QMX", "disconnect:QMX", "close:QMX",
            "create:QMX_PLUS", "connect:QMX_PLUS"), events)
    }

    @Test fun profileRestoreDoesNotConnectUnlessExplicitlyRequested() = runBlocking {
        val events = mutableListOf<String>()
        val controller = RadioPlatformController(mapOf(RadioBackendKind.NATIVE_QMX to RadioBackendFactory { FakeBackend(it, events) }))
        assertTrue(controller.select(RadioProfileCatalog.QMX, connectAfterSelection = false))
        assertFalse(controller.snapshot.connected)
        assertTrue(events.isEmpty())
        assertTrue(controller.connectSelected())
        assertEquals(listOf("create:QMX", "connect:QMX"), events)
    }

    @Test fun transmitNeedsFreshConfirmationAndReadOnlyStillBlocksIt() = runBlocking {
        val events = mutableListOf<String>()
        val factory = RadioBackendFactory { profile -> FakeBackend(profile, events) }
        val controller = RadioPlatformController(mapOf(RadioBackendKind.NATIVE_QMX to factory))
        controller.select(RadioProfileCatalog.QMX, true)
        val ptt = RadioPlatformAction(RadioActionClass.TRANSMIT, "ptt", longValue = 1)
        assertFalse(controller.dispatch(ptt, operatorConfirmed = false))
        assertTrue(controller.dispatch(ptt, operatorConfirmed = true))
        controller.select(RadioProfileCatalog.QMX.copy(readOnly = true), true)
        assertFalse(controller.dispatch(ptt, operatorConfirmed = true))
    }

    @Test fun sharedPhysicalIdentityCannotHaveRadioAndRotatorOwners() {
        val authority = PhysicalDeviceAuthority()
        assertTrue(authority.acquire("serial:abc", "radio:qmx"))
        assertFalse(authority.acquire("serial:abc", "rotator:arco"))
        authority.release("serial:abc", "radio:qmx")
        assertTrue(authority.acquire("serial:abc", "rotator:arco"))
    }

    private class FakeBackend(
        override val profile: RadioConnectionProfile,
        private val events: MutableList<String>,
    ) : ManagedRadioBackend {
        private var connected = false
        override val snapshot: RadioRuntimeSnapshot get() = RadioRuntimeSnapshot(
            profileId = profile.id, backendKind = profile.backendKind, modelId = profile.modelId, connected = connected,
        )
        override suspend fun connect(): Boolean { events += "connect:${profile.modelId.value}"; connected = true; return true }
        override suspend fun disconnect() { events += "disconnect:${profile.modelId.value}"; connected = false }
        override suspend fun requestReceive(): Boolean { events += "receive:${profile.modelId.value}"; return true }
        override suspend fun execute(action: RadioPlatformAction): Boolean { events += "execute:${action.name}"; return true }
        override fun close() { events += "close:${profile.modelId.value}" }
        init { events += "create:${profile.modelId.value}" }
    }
}
