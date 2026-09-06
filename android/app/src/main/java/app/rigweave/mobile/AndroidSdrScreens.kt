// SPDX-License-Identifier: GPL-3.0-only
package app.rigweave.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val SdrChassis = Color(0xFF111519)
private val SdrPanel = Color(0xFF1B2228)
private val SdrRaised = Color(0xFF283139)
private val SdrInk = Color(0xFFF4F0E7)
private val SdrMuted = Color(0xFFA5ADB2)
private val SdrAmber = Color(0xFFE9A72B)
private val SdrHold = Color(0xFFF4C94E)
private val SdrHealthy = Color(0xFF42C77B)
private val SdrDanger = Color(0xFFE4544D)
private val MapGridForRf = Color(0xFF35515B).copy(alpha = .58f)

private enum class TciCockpitPage { RECEIVERS, TRANSMIT, PANADAPTER, SCANNER, CALIBRATION }

@Composable
fun TciRadioCockpit(
    runtime: TciRuntimeState,
    platform: RadioRuntimeSnapshot,
    panadapter: PanadapterController,
    rxAudio: TciRxAudioController,
    scanner: ReceiveOnlyScannerController,
    operational: SdrOperationalV2,
    workbench: AndroidSdrWorkbenchV4,
    localReceivers: LocalReceiverController,
    transmit: TciTransmitAuthority,
    memories: List<RadioPreset>,
    dispatch: (RadioPlatformAction) -> Unit,
    connect: () -> Unit,
    disconnect: () -> Unit,
    debugLab: DebugSdrLab?,
    openDigi: () -> Unit,
    requestRxRecheck: () -> Unit,
    requestTune: () -> Unit,
) {
    val state = runtime.snapshot
    var page by remember { mutableStateOf(TciCockpitPage.RECEIVERS) }
    Column(Modifier.fillMaxSize().background(SdrChassis).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("TCI RADIO · TRANSMIT CONTROL V5", color = SdrAmber, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("${state.device} · ${state.protocol} ${state.protocolVersion} · ${state.state}", color = if (state.ready) SdrHealthy else SdrHold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.ready) Button(connect, modifier = Modifier.heightIn(min = 48.dp)) { Text("CONNECT") }
                else OutlinedButton(disconnect, modifier = Modifier.heightIn(min = 48.dp)) { Text("DISCONNECT") }
                if (BuildConfig.DEBUG && debugLab != null) OutlinedButton({ if (debugLab.active) debugLab.stop() else debugLab.start() }) {
                    Text(if (debugLab.active) "STOP DEMO" else "DEMO · NO RADIO")
                }
            }
        }
        if (BuildConfig.DEBUG && debugLab?.active == true) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("DEMO · NO RADIO", color = SdrDanger, modifier = Modifier.padding(top = 12.dp))
            DebugLocalFixture.entries.forEach { fixture -> FilterChip(debugLab.localFixture == fixture,
                { debugLab.selectLocalFixture(fixture) }, { Text(fixture.name.replace('_', ' ')) }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TciCockpitPage.entries.forEach { item -> FilterChip(page == item, { page = item }, { Text(item.name) }) }
            SdrTruthChip("RX ${state.receivers.size}/${state.declaredReceiverCount}", state.receivers.isNotEmpty())
            SdrTruthChip("IQ DROP ${state.droppedFrames}", state.droppedFrames == 0L)
            SdrTruthChip(transmit.snapshot.acceptance.name.replace('_', ' '), transmit.snapshot.acceptance.permits(TciAcceptanceState.PTT_ACCEPTED))
            SdrTruthChip("TX ${transmit.snapshot.state}", transmit.snapshot.state == TciTxMachineState.RX_IDLE)
        }
        when (page) {
            TciCockpitPage.RECEIVERS -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalReceiverRail(localReceivers, if (debugLab?.active == true) "DEBUG FIXTURE" else "TCI",
                    state.receivers.firstOrNull()?.backendIndex ?: 0, state.receivers.firstOrNull()?.effectiveRxHz ?: 0,
                    state.receivers.firstOrNull()?.sampleRate ?: 0)
                ReceiverCockpit(state, rxAudio, operational, dispatch)
            }
            TciCockpitPage.TRANSMIT -> TciTransmitPanel(transmit.snapshot, requestRxRecheck, requestTune,
                { transmit.globalStop("TCI_UI_STOP") }, openDigi, debugLab)
            TciCockpitPage.PANADAPTER -> TciPanadapterPanel(state, panadapter, dispatch, operational = operational, workbench = workbench,
                localReceivers = localReceivers, openDigi = openDigi)
            TciCockpitPage.SCANNER -> ScannerPanel(scanner, memories, panadapter.frame, state, dispatch, operational)
            TciCockpitPage.CALIBRATION -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SdrWorkbenchCalibrationPanel(workbench, state)
                TxAudioCalibrationPanel(operational, state)
            }
        }
        if (!state.ready && debugLab?.active != true) SdrEmptyState(
            "TCI data unavailable",
            "Configure a TCI profile in Settings, then explicitly Connect. TX stays locked until exact-device physical acceptance.")
        if (platform.lastSanitizedError != null || state.lastError != null) {
            Text("STATUS · ${state.lastError ?: platform.lastSanitizedError}", color = SdrDanger)
        }
    }
}

@Composable private fun TciTransmitPanel(snapshot: TciTxSnapshot, requestRxRecheck: () -> Unit,
    requestTune: () -> Unit, stop: () -> Unit, openDigi: () -> Unit, debugLab: DebugSdrLab?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("TCI TX AUTHORITY", color = SdrAmber, fontWeight = FontWeight.Black)
        Text(if (snapshot.demoNoRadio) "DEMO · NO RADIO" else "LIVE PROFILE · PHYSICAL ACCEPTANCE APPLIES",
            color = if (snapshot.demoNoRadio) SdrDanger else SdrHold, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SdrHealthCard("STATE", snapshot.state.name, snapshot.state == TciTxMachineState.RX_IDLE, Modifier.weight(1f))
            SdrHealthCard("ACCEPTANCE", snapshot.acceptance.name.replace('_', ' '),
                snapshot.acceptance.permits(TciAcceptanceState.PTT_ACCEPTED), Modifier.weight(1f))
            SdrHealthCard("READBACK", snapshot.readback.name, snapshot.readback == TciReadbackTruth.CONFIRMED, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SdrHealthCard("TX", "${snapshot.txFrequencyHz?.let { "${it / 1_000} kHz" } ?: "UNKNOWN"} · ${snapshot.mode ?: "UNKNOWN"} · ${snapshot.txVfo ?: "VFO UNKNOWN"}",
                snapshot.txFrequencyHz != null, Modifier.weight(1f))
            SdrHealthCard("POWER", "FWD ${snapshot.forwardPowerWatts?.let { "%.1f W".format(it) } ?: "UNKNOWN"} · REFL ${snapshot.reflectedPowerWatts?.let { "%.1f W".format(it) } ?: "UNAVAILABLE"}",
                snapshot.swr?.let { it < 3.0 } != false, Modifier.weight(1f))
            SdrHealthCard("SWR / ALC", "${snapshot.swr?.let { "%.2f".format(it) } ?: "UNKNOWN"} / ${snapshot.alc?.let { "%.2f".format(it) } ?: "UNAVAILABLE_PROTOCOL"}",
                snapshot.interlock == null, Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TX AUDIO · LOCAL, NOT RF", color = SdrAmber, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { (snapshot.peak ?: 0.0).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text("RMS ${snapshot.rms?.let { "%.3f".format(it) } ?: "—"} · PEAK ${snapshot.peak?.let { "%.3f".format(it) } ?: "—"} · CLIP ${snapshot.clippedSamples} · ${snapshot.txAudioRate ?: 0} Hz",
                    color = SdrMuted, fontFamily = FontFamily.Monospace)
                Text("frames ${snapshot.frames} · queue ${snapshot.queueDepth} · under/over ${snapshot.underruns}/${snapshot.overruns} · jitter ${snapshot.frameJitterMillis?.let { "%.2f ms".format(it) } ?: "—"}", color = SdrMuted)
            }
        }
        Text("INTERLOCK · ${snapshot.interlock ?: "CLEAR"} · elapsed ${snapshot.elapsedMillis} ms · watchdog ${snapshot.watchdogMillis ?: 0} ms", color = if (snapshot.interlock == null) SdrHealthy else SdrDanger)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(stop) { Text("GLOBAL STOP") }
            OutlinedButton(requestRxRecheck, enabled = snapshot.state == TciTxMachineState.RX_UNCONFIRMED) { Text("REQUEST RX & RECHECK") }
            OutlinedButton(requestTune, enabled = snapshot.acceptance.permits(TciAcceptanceState.TUNE_ACCEPTED) && snapshot.state == TciTxMachineState.RX_IDLE) { Text("BOUNDED TUNE") }
            OutlinedButton(openDigi) { Text("OPEN DIGI") }
        }
        Text("Split ${snapshot.split ?: "UNKNOWN"} · XIT ${snapshot.xitOffsetHz?.let { "$it Hz" } ?: "UNKNOWN"} · TX filter ${snapshot.txFilterHz?.let { "$it Hz" } ?: "UNAVAILABLE_PROTOCOL"}. Split changes are blocked during TX by default.", color = SdrMuted)
        if (BuildConfig.DEBUG && debugLab?.active == true) {
            Text("DEBUG LAB V5 · DEMO · NO RADIO", color = SdrDanger, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DebugTciTxScenario.entries.forEach { scenario -> FilterChip(debugLab.txScenario == scenario,
                    { debugLab.selectTxScenario(scenario) }, { Text(scenario.name.replace('_', ' ')) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(debugLab::fakeTransmit) { Text("FAKE PTT + TX AUDIO") }
                OutlinedButton(debugLab::fakeTune) { Text("FAKE TUNE") }
                Text("DEMO · NO RADIO", color = SdrDanger, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun ReceiverCockpit(state: TciRuntimeSnapshot, rxAudio: TciRxAudioController, operational: SdrOperationalV2,
    dispatch: (RadioPlatformAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        val content: @Composable (TciReceiverSnapshot) -> Unit = { receiver ->
            ReceiverInstrument(receiver, {
                dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "select_receiver", longValue = receiver.backendIndex.toLong()))
            }, {
                dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "listen_receiver", longValue = receiver.backendIndex.toLong()))
            }, { action ->
                val dualAudio = rxAudio.mixer.mode in setOf(RxMixerMode.STEREO_SPLIT, RxMixerMode.MIX)
                val targets = if (action.startsWith("audio_") && dualAudio) state.receivers.take(2) else listOf(receiver)
                val outputRate = targets.maxOfOrNull { it.rxAudioSampleRate.coerceAtLeast(48_000) } ?: 48_000
                val routeReady = action != "audio_start" || rxAudio.start(receiver.backendIndex, outputRate)
                if (action == "audio_stop") rxAudio.stop("Operator stopped TCI RX audio")
                if (routeReady) targets.forEach { target -> dispatch(
                    RadioPlatformAction(RadioActionClass.SAFE_SET, if (action == "unmute") "mute" else action,
                        longValue = when (action) { "mute" -> 1L; "unmute" -> 0L; else -> null },
                        targetReceiver = target.backendIndex),
                ) }
            })
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.receivers.take(2).forEach { receiver -> Box(Modifier.weight(1f)) { content(receiver) } }
            TciWorkbenchRail(state, rxAudio, operational, dispatch, Modifier.widthIn(min = 220.dp, max = 310.dp).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.receivers.take(2).forEach { receiver -> content(receiver) }
            TciWorkbenchRail(state, rxAudio, operational, dispatch, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ReceiverInstrument(receiver: TciReceiverSnapshot, select: () -> Unit, listen: () -> Unit, stream: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (receiver.active) SdrAmber.copy(alpha = .13f) else SdrPanel),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "TCI ${receiver.label}, ${receiver.mode}, ${receiver.effectiveRxHz} hertz" }) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(receiver.label, color = SdrAmber, fontWeight = FontWeight.Black)
                Text(buildString { if (receiver.active) append("CONTROL "); if (receiver.listening) append("LISTEN ") }.ifBlank { "AVAILABLE" },
                    color = if (receiver.active || receiver.listening) SdrHealthy else SdrMuted)
            }
            Text(formatRadioFrequency(receiver.effectiveRxHz), color = SdrInk, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black, fontSize = 34.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SdrMeasure("VFO A", formatRadioFrequency(receiver.vfoAHz))
                SdrMeasure("VFO B", formatRadioFrequency(receiver.vfoBHz))
                SdrMeasure("MODE", receiver.mode)
                SdrMeasure("PASSBAND", receiver.passbandHz?.let { "$it Hz" } ?: "UNKNOWN")
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(select, modifier = Modifier.heightIn(min = 48.dp)) { Text("CONTROL") }
                OutlinedButton(listen, modifier = Modifier.heightIn(min = 48.dp)) { Text("LISTEN") }
                OutlinedButton({ stream(if (receiver.iqRunning) "iq_stop" else "iq_start") }) { Text(if (receiver.iqRunning) "STOP IQ" else "START IQ") }
                OutlinedButton({ stream(if (receiver.audioRunning) "audio_stop" else "audio_start") }) { Text(if (receiver.audioRunning) "STOP AUDIO" else "START AUDIO") }
                OutlinedButton({ stream(if (receiver.muted) "unmute" else "mute") }) { Text(if (receiver.muted) "UNMUTE" else "MUTE") }
            }
            Text("${if (receiver.enabled) "RX ENABLED" else "RX DISABLED"} · IF ${receiver.ifOffsetHz?.let { "$it Hz" } ?: "UNKNOWN"} · SPLIT ${receiver.split?.toString()?.uppercase() ?: "UNKNOWN"} · IQ ${if (receiver.iqRunning) "LIVE" else "STOPPED"} · AUDIO ${if (receiver.audioRunning) "LIVE" else "STOPPED"} · IQ ${receiver.iqSampleRate} / AUDIO ${receiver.rxAudioSampleRate} Hz · DROP ${receiver.droppedFrames}", color = SdrMuted)
            Text("METER ${receiver.meterDbm?.let { "%.1f dBm".format(it) } ?: "UNKNOWN"} · FWD ${receiver.forwardPowerWatts?.let { "%.1f W".format(it) } ?: "UNKNOWN"} · SWR ${receiver.swr?.let { "%.2f".format(it) } ?: "UNKNOWN"} · DRIVE ${receiver.drivePercent?.let { "$it%" } ?: "UNKNOWN"}", color = SdrMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun TciWorkbenchRail(state: TciRuntimeSnapshot, rxAudio: TciRxAudioController, operational: SdrOperationalV2,
    dispatch: (RadioPlatformAction) -> Unit, modifier: Modifier) {
    var digits by remember { mutableStateOf(state.receivers.firstOrNull { it.active }?.effectiveRxHz?.toString().orEmpty()) }
    var ifOffset by remember { mutableStateOf("0") }
    var volume by remember { mutableFloatStateOf((state.receivers.firstOrNull()?.volumeDb ?: -20).toFloat()) }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = SdrRaised)) {
        Column(Modifier.padding(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("RECEIVER / AUDIO / LINK", color = SdrAmber, fontWeight = FontWeight.Bold)
            ReceiverLinkMode.entries.forEach { value ->
                FilterChip(operational.linkMode == value, { operational.updateLinkMode(value) }, { Text(value.name.replace('_', ' '), fontSize = 10.sp) })
            }
            Text("Diversity · ${operational.diversity} · no coherence claim", color = SdrHold, fontSize = 10.sp)
            Text("DIRECT DIGIT TUNING", color = SdrAmber, fontSize = 10.sp)
            OutlinedTextField(digits, { digits = it.filter(Char::isDigit).take(11) }, label = { Text("Frequency Hz") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(1_000_000L, 100_000L, 10_000L, 1_000L, 100L, 10L).forEach { decade ->
                    TextButton({ val current = frequencyFromDigits(digits) ?: return@TextButton; digits = adjustFrequencyDigit(current, decade, 1).toString() }) {
                        Text("+${when { decade >= 1_000_000 -> "M"; decade >= 1_000 -> "k"; else -> decade }}", fontSize = 9.sp)
                    }
                }
            }
            Button({
                val receiver = state.receivers.firstOrNull { it.active } ?: state.receivers.firstOrNull() ?: return@Button
                val hz = frequencyFromDigits(digits) ?: return@Button
                dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = hz, targetReceiver = receiver.backendIndex))
                operational.linkedActions(receiver.backendIndex, hz, null, state.receivers).forEach(dispatch)
            }, enabled = state.ready && frequencyFromDigits(digits) != null) { Text("RECEIVE REVIEW · SET") }
            Text("A frequency change is operator initiated; linked receiver writes remain bounded and require readback.", color = SdrMuted, fontSize = 9.sp)
            Text("MODE / IF / SPLIT / VOLUME", color = SdrAmber, fontSize = 10.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("LSB", "USB", "CW", "AM", "SAM", "NFM", "WFM", "DIGU", "DIGL", "DSB").forEach { mode ->
                    FilterChip(state.receivers.firstOrNull { it.active }?.mode == mode, {
                        val receiver = state.receivers.firstOrNull { it.active } ?: return@FilterChip
                        dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "mode", textValue = mode, targetReceiver = receiver.backendIndex))
                        operational.linkedActions(receiver.backendIndex, null, mode, state.receivers).forEach(dispatch)
                    }, { Text(mode, fontSize = 9.sp) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ifOffset, { ifOffset = it.filter { char -> char.isDigit() || char == '-' }.take(9) },
                    label = { Text("IF offset Hz") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton({
                    val receiver = state.receivers.firstOrNull { it.active } ?: return@OutlinedButton
                    dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "if_offset", longValue = ifOffset.toLongOrNull() ?: 0,
                        targetReceiver = receiver.backendIndex))
                }) { Text("SET IF") }
                OutlinedButton({
                    val receiver = state.receivers.firstOrNull { it.active } ?: return@OutlinedButton
                    dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "split", longValue = if (receiver.split == true) 0 else 1,
                        targetReceiver = receiver.backendIndex))
                }) { Text("SPLIT") }
            }
            Text("TCI master volume ${volume.toInt()} dB", color = SdrMuted, fontSize = 9.sp)
            Slider(volume, { value -> volume = value; dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "volume", longValue = value.toLong())) }, valueRange = -60f..0f)
            Text("Passband/filter and RIT/XIT remain UNKNOWN/UNAVAILABLE because v1.5.3 exposes no stable setter contract.", color = SdrHold, fontSize = 9.sp)
            Text("AUDIO MIXER", color = SdrAmber, fontSize = 10.sp)
            RxMixerMode.entries.forEach { mode -> FilterChip(rxAudio.mixer.mode == mode,
                { rxAudio.updateMixer(rxAudio.mixer.copy(mode = mode)) }, { Text(mode.name.replace('_', ' '), fontSize = 10.sp) }) }
            Text("Crossfade ${"%.2f".format(rxAudio.mixer.crossfade)} · master ${"%.2f".format(rxAudio.mixer.master)}", color = SdrMuted, fontSize = 9.sp)
            Slider(rxAudio.mixer.crossfade, { rxAudio.updateMixer(rxAudio.mixer.copy(crossfade = it)) }, valueRange = -1f..1f)
            Slider(rxAudio.mixer.master, { rxAudio.updateMixer(rxAudio.mixer.copy(master = it)) }, valueRange = 0f..1.5f)
            Text("A gain ${"%.2f".format(rxAudio.mixer.receiverA.gain)} · pan ${"%.2f".format(rxAudio.mixer.receiverA.pan)}", color = SdrMuted, fontSize = 9.sp)
            Slider(rxAudio.mixer.receiverA.gain, { rxAudio.updateMixer(rxAudio.mixer.copy(receiverA = rxAudio.mixer.receiverA.copy(gain = it))) }, valueRange = 0f..2f)
            Slider(rxAudio.mixer.receiverA.pan, { rxAudio.updateMixer(rxAudio.mixer.copy(receiverA = rxAudio.mixer.receiverA.copy(pan = it))) }, valueRange = -1f..1f)
            Text("B gain ${"%.2f".format(rxAudio.mixer.receiverB.gain)} · pan ${"%.2f".format(rxAudio.mixer.receiverB.pan)}", color = SdrMuted, fontSize = 9.sp)
            Slider(rxAudio.mixer.receiverB.gain, { rxAudio.updateMixer(rxAudio.mixer.copy(receiverB = rxAudio.mixer.receiverB.copy(gain = it))) }, valueRange = 0f..2f)
            Slider(rxAudio.mixer.receiverB.pan, { rxAudio.updateMixer(rxAudio.mixer.copy(receiverB = rxAudio.mixer.receiverB.copy(pan = it))) }, valueRange = -1f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ rxAudio.updateMixer(rxAudio.mixer.copy(receiverA = rxAudio.mixer.receiverA.copy(solo = !rxAudio.mixer.receiverA.solo))) }) { Text("A SOLO") }
                TextButton({ rxAudio.updateMixer(rxAudio.mixer.copy(receiverA = rxAudio.mixer.receiverA.copy(muted = !rxAudio.mixer.receiverA.muted))) }) { Text("A MUTE") }
                TextButton({ rxAudio.updateMixer(rxAudio.mixer.copy(receiverB = rxAudio.mixer.receiverB.copy(solo = !rxAudio.mixer.receiverB.solo))) }) { Text("B SOLO") }
                TextButton({ rxAudio.updateMixer(rxAudio.mixer.copy(receiverB = rxAudio.mixer.receiverB.copy(muted = !rxAudio.mixer.receiverB.muted))) }) { Text("B MUTE") }
            }
            SdrMeasure("READBACK", "${state.confirmedReadbacks} confirmed · ${state.pendingReadbacks.size} pending")
            SdrMeasure("WRITE FAIL", "${state.failedWrites}")
            SdrMeasure("AUDIO QUEUES", "drop ${rxAudio.overflowByReceiver.joinToString("/")} · under ${rxAudio.underflowByReceiver.joinToString("/")}")
            Text("Spot bridge · ${operational.spotBridge}; the audited dialect has no stable spot contract.", color = SdrHold, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StreamHealthRail(state: TciRuntimeSnapshot, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = SdrRaised)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("STREAM / SAFETY", color = SdrAmber, fontWeight = FontWeight.Bold)
            SdrMeasure("CONNECTION", state.state.name)
            SdrMeasure("RECEIVERS", "${state.receivers.size}")
            SdrMeasure("MALFORMED", "${state.malformedFrames}")
            SdrMeasure("OVERLOAD DROP", "${state.droppedFrames}")
            SdrMeasure("TX STREAMS IGNORED", "${state.blockedTxFrames}")
            Text("PTT, TUNE, TX audio, drive and automatic transmit are unavailable in Android TCI v1.", color = SdrHold, fontSize = 11.sp)
        }
    }
}

@Composable
fun TciPanadapterPanel(state: TciRuntimeSnapshot, controller: PanadapterController, dispatch: (RadioPlatformAction) -> Unit,
    scanner: ReceiveOnlyScannerController? = null, operational: SdrOperationalV2? = null,
    workbench: AndroidSdrWorkbenchV4? = null, localReceivers: LocalReceiverController? = null,
    memories: List<RadioPreset> = emptyList(), openDigi: () -> Unit = {}) {
    var dual by remember { mutableStateOf(true) }
    var fit by remember { mutableStateOf(true) }
    var peak by remember { mutableStateOf(true) }
    var palette by remember { mutableIntStateOf(0) }
    var floor by remember { mutableFloatStateOf(-125f) }
    var top by remember { mutableFloatStateOf(-35f) }
    var displayMode by remember { mutableStateOf("BOTH") }
    var scannerOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(dual, { dual = !dual }, { Text(if (dual) "DUAL RECEIVER" else "SELECTED RECEIVER") })
            FilterChip(fit, { fit = !fit }, { Text("FIT ${if (fit) "ON" else "OFF"}") })
            FilterChip(peak, { peak = !peak }, { Text("PEAK HOLD ${if (peak) "ON" else "OFF"}") })
            FilterChip(false, { palette = (palette + 1) % 4 }, { Text(listOf("AMBER", "VIRIDIS", "ICE", "MONO")[palette]) })
            OutlinedButton(controller::resetPeakHold) { Text("RESET PEAK") }
            if (scanner != null) FilterChip(scannerOpen, { scannerOpen = !scannerOpen }, { Text("SCANNER") })
        }
        if (scannerOpen && scanner != null) {
            ScannerPanel(scanner, memories, controller.frame, state, dispatch, operational)
            return@Column
        }
        operational?.let { v2 ->
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeShiftLength.entries.forEach { length -> FilterChip(v2.timeShift.snapshot.length == length,
                    { v2.timeShift.configure(length) }, { Text(if (length == TimeShiftLength.OFF) "SHIFT OFF" else "${length.seconds}s SHIFT") }) }
                OutlinedButton(v2.timeShift::pause, enabled = v2.timeShift.snapshot.frameCount > 0) { Text("PAUSE") }
                OutlinedButton(v2.timeShift::replay, enabled = v2.timeShift.selectedFrame != null) { Text("REPLAY") }
                OutlinedButton(v2.timeShift::returnLive) { Text("LIVE") }
                OutlinedButton({ v2.timeShift.bookmark("Operator signal bookmark") }, enabled = v2.timeShift.selectedFrame != null) { Text("BOOKMARK") }
                OutlinedButton({ v2.timeShift.clear() }) { Text("CLEAR") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SkimmerMode.entries.forEach { mode -> FilterChip(mode in v2.skimmer.enabledModes,
                    { v2.skimmer.setEnabled(mode, mode !in v2.skimmer.enabledModes) }, { Text("${mode.name} SKIMMER") }) }
                SdrTruthChip("${workbench?.historicalLabel ?: v2.timeShift.snapshot.playback} · ${v2.timeShift.snapshot.bufferedSeconds}s · ${v2.timeShift.snapshot.bytes / 1024} KiB", true)
                SdrTruthChip("${v2.skimmer.markers.size} MARKERS · ${v2.skimmer.decodeMillis} ms", v2.skimmer.decodeMillis < 250)
            }
            if (v2.timeShift.snapshot.length != TimeShiftLength.OFF && v2.timeShift.snapshot.frameCount > 0) {
                Text("TIME-SHIFT CURSOR · ${v2.timeShift.snapshot.cursorSecondsBehind}s behind live", color = SdrMuted, fontSize = 9.sp)
                Slider(v2.timeShift.snapshot.cursorSecondsBehind.toFloat(), { v2.timeShift.scrub(it.toInt()) },
                    valueRange = 0f..v2.timeShift.snapshot.length.seconds.toFloat().coerceAtLeast(1f))
            }
            if (v2.timeShift.bookmarks.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                v2.timeShift.bookmarks.take(6).forEach { bookmark ->
                    TextButton({ v2.timeShift.deleteBookmark(bookmark.id) }) {
                        Text("BOOKMARK · ${formatRadioFrequency(bookmark.frequencyHz)} · DELETE", fontSize = 9.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("BOTH", "SPECTRUM", "WATERFALL").forEach { value ->
                FilterChip(displayMode == value, { displayMode = value }, { Text(value) })
            }
            listOf(1, 2, 4, 8).forEach { frames -> FilterChip(controller.settings.averageFrames == frames,
                { controller.updateSettings(controller.settings.copy(averageFrames = frames)) }, { Text("AVG $frames") }) }
            listOf(0f, 5f, 10f).forEach { decay -> FilterChip(controller.settings.peakDecayDbPerSecond == decay,
                { controller.updateSettings(controller.settings.copy(peakDecayDbPerSecond = decay)) }, { Text("DECAY ${decay.toInt()}") }) }
        }
        if (!fit) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FLOOR ${floor.toInt()}", color = SdrMuted); Slider(floor, { floor = it.coerceAtMost(top - 20) }, valueRange = -140f..-50f, modifier = Modifier.weight(1f))
            Text("TOP ${top.toInt()}", color = SdrMuted); Slider(top, { top = it.coerceAtLeast(floor + 20) }, valueRange = -100f..0f, modifier = Modifier.weight(1f))
        }
        val displays = controller.tciDisplays.toSortedMap().values.take(if (dual) 2 else 1).toList()
        if (displays.isEmpty()) SdrEmptyState("TCI I/Q unavailable", "Start I/Q on a receiver after the TCI connection reports READY, or use DEMO · NO RADIO in a debug build.")
        else BoxWithConstraints(Modifier.weight(1f)) {
            val sideBySide = dual && maxWidth >= 780.dp
            if (sideBySide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                displays.forEach { display ->
                    val centerHz = state.receivers.firstOrNull { it.backendIndex == display.receiverIndex }?.effectiveRxHz
                    TciSpectrumInstrument(display, centerHz, fit, peak, palette, floor, top, displayMode, operational, workbench, localReceivers, dispatch, Modifier.weight(1f))
                }
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                displays.forEach { display ->
                    val centerHz = state.receivers.firstOrNull { it.backendIndex == display.receiverIndex }?.effectiveRxHz
                    TciSpectrumInstrument(display, centerHz, fit, peak, palette, floor, top, displayMode, operational, workbench, localReceivers, dispatch, Modifier.weight(1f))
                }
            }
        }
        operational?.skimmer?.selectedMarker?.let { marker -> MarkerInspector(marker, operational, dispatch, openDigi) }
        if (workbench != null) SdrWorkbenchControlPanel(workbench, state, controller, localReceivers, dispatch)
    }
}

@Composable
private fun TciSpectrumInstrument(display: TciPanadapterDisplay, centerHz: Long?, fit: Boolean, peak: Boolean, palette: Int,
    manualFloor: Float, manualTop: Float, displayMode: String, operational: SdrOperationalV2?,
    workbench: AndroidSdrWorkbenchV4?, localReceivers: LocalReceiverController?, dispatch: (RadioPlatformAction) -> Unit, modifier: Modifier) {
    val frame = display.frame
    val reviewFrame = operational?.timeShift?.selectedFrame?.takeIf {
        operational.timeShift.snapshot.playback != TimeShiftPlayback.LIVE && it.receiver == display.receiverIndex
    }
    val visibleTrace = reviewFrame?.trace ?: frame.trace
    val tracePeak = visibleTrace.maxOrNull() ?: frame.peakDb
    val traceFloor = visibleTrace.minOrNull() ?: frame.floorDb
    val fittedFloor = if (fit) traceFloor.coerceAtMost(tracePeak - 30f) else manualFloor
    val fittedTop = if (fit) max(tracePeak + 4f, fittedFloor + 30f) else manualTop
    var viewZoom by remember(display.receiverIndex) { mutableFloatStateOf(1f) }
    var viewPan by remember(display.receiverIndex) { mutableFloatStateOf(0f) }
    var reviewOffsetHz by remember(display.receiverIndex) { mutableStateOf(0L) }
    var pointerHz by remember(display.receiverIndex) { mutableStateOf<Long?>(null) }
    var localReviewHz by remember(display.receiverIndex) { mutableStateOf<Long?>(null) }
    var draggingLocalId by remember(display.receiverIndex) { mutableStateOf<String?>(null) }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RX ${display.receiverIndex + 1} · ${display.sampleRate / 1000} kHz · ${if (reviewFrame == null) "LIVE" else "TIME SHIFT ${operational?.timeShift?.snapshot?.cursorSecondsBehind}s"}", color = SdrAmber, fontWeight = FontWeight.Bold)
                Text("FIT ${"%.0f".format(fittedFloor)} / ${"%.0f".format(fittedTop)} dB · DROP ${display.droppedFrames}", color = SdrMuted)
            }
            if (displayMode != "WATERFALL") Canvas(Modifier.fillMaxWidth().weight(if (displayMode == "BOTH") .44f else 1f)
                .pointerInput(centerHz, display.sampleRate) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val width = size.width.coerceAtLeast(1).toFloat()
                        viewZoom = (viewZoom * gestureZoom).coerceIn(1f, 32f)
                        viewPan = (viewPan - pan.x / width / viewZoom).coerceIn(-.5f, .5f)
                        centerHz?.let { center ->
                            val offset = ((centroid.x / width - .5f + viewPan) * display.sampleRate / viewZoom).toLong()
                            pointerHz = center + offset
                            reviewOffsetHz = offset
                        }
                    }
                }
                .pointerInput(centerHz, operational?.skimmer?.markers) {
                    detectTapGestures { offset ->
                        val center = centerHz ?: return@detectTapGestures
                        val hz = center - display.sampleRate / 2L + (display.sampleRate * offset.x / size.width.coerceAtLeast(1)).toLong()
                        val marker = operational?.skimmer?.markers?.minByOrNull { kotlin.math.abs(it.frequencyHz - hz) }
                            ?.takeIf { kotlin.math.abs(it.frequencyHz - hz) <= display.sampleRate / 30L }
                        if (marker != null) operational?.skimmer?.select(marker.id) else {
                            pointerHz = hz; localReviewHz = hz
                            if (workbench?.measurement?.inspector?.markerAHz == null) workbench?.measurement?.setMarkerA(hz)
                            else workbench?.measurement?.setMarkerB(hz)
                        }
                    }
                }
                .pointerInput(centerHz, localReceivers?.snapshot?.receivers) {
                    detectDragGestures(onDragStart = { offset ->
                        val center = centerHz ?: return@detectDragGestures
                        val hz = center - display.sampleRate / 2L + (display.sampleRate * offset.x / size.width.coerceAtLeast(1)).toLong()
                        draggingLocalId = localReceivers?.snapshot?.receivers
                            ?.filter { it.sourceReceiver == display.receiverIndex }
                            ?.minByOrNull { kotlin.math.abs(it.frequencyHz - hz) }
                            ?.takeIf { kotlin.math.abs(it.frequencyHz - hz) <= display.sampleRate / 30L }?.id
                    }, onDragEnd = { draggingLocalId = null }, onDragCancel = { draggingLocalId = null }) { change, _ ->
                        val center = centerHz ?: return@detectDragGestures
                        val id = draggingLocalId ?: return@detectDragGestures
                        val hz = center - display.sampleRate / 2L + (display.sampleRate * change.position.x / size.width.coerceAtLeast(1)).toLong()
                        localReviewHz = hz
                        localReceivers?.move(id, (hz - center).toInt())
                        change.consume()
                    }
                }
                .semantics { contentDescription = "TCI receiver ${display.receiverIndex + 1} live spectrum with VFO, passband, band plan and explicit QSY review" }) {
                drawRect(Color(0xFF07090B))
                val trace = visibleTrace
                if (trace.size > 1) {
                    val visibleBins = (trace.size / viewZoom).toInt().coerceAtLeast(2)
                    val centerBin = ((.5f + viewPan) * trace.lastIndex).toInt().coerceIn(0, trace.lastIndex)
                    val firstBin = (centerBin - visibleBins / 2).coerceIn(0, (trace.size - visibleBins).coerceAtLeast(0))
                    val lastBin = (firstBin + visibleBins - 1).coerceAtMost(trace.lastIndex)
                    val path = Path()
                    for (index in firstBin..lastBin) {
                        val value = trace[index]
                        val x = size.width * (index - firstBin) / (lastBin - firstBin).coerceAtLeast(1).toFloat()
                        val y = size.height * (1f - ((value - fittedFloor) / (fittedTop - fittedFloor)).coerceIn(0f, 1f))
                        if (index == firstBin) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, SdrAmber, style = Stroke(1.5.dp.toPx()))
                    if (peak && reviewFrame == null && frame.peakHold.size == trace.size) {
                        val peakPath = Path()
                        for (index in firstBin..lastBin) {
                            val value = frame.peakHold[index]
                            val x = size.width * (index - firstBin) / (lastBin - firstBin).coerceAtLeast(1).toFloat()
                            val y = size.height * (1f - ((value - fittedFloor) / (fittedTop - fittedFloor)).coerceIn(0f, 1f))
                            if (index == firstBin) peakPath.moveTo(x, y) else peakPath.lineTo(x, y)
                        }
                        drawPath(peakPath, SdrHold.copy(alpha = .65f), style = Stroke(1.dp.toPx()))
                    }
                }
                val passbandCenter = (.5f + reviewOffsetHz.toFloat() * viewZoom / display.sampleRate).coerceIn(0f, 1f)
                drawRect(SdrHealthy.copy(alpha = .13f), topLeft = Offset(size.width * (passbandCenter - .06f).coerceAtLeast(0f), 0f),
                    size = androidx.compose.ui.geometry.Size(size.width * .12f, size.height))
                drawLine(SdrHealthy, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
                val segments = listOf(0f to .18f, .18f to .38f, .38f to .86f, .86f to 1f)
                val colors = listOf(SdrAmber, Color(0xFF43C7D9), SdrHealthy, SdrHold)
                segments.forEachIndexed { index, segment -> drawRect(colors[index].copy(alpha = .45f),
                    topLeft = Offset(size.width * segment.first, size.height - 7.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width * (segment.second - segment.first), 7.dp.toPx())) }
                if (centerHz != null && operational != null) operational.skimmer.markers.forEach { marker ->
                    val x = size.width * ((marker.frequencyHz - (centerHz - display.sampleRate / 2.0)) / display.sampleRate).toFloat()
                    if (x in 0f..size.width) {
                        drawLine(if (marker.confirmed) SdrHealthy else SdrHold, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                }
                if (centerHz != null && localReceivers != null) localReceivers.snapshot.receivers
                    .filter { it.sourceReceiver == display.receiverIndex }.forEach { receiver ->
                        val x = size.width * ((receiver.frequencyHz - (centerHz - display.sampleRate / 2.0)) / display.sampleRate).toFloat()
                        val half = receiver.preferences.filterHighHz * size.width / display.sampleRate / 2f
                        if (x in 0f..size.width) {
                            drawRect((if (receiver.listening) SdrHealthy else SdrHold).copy(alpha = .14f),
                                Offset((x - half).coerceAtLeast(0f), 0f), androidx.compose.ui.geometry.Size(half * 2f, size.height))
                            drawLine(if (receiver.id.endsWith("A")) SdrHealthy else Color.Cyan,
                                Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                        }
                    }
                if (centerHz != null && workbench != null) {
                    listOf(workbench.measurement.inspector.markerAHz to SdrHealthy, workbench.measurement.inspector.markerBHz to Color.Cyan).forEach { (frequency, color) ->
                        frequency?.let { markerHz ->
                            val x = size.width * ((markerHz - (centerHz - display.sampleRate / 2.0)) / display.sampleRate).toFloat()
                            if (x in 0f..size.width) drawLine(color, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                        }
                    }
                    workbench.measurement.monitors.forEach { monitor ->
                        val x = size.width * ((monitor.frequencyHz - (centerHz - display.sampleRate / 2.0)) / display.sampleRate).toFloat()
                        if (x in 0f..size.width) drawLine((if (monitor.occupied) SdrDanger else SdrMuted).copy(alpha = .8f),
                            Offset(x, size.height * .55f), Offset(x, size.height), 1.dp.toPx())
                    }
                }
            }
            if (displayMode != "SPECTRUM") WaterfallCanvas(display.waterfallRows, fittedFloor, fittedTop, palette,
                Modifier.fillMaxWidth().weight(if (displayMode == "BOTH") .56f else 1f))
            Row(Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${pointerHz?.let(::formatRadioFrequency) ?: "touch for frequency"} · zoom ${"%.1f".format(viewZoom)}× · ${listOf("amber", "viridis", "ice", "mono")[palette]} ${fittedFloor.toInt()}…${fittedTop.toInt()} dB",
                    color = SdrMuted, fontSize = 10.sp)
                TextButton({
                    centerHz?.let { dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = it + reviewOffsetHz)) }
                }, enabled = centerHz != null) { Text("QSY REVIEW") }
            }
            if (centerHz != null && localReceivers != null) LocalReceiverTapActions(localReceivers, "TCI", display.receiverIndex,
                centerHz, display.sampleRate, localReviewHz)
        }
    }
}

@Composable
private fun MarkerInspector(marker: SkimmerMarker, operational: SdrOperationalV2,
    dispatch: (RadioPlatformAction) -> Unit, openDigi: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("MARKER INSPECTOR · ${marker.mode} · ${if (marker.confirmed) "DECODE CONFIRMED" else "CANDIDATE ONLY"}",
                color = if (marker.confirmed) SdrHealthy else SdrHold, fontWeight = FontWeight.Bold)
            Text("${marker.callLikeToken ?: "NO CALL TOKEN"} · ${formatRadioFrequency(marker.frequencyHz)} · SNR ${"%.1f".format(marker.snrDb)} dB · confidence ${"%.2f".format(marker.confidence)} · ${marker.source}", color = SdrMuted)
            if (marker.text.isNotBlank()) Text(marker.text.take(160), color = SdrInk, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = marker.frequencyHz)) }) { Text("RECEIVE REVIEW") }
                OutlinedButton(openDigi) { Text("OPEN DIGI") }
                OutlinedButton({ operational.timeShift.bookmark("${marker.mode} · ${marker.callLikeToken ?: "candidate"}") }) { Text("BOOKMARK") }
                OutlinedButton({ operational.skimmer.hide(marker.id) }) { Text("HIDE") }
            }
            Text("No marker tunes automatically and no skimmer action can transmit.", color = SdrHold, fontSize = 9.sp)
        }
    }
}

@Composable
private fun WaterfallCanvas(rows: List<FloatArray>, floor: Float, top: Float, palette: Int, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "True scrolling TCI waterfall with colour scale" }) {
        drawRect(Color.Black)
        if (rows.isEmpty()) return@Canvas
        val rowHeight = size.height / rows.size
        rows.forEachIndexed { yIndex, row ->
            val step = (row.size / 256).coerceAtLeast(1)
            for (xIndex in row.indices step step) {
                val value = ((row[xIndex] - floor) / (top - floor)).coerceIn(0f, 1f)
                val color = waterfallColor(value, palette)
                val x = size.width * xIndex / row.size
                drawRect(color, Offset(x, yIndex * rowHeight), androidx.compose.ui.geometry.Size(size.width * step / row.size + 1, rowHeight + 1))
            }
        }
    }
}

private fun waterfallColor(value: Float, palette: Int): Color = when (palette) {
    1 -> Color(value * .35f, value, .55f + value * .4f)
    2 -> Color(value * .4f, value * .85f, 1f)
    3 -> Color(value, value, value)
    else -> Color(value, value * .55f, value * .08f)
}

@Composable
fun ScannerPanel(scanner: ReceiveOnlyScannerController, memories: List<RadioPreset>, frame: PanadapterFrame?,
    tci: TciRuntimeSnapshot, dispatch: (RadioPlatformAction) -> Unit, operational: SdrOperationalV2? = null) {
    val snapshot = scanner.snapshot
    var mode by remember { mutableStateOf(scanner.config.mode) }
    var startMHz by remember { mutableStateOf("%.6f".format(scanner.config.startHz / 1_000_000.0)) }
    var endMHz by remember { mutableStateOf("%.6f".format(scanner.config.endHz / 1_000_000.0)) }
    var stepKhz by remember { mutableStateOf("%.3f".format(scanner.config.stepHz / 1_000.0)) }
    var radioMode by remember { mutableStateOf(scanner.config.radioMode) }
    var filterHz by remember { mutableStateOf(scanner.config.filterHz.toString()) }
    var skipList by remember { mutableStateOf(scanner.config.skipHz.sorted().joinToString(",")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("RECEIVE-ONLY SCANNER", color = SdrAmber, fontWeight = FontWeight.Black)
        operational?.let { v2 ->
            Text("SCAN BANKS / PRIORITY", color = SdrAmber, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                v2.scanBanks.forEach { bank -> FilterChip(v2.selectedBankId == bank.id,
                    { v2.selectBank(bank.id) }, { Text("${bank.name} · ${bank.memories.size}") }) }
            }
            v2.scanBanks.firstOrNull { it.id == v2.selectedBankId }?.let { bank ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({ scanner.startBank(bank) }, enabled = snapshot.state == ScannerState.STOPPED && tci.ready && bank.enabled) { Text("START BANK") }
                    SdrMeasure("PRIORITY", bank.priority?.let { formatRadioFrequency(it.frequencyHz) } ?: "OFF")
                    RecordOnHitMode.entries.forEach { mode -> FilterChip(bank.recordOnHit == mode,
                        { v2.upsertBank(bank.copy(recordOnHit = mode)) }, { Text("REC ${mode.name}") }) }
                }
                Text("Priority is checked every five memories and never interrupts an operator-locked signal. Record-on-hit is explicit and uses the bounded time-shift authority.", color = SdrMuted, fontSize = 9.sp)
                Text("CAPTURE BOUNDS · pre ${v2.recordPolicy.preRollSeconds}s · post ${v2.recordPolicy.postRollSeconds}s · max ${v2.recordPolicy.maximumDurationSeconds}s · daily ${v2.recordPolicy.dailyBytes / 1024 / 1024} MiB · total ${v2.recordPolicy.totalBytes / 1024 / 1024} MiB", color = SdrMuted, fontSize = 9.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { ScannerMode.entries.forEach { value ->
            FilterChip(mode == value, { mode = value; if (snapshot.state == ScannerState.STOPPED) scanner.updateConfig(scanner.config.copy(mode = value)) }, { Text(value.name.replace('_', ' ')) }) } }
        SdrMeasure("STATE", snapshot.state.name)
        SdrMeasure("CURRENT", formatRadioFrequency(snapshot.currentHz))
        SdrMeasure("CANDIDATES", "${snapshot.candidateCount}")
        SdrMeasure("DWELL", "${scanner.config.dwellMillis} ms · ${scanner.config.resumePolicy}")
        if (snapshot.state == ScannerState.STOPPED) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startMHz, { startMHz = it.take(14) }, label = { Text("Start MHz") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(endMHz, { endMHz = it.take(14) }, label = { Text("End MHz") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(stepKhz, { stepKhz = it.take(10) }, label = { Text("Step kHz") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(radioMode, { radioMode = it.uppercase().take(12) }, label = { Text("Mode") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(filterHz, { filterHz = it.filter(Char::isDigit).take(6) }, label = { Text("Filter Hz") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton({
                    scanner.updateConfig(scanner.config.copy(
                        mode = mode,
                        startHz = ((startMHz.toDoubleOrNull() ?: 14.0) * 1_000_000).toLong(),
                        endHz = ((endMHz.toDoubleOrNull() ?: 14.35) * 1_000_000).toLong(),
                        stepHz = ((stepKhz.toDoubleOrNull() ?: 1.0) * 1_000).toLong(),
                        radioMode = radioMode.ifBlank { "USB" },
                        filterHz = filterHz.toIntOrNull() ?: 2_700,
                        skipHz = skipList.split(',').mapNotNull { token -> token.trim().toLongOrNull() }.toSet(),
                    ))
                }) { Text("APPLY") }
            }
            OutlinedTextField(skipList, { skipList = it.take(1024) }, label = { Text("Skip frequencies · Hz, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Threshold ${scanner.config.thresholdDb.toInt()} dB · dwell ${scanner.config.dwellMillis} ms", color = SdrMuted)
            Slider(scanner.config.thresholdDb, { scanner.updateConfig(scanner.config.copy(thresholdDb = it)) }, valueRange = -140f..0f)
            Slider(scanner.config.dwellMillis.toFloat(), { scanner.updateConfig(scanner.config.copy(dwellMillis = it.toLong())) }, valueRange = 100f..10_000f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ScannerResumePolicy.entries.forEach { policy ->
                FilterChip(scanner.config.resumePolicy == policy, { scanner.updateConfig(scanner.config.copy(resumePolicy = policy)) }, { Text(policy.name.replace('_', ' ')) })
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ when (mode) {
                ScannerMode.MEMORY -> scanner.startMemory(memories)
                ScannerMode.RANGE -> scanner.startRange()
                ScannerMode.FFT_SPAN -> frame?.let { scanner.startFft(it.trace, tci.receivers.firstOrNull { row -> row.active }?.effectiveRxHz ?: 0, it.sampleRate.toLong()) }
            } }, enabled = snapshot.state == ScannerState.STOPPED && tci.ready) { Text("START") }
            OutlinedButton({ scanner.stop() }, enabled = snapshot.state != ScannerState.STOPPED) { Text("STOP") }
            OutlinedButton({ dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = snapshot.currentHz)) }, enabled = snapshot.currentHz > 0) { Text("HOLD / REVIEW") }
        }
        Text("Explicit Start only. Background, profile change, disconnect, manual tune and Global Stop disarm scanning. No PTT, TUNE or TX command exists.", color = SdrHold)
        operational?.let { v2 ->
            HorizontalDivider()
            Text("ACTIVITY JOURNAL · ${v2.journalRetentionDays} DAYS · ${v2.journal.size}/${v2.maximumJournalRows}", color = SdrAmber, fontWeight = FontWeight.Bold)
            v2.journal.take(8).forEach { row -> Text("${row.bank} · ${formatRadioFrequency(row.frequencyHz)} · ${row.mode} · ${row.peakDb?.let { "${it.toInt()} dB" } ?: "UNKNOWN"} · ${row.resumeReason}${row.captureId?.let { " · CAPTURE" } ?: ""}", color = SdrMuted, fontSize = 9.sp) }
            if (v2.journal.isEmpty()) Text("No bounded scanner activity yet.", color = SdrMuted)
        }
    }
}

@Composable
fun RfIntelligenceWorkspace(controller: RfObservationController, workbench: AndroidSdrWorkbenchV4,
    existing: @Composable () -> Unit) {
    var page by remember { mutableStateOf("INTELLIGENCE") }
    Column(Modifier.fillMaxSize().background(SdrChassis)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("INTELLIGENCE", "SPECTRUM ANALYSIS", "RF PATH MAP", "RF GLOBE").forEach { label ->
                val value = when (label) { "SPECTRUM ANALYSIS" -> "SPECTRUM"; "RF PATH MAP" -> "RF MAP"; else -> label }
                FilterChip(page == value, { page = value }, { Text(label) })
            }
        }
        Text(when (page) {
            "SPECTRUM" -> "SIGNAL ANALYSIS · frequency × UTC occupancy and all-band comparison · no geographic paths"
            "RF MAP" -> "FLAT GEOGRAPHIC PATH MAP · drag to pan · pinch to zoom"
            "RF GLOBE" -> "ORTHOGRAPHIC GLOBE · front hemisphere only · drag to rotate · pinch to zoom"
            else -> "INTELLIGENCE · operational evidence and decisions"
        }, color = SdrMuted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        Box(Modifier.weight(1f)) {
            when (page) {
                "RF MAP" -> RfMapGlobeScreen(controller, globe = false)
                "RF GLOBE" -> RfMapGlobeScreen(controller, globe = true)
                "SPECTRUM" -> SpectrumSurveyPanel(workbench)
                else -> existing()
            }
        }
    }
}

@Composable
fun DigiRfPathWrapper(controller: RfObservationController, existing: @Composable () -> Unit) {
    var expandedMap by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { existing() }
        Card(Modifier.fillMaxWidth().height(142.dp).padding(horizontal = 8.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = SdrPanel)) {
            Column(Modifier.fillMaxSize().padding(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DIGI / WSPR SELECTED PATH · sequence visualisation is not RF proof", color = SdrAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton({ expandedMap = true }, modifier = Modifier.heightIn(min = 44.dp)) { Text("EXPAND MAP") }
                }
                RfCanvas(controller.filtered.take(32), globe = false, centerLat = 0.0, centerLon = 0.0,
                    zoom = 1f, longPath = false, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    if (expandedMap) Dialog(onDismissRequest = { expandedMap = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.90f), color = SdrChassis, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton({ expandedMap = false }) { Text("CLOSE MAP") }
                }
                Box(Modifier.weight(1f)) { RfMapGlobeScreen(controller, globe = false) }
            }
        }
    }
}

@Composable
fun RfMapGlobeScreen(controller: RfObservationController, globe: Boolean) {
    var filtersOpen by remember { mutableStateOf(false) }
    val receiverAnchor = controller.filtered.firstOrNull()
    var centerLat by remember { mutableDoubleStateOf(receiverAnchor?.receiverLatitude ?: 0.0) }
    var centerLon by remember { mutableDoubleStateOf(receiverAnchor?.receiverLongitude ?: 0.0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var globeAdjusted by remember { mutableStateOf(false) }
    LaunchedEffect(globe, receiverAnchor?.receiverLatitude, receiverAnchor?.receiverLongitude) {
        if (globe && !globeAdjusted && receiverAnchor != null) {
            centerLat = receiverAnchor.receiverLatitude
            centerLon = receiverAnchor.receiverLongitude
        }
    }
    Column(Modifier.fillMaxSize().background(SdrChassis).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(if (globe) "INTERACTIVE ORTHOGRAPHIC RF GLOBE" else "FLAT RF EVIDENCE PATH MAP", color = SdrAmber, fontWeight = FontWeight.Black)
                Text("${controller.filtered.size}/${controller.observations.size} bounded observations · filter ${controller.filterMillis} ms", color = SdrMuted) }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (globe && receiverAnchor != null) OutlinedButton({
                    centerLat = receiverAnchor.receiverLatitude
                    centerLon = receiverAnchor.receiverLongitude
                    zoom = 1f
                    globeAdjusted = false
                }) { Text("CENTER RX") }
                OutlinedButton({ filtersOpen = true }) { Text("FILTERS") }
                OutlinedButton(controller::resetFilters) { Text("RESET FILTERS") }
            }
        }
        if (globe) {
            Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    globeAdjusted = true
                    centerLon = normalizeUiLongitude(centerLon - pan.x / size.width * 180 / zoom)
                    centerLat = (centerLat + pan.y / size.height * 90 / zoom).coerceIn(-90.0, 90.0)
                    zoom = (zoom * gestureZoom).coerceIn(.7f, 3.5f)
                }
            }) { RfCanvas(controller.filtered, true, centerLat, centerLon, zoom, controller.filters.longPath, Modifier.fillMaxSize()) }
        } else {
            RfPathMap(controller.filtered, controller.filters.longPath, Modifier.weight(1f).fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("OBSERVED", color = SdrHealthy); Text("HISTORICAL", color = SdrMuted); Text("EMPIRICAL OUTLOOK", color = SdrHold)
            Text("COARSE locations are hollow · selection never tunes", color = SdrMuted)
        }
    }
    if (filtersOpen) RfFilterDialog(controller, { filtersOpen = false })
}

@Composable
private fun RfPathMap(rows: List<RfObservation>, longPath: Boolean, modifier: Modifier) {
    val visible = rows.takeLast(80)
    val paths = remember(visible, longPath) {
        visible.flatMap { row ->
            val color = when (row.evidence) {
                RfEvidenceClass.OBSERVED -> SdrHealthy
                RfEvidenceClass.HISTORICAL -> SdrMuted
                RfEvidenceClass.OUTLOOK -> SdrHold
            }
            val points = greatCircle(
                row.transmitterLatitude, row.transmitterLongitude,
                row.receiverLatitude, row.receiverLongitude, longPath, 48,
            ).map { LatLng(it.latitude, it.longitude) }
            splitAtDateline(points).map { segment -> MapPath(segment, color.copy(alpha = .72f).toArgb(), 1.6f) }
        }
    }
    val markers = remember(visible) {
        buildList {
            visible.asReversed().distinctBy { Pair(it.transmitterLatitude, it.transmitterLongitude) }.take(100).forEach { row ->
                val color = when (row.evidence) {
                    RfEvidenceClass.OBSERVED -> SdrHealthy
                    RfEvidenceClass.HISTORICAL -> SdrMuted
                    RfEvidenceClass.OUTLOOK -> SdrHold
                }
                add(MapMarker(
                    row.transmitterLatitude, row.transmitterLongitude, row.callsign,
                    "${row.band} ${row.mode} · ${row.source} · ${row.evidence.name.lowercase(Locale.US)}",
                    color.toArgb(), if (row.precision == RfPrecision.COARSE) 10 else 12,
                    ring = row.precision == RfPrecision.COARSE,
                ))
            }
            visible.lastOrNull()?.let { row ->
                add(MapMarker(row.receiverLatitude, row.receiverLongitude, "RECEIVER",
                    row.receiverRegion.ifBlank { "Selected receive location" }, SdrAmber.toArgb(), 15, ring = true))
            }
        }
    }
    NativeNeuralMap(
        markers, paths, emptyList(), LatLng(18.0, 10.0), 1.15, NeuralBasemap.DARK,
        "RF PATHS", "Interactive geographic map with ${visible.size} bounded RF evidence paths",
        modifier, "GREEN OBSERVED · GREY HISTORICAL · AMBER OUTLOOK · HOLLOW COARSE",
    )
}

@Composable
private fun RfCanvas(rows: List<RfObservation>, globe: Boolean, centerLat: Double, centerLon: Double, zoom: Float,
    longPath: Boolean, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = if (globe) "Interactive RF globe with paths, control points, grayline and filters" else "Interactive flat RF map with paths, control points, grayline and filters" }) {
        drawRect(Color(0xFF071014))
        if (globe) {
            val globeRadius = minOf(size.width, size.height) * .46f * zoom
            drawCircle(Color(0xFF102C35), radius = globeRadius, center = center)
            drawRfLand(centerLat, centerLon, zoom)
            for (latitude in -60..60 step 30) {
                var previous: Offset? = null
                for (longitude in -180..180 step 4) {
                    val point = projectRf(latitude.toDouble(), longitude.toDouble(), true, centerLat, centerLon, zoom, size.width, size.height)
                    if (point != null && previous != null) drawLine(MapGridForRf, previous, point, 1.dp.toPx())
                    previous = point
                }
            }
            for (longitude in -180..150 step 30) {
                var previous: Offset? = null
                for (latitude in -90..90 step 3) {
                    val point = projectRf(latitude.toDouble(), longitude.toDouble(), true, centerLat, centerLon, zoom, size.width, size.height)
                    if (point != null && previous != null) drawLine(MapGridForRf, previous, point, 1.dp.toPx())
                    previous = point
                }
            }
            drawCircle(SdrAmber.copy(alpha = .55f), radius = globeRadius, center = center, style = Stroke(2.dp.toPx()))
        } else {
            for (step in 1 until 12) drawLine(MapGridForRf, Offset(size.width * step / 12f, 0f), Offset(size.width * step / 12f, size.height), 1.dp.toPx())
            for (step in 1 until 6) drawLine(MapGridForRf, Offset(0f, size.height * step / 6f), Offset(size.width, size.height * step / 6f), 1.dp.toPx())
        }
        val visible = rows.takeLast(4_096)
        visible.forEach { row ->
            val color = when (row.evidence) { RfEvidenceClass.OBSERVED -> SdrHealthy; RfEvidenceClass.HISTORICAL -> SdrMuted; RfEvidenceClass.OUTLOOK -> SdrHold }
            var previous: Offset? = null
            greatCircle(row.transmitterLatitude, row.transmitterLongitude, row.receiverLatitude, row.receiverLongitude,
                longPath, if (globe) 72 else 48).forEach { point ->
                val projected = projectRf(point.latitude, point.longitude, globe, centerLat, centerLon, zoom, size.width, size.height)
                val prior = previous
                if (projected != null && prior != null && (globe || kotlin.math.abs(projected.x - prior.x) < size.width * .45f)) {
                    drawLine(color.copy(alpha = .34f), prior, projected, 1.dp.toPx(), StrokeCap.Round)
                }
                previous = projected
            }
            val receiver = projectRf(row.receiverLatitude, row.receiverLongitude, globe, centerLat, centerLon, zoom, size.width, size.height)
            if (receiver != null) drawCircle(if (row.precision == RfPrecision.COARSE) Color.Transparent else color, 2.5.dp.toPx(), receiver,
                style = if (row.precision == RfPrecision.COARSE) Stroke(1.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
            propagationControlPoints(row, longPath).forEach { point ->
                projectRf(point.latitude, point.longitude, globe, centerLat, centerLon, zoom, size.width, size.height)?.let {
                    drawCircle(color.copy(alpha = .8f), 1.8.dp.toPx(), it)
                }
            }
        }
        val stationRow = rows.lastOrNull()
        val station = stationRow?.let { projectRf(it.receiverLatitude, it.receiverLongitude, globe, centerLat, centerLon, zoom, size.width, size.height) }
        if (station != null) drawCircle(SdrAmber, 6.dp.toPx(), station)
    }
}

private fun DrawScope.drawRfLand(centerLat: Double, centerLon: Double, zoom: Float) {
    val fill = Color(0xFF2B5149)
    val outline = Color(0xFF84B49B)
    rfWorldLandContours.forEach { contour ->
        val projected = contour.map { point ->
            projectRf(point.latitude, point.longitude, true, centerLat, centerLon, zoom, size.width, size.height)
        }
        if (projected.all { it != null }) {
            val path = Path()
            projected.filterNotNull().forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            path.close()
            drawPath(path, fill)
            drawPath(path, outline.copy(alpha = .82f), style = Stroke(1.1.dp.toPx()))
        } else {
            var path = Path()
            var pointsInPath = 0
            fun flush() {
                if (pointsInPath >= 2) drawPath(path, outline.copy(alpha = .78f), style = Stroke(1.05.dp.toPx()))
                path = Path(); pointsInPath = 0
            }
            projected.forEach { point ->
                if (point == null) flush()
                else {
                    if (pointsInPath == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    pointsInPath++
                }
            }
            flush()
        }
    }
}

private fun projectRf(latitude: Double, longitude: Double, globe: Boolean, centerLat: Double, centerLon: Double,
    zoom: Float, width: Float, height: Float): Offset? {
    if (!globe) return Offset(((normalizeUiLongitude(longitude - centerLon) + 180) / 360 * width * zoom).toFloat(),
        ((90 - latitude + centerLat) / 180 * height * zoom).toFloat())
    val lat = Math.toRadians(latitude); val lon = Math.toRadians(longitude - centerLon); val cLat = Math.toRadians(centerLat)
    val z = sin(cLat) * sin(lat) + cos(cLat) * cos(lat) * cos(lon)
    if (z < 0) return null
    val radius = minOf(width, height) * .46f * zoom
    val x = radius * cos(lat).toFloat() * sin(lon).toFloat()
    val y = radius * (cos(cLat) * sin(lat) - sin(cLat) * cos(lat) * cos(lon)).toFloat()
    return Offset(width / 2 + x, height / 2 - y)
}

@Composable
private fun RfFilterDialog(controller: RfObservationController, close: () -> Unit) {
    var draft by remember { mutableStateOf(controller.filters) }
    AlertDialog(onDismissRequest = close, title = { Text("RF evidence filters") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.callsign, { draft = draft.copy(callsign = it.take(24)) }, label = { Text("Callsign") })
            fun words(value: String) = value.split(',').map { it.trim().uppercase(Locale.US) }.filter(String::isNotBlank).toSet()
            OutlinedTextField(draft.sources.sorted().joinToString(","), { draft = draft.copy(sources = words(it)) },
                label = { Text("Sources · comma separated") })
            OutlinedTextField(draft.bands.sorted().joinToString(","), { draft = draft.copy(bands = words(it)) },
                label = { Text("Bands / all bands") })
            OutlinedTextField(draft.modes.sorted().joinToString(","), { draft = draft.copy(modes = words(it)) },
                label = { Text("Modes") })
            OutlinedTextField(draft.entities.sorted().joinToString(","), { draft = draft.copy(entities = words(it)) },
                label = { Text("DXCC / entity") })
            OutlinedTextField(draft.continents.sorted().joinToString(","), { draft = draft.copy(continents = words(it)) },
                label = { Text("Continents") })
            OutlinedTextField(draft.receiverRegions.sorted().joinToString(","), { draft = draft.copy(receiverRegions = words(it)) },
                label = { Text("Receive regions") })
            OutlinedTextField(draft.transmitterRegions.sorted().joinToString(","), { draft = draft.copy(transmitterRegions = words(it)) },
                label = { Text("Transmit regions") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.minimumDistanceKm?.toInt()?.toString().orEmpty(),
                    { draft = draft.copy(minimumDistanceKm = it.toDoubleOrNull()) }, label = { Text("Min km") }, modifier = Modifier.weight(1f))
                OutlinedTextField(draft.maximumDistanceKm?.toInt()?.toString().orEmpty(),
                    { draft = draft.copy(maximumDistanceKm = it.toDoubleOrNull()) }, label = { Text("Max km") }, modifier = Modifier.weight(1f))
                OutlinedTextField(draft.minimumBearingDegrees?.toInt()?.toString().orEmpty(),
                    { draft = draft.copy(minimumBearingDegrees = it.toDoubleOrNull()) }, label = { Text("Bearing from") }, modifier = Modifier.weight(1f))
                OutlinedTextField(draft.maximumBearingDegrees?.toInt()?.toString().orEmpty(),
                    { draft = draft.copy(maximumBearingDegrees = it.toDoubleOrNull()) }, label = { Text("Bearing to") }, modifier = Modifier.weight(1f))
            }
            Text("EVIDENCE", color = SdrAmber); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RfEvidenceClass.entries.forEach { value ->
                FilterChip(value in draft.evidence, { draft = draft.copy(evidence = if (value in draft.evidence) draft.evidence - value else draft.evidence + value) }, { Text(value.name) }) } }
            Text("OUTLOOK WINDOWS", color = SdrAmber); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(30, 60, 120).forEach { value ->
                FilterChip(value in draft.outlookMinutes, { draft = draft.copy(outlookMinutes = if (value in draft.outlookMinutes) draft.outlookMinutes - value else draft.outlookMinutes + value) }, { Text("$value min") }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.contestMultipliersOnly, { draft = draft.copy(contestMultipliersOnly = it) }); Text("Contest multipliers only") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.hideContestDuplicates, { draft = draft.copy(hideContestDuplicates = it) }); Text("Hide contest duplicates") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.longPath, { draft = draft.copy(longPath = it) }); Text("Explicit long path") }
            Text("Time window · ${draft.maximumAgeSeconds / 60} minutes", color = SdrMuted)
            Slider(draft.maximumAgeSeconds.toFloat(), { draft = draft.copy(maximumAgeSeconds = it.toLong()) }, valueRange = 300f..7_200f)
        } }, confirmButton = { Button({ controller.updateFilters(draft); close() }) { Text("APPLY") } },
        dismissButton = { TextButton(close) { Text("CANCEL") } })
}

@Composable
fun TxAudioCalibrationPanel(operational: SdrOperationalV2, state: TciRuntimeSnapshot) {
    val controller = operational.txLevels
    val current = controller.level()
    val snapshot = controller.snapshot(state.device, "TCI TX SOURCE · FAKE PREVIEW ONLY",
        state.receivers.any { it.forwardPowerWatts != null && it.swr != null })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("PER-MODE TX-AUDIO CALIBRATION · PHYSICALLY LOCKED", color = SdrAmber, fontWeight = FontWeight.Black)
        Text("Level editing and deterministic preview are available. No real calibration transmission is authorised.", color = SdrHold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("LSB", "USB", "CW", "AM", "SAM", "NFM", "WFM", "DIGU", "DIGL", "DSB").forEach { mode ->
                FilterChip(controller.selectedMode == mode, { controller.select(mode) }, { Text(mode) })
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SdrMeasure("MODE", snapshot.mode)
                SdrMeasure("LEVEL", "${"%.3f".format(snapshot.level)} · ${if (snapshot.inherited) "INHERITED" else "OVERRIDE"}")
                Slider(current.level, { controller.update(it, current.mode) }, valueRange = 0f..1f)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton({ controller.clearOverride() }) { Text("USE DEFAULT") }
                    OutlinedButton({ }, enabled = false) { Text("SEND ON AIR · LOCKED") }
                    OutlinedButton({ }, enabled = false) { Text("CALIBRATE ON AIR · LOCKED") }
                }
                Text("Profile ${snapshot.profile} · route ${snapshot.route} · ALC/SWR/power telemetry ${if (snapshot.telemetryReady) "READBACK READY" else "UNCONFIRMED"}", color = SdrMuted)
                Text(snapshot.reason, color = SdrDanger)
            }
        }
        Text("Fake accepted-profile tests cover mode inheritance, clamping, debounced persistence, plan snapshots, telemetry abort contracts and RX cleanup. Production TCI trx:true, tune:true and TX-audio frames remain unreachable.", color = SdrMuted)
    }
}

@Composable
fun SdrSettingsPanel(runtime: TciRuntimeState, rxAudio: TciRxAudioController, scanner: ReceiveOnlyScannerController,
    operational: SdrOperationalV2, workbench: AndroidSdrWorkbenchV4, localReceivers: LocalReceiverController, rf: RfObservationController,
    announcements: SpokenAnnouncementController, bandStacks: BandStackStore, debugLab: DebugSdrLab?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SdrWorkbenchSettingsPanel(workbench, operational)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) {
            Column(Modifier.padding(12.dp)) { LocalReceiverSettingsPanel(localReceivers) }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TCI / MULTI-RECEIVER / RX AUDIO", color = SdrAmber, fontWeight = FontWeight.Bold)
            Text("${runtime.snapshot.state} · ${runtime.snapshot.receivers.size} receivers · restore is always disconnected with IQ, RX audio and scanner stopped", color = SdrMuted)
            Text("RX output route starts only after an explicit operator action. TLS certificate validation is never disabled.", color = SdrHold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(rxAudio.status, color = if (rxAudio.running) SdrHealthy else SdrMuted, modifier = Modifier.weight(1f))
                if (rxAudio.running) OutlinedButton({ rxAudio.stop() }) { Text("STOP RX AUDIO") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rxAudio.settings.noiseBlanker, { rxAudio.update(rxAudio.settings.copy(noiseBlanker = it)) })
                Text("Impulse blanker")
                Checkbox(rxAudio.settings.automaticNotch, { rxAudio.update(rxAudio.settings.copy(automaticNotch = it)) })
                Text("Automatic notch")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rxAudio.settings.agc, { rxAudio.update(rxAudio.settings.copy(agc = it)) })
                Text("AGC with bounded hang")
            }
            Text("SPECTRAL NOISE REDUCTION", color = SdrMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("OFF" to 0f, "LOW" to .25f, "MEDIUM" to .55f, "HIGH" to .85f).forEach { (label, level) ->
                    FilterChip(kotlin.math.abs(rxAudio.settings.noiseReduction - level) < .05f,
                        { rxAudio.update(rxAudio.settings.copy(noiseReduction = level)) }, { Text(label) })
                }
            }
            Text("AGC hang · ${rxAudio.settings.agcHangMillis} ms", color = SdrMuted)
            Slider(rxAudio.settings.agcHangMillis.toFloat(),
                { rxAudio.update(rxAudio.settings.copy(agcHangMillis = it.toInt())) }, valueRange = 0f..2_000f)
            Text("Squelch · ${rxAudio.settings.squelchDb.toInt()} dB", color = SdrMuted)
            Slider(rxAudio.settings.squelchDb,
                { rxAudio.update(rxAudio.settings.copy(squelchDb = it)) }, valueRange = -120f..-20f)
            Text("Stereo mix · ${"%.1f".format(rxAudio.settings.stereoMix)}", color = SdrMuted)
            Slider(rxAudio.settings.stereoMix,
                { rxAudio.update(rxAudio.settings.copy(stereoMix = it)) }, valueRange = -1f..1f)
            if (BuildConfig.DEBUG && debugLab != null) OutlinedButton({ if (debugLab.active) debugLab.stop() else debugLab.start() }) { Text(if (debugLab.active) "STOP DEMO · NO RADIO" else "START DEMO · NO RADIO") }
        } }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OPERATIONAL SDR v2", color = SdrAmber, fontWeight = FontWeight.Bold)
            Text("Link ${operational.linkMode} · time-shift ${operational.timeShift.snapshot.length.seconds}s · skimmers restore STOPPED · recording ${operational.recordingState}", color = SdrMuted)
            Text("Record bounds · ${operational.recordPolicy.preRollSeconds}s pre / ${operational.recordPolicy.postRollSeconds}s post / ${operational.recordPolicy.maximumDurationSeconds}s max · ${operational.recordPolicy.dailyBytes / 1024 / 1024} MiB daily / ${operational.recordPolicy.totalBytes / 1024 / 1024} MiB total", color = SdrMuted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ReceiverLinkMode.entries.forEach { mode ->
                FilterChip(operational.linkMode == mode, { operational.updateLinkMode(mode) }, { Text(mode.name.replace('_', ' ')) })
            } }
            Text("Journal retention", color = SdrMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(7, 30, 90).forEach { days ->
                FilterChip(operational.journalRetentionDays == days, { operational.updateJournalRetention(days, operational.maximumJournalRows) }, { Text("$days days") })
            } }
            Text("Safe restore is disconnected: audio, IQ, scanner, time-shift replay, skimmers, recording, TX acceptance and pending TX plans are inactive.", color = SdrHold)
        } }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SCANNER", color = SdrAmber, fontWeight = FontWeight.Bold)
            Text("${scanner.config.mode} · ${scanner.config.startHz}–${scanner.config.endHz} Hz · ${scanner.config.resumePolicy} · ${operational.scanBanks.size} banks", color = SdrMuted)
            Text("Active scanning is never persisted or restored.", color = SdrHold)
        } }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("RF MAP / GLOBE", color = SdrAmber, fontWeight = FontWeight.Bold)
            Text("${rf.observations.size} observations · ${rf.filtered.size} visible · live, historical and empirical outlook remain separate", color = SdrMuted)
            Text("100,000-row cap · batched Canvas rendering · no WebView, tile service or automatic tune", color = SdrHold)
        } }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SPOKEN ANNOUNCEMENTS · SYSTEM TTS", color = SdrAmber, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (announcements.available) "System voice available" else "System TTS unavailable", color = if (announcements.available) SdrHealthy else SdrHold)
                Switch(announcements.settings.enabled, { announcements.update(announcements.settings.copy(enabled = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(announcements.settings.frequency, { announcements.update(announcements.settings.copy(frequency = it)) }); Text("Frequency after tuning settles") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(announcements.settings.bandMode, { announcements.update(announcements.settings.copy(bandMode = it)) }); Text("Band and mode changes") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(announcements.settings.routeLoss, { announcements.update(announcements.settings.copy(routeLoss = it)) }); Text("Critical route loss") }
            Text("Speech rate · ${"%.1f".format(announcements.settings.speechRate)}", color = SdrMuted)
            Slider(announcements.settings.speechRate, { announcements.update(announcements.settings.copy(speechRate = it)) }, valueRange = .5f..2f)
            Text("Suppressed during TX, voice macros and quiet profile. Global Stop cancels speech. TTS can never feed radio TX audio.", color = SdrHold)
        } }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrRaised)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BAND STACKS", color = SdrAmber, fontWeight = FontWeight.Bold)
            Text("Bounded depth · ${bandStacks.depth}", color = SdrMuted)
            Slider(bandStacks.depth.toFloat(), { bandStacks.updateDepth(it.toInt()) }, valueRange = 1f..12f, steps = 10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(bandStacks.perModeStacks, { bandStacks.updateOptions(it, bandStacks.cycleDirection) })
                Text("Separate stacks per mode")
                BandStackCycleDirection.entries.forEach { direction -> FilterChip(bandStacks.cycleDirection == direction,
                    { bandStacks.updateOptions(bandStacks.perModeStacks, direction) }, { Text(direction.name) }) }
            }
            Text("Band stacks store frequency, mode, filter, receiver and timestamp. Recall is explicit and never starts a connection.", color = SdrHold)
        } }
    }
}

@Composable
fun SdrHealthPanel(runtime: TciRuntimeState, transmit: TciTransmitAuthority, rxAudio: TciRxAudioController, panadapter: PanadapterController, scanner: ReceiveOnlyScannerController,
    operational: SdrOperationalV2, workbench: AndroidSdrWorkbenchV4, localReceivers: LocalReceiverController,
    rf: RfObservationController, announcements: SpokenAnnouncementController) {
    val tci = runtime.snapshot
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ANDROID SDR HEALTH", color = SdrAmber, fontWeight = FontWeight.Bold)
        SdrWorkbenchHealthPanel(workbench, tci)
        LocalReceiverHealthPanel(localReceivers)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SdrHealthCard("TCI", "${tci.state} · ${tci.protocol} ${tci.protocolVersion}", tci.ready, Modifier.weight(1f))
            SdrHealthCard("RX DSP", "${if (rxAudio.running) "LIVE" else "STOPPED"} · out ${rxAudio.outputLevelDb.toInt()} dB · GR ${rxAudio.gainReductionDb.toInt()} dB · notch ${rxAudio.notchFrequencyHz.toInt()} Hz · blank ${rxAudio.blankedImpulses} · drop ${rxAudio.droppedFrames}/${rxAudio.underflowFrames} · ${"%.1f".format(rxAudio.processingLatencyMs)} ms",
                rxAudio.clippedFraction < .02f, Modifier.weight(1f))
            SdrHealthCard("RECEIVERS", "${tci.receivers.size} · IQ drop ${tci.droppedFrames}", tci.droppedFrames == 0L, Modifier.weight(1f))
            SdrHealthCard("PANADAPTER", "${panadapter.tciDisplays.size} TCI contexts · ${panadapter.status}", panadapter.tciDisplays.isNotEmpty(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SdrHealthCard("SCANNER", "${scanner.snapshot.state} · ${scanner.snapshot.stopReason}", scanner.snapshot.state != ScannerState.ERROR, Modifier.weight(1f))
            SdrHealthCard("TIME-SHIFT", "${operational.timeShift.snapshot.playback} · ${operational.timeShift.snapshot.bufferedSeconds}s · ${operational.timeShift.snapshot.bytes / 1024} KiB", operational.timeShift.snapshot.bytes <= 32L * 1024 * 1024, Modifier.weight(1f))
            SdrHealthCard("SKIMMERS", "${operational.skimmer.markers.size} markers · ${operational.skimmer.decodeMillis} ms · ${operational.skimmer.status}", operational.skimmer.decodeMillis < 250, Modifier.weight(1f))
            SdrHealthCard("RF GLOBE", "${rf.filtered.size}/${rf.observations.size} · ${rf.filterMillis} ms", rf.observations.size <= 100_000, Modifier.weight(1f))
            SdrHealthCard("TTS", if (announcements.available) "AVAILABLE · ${if (announcements.speaking) "SPEAKING" else "IDLE"}" else "UNAVAILABLE", announcements.available, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SdrHealthCard("READBACK", "${tci.confirmedReadbacks} confirmed · ${tci.pendingReadbacks.size} pending · ${tci.failedWrites} failed", tci.failedWrites == 0L, Modifier.weight(1f))
            SdrHealthCard("AUDIO MIXER", "${rxAudio.mixer.mode} · overflow ${rxAudio.overflowByReceiver.joinToString("/")} · underflow ${rxAudio.underflowByReceiver.joinToString("/")}", rxAudio.droppedFrames == 0L, Modifier.weight(1f))
            SdrHealthCard("TX GATE", "${transmit.snapshot.acceptance} · ${transmit.snapshot.state} · ${transmit.snapshot.interlock ?: "CLEAR"}",
                transmit.snapshot.state != TciTxMachineState.RX_UNCONFIRMED, Modifier.weight(1f))
            SdrHealthCard("DERIVED STORE", "${operational.journal.size} journal · ${operational.timeShift.bookmarks.size} bookmarks", true, Modifier.weight(1f))
        }
        Text("Support metrics are bounded and sanitized. Raw IQ, raw audio, raw TCI payloads, credentials, private endpoint values and precise callsign coordinates are excluded.", color = SdrMuted)
    }
}

@Composable
fun TciProfileDialog(app: AppController, close: () -> Unit) {
    var name by remember { mutableStateOf("TCI Radio") }
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("50001") }
    var secure by remember { mutableStateOf(false) }
    var rate by remember { mutableIntStateOf(96_000) }
    var receiver by remember { mutableIntStateOf(0) }
    var maxDrive by remember { mutableIntStateOf(35) }
    var maxTuneDrive by remember { mutableIntStateOf(10) }
    var maxTuneSeconds by remember { mutableIntStateOf(10) }
    var swrAbort by remember { mutableFloatStateOf(3f) }
    var alcAbort by remember { mutableFloatStateOf(.95f) }
    var txRate by remember { mutableIntStateOf(48_000) }
    var monitor by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && host.isNotBlank() && host.none { it.isWhitespace() || it == '/' } && port.toIntOrNull() in 1..65_535
    AlertDialog(onDismissRequest = close, title = { Text("Add TCI radio") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Profile creation never connects or upgrades acceptance. New and restored profiles start UNVERIFIED.", color = SdrHold)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Display name") }, singleLine = true)
            OutlinedTextField(host, { host = it.take(253) }, label = { Text("Host") }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(secure, { secure = it }); Text(if (secure) "wss · normal certificate validation" else "ws") }
            Text("Preferred I/Q rate", color = SdrAmber); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(48_000, 96_000, 192_000, 240_000, 384_000).forEach { value ->
                FilterChip(rate == value, { rate = value }, { Text("${value / 1000} kHz") }) } }
            Text("Initial receiver", color = SdrAmber); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0..1).forEach { value ->
                FilterChip(receiver == value, { receiver = value }, { Text("RX ${value + 1}") }) } }
            Text("TCI TX SAFETY · acceptance remains UNVERIFIED", color = SdrAmber, fontWeight = FontWeight.Bold)
            Text("Maximum drive · $maxDrive%", color = SdrMuted)
            Slider(maxDrive.toFloat(), { maxDrive = it.toInt() }, valueRange = 0f..100f)
            Text("Maximum tune drive · $maxTuneDrive%", color = SdrMuted)
            Slider(maxTuneDrive.toFloat(), { maxTuneDrive = it.toInt() }, valueRange = 0f..25f)
            Text("Tune watchdog · ${maxTuneSeconds}s", color = SdrMuted)
            Slider(maxTuneSeconds.toFloat(), { maxTuneSeconds = it.toInt() }, valueRange = 1f..30f)
            Text("SWR abort · ${"%.1f".format(swrAbort)}", color = SdrMuted)
            Slider(swrAbort, { swrAbort = it }, valueRange = 1.1f..5f)
            Text("ALC abort · ${"%.2f".format(alcAbort)} · unavailable on baseline TCI", color = SdrMuted)
            Slider(alcAbort, { alcAbort = it }, valueRange = .1f..1f)
            Text("Audited TX audio rate", color = SdrMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(8_000, 12_000, 24_000, 48_000).forEach { value ->
                FilterChip(txRate == value, { txRate = value }, { Text("${value / 1000}k") }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(monitor, { monitor = it }); Text("Local TX-audio monitor") }
            Text("No Enable TX switch. PTT, TUNE and RF require sequential physical acceptance with exact device identity.", color = SdrHold)
            Text("Safe reconnect defaults OFF. RX audio and IQ never restore active.", color = SdrMuted)
        } },
        confirmButton = { Button({
            val id = "tci.${name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)}.${System.currentTimeMillis().toString(36)}"
            app.upsertTciProfile(RadioConnectionProfile(RadioProfileId(id), name.trim(), RadioBackendKind.NATIVE_TCI,
                RadioModelId("TCI:${id.take(80)}"), "TCI", "TCI control server", RadioTransportType.TCI,
                host = host.trim(), port = port.toInt(), readOnly = false, automaticSafeReconnect = false,
                secureWebSocket = secure, preferredIqSampleRate = rate, preferredInitialReceiver = receiver,
                tciTxSettings = TciTxSettings(maxDrivePercent = maxDrive, maxTuneDrivePercent = maxTuneDrive,
                    maxTuneDurationMillis = maxTuneSeconds * 1_000L, swrAbort = swrAbort.toDouble(),
                    alcAbort = alcAbort.toDouble(), txAudioRate = txRate, monitorEnabled = monitor)))
            close()
        }, enabled = valid) { Text("ADD DISCONNECTED") } },
        dismissButton = { TextButton(close) { Text("CANCEL") } })
}

@Composable private fun SdrTruthChip(label: String, healthy: Boolean) = Surface(color = (if (healthy) SdrHealthy else SdrHold).copy(alpha = .13f), shape = RoundedCornerShape(40)) {
    Text(label, color = if (healthy) SdrHealthy else SdrHold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable private fun SdrMeasure(label: String, value: String) = Column { Text(label, color = SdrMuted, fontSize = 9.sp); Text(value, color = SdrInk, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }

@Composable private fun SdrEmptyState(title: String, detail: String) = Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SdrPanel)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, color = SdrInk, fontWeight = FontWeight.Bold); Text(detail, color = SdrMuted) }
}

@Composable private fun SdrHealthCard(title: String, detail: String, healthy: Boolean, modifier: Modifier) = Card(modifier, colors = CardDefaults.cardColors(containerColor = SdrRaised)) {
    Column(Modifier.padding(9.dp)) { Text(title, color = if (healthy) SdrHealthy else SdrHold, fontWeight = FontWeight.Bold); Text(detail, color = SdrMuted, fontSize = 10.sp) }
}

private fun normalizeUiLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
