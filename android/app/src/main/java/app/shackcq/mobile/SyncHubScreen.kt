package app.shackcq.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SyncPanel = Color(0xFF1B2228)
private val SyncRaised = Color(0xFF283139)
private val SyncInk = Color(0xFFF4F0E7)
private val SyncMuted = Color(0xFFA5ADB2)
private val SyncAmber = Color(0xFFE9A72B)
private val SyncGreen = Color(0xFF42C77B)
private val SyncRed = Color(0xFFE4544D)

private enum class OutboxFilter { ALL, QUEUED, ATTENTION, DELIVERED }

@Composable
fun SyncHubScreen(
    database: QsoDatabase,
    mutations: QsoMutationCoordinator,
    controller: SyncHubController,
    wavelog: WavelogController,
    nativeWavelog: WavelogNativeController,
    onBack: () -> Unit,
) {
    var filter by remember { mutableStateOf(OutboxFilter.ALL) }
    var configure by remember { mutableStateOf<SyncProvider?>(null) }
    var selected by remember { mutableStateOf<DeliveryRecord?>(null) }
    var catchUp by remember { mutableStateOf(false) }
    var nativeOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { controller.syncNow() }

    val visible = controller.records.filter { record ->
        when (filter) {
            OutboxFilter.ALL -> true
            OutboxFilter.QUEUED -> record.state in setOf(DeliveryState.QUEUED, DeliveryState.BATCH_QUEUED, DeliveryState.BATCH_RETRY_WAIT,
                DeliveryState.BATCH_PAUSED_AUTHORITY,
                DeliveryState.SENDING, DeliveryState.RETRY_WAIT, DeliveryState.PAUSED_AUTHORITY)
            OutboxFilter.ATTENTION -> record.state in setOf(DeliveryState.REJECTED, DeliveryState.AUTH_BLOCKED, DeliveryState.BATCH_AUTH_BLOCKED,
                DeliveryState.PROFILE_REQUIRED, DeliveryState.CONFIG_REQUIRED, DeliveryState.LOCAL_CHANGED)
            OutboxFilter.DELIVERED -> record.state in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE,
                DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH)
        }
    }.sortedWith(compareByDescending<DeliveryRecord> { it.updatedAt }.thenBy { it.provider.name })

    Column(Modifier.fillMaxSize().background(Color(0xFF111519)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(5.dp)); Text("LOGBOOK") }
            Column(Modifier.weight(1f)) {
                Text("SYNC HUB", color = SyncAmber, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text(if (wavelog.logMode == LogMode.LOCAL) "LOCAL LOG AUTHORITY · direct destinations available"
                    else "WAVELOG AUTHORITY · direct destinations paused to prevent duplicates",
                    color = if (wavelog.logMode == LogMode.LOCAL) SyncGreen else SyncAmber, fontWeight = FontWeight.Bold)
            }
            AssistChip({ controller.syncNow() }, { Text(if (controller.busy) "SENDING" else "SYNC NOW") },
                leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
            AssistChip({ nativeOpen = true }, { Text("WAVELOG LINK") },
                leadingIcon = { Icon(Icons.Outlined.Settings, null) })
        }
        Surface(color = if (wavelog.logMode == LogMode.LOCAL) SyncRaised else Color(0xFF49371E),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (wavelog.logMode == LogMode.LOCAL)
                "Log once. Enabled services receive only future operator-created QSOs; existing logbook entries require explicit selection."
            else "Wavelog remains the sole authority. Direct queues and automatic delivery are paused and will not resume without your action.",
                color = SyncInk, modifier = Modifier.padding(12.dp))
        }

        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderColumn(controller, wavelog.logMode, { configure = it }, { catchUp = true }, Modifier.weight(.9f))
                    OutboxColumn(database, visible, filter, { filter = it }, { selected = it }, Modifier.weight(1.4f))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { ProviderColumn(controller, wavelog.logMode, { configure = it }, { catchUp = true }, Modifier.fillMaxWidth()) }
                    item { OutboxColumn(database, visible, filter, { filter = it }, { selected = it }, Modifier.fillMaxWidth().heightIn(min = 420.dp)) }
                }
            }
        }
        Text(controller.status, color = SyncMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

    configure?.let { ProviderConfigDialog(it, controller) { configure = null } }
    selected?.let { DeliveryDialog(database, mutations, controller, it) { selected = null } }
    if (catchUp) CatchUpDialog(database, controller) { catchUp = false }
    if (nativeOpen) WavelogNativeDialog(nativeWavelog, wavelog) { nativeOpen = false }
}

@Composable
private fun ProviderColumn(
    controller: SyncHubController,
    authority: LogMode,
    configure: (SyncProvider) -> Unit,
    catchUp: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SyncProvider.entries.forEach { provider ->
            val records = controller.records.filter { it.provider == provider }
            val queued = records.count { it.state in setOf(DeliveryState.QUEUED, DeliveryState.BATCH_QUEUED, DeliveryState.BATCH_RETRY_WAIT,
                DeliveryState.BATCH_PAUSED_AUTHORITY,
                DeliveryState.SENDING, DeliveryState.RETRY_WAIT, DeliveryState.PAUSED_AUTHORITY) }
            val attention = records.count { it.state in setOf(DeliveryState.REJECTED, DeliveryState.AUTH_BLOCKED, DeliveryState.BATCH_AUTH_BLOCKED,
                DeliveryState.PROFILE_REQUIRED, DeliveryState.CONFIG_REQUIRED, DeliveryState.LOCAL_CHANGED) }
            val accepted = records.count { it.state in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE,
                DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH) }
            val last = records.maxByOrNull { it.updatedAt }
            Card(colors = CardDefaults.cardColors(containerColor = SyncPanel), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.label.uppercase(), color = SyncAmber, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text(providerIdentity(controller, provider), color = SyncMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(controller.isEnabled(provider), { controller.setEnabled(provider, it) },
                            enabled = authority == LogMode.LOCAL && controller.isConfigured(provider))
                    }
                    val state = when {
                        authority == LogMode.WAVELOG -> "PAUSED · WAVELOG AUTHORITY"
                        controller.isAuthBlocked(provider) -> "AUTHENTICATION BLOCKED"
                        !controller.isConfigured(provider) -> if (provider == SyncProvider.CLUB_LOG &&
                            controller.clubLogConfig.apiKey.isBlank()) "APP API KEY REQUIRED" else "NOT CONFIGURED"
                        controller.isEnabled(provider) && controller.isResumed(provider) -> "READY"
                        controller.isEnabled(provider) -> "PAUSED · RESUME REQUIRED"
                        else -> "DISABLED"
                    }
                    Text(state, color = when {
                        state == "READY" -> SyncGreen
                        state.contains("BLOCKED") || state.contains("REQUIRED") -> SyncRed
                        else -> SyncMuted
                    }, fontWeight = FontWeight.Bold)
                    Text("$queued queued · $attention attention · $accepted delivered", color = SyncInk)
                    if (last != null) Text("Last · ${last.providerMessage.ifBlank { last.state.name.replace('_', ' ') }}",
                        color = SyncMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton({ configure(provider) }) { Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(4.dp)); Text("CONFIGURE") }
                        Button({ controller.resume(provider) }, enabled = authority == LogMode.LOCAL && controller.isEnabled(provider) &&
                            controller.isConfigured(provider) && !controller.isResumed(provider)) { Text("RESUME") }
                    }
                    if (provider == SyncProvider.CLUB_LOG)
                        Text("Application password required. A 403 stops all Club Log traffic to protect the IP address.", color = SyncMuted, fontSize = 12.sp)
                    if (provider == SyncProvider.EQSL)
                        Text("Portable or different-QTH QSOs require a matching QTH nickname.", color = SyncMuted, fontSize = 12.sp)
                }
            }
        }
        Button(catchUp, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(7.dp)); Text("QUEUE EXISTING QSOS")
        }
        Text("Historical selection uses sequential QRZ inserts, one Club Log batch, and one eQSL batch. Nothing uploads without preview and confirmation.",
            color = SyncMuted, fontSize = 12.sp)
    }
}

@Composable
private fun OutboxColumn(
    database: QsoDatabase,
    records: List<DeliveryRecord>,
    filter: OutboxFilter,
    setFilter: (OutboxFilter) -> Unit,
    select: (DeliveryRecord) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(SyncPanel, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutboxFilter.entries.forEach { item -> FilterChip(filter == item, { setFilter(item) }, { Text(item.name) }) }
        }
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No ${filter.name.lowercase()} delivery items", color = SyncMuted)
            }
        } else LazyColumn(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(records, key = { "${it.qsoId}-${it.provider}" }) { record ->
                val qso = database.qso(record.qsoId)
                Card(onClick = { select(record) }, colors = CardDefaults.cardColors(containerColor = SyncRaised)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(qso?.callsign ?: "Deleted local QSO", color = SyncInk, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(qso?.let { "${utc(it.createdAt)} · ${it.band.ifBlank { bandForFrequency(it.frequencyHz) }} ${it.mode} · ${it.stationCallsign.ifBlank { "station not set" }}" }
                                ?: record.qsoId, color = SyncMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (record.providerMessage.isNotBlank()) Text(record.providerMessage, color = SyncMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(record.provider.shortLabel, color = SyncAmber, fontWeight = FontWeight.Black)
                            Text(record.state.name.replace('_', ' '), color = deliveryColor(record.state), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${record.attemptCount} attempts", color = SyncMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigDialog(provider: SyncProvider, controller: SyncHubController, dismiss: () -> Unit) {
    var first by remember(provider) { mutableStateOf(when (provider) {
        SyncProvider.QRZ -> controller.qrzConfig.callsign
        SyncProvider.CLUB_LOG -> controller.clubLogConfig.email
        SyncProvider.EQSL -> controller.eqslConfig.username
    }) }
    var second by remember(provider) { mutableStateOf("") }
    var third by remember(provider) { mutableStateOf(when (provider) {
        SyncProvider.QRZ -> ""
        SyncProvider.CLUB_LOG -> controller.clubLogConfig.callsign
        SyncProvider.EQSL -> controller.eqslConfig.qthNickname
    }) }
    var fourth by remember(provider) { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text("CONFIGURE ${provider.label.uppercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                when (provider) {
                    SyncProvider.QRZ -> {
                        OutlinedTextField(first, { first = it.uppercase() }, label = { Text("QRZ logbook callsign") })
                        OutlinedTextField(second, { second = it }, label = { Text(if (controller.qrzConfig.apiKey.isBlank()) "Logbook API key" else "Replace saved API key") },
                            visualTransformation = PasswordVisualTransformation())
                        Text("The key selects one callsign-specific logbook and requires a suitable QRZ subscription.", color = SyncMuted)
                    }
                    SyncProvider.CLUB_LOG -> {
                        OutlinedTextField(first, { first = it }, label = { Text("Account email") })
                        OutlinedTextField(second, { second = it }, label = { Text(if (controller.clubLogConfig.password.isBlank()) "Application password" else "Replace saved application password") },
                            visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(third, { third = it.uppercase() }, label = { Text("Owned target callsign") })
                        OutlinedTextField(fourth, { fourth = it }, label = { Text(if (controller.clubLogConfig.apiKey.isBlank()) "App API key · required" else "Replace saved app API key") },
                            visualTransformation = PasswordVisualTransformation())
                        Text("Use a Club Log application password, never the main password. Self-built installs may enter their own app key.", color = SyncMuted)
                    }
                    SyncProvider.EQSL -> {
                        OutlinedTextField(first, { first = it.uppercase() }, label = { Text("eQSL callsign / username") })
                        OutlinedTextField(second, { second = it }, label = { Text(if (controller.eqslConfig.password.isBlank()) "Password" else "Replace saved password") },
                            visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(third, { third = it }, label = { Text("Optional QTH nickname") })
                        Text("A QTH nickname is required before portable or different-grid QSOs can send automatically.", color = SyncMuted)
                    }
                }
            }
        },
        confirmButton = {
            Button({
                when (provider) {
                    SyncProvider.QRZ -> controller.saveQrz(first, second.ifBlank { controller.qrzConfig.apiKey })
                    SyncProvider.CLUB_LOG -> controller.saveClubLog(first, second.ifBlank { controller.clubLogConfig.password },
                        third, fourth.ifBlank { controller.clubLogConfig.apiKey })
                    SyncProvider.EQSL -> controller.saveEqsl(first, second.ifBlank { controller.eqslConfig.password }, third)
                }
                dismiss()
            }) { Text("SAVE · PAUSE UNTIL RESUMED") }
        },
        dismissButton = {
            Row {
                if (provider == SyncProvider.QRZ) TextButton({ controller.testQrz() }) { Text("READ-ONLY STATUS TEST") }
                TextButton({ controller.clearProvider(provider); dismiss() }) { Text("CLEAR SAVED") }
                TextButton(dismiss) { Text("CANCEL") }
            }
        })
}

@Composable
private fun DeliveryDialog(database: QsoDatabase, mutations: QsoMutationCoordinator,
    controller: SyncHubController, record: DeliveryRecord, dismiss: () -> Unit) {
    val qso = database.qso(record.qsoId)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val inAppBrowser = LocalInAppBrowserState.current
    var editing by remember { mutableStateOf(false) }
    if (editing && qso != null) {
        QsoCorrectionDialog(qso, mutations, controller) { editing = false }
        return
    }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text("${record.provider.label} · ${qso?.callsign ?: record.qsoId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.state.name.replace('_', ' '), color = deliveryColor(record.state), fontWeight = FontWeight.Black)
                Text(record.providerMessage.ifBlank { "No provider message" })
                Text("Attempts ${record.attemptCount} · payload ${record.payloadHash.take(12).ifBlank { "not sent" }}", color = SyncMuted)
                if (record.remoteId.isNotBlank()) Text("Remote ID ${record.remoteId}", color = SyncMuted)
                Text("Provider messages are sanitized and bounded. Confirmation is never inferred from upload acceptance.", color = SyncMuted)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (record.state == DeliveryState.LOCAL_CHANGED)
                    Button({ controller.requeueCurrent(record); dismiss() }) { Text("QUEUE UPDATED COPY") }
                else Button({ controller.retry(record); dismiss() },
                    enabled = record.state !in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE,
                        DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH)) { Text("RETRY NOW") }
                OutlinedButton({ controller.removeUnsent(record); dismiss() },
                    enabled = record.state !in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE,
                        DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH)) { Text("REMOVE UNSENT") }
                OutlinedButton({ editing = true }, enabled = qso != null) { Text("EDIT QSO") }
            }
        },
        dismissButton = {
            Row {
                TextButton({ clipboard.setText(AnnotatedString(record.providerMessage)) }, enabled = record.providerMessage.isNotBlank()) {
                    Text("COPY MESSAGE")
                }
                TextButton({
                    val url = when (record.provider) {
                        SyncProvider.QRZ -> "https://logbook.qrz.com"
                        SyncProvider.CLUB_LOG -> "https://clublog.org"
                        SyncProvider.EQSL -> "https://www.eqsl.cc"
                    }
                    inAppBrowser?.open(url)
                }) { Text("OPEN PROVIDER") }
                TextButton(dismiss) { Text("CLOSE") }
            }
        })
}

@Composable
internal fun QsoCorrectionDialog(qso: Qso, mutations: QsoMutationCoordinator,
    controller: SyncHubController, dismiss: () -> Unit) {
    val initial = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC)
    var callsign by remember { mutableStateOf(qso.callsign) }
    var station by remember { mutableStateOf(qso.stationCallsign) }
    var frequency by remember { mutableStateOf("%.6f".format(Locale.US, qso.frequencyHz / 1_000_000.0)) }
    var mode by remember { mutableStateOf(qso.mode) }
    var date by remember { mutableStateOf(initial.toLocalDate().toString()) }
    var time by remember { mutableStateOf(initial.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))) }
    var grid by remember { mutableStateOf(qso.myGrid) }
    var notes by remember { mutableStateOf(qso.notes) }
    val epoch = runCatching { LocalDateTime.parse("$date $time", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    val hz = frequency.toDoubleOrNull()?.let { (it * 1_000_000).toLong() }
    val valid = callsign.isNotBlank() && station.isNotBlank() && mode.isNotBlank() && epoch != null && hz != null && hz > 0
    AlertDialog(onDismissRequest = dismiss,
        title = { Text("CORRECT LOCAL QSO") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(callsign, { callsign = it.uppercase() }, label = { Text("Callsign") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(station, { station = it.uppercase() }, label = { Text("Station callsign") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(frequency, { frequency = it }, label = { Text("Frequency MHz") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(mode, { mode = it.uppercase() }, label = { Text("Mode") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(date, { date = it }, label = { Text("UTC date") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(time, { time = it }, label = { Text("UTC time") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(grid, { grid = it.uppercase() }, label = { Text("My grid") })
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
                Text("Accepted deliveries become LOCAL CHANGED and are never overwritten remotely until you explicitly queue the updated copy.", color = SyncMuted)
            }
        },
        confirmButton = {
            Button({
                mutations.update(qso.copy(callsign = callsign.trim(), stationCallsign = station.trim(),
                    frequencyHz = hz!!, band = bandForFrequency(hz), mode = mode.trim(), createdAt = epoch!!,
                    myGrid = grid.trim(), notes = notes))
                controller.refreshNow()
                dismiss()
            }, enabled = valid) { Text("SAVE LOCAL CORRECTION") }
        },
        dismissButton = { TextButton(dismiss) { Text("CANCEL") } })
}

@Composable
private fun CatchUpDialog(database: QsoDatabase, controller: SyncHubController, dismiss: () -> Unit) {
    var from by remember { mutableStateOf(LocalDate.now(ZoneOffset.UTC).minusDays(7).toString()) }
    var to by remember { mutableStateOf(LocalDate.now(ZoneOffset.UTC).toString()) }
    var station by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf(emptySet<SyncProvider>()) }
    var confirm by remember { mutableStateOf(false) }
    val fromDate = runCatching { LocalDate.parse(from) }.getOrNull()
    val toDate = runCatching { LocalDate.parse(to) }.getOrNull()
    var candidates by remember { mutableStateOf<List<QsoCatchUpCandidate>>(emptyList()) }
    LaunchedEffect(fromDate,toDate,station){candidates=if(fromDate==null||toDate==null||fromDate>toDate)emptyList()else withContext(Dispatchers.IO){database.catchUpCandidates(fromDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC),toDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC),station)}}
    val callsigns = candidates.map { it.stationCallsign.ifBlank { "station not set" } }.distinct().sorted()
    AlertDialog(onDismissRequest = dismiss,
        title = { Text("QUEUE EXISTING QSOS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(from, { from = it }, label = { Text("From UTC") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(to, { to = it }, label = { Text("To UTC") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(station, { station = it.uppercase() }, label = { Text("Station callsign · optional exact filter") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SyncProvider.entries.forEach { provider ->
                        FilterChip(provider in providers, {
                            providers = if (provider in providers) providers - provider else providers + provider
                        }, { Text(provider.shortLabel) }, enabled = controller.isConfigured(provider))
                    }
                }
                Text("PREVIEW · ${candidates.size} QSOs · ${callsigns.joinToString().ifBlank { "no station callsigns" }}",
                    color = if (candidates.isEmpty()) SyncMuted else SyncGreen, fontWeight = FontWeight.Bold)
                Text("QRZ sends conservatively one-by-one. Club Log and eQSL use one batch each. Club Log never receives clear=1.", color = SyncMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(confirm, { confirm = it }, enabled = candidates.isNotEmpty() && providers.isNotEmpty())
                    Text(" I reviewed the count, station callsigns and destinations")
                }
            }
        },
        confirmButton = {
            Button({ controller.queueExisting(candidates.map(QsoCatchUpCandidate::id), providers); dismiss() },
                enabled = confirm && candidates.isNotEmpty() && providers.isNotEmpty()) { Text("QUEUE ${candidates.size} QSOS") }
        },
        dismissButton = { TextButton(dismiss) { Text("CANCEL") } })
}

private fun providerIdentity(controller: SyncHubController, provider: SyncProvider) = when (provider) {
    SyncProvider.QRZ -> listOf(controller.qrzConfig.callsign, controller.qrzStatus.name,
        listOf(controller.qrzStatus.startDate, controller.qrzStatus.endDate).filter(String::isNotBlank).joinToString("–"))
        .filter(String::isNotBlank).joinToString(" · ").ifBlank { "No logbook callsign" }
    SyncProvider.CLUB_LOG -> controller.clubLogConfig.callsign.ifBlank { "No target callsign" }
    SyncProvider.EQSL -> listOf(controller.eqslConfig.username, controller.eqslConfig.qthNickname)
        .filter(String::isNotBlank).joinToString(" · ").ifBlank { "No eQSL profile" }
}

private fun deliveryColor(state: DeliveryState) = when (state) {
    DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE, DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH -> SyncGreen
    DeliveryState.REJECTED, DeliveryState.AUTH_BLOCKED, DeliveryState.BATCH_AUTH_BLOCKED, DeliveryState.PROFILE_REQUIRED, DeliveryState.CONFIG_REQUIRED, DeliveryState.LOCAL_CHANGED -> SyncRed
    DeliveryState.QUEUED, DeliveryState.BATCH_QUEUED, DeliveryState.BATCH_RETRY_WAIT, DeliveryState.BATCH_PAUSED_AUTHORITY,
    DeliveryState.SENDING, DeliveryState.RETRY_WAIT, DeliveryState.PAUSED_AUTHORITY -> SyncAmber
}

private fun utc(epoch: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    .withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epoch))
