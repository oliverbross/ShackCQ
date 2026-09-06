import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum Destination: String, CaseIterable, Identifiable {
    case home = "Home", radio = "Radio", spots = "Spots", dx = "Neural DX", log = "Log"
    case intelligence = "Log Intelligence", sync = "Sync", operations = "Operations"
    case panadapter = "Panadapter", remote = "Remote Stations", lookup = "Lookup", groupsIo = "Groups.io", settings = "Settings"
    var id: String { rawValue }
    var icon: String {
        switch self { case .home: "house"; case .radio: "antenna.radiowaves.left.and.right"; case .spots: "dot.radiowaves.up.forward"; case .dx: "globe.americas"; case .log: "list.clipboard"; case .intelligence: "chart.bar.xaxis"; case .sync: "arrow.triangle.2.circlepath"; case .operations: "location.north.circle"; case .panadapter: "waveform.path.ecg.rectangle"; case .remote: "network"; case .lookup: "person.text.rectangle"; case .groupsIo: "person.3.sequence"; case .settings: "gearshape" }
    }
}

struct ContentView: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @EnvironmentObject private var groupsIo: GroupsIoController
    @State private var selection: Destination = .home

    private var destinations: [Destination] {
        Destination.allCases.filter { destination in
            if destination == .groupsIo { return groupsIo.enabled && sizeClass != .compact }
            return true
        }
    }

    var body: some View {
        Group {
            if sizeClass == .compact {
                TabView(selection: $selection) {
                    ForEach(destinations) { destination in
                        NavigationStack { destinationView(destination) }
                            .tabItem { Label(destination.rawValue, systemImage: destination.icon) }
                            .tag(destination)
                    }
                }
            } else {
                NavigationSplitView {
                    List {
                        ForEach(destinations) { destination in
                            Button { selection = destination } label: {
                                Label(destination.rawValue, systemImage: destination.icon)
                                    .foregroundStyle(selection == destination ? RigTheme.amber : .primary)
                            }
                        }
                    }
                    .navigationTitle("ShackCQ")
                } detail: {
                    destinationView(selection)
                }
            }
        }
        .tint(RigTheme.amber)
        .onChange(of: groupsIo.enabled) { _, enabled in if !enabled && selection == .groupsIo { selection = .settings } }
    }

    @ViewBuilder private func destinationView(_ destination: Destination) -> some View {
        switch destination {
        case .home: HomeView(route: route)
        case .radio: RadioView()
        case .spots: SpotsView()
        case .dx: DXView()
        case .log: LogView()
        case .intelligence: LogIntelligenceView(route: route)
        case .sync: SyncCentreView(route: route)
        case .operations: OperationsView(route: route)
        case .panadapter: PanadapterView()
        case .remote: RemoteStationsView()
        case .lookup: LookupView()
        case .groupsIo: GroupsIoView()
        case .settings: SettingsView()
        }
    }

    private func route(_ action: WorkspaceAction) {
        selection = switch action {
        case .openRadio: .radio
        case .openNeuralDX: .dx
        case .openLog: .log
        case .openLogIntelligence: .intelligence
        case .openSync: .sync
        case .openOperations: .operations
        case .openGroups: groupsIo.enabled ? .groupsIo : .settings
        case .openSettings: .settings
        }
    }
}

enum WorkspaceAction {
    case openRadio, openNeuralDX, openLog, openLogIntelligence, openSync, openOperations, openGroups, openSettings
}

enum RigTheme {
    static let amber = Color(red: 0.96, green: 0.61, blue: 0.16)
    static let panel = Color(red: 0.10, green: 0.12, blue: 0.14)
    static let green = Color(red: 0.27, green: 0.83, blue: 0.52)
}

struct BrandHeader: View {
    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image("ShackCQLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 38, height: 38)
                .accessibilityHidden(true)
            Text("SHACKCQ").font(.title.bold()).tracking(2).foregroundStyle(RigTheme.amber)
            Spacer()
            Text("Radio. Spectrum. Spots. Logs.").font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct StatusBadge: View {
    let status: String
    var body: some View {
        Text(status).font(.caption.bold()).padding(.horizontal, 10).padding(.vertical, 6)
            .background(status == "LIVE" ? RigTheme.green.opacity(0.2) : Color.red.opacity(0.2))
            .foregroundStyle(status == "LIVE" ? RigTheme.green : .red).clipShape(Capsule())
    }
}

struct RadioSummary: View {
    @EnvironmentObject private var radio: RadioModel
    var body: some View {
        let state = radio.snapshot
        VStack(alignment: .leading, spacing: 18) {
            HStack { StatusBadge(status: state.status); Spacer(); Text(state.model).font(.headline) }
            Text(state.connected ? state.frequencyText : "—.——— MHz").font(.system(size: 54, weight: .semibold, design: .monospaced)).minimumScaleFactor(0.55)
                .accessibilityIdentifier("homeFrequencyDisplay")
            HStack(spacing: 24) {
                Label(state.mode, systemImage: "waveform")
                Label(state.transmitting ? "TX" : "RX", systemImage: state.transmitting ? "dot.radiowaves.left.and.right" : "arrow.down.circle")
                Label(state.identity, systemImage: "cpu")
            }.foregroundStyle(.secondary)
            ProgressView(value: Double(state.meter), total: 100).tint(RigTheme.amber)
            Text("Signal / status \(state.meter)%").font(.caption).foregroundStyle(.secondary)
        }
        .padding(24).background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

struct HomeView: View {
    @EnvironmentObject private var radio: RadioModel
    @EnvironmentObject private var logbook: QSOStore
    @EnvironmentObject private var features: FeatureModel
    @EnvironmentObject private var groupsIo: GroupsIoController
    let route: (WorkspaceAction) -> Void

    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 22) {
            BrandHeader(); RadioSummary()
            let state = radio.snapshot
            GroupBox("Operating context · source truth") {
                VStack(alignment: .leading, spacing: 8) {
                    LabeledContent("Radio", value: state.connected ? "\(state.frequencyText) · \(state.mode)" : "Disconnected")
                    LabeledContent("Log", value: "\(logbook.records.count) local · \(features.wavelog.contacts.count) Wavelog cached")
                    LabeledContent("DX", value: "\(features.dx.opportunities.count) current opportunities · \(features.clusterStatus)")
                    LabeledContent("Groups.io", value: groupsIo.enabled ? "\(groupsIo.messages.count) cached · \(groupsIo.status)" : "Disabled")
                    Text("Every value above is read from the owning model. Missing state remains unavailable; no demo or simulated radio state is substituted.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 210))], spacing: 12) {
                HomeActionCard(title: "Neural DX", detail: "\(features.dx.opportunities.count) ranked observations", icon: "globe.americas") { route(.openNeuralDX) }
                HomeActionCard(title: "Log intelligence", detail: "\(logbook.records.count) local contacts", icon: "chart.bar.xaxis") { route(.openLogIntelligence) }
                HomeActionCard(title: "Sync", detail: "\(features.wavelog.pendingCount) Wavelog queued", icon: "arrow.triangle.2.circlepath") { route(.openSync) }
                HomeActionCard(title: "Operations", detail: "Portable, satellite and QO-100 truth", icon: "location.north.circle") { route(.openOperations) }
                HomeActionCard(title: "Groups.io", detail: groupsIo.enabled ? "\(groupsIo.messages.count) cached messages" : "Enable in Settings", icon: "person.3.sequence") { route(.openGroups) }
            }
        }.padding() }.navigationTitle("Home")
    }
}

private struct HomeActionCard: View {
    let title: String
    let detail: String
    let icon: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.title2).foregroundStyle(RigTheme.amber)
                VStack(alignment: .leading) { Text(title).font(.headline); Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }.padding().frame(maxWidth: .infinity, alignment: .leading)
        }.buttonStyle(.plain).background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

struct LogIntelligenceView: View {
    @EnvironmentObject private var logbook: QSOStore
    @EnvironmentObject private var features: FeatureModel
    let route: (WorkspaceAction) -> Void
    var body: some View {
        List {
            Section("Owned evidence") {
                LabeledContent("Local QSO records", value: "\(logbook.records.count)")
                LabeledContent("Wavelog cached contacts", value: "\(features.wavelog.contacts.count)")
                LabeledContent("Worked index", value: features.dx.safeWorkedLog.complete ? "Complete" : "Partial")
                Text("Counts are descriptive evidence, not propagation probability or a promise of a future contact.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Handoffs") {
                Button("Open log") { route(.openLog) }
                Button("Review Neural DX observations") { route(.openNeuralDX) }
            }
        }.navigationTitle("Log Intelligence")
    }
}

struct SyncCentreView: View {
    @EnvironmentObject private var features: FeatureModel
    @EnvironmentObject private var groupsIo: GroupsIoController
    let route: (WorkspaceAction) -> Void
    var body: some View {
        List {
            Section("Wavelog") {
                Text(features.wavelog.status)
                LabeledContent("Pending", value: "\(features.wavelog.pendingCount)")
                LabeledContent("Cached contacts", value: "\(features.wavelog.contacts.count)")
                Button("Refresh Wavelog log") { Task { await features.wavelog.fullSync() } }.disabled(features.wavelog.apiKey.isEmpty)
            }
            Section("Groups.io") {
                Text(groupsIo.enabled ? groupsIo.status : "Disabled")
                LabeledContent("Cached messages", value: "\(groupsIo.messages.count)")
                Button("Open Groups.io") { route(.openGroups) }
            }
        }.navigationTitle("Sync")
    }
}

struct OperationsView: View {
    @EnvironmentObject private var features: FeatureModel
    let route: (WorkspaceAction) -> Void
    private var qo100: [DXOpportunity] { features.dx.opportunities.filter { $0.displayBand.contains("QO-100") } }
    var body: some View {
        List {
            Section("Portable") {
                Text("Portable references and activation fields are retained through the log workspace.")
                Button("Open portable-aware log") { route(.openLog) }
            }
            Section("Satellite and QO-100") {
                LabeledContent("QO-100 observations", value: "\(qo100.count)")
                Text("Apple currently has no verified orbital pass engine. Next passes, Doppler and rotator control are therefore unavailable on this platform; no synthetic pass is shown.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Review QO-100 observations") { route(.openNeuralDX) }
            }
            Section("Transmit boundary") {
                Text("This workspace prepares review only. It does not key PTT, tune, transmit, post, or log.")
            }
        }.navigationTitle("Operations")
    }
}

struct RadioView: View {
    @EnvironmentObject private var radio: RadioModel
    @State private var frequencyMHz = ""
    @State private var modeCode = "2"
    @State private var rawCAT = ""
    @State private var cwText = ""
    @State private var power = ""
    @State private var filterHz = ""
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 22) {
            BrandHeader(); KX3ControlDeck(state: radio.snapshot, send: radio.sendCAT)
            GroupBox("Connection") {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("Serial endpoint", selection: $radio.selectedPort) {
                        if radio.serialPorts.isEmpty { Text("No serial endpoints").tag("") }
                        ForEach(radio.serialPorts, id: \.self) { Text(($0 as NSString).lastPathComponent).tag($0) }
                    }
                    HStack {
                        Button("Scan serial devices") { radio.refreshPorts() }
                        Button("Connect KX3") { radio.connect() }.buttonStyle(.borderedProminent).disabled(radio.selectedPort.isEmpty)
                        Button("Disconnect") { radio.disconnect() }.buttonStyle(.bordered)
                    }
                    LabeledContent("Transport", value: radio.transportStatus)
                        .accessibilityIdentifier("serialStatus")
                    LabeledContent("Radio", value: radio.snapshot.model)
                        .accessibilityIdentifier("radioModel")
                    LabeledContent("CAT polling", value: "ID · FA · FB · MD · IF · TQ · meters · gains · flags")
                }
            }
            GroupBox("CAT control") {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        TextField("Frequency MHz", text: $frequencyMHz).keyboardType(.decimalPad)
                        Button("Set VFO A") { radio.setFrequencyMHz(frequencyMHz) }
                    }
                    HStack {
                        Picker("Mode", selection: $modeCode) {
                            Text("LSB").tag("1"); Text("USB").tag("2"); Text("CW").tag("3")
                            Text("FM").tag("4"); Text("AM").tag("5"); Text("DATA").tag("6")
                            Text("CW-REV").tag("7"); Text("DATA-REV").tag("9")
                        }
                        Button("Set Mode") { radio.setMode(code: modeCode) }
                    }
                    HStack {
                        TextField("Raw KX3 CAT command", text: $rawCAT).textInputAutocapitalization(.characters).autocorrectionDisabled()
                        Button("Send") { radio.sendCAT(rawCAT); rawCAT = "" }.disabled(rawCAT.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
            GroupBox("Power, filter and keyer") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack { TextField("PC value", text: $power).keyboardType(.numberPad); Button("Set power") { radio.sendCAT("PC\(power);") } }
                    HStack { TextField("Filter Hz", text: $filterHz).keyboardType(.numberPad); Button("Set filter") { radio.sendCAT("BW\(filterHz);") } }
                    HStack { TextField("CW keyer text", text: $cwText).textInputAutocapitalization(.characters).autocorrectionDisabled(); Button("Send KY") { radio.sendCAT("KY\(cwText);"); cwText = "" } }
                    Text("Values and switch commands are sent directly to the connected radio; the app does not clamp them.").font(.caption).foregroundStyle(.secondary)
                }
            }
        }.padding() }.navigationTitle("Radio")
            .alert("Confirm radio action", isPresented: Binding(
                get: { radio.pendingTransmitCommand != nil },
                set: { if !$0 { radio.resolvePendingTransmit(accept: false) } }
            )) {
                Button("Send once", role: .destructive) { radio.resolvePendingTransmit(accept: true) }
                Button("Cancel", role: .cancel) { radio.resolvePendingTransmit(accept: false) }
            } message: {
                Text("This CAT command can key or otherwise control transmission. Send only if the radio, antenna, frequency, and station are ready.\n\n\(radio.pendingTransmitCommand ?? "")")
            }
    }
}

private struct KX3ControlDeck: View {
    let state: RadioSnapshot
    let send: (String) -> Void
    private let controls = [
        ("RIT", "SWT18;"), ("XIT", "SWT26;"), ("A/B", "SWT24;"), ("A→B", "SWT25;"),
        ("SPLIT", "SWH25;"), ("RATE", "SWT12;"), ("MODE", "SWT14;"), ("DATA", "SWT17;"),
        ("PBT I/II", "SWT33;"), ("CLR", "SWH35;"), ("ATU", "SWT44;"), ("ANT", "SWH44;"),
        ("VFO +", "UP;"), ("VFO −", "DN;"), ("CWT", "SWT28;")
    ]

    var body: some View {
        VStack(spacing: 14) {
            displayPanel
            controlsGrid
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Elecraft KX3 radio control deck")
    }

    private var displayPanel: some View {
        VStack(spacing: 10) {
            HStack(alignment: .top, spacing: 16) {
                frequencyPanel
                Spacer(minLength: 4)
                tuningIndicator
                StatusBadge(status: state.status)
            }
            statusLine
            meter
            HStack { Text("AF \(state.afGain)"); Spacer(); Text("RF \(state.rfGain)") }
                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .padding(18).background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var frequencyPanel: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("VFO A").font(.caption.weight(.bold)).foregroundStyle(RigTheme.amber)
            Text(state.connected ? state.frequencyText : "—.——— MHz")
                .font(.system(.largeTitle, design: .monospaced).weight(.semibold)).minimumScaleFactor(0.55)
                .accessibilityIdentifier("frequencyDisplay")
            Text(state.frequencyBHz > 0 ? String(format: "VFO B  %.3f MHz", Double(state.frequencyBHz) / 1_000_000) : "VFO B  —.——— MHz")
                .font(.system(.subheadline, design: .monospaced)).foregroundStyle(state.split ? Color.red : .secondary)
        }
    }

    private var tuningIndicator: some View {
        KX3TuningIndicator()
    }

    private var statusLine: some View {
        HStack(spacing: 12) {
            Text(state.transmitting ? "TX" : "RX").foregroundStyle(state.transmitting ? .red : RigTheme.green).fontWeight(.bold)
            Text(state.mode).font(.system(.body, design: .monospaced).bold())
            if state.split { flag("SPLIT", active: true) }
            flag("RIT", active: state.rit); flag("XIT", active: state.xit)
            flag("PRE", active: state.preamp); flag("ATT", active: state.attenuator)
            Spacer()
            Text("BW \(state.bandwidthHz) Hz · PWR \(state.powerW) W").font(.caption).foregroundStyle(.secondary)
        }
    }

    private var meter: some View {
        KX3Meter(
            label: state.transmitting ? "RF OUTPUT" : "S-METER",
            value: state.transmitting ? Double(max(0, state.rfOutputTenths)) : Double(state.meter),
            maximum: state.transmitting ? 120 : 30,
            annotation: state.transmitting && state.swrTenths >= 0
                ? String(format: "SWR %.1f:1", Double(state.swrTenths) / 10)
                : "S 1  3  5  7  9  +10  +20  +30"
        )
    }

    private var controlsGrid: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 86), spacing: 8)], spacing: 8) {
            ForEach(controls, id: \.0) { control in
                Button(control.0) { send(control.1) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
            }
            Button(state.preamp ? "PRE OFF" : "PRE ON") { send(state.preamp ? "PA0;" : "PA1;") }.buttonStyle(.bordered).frame(minHeight: 44)
            Button(state.attenuator ? "ATT OFF" : "ATT ON") { send(state.attenuator ? "RA00;" : "RA01;") }.buttonStyle(.bordered).frame(minHeight: 44)
        }
    }

    private func flag(_ text: String, active: Bool) -> some View {
        Text(text).font(.caption2.bold()).foregroundStyle(active ? RigTheme.amber : .secondary)
    }
}

private struct KX3TuningIndicator: View {
    private let heights: [CGFloat] = [8, 12, 16, 20, 16, 12, 8]

    var body: some View {
        VStack(spacing: 5) {
            Text("CWT").font(.caption.bold())
            HStack(alignment: .bottom, spacing: 3) {
                ForEach(heights.indices, id: \.self) { index in
                    tuningBar(index: index)
                }
            }
            .accessibilityHidden(true)
        }
        .frame(minWidth: 88)
        .padding(10)
        .background(Color.black.opacity(0.28))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func tuningBar(index: Int) -> some View {
        let color = index == 3 ? RigTheme.amber : Color.secondary.opacity(0.45)
        return Capsule().fill(color).frame(width: 5, height: heights[index])
    }
}

private struct KX3Meter: View {
    let label: String
    let value: Double
    let maximum: Double
    let annotation: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack { Text(label).font(.caption.bold()); Spacer(); Text(annotation).font(.caption2.monospacedDigit()).foregroundStyle(.secondary) }
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.09))
                    Capsule().fill(RigTheme.amber).frame(width: geometry.size.width * max(0, min(1, value / maximum)))
                }
            }.frame(height: 10)
        }.accessibilityElement(children: .ignore).accessibilityLabel(label).accessibilityValue("\(Int(value)) of \(Int(maximum))")
    }
}

private enum LogScope: String, CaseIterable, Identifiable { case local = "Local ADIF", wavelog = "Wavelog"; var id: String { rawValue } }

struct LogView: View {
    @EnvironmentObject private var radio: RadioModel
    @EnvironmentObject private var logbook: QSOStore
    @EnvironmentObject private var features: FeatureModel
    @State private var callsign = ""
    @State private var rstSent = "59"
    @State private var rstReceived = "59"
    @State private var frequencyMHz = ""
    @State private var mode = ""
    @State private var name = ""
    @State private var qth = ""
    @State private var country = ""
    @State private var notes = ""
    @State private var scope: LogScope = .local
    @State private var search = ""
    @State private var exportURL: URL?
    @State private var importingADIF = false
    @State private var fastEntry = false

    var body: some View {
        ScrollView { LazyVStack(alignment: .leading, spacing: 18) {
            BrandHeader()
            Picker("Log source", selection: $scope) { ForEach(LogScope.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
            if scope == .local { localLog } else { wavelogLog }
        }.padding() }.navigationTitle("Log")
            .searchable(text: $search, prompt: "Callsign, band, mode or country")
            .fileImporter(isPresented: $importingADIF, allowedContentTypes: [.data, .plainText]) { result in
                if case .success(let url) = result { _ = logbook.importADIF(from: url) }
            }
            .fullScreenCover(isPresented: $fastEntry) { FastEntryView().environmentObject(radio).environmentObject(logbook).environmentObject(features) }
    }

    private var localLog: some View {
        Group {
            GroupBox("New local QSO") {
                HStack {
                    TextField("Callsign", text: $callsign).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    Button("Enrich") { Task {
                        await features.callbook.lookup(callsign)
                        if let record = features.callbook.result {
                            name = record.name; qth = record.location; country = record.country
                        }
                    } }.disabled(callsign.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                HStack { TextField("RST sent", text: $rstSent); TextField("RST received", text: $rstReceived) }
                TextField("Frequency MHz", text: $frequencyMHz).keyboardType(.decimalPad)
                TextField("Mode", text: $mode).textInputAutocapitalization(.characters).autocorrectionDisabled()
                HStack { TextField("Name", text: $name); TextField("QTH", text: $qth); TextField("Country", text: $country) }
                TextField("Notes", text: $notes, axis: .vertical)
                Text(features.callbook.status).font(.caption).foregroundStyle(.secondary)
                Button("Save QSO") { save() }.buttonStyle(.borderedProminent).disabled(callsign.trimmingCharacters(in: .whitespaces).isEmpty)
                if !logbook.message.isEmpty { Text(logbook.message).font(.caption).foregroundStyle(.secondary) }
            }
            HStack {
                Text("Local log · \(logbook.records.count) recent").font(.headline)
                Spacer()
                Button("Fast Entry") { fastEntry = true }.buttonStyle(.borderedProminent)
                Button("Build ADIF export") { exportURL = logbook.exportADIF(using: radio.adif) }
                Button("Import ADIF") { importingADIF = true }
                if let exportURL { ShareLink(item: exportURL) { Label("Share ADIF", systemImage: "square.and.arrow.up") } }
            }
            if filteredLocal.isEmpty { ContentUnavailableView("No matching local QSOs", systemImage: "list.clipboard") }
            ForEach(filteredLocal) { qso in localRow(qso) }
        }
    }

    private var wavelogLog: some View {
        Group {
            GroupBox("Wavelog station log") {
                HStack { Text(features.wavelog.status).font(.subheadline); Spacer(); if features.wavelog.pendingCount > 0 { Text("\(features.wavelog.pendingCount) queued").foregroundStyle(RigTheme.amber) } }
                HStack {
                    Button("Test") { Task { await features.wavelog.testConnection() } }
                    Button("Load stations") { Task { await features.wavelog.loadStations() } }
                    Button("Full log refresh") { Task { await features.wavelog.fullSync() } }.buttonStyle(.borderedProminent)
                }
                if !features.wavelog.stations.isEmpty {
                    Picker("Station", selection: Binding(get: { features.wavelog.stationProfile }, set: features.wavelog.selectStation)) {
                        ForEach(features.wavelog.stations) { Text($0.label).tag($0.id) }
                    }
                }
                LabeledContent("Cached QSOs", value: "\(features.wavelog.contacts.count)")
                LabeledContent("Sync pages", value: "\(features.wavelog.syncPages)")
            }
            if filteredWavelog.isEmpty {
                ContentUnavailableView("No cached Wavelog QSOs", systemImage: "arrow.triangle.2.circlepath",
                    description: Text("Load stations, select one, then run Full log refresh."))
            }
            ForEach(filteredWavelog) { contact in
                VStack(alignment: .leading, spacing: 5) {
                    HStack { Text(contact.callsign).font(.headline); Spacer(); Text([contact.mode, contact.submode].filter { !$0.isEmpty }.joined(separator: " / ")) }
                    Text([contact.band, contact.frequency, contact.country].filter { !$0.isEmpty }.joined(separator: " · ")).foregroundStyle(.secondary)
                    Text("\(contact.date) \(contact.time) · RST \(contact.rstSent)/\(contact.rstReceived)").font(.caption).foregroundStyle(.secondary)
                }.padding().background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    private var filteredLocal: [QSO] {
        let term = search.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return term.isEmpty ? logbook.records : logbook.records.filter { $0.callsign.contains(term) || $0.mode.contains(term) }
    }

    private var filteredWavelog: [WavelogContact] {
        let term = search.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return term.isEmpty ? features.wavelog.contacts : features.wavelog.contacts.filter {
            [$0.callsign, $0.band, $0.mode, $0.submode, $0.country].contains { $0.uppercased().contains(term) }
        }
    }

    private func localRow(_ qso: QSO) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack { Text(qso.callsign).font(.headline); Spacer(); Text(qso.mode) }
            Text([String(format: "%.3f MHz", Double(qso.frequencyHz) / 1_000_000), qso.name, qso.qth, qso.country]
                .filter { !$0.isEmpty }.joined(separator: " · ")).foregroundStyle(.secondary)
            ShareLink(item: radio.adif(for: qso), preview: SharePreview("\(qso.callsign).adi")) { Label("Share record ADIF", systemImage: "square.and.arrow.up") }.font(.caption)
        }.padding().background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func save() {
        let now = Date(); let clean = callsign.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let enteredHz = UInt64((Double(frequencyMHz) ?? 0) * 1_000_000)
        let actualHz = radio.snapshot.connected ? radio.snapshot.frequencyHz : enteredHz
        let actualMode = radio.snapshot.connected ? radio.snapshot.mode : mode.uppercased()
        guard actualHz > 0, !actualMode.isEmpty else { return }
        let resolvedCountry = country.isEmpty ? features.cty.country(for: clean) : country
        let qso = QSO(id: radio.qsoIdentity(callsign: clean, at: now, frequencyHz: actualHz, mode: actualMode), callsign: clean,
                      frequencyHz: actualHz, mode: actualMode,
                      rstSent: rstSent, rstReceived: rstReceived, createdAt: now,
                      name: name, qth: qth, country: resolvedCountry, notes: notes)
        if logbook.save(qso) {
            features.enqueueWavelog(id: qso.id, adif: radio.adif(for: qso))
            callsign = ""; name = ""; qth = ""; country = ""; notes = ""
        }
    }
}

struct PanadapterView: View {
    @EnvironmentObject private var features: FeatureModel
    @EnvironmentObject private var radio: RadioModel
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                BrandHeader()
                HStack(spacing: 12) {
                    StatusBadge(status: features.spectrumDb.isEmpty ? "OFFLINE" : "LIVE")
                    Text(features.audioStatus).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        .accessibilityIdentifier("audioStatus")
                    Spacer()
                    if !features.spectrumDb.isEmpty {
                        Text(String(format: "%.0f kHz I/Q", features.audioSampleRate / 1_000))
                            .font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary)
                    }
                }
            if features.spectrumDb.isEmpty {
                ContentUnavailableView("No physical audio source", systemImage: "waveform.slash",
                    description: Text("Connect the stereo I/Q USB interface, then start capture. ShackCQ never substitutes generated spectrum data."))
                    .frame(maxWidth: .infinity, minHeight: 430).background(Color(red: 0.025, green: 0.035, blue: 0.05)).clipShape(RoundedRectangle(cornerRadius: 14))
            } else {
                PanadapterInstrument(bins: features.spectrumDb, waterfall: features.waterfallImage,
                    centreHz: radio.snapshot.frequencyHz, sampleRate: features.audioSampleRate,
                    noiseFloor: features.audioNoiseFloor, floorOffset: Float(features.panFloorOffsetDb),
                    rangeDb: Float(features.panRangeDb))
                    .frame(maxWidth: .infinity, minHeight: 500)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 22) {
                        measurement("PEAK", String(format: "%.1f dBFS", features.audioPeak))
                        measurement("FLOOR", String(format: "%.1f dBFS", features.audioNoiseFloor))
                        measurement("I RMS", String(format: "%.1f dBFS", features.audioIRms))
                        measurement("Q RMS", String(format: "%.1f dBFS", features.audioQRms))
                        measurement("I/Q CORR", String(format: "%+.3f", features.audioIQCorrelation))
                        measurement("SPAN", String(format: "±%.0f kHz", features.audioSampleRate / 2_000))
                        measurement("FRAMES", features.audioCapturedFrames.formatted())
                    }
                    .padding(.horizontal, 4)
                }
            }
                GroupBox("Display") {
                    VStack(spacing: 12) {
                        Picker("Waterfall palette", selection: $features.panPalette) {
                            ForEach(PanPalette.allCases) { Text($0.rawValue).tag($0) }
                        }.pickerStyle(.segmented)
                        LabeledContent("Dynamic range", value: "\(Int(features.panRangeDb)) dB")
                        Slider(value: $features.panRangeDb, in: 40...110, step: 2)
                        LabeledContent("Black level", value: String(format: "%+.0f dB from floor", features.panFloorOffsetDb))
                        Slider(value: $features.panFloorOffsetDb, in: -20...10, step: 1)
                        Toggle("Swap I/Q channels", isOn: $features.reverseSpectrum)
                    }
                }
                GroupBox("Physical I/Q input") {
                    VStack(alignment: .leading, spacing: 10) {
                        Picker("Audio input", selection: $features.selectedAudioInputUID) {
                            if features.audioInputs.isEmpty { Text("No audio inputs").tag("") }
                            ForEach(features.audioInputs) { input in
                                Text("\(input.name) · \(input.type)").tag(input.id)
                            }
                        }
                        Button("Scan audio devices") { Task { await features.refreshAudioInputs() } }
                        Text(features.audioStatus).font(.caption).foregroundStyle(.secondary)
                    }
                }
                HStack {
                    Button("Start I/Q capture") { Task { await features.startAudioCapture() } }.buttonStyle(.borderedProminent)
                    Button("Stop capture") { features.stopAudioCapture() }.buttonStyle(.bordered)
                }
            }
            .padding()
        }.navigationTitle("Panadapter").task { await features.refreshAudioInputs() }
    }

    private func measurement(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            Text(value).font(.system(.callout, design: .monospaced).weight(.medium))
        }.accessibilityIdentifier("metric.\(label.lowercased())")
    }
}

struct PanadapterInstrument: View {
    let bins: [Float]
    let waterfall: CGImage?
    let centreHz: UInt64
    let sampleRate: Double
    let noiseFloor: Float
    let floorOffset: Float
    let rangeDb: Float

    var body: some View {
        Canvas(rendersAsynchronously: true) { context, size in
            guard bins.count > 1 else { return }
            let spectrumHeight = size.height * 0.41
            let axisHeight: CGFloat = 30
            let waterfallTop = spectrumHeight + axisHeight
            let waterfallRect = CGRect(x: 0, y: waterfallTop, width: size.width, height: size.height - waterfallTop)
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(red: 0.018, green: 0.025, blue: 0.038)))

            if let waterfall {
                context.draw(Image(decorative: waterfall, scale: 1), in: waterfallRect)
            }

            let floor = noiseFloor + floorOffset
            for step in 0...4 {
                let y = spectrumHeight * CGFloat(step) / 4
                var line = Path(); line.move(to: CGPoint(x: 0, y: y)); line.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(line, with: .color(.white.opacity(step == 4 ? 0.16 : 0.08)), lineWidth: 0.5)
                let db = floor + rangeDb * Float(4 - step) / 4
                context.draw(Text(String(format: "%+.0f", db)).font(.system(size: 10, design: .monospaced)).foregroundStyle(.white.opacity(0.62)), at: CGPoint(x: 22, y: max(8, y - 7)), anchor: .leading)
            }
            for step in 0...8 {
                let x = size.width * CGFloat(step) / 8
                var line = Path(); line.move(to: CGPoint(x: x, y: 0)); line.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(line, with: .color(.white.opacity(step == 4 ? 0.22 : 0.065)), lineWidth: step == 4 ? 1 : 0.5)
            }

            var path = Path()
            for (index, bin) in bins.enumerated() {
                let x = size.width * CGFloat(index) / CGFloat(bins.count - 1)
                let normalized = max(0, min(1, (bin - floor) / rangeDb))
                let y = spectrumHeight * (1 - CGFloat(normalized))
                if index == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
            }
            var fill = path; fill.addLine(to: CGPoint(x: size.width, y: spectrumHeight)); fill.addLine(to: CGPoint(x: 0, y: spectrumHeight)); fill.closeSubpath()
            context.fill(fill, with: .linearGradient(Gradient(colors: [RigTheme.amber.opacity(0.30), RigTheme.amber.opacity(0.015)]), startPoint: .zero, endPoint: CGPoint(x: 0, y: spectrumHeight)))
            context.stroke(path, with: .color(Color(red: 1.0, green: 0.69, blue: 0.24)), lineWidth: 1.35)

            context.fill(Path(CGRect(x: 0, y: spectrumHeight, width: size.width, height: axisHeight)), with: .color(Color(red: 0.04, green: 0.055, blue: 0.075)))
            let halfSpan = sampleRate / 2
            let frequencies = [Double(centreHz) - halfSpan, Double(centreHz), Double(centreHz) + halfSpan]
            let anchors: [UnitPoint] = [.leading, .center, .trailing]
            let positions: [CGFloat] = [8, size.width / 2, size.width - 8]
            for index in 0..<3 {
                context.draw(Text(String(format: "%.3f", frequencies[index] / 1_000_000)).font(.system(size: 11, weight: index == 1 ? .semibold : .regular, design: .monospaced)).foregroundStyle(index == 1 ? RigTheme.amber : .white.opacity(0.68)), at: CGPoint(x: positions[index], y: spectrumHeight + axisHeight / 2), anchor: anchors[index])
            }
            context.draw(Text("MHz").font(.system(size: 9, weight: .medium)).foregroundStyle(.white.opacity(0.42)), at: CGPoint(x: size.width - 8, y: spectrumHeight + axisHeight - 4), anchor: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Live I Q panadapter and waterfall")
        .accessibilityValue(String(format: "Center %.3f megahertz, peak %.1f decibels full scale", Double(centreHz) / 1_000_000, bins.max() ?? -140))
    }
}

struct SpotsView: View {
    @EnvironmentObject private var features: FeatureModel
    @EnvironmentObject private var radio: RadioModel
    var body: some View {
        List {
            Section {
                HStack { StatusBadge(status: features.clusterStatus.hasPrefix("Connected") ? "LIVE" : "OFFLINE"); Text(features.clusterStatus).font(.caption) }
            }
            Section("Live cluster opportunities") {
                if features.dx.liveSpots.isEmpty {
                    ContentUnavailableView("No live spots", systemImage: "dot.radiowaves.up.forward", description: Text("Connect the configured DX cluster. No fixture spots are loaded."))
                }
                ForEach(features.dx.liveSpots) { spot in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack { Text(spot.callsign).font(.headline); if spot.watchlisted { Image(systemName: "star.fill").foregroundStyle(RigTheme.amber) }; Spacer(); Text(spot.displayBand) }
                        Text(String(format: "%.3f MHz · %@ · %@", Double(spot.frequencyHz) / 1_000_000, spot.mode, spot.country)).foregroundStyle(.secondary)
                        if !spot.comment.isEmpty { Text(spot.comment).font(.caption) }
                        HStack { Text("via \(spot.spotter)").font(.caption2).foregroundStyle(.secondary); Spacer(); Button("Tune") { if dxDirectTuneAvailable(spot.frequencyHz) { radio.sendCAT(String(format: "FA%011llu;", spot.frequencyHz)) } }.disabled(!dxDirectTuneAvailable(spot.frequencyHz)) }
                        if !dxDirectTuneAvailable(spot.frequencyHz) { Text("Observation only above 6 m · configure a supported radio/transverter path before direct CAT tuning.").font(.caption2).foregroundStyle(.secondary) }
                    }.padding(.vertical, 5)
                }
            }
        }.navigationTitle("Spots")
    }
}

private enum DXSurface: String, CaseIterable, Identifiable {
    case live = "LIVE", smart = "SMART", bandmap = "BANDMAP", pulse = "PULSE", world = "WORLD", watch = "WATCH"
    var id: String { rawValue }
}

struct DXView: View {
    @EnvironmentObject private var features: FeatureModel
    @EnvironmentObject private var radio: RadioModel
    @State private var surface: DXSurface = .smart
    @State private var selected: DXOpportunity?
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            BrandHeader()
            HStack {
                metric("5 min", features.dx.spots5m)
                metric("60 min", features.dx.spots60m)
                metric("Watch", features.dx.watchlistHits)
                metric("Surges", features.dx.surgingBands)
            }
            GroupBox("NOAA space weather") {
                if features.dx.solar.valid {
                    HStack { LabeledContent("SFI", value: String(format: "%.1f", features.dx.solar.flux)); LabeledContent("Kp", value: String(format: "%.1f", features.dx.solar.kpIndex)) }
                } else { Text("No current NOAA reading loaded").foregroundStyle(.secondary) }
                Button("Refresh NOAA") { Task { await features.refreshSolar() } }
            }
            Picker("DX view", selection: $surface) { ForEach(DXSurface.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
            Text(features.dx.safeWorkedLog.loaded ?
                (features.dx.safeWorkedLog.complete ? "Log intelligence ready" : "Log intelligence partial") :
                "Log intelligence loading")
                .font(.caption).foregroundStyle(.secondary)
            dxContent
        }.padding() }.navigationTitle("DX Watcher")
            .sheet(item: $selected) { opportunity in DXOpportunityDetail(opportunity: opportunity, tune: tune) }
    }
    private func metric(_ name: String, _ value: UInt) -> some View {
        VStack { Text("\(value)").font(.title2.bold()); Text(name).font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity).padding().background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder private var dxContent: some View {
        switch surface {
        case .live: opportunityList(features.dx.liveSpots)
        case .smart: opportunityList(features.dx.opportunities)
        case .watch: opportunityList(features.dx.watchActivity)
        case .bandmap: DXBandmap(bands: features.dx.bands)
        case .pulse: DXPulse(bands: features.dx.bands, timeline: features.dx.bandTimeline)
        case .world: DXWorld(grid: features.dx.worldGrid, regions: features.dx.regions)
        }
    }

    private func opportunityList(_ rows: [DXOpportunity]) -> some View {
        LazyVStack(spacing: 8) {
            if rows.isEmpty { ContentUnavailableView("Learning from live cluster history", systemImage: "scope", description: Text("Connect the DX cluster; no generated spots are displayed.")) }
            ForEach(rows) { row in
                Button { selected = row } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack { Text(row.callsign).font(.headline); if row.watchlisted { Image(systemName: "star.fill").foregroundStyle(RigTheme.amber) }; Text(row.displayBand).font(.caption.monospaced()).foregroundStyle(.secondary) }
                            Text(String(format: "%.3f MHz · %@ · %@", Double(row.frequencyHz) / 1_000_000, row.mode, row.country)).foregroundStyle(.secondary)
                            Text(row.safeReason).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Spacer()
                        VStack(alignment: .trailing) { Text("\(row.score)").font(.title3.bold()); Text("\(row.confidence)%").font(.caption).foregroundStyle(.secondary) }
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain).padding(12).background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    private func tune(_ row: DXOpportunity) {
        guard dxDirectTuneAvailable(row.frequencyHz) else { return }
        radio.sendCAT(String(format: "FA%011llu;", row.frequencyHz))
    }
}

private struct DXBandmap: View {
    let bands: [DXBand]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Band activity · last 60 minutes").font(.headline)
            let maximum = max(1, bands.map(\.spots60m).max() ?? 1)
            ForEach(bands) { band in
                HStack(spacing: 10) {
                    Text(band.band).font(.system(.body, design: .monospaced).bold()).frame(width: 42, alignment: .leading)
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.secondary.opacity(0.12))
                            Capsule().fill(band.surge ? Color.red : RigTheme.amber).frame(width: geometry.size.width * CGFloat(band.spots60m) / CGFloat(maximum))
                        }
                    }.frame(height: 12)
                    Text("\(band.spots5m)/\(band.spots60m)").font(.caption.monospacedDigit()).frame(width: 58, alignment: .trailing)
                    if band.surge { Text("SURGE").font(.caption2.bold()).foregroundStyle(.red) }
                }.frame(minHeight: 30)
            }
        }.padding(16).background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

private struct DXPulse: View {
    let bands: [DXBand]
    let timeline: [[UInt]]
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text("Activity pulse").font(.headline); Spacer(); Text("60 min ago → now").font(.caption).foregroundStyle(.secondary) }
            let maximum = max(1, timeline.flatMap { $0 }.max() ?? 1)
            ForEach(Array(bands.enumerated()), id: \.element.id) { index, band in
                HStack(spacing: 6) {
                    Text(band.band).font(.caption.monospaced().bold()).frame(width: 38, alignment: .leading)
                    let row = index < timeline.count ? timeline[index] : []
                    ForEach(Array(row.enumerated()), id: \.offset) { _, value in
                        RoundedRectangle(cornerRadius: 3).fill(heat(value, maximum: maximum)).frame(maxWidth: .infinity, minHeight: 26)
                            .overlay(Text(value == 0 ? "" : "\(value)").font(.caption2).foregroundStyle(.white))
                    }
                }
            }
        }.padding(16).background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
    }
    private func heat(_ value: UInt, maximum: UInt) -> Color {
        let level = Double(value) / Double(maximum)
        return level == 0 ? Color.white.opacity(0.04) : RigTheme.amber.opacity(0.25 + level * 0.75)
    }
}

private struct DXWorld: View {
    let grid: [[UInt]]
    let regions: [DXRegion]
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("World activity · 60 minutes · west → east").font(.headline)
            let maximum = max(1, grid.flatMap { $0 }.max() ?? 1)
            VStack(spacing: 5) {
                ForEach(Array(grid.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 5) {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, value in
                            RoundedRectangle(cornerRadius: 4).fill(value == 0 ? Color.white.opacity(0.035) : RigTheme.amber.opacity(0.22 + 0.78 * Double(value) / Double(maximum)))
                                .aspectRatio(1.25, contentMode: .fit).overlay(Text(value == 0 ? "" : "\(value)").font(.caption2))
                        }
                    }
                }
            }
            Text("Region pulse · 15m : 60m").font(.headline)
            ForEach(regions) { region in
                VStack(alignment: .leading, spacing: 4) {
                    HStack { Text(region.region).font(.subheadline.bold()); Spacer(); Text("\(region.spots15m) : \(region.spots60m) · \(region.uniqueCalls) calls").font(.caption.monospacedDigit()); if region.anomaly { Text("ANOMALY").font(.caption2.bold()).foregroundStyle(.red) } }
                    ProgressView(value: Double(min(region.activityPercent, 300)), total: 300).tint(region.anomaly ? .red : RigTheme.amber)
                }
            }
        }.padding(16).background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

private struct DXOpportunityDetail: View {
    let opportunity: DXOpportunity
    let tune: (DXOpportunity) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                Section("Target") {
                    LabeledContent("Callsign", value: opportunity.callsign)
                    LabeledContent("Frequency", value: String(format: "%.3f MHz", Double(opportunity.frequencyHz) / 1_000_000))
                    LabeledContent("Mode / band", value: "\(opportunity.mode) · \(opportunity.displayBand)")
                    LabeledContent("Entity", value: opportunity.country)
                    LabeledContent("Path", value: opportunity.pathState.isEmpty ? "Unknown" : opportunity.pathState)
                    LabeledContent("Bearing / distance", value: "\(opportunity.bearingDegrees)° · \(opportunity.distanceKm) km")
                }
                Section("Operator interpretation") {
                    Text(opportunity.safeReason)
                    LabeledContent("Score", value: "\(opportunity.score)")
                    LabeledContent("Confidence", value: "\(opportunity.confidence)%")
                    LabeledContent("Worked", value: [opportunity.workedCountry ? "entity" : nil, opportunity.workedCall ? "call" : nil, opportunity.workedBand ? "band" : nil, opportunity.workedMode ? "mode" : nil, opportunity.workedBandMode ? "band+mode" : nil].compactMap { $0 }.joined(separator: ", ").ifEmpty("No match"))
                }
                if !dxDirectTuneAvailable(opportunity.frequencyHz) {
                    Text("Observation only above 6 m · configure a supported radio/transverter path before direct CAT tuning.").font(.caption).foregroundStyle(.secondary)
                }
                Button("Tune VFO A") { tune(opportunity); dismiss() }.buttonStyle(.borderedProminent)
                    .disabled(!dxDirectTuneAvailable(opportunity.frequencyHz))
            }.navigationTitle(opportunity.callsign).toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private extension String { func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self } }

struct LookupView: View {
    @EnvironmentObject private var features: FeatureModel
    @State private var callsign = ""
    var body: some View {
        Form {
            Section("Live callbook lookup") {
                HStack {
                    TextField("Callsign", text: $callsign).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    Button("Lookup") { Task { await features.callbook.lookup(callsign) } }
                }
                Text(features.callbook.status).font(.caption).foregroundStyle(.secondary)
            }
            if let result = features.callbook.result {
                Section(result.callsign) {
                    LabeledContent("Name", value: result.name)
                    LabeledContent("Location", value: result.location)
                    LabeledContent("Country", value: result.country)
                    LabeledContent("Grid", value: result.grid)
                    if !result.latitude.isEmpty { LabeledContent("Coordinates", value: "\(result.latitude), \(result.longitude)") }
                }
            }
        }.navigationTitle("Lookup")
    }
}

private enum SettingsTab: String, CaseIterable, Identifiable {
    case defaults = "Default", log = "Log", cluster = "Cluster", macros = "Macros", alerts = "Alerts"
    case safety = "Safety", audio = "Audio", integrations = "Integrations", health = "Health", diag = "Diag", about = "About"
    var id: String { rawValue }
}

struct SettingsView: View {
    @Environment(\.openURL) private var openURL
    @EnvironmentObject private var radio: RadioModel
    @EnvironmentObject private var features: FeatureModel
    @EnvironmentObject private var logbook: QSOStore
    @State private var tab: SettingsTab = .defaults
    @State private var importingADIF = false
    @State private var exportURL: URL?
    @AppStorage("macroLabel1") private var macroLabel1 = "CQ"
    @AppStorage("macroLabel2") private var macroLabel2 = "EXCH"
    @AppStorage("macroLabel3") private var macroLabel3 = "TU"
    @AppStorage("macroText1") private var macroText1 = ""
    @AppStorage("macroText2") private var macroText2 = ""
    @AppStorage("macroText3") private var macroText3 = ""
    @AppStorage("alertSounds") private var alertSounds = false
    @AppStorage("quietAlerts") private var quietAlerts = false
    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SettingsTab.allCases) { item in
                        Button(item.rawValue) { tab = item }
                            .buttonStyle(.bordered).tint(tab == item ? RigTheme.amber : .secondary)
                            .accessibilityIdentifier("settingsTab\(item.rawValue)")
                    }
                }.padding(.horizontal).padding(.vertical, 10)
            }
            .accessibilityIdentifier("settingsTabs")
            .background(RigTheme.panel)
            Form { tabContent }
        }
        .navigationTitle("Settings")
        .fileImporter(isPresented: $importingADIF, allowedContentTypes: [.data, .plainText]) { result in
            if case .success(let url) = result { _ = logbook.importADIF(from: url) }
        }
    }

    @ViewBuilder private var tabContent: some View {
        switch tab {
        case .defaults:
            Section("Save configuration") {
                Button("Save settings") {
                    radio.saveSettings()
                    features.saveSettings()
                }
                .buttonStyle(.borderedProminent)
                Text(features.settingsStatus).font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("settingsSaveStatus")
            }
            Section("Radio profile") {
                LabeledContent("Radio", value: radio.snapshot.connected ? radio.snapshot.model : "Awaiting ID response")
                    .accessibilityIdentifier("settingsRadioModel")
                LabeledContent("Mode", value: "Real hardware only")
            }
            Section("Operator defaults") {
                TextField("Operator callsign", text: $features.operatorCallsign)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                TextField("DX watchlist callsigns", text: $features.watchlist, axis: .vertical)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
            }
            Section("CTY.DAT") {
                LabeledContent("Status", value: features.cty.status)
                    .accessibilityIdentifier("ctyStatus")
                Button("Update CTY.DAT") { Task { await features.cty.update() } }
            }
        case .log:
            Section("Local tablet log") {
                LabeledContent("SQLite QSOs", value: "\(logbook.records.count) recent")
                HStack {
                    Button("Import ADIF") { importingADIF = true }
                    Button("Build ADIF export") { exportURL = logbook.exportADIF(using: radio.adif) }
                    if let exportURL { ShareLink(item: exportURL) { Text("Share ADIF") } }
                }.buttonStyle(.borderless)
                Text(logbook.message).font(.caption).foregroundStyle(.secondary)
            }
            Section("Wavelog") {
                TextField("Wavelog HTTPS base URL", text: Binding(get: { features.wavelog.baseURL }, set: { features.wavelog.baseURL = $0 }))
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("API key", text: Binding(get: { features.wavelog.apiKey }, set: { features.wavelog.apiKey = $0 }))
                if features.wavelog.stations.isEmpty {
                    TextField("Station profile ID", text: Binding(get: { features.wavelog.stationProfile }, set: { features.wavelog.stationProfile = $0 }))
                } else {
                    Picker("Station", selection: Binding(get: { features.wavelog.stationProfile }, set: features.wavelog.selectStation)) {
                        ForEach(features.wavelog.stations) { Text($0.label).tag($0.id) }
                    }
                }
                HStack {
                    Button("Sync queue") { Task { await features.wavelog.syncNow() } }
                    Button("Full log") { Task { await features.wavelog.fullSync() } }
                }.buttonStyle(.borderless)
                LabeledContent("Pending", value: "\(features.wavelog.pendingCount)")
                LabeledContent("Cached remote QSOs", value: "\(features.wavelog.contacts.count)")
                Text(features.wavelog.status).font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("wavelogStatus")
            }
            Section("Callbook enrichment") {
                Picker("Provider", selection: Binding(get: { features.callbook.provider }, set: { features.callbook.provider = $0 })) {
                    Text("QRZ.com").tag("QRZ"); Text("HamQTH").tag("HamQTH")
                }
                TextField("Username", text: Binding(get: { features.callbook.username }, set: { features.callbook.username = $0 }))
                SecureField("Password", text: Binding(get: { features.callbook.password }, set: { features.callbook.password = $0 }))
                Text("Log → Enrich fills Name, QTH and Country before the local save and Wavelog queue.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        case .cluster:
            Section("DX cluster endpoints") {
                TextField("Operator callsign", text: $features.operatorCallsign).textInputAutocapitalization(.characters).autocorrectionDisabled()
                endpointFields("Primary", host: $features.clusterHost, port: $features.clusterPort)
                endpointFields("Fallback 1", host: $features.clusterFallbackHost, port: $features.clusterFallbackPort)
                endpointFields("Fallback 2", host: $features.clusterFallback2Host, port: $features.clusterFallback2Port)
                TextField("Watchlist callsigns", text: $features.watchlist, axis: .vertical).textInputAutocapitalization(.characters).autocorrectionDisabled()
                HStack { Button("Connect") { features.connectCluster() }; Button("Disconnect") { features.disconnectCluster() } }
                    .buttonStyle(.borderless)
                Text(features.clusterStatus).font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("clusterStatus")
            }
        case .integrations:
            GroupsIoSettingsSection()
        case .macros:
            Section("CW macros") {
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 10) {
                    GridRow {
                        Text("Button label")
                        Text("CW message")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)

                    macroRow(index: 1, label: $macroLabel1, text: $macroText1)
                    macroRow(index: 2, label: $macroLabel2, text: $macroText2)
                    macroRow(index: 3, label: $macroLabel3, text: $macroText3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("cwMacroGrid")
                Text("Macros are stored locally. Transmission remains subject to the Safety controls and a live CW-mode radio session.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        case .alerts:
            Section("Alerts") {
                Toggle("Audible opportunity tones", isOn: $alertSounds)
                Toggle("Quiet non-critical alerts", isOn: $quietAlerts)
                Button("Refresh NOAA conditions") { Task { await features.refreshSolar() } }
                Text(features.clusterStatus).font(.caption).foregroundStyle(.secondary)
            }
        case .safety:
            Section("Transmit safety") {
                Text("ShackCQ never transmits on launch. Transmit-capable CAT actions require a live radio session and deliberate operator action.")
                Button("Force receive now") { radio.sendCAT("RX;") }.disabled(!radio.snapshot.connected)
                    .buttonStyle(.borderedProminent)
                LabeledContent("Radio", value: radio.snapshot.connected ? "Connected" : "Disconnected")
                LabeledContent("State", value: radio.snapshot.transmitting ? "TRANSMITTING" : "Receive")
            }
        case .audio:
            Section("USB receive audio") {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: 24) {
                        audioInputColumn
                            .frame(minWidth: 260, maxWidth: .infinity, alignment: .topLeading)
                        Divider()
                        audioCaptureColumn
                            .frame(minWidth: 260, maxWidth: .infinity, alignment: .topLeading)
                    }
                    VStack(alignment: .leading, spacing: 20) {
                        audioInputColumn
                        Divider()
                        audioCaptureColumn
                    }
                }
                .padding(.vertical, 4)
                .accessibilityIdentifier("audioSettingsLayout")
            }
        case .health:
            Section("System health") {
                LabeledContent("CAT / USB", value: radio.snapshot.connected ? "LIVE" : "OFFLINE")
                LabeledContent("DX cluster", value: features.clusterStatus)
                LabeledContent("Wavelog", value: features.wavelog.status)
                LabeledContent("Audio", value: features.audioStatus)
                LabeledContent("Local database", value: "\(logbook.records.count) recent QSOs")
                LabeledContent("CTY", value: features.cty.status)
            }
        case .diag:
            Section("Physical Apple hardware") {
                LabeledContent("Driver", value: "Elecraft KXUSB / PL2303GC · DriverKit")
                Text("Driver approval is controlled by iPadOS, not by this screen. Open iPad Settings, choose Drivers, enable ShackCQ Prolific KXUSB Driver, then reconnect the cable.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Open iPad Settings to enable driver") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        openURL(url)
                    }
                }
                Picker("Serial port", selection: $radio.selectedPort) {
                    if radio.serialPorts.isEmpty { Text("No physical port").tag("") }
                    ForEach(radio.serialPorts, id: \.self) { Text(($0 as NSString).lastPathComponent).tag($0) }
                }
                HStack {
                    Button("Scan") { radio.refreshPorts() }
                    Button("Connect") { radio.connect() }.disabled(radio.selectedPort.isEmpty)
                    Button("Disconnect") { radio.disconnect() }
                }
                .buttonStyle(.borderless)
                Text(radio.transportStatus).font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("settingsSerialStatus")
                Text("Use Radio for frequency, mode and raw KX3 CAT commands.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Software") { LabeledContent("Shared core", value: radio.coreVersion) }
            Section("Required service tests") {
                HStack {
                    Button("Test Wavelog") { Task { await features.wavelog.testConnection() } }
                    Button("Test QRZ / HamQTH") { Task { await features.callbook.testConnection() } }
                    Button("Check time sync") { Task { await features.wavelog.checkDeviceTime() } }
                    Button("Load stations") { Task { await features.wavelog.loadStations() } }
                }.buttonStyle(.borderless)
                Text(features.wavelog.status).font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("wavelogStatus")
                Text(features.callbook.status).font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("callbookStatus")
            }
        case .about:
            Section("ShackCQ") {
                LabeledContent("Product", value: "Radio. Spectrum. Spots. Logs.")
                LabeledContent("Shared core", value: radio.coreVersion)
                Text("Local-first tablet control and logging for real radio hardware. Wavelog, QRZ.com, HamQTH, CTY.DAT and DX-cluster integrations are optional.")
                    .font(.caption).foregroundStyle(.secondary)
                Text("WSJT-X is intentionally not exposed in Settings yet.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Developer") {
                HStack(alignment: .top, spacing: 20) {
                    Image("DeveloperPortrait")
                        .resizable()
                        .scaledToFill()
                        .frame(width: 112, height: 112)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .accessibilityLabel("Oliver Bross, OM0RX")
                        .accessibilityIdentifier("developerPortrait")

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Oliver Bross, OM0RX")
                            .font(.headline)
                        Text("Amateur radio operator licensed since 2000.")
                            .foregroundStyle(.secondary)
                        Text("ShackCQ is built by a radio amateur for real portable and station operating.")
                            .fixedSize(horizontal: false, vertical: true)
                        Link("stationpilot.app", destination: URL(string: "https://stationpilot.app")!)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.vertical, 4)
                .accessibilityIdentifier("developerInformation")
            }
        }
    }

    private func endpointFields(_ title: String, host: Binding<String>, port: Binding<String>) -> some View {
        HStack { TextField("\(title) host", text: host).textInputAutocapitalization(.never).autocorrectionDisabled()
            TextField("Port", text: port).keyboardType(.numberPad).frame(maxWidth: 120) }
    }

    private func macroRow(index: Int, label: Binding<String>, text: Binding<String>) -> some View {
        GridRow {
            TextField("Button label", text: label)
                .frame(width: 140)
                .accessibilityIdentifier("cwMacroLabel\(index)")
            TextField("CW message", text: text)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("cwMacroMessage\(index)")
        }
        .textFieldStyle(.roundedBorder)
    }

    private var audioInputColumn: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Input device", systemImage: "cable.connector")
                .font(.headline)
            Picker("Audio input", selection: $features.selectedAudioInputUID) {
                if features.audioInputs.isEmpty { Text("No physical input").tag("") }
                ForEach(features.audioInputs) { Text("\($0.name) · \($0.type)").tag($0.id) }
            }
            .pickerStyle(.menu)
            .frame(maxWidth: 320, alignment: .leading)
            .accessibilityIdentifier("audioInputPicker")
            Button("Scan devices") { Task { await features.refreshAudioInputs() } }
                .buttonStyle(.bordered)
            Text(features.audioStatus)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("audioSettingsStatus")
        }
    }

    private var audioCaptureColumn: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("I/Q capture", systemImage: "waveform")
                .font(.headline)
            LabeledContent("Sample rate", value: "\(Int(features.audioSampleRate)) Hz")
            LabeledContent("Channels", value: "Stereo I/Q required")
            HStack(spacing: 12) {
                Button("Start capture") { Task { await features.startAudioCapture() } }
                    .buttonStyle(.borderedProminent)
                Button("Stop") { features.stopAudioCapture() }
                    .buttonStyle(.bordered)
            }
            .accessibilityIdentifier("audioCaptureControls")
        }
    }
}
