package app.shackcq.mobile.radio.hamlib

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class HamlibSerialProfile(
    val stableDeviceId: String,
    val baud: Int,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Int = 0,
    val handshake: Int = 0,
    val timeoutMs: Int = 1000,
    val rts: Int = 0,
    val dtr: Int = 0,
) {
    init {
        require(stableDeviceId.isNotBlank() && stableDeviceId.length <= 256)
        require(baud in 300..3_000_000 && dataBits in 5..8 && stopBits in 1..2)
        require(timeoutMs in 50..60_000)
    }
}

data class HamlibNetworkProfile(
    val host: String,
    val port: Int,
    val timeoutMs: Int = 1000,
    val enabled: Boolean = false,
) {
    init {
        require(host.isNotBlank() && host.length <= 253)
        require(port in 1..65535 && timeoutMs in 50..60_000)
    }
}

interface HamlibSerialTransportPort {
    suspend fun configure(profile: HamlibSerialProfile)
    suspend fun read(maximum: Int, timeoutMs: Int): ByteArray
    suspend fun write(data: ByteArray): Int
    suspend fun flush()
    suspend fun setControlLines(rts: Int, dtr: Int)
    fun cancelPendingIo()
    suspend fun disconnect()
}

internal class HamlibTransportBridge(
    private val api: HamlibNativeApi,
    private val session: HamlibSession,
    private val serial: HamlibSerialTransportPort,
) {
    private var outbound: Job? = null
    private var inbound: Job? = null

    suspend fun start(scope: CoroutineScope, profile: HamlibSerialProfile) {
        check(outbound == null && inbound == null) { "Transport bridge already started" }
        serial.configure(profile)
        serial.setControlLines(profile.rts, profile.dtr)
        outbound = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val bytes = session.withHandle { api.bridgeRead(it, 16_384, 250) } ?: break
                if (bytes.isNotEmpty()) {
                    var offset = 0
                    while (offset < bytes.size && isActive) {
                        val count = serial.write(bytes.copyOfRange(offset, bytes.size))
                        check(count > 0) { "Serial transport made no write progress" }
                        offset += count
                    }
                }
            }
        }
        inbound = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val bytes = serial.read(16_384, 250)
                if (bytes.isNotEmpty()) {
                    val written = session.withHandle { api.bridgeWrite(it, bytes) } ?: break
                    check(written == bytes.size) { "Hamlib bridge rejected serial input" }
                }
            }
        }
    }

    suspend fun stop() {
        serial.cancelPendingIo()
        outbound?.cancel(CancellationException("Hamlib bridge closing"))
        inbound?.cancel(CancellationException("Hamlib bridge closing"))
        withTimeoutOrNull(2_000L) {
            outbound?.join()
            inbound?.join()
        }
        outbound = null
        inbound = null
        withTimeoutOrNull(2_000L) {
            serial.flush()
            serial.disconnect()
        }
    }
}
