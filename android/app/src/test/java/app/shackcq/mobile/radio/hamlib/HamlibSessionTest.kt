package app.shackcq.mobile.radio.hamlib

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HamlibSessionTest {
    @Test fun sessionCreatesRequestedModelAndRejectsInvalidModel() {
        val api = FakeHamlibApi(); HamlibSession(1, api).close(); assertEquals(1, api.createdModel)
        assertThrows(IllegalStateException::class.java) { HamlibSession(999, api) }
    }
    @Test fun closeIsIdempotent() { val api = FakeHamlibApi(); val session = HamlibSession(1, api); session.close(); session.close(); assertEquals(1, api.destroyCount) }
    @Test fun fiveHundredDummySessionsOpenAndCloseWithoutLeakingHandles() {
        val api = FakeHamlibApi()
        repeat(500) { HamlibSession(1, api).close() }
        assertEquals(500, api.destroyCount)
    }
    @Test fun readOnlyReachesNative() { val api = FakeHamlibApi(); val session = HamlibSession(1, api); session.setReadOnly(true); assertTrue(api.readOnly) }
    @Test fun serialConfigurationAndBridgeIoAreBounded() {
        val api = FakeHamlibApi(); val session = HamlibSession(1, api)
        session.configure(HamlibSerialProfile("stable", 9600)); assertEquals(9600, api.serial?.baud)
        assertEquals(3, session.withHandle { api.bridgeWrite(it, byteArrayOf(1, 2, 3)) })
        assertArrayEquals(ByteArray(0), session.withHandle { api.bridgeRead(it, 32, 50) })
        assertThrows(IllegalArgumentException::class.java) { HamlibSerialProfile("stable", 9600, timeoutMs = 1) }
    }
    @Test fun networkConfigurationAndBoundsReachNative() {
        val api = FakeHamlibApi(); val session = HamlibSession(1, api)
        session.configure(HamlibNetworkProfile("localhost", 4532, enabled = true)); assertEquals(4532, api.network?.port)
        assertThrows(IllegalArgumentException::class.java) { HamlibNetworkProfile("localhost", 0) }
    }
    @Test fun snapshotProjectsVfoPairsAndRejectsMalformedPayload() {
        val api = FakeHamlibApi(); val snapshot = HamlibSession(1, api).snapshot()
        assertEquals(7_100_000, snapshot.frequencyHz); assertEquals(7_100_000, snapshot.frequencyAHz)
        assertEquals(7_200_000, snapshot.frequencyBHz); assertEquals("VFOA", snapshot.vfo); assertEquals("VFOB", snapshot.txVfo)
        assertThrows(IllegalArgumentException::class.java) { HamlibModelRegistry.parseSnapshot("{\"ok\":false,\"error\":\"bad\"}") }
    }
    @Test fun typedActionReachesNative() { val api = FakeHamlibApi(); val action = HamlibAction.SetMode("USB"); HamlibSession(1, api).apply(action); assertEquals(action, api.actions.single()) }
    @Test fun controllerRejectsDisabledNetwork() = runBlocking {
        val controller = HamlibConnectionController(FakeHamlibApi())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { controller.connectNetwork(1, HamlibNetworkProfile("localhost", 4532)) } }
        Unit
    }
    @Test fun controllerPublishesConnectedSnapshot() = runBlocking {
        val controller = HamlibConnectionController(FakeHamlibApi())
        controller.connectNetwork(1, HamlibNetworkProfile("localhost", 4532, enabled = true), pollIntervalMs = 100)
        delay(150); assertEquals(7_100_000L, controller.snapshot.value?.frequencyHz)
        controller.disconnect(); assertFalse(controller.diagnostics.value.connected); assertNull(controller.snapshot.value)
        controller.connectNetwork(1, HamlibNetworkProfile("localhost", 4532, enabled = true), pollIntervalMs = 100)
        assertTrue(controller.diagnostics.value.connected); controller.disconnect()
    }
}

internal class FakeHamlibApi : HamlibNativeApi {
    var createdModel = 0; var destroyCount = 0; var readOnly = false
    var serial: HamlibSerialProfile? = null; var network: HamlibNetworkProfile? = null
    val actions = mutableListOf<HamlibAction>()
    override fun libraryInfo() = HamlibModelRegistryTest.LIBRARY_JSON
    override fun models() = HamlibModelRegistryTest.MODELS_JSON
    override fun create(modelId: Int) = (if (modelId == 1) 9L else 0L).also { createdModel = modelId }
    override fun destroy(handle: Long) { destroyCount++ }
    override fun readOnly(handle: Long, enabled: Boolean) = 0.also { readOnly = enabled }
    override fun configureSerial(handle: Long, profile: HamlibSerialProfile) = 0.also { serial = profile }
    override fun configureNetwork(handle: Long, profile: HamlibNetworkProfile) = 0.also { network = profile }
    override fun open(handle: Long) = 0
    override fun close(handle: Long) = 0
    override fun bridgeRead(handle: Long, maximum: Int, timeoutMs: Int) = ByteArray(0)
    override fun bridgeWrite(handle: Long, data: ByteArray) = data.size
    override fun snapshot(handle: Long) = """{"ok":true,"modelId":1,"vfo":"VFOA","txVfo":"VFOB","frequencyHz":7100000,"frequencyAHz":7100000,"frequencyBHz":7200000,"mode":"USB","passbandHz":2400,"split":false,"ritHz":0,"xitHz":0,"ptt":false,"levels":{"STRENGTH":-73}}"""
    override fun apply(handle: Long, action: HamlibAction) = 0.also { actions += action }
    override fun error(status: Int) = "error $status"
}
