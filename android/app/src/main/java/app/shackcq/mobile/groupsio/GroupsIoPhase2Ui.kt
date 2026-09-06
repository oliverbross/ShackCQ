package app.shackcq.mobile.groupsio

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun GroupsIoPhase2Overlays(controller: GroupsIoController) {
    if (controller.showComposer) GroupsIoComposerDialog(controller)
    if (controller.showDraftsOutbox) GroupsIoDraftsOutboxDialog(controller)
    if (controller.showAttachments) GroupsIoAttachmentsDialog(controller)
}

@Composable
private fun GroupsIoAttachmentsDialog(controller: GroupsIoController) {
    AlertDialog(onDismissRequest = controller::closeAttachments, title = { Text("Message Attachments") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (controller.incomingAttachments.isEmpty()) item { Text(if (controller.connected) "No attachments reported" else "Reconnect to refresh secure download links") }
            items(controller.incomingAttachments, key = { it.id }) { value ->
                val imageFile = controller.attachmentFile(value.localRelativePath) ?: controller.attachmentFile(value.localPreviewRelativePath)
                val bitmap = remember(imageFile?.path, imageFile?.lastModified()) { imageFile?.let { BitmapFactory.decodeFile(it.path) } }
                Card { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListItem(headlineContent = { Text(value.filename) }, supportingContent = {
                        Text("${value.mediaType} · ${value.size?.let { "$it bytes" } ?: "size unknown"}${if (value.localRelativePath != null) " · available offline" else ""}")
                    }, trailingContent = {
                        Button({ controller.downloadAttachment(value) }, enabled = controller.connected && !controller.busy && value.localRelativePath == null) {
                            Text(if (value.localRelativePath == null) "Download" else "Saved")
                        }
                    })
                    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Preview of ${value.filename}",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit)
                    else if (value.mediaType.startsWith("image/")) Text(if (controller.busy) "Loading image preview…" else "Preview unavailable · download to view offline", style = MaterialTheme.typography.bodySmall)
                } }
            }
        } }, confirmButton = { TextButton(controller::closeAttachments) { Text("Done") } })
}

@Composable
private fun GroupsIoComposerDialog(controller: GroupsIoController) {
    val draft = controller.composerDraft ?: return
    var confirmMode by remember { mutableStateOf<Boolean?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> controller.addComposerAttachments(uris) }
    LaunchedEffect(draft.subject, draft.bodyPlain, draft.replyDestination) { delay(600); controller.autosaveComposer() }

    AlertDialog(
        onDismissRequest = controller::closeComposer,
        title = { Text(if (draft.type == GroupsIoDraftType.NEW_TOPIC) "New Topic" else "Reply") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                item { Text("Destination group #${draft.groupId}", style = MaterialTheme.typography.bodySmall) }
                if (draft.type == GroupsIoDraftType.REPLY) item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        GroupsIoReplyDestination.entries.forEachIndexed { index, value ->
                            SegmentedButton(selected = draft.replyDestination == value, onClick = { controller.updateComposer(draft.subject, draft.bodyPlain, value) },
                                shape = SegmentedButtonDefaults.itemShape(index, GroupsIoReplyDestination.entries.size), label = { Text(value.label.removePrefix("Reply to ")) })
                        }
                    }
                }
                item { OutlinedTextField(draft.subject, { controller.updateComposer(it, draft.bodyPlain) }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(draft.bodyPlain, { controller.updateComposer(draft.subject, it) }, label = { Text("Message (plain text)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), minLines = 7) }
                item { OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text("Add Attachments") } }
                item { Text("Files are copied immediately to private Groups.io storage. 100 MiB technical ceiling per attachment.", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton({ controller.autosaveComposer(); controller.closeComposer() }) { Text("Save Draft") }
                OutlinedButton({ confirmMode = false }) { Text("Send When Online") }
                Button({ confirmMode = true }, enabled = controller.connected && controller.enabled) { Text("Send Now") }
            }
        },
        dismissButton = { TextButton(controller::closeComposer) { Text("Close Safely") } },
    )

    confirmMode?.let { sendNow ->
        AlertDialog(onDismissRequest = { confirmMode = null }, title = { Text(if (sendNow) "Confirm Send Now" else "Confirm Offline Queue") },
            text = { Text("Group #${draft.groupId}\n${if (draft.type == GroupsIoDraftType.NEW_TOPIC) "New Topic" else draft.replyDestination?.label ?: "Reply"}\nSubject: ${draft.subject}\n${if (sendNow) "The message will be sent now." else "The message may leave the device only after an explicit foreground outbox action."}") },
            confirmButton = { Button({ controller.queueComposer(sendNow); confirmMode = null }) { Text(if (sendNow) "Send" else "Queue") } },
            dismissButton = { TextButton({ confirmMode = null }) { Text("Cancel") } })
    }
}

@Composable
private fun GroupsIoDraftsOutboxDialog(controller: GroupsIoController) {
    AlertDialog(
        onDismissRequest = controller::closeDraftsOutbox,
        title = { Text("Drafts & Outbox") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(controller::refreshServerDrafts, enabled = controller.connected && !controller.busy) { Text("Refresh Server Drafts") }
                        Button(controller::processQueuedExplicitly, enabled = controller.connected && controller.enabled) { Text("Process Queued") }
                    }
                }
                val groups = listOf(
                    "Local Drafts" to controller.localDrafts.filter { it.state == GroupsIoOutboxState.DRAFT_LOCAL },
                    "Queued" to controller.localDrafts.filter { it.state in setOf(GroupsIoOutboxState.QUEUED, GroupsIoOutboxState.FAILED_RETRYABLE) },
                    "Sending" to controller.localDrafts.filter { it.state in setOf(GroupsIoOutboxState.CREATING_REMOTE, GroupsIoOutboxState.UPDATING_REMOTE, GroupsIoOutboxState.UPLOADING, GroupsIoOutboxState.READY_TO_POST, GroupsIoOutboxState.POSTING) },
                    "Needs Attention" to controller.localDrafts.filter { it.state in setOf(GroupsIoOutboxState.NEEDS_ATTENTION, GroupsIoOutboxState.DELIVERY_UNKNOWN) },
                    "Recently Submitted" to controller.localDrafts.filter { it.state in setOf(GroupsIoOutboxState.POSTED, GroupsIoOutboxState.PENDING_MODERATION) },
                )
                groups.forEach { (title, values) ->
                    item { Text(title, fontWeight = FontWeight.Bold) }
                    if (values.isEmpty()) item { Text("None", style = MaterialTheme.typography.bodySmall) }
                    items(values, key = { it.localId }) { value ->
                        val timestamp = groupsIoTimestampText(value.updatedAtMillis)
                        ListItem(headlineContent = { Text(value.subject.ifBlank { "Untitled" }) },
                            supportingContent = { Text("${value.state.wire} · ${timestamp.row}${value.lastAttemptAtMillis?.let { " · attempted ${groupsIoTimestampText(it).row}" }.orEmpty()}${value.lastErrorText?.let { " · $it" }.orEmpty()}") },
                            trailingContent = { TextButton({ controller.openLocalDraft(value) }) { Text("Open") } })
                    }
                }
                item { Text("Server Drafts", fontWeight = FontWeight.Bold) }
                if (controller.serverDrafts.isEmpty()) item { Text("Refresh explicitly to reconcile; local edits are never overwritten.", style = MaterialTheme.typography.bodySmall) }
                items(controller.serverDrafts, key = { it.id }) { value ->
                    ListItem(headlineContent = { Text(value.subject.ifBlank { "Server draft #${value.id}" }) }, supportingContent = { Text("Remote only · ${value.attachmentCount} attachment(s) · ${groupsIoTimestampText(value.updatedAtMillis).row}") })
                }
            }
        },
        confirmButton = { TextButton(controller::closeDraftsOutbox) { Text("Done") } },
    )
}

@Composable
internal fun GroupsIoOfflineDialog(controller: GroupsIoController, dismiss: () -> Unit) {
    val progress = controller.archiveProgress
    var selectedRange by rememberSaveable { mutableIntStateOf(controller.archiveRangeDays ?: -1) }
    val ranges = listOf(30 to "30 days", 90 to "90 days", 365 to "Past year", -1 to "Full history")
    val selectedLabel = ranges.first { it.first == selectedRange }.second
    AlertDialog(onDismissRequest = dismiss, title = { Text("Group Offline Storage") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Only the selected group is downloaded. Choose how much history to keep available offline.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ranges.forEach { (days, label) -> FilterChip(selected = selectedRange == days, onClick = { selectedRange = days }, label = { Text(label) }) }
            }
            Text("Archive state: ${progress.state}")
            Text("Downloaded this run: ${progress.downloaded}${progress.total?.let { "/$it" }.orEmpty()} messages")
            Text("Database/cache: ${controller.storageBytes / 1024} KiB")
            Text("Complete archive pages are applied transactionally and remain available after cancellation.", style = MaterialTheme.typography.bodySmall)
            Text("Official archive ZIP is permission-gated, manual only, and limited by Groups.io to one request per person/group per 24 hours. MBOX content is not parsed.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Column {
            Button({ controller.startArchiveDownload(selectedRange.takeIf { it > 0 }) }, enabled = controller.connected && controller.capabilities?.archivesVisible == true && !controller.busy) {
                Text(if (progress.state in setOf("partial", "paused", "failed") && controller.archiveRangeDays == selectedRange.takeIf { it > 0 }) "Resume $selectedLabel" else "Sync $selectedLabel Offline")
            }
            TextButton(controller::pauseArchiveDownload, enabled = controller.busy) { Text("Pause/Cancel") }
            OutlinedButton(controller::downloadOfficialArchive, enabled = controller.connected && controller.capabilities?.downloadArchives == true && !controller.busy) { Text("Download Official Archive ZIP") }
        } },
        dismissButton = { TextButton(dismiss) { Text("Close") } })
}
