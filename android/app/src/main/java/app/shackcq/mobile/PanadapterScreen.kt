package app.shackcq.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

private val PanChassis = Color(0xFF111519)
private val PanPanel = Color(0xFF1B2228)
private val PanRaised = Color(0xFF283139)
private val PanInk = Color(0xFFF4F0E7)
private val PanMuted = Color(0xFFA5ADB2)
private val PanAmber = Color(0xFFE9A72B)
private val PanHold = Color(0xFFF4C94E)
private val PanHealthy = Color(0xFF42C77B)
private val PanDanger = Color(0xFFE4544D)

@Composable
fun PanadapterScreen(
    controller: PanadapterController,
    radio: RadioState,
    spots: List<AndroidDXSpot>,
    compact: Boolean,
    localReceivers: LocalReceiverController? = null,
    workbench: AndroidSdrWorkbenchV4? = null,
    onControls: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var inspector by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var levelCalibrationDialog by rememberSaveable { mutableStateOf(false) }
    var markerAHz by rememberSaveable { mutableStateOf(0L) }
    var markerBHz by rememberSaveable { mutableStateOf(0L) }
    var activeMarker by rememberSaveable { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var viewZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var viewPan by rememberSaveable { mutableFloatStateOf(0f) }
    var spectrumFraction by rememberSaveable { mutableFloatStateOf(.41f) }
    var displayMode by rememberSaveable { mutableIntStateOf(0) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start() else message = "RECORD_AUDIO permission denied"
    }
    val frame = controller.frame
    val center = controller.effectiveCenter()
    val provenSpan = frame?.effectiveSampleRate ?: controller.routeProof.physicalRate
    val usableSpan = provenSpan * controller.displayMetrics.validBinFraction
    val span = (provenSpan.takeIf { it > 0 } ?: controller.settings.requestedRate).toFloat() / viewZoom
    val autoFloorDb = if (controller.settings.autoLevel && controller.displayMetrics.stabilizedFloorDb.isFinite())
        controller.displayMetrics.spectrumFloorDb else controller.settings.displayFloorDb
    val autoTopDb = if (controller.settings.autoLevel && controller.displayMetrics.stabilizedFloorDb.isFinite())
        controller.displayMetrics.spectrumTopDb else controller.settings.displayTopDb
    val visibleStart = center - span / 2f + viewPan
    val visibleEnd = center + span / 2f + viewPan
    val visibleSpots = remember(spots, visibleStart, visibleEnd, controller.settings.showSpots) {
        if (!controller.settings.showSpots) emptyList() else spots.filter { it.frequencyHz in visibleStart.toLong()..visibleEnd.toLong() }.take(20)
    }

    LaunchedEffect(radio.revision) { controller.observeRadioState(radio) }
    LaunchedEffect(markerAHz, markerBHz) {
        workbench?.measurement?.setMarkerA(markerAHz.takeIf { it > 0 })
        workbench?.measurement?.setMarkerB(markerBHz.takeIf { it > 0 })
    }
    DisposableEffect(Unit) { onDispose { controller.stop() } }
    DisposableEffect(controller.lifecycle, controller.settings.keepScreenAwake) {
        view.keepScreenOn = controller.lifecycle == PanadapterLifecycle.LIVE && controller.settings.keepScreenAwake
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(immersive) {
        context.activity()?.window?.let { window ->
            WindowInsetsControllerCompat(window, view).let { insets ->
                if (immersive) {
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                    insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else insets.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { if (immersive) context.activity()?.window?.let { WindowInsetsControllerCompat(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }

    Column(Modifier.fillMaxSize().background(PanChassis).navigationBarsPadding().padding(if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanadapterHeader(controller, radio, compact, onControls,
            onStart = { if (controller.hasRecordPermission()) controller.start() else permission.launch(Manifest.permission.RECORD_AUDIO) },
            onStop = { controller.stop() }, onInspector = { inspector = true }, onDiagnostics = { diagnostics = true })
        PanadapterTruthStrip(controller)
        localReceivers?.let { LocalReceiverRail(it, "STEREO I/Q", 0, center, provenSpan) }
        if (workbench != null) SdrStereoWorkbenchStrip(workbench, controller, radio, localReceivers)

        if (radio.transmitting) {
            Surface(color = PanDanger, modifier = Modifier.fillMaxWidth()) {
                Text("TRANSMIT — RECEIVE SPECTRUM FROZEN", color = Color.White, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        if (displayMode != 2) BoxWithConstraints(Modifier.fillMaxWidth()
            .weight(if (displayMode == 1) 1f else spectrumFraction)
            .border(1.dp, PanRaised).background(Color(0xFF0A0D10))) {
            val density = maxWidth.value.coerceAtLeast(1f)
            SpectrumCanvas(frame, radio, center, span, viewPan, markerAHz, markerBHz, visibleSpots, controller.settings,
                autoFloorDb, autoTopDb,
                Modifier.fillMaxSize().semantics { contentDescription = spectrumDescription(frame, center) }
                    .pointerInput(center, span, viewPan) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            viewZoom = (viewZoom * zoom).coerceIn(1f, 16f)
                            viewPan = (viewPan - pan.x / size.width * span).coerceIn(-span / 2f, span / 2f)
                        }
                    }
                    .pointerInput(center, span, viewPan, activeMarker) {
                        detectTapGestures { offset ->
                            val frequency = frequencyAt(offset.x, size.width.toFloat(), center, span, viewPan)
                            if (activeMarker == 0) { markerAHz = frequency; activeMarker = 1 }
                            else { markerBHz = frequency; activeMarker = 0 }
                        }
                    }
                    .pointerInput(center, span, viewPan, activeMarker) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val frequency = frequencyAt(change.position.x, size.width.toFloat(), center, span, viewPan)
                            if (activeMarker == 0) markerAHz = frequency else markerBHz = frequency
                        }
                    })
            Text("${if (provenSpan > 0) "TRUE" else "REQUESTED"} · ${if (viewZoom > 1f) "VIEW ${"%.1f".format(viewZoom)}× · " else ""}${"%.1f".format(span / 1000f)} kHz nominal" +
                if (usableSpan > 0f) " · ${"%.1f".format(usableSpan / 1000f)} kHz usable" else "",
                color = PanMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }

        if (displayMode == 0) Box(Modifier.fillMaxWidth().height(8.dp).background(PanRaised)
            .semantics { contentDescription = "Drag to resize spectrum and waterfall" }
            .pointerInput(Unit) { detectDragGestures { change, drag ->
                change.consume(); spectrumFraction = (spectrumFraction + drag.y / 900f).coerceIn(.2f, .8f)
            } })

        if (displayMode != 1) WaterfallCanvas(controller, center, span, viewPan, markerAHz, markerBHz,
            Modifier.fillMaxWidth().weight(if (displayMode == 2) 1f else 1f - spectrumFraction).border(1.dp, PanRaised)
                .semantics { contentDescription = "Live waterfall, newest row at top" })

        PanadapterActionStrip(controller, radio, frame, center, markerAHz, markerBHz,
            onTuneA = { if (markerAHz > 0) message = controller.tune(markerAHz) },
            onTuneB = { if (markerBHz > 0) message = controller.tune(markerBHz) },
            onUndo = { message = controller.undoLastQsy() },
            onCalibrate = { message = controller.startCalibration((markerAHz - center).toFloat()) },
            onLevelCalibrate = { levelCalibrationDialog = true },
            onRecord = { message = if (controller.recordingStatus.startsWith("Recording")) controller.stopRecording() else controller.startRecording(10) },
            onReplay = { message = controller.replayLastRecording() },
            onResetView = { viewZoom = 1f; viewPan = 0f },
            onDisplayMode = { displayMode = (displayMode + 1) % 3 },
            onImmersive = { immersive = !immersive },
            compact = compact)
        localReceivers?.let { LocalReceiverTapActions(it, "STEREO I/Q", 0, center, provenSpan, markerAHz.takeIf { value -> value > 0 }) }
        if (message.isNotBlank()) Text(message, color = if (message.contains("blocked", true) || message.contains("denied", true)) PanDanger else PanHold,
            fontSize = 11.sp, maxLines = 1)
    }

    if (inspector) PanadapterInspector(controller, onDismiss = { inspector = false }, onMessage = { message = it })
    if (diagnostics) PanadapterDiagnostics(controller, radio, onDismiss = { diagnostics = false }, onMessage = { message = it })
    if (levelCalibrationDialog) LevelCalibrationDialog(controller,
        onDismiss = { levelCalibrationDialog = false }, onMessage = { message = it })
    controller.calibrationCandidate?.takeIf { it.rejectionAfterDb != null }?.let { candidate ->
        AlertDialog(onDismissRequest = { message = controller.cancelCalibration() },
            title = { Text("Confirm I/Q calibration") },
            text = { Text("Known tone ${if (candidate.knownOffsetHz >= 0) "+" else ""}${"%.0f".format(candidate.knownOffsetHz)} Hz\n" +
                "Measured ${"%+.1f".format(candidate.measuredOffsetHz)} Hz · axis error ${"%+.1f".format(candidate.axisErrorHz)} Hz\n" +
                "Desired ${"%.1f".format(candidate.desiredLevelDb)} dBFS · image ${"%.1f".format(candidate.imageLevelDb)} dBFS\n" +
                "Gain imbalance ${"%+.2f".format(candidate.gainImbalanceDb)} dB · phase error ${"%+.2f".format(candidate.phaseErrorDegrees)}°\n" +
                "Image rejection ${"%.1f".format(candidate.rejectionBeforeDb)} → ${"%.1f".format(candidate.rejectionAfterDb)} dB\n\n" +
                "Verify this offset. A device-bound profile is saved only after a stable opposite-offset proof.") },
            confirmButton = { Button({ message = controller.confirmCalibration() }) { Text("Verify offset") } },
            dismissButton = { TextButton({ message = controller.cancelCalibration() }) { Text("Reject") } })
    }
    controller.levelCalibrationCandidate?.let { candidate ->
        AlertDialog(onDismissRequest = { message = controller.cancelLevelCalibration() },
            title = { Text("Confirm measured level profile") },
            text = { Text("${"%.1f".format(candidate.measuredDbfs)} dBFS = ${"%.1f".format(candidate.knownDbm)} dBm\n" +
                "Offset ${"%+.1f".format(candidate.offsetDb)} dB · uncertainty ±${"%.1f".format(candidate.uncertaintyDb)} dB\n\n" +
                "This profile is valid only for the current USB input, sample rate, radio/band and unchanged physical/system input gain.") },
            confirmButton = { Button({ message = controller.confirmLevelCalibration() }) { Text("Save measured profile") } },
            dismissButton = { TextButton({ message = controller.cancelLevelCalibration() }) { Text("Reject") } })
    }
}

@Composable
private fun PanadapterTruthStrip(controller: PanadapterController) {
    val proof = controller.routeProof
    val metrics = controller.displayMetrics
    val iq = controller.iqState()
    val catLive = controller.effectiveCenter() > 0
    val usableKhz = proof.physicalRate * metrics.validBinFraction / 1000f
    val periodicSpurs = metrics.combPersistence > .6f && metrics.combSpacingHz.isFinite()
    val danger = proof.state in setOf(PanadapterFormatState.RESAMPLED_48_TO_96,
        PanadapterFormatState.OTHER_RESAMPLED_PATH, PanadapterFormatState.UNSUPPORTED_MONO_OR_CONVERTED_CHANNEL_PATH) ||
        metrics.state == PanadapterDisplayState.SATURATED || iq in setOf(PanadapterIqState.INVALID, PanadapterIqState.MIRROR_IMAGES_DOMINANT)
    Surface(color = when { danger -> PanDanger.copy(alpha = .22f); periodicSpurs || !catLive -> PanAmber.copy(alpha = .14f); else -> PanRaised.copy(alpha = .70f) }, modifier = Modifier.fillMaxWidth()) {
        Text("CAT ${if (catLive) "LIVE" else "OFFLINE · RELATIVE OFFSETS ONLY"} · ROUTE ${if (proof.routeVerified) "OK" else "UNPROVEN"} · FORMAT ${proof.state.name.replace('_', ' ')} · " +
            "USABLE ${if (usableKhz > 0f) "%.1f kHz".format(usableKhz) else "UNPROVEN"} · I/Q ${iq.name.replace('_', ' ')} · " +
            "DISPLAY ${metrics.state.name.replace('_', ' ')}" +
            if (periodicSpurs) " · PERIODIC SPURS ${"%.0f".format(metrics.combSpacingHz)} Hz" else "",
            color = when { danger -> PanDanger; periodicSpurs || !catLive -> PanAmber; else -> PanMuted }, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            maxLines = 2, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

@Composable
private fun PanadapterHeader(controller: PanadapterController, radio: RadioState, compact: Boolean,
    onControls: (() -> Unit)?, onStart: () -> Unit, onStop: () -> Unit,
    onInspector: () -> Unit, onDiagnostics: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact && onControls != null) IconButton(onControls) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Radio controls", tint = PanInk) }
        Column(Modifier.weight(1f)) {
            Text("PANADAPTER", color = PanAmber, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text(controller.status, color = when (controller.lifecycle) {
                PanadapterLifecycle.LIVE -> PanHealthy
                PanadapterLifecycle.ERROR, PanadapterLifecycle.ROUTE_LOST -> PanDanger
                else -> PanMuted
            }, fontSize = 10.sp, maxLines = 1)
        }
        val liveCenter = controller.effectiveCenter()
        Text(if (liveCenter > 0) formatRadioFrequency(liveCenter) else "RF STALE",
            color = if (liveCenter > 0) PanInk else PanDanger, fontFamily = FontFamily.Monospace,
            fontSize = if (compact) 13.sp else 17.sp, fontWeight = FontWeight.Bold)
        listOf(48_000 to "48K", 96_000 to "96K").forEach { (rate, label) ->
            FilterChip(
                selected = controller.settings.requestedRate == rate,
                onClick = { controller.updateSettings(controller.settings.copy(requestedRate = rate)) },
                label = { Text(label, fontFamily = FontFamily.Monospace) },
            )
        }
        IconButton(onDiagnostics) { Icon(Icons.Outlined.MonitorHeart, "Diagnostics", tint = PanMuted) }
        IconButton(onInspector) { Icon(Icons.Outlined.Tune, "Panadapter settings", tint = PanAmber) }
        if (controller.lifecycle == PanadapterLifecycle.LIVE || controller.lifecycle == PanadapterLifecycle.STARTING)
            FilledTonalButton(onStop) { Text("STOP") }
        else Button(onStart) { Text("START") }
    }
}

@Composable
private fun SpectrumCanvas(frame: PanadapterFrame?, radio: RadioState, center: Long, span: Float, viewPan: Float,
    markerAHz: Long, markerBHz: Long, spots: List<AndroidDXSpot>, settings: PanadapterSettings,
    spectrumFloorDb: Float, spectrumTopDb: Float, modifier: Modifier) {
    val leftHz = center - span / 2f + viewPan
    val frequencyLabels = remember(leftHz, span) {
        List(9) { index -> "%.3f".format((leftHz + span * index / 8f) / 1_000_000f) }
    }
    val frequencyPaint = remember { android.graphics.Paint().apply {
        color = android.graphics.Color.LTGRAY; textSize = 22f; typeface = android.graphics.Typeface.MONOSPACE
    } }
    val annotationPaint = remember { android.graphics.Paint().apply {
        textSize = 22f; typeface = android.graphics.Typeface.DEFAULT_BOLD
    } }
    Canvas(modifier) {
        drawRect(Color(0xFF0A0D10))
        val topDb = spectrumTopDb
        val floorDb = spectrumFloorDb
        repeat(9) { index ->
            val x = size.width * index / 8f
            drawLine(PanRaised.copy(alpha = .55f), Offset(x, 0f), Offset(x, size.height), 1f)
            if (center > 0) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(frequencyLabels[index], x + 4f, size.height - 6f, frequencyPaint)
                }
            }
        }
        repeat(6) { index ->
            val y = size.height * index / 5f
            drawLine(PanRaised.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), 1f)
            drawIntoCanvas { canvas ->
                annotationPaint.color = PanMuted.toArgb(); annotationPaint.textSize = 18f
                canvas.nativeCanvas.drawText("%.0f dBFS".format(topDb - (topDb - floorDb) * index / 5f), 5f, (y + 18f).coerceAtMost(size.height - 4f), annotationPaint)
            }
        }
        if (center > 0 && radio.bandwidthHz > 0) {
            val passband = panadapterPassband(radio)
            val x1 = ((center + passband.lowOffsetHz - leftHz) / span * size.width).coerceIn(0f, size.width)
            val x2 = ((center + passband.highOffsetHz - leftHz) / span * size.width).coerceIn(0f, size.width)
            drawRect(PanAmber.copy(alpha = .10f), Offset(minOf(x1, x2), 0f), Size(abs(x2 - x1), size.height))
        }
        val source = frame?.trace
        if (source != null && source.isNotEmpty()) {
            val pointCount = minOf(1_200, source.size, size.width.toInt()).coerceAtLeast(2)
            val firstBin = (((viewPan - span / 2f) / frame.effectiveSampleRate + .5f) * source.size).toInt().coerceIn(0, source.lastIndex)
            val lastBin = (((viewPan + span / 2f) / frame.effectiveSampleRate + .5f) * source.size).toInt().coerceIn(firstBin + 1, source.size)
            val buckets = reducePanadapterBuckets(source, firstBin, lastBin, pointCount)
            val path = Path()
            for (point in buckets.indices) {
                val normalized = point.toFloat() / (buckets.size - 1).coerceAtLeast(1)
                val db = buckets[point].displayDb
                val y = ((topDb - db) / (topDb - floorDb) * size.height).coerceIn(0f, size.height)
                val x = normalized * size.width
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, PanAmber, style = Stroke(2.2f))
            if (settings.peakHold && frame.peakHold.isNotEmpty()) {
                val peakBuckets = reducePanadapterBuckets(frame.peakHold, firstBin, lastBin, pointCount)
                val peakPath = Path()
                for (point in peakBuckets.indices) {
                    val normalized = point.toFloat() / (peakBuckets.size - 1).coerceAtLeast(1)
                    val y = ((topDb - peakBuckets[point].highDb) / (topDb - floorDb) * size.height).coerceIn(0f, size.height)
                    val x = normalized * size.width
                    if (point == 0) peakPath.moveTo(x, y) else peakPath.lineTo(x, y)
                }
                drawPath(peakPath, PanHold.copy(alpha = .55f), style = Stroke(1f))
            }
            val floorY = ((topDb - frame.floorDb) / (topDb - floorDb) * size.height).coerceIn(0f, size.height)
            if (settings.showFloor) drawLine(PanHealthy.copy(alpha = .55f), Offset(0f, floorY), Offset(size.width, floorY), 1f)
            if (frame.validMask.isNotEmpty()) {
                val validFirst = frame.validMask.indexOfFirst { it }
                val validLast = frame.validMask.indexOfLast { it }
                if (validFirst >= 0) {
                    val validLeftHz = (validFirst.toFloat() / frame.validMask.size - .5f) * frame.effectiveSampleRate
                    val validRightHz = ((validLast + 1f) / frame.validMask.size - .5f) * frame.effectiveSampleRate
                    val x1 = ((validLeftHz - (viewPan - span / 2f)) / span * size.width).coerceIn(0f, size.width)
                    val x2 = ((validRightHz - (viewPan - span / 2f)) / span * size.width).coerceIn(0f, size.width)
                    if (x1 > 0f) drawRect(PanMuted.copy(alpha = .18f), Offset.Zero, Size(x1, size.height))
                    if (x2 < size.width) drawRect(PanMuted.copy(alpha = .18f), Offset(x2, 0f), Size(size.width - x2, size.height))
                }
            }
        }
        if (center > 0) {
            if (settings.centerMaskBins > 0 && frame != null) {
                val maskWidth = (settings.centerMaskBins.toFloat() / frame.fftSize * frame.effectiveSampleRate / span * size.width).coerceAtLeast(1f)
                val centerX = (.5f - viewPan / span) * size.width
                drawRect(Color(0xFF0A0D10).copy(alpha = .78f), Offset(centerX - maskWidth / 2f, 0f), Size(maskWidth, size.height))
                drawLine(PanMuted, Offset(centerX, 0f), Offset(centerX, size.height), 1f)
            }
            drawLine(if (radio.transmitting) PanDanger else PanHealthy, Offset((.5f - viewPan / span) * size.width, 0f),
                Offset((.5f - viewPan / span) * size.width, size.height), 2f)
            val tx = radio.effectiveTxHz
            if (radio.split && tx > 0) markerLine(tx, leftHz, span, size.width, size.height, PanDanger, "TX", annotationPaint)
            if (markerAHz > 0) markerLine(markerAHz, leftHz, span, size.width, size.height, PanHold, "A", annotationPaint)
            if (markerBHz > 0) markerLine(markerBHz, leftHz, span, size.width, size.height, Color.Cyan, "B", annotationPaint)
            spots.forEach { spot ->
                val x = ((spot.frequencyHz - leftHz) / span * size.width)
                val color = when { spot.watchlisted -> PanHold; spot.workedCall -> PanMuted; else -> PanHealthy }
                drawLine(color.copy(alpha = .7f), Offset(x, 18f), Offset(x, 48f), 2f)
                drawIntoCanvas { canvas ->
                    annotationPaint.color = color.toArgb(); annotationPaint.textSize = 20f
                    canvas.nativeCanvas.drawText(spot.callsign, x + 3f, 16f, annotationPaint)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.markerLine(frequency: Long, leftHz: Float, span: Float,
    width: Float, height: Float, color: Color, label: String, paint: android.graphics.Paint) {
    val x = ((frequency - leftHz) / span * width)
    if (x !in 0f..width) return
    drawLine(color, Offset(x, 0f), Offset(x, height), 2f)
    drawCircle(color, 7f, Offset(x, 10f))
    drawIntoCanvas { canvas ->
        paint.color = color.toArgb(); paint.textSize = 22f
        canvas.nativeCanvas.drawText(label, x + 7f, 22f, paint)
    }
}

@Composable
private fun WaterfallCanvas(controller: PanadapterController, center: Long, span: Float, viewPan: Float,
    markerAHz: Long, markerBHz: Long, modifier: Modifier) {
    val bitmap = controller.waterfallBitmap
    val head = controller.waterfallHead
    controller.waterfallRevision
    Canvas(modifier.background(Color.Black)) {
        if (bitmap != null && !bitmap.isRecycled) {
            val image = bitmap.asImageBitmap()
            val firstHeight = bitmap.height - head
            if (firstHeight > 0) drawImage(image, IntOffset(0, head), IntSize(bitmap.width, firstHeight),
                IntOffset.Zero, IntSize(size.width.toInt(), (size.height * firstHeight / bitmap.height).toInt()))
            if (head > 0) drawImage(image, IntOffset(0, 0), IntSize(bitmap.width, head),
                IntOffset(0, (size.height * firstHeight / bitmap.height).toInt()),
                IntSize(size.width.toInt(), (size.height * head / bitmap.height).toInt()))
        } else drawRect(Color(0xFF080B0D))
        if (center > 0) {
            val left = center - span / 2f + viewPan
            fun line(frequency: Long, color: Color) {
                if (frequency <= 0) return
                val x = ((frequency - left) / span * size.width)
                if (x in 0f..size.width) drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5f)
            }
            line(center, PanHealthy); line(markerAHz, PanHold); line(markerBHz, Color.Cyan)
        }
    }
}

@Composable
private fun PanadapterActionStrip(controller: PanadapterController, radio: RadioState, frame: PanadapterFrame?, center: Long,
    markerAHz: Long, markerBHz: Long, onTuneA: () -> Unit, onTuneB: () -> Unit, onUndo: () -> Unit,
    onCalibrate: () -> Unit, onLevelCalibrate: () -> Unit, onRecord: () -> Unit, onReplay: () -> Unit, onResetView: () -> Unit,
    onDisplayMode: () -> Unit, onImmersive: () -> Unit, compact: Boolean) {
    val levelOffset = if (controller.levelCalibrationActive()) controller.settings.dbfsToDbmOffset else 0f
    val levelUnit = if (controller.levelCalibrationActive()) "dBm" else "dBFS"
    val aLevel = markerLevel(frame, center, markerAHz) + levelOffset
    val bLevel = markerLevel(frame, center, markerBHz) + levelOffset
    @Composable fun Readouts(modifier: Modifier = Modifier) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("A ${if (markerAHz > 0) formatRadioFrequency(markerAHz) else "—"}  ${"%.1f".format(aLevel)} $levelUnit",
            color = PanHold, fontFamily = FontFamily.Monospace, fontSize = if (compact) 10.sp else 12.sp,
            maxLines = 1, modifier = Modifier.weight(1f))
        Text("B ${if (markerBHz > 0) formatRadioFrequency(markerBHz) else "—"}  ${"%.1f".format(bLevel)} $levelUnit",
            color = Color.Cyan, fontFamily = FontFamily.Monospace, fontSize = if (compact) 10.sp else 12.sp,
            maxLines = 1, modifier = Modifier.weight(1f))
    }
    @Composable fun Actions(modifier: Modifier = Modifier) {
        CompositionLocalProvider(LocalContentColor provides PanInk) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (compact) Arrangement.SpaceEvenly else Arrangement.spacedBy(6.dp)) {
                TextButton({ controller.setAutoDisplay(!controller.settings.autoLevel) }) {
                    Text(if (controller.settings.autoLevel) "AUTO DISPLAY" else "MANUAL")
                }
                if (markerAHz > 0) TextButton(onTuneA, enabled = !radio.transmitting) { Text("QSY A") }
                if (markerBHz > 0) TextButton(onTuneB, enabled = !radio.transmitting) { Text("QSY B") }
                IconButton(onUndo, enabled = controller.lastQsy != null) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo last panadapter QSY") }
                IconButton(onCalibrate, enabled = markerAHz > 0 && controller.lifecycle == PanadapterLifecycle.LIVE) { Icon(Icons.Outlined.Science, "Calibrate I Q using marker A known tone") }
                IconButton(onLevelCalibrate, enabled = controller.lifecycle == PanadapterLifecycle.LIVE) { Icon(Icons.Outlined.Speed, "Calibrate displayed level from a known reference") }
                IconButton(onRecord, enabled = controller.lifecycle == PanadapterLifecycle.LIVE) { Icon(if (controller.recordingStatus.startsWith("Recording")) Icons.Outlined.StopCircle else Icons.Outlined.FiberManualRecord, "Record bounded I Q") }
                IconButton(onReplay, enabled = controller.lastRecordingPath.isNotBlank() && controller.lifecycle != PanadapterLifecycle.LIVE) { Icon(Icons.Outlined.Replay, "Replay last I Q recording") }
                IconButton(onResetView) { Icon(Icons.Outlined.CenterFocusStrong, "Reset view zoom") }
                IconButton(onDisplayMode) { Icon(Icons.Outlined.ViewAgenda, "Cycle spectrum and waterfall layout") }
                IconButton(onImmersive) { Icon(Icons.Outlined.Fullscreen, "Toggle immersive panadapter") }
            }
        }
    }
    if (compact) Column(Modifier.fillMaxWidth()) {
        Readouts(Modifier.fillMaxWidth())
        Actions(Modifier.fillMaxWidth().heightIn(min = 48.dp))
    } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Readouts(Modifier.weight(1f))
        Actions()
    }
}

@Composable
private fun PanadapterInspector(controller: PanadapterController, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    var draft by remember(controller.settings) { mutableStateOf(controller.settings) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Panadapter setup") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("STEREO I/Q INPUT", color = PanAmber, fontWeight = FontWeight.Bold)
            controller.run { }
            ControllerInputSelector(controller)
            Text("Requested sample rate", color = PanMuted)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(96_000, 48_000).forEachIndexed { index, rate -> SegmentedButton(draft.requestedRate == rate,
                    { draft = draft.copy(requestedRate = rate) }, SegmentedButtonDefaults.itemShape(index, 2)) { Text("${rate / 1000} kHz") } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.allow48kFallback, { draft = draft.copy(allow48kFallback = it) }); Text("Allow explicit 96 → 48 kHz fallback") }
            Text("FFT", color = PanMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(1_024, 2_048, 4_096, 8_192).forEach { value ->
                FilterChip(draft.fftSize == value, { draft = draft.copy(fftSize = value) }, { Text(value.toString()) }) } }
            Text("Overlap", color = PanMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(25, 50, 75).forEach { value ->
                FilterChip(draft.overlapPercent == value, { draft = draft.copy(overlapPercent = value) }, { Text("$value%") }) } }
            Text("Window", color = PanMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PanadapterWindow.entries.forEach { value ->
                FilterChip(draft.window == value, { draft = draft.copy(window = value) }, { Text(value.name.lowercase().replace('_', ' ')) }) } }
            Text("Power averaging · ${draft.averageFrames} frames", color = PanMuted)
            Slider(draft.averageFrames.toFloat(), { draft = draft.copy(averageFrames = it.toInt().coerceAtLeast(1)) }, valueRange = 1f..16f, steps = 14)
            Text("Spectrum scale · ${"%.0f".format(draft.displayFloorDb)} to ${"%.0f".format(draft.displayTopDb)} dBFS", color = PanMuted)
            Slider(draft.displayFloorDb, { draft = draft.copy(displayFloorDb = it.coerceAtMost(draft.displayTopDb - 20f)) }, valueRange = -140f..-50f)
            Slider(draft.displayTopDb, { draft = draft.copy(displayTopDb = it.coerceAtLeast(draft.displayFloorDb + 20f)) }, valueRange = -80f..0f)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.autoLevel, { draft = draft.copy(autoLevel = it) }); Text("Robust automatic spectrum level")
                Spacer(Modifier.weight(1f)); TextButton({ draft = draft.copy(displayFloorDb = -120f, displayTopDb = -20f, levelAttack = .35f, levelRelease = .08f) }) { Text("Reset") } }
            Text("Auto-level attack · ${"%.2f".format(draft.levelAttack)}", color = PanMuted)
            Slider(draft.levelAttack, { draft = draft.copy(levelAttack = it) }, valueRange = .01f..1f)
            Text("Auto-level release · ${"%.2f".format(draft.levelRelease)}", color = PanMuted)
            Slider(draft.levelRelease, { draft = draft.copy(levelRelease = it) }, valueRange = .005f..0.4f)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.showFloor, { draft = draft.copy(showFloor = it) }); Text("Robust floor line") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.peakHold, { draft = draft.copy(peakHold = it) }); Text("Peak hold")
                Spacer(Modifier.weight(1f)); TextButton({ controller.resetPeakHold(); onMessage("Peak hold reset") }) { Text("Reset") } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.genericKx3Flatness, { draft = draft.copy(genericKx3Flatness = it) }); Text("Elecraft generic I/Q flatness curve") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.measuredFlatnessEnabled,
                { draft = draft.copy(measuredFlatnessEnabled = it) }); Text("Measured symmetric flatness profile") }
            if (draft.measuredFlatnessEnabled) {
                Text("Preview uses symmetric interpolation. Enter matched comma-separated offset Hz and correction dB points, beginning at 0 Hz.", color = PanMuted, fontSize = 10.sp)
                OutlinedTextField(draft.measuredFlatnessOffsetsCsv,
                    { draft = draft.copy(measuredFlatnessOffsetsCsv = it.take(180)) }, label = { Text("Offsets Hz · 0,12000,24000,48000") }, singleLine = true)
                OutlinedTextField(draft.measuredFlatnessGainsCsv,
                    { draft = draft.copy(measuredFlatnessGainsCsv = it.take(180)) }, label = { Text("Correction dB · 0,0.4,1.1,3.0") }, singleLine = true)
                val validPoints = parseMeasuredFlatness(draft)
                Text(if (validPoints.isEmpty()) "Profile invalid — it will not be applied" else "${validPoints.size} measured points ready for comparison",
                    color = if (validPoints.isEmpty()) PanDanger else PanHealthy, fontSize = 10.sp)
                TextButton({ draft = draft.copy(measuredFlatnessEnabled = false, measuredFlatnessOffsetsCsv = "", measuredFlatnessGainsCsv = "") }) { Text("Reset measured flatness") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.swapIq, { draft = draft.copy(swapIq = it) }); Text("Swap left/right I/Q") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.conjugate, { draft = draft.copy(conjugate = it) }); Text("Conjugate / reverse spectrum") }
            Text("Visual DC mask (raw DSP remains unchanged)", color = PanMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(0, 2, 4, 8).forEach { value ->
                FilterChip(draft.centerMaskBins == value, { draft = draft.copy(centerMaskBins = value) }, { Text(if (value == 0) "Raw" else "$value bins") }) } }
            Text("High-resolution zoom", color = PanMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(1, 2, 4, 8).forEach { value ->
                FilterChip(draft.zoomDecimation == value, { draft = draft.copy(zoomDecimation = value) }, { Text("${value}×") }) } }
            Text("Waterfall palette", color = PanMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PanadapterPalette.entries.forEach { value ->
                FilterChip(draft.palette == value, { draft = draft.copy(palette = value) }, { Text(value.name.lowercase().replaceFirstChar(Char::uppercase)) }) } }
            Text("Waterfall range · ${"%.0f".format(draft.waterfallMinDb)} to ${"%.0f".format(draft.waterfallMaxDb)} dBFS", color = PanMuted)
            Slider(draft.waterfallMinDb, { draft = draft.copy(waterfallMinDb = it.coerceAtMost(draft.waterfallMaxDb - 10f)) }, valueRange = -140f..-60f)
            Slider(draft.waterfallMaxDb, { draft = draft.copy(waterfallMaxDb = it.coerceAtLeast(draft.waterfallMinDb + 10f)) }, valueRange = -100f..-20f)
            Text("Waterfall gamma · ${"%.2f".format(draft.waterfallGamma)}", color = PanMuted)
            Slider(draft.waterfallGamma, { draft = draft.copy(waterfallGamma = it) }, valueRange = .25f..3f)
            Text("Waterfall line rate · ${draft.waterfallLineRate}/s", color = PanMuted)
            Slider(draft.waterfallLineRate.toFloat(), { draft = draft.copy(waterfallLineRate = it.toInt()) }, valueRange = 5f..30f, steps = 24)
            Text("Waterfall power averaging · ${draft.waterfallAverageFrames} frames", color = PanMuted)
            Slider(draft.waterfallAverageFrames.toFloat(), { draft = draft.copy(waterfallAverageFrames = it.toInt().coerceAtLeast(1)) }, valueRange = 1f..16f, steps = 14)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.showSpots, { draft = draft.copy(showSpots = it) }); Text("DX / worked-status overlays") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.keepScreenAwake, { draft = draft.copy(keepScreenAwake = it) }); Text("Keep screen awake while live") }
        } },
        confirmButton = { Button({ controller.updateSettings(draft); onMessage("Panadapter settings applied"); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun LevelCalibrationDialog(controller: PanadapterController, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    var known by remember { mutableStateOf("-73") }
    var uncertainty by remember { mutableStateOf("1.0") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Measured level calibration") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect a known, stable reference without transmitting. Never transmit into a signal generator, sound-card input, attenuator not rated for RF, or an inappropriate test connection.", color = PanDanger)
            Text("Use the same USB input and gain state you will operate with. Any physical or Android input-gain change invalidates the profile.", color = PanMuted)
            OutlinedTextField(known, { known = it.take(12) }, label = { Text("Known reference level (dBm)") }, singleLine = true)
            OutlinedTextField(uncertainty, { uncertainty = it.take(8) }, label = { Text("Uncertainty (dB)") }, singleLine = true)
            OutlinedTextField(notes, { notes = it.take(160) }, label = { Text("Reference and input-gain notes") }, maxLines = 3)
        } },
        confirmButton = { Button({
            onMessage(controller.startLevelCalibration(known.toFloatOrNull() ?: Float.NaN,
                uncertainty.toFloatOrNull() ?: Float.NaN, notes)); onDismiss()
        }) { Text("Measure 3 seconds") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun ControllerInputSelector(controller: PanadapterController) {
    // The shared audio selector owns the persisted fingerprint; all candidates remain explicit.
    if (controller.inputCandidates.isEmpty()) Text("No external USB inputs detected", color = PanDanger)
    else controller.inputCandidates.forEach { route ->
        val selected = controller.selectedInput?.sessionId == route.sessionId
        Surface(onClick = { controller.selectInput(route.sessionId) }, color = if (selected) PanHealthy.copy(alpha = .14f) else PanRaised,
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected, { controller.selectInput(route.sessionId) })
                Column { Text(route.name, color = PanInk); Text(route.label, color = PanMuted, fontSize = 10.sp)
                    Text("rates ${route.sampleRates.joinToString()} · channels ${route.channelCounts.joinToString()} · enc ${route.encodings.joinToString()}", color = PanMuted, fontSize = 9.sp) }
            }
        }
    }
}

@Composable
private fun PanadapterDiagnostics(controller: PanadapterController, radio: RadioState, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val proof = controller.routeProof
    val frame = controller.frame
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Panadapter diagnostics") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            DiagnosticRow("Lifecycle", controller.lifecycle.name)
            DiagnosticRow("Route verified", proof.verified.toString())
            DiagnosticRow("Requested", proof.requestedDevice)
            DiagnosticRow("Actual", proof.actualDevice)
            DiagnosticRow("Rate / channels", "${proof.configuredRate} Hz / ${proof.configuredChannels}")
            DiagnosticRow("Format proof", proof.state.name)
            DiagnosticRow("Client numeric", "${proof.clientRate} Hz / ${proof.clientChannels} ch / enc ${proof.clientEncoding} / mask 0x${proof.clientChannelMask.toString(16)}")
            DiagnosticRow("Device numeric", "${proof.deviceRate} Hz / ${proof.deviceChannels} ch / enc ${proof.deviceEncoding} / mask 0x${proof.deviceChannelMask.toString(16)}")
            DiagnosticRow("Conversion", proof.conversionPresent.toString())
            DiagnosticRow("Client silenced", proof.clientSilenced.toString())
            DiagnosticRow("Client / device effects", "${proof.clientEffects} / ${proof.deviceEffects}")
            DiagnosticRow("Client format", proof.clientFormat)
            DiagnosticRow("Device format", proof.deviceFormat)
            DiagnosticRow("Audio source/session", "${proof.audioSource} / ${proof.sessionId}")
            DiagnosticRow("FFT / hop / RBW", "${frame?.fftSize ?: 0} / ${frame?.hopSize ?: 0} / ${"%.2f".format(frame?.rbwHz ?: 0f)} Hz")
            DiagnosticRow("Frames / transforms", "${frame?.inputFrames ?: 0} / ${frame?.transforms ?: 0}")
            DiagnosticRow("Discontinuities", "${frame?.discontinuities ?: 0}")
            DiagnosticRow("Spectrum / waterfall", "${"%.1f".format(controller.publishedFps)} / ${"%.1f".format(controller.waterfallFps)} fps")
            DiagnosticRow("Capture/display estimate", "${"%.0f".format(controller.latencyEstimateMs)} ms")
            DiagnosticRow("I / Q RMS", "${"%.1f".format(frame?.iRmsDb ?: -140f)} / ${"%.1f".format(frame?.qRmsDb ?: -140f)} dBFS")
            DiagnosticRow("Correlation / duplicate", "${"%.4f".format(frame?.iqCorrelation ?: 0f)} / ${"%.4f".format(frame?.duplicateCorrelation ?: 0f)}")
            DiagnosticRow("Peak / floor", "${"%.1f".format(frame?.peakDb ?: -140f)} / ${"%.1f".format(frame?.floorDb ?: -140f)} dBFS")
            DiagnosticRow("Raw / stable floor", "${"%.1f".format(controller.displayMetrics.rawFloorDb)} / ${"%.1f".format(controller.displayMetrics.stabilizedFloorDb)} dBFS")
            DiagnosticRow("Waterfall black / top", "${"%.1f".format(controller.displayMetrics.waterfallBlackDb)} / ${"%.1f".format(controller.displayMetrics.waterfallTopDb)} dBFS")
            DiagnosticRow("Saturated / valid", "${"%.2f".format(controller.displayMetrics.waterfallSaturatedFraction * 100)}% / ${"%.1f".format(controller.displayMetrics.validBinFraction * 100)}%")
            DiagnosticRow("Display / I Q / calibration", "${controller.displayMetrics.state} / ${controller.iqState()} / ${controller.calibrationState()}")
            DiagnosticRow("Comb", if (controller.displayMetrics.combPersistence > .5f) "${"%.1f".format(controller.displayMetrics.combSpacingHz)} Hz · ${"%.0f".format(controller.displayMetrics.combPersistence * 100)}% persistent" else "not persistent")
            Text("SPUR DIAGNOSTIC", color = PanAmber, fontWeight = FontWeight.Bold)
            Text("A: safely terminate/short the sound-card input with KX3 disconnected. B: connect KX3 I/Q with its antenna path safely terminated. C: restore normal station receive. Wait for stable metrics before retaining each stage.", color = PanMuted, fontSize = 9.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("A", "B", "C").forEach { stage ->
                    OutlinedButton({ onMessage(controller.captureSpurStage(stage)) }) {
                        Text("CAPTURE $stage${if (controller.spurCaptures.containsKey(stage)) " ✓" else ""}")
                    }
                }
            }
            DiagnosticRow("Measured flatness", if (controller.measuredFlatnessActive()) "ACTIVE · device/rate/radio matched" else "INACTIVE / profile mismatch")
            DiagnosticRow("Level calibration", if (controller.levelCalibrationActive())
                "ACTIVE · ${"%+.1f".format(controller.settings.dbfsToDbmOffset)} dB · ±${"%.1f".format(controller.settings.levelCalibrationUncertaintyDb)} dB"
                else "INACTIVE · dBFS only")
            DiagnosticRow("Clipping", "${"%.4f".format((frame?.clippedFraction ?: 0f) * 100)}%")
            DiagnosticRow("Recording", controller.recordingStatus)
            DiagnosticRow("Calibration", controller.calibrationStatus)
            DiagnosticRow("CAT", "${radio.model} rev ${radio.revision} · RX ${radio.effectiveRxHz} · TX ${radio.effectiveTxHz}")
            if (controller.supportExportPath.isNotBlank()) Text(controller.supportExportPath, color = PanMuted, fontSize = 9.sp)
        } },
        confirmButton = { Button({ onMessage(controller.exportSupportSnapshot()) }) { Text("Export support snapshot") } },
        dismissButton = { TextButton(onDismiss) { Text("Close") } })
}

@Composable private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = PanMuted, fontSize = 11.sp); Text(value, color = PanInk, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

private fun frequencyAt(x: Float, width: Float, center: Long, span: Float, pan: Float): Long =
    (center + (x / width - .5f) * span + pan).toLong()

private fun spectrumDescription(frame: PanadapterFrame?, center: Long): String = if (frame == null)
    "Panadapter offline" else "Live complex I Q spectrum centered at $center hertz, peak ${"%.1f".format(frame.peakDb)} dBFS, floor ${"%.1f".format(frame.floorDb)} dBFS"

private fun Color.toArgb(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
