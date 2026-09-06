package app.shackcq.mobile

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FlexAmber = Color(0xFFE9A72B)
private val FlexGreen = Color(0xFF42C77B)
private val FlexMuted = Color(0xFFA5ADB2)

@Composable
fun FlexRadioScreen(controller: FlexRadioController, openLog: () -> Unit) {
    val scope = rememberCoroutineScope()
    var authUri by remember { mutableStateOf<Uri?>(null) }
    var frequency by remember(controller.selectedSliceIndex, controller.snapshot) {
        mutableStateOf(controller.snapshot.selected(controller.selectedSliceIndex)?.frequencyHz?.toString().orEmpty())
    }
    authUri?.let { uri -> SmartLinkAuthDialog(uri, { controller.completeSmartLinkSignIn(it); authUri = null }, { authUri = null }) }
    controller.pendingCertificateChange?.let { change ->
        AlertDialog(
            onDismissRequest = { controller.resolveCertificateChange(false) },
            title = { Text(if (change.expectedFingerprint.isBlank()) "TRUST SMARTLINK RADIO" else "SMARTLINK CERTIFICATE CHANGED") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (change.expectedFingerprint.isBlank())
                        "This is the first certificate seen for this radio. Verify the fingerprint through a trusted path before accepting it."
                    else "The radio certificate no longer matches the fingerprint previously trusted for this radio. Accept only if you expect the radio certificate to have changed.")
                    Text(if (change.expectedFingerprint.isBlank()) "Observed\n${change.observedFingerprint}"
                        else "Previous\n${change.expectedFingerprint}\n\nObserved\n${change.observedFingerprint}", fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton({ controller.resolveCertificateChange(true) }) { Text(if (change.expectedFingerprint.isBlank()) "TRUST CERTIFICATE" else "TRUST NEW CERTIFICATE") } },
            dismissButton = { TextButton({ controller.resolveCertificateChange(false) }) { Text("REJECT") } },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val wide = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("FLEXRADIO", color = FlexAmber, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("LAN / SMARTLINK · PAN · AUDIO · CONTROLLED TX", color = FlexMuted, fontWeight = FontWeight.Bold) }
                Surface(color = if (controller.connectionState == FlexConnectionState.CONNECTED) FlexGreen.copy(alpha = .18f) else FlexAmber.copy(alpha = .14f), shape = MaterialTheme.shapes.small) {
                    Text(controller.connectionState.label, color = if (controller.connectionState == FlexConnectionState.CONNECTED) FlexGreen else FlexAmber,
                        fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            if (wide) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlexRadios(controller, Modifier.weight(1f)); FlexStationSlice(controller, frequency, { frequency = it }, openLog, Modifier.weight(1.25f), scrollable = true)
            } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { FlexRadios(controller, Modifier.fillMaxWidth()) }
                item { FlexStationSlice(controller, frequency, { frequency = it }, openLog, Modifier.fillMaxWidth(), scrollable = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ controller.discoverLan() }) { Text("SEARCH LAN") }
                OutlinedButton({ authUri = controller.beginSmartLinkSignIn() }, enabled = controller.smartLinkConfigured) { Text("SIGN IN") }
                OutlinedButton({ controller.refreshSmartLinkRadios() }, enabled = controller.smartLinkSignedIn) { Text("REFRESH SMARTLINK") }
                OutlinedButton({ scope.launch { controller.disconnect() } }) { Text("DISCONNECT") }
                OutlinedButton({
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    controller.logout()
                }) { Text("LOGOUT") }
            }
            if (!controller.smartLinkConfigured) Text("OWNER CONFIGURATION VALUES REQUIRED · populate FLEX_SMARTLINK_CLIENT_ID in the ignored flex-developer.properties file or environment. Official Auth0 redirect and SmartLink server defaults are built in.", color = FlexAmber)
            Text(controller.detail, color = FlexMuted)
            Text("ShackCQ is an independent GPLv3 application. Flex protocol core includes GPLv3 code derived from Nexus by KD9TAW.", color = FlexMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SmartLinkAuthDialog(authorizationUri: Uri, onRedirect: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val allowedHost = authorizationUri.host
    val callbackLifecycle = remember(authorizationUri) { LifecycleGeneration() }
    val callbackGeneration = remember(authorizationUri) { callbackLifecycle.next() }
    val webView = remember(authorizationUri) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                private fun inspect(uri: Uri): Boolean {
                    if (!callbackLifecycle.isCurrent(callbackGeneration)) return true
                    if (uri.scheme != "https" || uri.host != allowedHost) return true
                    if (!uri.fragment.isNullOrBlank() && uri.fragment.orEmpty().contains("state=")) {
                        onRedirect(uri)
                        return true
                    }
                    return false
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) =
                    request?.url?.let(::inspect) ?: true
                override fun onPageFinished(view: WebView?, url: String?) {
                    url?.let(Uri::parse)?.takeIf { !it.fragment.isNullOrBlank() }?.let(::inspect)
                }
            }
            loadUrl(authorizationUri.toString())
        }
    }
    DisposableEffect(webView) {
        onDispose {
            callbackLifecycle.close()
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.clearHistory()
            webView.destroy()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(.92f).fillMaxHeight(.9f), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2228))) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SMARTLINK SIGN IN", color = FlexAmber, fontWeight = FontWeight.Black)
                    OutlinedButton(onDismiss) { Text("CANCEL") }
                }
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable private fun FlexRadios(controller: FlexRadioController, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2228))) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("RADIOS", color = FlexAmber, fontWeight = FontWeight.Black)
        controller.manualTarget?.let { radio ->
            Text("MANUAL LAN / VPN", color = FlexAmber, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF283139))) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(radio.nickname, fontWeight = FontWeight.Bold)
                        Text("${radio.ip}:${radio.port}", color = FlexMuted, fontFamily = FontFamily.Monospace)
                    }
                    Button({ scope.launch { controller.connectLan(radio) } }) { Text("CONNECT") }
                }
            }
            HorizontalDivider()
        }
        if (controller.radios.isEmpty()) Text(if (controller.connectionState == FlexConnectionState.SEARCHING) "SEARCHING LAN" else "NO DISCOVERED LAN RADIOS", color = FlexMuted)
        controller.radios.forEach { radio -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF283139))) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(radio.nickname.ifBlank { radio.model }, fontWeight = FontWeight.Bold); Text("${radio.model} · ${radio.callsign.ifBlank { radio.serial }}", color = FlexMuted); Text("${radio.ip}:${radio.port} · ${radio.status}", color = FlexMuted, fontFamily = FontFamily.Monospace) }
                Button({ scope.launch { controller.connectLan(radio) } }) { Text("CONNECT") }
            }
        } }
        if (controller.smartLinkRadios.isNotEmpty()) {
            HorizontalDivider(); Text("SMARTLINK RADIOS", color = FlexAmber, fontWeight = FontWeight.Black)
            controller.smartLinkRadios.forEach { radio -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(radio.nickname.ifBlank { radio.model }, fontWeight = FontWeight.Bold); Text("${radio.model} · ${radio.callsign} · ${radio.status}", color = FlexMuted) }
                Button({ scope.launch { controller.connectSmartLink(radio) } }) { Text("CONNECT WAN") }
            } }
        }
        HorizontalDivider()
        Text("DIAGNOSTICS", color = FlexAmber, fontWeight = FontWeight.Bold)
        Text("V ${controller.snapshot.version.ifBlank { "—" }} · H ${controller.snapshot.handle.takeIf { it != 0L }?.toString(16)?.uppercase() ?: "—"}", color = FlexMuted, fontFamily = FontFamily.Monospace)
        if (controller.lastDisconnectReason.isNotBlank()) Text(controller.lastDisconnectReason, color = FlexAmber)
    }
    }
}

@Composable private fun FlexStationSlice(controller: FlexRadioController, frequency: String, setFrequency: (String) -> Unit, openLog: () -> Unit, modifier: Modifier, scrollable: Boolean) {
    val scope = rememberCoroutineScope()
    val selected = controller.snapshot.selected(controller.selectedSliceIndex)
    var audioGain by remember { mutableStateOf(50f) }
    var audioPan by remember { mutableStateOf(50f) }
    var muted by remember { mutableStateOf(false) }
    var txAcknowledgement by remember { mutableStateOf("") }
    var cwxText by remember { mutableStateOf("") }
    var confirmTxSlice by remember { mutableStateOf<Int?>(null) }
    confirmTxSlice?.let { index ->
        AlertDialog(
            onDismissRequest = { confirmTxSlice = null },
            title = { Text("ASSIGN TX SLICE") },
            text = { Text("Make slice ${controller.snapshot.slices.firstOrNull { it.index == index }?.letter ?: index} the transmit slice? This changes radio TX routing.") },
            confirmButton = { TextButton({ controller.requestTxSlice(index, confirmed = true); confirmTxSlice = null }) { Text("CONFIRM TX SLICE") } },
            dismissButton = { TextButton({ confirmTxSlice = null }) { Text("CANCEL") } },
        )
    }
    val scrollState = rememberScrollState()
    val contentModifier = if (scrollable) Modifier.verticalScroll(scrollState) else Modifier
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2228))) { Column(contentModifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("FLEX OPERATING COCKPIT", color = FlexAmber, fontWeight = FontWeight.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FlexDisplayMode.entries.forEach { mode ->
                FilterChip(controller.displayMode == mode, { controller.chooseDisplayMode(mode) }, { Text(if (mode == FlexDisplayMode.ATTACH) "ATTACH" else "SHACKCQ CLIENT") })
            }
            Button(
                { scope.launch { controller.createShackCQDisplay(selected?.frequencyHz ?: 14_074_000) } },
                enabled = controller.displayMode == FlexDisplayMode.SHACKCQ_CLIENT && controller.snapshot.hasCommandChannel,
            ) { Text("CREATE PANAFALL") }
        }
        val stations = controller.snapshot.clients.filter { it.connected && it.gui && it.station.isNotBlank() }.map { it.station }.distinct()
        if (stations.isEmpty()) Text("NO GUI STATION", color = FlexMuted) else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { stations.forEach { station -> FilterChip(controller.snapshot.clients.any { it.station == station && controller.snapshot.selected(controller.selectedSliceIndex)?.clientHandle == it.handle }, { controller.selectStation(station) }, { Text(station) }) } }
        val slices = controller.snapshot.slices.filter { it.inUse }
        if (slices.isEmpty()) Text("NO FLEX SLICE · attach to a GUI station or create a ShackCQ client display.", color = FlexMuted)
        else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { slices.forEach { slice ->
            FilterChip(controller.selectedSliceIndex == slice.index, { if (!slice.tx) controller.selectSlice(slice.index) }, { Text("${slice.letter} · ${slice.mode}${if (slice.tx) " · TX" else ""}") })
            OutlinedButton({ confirmTxSlice = slice.index }, enabled = controller.tx.state in setOf(FlexTxState.DISABLED, FlexTxState.READY)) { Text("TX ${slice.letter}") }
        } }
        Text(selected?.frequencyHz?.let(::formatRadioFrequency)?.plus(" MHz") ?: "—.——— MHz", color = FlexAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 38.sp)
        Text("${selected?.mode ?: "—"} · FILTER ${selected?.filterWidthHz ?: 0} Hz · RX ANT ${selected?.rxAntenna?.ifBlank { "—" } ?: "—"} · MAX SLICES ${controller.extended.capabilities.maxSlices}", color = FlexMuted)
        FlexPanWaterfall(controller) { hz -> scope.launch { controller.tune(ReceiveTuneRequest(hz)) } }
        FlexMeters(controller)
        OutlinedTextField(frequency, { setFrequency(it.filter(Char::isDigit).take(11)) }, label = { Text("Frequency Hz") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ frequency.toLongOrNull()?.let { scope.launch { controller.tune(ReceiveTuneRequest(it)) } } }, enabled = selected != null && frequency.toLongOrNull() != null) { Text("SET RECEIVE FREQUENCY") }
            listOf(-1000L, -100L, 100L, 1000L).forEach { step -> OutlinedButton({ selected?.let { scope.launch { controller.tune(ReceiveTuneRequest(it.frequencyHz + step)) } } }, enabled = selected != null) { Text(if (step > 0) "+$step" else "$step") } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("LSB", "USB", "CW", "DIGU", "DIGL", "AM", "FM").forEach { mode -> FilterChip(selected?.mode == mode, { selected?.let { scope.launch { controller.tune(ReceiveTuneRequest(it.frequencyHz, mode)) } } }, { Text(mode) }) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AF", color = FlexMuted)
            Slider(audioGain, { audioGain = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
            Text("PAN", color = FlexMuted)
            Slider(audioPan, { audioPan = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
            Switch(muted, { muted = it })
            Text(if (muted) "MUTED" else "LIVE", color = FlexMuted)
            Button({ controller.setSliceAudio(audioGain.roundToInt(), audioPan.roundToInt(), muted) }, enabled = selected != null) { Text("APPLY") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ if (controller.rxAudioEnabled) controller.disableRxAudio() else controller.enableRxAudio() }, enabled = selected != null) {
                Text(if (controller.rxAudioEnabled) "STOP PC AUDIO" else "START PC AUDIO")
            }
            Button(openLog, enabled = selected != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("OPEN LOG") }
            controller.extended.profiles["global"].orEmpty().take(3).forEach { profile ->
                OutlinedButton({ controller.loadProfile("global", profile) }) { Text(profile) }
            }
        }
        HorizontalDivider()
        val txColor = when (controller.tx.state) {
            FlexTxState.TRANSMITTING, FlexTxState.TUNING, FlexTxState.KEYING -> Color(0xFFFF5252)
            FlexTxState.FAULT -> FlexAmber
            else -> FlexGreen
        }
        Text("TX · ${controller.tx.state}${controller.tx.fault?.let { " · $it" }.orEmpty()}", color = txColor, fontWeight = FontWeight.Black)
        val txInfo = controller.tx.eligibility
        Text("SLICE ${txInfo.txSliceIndex?.toString() ?: "—"} · ${txInfo.txFrequencyHz.takeIf { it > 0 }?.let(::formatRadioFrequency) ?: "—"} MHz · ${txInfo.txMode.ifBlank { "—" }} · ${txInfo.powerWatts} W · ANT ${txInfo.txAntenna.ifBlank { "—" }} · INTERLOCK ${controller.extended.transmit.interlock.ifBlank { "UNKNOWN" }}", color = FlexMuted)
        if (controller.tx.state == FlexTxState.DISABLED) {
            OutlinedTextField(txAcknowledgement, { txAcknowledgement = it }, label = { Text("Type: ENABLE FLEX TRANSMIT FOR THIS SESSION") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ controller.enableTransmitForSession(txAcknowledgement) }, enabled = txInfo.ready) { Text("ENABLE FLEX TRANSMIT FOR THIS SESSION") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ controller.armTransmit() }, enabled = controller.tx.state == FlexTxState.READY) { Text("ARM") }
            Button({ scope.launch { controller.startMox() } }, enabled = controller.tx.state == FlexTxState.ARMED) { Text("MOX / PTT") }
            Button({ controller.startMicrophoneTx() }, enabled = controller.tx.state == FlexTxState.ARMED) { Text("LIVE MIC") }
            Button({ scope.launch { controller.startTune() } }, enabled = controller.tx.state == FlexTxState.ARMED) { Text("TUNE") }
            Button({ scope.launch { controller.stopTransmit() } }, enabled = controller.tx.state !in setOf(FlexTxState.DISABLED, FlexTxState.READY)) { Text("STOP / RX") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cwxText, { cwxText = it.take(128) }, label = { Text("CWX text") }, singleLine = true, modifier = Modifier.weight(1f))
            Button({ scope.launch { controller.sendCwx(cwxText) } }, enabled = controller.tx.state == FlexTxState.ARMED && cwxText.isNotBlank()) { Text("SEND CWX") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("VOICE", color = FlexMuted, fontWeight = FontWeight.Bold)
            controller.availableVoiceMacros().filter { it.exists }.forEach { macro ->
                OutlinedButton({ controller.startVoiceMacroTx(macro.index) }, enabled = controller.tx.state == FlexTxState.ARMED) {
                    Text(macro.label)
                }
            }
            if (controller.availableVoiceMacros().none { it.exists }) Text("No recorded voice macros", color = FlexMuted)
        }
        if (controller.tx.rxUnconfirmed) Text("RX UNCONFIRMED — verify the radio before any further transmit action.", color = Color(0xFFFF5252), fontWeight = FontWeight.Black)
    } }
}

@Composable
private fun FlexPanWaterfall(controller: FlexRadioController, tune: (Long) -> Unit) {
    val frame = controller.spectrum
    val rows = controller.waterfallRows.takeLast(60)
    val pan = controller.extended.pans.firstOrNull { it.streamId == frame?.streamId } ?: controller.extended.pans.firstOrNull()
    val interaction = Modifier.pointerInput(pan?.centerHz, pan?.bandwidthHz) {
        detectTapGestures { offset ->
            val value = pan ?: return@detectTapGestures
            if (value.bandwidthHz <= 0 || size.width <= 0) return@detectTapGestures
            val low = value.centerHz - value.bandwidthHz / 2
            tune(low + (offset.x / size.width * value.bandwidthHz).toLong())
        }
    }
    Box(Modifier.fillMaxWidth().height(230.dp).then(interaction)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF080D11))
            if (rows.isNotEmpty()) {
                val rowHeight = size.height * .55f / rows.size
                rows.forEachIndexed { rowIndex, row ->
                    val step = (row.binsDbm.size / size.width.coerceAtLeast(1f)).coerceAtLeast(1f)
                    var x = 0f
                    while (x < size.width) {
                        val bin = row.binsDbm[(x * step).toInt().coerceIn(0, row.binsDbm.lastIndex)]
                        val level = ((bin + 130f) / 100f).coerceIn(0f, 1f)
                        val color = Color(level, level * .75f, (1f - level) * .45f + .1f)
                        drawRect(color, androidx.compose.ui.geometry.Offset(x, size.height * .45f + rowIndex * rowHeight), androidx.compose.ui.geometry.Size(2f, rowHeight + 1f))
                        x += 2f
                    }
                }
            }
            frame?.binsDbm?.takeIf { it.size > 1 }?.let { bins ->
                val path = Path()
                bins.forEachIndexed { index, dbm ->
                    val x = index.toFloat() / (bins.size - 1) * size.width
                    val y = ((-30f - dbm) / 100f).coerceIn(0f, 1f) * size.height * .42f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, FlexAmber, style = Stroke(2f))
            }
            drawLine(Color.White.copy(alpha = .35f), androidx.compose.ui.geometry.Offset(size.width / 2, 0f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), 1f)
        }
        if (frame == null) Text("WAITING FOR REGISTERED FLEX VITA PANADAPTER", color = FlexMuted, modifier = Modifier.align(Alignment.Center))
        Text("Tap spectrum to tune · packets ${controller.streamPacketCount()} · gaps ${controller.streamSequenceGaps()}", color = FlexMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

@Composable
private fun FlexMeters(controller: FlexRadioController) {
    val values = controller.meters
    if (values.isEmpty()) {
        Text("METERS · WAITING FOR LIVE VITA VALUES", color = FlexMuted)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        values.entries.take(6).forEach { (name, value) ->
            Column(Modifier.weight(1f)) {
                Text(name, color = FlexMuted, style = MaterialTheme.typography.labelSmall)
                Text("%.1f".format(value), color = FlexGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}
