package app.shackcq.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun FastEntryDialog(
    mutations: QsoMutationCoordinator,
    wavelog: WavelogController,
    callbook: CallbookController,
    operatorCallsign: String,
    onImported: (Int, Int) -> Unit,
    initialDraft: String = "",
    dismiss: () -> Unit,
) {
    var draft by rememberSaveable(initialDraft) { mutableStateOf(initialDraft) }
    var selectedLines by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var importSelected by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Enter or paste contacts; preview updates locally.") }
    var receipt by remember { mutableStateOf<FastEntryImportReceipt?>(null) }
    var overrides by remember { mutableStateOf<Map<Int, Qso>>(emptyMap()) }
    var editing by remember { mutableStateOf<FastEntryRow?>(null) }
    var lookupLine by remember { mutableStateOf<Int?>(null) }
    val parsed = remember(draft, overrides) {
        val result = FastEntryParser.parse(draft, FastEntryDefaults(
            LocalDate.now(ZoneOffset.UTC), operatorCallsign, operatorCallsign,
            wavelog.stationId, wavelog.selectedStation?.grid.orEmpty(),
        ))
        result.copy(rows = result.rows.map { row -> overrides[row.line]?.let { row.copy(qso = it) } ?: row })
    }
    val importRows = if (importSelected) parsed.rows.filter { it.line in selectedLines } else parsed.rows

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("NATIVE FAST ENTRY", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${parsed.rows.size} valid · ${parsed.errors.size} errors · ${parsed.warnings.size} warnings") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ showHelp = !showHelp }) { Text(if (showHelp) "HIDE HELP" else "HELP") }
                        TextButton(dismiss) { Text("CLOSE") }
                    }
                }
                if (showHelp) FastEntryHelp()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FastEntryEditor(draft, { draft = it; receipt = null }, Modifier.weight(0.9f).fillMaxHeight())
                        FastEntryPreview(parsed, selectedLines, { selectedLines = it }, { editing = it }, { row ->
                            lookupLine = row.line
                            callbook.lookup(row.qso.callsign) { record ->
                                lookupLine = null
                                if (record != null) overrides = overrides + (row.line to row.qso.copy(
                                    name = record.name, qth = record.qth, country = record.country, grid = record.grid,
                                    dxcc = record.dxcc, continent = record.continent, cqZone = record.cqZone,
                                    ituZone = record.ituZone, state = record.state, email = record.email,
                                ))
                            }
                        }, lookupLine, Modifier.weight(1.1f).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FastEntryEditor(draft, { draft = it; receipt = null }, Modifier.fillMaxWidth().weight(0.85f))
                        FastEntryPreview(parsed, selectedLines, { selectedLines = it }, { editing = it }, { row ->
                            lookupLine = row.line; callbook.lookup(row.qso.callsign) { record ->
                                lookupLine = null
                                if (record != null) overrides = overrides + (row.line to row.qso.copy(name = record.name,
                                    qth = record.qth, country = record.country, grid = record.grid, dxcc = record.dxcc))
                            }
                        }, lookupLine, Modifier.fillMaxWidth().weight(1.15f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(!importSelected, { importSelected = false }, { Text("ALL VALID (${parsed.rows.size})") })
                    FilterChip(importSelected, { importSelected = true }, { Text("SELECTED (${selectedLines.size})") })
                    Spacer(Modifier.weight(1f))
                    receipt?.let { saved -> OutlinedButton({
                        status = if (mutations.undoFastEntry(saved)) "Import undone locally; unsent Wavelog creates removed."
                            else "Undo expired because a later log mutation occurred."
                        receipt = null; onImported(0, 0)
                    }) { Text("UNDO IMPORT") } }
                    Button({
                        if (parsed.errors.isNotEmpty() && parsed.rows.isEmpty()) { status = "Nothing imported; fix the line errors."; return@Button }
                        val saved = mutations.importFastEntry(importRows.map(FastEntryRow::qso))
                        receipt = saved
                        status = "Imported ${saved.qsoIds.size}; skipped ${saved.skipped} duplicate candidates. Wavelog-eligible rows are queued."
                        onImported(saved.qsoIds.size, saved.skipped)
                    }, enabled = importRows.isNotEmpty()) { Text("IMPORT") }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    editing?.let { row -> FastEntryRowEditor(row.qso, { updated -> overrides = overrides + (row.line to updated); editing = null }, { editing = null }) }
}

@Composable private fun FastEntryEditor(value: String, changed: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, changed, modifier = modifier, label = { Text("Fast Entry text") },
        placeholder = { Text("20m SSB\n2134 OM0RX 59 59 #JN88TQ {portable}\n6 VK8ABC 57 59 VK-0001") })
}

@Composable private fun FastEntryPreview(result: FastEntryResult, selected: List<Int>, select: (List<Int>) -> Unit,
    edit: (FastEntryRow) -> Unit, lookup: (FastEntryRow) -> Unit, lookupLine: Int?, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (result.issues.isNotEmpty()) items(result.issues, key = { "${it.line}-${it.message}" }) { issue ->
            Text("Line ${issue.line} · ${if (issue.warning) "warning" else "error"} · ${issue.message}",
                color = if (issue.warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        items(result.rows, key = { it.line }) { row ->
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(row.line in selected, { checked -> select(if (checked) (selected + row.line).distinct() else selected - row.line) })
                    Column(Modifier.weight(1f)) {
                        Text("Line ${row.line} · ${row.qso.callsign} · ${row.qso.band} ${row.qso.submode.ifBlank { row.qso.mode }}", fontWeight = FontWeight.SemiBold)
                        Text("${java.time.Instant.ofEpochSecond(row.qso.createdAt)} · ${row.qso.rstSent}/${row.qso.rstReceived}" +
                            row.inherited.takeIf(Set<String>::isNotEmpty)?.joinToString(prefix = " · inherited ").orEmpty(), style = MaterialTheme.typography.bodySmall)
                        if (row.qso.name.isNotBlank() || row.qso.grid.isNotBlank()) Text("${row.qso.name} ${row.qso.grid}".trim(), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton({ edit(row) }) { Text("EDIT") }
                    TextButton({ lookup(row) }, enabled = lookupLine == null) { Text(if (lookupLine == row.line) "LOOKING…" else "CALLBOOK") }
                }
            } }
        }
    }
}

@Composable private fun FastEntryRowEditor(qso: Qso, save: (Qso) -> Unit, dismiss: () -> Unit) {
    var callsign by remember(qso.id) { mutableStateOf(qso.callsign) }; var name by remember(qso.id) { mutableStateOf(qso.name) }
    var grid by remember(qso.id) { mutableStateOf(qso.grid) }; var notes by remember(qso.id) { mutableStateOf(qso.notes) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("EDIT PREVIEW ROW") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(callsign, { callsign = it.uppercase() }, label = { Text("Callsign") })
        OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(grid, { grid = it.uppercase() }, label = { Text("Grid") })
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
    } }, confirmButton = { Button({ save(qso.copy(callsign = callsign, name = name, grid = grid, notes = notes)) }, enabled = callsign.isNotBlank()) { Text("APPLY") } },
        dismissButton = { TextButton(dismiss) { Text("CANCEL") } })
}

@Composable private fun FastEntryHelp() {
    ElevatedCard(Modifier.fillMaxWidth()) { Text(
        "Headers inherit: date YYYY-MM-DD, day +, TIMEZONE/TZOFS +HH:MM, band, mode, MHz, OPERATOR=. " +
            "Rows accept time (and shorthand), callsign, RSTs, @name, #grid, multiple POTA/SOTA/IOTA/WWFF refs, contest exchanges, " +
            "{comment}, [QSL message], and <ADIF_FIELD:value>. Unknown ADIF fields are preserved as UTF-8. " +
            "Red line errors never create hidden rows; duplicate candidates use same call/frequency/mode within 15 seconds.",
        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}
