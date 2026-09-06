package app.shackcq.mobile.contest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable internal fun ContestReviewScreen(state: ContestWorkspaceState, callbacks: ContestWorkspaceCallbacks) {
    var query by remember { mutableStateOf("") }
    var band by remember { mutableStateOf("ALL") }
    var mode by remember { mutableStateOf("ALL") }
    var duplicateOnly by remember { mutableStateOf(false) }
    var invalidOnly by remember { mutableStateOf(false) }
    var reviewOnly by remember { mutableStateOf(false) }
    var zeroOnly by remember { mutableStateOf(false) }
    var networkOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ContestReviewRow?>(null) }
    var delete by remember { mutableStateOf<ContestReviewRow?>(null) }
    var confirmMerge by remember { mutableStateOf(false) }
    val rows = state.reviewRows.filter { row ->
        (query.isBlank() || row.callsign.contains(query, true) || row.id.contains(query, true)) &&
            (band == "ALL" || row.band == band) && (mode == "ALL" || row.mode == mode) &&
            (!duplicateOnly || row.duplicate) && (!invalidOnly || row.invalid) && (!reviewOnly || row.reviewRequired) &&
            (!zeroOnly || row.zeroPoint) && (!networkOnly || row.networkOrigin)
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "CONTEST REVIEW",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("${state.score.reviewQsos} review · ${state.score.duplicates} duplicates · ${state.score.zeroPointValidQsos} valid zero-point",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(callbacks.onOpenLogbook) { Text("OPEN LOGBOOK") }
            Button({ confirmMerge = true }, enabled = state.reviewRows.any { it.mergeState != "MERGED" }) { Text("MERGE TO LOGBOOK") }
        }
        OutlinedTextField(query, { query = it.take(80) }, label = { Text("Search callsign / QSO ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("ALL") + state.reviewRows.map(ContestReviewRow::band).filter(String::isNotBlank).distinct()).forEach { value ->
                FilterChip(band == value, { band = value }, { Text(value) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("ALL") + state.reviewRows.map(ContestReviewRow::mode).filter(String::isNotBlank).distinct()).forEach { value ->
                FilterChip(mode == value, { mode = value }, { Text(value) })
            }
            FilterChip(duplicateOnly, { duplicateOnly = !duplicateOnly }, { Text("DUPLICATES") })
            FilterChip(invalidOnly, { invalidOnly = !invalidOnly }, { Text("INVALID / INCOMPLETE") })
            FilterChip(reviewOnly, { reviewOnly = !reviewOnly }, { Text("REVIEW REQUIRED") })
            FilterChip(zeroOnly, { zeroOnly = !zeroOnly }, { Text("ZERO POINT") })
            FilterChip(networkOnly, { networkOnly = !networkOnly }, { Text("NETWORK ORIGIN") })
        }
        if (rows.isEmpty()) {
            Surface(Modifier.fillMaxWidth().heightIn(min = 190.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SESSION LOG IS EMPTY", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("Contest QSOs are staged here before an explicit merge to the canonical Logbook.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Open Logging, start or resume the session, then enter a callsign and exchange. Nothing has been written to the Logbook.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ callbacks.onPage(ContestWorkspacePage.LOGGING) }) { Text("OPEN LOGGING") }
                }
            }
            Spacer(Modifier.weight(1f))
        } else Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = ContestReviewRow::id) { row ->
                    Card(onClick = { selected = row }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(row.callsign, style = MaterialTheme.typography.titleMedium)
                                Text("${utcDate(row.createdAt)} · ${row.band} ${row.mode} · ${"%.3f".format(row.frequencyHz / 1_000_000.0)} MHz")
                                Text(listOf("DUPE".takeIf { row.duplicate }, "INVALID".takeIf { row.invalid },
                                    "REVIEW".takeIf { row.reviewRequired }, "ZERO POINT".takeIf { row.zeroPoint },
                                    "NETWORK".takeIf { row.networkOrigin }, row.mergeState).filterNotNull().joinToString(" · ").ifBlank { "VALID" },
                                    color = if (row.invalid || row.reviewRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (row.issue.isNotBlank()) Text(row.issue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Text("${row.rstSent} / ${row.rstReceived}")
                        }
                    }
                }
                if (state.reviewHasMore) item { Text("More rows remain; this page is bounded to 100 Contest session entries.", modifier = Modifier.padding(12.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ callbacks.onExport("CABRILLO") }) { Text("CABRILLO VALIDATION / PREVIEW") }
            OutlinedButton({ callbacks.onExport("ADIF") }) { Text("ADIF PREVIEW") }
        }
        if (state.exportPreview.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(state.exportPreview, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 7)
            }
        }
    }
    selected?.let { row -> ContestEditDialog(row, { call, sent, received -> callbacks.onEditQso(row.id, call, sent, received); selected = null },
        { delete = row; selected = null }, { selected = null }) }
    delete?.let { row -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("DELETE CONTEST QSO?") },
        text = { Text("Delete the unmerged temporary Contest entry for ${row.callsign}? Canonical Logbook and Wavelog data are unchanged.") },
        confirmButton = { Button({ callbacks.onDeleteQso(row.id); delete = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("DELETE") } },
        dismissButton = { TextButton({ delete = null }) { Text("CANCEL") } }) }
    if (confirmMerge) AlertDialog(onDismissRequest = { confirmMerge = false }, title = { Text("MERGE CONTEST SESSION TO LOGBOOK?") },
        text = { Text("Each reviewed unmerged entry will pass through the canonical QSO mutation coordinator. Existing canonical duplicates fail safely and remain retryable; the normal Wavelog outbox remains the only upload path.") },
        confirmButton = { Button({ callbacks.onMergeToLogbook(); confirmMerge = false }) { Text("MERGE REVIEWED ENTRIES") } },
        dismissButton = { TextButton({ confirmMerge = false }) { Text("CANCEL") } })
}

@Composable private fun ContestEditDialog(row: ContestReviewRow, save: (String, String, String) -> Unit, delete: () -> Unit, dismiss: () -> Unit) {
    var call by remember(row.id) { mutableStateOf(row.callsign) }
    var sent by remember(row.id) { mutableStateOf(row.rstSent) }
    var received by remember(row.id) { mutableStateOf(row.rstReceived) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("EDIT ${row.callsign}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${row.id}\n${utcDate(row.createdAt)} · ${row.band} ${row.mode} · ${row.frequencyHz} Hz",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(call, { call = it.uppercase().take(24) }, label = { Text("Callsign") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sent, { sent = it.take(4) }, label = { Text("RST sent") }, modifier = Modifier.weight(1f))
                OutlinedTextField(received, { received = it.take(4) }, label = { Text("RST received") }, modifier = Modifier.weight(1f))
            }
        }
    }, confirmButton = { Button({ save(call, sent, received) }, enabled = call.isNotBlank() && row.mergeState != "MERGED") { Text("SAVE SESSION ENTRY") } },
        dismissButton = { Row { TextButton(delete) { Text("DELETE") }; TextButton(dismiss) { Text("CANCEL") } } })
}

private fun utcDate(epoch: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epoch))
