package app.shackcq.mobile

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val EqChassis = Color(0xFF111519)
private val EqPanel = Color(0xFF1B2228)
private val EqRaised = Color(0xFF283139)
private val EqInk = Color(0xFFF4F0E7)
private val EqMuted = Color(0xFFA5ADB2)
private val EqAmber = Color(0xFFE9A72B)
private val EqGreen = Color(0xFF42C77B)
private val EqYellow = Color(0xFFF4C94E)
private val EqRed = Color(0xFFE4544D)

@Composable fun EqStudioScreen(controller: EqStudioController, radio: RadioState, compact: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pendingCapturePause by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.audio.startCapture(controller.radioSnapshot, pendingCapturePause)
    }
    LaunchedEffect(controller, radio.connected, radio.model) {
        if (controller.canUseHardware) controller.readBothFromRadio()
    }
    DisposableEffect(Unit) { onDispose { controller.closeSession() } }
    controller.conflict?.let { changed ->
        AlertDialog(onDismissRequest = {}, title = { Text("Radio curve changed") },
            text = { Text("The KX3 now reads ${changed.curve}. Keep the draft and explicitly overwrite, reload this radio curve as the new baseline, or cancel.") },
            confirmButton = { Button({ scope.launch { controller.applyAndVerify(overwriteConflict = true) } }) { Text("OVERWRITE & VERIFY") } },
            dismissButton = { Row { TextButton(controller::acceptConflictAsBaseline) { Text("RELOAD BASELINE") }
                TextButton(controller::cancelConflict) { Text("CANCEL") } } })
    }
    if (saveDialog) SaveEqProfileDialog(controller, { saveDialog = false })
    BoxWithConstraints(Modifier.fillMaxSize().background(EqChassis).navigationBarsPadding().padding(10.dp)) {
        val expanded = maxWidth >= 950.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EqHeader(controller, radio, compact, onBack)
            if (expanded) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(.78f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqWorkflowPanel(controller, permission::launch) { pendingCapturePause = it }
                    EqProfilesPanel(controller, { saveDialog = true })
                }
                Column(Modifier.weight(1.25f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqCurvesPanel(controller)
                    EqBandEditor(controller)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqMeasurementPanel(controller)
                    EqAssistantPanel(controller)
                    EqApplyPanel(controller, scope)
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EqWorkflowPanel(controller, permission::launch) { pendingCapturePause = it }
                EqCurvesPanel(controller); EqBandEditor(controller); EqMeasurementPanel(controller)
                EqAssistantPanel(controller); EqApplyPanel(controller, scope); EqProfilesPanel(controller, { saveDialog = true })
            }
        }
    }
}

@Composable private fun EqHeader(controller: EqStudioController, radio: RadioState, compact: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Radio") }
        Column(Modifier.weight(1f)) {
            Text("SHACKCQ · EQ STUDIO", color = EqAmber, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text("Flightline audio bench · approximate preview, exact KX3 readback", color = EqMuted, style = MaterialTheme.typography.labelSmall)
        }
        val firmware = controller.radioSnapshot?.firmware?.removeSuffix(";")
        EqStateChip(if (radio.connected) listOfNotNull(radio.model, firmware, "CAT LIVE").joinToString(" · ") else "CAT OFFLINE", radio.connected)
        EqStateChip(controller.operation.name.replace('_', ' '), controller.operation == EqOperationState.LIVE_VERIFIED)
    }
}

@Composable private fun EqStateChip(text: String, good: Boolean) = Surface(color = (if (good) EqGreen else EqYellow).copy(alpha = .16f), shape = MaterialTheme.shapes.small) {
    Text(text, color = if (good) EqGreen else EqYellow, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
}

@Composable private fun EqPanel(title: String, content: @Composable () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = EqPanel), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = EqAmber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge); content()
    }
}

@Composable private fun EqWorkflowPanel(controller: EqStudioController, requestPermission: (String) -> Unit, setPause: (Boolean) -> Unit) = EqPanel("1 · SELECT / READ / CAPTURE") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { EqPath.entries.forEach { value ->
        FilterChip(controller.path == value, { controller.selectPath(value) }, { Text(value.name) })
    } }
    Text(controller.context.label, color = if (controller.context.writable) EqInk else EqRed, fontWeight = FontWeight.Bold)
    Text(controller.contextSource, color = EqMuted, style = MaterialTheme.typography.bodySmall)
    val scope = rememberCoroutineScope()
    Button({ scope.launch { controller.readFromRadio() } }, enabled = controller.canUseHardware && controller.operation !in setOf(EqOperationState.READING, EqOperationState.APPLYING, EqOperationState.VERIFYING),
        modifier = Modifier.heightIn(min = 48.dp)) { Text("READ FROM RADIO") }
    HorizontalDivider()
    Text("RECORD ONCE · AUDITION MANY", color = EqInk, fontWeight = FontWeight.SemiBold)
    EqCaptureSource.entries.forEach { value -> FilterChip(controller.audio.source == value, { controller.audio.source = value }, { Text(value.label) }) }
    FilterChip(controller.audio.useBuiltInReference, { controller.audio.useBuiltInReference = !controller.audio.useBuiltInReference },
        { Text("BUILT-IN MIC · REFERENCE ONLY") })
    Text(controller.audio.status, color = if (controller.audio.status.contains("failed", true)) EqRed else EqMuted, style = MaterialTheme.typography.bodySmall)
    val capturing = controller.audio.state is EqAudioState.Capturing
    if (capturing) Button(controller.audio::stop, colors = ButtonDefaults.buttonColors(containerColor = EqRed), modifier = Modifier.heightIn(min = 48.dp)) { Text("STOP CAPTURE") }
    else {
        val monitorBusy = controller.audioStatusMonitorBusy()
        Button({ setPause(monitorBusy); requestPermission(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (monitorBusy) "PAUSE AND USE FOR EQ" else "CAPTURE 10–15 SECONDS")
        }
    }
}

private fun EqStudioController.audioStatusMonitorBusy(): Boolean = audio.routeOwner == "MONITOR"

@Composable private fun EqCurvesPanel(controller: EqStudioController) = EqPanel("2 · RADIO / DRAFT / PROFILE") {
    EqCurveRow("RADIO", controller.radioSnapshot?.curve, EqGreen, if (controller.radioSnapshot == null) "NOT READ" else "VERIFIED")
    EqCurveRow("DRAFT", controller.draft, EqYellow, "${controller.dirtyBands} changed")
    EqCurveRow("PROFILE", controller.loadedProfile?.curve, EqMuted, controller.loadedProfile?.name ?: "NONE")
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        OutlinedButton(controller::undoDraft) { Text("UNDO DRAFT") }
        OutlinedButton(controller::restoreRadioCurve) { Text("RESTORE RADIO") }
        OutlinedButton(controller::flatDraft) { Text("FLAT DRAFT") }
    }
    Text("FLAT DRAFT is local only; it never invokes the KX3 CLR/reset switch.", color = EqMuted, style = MaterialTheme.typography.labelSmall)
}

@Composable private fun EqCurveRow(label: String, curve: EqCurve?, color: Color, suffix: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontWeight = FontWeight.Black, modifier = Modifier.width(58.dp))
        Text(curve?.values?.joinToString("  ") { "%+03d".format(it) } ?: "—  —  —  —  —  —  —  —",
            color = if (curve == null) EqMuted else EqInk, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(suffix, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun EqBandEditor(controller: EqStudioController) = EqPanel("3 · LOCAL DRAFT · dB") {
    val curve = controller.draft ?: EqCurve.FLAT
    EQ_FREQUENCIES_HZ.indices.forEach { band ->
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = "${EQ_FREQUENCIES_HZ[band]} hertz, ${curve[band]} decibels" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (EQ_FREQUENCIES_HZ[band] >= 1000) "${EQ_FREQUENCIES_HZ[band] / 1000f}k" else "${EQ_FREQUENCIES_HZ[band]}",
                color = EqAmber, fontFamily = FontFamily.Monospace, modifier = Modifier.width(46.dp))
            OutlinedButton({ controller.setBand(band, curve[band] - 1) }, enabled = curve[band] > -16, modifier = Modifier.size(48.dp)) { Text("−") }
            Slider(curve[band].toFloat(), { controller.setBand(band, it.toInt()) }, valueRange = -16f..16f, steps = 31, modifier = Modifier.weight(1f))
            OutlinedButton({ controller.setBand(band, curve[band] + 1) }, enabled = curve[band] < 16, modifier = Modifier.size(48.dp)) { Text("+") }
            Text("%+03d".format(curve[band]), color = EqInk, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
        }
    }
}

@Composable private fun EqMeasurementPanel(controller: EqStudioController) = EqPanel("4 · MEASURE / COMPARE") {
    val capture = controller.audio.capture
    val preview = controller.audio.preview
    if (capture == null) Text("No sample. Waveform and spectra stay empty until real PCM is captured.", color = EqMuted)
    else {
        Text("${capture.source.label} · ${capture.inputDevice} · ${capture.sampleRate / 1000f} kHz ${capture.channel} · ${capture.processing.label}", color = EqInk)
        EqPlot(preview?.waveform ?: emptyList(), preview?.beforeSpectrum ?: emptyList(), preview?.afterSpectrum ?: emptyList(), preview?.responseDb ?: emptyList())
        val m = capture.metrics
        Text("PEAK ${"%.1f".format(m.peakDbfs)} dBFS · ACTIVE RMS ${"%.1f".format(m.activeRmsDbfs)} · CREST ${"%.1f".format(m.crestDb)} dB · NOISE ${"%.1f".format(m.noiseFloorDbfs)} dBFS · CLIPS ${m.clippedSamples}",
            color = if (m.qualityLabel == "VALID REFERENCE") EqGreen else EqRed, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button({ controller.audio.rebuildPreview(controller.draft ?: EqCurve.FLAT) }) { Text("BUILD PREVIEW") }
            OutlinedButton({ controller.audio.play(false) }, enabled = preview != null) { Text(if (controller.audio.blind) if (controller.audio.blindAfterIsA) "B" else "A" else "BEFORE") }
            OutlinedButton({ controller.audio.play(true) }, enabled = preview != null) { Text(if (controller.audio.blind) if (controller.audio.blindAfterIsA) "A" else "B" else "AFTER") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(controller.audio.loudnessMatched, { controller.audio.loudnessMatched = !controller.audio.loudnessMatched; controller.audio.rebuildPreview(controller.draft ?: EqCurve.FLAT) }, { Text("MATCHED LOUDNESS") })
            FilterChip(controller.audio.blind, controller.audio::toggleBlind, { Text(if (controller.audio.blind) "REVEAL A/B" else "BLIND A/B") })
        }
        preview?.let { if (it.safetyReductionDb < 0) Text("Static preview safety reduction ${"%.1f".format(it.safetyReductionDb)} dB", color = EqYellow) }
        Text("Approximate KX3-band preview — Elecraft’s internal filter topology is not documented.", color = EqYellow, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun EqPlot(waveform: List<Float>, before: List<Float>, after: List<Float>, response: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(190.dp).background(Color(0xFF201708))) {
        drawLine(EqMuted.copy(alpha = .25f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        fun path(values: List<Float>, map: (Float) -> Float): Path {
            val p = Path(); values.forEachIndexed { index, value ->
                val x = index * size.width / (values.size - 1).coerceAtLeast(1); val y = map(value).coerceIn(0f, size.height)
                if (index == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }; return p
        }
        if (waveform.isNotEmpty()) drawPath(path(waveform) { size.height * .48f - it * size.height * .35f }, EqMuted, style = Stroke(1.5f))
        if (before.isNotEmpty()) drawPath(path(before) { size.height * .78f - (it + 80f) / 80f * size.height * .55f }, EqGreen, style = Stroke(2f))
        if (after.isNotEmpty()) drawPath(path(after) { size.height * .78f - (it + 80f) / 80f * size.height * .55f }, EqYellow, style = Stroke(2f))
        if (response.isNotEmpty()) drawPath(path(response) { size.height * .48f - it / 32f * size.height * .45f }, EqAmber, style = Stroke(2.5f))
    }
}

@Composable private fun EqAssistantPanel(controller: EqStudioController) = EqPanel("5 · STARTING-POINT ASSISTANT") {
    Text("Deterministic, conservative, and explainable. Suggestions only change the local draft.", color = EqMuted)
    val intents = when (controller.context) {
        EqContext.TX_SSB -> listOf(EqIntent.NATURAL, EqIntent.CLEAR_SSB, EqIntent.DX_PILEUP)
        EqContext.TX_WIDEBAND -> listOf(EqIntent.NATURAL, EqIntent.WIDEBAND_FIDELITY, EqIntent.CLEAR_VOICE)
        EqContext.RX_CW -> listOf(EqIntent.CW_FOCUS)
        else -> listOf(EqIntent.NATURAL, EqIntent.SPEECH_CLARITY)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { intents.forEach { intent ->
        OutlinedButton({ controller.suggest(intent, 600) }) { Text(intent.label) }
    } }
    controller.lastSuggestion?.let { suggestion ->
        Text("${suggestion.confidence} CONFIDENCE", color = EqYellow, fontWeight = FontWeight.Bold)
        suggestion.rationale.forEach { Text("• $it", color = EqMuted, style = MaterialTheme.typography.bodySmall) }
    }
    OutlinedButton(controller::makeHeadroom) { Text("MAKE HEADROOM") }
}

@Composable private fun EqProfilesPanel(controller: EqStudioController, save: () -> Unit) = EqPanel("LOCAL EQ PROFILES") {
    controller.profiles.profiles.filter { it.path == controller.path }.forEach { profile ->
        OutlinedButton({ controller.loadProfile(profile) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(profile.name); Text("${profile.context.label} · ${profile.curve}", color = EqMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }
    Button(save, enabled = controller.radioSnapshot != null && controller.draft != null) { Text("SAVE DRAFT AS PROFILE") }
    Text("Profiles are app-private templates. Loading never changes the radio; audio clips are never stored.", color = EqMuted, style = MaterialTheme.typography.labelSmall)
}

@Composable private fun EqApplyPanel(controller: EqStudioController, scope: kotlinx.coroutines.CoroutineScope) = EqPanel("6 · APPLY / VERIFY") {
    Text(controller.message, color = if (controller.operation == EqOperationState.FAILED) EqRed else EqInk)
    Button({ scope.launch { controller.applyAndVerify() } }, enabled = controller.canUseHardware && controller.draft != null && controller.radioSnapshot != null && controller.dirtyBands > 0,
        colors = ButtonDefaults.buttonColors(containerColor = EqAmber), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("APPLY TO RADIO & VERIFY", color = Color(0xFF201708), fontWeight = FontWeight.Black) }
    Text("Preflight: KX3 · TQ0 · menu closed · same split-aware context · unchanged baseline. No PTT, TX, TUNE, DVR, mode change, or blind retry.", color = EqMuted, style = MaterialTheme.typography.labelSmall)
    if (controller.trace.isNotEmpty()) {
        Text("SANITISED SESSION TRACE", color = EqAmber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        controller.trace.takeLast(12).forEach { Text(it, color = EqMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
    }
}

@Composable private fun SaveEqProfileDialog(controller: EqStudioController, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }; var chain by remember { mutableStateOf(controller.audio.capture?.inputDevice.orEmpty()) }; var notes by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Save local EQ profile") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(48) }, label = { Text("Profile name") })
        OutlinedTextField(chain, { chain = it.take(80) }, label = { Text("Microphone / audio chain (optional)") })
        OutlinedTextField(notes, { notes = it.take(240) }, label = { Text("Notes (optional)") })
    } }, confirmButton = { Button({
        val snapshot = controller.radioSnapshot ?: return@Button
        controller.profiles.create(name, snapshot, controller.draft ?: snapshot.curve, controller.lastIntent, chain, notes); dismiss()
    }, enabled = name.isNotBlank()) { Text("SAVE") } }, dismissButton = { TextButton(dismiss) { Text("CANCEL") } })
}
