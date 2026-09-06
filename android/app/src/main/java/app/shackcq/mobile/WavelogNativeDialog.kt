package app.shackcq.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WavelogNativeDialog(controller: WavelogNativeController, wavelog: WavelogController, dismiss: () -> Unit) {
    val inspection = controller.inspection
    val binding = controller.binding
    var pendingMapping by remember { mutableStateOf<Pair<WavelogV2Station, String>?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var cancelCreate by remember { mutableStateOf<WavelogOutboxItem?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("NATIVE WAVELOG LINK") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Local SQLite remains authoritative. API v2 is an optional remote peer; one writable binding is allowed.")
                    Text(if (wavelog.apiKey.startsWith("wl2_")) "API v2 token is stored in Android Keystore-backed encrypted preferences."
                        else "Enter a wl2_ API v2 token in Settings. A legacy key is not treated as a bearer token.", color = Color(0xFFE9A72B))
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ controller.inspect() }, enabled = !controller.busy && wavelog.apiKey.startsWith("wl2_")) { Text("INSPECT TOKEN") }
                        inspection?.token?.let { token ->
                            AssistChip({}, { Text(if (token.capabilities.canWriteQsos) "READ / WRITE" else "READ ONLY") })
                        }
                    }
                }
                inspection?.let { result ->
                    item {
                        val scopes = result.token.scopes.sorted().joinToString(", ").ifBlank { "No scopes reported" }
                        Text("Owner ${result.token.owner.ifBlank { "unknown" }}", fontWeight = FontWeight.Bold)
                        Text("Scopes: $scopes")
                        result.token.expiresAt.takeIf(String::isNotBlank)?.let { Text("Expires: $it") }
                    }
                    item { HorizontalDivider() }
                    items(result.stations, key = { it.id }) { station ->
                        val selected = binding?.remoteStationId == station.id
                        Card(colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF314B3C) else Color(0xFF283139))) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(listOf(station.name, station.callsign).filter(String::isNotBlank).joinToString(" · "), fontWeight = FontWeight.Bold)
                                Text(listOf(station.grid, "ID ${station.id}", station.uuid).filter(String::isNotBlank).joinToString(" · "))
                                Text("Map one local station explicitly:", color = Color(0xFFA5ADB2))
                                controller.localStationIds.forEach { localId ->
                                    val mapped = selected && binding?.localStationProfileId == localId
                                    OutlinedButton({
                                        val remap = binding != null && (binding.remoteStationId != station.id ||
                                            binding.localStationProfileId != localId)
                                        if (remap) pendingMapping = station to localId
                                        else controller.bindStation(station, localId)
                                    }, enabled = !controller.busy) {
                                        Text(if (mapped) "MAPPED · ${controller.localStationLabel(localId)}"
                                            else "MAP · ${controller.localStationLabel(localId)}")
                                    }
                                }
                            }
                        }
                    }
                }
                pendingMapping?.let { (station, localId) -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF5A3B23))) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("REMAP WARNING", fontWeight = FontWeight.Bold)
                            Text("Current: local ${binding?.let { controller.localStationLabel(it.localStationProfileId) }} ↔ " +
                                "${binding?.remoteStationName} (${binding?.remoteStationId})")
                            Text("New: local ${controller.localStationLabel(localId)} ↔ " +
                                "${station.name.ifBlank { station.callsign }} (${station.id})")
                            Text("Existing links and queued metadata remain attached to this binding; run FULL after remapping.")
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Button({ controller.bindStation(station, localId); pendingMapping = null }, enabled = !controller.busy) { Text("CONFIRM REMAP") }
                                TextButton({ pendingMapping = null }) { Text("CANCEL") }
                            }
                        }
                    }
                } }
                binding?.let { active ->
                    item { HorizontalDivider() }
                    item {
                        Text(active.remoteStationName.ifBlank { "Station ${active.remoteStationId}" }, fontWeight = FontWeight.Bold)
                        Text("Local ${controller.localStationLabel(active.localStationProfileId)} ↔ remote station ${active.remoteStationId}")
                        Text(when (active.state) {
                            WavelogBindingState.READ_ONLY -> "READ-ONLY LOCAL REPLICA · local changes cannot be pushed"
                            WavelogBindingState.PAUSED -> "PAUSED · local mutations remain durably queued; no remote work runs"
                            WavelogBindingState.ENABLED -> "TWO-WAY LINK · downstream direct uploads remain governed by Sync Hub authority"
                        })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedButton({ controller.initialSync() }, enabled = !controller.busy && active.state != WavelogBindingState.PAUSED) { Text("INITIAL") }
                            OutlinedButton({ controller.quickSync() }, enabled = !controller.busy && active.state != WavelogBindingState.PAUSED) { Text("QUICK") }
                            OutlinedButton({ controller.fullReconcile() }, enabled = !controller.busy && active.state != WavelogBindingState.PAUSED) { Text("FULL") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedButton({ if (active.state == WavelogBindingState.PAUSED) controller.resumeBinding() else controller.pauseBinding() },
                                enabled = !controller.busy && active.state != WavelogBindingState.READ_ONLY) {
                                Text(if (active.state == WavelogBindingState.PAUSED) "RESUME" else "PAUSE")
                            }
                            OutlinedButton({ confirmReset = true }, enabled = !controller.busy) { Text("RESET METADATA") }
                            OutlinedButton({ confirmRemove = true }, enabled = !controller.busy) { Text("REMOVE LINK") }
                        }
                        if (active.lastErrorClass != WavelogErrorClass.NONE) {
                            Text("Last operational error · ${active.lastErrorClass.name.replace('_', ' ')} · ${active.lastErrorSummary}",
                                color = Color(0xFFE4544D))
                        }
                        if (controller.busy) {
                            OutlinedButton({ controller.cancelSync() }) { Text("CANCEL AFTER CURRENT PAGE") }
                        }
                    }
                    controller.lastSummary?.let { summary -> item {
                        Text("${summary.imported} imported · ${summary.linked} linked · ${summary.merged} merged · ${summary.ambiguous} ambiguous")
                        Text("${summary.pages} pages · resumed at ${summary.resumedFromPage} · " +
                            when {
                                summary.cancelled -> "cancelled safely"
                                summary.scope == "QUICK" -> "bounded recent overlap complete · historic edits require FULL"
                                summary.completed && summary.inventoryStable -> "two-pass inventory stable · full available history complete"
                                summary.scope == "FULL" -> "inventory changed or scan incomplete · no remote deletions inferred"
                                else -> "scan incomplete · restart begins safely at page 1"
                            })
                        Text("${controller.openConflicts} open conflicts", color = if (controller.openConflicts > 0) Color(0xFFE4544D) else Color(0xFF42C77B))
                    } }
                    items(controller.conflicts.filter { it.state == WavelogConflictState.OPEN }, key = { it.id }) { conflict ->
                        WavelogConflictCard(controller, conflict)
                    }
                    if (controller.outbox.isNotEmpty()) item {
                        HorizontalDivider()
                        Text("NATIVE OUTBOX · ${controller.outbox.size}", fontWeight = FontWeight.Bold)
                        Text("Ambiguous CREATE/DELETE results require reconciliation and cannot be blindly retried.", color = Color(0xFFA5ADB2))
                    }
                    items(controller.outbox, key = { it.entry.id }) { item ->
                        WavelogOutboxCard(controller, item, { controller.reconcileOutbox() }, { cancelCreate = item })
                    }
                }
                if (confirmReset) item { DestructiveWavelogCard(
                    "RESET ALL SYNC METADATA?",
                    "Deletes remote links, outbox history, scan checkpoints, conflicts, and tombstones. Local QSOs and the station mapping remain.",
                    { controller.resetSynchronizationMetadata(); confirmReset = false }, { confirmReset = false }, controller.busy,
                ) }
                if (confirmRemove) item { DestructiveWavelogCard(
                    "REMOVE WAVELOG LINK?",
                    "Deletes the binding and all native Wavelog sync metadata. Local QSOs remain on this tablet.",
                    { controller.removeBinding(); confirmRemove = false }, { confirmRemove = false }, controller.busy,
                ) }
                cancelCreate?.let { pending -> item { DestructiveWavelogCard(
                    "CANCEL UNSENT CREATE?",
                    "Removes only this unsent outbox CREATE. The local QSO remains. Do not use this after an ambiguous remote result unless you have reconciled Wavelog.",
                    { controller.cancelUnsentCreate(pending); cancelCreate = null }, { cancelCreate = null }, controller.busy,
                ) } }
                item { Text(controller.status, color = Color(0xFFA5ADB2)) }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("CLOSE") } },
    )
}

@Composable
private fun WavelogConflictCard(controller: WavelogNativeController, conflict: WavelogConflict) {
    val baseline = remember(conflict.id) { CanonicalQso.decode(conflict.baselineCanonical) }
    val local = remember(conflict.id) { CanonicalQso.decode(conflict.localCanonical) }
    val remote = remember(conflict.id) { CanonicalQso.decode(conflict.remoteCanonical) }
    val merged = remember(conflict.id) { mutableStateMapOf<String, String>().apply { putAll(local.fields) } }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2D2D))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("CONFLICT · ${conflict.localQsoId}", fontWeight = FontWeight.Bold)
            conflict.resolutionIntent?.let { Text("${it.name.replace('_', ' ')} is pending remote acceptance", color = Color(0xFFE9A72B)) }
            conflict.conflictingFields.sorted().forEach { field ->
                if (field == "LOCAL_DELETE" || field == "REMOTE_DELETE") {
                    Text(if (field == "LOCAL_DELETE") "Deleted locally, but the remote QSO changed."
                        else "Deleted remotely, but the local QSO changed.")
                } else {
                    Text(field, fontWeight = FontWeight.Bold)
                    Text("Baseline: ${baseline.fields[field].orEmpty()}")
                    Text("Local: ${local.fields[field].orEmpty()}")
                    Text("Remote: ${remote.fields[field].orEmpty()}")
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton({ merged[field] = local.fields[field].orEmpty() }) { Text("USE LOCAL") }
                        OutlinedButton({ merged[field] = remote.fields[field].orEmpty() }) { Text("USE REMOTE") }
                    }
                    Text("Merged choice: ${merged[field].orEmpty()}", color = Color(0xFFA5ADB2))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton({ controller.resolveConflict(conflict, WavelogConflictState.KEEP_LOCAL) },
                    enabled = !controller.busy && conflict.resolutionIntent == null) { Text("KEEP LOCAL") }
                OutlinedButton({ controller.resolveConflict(conflict, WavelogConflictState.KEEP_REMOTE) },
                    enabled = !controller.busy && conflict.resolutionIntent == null) { Text("KEEP REMOTE") }
                Button({ controller.resolveConflict(conflict, WavelogConflictState.MERGED, merged.toMap()) },
                    enabled = !controller.busy && conflict.resolutionIntent == null && conflict.conflictingFields.none { it.endsWith("_DELETE") }) { Text("APPLY MERGED") }
            }
        }
    }
}

@Composable
private fun WavelogOutboxCard(controller: WavelogNativeController, item: WavelogOutboxItem,
    reconcile: () -> Unit, cancelCreate: () -> Unit) {
    val entry = item.entry
    val ambiguous = entry.errorClass == WavelogErrorClass.AMBIGUOUS_WRITE
    Card(colors = CardDefaults.cardColors(containerColor = if (item.invariantViolation) Color(0xFF5B2525) else Color(0xFF283139))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${entry.operation.name} · ${entry.state.name}", fontWeight = FontWeight.Bold)
            Text("Local ${entry.localQsoId} · Remote ${item.remoteId.ifBlank { "not linked" }}")
            Text(item.relation, color = if (item.invariantViolation || ambiguous) Color(0xFFE4544D) else Color(0xFFA5ADB2))
            Text("Attempts ${entry.attemptCount}" + (entry.nextAttemptAt?.let { " · retry at $it UTC epoch" } ?: ""))
            if (entry.errorClass != WavelogErrorClass.NONE || entry.lastError.isNotBlank()) {
                Text("${entry.errorClass.name.replace('_', ' ')} · ${entry.lastError}", color = Color(0xFFE9A72B))
            }
            if (entry.state != WavelogOutboxState.ACCEPTED) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (ambiguous && entry.operation in setOf(WavelogOperation.CREATE, WavelogOperation.DELETE)) {
                    OutlinedButton(reconcile, enabled = !controller.busy) { Text("RECONCILE") }
                } else {
                    OutlinedButton({ controller.retryOutbox(item) }, enabled = !controller.busy && entry.state != WavelogOutboxState.PAUSED) { Text("RETRY SAFE") }
                }
                if (entry.operation == WavelogOperation.CREATE && !ambiguous) {
                    TextButton(cancelCreate, enabled = !controller.busy) { Text("CANCEL UNSENT") }
                }
            }
        }
    }
}

@Composable
private fun DestructiveWavelogCard(title: String, body: String, confirm: () -> Unit, cancel: () -> Unit, busy: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF5B2525))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(confirm, enabled = !busy) { Text("CONFIRM") }
                TextButton(cancel) { Text("CANCEL") }
            }
        }
    }
}
