package app.shackcq.mobile.contest

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val contestUtc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

@Composable internal fun ContestSetupScreen(state: ContestWorkspaceState, callbacks: ContestWorkspaceCallbacks) {
    var search by remember { mutableStateOf("") }
    val editable = state.session.state in setOf(ContestSessionState.DRAFT, ContestSessionState.READY, ContestSessionState.STOPPED)
    val definitions = state.definitions.filter {
        search.isBlank() || listOf(it.humanName, it.adifContestId, it.cabrilloContestName).any { value -> value.contains(search, true) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("CONTEST SESSION", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("${state.session.state} · ${state.session.role.name.replace('_', ' ')} · ${state.wavelogBinding}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(callbacks.onNewSession) { Text("NEW") }
            OutlinedButton(callbacks.onCloneSession) { Text("CLONE") }
            OutlinedButton(callbacks.onOpenSettings) { Text("SETTINGS") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(search, { search = it.take(80) }, label = { Text("Find contest") },
                singleLine = true, modifier = Modifier.widthIn(min = 250.dp, max = 360.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                definitions.forEach { definition ->
                    FilterChip(state.definition.id == definition.id, { callbacks.onDefinition(definition.id) },
                        { Text(definition.humanName) }, enabled = editable)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.definition.humanName, style = MaterialTheme.typography.titleLarge)
                Text("ADIF ${state.definition.adifContestId} · Cabrillo ${state.definition.cabrilloContestName}")
                Text("Rules ${state.definition.version.value} · ${state.definition.officialSources.joinToString { it.edition }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.definition.officialSources.forEach { Text(it.url, style = MaterialTheme.typography.bodySmall) }
                if (state.definition.ambiguities.isNotEmpty()) Text(state.definition.ambiguities.joinToString(" · "), color = MaterialTheme.colorScheme.tertiary)
            }
        }

        OutlinedTextField(state.session.name, { callbacks.onSession(state.session.copy(name = it.take(80))) },
            label = { Text("Session name") }, enabled = editable, colors = contestFieldColors(), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.session.stationCallsign, { callbacks.onSession(state.session.copy(stationCallsign = it.uppercase().take(24))) },
                label = { Text("Station callsign") }, enabled = editable, colors = contestFieldColors(), modifier = Modifier.weight(1f))
            OutlinedTextField(state.session.stationGrid, { callbacks.onSession(state.session.copy(stationGrid = it.uppercase().take(10))) },
                label = { Text("Station grid") }, enabled = editable, colors = contestFieldColors(), modifier = Modifier.weight(1f))
            OutlinedTextField(state.session.operators.joinToString(","), { value ->
                callbacks.onSession(state.session.copy(operators = value.split(',').map(String::trim).filter(String::isNotBlank).map(String::uppercase)))
            }, label = { Text("Operator callsign(s)") }, enabled = editable, colors = contestFieldColors(), modifier = Modifier.weight(1.2f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.session.utcStart.toString(), { it.toLongOrNull()?.let { epoch -> callbacks.onSession(state.session.copy(utcStart = epoch)) } },
                label = { Text("UTC start epoch") }, supportingText = { Text(contestUtc.format(Instant.ofEpochSecond(state.session.utcStart))) },
                enabled = editable, colors = contestFieldColors(), modifier = Modifier.weight(1f))
            OutlinedTextField(state.session.utcEnd.toString(), { it.toLongOrNull()?.let { epoch -> callbacks.onSession(state.session.copy(utcEnd = epoch)) } },
                label = { Text("UTC end epoch") }, supportingText = { Text(contestUtc.format(Instant.ofEpochSecond(state.session.utcEnd))) },
                enabled = editable, colors = contestFieldColors(), modifier = Modifier.weight(1f))
            OutlinedTextField(state.session.initialSerial.toString(), { it.toIntOrNull()?.let { serial -> callbacks.onSession(state.session.copy(initialSerial = serial.coerceAtLeast(1))) } },
                label = { Text("Initial serial") }, enabled = editable && state.definition.serialRequired, colors = contestFieldColors(), modifier = Modifier.weight(.7f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ContestChoice("OPERATOR CLASS", listOf("SINGLE-OP", "MULTI-OP"), state.session.category.operator, editable, Modifier.weight(1f)) {
                callbacks.onSession(state.session.copy(category = state.session.category.copy(operator = it)))
            }
            ContestChoice("ASSISTANCE", listOf("NON-ASSISTED", "ASSISTED"), state.session.category.assisted, editable, Modifier.weight(1f)) {
                callbacks.onSession(state.session.copy(category = state.session.category.copy(assisted = it)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ContestChoice("BAND", listOf("ALL") + state.definition.allowedBands.map(ContestBand::label), state.session.category.band, editable, Modifier.weight(1f)) {
                callbacks.onSession(state.session.copy(category = state.session.category.copy(band = it)))
            }
            ContestChoice("MODE", state.definition.allowedModes.map(ContestMode::name), state.session.category.mode.name, editable, Modifier.weight(1f)) {
                callbacks.onSession(state.session.copy(category = state.session.category.copy(mode = ContestMode.valueOf(it))))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ContestChoice("POWER", listOf("QRP", "LOW", "HIGH"), state.session.category.power, editable, Modifier.weight(1f)) {
                callbacks.onSession(state.session.copy(category = state.session.category.copy(power = it)))
            }
            ContestChoice("STATION / TRANSMITTER", listOf("FIXED/ONE", "FIXED/TWO", "MOBILE/ONE", "PORTABLE/ONE"),
                "${state.session.category.station}/${state.session.category.transmitter}", editable, Modifier.weight(1f)) { value ->
                val parts = value.split('/')
                callbacks.onSession(state.session.copy(category = state.session.category.copy(station = parts[0], transmitter = parts[1])))
            }
        }
        if (state.definition.family == ContestRuleFamily.ARRL_FIELD_DAY) {
            OutlinedTextField(state.session.category.overlay, { callbacks.onSession(state.session.copy(category = state.session.category.copy(overlay = it.uppercase().take(24)))) },
                label = { Text("Overlay / class where supported") }, enabled = editable, colors = contestFieldColors(), modifier = Modifier.fillMaxWidth())
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("EXCHANGE CONTRACT", style = MaterialTheme.typography.titleMedium)
                Text("Sent preview · " + state.definition.sentExchange.joinToString { field ->
                    if (field == ContestExchangeField.SERIAL) "SERIAL ${state.session.initialSerial}" else field.name.replace('_', ' ')
                })
                Text("Expected received · ${state.definition.receivedExchange.joinToString { it.name.replace('_', ' ') }}")
                Text("Run/S&P default · ${state.session.role.name.replace('_', ' ')}")
                Text("N1MM default OFF / loopback · starting this session never transmits", color = MaterialTheme.colorScheme.tertiary)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SUPER CHECK PARTIAL", style = MaterialTheme.typography.titleMedium)
                Text(if (state.scpStatus.available) "READY · ${state.scpStatus.rowCount} calls · ${state.scpStatus.generatedAt.ifBlank { "generation unavailable" }}"
                    else "DATABASE UNAVAILABLE · absence never makes a callsign invalid")
                if (state.scpStatus.sha256.isNotBlank()) Text("SHA-256 ${state.scpStatus.sha256.take(16)}… · private offline last-good",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Official runtime download only · conditional cache · no bundled database · no Cabrillo upload",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(callbacks.onRefreshScp) { Text("REFRESH SCP.DB") }
                    TextButton(callbacks.onDeleteScp, enabled = state.scpStatus.available) { Text("DELETE CACHE") }
                }
            }
        }

        state.validation.forEach { issue ->
            AssistChip({}, { Text("${issue.truth}: ${issue.reason}") },
                colors = AssistChipDefaults.assistChipColors(labelColor = if (issue.truth == ContestTruth.INVALID || issue.truth == ContestTruth.INCOMPLETE)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(callbacks.onSaveSession, enabled = editable) { Text("SAVE") }
            Button(callbacks.onStartSession,
                enabled = state.validation.none { it.truth == ContestTruth.INVALID || it.truth == ContestTruth.INCOMPLETE } &&
                    state.session.state !in setOf(ContestSessionState.RUNNING, ContestSessionState.CLOSED)) {
                Text(if (state.session.state == ContestSessionState.PAUSED) "RESUME" else "START WITHOUT TRANSMITTING")
            }
            OutlinedButton(callbacks.onPauseSession, enabled = state.session.state == ContestSessionState.RUNNING) { Text("PAUSE") }
            OutlinedButton(callbacks.onCloseSession, enabled = state.session.state !in setOf(ContestSessionState.RUNNING, ContestSessionState.CLOSED)) { Text("CLOSE") }
        }
        Text(state.statusMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ContestChoice(label: String, values: List<String>, selected: String, enabled: Boolean,
    modifier: Modifier = Modifier, choose: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            values.distinct().forEach { value -> FilterChip(selected == value, { choose(value) }, { Text(value.replace('_', ' ')) }, enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .78f),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f))) }
        }
    }
}

@Composable private fun contestFieldColors() = OutlinedTextFieldDefaults.colors(
    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .90f),
    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = .80f),
    disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .82f),
)
