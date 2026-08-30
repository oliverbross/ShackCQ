package app.rigweave.mobile.morse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class M32BleDevice(val address: String, val name: String = "Morserino-32")

internal fun m32BleWriteChunks(bytes: ByteArray): List<ByteArray> =
    bytes.asList().chunked(20).map { it.toByteArray() }

internal fun m32BleResponseComplete(command: String, response: String): Boolean {
    val objects = extractJsonObjects(response)
    return if (command.equals("put device/protocol/on", ignoreCase = true)) {
        objects.any { "\"device\"" in it || "\"error\"" in it }
    } else objects.isNotEmpty()
}

/** Nordic UART Service transport used by M32 firmware when Bluetooth Use = BLE Serial. */
@SuppressLint("MissingPermission")
class M32BleSerialTransport(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val appContext = context.applicationContext
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val operationMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var connectResult: CompletableDeferred<Unit>? = null
    private var writeResult: CompletableDeferred<Unit>? = null

    suspend fun scan(durationMs: Long = 5_000): List<M32BleDevice> {
        val scanner = adapter?.bluetoothLeScanner ?: error("Bluetooth is off or unavailable.")
        val found = linkedMapOf<String, M32BleDevice>()
        val scanFailure = AtomicReference<String?>(null)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "Morserino-32"
                found[result.device.address] = M32BleDevice(result.device.address, name)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                scanFailure.set("BLE scan failed ($errorCode).")
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        try { delay(durationMs) } finally { runCatching { scanner.stopScan(callback) } }
        scanFailure.get()?.let { error(it) }
        return found.values.toList()
    }

    suspend fun connect(address: String) {
        disconnect()
        while (incoming.tryReceive().isSuccess) Unit
        val device = adapter?.getRemoteDevice(address) ?: error("Bluetooth is unavailable.")
        val ready = CompletableDeferred<Unit>()
        connectResult = ready
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        try { withTimeout(15_000) { ready.await() } }
        finally { connectResult = null }
    }

    suspend fun exchange(command: String, timeoutMs: Long): ByteArray = operationMutex.withLock {
        while (incoming.tryReceive().isSuccess) Unit
        write("$command\n".toByteArray(Charsets.UTF_8))
        val bytes = ByteArrayOutputStream()
        withTimeout(timeoutMs) {
            while (true) {
                bytes.write(incoming.receive())
                val text = bytes.toString(Charsets.UTF_8.name())
                if (m32BleResponseComplete(command, text)) return@withTimeout
            }
        }
        bytes.toByteArray()
    }

    suspend fun read(timeoutMs: Long): ByteArray = withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: byteArrayOf()

    fun disconnect() {
        connectResult?.cancel()
        writeResult?.cancel()
        rx = null
        gatt?.runCatching { disconnect() }
        gatt?.close()
        gatt = null
    }

    private suspend fun write(bytes: ByteArray) {
        val currentGatt = gatt ?: error("M32 BLE is not connected.")
        val characteristic = rx ?: error("M32 BLE Serial service is unavailable.")
        for (chunk in m32BleWriteChunks(bytes)) {
            val completed = CompletableDeferred<Unit>()
            writeResult = completed
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(characteristic)
            }
            if (!accepted) error("M32 BLE write was rejected.")
            withTimeout(3_000) { completed.await() }
            writeResult = null
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@M32BleSerialTransport.gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.discoverServices()) failConnect("Could not discover M32 BLE services.")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                failConnect("M32 BLE disconnected${if (status == BluetoothGatt.GATT_SUCCESS) "." else " ($status)."}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return failConnect("M32 BLE service discovery failed ($status).")
            val service: BluetoothGattService = gatt.getService(NUS_SERVICE_UUID)
                ?: return failConnect("This device does not expose M32 BLE Serial.")
            rx = service.getCharacteristic(NUS_RX_UUID)
                ?: return failConnect("M32 BLE receive characteristic is missing.")
            val tx = service.getCharacteristic(NUS_TX_UUID)
                ?: return failConnect("M32 BLE notify characteristic is missing.")
            if (!gatt.setCharacteristicNotification(tx, true)) return failConnect("Could not enable M32 BLE notifications.")
            val ccc = tx.getDescriptor(CLIENT_CONFIG_UUID)
                ?: return failConnect("M32 BLE notification descriptor is missing.")
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(ccc)
            }
            if (!accepted) failConnect("Could not subscribe to M32 BLE notifications.")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CONFIG_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) connectResult?.complete(Unit)
            else failConnect("M32 BLE notification setup failed ($status).")
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NUS_TX_UUID) incoming.trySend(characteristic.value.copyOf())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == NUS_TX_UUID) incoming.trySend(value.copyOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val pending = writeResult ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(Unit)
            else pending.completeExceptionally(IllegalStateException("M32 BLE write failed ($status)."))
        }
    }

    private fun failConnect(message: String) {
        connectResult?.completeExceptionally(IllegalStateException(message))
        writeResult?.completeExceptionally(IllegalStateException(message))
    }

    companion object {
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
