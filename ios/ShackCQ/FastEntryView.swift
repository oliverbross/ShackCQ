import SwiftUI

struct FastEntryView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var radio: RadioModel
    @EnvironmentObject private var logbook: QSOStore
    @EnvironmentObject private var features: FeatureModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @SceneStorage("shackcq.apple.fastEntryDraft") private var draft = ""
    @State private var selected = Set<Int>()
    @State private var selectedOnly = false
    @State private var showHelp = false
    @State private var overrides: [Int: FastEntryCanonical] = [:]
    @State private var editing: AppleFastEntryRow?
    @State private var receipt: AppleFastEntryImportReceipt?
    @State private var status = "Enter or paste contacts; preview is local until Import."

    private var result: AppleFastEntryResult {
        var parsed = AppleFastEntryParser.parse(draft, defaults: .init(date: Date(), operatorCallsign: features.operatorCallsign,
            stationCallsign: features.operatorCallsign))
        parsed.rows = parsed.rows.map { row in overrides[row.line].map { AppleFastEntryRow(line: row.line, qso: $0, inherited: row.inherited) } ?? row }
        return parsed
    }
    private var importRows: [AppleFastEntryRow] { selectedOnly ? result.rows.filter { selected.contains($0.line) } : result.rows }

    var body: some View {
        NavigationStack {
            Group {
                if horizontalSizeClass == .regular {
                    HStack(spacing: 12) { editor.frame(maxWidth: .infinity); preview.frame(maxWidth: .infinity) }
                } else {
                    VStack(spacing: 10) { editor.frame(minHeight: 180); preview }
                }
            }
            .padding()
            .safeAreaInset(edge: .bottom) { commandBar }
            .navigationTitle("Native Fast Entry")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Help") { showHelp.toggle() } }
                ToolbarItem(placement: .topBarTrailing) { Button("Close") { dismiss() } }
            }
        }
        .sheet(isPresented: $showHelp) { help }
        .sheet(item: $editing) { row in FastEntryCanonicalEditor(qso: row.qso) { overrides[row.line] = $0 } }
    }

    private var editor: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("FAST ENTRY TEXT").font(.caption.bold()).foregroundStyle(.secondary)
            TextEditor(text: $draft).font(.body.monospaced()).padding(6)
                .background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 12))
            Text("\(result.rows.count) valid · \(result.errors.count) errors · \(result.warnings.count) warnings")
                .font(.caption).foregroundStyle(result.errors.isEmpty ? Color.secondary : Color.red)
        }
    }

    private var preview: some View {
        List {
            ForEach(result.issues) { issue in
                Label("Line \(issue.line) · \(issue.message)", systemImage: issue.warning ? "exclamationmark.triangle" : "xmark.octagon")
                    .font(.caption).foregroundStyle(issue.warning ? RigTheme.amber : .red)
            }
            ForEach(result.rows) { row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Toggle("", isOn: Binding(get: { selected.contains(row.line) }, set: { on in if on { selected.insert(row.line) } else { selected.remove(row.line) } })).labelsHidden()
                        VStack(alignment: .leading) {
                            Text("Line \(row.line) · \(row.qso.callsign)").font(.headline)
                            Text("\(row.qso.band) \(row.qso.submode.isEmpty ? row.qso.mode : row.qso.submode) · \(row.qso.rstSent)/\(row.qso.rstReceived)").font(.caption)
                            Text(row.qso.createdAt.formatted(.iso8601)).font(.caption2).foregroundStyle(.secondary)
                            if !row.inherited.isEmpty { Text("Inherited: \(row.inherited.sorted().joined(separator: ", "))").font(.caption2).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        Menu {
                            Button("Edit preview row") { editing = row }
                            Button("Enrich from callbook") { Task {
                                await features.callbook.lookup(row.qso.callsign)
                                if let found = features.callbook.result {
                                    var updated = row.qso; updated.name = found.name; updated.qth = found.location
                                    updated.country = found.country; updated.grid = found.grid; overrides[row.line] = updated
                                }
                            } }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            }
        }.listStyle(.plain)
    }

    private var commandBar: some View {
        VStack(spacing: 7) {
            Picker("Import scope", selection: $selectedOnly) { Text("All valid (\(result.rows.count))").tag(false); Text("Selected (\(selected.count))").tag(true) }.pickerStyle(.segmented)
            HStack {
                Text(status).font(.caption).foregroundStyle(.secondary).lineLimit(2); Spacer()
                if let receipt { Button("Undo") {
                    status = logbook.undoFastEntry(receipt, wavelog: features.wavelog) ? "Import undone; unsent Wavelog creates removed." : logbook.message
                    self.receipt = nil
                }.buttonStyle(.bordered) }
                Button("Import") {
                    let saved = logbook.importFastEntry(importRows.map(\.qso), wavelog: features.wavelog, serialize: radio.adif)
                    receipt = saved; status = "Imported \(saved.qsoIDs.count); skipped \(saved.skipped). Wavelog-eligible rows queued."
                }.buttonStyle(.borderedProminent).disabled(importRows.isEmpty)
            }
        }.padding().background(.bar)
    }

    private var help: some View {
        NavigationStack { ScrollView { Text("""
        Headers inherit date, day +, TIMEZONE/TZOFS +HH:MM, band, mode, submode, MHz, operator, power, own grid, and own references.

        Contact rows accept time and shorthand, callsign, RSTs, @name, #grid, multiple POTA/SOTA/IOTA/WWFF references, contest exchanges, satellite fields, {comments}, [QSL messages], and arbitrary <ADIF_FIELD:value> UTF-8 fields.

        Red line errors never create hidden rows. Duplicate candidates mean the same normalized callsign, frequency, main mode, and 15-second window. Import either all valid rows or explicitly selected rows.
        """).padding() }.navigationTitle("Fast Entry Help").toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showHelp = false } } } }
    }
}

private struct FastEntryCanonicalEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State var qso: FastEntryCanonical
    let save: (FastEntryCanonical) -> Void
    var body: some View {
        NavigationStack { Form {
            TextField("Callsign", text: $qso.callsign).textInputAutocapitalization(.characters)
            TextField("Name", text: $qso.name); TextField("QTH", text: $qso.qth); TextField("Country", text: $qso.country)
            TextField("Grid", text: $qso.grid).textInputAutocapitalization(.characters)
            TextField("Notes", text: $qso.notes, axis: .vertical)
        }.navigationTitle("Edit Preview Row").toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Apply") { save(qso); dismiss() }.disabled(qso.callsign.isEmpty) }
        } }
    }
}
