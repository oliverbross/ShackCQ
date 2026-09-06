// SPDX-License-Identifier: GPL-3.0-only
import AVFAudio
import CryptoKit
import Foundation
import Security
import SwiftUI

struct AppleRemoteStationProfile: Codable, Identifiable, Hashable {
    let stationId: String
    var name: String
    var host: String
    var port: Int
    let certificateSHA256: String
    let deviceId: String
    var role: String
    var id: String { stationId }
}

@MainActor
final class RemoteStationModel: NSObject, ObservableObject, URLSessionWebSocketDelegate {
    enum State: String { case disconnected = "Disconnected", connecting = "Connecting", authenticating = "Authenticating", ready = "Ready", failed = "Failed" }

    @Published private(set) var profiles: [AppleRemoteStationProfile] = []
    @Published var selectedStationId = ""
    @Published private(set) var state: State = .disconnected
    @Published private(set) var status = "No Remote Station selected"
    @Published private(set) var stationName = ""
    @Published private(set) var role = "OBSERVER"
    @Published private(set) var sessionId = ""
    @Published private(set) var generation: UInt64 = 0
    @Published private(set) var frequencyHz: UInt64 = 0
    @Published private(set) var mode = ""
    @Published private(set) var writerLease = false
    @Published private(set) var spectrum: [Double] = []
    @Published private(set) var waterfall: [[Double]] = []
    @Published private(set) var audioFrames: UInt64 = 0
    @Published private(set) var droppedFrames: UInt64 = 0
    @Published private(set) var roundTripMs: Int?
    @Published private(set) var radioRoster: [String] = []

    private var socket: URLSessionWebSocketTask?
    private var session: URLSession?
    private var pinnedCertificate = ""
    private var pending: [String: (Bool) -> Void] = [:]
    private var heartbeatTask: Task<Void, Never>?
    private var heartbeatSent: [String: Date] = [:]
    private let audio = RemotePCMAudioPlayer()
    private let defaultsKey = "shackcq.remote.apple.profiles.v1"

    override init() {
        super.init()
        if let data = UserDefaults.standard.data(forKey: defaultsKey),
           let rows = try? JSONDecoder().decode([AppleRemoteStationProfile].self, from: data) {
            profiles = rows
            selectedStationId = rows.first?.stationId ?? ""
        }
    }

    deinit { heartbeatTask?.cancel(); socket?.cancel(with: .goingAway, reason: nil) }

    var selectedProfile: AppleRemoteStationProfile? { profiles.first { $0.stationId == selectedStationId } }
    var connected: Bool { state == .ready }

    func importPairingOffer(_ text: String, requestedRole: String = "OBSERVER") async {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let stationId = json["stationId"] as? String,
              let stationName = json["stationName"] as? String,
              let endpoint = json["endpoint"] as? String,
              let fingerprint = json["certificateSha256"] as? String,
              let nonce = json["nonce"] as? String,
              let expires = json["expiresAtMs"] as? NSNumber,
              fingerprint.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil,
              expires.int64Value > Int64(Date().timeIntervalSince1970 * 1000),
              let url = URL(string: endpoint), url.scheme == "wss", let host = url.host else {
            status = "Pairing offer is invalid or expired"; return
        }
        let deviceId = KeychainString.load("remote.deviceId") ?? UUID().uuidString
        guard KeychainString.save(deviceId, account: "remote.deviceId") else { status = "Secure identity storage unavailable"; return }
        let identity = RemoteAppleIdentity(deviceId: deviceId)
        guard let signature = identity.sign("\(stationId)|\(nonce)|\(deviceId)"),
              let publicKey = identity.publicKeyPEM else { status = "Secure device identity unavailable"; return }
        let profile = AppleRemoteStationProfile(stationId: stationId, name: stationName, host: host,
            port: url.port ?? 7443, certificateSHA256: fingerprint.lowercased(), deviceId: deviceId,
            role: requestedRole.uppercased())
        pinnedCertificate = profile.certificateSHA256
        let configuration = URLSessionConfiguration.ephemeral
        let pairingSession = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        let ws = pairingSession.webSocketTask(with: url, protocols: ["shackcq.remote.v1"])
        ws.resume()
        do {
            let hello = try await receiveJSON(ws)
            guard hello["type"] as? String == "HELLO", hello["stationId"] as? String == stationId else { throw RemoteAppleError.protocolViolation }
            let request: [String: Any] = ["version": 1, "type": "PAIR_REQUEST", "requestId": UUID().uuidString,
                "payload": ["nonce": nonce, "deviceId": deviceId, "publicKeyPem": publicKey,
                            "signature": signature, "requestedRole": profile.role]]
            try await ws.send(URLSessionWebSocketTask.Message.string(jsonString(request)))
            let ack = try await receiveJSON(ws)
            guard ack["ok"] as? Bool == true, ack["code"] as? String == "LOCAL_APPROVAL_REQUIRED" else { throw RemoteAppleError.pairingRejected }
            upsert(profile); status = "Pairing request sent; approve it locally at the station"
            ws.cancel(with: URLSessionWebSocketTask.CloseCode.normalClosure, reason: Optional<Data>.none)
        } catch {
            status = "Pairing failed: \(error.localizedDescription)"
            ws.cancel(with: URLSessionWebSocketTask.CloseCode.goingAway, reason: Optional<Data>.none)
        }
        pairingSession.invalidateAndCancel()
    }

    func connect() {
        guard socket == nil, let profile = selectedProfile,
              let url = URL(string: "wss://\(profile.host):\(profile.port)") else { status = "Select a valid Remote Station profile"; return }
        state = .connecting; status = "Connecting securely…"; pinnedCertificate = profile.certificateSHA256
        let configuration = URLSessionConfiguration.ephemeral
        configuration.waitsForConnectivity = false
        let nextSession = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        session = nextSession
        let task = nextSession.webSocketTask(with: url, protocols: ["shackcq.remote.v1"])
        socket = task; task.resume(); receiveLoop(task)
    }

    func disconnect() {
        heartbeatTask?.cancel(); heartbeatTask = nil
        socket?.cancel(with: .normalClosure, reason: nil); socket = nil
        session?.invalidateAndCancel(); session = nil; audio.stop()
        pending.values.forEach { $0(false) }; pending.removeAll(); heartbeatSent.removeAll()
        state = .disconnected; sessionId = ""; writerLease = false; status = "Disconnected"
    }

    func removeSelectedProfile() {
        guard !connected, !selectedStationId.isEmpty else { return }
        profiles.removeAll { $0.stationId == selectedStationId }
        selectedStationId = profiles.first?.stationId ?? ""
        persistProfiles()
    }

    func acquireWriter() { request("LEASE", payload: ["kind": "WRITER", "ttlMs": 10_000]) { [weak self] ok in self?.writerLease = ok } }
    func setFrequency(_ value: UInt64) { safeMutation("frequency", value: String(value)) }
    func setMode(_ value: String) { safeMutation("mode", value: value.uppercased()) }
    func globalStop() { request("GLOBAL_STOP", payload: [:]) { [weak self] ok in self?.status = ok ? "Global Stop confirmed" : "Global Stop was not confirmed" } }

    private func safeMutation(_ operation: String, value: Any) {
        guard writerLease else { status = "Acquire the writer lease first"; return }
        request("MUTATE", payload: ["operation": operation, "value": value]) { [weak self] ok in self?.status = ok ? "Command accepted; awaiting station readback" : "Command rejected" }
    }

    private func receiveLoop(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.socket === task else { return }
                switch result {
                case .failure(let error): self.fail(error.localizedDescription)
                case .success(.string(let text)): self.handleText(text); self.receiveLoop(task)
                case .success(.data(let data)): self.handleMedia(data); self.receiveLoop(task)
                @unknown default: self.fail("Unsupported Remote Station frame")
                }
            }
        }
    }

    private func handleText(_ text: String) {
        guard text.utf8.count <= 65_536, let data = text.data(using: .utf8),
              let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = message["type"] as? String else { fail("Malformed Remote Station control frame"); return }
        if type == "HELLO" { authenticate(message); return }
        if type == "ACK" {
            let ok = message["ok"] as? Bool == true, code = message["code"] as? String ?? ""
            if let id = message["requestId"] as? String { pending.removeValue(forKey: id)?(ok) }
            if let id = message["requestId"] as? String, let sent = heartbeatSent.removeValue(forKey: id), code == "HEARTBEAT", ok { roundTripMs = Int(Date().timeIntervalSince(sent) * 1000) }
            if code == "AUTHENTICATED", ok, let payload = message["payload"] as? [String: Any] {
                sessionId = payload["sessionId"] as? String ?? ""; role = payload["role"] as? String ?? selectedProfile?.role ?? "OBSERVER"
                radioRoster = (payload["radioRoster"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
                state = .ready; status = "Secure session authenticated"; configurePCM(); startHeartbeat()
            } else if !ok, ["AUTH_FAILED", "SESSION_REQUIRED", "STALE_GENERATION"].contains(code) { fail("Remote session rejected: \(code)") }
            return
        }
        if type == "STATE", state == .ready {
            generation = UInt64(message["generation"] as? String ?? "") ?? generation
            if let radio = message["radio"] as? [String: Any] {
                frequencyHz = UInt64(radio["frequencyHz"] as? String ?? "") ?? frequencyHz
                mode = radio["mode"] as? String ?? mode
            }
            if let leases = message["leases"] as? [String: Any] { writerLease = leases["writer"] as? Bool == true }
        }
    }

    private func authenticate(_ message: [String: Any]) {
        guard let profile = selectedProfile,
              message["stationId"] as? String == profile.stationId,
              (message["certificateSha256"] as? String)?.lowercased() == profile.certificateSHA256.lowercased(),
              let nonce = message["authNonce"] as? String, nonce.count == 48,
              let remoteGeneration = UInt64(message["generation"] as? String ?? ""),
              let signature = RemoteAppleIdentity(deviceId: profile.deviceId).sign("\(profile.stationId)|auth|\(nonce)|\(remoteGeneration)") else {
            fail("Pinned Remote Station identity or challenge mismatch"); return
        }
        generation = remoteGeneration; stationName = profile.name; state = .authenticating
        sendRaw(["version": 1, "type": "AUTH", "requestId": UUID().uuidString,
            "generation": String(remoteGeneration), "payload": ["deviceId": profile.deviceId, "nonce": nonce,
            "signature": signature, "foreground": true]])
    }

    private func configurePCM() { request("MEDIA_CONFIG", payload: ["audioCodec": "PCM16", "audioPreset": "BALANCED", "audioCapKbps": 128, "rawIq": false, "lowDataMode": false]) { _ in } }

    private func request(_ type: String, payload: [String: Any], completion: @escaping (Bool) -> Void) {
        guard !sessionId.isEmpty || type == "GLOBAL_STOP" else { completion(false); return }
        let id = UUID().uuidString; pending[id] = completion
        sendRaw(["version": 1, "type": type, "stationId": selectedStationId, "sessionId": sessionId,
            "requestId": id, "generation": String(generation), "timestampMs": String(Int64(Date().timeIntervalSince1970 * 1000)), "payload": payload])
    }

    private func sendRaw(_ value: [String: Any]) { socket?.send(.string(jsonString(value))) { [weak self] error in if let error { Task { @MainActor in self?.fail(error.localizedDescription) } } } }

    private func startHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2)); guard let self, self.connected else { return }
                let id = UUID().uuidString; self.heartbeatSent[id] = Date()
                self.sendRaw(["version": 1, "type": "HEARTBEAT", "stationId": self.selectedStationId,
                    "sessionId": self.sessionId, "requestId": id, "generation": String(self.generation), "payload": ["foreground": true]])
            }
        }
    }

    private func handleMedia(_ data: Data) {
        guard data.count >= 36, String(data: data.prefix(4), encoding: .ascii) == "RWR1" else { droppedFrames += 1; return }
        let channel = Int(data[6]), flags = UInt16(data[8]) << 8 | UInt16(data[9])
        let frameGeneration = data.uint64BE(at: 24), size = Int(data.uint32BE(at: 32))
        guard frameGeneration == generation, size >= 0, size <= 262_144, data.count == 36 + size else { droppedFrames += 1; return }
        let payload = data.subdata(in: 36..<data.count)
        if channel == 5, flags & 1 == 0, payload.count >= 4 {
            let rate = payload.uint32BE(at: 0); let pcm = payload.subdata(in: 4..<payload.count)
            guard pcm.count.isMultiple(of: 2), (8_000...192_000).contains(Int(rate)) else { droppedFrames += 1; return }
            audio.play(pcm16LE: pcm, sampleRate: Double(rate)); audioFrames += 1
        } else if channel == 7 || channel == 8 {
            spectrum = payload.prefix(2048).map { Double($0) / 255.0 }
            waterfall.append(spectrum)
            if waterfall.count > 48 { waterfall.removeFirst(waterfall.count - 48) }
        }
    }

    private func fail(_ reason: String) { state = .failed; status = reason.prefix(240).description; disconnectTransportOnly() }
    private func disconnectTransportOnly() { heartbeatTask?.cancel(); socket?.cancel(with: .goingAway, reason: nil); socket = nil; session?.invalidateAndCancel(); session = nil; sessionId = ""; writerLease = false; audio.stop() }
    private func persistProfiles() { if let data = try? JSONEncoder().encode(profiles) { UserDefaults.standard.set(data, forKey: defaultsKey) } }
    private func upsert(_ profile: AppleRemoteStationProfile) { profiles.removeAll { $0.stationId == profile.stationId }; profiles.append(profile); selectedStationId = profile.stationId; persistProfiles() }

    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {}
    nonisolated func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let certificates = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let certificate = certificates.first else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        let digest = SHA256.hash(data: SecCertificateCopyData(certificate) as Data).map { String(format: "%02x", $0) }.joined()
        Task { @MainActor in
            if digest.caseInsensitiveCompare(self.pinnedCertificate) == .orderedSame { completionHandler(.useCredential, URLCredential(trust: trust)) }
            else { completionHandler(.cancelAuthenticationChallenge, nil) }
        }
    }
}

struct RemoteStationsView: View {
    @EnvironmentObject private var remote: RemoteStationModel
    @EnvironmentObject private var logbook: QSOStore
    @State private var offer = ""
    @State private var requestedRole = "OBSERVER"
    @State private var frequency = ""
    @State private var mode = ""
    @State private var callsign = ""

    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) {
            BrandHeader()
            GroupBox("Paired Remote Stations") {
                Picker("Station", selection: $remote.selectedStationId) { Text("Select…").tag(""); ForEach(remote.profiles) { Text($0.name).tag($0.stationId) } }
                HStack {
                    Button(remote.connected ? "Disconnect" : "Connect") { remote.connected ? remote.disconnect() : remote.connect() }.buttonStyle(.borderedProminent)
                    Button("Remove") { remote.removeSelectedProfile() }.disabled(remote.connected || remote.selectedStationId.isEmpty)
                    StatusBadge(status: remote.connected ? "LIVE" : remote.state.rawValue.uppercased())
                }
                Text(remote.status).font(.caption).foregroundStyle(.secondary)
            }
            GroupBox("Pair a station") {
                TextEditor(text: $offer).frame(minHeight: 90).font(.caption.monospaced())
                Picker("Requested role", selection: $requestedRole) { Text("Observer").tag("OBSERVER"); Text("Operator").tag("OPERATOR"); Text("Admin").tag("ADMIN") }.pickerStyle(.segmented)
                Button("Submit signed pairing request") { Task { await remote.importPairingOffer(offer, requestedRole: requestedRole) } }.disabled(offer.isEmpty)
            }
            GroupBox("Station state") {
                LabeledContent("Station", value: remote.stationName.isEmpty ? "—" : remote.stationName)
                LabeledContent("Role", value: remote.role); LabeledContent("Radio", value: remote.radioRoster.joined(separator: ", ").isEmpty ? "—" : remote.radioRoster.joined(separator: ", "))
                LabeledContent("Frequency", value: remote.frequencyHz == 0 ? "—" : "\(remote.frequencyHz) Hz"); LabeledContent("Mode", value: remote.mode.isEmpty ? "—" : remote.mode)
                LabeledContent("RTT", value: remote.roundTripMs.map { "\($0) ms" } ?? "—")
                Button(remote.writerLease ? "Writer lease held" : "Acquire writer lease") { remote.acquireWriter() }.disabled(!remote.connected || remote.writerLease)
                HStack { TextField("Frequency Hz", text: $frequency).keyboardType(.numberPad); Button("Set") { if let value = UInt64(frequency) { remote.setFrequency(value) } } }
                HStack { TextField("Mode", text: $mode).textInputAutocapitalization(.characters); Button("Set") { remote.setMode(mode) } }
                Button("GLOBAL STOP", role: .destructive) { remote.globalStop() }.buttonStyle(.borderedProminent).disabled(!remote.connected)
                Text("PTT, TUNE and rotator movement remain unavailable until station policy and physical acceptance explicitly permit them.").font(.caption).foregroundStyle(.secondary)
            }
            GroupBox("Remote spectrum and RX audio") {
                RemoteSpectrumTrace(values: remote.spectrum).frame(height: 130)
                RemoteWaterfall(rows: remote.waterfall).frame(height: 130)
                LabeledContent("RX audio frames", value: "\(remote.audioFrames)"); LabeledContent("Dropped media", value: "\(remote.droppedFrames)")
            }
            GroupBox("Log this remote contact locally") {
                TextField("Callsign", text: $callsign).textInputAutocapitalization(.characters).autocorrectionDisabled()
                Button("Save local QSO") {
                    let qso = QSO(id: UUID().uuidString, callsign: callsign.uppercased(), frequencyHz: remote.frequencyHz,
                        mode: remote.mode, rstSent: "59", rstReceived: "59", createdAt: Date(), notes: "Remote Station \(remote.stationName)", fields: ["APP_SHACKCQ_REMOTE_STATION": remote.selectedStationId])
                    if logbook.save(qso) { callsign = "" }
                }.disabled(callsign.trimmingCharacters(in: .whitespaces).isEmpty || remote.frequencyHz == 0)
            }
        }.padding() }.navigationTitle("Remote Stations")
    }
}

private struct RemoteSpectrumTrace: View {
    let values: [Double]
    var body: some View { Canvas { context, size in
        guard values.count > 1 else { return }
        var path = Path(); for (index, value) in values.enumerated() { let p = CGPoint(x: Double(index) / Double(values.count - 1) * size.width, y: (1 - value) * size.height); index == 0 ? path.move(to: p) : path.addLine(to: p) }
        context.stroke(path, with: .color(RigTheme.amber), lineWidth: 1.5)
    }.background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 10)) }
}

private struct RemoteWaterfall: View {
    let rows: [[Double]]
    var body: some View { Canvas { context, size in
        guard !rows.isEmpty else { return }
        let rowHeight = size.height / Double(rows.count)
        for (rowIndex, row) in rows.enumerated() where !row.isEmpty {
            let cellWidth = size.width / Double(row.count)
            for (column, value) in row.enumerated() {
                let color = Color(hue: 0.58 - min(max(value, 0), 1) * 0.55, saturation: 0.9, brightness: 0.15 + value * 0.85)
                context.fill(Path(CGRect(x: Double(column) * cellWidth, y: Double(rowIndex) * rowHeight,
                                         width: cellWidth + 0.5, height: rowHeight + 0.5)), with: .color(color))
            }
        }
    }.background(RigTheme.panel).clipShape(RoundedRectangle(cornerRadius: 10)) }
}

private final class RemotePCMAudioPlayer {
    private let engine = AVAudioEngine(), player = AVAudioPlayerNode()
    private var rate: Double = 0
    init() { engine.attach(player) }
    func play(pcm16LE data: Data, sampleRate: Double) {
        guard let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: 1, interleaved: true),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(data.count / 2)) else { return }
        if rate != sampleRate { if engine.isRunning { engine.stop() }; engine.disconnectNodeOutput(player); engine.connect(player, to: engine.mainMixerNode, format: format); try? engine.start(); player.play(); rate = sampleRate }
        buffer.frameLength = buffer.frameCapacity; _ = data.copyBytes(to: UnsafeMutableBufferPointer(start: buffer.int16ChannelData?[0], count: data.count / 2)); player.scheduleBuffer(buffer)
    }
    func stop() { player.stop(); engine.stop(); rate = 0 }
}

private struct RemoteAppleIdentity {
    let deviceId: String
    private var key: P256.Signing.PrivateKey? {
        let account = "remote.identity.\(deviceId)"
        if let data = KeychainString.loadData(account), let existing = try? P256.Signing.PrivateKey(rawRepresentation: data) { return existing }
        let generated = P256.Signing.PrivateKey(); return KeychainString.saveData(generated.rawRepresentation, account: account) ? generated : nil
    }
    var publicKeyPEM: String? {
        guard let raw = key?.publicKey.x963Representation else { return nil }
        let prefix = Data([0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00])
        let base64 = (prefix + raw).base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
        return "-----BEGIN PUBLIC KEY-----\n\(base64)-----END PUBLIC KEY-----\n"
    }
    func sign(_ challenge: String) -> String? { try? key?.signature(for: Data(challenge.utf8)).derRepresentation.base64EncodedString() }
}

private enum KeychainString {
    static func load(_ account: String) -> String? { loadData(account).flatMap { String(data: $0, encoding: .utf8) } }
    static func save(_ value: String, account: String) -> Bool { saveData(Data(value.utf8), account: account) }
    static func loadData(_ account: String) -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassGenericPassword, kSecAttrService: "app.shackcq.remote", kSecAttrAccount: account, kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne] as CFDictionary, &result)
        return status == errSecSuccess ? result as? Data : nil
    }
    static func saveData(_ data: Data, account: String) -> Bool { let query = [kSecClass: kSecClassGenericPassword, kSecAttrService: "app.shackcq.remote", kSecAttrAccount: account] as CFDictionary; SecItemDelete(query); return SecItemAdd([kSecClass: kSecClassGenericPassword, kSecAttrService: "app.shackcq.remote", kSecAttrAccount: account, kSecValueData: data, kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly] as CFDictionary, nil) == errSecSuccess }
}

private enum RemoteAppleError: LocalizedError { case protocolViolation, pairingRejected; var errorDescription: String? { self == .pairingRejected ? "Station rejected pairing request" : "Remote Station protocol violation" } }
private func jsonString(_ value: [String: Any]) -> String { String(data: (try? JSONSerialization.data(withJSONObject: value)) ?? Data("{}".utf8), encoding: .utf8) ?? "{}" }
private func receiveJSON(_ socket: URLSessionWebSocketTask) async throws -> [String: Any] { let message = try await socket.receive(); guard case .string(let text) = message, let data = text.data(using: .utf8), let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw RemoteAppleError.protocolViolation }; return value }
private extension Data { func uint32BE(at i: Int) -> UInt32 { self[i..<i+4].reduce(0) { ($0 << 8) | UInt32($1) } }; func uint64BE(at i: Int) -> UInt64 { self[i..<i+8].reduce(0) { ($0 << 8) | UInt64($1) } } }
