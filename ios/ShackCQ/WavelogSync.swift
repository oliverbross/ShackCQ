import Foundation
import Security

private struct WavelogQueueItem: Codable, Identifiable {
    let id: String
    let adif: String
    var attempts: UInt32
    var nextAttempt: Date
    var state: String
    var lastError: String
}

struct WavelogStation: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let callsign: String
    let grid: String
    let city: String
    let country: String
    let active: Bool
    var label: String { [name, callsign, grid].filter { !$0.isEmpty }.joined(separator: " · ") }
}

struct WavelogContact: Codable, Identifiable {
    let id: String
    let callsign: String
    let name: String
    let band: String
    let mode: String
    let submode: String
    let country: String
    let date: String
    let time: String
    let frequency: String
    let rstSent: String
    let rstReceived: String
}

enum KeychainValue {
    static func load(_ account: String) -> String {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.shackcq.mobile", kSecAttrAccount as String: account,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    static func save(_ value: String, account: String) {
        let identity: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.shackcq.mobile", kSecAttrAccount as String: account]
        let attributes: [String: Any] = [kSecValueData as String: Data(value.utf8),
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        if SecItemUpdate(identity as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            var created = identity; attributes.forEach { created[$0.key] = $0.value }
            SecItemAdd(created as CFDictionary, nil)
        }
    }
}

@MainActor
final class WavelogSync: ObservableObject {
    @Published var baseURL: String { didSet { defaults.set(baseURL, forKey: "wavelogBaseURL") } }
    @Published var stationProfile: String { didSet { defaults.set(stationProfile, forKey: "wavelogStationProfile") } }
    @Published var apiKey: String { didSet { KeychainValue.save(apiKey, account: "wavelogApiKey") } }
    @Published private(set) var status = "Wavelog not configured"
    @Published private(set) var pendingCount = 0
    @Published private(set) var stations: [WavelogStation] = []
    @Published private(set) var contacts: [WavelogContact] = []
    @Published private(set) var syncPages = 0
    @Published private(set) var lastFullSync: Date?

    private var queue: [WavelogQueueItem] = []
    private weak var core: FeatureCore?
    private let defaults = UserDefaults.standard
    private let queueURL: URL
    private let contactsURL: URL
    private var syncing = false
    private var queuePersistenceFailed = false

    init() {
        baseURL = defaults.string(forKey: "wavelogBaseURL") ?? ""
        stationProfile = defaults.string(forKey: "wavelogStationProfile") ?? ""
        apiKey = KeychainValue.load("wavelogApiKey")
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        queueURL = directory.appendingPathComponent("wavelog-sync-queue.json")
        contactsURL = directory.appendingPathComponent("wavelog-contacts.json")
        if FileManager.default.fileExists(atPath: queueURL.path) {
            do {
                let data = try Data(contentsOf: queueURL)
                let decoded = try JSONDecoder().decode([WavelogQueueItem].self, from: data)
                queue = decoded; pendingCount = decoded.filter { $0.state == "pending" || $0.state == "retry" }.count
            } catch {
                status = "Wavelog queue is unreadable and was preserved for recovery"
            }
        }
        if let data = try? Data(contentsOf: contactsURL),
           let decoded = try? JSONDecoder().decode([WavelogContact].self, from: data) { contacts = decoded }
    }

    func bind(core: FeatureCore) { self.core = core; Task { await syncNow() } }

    func saveSettings() {
        baseURL = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        stationProfile = stationProfile.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(baseURL, forKey: "wavelogBaseURL")
        defaults.set(stationProfile, forKey: "wavelogStationProfile")
        KeychainValue.save(apiKey, account: "wavelogApiKey")
        defaults.synchronize()
        status = baseURL.isEmpty || apiKey.isEmpty ? "Wavelog settings saved; URL and API key are required" : "Wavelog settings saved"
    }

    func enqueue(id: String, adif: String) {
        guard !queue.contains(where: { $0.id == id }) else { status = "QSO already queued or acknowledged"; return }
        queue.append(WavelogQueueItem(id: id, adif: adif, attempts: 0, nextAttempt: Date(), state: "pending", lastError: ""))
        persist(); Task { await syncNow() }
    }

    func enqueueBatch(_ records: [(id: String, adif: String)]) {
        for record in records where !queue.contains(where: { $0.id == record.id }) {
            queue.append(WavelogQueueItem(id: record.id, adif: record.adif, attempts: 0, nextAttempt: Date(), state: "pending", lastError: ""))
        }
        persist()
        status = records.isEmpty ? status : "Fast Entry saved locally · \(records.count) Wavelog creates queued"
    }

    func canCancelUnsent(ids: Set<String>) -> Bool {
        ids.allSatisfy { id in queue.contains { $0.id == id && $0.state == "pending" && $0.attempts == 0 } }
    }

    func cancelUnsent(ids: Set<String>) {
        queue.removeAll { ids.contains($0.id) && $0.state == "pending" && $0.attempts == 0 }
        persist()
    }

    func syncNow() async {
        guard !syncing else { return }
        guard !queuePersistenceFailed else { status = "Wavelog sync paused because its queue could not be saved"; return }
        guard let core, !baseURL.isEmpty, !stationProfile.isEmpty, !apiKey.isEmpty else {
            status = queue.isEmpty ? "Wavelog not configured" : "Wavelog credentials required; QSOs remain queued"
            return
        }
        if apiKey.hasPrefix("wl2_") { await syncNativeV2(core: core); return }
        let endpoints = endpointCandidates("qso", core: core)
        guard !endpoints.isEmpty else { status = "Wavelog requires a valid HTTPS URL"; return }
        syncing = true; defer { syncing = false; persist() }
        for index in queue.indices where ["pending", "retry"].contains(queue[index].state) && queue[index].nextAttempt <= Date() {
            guard let payload = core.wavelogPayload(key: apiKey, station: stationProfile, adif: queue[index].adif) else {
                queue[index].state = "quarantined"; queue[index].lastError = "Payload encoding failed"; continue
            }
            queue[index].attempts += 1
            do {
                let (_, http) = try await perform(endpoints: endpoints, method: "POST", body: payload)
                apply(action: core.syncAction(status: http.statusCode, networkError: false, ambiguous: http.statusCode >= 500), index: index,
                      retryAfter: http.value(forHTTPHeaderField: "Retry-After").flatMap(UInt32.init), core: core)
            } catch {
                apply(action: core.syncAction(status: 0, networkError: true, ambiguous: true), index: index, retryAfter: nil, core: core)
                queue[index].lastError = error.localizedDescription
            }
        }
        status = summary
    }

    func loadStations() async {
        guard let core, !apiKey.isEmpty else {
            status = "Wavelog URL and API key are required"
            return
        }
        if apiKey.hasPrefix("wl2_") {
            status = "Loading Wavelog API v2 stations…"
            do {
                stations = try await WavelogNativeV2Client(baseURL: baseURL, token: apiKey).stations()
                if stationProfile.isEmpty, let preferred = stations.first(where: \.active) ?? stations.first { stationProfile = preferred.id }
                status = stations.isEmpty ? "No Wavelog stations available to this token" : "\(stations.count) Wavelog API v2 stations loaded"
            } catch { status = "Load stations failed: \(error.localizedDescription)" }
            return
        }
        let endpoints = endpointCandidates("station_info", core: core).map { $0.appendingPathComponent(apiKey) }
        guard !endpoints.isEmpty else { status = "Wavelog requires a valid HTTPS URL"; return }
        status = "Loading Wavelog stations…"
        do {
            let (data, response) = try await perform(endpoints: endpoints, method: "GET")
            try Self.requireSuccess(response)
            guard let rows = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                throw WavelogError.invalidResponse
            }
            stations = rows.compactMap(Self.station)
            if stations.isEmpty { status = "No Wavelog stations available to this API key" }
            else {
                if stationProfile.isEmpty, let preferred = stations.first(where: \.active) ?? stations.first {
                    stationProfile = preferred.id
                }
                status = "\(stations.count) Wavelog stations loaded"
            }
        } catch { status = "Load stations failed: \(error.localizedDescription)" }
    }

    func fullSync() async {
        guard !syncing else { return }
        guard let core, !apiKey.isEmpty else {
            status = "Wavelog URL, API key and station are required"
            return
        }
        if apiKey.hasPrefix("wl2_") { await fullSyncNativeV2(); return }
        guard let stationID = Int(stationProfile) else { status = "Select a numeric Wavelog station"; return }
        let endpoints = endpointCandidates("get_contacts_adif", core: core)
        guard !endpoints.isEmpty else { status = "Wavelog requires a valid HTTPS URL"; return }
        syncing = true; defer { syncing = false }
        var cursor: Int64 = 0
        var pages = 0
        var loaded: [WavelogContact] = []
        var complete = false
        status = "Starting full Wavelog sync…"
        do {
            for page in 0..<256 {
                let body: [String: Any] = ["key": apiKey, "station_id": stationID,
                    "fetchfromid": cursor, "output_format": "json", "fields": Self.contactFields]
                let payload = try JSONSerialization.data(withJSONObject: body)
                let (data, response) = try await perform(endpoints: endpoints, method: "POST", body: payload)
                try Self.requireSuccess(response)
                let root = try JSONSerialization.jsonObject(with: data)
                guard let exported = Self.integer(in: root, keys: ["exported_qsos", "exported_records", "exportedRecords"]),
                      let next = Self.integer(in: root, keys: ["lastfetchedid", "lastFetchedId", "last_fetched_id"]) else {
                    throw WavelogError.invalidCursor
                }
                if exported == 0 { complete = true; break }
                guard next > cursor else { throw WavelogError.invalidCursor }
                loaded.append(contentsOf: Self.contactRows(in: root))
                cursor = next; pages = page + 1; syncPages = pages
                status = "Full sync page \(pages) · \(loaded.count) QSOs"
            }
            guard complete else { throw WavelogError.incompleteSync }
            contacts = Self.deduplicated(loaded)
            lastFullSync = Date()
            if let data = try? JSONEncoder().encode(contacts) { try data.write(to: contactsURL, options: .atomic) }
            status = "Full Wavelog sync complete · \(contacts.count) QSOs · \(pages) pages"
        } catch { status = "Full Wavelog sync failed: \(error.localizedDescription)" }
    }

    func selectStation(_ id: String) {
        stationProfile = id
        if let station = stations.first(where: { $0.id == id }) { status = "Selected \(station.label)" }
    }

    func testConnection() async {
        guard let core, !apiKey.isEmpty else {
            status = "Wavelog URL and API key are required"
            return
        }
        if apiKey.hasPrefix("wl2_") {
            status = "Testing Wavelog API v2 token…"
            do {
                let metadata = try await WavelogNativeV2Client(baseURL: baseURL, token: apiKey).metadata()
                let expiry = metadata.expiresAt.isEmpty ? "no expiry reported" : "expires \(metadata.expiresAt)"
                status = "Wavelog API v2 connected · \(metadata.owner) · \(metadata.scopes.sorted().joined(separator: ", ")) · \(expiry)"
            } catch { status = "Wavelog API v2 test failed: \(error.localizedDescription)" }
            return
        }
        let endpoints = endpointCandidates("version", core: core)
        guard !endpoints.isEmpty else { status = "Wavelog requires a valid HTTPS URL"; return }
        status = "Testing Wavelog API…"
        do {
            let payload = try JSONSerialization.data(withJSONObject: ["key": apiKey])
            let (data, response) = try await perform(endpoints: endpoints, method: "POST", body: payload)
            try Self.requireSuccess(response)
            guard !data.isEmpty else { throw WavelogError.invalidResponse }
            let body = String(decoding: data, as: UTF8.self).lowercased()
            guard !["error", "invalid", "unauthorized"].contains(where: body.contains) else {
                throw WavelogError.invalidResponse
            }
            status = "Wavelog connection passed"
        } catch {
            status = "Wavelog test failed: \(error.localizedDescription)"
        }
    }

    func checkDeviceTime() async {
        guard let core else { status = "Wavelog service is not ready"; return }
        let endpoints = endpointCandidates("version", core: core)
        guard !endpoints.isEmpty else { status = "Set the Wavelog HTTPS URL before checking time"; return }
        status = "Checking device time against Wavelog…"
        do {
            let payload = try JSONSerialization.data(withJSONObject: ["key": apiKey])
            let (_, response) = try await perform(endpoints: endpoints, method: "POST", body: payload)
            guard let header = response.value(forHTTPHeaderField: "Date") else { throw WavelogError.missingServerTime }
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = .gmt; formatter.dateFormat = "EEE',' dd MMM yyyy HH':'mm':'ss z"
            guard let serverDate = formatter.date(from: header) else { throw WavelogError.missingServerTime }
            let drift = abs(Date().timeIntervalSince(serverDate))
            status = drift <= 5 ? "Device time synchronized · drift under 5 seconds" :
                "Device time differs from Wavelog by \(Int(drift)) seconds · enable automatic date & time"
        } catch { status = "Time check failed: \(error.localizedDescription)" }
    }

    private func apply(action: Int, index: Int, retryAfter: UInt32?, core: FeatureCore) {
        switch action {
        case 0: queue[index].state = "acknowledged"; queue[index].lastError = ""
        case 1:
            queue[index].state = "retry"
            let delay = core.retryDelay(attempt: queue[index].attempts, seed: UInt32(truncatingIfNeeded: queue[index].id.hashValue), retryAfter: retryAfter)
            queue[index].nextAttempt = Date().addingTimeInterval(TimeInterval(delay))
        case 2: queue[index].state = "auth"; queue[index].lastError = "Authentication rejected"
        case 3: queue[index].state = "quarantined"; queue[index].lastError = "Request rejected"
        default: queue[index].state = "inspect"; queue[index].lastError = "Ambiguous response"
        }
    }

    private func syncNativeV2(core: FeatureCore) async {
        syncing = true; defer { syncing = false; persist() }
        do {
            let client = try WavelogNativeV2Client(baseURL: baseURL, token: apiKey)
            for index in queue.indices where ["pending", "retry"].contains(queue[index].state) && queue[index].nextAttempt <= Date() {
                queue[index].attempts += 1
                do {
                try await client.create(stationID: stationProfile, adif: queue[index].adif)
                    queue[index].state = "acknowledged"; queue[index].lastError = ""
                } catch WavelogV2ClientError.network(let message) {
                    queue[index].state = "inspect"; queue[index].lastError = "Ambiguous write: \(message)"
                } catch WavelogV2ClientError.response(let http, let message, let retryAfter) {
                    apply(action: core.syncAction(status: http, networkError: false, ambiguous: http >= 500), index: index,
                          retryAfter: retryAfter, core: core); queue[index].lastError = message
                } catch {
                    queue[index].state = "quarantined"; queue[index].lastError = error.localizedDescription
                }
            }
            status = summary
        } catch { status = "Wavelog API v2 sync failed: \(error.localizedDescription)" }
    }

    private func fullSyncNativeV2() async {
        guard !stationProfile.isEmpty else { status = "Select a Wavelog station"; return }
        syncing = true; defer { syncing = false }
        var loaded: [WavelogContact] = []
        do {
            let client = try WavelogNativeV2Client(baseURL: baseURL, token: apiKey)
            for pageNumber in 1...256 {
                let page = try await client.qsoPage(stationID: stationProfile, page: pageNumber)
                loaded.append(contentsOf: page.rows.compactMap(Self.v2Contact))
                syncPages = pageNumber; status = "Wavelog API v2 page \(pageNumber) · \(loaded.count) QSOs"
                if !page.hasMore {
                    contacts = Self.deduplicated(loaded); lastFullSync = Date()
                    if let data = try? JSONEncoder().encode(contacts) { try data.write(to: contactsURL, options: .atomic) }
                    status = "Full Wavelog API v2 sync complete · \(contacts.count) QSOs · \(pageNumber) pages"
                    return
                }
            }
            status = "Full Wavelog API v2 sync stopped at the safety page limit"
        } catch { status = "Full Wavelog API v2 sync failed: \(error.localizedDescription)" }
    }

    private static func v2Contact(_ row: [String: Any]) -> WavelogContact? {
        func field(_ name: String) -> String { text(row.first(where: { $0.key.uppercased() == name })?.value) }
        let call = field("CALL").uppercased(); guard !call.isEmpty else { return nil }
        let remoteID = text(row["id"]); let id = remoteID.isEmpty
            ? [call, field("QSO_DATE"), field("TIME_ON"), field("BAND"), field("MODE")].joined(separator: "-") : remoteID
        return WavelogContact(id: id, callsign: call, name: field("NAME"), band: field("BAND"), mode: field("MODE"),
            submode: field("SUBMODE"), country: field("COUNTRY"), date: field("QSO_DATE"), time: field("TIME_ON"),
            frequency: field("FREQ"), rstSent: field("RST_SENT"), rstReceived: field("RST_RCVD"))
    }

    private var summary: String {
        let acknowledged = queue.filter { $0.state == "acknowledged" }.count
        let pending = queue.filter { $0.state == "pending" || $0.state == "retry" }.count
        return "Wavelog: \(acknowledged) acknowledged · \(pending) pending"
    }

    private func endpointCandidates(_ resource: String, core: FeatureCore) -> [URL] {
        var value = core.normalizedWavelogURL(baseURL)
        while value.hasSuffix("/") { value.removeLast() }
        guard let base = URL(string: value), base.scheme?.lowercased() == "https" else { return [] }
        let roots: [String]
        if value.hasSuffix("/index.php/api") || value.hasSuffix("/api") {
            roots = [value]
        } else if value.hasSuffix("/index.php") {
            roots = [value + "/api"]
        } else {
            roots = [value + "/api", value + "/index.php/api"]
        }
        return roots.compactMap(URL.init(string:)).map { $0.appendingPathComponent(resource) }
    }

    private func perform(endpoints: [URL], method: String, body: Data? = nil) async throws -> (Data, HTTPURLResponse) {
        for (index, endpoint) in endpoints.enumerated() {
            var request = URLRequest(url: endpoint)
            request.httpMethod = method
            request.timeoutInterval = 20
            request.httpBody = body
            if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw WavelogError.invalidResponse }
            if [404, 405].contains(http.statusCode), index + 1 < endpoints.count { continue }
            return (data, http)
        }
        throw WavelogError.invalidResponse
    }

    private static let contactFields = ["CALL", "NAME", "BAND", "MODE", "SUBMODE", "DXCC", "COUNTRY",
        "QSO_DATE", "TIME_ON", "FREQ", "RST_SENT", "RST_RCVD", "GRIDSQUARE", "STATION_CALLSIGN",
        "MY_GRIDSQUARE", "COMMENT"]

    private static func station(_ row: [String: Any]) -> WavelogStation? {
        let id = text(row["station_id"]); guard !id.isEmpty, Int(id) != nil else { return nil }
        return WavelogStation(id: id, name: text(row["station_profile_name"]), callsign: text(row["station_callsign"]),
            grid: text(row["station_gridsquare"]), city: text(row["station_city"]), country: text(row["station_country"]),
            active: bool(row["station_active"]))
    }

    private static func contactRows(in value: Any) -> [WavelogContact] {
        var result: [WavelogContact] = []
        func visit(_ node: Any) {
            if let rows = node as? [[String: Any]], rows.contains(where: { !$0.keys.filter { $0.uppercased() == "CALL" }.isEmpty }) {
                result.append(contentsOf: rows.compactMap(contact)); return
            }
            if let array = node as? [Any] { array.forEach(visit) }
            else if let object = node as? [String: Any] { object.values.forEach(visit) }
        }
        visit(value); return result
    }

    private static func contact(_ row: [String: Any]) -> WavelogContact? {
        func field(_ name: String) -> String {
            text(row.first(where: { $0.key.uppercased() == name })?.value)
        }
        let call = field("CALL").uppercased(); guard !call.isEmpty else { return nil }
        let id = field("COL_PRIMARY_KEY").isEmpty
            ? [call, field("QSO_DATE"), field("TIME_ON"), field("BAND"), field("MODE")].joined(separator: "-")
            : field("COL_PRIMARY_KEY")
        return WavelogContact(id: id, callsign: call, name: field("NAME"), band: field("BAND"), mode: field("MODE"),
            submode: field("SUBMODE"), country: field("COUNTRY"), date: field("QSO_DATE"), time: field("TIME_ON"),
            frequency: field("FREQ"), rstSent: field("RST_SENT"), rstReceived: field("RST_RCVD"))
    }

    private static func integer(in value: Any, keys: Set<String>) -> Int64? {
        if let object = value as? [String: Any] {
            for (key, item) in object where keys.contains(key) {
                if let number = item as? NSNumber { return number.int64Value }
                if let string = item as? String, let number = Int64(string) { return number }
            }
            for item in object.values { if let found = integer(in: item, keys: keys) { return found } }
        } else if let array = value as? [Any] {
            for item in array { if let found = integer(in: item, keys: keys) { return found } }
        }
        return nil
    }

    private static func deduplicated(_ rows: [WavelogContact]) -> [WavelogContact] {
        var seen = Set<String>(); return rows.filter { seen.insert($0.id).inserted }
    }

    private static func text(_ value: Any?) -> String {
        if let string = value as? String { return string }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
    }

    private static func bool(_ value: Any?) -> Bool {
        if let flag = value as? Bool { return flag }
        if let number = value as? NSNumber { return number.boolValue }
        return ["1", "true", "yes"].contains(text(value).lowercased())
    }

    private static func requireSuccess(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode else {
            throw WavelogError.http((response as? HTTPURLResponse)?.statusCode ?? 0)
        }
    }

    private enum WavelogError: LocalizedError {
        case invalidResponse, invalidCursor, incompleteSync, missingServerTime, http(Int)
        var errorDescription: String? {
            switch self {
            case .invalidResponse: "Invalid Wavelog response"
            case .invalidCursor: "Invalid Wavelog pagination cursor"
            case .incompleteSync: "Wavelog full sync ended before the terminal page"
            case .missingServerTime: "Wavelog did not return a server time"
            case .http(let status): "Wavelog HTTP \(status)"
            }
        }
    }

    private func persist() {
        pendingCount = queue.filter { $0.state == "pending" || $0.state == "retry" }.count
        do {
            let data = try JSONEncoder().encode(queue)
            try data.write(to: queueURL, options: .atomic)
            queuePersistenceFailed = false
        } catch {
            queuePersistenceFailed = true
            status = "Wavelog queue could not be saved; sync is paused to protect pending QSOs"
        }
    }
}
