package app.shackcq.mobile

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import app.shackcq.mobile.hamclock.mergeDxNews
import app.shackcq.mobile.hamclock.HamClockClusterPreference
import app.shackcq.mobile.hamclock.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

private val DxBg = Color(0xFF111519)
private val DxPanel = Color(0xFF1B2228)
private val DxRaised = Color(0xFF283139)
private val DxInk = Color(0xFFF4F0E7)
private val DxMuted = Color(0xFFA5ADB2)
private val DxAmber = Color(0xFFE9A72B)
private val DxGreen = Color(0xFF42C77B)
private val DxRed = Color(0xFFE4544D)
private val DxCyan = Color(0xFF43C7D9)
private val DxYellow = Color(0xFFF4C94E)
private val DxBands = listOf("ALL") + spotBandOptions

@Composable
fun NeuralDxScreen(
    controller: NeuralDxController,
    features: FeatureController,
    database: QsoDatabase,
    wavelog: WavelogController,
    callbook: CallbookController,
    cty: CtyController,
    app: AppController,
    clusterPreference: HamClockClusterPreference,
    dxNewsPreference: HamClockDxNewsPreference,
    updateDxNewsPreference: (HamClockDxNewsPreference) -> Unit,
    tune: (String) -> Unit,
    requestReceiveTune: (Long, String?, String, String) -> Unit,
    intelligenceNeeds: Map<String, List<String>> = emptyMap(),
    bandHealthPreference: HamClockBandHealthPreference = HamClockBandHealthPreference(),
    bandHealthSnapshot: HamClockBandHealthSnapshot = HamClockBandHealthSnapshot(),
    updateBandHealthPreference: (HamClockBandHealthPreference) -> Unit = {},
    openCallHistory: (String) -> Unit = {},
    previousQsos: (AndroidDXSpot) -> Unit,
) {
    var page by remember { mutableStateOf(NeuralDxPage.COCKPIT) }
    val stationId = wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG }
    val stationGrid = wavelog.selectedStation?.grid?.ifBlank { null } ?: app.stationGrid
    val stationCall = wavelog.selectedStation?.callsign?.ifBlank { null } ?: app.stationCallsign.ifBlank { features.clusterCallsign }
    val policySpots = remember(features.liveSpots, clusterPreference, Instant.now().epochSecond / 60) {
        filterClusterPresentation(features.liveSpots, clusterPreference, Instant.now().epochSecond)
    }
    val enrichedPolicySpots = remember(policySpots, controller.enrichedSpots) {
        policySpots.map { spot -> controller.enrichedSpots.firstOrNull { it.id == spot.id } ?: spot }
    }
    LaunchedEffect(controller.requestedSignalReportId) {
        if (controller.requestedSignalReportId != null) page = NeuralDxPage.MAP
    }
    LaunchedEffect(controller.requestedPage) {
        controller.requestedPage?.let { requested ->
            page = requested
            controller.consumeRequestedPage()
        }
    }

    LaunchedEffect(policySpots, stationId, cty.dataRevision) {
        controller.ingest(policySpots, stationId, cty, stationCall)
    }
    LaunchedEffect(controller) { controller.ensureDxNews() }
    LaunchedEffect(stationGrid, stationCall, stationId, page) {
        if (controller.lastRefreshEpoch == 0L || Instant.now().epochSecond - controller.lastRefreshEpoch > 15 * 60) {
            controller.refresh(stationCall, stationGrid, stationId, policySpots,
                refreshScope = if (page == NeuralDxPage.SATELLITES) NeuralDxRefreshScope.FULL_DX else NeuralDxRefreshScope.HOME)
            if (!features.solar.valid) features.refreshSolar()
        }
    }
    DisposableEffect(page, stationGrid) {
        val active = page == NeuralDxPage.SATELLITES
        controller.setSatelliteWorkspaceActive(active, stationGrid)
        onDispose { if (active) controller.setSatelliteWorkspaceActive(false, stationGrid) }
    }

    Box(Modifier.fillMaxSize().background(DxBg).navigationBarsPadding().clipToBounds()) {
    Column(Modifier.fillMaxSize().padding(14.dp).testTag("dx-safe-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("NEURAL DX WATCHER", color = DxInk, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("ShackCQ native · Neural DX behavioural baseline 12.1 · ${features.clusterStatus}",
                    color = DxMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    !features.workedLog.loaded -> "Log intelligence loading"
                    !features.workedLog.complete -> "Log intelligence partial"
                    else -> "Log intelligence ready"
                }, color = DxMuted, fontSize = 11.sp)
            }
            DxMetric("SPOTS", policySpots.size.toString(), DxCyan)
            DxMetric("SFI", features.solar.flux.takeIf { features.solar.valid }?.roundToInt()?.toString() ?: "—", DxAmber)
            DxMetric("A", features.solar.aIndex.takeIf { features.solar.valid }?.roundToInt()?.toString() ?: "—",
                if (features.solar.aIndex >= 30) DxRed else DxAmber)
            DxMetric("KP", features.solar.kpIndex.takeIf { features.solar.valid }?.let { "%.1f".format(Locale.US, it) } ?: "—",
                if (features.solar.kpIndex >= 5) DxRed else DxGreen)
            Button({ controller.refresh(stationCall, stationGrid, stationId, policySpots, true,
                if (page == NeuralDxPage.SATELLITES) NeuralDxRefreshScope.FULL_DX else NeuralDxRefreshScope.HOME); features.refreshSolar() },
                enabled = !controller.refreshing, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(5.dp)); Text(if (controller.refreshing) "REFRESHING" else "REFRESH")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            NeuralDxPage.entries.forEach { item ->
                FilterChip(page == item, { page = item }, { Text(item.label, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(dxPageIcon(item), null, modifier = Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val providerSummary = if (!controller.refreshing && controller.providerStatuses.isNotEmpty()) neuralProviderSummary(controller.providerStatuses) else controller.status
            Text(providerSummary, color = if (providerSummary.contains("unavailable") || providerSummary.contains("stale")) DxYellow else DxMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("QTH ${stationGrid.ifBlank { "NOT SET" }} · ${if (wavelog.logMode == LogMode.WAVELOG) "WAVELOG" else "LOCAL LOG"}",
                color = DxYellow, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        HorizontalDivider(color = Color(0xFF465159))
        val pageModifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("dx-page-${page.name.lowercase()}")
        when (page) {
            NeuralDxPage.COCKPIT -> DxCockpit(controller, features, policySpots, database, wavelog, callbook, cty, app, stationGrid, tune,
                requestReceiveTune, intelligenceNeeds, previousQsos, { page = NeuralDxPage.INSIGHT }, pageModifier)
            NeuralDxPage.MAP -> DxMap(controller, enrichedPolicySpots, features, cty, app, stationGrid,
                database, wavelog, requestReceiveTune, previousQsos, pageModifier)
            NeuralDxPage.INSIGHT -> DxInsightPage(controller, policySpots, pageModifier)
            NeuralDxPage.WORLD -> DxWorldPage(controller, features, pageModifier)
            NeuralDxPage.BRIEFING -> DxBriefingPage(controller, features, policySpots, database, wavelog, cty,
                requestReceiveTune, intelligenceNeeds, previousQsos, dxNewsPreference, updateDxNewsPreference, pageModifier)
            NeuralDxPage.OBSERVATIONS -> DxRfEvidencePage(controller, features, policySpots, database, cty, app,
                callbook, stationId, stationCall, stationGrid, bandHealthPreference, bandHealthSnapshot, updateBandHealthPreference,
                openCallHistory, requestReceiveTune, pageModifier)
            NeuralDxPage.SATELLITES -> DxSatellitesPage(controller, stationGrid, pageModifier)
            NeuralDxPage.HISTORY -> DxHistoryPage(controller, pageModifier)
            NeuralDxPage.WEATHER -> DxWeatherPage(controller, features, stationGrid, pageModifier)
        }
    }
    }
}

@Composable
private fun DxHistoryPage(controller: NeuralDxController, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(250)
        controller.searchSpotHistory(query)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DxSection("HISTORICAL SPOT JOURNAL · LOCAL SQLITE") {
            OutlinedTextField(query, { query = it.uppercase(Locale.US).filter { ch -> ch.isLetterOrDigit() || ch == '/' } },
                label = { Text("Exact callsign") }, placeholder = { Text("K1ABC") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
            Text("Append-only observations are deduplicated by source ID and indexed by callsign, band and UTC time. Up to 500,000 recent observations are retained offline.",
                color = DxMuted)
        }
        if (controller.historySearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (query.length < 2) DxEmpty("Enter at least two callsign characters")
        else if (!controller.historySearching && controller.historyResults.isEmpty()) DxEmpty("No historical observations match $query")
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(controller.historyResults, key = SpotHistorySummary::callsign) { row ->
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = DxPanel)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.callsign, color = DxCyan, fontWeight = FontWeight.Black, fontSize = 22.sp)
                            Text("${row.observations} observations · ${row.spotters} spotters", color = DxInk)
                        }
                        Text("First ${spotHistoryDate(row.firstEpoch)} · last ${spotHistoryDate(row.lastEpoch)}", color = DxMuted)
                        Text("Bands · ${row.bands.joinToString().ifBlank { "unknown" }}", color = DxInk)
                        Text("Modes · ${row.modes.joinToString().ifBlank { "unknown" }}", color = DxInk)
                        Text("Entities · ${row.countries.take(6).joinToString().ifBlank { "unknown" }}", color = DxMuted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun spotHistoryDate(epoch: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
    .withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epoch))

@Composable private fun DxRfEvidencePage(
    controller: NeuralDxController,
    features: FeatureController,
    cluster: List<AndroidDXSpot>,
    database: QsoDatabase,
    cty: CtyController,
    app: AppController,
    callbook: CallbookController,
    stationId: String?,
    stationCall: String,
    stationGrid: String,
    bandHealthPreference: HamClockBandHealthPreference,
    bandHealthSnapshot: HamClockBandHealthSnapshot,
    updateBandHealthPreference: (HamClockBandHealthPreference) -> Unit,
    openCallHistory: (String) -> Unit,
    requestReceiveTune: (Long, String?, String, String) -> Unit,
    modifier: Modifier,
) {
    val inAppBrowser = LocalInAppBrowserState.current
    val stationPoint = remember(stationGrid) { maidenheadCenter(stationGrid) }
    val rbn = remember(features.rbnObservations, stationCall, stationPoint, cty.dataRevision, callbook.status) {
        fun ctyPoint(call: String) = cty.lookup(call)?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
            ?.let { GeoPoint(it.latitude, it.longitude) }
        features.rbnObservations.map { row -> resolveRbnObservationView(row, stationCall, stationPoint,
            { call -> callbook.cachedRecord(call)?.grid }, ::ctyPoint) }
    }
    val wspr = controller.wsprPersonal.reports
    val psk = controller.mySignal.reports
    val ibp = remember(Instant.now().epochSecond / 10) { hamClockIbpSchedule() }
    var selectedRbn by remember { mutableStateOf<HamClockRbnObservation?>(null) }
    var selectedSignal by remember { mutableStateOf<Pair<String, SignalReport>?>(null) }
    var selectedCluster by remember { mutableStateOf<AndroidDXSpot?>(null) }
    var selectedIbp by remember { mutableStateOf<HamClockIbpTransmission?>(null) }
    var selectedIbpSite by remember { mutableStateOf<HamClockIbpBeacon?>(null) }
    var selectedIbpObserved by remember { mutableStateOf<HamClockIbpObservedEvidence?>(null) }
    var selectedBand by rememberSaveable { mutableStateOf(bandHealthPreference.visibleBands.firstOrNull() ?: "20m") }
    var statuses by remember { mutableStateOf<Map<String, SpotLogStatus>>(emptyMap()) }
    val watchlist = features.watchlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
    fun toggleWatch(call: String) {
        val current = watchlist.toMutableSet()
        if (!current.add(call)) current.remove(call)
        features.setWatchlist(current.sorted().joinToString("\n"))
    }
    LaunchedEffect(rbn, psk, wspr, stationId, cty.dataRevision) {
        val identities = cluster.map { row ->
            val entity = cty.lookup(row.callsign)
            SpotLogIdentity(row.id, row.callsign, entity?.dxcc.orEmpty(), entity?.country.orEmpty(), row.band, row.mode)
        } + rbn.map { row ->
            val entity = cty.lookup(row.dxCall)
            SpotLogIdentity(row.id, row.dxCall, entity?.dxcc.orEmpty(), entity?.country.orEmpty(), row.band, row.mode)
        } + (psk + wspr).map { row ->
            val entity = cty.lookup(row.callsign)
            row.toSpotLogIdentity(entity)
        }
        statuses = withContext(Dispatchers.IO) { database.spotStatuses(identities, stationId) }
    }
    LaunchedEffect(controller.requestedRfEvidenceId, rbn, wspr, ibp) {
        val id = controller.requestedRfEvidenceId ?: return@LaunchedEffect
        selectedRbn = rbn.firstOrNull { it.id == id }
        selectedSignal = wspr.firstOrNull { signalReportReference(it) == id }?.let { "WSPR" to it }
        selectedIbp = ibp.transmissions.firstOrNull { it.beacon.callsign == id }
        selectedIbpSite = if (selectedIbp == null) hamClockIbpManifest.firstOrNull { it.callsign == id } else null
        // A request is a one-shot navigation intent, even if its bounded source aged out.
        controller.consumeRequestedRfEvidence()
    }
    LaunchedEffect(controller.requestedBandEvidence) {
        controller.requestedBandEvidence?.let { selectedBand = it }
        controller.consumeRequestedBandEvidence()
    }
    val evidence = cluster.map { HamClockBandEvidence("CLUSTER", it.band, it.mode, it.callsign, it.spotter,
        observedEpoch = it.receivedEpoch, frequencyHz = it.frequencyHz) } +
        controller.mySignal.reports.map { HamClockBandEvidence("PSK", it.band, it.mode, it.callsign,
            it.receiverCallsign, it.snr, it.epoch) } +
        rbn.map { HamClockBandEvidence("RBN", it.band, it.mode, it.dxCall, it.skimmerCall, it.snr, it.observedEpoch, it.frequencyHz) } +
        wspr.map { HamClockBandEvidence("WSPR", it.band, "WSPR", it.callsign, it.receiverCallsign, it.snr, it.epoch) }
    val health = bandHealthSnapshot.rows
    val ibpObserved = observedIbpEvidence(evidence)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { Text("MEASURED RF EVIDENCE", color = DxAmber, fontWeight = FontWeight.Black)
            Text("RBN uses the configured retail cluster. Personal WSPR reuses PSK Reporter. Regional WSPR.live: ${controller.wsprPersonal.regionalState}.",
                color = DxMuted, fontSize = 10.sp) }
        item {
            Text("SOURCE CONTROLS", color = DxCyan, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("CLUSTER", "PSK", "RBN", "WSPR").forEach { source ->
                    FilterChip(source in bandHealthPreference.enabledSources, {
                        val sources = bandHealthPreference.enabledSources.toMutableSet()
                        if (!sources.add(source)) sources.remove(source)
                        updateBandHealthPreference(bandHealthPreference.copy(enabledSources = sources))
                    }, { Text(source) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(5, 10, 15, 30, 60).forEach { window -> FilterChip(bandHealthPreference.windowMinutes == window,
                    { updateBandHealthPreference(bandHealthPreference.copy(windowMinutes = window)) }, { Text("${window}m") }) }
                listOf("ALL", "CW", "SSB", "FT8", "WSPR", "RTTY").forEach { mode -> FilterChip(bandHealthPreference.mode == mode,
                    { updateBandHealthPreference(bandHealthPreference.copy(mode = mode)) }, { Text(mode) }) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                bandHealthPreference.visibleBands.sorted().forEach { band -> FilterChip(selectedBand == band, { selectedBand = band }, { Text(band) }) }
            }
            Text(bandHealthSnapshot.sourceStates.entries.joinToString(" · ") { "${it.key} ${it.value}" }, color = DxMuted, fontSize = 10.sp)
            if (bandHealthSnapshot.message.isNotBlank()) Text(bandHealthSnapshot.message, color = DxYellow, fontSize = 10.sp)
        }
        item { Text("RBN · ${rbn.size}", color = DxCyan, fontWeight = FontWeight.Bold) }
        items(rbn.take(120), key = { it.id }) { row ->
            val status = statuses[row.id]
            Surface(color = DxPanel, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().clickable { selectedRbn = row }) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.dxCall, color = DxInk, fontWeight = FontWeight.Bold)
                    Text("${row.band} ${row.mode} · ${row.skimmerCall} · ${row.snr?.let { "$it dB" } ?: "SNR —"}",
                        color = DxMuted, modifier = Modifier.weight(1f))
                    Text("CS ${status?.callStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_CS, status?.callStatus)), fontWeight = FontWeight.Bold)
                    Text("DS ${status?.dxccStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_DS, status?.dxccStatus)), fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("PERSONAL WSPR · ${wspr.size}", color = DxCyan, fontWeight = FontWeight.Bold) }
        items(wspr.take(100), key = { signalReportReference(it) }) { row ->
            val id = signalReportReference(row)
            Surface(color = DxPanel, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().clickable { selectedSignal = "WSPR" to row }) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.callsign, color = DxInk, fontWeight = FontWeight.Bold)
                    Text("${row.direction.name.replace('_', ' ')} · ${row.band} · ${row.snr?.let { "$it dB" } ?: "SNR —"}",
                        color = DxMuted, modifier = Modifier.weight(1f))
                    val status = statuses[id]
                    Text("CS ${status?.callStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_CS, status?.callStatus)), fontWeight = FontWeight.Bold)
                    Text("DS ${status?.dxccStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_DS, status?.dxccStatus)), fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("IBP SCHEDULE · slot ${ibp.slot + 1}/18", color = DxCyan, fontWeight = FontWeight.Bold)
            Text("Schedule reference only — never presented as heard evidence.", color = DxMuted, fontSize = 10.sp) }
        items(ibp.transmissions, key = { it.band }) { row ->
            Surface(color = DxPanel, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().clickable { selectedIbp = row }) {
                Text("${row.band} · ${row.beacon.callsign} ${row.beacon.grid} · ${formatMHz(row.frequencyHz)} MHz",
                    color = DxInk, modifier = Modifier.padding(8.dp))
            }
        }
        item { Text("IBP OBSERVED EVIDENCE · ${ibpObserved.size}", color = DxCyan, fontWeight = FontWeight.Bold)
            Text("Cluster/RBN observations are listed separately from the schedule.", color = DxMuted, fontSize = 10.sp) }
        items(ibpObserved, key = { "${it.source}|${it.beacon.callsign}|${it.observedEpoch}|${it.receiver}" }) { row ->
            Surface(color = DxPanel, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().clickable { selectedIbpObserved = row }) {
                Text("${row.source} · ${row.beacon.callsign} · ${row.band} ${row.mode} · heard by ${row.receiver.ifBlank { "unknown" }}",
                    color = DxInk, modifier = Modifier.padding(8.dp))
            }
        }
        item { Text("BAND HEALTH MATRIX · ${bandHealthPreference.windowMinutes}m · ${bandHealthPreference.mode}", color = DxCyan, fontWeight = FontWeight.Bold) }
        items(health, key = { it.band }) { row ->
            Text("${row.band.padEnd(5)} ${row.state} · n=${row.observations} · ${row.trend} · ${row.confidence}",
                color = if (row.state == "ACTIVE") DxGreen else DxMuted, fontFamily = FontFamily.Monospace)
            Text(row.reasons.joinToString(" · "), color = DxMuted, fontSize = 9.sp)
        }
        item {
            val selected = health.firstOrNull { it.band == selectedBand }
            val historical = bandHealthSnapshot.historical.filter { it.band == selectedBand &&
                (bandHealthPreference.mode == "ALL" || it.modeFamily == bandHealthPreference.mode) }
            Text("SELECTED BAND · $selectedBand", color = DxCyan, fontWeight = FontWeight.Bold)
            Text(selected?.let { "${it.state} · ${it.confidence} · ${it.observations} live observations" } ?: "No live row", color = DxInk)
            Text("HISTORICAL PROJECTION · ${historical.sumOf { it.qsoCount }} QSOs · ${historical.sumOf { it.uniqueCalls }} unique · ${historical.sumOf { it.comparableWindowCount }} comparable UTC-window",
                color = DxMuted, fontSize = 10.sp)
            Text("CONTRIBUTING OBSERVATIONS · ${bandHealthSnapshot.contributors[selectedBand].orEmpty().size}", color = DxMuted)
        }
        items(bandHealthSnapshot.contributors[selectedBand].orEmpty().take(24), key = { it.id }) { contributor ->
            Surface(color = DxPanel, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().clickable {
                when (contributor.source) {
                    "RBN" -> selectedRbn = rbn.firstOrNull { it.id == contributor.sourceReferenceId }
                    "PSK" -> selectedSignal = controller.mySignal.reports.firstOrNull {
                        signalReportReference(it) == contributor.sourceReferenceId }?.let { "PSK" to it }
                    "WSPR" -> selectedSignal = wspr.firstOrNull {
                        signalReportReference(it) == contributor.sourceReferenceId }?.let { "WSPR" to it }
                    "CLUSTER" -> selectedCluster = features.liveSpots.firstOrNull { it.id == contributor.sourceReferenceId }
                    "IBP" -> selectedIbp = ibp.transmissions.firstOrNull { it.beacon.callsign == contributor.sourceReferenceId }
                }
            }) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val status = statuses[contributor.sourceReferenceId]
                    Text(contributor.source, color = DxCyan, fontWeight = FontWeight.Bold)
                    Text(contributor.callsign, color = DxInk, fontWeight = FontWeight.Bold)
                    Text("${contributor.band} ${contributor.mode} · ${contributor.receiver.ifBlank { "receiver —" }} · ${contributor.snr?.let { "$it dB" } ?: "SNR —"}",
                        color = DxMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (status != null) Text("CS ${status.callStatus} · DS ${status.dxccStatus}", color = Color(app.spotStatusColour(SPOT_STATUS_CS, status.callStatus)), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    selectedRbn?.let { row ->
        AlertDialog(onDismissRequest = { selectedRbn = null }, title = { Text("${row.skimmerCall} heard ${row.dxCall}") },
            text = { Text("${row.band} ${row.mode} · ${formatMHz(row.frequencyHz)} MHz · ${row.snr?.let { "$it dB" } ?: "SNR unavailable"} · ${row.wpm?.let { "$it WPM" } ?: row.bps?.let { "$it BPS" } ?: "speed unavailable"}\n" +
                "Observed ${utcSeconds(row.observedEpoch)} UTC · received ${utcSeconds(row.receivedEpoch)} UTC\n" +
                "DX geometry ${row.dxGeometry} · skimmer geometry ${row.skimmerGeometry}\n" +
                "Call ${statuses[row.id]?.callStatus ?: "—"} · DXCC ${statuses[row.id]?.dxccStatus ?: "—"} · ${if (row.dxCall in watchlist) "WATCHLISTED" else "not watched"}\n${row.rawComment}") },
            confirmButton = { Button({ requestReceiveTune(row.frequencyHz, row.mode, "RBN observation",
                "Review receive-only frequency change"); selectedRbn = null }) { Text("Review receive") } },
            dismissButton = { Row { TextButton({ openCallHistory(row.dxCall); selectedRbn = null }) { Text("Logbook history") }
                TextButton({ toggleWatch(row.dxCall) }) { Text(if (row.dxCall in watchlist) "Unwatch" else "Watch") }
                TextButton({ selectedRbn = null }) { Text("Close") } } })
    }
    selectedSignal?.let { (source, row) ->
        AlertDialog(onDismissRequest = { selectedSignal = null }, title = { Text(row.callsign) },
            text = { Text("$source · ${row.direction.name.replace('_', ' ')} · ${row.band} · ${row.snr?.let { "$it dB" } ?: "SNR unavailable"}\n" +
                "Call ${statuses[signalReportReference(row)]?.callStatus ?: "—"} · DXCC ${statuses[signalReportReference(row)]?.dxccStatus ?: "—"} · ${if (row.callsign in watchlist) "WATCHLISTED" else "not watched"}") },
            confirmButton = { Button({ requestReceiveTune(row.frequencyHz, row.mode, "$source observation",
                "Review receive-only frequency change"); selectedSignal = null }) { Text("Review receive") } },
            dismissButton = { Row { TextButton({ openCallHistory(row.callsign); selectedSignal = null }) { Text("Logbook history") }
                TextButton({ toggleWatch(row.callsign) }) { Text(if (row.callsign in watchlist) "Unwatch" else "Watch") }
                TextButton({ selectedSignal = null }) { Text("Close") } } })
    }
    selectedCluster?.let { row ->
        DxSpotDialog(row, cty, statuses[row.id], { selectedCluster = null },
            { requestReceiveTune(row.frequencyHz, row.mode, "Cluster observation", "Review receive-only frequency change"); selectedCluster = null },
            { openCallHistory(row.callsign); selectedCluster = null }, { toggleWatch(row.callsign) })
    }
    selectedIbp?.let { row ->
        val station = maidenheadCenter(stationGrid)
        val distance = station?.let { distanceKm(it, row.beacon.point).roundToInt() }
        val bearing = station?.let { initialBearingDegrees(it, row.beacon.point) }
        val observed = ibpObserved.filter { it.beacon.callsign == row.beacon.callsign }
        AlertDialog(onDismissRequest = { selectedIbp = null }, title = { Text("IBP · ${row.beacon.callsign}") },
            text = { Text("${row.band} · ${row.beacon.grid} · ${row.beacon.locationLabel}\n${distance?.let { "$it km · %03d°".format(bearing) } ?: "Bearing/distance unavailable"}\nScheduled ${utcSeconds(row.slotStartEpoch)}–${utcSeconds(row.slotEndEpoch)} UTC · ${((row.slotEndEpoch - Instant.now().epochSecond).coerceAtLeast(0))}s remaining.\n${observed.take(3).joinToString(" · ") { "${it.source} ${it.band} ${ageLabel(it.observedEpoch)}" }.ifBlank { "No recent cluster/RBN reception evidence" }}\nSchedule reference remains separate from observed reception.\n${HAMCLOCK_IBP_MANIFEST_VERSION} · ${HAMCLOCK_IBP_MANIFEST_HASH.take(12)}…") },
            confirmButton = { Button({ requestReceiveTune(row.frequencyHz, "CW", "IBP scheduled beacon",
                "Review receive-only frequency change"); selectedIbp = null }) { Text("Review receive") } },
            dismissButton = { Row { TextButton({ openCallHistory(row.beacon.callsign); selectedIbp = null }) { Text("Logbook history") }
                TextButton({ toggleWatch(row.beacon.callsign) }) { Text(if (row.beacon.callsign in watchlist) "Unwatch" else "Watch") }
                TextButton({ inAppBrowser?.open(HAMCLOCK_IBP_MANIFEST_SOURCE) }) { Text("NCDXF source") }
                TextButton({ selectedIbp = null }) { Text("Close") } } })
    }
    selectedIbpSite?.takeIf { site -> selectedIbp?.beacon?.callsign != site.callsign }?.let { site ->
        val next = nextHamClockIbpTransmission(site.callsign)
        AlertDialog(onDismissRequest = { selectedIbpSite = null }, title = { Text("IBP · ${site.callsign}") },
            text = { Text("${site.grid} · ${site.locationLabel}\n${next?.let { "Next ${it.band} ${formatMHz(it.frequencyHz)} MHz at ${utcSeconds(it.slotStartEpoch)} UTC" } ?: "Next schedule unavailable"}\nNCDXF/IARU schedule site reference; no reception is claimed.\nReviewed $HAMCLOCK_IBP_MANIFEST_REVIEW_DATE") },
            confirmButton = { TextButton({ inAppBrowser?.open(HAMCLOCK_IBP_MANIFEST_SOURCE) }) { Text("NCDXF source") } },
            dismissButton = { Row { TextButton({ openCallHistory(site.callsign); selectedIbpSite = null }) { Text("Logbook history") }
                TextButton({ toggleWatch(site.callsign) }) { Text(if (site.callsign in watchlist) "Unwatch" else "Watch") }
                TextButton({ selectedIbpSite = null }) { Text("Close") } } })
    }
    selectedIbpObserved?.let { row ->
        AlertDialog(onDismissRequest = { selectedIbpObserved = null }, title = { Text("Observed IBP · ${row.beacon.callsign}") },
            text = { Text("${row.source} observation · ${row.band} ${row.mode} · heard by ${row.receiver.ifBlank { "unknown" }}\n" +
                "${row.snr?.let { "$it dB" } ?: "SNR unavailable"} · ${row.beacon.grid} ${row.beacon.locationLabel}\nThis is observed evidence, not the schedule projection.") },
            confirmButton = { Button({ requestReceiveTune(row.frequencyHz, row.mode, "Observed IBP evidence",
                "Review receive-only frequency change"); selectedIbpObserved = null }, enabled = row.frequencyHz > 0) { Text("Review receive") } },
            dismissButton = { Row { TextButton({ openCallHistory(row.beacon.callsign); selectedIbpObserved = null }) { Text("Logbook history") }
                TextButton({ toggleWatch(row.beacon.callsign); selectedIbpObserved = null }) {
                    Text(if (row.beacon.callsign in watchlist) "Unwatch" else "Watch") } } })
    }
}

@Composable private fun DxCockpit(controller: NeuralDxController, features: FeatureController, policySpots: List<AndroidDXSpot>, database: QsoDatabase,
    wavelog: WavelogController, callbook: CallbookController, cty: CtyController, app: AppController, stationGrid: String,
    tune: (String) -> Unit, requestReceiveTune: (Long, String?, String, String) -> Unit,
    intelligenceNeeds: Map<String, List<String>>, previousQsos: (AndroidDXSpot) -> Unit,
    openOutlook: () -> Unit,
    modifier: Modifier) {
    var mode by remember { mutableStateOf("COCKPIT") }; var band by remember { mutableStateOf("ALL") }
    var radioMode by remember { mutableStateOf("ALL") }; var watchOnly by remember { mutableStateOf(false) }
    var newOnly by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf<AndroidDXSpot?>(null) }
    var selectedRequiresReview by remember { mutableStateOf(false) }
    var watchSearch by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }; var statuses by remember { mutableStateOf(emptyMap<String, SpotLogStatus>()) }
    val distances = remember(stationGrid) { mutableStateMapOf<String, Int>() }
    val distanceLookups = remember(stationGrid) { mutableStateMapOf<String, Boolean>() }
    val stationId = wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG }
    LaunchedEffect(features.requestedSpotId, policySpots) {
        features.requestedSpotId?.let { id ->
            policySpots.firstOrNull { it.id == id }?.let {
                selected = it
                selectedRequiresReview = features.requestedSpotRequiresReceiveReview
                features.consumeRequestedSpot()
            }
        }
    }
    LaunchedEffect(policySpots, stationId, wavelog.logMode, wavelog.configured, cty.dataRevision) {
        if (policySpots.isEmpty() ||
            (wavelog.logMode == LogMode.WAVELOG && (!wavelog.configured || stationId.isNullOrBlank()))) {
            statuses = emptyMap()
            return@LaunchedEffect
        }
        val identities = policySpots.map { it.toSpotLogIdentity(cty.lookup(it.callsign)) }
        var observedRevision = Long.MIN_VALUE
        while (true) {
            val revision = database.changeToken()
            if (revision != observedRevision) {
                statuses = withContext(Dispatchers.IO) { database.spotStatuses(identities, stationId) }
                observedRevision = revision
            }
            delay(2_000)
        }
    }
    val rows = policySpots.filter { spot ->
        (band == "ALL" || spot.band == band) && (radioMode == "ALL" || spot.mode == radioMode) &&
            (!watchOnly || spot.watchlisted) && (!newOnly || statuses[spot.id]?.dxccStatus in setOf("ATNO", "W/NB", "C/NB") || intelligenceNeeds[spot.id].orEmpty().isNotEmpty())
    }
    LaunchedEffect(stationGrid, rows.map { it.callsign }, callbook.configured) {
        if (maidenheadCenter(stationGrid) == null) return@LaunchedEffect
        rows.distinctBy { it.callsign.uppercase(Locale.US) }.take(24).forEach { spot ->
            val call = spot.callsign.uppercase(Locale.US)
            val direct = dxDistanceKm(stationGrid, "", spot.latitude.toString(), spot.longitude.toString())
            if (direct != null) {
                distances[call] = direct
            } else if (callbook.configured && distanceLookups.put(call, true) == null) {
                callbook.lookup(call) { record ->
                    record?.let {
                        dxDistanceKm(stationGrid, it.grid, it.latitude, it.longitude)?.let { km -> distances[call] = km }
                    }
                }.join()
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("CLASSIC", "SMART", "COCKPIT").forEach { FilterChip(mode == it, { mode = it }, { Text(it) }) }
            DxSelect("BAND", band, DxBands) { band = it }
            DxSelect("MODE", radioMode, listOf("ALL") + policySpots.map { it.mode }.filter(String::isNotBlank).distinct()) { radioMode = it }
            FilterChip(watchOnly, { watchOnly = !watchOnly }, { Text("★ WATCHLIST") })
            FilterChip(newOnly, { newOnly = !newOnly }, { Text("NEW DXCC") })
            OutlinedButton({ manual = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Podcasts, null); Spacer(Modifier.width(5.dp)); Text("SEND SPOT") }
            Text("${rows.size} LIVE", color = DxCyan, fontWeight = FontWeight.Black)
        }
        if (mode == "COCKPIT") Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DxSpotFeed(rows, statuses, distances, cty, app, intelligenceNeeds, { selected = it; selectedRequiresReview = false }, previousQsos, Modifier.weight(1.9f).fillMaxHeight())
            LazyColumn(Modifier.weight(.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { DxSection("ACTIVE BANDS · 24H") { controller.bandActivity.entries.take(12).forEach { DxBar(it.key, it.value, controller.bandActivity.values.maxOrNull() ?: 1) } } }
                item { DxSection("NEXT 60 MIN", Modifier.clickable(onClick = openOutlook)) {
                    val outlook = controller.outlook.snapshot
                    outlook.topBands.take(3).forEach { row ->
                        DxLine(row.band, "${row.label.name.replace('_', ' ')} · ${row.confidence} · ${row.sourceCount} sources", DxCyan)
                    }
                    if (outlook.topBands.isEmpty()) DxEmpty("Insufficient evidence · calibration collecting")
                    Text("OPEN INSIGHT & OUTLOOK", color = DxAmber, fontWeight = FontWeight.Bold)
                } }
                item { DxSection("CURRENT OPPORTUNITIES") {
                    Text("Live ranking only · P = priority · E = evidence support · not a probability or forecast",color=DxMuted,fontSize=12.sp)
                    controller.currentOpportunities.take(6).forEach { opportunity ->
                        DxLine("${opportunity.callsign} · ${opportunity.band} ${opportunity.mode}", "P ${opportunity.priority} · E ${opportunity.evidenceScore}", if (opportunity.priority >= 70) DxGreen else DxYellow)
                    }
                    if (controller.currentOpportunities.isEmpty()) DxEmpty("No current live opportunities")
                } }
                item { DxSection("MY SIGNAL · PSK REPORTER") {
                    DxProviderStatusRow(controller.mySignal.status)
                    if(controller.mySignal.status.detail.isNotBlank()&&controller.mySignal.status.effective().state in setOf(NeuralProviderState.STALE,NeuralProviderState.UNAVAILABLE))Text(controller.mySignal.status.detail,color=DxYellow,fontSize=11.sp)
                    Text("${controller.mySignal.callsign.ifBlank{"Station callsign"}} · ${controller.mySignal.reports.size} receivers · 5-minute source cache",color=DxMuted,fontSize=12.sp)
                    controller.mySignal.reports.take(7).forEach { report -> DxLine("${report.callsign} · ${report.band} ${report.mode}", "${report.snr?.let{"$it dB"}?:"—"} · ${report.distanceKm?.let{"$it km"}?:report.locator}", DxCyan) }
                    if (controller.mySignal.reports.isEmpty()) DxEmpty(controller.mySignal.error.ifBlank { "No recent PSK Reporter receptions" })
                } }
                item { DxSection("WATCHLIST TRACKING") {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(watchSearch,{watchSearch=it.uppercase()},label={Text("Search watchlist")},singleLine=true,modifier=Modifier.weight(1f));TextButton({features.setWatchlist("")},modifier=Modifier.heightIn(min=48.dp)){Text("PURGE")}}
                    features.watchSpots.filter{watchSearch.isBlank()||it.callsign.contains(watchSearch,true)}.take(8).forEach { DxLine(it.callsign, "${it.band} ${it.mode} · ${ageLabel(it.receivedEpoch)}", DxYellow) }
                    if (features.watchSpots.isEmpty()) DxEmpty("No watchlist activity yet")
                } }
                item { DxSection("LOG OPPORTUNITIES · QSL / LoTW") {
                    val opportunities=rows.filter{statuses[it.id]?.dxccStatus in setOf("ATNO","W/NB","C/NB","W")}.distinctBy{cty.lookup(it.callsign)?.dxcc.orEmpty().ifBlank{it.country}}.take(8)
                    opportunities.forEach{spot->val state=statuses[spot.id];DxLine("${spot.callsign} · ${spot.band}","${state?.dxccStatus?:"—"} · ${cty.lookup(spot.callsign)?.country.orEmpty().ifBlank{spot.country}}",if(state?.dxccStatus=="ATNO")DxRed else DxYellow)}
                    if(opportunities.isEmpty())DxEmpty("No unconfirmed or new DXCC opportunity in the live feed")
                } }
                item { DxHeatmap(controller.heatmap6m) }
            }
        } else if (mode == "SMART") DxSmartFeed(rows, statuses, distances, cty, app, intelligenceNeeds, { selected = it; selectedRequiresReview = false }, previousQsos, Modifier.weight(1f))
        else DxSpotTable(rows, statuses, distances, cty, app, intelligenceNeeds, { selected = it; selectedRequiresReview = false }, previousQsos, Modifier.weight(1f))
    }
    selected?.let { spot -> DxSpotDialog(spot, cty, statuses[spot.id], { selected = null }, {
        if (selectedRequiresReview) requestReceiveTune(spot.frequencyHz, spot.mode, "Home DX spot ${spot.callsign}",
            "Review receive-only frequency change") else tune("FA%011d;".format(spot.frequencyHz))
        selected = null
    }, { previousQsos(spot); selected = null }, {
        val calls = features.watchlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toMutableSet()
        if (spot.watchlisted) calls.remove(spot.callsign.uppercase(Locale.US)) else calls.add(spot.callsign.uppercase(Locale.US))
        features.setWatchlist(calls.joinToString("\n")); selected = null
    }) }
    if (manual) ManualSpotDialog(features) { manual = false }
}

@Composable private fun DxSpotFeed(rows: List<AndroidDXSpot>, statuses: Map<String, SpotLogStatus>, distances: Map<String, Int>, cty: CtyController,
    app: AppController, intelligenceNeeds: Map<String, List<String>> = emptyMap(), selected: (AndroidDXSpot) -> Unit,
    previous: (AndroidDXSpot) -> Unit, modifier: Modifier) {
    DxSection("DX FEED · WORKED STATUS · DISTANCE · SCORE", modifier) {
        DxSpotHeader()
        LazyColumn(Modifier.fillMaxSize()) { items(rows, key = { it.id }) { spot ->
            DxSpotRow(spot, statuses[spot.id], distances[spot.callsign.uppercase(Locale.US)], cty, app, selected, previous, intelligenceNeeds[spot.id].orEmpty())
        } }
    }
}

@Composable private fun DxSmartFeed(rows: List<AndroidDXSpot>, statuses: Map<String, SpotLogStatus>, distances: Map<String, Int>, cty: CtyController,
    app: AppController, intelligenceNeeds: Map<String, List<String>>, selected: (AndroidDXSpot) -> Unit,
    previous: (AndroidDXSpot) -> Unit, modifier: Modifier) {
    DxSection("SMART PRIORITY · HIGHEST OPPORTUNITY FIRST", modifier) {
        DxSpotHeader()
        LazyColumn(Modifier.fillMaxSize()) { items(rows.sortedByDescending { intelligenceNeeds[it.id].orEmpty().size * 100 + it.score }, key = { it.id }) { spot ->
            DxSpotRow(spot, statuses[spot.id], distances[spot.callsign.uppercase(Locale.US)], cty, app, selected, previous,
                intelligenceNeeds[spot.id].orEmpty(), smart = true)
        } }
    }
}

@Composable private fun DxSpotTable(rows: List<AndroidDXSpot>, statuses: Map<String, SpotLogStatus>, distances: Map<String, Int>, cty: CtyController,
    app: AppController, intelligenceNeeds: Map<String, List<String>>, selected: (AndroidDXSpot) -> Unit,
    previous: (AndroidDXSpot) -> Unit, modifier: Modifier) {
    DxSection("CLASSIC CLUSTER TABLE · ${rows.size} LIVE", modifier) {
        DxSpotHeader()
        LazyColumn(Modifier.fillMaxSize()) { items(rows, key = { it.id }) { spot ->
            DxSpotRow(spot, statuses[spot.id], distances[spot.callsign.uppercase(Locale.US)], cty, app, selected, previous, intelligenceNeeds[spot.id].orEmpty())
        } }
    }
}

@Composable private fun DxSpotHeader() = Row(Modifier.fillMaxWidth().height(52.dp).background(DxRaised).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
    DxFlexCell("UTC",.72f,DxInk,true);DxFlexCell("CALL",.88f,DxInk,true);DxFlexCell("BAND",.5f,DxInk,true);DxFlexCell("MODE",.55f,DxInk,true)
    DxFlexCell("FREQ MHz",.76f,DxInk,true);DxFlexCell("COUNTRY / DXCC",1.42f,DxInk,true);DxFlexCell("CQ",.36f,DxInk,true)
    DxFlexCell("DX DE",.82f,DxInk,true);DxFlexCell("CS",.34f,DxInk,true);DxFlexCell("DS",.46f,DxInk,true);DxFlexCell("DIST",.66f,DxInk,true)
    DxFlexCell("SCORE",.48f,DxInk,true);DxFlexCell("COMMENT / REASON",1.55f,DxInk,true)
}

@Composable private fun DxSpotRow(spot:AndroidDXSpot,status:SpotLogStatus?,calculatedDistanceKm:Int?,cty:CtyController,app:AppController,selected:(AndroidDXSpot)->Unit,
    previous:(AndroidDXSpot)->Unit,intelligenceNeeds:List<String> = emptyList(),smart:Boolean=false){
    val entity=cty.lookup(spot.callsign);val country=entity?.country.orEmpty().ifBlank{spot.country}.ifBlank{"Unknown"}
    Row(Modifier.fillMaxWidth().height(64.dp).clickable(role=Role.Button){selected(spot)}
        .background(if(smart&&spot.score>=75)DxGreen.copy(alpha=.08f) else if(spot.receivedEpoch%2L==0L)DxPanel else DxBg).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
        DxFlexCell(utcSeconds(spot.receivedEpoch),.72f,DxInk);Box(Modifier.weight(.88f).fillMaxHeight().clickable{previous(spot)},contentAlignment=Alignment.CenterStart){Text(spot.callsign,color=if(spot.watchlisted)DxYellow else OperationalCallsign,fontWeight=FontWeight.Black,fontSize=19.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
        DxFlexCell(spot.band,.5f,DxInk);DxFlexCell(spot.mode,.55f,DxInk);DxFlexCell(formatMHz(spot.frequencyHz),.76f,OperationalFrequency)
        DxFlexCell(country,1.42f,OperationalCountry);DxFlexCell(entity?.cqZone.orEmpty().ifBlank{spot.cqZone.takeIf{it>0}?.toString().orEmpty()},.36f,DxInk)
        DxFlexCell(spot.spotter,.82f,DxMuted);DxFlexCell(status?.callStatus.orEmpty(),.34f,Color(app.spotStatusColour(SPOT_STATUS_CS,status?.callStatus)),true);DxFlexCell(status?.dxccStatus.orEmpty(),.46f,Color(app.spotStatusColour(SPOT_STATUS_DS,status?.dxccStatus)),true)
        DxFlexCell((spot.distanceKm.takeIf{it>0} ?: calculatedDistanceKm)?.let{"$it km"}.orEmpty(),.66f,DxMuted);DxFlexCell(spot.score.toString(),.48f,scoreColor(spot.score),true)
        DxFlexCell(intelligenceNeeds.joinToString(" · ").ifBlank { spot.reason.ifBlank{spot.comment} },1.55f,if(intelligenceNeeds.isEmpty())DxMuted else DxYellow,true)
    };HorizontalDivider(color=Color(0xFF303940))
}

@Composable private fun RowScope.DxFlexCell(text:String,weight:Float,color:Color,bold:Boolean=false){Text(text,color=color,fontWeight=if(bold)FontWeight.Black else FontWeight.Medium,fontFamily=FontFamily.Monospace,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(weight).padding(horizontal=3.dp))}

@Composable private fun DxMap(controller:NeuralDxController,rows: List<AndroidDXSpot>, features: FeatureController, cty: CtyController, app: AppController, stationGrid: String,
    database: QsoDatabase, wavelog: WavelogController, requestReceiveTune: (Long, String?, String, String) -> Unit,
    previous: (AndroidDXSpot) -> Unit, modifier: Modifier) {
    var minutes by remember { mutableIntStateOf(60) }; var limit by remember { mutableIntStateOf(250) }
    var band by remember { mutableStateOf("ALL") }; var mode by remember { mutableStateOf("ALL") }; var hearsMe by remember { mutableStateOf(false) }
    var pskView by remember { mutableStateOf("BOTH") }
    var showPaths by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<AndroidDXSpot?>(null) }
    var selectedReport by remember { mutableStateOf<SignalReport?>(null) }
    var reportStatuses by remember { mutableStateOf(emptyMap<String, SpotLogStatus>()) }
    val stationId = wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG }
    val now=Instant.now().epochSecond
    val pskRows = controller.mySignal.reports.filter { report -> when (pskView) {
        "BEING HEARD" -> report.direction == SignalDirection.BEING_HEARD
        "HEARING" -> report.direction == SignalDirection.HEARING
        "MUTUAL" -> report.mutual
        else -> true
    } }
    LaunchedEffect(controller.mySignal.reports, rows, stationId, cty.dataRevision) {
        val identities = controller.mySignal.reports.map { report ->
            val entity = cty.lookup(report.callsign)
            SpotLogIdentity(signalReportReference(report), report.callsign, entity?.dxcc.orEmpty(),
                entity?.country.orEmpty(), report.band, report.mode)
        } + rows.map { spot -> spot.toSpotLogIdentity(cty.lookup(spot.callsign)) }
        reportStatuses = withContext(Dispatchers.IO) { database.spotStatuses(identities, stationId) }
    }
    LaunchedEffect(controller.requestedSignalReportId, controller.mySignal.reports) {
        controller.requestedSignalReportId?.let { id ->
            hearsMe = true
            pskView = "BOTH"
            val consumed = consumeSignalRequest(id, controller.mySignal.reports)
            selectedReport = consumed.report
            controller.consumeRequestedSignalReport(consumed.message)
        }
    }
    val filtered=rows.filter{now-it.receivedEpoch<=minutes*60&&(band=="ALL"||it.band==band)&&(mode=="ALL"||it.mode==mode)}.take(limit)
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
            DxSelect("WINDOW","${minutes}m",listOf("15m","30m","60m","180m","360m")){minutes=it.removeSuffix("m").toInt()}
            DxSelect("LIMIT",limit.toString(),listOf("50","100","250","500","1000")){limit=it.toInt()}
            DxSelect("BAND",band,DxBands){band=it};DxSelect("MODE",mode,listOf("ALL")+rows.map{it.mode}.distinct()){mode=it}
            FilterChip(hearsMe,{hearsMe=!hearsMe},{Text("PSK SIGNALS")})
            if (hearsMe) listOf("BEING HEARD", "HEARING", "BOTH", "MUTUAL").forEach { value ->
                FilterChip(pskView == value, { pskView = value }, { Text(value) })
            }
            if(!hearsMe)FilterChip(showPaths,{showPaths=!showPaths},{Text("SPOT PATHS")})
            Text("${filtered.size} OBSERVATIONS",color=DxCyan,fontWeight=FontWeight.Black)
        }
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(hearsMe)DxReceiverMap(pskRows,stationGrid,Modifier.weight(2.2f).fillMaxHeight())else DxWorldCanvas(filtered,stationGrid,false,cty,showPaths,Modifier.weight(2.2f).fillMaxHeight())
            DxSection(if(hearsMe)"RECEIVERS / SPOTTERS" else "MAP OBSERVATIONS",Modifier.weight(1f).fillMaxHeight()){
                Row(Modifier.fillMaxWidth().height(34.dp).background(DxRaised).padding(horizontal=5.dp),verticalAlignment=Alignment.CenterVertically){DxFlexCell(if(hearsMe)"RX CALL" else "DX",.8f,DxInk,true);DxFlexCell("BAND / MODE",.9f,DxInk,true);DxFlexCell(if(hearsMe)"SNR / KM" else "MHz / COUNTRY",1.2f,DxInk,true)}
                if(hearsMe)LazyColumn(Modifier.fillMaxSize()){items(pskRows,key={signalReportReference(it)}){r->val exact=selectedReport?.let(::signalReportReference)==signalReportReference(r);Row(Modifier.fillMaxWidth().height(44.dp).clickable{selectedReport=r}.background(if(exact)DxGreen.copy(alpha=.16f) else Color.Transparent).padding(horizontal=5.dp),verticalAlignment=Alignment.CenterVertically){DxFlexCell("${if(exact)"SELECTED · " else ""}${r.callsign} ${r.locator}",.8f,DxGreen,true);DxFlexCell("${r.band} ${r.mode}${if(r.mutual)" · MUTUAL" else ""}",.9f,DxInk);DxFlexCell("${r.snr?.let{"$it dB"}?:"—"} · ${r.distanceKm?.let{"$it km"}?:"—"}",1.2f,DxAmber)};HorizontalDivider(color=Color(0xFF303940))}}
                else LazyColumn(Modifier.fillMaxSize()){items(filtered,key={it.id}){spot->val status=reportStatuses[spot.id];Row(Modifier.fillMaxWidth().height(44.dp).clickable{selected=spot}.padding(horizontal=5.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(.8f).fillMaxHeight().clickable{previous(spot)},contentAlignment=Alignment.CenterStart){Text(spot.callsign,color=DxCyan,fontWeight=FontWeight.Black,maxLines=1)};DxFlexCell("${spot.band} ${spot.mode}",.9f,DxInk);DxFlexCell("${formatMHz(spot.frequencyHz)} · ${cty.lookup(spot.callsign)?.country.orEmpty().ifBlank{spot.country}}",1.2f,DxAmber);DxFlexCell("CS ${status?.callStatus?:"—"}",.48f,Color(app.spotStatusColour(SPOT_STATUS_CS,status?.callStatus)),true);DxFlexCell("DS ${status?.dxccStatus?:"—"}",.52f,Color(app.spotStatusColour(SPOT_STATUS_DS,status?.dxccStatus)),true)};HorizontalDivider(color=Color(0xFF303940))}}
            }
        }
    }
    selected?.let{spot->DxSpotDialog(spot,cty,reportStatuses[spot.id],{selected=null},{requestReceiveTune(spot.frequencyHz, spot.mode,
        "DX map · ${spot.callsign}", "Review receive-only frequency change");selected=null},
        {previous(spot);selected=null},{val calls=features.watchlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toMutableSet();if(spot.watchlisted)calls.remove(spot.callsign.uppercase(Locale.US))else calls.add(spot.callsign.uppercase(Locale.US));features.setWatchlist(calls.joinToString("\n"));selected=null})}
    selectedReport?.let { report ->
        val reference = signalReportReference(report)
        val status = reportStatuses[reference]
        val watched = features.watchlistText.lineSequence().any { it.equals(report.callsign, true) }
        AlertDialog(onDismissRequest = { selectedReport = null }, title = { Text("${report.callsign} · ${report.direction.name.replace('_',' ')}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${report.senderCallsign} ${report.senderLocator} → ${report.receiverCallsign} ${report.receiverLocator}")
                Text("${formatMHz(report.frequencyHz)} MHz · ${report.band} ${report.mode} · ${report.snr?.let { "$it dB" } ?: "SNR unavailable"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CS ${status?.callStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_CS, status?.callStatus)), fontWeight = FontWeight.Bold)
                    Text("DS ${status?.dxccStatus ?: "—"}", color = Color(app.spotStatusColour(SPOT_STATUS_DS, status?.dxccStatus)), fontWeight = FontWeight.Bold)
                    if (report.mutual) Text("MUTUAL", color = DxMuted)
                }
                if (controller.signalSelectionMessage.isNotBlank()) Text(controller.signalSelectionMessage, color = DxYellow)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TextButton({ previous(signalReportSpot(report, cty)); selectedReport = null }) { Text("LOG HISTORY") }
                    TextButton({
                        val calls = features.watchlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toMutableSet()
                        if (watched) calls.removeAll { it.equals(report.callsign, true) } else calls.add(report.callsign.uppercase(Locale.US))
                        features.setWatchlist(calls.joinToString("\n")); selectedReport = null
                    }) { Text(if (watched) "UNWATCH" else "WATCH") }
                }
            } },
            confirmButton = { TextButton({ requestReceiveTune(report.frequencyHz, report.mode,
                "PSK Reporter · ${report.callsign}", "Review receive-only frequency from exact ${report.direction.name.lowercase().replace('_',' ')} report"); selectedReport = null }) { Text("REVIEW RECEIVE") } },
            dismissButton = { TextButton({ selectedReport = null }) { Text("Close") } })
    }
}

private fun signalReportSpot(report: SignalReport, cty: CtyController): AndroidDXSpot {
    val entity = cty.lookup(report.callsign)
    return AndroidDXSpot(signalReportReference(report), report.callsign, report.receiverCallsign, report.frequencyHz,
        report.epoch, report.band, report.mode, entity?.country.orEmpty(), entity?.continent.orEmpty(),
        entity?.cqZone?.toIntOrNull() ?: 0, entity?.ituZone?.toIntOrNull() ?: 0,
        report.latitude ?: 0.0, report.longitude ?: 0.0, "PSK Reporter ${report.direction.name}",
        0, 0, 1, false, false, false, false, false, false, false,
        report.distanceKm ?: 0, 0, "PSK", "Exact PSK report; no automatic CAT")
}

@Composable private fun DxInsightPage(controller: NeuralDxController, policySpots: List<AndroidDXSpot>, modifier: Modifier){
    val outlook = controller.outlook.snapshot
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DxSection("CURRENT EVIDENCE") {
            DxLine("Observed opportunities", controller.currentOpportunities.size.toString(), DxCyan)
            DxLine("Policy observations", policySpots.size.toString(), DxGreen)
            outlook.sources.forEach { (source, state) ->
                val age = outlook.sourceAgesSeconds[source]?.let { if (it >= 0) " · ${it / 60}m old" else " · age unavailable" }.orEmpty()
                DxLine(source.replace('_', ' '), state.name + age, if (state == OutlookSourceState.CURRENT) DxGreen else DxYellow)
            }
        } }
        OutlookWindow.entries.forEach { window ->
            item { DxSection("NEXT ${window.minutes} MINUTES") {
                val rows = outlook.forecasts.filter { it.window == window }.sortedByDescending(OutlookForecast::supportScore).take(3)
                if (rows.isEmpty()) DxEmpty(if (window == outlook.selectedWindow) "Insufficient evidence" else "Select this window in World for detail")
                rows.take(5).forEach { row -> DxLine(row.band,
                    "${row.label.name.replace('_', ' ')} · ${row.confidence} · support ${row.supportScore} · ${row.sourceCount} sources", DxCyan) }
            } }
        }
        item { DxSection("TOP REGIONS") {
            outlook.world.filter { it.forecast.label != OutlookLabel.INSUFFICIENT_EVIDENCE }
                .sortedByDescending { it.forecast.supportScore }.take(6).forEach { cell ->
                    DxLine("${hemisphere(cell.latitude,"N","S")} ${hemisphere(cell.longitude,"E","W")}",
                        "${cell.forecast.label.name.replace('_', ' ')} · ${cell.forecast.confidence} · n=${cell.forecast.baselineSamples}", DxCyan)
                }
            if (outlook.world.none { it.forecast.label != OutlookLabel.INSUFFICIENT_EVIDENCE }) DxEmpty("No supported region cells yet")
        } }
        item { DxSection("CANDIDATE OPPORTUNITIES") {
            outlook.candidates.forEach { row -> DxLine(row.callsign, "${row.source.label}${row.band.takeIf(String::isNotBlank)?.let { " · $it ${row.mode}" }.orEmpty()}", DxYellow) }
            if (outlook.candidates.isEmpty()) DxEmpty("No current, scheduled, watchlist, needed, or recent-pattern candidates")
            Text("Scheduled candidates are calendar evidence, not predicted transmissions.", color = DxMuted, fontSize = 12.sp)
        } }
        item { DxSection("WHY") {
            outlook.topBands.firstOrNull()?.reasons?.forEachIndexed { index, reason -> DxLine("${index + 1}", reason, DxCyan) }
            Text("QSO history is personal context only and never counts as live evidence.", color = DxMuted, fontSize = 12.sp)
        } }
        item { DxSection("CALIBRATION") {
            DxLine(outlook.calibration.state.name, outlook.calibration.label, DxAmber)
            Text("A percentage appears only after 40 verified predictions in the window/band family and 15 in the score bin.", color = DxMuted, fontSize = 12.sp)
        } }
        item { DxSection("OPTIONAL AI BRIEFING · ${controller.insight.source}") {
            Text(controller.insight.title, color = DxCyan, fontWeight = FontWeight.Black)
            Text(controller.insight.report, color = DxInk, fontSize = 15.sp, lineHeight = 21.sp)
            Text("Optional AI summarizes structured results only; it does not generate or overwrite the empirical score.", color = DxMuted, fontSize = 12.sp)
        } }
    }
}

@Composable private fun DxWorldPage(controller: NeuralDxController, features: FeatureController, modifier: Modifier){
    var anomaliesOnly by remember{mutableStateOf(false)};var greyline by remember{mutableStateOf(true)};var mode by remember{mutableStateOf("CURRENT")}
    val rows=controller.world.filter{!anomaliesOnly||(it.anomalyRatio?:0.0)>=1.8}
    val outlook = controller.outlook.snapshot
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
            DxSelect("BAND",controller.worldBand,DxBands){controller.setWorldFilter(controller.worldWindowMinutes,it)}
            DxSelect("MODE",mode,listOf("CURRENT","OUTLOOK 30","OUTLOOK 60","OUTLOOK 120")){ selected ->
                mode=selected
                if(selected!="CURRENT") controller.outlook.select(OutlookWindow.entries.first{it.minutes==selected.substringAfter(' ').toInt()},controller.worldBand.takeUnless{it=="ALL"}?:"20m")
            }
            if(mode=="CURRENT") DxSelect("WINDOW","${controller.worldWindowMinutes}m",listOf("15m","30m","60m","180m","360m")){controller.setWorldFilter(it.removeSuffix("m").toInt(),controller.worldBand)}
            FilterChip(anomaliesOnly,{anomaliesOnly=!anomaliesOnly},{Text("ANOMALIES ONLY")});FilterChip(greyline,{greyline=!greyline},{Text("GREY LINE")})
            Text("${if(mode=="CURRENT")rows.size else outlook.world.size} CELLS",color=DxCyan,fontWeight=FontWeight.Black)
        }
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(mode=="CURRENT") DxWorldAnomalyCanvas(rows,greyline,Modifier.weight(2.2f).fillMaxHeight())
            else DxWorldOutlookCanvas(outlook.world,greyline,Modifier.weight(2.2f).fillMaxHeight())
            DxSection(if(mode=="CURRENT")"ACTIVITY / ANOMALIES" else "EMPIRICAL OUTLOOK / LOW DATA",Modifier.weight(1f).fillMaxHeight()){
                Row(Modifier.fillMaxWidth().height(34.dp).background(DxRaised).padding(horizontal=5.dp),verticalAlignment=Alignment.CenterVertically){DxFlexCell("CELL",.8f,DxInk,true);DxFlexCell("OBS / EXP",.8f,DxInk,true);DxFlexCell("RATIO / STATE",1.2f,DxInk,true);DxFlexCell("EVIDENCE",.8f,DxInk,true)}
                if(mode=="CURRENT") LazyColumn(Modifier.fillMaxSize()){items(rows){cell->Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=5.dp),verticalAlignment=Alignment.CenterVertically){DxFlexCell("${hemisphere(cell.latitude,"N","S")} ${hemisphere(cell.longitude,"E","W")}",.8f,DxCyan,true);DxFlexCell("${cell.observed} / ${cell.expected?.let{"%.1f".format(it)}?:"—"}",.8f,DxInk);DxFlexCell("${cell.anomalyRatio?.let{"×%.1f · ".format(it)}.orEmpty()}${cell.anomalyLabel}",1.2f,if(cell.anomalyLabel=="STRONG ANOMALY")DxRed else DxYellow,true);DxFlexCell("n=${cell.baselineSamples} · ${cell.sourceCount} src · ${cell.confidence}",.8f,DxMuted)};HorizontalDivider(color=Color(0xFF303940))}}
                else LazyColumn(Modifier.fillMaxSize()){items(outlook.world.take(72)){cell->val f=cell.forecast;DxLine("${cell.row},${cell.column}","${f.label.name.replace('_',' ')} · ${f.confidence} · n=${f.baselineSamples}",if(f.label==OutlookLabel.INSUFFICIENT_EVIDENCE)DxMuted else DxCyan)}}
            }
        }
    }
}

@Composable private fun DxBriefingPage(controller: NeuralDxController, features: FeatureController, policySpots: List<AndroidDXSpot>, database: QsoDatabase,
    wavelog: WavelogController, cty: CtyController, requestReceiveTune: (Long, String?, String, String) -> Unit,
    intelligenceNeeds: Map<String, List<String>>, previous: (AndroidDXSpot) -> Unit,
    dxNewsPreference: HamClockDxNewsPreference, updateDxNewsPreference: (HamClockDxNewsPreference) -> Unit,
    modifier: Modifier) {
    val sourceFilter = when (dxNewsPreference.source) {
        HamClockDxNewsSource.ALL -> "ALL"
        HamClockDxNewsSource.DX_WORLD -> "dxworld"
        HamClockDxNewsSource.NG3K -> "ng3k"
    }
    var view by remember { mutableStateOf("CURRENT") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<BriefingItem?>(null) }
    var statuses by remember { mutableStateOf(emptyMap<String, SpotLogStatus>()) }
    val inAppBrowser = LocalInAppBrowserState.current
    val now = Instant.now().epochSecond
    val merged = remember(controller.briefing, now / 60) { mergeDxNews(controller.briefing.flatMap(BriefingSource::items), now) }
    val watched = features.watchlistText.lineSequence().map { it.trim().uppercase(Locale.US) }.filter(String::isNotBlank).toSet()
    val rows = merged.filter { item ->
        (sourceFilter == "ALL" || item.sourceId == sourceFilter) &&
            (query.isBlank() || item.title.contains(query, true) || item.entity.contains(query, true) ||
                item.callsigns.any { it.contains(query, true) }) && when (view) {
            "UPCOMING" -> item.sourceId == "ng3k" && item.publishedEpoch > now
            "SAVED" -> item.callsigns.any { it in watched }
            else -> item.sourceId != "ng3k" || item.publishedEpoch <= now
        }
    }
    val stationId = wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG }
    LaunchedEffect(merged, stationId, cty.dataRevision) {
        val calls = merged.flatMap(BriefingItem::callsigns).distinct().take(80)
        val identities = calls.map { call -> val entity = cty.lookup(call); SpotLogIdentity("news:$call", call,
            entity?.dxcc.orEmpty(), entity?.country.orEmpty(), "", "") }
        statuses = withContext(Dispatchers.IO) { database.spotStatuses(identities, stationId) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf("CURRENT", "UPCOMING", "SAVED").forEach { FilterChip(view == it, { view = it }, { Text(it) }) }
            (listOf("ALL") + controller.briefing.map(BriefingSource::id)).forEach { id ->
                FilterChip(sourceFilter == id, {
                    updateDxNewsPreference(dxNewsPreference.copy(source = when (id) {
                        "dxworld" -> HamClockDxNewsSource.DX_WORLD
                        "ng3k" -> HamClockDxNewsSource.NG3K
                        else -> HamClockDxNewsSource.ALL
                    }))
                }, { Text(if (id == "ALL") id else controller.briefing.firstOrNull { it.id == id }?.name ?: id) })
            }
            Text("${rows.size} / ${merged.size}", color = DxCyan, fontWeight = FontWeight.Black)
        }
        OutlinedTextField(query, { query = it.take(48) }, label = { Text("Callsign, entity or headline") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(controller.briefing.joinToString(" · ") { "${it.name} ${it.state.name} ${it.items.size}${it.error.takeIf(String::isNotBlank)?.let { error -> ": $error" }.orEmpty()}" },
            color = DxMuted, fontSize = 10.sp, maxLines = 2)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows, key = { it.id.ifBlank { "${it.sourceId}:${it.link}" } }) { item ->
                val call = item.callsigns.firstOrNull()
                val status = call?.let { statuses["news:$it"] }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { selected = item }.padding(5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DxBriefImage(item.imageUrl, item.title, Modifier.width(82.dp).height(50.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = DxCyan, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(item.sourceLabel, item.published.take(22), item.callsigns.joinToString(), item.entity,
                            status?.let { "${it.callStatus}/${it.dxccStatus}" }).filterNotNull().filter(String::isNotBlank).joinToString(" · "),
                            color = DxMuted, fontSize = 11.sp, maxLines = 1)
                    }
                    if (call != null && call in watched) Text("★", color = DxYellow)
                }
                HorizontalDivider(color = Color(0xFF303940))
            }
        }
    }
    selected?.let { item ->
        val call = item.callsigns.firstOrNull().orEmpty()
        val status = statuses["news:$call"]
        val live = policySpots.firstOrNull { it.callsign.equals(call, true) }
        val calendar = controller.briefing.firstOrNull { it.id == "ng3k" }?.items?.firstOrNull { it.callsigns.any { c -> c.equals(call, true) } }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title) }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${item.sourceLabel} · ${item.published.ifBlank { ageLabel(item.publishedEpoch) }}", color = DxMuted)
                Text(item.summary.ifBlank { "No source summary" })
                Text("Calls ${item.callsigns.joinToString().ifBlank { "none extracted" }} · Worked ${status?.callStatus ?: "—"} · DXCC ${status?.dxccStatus ?: "—"}")
                calendar?.let { Text("Calendar match · ${it.published} · ${it.summary}", color = DxYellow) }
                live?.let { Text("Live cluster · ${formatMHz(it.frequencyHz)} MHz ${it.mode} · ${intelligenceNeeds[it.id].orEmpty().joinToString().ifBlank { it.reason }}", color = DxGreen) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ if (item.sourceHomeUrl.startsWith("https://")) inAppBrowser?.open(item.sourceHomeUrl) }) { Text("SOURCE") }
                    TextButton({ if (item.link.startsWith("https://")) inAppBrowser?.open(item.link) }) { Text("ARTICLE") }
                    if (call.isNotBlank()) TextButton({
                        val calls = watched.toMutableSet(); if (!calls.add(call)) calls.remove(call)
                        features.setWatchlist(calls.joinToString("\n")); selected = null
                    }) { Text(if (call in watched) "UNWATCH" else "WATCH") }
                    if (call.isNotBlank()) TextButton({ previous(live ?: newsItemSpot(item, cty)); selected = null }) { Text("LOG HISTORY") }
                }
            }
        }, confirmButton = {
            if (live != null) TextButton({ requestReceiveTune(live.frequencyHz, live.mode, "Live cluster match · ${live.callsign}",
                "Review receive-only frequency; news article itself cannot tune"); selected = null }) { Text("REVIEW LIVE SPOT") }
            else TextButton({ selected = null }) { Text("Close") }
        }, dismissButton = { if (live != null) TextButton({ selected = null }) { Text("Close") } })
    }
}

private fun newsItemSpot(item: BriefingItem, cty: CtyController): AndroidDXSpot {
    val call = item.callsigns.firstOrNull().orEmpty(); val entity = cty.lookup(call)
    return AndroidDXSpot("news:${item.id}", call, item.sourceLabel, 0, item.publishedEpoch,
        item.bands.firstOrNull().orEmpty(), item.modes.firstOrNull().orEmpty(), item.entity.ifBlank { entity?.country.orEmpty() },
        entity?.continent.orEmpty(), entity?.cqZone?.toIntOrNull() ?: 0, entity?.ituZone?.toIntOrNull() ?: 0,
        entity?.latitude ?: 0.0, entity?.longitude ?: 0.0, "DX News history context", 0, 0, 1,
        call in emptySet<String>(), false, false, false, false, false, false, 0, 0, "NEWS", "News item; no automatic CAT")
}

@Composable private fun DxSatellitesPage(controller:NeuralDxController,stationGrid:String,modifier:Modifier){
    var selectedNorad by remember{mutableStateOf<Int?>(null)};var detailsNorad by remember{mutableStateOf<Int?>(null)};var search by remember{mutableStateOf("")};var window by remember{mutableIntStateOf(24)}
    val positions=controller.satellites;val catalog=controller.satelliteCatalogue.filter{search.isBlank()||it.name.contains(search,true)||it.norad.toString().contains(search)}.take(80)
    var tab by remember{mutableStateOf("LIVE")}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){listOf("LIVE","PASSES","CATALOG").forEach{FilterChip(tab==it,{tab=it},{Text(it)})};if(tab=="PASSES")listOf(4,12,24).forEach{FilterChip(window==it,{window=it},{Text("${it}h")})};Text("${positions.count{it.visible}} VISIBLE · ${positions.size} TRACKED · ${controller.passes.size} PASSES",color=DxCyan,fontWeight=FontWeight.Black)}
        DxProviderStatusRow(controller.satelliteStatus)
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            DxSatelliteMap(positions,stationGrid,selectedNorad,Modifier.weight(2.25f).fillMaxHeight())
            DxSection(when(tab){"PASSES"->"PASSES ABOVE QTH";"CATALOG"->"FOLLOWED / AMSAT CATALOG";else->"LIVE POSITIONS"},Modifier.weight(1f).fillMaxHeight()){
                when(tab){
                    "PASSES"-> {Row(Modifier.fillMaxWidth().height(34.dp).background(DxRaised)){DxFlexCell("SATELLITE",1f,DxInk,true);DxFlexCell("AOS–LOS",1f,DxInk,true);DxFlexCell("MAX EL",.55f,DxInk,true)};LazyColumn(Modifier.fillMaxSize()){items(controller.passes.filter{it.aosEpoch<=Instant.now().epochSecond+window*3600L}.take(40)){p->Row(Modifier.fillMaxWidth().height(46.dp),verticalAlignment=Alignment.CenterVertically){DxFlexCell(p.name,1f,DxCyan,true);DxFlexCell("${utcTime(p.aosEpoch)}–${utcTime(p.losEpoch)}",1f,DxInk);DxFlexCell("${p.maxElevation.roundToInt()}°",.55f,if(p.maxElevation>=45)DxGreen else if(p.maxElevation>=20)DxYellow else DxAmber,true)};HorizontalDivider(color=Color(0xFF303940))}}}
                    "CATALOG"->{OutlinedTextField(search,{search=it},label={Text("Search ${controller.satelliteCatalogue.size} satellites")},singleLine=true,modifier=Modifier.fillMaxWidth());LazyColumn(Modifier.fillMaxSize()){items(catalog,key={it.norad}){o->val followed=o.norad in controller.followedNorads;Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{controller.setFollowed(o.norad,!followed)},verticalAlignment=Alignment.CenterVertically){Checkbox(followed,null);Text(o.name,color=DxInk,modifier=Modifier.weight(1f),maxLines=1);Text(o.norad.toString(),color=DxMuted)}}}}
                    else->{Row(Modifier.fillMaxWidth().height(34.dp).background(DxRaised)){DxFlexCell("SATELLITE",1f,DxInk,true);DxFlexCell("AZ / EL",.65f,DxInk,true);DxFlexCell("ALT / RANGE",.9f,DxInk,true)};LazyColumn(Modifier.fillMaxSize()){items(positions,key={it.norad}){p->Row(Modifier.fillMaxWidth().height(48.dp).clickable{selectedNorad=p.norad;detailsNorad=p.norad;controller.refreshSatelliteTransmitters(p.norad)},verticalAlignment=Alignment.CenterVertically){DxFlexCell(p.name,1f,if(p.norad==selectedNorad)DxYellow else if(p.visible)DxGreen else DxCyan,true);DxFlexCell("${p.azimuth.roundToInt()}° / ${p.elevation.roundToInt()}°",.65f,DxAmber);DxFlexCell("${p.altitudeKm.roundToInt()} / ${p.rangeKm.roundToInt()} km",.9f,DxInk)};HorizontalDivider(color=Color(0xFF303940))}}}
                }
            }
        }
    }
    detailsNorad?.let{norad->val pos=positions.firstOrNull{it.norad==norad};AlertDialog(onDismissRequest={detailsNorad=null},title={Text(pos?.name?:"Satellite $norad")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){pos?.let{Text("Lat %.2f° · Lon %.2f° · Alt %.0f km".format(it.latitude,it.longitude,it.altitudeKm));Text("Az %.0f° · El %.0f° · Range %.0f km · Footprint %.0f km".format(it.azimuth,it.elevation,it.rangeKm,it.footprintKm))};HorizontalDivider();Text("SatNOGS transmitters",fontWeight=FontWeight.Bold);controller.transmitters[norad]?.forEach{t->Text("${t.description}\n↓ ${t.downlink} · ↑ ${t.uplink} · ${t.mode}",color=DxMuted)}?:Text("Loading frequencies…",color=DxMuted)}},confirmButton={TextButton({detailsNorad=null}){Text("Close")}})}
}

@Composable private fun DxWeatherPage(controller:NeuralDxController,features:FeatureController,stationGrid:String,modifier:Modifier){
    var cond by remember{mutableStateOf("HF")};var zone by remember{mutableStateOf("EUROPE")};val weather=controller.weather
    val hfPct=when{!controller.wspr.available->null;controller.wspr.hf.sumOf{it.spots}>=25->85;controller.wspr.hf.sumOf{it.spots}>=8->65;else->42}
    val vhfSnr=controller.wspr.vhf.mapNotNull{it.averageSnr}.average().takeIf{it.isFinite()};val vhfPct=vhfSnr?.let{((it+25)*4).roundToInt().coerceIn(0,100)}?:weather.tropoIndex?.times(6)
    val global=listOfNotNull(hfPct,vhfPct).average().takeIf{it.isFinite()}?.roundToInt()
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            DxSection("GLOBAL SYNTHESIS",Modifier.weight(1f)){DxGauge(global,"GLOBAL");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceAround){DxMiniValue("HF",hfPct?.let{"$it%"}?:"—");DxMiniValue("VHF",vhfPct?.let{"$it%"}?:"—")};Text("Heuristic visual reference, not a calibrated physical measurement.",color=DxMuted,fontSize=12.sp)}
            DxSection("ELECTRICAL ACTIVITY",Modifier.weight(1f)){val strikes=controller.lightning.strikes;DxProviderStatusRow(controller.lightning.status);DxLightningMap(strikes,stationGrid,Modifier.fillMaxWidth().weight(1f));strikes.firstOrNull()?.let{DxLine("Nearest/latest","${it.distanceKm} km ${it.bearing} · ${ageLabel(it.epoch)}",if(it.distanceKm<50)DxRed else DxYellow)};if(!controller.lightning.connected)Text(controller.lightning.error.ifBlank{"Connecting to the regional feed; zero is never fabricated."},color=DxMuted)}
            DxSection("CURRENT CONDITIONS",Modifier.weight(1f)){DxProviderStatusRow(weather.status);Row{listOf("HF","VHF/UHF").forEach{FilterChip(cond==it,{cond=it},{Text(it)})}};if(cond=="HF"){DxLine("Temperature",weather.temperatureC?.let{"%.1f °C".format(it)}?:"—",if((weather.temperatureC?:0.0)>=30)DxRed else DxInk);DxLine("Pressure",weather.pressureHpa?.let{"%.0f hPa".format(it)}?:"—",if((weather.pressureHpa?:1100.0)<1000)DxRed else DxInk);DxLine("2h trend",weather.pressureTrend,DxYellow);DxLine("Humidity",weather.humidityPercent?.let{"$it%"}?:"—",DxInk);DxLine("Wind",weather.windKmh?.let{"%.0f km/h · %03d°".format(it,weather.windDirection?:0)}?:"—",DxInk);DxLine("Precipitation",weather.precipitationMm?.let{"$it mm"}?:"—",DxInk)}else{DxLine("Tropo / ducting",weather.ductingRisk?:"—",if(weather.ductingRisk=="HIGH")DxGreen else DxYellow);DxLine("Tropo index",weather.tropoIndex?.let{"$it / 10"}?:"—",DxInk);DxLine("850 hPa temp",weather.temperature850C?.let{"%.1f °C".format(it)}?:"—",DxInk);DxLine("300 hPa wind",weather.wind300Kmh?.let{"%.0f km/h".format(it)}?:"—",DxInk);DxLine("CAPE",weather.cape?.let{"%.0f J/kg".format(it)}?:"—",DxInk);val two=controller.wspr.vhf.firstOrNull{it.band=="2m"};DxLine("WSPR 2m",two?.let{"${it.spots} · ${it.averageSnr?.let{n->"%.1f dB".format(n)}?:"—"}"}?:"NO BEACON DATA",if(two!=null)DxGreen else DxMuted)}}
        }
        Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            DxSection("NOISE / QRN",Modifier.weight(1f)){DxProviderStatusRow(controller.wspr.status);val total=controller.wspr.hf.sumOf{it.spots};val strikes=controller.lightning.strikes;val closest=strikes.minOfOrNull{it.distanceKm};val qrn=when{closest!=null&&closest<50->92;closest!=null&&closest<120->76;closest!=null&&closest<300->58;else->hfPct};val level=when{closest!=null&&closest<50->"SEVERE QRN";closest!=null&&closest<120->"HIGH QRN";total>=40->"CALM / ACTIVE";total>=12->"ELEVATED";total>0->"SPARSE";else->"—"};DxGauge(qrn,level);Text(when{strikes.isNotEmpty()->"${strikes.size} lightning strikes within 300 km in the last hour; nearest ${closest} km.";total>0->"$total nearby WSPR receptions in the 30-minute source window.";else->"No live lightning or WSPR measurement; no noise value is inferred."},color=DxMuted)}
            DxSection("QUICK VOACAP · $zone",Modifier.weight(1.3f)){DxSelect("PATH",zone,listOf("EUROPE","AMERICAS","ASIA","OCEANIA","AFRICA")){zone=it};listOf("80m","40m","30m","20m","17m","15m","12m","10m").forEach{band->val rel=voacapReliability(band,zone,features.solar.flux,features.solar.kpIndex);DxBar(band,rel,100)};Text("Single-path heuristic; it may differ from global observations.",color=DxMuted,fontSize=12.sp)}
            DxSection("BAND ACTIVITY · 24H",Modifier.weight(1f)){controller.bandActivity.entries.take(12).forEach{DxBar(it.key,it.value,controller.bandActivity.values.maxOrNull()?:1)};if(controller.bandActivity.isEmpty())DxEmpty("Cluster history is still learning")}
        }
        DxSection("VHF / UHF / SHF BEACONS",Modifier.fillMaxWidth().weight(1f)){
            DxProviderStatusRow(controller.beaconProviderStatus)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Column(Modifier.weight(1f)){Text("RECEIVED FROM CLUSTER",color=DxCyan,fontWeight=FontWeight.Bold);if(controller.beacons.isEmpty())DxEmpty("No nearby beacon report; this does not mean no opening.")else controller.beacons.take(12).forEach{b->DxLine("${if(b.known)"★" else "·"} ${b.callsign} · ${b.band}","${formatMHz(b.frequencyHz)} · ${b.ageMinutes}m · ${b.spotter}",if(b.known)DxGreen else DxCyan)}}
                Column(Modifier.weight(1f)){Text("REFERENCE · IN INDICATIVE RANGE",color=DxCyan,fontWeight=FontWeight.Bold);controller.beaconReference.filter{it.inTypicalRange}.take(12).forEach{b->DxLine("${b.callsign} · ${b.band}","${"%.4f".format(Locale.US,b.frequencyMHz)} · ${b.distanceKm} km ${b.bearing}",DxInk)};if(controller.beaconReference.none{it.inTypicalRange})DxEmpty("Reference will populate after the monthly source refresh.")}
            }
        }
    }
}

@Composable private fun DxProviderStatusRow(status:NeuralProviderStatus,modifier:Modifier=Modifier){
    val effective=status.effective();val age=neuralProviderAgeLabel(effective.updatedEpoch)
    val color=when(effective.state){NeuralProviderState.LIVE->DxGreen;NeuralProviderState.CACHED->DxCyan;NeuralProviderState.STALE->DxYellow;NeuralProviderState.UNAVAILABLE->DxRed}
    Text(listOf(effective.source,effective.state.name,age).filter{it.isNotBlank()}.joinToString(" · "),color=color,fontSize=11.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=modifier)
}

@Composable private fun DxSection(title:String,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){Column(modifier.background(DxPanel,RoundedCornerShape(10.dp)).border(1.dp,Color(0xFF3D474E),RoundedCornerShape(10.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,color=DxAmber,fontSize=13.sp,fontWeight=FontWeight.Black);HorizontalDivider(color=Color(0xFF374047));content()}}
@Composable private fun DxMetric(label:String,value:String,color:Color){Column(Modifier.widthIn(min=64.dp).background(DxPanel,RoundedCornerShape(8.dp)).padding(horizontal=9.dp,vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(label,color=DxMuted,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(value,color=color,fontSize=18.sp,fontWeight=FontWeight.Black,fontFamily=FontFamily.Monospace)}}
@Composable private fun DxSummaryCard(label:String,value:String,modifier:Modifier){Column(modifier.background(DxPanel,RoundedCornerShape(9.dp)).padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(value,color=DxCyan,fontSize=28.sp,fontWeight=FontWeight.Black);Text(label,color=DxMuted,fontWeight=FontWeight.Bold)}}
@Composable private fun DxLine(label:String,value:String,color:Color=DxInk){Row(Modifier.fillMaxWidth().heightIn(min=30.dp),verticalAlignment=Alignment.CenterVertically){Text(label,color=DxMuted,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Text(value,color=color,fontWeight=FontWeight.Bold,maxLines=1)}}
@Composable private fun DxMiniValue(label:String,value:String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(value,color=DxCyan,fontSize=21.sp,fontWeight=FontWeight.Black);Text(label,color=DxMuted,fontSize=11.sp)}}
@Composable private fun DxEmpty(text:String){Text(text,color=DxMuted,fontStyle=androidx.compose.ui.text.font.FontStyle.Italic,modifier=Modifier.padding(vertical=8.dp))}
@Composable private fun DxBar(label:String,value:Int,maxValue:Int){Row(Modifier.fillMaxWidth().height(23.dp),verticalAlignment=Alignment.CenterVertically){Text(label,color=DxInk,modifier=Modifier.width(48.dp),fontWeight=FontWeight.Bold);Box(Modifier.weight(1f).height(8.dp).background(DxRaised,RoundedCornerShape(4.dp))){Box(Modifier.fillMaxWidth((value.toFloat()/maxValue.coerceAtLeast(1)).coerceIn(0f,1f)).fillMaxHeight().background(if(value.toFloat()/maxValue.coerceAtLeast(1)>.7f)DxGreen else DxCyan,RoundedCornerShape(4.dp)))};Text(value.toString(),color=DxMuted,modifier=Modifier.width(38.dp),fontFamily=FontFamily.Monospace)}}
@Composable private fun DxGauge(value:Int?,label:String){Box(Modifier.fillMaxWidth().height(74.dp),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize()){drawArc(DxRaised,180f,180f,false,style=Stroke(12.dp.toPx()));if(value!=null)drawArc(if(value>=70)DxGreen else if(value>=40)DxYellow else DxRed,180f,180f*value/100f,false,style=Stroke(12.dp.toPx()))};Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(top=25.dp)){Text(value?.let{"$it%"}?:"—",color=DxInk,fontSize=22.sp,fontWeight=FontWeight.Black);Text(label,color=DxMuted,fontSize=10.sp)}}}
@Composable private fun DxHeatmap(rows:List<List<Int>>){DxSection("6m ACTIVITY HEATMAP · UTC"){val max=rows.flatten().maxOrNull()?.coerceAtLeast(1)?:1;Canvas(Modifier.fillMaxWidth().height(108.dp)){val cw=size.width/24;val ch=size.height/7;rows.forEachIndexed{r,line->line.forEachIndexed{c,v->drawRect(DxCyan.copy(alpha=.08f+.82f*v/max),Offset(c*cw,r*ch),androidx.compose.ui.geometry.Size(cw-1,ch-1))}}};Text("Mon → Sun · 00 → 23 UTC",color=DxMuted,fontSize=11.sp)}}
@Composable private fun DxCell(text:String,width:Dp,color:Color,bold:Boolean=false){Text(text,color=color,fontWeight=if(bold)FontWeight.Black else FontWeight.Medium,fontFamily=if(width<=100.dp)FontFamily.Monospace else FontFamily.Default,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.width(width).padding(horizontal=5.dp))}
@Composable private fun DxSelect(label:String,value:String,choices:List<String>,change:(String)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton({open=true},modifier=Modifier.heightIn(min=48.dp)){Text("$label · $value",fontWeight=FontWeight.Bold);Icon(Icons.Outlined.ArrowDropDown,null)};DropdownMenu(open,{open=false}){choices.distinct().forEach{choice->DropdownMenuItem({Text(choice)},onClick={change(choice);open=false},trailingIcon={if(choice==value)Icon(Icons.Outlined.Check,null)})}}}}

private val briefingImageCache=object:LruCache<String,ImageBitmap>(12*1024*1024){override fun sizeOf(key:String,value:ImageBitmap)=value.width*value.height*4}
@Composable private fun DxBriefImage(url:String,title:String,modifier:Modifier){val bitmap by produceState<ImageBitmap?>(null,url){value=null;if(url.startsWith("https://",true))value=withContext(Dispatchers.IO){briefingImageCache.get(url)?:runCatching{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=6000;c.readTimeout=9000;c.instanceFollowRedirects=true;try{val bytes=c.inputStream.use{it.readBoundedBytesOrThrow(2*1024*1024)};val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,b);var s=1;while(b.outWidth/s>360||b.outHeight/s>240)s*=2;BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=s})?.asImageBitmap()?.also{briefingImageCache.put(url,it)}}finally{c.disconnect()}}.getOrNull()}}
    Surface(color=DxRaised,shape=RoundedCornerShape(6.dp),modifier=modifier){if(bitmap!=null)Image(bitmap!!,contentDescription="Briefing image for $title",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Newspaper,null,tint=DxMuted)}}}

@Composable private fun DxSpotDialog(spot:AndroidDXSpot,cty:CtyController,status:SpotLogStatus?,dismiss:()->Unit,tune:()->Unit,history:()->Unit,toggleWatch:()->Unit){val e=cty.lookup(spot.callsign);val directTune=dxDirectTuneAvailable(spot.frequencyHz);AlertDialog(onDismissRequest=dismiss,title={Text(spot.callsign)},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("${formatMHz(spot.frequencyHz)} MHz · ${dxDisplayBand(spot.band,spot.frequencyHz,spot.comment)} · ${spot.mode}",color=DxAmber,fontWeight=FontWeight.Bold);Text(e?.country.orEmpty().ifBlank{spot.country}.ifBlank{"Unknown DXCC"});Text("CQ ${e?.cqZone.orEmpty().ifBlank{spot.cqZone.toString()}} · ITU ${e?.ituZone.orEmpty().ifBlank{spot.ituZone.toString()}} · ${e?.continent.orEmpty().ifBlank{spot.continent}}",color=DxMuted);Text("Call ${status?.callStatus?:"—"} · DXCC ${status?.dxccStatus?:"—"}",color=DxYellow);Text("Score ${spot.score} · confidence ${spot.confidence} · ${spot.samples} samples");Text(spot.reason.ifBlank{spot.comment}.ifBlank{"No additional analysis"},color=DxMuted);if(!directTune)Text("Observation only above 6 m · configure a supported radio/transverter path before direct CAT tuning.",color=DxMuted)}},confirmButton={Button(tune,enabled=directTune){Text("Tune VFO A")}},dismissButton={Row{TextButton(history){Text("Log history")};TextButton(toggleWatch){Text(if(spot.watchlisted)"Unwatch" else "Watch")};TextButton(dismiss){Text("Close")}}})}
@Composable private fun ManualSpotDialog(features:FeatureController,dismiss:()->Unit){var call by remember{mutableStateOf("")};var freq by remember{mutableStateOf("")};var comment by remember{mutableStateOf("")};AlertDialog(onDismissRequest=dismiss,title={Text("Send DX cluster spot")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(call,{call=it.uppercase()},label={Text("Callsign")},singleLine=true);OutlinedTextField(freq,{freq=it.filter{c->c.isDigit()||c=='.'}},label={Text("Frequency kHz")},singleLine=true);OutlinedTextField(comment,{comment=it},label={Text("Comment")},singleLine=true);Text("Posts to the currently connected cluster only.",color=DxMuted)}},confirmButton={Button({features.postSpot(call,freq.toDoubleOrNull()?:0.0,comment);dismiss()},enabled=call.isNotBlank()&&freq.toDoubleOrNull()!=null){Text("Send spot")}},dismissButton={TextButton(dismiss){Text("Cancel")}})}

private fun dxPageIcon(page:NeuralDxPage)=when(page){NeuralDxPage.COCKPIT->Icons.Outlined.SpaceDashboard;NeuralDxPage.MAP->Icons.Outlined.Map;NeuralDxPage.INSIGHT->Icons.Outlined.Psychology;NeuralDxPage.WORLD->Icons.Outlined.Public;NeuralDxPage.BRIEFING->Icons.Outlined.Newspaper;NeuralDxPage.OBSERVATIONS->Icons.Outlined.Radar;NeuralDxPage.SATELLITES->Icons.Outlined.SatelliteAlt;NeuralDxPage.HISTORY->Icons.Outlined.History;NeuralDxPage.WEATHER->Icons.Outlined.Thunderstorm}
private fun scoreColor(score:Int)=when{score>=75->DxGreen;score>=55->DxYellow;else->DxMuted}
private fun formatMHz(hz:Long)="%.3f".format(Locale.US,hz/1_000_000.0)
private fun ageLabel(epoch:Long):String{val m=((Instant.now().epochSecond-epoch).coerceAtLeast(0)/60).toInt();return if(m<60)"${m}m" else "${m/60}h ${m%60}m"}
private fun utcTime(epoch:Long)=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epoch))
private fun utcSeconds(epoch:Long)=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epoch))
private fun hemisphere(value:Double,positive:String,negative:String)="%.0f°%s".format(kotlin.math.abs(value),if(value>=0)positive else negative)
private fun voacapReliability(band:String,zone:String,sfi:Float,kp:Float):Int{val base=mapOf("80m" to 58,"40m" to 70,"30m" to 76,"20m" to 82,"17m" to 72,"15m" to 64,"12m" to 50,"10m" to 42)[band]?:50;val flux=((sfi-80)/2).roundToInt().coerceIn(-15,25);val storm=(kp*5).roundToInt();val distance=when(zone){"EUROPE"->8;"AFRICA"->2;"ASIA"->-4;"AMERICAS"->-7;else->-8};return(base+flux-storm+distance).coerceIn(0,100)}
