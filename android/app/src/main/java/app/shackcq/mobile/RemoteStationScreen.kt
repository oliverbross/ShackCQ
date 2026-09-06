// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

@Composable
fun RemoteStationScreen(
    app: AppController,
    runtime: RemoteRuntimeState,
    factory: RemoteStationBackendFactory,
    selectAndConnect: (RemoteStationProfile) -> Unit,
    disconnect: () -> Unit,
    globalStop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { RemoteStationDiscovery(context) }
    val store = remember { RemoteStationStore(context) }
    var discovered by remember { mutableStateOf<List<RemoteDiscoveryResult>>(emptyList()) }
    var pairingText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Remote restore is disconnected, read-only, and TX disarmed") }
    var pairingBusy by remember { mutableStateOf(false) }
    DisposableEffect(discovery) { onDispose { discovery.close() } }
    val state = runtime.snapshot
    val debugLab = remember { DebugRemoteLabV6() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("REMOTE STATIONS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(if (state.stationName != null) "REMOTE · ${state.stationName}" else "REMOTE · DISCONNECTED",
            color = if (state.state == RemoteConnectionState.READY) Color(0xFF42C77B) else Color(0xFFF4C94E), fontWeight = FontWeight.Bold)
        Text("${state.state} · ${state.role} · writer ${state.writerLease} · TX ${state.txLease} · rotator ${state.rotatorLease}")
        Text("RTT ${state.rttMillis?.let { "$it ms" } ?: "unavailable"} · audio ${state.audioSequence} · spectrum ${state.spectrumSequence} · drops ${state.droppedFrames}")
        if (state.radioRoster.isNotEmpty()) Text("RADIOS · ${state.radioRoster.joinToString(" · ")}", color = Color(0xFFA5ADB2))
        state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(globalStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("GLOBAL STOP") }
            OutlinedButton(disconnect) { Text("DISCONNECT") }
        }
        if (state.state == RemoteConnectionState.READY && state.role != RemoteRole.OBSERVER) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button({ scope.launch { message = if (factory.acquireWriter()) "Writer lease acquired" else "Writer lease denied" } }) { Text("WRITER") }
                OutlinedButton({ scope.launch { message = if (factory.acquireTransmit()) "TX lease acquired" else "TX lease blocked by station policy/acceptance" } }) { Text("TX LEASE") }
                OutlinedButton({ scope.launch { message = if (factory.acquireRotator()) "Rotator lease acquired" else "Rotator lease blocked by station policy/acceptance" } }) { Text("ROTATOR") }
            }
        }
        Text(message, color = Color(0xFFA5ADB2))
        HorizontalDivider()
        Text("PAIRED STATIONS", fontWeight = FontWeight.Bold)
        app.remoteStationProfiles.forEach { station ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(station.name, fontWeight = FontWeight.Bold)
                    Text("${station.role} · ${station.host}:${station.port} · certificate ${station.certificateSha256.take(12)}…")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ selectAndConnect(station) }) { Text("CONNECT") }
                        TextButton({ app.selectRadioProfile(station.radioProfile()) }) { Text("SELECT") }
                        TextButton({ if (app.forgetRemoteStation(station.stationId)) message = "Station forgotten; its device key remains in Android Keystore" else message = "Disconnect and select another radio before forgetting" }) { Text("FORGET") }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("PAIR BY QR / TEXT", fontWeight = FontWeight.Bold)
        OutlinedTextField(pairingText, { pairingText = it.take(16_384) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Pairing offer JSON") }, minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val offer = PairingOfferV1.parse(pairingText)
                if (offer == null) { message = "Invalid or expired pairing offer"; return@Button }
                pairingBusy = true
                scope.launch {
                    val accepted = withContext(Dispatchers.IO) { RemotePairingClient().request(offer, store.deviceId) }
                    pairingBusy = false
                    if (!accepted) message = "Pairing request rejected or certificate mismatch"
                    else {
                        val uri = URI(offer.endpoint)
                        val profile = RemoteStationProfile(offer.stationId, offer.stationName, uri.host,
                            if (uri.port > 0) uri.port else 7443, offer.certificateSha256, store.deviceId, offer.defaultRole)
                        app.upsertRemoteStation(profile)
                        message = "Pairing request submitted · approve this device locally at the station, then connect"
                    }
                }
            }, enabled = !pairingBusy) { Text(if (pairingBusy) "PAIRING…" else "REQUEST PAIRING") }
            OutlinedButton({ pairingText = "" }) { Text("CLEAR") }
        }
        Text("Pairing uses an exact SHA-256 station-certificate pin and an Android Keystore P-256 signed challenge. Password-only and trust-all connections are unavailable.")
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                discovery.start({ result -> if (discovered.none { it.host == result.host && it.port == result.port }) discovered = (discovered + result).take(16) },
                    { error -> message = error })
                message = "Bounded LAN discovery started · discovery never connects"
            }) { Text("DISCOVER LAN") }
            OutlinedButton({ discovery.close(); message = "LAN discovery stopped" }) { Text("STOP DISCOVERY") }
        }
        discovered.forEach { row -> FilterChip(false, { message = "Discovered ${row.name} at ${row.host}:${row.port}; import a fingerprint-bearing pairing offer before connecting" },
            { Text("${row.name} · ${row.host}:${row.port}") }) }
        Text("Manual/VPN/direct-host connections are created only from a fingerprint-bearing pairing offer. Cleartext WebSockets and a trust-all option are not provided.", color = Color(0xFFA5ADB2))
        if (BuildConfig.DEBUG) {
            HorizontalDivider()
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF251D12))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(debugLab.snapshot.banner, color = Color(0xFFF4C94E), fontWeight = FontWeight.Black)
                    Text("DEBUG REMOTE LAB V6", fontWeight = FontWeight.Bold)
                    Text(debugLab.snapshot.station); Text(debugLab.snapshot.clients)
                    Text(debugLab.snapshot.media); Text(debugLab.snapshot.leases)
                    Text(debugLab.snapshot.fault, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(debugLab::seed) { Text("RESET") }
                        OutlinedButton(debugLab::acquireWriter) { Text("WRITER") }
                        OutlinedButton(debugLab::exerciseLeases) { Text("LEASES") }
                        OutlinedButton(debugLab::injectNetworkLoss) { Text("LOSS") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(debugLab::injectCertificateMismatch) { Text("CERT ERROR") }
                        OutlinedButton(debugLab::revokeOperator) { Text("REVOKE") }
                        Button(debugLab::globalStop) { Text("GLOBAL STOP") }
                    }
                    Text("Synthetic TCI/rigctld clients and media are fixtures only. This lab cannot open an external network, key PTT, Tune, move a rotator, or claim RF acceptance.")
                }
            }
        }
    }
}
