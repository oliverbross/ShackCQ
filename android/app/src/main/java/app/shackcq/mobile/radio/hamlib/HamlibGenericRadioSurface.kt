package app.shackcq.mobile.radio.hamlib

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HamlibGenericRadioSurface(
    model: HamlibModelDescriptor,
    snapshot: HamlibRadioSnapshot?,
    connected: Boolean,
    readOnly: Boolean,
    onAction: (HamlibAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var frequency by remember(snapshot?.frequencyHz) {
        mutableStateOf(snapshot?.frequencyHz?.toString().orEmpty())
    }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(model.label, style = MaterialTheme.typography.titleLarge)
        Text("${model.backend} · ${model.status} · ${model.portType}", style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (connected) "Connected" else "Disconnected")
                OutlinedTextField(
                    value = frequency,
                    onValueChange = { frequency = it.filter(Char::isDigit).take(14) },
                    label = { Text("Frequency (Hz)") },
                    enabled = connected && !readOnly,
                    trailingIcon = {
                        Button(onClick = {
                            frequency.toLongOrNull()?.let { onAction(HamlibAction.SetFrequency(it)) }
                        }, enabled = connected && !readOnly && frequency.toLongOrNull() != null) { Text("Set") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.capabilities.modes.take(12).forEach { mode ->
                        FilterChip(
                            selected = snapshot?.mode == mode,
                            onClick = { onAction(HamlibAction.SetMode(mode)) },
                            label = { Text(mode) }, enabled = connected && !readOnly,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.capabilities.vfos.take(4).forEach { vfo ->
                        FilterChip(snapshot?.vfo == vfo, { onAction(HamlibAction.SetVfo(vfo)) }, { Text(vfo) },
                            enabled = connected && !readOnly)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            model.capabilities.readableLevels.take(12).forEach { level ->
                AssistChip(onClick = {}, label = { Text("$level ${snapshot?.levels?.get(level)?.formatLevel() ?: "—"}") })
            }
        }
        model.capabilities.writableLevels.take(4).forEach { level ->
            val value = snapshot?.levels?.get(level)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
            Column(Modifier.widthIn(max = 520.dp)) {
                Text(level, style = MaterialTheme.typography.labelMedium)
                Slider(value, { onAction(HamlibAction.SetLevel(level, it.toDouble())) },
                    enabled = connected && !readOnly)
            }
        }
        if (model.capabilities.pttType != 0) {
            Text("PTT is a separate transmit action and is never automatic.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun HamlibModelSelector(
    registry: HamlibModelRegistry,
    favorites: Set<Int>,
    recents: List<Int>,
    onSelect: (HamlibModelDescriptor) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val priority = (favorites + recents).withIndex().associate { it.value to it.index }
    val models = registry.search(query).sortedWith(compareBy({ priority[it.id] ?: Int.MAX_VALUE }, { it.label }))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(80) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search radios") },
        )
        models.take(100).forEach { model ->
            AssistChip(onClick = { onSelect(model) }, label = { Text("${model.label} · ${model.backend}") })
        }
    }
}

private fun Double.formatLevel() = if (kotlin.math.abs(this) >= 10) "%.0f".format(this) else "%.2f".format(this)
