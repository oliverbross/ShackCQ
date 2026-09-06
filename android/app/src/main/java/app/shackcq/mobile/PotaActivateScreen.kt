package app.shackcq.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ActivateAmber = Color(0xFFE9A72B)
private val ActivateGreen = Color(0xFF42C77B)
private val ActivateDanger = Color(0xFFE4544D)
private val ActivateInk = Color(0xFFF4F0E7)
private val ActivateMuted = Color(0xFFA5ADB2)
private val ActivatePanel = Color(0xFF1B2329)

internal fun defaultActivationRst(mode: String): String = if (modeFamily(mode) == "SSB" || modeFamily(mode) in setOf("FM", "AM")) "59" else "599"

@Composable
internal fun PotaActivationStrip(controller: PotaActivationController, radio: RadioState, onOpen: () -> Unit) {
    val session = controller.session?.takeIf { it.state == PotaActivationState.ACTIVE } ?: return
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(session.id) { while (true) { delay(1_000); now = Instant.now().epochSecond } }
    val progress = controller.progress(now) ?: return
    val elapsed = (now - session.setup.startAt).coerceAtLeast(0)
    val refs = session.setup.references
    Surface(Modifier.fillMaxWidth().clickable(onClick = onOpen), color = ActivateAmber.copy(alpha = .14f), tonalElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Park, null, tint = ActivateAmber)
            Text("POTA ACTIVATE · ${session.setup.primaryReference}${if (refs.size > 1) " [+${refs.size - 1}]" else ""} · ${progress.currentDay}/10 · " +
                "${bandForFrequency(radio.frequencyHz).ifBlank { "manual" }} ${radio.mode.ifBlank { "mode" }} · ${elapsed / 3600}:${"%02d".format((elapsed / 60) % 60)}",
                color = ActivateInk, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Outlined.ChevronRight, null, tint = ActivateMuted)
        }
    }
}

@Composable
internal fun PotaActivateScreen(controller: PotaActivationController, pota: PotaController, radio: RadioState,
    app: AppController, database: QsoDatabase, mutations: QsoMutationCoordinator,
    wavelog: WavelogController, callbook: CallbookController, cty: CtyController,
    compact: Boolean, onOpenLogbook: () -> Unit) {
    LaunchedEffect(radio.frequencyHz, radio.mode, radio.connected, controller.recovered) {
        if (radio.connected && !controller.recovered) controller.updateRadio(radio.frequencyHz, radio.mode)
    }
    when (controller.session?.state) {
        PotaActivationState.ACTIVE -> if (controller.recovered) PotaRecovery(controller) else
            PotaOperating(controller, radio, app, database, mutations, wavelog, callbook, cty, compact, onOpenLogbook)
        PotaActivationState.FINISHED -> PotaReview(controller, radio, compact)
        null -> PotaSetup(controller, pota, radio, app)
    }
}

@Composable private fun PotaRecovery(controller: PotaActivationController) {
    val session = controller.session ?: return
    var confirmAbandon by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).background(ActivatePanel).border(1.dp, ActivateAmber).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Restore, null, tint = ActivateAmber, modifier = Modifier.size(38.dp))
            Text("RESUME ACTIVATION", color = ActivateAmber, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("${session.setup.references.joinToString()} · started ${Instant.ofEpochSecond(session.setup.startAt).atZone(ZoneOffset.UTC)}", color = ActivateInk)
            Text("The app recovered the local session journal. No radio, audio, macro, PTT, TUNE, or transmit action was started.", color = ActivateMuted)
            Button(controller::resume, Modifier.fillMaxWidth()) { Text("RESUME LOCAL SESSION") }
            TextButton({ confirmAbandon = true }, Modifier.fillMaxWidth()) { Text("ABANDON SESSION STATE", color = ActivateDanger) }
        }
    }
    if (confirmAbandon) ConfirmDialog("Abandon recovered session?", "Saved QSOs remain in the logbook; only the session pointer is removed.",
        { controller.abandon(); confirmAbandon = false }, { confirmAbandon = false })
}

@Composable
private fun PotaSetup(controller: PotaActivationController, pota: PotaController, radio: RadioState, app: AppController) {
    val prepared = controller.pendingPlan
    var station by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.stationCallsign ?: app.stationCallsign) }
    var operator by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.operatorCallsign ?: app.stationCallsign) }
    var refsText by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.references?.joinToString(", ").orEmpty()) }
    var primary by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.primaryReference.orEmpty()) }
    var grid by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.stationGrid ?: app.stationGrid) }
    var location by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.location ?: app.stationName) }
    var state by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.state.orEmpty()) }
    var profile by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.stationProfileId.orEmpty()) }
    var power by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.txPowerW?.takeIf { it > 0 }?.toString() ?: radio.powerW.takeIf { it > 0 }?.toString().orEmpty()) }
    var antenna by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.antenna.orEmpty()) }
    var notes by rememberSaveable(prepared?.startAt) { mutableStateOf(prepared?.notes.orEmpty()) }
    var startUtc by rememberSaveable(prepared?.startAt) { mutableStateOf(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(prepared?.startAt ?: Instant.now().epochSecond))) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val refs = normalizePotaReferences(refsText.split(',', ' ', '\n'))
    LaunchedEffect(prepared?.startAt) { if (prepared != null) controller.consumePlan() }
    LaunchedEffect(search) { if (search.length >= 2) { delay(250); pota.searchParks(search) } }
    LaunchedEffect(refsText) { if (primary !in refs) primary = refs.firstOrNull().orEmpty() }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("POTA ACTIVATE", color = ActivateAmber, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text("LOCAL-FIRST SESSION SETUP", color = ActivateMuted) }
                AssistChip({}, { Text(if (radio.connected) "CAT LIVE" else "CAT OPTIONAL") }, enabled = false)
            }
            if (controller.recovered) Text("Resume activation available — no radio or transmit action was started.", color = ActivateAmber)
            if (controller.message.isNotBlank()) Text(controller.message, color = ActivateAmber)
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivateField("Station callsign", station, { station = it.uppercase(Locale.US) }, Modifier.weight(1f))
            ActivateField("Operator callsign", operator, { operator = it.uppercase(Locale.US) }, Modifier.weight(1f))
        } }
        item { ActivateField("Search downloaded parks", search, { search = it }, Modifier.fillMaxWidth()) }
        items(pota.parkResults.take(6), key = { it.reference }) { park ->
            ListItem(headlineContent = { Text("${park.reference} · ${park.name}", fontWeight = FontWeight.Bold) },
                supportingContent = { Text("${if (park.active) "ACTIVE" else "RETIRED"} · ${park.location}") },
                trailingContent = { TextButton({ refsText = (refs + park.reference).distinct().joinToString(", ") }) { Text("ADD") } },
                colors = ListItemDefaults.colors(containerColor = ActivatePanel))
        }
        item {
            ActivateField("POTA references · comma separated", refsText, { refsText = it.uppercase(Locale.US) }, Modifier.fillMaxWidth())
            Text(if (refs.isEmpty()) "A valid reference such as AU-1234 is required. Manual references are UNVERIFIED."
                else refs.joinToString(" · ") { ref -> "$ref${pota.parkResults.firstOrNull { it.reference == ref }?.let { if (it.active) " ACTIVE" else " RETIRED" } ?: " UNVERIFIED"}" },
                color = if (refs.isEmpty()) ActivateDanger else ActivateMuted, fontSize = 12.sp)
            if (refs.size > 1) ExposedPrimaryChoice(refs, primary) { primary = it }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivateField("Grid", grid, { grid = it.uppercase(Locale.US) }, Modifier.weight(1f))
            ActivateField("State / subdivision", state, { state = it.uppercase(Locale.US) }, Modifier.weight(1f))
        } }
        item { ActivateField("Manual location", location, { location = it }, Modifier.fillMaxWidth()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivateField("Station profile", profile, { profile = it }, Modifier.weight(1f))
            ActivateField("TX power W", power, { power = it.filter(Char::isDigit) }, Modifier.weight(1f))
        } }
        item { ActivateField("Antenna / setup label", antenna, { antenna = it }, Modifier.fillMaxWidth()) }
        item { ActivateField("Activation notes", notes, { notes = it }, Modifier.fillMaxWidth(), singleLine = false) }
        item { ActivateField("Start UTC · ISO-8601", startUtc, { startUtc = it }, Modifier.fillMaxWidth()) }
        item {
            Row(Modifier.fillMaxWidth().border(1.dp, ActivateAmber.copy(alpha = .5f)).clickable { acknowledged = !acknowledged }.padding(10.dp),
                verticalAlignment = Alignment.Top) {
                Checkbox(acknowledged, { acknowledged = it })
                Text("I have verified that I and all station equipment are on public property and entirely within every selected POTA park boundary.",
                    color = ActivateInk, modifier = Modifier.padding(top = 10.dp))
            }
            Text("Operator acknowledgement only — ShackCQ does not verify legal boundaries or GPS eligibility.", color = ActivateMuted, fontSize = 12.sp)
        }
        item {
            Button({
                val names = pota.parkResults.filter { it.reference in refs }.associate { it.reference to it.name }
                controller.start(PotaActivationSetup(station.trim(), operator.trim(), refs, primary, names, grid.trim(), location.trim(), state.trim(),
                    profile.trim(), "Elecraft KX3", power.toIntOrNull() ?: 0, antenna.trim(), notes.trim(),
                    runCatching { Instant.parse(startUtc).epochSecond }.getOrDefault(Instant.now().epochSecond), acknowledged))
            }, enabled = station.isNotBlank() && refs.isNotEmpty() && acknowledged && runCatching { Instant.parse(startUtc) }.isSuccess,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("START LOCAL ACTIVATION", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun PotaOperating(controller: PotaActivationController, radio: RadioState, app: AppController,
    database: QsoDatabase, mutations: QsoMutationCoordinator, wavelog: WavelogController,
    callbook: CallbookController, cty: CtyController, compact: Boolean, onOpenLogbook: () -> Unit) {
    val session = controller.session ?: return
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(session.id) { while (true) { delay(1_000); now = Instant.now().epochSecond } }
    val progress = controller.progress(now) ?: return
    val content: @Composable () -> Unit = {
        PotaFastLogger(controller, session, radio, app, database, mutations, wavelog, callbook, cty, onOpenLogbook)
    }
    if (compact) Column(Modifier.fillMaxSize()) {
        PotaSessionHeader(session, progress, now, radio)
        Box(Modifier.weight(1f)) { content() }
    } else Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PotaSessionHeader(session, progress, now, radio)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1.1f).fillMaxHeight()) { content() }
            PotaSessionSummary(controller, session, progress, now, radio, Modifier.weight(.9f).fillMaxHeight(), onOpenLogbook)
        }
    }
}

@Composable
private fun PotaSessionHeader(session: PotaActivationSession, progress: PotaActivationProgress, now: Long, radio: RadioState) {
    val secondsToMidnight = 86_400 - (now % 86_400)
    Column(Modifier.fillMaxWidth().background(ActivatePanel).border(1.dp, ActivateAmber.copy(alpha = .45f)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("POTA ACTIVATE · ${session.setup.references.joinToString(" + ")}", color = ActivateAmber, fontWeight = FontWeight.Black)
            Text(if (radio.connected) "CAT LIVE" else "CAT OFFLINE · MANUAL LOGGER", color = if (radio.connected) ActivateGreen else ActivateMuted)
        }
        Text(if (progress.thresholdReached) "10+ local QSOs — activation threshold reached" else "${progress.currentDay} / 10 local QSOs this UTC day",
            color = if (progress.thresholdReached) ActivateGreen else ActivateInk, fontWeight = FontWeight.Bold)
        if (secondsToMidnight <= 15 * 60) Text("UTC rollover in ${secondsToMidnight / 60} minutes — the session continues and exports split by date.", color = ActivateDanger)
    }
}

@Composable
private fun PotaFastLogger(controller: PotaActivationController, session: PotaActivationSession,
    radio: RadioState, app: AppController, database: QsoDatabase, mutations: QsoMutationCoordinator,
    wavelog: WavelogController, callbook: CallbookController, cty: CtyController, onOpenLogbook: () -> Unit) {
    var call by rememberSaveable { mutableStateOf("") }
    var frequency by rememberSaveable { mutableStateOf(if (radio.frequencyHz > 0) "%.6f".format(Locale.US, radio.frequencyHz / 1_000_000.0) else "") }
    var mode by rememberSaveable { mutableStateOf(radio.mode) }
    var sent by rememberSaveable { mutableStateOf(defaultActivationRst(radio.mode)) }
    var received by rememberSaveable { mutableStateOf(defaultActivationRst(radio.mode)) }
    var p2p by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var enrichment by remember { mutableStateOf<AndroidCallbookRecord?>(null) }
    val focus = remember { FocusRequester() }
    val draft = controller.pendingP2p
    LaunchedEffect(draft?.token) { draft?.let {
        call = it.callsign; frequency = "%.6f".format(Locale.US, it.frequencyHz / 1_000_000.0); mode = it.mode
        sent = defaultActivationRst(mode); received = sent; p2p = it.references.joinToString(", "); controller.consumeP2p(); focus.requestFocus()
    } }
    LaunchedEffect(session.id) { focus.requestFocus() }
    LaunchedEffect(call) {
        val requested = call.trim().uppercase(Locale.US)
        enrichment = null
        if (requested.length >= 3) { delay(450); callbook.lookup(requested) { if (call.trim().uppercase(Locale.US) == requested) enrichment = it } }
    }
    fun save() {
        val hz = frequency.replace(',', '.').toDoubleOrNull()?.times(1_000_000)?.toLong() ?: 0
        val normalizedCall = call.trim().uppercase(Locale.US)
        val normalizedMode = mode.trim().uppercase(Locale.US)
        if (!Regex("^[A-Z0-9/]+$").matches(normalizedCall) || hz <= 0 || normalizedMode.isBlank()) { status = "Correct callsign, frequency and mode before saving"; return }
        val now = wavelog.synchronizedNow(); val id = NativeCore.qsoIdentity(normalizedCall, DateTimeFormatter.ISO_INSTANT.format(now), hz, normalizedMode)
        val otherRefs = normalizePotaReferences(p2p.split(',', ' ', '\n'))
        val callbookRow = enrichment; val ctyRow = cty.lookup(normalizedCall)
        val qso = Qso(id, normalizedCall, hz, normalizedMode, sent, received, now.epochSecond,
            name = callbookRow?.name.orEmpty(), qth = callbookRow?.qth.orEmpty(), notes = notes,
            country = callbookRow?.country.orEmpty().ifBlank { ctyRow?.country.orEmpty() },
            band = bandForFrequency(hz), potaRef = otherRefs.firstOrNull().orEmpty(), frequencyRxHz = hz,
            bandRx = bandForFrequency(hz), txPowerW = session.setup.txPowerW.takeIf { it > 0 } ?: radio.powerW,
            operatorCallsign = session.setup.operatorCallsign, stationCallsign = session.setup.stationCallsign,
            stationProfileId = if (wavelog.logMode == LogMode.WAVELOG) wavelog.stationId else session.setup.stationProfileId,
            stationLocation = session.setup.location, myGrid = session.setup.stationGrid, myState = session.setup.state,
            myPotaRef = session.setup.primaryReference, radioModel = session.setup.radioModel,
            grid = callbookRow?.grid.orEmpty(), dxcc = callbookRow?.dxcc.orEmpty().ifBlank { ctyRow?.dxcc.orEmpty() },
            continent = callbookRow?.continent.orEmpty().ifBlank { ctyRow?.continent.orEmpty() },
            region = callbookRow?.region.orEmpty().ifBlank { ctyRow?.region.orEmpty() },
            cqZone = callbookRow?.cqZone.orEmpty().ifBlank { ctyRow?.cqZone.orEmpty() },
            ituZone = callbookRow?.ituZone.orEmpty().ifBlank { ctyRow?.ituZone.orEmpty() }, state = callbookRow?.state.orEmpty(),
            antennaPath = session.setup.antenna, syncState = if (wavelog.logMode == LogMode.WAVELOG) "pending" else "local",
            activationSessionId = session.id, activationProgram = "POTA", myPotaRefs = session.setup.references, potaRefs = otherRefs)
        if (!mutations.save(qso)) { status = "DUPLICATE NOT SAVED"; return }
        controller.recordQso(id)
        if (mutations.isMapped(qso)) status = "SAVED · NATIVE WAVELOG QUEUED WITH PRIMARY OWN PARK"
        else if (wavelog.logMode == LogMode.WAVELOG) { wavelog.enqueue(id, database.toADIF(qso)); status = "SAVED · LEGACY WAVELOG QUEUED WITH PRIMARY OWN PARK" }
        else status = "SAVED · LOCAL JOURNAL"
        call = ""; notes = ""; p2p = ""; enrichment = null; sent = defaultActivationRst(mode); received = sent; focus.requestFocus()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FAST LOGGER", color = ActivateAmber, fontWeight = FontWeight.Black, fontSize = 18.sp)
        OutlinedTextField(call, { call = it.uppercase(Locale.US) }, label = { Text("CALLSIGN") }, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Black),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivateField("Frequency MHz", frequency, { frequency = it }, Modifier.weight(1f))
            ActivateField("Mode", mode, { mode = it.uppercase(Locale.US); sent = defaultActivationRst(it); received = sent }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivateField("RST sent", sent, { sent = it }, Modifier.weight(1f))
            ActivateField("RST received", received, { received = it }, Modifier.weight(1f))
        }
        ActivateField("P2P references · comma separated", p2p, { p2p = it.uppercase(Locale.US) }, Modifier.fillMaxWidth())
        enrichment?.let { Text("${it.source} · ${it.name} · ${it.qth} · ${it.country}", color = ActivateMuted, fontSize = 12.sp) }
        ActivateField("Quick notes", notes, { notes = it }, Modifier.fillMaxWidth(), singleLine = false)
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("SAVED")) ActivateGreen else ActivateAmber)
        Button(::save, enabled = call.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Outlined.Save, null); Spacer(Modifier.width(8.dp)); Text("LOG QSO", fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Text("Saving never keys PTT, TUNE, CW, or voice. CAT is optional; manual frequency and mode remain editable.", color = ActivateMuted, fontSize = 12.sp)
        TextButton(onOpenLogbook) { Text("OPEN LOGBOOK TO CORRECT / DELETE RECENT QSOS") }
        Text("RECENT SESSION QSOS", color = ActivateAmber, fontWeight = FontWeight.Black)
        controller.qsos().takeLast(5).reversed().forEach { row ->
            Text("${row.callsign} · ${row.band} ${row.mode}${if (row.potaRefs.isNotEmpty()) " · P2P ${row.potaRefs.joinToString()}" else ""}", color = ActivateInk)
        }
    }
}

@Composable
private fun PotaSessionSummary(controller: PotaActivationController, session: PotaActivationSession, progress: PotaActivationProgress,
    now: Long, radio: RadioState, modifier: Modifier, onOpenLogbook: () -> Unit) {
    val context = LocalContext.current
    val inAppBrowser = LocalInAppBrowserState.current
    var confirmFinish by remember { mutableStateOf(false) }; var confirmAbandon by remember { mutableStateOf(false) }
    Column(modifier.background(ActivatePanel).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CURRENT SESSION", color = ActivateAmber, fontWeight = FontWeight.Black)
        Text("${progress.total} QSOs · ${progress.uniqueCalls} calls · ${progress.p2p} P2P", color = ActivateInk)
        Text("Bands: ${progress.bands.entries.joinToString { "${it.key} ${it.value}" }.ifBlank { "none" }}", color = ActivateMuted)
        Text("Modes: ${progress.modes.entries.joinToString { "${it.key} ${it.value}" }.ifBlank { "none" }}", color = ActivateMuted)
        Text("UTC ${Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}", color = ActivateInk, fontFamily = FontFamily.Monospace)
        HorizontalDivider()
        val recent = controller.qsos().takeLast(6).reversed()
        Text("RECENT QSOS", color = ActivateInk, fontWeight = FontWeight.Black)
        recent.forEach { Text("${it.callsign} · ${it.band} ${it.mode}${if (it.potaRefs.isNotEmpty()) " · P2P ${it.potaRefs.joinToString()}" else ""}", color = ActivateInk) }
        if (recent.isEmpty()) Text("No QSOs saved in this session.", color = ActivateMuted)
        OutlinedButton(onOpenLogbook, modifier = Modifier.fillMaxWidth()) { Text("CORRECT IN LOGBOOK") }
        OutlinedButton({ copySpotSummary(context, session, radio); inAppBrowser?.open("https://pota.app/") }, modifier = Modifier.fillMaxWidth()) {
            Text("OPEN POTA SPOTTING")
        }
        Button({ confirmFinish = true }, modifier = Modifier.fillMaxWidth()) { Text("FINISH & REVIEW") }
        TextButton({ confirmAbandon = true }, modifier = Modifier.fillMaxWidth()) { Text("ABANDON SESSION STATE", color = ActivateDanger) }
    }
    if (confirmFinish) ConfirmDialog("Finish activation?", "This enters local review. It does not upload or delete any QSO.", { controller.finish(); confirmFinish = false }, { confirmFinish = false })
    if (confirmAbandon) ConfirmDialog("Abandon session state?", "The active-session pointer is removed. Already saved QSOs remain in the logbook.", { controller.abandon(); confirmAbandon = false }, { confirmAbandon = false })
}

@Composable
private fun PotaReview(controller: PotaActivationController, radio: RadioState, compact: Boolean) {
    val context = LocalContext.current; val session = controller.session ?: return; val qsos = controller.qsos()
    val inAppBrowser = LocalInAppBrowserState.current
    val result = remember(session, qsos) { buildPotaExports(session, qsos) }
    var pendingFile by remember { mutableStateOf<PotaAdifFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingFile; if (uri != null && file != null) context.contentResolver.openOutputStream(uri)?.use { it.write(file.contents.toByteArray()) }
        pendingFile = null
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { Text("POTA ACTIVATION REVIEW", color = ActivateAmber, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("${session.setup.references.joinToString()} · ${qsos.size} QSOs · ${qsos.map { it.callsign }.distinct().size} unique calls", color = ActivateInk)
            Text("${qsos.count { it.potaRefs.isNotEmpty() || it.potaRef.isNotBlank() }} P2P · ${qsos.map { it.band }.distinct().filter(String::isNotBlank).joinToString()} · ${qsos.map { it.mode }.distinct().joinToString()}", color = ActivateMuted)
            Text(if (qsos.groupingBy { utcDay(it.createdAt) }.eachCount().values.any { it >= 10 }) "Local 10-QSO threshold reached" else "Local 10-QSO threshold not reached", color = ActivateInk)
            Text("Station ${session.setup.stationCallsign} · Operator ${session.setup.operatorCallsign} · ${session.setup.location} ${session.setup.stationGrid} ${session.setup.state}", color = ActivateInk)
        }
        if (result.corrections.isNotEmpty()) item {
            Text("CORRECTIONS REQUIRED", color = ActivateDanger, fontWeight = FontWeight.Black)
            result.corrections.forEach { Text("• $it", color = ActivateDanger) }
            Text("No invalid or empty ADIF file was created.", color = ActivateMuted)
        }
        items(result.files, key = { it.filename }) { file ->
            ListItem(headlineContent = { Text(file.filename, fontWeight = FontWeight.Bold) },
                supportingContent = { Text("${file.ownReference} · ${file.utcDay}") },
                trailingContent = { Row { TextButton({ shareFiles(context, listOf(file)) }) { Text("SHARE") }
                    TextButton({ pendingFile = file; saveLauncher.launch(file.filename) }) { Text("SAVE") } } },
                colors = ListItemDefaults.colors(containerColor = ActivatePanel))
        }
        if (result.files.isNotEmpty()) item {
            Button({ shareFiles(context, result.files) }, Modifier.fillMaxWidth()) { Text("SHARE ALL") }
            OutlinedButton({ inAppBrowser?.open("https://pota.app/") }, Modifier.fillMaxWidth()) { Text("OPEN POTA · CHOOSE MY LOG UPLOADS") }
        }
        item { Text("Files are generated from the local journal and kept separate from it. No direct upload is performed.", color = ActivateMuted)
            TextButton({ controller.dismissReview() }, Modifier.fillMaxWidth()) { Text("CLOSE REVIEW") } }
    }
}

@Composable private fun ActivateField(label: String, value: String, update: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true) =
    OutlinedTextField(value, update, label = { Text(label) }, singleLine = singleLine, modifier = modifier)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExposedPrimaryChoice(refs: List<String>, selected: String, update: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(selected, {}, readOnly = true, label = { Text("Primary park") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) { refs.forEach { ref -> DropdownMenuItem({ Text(ref) }, { update(ref); expanded = false }) } }
    }
}

@Composable private fun ConfirmDialog(title: String, body: String, confirm: () -> Unit, dismiss: () -> Unit) = AlertDialog(
    onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { Button(confirm) { Text("CONFIRM") } },
    dismissButton = { TextButton(dismiss) { Text("CANCEL") } })

private fun copySpotSummary(context: Context, session: PotaActivationSession, radio: RadioState) {
    val summary = listOf(session.setup.stationCallsign, session.setup.primaryReference,
        radio.frequencyHz.takeIf { it > 0 }?.let { "%.6f MHz".format(Locale.US, it / 1_000_000.0) }.orEmpty(), radio.mode).filter(String::isNotBlank).joinToString(" · ")
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("POTA spot summary", summary))
}

private fun shareFiles(context: Context, files: List<PotaAdifFile>) {
    val directory = File(context.cacheDir, "pota-exports").apply { mkdirs() }
    val uris = ArrayList<Uri>()
    files.forEach { export ->
        val file = File(directory, export.filename); file.writeText(export.contents)
        uris += FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    intent.type = "application/octet-stream"; intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share POTA ADIF"))
}
