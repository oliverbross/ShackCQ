// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import kotlin.math.max

private val WorkbenchPanel = Color(0xFF161B20)
private val WorkbenchRaised = Color(0xFF222A31)
private val WorkbenchInk = Color(0xFFF4F0E7)
private val WorkbenchMuted = Color(0xFFA5ADB2)
private val WorkbenchAmber = Color(0xFFE9A72B)
private val WorkbenchHealthy = Color(0xFF42C77B)
private val WorkbenchDanger = Color(0xFFE4544D)
private val WorkbenchCyan = Color(0xFF43C7D9)

@Composable
fun SdrWorkbenchControlPanel(workbench: AndroidSdrWorkbenchV4, state: TciRuntimeSnapshot,
    panadapter: PanadapterController, localReceivers: LocalReceiverController?,
    dispatch: (RadioPlatformAction) -> Unit = {}) {
    val capture = workbench.capture.snapshot
    val replay = workbench.replay.snapshot
    val active = state.receivers.firstOrNull { it.id == state.activeReceiverId } ?: state.receivers.firstOrNull()
    var replaySpeed by remember { mutableFloatStateOf(1f) }
    Card(Modifier.fillMaxWidth().heightIn(max = 330.dp), colors = CardDefaults.cardColors(containerColor = WorkbenchPanel)) {
        Column(Modifier.fillMaxWidth().padding(9.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("PANADAPTER v6 · SDR OPERATOR WORKBENCH", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
                Text(workbench.historicalLabel, color = if (workbench.historicalLabel == "LIVE") WorkbenchHealthy else WorkbenchCyan,
                    fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (capture.state == IqCaptureState.RECORDING) Button({ workbench.capture.stop() }) { Text("STOP I/Q RECORDING") }
                else Button({ active?.let { receiver -> workbench.capture.start(
                    if (state.device == "DEMO · NO RADIO") "DEBUG FIXTURE" else "TCI", receiver.backendIndex,
                    receiver.effectiveRxHz, receiver.iqSampleRate.takeIf { it > 0 } ?: receiver.sampleRate,
                    state.device, radioPresetBandName(receiver.effectiveRxHz) ?: "OUTSIDE", receiver.mode)
                } }, enabled = active?.effectiveRxHz?.let { it > 0 } == true && replay.state !in setOf(IqReplayState.PLAYING, IqReplayState.PAUSED)) {
                    Text("RECORD I/Q")
                }
                Text(capture.status, color = if (capture.state == IqCaptureState.ERROR) WorkbenchDanger else WorkbenchMuted, fontSize = 10.sp)
                Text("${capture.bytes / 1024 / 1024} MiB · ${capture.durationMillis / 1_000}s", color = WorkbenchMuted, fontSize = 10.sp)
            }
            if (workbench.capture.captures.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(.25f, .5f, 1f, 2f).forEach { speed -> FilterChip(replaySpeed == speed, { replaySpeed = speed }, { Text("${speed}×") }) }
                    workbench.capture.captures.take(4).forEach { row -> OutlinedButton({
                        active?.let { receiver ->
                            dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "iq_stop", targetReceiver = receiver.backendIndex))
                        }
                        localReceivers?.stopActive("Replay selected")
                        panadapter.detachTciSources("Replay selected · live stream detached")
                        workbench.replay.play(row.metadata.id, replaySpeed)
                    }) { Text("REPLAY ${row.metadata.durationMillis / 1_000}s") } }
                }
            }
            if (replay.state !in setOf(IqReplayState.STOPPED, IqReplayState.COMPLETE)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(workbench.replay::pause) { Text(if (replay.state == IqReplayState.PAUSED) "PLAY" else "PAUSE") }
                    OutlinedButton({ workbench.replay.skip(-10_000) }) { Text("−10 s") }
                    OutlinedButton({ workbench.replay.skip(10_000) }) { Text("+10 s") }
                    OutlinedButton({ workbench.replay.stop("Return Live · explicit stream reattachment required") }) { Text("RETURN LIVE") }
                    Text(replay.status, color = if (replay.audioTruthful) WorkbenchHealthy else WorkbenchDanger, fontWeight = FontWeight.Bold)
                }
                Slider(replay.positionMillis.toFloat(), { workbench.replay.seek(it.toLong()) },
                    valueRange = 0f..replay.durationMillis.toFloat().coerceAtLeast(1f))
            }
            HorizontalDivider()
            MeasurementInspector(workbench, active, localReceivers)
            ChannelMonitorRail(workbench, active?.effectiveRxHz, active?.mode ?: "NFM")
            Text("Replay and history are derived receive sources. No control here can move a physical VFO, assert PTT/TUNE, or transmit.",
                color = WorkbenchAmber, fontSize = 9.sp)
        }
    }
}

@Composable
fun SdrStereoWorkbenchStrip(workbench: AndroidSdrWorkbenchV4, controller: PanadapterController,
    radio: RadioState, localReceivers: LocalReceiverController?) {
    val capture = workbench.capture.snapshot
    val replay = workbench.replay.snapshot
    val center = controller.effectiveCenter()
    val rate = controller.frame?.effectiveSampleRate ?: controller.routeProof.physicalRate
    var replaySpeed by remember { mutableFloatStateOf(1f) }
    Card(Modifier.fillMaxWidth().heightIn(max = 240.dp), colors = CardDefaults.cardColors(containerColor = WorkbenchPanel)) {
        Column(Modifier.fillMaxWidth().padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("SDR OPERATOR WORKBENCH · STEREO I/Q", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
                if (capture.state == IqCaptureState.RECORDING) Button({ workbench.capture.stop() }) { Text("STOP I/Q") }
                else Button({ workbench.capture.start("STEREO I/Q", 0, center, rate, radio.model,
                    radioPresetBandName(center) ?: "OUTSIDE", radio.mode) },
                    enabled = center > 0 && rate > 0 && replay.state !in setOf(IqReplayState.PLAYING, IqReplayState.PAUSED)) {
                    Text("RECORD I/Q")
                }
                Text(capture.status, color = if (capture.state == IqCaptureState.ERROR) WorkbenchDanger else WorkbenchMuted, fontSize = 9.sp)
            }
            if (workbench.capture.captures.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(.25f, .5f, 1f, 2f).forEach { speed -> FilterChip(replaySpeed == speed, { replaySpeed = speed }, { Text("${speed}×") }) }
                workbench.capture.captures.take(4).forEach { row -> OutlinedButton({
                    localReceivers?.stopActive("Replay selected")
                    controller.stop()
                    workbench.replay.play(row.metadata.id, replaySpeed)
                }, enabled = capture.state != IqCaptureState.RECORDING) { Text("REPLAY ${row.metadata.durationMillis / 1_000}s") } }
            }
            if (replay.state !in setOf(IqReplayState.STOPPED, IqReplayState.COMPLETE)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(workbench.replay::pause) { Text(if (replay.state == IqReplayState.PAUSED) "PLAY" else "PAUSE") }
                    OutlinedButton({ workbench.replay.skip(-10_000) }) { Text("−10 s") }
                    OutlinedButton({ workbench.replay.skip(10_000) }) { Text("+10 s") }
                    OutlinedButton({ workbench.replay.stop("Return Live") }) { Text("RETURN LIVE") }
                    Text(replay.status, color = if (replay.audioTruthful) WorkbenchHealthy else WorkbenchDanger, fontSize = 9.sp)
                }
            }
            MeasurementInspector(workbench, null, localReceivers)
            ChannelMonitorRail(workbench, center, radio.mode)
            Text("Offline replay never changes the physical VFO and non-1× replay never claims truthful audio.",
                color = WorkbenchAmber, fontSize = 9.sp)
        }
    }
}

@Composable
private fun MeasurementInspector(workbench: AndroidSdrWorkbenchV4, receiver: TciReceiverSnapshot?,
    localReceivers: LocalReceiverController?) {
    val inspector = workbench.measurement.inspector
    val tracker = workbench.measurement.tracker
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("MARKER A/B", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
        inspector.markerA?.let { MeasurementChip("A", it) } ?: Text("A · touch spectrum", color = WorkbenchMuted)
        inspector.markerB?.let { MeasurementChip("B", it) } ?: Text("B · touch spectrum", color = WorkbenchMuted)
        Text("Δf ${inspector.deltaFrequencyHz ?: 0} Hz · ΔL ${inspector.deltaLevel?.let { "%.1f".format(it) } ?: "—"} dB",
            color = WorkbenchCyan)
        OutlinedButton({ workbench.measurement.setMarkerA(null); workbench.measurement.setMarkerB(null) }) { Text("CLEAR MARKERS") }
        OutlinedButton({ inspector.markerAHz?.let { workbench.measurement.selectTracker(it, localReceivers?.snapshot?.receivers?.firstOrNull()?.id) } },
            enabled = inspector.markerAHz != null) { Text("TRACK A") }
    }
    if (tracker.selected) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("TRACKER · ${formatRadioFrequency(tracker.frequencyHz)} · drift ${tracker.driftHz} Hz · ${"%.1f".format(tracker.level)} · SNR ${"%.1f".format(tracker.snr)} · ${tracker.durationMillis / 1_000}s",
            color = WorkbenchInk)
        FilterChip(tracker.localRxFollow, { workbench.measurement.setLocalFollow(!tracker.localRxFollow) }, { Text("LOCAL RX FOLLOW") })
        OutlinedButton(workbench.measurement::stopTracker) { Text("STOP TRACK") }
        Text(tracker.status, color = WorkbenchAmber, fontSize = 9.sp)
    }
}

@Composable
private fun MeasurementChip(label: String, value: SignalMeasurement) {
    Column(Modifier.background(WorkbenchRaised).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text("$label · ${formatRadioFrequency(value.frequencyHz)} · ${"%.1f".format(value.level)} ${value.units}", color = WorkbenchInk, fontSize = 10.sp)
        Text("SNR ${"%.1f".format(value.snr)} · noise ${"%.1f".format(value.noiseFloor)} · BW 3/6/26 ${value.bandwidth3DbHz.toInt()}/${value.bandwidth6DbHz.toInt()}/${value.bandwidth26DbHz.toInt()} Hz · occupied ${value.occupiedBandwidthHz.toInt()} Hz · channel ${"%.1f".format(value.channelPower)} · adjacent ${"%.1f".format(value.adjacentChannel)}",
            color = WorkbenchMuted, fontSize = 8.sp)
    }
}

@Composable
private fun ChannelMonitorRail(workbench: AndroidSdrWorkbenchV4, centerHz: Long?, mode: String) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("CHANNEL MONITOR · ${workbench.measurement.monitors.size}/4", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
        if (workbench.measurement.monitors.size < 4 && centerHz != null && centerHz > 0) OutlinedButton({
            val index = workbench.measurement.monitors.size
            workbench.measurement.upsertMonitor(ChannelMonitor(name = "MON ${index + 1}",
                frequencyHz = centerHz + index * 5_000L, mode = if (index % 2 == 0) "NFM" else mode))
        }) { Text("ADD IN SPAN") }
        workbench.measurement.monitors.forEach { monitor ->
            Column(Modifier.background(WorkbenchRaised).padding(5.dp)) {
                Text("${monitor.name} · ${formatRadioFrequency(monitor.frequencyHz)} · ${monitor.activity}",
                    color = if (monitor.occupied) WorkbenchDanger else WorkbenchInk, fontSize = 9.sp)
                Text("${if (monitor.level.isFinite()) "%.1f dBFS".format(monitor.level) else "OUT OF SPAN"} · SQL ${monitor.squelchDb.toInt()} · ${monitor.occupancyPercent.toInt()}% · ${monitor.activityDurationMillis / 1_000}s · ${monitor.toneState}",
                    color = WorkbenchMuted, fontSize = 8.sp)
                if (monitor.mode == "NFM") Text("Expected CTCSS ${monitor.expectedCtcssHz ?: "—"} · DCS ${monitor.expectedDcs ?: "—"}",
                    color = WorkbenchMuted, fontSize = 8.sp)
                OutlinedButton({ workbench.measurement.removeMonitor(monitor.id) }, modifier = Modifier.height(30.dp)) { Text("REMOVE", fontSize = 8.sp) }
            }
        }
    }
}

@Composable
fun SpectrumSurveyPanel(workbench: AndroidSdrWorkbenchV4) {
    var selectedBand by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var selectedReceiver by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedBand, selectedSource, selectedReceiver) { workbench.refreshSurvey(selectedBand, selectedSource, selectedReceiver) }
    val rows = workbench.surveyRows
    Column(Modifier.fillMaxSize().background(WorkbenchPanel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SPECTRUM SURVEY · DERIVED AGGREGATES ONLY", color = WorkbenchAmber, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Frequency × UTC occupancy heatmap · ${rows.size} displayed / ${workbench.survey.stats.rows} stored · ${workbench.survey.stats.bytes / 1024} KiB · ${workbench.survey.retentionDays} days",
            color = WorkbenchMuted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf<String?>(null, "20m", "40m", "2m").forEach { band -> FilterChip(selectedBand == band, { selectedBand = band }, { Text(band ?: "ALL BANDS") }) }
            listOf<String?>(null, "TCI", "REPLAY", "DEMO · NO RADIO").forEach { source -> FilterChip(selectedSource == source, { selectedSource = source }, { Text(source ?: "ALL SOURCES") }) }
            listOf<Int?>(null, 0, 1).forEach { receiver -> FilterChip(selectedReceiver == receiver, { selectedReceiver = receiver }, { Text(receiver?.let { "RX ${it + 1}" } ?: "ALL RX") }) }
            FilterChip(false, {}, { Text("DATE · RETENTION") }); FilterChip(false, {}, { Text("HOUR · ALL") }); FilterChip(false, {}, { Text("SCAN BANK · ALL") })
        }
        SpectrumMatrix(rows, Modifier.fillMaxWidth().weight(1f).heightIn(min = 360.dp))
        val byBand = rows.groupBy(SpectrumAggregate::band).mapValues { (_, values) -> values.map(SpectrumAggregate::occupancyPercent).average() }
        Text("BAND COMPARISON · ALL VISIBLE WITHOUT HORIZONTAL SCROLL", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
        val comparisonRows = byBand.toList().sortedByDescending { it.second }
        if (comparisonRows.isEmpty()) Text("No aggregate evidence yet", color = WorkbenchMuted)
        else Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comparisonRows.chunked(6).forEach { comparisonRow ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    comparisonRow.forEach { (band, occupancy) -> Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = WorkbenchRaised)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(band, color = WorkbenchAmber, fontWeight = FontWeight.Bold)
                            Text("${"%.1f".format(occupancy)}% occupancy", color = WorkbenchInk)
                        }
                    } }
                    repeat(6 - comparisonRow.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Text("DAILY TIMELINE · ${rows.groupBy { it.bucketEpoch / 86_400L }.size} days · SCANNER ACTIVITY ${rows.sumOf { it.scannerHitCount }} hits · SIGNALS ${rows.sumOf { it.signalCount }}",
            color = WorkbenchInk)
        Text("Historical occupancy never overrides current RF and never appears as a live spectrum trace.", color = WorkbenchAmber)
    }
}

@Composable
private fun SpectrumMatrix(rows: List<SpectrumAggregate>, modifier: Modifier) {
    val bandLabels = rows.map(SpectrumAggregate::band).distinct().take(9).ifEmpty {
        listOf("160m", "80m", "40m", "30m", "20m", "15m", "10m", "6m", "2m")
    }
    Column(modifier.background(WorkbenchRaised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(52.dp).fillMaxHeight().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                bandLabels.asReversed().forEach { Text(it, color = WorkbenchMuted, fontSize = 9.sp) }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFF0C171C))
                    for (step in 0..12) {
                        val x = size.width * step / 12f
                        drawLine(Color(0xFF35515B), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                    for (step in 0..bandLabels.size) {
                        val y = size.height * step / bandLabels.size.coerceAtLeast(1)
                        drawLine(Color(0xFF35515B), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    if (rows.isNotEmpty()) {
                        val sortedTimes = rows.map(SpectrumAggregate::bucketEpoch).distinct().sorted()
                        val sortedFrequencies = rows.map(SpectrumAggregate::frequencyBucketHz).distinct().sorted()
                        val width = size.width / sortedTimes.size.coerceAtLeast(1)
                        val height = size.height / sortedFrequencies.size.coerceAtLeast(1)
                        rows.take(100_000).forEach { row ->
                            val x = sortedTimes.binarySearch(row.bucketEpoch).coerceAtLeast(0) * width
                            val y = (sortedFrequencies.size - 1 - sortedFrequencies.binarySearch(row.frequencyBucketHz).coerceAtLeast(0)) * height
                            val t = (row.occupancyPercent / 100f).coerceIn(0f, 1f)
                            drawRect(Color(t, .18f + .75f * t, 1f - .85f * t), Offset(x, y), Size(width + 1, height + 1))
                        }
                    }
                }
                if (rows.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NO CAPTURED SPECTRUM YET", color = WorkbenchAmber, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Start an explicit spectrum survey from Panadapter or Scanner. This matrix will then show measured frequency × UTC occupancy.",
                        color = WorkbenchInk, fontSize = 12.sp)
                    Text("No synthetic occupancy is drawn.", color = WorkbenchCyan, fontSize = 11.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "04", "08", "12", "16", "20", "24 UTC").forEach { Text(it, color = WorkbenchMuted, fontSize = 9.sp) }
        }
    }
}

@Composable
fun SdrWorkbenchSettingsPanel(workbench: AndroidSdrWorkbenchV4, operational: SdrOperationalV2) {
    val selectedBank = operational.scanBanks.firstOrNull { it.id == operational.selectedBankId } ?: operational.scanBanks.firstOrNull()
    var interchange by remember { mutableStateOf("") }
    var interchangeStatus by remember { mutableStateOf("No memory interchange performed") }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = WorkbenchRaised)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("PANADAPTER / ANALYSIS · WORKBENCH v4", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
            Text("I/Q file cap ${workbench.capture.maximumFileSeconds}s · total ${workbench.capture.maximumTotalBytes / 1024 / 1024} MiB · float32 interleaved I/Q · metadata JSON",
                color = WorkbenchMuted)
            Slider(workbench.capture.maximumFileSeconds.toFloat(), { workbench.capture.configure(it.toInt(), workbench.capture.maximumTotalBytes) }, valueRange = 30f..600f)
            Slider((workbench.capture.maximumTotalBytes / 1024 / 1024).toFloat(), {
                workbench.capture.configure(workbench.capture.maximumFileSeconds, it.toLong() * 1024 * 1024) }, valueRange = 16f..2048f)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("AUTO", "DBFS").forEach { units -> FilterChip(workbench.settings.measurementUnits == units,
                    { workbench.updateSettings(workbench.settings.copy(measurementUnits = units)) }, { Text("MEASURE $units") }) }
                OutlinedButton(workbench.measurement::stopTracker, enabled = workbench.measurement.tracker.selected) { Text("RESET TRACKER") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(workbench.settings.historyOverlay, { workbench.updateSettings(workbench.settings.copy(historyOverlay = it)) })
                Text("Subtle HISTORY occupancy overlay · never live RF")
                Checkbox(workbench.settings.replayAudioEnabled, { workbench.updateSettings(workbench.settings.copy(replayAudioEnabled = it)) })
                Text("Replay audio at truthful 1× only")
            }
            Text("SCANNER INTELLIGENCE", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ScannerIntelligenceOrder.entries.forEach { order -> FilterChip(workbench.settings.scannerOrder == order,
                    { workbench.updateSettings(workbench.settings.copy(scannerOrder = order)) }, { Text(order.name.replace('_', ' ')) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { AdaptiveDwellMode.entries.forEach { mode -> FilterChip(workbench.settings.adaptiveDwell == mode,
                { workbench.updateSettings(workbench.settings.copy(adaptiveDwell = mode)) }, { Text("ADAPTIVE ${mode.name}") }) } }
            Text("Adaptive dwell is conservative and never below the operator minimum. Current RF always wins.", color = WorkbenchAmber, fontSize = 9.sp)
            Text("SPECTRUM SURVEY · retention", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(7, 30, 90).forEach { days -> FilterChip(workbench.survey.retentionDays == days,
                { workbench.survey.configure(days, workbench.survey.timeBucketMinutes) }, { Text("$days days") }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(5, 15, 30, 60).forEach { minutes ->
                FilterChip(workbench.survey.timeBucketMinutes == minutes,
                    { workbench.survey.configure(workbench.survey.retentionDays, minutes) }, { Text("$minutes min") }) } }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("HEATMAP", "BAND", "DAILY", "SCANNER").forEach { display ->
                    FilterChip(workbench.settings.surveyDisplayDefault == display,
                        { workbench.updateSettings(workbench.settings.copy(surveyDisplayDefault = display)) }, { Text("DEFAULT $display") }) }
            }
            Text("MEMORIES · ${selectedBank?.name ?: "NO SCAN BANK"} · validated JSON/CSV", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedButton({ interchange = exportChannelMemoriesJson(selectedBank?.memories.orEmpty()); interchangeStatus = "JSON exported to review field" },
                    enabled = selectedBank != null) { Text("EXPORT JSON") }
                OutlinedButton({ interchange = exportChannelMemoriesCsv(selectedBank?.memories.orEmpty()); interchangeStatus = "CSV exported to review field" },
                    enabled = selectedBank != null) { Text("EXPORT CSV") }
                Button({
                    runCatching { importChannelMemoriesJson(interchange) }.onSuccess { rows ->
                        selectedBank?.let { operational.upsertBank(it.copy(memories = rows)) }
                        interchangeStatus = "Imported ${rows.size} JSON memories"
                    }.onFailure { interchangeStatus = "JSON import rejected · ${it.message}" }
                }, enabled = selectedBank != null && interchange.isNotBlank()) { Text("IMPORT JSON") }
                Button({
                    runCatching { importChannelMemoriesCsv(interchange) }.onSuccess { rows ->
                        selectedBank?.let { operational.upsertBank(it.copy(memories = rows)) }
                        interchangeStatus = "Imported ${rows.size} CSV memories"
                    }.onFailure { interchangeStatus = "CSV import rejected · ${it.message}" }
                }, enabled = selectedBank != null && interchange.isNotBlank()) { Text("IMPORT CSV") }
            }
            OutlinedTextField(interchange, { interchange = it.take(250_000) }, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                label = { Text("Memory JSON/CSV · review before import") })
            Text(interchangeStatus, color = WorkbenchMuted, fontSize = 9.sp)
            selectedBank?.memories?.filter { it.scanEnabled }?.take(8)?.let { rows ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    rows.forEach { memory -> OutlinedButton({
                        workbench.measurement.upsertMonitor(ChannelMonitor(name = memory.name.ifBlank { memory.group.ifBlank { "MEMORY" } },
                            frequencyHz = memory.frequencyHz, mode = memory.mode, expectedCtcssHz = memory.expectedCtcssHz,
                            expectedDcs = memory.expectedDcs))
                    }, enabled = workbench.measurement.monitors.size < 4) { Text("MONITOR ${memory.name.ifBlank { formatRadioFrequency(memory.frequencyHz) }}") } }
                }
            }
            Text("TCI RECONNECT · OFF BY DEFAULT · control reconnect requires explicit Connect and explicit IQ/audio/scanner reattachment.", color = WorkbenchAmber)
        }
    }
}

@Composable
fun SdrWorkbenchHealthPanel(workbench: AndroidSdrWorkbenchV4, tci: TciRuntimeSnapshot) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkbenchHealthCard("I/Q RECORDER", "${workbench.capture.snapshot.state} · ${workbench.capture.snapshot.bytes / 1024} KiB", workbench.capture.snapshot.state != IqCaptureState.ERROR, Modifier.weight(1f))
            WorkbenchHealthCard("REPLAY", "${workbench.replay.snapshot.state} · ${workbench.replay.snapshot.positionMillis}/${workbench.replay.snapshot.durationMillis} ms", workbench.replay.snapshot.state != IqReplayState.ERROR, Modifier.weight(1f))
            WorkbenchHealthCard("SURVEY", "${workbench.survey.stats.rows} rows · ${workbench.survey.stats.bytes / 1024} KiB · ${workbench.survey.stats.aggregationLatencyMillis} ms", workbench.survey.stats.rows <= workbench.survey.maximumRows, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkbenchHealthCard("TRACKER", workbench.measurement.tracker.status, !workbench.measurement.tracker.status.contains("BLOCKED"), Modifier.weight(1f))
            WorkbenchHealthCard("MONITORS", "${workbench.measurement.monitors.size}/4 · ${workbench.measurement.monitors.count(ChannelMonitor::occupied)} occupied", workbench.measurement.monitors.size <= 4, Modifier.weight(1f))
            WorkbenchHealthCard("TCI HARDENING", "${tci.reconnectState} · stale ${tci.staleFrames} · duplicate ${tci.duplicateStatus} · capability ${tci.capabilityChanges}", tci.staleFrames == 0L, Modifier.weight(1f))
            WorkbenchHealthCard("CALIBRATION", workbench.calibration.rows.values.joinToString { "${it.source}:${it.truth}" }.ifBlank { "UNCALIBRATED" }, true, Modifier.weight(1f))
        }
        Text("Support bundles exclude I/Q/audio recordings, decoded conversations, RDS RadioText, operator notes, capture paths and private endpoints.", color = WorkbenchMuted, fontSize = 9.sp)
    }
}

@Composable
private fun WorkbenchHealthCard(title: String, detail: String, healthy: Boolean, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = WorkbenchRaised)) {
        Column(Modifier.padding(6.dp)) {
            Text(title, color = if (healthy) WorkbenchHealthy else WorkbenchDanger, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            Text(detail, color = WorkbenchMuted, fontSize = 8.sp)
        }
    }
}

@Composable
fun SdrWorkbenchCalibrationPanel(workbench: AndroidSdrWorkbenchV4, state: TciRuntimeSnapshot) {
    val receiver = state.receivers.firstOrNull { it.id == state.activeReceiverId } ?: state.receivers.firstOrNull()
    val source = if (state.device == "DEMO · NO RADIO") "DEBUG FIXTURE" else "TCI"
    val calibration = workbench.calibration.calibration(source)
    var known by remember { mutableStateOf(receiver?.effectiveRxHz?.toString().orEmpty()) }
    var observed by remember { mutableStateOf(receiver?.effectiveRxHz?.toString().orEmpty()) }
    var knownLevel by remember { mutableStateOf("") }
    var measuredLevel by remember { mutableStateOf("-60") }
    var reference by remember { mutableStateOf("Known signal") }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = WorkbenchRaised)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RECEIVE CALIBRATION · $source · ${calibration.truth}", color = WorkbenchAmber, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(known, { known = it.filter(Char::isDigit).take(11) }, label = { Text("Known Hz") }, modifier = Modifier.weight(1f))
                OutlinedTextField(observed, { observed = it.filter(Char::isDigit).take(11) }, label = { Text("Observed Hz") }, modifier = Modifier.weight(1f))
                OutlinedTextField(knownLevel, { knownLevel = it.take(12) }, label = { Text("Known dBm · optional") }, modifier = Modifier.weight(1f))
                OutlinedTextField(measuredLevel, { measuredLevel = it.take(12) }, label = { Text("Measured dBFS") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(reference, { reference = it.take(120) }, label = { Text("Reference note") }, modifier = Modifier.fillMaxWidth())
            Button({
                val knownHz = known.toLongOrNull(); val observedHz = observed.toLongOrNull(); val measured = measuredLevel.toFloatOrNull()
                if (knownHz != null && observedHz != null && measured != null)
                    workbench.calibration.guided(source, knownHz, observedHz, knownLevel.toFloatOrNull(), measured, reference)
            }) { Text("APPLY USER CALIBRATION") }
            Text("Level ${calibration.levelOffsetDb} dB · frequency ${calibration.frequencyCorrectionPpm} ppm · I/Q gain ${calibration.iqGainCorrection} · phase ${calibration.iqPhaseCorrectionDegrees}°",
                color = WorkbenchInk)
            Text("Without a user-supplied reference level, measurements remain RELATIVE/dBFS. No absolute dBm claim is made.", color = WorkbenchAmber)
        }
    }
}
