package app.shackcq.mobile.radio.rgoone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

enum class RgoOneSurfaceLayout { COMPACT, STANDARD, WIDE }

data class RgoOneSurfaceModel(
    val layout: RgoOneSurfaceLayout,
    val primaryFrequency: String,
    val secondaryFrequency: String,
    val statusLabels: List<String>,
    val controls: List<RgoOneCapability>,
    val moduleTruth: List<String>,
) {
    companion object {
        fun from(snapshot: RgoOneRadioSnapshot, widthDp: Int, preference: RgoOneLayoutPreference = RgoOneLayoutPreference.AUTO): RgoOneSurfaceModel {
            val layout = when (preference) {
                RgoOneLayoutPreference.COMPACT -> RgoOneSurfaceLayout.COMPACT
                RgoOneLayoutPreference.STANDARD -> RgoOneSurfaceLayout.STANDARD
                RgoOneLayoutPreference.WIDE -> RgoOneSurfaceLayout.WIDE
                RgoOneLayoutPreference.AUTO -> when { widthDp < 560 -> RgoOneSurfaceLayout.COMPACT; widthDp < 900 -> RgoOneSurfaceLayout.STANDARD; else -> RgoOneSurfaceLayout.WIDE }
            }
            val controls = snapshot.capabilities.filterValues { it == RgoOneCapabilityState.SUPPORTED_PRESENT }.keys.sortedBy { it.name }
            val labels = buildList {
                add(snapshot.mode?.name ?: "MODE --")
                add("RX ${snapshot.rxVfo?.name ?: "--"}")
                add("TX ${snapshot.txVfo?.name ?: "--"}")
                add(if (snapshot.split == true) "SPLIT" else "SIMPLEX")
                snapshot.agc?.let { add("AGC ${it.name}") }
                snapshot.filterBandwidthHz?.let { add("BW $it Hz") }
                if (snapshot.stale) add("STALE")
            }
            val moduleTruth = RgoOneModule.entries.map { module -> "$module: ${snapshot.modules[module].state.name.removePrefix("SUPPORTED_")}" }
            return RgoOneSurfaceModel(layout, formatFrequency(snapshot.primaryFrequencyHz), formatFrequency(snapshot.secondaryFrequencyHz), labels, controls, moduleTruth)
        }

        private fun formatFrequency(value: Long?): String = value?.let { String.format(Locale.US, "%03d.%03d.%03d", it / 1_000_000, (it / 1_000) % 1_000, it % 1_000) } ?: "---.---.---"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RgoOneRadioSurface(
    snapshot: RgoOneRadioSnapshot,
    onAction: (RgoOneAction) -> Unit,
    modifier: Modifier = Modifier,
    settings: RgoOneSettingsDocument = RgoOneSettingsDocument(),
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val model = RgoOneSurfaceModel.from(snapshot, maxWidth.value.toInt(), settings.layout)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("RGO ONE ${snapshot.generation.name.replace('_', ' ')}", style = MaterialTheme.typography.titleMedium)
                        Text(model.primaryFrequency, style = when (model.layout) {
                            RgoOneSurfaceLayout.COMPACT -> MaterialTheme.typography.headlineLarge
                            else -> MaterialTheme.typography.displaySmall
                        }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("VFO ${snapshot.rxVfo?.name ?: "-"}", style = MaterialTheme.typography.labelMedium)
                        Text(model.secondaryFrequency, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.statusLabels.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
                MeterStrip(snapshot)
                PrimaryControls(model, snapshot, onAction)
                if (model.layout != RgoOneSurfaceLayout.COMPACT) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        model.moduleTruth.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Text("${snapshot.status} · ${snapshot.firmware?.let { "FW $it" } ?: "firmware unknown"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun MeterStrip(snapshot: RgoOneRadioSnapshot) {
    val meters = buildList {
        snapshot.sMeter?.let { add("S $it/15") }
        snapshot.meters[RgoOneMeter.RF_POWER]?.let { add("PWR $it/15") }
        snapshot.meters[RgoOneMeter.ALC]?.let { add("ALC $it/15") }
        snapshot.meters[RgoOneMeter.SWR]?.let { add("SWR $it/15") }
    }
    if (meters.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { meters.forEach { Text(it, fontWeight = FontWeight.SemiBold) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PrimaryControls(model: RgoOneSurfaceModel, snapshot: RgoOneRadioSnapshot, onAction: (RgoOneAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (RgoOneCapability.RX_VFO in model.controls) {
            OutlinedButton(onClick = { onAction(RgoOneAction.SelectRxVfo(if (snapshot.rxVfo == RgoOneVfo.A) RgoOneVfo.B else RgoOneVfo.A)) }) { Text("A/B") }
        }
        if (RgoOneCapability.SPLIT in model.controls) {
            OutlinedButton(onClick = { onAction(RgoOneAction.SelectTxVfo(if (snapshot.rxVfo == RgoOneVfo.A) RgoOneVfo.B else RgoOneVfo.A)) }) { Text("Split") }
        }
        if (RgoOneCapability.RIT in model.controls) {
            OutlinedButton(onClick = { onAction(RgoOneAction.NudgeRit(false)) }) { Text("RIT -") }
            OutlinedButton(onClick = { onAction(RgoOneAction.ClearRit) }) { Text("RIT 0") }
            OutlinedButton(onClick = { onAction(RgoOneAction.NudgeRit(true)) }) { Text("RIT +") }
        }
        if (RgoOneCapability.TRANSMIT in model.controls) Button(onClick = { onAction(RgoOneAction.Transmit) }) { Text("TX review") }
        if (RgoOneCapability.TUNE in model.controls) OutlinedButton(onClick = { onAction(RgoOneAction.Tune) }) { Text("Tune review") }
        OutlinedButton(onClick = { onAction(RgoOneAction.Receive) }) { Text("RX") }
    }
}
