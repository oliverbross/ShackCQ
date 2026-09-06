package app.shackcq.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun IntegratedRadioPlatformScreen(
    profile: RadioConnectionProfile,
    snapshot: RadioRuntimeSnapshot,
    detail: String,
    connect: () -> Unit,
    disconnect: () -> Unit,
    dispatch: (RadioPlatformAction) -> Unit,
) {
    var frequency by remember(profile.id) { mutableStateOf("") }
    var mode by remember(profile.id) { mutableStateOf("") }
    val available = snapshot.connected
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(profile.name.uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text("${profile.manufacturer} · ${profile.model} · ${profile.backendKind.name}", color = Color(0xFFA5ADB2))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (available) "CONNECTED" else "DISCONNECTED", fontWeight = FontWeight.Bold,
                    color = if (available) Color(0xFF42C77B) else Color(0xFFE4544D))
                Text(snapshot.vfoAHz.value?.let(::formatRadioFrequency) ?: "—.———", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.displaySmall)
                Text("Mode ${snapshot.mode.value ?: "UNKNOWN"} · Split ${snapshot.split.value?.toString() ?: "UNKNOWN"}")
                Text(detail, color = Color(0xFFA5ADB2))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(connect, enabled = !available, modifier = Modifier.heightIn(min = 48.dp)) { Text("CONNECT") }
            OutlinedButton(disconnect, enabled = available, modifier = Modifier.heightIn(min = 48.dp)) { Text("DISCONNECT") }
        }
        if (profile.readOnly) Text("READ-ONLY PROFILE · all set, transmit, tune and memory-write actions are blocked.",
            color = Color(0xFFE9A72B), fontWeight = FontWeight.Bold)
        OutlinedTextField(frequency, { frequency = it.filter(Char::isDigit).take(14) }, label = { Text("VFO A frequency (Hz)") },
            enabled = available && !profile.readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ frequency.toLongOrNull()?.let { dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = it)) } },
            enabled = available && !profile.readOnly && frequency.toLongOrNull() != null) { Text("SET FREQUENCY") }
        OutlinedTextField(mode, { mode = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(16) },
            label = { Text("Mode") }, enabled = available && !profile.readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ mode.takeIf(String::isNotBlank)?.let { dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "mode", textValue = it)) } },
            enabled = available && !profile.readOnly && mode.isNotBlank()) { Text("SET MODE") }
        Text("PTT, TUNE and memory writes require a separate fresh confirmation and are not exposed on this generic surface.",
            color = Color(0xFFA5ADB2))
    }
}
