package app.shackcq.mobile.dxchaser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val ChaserBackground = Color(0xFF071015)
private val ChaserPanel = Color(0xFF101C22)
private val ChaserCyan = Color(0xFF5DE2E7)
private val ChaserAmber = Color(0xFFFFC857)
private val ChaserMuted = Color(0xFF91A3AD)

@Composable
fun DxChaserScreen(
    snapshot: DxChaserReadOnlySnapshot,
    onStartAssist: () -> Unit,
    onStartChase: () -> Unit,
    onStartDryRun: () -> Unit,
    onStop: () -> Unit,
    onCandidate: (DxChaserCandidateSnapshot) -> Unit,
    onCrossBandReview: (DxChaserCrossBandOpportunity) -> Unit,
    settings: DxChaserSettingsDocument = DxChaserSettingsDocument(),
    onSettingsChanged: (DxChaserSettingsDocument) -> Unit = {},
    onImportRarity: () -> Unit = {},
    onClearRarity: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = ChaserBackground) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val wide = maxWidth >= 840.dp
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LazyColumn(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)) {
                    item { SessionCard(snapshot, onStartAssist, onStartChase, onStartDryRun, onStop) }
                    item { TargetCard(snapshot.currentTarget) }
                    item { PolicyCard(settings, onSettingsChanged, onImportRarity, onClearRarity) }
                    item { HistoryCard(snapshot) }
                    item { DiagnosticsCard(snapshot) }
                    item { SafetyTruthCard(snapshot) }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CandidateList(snapshot, onCandidate, Modifier.weight(1f))
                    CrossBandList(snapshot.crossBandOpportunities, onCrossBandReview)
                }
            } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SessionCard(snapshot, onStartAssist, onStartChase, onStartDryRun, onStop) }
                item { TargetCard(snapshot.currentTarget) }
                item { CandidateList(snapshot, onCandidate, Modifier.height(480.dp)) }
                item { CrossBandList(snapshot.crossBandOpportunities, onCrossBandReview) }
                item { PolicyCard(settings, onSettingsChanged, onImportRarity, onClearRarity) }
                item { HistoryCard(snapshot) }
                item { DiagnosticsCard(snapshot) }
                item { SafetyTruthCard(snapshot) }
            }
        }
    }
}

@Composable
private fun SessionCard(snapshot: DxChaserReadOnlySnapshot, onAssist: () -> Unit, onChase: () -> Unit,
    onDryRun: () -> Unit, onStop: () -> Unit) {
    ChaserCard {
        Text("DX CHASER", color = ChaserCyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text("${snapshot.session.mode.name.replace('_', ' ')} · ${snapshot.session.state.name.replace('_', ' ')}",
            color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("Operator-started search and pounce", color = ChaserMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAssist) { Text("ASSIST") }
            Button(onClick = onChase) { Text("START CHASE") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDryRun) { Text("DRY RUN") }
            OutlinedButton(onClick = onStop) { Text("STOP") }
        }
        Text("Only locally decoded FT8/FT4 stations are eligible. External spots never start a call. Final transmission uses ShackCQ Digi safety.",
            color = ChaserAmber, style = MaterialTheme.typography.bodySmall)
        Text("Candidates ${snapshot.rankedCandidates.size} · Cooldowns ${snapshot.cooldowns.size} · Generation ${snapshot.generation}",
            color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TargetCard(target: DxChaserTargetSnapshot?) = ChaserCard {
    Text("CURRENT TARGET", color = ChaserMuted, fontWeight = FontWeight.Bold)
    if (target == null) Text("Monitoring local decodes", color = Color.White)
    else {
        Text(target.candidate.callsign, color = if (target.engaged) ChaserAmber else ChaserCyan,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Text("${target.candidate.band} ${target.candidate.mode} · ${target.candidate.snr} dB · score ${target.candidate.breakdown.total}", color = Color.White)
        Text(if (target.engaged) "ENGAGED LOCK — no pre-emption" else "Pre-engagement · attempts ${target.attempts}", color = ChaserAmber)
        Text(target.candidate.breakdown.reasons.take(4).joinToString(" · "), color = ChaserMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CandidateList(snapshot: DxChaserReadOnlySnapshot, onCandidate: (DxChaserCandidateSnapshot) -> Unit,
    modifier: Modifier = Modifier) = ChaserCard(modifier) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("LOCAL CANDIDATES", color = ChaserMuted, fontWeight = FontWeight.Bold)
        Text("${snapshot.rankedCandidates.count(DxChaserCandidateSnapshot::eligible)} eligible", color = ChaserCyan)
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(snapshot.rankedCandidates.take(50), key = { "${it.baseCallsign}-${it.localDecodeId}" }) { row ->
            Card(onClick = { onCandidate(row) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF16262E))) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.callsign, color = if (row.eligible) Color.White else ChaserMuted, fontWeight = FontWeight.Bold)
                        Text(row.breakdown.total.toString(), color = if (row.eligible) ChaserCyan else ChaserMuted, fontWeight = FontWeight.Bold)
                    }
                    Text("${row.band} ${row.mode} · ${row.snr} dB · ${row.localDecodeAgeSeconds}s · ×${row.decodeCount}", color = ChaserMuted)
                    Text(listOf(row.entity, row.grid, "WORKED".takeIf { row.worked }, "CONFIRMED".takeIf { row.confirmed },
                        "WATCH".takeIf { row.watchlisted }, "COOLDOWN".takeIf { row.cooldownActive }).filterNotNull()
                        .filter(String::isNotBlank).joinToString(" · "), color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
                    Text(if (row.eligible) "${row.priorityTier} · ${row.breakdown.reasons.take(3).joinToString(" · ")}"
                        else "INELIGIBLE · ${row.ineligibleReasons.take(2).joinToString(" · ")}",
                        color = if (row.eligible) ChaserAmber else Color(0xFFE58C8C), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Text("${row.currentEvidenceLabel} · ${row.outlookLabel} · ${if (row.rarity.origin == DxChaserRarityOrigin.UNAVAILABLE) "RARITY UNAVAILABLE" else "RARITY ${row.rarity.rank ?: row.rarity.tier}"}",
                        color = ChaserMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CrossBandList(rows: List<DxChaserCrossBandOpportunity>, onReview: (DxChaserCrossBandOpportunity) -> Unit) = ChaserCard {
    Text("RECEIVE-REVIEW OPPORTUNITIES", color = ChaserMuted, fontWeight = FontWeight.Bold)
    if (rows.isEmpty()) Text("No corroborated cross-band review", color = ChaserMuted)
    rows.take(20).forEach { row ->
        HorizontalDivider(color = Color(0xFF263840))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${row.callsign} · ${row.band}", color = Color.White, fontWeight = FontWeight.Bold)
                Text("${row.sourceCount} sources · ${row.needReason} · ${row.confidenceLabel}", color = ChaserMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { onReview(row) }) { Text("REVIEW RX") }
        }
    }
    Text("Review never tunes automatically and never makes external evidence call-eligible.", color = ChaserAmber,
        style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun SafetyTruthCard(snapshot: DxChaserReadOnlySnapshot) = ChaserCard {
    val safety = snapshot.safety
    Text("SAFETY BOUNDARY", color = ChaserMuted, fontWeight = FontWeight.Bold)
    Text(if (safety.preparationPermitted) "PREPARE PATH AVAILABLE" else "INTERLOCKED",
        color = if (safety.preparationPermitted) ChaserCyan else ChaserAmber, fontWeight = FontWeight.Bold)
    listOf(
        "Foreground" to safety.foreground,
        "Radio connected" to safety.radioConnected,
        "Digi FT8/FT4" to safety.digiModeEligible,
        "Local modem authority" to safety.localModemAuthority,
        "Audio route" to (safety.routeHealthy && safety.digiAudioHealthy),
        "TX enabled by operator" to safety.txEnabledByOperator,
        "Contest compatible" to safety.contestCompatible,
        "Keyer idle" to safety.keyerIdle,
        "RX confirmed" to safety.rxConfirmed,
    ).forEach { (label, ready) -> Text("$label · ${if (ready) "READY" else "BLOCKED"}", color = ChaserMuted,
        style = MaterialTheme.typography.labelSmall) }
    Text("No CAT · no PTT · no TUNE · no TX enable · no QSO mutation · no provider connection", color = ChaserMuted,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PolicyCard(settings: DxChaserSettingsDocument, onChanged: (DxChaserSettingsDocument) -> Unit,
    onImportRarity: () -> Unit, onClearRarity: () -> Unit) = ChaserCard {
    Text("POLICY", color = ChaserMuted, fontWeight = FontWeight.Bold)
    Text(settings.profile.name.replace('_', ' '), color = ChaserCyan, fontWeight = FontWeight.Bold)
    Text("${settings.selectedBands.joinToString()} · ${settings.selectedModes.joinToString()} · min ${settings.minimumSnr} dB",
        color = ChaserMuted, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val profiles = DxChaserProfile.entries
            onChanged(settings.copy(profile = profiles[(profiles.indexOf(settings.profile) + 1) % profiles.size]))
        }) { Text("NEXT PROFILE") }
        OutlinedButton(onClick = { onChanged(settings.copy(preemptionEnabled = !settings.preemptionEnabled)) }) {
            Text(if (settings.preemptionEnabled) "PRE-EMPT ON" else "PRE-EMPT OFF")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChanged(settings.copy(minimumSnr = settings.minimumSnr - 1).clamped()) }) { Text("SNR −") }
        OutlinedButton(onClick = { onChanged(settings.copy(minimumSnr = settings.minimumSnr + 1).clamped()) }) { Text("SNR +") }
    }
    Text("Attempts ${settings.normalAttemptLimit}/${settings.scarceAttemptLimit}/${settings.atnoAttemptLimit} · session ${settings.sessionTimeoutSeconds / 60}m · hysteresis ${settings.preemptionHysteresisPercent}%",
        color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
    Text("Evidence current ${settings.currentEvidenceContribution} · outlook ${settings.empiricalOutlookContribution} · rarity ${settings.rarityContribution}",
        color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onImportRarity) { Text("IMPORT RARITY") }
        OutlinedButton(onClick = onClearRarity) { Text("CLEAR") }
    }
}

@Composable
private fun HistoryCard(snapshot: DxChaserReadOnlySnapshot) = ChaserCard {
    Text("HISTORY & STATISTICS", color = ChaserMuted, fontWeight = FontWeight.Bold)
    Text("Targets ${snapshot.session.attemptedTargets} · completed ${snapshot.session.completedQsos} · failures ${snapshot.session.failures}", color = Color.White)
    val total = snapshot.session.completedQsos + snapshot.session.failures
    Text("Success ${if (total == 0) "—" else "${snapshot.session.completedQsos * 100 / total}%"} · cooldowns ${snapshot.cooldowns.size}",
        color = ChaserMuted)
    if (snapshot.databaseCounts.isNotEmpty()) Text(snapshot.databaseCounts.entries.joinToString(" · ") { "${it.key} ${it.value}" },
        color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun DiagnosticsCard(snapshot: DxChaserReadOnlySnapshot) = ChaserCard {
    Text("DIAGNOSTICS", color = ChaserMuted, fontWeight = FontWeight.Bold)
    Text("Generation ${snapshot.generation} · input ${snapshot.rankedCandidates.size} · eligible ${snapshot.rankedCandidates.count { it.eligible }}",
        color = Color.White, style = MaterialTheme.typography.bodySmall)
    Text("Providers ${snapshot.providerFreshness.entries.joinToString(" · ") { "${it.key} ${it.value}" }.ifBlank { "UNAVAILABLE" }}",
        color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
    Text("Settings ${snapshot.settingsDigest.ifBlank { "unavailable" }} · pending ${snapshot.session.pendingIntent?.type ?: "none"}",
        color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
    Text("Last action ${snapshot.lastAction?.type ?: "none"} · error ${snapshot.lastEngineError.ifBlank { "none" }}",
        color = ChaserMuted, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun ChaserCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = ChaserPanel)) {
        Column(Modifier.fillMaxWidth().background(ChaserPanel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
