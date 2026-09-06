package app.shackcq.mobile.keyer

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun keyerStripState(messageId: String, queue: KeyerQueueSnapshot, available: Boolean): String = when {
    queue.active?.messageId == messageId -> "ACTIVE"
    queue.pending?.messageId == messageId -> "PENDING"
    !available -> "UNAVAILABLE"
    else -> "READY"
}

fun keyerStripContentDescription(profile: KeyerProfile, binding: KeyerBinding, message: KeyerMessageTemplate,
    queue: KeyerQueueSnapshot, available: Boolean): String =
    "${binding.chord.label}, ${message.label}, ${message.mode.name}, ${profile.name}, ${profile.role.name}, ${keyerStripState(message.id, queue, available)}"

fun androidFunctionChord(event: AndroidKeyEvent): KeyChord? {
    val function = when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_F1 -> 1; AndroidKeyEvent.KEYCODE_F2 -> 2; AndroidKeyEvent.KEYCODE_F3 -> 3
        AndroidKeyEvent.KEYCODE_F4 -> 4; AndroidKeyEvent.KEYCODE_F5 -> 5; AndroidKeyEvent.KEYCODE_F6 -> 6
        AndroidKeyEvent.KEYCODE_F7 -> 7; AndroidKeyEvent.KEYCODE_F8 -> 8; AndroidKeyEvent.KEYCODE_F9 -> 9
        AndroidKeyEvent.KEYCODE_F10 -> 10; AndroidKeyEvent.KEYCODE_F11 -> 11; AndroidKeyEvent.KEYCODE_F12 -> 12
        else -> return null
    }
    return KeyChord(function, event.isShiftPressed, event.isCtrlPressed, event.isAltPressed)
}

@Composable fun KeyerHotkeyStrip(
    profile: KeyerProfile,
    queue: KeyerQueueSnapshot,
    availability: KeyerAvailability,
    onAction: (KeyerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        profile.bindings.sortedBy { it.chord.label }.forEach { binding ->
            val message = profile.messages.firstOrNull { it.id == binding.action.messageId } ?: return@forEach
            val active = queue.active?.messageId == message.id
            val pending = queue.pending?.messageId == message.id
            val state = keyerStripState(message.id, queue, availability.available)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(if (pending) 2.dp else 1.dp, if (availability.available) MaterialTheme.colorScheme.outline else Color.Gray),
                modifier = Modifier.clickable(enabled = availability.available) { onAction(binding.action) }
                    .semantics { contentDescription = keyerStripContentDescription(profile, binding, message, queue, availability.available) },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(binding.chord.label, fontWeight = FontWeight.Black)
                    Text("${message.label} · ${message.mode.name}", style = MaterialTheme.typography.labelSmall)
                    Text("${profile.name} · ${profile.role.name}", style = MaterialTheme.typography.labelSmall)
                    Text(state, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (profile.bindings.isEmpty()) Text("No keyer hotkeys assigned · ${profile.name}", modifier = Modifier.padding(8.dp))
        if (queue.pendingCount > 0) Text("Queue 1", modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
    }
}
