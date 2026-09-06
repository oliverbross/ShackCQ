// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shackcq.mobile.CtyController
import app.shackcq.mobile.AppController
import app.shackcq.mobile.SPOT_STATUS_CS
import app.shackcq.mobile.SPOT_STATUS_DS
import app.shackcq.mobile.SpotLogIdentity
import app.shackcq.mobile.SpotLogStatus
import app.shackcq.mobile.spotCallStatusOptions
import app.shackcq.mobile.spotDxccStatusOptions
import app.shackcq.mobile.FeatureController
import app.shackcq.mobile.NeuralDxController
import app.shackcq.mobile.OperatingContextSnapshot
import app.shackcq.mobile.PortableController
import app.shackcq.mobile.QsoDatabase
import app.shackcq.mobile.WorkspaceAction
import app.shackcq.mobile.WorkspaceDestination
import app.shackcq.mobile.ContestRuntime
import app.shackcq.mobile.DxChaserRuntime
import app.shackcq.mobile.toPortable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.abs

private val MapBackground = Color(0xFF10171C)
private val MapPanel = Color(0xFF1A242B)
private val MapGrid = Color(0xFF51606A)
private val MapText = Color(0xFFF4F0E7)
private val MapMuted = Color(0xFFAAB5BC)
private val MapCyan = Color(0xFF4BC3D5)
private val MapAmber = Color(0xFFF0B33C)
private val MapGreen = Color(0xFF52CB82)
private val MapMagenta = Color(0xFFE77CB6)

@Composable
internal fun BandMapScreen(
    controller: BandMapController,
    database: QsoDatabase,
    features: FeatureController,
    neuralDx: NeuralDxController,
    portable: PortableController,
    cty: CtyController,
    contest: ContestRuntime,
    chaser: DxChaserRuntime,
    operatingContext: OperatingContextSnapshot,
    keyer: BandMapKeyerContext,
    app: AppController,
    onAction: (WorkspaceAction) -> Unit,
) {
    val contestSnapshot = contest.snapshot()
    val snapshot = controller.snapshot
    val statuses by produceState<Map<String, SpotLogStatus>>(emptyMap(), snapshot.generation,
        operatingContext.stationProfileId.value, cty.dataRevision) {
        val identities = snapshot.rankedSpots.map { ranked ->
            val entity = cty.lookup(ranked.spot.callsign)
            SpotLogIdentity(ranked.spot.id, ranked.spot.callsign, entity?.dxcc.orEmpty(), entity?.country.orEmpty(),
                ranked.spot.band, ranked.spot.modeFamily.name)
        }
        value = withContext(Dispatchers.IO) { database.spotStatuses(identities, operatingContext.stationProfileId.value) }
    }
    val visibleRows = snapshot.rankedSpots.filter { ranked ->
        val status = statuses[ranked.spot.id]
        (controller.settings.callStatusFilters.isEmpty() || status?.callStatus in controller.settings.callStatusFilters) &&
            (controller.settings.dxccStatusFilters.isEmpty() || status?.dxccStatus in controller.settings.dxccStatusFilters)
    }
    val visibleSnapshot = snapshot.copy(rankedSpots = visibleRows)
    var selected by remember {
        mutableStateOf(visibleRows.firstOrNull { it.spot.id == snapshot.selectedSpotId })
    }
    val openSpot: (BandMapRankedSpot) -> Unit = { ranked ->
        selected = ranked
        controller.select(ranked.spot.id)
    }
    var filterOpen by remember { mutableStateOf(false) }
    val keyboardModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key in setOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)) return@onPreviewKeyEvent false
        val rows = visibleRows
        val index = rows.indexOfFirst { it.spot.id == selected?.spot?.id }.let { if (it < 0) 0 else it }
        when (event.key) {
            Key.DirectionDown, Key.N -> rows.getOrNull((index + 1).coerceAtMost(rows.lastIndex))?.let { selected = it; controller.select(it.spot.id) }
            Key.DirectionUp, Key.P -> rows.getOrNull((index - 1).coerceAtLeast(0))?.let { selected = it; controller.select(it.spot.id) }
            Key.F -> filterOpen = true
            Key.Plus, Key.Equals -> snapshot.selectedBands.firstOrNull()?.let { controller.zoom(it, .5) }
            Key.Minus -> snapshot.selectedBands.firstOrNull()?.let { controller.zoom(it, 2.0) }
            Key.Escape -> { selected = null; controller.select(null); filterOpen = false }
            else -> return@onPreviewKeyEvent false
        }
        true
    }
    Column(keyboardModifier.fillMaxSize().focusable().background(MapBackground).padding(10.dp).testTag("band-map-screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BandMapHeader(visibleSnapshot, keyer, {
            val current = snapshot.selectedBands.firstOrNull()
            val next = bandMapVisibleBands[(bandMapVisibleBands.indexOf(current).takeIf { it >= 0 } ?: -1)
                .let { (it + 1) % bandMapVisibleBands.size }]
            controller.updateSettings { it.copy(selectedBands = listOf(next), selectedLayout = BandMapLayoutMode.SINGLE_EXPANDED) }
        }, { filterOpen = true })
        BandControls(controller)
        PresetAndLayoutControls(controller, contestSnapshot.activeSession != null)
        if (snapshot.unavailableReasons.isNotEmpty()) Text(snapshot.unavailableReasons.joinToString(" · "), color = MapAmber, fontSize = 12.sp)
        Box(Modifier.weight(1f)) {
            when (visibleSnapshot.layout) {
                BandMapLayoutMode.GRID_OVERVIEW -> BandGrid(visibleSnapshot, controller, statuses, app, openSpot)
                BandMapLayoutMode.MULTI_HORIZONTAL -> HorizontalBandRows(visibleSnapshot, controller, statuses, app, openSpot)
                BandMapLayoutMode.SINGLE_EXPANDED -> SingleExpanded(visibleSnapshot, controller, statuses, app, operatingContext, openSpot)
                BandMapLayoutMode.MULTI_VERTICAL -> VerticalBandColumns(visibleSnapshot, controller, statuses, app, openSpot)
            }
        }
    }
    selected?.let { ranked -> SpotDetail(ranked, statuses[ranked.spot.id], app, snapshot.contextGeneration, controller, onAction, { controller.toggleMark(ranked.spot, it) }) { selected = null; controller.select(null) } }
    if (filterOpen) FilterDialog(controller, app) { filterOpen = false }
}

@Composable private fun BandMapHeader(snapshot: BandMapUiSnapshot, keyer: BandMapKeyerContext, cycleBand: () -> Unit, openFilters: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.clickable(onClick = cycleBand).semantics { contentDescription = "Band Maps header, tap to show the next band" }) {
            Text("INTELLIGENT BAND MAPS", color = MapText, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("TAP HEADER FOR NEXT BAND", color = MapCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("gen ${snapshot.generation} · ${snapshot.diagnostic.sourceObservations} received · ${snapshot.diagnostic.canonicalSpots} repository · ${snapshot.diagnostic.afterSourceFilter} source · ${snapshot.diagnostic.afterBandModeFilter} band/mode · ${snapshot.diagnostic.afterIntelligenceFilter} intelligence · ${snapshot.rankedSpots.size} displayed · ${snapshot.diagnostic.rebuildMillis} ms",
                color = MapMuted, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("KEYER ${if (keyer.availability.available) "AVAILABLE" else "READ-ONLY"} · ${keyer.queue.state}", color = MapCyan, fontSize = 11.sp,
                modifier = Modifier.semantics { contentDescription = "Keyer read only status ${keyer.availability.available}, queue ${keyer.queue.state}" })
            OutlinedButton(openFilters) { Text("FILTERS") }
        }
    }
}

@Composable private fun PresetAndLayoutControls(controller: BandMapController, contestActive: Boolean) {
    val settings = controller.settings
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        settings.presets.filter { it.id != "contest" || contestActive }.forEach { preset -> FilterChip(settings.activePresetId == preset.id,
            { controller.updateSettings { it.copy(activePresetId = preset.id, selectedLayout = preset.layout) } }, { Text(preset.label) }) }
        if (!contestActive) Text("Contest S&P · start a Contest session", color = MapMuted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterVertically))
        Spacer(Modifier.width(8.dp))
        BandMapLayoutMode.entries.forEach { layout -> FilterChip(settings.selectedLayout == layout,
            { controller.updateSettings { it.copy(selectedLayout = layout) } }, { Text(layout.name.replace('_', ' ')) }) }
    }
}

@Composable private fun BandControls(controller: BandMapController) {
    val selected = controller.settings.selectedBands
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            bandMapVisibleBands.map { name -> bandMapBands.first { it.name == name } }.forEach { band -> FilterChip(band.name in selected, {
            controller.updateSettings { current ->
                val next = if (band.name in current.selectedBands) current.selectedBands - band.name else current.selectedBands + band.name
                current.copy(selectedBands = next.ifEmpty { current.selectedBands })
            }
            }, { Text(band.name) }) }
        }
    }
}

@Composable private fun HorizontalBandRows(snapshot: BandMapUiSnapshot, controller: BandMapController, statuses: Map<String, SpotLogStatus>, app: AppController, select: (BandMapRankedSpot) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items(snapshot.selectedBands, key = { it }) { band -> HorizontalLane(band, snapshot.rankedSpots.filter { it.spot.band == band }, controller, statuses, app, select) }
    }
}

@Composable private fun VerticalBandColumns(snapshot: BandMapUiSnapshot, controller: BandMapController, statuses: Map<String, SpotLogStatus>, app: AppController, select: (BandMapRankedSpot) -> Unit) {
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.selectedBands.forEach { band -> VerticalLane(band, snapshot.rankedSpots.filter { it.spot.band == band }, controller, statuses, app, select) }
    }
}

@Composable private fun BandGrid(snapshot: BandMapUiSnapshot, controller: BandMapController, statuses: Map<String, SpotLogStatus>, app: AppController, select: (BandMapRankedSpot) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(snapshot.selectedBands, key = { it }) { band ->
            val rows = snapshot.rankedSpots.filter { it.spot.band == band }
            Surface(Modifier.heightIn(min = 140.dp).clickable { controller.updateSettings { it.copy(selectedBands = listOf(band), selectedLayout = BandMapLayoutMode.SINGLE_EXPANDED) } },
                color = MapPanel, shape = RoundedCornerShape(10.dp)) { Column(Modifier.padding(10.dp)) {
                Text(band.uppercase(), color = MapAmber, fontWeight = FontWeight.Black)
                Text("${rows.size} visible · ${rows.count { it.spot.contest.newMultipliers.isNotEmpty() }} multipliers", color = MapMuted, fontSize = 11.sp)
                MiniFrequencyAxis(controller.visibleSegment(band))
                rows.take(4).forEach { SpotLabel(it, statuses[it.spot.id], app, select, controller.settings.labelMetadata,
                    labelSizeSp = controller.settings.spotLabelSizeSp) }
                if (rows.size > 5) Text("+${rows.size - 5} more", color = MapCyan, fontSize = 11.sp)
            } }
        }
    }
}

@Composable private fun SingleExpanded(snapshot: BandMapUiSnapshot, controller: BandMapController, statuses: Map<String, SpotLogStatus>, app: AppController, operatingContext: OperatingContextSnapshot, select: (BandMapRankedSpot) -> Unit) {
    val band = snapshot.selectedBands.firstOrNull() ?: return
    Column {
        val segment = controller.visibleSegment(band)
        Text("SINGLE BAND · $band · ${formatBandMapFrequency(segment.lowerHz)} – ${formatBandMapFrequency(segment.upperHz)}", color = MapAmber, fontWeight = FontWeight.Bold)
        BandViewportControls(band, controller)
        Text("Drag to pan · pinch to zoom · buttons and keyboard remain available", color = MapCyan, fontSize = 10.sp)
        Text("RX ${formatBandMapFrequency(operatingContext.receiveFrequencyHz.value)} · TX ${operatingContext.transmitFrequencyHz.value?.takeIf { it > 0 }?.let(::formatBandMapFrequency) ?: "same / unavailable"} · ${if (operatingContext.split.value) "SPLIT" else "SIMPLEX"} · PASSBAND ${operatingContext.receiveBandwidthHz.value.takeIf { it > 0 }?.let { "$it Hz" } ?: "unavailable"}", color = MapMuted, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        HorizontalLane(band, snapshot.rankedSpots.filter { it.spot.band == band }, controller, statuses, app, select, expanded = true, radioContext = operatingContext)
    }
}

@Composable private fun HorizontalLane(band: String, rows: List<BandMapRankedSpot>, controller: BandMapController,
    statuses: Map<String, SpotLogStatus>, app: AppController, select: (BandMapRankedSpot) -> Unit, expanded: Boolean = false,
    radioContext: OperatingContextSnapshot? = null) {
    val segment = controller.visibleSegment(band)
    val currentSegment by rememberUpdatedState(segment)
    val laneHeight = when (controller.settings.laneSize) {
        1 -> if (expanded) 320.dp else 170.dp
        3 -> if (expanded) 520.dp else 320.dp
        else -> if (expanded) 430.dp else 250.dp
    }
    Surface(color = MapPanel, shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth().height(laneHeight)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)
            .bandMapTouchGestures(band, controller, vertical = false) { currentSegment }
            .pointerInput(band) { awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent(); if (event.type == PointerEventType.Scroll) {
                    val change = event.changes.firstOrNull() ?: continue
                    val delta = change.scrollDelta.y
                    if (delta != 0f) { val activeSegment = currentSegment; controller.zoom(band, if (delta > 0f) 1.35 else .74,
                        activeSegment.lowerHz + (activeSegment.spanHz * (change.position.x / size.width.coerceAtLeast(1))).toLong()); change.consume() }
                }
            } } }) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val laneStepPx = with(density) { when (controller.settings.laneSize) { 1 -> 22.dp; 3 -> 42.dp; else -> 34.dp }.toPx() }
            val minimumLabelSpacingPx = with(density) {
                ((controller.settings.spotLabelSizeSp * if (expanded) 10 else 9).coerceIn(82, 150)).dp.toPx().roundToInt()
            }
            val placements = BandMapLayoutEngine.place(rows.map { it.spot }, segment, widthPx.roundToInt().coerceAtLeast(1), minimumLabelSpacingPx)
            val markerColors = placements.associate { placed -> placed.id to Color(app.spotStatusColour(SPOT_STATUS_DS, statuses[placed.id]?.dxccStatus)) }
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height - 22.dp.toPx(); drawLine(MapGrid, Offset(0f, y), Offset(size.width, y), 2f)
                BandMapDisplayPlans.forBand(band, controller.settings.iaruRegion).segments.forEach { plan ->
                    val left = BandMapLayoutEngine.coordinate(plan.lowerHz, segment) * size.width
                    val right = BandMapLayoutEngine.coordinate(plan.upperHz, segment) * size.width
                    if (right > 0 && left < size.width) drawRect(segmentColor(plan.kind).copy(alpha = .10f), Offset(left.coerceAtLeast(0f), 0f),
                        androidx.compose.ui.geometry.Size((right.coerceAtMost(size.width) - left.coerceAtLeast(0f)).coerceAtLeast(0f), y))
                    if (right > 0 && left < size.width) drawLine(segmentColor(plan.kind), Offset(left.coerceAtLeast(0f), y), Offset(right.coerceAtMost(size.width), y), 5f)
                }
                BandMapLayoutEngine.ticks(segment, size.width.roundToInt()).forEach { tick ->
                    val x = tick.position * size.width; drawLine(MapGrid, Offset(x, y - if (tick.major) 15f else 8f), Offset(x, y + 5f), if (tick.major) 2f else 1f)
                }
                radioContext?.let { context ->
                    val rx = context.receiveFrequencyHz.value
                    val bandwidth = context.receiveBandwidthHz.value
                    if (rx in segment.lowerHz..segment.upperHz && bandwidth > 0) {
                        val left = BandMapLayoutEngine.coordinate(rx - bandwidth / 2, segment) * size.width
                        val right = BandMapLayoutEngine.coordinate(rx + bandwidth / 2, segment) * size.width
                        drawRect(MapCyan.copy(alpha = .16f), Offset(left.coerceAtLeast(0f), 0f), androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(1f), y))
                    }
                    if (rx in segment.lowerHz..segment.upperHz) { val x = BandMapLayoutEngine.coordinate(rx, segment) * size.width; drawLine(MapCyan, Offset(x, 0f), Offset(x, y), 3f) }
                    context.transmitFrequencyHz.value?.takeIf { context.split.value && it in segment.lowerHz..segment.upperHz }?.let { tx ->
                        val x = BandMapLayoutEngine.coordinate(tx, segment) * size.width; drawLine(MapMagenta, Offset(x, 0f), Offset(x, y), 3f)
                    }
                }
                placements.forEach { placed ->
                    val anchorX = placed.primary * size.width
                    val labelY = 26.dp.toPx() + placed.lane * laneStepPx
                    drawLine(MapGrid, Offset(anchorX, y), Offset(anchorX, labelY), 1f)
                    drawCircle(markerColors[placed.id] ?: MapAmber, 3.dp.toPx(), Offset(anchorX, y))
                }
            }
            Text("${band.uppercase()} · ${formatBandMapFrequency(segment.lowerHz)}–${formatBandMapFrequency(segment.upperHz)}", color = MapAmber,
                fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopStart))
            BandScaleControls(band, controller, Modifier.align(Alignment.TopEnd))
            BandMapLayoutEngine.ticks(segment, widthPx.roundToInt()).filterIndexed { index, _ -> index % controller.settings.frequencyLabelEvery == 0 }.forEach { tick ->
                Text(formatBandMapFrequency(tick.frequencyHz), color = MapMuted, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomStart).offset { IntOffset((tick.position * widthPx).roundToInt().coerceIn(0, (widthPx - 70).roundToInt().coerceAtLeast(0)), 0) })
            }
            rows.forEach { ranked -> placements.firstOrNull { it.id == ranked.spot.id }?.let { placed ->
                Box(Modifier.offset { IntOffset(
                    (placed.primary * widthPx).roundToInt().coerceIn(0, (widthPx - with(density) { 138.dp.toPx() }).roundToInt().coerceAtLeast(0)),
                    with(density) { 26.dp.toPx() }.roundToInt() + (placed.lane * laneStepPx).roundToInt(),
                ) }.widthIn(max = (controller.settings.spotLabelSizeSp * 12).dp)) { SpotLabel(ranked, statuses[ranked.spot.id], app, select, controller.settings.labelMetadata,
                        labelSizeSp = controller.settings.spotLabelSizeSp,
                        showFrequency = false,
                        stackMembers = placed.memberIds.mapNotNull { id -> rows.firstOrNull { it.spot.id == id } }) }
            } }
        }
    }
}

@Composable private fun VerticalLane(band: String, rows: List<BandMapRankedSpot>, controller: BandMapController,
    statuses: Map<String, SpotLogStatus>, app: AppController, select: (BandMapRankedSpot) -> Unit,
    modifier: Modifier = Modifier.width(when (controller.settings.laneSize) { 1 -> 180.dp; 3 -> 300.dp; else -> 230.dp }).fillMaxHeight(),
    radioContext: OperatingContextSnapshot? = null,
    showScaleControls: Boolean = true) {
    val segment = controller.visibleSegment(band)
    val currentSegment by rememberUpdatedState(segment)
    Surface(color = MapPanel, shape = RoundedCornerShape(9.dp), modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)
            .bandMapTouchGestures(band, controller, vertical = true) { currentSegment }
            .pointerInput(band) { awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent(); if (event.type == PointerEventType.Scroll) {
                    val change = event.changes.firstOrNull() ?: continue; val delta = change.scrollDelta.y
                    if (delta != 0f) { val activeSegment = currentSegment; controller.zoom(band, if (delta > 0f) 1.35 else .74,
                        activeSegment.lowerHz + (activeSegment.spanHz * (change.position.y / size.height.coerceAtLeast(1))).toLong()); change.consume() }
                }
            } } }) {
            val density = LocalDensity.current
            val heightPx = with(density) { maxHeight.toPx() }
            val labelStartPx = with(density) { when (controller.settings.laneSize) { 1 -> 42.dp; 3 -> 54.dp; else -> 46.dp }.toPx() }
            val labelHeightPx = with(density) { (controller.settings.spotLabelSizeSp * 3.5f).dp.toPx() }
            val minimumLabelSpacingPx = with(density) { (controller.settings.spotLabelSizeSp * 4.4f).dp.toPx().roundToInt() }
            val plotTopPx = with(density) { (if (showScaleControls) 60.dp else 14.dp).toPx() }
            val plotBottomInsetPx = with(density) { 30.dp.toPx() }
            val plotBottomPx = (heightPx - plotBottomInsetPx).coerceAtLeast(plotTopPx + 1f)
            val plotHeightPx = (plotBottomPx - plotTopPx).coerceAtLeast(1f)
            val rawPlacements = BandMapLayoutEngine.place(rows.map { it.spot }, segment, plotHeightPx.roundToInt(), minimumLabelSpacingPx)
            val placements = BandMapLayoutEngine.fitVerticalLabels(
                rawPlacements, heightPx, labelHeightPx, plotTopPx, plotBottomInsetPx,
            )
            val markerColors = placements.associate { placed -> placed.id to Color(app.spotStatusColour(SPOT_STATUS_DS, statuses[placed.id]?.dxccStatus)) }
            val labelPositions = BandMapLayoutEngine.resolveVerticalLabels(
                placements, heightPx, labelHeightPx, plotTopPx, plotBottomInsetPx,
            )
            fun labelY(placed: BandMapPlacedSpot) = labelPositions[placed.id] ?: plotTopPx
            fun axisY(primary: Float) = plotTopPx + primary * plotHeightPx
            Canvas(Modifier.fillMaxSize()) {
                val x = 18.dp.toPx(); drawLine(MapGrid, Offset(x, plotTopPx), Offset(x, plotBottomPx), 2f)
                BandMapDisplayPlans.forBand(band, controller.settings.iaruRegion).segments.forEach { plan ->
                    val top = axisY(BandMapLayoutEngine.coordinate(plan.lowerHz, segment))
                    val bottom = axisY(BandMapLayoutEngine.coordinate(plan.upperHz, segment))
                    if (bottom > plotTopPx && top < plotBottomPx) {
                        drawRect(segmentColor(plan.kind).copy(alpha = .10f), Offset(0f, top.coerceAtLeast(plotTopPx)),
                            androidx.compose.ui.geometry.Size(size.width, (bottom.coerceAtMost(plotBottomPx) - top.coerceAtLeast(plotTopPx)).coerceAtLeast(0f)))
                        drawLine(segmentColor(plan.kind), Offset(x, top.coerceAtLeast(plotTopPx)), Offset(x, bottom.coerceAtMost(plotBottomPx)), 5f)
                    }
                }
                BandMapLayoutEngine.ticks(segment, size.height.roundToInt()).forEach { tick ->
                    val y = axisY(tick.position); drawLine(MapGrid, Offset(x - 5f, y), Offset(x + if (tick.major) 15f else 8f, y), 1f)
                }
                radioContext?.let { context ->
                    val rx = context.receiveFrequencyHz.value
                    val bandwidth = context.receiveBandwidthHz.value
                    if (rx in segment.lowerHz..segment.upperHz && bandwidth > 0) {
                        val top = axisY(BandMapLayoutEngine.coordinate(rx - bandwidth / 2, segment))
                        val bottom = axisY(BandMapLayoutEngine.coordinate(rx + bandwidth / 2, segment))
                        drawRect(MapCyan.copy(alpha = .16f), Offset(0f, top.coerceAtLeast(plotTopPx)),
                            androidx.compose.ui.geometry.Size(size.width, (bottom.coerceAtMost(plotBottomPx) - top.coerceAtLeast(plotTopPx)).coerceAtLeast(1f)))
                    }
                    if (rx in segment.lowerHz..segment.upperHz) {
                        val y = axisY(BandMapLayoutEngine.coordinate(rx, segment))
                        drawLine(MapCyan, Offset(0f, y), Offset(size.width, y), 3f)
                    }
                    context.transmitFrequencyHz.value?.takeIf { context.split.value && it in segment.lowerHz..segment.upperHz }?.let { tx ->
                        val y = axisY(BandMapLayoutEngine.coordinate(tx, segment))
                        drawLine(MapMagenta, Offset(0f, y), Offset(size.width, y), 3f)
                    }
                }
                placements.forEach { placed ->
                    val anchorY = axisY(placed.primary)
                    val targetY = labelY(placed)
                    drawLine(MapGrid, Offset(x, anchorY), Offset(labelStartPx - 6.dp.toPx(), targetY), 1f)
                    drawCircle(markerColors[placed.id] ?: MapAmber, 3.dp.toPx(), Offset(x, anchorY))
                }
            }
            if (showScaleControls) BandScaleControls(band, controller, Modifier.align(Alignment.TopCenter), includeBand = true)
            BandMapLayoutEngine.ticks(segment, heightPx.roundToInt()).filterIndexed { index, _ -> index % controller.settings.frequencyLabelEvery == 0 }.forEach { tick ->
                Text(formatBandMapFrequency(tick.frequencyHz), color = MapMuted, fontSize = 8.sp,
                    modifier = Modifier.offset { IntOffset(0, (axisY(tick.position) - with(density) { 8.dp.toPx() }).roundToInt()) })
            }
            placements.forEach { placed -> rows.firstOrNull { it.spot.id == placed.id }?.let { ranked ->
                Box(Modifier.offset { IntOffset(labelStartPx.roundToInt(), labelY(placed).roundToInt()) }
                    .widthIn(max = when (controller.settings.laneSize) { 1 -> 126.dp; 3 -> 236.dp; else -> 168.dp })) {
                    SpotLabel(ranked, statuses[ranked.spot.id], app, select, controller.settings.labelMetadata,
                        labelSizeSp = controller.settings.spotLabelSizeSp,
                        showFrequency = false,
                        stackMembers = placed.memberIds.mapNotNull { id -> rows.firstOrNull { it.spot.id == id } }) }
            } }
        }
    }
}

@Composable internal fun CompactRadioBandMap(
    controller: BandMapController,
    database: QsoDatabase,
    cty: CtyController,
    operatingContext: OperatingContextSnapshot,
    app: AppController,
    onAction: (WorkspaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = controller.snapshot
    val radioBand = bandMapBands.firstOrNull { operatingContext.receiveFrequencyHz.value in it.lowerHz..it.upperHz }?.name
        ?: snapshot.selectedBands.firstOrNull()
    var manualBand by remember { mutableStateOf<String?>(null) }
    val activeBand = manualBand ?: radioBand
    var selected by remember(activeBand) { mutableStateOf<BandMapRankedSpot?>(null) }
    if (activeBand == null) return
    val activeRows = snapshot.rankedSpots.filter { it.spot.band == activeBand }
    val statuses by produceState<Map<String, SpotLogStatus>>(emptyMap(), snapshot.generation, activeBand,
        operatingContext.stationProfileId.value, cty.dataRevision) {
        val identities = activeRows.map { ranked ->
            val entity = cty.lookup(ranked.spot.callsign)
            SpotLogIdentity(ranked.spot.id, ranked.spot.callsign, entity?.dxcc.orEmpty(), entity?.country.orEmpty(),
                ranked.spot.band, ranked.spot.modeFamily.name)
        }
        value = withContext(Dispatchers.IO) { database.spotStatuses(identities, operatingContext.stationProfileId.value) }
    }
    Column(modifier.background(MapBackground).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Column(Modifier.fillMaxWidth().clickable {
            val index = bandMapVisibleBands.indexOf(activeBand).takeIf { it >= 0 } ?: -1
            manualBand = bandMapVisibleBands[(index + 1) % bandMapVisibleBands.size]
        }.semantics { contentDescription = "Band Map $activeBand, tap to show the next band" }) {
            Text("BAND MAP · $activeBand", color = MapAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text("TAP HEADER TO CHANGE BAND", color = MapCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        BandScaleControls(activeBand, controller, Modifier.fillMaxWidth())
        Text("Drag/pinch · RX cyan · TX magenta", color = MapMuted, fontSize = 8.sp)
        VerticalLane(activeBand, activeRows, controller, statuses, app,
            { selected = it; controller.select(it.spot.id) }, Modifier.fillMaxWidth().weight(1f), operatingContext, showScaleControls = false)
    }
    selected?.let { ranked ->
        AlertDialog(onDismissRequest = { selected = null; controller.select(null) },
            title = { Text(ranked.spot.callsign) },
            text = { Text("${formatBandMapFrequency(ranked.spot.frequencyHz)} · ${ranked.spot.modeFamily.name}\nReviewing this action never transmits.") },
            confirmButton = { Button({
                controller.workspaceAction(ranked.spot, WorkspaceDestination.RADIO, snapshot.contextGeneration)?.let(onAction)
                selected = null; controller.select(null)
            }) { Text("REVIEW RX") } },
            dismissButton = { OutlinedButton({ selected = null; controller.select(null) }) { Text("CLOSE") } })
    }
}

@Composable private fun BandScaleControls(band: String, controller: BandMapController, modifier: Modifier = Modifier, includeBand: Boolean = false) {
    val span = controller.visibleSegment(band).spanHz
    val spanLabel = if (span >= 1_000L) "${(span + 500L) / 1_000L} kHz" else "$span Hz"
    Row(modifier.heightIn(min = 56.dp).background(MapBackground.copy(alpha = .88f), RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clickable { controller.zoom(band, 1.5) }
            .semantics { contentDescription = "Widen $band band map spacing" }, contentAlignment = Alignment.Center) {
            Text("−", color = MapText, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
        Text(if (includeBand) "${band.uppercase()} · $spanLabel" else spanLabel, color = MapCyan, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp)
                .semantics { contentDescription = "$band band map span $span hertz" })
        Box(Modifier.size(56.dp).clickable { controller.zoom(band, 2.0 / 3.0) }
            .semantics { contentDescription = "Narrow $band band map spacing" }, contentAlignment = Alignment.Center) {
            Text("+", color = MapText, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable private fun SpotLabel(ranked: BandMapRankedSpot, status: SpotLogStatus?, app: AppController, select: (BandMapRankedSpot) -> Unit,
    metadata: Set<BandMapLabelMetadata> = emptySet(), showFrequency: Boolean = true, labelSizeSp: Int = 11,
    stackMembers: List<BandMapRankedSpot> = listOf(ranked)) {
    val spot = ranked.spot
    var stackOpen by remember(stackMembers.map { it.spot.id }) { mutableStateOf(false) }
    val accent = when { spot.contest.newMultipliers.isNotEmpty() -> MapMagenta; spot.need.entity == BandMapNeedTruth.NEEDED -> MapGreen; spot.chaser.eligible == true -> MapCyan; else -> MapAmber }
    Column(Modifier.padding(1.dp).clickable { if (stackMembers.size > 1) stackOpen = true else select(ranked) }.padding(horizontal = 3.dp, vertical = 1.dp)
        .semantics { contentDescription = "${spot.callsign}, ${spot.band}, ${spot.frequencyHz} hertz, call status ${status?.callStatus ?: "unknown"}, entity status ${status?.dxccStatus ?: "unknown"}, priority ${ranked.score}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spot.callsign, color = Color(app.spotStatusColour(SPOT_STATUS_CS, status?.callStatus)),
                fontWeight = FontWeight.Bold, fontSize = labelSizeSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (BandMapLabelMetadata.CALL_STATUS in metadata) {
                Spacer(Modifier.width(8.dp))
                Text(status?.callStatus ?: "?", color = Color(app.spotStatusColour(SPOT_STATUS_CS, status?.callStatus)),
                    fontWeight = FontWeight.Bold, fontSize = (labelSizeSp - 1).coerceAtLeast(8).sp)
            }
            if (BandMapLabelMetadata.DXCC_STATUS in metadata) {
                Spacer(Modifier.width(6.dp))
                Text(status?.dxccStatus ?: "?", color = Color(app.spotStatusColour(SPOT_STATUS_DS, status?.dxccStatus)),
                    fontWeight = FontWeight.Bold, fontSize = (labelSizeSp - 1).coerceAtLeast(8).sp)
            }
            if (stackMembers.size > 1) Text(" +${stackMembers.size - 1}", color = MapMagenta, fontWeight = FontWeight.Black, fontSize = (labelSizeSp - 2).coerceAtLeast(8).sp)
        }
        if (showFrequency) Text(formatBandMapFrequency(spot.frequencyHz), color = MapMuted, fontSize = (labelSizeSp - 2).coerceAtLeast(8).sp)
        val observation = spot.observations.maxByOrNull(BandMapSourceObservation::observedEpoch)
        val details = buildList {
            if (BandMapLabelMetadata.AGE in metadata) add("${((Instant.now().epochSecond - spot.newestObservationEpoch).coerceAtLeast(0) / 60)}m")
            if (BandMapLabelMetadata.BEARING in metadata) observation?.bearingDegrees?.let { add("$it°") }
            if (BandMapLabelMetadata.DISTANCE in metadata) observation?.distanceKm?.let { add("$it km") }
            if (BandMapLabelMetadata.MODE in metadata) add(spot.submode.ifBlank { spot.modeFamily.name })
            if (BandMapLabelMetadata.SPOTTER in metadata) observation?.spotterCallsign?.takeIf(String::isNotBlank)?.let(::add)
            if (BandMapLabelMetadata.SOURCE in metadata) add(spot.sources.joinToString("/") { it.name })
            if (BandMapLabelMetadata.SNR in metadata) observation?.snr?.let { add("$it dB") }
        }
        if (details.isNotEmpty()) Text(details.joinToString(" · "), color = MapCyan, fontSize = (labelSizeSp - 3).coerceAtLeast(8).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (stackOpen) AlertDialog(onDismissRequest = { stackOpen = false }, title = { Text("${stackMembers.size} spots at this position") },
        text = { LazyColumn { items(stackMembers.take(20), key = { it.spot.id }) { member ->
            TextButton({ stackOpen = false; select(member) }, modifier = Modifier.fillMaxWidth()) { Text("${member.spot.callsign} · ${formatBandMapFrequency(member.spot.frequencyHz)}") }
        } } }, confirmButton = { TextButton({ stackOpen = false }) { Text("CLOSE") } })
}

@Composable private fun MiniFrequencyAxis(segment: BandMapSegment) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f; drawLine(MapGrid, Offset(0f, y), Offset(size.width, y), 1f)
            BandMapLayoutEngine.ticks(segment, size.width.roundToInt()).forEach { tick ->
                val x = tick.position * size.width; drawLine(MapGrid, Offset(x, y - if (tick.major) 7f else 4f), Offset(x, y + 4f), 1f)
            }
        }
        BandMapLayoutEngine.ticks(segment, widthPx.roundToInt()).filter(BandMapTick::major).forEach { tick ->
            Text(formatBandMapFrequency(tick.frequencyHz), color = MapMuted, fontSize = 8.sp,
                modifier = Modifier.offset { IntOffset((tick.position * widthPx).roundToInt().coerceIn(0, (widthPx - 60).roundToInt().coerceAtLeast(0)), 16) })
        }
    }
}

@Composable private fun BandViewportControls(band: String, controller: BandMapController) {
    val segment = controller.visibleSegment(band)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.zoom(band, .5) }) { Text("+") }
            OutlinedButton({ controller.zoom(band, 2.0) }) { Text("−") }
            OutlinedButton({ controller.pan(band, -.25) }) { Text("←") }
            OutlinedButton({ controller.pan(band, .25) }) { Text("→") }
            OutlinedButton({ controller.resetViewport(band) }) { Text("RESET") }
            FilterChip(controller.settings.linkedZoom, { controller.updateSettings { it.copy(linkedZoom = !it.linkedZoom) } }, { Text("LINKED ZOOM") })
        }
        Text("Viewport ${formatBandMapFrequency(segment.lowerHz)} – ${formatBandMapFrequency(segment.upperHz)} · pinch, drag, wheel and keyboard +/− supported where focus is owned",
            color = MapMuted, fontSize = 10.sp)
        Text("Operating guidance · ${controller.settings.iaruRegion.name.replace('_', ' ')} · CW amber · DATA cyan · SSB/PHONE green · FM magenta · not regulatory authority",
            color = MapMuted, fontSize = 10.sp)
    }
}

private fun formatBandMapFrequency(value: Long): String = if (value >= 1_000_000L) "%.3f MHz".format(value / 1_000_000.0) else "%.1f kHz".format(value / 1_000.0)

private fun segmentColor(kind: BandMapOperatingSegmentKind): Color = when (kind) {
    BandMapOperatingSegmentKind.CW -> MapAmber
    BandMapOperatingSegmentKind.DATA -> MapCyan
    BandMapOperatingSegmentKind.PHONE -> MapGreen
    BandMapOperatingSegmentKind.FM_REPEATER -> MapMagenta
    BandMapOperatingSegmentKind.BEACON_SATELLITE -> MapMuted
    BandMapOperatingSegmentKind.CUSTOM -> MapText
}

@Composable private fun SpotDetail(ranked: BandMapRankedSpot, status: SpotLogStatus?, app: AppController, contextGeneration: Long, controller: BandMapController,
    onAction: (WorkspaceAction) -> Unit, mark: (BandMapMarkKind) -> Unit, dismiss: () -> Unit) {
    val spot = ranked.spot
    AlertDialog(onDismissRequest = dismiss, title = { Text("${spot.callsign} · ${spot.band} · ${"%.3f".format(spot.frequencyHz / 1_000_000.0)} MHz") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item { Text(ranked.explanation, color = MapAmber, fontWeight = FontWeight.Bold) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CS ${status?.callStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_CS, status?.callStatus)), fontWeight = FontWeight.Bold)
                Text("DS ${status?.dxccStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_DS, status?.dxccStatus)), fontWeight = FontWeight.Bold)
            } }
            item { Text("Sources: ${spot.observations.joinToString { "${it.source.name} ${it.spotterCallsign}" }}") }
            item { Text("Need: entity ${spot.need.entity} · band ${spot.need.band} · mode ${spot.need.mode} · slot ${spot.need.bandMode}") }
            item { Text("Contest: ${if (spot.contest.active) "active" else "inactive"} · dupe ${spot.contest.duplicate ?: "unknown"} · multipliers ${spot.contest.newMultipliers.ifEmpty { setOf("none") }}") }
            item { Text("DX Chaser: ${if (!spot.chaser.available) "unavailable" else "${spot.chaser.priorityTier} · eligible ${spot.chaser.eligible}"} · target ${spot.chaser.currentTarget} · engaged ${spot.chaser.engagedTarget}") }
            item { Text("Evidence: ${spot.evidence.joinToString { "${it.kind}:${it.status}" }.ifBlank { "unavailable" }}") }
            item { Text("Observed ${Instant.now().epochSecond - spot.newestObservationEpoch}s ago · ${spot.spotters.size} independent spotters") }
            item { HorizontalDivider(); Text("Actions are receive-reviewed. Selection, marks and navigation send no CAT or TX.", color = MapMuted, fontSize = 11.sp) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BandMapMarkKind.entries.forEach { kind -> OutlinedButton({ mark(kind) }) { Text(kind.name) } }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ controller.workspaceAction(spot, WorkspaceDestination.RADIO, contextGeneration)?.let(onAction) }) { Text("REVIEW RX") }
                OutlinedButton({ controller.workspaceAction(spot, WorkspaceDestination.DX, contextGeneration)?.let(onAction) }) { Text("DX DETAILS") }
                OutlinedButton({ controller.workspaceAction(spot, WorkspaceDestination.LOGBOOK, contextGeneration)?.let(onAction) }) { Text("HISTORY") }
                OutlinedButton({ controller.workspaceAction(spot, WorkspaceDestination.DX_CHASER, contextGeneration)?.let(onAction) }) { Text("OPEN CHASER") }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ controller.workspaceAction(spot, WorkspaceDestination.CALLBOOK, contextGeneration)?.let(onAction) }) { Text("CALLBOOK") }
                OutlinedButton({ controller.workspaceAction(spot, WorkspaceDestination.CONTEST, contextGeneration)?.let(onAction) }) { Text("CONTEST") }
                OutlinedButton({ controller.workspaceAction(spot, WorkspaceDestination.DIGI, contextGeneration)?.let(onAction) }) { Text("PREPARE DIGI") }
                if (spot.portablePrograms.isNotEmpty()) OutlinedButton({ controller.workspaceAction(spot, WorkspaceDestination.PORTABLE, contextGeneration)?.let(onAction) }) { Text("ACTIVATION") }
            } }
        } }, confirmButton = { TextButton(dismiss) { Text("CLOSE") } })
}

@Composable private fun FilterDialog(controller: BandMapController, app: AppController, dismiss: () -> Unit) {
    val preset = controller.settings.presets.firstOrNull { it.id == controller.settings.activePresetId } ?: builtInBandMapPresets.first()
    var draft by remember(preset.id) { mutableStateOf(preset.filter) }
    var callStatuses by remember { mutableStateOf(controller.settings.callStatusFilters) }
    var dxccStatuses by remember { mutableStateOf(controller.settings.dxccStatusFilters) }
    var segmentProfile by remember(preset.id) { mutableStateOf(BandMapSegmentProfile.WHOLE) }
    var customLower by remember(preset.id) { mutableStateOf("") }; var customUpper by remember(preset.id) { mutableStateOf("") }
    fun <T> toggle(set: Set<T>, value: T) = if (value in set) set - value else set + value
    AlertDialog(onDismissRequest = dismiss, title = { Text("Band Map filters") }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 570.dp)) {
        item { OutlinedTextField(draft.search, { draft = draft.copy(search = it.take(80)) }, label = { Text("Callsign, comment or reference") }) }
        item { Text("MODE FAMILY", fontWeight = FontWeight.Bold); ChipFlow { BandMapModeFamily.entries.forEach { value -> FilterChip(value in draft.modes, { draft = draft.copy(modes = toggle(draft.modes, value)) }, { Text(value.name) }) } } }
        item { Text("SOURCE", fontWeight = FontWeight.Bold); ChipFlow { BandMapSource.entries.forEach { value -> FilterChip(value in draft.sources, { draft = draft.copy(sources = toggle(draft.sources, value)) }, { Text(value.name.replace('_', ' ')) }) } } }
        item { Text("CS · CALL STATUS", fontWeight = FontWeight.Bold); ChipFlow { spotCallStatusOptions.forEach { value ->
            val color = Color(app.spotStatusColour(SPOT_STATUS_CS, value))
            FilterChip(value in callStatuses, { callStatuses = toggle(callStatuses, value) }, { Text(value) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = .22f), selectedLabelColor = color))
        } } }
        item { Text("DS · DXCC STATUS", fontWeight = FontWeight.Bold); ChipFlow { spotDxccStatusOptions.forEach { value ->
            val color = Color(app.spotStatusColour(SPOT_STATUS_DS, value))
            FilterChip(value in dxccStatuses, { dxccStatuses = toggle(dxccStatuses, value) }, { Text(value) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = .22f), selectedLabelColor = color))
        } } }
        item { Text("DISPLAY SEGMENT · not a legal band plan", fontWeight = FontWeight.Bold); ChipFlow { BandMapSegmentProfile.entries.forEach { value -> FilterChip(segmentProfile == value, {
            segmentProfile = value
            if (value != BandMapSegmentProfile.CUSTOM) draft = draft.copy(segments = controller.settings.selectedBands.map { bandMapDisplaySegment(it, value) })
        }, { Text(value.name.replace('_', ' ')) }) } } }
        if (segmentProfile == BandMapSegmentProfile.CUSTOM) item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(customLower, { customLower = it.filter(Char::isDigit).take(12) }, label = { Text("Start kHz") }, modifier = Modifier.weight(1f))
            OutlinedTextField(customUpper, { customUpper = it.filter(Char::isDigit).take(12) }, label = { Text("End kHz") }, modifier = Modifier.weight(1f))
        } }
        item { Text("AGE / DIVERSITY", fontWeight = FontWeight.Bold); ChipFlow {
            listOf(300L, 900L, 3_600L, 10_800L).forEach { seconds -> FilterChip(draft.maximumAgeSeconds == seconds, { draft = draft.copy(maximumAgeSeconds = seconds) }, { Text(if (seconds < 3_600) "${seconds / 60}m" else "${seconds / 3_600}h") }) }
            (0..3).forEach { count -> FilterChip(draft.minimumSpotters == count, { draft = draft.copy(minimumSpotters = count) }, { Text("$count+ spotters") }) }
            (0..3).forEach { count -> FilterChip(draft.minimumSourceDiversity == count, { draft = draft.copy(minimumSourceDiversity = count) }, { Text("$count+ sources") }) }
        } }
        item { Text("SPOTTER CONTINENT", fontWeight = FontWeight.Bold); ChipFlow { listOf("AF", "AS", "EU", "NA", "OC", "SA").forEach { value -> FilterChip(value in draft.spotterContinents, { draft = draft.copy(spotterContinents = toggle(draft.spotterContinents, value)) }, { Text(value) }) } } }
        item { Text("TARGET CONTINENT", fontWeight = FontWeight.Bold); ChipFlow { listOf("AF", "AS", "EU", "NA", "OC", "SA").forEach { value -> FilterChip(value in draft.targetContinents, { draft = draft.copy(targetContinents = toggle(draft.targetContinents, value)) }, { Text(value) }) } } }
        item { Text("NEEDS", fontWeight = FontWeight.Bold); ChipFlow { listOf("ENTITY", "BAND", "MODE", "BAND_MODE", "GRID", "CQ", "ITU", "PORTABLE", "UNKNOWN").forEach { value -> FilterChip(value in draft.requiredNeeds, { draft = draft.copy(requiredNeeds = toggle(draft.requiredNeeds, value)) }, { Text(value) }) } } }
        item { Text("CONTEST / CHASER", fontWeight = FontWeight.Bold); ChipFlow {
            FilterChip(draft.contestOnly, { draft = draft.copy(contestOnly = !draft.contestOnly) }, { Text("CONTEST ACTIVE") })
            FilterChip(draft.multipliersOnly, { draft = draft.copy(multipliersOnly = !draft.multipliersOnly) }, { Text("NEW MULT") })
            FilterChip(draft.hideDuplicates, { draft = draft.copy(hideDuplicates = !draft.hideDuplicates) }, { Text("HIDE DUPES") })
            FilterChip(draft.chaserEligibleOnly, { draft = draft.copy(chaserEligibleOnly = !draft.chaserEligibleOnly) }, { Text("CHASER ELIGIBLE") })
        } }
        item { Text("PORTABLE", fontWeight = FontWeight.Bold); ChipFlow { listOf("POTA", "SOTA", "WWFF").forEach { value -> FilterChip(value in draft.portablePrograms, { draft = draft.copy(portablePrograms = toggle(draft.portablePrograms, value)) }, { Text(value) }) } } }
        item { Text("CURRENT EVIDENCE STATUS", fontWeight = FontWeight.Bold); ChipFlow { BandMapEvidenceStatus.entries.forEach { value -> FilterChip(value in draft.evidenceStatuses, { draft = draft.copy(evidenceStatuses = toggle(draft.evidenceStatuses, value)) }, { Text(value.name) }) } } }
        item { ChipFlow {
            FilterChip(draft.showUnknown, { draft = draft.copy(showUnknown = !draft.showUnknown) }, { Text("SHOW UNKNOWN") })
            FilterChip(draft.showStale, { draft = draft.copy(showStale = !draft.showStale) }, { Text("SHOW STALE") })
        }; Text("Filters remain separate from priority ranking. Unknown provider data is not silently classified as needed.", color = MapMuted, fontSize = 11.sp) }
    } }, confirmButton = { Button({
        if (segmentProfile == BandMapSegmentProfile.CUSTOM) {
            val band = controller.settings.selectedBands.first(); val definition = bandMapBands.first { it.name == band }
            val low = customLower.toLongOrNull()?.times(1_000); val high = customUpper.toLongOrNull()?.times(1_000)
            if (low != null && high != null && low >= definition.lowerHz && high <= definition.upperHz && high > low) draft = draft.copy(segments = listOf(BandMapSegment(band, "Custom display range", low, high)))
        }
        controller.updateSettings { current -> current.copy(
            callStatusFilters = callStatuses,
            dxccStatusFilters = dxccStatuses,
            presets = current.presets.map { if (it.id == preset.id) it.copy(filter = draft) else it },
        ) }; dismiss()
    }) { Text("APPLY") } },
        dismissButton = { Row {
            TextButton({
                draft = BandMapFilter()
                callStatuses = emptySet()
                dxccStatuses = emptySet()
            }) { Text("RESET FILTERS") }
            TextButton(dismiss) { Text("CANCEL") }
        } })
}

@Composable private fun ChipFlow(content: @Composable RowScope.() -> Unit) = Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)

@Composable
internal fun BandMapSettingsPanel(controller: BandMapController) {
    val value = controller.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("INTELLIGENT BAND MAPS", fontWeight = FontWeight.Black)
        when (controller.saveTruth.status) {
            BandMapSaveStatus.SAVING -> Text("Saving…", style = MaterialTheme.typography.labelMedium)
            BandMapSaveStatus.SAVED -> Text(if (controller.saveTruth.savedEpoch == 0L) "Saved automatically"
                else "Saved automatically · ${java.time.Instant.ofEpochSecond(controller.saveTruth.savedEpoch).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}",
                style = MaterialTheme.typography.labelMedium)
            BandMapSaveStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Save failed", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(controller::retrySave) { Text("RETRY") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(value.enabled, { controller.updateSettings { it.copy(enabled = !it.enabled) } }, { Text(if (value.enabled) "ENABLED" else "DISABLED") })
            FilterChip(value.navigationVisible, { controller.updateSettings { it.copy(navigationVisible = !it.navigationVisible) } }, { Text("SHOW IN NAV") })
            FilterChip(value.palette == "COLOUR_VISION_FRIENDLY", { controller.updateSettings { it.copy(palette = "COLOUR_VISION_FRIENDLY") } }, { Text("COLOUR-VISION PALETTE") })
        }
        Text("DISPLAY GUIDANCE REGION", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BandMapIaruRegion.entries.forEach { region -> FilterChip(value.iaruRegion == region,
                { controller.updateSettings { it.copy(iaruRegion = region) } }, { Text(region.description()) }) }
        }
        Text("OPERATING JURISDICTION", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BandMapJurisdiction.entries.forEach { jurisdiction -> FilterChip(value.jurisdiction == jurisdiction,
                { controller.updateSettings { it.copy(jurisdiction = jurisdiction) } }, { Text(jurisdiction.name.replace('_', ' ')) }) }
        }
        Text("LABEL METADATA · ladders show callsigns on the calibrated frequency axis; exact frequency remains in row/detail views", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BandMapLabelMetadata.entries.forEach { metadata -> FilterChip(metadata in value.labelMetadata, { controller.updateSettings {
                current -> current.copy(labelMetadata = if (metadata in current.labelMetadata) current.labelMetadata - metadata else current.labelMetadata + metadata)
            } }, { Text(when (metadata) {
                BandMapLabelMetadata.AGE -> "SPOT AGE"
                BandMapLabelMetadata.CALL_STATUS -> "CS"
                BandMapLabelMetadata.DXCC_STATUS -> "DS"
                else -> metadata.name
            }) }) }
        }
        Text("BAND MAP SIZE", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "COMPACT", 2 to "STANDARD", 3 to "WIDE").forEach { (size, label) ->
                FilterChip(value.laneSize == size, { controller.updateSettings { it.copy(laneSize = size) } }, { Text(label) })
            }
        }
        Text("SPOT LABEL SIZE", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(9, 11, 13, 16).forEach { size ->
                FilterChip(value.spotLabelSizeSp == size, { controller.updateSettings { it.copy(spotLabelSizeSp = size) } }, { Text("$size sp") })
            }
        }
        Text("FREQUENCY LABELS", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "EVERY TICK", 2 to "EVERY 2", 5 to "EVERY 5").forEach { (interval, label) ->
                FilterChip(value.frequencyLabelEvery == interval,
                    { controller.updateSettings { it.copy(frequencyLabelEvery = interval) } }, { Text(label) })
            }
        }
        FilterChip(value.showOnRadioScreen, { controller.updateSettings { it.copy(showOnRadioScreen = !it.showOnRadioScreen) } },
            { Text(if (value.showOnRadioScreen) "VERTICAL BAND MAP ON RADIO · ON" else "VERTICAL BAND MAP ON RADIO · OFF") })
        Text("Colored frequency rails: CW · data · phone/SSB · FM/repeater where present in the selected regional display guidance.",
            style = MaterialTheme.typography.bodySmall)
        Text("ShackCQ reviewed IARU display guidance 2026-08 · operating guidance only, not country-specific regulatory authority · 60m uncertainty explicit",
            style = MaterialTheme.typography.bodySmall)
        Text("Settings, editable built-in presets, layouts, selected bands and local marks are included in configuration backup. Restore never triggers CAT, Keyer, Digi or DX Chaser actions.",
            style = MaterialTheme.typography.bodySmall)
        Text("Schema $BAND_MAP_SETTINGS_SCHEMA · ${value.presets.size} presets · ${value.marks.size} local marks", style = MaterialTheme.typography.labelSmall)
    }
}
