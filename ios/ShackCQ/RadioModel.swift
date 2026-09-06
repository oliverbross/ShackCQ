import Foundation

struct RadioSnapshot: Equatable {
    let identity: String
    let model: String
    let mode: String
    let frequencyHz: UInt64
    let frequencyBHz: UInt64
    let connected: Bool
    let transmitting: Bool
    let meter: Int
    let swrTenths: Int
    let rfOutputTenths: Int
    let afGain: Int
    let rfGain: Int
    let bandwidthHz: Int
    let powerW: Int
    let preamp: Bool
    let attenuator: Bool
    let rit: Bool
    let xit: Bool
    let split: Bool

    var status: String { connected ? "LIVE" : "OFFLINE" }
    var frequencyText: String {
        String(format: "%.3f MHz", Double(frequencyHz) / 1_000_000.0)
    }
}

final class RadioCore {
    private let context: OpaquePointer

    init() {
        guard let created = shackcq_context_create() else { fatalError("Shared core unavailable") }
        context = created
    }

    deinit { shackcq_context_destroy(context) }

    func snapshot() -> RadioSnapshot {
        var state = shackcq_context_state(context)
        return RadioSnapshot(
            identity: Self.string(from: &state.identity),
            model: Self.string(from: &state.model),
            mode: Self.string(from: &state.mode),
            frequencyHz: state.vfo_a_hz,
            frequencyBHz: state.vfo_b_hz,
            connected: state.connected != 0,
            transmitting: state.transmitting != 0,
            meter: Int(state.meter),
            swrTenths: Int(state.swr_tenths),
            rfOutputTenths: Int(state.rf_output_tenths),
            afGain: Int(state.af_gain),
            rfGain: Int(state.rf_gain),
            bandwidthHz: Int(state.bandwidth_hz),
            powerW: Int(state.power_w),
            preamp: state.preamp != 0,
            attenuator: state.attenuator != 0,
            rit: state.rit != 0,
            xit: state.xit != 0,
            split: state.split != 0
        )
    }

    func feed(_ data: Data) -> Int {
        data.withUnsafeBytes { bytes in
            guard let base = bytes.baseAddress?.assumingMemoryBound(to: CChar.self) else { return 0 }
            return Int(shackcq_context_feed(context, base, bytes.count))
        }
    }

    func reset() -> RadioSnapshot {
        shackcq_context_reset(context)
        return snapshot()
    }

    func identity(callsign: String, date: Date, frequencyHz: UInt64, mode: String) -> String {
        var output = [CChar](repeating: 0, count: 32)
        let iso = ISO8601DateFormatter().string(from: date)
        let ok = callsign.withCString { call in
            iso.withCString { timestamp in
                mode.withCString { value in
                    shackcq_qso_identity(&output, output.count, call, timestamp, frequencyHz, value)
                }
            }
        }
        return ok == 1 ? String(cString: output) : ""
    }

    func adif(for qso: QSO) -> String {
        var output = [CChar](repeating: 0, count: 768)
        let day = Self.dayFormatter.string(from: qso.createdAt)
        let time = Self.timeFormatter.string(from: qso.createdAt)
        let length = qso.id.withCString { identity in
            qso.callsign.withCString { callsign in
                day.withCString { date in
                    time.withCString { clock in
                        qso.mode.withCString { mode in
                            qso.rstSent.withCString { sent in
                                qso.rstReceived.withCString { received in
                                    shackcq_adif_serialize(&output, output.count, identity, callsign, date, clock,
                                                      qso.frequencyHz, mode, sent, received)
                                }
                            }
                        }
                    }
                }
            }
        }
        var adif = length > 0 ? String(cString: output) : ""
        for (name, value) in [("NAME", qso.name), ("QTH", qso.qth),
                              ("COUNTRY", qso.country), ("COMMENT", qso.notes)] where !value.isEmpty {
            guard let end = adif.range(of: "<EOR>", options: .caseInsensitive) else { continue }
            adif.insert(contentsOf: "<\(name):\(value.utf8.count)>\(value)", at: end.lowerBound)
        }
        var reserved = Set(["APP_KX3TOUCH_UUID", "CALL", "QSO_DATE", "TIME_ON", "FREQ", "MODE", "RST_SENT", "RST_RCVD", "NAME", "QTH", "COUNTRY"])
        if !qso.notes.isEmpty { reserved.insert("COMMENT") }
        for (name, value) in qso.fields.sorted(by: { $0.key < $1.key }) where !value.isEmpty && !reserved.contains(name.uppercased()) {
            guard let end = adif.range(of: "<EOR>", options: .caseInsensitive) else { continue }
            adif.insert(contentsOf: "<\(name.uppercased()):\(value.utf8.count)>\(value)", at: end.lowerBound)
        }
        return adif
    }

    var version: String { String(cString: shackcq_core_version()) }

    private static func string<T>(from tuple: inout T) -> String {
        withUnsafeBytes(of: &tuple) { bytes in
            guard let base = bytes.baseAddress else { return "" }
            return String(cString: base.assumingMemoryBound(to: CChar.self))
        }
    }

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter(); formatter.timeZone = .gmt; formatter.dateFormat = "yyyyMMdd"; return formatter
    }()
    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter(); formatter.timeZone = .gmt; formatter.dateFormat = "HHmmss"; return formatter
    }()
}

@MainActor
final class RadioModel: ObservableObject {
    private let core = RadioCore()
    private let transport = SerialTransport()
    @Published private(set) var snapshot: RadioSnapshot
    @Published private(set) var serialPorts: [String] = []
    @Published private(set) var transportStatus = "Driver ready; no serial port scanned"
    @Published var pendingTransmitCommand: String?
    @Published var selectedPort: String {
        didSet { defaults.set(selectedPort, forKey: "selectedSerialPort") }
    }
    private let defaults = UserDefaults.standard
    private var wantsConnection: Bool
    private var connectedAt: Date?
    private var lastCATResponseAt: Date?

    init() {
        snapshot = core.snapshot()
        selectedPort = defaults.string(forKey: "selectedSerialPort") ?? ""
        wantsConnection = defaults.bool(forKey: "radioAutoConnect") &&
            !ProcessInfo.processInfo.arguments.contains("--disable-radio-autoconnect")
        transport.onData = { [weak self] data in
            Task { @MainActor in self?.acceptCAT(data) }
        }
        transport.onStatus = { [weak self] status in
            Task { @MainActor in self?.transportStatus = status }
        }
        refreshPorts()
    }

    deinit { if transport.isConnected { try? transport.send("RX;") }; transport.disconnect() }

    func acceptCAT(_ data: Data) {
        if core.feed(data) > 0 {
            snapshot = core.snapshot()
            lastCATResponseAt = Date()
        }
    }

    func refreshPorts() {
        guard !transport.isConnected else {
            if serialPorts.isEmpty, !selectedPort.isEmpty { serialPorts = [selectedPort] }
            return
        }
        serialPorts = SerialTransport.serialPorts()
        if !serialPorts.contains(selectedPort) { selectedPort = serialPorts.first ?? "" }
        transportStatus = serialPorts.isEmpty
            ? "No active PL2303/KXUSB DriverKit service is exposed. Enable the updated driver or reconnect the cable."
            : "Found \(serialPorts.count) physical serial endpoint(s): \(serialPorts.map { ($0 as NSString).lastPathComponent }.joined(separator: ", "))"
    }

    func connect() {
        wantsConnection = true
        defaults.set(true, forKey: "radioAutoConnect")
        connectNow()
    }

    private func connectNow() {
        guard !transport.isConnected else { return }
        do {
            try transport.connect(path: selectedPort)
            connectedAt = Date()
            lastCATResponseAt = nil
            try transport.send("ID;")
            try transport.send("FA;")
            try transport.send("MD;")
        } catch {
            transport.disconnect()
            connectedAt = nil
            transportStatus = error.localizedDescription
        }
    }

    func disconnect() {
        wantsConnection = false
        defaults.set(false, forKey: "radioAutoConnect")
        if transport.isConnected { try? transport.send("RX;") }
        pendingTransmitCommand = nil
        transport.disconnect()
        connectedAt = nil
        lastCATResponseAt = nil
        snapshot = core.reset()
        transportStatus = "Disconnected"
    }

    func maintainConnection() {
        if transport.isConnected {
            let reference = lastCATResponseAt ?? connectedAt ?? Date()
            if Date().timeIntervalSince(reference) <= 5 { return }
            transportStatus = "KX3 did not answer CAT; reopening the physical connection…"
            try? transport.send("RX;")
            transport.disconnect()
            connectedAt = nil
            lastCATResponseAt = nil
        }
        let available = SerialTransport.serialPorts()
        serialPorts = available
        if !available.contains(selectedPort) { selectedPort = available.first ?? "" }
        guard wantsConnection, !transport.isConnected else { return }
        guard !selectedPort.isEmpty else {
            transportStatus = "Waiting for the PL2303GC/KXUSB DriverKit service…"
            return
        }
        connectNow()
    }

    func saveSettings() {
        defaults.set(selectedPort, forKey: "selectedSerialPort")
        defaults.set(wantsConnection, forKey: "radioAutoConnect")
        defaults.synchronize()
    }

    func sendCAT(_ command: String) {
        let normalized = command.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let framed = normalized.hasSuffix(";") ? normalized : normalized + ";"
        let commandClass = framed.withCString { shackcq_classify_command($0) }
        guard commandClass != SHACKCQ_COMMAND_TRANSMIT else {
            pendingTransmitCommand = framed
            transportStatus = "Transmit-capable CAT command requires explicit one-shot confirmation."
            return
        }
        sendFramedCAT(framed)
    }

    func resolvePendingTransmit(accept: Bool) {
        guard let framed = pendingTransmitCommand else { return }
        pendingTransmitCommand = nil
        guard accept else { transportStatus = "Transmit-capable CAT command cancelled"; return }
        let commandClass = framed.withCString { shackcq_classify_command($0) }
        guard commandClass == SHACKCQ_COMMAND_TRANSMIT else {
            transportStatus = "CAT command changed classification and was not sent"
            return
        }
        sendFramedCAT(framed)
    }

    private func sendFramedCAT(_ framed: String) {
        do {
            try transport.send(framed)
            transportStatus = "Sent: \(framed)"
        } catch {
            transportStatus = error.localizedDescription
        }
    }

    func setFrequencyMHz(_ value: String) {
        guard let mhz = Double(value), mhz > 0 else {
            transportStatus = "Enter a valid frequency in MHz."
            return
        }
        let hz = UInt64((mhz * 1_000_000).rounded())
        sendCAT(String(format: "FA%011llu;", hz))
    }

    func setMode(code: String) {
        sendCAT("MD\(code);")
    }
    func qsoIdentity(callsign: String, at date: Date, frequencyHz: UInt64, mode: String) -> String {
        core.identity(callsign: callsign, date: date, frequencyHz: frequencyHz, mode: mode)
    }
    func adif(for qso: QSO) -> String { core.adif(for: qso) }
    var coreVersion: String { core.version }
}
