package app.shackcq.mobile.rotator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class RotatorWorkspaceTab { Operate, Targets, Presets, BandPolicies, Satellite, Devices, Diagnostics }

@Composable
fun RotatorWorkspace(
    state: RotatorStateSnapshot?,
    capabilities: RotatorCapabilitySnapshot,
    assignment: RotatorBandAssignment?,
    automation: RotatorAutomationSession,
    candidates: List<RotatorTargetIntent>,
    diagnostics: RotatorDiagnosticsSnapshot?,
    onAction: (RotatorAction, Double?, Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(RotatorWorkspaceTab.Operate) }
    var heading by remember { mutableStateOf("") }
    var elevation by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Rotator", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        RotatorStatus(state, assignment, automation)
        Button(
            onClick = { onAction(RotatorAction.STOP, null, null) },
            enabled = state?.connected == true && capabilities.supports(RotatorCapability.STOP),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("rotator-stop")
                .semantics { contentDescription = "Stop rotator; confirmation remains pending until position reports stopped" },
        ) { Text("STOP", fontWeight = FontWeight.Bold) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RotatorWorkspaceTab.entries.forEach { item -> FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item.name) }) }
        }
        when (tab) {
            RotatorWorkspaceTab.Operate -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 700.dp
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RotatorCompass(state, Modifier.weight(1f)); OperateControls(state, capabilities, heading, elevation,
                        { heading = it }, { elevation = it }, onAction, Modifier.weight(1f))
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { RotatorCompass(state, Modifier.fillMaxWidth().height(280.dp)) }
                    item { OperateControls(state, capabilities, heading, elevation, { heading = it }, { elevation = it }, onAction) }
                }
            }
            RotatorWorkspaceTab.Targets -> LazyColumn { items(candidates.size) { index ->
                val target = candidates[index]; ListItem(headlineContent = { Text(target.callsign ?: target.source.name) },
                    supportingContent = { Text("${"%.1f".format(target.shortPathAzimuthDeg)}° short / ${"%.1f".format(target.longPathAzimuthDeg)}° long — viewing does not move hardware") })
            } }
            RotatorWorkspaceTab.Presets -> Text("Mechanical presets are profile-scoped and require an explicit Rotate review.")
            RotatorWorkspaceTab.BandPolicies -> Text(assignment?.let { "${it.bandId}: ${it.policy} · ${it.headingMode} · offset ${it.offsetDeg}° · TX ${it.txPolicy}" } ?: "No band policy")
            RotatorWorkspaceTab.Satellite -> Text("Tracking requires a selected pass, compatible az/el profile, limits review, and explicit Start.")
            RotatorWorkspaceTab.Devices -> Text("Profile tests are read-only. USB permission and central discovery are supplied by the later integration adapter.")
            RotatorWorkspaceTab.Diagnostics -> Text(diagnostics?.let { "Commands ${it.commands} · responses ${it.responses} · timeouts ${it.timeouts}\nSettings ${it.settingsDigest}" } ?: "No diagnostics")
        }
    }
}

@Composable private fun RotatorStatus(state: RotatorStateSnapshot?, assignment: RotatorBandAssignment?, automation: RotatorAutomationSession) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(state?.displayName ?: "No active device", fontWeight = FontWeight.SemiBold)
        Text(state?.let { "${it.backend} · ${it.protocol} · ${it.transport}" } ?: "Disconnected")
        Text(state?.let { "Az ${it.azimuthDeg?.let { v -> "%.1f°".format(v) } ?: "unknown"}${it.elevationDeg?.let { v -> " · El %.1f°".format(v) } ?: ""} · ${it.movement}" } ?: "Position unknown")
        Text("Policy ${assignment?.policy ?: RotatorBandPolicy.OFF} · automation ${if (automation.armed) "ARMED" else "off"}")
        state?.lastSanitizedError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }
}

@Composable private fun OperateControls(state: RotatorStateSnapshot?, capabilities: RotatorCapabilitySnapshot,
    heading: String, elevation: String, onHeading: (String) -> Unit, onElevation: (String) -> Unit,
    onAction: (RotatorAction, Double?, Double?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(heading, onHeading, label = { Text("Heading") }, singleLine = true)
        if (capabilities.supports(RotatorCapability.ELEVATION)) OutlinedTextField(elevation, onElevation, label = { Text("Elevation") }, singleLine = true,
            modifier = Modifier.testTag("rotator-elevation"))
        Button(onClick = { onAction(RotatorAction.MOVE_ABSOLUTE, heading.toDoubleOrNull(), elevation.toDoubleOrNull()) },
            enabled = state?.ready == true && heading.toDoubleOrNull() != null) { Text("Rotate review") }
        OutlinedButton(onClick = { onAction(RotatorAction.PARK, null, null) },
            enabled = state?.connected == true && capabilities.supports(RotatorCapability.PARK)) { Text("Park") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, value) ->
                OutlinedButton(onClick = { onHeading(value.toString()) }) { Text(label) }
            }
        }
        Text("Manual movement disarms automation. Remote TCP control can move physical machinery.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun RotatorCompass(state: RotatorStateSnapshot?, modifier: Modifier = Modifier) {
    val current = state?.azimuthDeg
    val target = state?.targetAzimuthDeg
    val outlineColor = MaterialTheme.colorScheme.outline
    val currentColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.semantics { contentDescription = "Rotator compass; current ${current?.let { "%.1f degrees".format(it) } ?: "unknown"}; target ${target?.let { "%.1f degrees".format(it) } ?: "none"}" }) {
        val radius = min(size.width, size.height) * 0.42f; val center = Offset(size.width / 2, size.height / 2)
        drawCircle(outlineColor, radius, center, style = Stroke(2.dp.toPx()))
        for (degree in 0 until 360 step 30) {
            val angle = Math.toRadians(degree - 90.0); val outer = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
            val inner = Offset(center.x + cos(angle).toFloat() * radius * .9f, center.y + sin(angle).toFloat() * radius * .9f)
            drawLine(outlineColor, inner, outer, 2.dp.toPx())
        }
        current?.let { drawNeedle(center, radius * .78f, it, currentColor, 5.dp.toPx()) }
        target?.let { drawNeedle(center, radius * .9f, it, targetColor, 3.dp.toPx()) }
        if (current == null) drawCircle(Color.Gray, 8.dp.toPx(), center)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeedle(center: Offset, radius: Float, degrees: Double, color: Color, width: Float) {
    val angle = Math.toRadians(degrees - 90.0)
    drawLine(color, center, Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius), width)
}
