// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider

private val LocalPanel = Color(0xFF1B2228)
private val LocalRaised = Color(0xFF283139)
private val LocalInk = Color(0xFFF4F0E7)
private val LocalMuted = Color(0xFFA5ADB2)
private val LocalAmber = Color(0xFFE9A72B)
private val LocalHold = Color(0xFFF4C94E)
private val LocalHealthy = Color(0xFF42C77B)
private val LocalDanger = Color(0xFFE4544D)

@Composable
fun LocalReceiverRail(
    controller: LocalReceiverController,
    sourceId: String,
    sourceReceiver: Int,
    centerHz: Long,
    sampleRate: Int,
    modifier: Modifier = Modifier,
) {
    val snapshot = controller.snapshot
    Column(modifier.fillMaxWidth().background(LocalPanel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("LOCAL RECEIVERS · RECEIVE ONLY", color = LocalAmber, fontWeight = FontWeight.Black)
                Text("${snapshot.receivers.size}/2 · ${snapshot.source} · ${snapshot.inputRate / 1000} kHz → 48 kHz", color = LocalMuted, fontSize = 10.sp)
            }
            Button({ controller.add(sourceId, sourceReceiver, centerHz, sampleRate) },
                enabled = snapshot.receivers.size < 2 && centerHz > 0 && sampleRate in 48_000..384_000,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("ADD LOCAL RX") }
        }
        if (snapshot.receivers.isEmpty()) Text("No local audio restores automatically. Add a bounded virtual receiver inside the current I/Q span.", color = LocalMuted)
        snapshot.receivers.forEach { LocalReceiverCard(controller, it) }
        Text("${snapshot.status} · queue ${snapshot.queueDepth}/8 · drops ${snapshot.droppedBlocks}" +
            if (snapshot.recordingState == "RECORDING") " · RECORDING ${snapshot.recordingBytes / 1024} KiB" else "",
            color = if (snapshot.recordingState == "RECORDING") LocalDanger else LocalMuted, fontSize = 10.sp)
    }
}

@Composable
private fun LocalReceiverCard(controller: LocalReceiverController, state: LocalReceiverState) {
    var note by remember(state.id) { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = LocalRaised)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("${state.label} · ${formatRadioFrequency(state.frequencyHz)} · ${state.preferences.mode}", color = LocalInk,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("${state.sourceId} RX ${state.sourceReceiver + 1} · ${state.sourceSampleRate / 1000} kHz · ${"%.1f".format(state.signalDb)} dBFS",
                        color = LocalMuted, fontSize = 10.sp)
                }
                TextButton({ controller.remove(state.id) }) { Text("REMOVE", color = LocalDanger) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LocalReceiverMode.entries.filter { it.supported(state.sourceSampleRate) }.forEach { mode ->
                    FilterChip(state.preferences.mode == mode, { controller.setMode(state.id, mode) }, { Text(mode.name) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(state.listening, { controller.listen(state.id, !state.listening) }, { Text(if (state.listening) "LISTENING" else "LISTEN") })
                FilterChip(state.muted, { controller.update(state.id) { it.copy(muted = !it.muted) } }, { Text("MUTE") })
                FilterChip(state.solo, { controller.update(state.id) { it.copy(solo = !it.solo) } }, { Text("SOLO") })
                Button({ if (state.recording) controller.stopRecording() else controller.startRecording(state.id, note) },
                    enabled = state.recording || state.listening) { Text(if (state.recording) "STOP RECORDING" else "RECORD") }
            }
            if (state.preferences.mode == LocalReceiverMode.CW) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(100, 200, 300, 500, 800).forEach { width -> FilterChip(
                    state.preferences.filterHighHz - state.preferences.filterLowHz == width,
                    { controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(
                        filterLowHz = (current.preferences.cwPitchHz - width / 2).coerceAtLeast(0),
                        filterHighHz = current.preferences.cwPitchHz + width / 2)) } }, { Text("CW $width Hz") }) }
            }
            if (state.preferences.mode == LocalReceiverMode.NFM) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(12_500, 20_000, 25_000).forEach { width -> FilterChip(state.preferences.filterHighHz == width,
                    { controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(filterLowHz = 0, filterHighHz = width)) } },
                    { Text("${width / 1_000.0} kHz") }) }
            }
            if (state.preferences.mode in setOf(LocalReceiverMode.NFM, LocalReceiverMode.WFM)) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(50, 75).forEach { value -> FilterChip(state.preferences.fmDeemphasisUs == value,
                    { controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(fmDeemphasisUs = value)) } },
                    { Text("$value µs") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(state.preferences.agc, { controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(agc = !current.preferences.agc)) } }, { Text("AGC") })
                FilterChip(state.preferences.noiseBlanker, { controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(noiseBlanker = !current.preferences.noiseBlanker)) } }, { Text("NB") })
                FilterChip(state.preferences.automaticNotch, { controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(automaticNotch = !current.preferences.automaticNotch)) } }, { Text("AUTO NOTCH") })
            }
            Text("LOCAL NCO ${if (state.relativeOffsetHz >= 0) "+" else ""}${state.relativeOffsetHz} Hz · drag/slide remains inside current I/Q span",
                color = LocalMuted, fontSize = 10.sp)
            Slider(state.relativeOffsetHz.toFloat(), { controller.move(state.id, it.toInt()) },
                valueRange = (-state.sourceSampleRate * .46f)..(state.sourceSampleRate * .46f), enabled = state.sourceSampleRate > 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("FILTER LOW ${state.preferences.filterLowHz} Hz", color = LocalMuted, fontSize = 9.sp)
                    Slider(state.preferences.filterLowHz.toFloat(), { value -> controller.update(state.id) { current ->
                        current.copy(preferences = current.preferences.copy(filterLowHz = value.toInt())) } }, valueRange = 0f..3_000f)
                }
                Column(Modifier.weight(1f)) {
                    Text("FILTER HIGH ${state.preferences.filterHighHz} Hz", color = LocalMuted, fontSize = 9.sp)
                    Slider(state.preferences.filterHighHz.toFloat(), { value -> controller.update(state.id) { current ->
                        current.copy(preferences = current.preferences.copy(filterHighHz = value.toInt())) } },
                        valueRange = (state.preferences.filterLowHz + 50).toFloat()..if (state.preferences.mode == LocalReceiverMode.WFM) 95_000f else 25_000f)
                }
            }
            Text("SQUELCH ${state.preferences.squelchDb.toInt()} dB · GAIN ${"%.2f".format(state.preferences.gain)} · PAN ${"%+.2f".format(state.preferences.pan)}", color = LocalMuted, fontSize = 9.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(state.preferences.squelchDb, { value -> controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(squelchDb = value)) } },
                    valueRange = -120f..-20f, modifier = Modifier.weight(1f))
                Slider(state.preferences.gain, { value -> controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(gain = value)) } },
                    valueRange = 0f..2f, modifier = Modifier.weight(1f))
                Slider(state.preferences.pan, { value -> controller.update(state.id) { current -> current.copy(preferences = current.preferences.copy(pan = value)) } },
                    valueRange = -1f..1f, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${if (state.preferences.mode == LocalReceiverMode.SAM) "SAM ${state.samState} · ${"%+.1f".format(state.samErrorHz)} Hz" else state.toneState}",
                    color = if (state.samState == LocalSamState.LOCKED || state.ctcssHz > 0 || state.dcsCode > 0) LocalHealthy else LocalHold,
                    modifier = Modifier.weight(1f))
                Text(if (state.preferences.mode == LocalReceiverMode.WFM) "PILOT ${"%.0f".format(state.wfmPilot * 100)}% · ${"%.1f".format(state.stereoSeparationDb)} dB · ${state.rdsState}" else "DSP ${"%.2f".format(state.processingMillis)} ms",
                    color = LocalMuted, modifier = Modifier.weight(1f))
            }
            if (state.rdsPs.isNotBlank() || state.rdsText.isNotBlank()) Text("RDS · ${state.rdsPs} · PTY ${state.rdsPty} · " +
                "TP ${if (state.rdsTp) "ON" else "OFF"} · TA ${if (state.rdsTa) "ON" else "OFF"} · ${state.rdsText.take(64)}" +
                (if (state.rdsAfKhz.isNotEmpty()) " · AF ${state.rdsAfKhz.joinToString()} kHz" else "") +
                (if (state.rdsClock.isNotBlank()) " · ${state.rdsClock}" else ""), color = LocalHealthy, fontSize = 10.sp)
            OutlinedTextField(note, { note = it.take(240) }, label = { Text("Recording note · stored only with explicit recording") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (state.lastError.isNotBlank()) Text(state.lastError, color = LocalDanger, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LocalReceiverTapActions(controller: LocalReceiverController, sourceId: String, sourceReceiver: Int, centerHz: Long,
    sampleRate: Int, selectedFrequencyHz: Long?) {
    val frequency = selectedFrequencyHz ?: return
    val offset = (frequency - centerHz).toInt()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LISTEN ${formatRadioFrequency(frequency)}", color = LocalAmber, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 13.dp))
        listOf("local:A" to "RX A", "local:B" to "RX B").forEach { (id, label) ->
            val existing = controller.snapshot.receivers.firstOrNull { it.id == id }
            OutlinedButton({
                if (existing == null) controller.add(sourceId, sourceReceiver, centerHz, sampleRate)
                controller.move(id, offset)
            }, enabled = kotlin.math.abs(offset) <= sampleRate * .46f && (existing != null || controller.snapshot.receivers.size < 2)) {
                Text(if (existing == null) "ADD $label" else "MOVE $label")
            }
        }
        if (kotlin.math.abs(offset) > sampleRate * .46f) Text("OUTSIDE CURRENT I/Q SPAN · PHYSICAL RECEIVE REVIEW", color = LocalDanger)
    }
}

@Composable
fun LocalReceiverSettingsPanel(controller: LocalReceiverController) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("PANADAPTER → LOCAL RECEIVERS", color = LocalAmber, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 5, 15, 30, 60, 120).forEach { seconds -> FilterChip(controller.preRollSeconds == seconds,
                { controller.updateRecordingPolicy(seconds, controller.maximumRecordingBytes) }, { Text("PRE-ROLL ${seconds}s") }) }
            listOf(64L, 128L, 250L).forEach { mb -> FilterChip(controller.maximumRecordingBytes == mb * 1024 * 1024,
                { controller.updateRecordingPolicy(controller.preRollSeconds, mb * 1024 * 1024) }, { Text("CAP $mb MB") }) }
        }
        Text("Safe per-mode defaults restore. Listening, recording, Scanner recording, SAM acquisition, RDS sessions and time-shift playback never restore.", color = LocalMuted)
        if (controller.recordings.isNotEmpty()) controller.recordings.take(8).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${row.mode} · ${formatRadioFrequency(row.frequencyHz)} · ${row.bytes / 1024} KiB", color = LocalInk,
                    modifier = Modifier.widthIn(max = 480.dp))
                TextButton({ controller.recordingFile(row.id)?.let { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share receive recording"))
                } }) { Text("SHARE", color = LocalAmber) }
                TextButton({ controller.deleteRecording(row.id) }) { Text("DELETE", color = LocalDanger) }
            }
        }
    }
}

@Composable
fun LocalReceiverHealthPanel(controller: LocalReceiverController) {
    val snapshot = controller.snapshot
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("LOCAL SDR HEALTH · ${snapshot.receivers.size}/2 ACTIVE · ${snapshot.source}", color = LocalAmber, fontWeight = FontWeight.Bold)
        Text("${snapshot.inputRate} → ${snapshot.outputRate} Hz · queue ${snapshot.queueDepth}/8 · dropped ${snapshot.droppedBlocks} · recording ${snapshot.recordingState} ${snapshot.recordingBytes} bytes",
            color = LocalMuted, fontFamily = FontFamily.Monospace)
        snapshot.receivers.forEach { state -> Text("${state.label} ${state.preferences.mode} ${state.relativeOffsetHz} Hz · filter ${state.preferences.filterLowHz}…${state.preferences.filterHighHz} · " +
            "DSP ${"%.2f".format(state.processingMillis)} ms · source age ${state.sourceAgeMillis} ms · ${state.samState} · ${state.toneState} · " +
            "pilot ${"%.0f".format(state.wfmPilot * 100)}% · RDS err ${"%.1f".format(state.rdsErrorRate * 100)}%",
            color = if (state.lastError.isBlank()) LocalHealthy else LocalDanger, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        Text("Support metrics exclude recorded audio, raw I/Q, RDS RadioText, operator notes and raw station metadata.", color = LocalMuted, fontSize = 10.sp)
    }
}
