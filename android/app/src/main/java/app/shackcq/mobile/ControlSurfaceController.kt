// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class SurfaceAction { GLOBAL_STOP, FREQUENCY_RELATIVE, FREQUENCY_ABSOLUTE, WORKSPACE_NEXT, PTT_HOLD, TUNE_HOLD }

interface ControlSurfaceActionPort {
    fun globalStop()
    fun frequencyDelta(steps: Int)
    fun absoluteFrequency(normalized: Float)
    fun nextWorkspace()
    fun pttHold(active: Boolean): Boolean = false
    fun tuneHold(active: Boolean): Boolean = false
}

data class ControlSurfaceSnapshot(
    val devices: List<String> = emptyList(),
    val state: String = "No MIDI/HID surface connected",
    val learnAction: SurfaceAction? = null,
    val lastControl: String? = null,
    val feedback: String = "LED/ring/motor feedback waits for a mapped capable device",
)

class ControlSurfaceController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val midi = appContext.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val devices = ConcurrentHashMap<Int, MidiDevice>()
    private val feedbackPorts = ConcurrentHashMap<Int, MidiInputPort>()
    private val preferences = appContext.getSharedPreferences("shackcq-control-surfaces-v1", Context.MODE_PRIVATE)
    private val mappings = ConcurrentHashMap<String, SurfaceAction>()
    @Volatile var actionPort: ControlSurfaceActionPort? = null
    @Volatile private var closed = false
    @Volatile private var physicallyAcceptedTransmit = false
    var snapshot by mutableStateOf(ControlSurfaceSnapshot())
        private set

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (count < 3 || closed) return
            val status = data[offset].toInt() and 0xf0
            val channel = data[offset].toInt() and 0x0f
            val control = data[offset + 1].toInt() and 0x7f
            val value = data[offset + 2].toInt() and 0x7f
            val key = "midi:$status:$channel:$control"
            dispatch(key, status, value)
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { open(info) }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            feedbackPorts.remove(info.id)?.close(); devices.remove(info.id)?.close(); refresh()
        }
    }

    init {
        restoreMappings()
        midi?.registerDeviceCallback(callback, handler)
        midi?.devices?.forEach(::open)
        refresh()
    }

    fun beginLearn(action: SurfaceAction) {
        snapshot = snapshot.copy(learnAction = action, state = "Move or press the control to bind ${action.name}")
    }

    fun cancelLearn() { snapshot = snapshot.copy(learnAction = null, state = "Learn cancelled") }

    fun unbind(control: String): Boolean {
        if (mappings[control] == SurfaceAction.GLOBAL_STOP) return false
        mappings.remove(control); persistMappings(); return true
    }

    fun recordPhysicalAcceptance(accepted: Boolean) {
        physicallyAcceptedTransmit = accepted
        if (!accepted) actionPort?.pttHold(false)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.device?.isVirtual != false) return false
        val key = "hid:key:${event.keyCode}"
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) dispatch(key, 0x90, 127)
        if (event.action == KeyEvent.ACTION_UP) dispatch(key, 0x80, 0)
        return mappings.containsKey(key)
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (device.isVirtual || event.action != MotionEvent.ACTION_SCROLL) return false
        val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt().coerceIn(-8, 8)
        if (delta == 0) return false
        val key = "hid:scroll:${device.id}"
        dispatch(key, 0xb0, if (delta > 0) 65 else 63)
        return mappings.containsKey(key)
    }

    private fun open(info: MidiDeviceInfo) {
        if (closed || devices.containsKey(info.id)) return
        midi?.openDevice(info, { device ->
            if (device == null || closed) return@openDevice
            devices[info.id] = device
            device.openOutputPort(0)?.connect(receiver)
            device.openInputPort(0)?.let { feedbackPorts[info.id] = it }
            refresh()
        }, handler)
    }

    private fun dispatch(key: String, status: Int, value: Int) {
        snapshot.learnAction?.let { learned ->
            if (mappings[key] == SurfaceAction.GLOBAL_STOP && learned != SurfaceAction.GLOBAL_STOP) {
                snapshot = snapshot.copy(learnAction = null, lastControl = key,
                    state = "Global Stop bindings cannot be replaced")
                return
            }
            mappings[key] = learned; persistMappings()
            snapshot = snapshot.copy(learnAction = null, lastControl = key, state = "Bound $key to ${learned.name}")
            return
        }
        val action = mappings[key] ?: return
        val port = actionPort ?: return
        when (action) {
            SurfaceAction.GLOBAL_STOP -> if (value > 0) port.globalStop()
            SurfaceAction.FREQUENCY_RELATIVE -> port.frequencyDelta(if (value in 1..63) value else value - 128)
            SurfaceAction.FREQUENCY_ABSOLUTE -> port.absoluteFrequency(value / 127f)
            SurfaceAction.WORKSPACE_NEXT -> if (value > 0) port.nextWorkspace()
            SurfaceAction.PTT_HOLD -> if (physicallyAcceptedTransmit) port.pttHold(status != 0x80 && value > 0)
            SurfaceAction.TUNE_HOLD -> if (physicallyAcceptedTransmit) port.tuneHold(status != 0x80 && value > 0)
        }
        snapshot = snapshot.copy(lastControl = key, state = "${action.name} routed through the typed authority")
        sendFeedback(status, value)
    }

    private fun sendFeedback(status: Int, value: Int) {
        val message = byteArrayOf((status or 0x00).toByte(), 0, value.coerceIn(0, 127).toByte())
        feedbackPorts.values.forEach { runCatching { it.send(message, 0, message.size) } }
        snapshot = snapshot.copy(feedback = "Feedback sent to ${feedbackPorts.size} capable surface(s)")
    }

    private fun refresh() {
        val names = buildList {
            midi?.devices?.forEach { add(it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI ${it.id}") }
            InputDevice.getDeviceIds().toList().mapNotNull { id: Int -> InputDevice.getDevice(id) }.filter { !it.isVirtual &&
                (it.sources and (InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE)) != 0 }
                .forEach { add(it.name) }
        }.distinct().sorted()
        snapshot = snapshot.copy(devices = names, state = if (names.isEmpty()) "No MIDI/HID surface connected" else "${names.size} control surface(s) available")
    }

    private fun restoreMappings() {
        val objectValue = runCatching { JSONObject(preferences.getString("mappings", "{}") ?: "{}") }.getOrDefault(JSONObject())
        objectValue.keys().forEach { key -> runCatching { SurfaceAction.valueOf(objectValue.getString(key)) }.getOrNull()?.let { mappings[key] = it } }
        if (mappings.values.none { it == SurfaceAction.GLOBAL_STOP }) mappings["hid:key:${KeyEvent.KEYCODE_ESCAPE}"] = SurfaceAction.GLOBAL_STOP
    }

    private fun persistMappings() {
        val value = JSONObject(); mappings.toSortedMap().forEach { (key, action) -> value.put(key, action.name) }
        preferences.edit().putString("mappings", value.toString()).apply()
    }

    override fun close() {
        closed = true; midi?.unregisterDeviceCallback(callback)
        feedbackPorts.values.forEach { runCatching { it.close() } }; feedbackPorts.clear()
        devices.values.forEach { runCatching { it.close() } }; devices.clear()
    }
}

@Composable
fun ControlSurfaceSettingsPanel(controller: ControlSurfaceController) {
    val state = controller.snapshot
    Column(Modifier.fillMaxWidth()) {
        Text(state.state)
        state.devices.forEach { Text("• $it", Modifier.padding(top = 4.dp)) }
        Text(state.lastControl?.let { "Last control: $it" } ?: "No control received this session", Modifier.padding(top = 8.dp))
        Text("PTT/TUNE mappings remain blocked until physical acceptance. Global Stop cannot be unbound.", Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp)) {
            Button({ controller.beginLearn(SurfaceAction.FREQUENCY_RELATIVE) }, Modifier.heightIn(min = 48.dp)) { Text("LEARN TUNING") }
            Button({ controller.beginLearn(SurfaceAction.GLOBAL_STOP) }, Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) { Text("LEARN STOP") }
        }
        Text(state.feedback, Modifier.padding(top = 8.dp))
    }
}
