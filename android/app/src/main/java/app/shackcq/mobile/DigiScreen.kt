package app.shackcq.mobile

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.content.Intent
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.pow

/*
THESIS: Nexus's cockpit model on Android: scope, working panes, and an immovable TX dock.
OWN-WORLD: deep navy instrument surfaces, cyan navigation, green RX, amber sequence, red stop.
STORY: choose a cockpit, monitor receive, compose one transmission, arm deliberately, send,
and retain an immediate path back to RX.
FIRST VIEWPORT: compact radio truth and mode rail, bounded scope, mode-specific panes, fixed dock.
FORM: Nexus cockpit translated to adaptive Compose; panes reflow by measured width and never clip.
*/

private val NexusBg = Color(0xFF061016)
private val NexusPanel = Color(0xFF0B171E)
private val NexusRaised = Color(0xFF10242D)
private val NexusInset = Color(0xFF040B0F)
private val NexusLine = Color(0xFF29424D)
private val NexusText = Color(0xFFEAF4F6)
private val NexusMuted = Color(0xFF93AAB4)
private val NexusCyan = Color(0xFF35D3E7)
private val NexusGreen = Color(0xFF43DD8B)
private val NexusAmber = Color(0xFFFFB347)
private val NexusRed = Color(0xFFFF5B66)
private val NexusBlue = Color(0xFF65A9FF)
private val CockpitShape = RoundedCornerShape(6.dp)

@Composable
fun DigiScreen(controller: DigiController, radio: RadioState, compact: Boolean) {
    var setup by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(NexusBg).navigationBarsPadding()) {
        val wide = !compact && maxWidth >= 900.dp
        Column(Modifier.fillMaxSize()) {
            DigiCockpitHeader(controller, radio) { setup = !setup }
            DigitalModeRail(controller)
            HorizontalDivider(color = NexusLine)
            if (setup) DigiSetupPanel(controller, radio, Modifier.weight(1f)) else when (controller.mode) {
                DigiMode.SSTV -> SstvCockpit(controller, wide, Modifier.weight(1f))
                DigiMode.CW, DigiMode.RTTY, DigiMode.PSK31 ->
                    StreamCockpit(controller, wide, Modifier.weight(1f))
                else -> WeakSignalCockpit(controller, wide, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DigiCockpitHeader(controller: DigiController, radio: RadioState, toggleSetup: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().background(NexusPanel).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(min = 150.dp)) {
            Text("DIGITAL", color = NexusCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Text(controller.mode.label, color = NexusText, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        HeaderTruth("RADIO", if (radio.connected) "${radio.model} · ${radio.frequencyText}" else "OFFLINE", if (radio.connected) NexusGreen else NexusRed)
        HeaderTruth("RX", if (controller.rxActive) "LIVE" else "STOPPED", if (controller.rxActive) NexusGreen else NexusMuted)
        HeaderTruth("AUDIO", controller.audioHealth.state.name, when (controller.audioHealth.state) {
            DigiAudioHealthState.LIVE -> NexusGreen
            DigiAudioHealthState.CLIPPING, DigiAudioHealthState.ROUTE_LOST, DigiAudioHealthState.ERROR -> NexusRed
            else -> NexusAmber
        })
        HeaderTruth("TX", when (controller.txPhase) {
            DigiTxPhase.PTT_CONFIRMED -> "ON AIR"
            DigiTxPhase.SEQUENCING -> "QUEUED"
            DigiTxPhase.RX_UNCONFIRMED -> "RX UNCONFIRMED"
            DigiTxPhase.SAFE -> if (controller.txArmed) "ARMED" else "SAFE"
        }, when {
            controller.txPhase in setOf(DigiTxPhase.PTT_CONFIRMED, DigiTxPhase.RX_UNCONFIRMED) -> NexusRed
            controller.txActive || controller.txArmed -> NexusAmber
            else -> NexusMuted
        })
        Spacer(Modifier.weight(1f))
        CockpitButton("SETUP", NexusBlue, filled = false, onClick = toggleSetup)
        CockpitButton(if (controller.rxActive) "STOP RX" else "START RX", if (controller.rxActive) NexusAmber else NexusGreen) {
            if (controller.rxActive) controller.stopRx() else controller.startRx()
        }
        CockpitButton("CLEAR", NexusBlue, filled = false) { controller.clear() }
        CockpitButton("STOP TX", NexusRed, enabled = controller.txActive || controller.txArmed) { controller.haltTx() }
    }
}

@Composable
private fun HeaderTruth(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Column {
            Text(label, color = NexusMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = NexusText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
private fun DigitalModeRail(controller: DigiController) {
    val weak = controller.mode.isSlotted
    Row(
        Modifier.fillMaxWidth().background(NexusInset).horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (DigiMode.entries.any { it.isSlotted && controller.isModeVisible(it) }) NexusModeTab("FT", weak) { controller.selectMode(DigiMode.FT8) }
        if (controller.isModeVisible(DigiMode.CW)) NexusModeTab("CW", controller.mode == DigiMode.CW) { controller.selectMode(DigiMode.CW) }
        if (controller.isModeVisible(DigiMode.RTTY)) NexusModeTab("RTTY", controller.mode == DigiMode.RTTY) { controller.selectMode(DigiMode.RTTY) }
        if (controller.isModeVisible(DigiMode.PSK31)) NexusModeTab("PSK", controller.mode == DigiMode.PSK31) { controller.selectMode(DigiMode.PSK31) }
        if (controller.isModeVisible(DigiMode.SSTV)) NexusModeTab("SSTV", controller.mode == DigiMode.SSTV) { controller.selectMode(DigiMode.SSTV) }
        Spacer(Modifier.width(6.dp))
        Text("NEXUS COCKPIT", color = NexusMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun NexusModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Black) },
        modifier = Modifier.heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = NexusPanel,
            labelColor = NexusMuted,
            selectedContainerColor = NexusCyan,
            selectedLabelColor = NexusBg,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = NexusLine,
            selectedBorderColor = NexusCyan,
        ),
    )
}

@Composable
private fun WeakSignalCockpit(controller: DigiController, wide: Boolean, modifier: Modifier) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WeakModePicker(controller)
        SignalScope(controller)
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecodePane(controller, Modifier.weight(1.65f).fillMaxHeight())
                WeakTxPane(controller, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecodePane(controller, Modifier.fillMaxWidth().requiredHeightIn(min = 280.dp))
                WeakTxPane(controller, Modifier.fillMaxWidth().requiredHeightIn(min = 300.dp))
            }
        }
        WeakSignalDock(controller)
    }
}

@Composable
private fun WeakModePicker(controller: DigiController) {
    val families = DigiModeFamilies.filter { it.isSlotted && controller.isModeVisible(it) }
    Column(Modifier.fillMaxWidth().background(NexusPanel, CockpitShape).border(1.dp, NexusLine, CockpitShape).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            families.forEach { value ->
                FilterChip(
                    selected = controller.mode.family == value.family,
                    onClick = { controller.selectMode(value) },
                    label = { Text(value.family, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NexusBlue, selectedLabelColor = NexusBg),
                )
            }
        }
    val variants = DigiMode.entries.filter { it.family == controller.mode.family && controller.isModeVisible(it) }
        if (variants.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                variants.forEach { value ->
                    FilterChip(
                        selected = controller.mode == value,
                        onClick = { controller.selectMode(value) },
                        label = { Text(value.label.removePrefix("${value.family}-").removePrefix(value.family).ifBlank { value.label }) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NexusAmber, selectedLabelColor = NexusBg),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalScope(controller: DigiController) {
    Column(Modifier.fillMaxWidth().background(NexusInset, CockpitShape).border(1.dp, NexusLine, CockpitShape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE AUDIO WATERFALL", color = NexusCyan, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text(
                if (controller.mode.isSlotted) "${(controller.slotProgress * 100).roundToInt()}% · ${controller.mode.slotMillis / 1_000.0}s UTC" else controller.status,
                color = if (controller.rxActive) NexusGreen else NexusMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val display = controller.settings.waterfall
        val rows = controller.waterfallRows
        val spectrum = controller.spectrumRow
        Canvas(Modifier.fillMaxWidth().height(148.dp).padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Digi audio waterfall ${display.lowHz.roundToInt()} to ${display.highHz.roundToInt()} hertz. Tap to set receive audio; long press to set transmit audio." }
            .pointerInput(display.lowHz, display.highHz, controller.mode) {
                detectTapGestures(
                    onTap = { controller.waterfallTap(it.x / size.width) },
                    onLongPress = { controller.waterfallTap(it.x / size.width, explicitTx = true) },
                )
            }) {
            drawRect(Color.Black)
            val visible = rows.takeLast(80)
            val rowHeight = if (visible.isEmpty()) 0f else size.height * .72f / visible.size
            visible.forEachIndexed { y, row ->
                val binWidth = size.width / row.size.coerceAtLeast(1)
                row.forEachIndexed { x, raw ->
                    val level = (((raw - display.floor) * display.gain).coerceIn(0f, 1f)).pow(1f / display.contrast)
                    drawRect(waterfallColor(level), Offset(x * binWidth, y * rowHeight), Size(binWidth + 1f, rowHeight + 1f))
                }
            }
            val spectrumTop = size.height * .76f
            drawLine(NexusLine, Offset(0f, spectrumTop), Offset(size.width, spectrumTop), 1f)
            if (spectrum.size > 1) for (index in 0 until spectrum.lastIndex) {
                val x1 = size.width * index / (spectrum.size - 1)
                val x2 = size.width * (index + 1) / (spectrum.size - 1)
                drawLine(NexusCyan, Offset(x1, size.height - spectrum[index] * (size.height - spectrumTop)), Offset(x2, size.height - spectrum[index + 1] * (size.height - spectrumTop)), 1.5f)
            }
            val rxX = ((controller.rxAudioHz - display.lowHz) / (display.highHz - display.lowHz)).coerceIn(0f, 1f) * size.width
            val txX = ((controller.txAudioHz - display.lowHz) / (display.highHz - display.lowHz)).coerceIn(0f, 1f) * size.width
            drawLine(NexusGreen, Offset(rxX, 0f), Offset(rxX, size.height), 2f)
            drawLine(NexusRed, Offset(txX, 0f), Offset(txX, size.height), 2f)
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("STD" to 3_000f, "FULL" to 4_000f).forEach { (label, high) ->
                CockpitButton(label, NexusBlue, filled = false) { controller.updateWaterfall(display.copy(lowHz = 0f, highHz = high)) }
            }
            DigiAnalysisWindow.entries.forEach { window ->
                CockpitButton(window.name, if (display.window == window) NexusCyan else NexusMuted, filled = false) { controller.updateWaterfall(display.copy(window = window)) }
            }
            CockpitButton(if (controller.waterfallState == DigiWaterfallState.LIVE) "PAUSE" else "SNAP LIVE", NexusAmber, filled = false) {
                    if (controller.waterfallState == DigiWaterfallState.LIVE) controller.updateWaterfallState(DigiWaterfallState.PAUSED) else controller.snapWaterfallLive()
            }
        }
    }
}

private fun waterfallColor(level: Float): Color = when {
    level < .2f -> Color(0xFF071B35)
    level < .4f -> Color(0xFF075A7A)
    level < .6f -> Color(0xFF13B89A)
    level < .8f -> Color(0xFFF5D547)
    else -> Color(0xFFFF5B66)
}

@Composable
private fun DecodePane(controller: DigiController, modifier: Modifier) {
    val recordingPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(controller::decodeRecording)
    }
    var historyMode by remember { mutableStateOf("BAND ACTIVITY") }
    var search by remember { mutableStateOf("") }
    var neededOnly by remember { mutableStateOf(false) }
    var hideWorked by remember { mutableStateOf(false) }
    var cqOnly by remember { mutableStateOf(false) }
    CockpitPane("DECODE & ROSTER", if (controller.rxActive) "LIVE" else "RX STOPPED", NexusGreen, modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitButton("OPEN WAV", NexusBlue, filled = false, modifier = Modifier.weight(1f)) { recordingPicker.launch("audio/*") }
            CockpitButton("RE-DECODE", NexusBlue, filled = false) { controller.redecodeLastSlot() }
            CockpitButton("ERASE", NexusMuted, filled = false) { controller.clear() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DigiOperatingView.entries.forEach { view -> CockpitButton(view.name, if (controller.operatingView == view) NexusCyan else NexusMuted, filled = false, modifier = Modifier.weight(1f)) { controller.selectOperatingView(view) } }
        }
        HorizontalDivider(color = NexusLine)
        val baseRows = controller.decodeHistory
        val shown = if (controller.operatingView == DigiOperatingView.CLASSIC) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("BAND ACTIVITY", "RX FREQUENCY", "SESSION HISTORY").forEach { value ->
                    CockpitButton(value, if (historyMode == value) NexusBlue else NexusMuted, filled = false) { historyMode = value }
                }
            }
            if (historyMode == "RX FREQUENCY") baseRows.filter { kotlin.math.abs(it.audioHz - controller.rxAudioHz) <= 25f } else baseRows
        } else {
            NexusTextField(search, { search = it.take(18) }, "ROSTER SEARCH (* wildcard)", true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(neededOnly, { neededOnly = !neededOnly }, { Text("NEEDED ONLY") })
                FilterChip(hideWorked, { hideWorked = !hideWorked }, { Text("HIDE WORKED") })
                FilterChip(cqOnly, { cqOnly = !cqOnly }, { Text("CQ ONLY") })
            }
            val regex = search.takeIf(String::isNotBlank)?.replace("*", ".*")?.let { runCatching { Regex("^$it$", RegexOption.IGNORE_CASE) }.getOrNull() }
            baseRows.filter { it.callsign.isNotBlank() }.groupBy { it.callsign }.values.mapNotNull { rows -> rows.maxByOrNull(DigiDecodeEvent::epoch) }
                .filter { (!neededOnly || it.needs.isNotEmpty()) && (!hideWorked || !it.worked) && (!cqOnly || it.text.startsWith("CQ ")) && (regex == null || regex.matches(it.callsign)) }
                .sortedWith(compareByDescending<DigiDecodeEvent> { it.needs.size + if (it.watchlisted) 1 else 0 }.thenByDescending { it.epoch })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            TableHead("SNR", Modifier.width(48.dp)); TableHead("AUDIO", Modifier.width(62.dp)); TableHead("CALL / MESSAGE", Modifier.weight(1f))
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(NexusInset), contentAlignment = Alignment.Center) {
                Text(
                    if (controller.rxActive) "Listening for ${controller.mode.label}\nDecoded messages appear here." else "Start RX or open a reference WAV.",
                    color = NexusMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).background(NexusInset)) {
                shown.asReversed().forEachIndexed { index, row ->
                    Row(Modifier.fillMaxWidth().clickable { controller.selectDecode(row) }
                        .background(if (controller.selectedDecode?.id == row.id) NexusRaised else if (index % 2 == 0) NexusInset else NexusPanel)
                        .padding(horizontal = 4.dp, vertical = 8.dp)) {
                        DecodeCell("%+.0f".format(row.snr), Modifier.width(48.dp), if (row.snr >= 0) NexusGreen else NexusText)
                        DecodeCell("%.0f".format(row.audioHz), Modifier.width(62.dp), NexusCyan)
                        Column(Modifier.weight(1f)) {
                            DecodeCell(listOf(row.callsign, row.country, row.grid).filter(String::isNotBlank).joinToString(" · ").ifBlank { row.text }, Modifier.fillMaxWidth(), NexusText)
                            Text(listOf(row.text, row.needs.joinToString(" · "), "WORKED".takeIf { row.worked }, "CONFIRMED".takeIf { row.confirmed }, "WATCH".takeIf { row.watchlisted }).filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = if (row.needs.isNotEmpty()) NexusAmber else NexusMuted, fontSize = 10.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
        controller.selectedDecode?.let {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CockpitButton("CALL", NexusGreen, filled = false) { controller.callSelected() }
                CockpitButton("LOG HISTORY", NexusBlue, filled = false) { controller.openSelectedLogbook() }
                CockpitButton("CALLBOOK", NexusBlue, filled = false) { controller.openSelectedCallbook() }
                CockpitButton("DX DETAILS", NexusBlue, filled = false) { controller.openSelectedDx() }
                CockpitButton("QSO DRAFT", NexusAmber, filled = false) { controller.prepareSelectedDraft() }
            }
        }
    }
}

@Composable
private fun WeakTxPane(controller: DigiController, modifier: Modifier) {
    CockpitPane("TX MESSAGES", if (controller.txArmed) "ARMED ONCE" else "SAFE", NexusAmber, modifier) {
        if (controller.mode != DigiMode.WSPR) {
            NexusTextField(controller.dxCall, controller::updateDxCall, "DX CALL", singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("CQ", "TX 1", "TX 2", "TX 3", "RR73", "73").forEachIndexed { index, label ->
                    CockpitButton(label, if (index == 0) NexusGreen else NexusBlue, filled = false) { controller.applyStandardMessage(index) }
                }
            }
        }
        NexusTextField(
            controller.txText,
            controller::updateTxText,
            if (controller.mode == DigiMode.WSPR) "CALL GRID DBM" else "NEXT MESSAGE",
            singleLine = controller.mode.isSlotted,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = !controller.mode.isSlotted),
        )
        if (controller.lastTxText.isNotBlank()) {
            Text("SENT · ${controller.lastTxText}", color = NexusMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
        Text("QSO · ${controller.ftSequence.state.name} · ${controller.ftSequence.displayCall.ifBlank { "NO LOCK" }}", color = NexusCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        controller.pendingDraft?.let { draft ->
            Text("DRAFT · ${draft.callsign} · ${draft.mode}${draft.submode.takeIf(String::isNotBlank)?.let { "/$it" }.orEmpty()} · ${draft.sentReport}/${draft.receivedReport}", color = NexusAmber, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CockpitButton("REVIEW & LOG", NexusGreen, filled = false) { controller.logPendingDraft() }
            }
        }
        if (controller.lastLoggedQsoId.isNotBlank()) CockpitButton("UNDO LAST LOG", NexusAmber, filled = false) { controller.undoLastLog() }
        Text(controller.status, color = if (controller.status.contains("UNCONFIRMED")) NexusRed else NexusMuted, fontSize = 11.sp)
    }
}

@Composable
private fun WeakSignalDock(controller: DigiController) {
    Row(
        Modifier.fillMaxWidth().background(NexusRaised, CockpitShape).border(1.dp, NexusLine, CockpitShape)
            .horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CockpitButton("CALL CQ", NexusGreen, filled = false, enabled = controller.mode in setOf(DigiMode.FT8, DigiMode.FT4) && controller.txEnabled) { controller.startCq() }
        if (controller.mode != DigiMode.WSPR) CockpitButton("CALL SELECTED", NexusBlue, filled = false, enabled = controller.mode in setOf(DigiMode.FT8, DigiMode.FT4) && controller.txEnabled) { controller.callSelected() }
        if (controller.mode in setOf(DigiMode.FT8, DigiMode.FT4)) {
            val sequenceActive = controller.ftSequence.state !in setOf(FtExchangeState.IDLE, FtExchangeState.STOPPED, FtExchangeState.COMPLETE, FtExchangeState.FAILED)
            val parity = if (controller.ftSequence.role == FtExchangeRole.SEARCH_AND_POUNCE && sequenceActive) controller.ftSequence.operatorTxParity else controller.effectiveFtTxParity
            CockpitButton("TX FIRST / EVEN", if (parity == 0) NexusCyan else NexusMuted, filled = parity == 0, enabled = !sequenceActive) { controller.updateFtTxParity(0) }
            CockpitButton("TX SECOND / ODD", if (parity == 1) NexusCyan else NexusMuted, filled = parity == 1, enabled = !sequenceActive) { controller.updateFtTxParity(1) }
            CockpitButton(if (controller.settings.ftAutoSequence) "AUTO SEQUENCE ON" else "AUTO SEQUENCE OFF",
                if (controller.settings.ftAutoSequence) NexusGreen else NexusMuted, filled = false) {
                controller.updateFtAutoSequence(!controller.settings.ftAutoSequence)
            }
            CockpitButton(if (controller.settings.ftAutoCq) "AUTO CQ ON" else "AUTO CQ OFF", if (controller.settings.ftAutoCq) NexusGreen else NexusMuted,
                filled = false, enabled = controller.settings.ftAutoSequence) {
                controller.updateFtAutomation(!controller.settings.ftAutoCq, controller.settings.ftAutoCqLimit, controller.settings.ftRetryLimit)
            }
            CockpitButton("CQ LIMIT ${controller.settings.ftAutoCqLimit}", NexusBlue, filled = false) {
                controller.updateFtAutomation(controller.settings.ftAutoCq, controller.settings.ftAutoCqLimit.mod(20) + 1, controller.settings.ftRetryLimit)
            }
            CockpitButton("RETRIES ${controller.settings.ftRetryLimit}", NexusBlue, filled = false) {
                controller.updateFtAutomation(controller.settings.ftAutoCq, controller.settings.ftAutoCqLimit, (controller.settings.ftRetryLimit + 1).mod(11))
            }
            if (controller.txSlotCountdownMillis > 0) Text("${"%.1f".format(controller.txSlotCountdownMillis / 1_000.0)}s", color = NexusAmber, fontFamily = FontFamily.Monospace)
        }
        VerticalDivider(Modifier.height(34.dp), color = NexusLine)
            CockpitButton(if (controller.txEnabled) "TX ENABLED" else "ENABLE TX", if (controller.txEnabled) NexusRed else NexusAmber,
                enabled = !controller.issSessionEnabled && controller.txPhase != DigiTxPhase.RX_UNCONFIRMED) { controller.updateTxEnabled(!controller.txEnabled) }
        CockpitButton(if (controller.txArmed) "TX ARMED" else "ARM TX", NexusAmber, enabled = controller.txPhase != DigiTxPhase.RX_UNCONFIRMED) { controller.arm() }
        CockpitButton("SEND ${controller.mode.label}", NexusGreen, enabled = controller.txEnabled && controller.txArmed && !controller.txActive &&
            controller.capability.sendEnabled && controller.txPhase != DigiTxPhase.RX_UNCONFIRMED) { controller.send() }
        CockpitButton("STOP TX", NexusRed, enabled = controller.txActive || controller.txArmed) { controller.haltTx() }
        if (controller.txPhase == DigiTxPhase.RX_UNCONFIRMED) {
            CockpitButton("REQUEST RX & RECHECK", NexusRed, filled = false) { controller.requestRxAndRecheck() }
        }
        Text(
            when (controller.txPhase) {
                DigiTxPhase.PTT_CONFIRMED -> "ON AIR"
                DigiTxPhase.SEQUENCING -> "WAITING FOR UTC SLOT"
                DigiTxPhase.RX_UNCONFIRMED -> "RX UNCONFIRMED · RECOVERY REQUIRED"
                DigiTxPhase.SAFE -> if (controller.txArmed) "ONE TRANSMISSION ARMED" else "RECEIVE ONLY"
            },
            color = if (controller.txActive) NexusRed else if (controller.txArmed) NexusAmber else NexusMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun StreamCockpit(controller: DigiController, wide: Boolean, modifier: Modifier) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StreamControls(controller)
        SignalScope(controller)
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranscriptPane(controller, Modifier.weight(1.55f).fillMaxHeight())
                StreamTxPane(controller, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TranscriptPane(controller, Modifier.fillMaxWidth().requiredHeightIn(min = 260.dp))
                StreamTxPane(controller, Modifier.fillMaxWidth().requiredHeightIn(min = 290.dp))
            }
        }
        StreamDock(controller)
    }
}

@Composable
private fun StreamControls(controller: DigiController) {
    Column(Modifier.fillMaxWidth().background(NexusPanel, CockpitShape).border(1.dp, NexusLine, CockpitShape).padding(8.dp)) {
        when (controller.mode) {
            DigiMode.CW -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PITCH · ${controller.cwPitchHz.roundToInt()} HZ", color = NexusCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Slider(controller.cwPitchHz, controller::updateCwPitch, valueRange = 400f..1_000f, modifier = Modifier.semantics { contentDescription = "CW pitch" })
                }
                Column(Modifier.weight(1f)) {
                    Text("KEYER · ${controller.cwWpm} WPM", color = NexusAmber, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Slider(controller.cwWpm.toFloat(), { controller.updateCwWpm(it.roundToInt()) }, valueRange = 8f..45f, steps = 36, modifier = Modifier.semantics { contentDescription = "CW speed" })
                }
            }
            DigiMode.RTTY -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BAUDOT · 45.45 BAUD · 170 HZ SHIFT", color = NexusCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text("CENTER ${controller.rxAudioHz.roundToInt()} Hz · AFC ${"%+.1f".format(controller.rttyAfcHz)} Hz · ${if (controller.rttyAfcLocked) "LOCKED" else "SEARCHING"}", color = if (controller.rttyAfcLocked) NexusGreen else NexusAmber, fontSize = 11.sp)
                }
                Text("REVERSE", color = NexusMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Switch(controller.rttyReverse, controller::updateRttyReverse)
                CockpitButton("RE-ACQUIRE", NexusBlue, filled = false) { controller.reacquire() }
            }
            DigiMode.PSK31 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BPSK31 · 31.25 BAUD · VARICODE", color = NexusCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text("CARRIER ${"%.1f".format(controller.pskCarrierHz)} HZ", color = NexusGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                CockpitButton("RE-ACQUIRE", NexusBlue, filled = false) { controller.reacquire() }
            }
            else -> Unit
        }
    }
}

@Composable
private fun TranscriptPane(controller: DigiController, modifier: Modifier) {
    val recordingPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(controller::decodeRecording) }
    CockpitPane("RECEIVE", if (controller.rxActive) "STREAMING" else "STOPPED", NexusGreen, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitButton("OPEN WAV", NexusBlue, filled = false, modifier = Modifier.weight(1f)) { recordingPicker.launch("audio/*") }
            CockpitButton("ERASE", NexusMuted, filled = false) { controller.clear() }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(NexusInset).border(1.dp, NexusLine).padding(10.dp)) {
            Text(
                controller.transcript.ifBlank { if (controller.rxActive) "Listening…" else "Start RX or open a reference WAV." },
                color = if (controller.transcript.isBlank()) NexusMuted else NexusText,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun StreamTxPane(controller: DigiController, modifier: Modifier) {
    CockpitPane("TRANSMIT", if (controller.txArmed) "ARMED ONCE" else "KEYBOARD", NexusAmber, modifier) {
        NexusTextField(controller.txText, controller::updateTxText, "TYPE TO SEND", singleLine = false, modifier = Modifier.fillMaxWidth().weight(1f))
        if (controller.lastTxText.isNotBlank()) {
            Text("LAST SENT", color = NexusMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(controller.lastTxText, color = NexusGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 3)
        }
        if (controller.mode == DigiMode.PSK31) Text("PSK is amplitude-shaped; reduce audio/RF drive so ALC is inactive.", color = NexusAmber, fontSize = 11.sp)
        Text("Continuous append is unavailable in the current native encoder; keyboard overs are explicit one-shot sends with a five-minute hard cap.", color = NexusMuted, fontSize = 10.sp)
        Text(controller.status, color = if (controller.status.contains("UNCONFIRMED")) NexusRed else NexusMuted, fontSize = 11.sp)
    }
}

@Composable
private fun StreamDock(controller: DigiController) {
    Row(
        Modifier.fillMaxWidth().background(NexusRaised, CockpitShape).border(1.dp, NexusLine, CockpitShape)
            .horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("CQ", "DE", "599", "RR", "TU", "73", "K").forEach { token ->
            CockpitButton(token, NexusBlue, filled = false) { controller.appendTxText(token) }
        }
        VerticalDivider(Modifier.height(34.dp), color = NexusLine)
            CockpitButton(if (controller.txEnabled) "TX ENABLED" else "ENABLE TX", if (controller.txEnabled) NexusRed else NexusAmber) { controller.updateTxEnabled(!controller.txEnabled) }
        CockpitButton(if (controller.txArmed) "TX ARMED" else "ARM TX", NexusAmber) { controller.arm() }
        CockpitButton("SEND", NexusGreen, enabled = controller.txEnabled && controller.txArmed && !controller.txActive) { controller.send() }
        CockpitButton("STOP TX", NexusRed, enabled = controller.txActive || controller.txArmed) { controller.haltTx() }
    }
}

@Composable
private fun SstvCockpit(controller: DigiController, wide: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selected ->
            val bitmap = runCatching { decodeOrientedBitmap(context, selected) }.getOrNull()
            if (bitmap != null) { controller.setSstvSource(bitmap, selected.lastPathSegment.orEmpty()); bitmap.recycle() }
        }
    }
    val recordingPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(controller::decodeRecording)
    }
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().background(NexusRaised, CockpitShape).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CockpitButton(if (controller.issSessionEnabled) "ISS RX SESSION ON" else "ENABLE ISS RX SESSION", NexusBlue, filled = false) { controller.setIssSession(!controller.issSessionEnabled) }
            CockpitButton("REVIEW 145.800 FM", NexusAmber, filled = false, enabled = controller.issSessionEnabled) { controller.requestIssReceiveReview() }
            Text(controller.issPass?.let { "${it.name} · AOS ${it.aosEpoch} · LOS ${it.losEpoch} · ${"%.0f".format(it.maximumElevation)}°" }
                ?: "Receive only. 145.800 MHz is an ISS downlink, not a general SSTV transmit frequency.", color = NexusMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().background(NexusPanel, CockpitShape).border(1.dp, NexusLine, CockpitShape)
                .horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SstvChoices.forEach { choice ->
                FilterChip(
                    selected = controller.sstvChoice == choice,
                    onClick = { controller.selectSstv(choice) },
                    label = { Text(choice.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NexusAmber, selectedLabelColor = NexusBg),
                )
            }
        }
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SstvRxPane(controller, { recordingPicker.launch("audio/*") }, Modifier.weight(1f).fillMaxHeight())
                SstvTxPane(controller, { imagePicker.launch("image/*") }, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SstvRxPane(controller, { recordingPicker.launch("audio/*") }, Modifier.fillMaxWidth().requiredHeightIn(min = 320.dp))
                SstvTxPane(controller, { imagePicker.launch("image/*") }, Modifier.fillMaxWidth().requiredHeightIn(min = 320.dp))
            }
        }
        SstvDock(controller)
    }
}

@Composable
private fun SstvRxPane(controller: DigiController, openRecording: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val revision = controller.imageRevision
    val image = remember(revision) { controller.currentSstvBitmap()?.asImageBitmap() }
    CockpitPane("SSTV RECEIVE", when {
        controller.sstvComplete -> "COMPLETE"
        controller.sstvLine >= 0 -> "LINE ${controller.sstvLine + 1}"
        else -> "WAITING FOR VIS"
    }, NexusGreen, modifier) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).border(1.dp, NexusLine), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, "Decoded SSTV image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Waiting for a valid VIS header", color = NexusMuted, fontFamily = FontFamily.Monospace)
        }
        CockpitButton("OPEN WAV", NexusBlue, filled = false, modifier = Modifier.fillMaxWidth(), onClick = openRecording)
        if (controller.sstvFskId.isNotBlank()) Text("FSK ID · ${controller.sstvFskId}", color = NexusGreen, fontFamily = FontFamily.Monospace)
        Text("GALLERY · ${controller.gallery.size} private PNG", color = NexusCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            controller.gallery.take(12).forEach { item ->
                CockpitButton("${item.mode} ${if (item.pinned) "★" else ""}", NexusBlue, filled = false) { shareSstv(context, item) }
                CockpitButton(if (item.pinned) "UNPIN" else "PIN", NexusAmber, filled = false) { controller.pinGallery(item.id, !item.pinned) }
                CockpitButton("DELETE", NexusRed, filled = false) { controller.deleteGallery(item.id) }
            }
        }
    }
}

@Composable
private fun SstvTxPane(controller: DigiController, openImage: () -> Unit, modifier: Modifier) {
    val revision = controller.sourceRevision
    val source = remember(revision) { controller.currentSstvSourceBitmap()?.asImageBitmap() }
    CockpitPane("SSTV TRANSMIT", if (controller.sourceReady) "IMAGE READY" else "NO IMAGE", NexusAmber, modifier) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).border(1.dp, NexusLine), contentAlignment = Alignment.Center) {
            if (source != null) Image(source, "SSTV transmit image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Choose an image to prepare ${controller.sstvChoice.label}", color = NexusMuted, fontFamily = FontFamily.Monospace)
        }
        CockpitButton("CHOOSE IMAGE", NexusBlue, filled = false, modifier = Modifier.fillMaxWidth(), onClick = openImage)
        Text("FRAME · drag long axis", color = NexusMuted, fontSize = 10.sp)
        Slider(controller.sstvFrameOffset, controller::updateSstvFrameOffset, valueRange = -1f..1f,
            modifier = Modifier.semantics { contentDescription = "SSTV crop framing" })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CALLSIGN OVERLAY", color = NexusMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Switch(controller.settings.sstvCallsignOverlay, { enabled -> controller.updateSettings { it.copy(sstvCallsignOverlay = enabled) }; controller.updateSstvFrameOffset(controller.sstvFrameOffset) })
        }
        Text("${controller.sstvChoice.label} · ${controller.sstvChoice.width} × ${controller.sstvChoice.height}", color = NexusMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SstvDock(controller: DigiController) {
    Row(
        Modifier.fillMaxWidth().background(NexusRaised, CockpitShape).border(1.dp, NexusLine, CockpitShape)
            .horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CockpitButton(if (controller.rxActive) "STOP RX" else "START RX", if (controller.rxActive) NexusAmber else NexusGreen) {
            if (controller.rxActive) controller.stopRx() else controller.startRx()
        }
            CockpitButton(if (controller.txEnabled) "TX ENABLED" else "ENABLE TX", if (controller.txEnabled) NexusRed else NexusAmber, enabled = !controller.issSessionEnabled) { controller.updateTxEnabled(!controller.txEnabled) }
        CockpitButton(if (controller.txArmed) "TX ARMED" else "ARM TX", NexusAmber, enabled = controller.sourceReady) { controller.arm() }
        CockpitButton("SEND IMAGE", NexusGreen, enabled = !controller.issSessionEnabled && controller.sourceReady && controller.txEnabled && controller.txArmed && !controller.txActive) { controller.send() }
        CockpitButton("STOP", NexusRed, enabled = controller.txActive || controller.txArmed) { controller.haltTx() }
        Text(controller.status, color = if (controller.status.contains("UNCONFIRMED")) NexusRed else NexusMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DigiSetupPanel(controller: DigiController, radio: RadioState, modifier: Modifier) {
    Column(modifier.padding(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CockpitPane("DIGI SETUP & HEALTH", controller.audioHealth.state.name, NexusCyan, Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
            val health = controller.audioHealth
            Text("STATION · ${radio.identity} · ${radio.model} · ${radio.frequencyText}", color = NexusText, fontFamily = FontFamily.Monospace)
            Text("ROUTE · ${health.source} · ${health.routeIdentity.ifBlank { "NOT SELECTED" }} · owner ${health.audioOwner}", color = NexusText, fontFamily = FontFamily.Monospace)
            Text("AUDIO · ${health.sampleRate} Hz · ${health.channels} ch · RMS ${"%.3f".format(health.rms)} · PEAK ${"%.3f".format(health.peak)} · clip ${"%.2f".format(health.clippedFraction * 100)}%", color = if (health.state == DigiAudioHealthState.CLIPPING) NexusRed else NexusGreen, fontFamily = FontFamily.Monospace)
            Text("NATIVE · ${NativeCore.version()} · ${controller.capability.fixtureStatus.name} · ${controller.capability.reason}", color = NexusMuted, fontSize = 11.sp)
            Text("LOG · canonical QSO mutation coordinator · one inherited Wavelog outbox", color = NexusGreen, fontSize = 11.sp)
            Text("UDP · ${controller.interopState.bind} · accepted ${controller.interopState.accepted} · rejected ${controller.interopState.rejected} · ${controller.interopState.lastError}", color = if (controller.interopState.lastError.isBlank()) NexusMuted else NexusRed, fontSize = 11.sp)
            Text("GALLERY · ${controller.gallery.size} items · quota ${controller.settings.galleryQuotaMb} MB · DECODE RETENTION ${controller.settings.decodeRetentionDays} days", color = NexusMuted, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CockpitButton("REFRESH ROUTES", NexusBlue, filled = false) { controller.refreshRouteHealth() }
                CockpitButton(if (controller.rxActive) "STOP LEVEL TEST" else "RECEIVE LEVEL TEST", NexusGreen, filled = false) { if (controller.rxActive) controller.stopRx() else controller.startRx() }
                CockpitButton(if (controller.settings.udpEnabled) "UDP ON" else "UDP OFF", NexusBlue, filled = false) { controller.setUdpEnabled(!controller.settings.udpEnabled) }
                CockpitButton(if (controller.settings.companionMode) "COMPANION ON" else "COMPANION OFF", NexusAmber, filled = false) { controller.setCompanionMode(!controller.settings.companionMode) }
                CockpitButton(if (controller.rawRecordingActive) "STOP RAW WAV" else "RECORD RAW WAV", NexusAmber, filled = false) { controller.toggleRawRecording() }
            }
            Text("Raw recording is operator-started, app-private, capped at 10 minutes, seven days, and 100 MB.", color = NexusMuted, fontSize = 11.sp)
            Text("WATERFALL FLOOR · ${"%.2f".format(controller.settings.waterfall.floor)}", color = NexusMuted)
            Slider(controller.settings.waterfall.floor, { controller.updateWaterfall(controller.settings.waterfall.copy(floor = it)) }, valueRange = 0f..0.8f,
                modifier = Modifier.semantics { contentDescription = "Waterfall floor" })
            Text("WATERFALL GAIN · ${"%.2f".format(controller.settings.waterfall.gain)}", color = NexusMuted)
            Slider(controller.settings.waterfall.gain, { controller.updateWaterfall(controller.settings.waterfall.copy(gain = it)) }, valueRange = .25f..4f,
                modifier = Modifier.semantics { contentDescription = "Waterfall gain" })
            Text("SANITIZED DIAGNOSTICS", color = NexusCyan, fontWeight = FontWeight.Bold)
            controller.diagnostics.forEach { row -> Text("${row.epoch} · ${row.state} · ${row.detail}", color = NexusMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }
}

private fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {
    val orientation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    val source = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
    if (orientation == 0) return source
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(orientation.toFloat()) }, true)
    source.recycle()
    return rotated
}

private fun shareSstv(context: Context, item: DigiGalleryItem) {
    val file = File(item.path)
    if (!file.isFile) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share SSTV image"))
}

@Composable
private fun CockpitPane(
    title: String,
    state: String,
    accent: Color,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.background(NexusPanel, CockpitShape).border(1.dp, NexusLine, CockpitShape)) {
        Row(
            Modifier.fillMaxWidth().background(NexusRaised).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text(state, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun CockpitButton(
    label: String,
    color: Color,
    filled: Boolean = true,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = CockpitShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = NexusBg,
                disabledContainerColor = NexusLine,
                disabledContentColor = NexusMuted,
            ),
        ) { Text(label, fontWeight = FontWeight.Black, fontSize = 11.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = CockpitShape,
            border = BorderStroke(1.dp, if (enabled) color else NexusLine),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (enabled) color else NexusMuted),
        ) { Text(label, fontWeight = FontWeight.Black, fontSize = 11.sp) }
    }
}

@Composable
private fun NexusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = modifier,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        shape = CockpitShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = NexusText,
            unfocusedTextColor = NexusText,
            focusedBorderColor = NexusCyan,
            unfocusedBorderColor = NexusLine,
            focusedLabelColor = NexusCyan,
            unfocusedLabelColor = NexusMuted,
            cursorColor = NexusCyan,
            focusedContainerColor = NexusInset,
            unfocusedContainerColor = NexusInset,
        ),
    )
}

@Composable
private fun TableHead(text: String, modifier: Modifier) {
    Text(text, modifier.padding(vertical = 4.dp), color = NexusMuted, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
}

@Composable
private fun DecodeCell(text: String, modifier: Modifier, color: Color = NexusText) {
    Text(text, modifier, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
