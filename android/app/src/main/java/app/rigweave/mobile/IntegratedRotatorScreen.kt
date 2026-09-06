package app.rigweave.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rigweave.mobile.rotator.*
import kotlinx.coroutines.launch

private data class PendingRotatorMotion(val action: RotatorAction, val azimuth: Double?, val elevation: Double?)

@Composable
fun IntegratedRotatorScreen(runtime: AndroidRotatorRuntime) {
    val scope = rememberCoroutineScope()
    var selectedId by remember(runtime.profiles) { mutableStateOf(runtime.state?.profileId ?: runtime.profiles.firstOrNull()?.id) }
    var pending by remember { mutableStateOf<PendingRotatorMotion?>(null) }
    var preview by remember { mutableStateOf(false) }
    val profiles = runtime.profiles
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (profiles.isEmpty()) {
            Text("No rotator profile is configured. You can safely preview the complete workspace before adding hardware. Preview controls never connect or move a rotator.")
            Button({ preview = !preview }) { Text(if (preview) "CLOSE UI PREVIEW" else "PREVIEW ROTATOR UI") }
            if (preview) {
                Text("UI PREVIEW · NO ROTATOR · ILLUSTRATIVE POSITION ONLY", color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary)
                RotatorWorkspace(
                    state = RotatorStateSnapshot("preview", "UI PREVIEW · NO DEVICE", RotatorBackend.NATIVE,
                        RotatorProtocolKind.GS232, RotatorTransportKind.SERIAL, connected = false, ready = false,
                        azimuthDeg = 132.0, elevationDeg = 18.0, targetAzimuthDeg = 145.0,
                        movement = RotatorMovementState.IDLE, limits = RotatorLimits()),
                    capabilities = RotatorCapabilitySnapshot(
                        RotatorCapability.entries.associateWith { CapabilitySupport.SUPPORTED }, "UI preview only"),
                    assignment = null,
                    automation = RotatorAutomationSession(),
                    candidates = emptyList(),
                    diagnostics = null,
                    onAction = { _, _, _ -> },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            profiles.forEach { profile -> FilterChip(selectedId == profile.id, { selectedId = profile.id }, { Text(profile.name) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ selectedId?.let { scope.launch { runtime.connect(it, readOnlyProbe = false) } } }, enabled = runtime.state?.connected != true) {
                Text("CONNECT")
            }
            OutlinedButton({ selectedId?.let { scope.launch { runtime.connect(it, readOnlyProbe = true) } } }, enabled = runtime.state?.connected != true) {
                Text("READ-ONLY TEST")
            }
            OutlinedButton({ scope.launch { runtime.disconnect() } }, enabled = runtime.state?.connected == true) { Text("DISCONNECT") }
        }
        RotatorWorkspace(
            state = runtime.state,
            capabilities = runtime.capabilities,
            assignment = runtime.store.snapshot().bandAssignments.firstOrNull { it.rotatorProfileId == runtime.state?.profileId },
            automation = runtime.automation,
            candidates = emptyList(),
            diagnostics = runtime.controller.diagnostics(),
            onAction = { action, azimuth, elevation ->
                if (action == RotatorAction.STOP) scope.launch { runtime.stopAndDisarm() }
                else if (action in setOf(RotatorAction.MOVE_ABSOLUTE, RotatorAction.PARK, RotatorAction.SELECT_PRESET, RotatorAction.JOG)) {
                    pending = PendingRotatorMotion(action, azimuth, elevation)
                } else scope.launch { runtime.submit(action, azimuth, elevation) }
            },
            modifier = Modifier.weight(1f),
        )
        }
    }
    pending?.let { motion ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Confirm physical rotator action") },
            text = { Text("${motion.action}${motion.azimuth?.let { " · %.1f°".format(it) }.orEmpty()} will command the selected physical movement backend. Confirm only when the antenna system is clear.") },
            confirmButton = { Button({
                pending = null
                scope.launch { runtime.submit(motion.action, motion.azimuth, motion.elevation) }
            }) { Text("CONFIRM MOVEMENT") } },
            dismissButton = { TextButton({ pending = null }) { Text("CANCEL") } },
        )
    }
}
