package app.shackcq.mobile.keyer

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable fun KeyerSettingsUi(store: KeyerProfileStore, repeatCq: RepeatCqController,
    onPreviewVoicePlan: (VoiceMacroPlan) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    var captureMessage by remember { mutableStateOf<KeyerMessageTemplate?>(null) }
    var conflict by remember { mutableStateOf<Pair<KeyerBinding, KeyerBinding>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var transferText by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    val profile = store.activeProfile()
    var repeatMessageId by remember(profile.id) { mutableStateOf(profile.messages.firstOrNull()?.id.orEmpty()) }
    var repeatClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (captureMessage != null) LaunchedEffect(captureMessage) { requester.requestFocus() }
    if (repeatCq.state.active) LaunchedEffect(repeatCq.state.active) {
        while (repeatCq.state.active) { repeatClock = System.currentTimeMillis(); delay(500) }
    }

    conflict?.let { (existing, candidate) -> AlertDialog(
        onDismissRequest = { conflict = null },
        title = { Text("Hotkey conflict") },
        text = { Text("${candidate.chord.label} is already assigned. Swap the two message bindings or cancel?") },
        confirmButton = { Button({
            val candidateId = candidate.action.messageId
            val existingId = existing.action.messageId
            val updated = profile.bindings.mapNotNull { binding -> when (binding) {
                existing -> candidate
                else -> if (binding.action.messageId == candidateId && existingId != null) binding.copy(action = KeyerAction.SendMessage(existingId)) else binding
            } }
            store.replaceProfile(profile.copy(bindings = updated)); conflict = null
        }) { Text("SWAP") } },
        dismissButton = { TextButton({ conflict = null }) { Text("CANCEL") } },
    ) }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, title = { Text("Reset ${profile.name}?") },
        text = { Text("Only this logical profile will return to its migration baseline. Voice recordings are not deleted.") },
        confirmButton = { Button({ store.resetProfile(profile.id, confirmed = true); confirmReset = false }) { Text("RESET PROFILE") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("CANCEL") } },
    )

    Card(modifier.fillMaxWidth().focusRequester(requester).focusable().onPreviewKeyEvent { event ->
        val message = captureMessage ?: return@onPreviewKeyEvent false
        if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
        val chord = androidFunctionChord(event.nativeKeyEvent) ?: return@onPreviewKeyEvent false
        val candidate = KeyerBinding(chord, KeyerAction.SendMessage(message.id))
        val existing = KeyerHotkeyDispatcher.conflict(profile.bindings, candidate)
        if (existing != null) conflict = existing to candidate else {
            val updated = profile.bindings.filterNot { it.action.messageId == message.id } + candidate
            store.replaceProfile(profile.copy(bindings = updated))
        }
        captureMessage = null
        true
    }.semantics { contentDescription = "Keyer and physical hotkey settings" }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("KEYER & HOTKEYS")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Enable physical-keyboard hotkeys"); Text("Off by default; foreground only") }
                Switch(store.hotkeysEnabled, store::updateHotkeysEnabled)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show keyer strip"); Switch(store.showStrip, store::updateShowStrip)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fallback to General profile"); Switch(store.fallbackToGeneral, store::updateFallbackToGeneral)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                store.profiles.forEach { item -> FilterChip(item.id == store.activeProfileId, { store.activate(item.id) }, { Text(item.name) }) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(profile.name, { store.renameProfile(profile.id, it) }, label = { Text("Profile name") },
                    singleLine = true, modifier = Modifier.weight(1f))
                val profileIndex = store.profiles.indexOfFirst { it.id == profile.id }
                OutlinedButton({ store.moveProfile(profile.id, -1) }, enabled = profileIndex > 0) { Text("LEFT") }
                OutlinedButton({ store.moveProfile(profile.id, 1) }, enabled = profileIndex in 0 until store.profiles.lastIndex) { Text("RIGHT") }
            }
            Text("Active profile · ${profile.mode.name}")
            profile.messages.forEachIndexed { index, message ->
                val binding = profile.bindings.firstOrNull { it.action.messageId == message.id }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(message.label, { value -> store.replaceProfile(profile.copy(messages = profile.messages.map {
                            if (it.id == message.id) it.copy(label = value.filterNot(Char::isISOControl).take(40)) else it })) },
                            label = { Text("Message label") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedButton({ captureMessage = message }) { Text(if (captureMessage?.id == message.id) "PRESS F-KEY" else binding?.chord?.label ?: "ASSIGN") }
                        if (binding != null) TextButton({ store.replaceProfile(profile.copy(bindings = profile.bindings - binding)) }) { Text("CLEAR") }
                    }
                    if (message.mode == KeyerMode.CW) OutlinedTextField(message.template, { value -> store.replaceProfile(profile.copy(messages = profile.messages.map {
                        if (it.id == message.id) it.copy(template = value.take(256)) else it })) }, label = { Text("CW template · resolved preview validates at dispatch") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    else {
                        Text(message.voicePlan?.slotIds?.joinToString(prefix = "Plan slots · ") { "M${it + 1}" } ?: "Plan is empty")
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            repeat(6) { slot -> OutlinedButton({
                                val slots = message.voicePlan?.slotIds.orEmpty() + slot
                                if (slots.size <= 12) store.replaceProfile(profile.copy(messages = profile.messages.map {
                                    if (it.id == message.id) it.copy(voicePlan = VoiceMacroPlan(slots,
                                        message.voicePlan?.interClipSilenceMillis ?: 80)) else it }))
                            }) { Text("+M${slot + 1}") } }
                            OutlinedButton({ val plan = message.voicePlan; plan?.slotIds?.dropLast(1)?.takeIf(List<Int>::isNotEmpty)?.let { slots ->
                                store.replaceProfile(profile.copy(messages = profile.messages.map {
                                    if (it.id == message.id) it.copy(voicePlan = plan.copy(slotIds = slots)) else it }))
                            } }) { Text("REMOVE LAST") }
                            OutlinedButton({ message.voicePlan?.let(onPreviewVoicePlan) },
                                enabled = message.voicePlan?.slotIds?.isNotEmpty() == true) { Text("PREVIEW PLAN") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedButton({ if (index > 0) store.replaceProfile(profile.copy(messages = profile.messages.toMutableList().apply {
                            add(index - 1, removeAt(index)) })) }, enabled = index > 0) { Text("UP") }
                        OutlinedButton({ if (index < profile.messages.lastIndex) store.replaceProfile(profile.copy(messages = profile.messages.toMutableList().apply {
                            add(index + 1, removeAt(index)) })) }, enabled = index < profile.messages.lastIndex) { Text("DOWN") }
                        OutlinedButton({ if (profile.messages.size < 12) store.replaceProfile(profile.copy(messages = profile.messages.toMutableList().apply {
                            add(index + 1, message.copy(id = "${message.id}-copy-${profile.messages.size}", label = "${message.label} copy".take(40))) })) },
                            enabled = profile.messages.size < 12) { Text("DUPLICATE") }
                    }
                }
                }
            }
            Text("REPEAT CQ")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                profile.messages.forEach { message -> FilterChip(repeatMessageId == message.id, { repeatMessageId = message.id }, { Text(message.label.ifBlank { message.id }) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Interval ${store.repeatIntervalSeconds}s")
                Slider(store.repeatIntervalSeconds.toFloat(), { store.updateRepeatLimits(it.toInt(), store.repeatMaximumCycles, store.repeatMaximumMinutes) },
                    valueRange = 2f..600f, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cycles ${store.repeatMaximumCycles}")
                Slider(store.repeatMaximumCycles.toFloat(), { store.updateRepeatLimits(store.repeatIntervalSeconds, it.toInt(), store.repeatMaximumMinutes) },
                    valueRange = 1f..50f, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Maximum ${store.repeatMaximumMinutes} min")
                Slider(store.repeatMaximumMinutes.toFloat(), { store.updateRepeatLimits(store.repeatIntervalSeconds, store.repeatMaximumCycles, it.toInt()) },
                    valueRange = 1f..30f, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Stop when operator begins text entry")
                Switch(store.repeatStopsOnInput, store::updateRepeatStopsOnInput)
            }
            if (repeatCq.state.active) {
                val nextSeconds = ((repeatCq.state.nextAt - repeatClock).coerceAtLeast(0) + 999) / 1_000
                Text("Repeat active · cycle ${repeatCq.state.cycle}/${store.repeatMaximumCycles} · next in ${nextSeconds}s")
                Button(repeatCq::stop) { Text("STOP REPEAT") }
            } else Button({ repeatCq.start(repeatMessageId,
                RepeatCqLimits(store.repeatIntervalSeconds, store.repeatMaximumCycles, store.repeatMaximumMinutes)) },
                enabled = repeatMessageId.isNotBlank()) { Text("START REPEAT") }
            Text("IMPORT / EXPORT / RESET")
            OutlinedTextField(transferText, { transferText = it.take(200_000) }, label = { Text("Keyer text settings") },
                minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ transferText = store.exportText() }) { Text("EXPORT TO TEXT") }
                OutlinedButton({ store.importText(transferText) }, enabled = transferText.isNotBlank()) { Text("IMPORT TEXT") }
                OutlinedButton({ confirmReset = true }) { Text("RESET PROFILE") }
            }
            Button(onStop) { Text("STOP / FORCE RX") }
            Text("Escape is reserved for immediate stop. Function-key repeats, key-up events, background input and text-entry focus are ignored.")
            if (store.status.isNotBlank()) Text(store.status)
        }
    }
}
