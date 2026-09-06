package app.shackcq.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class AudioRouteDescriptor(
    val sessionId: Int,
    val stableKey: String,
    val name: String,
    val type: Int,
    val address: String,
    val isSource: Boolean,
    val isSink: Boolean,
    val channelCounts: List<Int>,
    val sampleRates: List<Int>,
    val encodings: List<Int> = emptyList(),
    val usbIdentity: String = "",
) {
    val label: String get() = listOf(name, if (isSource) "input" else "output",
        "${channelCounts.joinToString("/").ifBlank { "?" }} ch", usbIdentity.takeIf(String::isNotBlank),
        address.takeIf(String::isNotBlank)).filterNotNull().joinToString(" · ")
}

/** Receive-only USB audio monitor plus explicit RX/TX USB route ownership. */
class AudioMonitorController(private val context: Context) {
    private data class AudioFrame(val samples: ShortArray, val count: Int)

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val prefs = context.getSharedPreferences("shackcq-audio-routes", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycle = LifecycleGeneration()
    private val frames = ArrayBlockingQueue<AudioFrame>(8)
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var sessionRxId: Int? = null
    private var sessionTxId: Int? = null
    private var restoreMonitorAfterLease = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
    }

    var enabled by mutableStateOf(false); private set
    var status by mutableStateOf("Audio monitor stopped"); private set
    var inputName by mutableStateOf("No USB audio input selected"); private set
    var outputName by mutableStateOf("Tablet speaker not selected"); private set
    var txOutputName by mutableStateOf("No voice TX USB output selected"); private set
    var routeStatus by mutableStateOf("Audio routes not scanned"); private set
    var inputCandidates by mutableStateOf(emptyList<AudioRouteDescriptor>()); private set
    var txOutputCandidates by mutableStateOf(emptyList<AudioRouteDescriptor>()); private set
    var selectedRx by mutableStateOf<AudioRouteDescriptor?>(null); private set
    var selectedTx by mutableStateOf<AudioRouteDescriptor?>(null); private set
    var level by mutableFloatStateOf(0f); private set
    var gain by mutableFloatStateOf(1f); private set
    var onTxRouteInvalidated: (() -> Unit)? = null
    var audioOwner by mutableStateOf(AudioOwners.NONE); private set

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        refreshDevices()
    }

    fun updateGain(value: Float) { gain = value.coerceIn(0f, 12f) }

    @Synchronized fun acquireAudio(owner: String, pauseMonitor: Boolean): Boolean {
        val decision = decideAudioLease(audioOwner, owner, enabled, pauseMonitor)
        if (!decision.accepted) return false
        restoreMonitorAfterLease = decision.pauseMonitor
        if (decision.pauseMonitor) stop()
        audioOwner = owner
        return true
    }

    @Synchronized fun releaseAudio(owner: String) {
        if (audioOwner != owner) return
        audioOwner = AudioOwners.NONE
        val restore = restoreMonitorAfterLease
        restoreMonitorAfterLease = false
        if (restore) start()
    }

    fun refreshDevices() {
        inputCandidates = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter(::isUsb).map(::descriptor)
        txOutputCandidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isUsb).map(::descriptor)
        val rxPolicy = sessionRxId?.let { id -> inputCandidates.firstOrNull { it.sessionId == id }?.let { StableSelection(selected = it) } }
            ?: chooseStableCandidate(inputCandidates, prefs.getString("rx_input", null), AudioRouteDescriptor::stableKey)
        val txPolicy = sessionTxId?.let { id -> txOutputCandidates.firstOrNull { it.sessionId == id }?.let { StableSelection(selected = it) } }
            ?: chooseStableCandidate(txOutputCandidates, prefs.getString("voice_tx_output", null), AudioRouteDescriptor::stableKey)
        selectedRx = rxPolicy.selected
        selectedTx = txPolicy.selected
        selectedRx?.let { prefs.edit().putString("rx_input", it.stableKey).apply() }
        selectedTx?.let { prefs.edit().putString("voice_tx_output", it.stableKey).apply() }
        inputName = selectedRx?.name ?: if (rxPolicy.selectionRequired) "Selection required" else "No USB audio input detected"
        txOutputName = selectedTx?.name ?: if (txPolicy.selectionRequired) "Selection required" else "No USB audio output detected"
        outputName = speakerDevice()?.productName?.toString()?.ifBlank { "Built-in speaker" } ?: "No built-in speaker detected"
        routeStatus = listOfNotNull(
            rxPolicy.reason.takeIf(String::isNotBlank)?.let { "RX: $it" },
            txPolicy.reason.takeIf(String::isNotBlank)?.let { "TX: $it" },
        ).joinToString(" · ").ifBlank { "Selected audio routes are present" }
    }

    fun selectRxInput(sessionId: Int) {
        stop(); sessionRxId = sessionId
        val route = inputCandidates.firstOrNull { it.sessionId == sessionId }
        if (route == null) prefs.edit().remove("rx_input").apply() else prefs.edit().putString("rx_input", route.stableKey).apply()
        refreshDevices()
    }

    fun selectTxOutput(sessionId: Int) {
        sessionTxId = sessionId
        val route = txOutputCandidates.firstOrNull { it.sessionId == sessionId }
        if (route == null) prefs.edit().remove("voice_tx_output").apply() else prefs.edit().putString("voice_tx_output", route.stableKey).apply()
        refreshDevices(); onTxRouteInvalidated?.invoke()
    }

    fun selectedRxDevice(): AudioDeviceInfo? = selectedRx?.let { selected ->
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).singleOrNull { descriptor(it).stableKey == selected.stableKey }
    }

    fun selectedTxDevice(): AudioDeviceInfo? = selectedTx?.let { selected ->
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).singleOrNull { descriptor(it).stableKey == selected.stableKey }
    }

    fun speakerDevice(): AudioDeviceInfo? = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).singleOrNull {
        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    fun builtInMicDevice(): AudioDeviceInfo? = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).singleOrNull {
        it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
    }

    @Synchronized fun start() {
        if (closed.get()) return
        if (running.get()) return
        if (!canStartAudioMonitor(audioOwner)) { status = "$audioOwner owns the selected audio device"; return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is required for USB audio monitoring"; return
        }
        stop()
        val input = selectedRxDevice()
        val output = speakerDevice()
        refreshDevices()
        if (input == null) { status = "Select one unambiguous USB RX input"; return }
        if (output == null) { status = "No tablet speaker output detected"; return }
        val rate = listOf(48_000, 44_100).firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0 &&
                AudioTrack.getMinBufferSize(it, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: run { status = "No compatible audio sample rate"; return }
        val recordMinimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val playMinimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frameSamples = rate / 50
        val record = runCatching {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(recordMinimum * 4, frameSamples * 8)).build()
        }.getOrElse { status = "USB audio input could not initialize: ${it.message}"; return }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val track = runCatching {
            AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(playMinimum * 4, frameSamples * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
        }.getOrElse { record.release(); status = "Tablet speaker could not initialize: ${it.message}"; return }
        if (!record.setPreferredDevice(input) || !track.setPreferredDevice(output)) {
            record.release(); track.release(); status = "Android refused the selected monitor route"; return
        }
        val generation = lifecycle.next()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) main.post {
                    if (lifecycle.isCurrent(generation)) stop()
                }
            }.build()
        audioManager.requestAudioFocus(focus); focusRequest = focus
        automaticGainControl = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true } else null
        recorder = record; player = track; frames.clear(); audioOwner = AudioOwners.MONITOR
        record.startRecording(); track.setVolume(1f); track.play()
        running.set(true); enabled = true; status = "Starting selected USB audio monitor · ${rate / 1000} kHz"
        captureThread = Thread({ captureLoop(record, input, frameSamples, rate, generation) }, "ShackCQ-USB-Capture").apply { start() }
        playbackThread = Thread({ playbackLoop(track, output, generation) }, "ShackCQ-Speaker-Playback").apply { start() }
    }

    @Synchronized fun stop() {
        lifecycle.retire()
        running.set(false); enabled = false
        captureThread?.interrupt(); playbackThread?.interrupt()
        recorder?.let { runCatching { it.stop() }; it.release() }
        player?.let { runCatching { it.pause() }; runCatching { it.flush() }; it.release() }
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        automaticGainControl?.release()
        recorder = null; player = null; captureThread = null; playbackThread = null
        focusRequest = null; automaticGainControl = null
        frames.clear(); level = 0f; status = "Audio monitor stopped"
        if (audioOwner == AudioOwners.MONITOR) audioOwner = AudioOwners.NONE
    }

    @Synchronized fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        lifecycle.close()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun captureLoop(
        record: AudioRecord,
        requestedInput: AudioDeviceInfo,
        frameSamples: Int,
        rate: Int,
        generation: Long,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var routeTicks = 0
        while (running.get() && recorder === record) {
            val samples = ShortArray(frameSamples)
            val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            if (count <= 0) { fail(generation, "USB audio read failed ($count)"); return }
            var energy = 0.0
            val multiplier = gain
            for (index in 0 until count) {
                val value = (samples[index] * multiplier).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                samples[index] = value.toShort(); energy += value.toDouble() * value
            }
            val rms = (sqrt(energy / count) / Short.MAX_VALUE).toFloat()
            main.post {
                if (lifecycle.isCurrent(generation)) level = (0.8f * level + 0.2f * (rms * 4f)).coerceIn(0f, 1f)
            }
            if (!frames.offer(AudioFrame(samples, count))) { frames.poll(); frames.offer(AudioFrame(samples, count)) }
            if (++routeTicks >= 50) {
                routeTicks = 0
                if (record.routedDevice?.id != requestedInput.id) {
                    fail(generation, "Selected USB RX route was lost; monitor stopped")
                    return
                }
                main.post {
                    if (lifecycle.isCurrent(generation)) {
                        status = "Monitoring selected USB input through tablet speaker · ${rate / 1000} kHz"
                    }
                }
            }
        }
    }

    private fun playbackLoop(track: AudioTrack, requestedOutput: AudioDeviceInfo, generation: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (running.get() && player === track) {
            val frame = try { frames.poll(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return }
            if (frame == null) continue
            var written = 0
            while (written < frame.count && running.get()) {
                val result = track.write(frame.samples, written, frame.count - written, AudioTrack.WRITE_BLOCKING)
                if (result <= 0) { fail(generation, "Tablet speaker write failed ($result)"); return }
                written += result
            }
            if (track.routedDevice?.id != requestedOutput.id) {
                fail(generation, "Tablet speaker route was lost; monitor stopped")
                return
            }
        }
    }

    private fun devicesChanged() {
        if (closed.get()) return
        val previousTx = selectedTx?.stableKey
        refreshDevices()
        if (enabled && selectedRxDevice() == null) stop()
        if (previousTx != null && selectedTxDevice() == null) onTxRouteInvalidated?.invoke()
    }

    private fun fail(generation: Long, value: String) = main.post {
        if (lifecycle.isCurrent(generation)) {
            stop()
            status = value
        }
    }

    private fun descriptor(device: AudioDeviceInfo): AudioRouteDescriptor {
        val address = if (Build.VERSION.SDK_INT >= 28) device.address.orEmpty() else ""
        val name = device.productName?.toString()?.ifBlank { "USB audio" } ?: "USB audio"
        val usbMatches = usbManager.deviceList.values.filter { usb ->
            usb.productName?.equals(name, ignoreCase = true) == true ||
                (address.isNotBlank() && usb.deviceName.contains(address))
        }
        val usbIdentity = usbMatches.singleOrNull()?.let { usb ->
            val serialHash = runCatching { usb.serialNumber }.getOrNull()?.hashCode()?.toUInt()?.toString(16).orEmpty()
            "%04x:%04x%s".format(usb.vendorId, usb.productId, serialHash.takeIf(String::isNotBlank)?.let { ":$it" }.orEmpty())
        }.orEmpty()
        val stable = listOf(device.type.toString(), address, name, usbIdentity, device.isSource.toString(), device.isSink.toString(),
            device.channelCounts.sorted().joinToString(","), device.sampleRates.sorted().joinToString(","),
            device.encodings.sorted().joinToString(",")).joinToString("|")
        return AudioRouteDescriptor(device.id, stable, name, device.type, address, device.isSource, device.isSink,
            device.channelCounts.toList(), device.sampleRates.toList(), device.encodings.toList(), usbIdentity)
    }

    private fun isUsb(device: AudioDeviceInfo): Boolean = device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        device.type == AudioDeviceInfo.TYPE_USB_HEADSET || device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
}
