package app.shackcq.mobile.contest

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable internal fun ContestNetworkScreen(state: ContestWorkspaceState, callbacks: ContestWorkspaceCallbacks) {
    var enabled by remember(state.network.enabled) { mutableStateOf(state.network.enabled) }
    var mode by remember(state.network.mode) { mutableStateOf(state.network.mode) }
    var lan by remember(state.network.lanOptIn) { mutableStateOf(state.network.lanOptIn) }
    var bind by remember(state.network.bindAddress) { mutableStateOf(state.network.bindAddress) }
    Column(Modifier.fillMaxSize().padding(14.dp).semantics { contentDescription = "Contest N1MM network controls" },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("N1MM NETWORK", style = MaterialTheme.typography.headlineSmall)
                Text("${if (state.network.active) "ACTIVE" else "STOPPED"} · ${if (state.network.armed) "ARMED" else "UNARMED"} · ${state.network.nodeIdentity}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(callbacks.onOpenSettings) { Text("SETTINGS") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(enabled, { enabled = it; if (!it) mode = "OFF" })
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) "NETWORK CONFIGURATION ENABLED" else "NETWORK DISABLED", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("Configuration never restores armed. Start/arm is an explicit foreground action.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("OFF", "MONITOR_ONLY", "TRUSTED_LAN_REVIEW", "TRUSTED_LAN_AUTO_ACCEPT_SAFE_ADDS").forEach { value ->
                        FilterChip(mode == value, { mode = value; enabled = value != "OFF" }, { Text(value.replace('_', ' ')) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(if (lan) bind else "127.0.0.1", { bind = it.take(64) }, label = { Text("Bind address") }, enabled = lan,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(state.network.port.toString(), {}, label = { Text("TCP / discovery port") }, enabled = false, modifier = Modifier.weight(.7f))
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(lan, { checked -> lan = checked; if (!checked) bind = "127.0.0.1" })
                    Text("Explicit trusted-LAN bind review")
                }
                Button({ callbacks.onNetworkConfig(enabled, mode, lan, if (lan) bind else "127.0.0.1") }) { Text("SAVE SAFE NETWORK CONFIGURATION") }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(callbacks.onNetworkStart,
                enabled = state.network.enabled && state.network.mode != "OFF" && state.session.state == ContestSessionState.RUNNING && !state.network.armed) {
                Text("START / ARM MONITOR")
            }
            OutlinedButton(callbacks.onNetworkStop, enabled = state.network.armed || state.network.active) { Text("STOP / DISARM") }
            OutlinedButton(callbacks.onTrustedModeReview, enabled = state.network.lanOptIn) { Text("REVIEW TRUSTED LAN") }
        }
        Text("Incoming radio/keyer/time/file/FREQMODE/FUNCTIONKEY/XMIT commands remain blocked. N1MM cannot control Radio, Digi, Keyer or Chaser.",
            color = MaterialTheme.colorScheme.tertiary)
        Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("PEERS & EXACT TRUST", style = MaterialTheme.typography.titleMedium)
                    Text("${state.network.peers.size} peers · trust binds station, operator, interface, contest, rule and observed address",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.network.peers, key = ContestNetworkPeer::station) { peer ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.station, style = MaterialTheme.typography.titleMedium)
                                Text("${peer.address} · ${peer.operatorCall} · ${peer.version}")
                                Text("Contest ${peer.contestName.ifBlank { "UNKNOWN" }} · last ${utc(peer.lastSeen)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column {
                                Switch(peer.trusted, { callbacks.onPeerTrust(peer.station, it) })
                                Text(if (peer.trusted) "TRUSTED" else "REVIEW")
                            }
                        }
                    }
                }
                if (state.network.peers.isEmpty()) item { Text("No peers observed. No trust is inferred.") }
                item {
                    HorizontalDivider()
                    Text("POLICY COUNTERS", style = MaterialTheme.typography.titleMedium)
                    if (state.network.counters.isEmpty()) Text("No retained packets or review decisions")
                    state.network.counters.toSortedMap().forEach { (name, count) -> Text("$name · $count") }
                    Text("Pending review · ${state.network.counters.filterKeys { it.startsWith("TRUSTED_REVIEW") }.values.sum()} · " +
                        "dedup/replay · ${state.network.counters.filterKeys { it.startsWith("DEDUPE") }.values.sum()}")
                    if (state.network.lastError.isNotBlank()) Text("Last sanitized error · ${state.network.lastError}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun utc(epoch: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epoch))
