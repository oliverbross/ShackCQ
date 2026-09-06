import AVFoundation
import Combine
import CoreGraphics
import Foundation
import Network

struct AudioInputChoice: Identifiable, Hashable {
    let id: String
    let name: String
    let type: String
}

struct DXBand: Codable, Identifiable {
    let band: String
    let spots5m: UInt
    let spots60m: UInt
    let uniqueCalls: UInt
    let surgePercent: UInt
    let surge: Bool
    var id: String { band }
}

struct DXOpportunity: Codable, Identifiable {
    let callsign: String
    let spotter: String
    let frequencyHz: UInt64
    let receivedEpoch: Int64
    let band: String
    let mode: String
    let country: String
    let continent: String
    let comment: String
    let score: UInt
    let confidence: UInt
    let samples: UInt
    let watchlisted: Bool
    let workedCountry: Bool
    let workedCall: Bool
    let workedBand: Bool
    let workedMode: Bool
    let workedBandMode: Bool
    let recentDupe: Bool
    let distanceKm: UInt
    let bearingDegrees: UInt
    let pathState: String
    let reason: String
    let workedIndexComplete: Bool?
    var id: String { "\(callsign)-\(frequencyHz)-\(receivedEpoch)" }
    var safeReason: String {
        workedIndexComplete != true && reason == "NEW ENTITY IN LOGBOOK" ? "FRESH CLUSTER ACTIVITY" : reason
    }
    var displayBand: String {
        guard band == "3cm" else { return band }
        let qo100 = (10_489_500_000...10_490_000_000).contains(frequencyHz) ||
            comment.localizedCaseInsensitiveContains("QO-100") ||
            comment.localizedCaseInsensitiveContains("QO100")
        return qo100 ? "3cm · QO-100" : band
    }
}

struct DXRegion: Codable, Identifiable {
    let region: String
    let spots15m: UInt
    let spots60m: UInt
    let uniqueCalls: UInt
    let activityPercent: UInt
    let anomaly: Bool
    var id: String { region }
}

struct DXSolar: Codable {
    let valid: Bool
    let flux: Float
    let aIndex: Float
    let kpIndex: Float
}

struct DXWorkedLog: Codable {
    let loaded: Bool
    let complete: Bool
    let cells: UInt
    let records: UInt?
    let accepted: UInt?
    let rejected: UInt
    let truncated: UInt

    static let unloaded = DXWorkedLog(loaded: false, complete: false, cells: 0,
        records: 0, accepted: 0, rejected: 0, truncated: 0)
}

struct DXSnapshot: Codable {
    let spots5m: UInt
    let spots60m: UInt
    let learnedSpots: UInt
    let duplicateSpots: UInt
    let watchlistHits: UInt
    let surgingBands: UInt
    let newestSpotEpoch: Int64
    let workedLog: DXWorkedLog?
    let solar: DXSolar
    let bands: [DXBand]
    let bandTimeline: [[UInt]]
    let regions: [DXRegion]
    let worldGrid: [[UInt]]
    let opportunities: [DXOpportunity]
    let liveSpots: [DXOpportunity]
    let watchActivity: [DXOpportunity]
    var safeWorkedLog: DXWorkedLog { workedLog ?? .unloaded }

    static let empty = DXSnapshot(spots5m: 0, spots60m: 0, learnedSpots: 0,
        duplicateSpots: 0, watchlistHits: 0, surgingBands: 0, newestSpotEpoch: 0,
        workedLog: .unloaded,
        solar: DXSolar(valid: false, flux: 0, aIndex: 0, kpIndex: 0), bands: [],
        bandTimeline: [], regions: [], worldGrid: [], opportunities: [], liveSpots: [], watchActivity: [])
}

struct WSJTXMessage: Codable {
    let valid: Bool
    let error: String?
    let stationId: String?
    let type: String?
    let frequencyHz: UInt64?
    let mode: String?
    let dxCall: String?
    let transmitting: Bool?
    let decoding: Bool?
    let snrDb: Int?
    let deltaFrequencyHz: UInt?
    let message: String?
    let lowConfidence: Bool?
    let callsign: String?
    let band: String?
    let submode: String?
    let adif: String?
}

enum PanPalette: String, CaseIterable, Identifiable {
    case aether = "Aether", ocean = "Ocean", fire = "Fire", grayscale = "Mono"
    var id: String { rawValue }
}

final class FeatureCore {
    private let context: OpaquePointer
    private let lock = NSLock()

    init() {
        guard let value = shackcq_feature_context_create() else { fatalError("Feature core unavailable") }
        context = value
    }

    deinit { shackcq_feature_context_destroy(context) }

    func setWatchlist(_ text: String) {
        lock.lock(); defer { lock.unlock() }
        text.withCString { _ = shackcq_feature_set_watchlist(context, $0) }
    }

    func loadCty(_ text: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return text.withCString { shackcq_feature_load_cty_text(context, $0) == 1 }
    }

    func setSolar(flux: Float, aIndex: Float, kpIndex: Float, at date: Date) {
        lock.lock(); defer { lock.unlock() }
        _ = shackcq_feature_set_solar(context, flux, aIndex, kpIndex, Int64(date.timeIntervalSince1970))
    }

    func ingestCluster(_ line: String, at date: Date) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return line.withCString {
            shackcq_feature_ingest_cluster_line(context, $0, Int64(date.timeIntervalSince1970)) == 1
        }
    }

    func snapshot(at date: Date = Date()) -> DXSnapshot {
        lock.lock(); defer { lock.unlock() }
        var output = [CChar](repeating: 0, count: 131_072)
        let count = shackcq_feature_dx_snapshot_json(context, &output, output.count, Int64(date.timeIntervalSince1970))
        guard count > 0 else {
            print("SHACKCQ_DX_SNAPSHOT_ERROR core returned \(count)")
            return .empty
        }
        guard let data = String(cString: output).data(using: .utf8) else {
            print("SHACKCQ_DX_SNAPSHOT_ERROR invalid UTF-8 count=\(count)")
            return .empty
        }
        do {
            return try JSONDecoder().decode(DXSnapshot.self, from: data)
        } catch {
            print("SHACKCQ_DX_SNAPSHOT_ERROR decode count=\(count) error=\(error)")
            return .empty
        }
    }

    func reloadWorkedLog(_ records: [(qso: QSO, country: String)]) {
        lock.lock(); defer { lock.unlock() }
        guard shackcq_feature_begin_worked_sync(context) == 1 else { return }
        for record in records {
            let band = workedBand(for: record.qso.frequencyHz)
            record.qso.callsign.withCString { callsign in
                record.country.withCString { country in
                    band.withCString { bandValue in
                        record.qso.mode.withCString { mode in
                            _ = shackcq_feature_add_worked_qso(context, callsign, country, bandValue, mode, "",
                                Int64(record.qso.createdAt.timeIntervalSince1970), 0)
                        }
                    }
                }
            }
        }
        _ = shackcq_feature_end_worked_sync(context)
    }

    func parseWSJTX(_ data: Data) -> WSJTXMessage? {
        lock.lock(); defer { lock.unlock() }
        var output = [CChar](repeating: 0, count: 65_536)
        let count = data.withUnsafeBytes { bytes in
            shackcq_wsjtx_parse_json(&output, output.count,
                bytes.baseAddress?.assumingMemoryBound(to: UInt8.self), bytes.count)
        }
        guard count > 0, let json = String(cString: output).data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(WSJTXMessage.self, from: json)
    }

    func pushAudio(_ data: Data, channels: UInt32, bytesPerSample: UInt32, bits: UInt32) -> [Float] {
        lock.lock(); defer { lock.unlock() }
        let accepted = data.withUnsafeBytes { bytes in
            shackcq_panadapter_push_pcm(context, bytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                                   bytes.count, channels, bytesPerSample, bits)
        }
        guard accepted == 1 else { return [] }
        var bins = [Float](repeating: -140, count: 1024)
        let count = shackcq_panadapter_copy_db_bins(context, &bins, bins.count)
        return Array(bins.prefix(count))
    }

    var audioMetrics: (peak: Float, i: Float, q: Float, correlation: Float) {
        lock.lock(); defer { lock.unlock() }
        return (shackcq_panadapter_peak_db(context), shackcq_panadapter_i_rms_db(context),
                shackcq_panadapter_q_rms_db(context), shackcq_panadapter_iq_correlation(context))
    }

    func normalizedWavelogURL(_ value: String) -> String {
        lock.lock(); defer { lock.unlock() }
        var output = [CChar](repeating: 0, count: 2048)
        let count = value.withCString { shackcq_wavelog_normalize_url(&output, output.count, $0) }
        return count > 0 ? String(cString: output) : ""
    }

    func wavelogPayload(key: String, station: String, adif: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        var output = [CChar](repeating: 0, count: max(16_384, adif.utf8.count * 2 + 4096))
        let count = key.withCString { keyValue in
            station.withCString { stationValue in
                adif.withCString { adifValue in
                    shackcq_wavelog_payload(&output, output.count, keyValue, stationValue, adifValue)
                }
            }
        }
        return count > 0 ? Data(String(cString: output).utf8) : nil
    }

    func syncAction(status: Int, networkError: Bool, ambiguous: Bool) -> Int {
        Int(shackcq_sync_action(Int32(status), networkError ? 1 : 0, ambiguous ? 1 : 0))
    }

    func retryDelay(attempt: UInt32, seed: UInt32, retryAfter: UInt32?) -> UInt32 {
        shackcq_sync_retry_delay(attempt, seed, retryAfter ?? 0, retryAfter == nil ? 0 : 1)
    }
}

final class DXClusterConnection {
    struct Endpoint: Equatable { let host: String; let port: UInt16 }
    var onLine: ((String) -> Void)?
    var onStatus: ((String) -> Void)?
    private let queue = DispatchQueue(label: "app.shackcq.dxcluster")
    private var connection: NWConnection?
    private var pending: [UInt8] = []
    private var historyRequested = false
    private var endpoints: [Endpoint] = []
    private var endpointIndex = 0
    private var callsign = ""
    private var reconnectAttempt = 0
    private var reconnectWorkItem: DispatchWorkItem?

    func connect(endpoints: [Endpoint], callsign: String) {
        queue.async { [weak self] in
            guard let self else { return }
            self.endpoints = endpoints
            self.endpointIndex = 0
            self.callsign = callsign
            self.reconnectAttempt = 0
            self.startCurrentEndpoint()
        }
    }

    private func startCurrentEndpoint() {
        guard !endpoints.isEmpty else { onStatus?("No valid cluster endpoints"); return }
        let endpoint = endpoints[endpointIndex % endpoints.count]
        startConnection(host: endpoint.host, port: endpoint.port, callsign: callsign)
    }

    private func startConnection(host: String, port: UInt16, callsign: String) {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        let oldConnection = connection
        connection = nil
        oldConnection?.cancel()
        pending.removeAll(keepingCapacity: true)
        historyRequested = false
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { onStatus?("Invalid cluster port"); return }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        self.connection = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.reconnectAttempt = 0
                self.onStatus?("Connected to \(host):\(port)")
                if !callsign.isEmpty {
                    connection.send(content: Data((callsign.uppercased() + "\r\n").utf8), completion: .contentProcessed { _ in })
                    self.queue.asyncAfter(deadline: .now() + 1.5) { [weak self, weak connection] in
                        guard let self, connection === self.connection else { return }
                        self.requestHistory()
                    }
                }
                self.receive(on: connection)
            case .waiting(let error): self.onStatus?("Cluster waiting: \(error.localizedDescription)")
            case .failed(let error):
                guard connection === self.connection else { return }
                self.connection = nil
                self.onStatus?("Cluster failed: \(error.localizedDescription)")
                self.scheduleReconnect()
            case .cancelled:
                if connection === self.connection {
                    self.connection = nil
                    self.scheduleReconnect()
                }
            default: break
            }
        }
        connection.start(queue: queue)
        onStatus?("Connecting to \(host):\(port)…")
    }

    func disconnect() {
        queue.async { [weak self] in
            guard let self else { return }
            self.endpoints = []
            self.reconnectWorkItem?.cancel()
            self.reconnectWorkItem = nil
            let connection = self.connection
            self.connection = nil
            connection?.cancel()
            self.pending.removeAll(keepingCapacity: true)
            self.historyRequested = false
            self.onStatus?("Cluster disconnected")
        }
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self, weak connection] data, _, complete, error in
            guard let self, let connection else { return }
            if let data { self.consume(data) }
            if let error {
                self.onStatus?("Cluster receive failed: \(error.localizedDescription)")
                self.connectionLost(connection)
                return
            }
            if complete {
                self.onStatus?("Cluster closed connection")
                self.connectionLost(connection)
                return
            }
            self.receive(on: connection)
        }
    }

    private func connectionLost(_ connection: NWConnection) {
        guard connection === self.connection else { return }
        self.connection = nil
        connection.cancel()
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        guard !endpoints.isEmpty, reconnectWorkItem == nil else { return }
        endpointIndex = (endpointIndex + 1) % endpoints.count
        let endpoint = endpoints[endpointIndex]
        let delay = min(30, 1 << min(reconnectAttempt, 4))
        reconnectAttempt += 1
        onStatus?("Trying \(endpoint.host):\(endpoint.port) in \(delay)s…")
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.endpoints.isEmpty else { return }
            self.reconnectWorkItem = nil
            self.startCurrentEndpoint()
        }
        reconnectWorkItem = work
        queue.asyncAfter(deadline: .now() + .seconds(delay), execute: work)
    }

    private func consume(_ data: Data) {
        for byte in data {
            if byte == 0x0A {
                emitPendingLine()
            } else if byte == 0x0D {
                continue
            } else if byte >= 0x20 && byte < 0x7F && pending.count < 2_048 {
                pending.append(byte)
            }
        }
        if !historyRequested,
           let text = String(bytes: pending, encoding: .utf8),
           text.uppercased().contains("DXSPIDER >") {
            requestHistory()
            pending.removeAll(keepingCapacity: true)
        }
    }

    private func emitPendingLine() {
        guard !pending.isEmpty else { return }
        let line = String(bytes: pending, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        pending.removeAll(keepingCapacity: true)
        guard !line.isEmpty else { return }
        if !historyRequested && line.uppercased().contains("DXSPIDER >") { requestHistory() }
        onLine?(line)
    }

    private func requestHistory() {
        guard !historyRequested, let connection else { return }
        historyRequested = true
        connection.send(content: Data("sh/dx 50\r\n".utf8), completion: .contentProcessed { _ in })
    }
}

final class WSJTXListener {
    var onDatagram: ((Data) -> Void)?
    var onStatus: ((String) -> Void)?
    private let queue = DispatchQueue(label: "app.shackcq.wsjtx")
    private var listener: NWListener?

    func start(port: UInt16) {
        stop()
        do {
            guard let endpointPort = NWEndpoint.Port(rawValue: port) else { throw ListenerError.invalidPort }
            let listener = try NWListener(using: .udp, on: endpointPort)
            self.listener = listener
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready: self?.onStatus?("Listening for WSJT-X UDP on \(port)")
                case .failed(let error): self?.onStatus?("WSJT-X listener failed: \(error.localizedDescription)")
                case .cancelled: self?.onStatus?("WSJT-X listener stopped")
                default: break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            listener.start(queue: queue)
        } catch { onStatus?(error.localizedDescription) }
    }

    func stop() { listener?.cancel(); listener = nil }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        func receive() {
            connection.receiveMessage { [weak self] data, _, _, error in
                if let data { self?.onDatagram?(data) }
                if error == nil { receive() }
            }
        }
        receive()
    }

    enum ListenerError: LocalizedError { case invalidPort; var errorDescription: String? { "Invalid WSJT-X port" } }
}

@MainActor
final class FeatureModel: ObservableObject {
    private let core = FeatureCore()
    private let cluster = DXClusterConnection()
    private let wsjtx = WSJTXListener()
    private let audioEngine = AVAudioEngine()
    let wavelog = WavelogSync()
    let callbook = CallbookService()
    let cty = CtyDatabase()

    @Published private(set) var dx = DXSnapshot.empty
    @Published private(set) var clusterStatus = "DX cluster disconnected"
    @Published private(set) var acceptedClusterLines = 0
    @Published private(set) var lastAcceptedClusterLine = ""
    @Published private(set) var settingsStatus = "Settings not saved in this session"
    @Published private(set) var wsjtxStatus = "WSJT-X listener stopped"
    @Published private(set) var lastWSJTX: WSJTXMessage?
    @Published private(set) var spectrumDb: [Float] = []
    @Published private(set) var waterfallImage: CGImage?
    @Published private(set) var audioStatus = "No physical audio capture"
    @Published private(set) var audioInputs: [AudioInputChoice] = []
    @Published var selectedAudioInputUID = ""
    @Published private(set) var audioPeak: Float = -120
    @Published private(set) var audioIRms: Float = -120
    @Published private(set) var audioQRms: Float = -120
    @Published private(set) var audioIQCorrelation: Float = 1
    @Published private(set) var audioNoiseFloor: Float = -120
    @Published private(set) var audioSampleRate: Double = 48_000
    @Published private(set) var audioCapturedFrames: UInt64 = 0
    @Published var panRangeDb: Double { didSet { defaults.set(panRangeDb, forKey: "panRangeDb"); rebuildWaterfallImage() } }
    @Published var panFloorOffsetDb: Double { didSet { defaults.set(panFloorOffsetDb, forKey: "panFloorOffsetDb"); rebuildWaterfallImage() } }
    @Published var panPalette: PanPalette { didSet { defaults.set(panPalette.rawValue, forKey: "panPalette"); rebuildWaterfallImage() } }
    @Published var reverseSpectrum: Bool { didSet { defaults.set(reverseSpectrum, forKey: "reverseSpectrum") } }
    @Published var clusterHost: String { didSet { defaults.set(clusterHost, forKey: "clusterHost") } }
    @Published var clusterPort: String { didSet { defaults.set(clusterPort, forKey: "clusterPort") } }
    @Published var clusterFallbackHost: String { didSet { defaults.set(clusterFallbackHost, forKey: "clusterFallbackHost") } }
    @Published var clusterFallbackPort: String { didSet { defaults.set(clusterFallbackPort, forKey: "clusterFallbackPort") } }
    @Published var clusterFallback2Host: String { didSet { defaults.set(clusterFallback2Host, forKey: "clusterFallback2Host") } }
    @Published var clusterFallback2Port: String { didSet { defaults.set(clusterFallback2Port, forKey: "clusterFallback2Port") } }
    @Published var operatorCallsign: String { didSet { defaults.set(operatorCallsign, forKey: "operatorCallsign") } }
    @Published var watchlist: String { didSet { defaults.set(watchlist, forKey: "watchlist"); core.setWatchlist(watchlist); refreshDX() } }
    @Published var wsjtxPort: String { didSet { defaults.set(wsjtxPort, forKey: "wsjtxPort") } }

    private let defaults = UserDefaults.standard
    private var observations = Set<AnyCancellable>()
    private var waterfallRows: [[Float]] = []
    private let waterfallWidth = 512
    private let waterfallDepth = 240
    private var noiseFloorSeeded = false
    private var audioTapInstalled = false
    private var accumulatedAudioFrames: UInt64 = 0
    private var lastPublishedAudioFrame: UInt64 = 0
    private var workedLogBound = false
    private weak var boundLogbook: QSOStore?

    init() {
        clusterHost = defaults.string(forKey: "clusterHost") ?? "cluster.om0rx.com"
        clusterPort = defaults.string(forKey: "clusterPort") ?? "7300"
        clusterFallbackHost = defaults.string(forKey: "clusterFallbackHost") ?? ""
        clusterFallbackPort = defaults.string(forKey: "clusterFallbackPort") ?? "7300"
        clusterFallback2Host = defaults.string(forKey: "clusterFallback2Host") ?? ""
        clusterFallback2Port = defaults.string(forKey: "clusterFallback2Port") ?? "7300"
        operatorCallsign = defaults.string(forKey: "operatorCallsign") ?? ""
        watchlist = defaults.string(forKey: "watchlist") ?? ""
        wsjtxPort = defaults.string(forKey: "wsjtxPort") ?? "2237"
        panRangeDb = defaults.object(forKey: "panRangeDb") as? Double ?? 72
        panFloorOffsetDb = defaults.object(forKey: "panFloorOffsetDb") as? Double ?? -6
        panPalette = PanPalette(rawValue: defaults.string(forKey: "panPalette") ?? "") ?? .aether
        reverseSpectrum = defaults.object(forKey: "reverseSpectrum") as? Bool ?? false
        wavelog.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &observations)
        callbook.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &observations)
        cty.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &observations)
        cty.onInstalledDataChanged = { [weak self] in self?.reloadBoundWorkedLog() }
        core.setWatchlist(watchlist)
        cluster.onStatus = { [weak self] value in Task { @MainActor in self?.clusterStatus = value } }
        cluster.onLine = { [weak self] line in
            guard let self else { return }
            Task { @MainActor in
                if self.core.ingestCluster(line, at: Date()) {
                    self.acceptedClusterLines += 1
                    self.lastAcceptedClusterLine = line
                    self.refreshDX()
                    self.clusterStatus = "DX cluster live · \(self.acceptedClusterLines) spots received"
                }
            }
        }
        wsjtx.onStatus = { [weak self] value in Task { @MainActor in self?.wsjtxStatus = value } }
        wsjtx.onDatagram = { [weak self] data in
            guard let self else { return }
            Task { @MainActor in self.lastWSJTX = self.core.parseWSJTX(data) }
        }
        wavelog.bind(core: core)
    }

    func enqueueWavelog(id: String, adif: String) {
        wavelog.enqueue(id: id, adif: adif)
    }

    func bind(logbook: QSOStore) {
        guard !workedLogBound else { return }
        workedLogBound = true
        boundLogbook = logbook
        logbook.onWorkedLogChanged = { [weak self] in self?.reloadBoundWorkedLog() }
        reloadBoundWorkedLog()
    }

    private func reloadBoundWorkedLog() {
        guard let logbook = boundLogbook else { return }
        let installedCty = cty.installedText()
        if let installedCty, !core.loadCty(installedCty) { return }
        let records = logbook.workedLogRecords().map { qso in
            let currentCountry = installedCty == nil ? "" : cty.country(for: qso.callsign)
            return (qso, currentCountry.isEmpty ? qso.country : currentCountry)
        }
        core.reloadWorkedLog(records)
        refreshDX()
    }

    deinit { cluster.disconnect(); wsjtx.stop(); audioEngine.stop() }

    func connectCluster() {
        clusterHost = clusterHost.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        clusterPort = clusterPort.trimmingCharacters(in: .whitespacesAndNewlines)
        operatorCallsign = operatorCallsign.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        saveSettings()
        guard let port = UInt16(clusterPort), !clusterHost.isEmpty else { clusterStatus = "Enter a valid cluster host and port"; return }
        guard !operatorCallsign.isEmpty else {
            clusterStatus = "Enter your operator callsign before connecting"
            return
        }
        guard operatorCallsign.range(of: "^[A-Z0-9/]{3,20}$", options: .regularExpression) != nil else {
            clusterStatus = "Enter a valid operator callsign"
            return
        }
        var endpoints = [DXClusterConnection.Endpoint(host: clusterHost, port: port)]
        for (host, value) in [(clusterFallbackHost, clusterFallbackPort),
                              (clusterFallback2Host, clusterFallback2Port)] {
            let clean = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if !clean.isEmpty, let fallbackPort = UInt16(value) {
                let endpoint = DXClusterConnection.Endpoint(host: clean, port: fallbackPort)
                if !endpoints.contains(endpoint) { endpoints.append(endpoint) }
            }
        }
        cluster.connect(endpoints: endpoints, callsign: operatorCallsign)
    }

    func disconnectCluster() { cluster.disconnect() }
    func refreshDX() { dx = core.snapshot() }

    func saveSettings() {
        defaults.set(clusterHost, forKey: "clusterHost")
        defaults.set(clusterPort, forKey: "clusterPort")
        defaults.set(clusterFallbackHost, forKey: "clusterFallbackHost")
        defaults.set(clusterFallbackPort, forKey: "clusterFallbackPort")
        defaults.set(clusterFallback2Host, forKey: "clusterFallback2Host")
        defaults.set(clusterFallback2Port, forKey: "clusterFallback2Port")
        defaults.set(operatorCallsign, forKey: "operatorCallsign")
        defaults.set(watchlist, forKey: "watchlist")
        defaults.set(wsjtxPort, forKey: "wsjtxPort")
        defaults.synchronize()
        wavelog.saveSettings()
        settingsStatus = "Settings saved on this iPad"
    }

    func startWSJTX() {
        guard let port = UInt16(wsjtxPort) else { wsjtxStatus = "Enter a valid WSJT-X port"; return }
        wsjtx.start(port: port)
    }
    func stopWSJTX() { wsjtx.stop() }

    func refreshSolar() async {
        let fluxURL = URL(string: "https://services.swpc.noaa.gov/products/summary/10cm-flux.json")!
        let kpURL = URL(string: "https://services.swpc.noaa.gov/products/summary/planetary-k-index.json")!
        do {
            async let fluxData = URLSession.shared.data(from: fluxURL).0
            async let kpData = URLSession.shared.data(from: kpURL).0
            let (flux, kp) = try await (Self.summaryValue(from: fluxData, keys: ["Flux", "flux"]),
                                        Self.summaryValue(from: kpData, keys: ["Kp", "kp_index", "KpIndex"]))
            core.setSolar(flux: flux, aIndex: 0, kpIndex: kp, at: Date())
            refreshDX()
        } catch { clusterStatus = "NOAA solar update failed: \(error.localizedDescription)" }
    }

    func refreshAudioInputs() async {
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else {
            audioInputs = []
            selectedAudioInputUID = ""
            audioStatus = "Microphone / USB audio permission denied. Enable Microphone for ShackCQ in iPad Settings."
            return
        }
        do {
            let wasCapturing = audioEngine.isRunning
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement)
            try session.setPreferredSampleRate(48_000)
            try session.setPreferredIOBufferDuration(2048.0 / 48_000.0)
            try session.setActive(true)
            let inputs = session.availableInputs ?? []
            audioInputs = inputs.map { AudioInputChoice(id: $0.uid, name: $0.portName, type: $0.portType.rawValue) }
            if !audioInputs.contains(where: { $0.id == selectedAudioInputUID }) {
                selectedAudioInputUID = inputs.first(where: { $0.portType == .usbAudio })?.uid ?? inputs.first?.uid ?? ""
            }
            audioStatus = audioInputs.isEmpty
                ? "No physical audio inputs reported by iPadOS"
                : "Found \(audioInputs.count) input(s): \(audioInputs.map(\.name).joined(separator: ", "))"
            if !wasCapturing { deactivateAudioSession() }
        } catch {
            audioInputs = []
            selectedAudioInputUID = ""
            audioStatus = "Audio discovery failed: \(error.localizedDescription)"
            deactivateAudioSession()
        }
    }

    func startAudioCapture() async {
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { audioStatus = "Microphone / USB audio permission denied"; return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement)
            try session.setPreferredSampleRate(48_000)
            try session.setPreferredIOBufferDuration(2048.0 / 48_000.0)
            try session.setActive(true)
            let inputs = session.availableInputs ?? []
            audioInputs = inputs.map { AudioInputChoice(id: $0.uid, name: $0.portName, type: $0.portType.rawValue) }
            guard let selected = inputs.first(where: { $0.uid == selectedAudioInputUID })
                ?? inputs.first(where: { $0.portType == .usbAudio })
                ?? inputs.first else {
                audioStatus = "No physical audio input is available after route activation."
                deactivateAudioSession()
                return
            }
            selectedAudioInputUID = selected.uid
            try session.setPreferredInput(selected)
            if audioEngine.isRunning { audioEngine.stop() }
            let input = audioEngine.inputNode
            if audioTapInstalled {
                input.removeTap(onBus: 0)
                audioTapInstalled = false
            }
            let format = input.outputFormat(forBus: 0)
            guard format.channelCount >= 2 else {
                audioStatus = "\(selected.portName) exposes \(format.channelCount) input channel(s). Panadapter requires stereo I/Q."
                deactivateAudioSession()
                return
            }
            audioSampleRate = format.sampleRate
            audioCapturedFrames = 0
            accumulatedAudioFrames = 0
            lastPublishedAudioFrame = 0
            input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in self?.acceptAudio(buffer) }
            audioTapInstalled = true
            audioEngine.prepare(); try audioEngine.start()
            audioStatus = "Capturing \(session.currentRoute.inputs.first?.portName ?? selected.portName) · \(format.channelCount) ch · \(Int(format.sampleRate)) Hz"
        } catch {
            if audioTapInstalled {
                audioEngine.inputNode.removeTap(onBus: 0)
                audioTapInstalled = false
            }
            audioEngine.stop()
            deactivateAudioSession()
            audioStatus = "Audio capture failed: \(error.localizedDescription)"
        }
    }

    func stopAudioCapture() {
        if audioTapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            audioTapInstalled = false
        }
        audioEngine.stop()
        deactivateAudioSession()
        spectrumDb = []; waterfallRows.removeAll(); waterfallImage = nil; noiseFloorSeeded = false
        audioPeak = -120; audioIRms = -120; audioQRms = -120; audioIQCorrelation = 1
        audioStatus = "Audio capture stopped"
    }

    private func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated private func acceptAudio(_ buffer: AVAudioPCMBuffer) {
        guard let channels = buffer.floatChannelData else { return }
        let frameCount = Int(buffer.frameLength), channelCount = Int(buffer.format.channelCount)
        var samples = [Int16](repeating: 0, count: frameCount * channelCount)
        for frame in 0..<frameCount {
            for channel in 0..<channelCount {
                let value = max(-1, min(1, channels[channel][frame]))
                samples[frame * channelCount + channel] = Int16(value * Float(Int16.max))
            }
        }
        let data = samples.withUnsafeBytes { Data($0) }
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.accumulatedAudioFrames += UInt64(frameCount)
            var prepared = data
            if self.reverseSpectrum {
                prepared.withUnsafeMutableBytes { raw in
                    let pcm = raw.bindMemory(to: Int16.self)
                    for frame in 0..<frameCount {
                        pcm.swapAt(frame * channelCount, frame * channelCount + 1)
                    }
                }
            }
            let bins = self.core.pushAudio(prepared, channels: UInt32(channelCount), bytesPerSample: 2, bits: 16)
            let publishInterval = UInt64(max(1, self.audioSampleRate / 9.0))
            if !bins.isEmpty,
               self.accumulatedAudioFrames - self.lastPublishedAudioFrame >= publishInterval {
                self.lastPublishedAudioFrame = self.accumulatedAudioFrames
                self.audioCapturedFrames = self.accumulatedAudioFrames
                self.acceptSpectrum(bins)
            }
        }
    }

    private func acceptSpectrum(_ bins: [Float]) {
        spectrumDb = bins
        let metrics = core.audioMetrics
        audioPeak = metrics.peak
        audioIRms = metrics.i
        audioQRms = metrics.q
        audioIQCorrelation = metrics.correlation
        let centre = bins.count / 2
        let usable = bins.enumerated().compactMap { index, value in
            abs(index - centre) <= 4 || !value.isFinite ? nil : value
        }
        guard !usable.isEmpty else { return }
        let mean = usable.reduce(0, +) / Float(usable.count)
        let quiet = usable.filter { $0 <= mean }
        let measured = quiet.isEmpty ? mean : quiet.reduce(0, +) / Float(quiet.count)
        if noiseFloorSeeded { audioNoiseFloor += 0.10 * (measured - audioNoiseFloor) }
        else { audioNoiseFloor = measured; noiseFloorSeeded = true }

        var row = [Float](repeating: audioNoiseFloor, count: waterfallWidth)
        for column in 0..<waterfallWidth {
            let start = column * bins.count / waterfallWidth
            let end = max(start + 1, (column + 1) * bins.count / waterfallWidth)
            row[column] = bins[start..<min(end, bins.count)].max() ?? audioNoiseFloor
        }
        waterfallRows.insert(row, at: 0)
        if waterfallRows.count > waterfallDepth { waterfallRows.removeLast(waterfallRows.count - waterfallDepth) }
        rebuildWaterfallImage()
    }

    private func rebuildWaterfallImage() {
        guard !waterfallRows.isEmpty else { waterfallImage = nil; return }
        let floor = audioNoiseFloor + Float(panFloorOffsetDb)
        let range = max(20, Float(panRangeDb))
        var pixels = [UInt8](repeating: 0, count: waterfallWidth * waterfallRows.count * 4)
        for (y, row) in waterfallRows.enumerated() {
            for x in 0..<waterfallWidth {
                let level = max(0, min(1, (row[x] - floor) / range))
                let color = paletteColor(level)
                let offset = (y * waterfallWidth + x) * 4
                pixels[offset] = color.0; pixels[offset + 1] = color.1; pixels[offset + 2] = color.2; pixels[offset + 3] = 255
            }
        }
        let data = Data(pixels) as CFData
        guard let provider = CGDataProvider(data: data) else { return }
        waterfallImage = CGImage(width: waterfallWidth, height: waterfallRows.count,
            bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: waterfallWidth * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    private func paletteColor(_ level: Float) -> (UInt8, UInt8, UInt8) {
        let stops: [(Float, (Float, Float, Float))]
        switch panPalette {
        case .aether: stops = [(0, (2, 6, 18)), (0.18, (14, 28, 74)), (0.42, (0, 132, 174)), (0.68, (93, 226, 170)), (0.84, (247, 201, 72)), (1, (255, 88, 62))]
        case .ocean: stops = [(0, (0, 4, 18)), (0.3, (0, 50, 110)), (0.6, (0, 184, 210)), (1, (224, 255, 255))]
        case .fire: stops = [(0, (5, 3, 10)), (0.28, (72, 12, 92)), (0.56, (212, 45, 45)), (0.8, (255, 174, 38)), (1, (255, 255, 220))]
        case .grayscale: stops = [(0, (0, 0, 0)), (1, (255, 255, 255))]
        }
        let upper = stops.firstIndex(where: { $0.0 >= level }) ?? stops.count - 1
        let lower = max(0, upper - 1), a = stops[lower], b = stops[upper]
        let t = b.0 == a.0 ? 0 : (level - a.0) / (b.0 - a.0)
        func mix(_ left: Float, _ right: Float) -> UInt8 { UInt8(max(0, min(255, left + (right - left) * t))) }
        return (mix(a.1.0, b.1.0), mix(a.1.1, b.1.1), mix(a.1.2, b.1.2))
    }

    private static func summaryValue(from data: Data, keys: [String]) throws -> Float {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw SolarError.invalidResponse }
        for key in keys {
            if let number = object[key] as? NSNumber { return number.floatValue }
            if let string = object[key] as? String, let value = Float(string) { return value }
        }
        throw SolarError.invalidResponse
    }

    enum SolarError: LocalizedError { case invalidResponse; var errorDescription: String? { "Unexpected NOAA response" } }
}

func dxDirectTuneAvailable(_ frequencyHz: UInt64) -> Bool {
    (1_000_000...54_000_000).contains(frequencyHz)
}

private func workedBand(for frequencyHz: UInt64) -> String {
    switch frequencyHz {
    case 1_800_000...2_000_000: return "160m"
    case 3_500_000...4_000_000: return "80m"
    case 5_250_000...5_450_000: return "60m"
    case 7_000_000...7_300_000: return "40m"
    case 10_100_000...10_150_000: return "30m"
    case 14_000_000...14_350_000: return "20m"
    case 18_068_000...18_168_000: return "17m"
    case 21_000_000...21_450_000: return "15m"
    case 24_890_000...24_990_000: return "12m"
    case 28_000_000...29_700_000: return "10m"
    case 50_000_000...54_000_000: return "6m"
    case 70_000_000..<71_000_000: return "4m"
    case 144_000_000..<148_000_000: return "2m"
    case 420_000_000..<450_000_000: return "70cm"
    case 1_240_000_000..<1_300_000_000: return "23cm"
    case 10_000_000_000..<10_500_000_000: return "3cm"
    default: return ""
    }
}
