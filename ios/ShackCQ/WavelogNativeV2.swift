import Foundation

struct WavelogV2TokenMetadata {
    let owner: String
    let scopes: Set<String>
    let expiresAt: String
}

struct WavelogV2QsoPage {
    let rows: [[String: Any]]
    let page: Int
    let hasMore: Bool
}

enum WavelogV2ClientError: LocalizedError {
    case invalidURL
    case invalidToken
    case network(String)
    case response(Int, String, UInt32?)

    var errorDescription: String? {
        switch self {
        case .invalidURL: "Wavelog API v2 requires a valid HTTPS URL"
        case .invalidToken: "Wavelog API v2 requires a wl2_ token"
        case .network(let message): message
        case .response(let status, let message, _): "Wavelog HTTP \(status): \(message)"
        }
    }
}

struct WavelogNativeV2Client {
    let root: URL
    let token: String

    init(baseURL: String, token: String) throws {
        guard token.hasPrefix("wl2_") else { throw WavelogV2ClientError.invalidToken }
        var value = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if !value.contains("://") { value = "https://" + value }
        while value.hasSuffix("/") { value.removeLast() }
        guard var components = URLComponents(string: value), components.scheme?.lowercased() == "https",
              components.host?.isEmpty == false else { throw WavelogV2ClientError.invalidURL }
        components.scheme = "https"; components.query = nil; components.fragment = nil; components.user = nil; components.password = nil
        guard let clean = components.url else { throw WavelogV2ClientError.invalidURL }
        let path = clean.path
        if path.hasSuffix("/index.php/api/v2") || path.hasSuffix("/api/v2") { root = clean }
        else if path.hasSuffix("/index.php") { root = clean.appendingPathComponent("api/v2") }
        else { root = clean.appendingPathComponent("index.php/api/v2") }
        self.token = token
    }

    func metadata() async throws -> WavelogV2TokenMetadata {
        let root = try await request("token")
        guard let data = root["data"] as? [String: Any] else { throw WavelogV2ClientError.response(200, "missing token metadata", nil) }
        return WavelogV2TokenMetadata(owner: Self.text(data["owner"]),
            scopes: Set((data["scopes"] as? [String]) ?? []), expiresAt: Self.text(data["expires_at"]))
    }

    func stations() async throws -> [WavelogStation] {
        let root = try await request("station")
        let rows = root["data"] as? [[String: Any]] ?? []
        return rows.compactMap { row in
            let id = Self.first(row, "id", "station_id"); guard !id.isEmpty else { return nil }
            return WavelogStation(id: id, name: Self.first(row, "name", "station_profile_name"),
                callsign: Self.first(row, "callsign", "station_callsign"),
                grid: Self.first(row, "grid", "gridsquare", "station_gridsquare"), city: "", country: "",
                active: Self.flag(row["active"]) || Self.flag(row["station_active"]))
        }
    }

    func qsoPage(stationID: String, page: Int, perPage: Int = 250) async throws -> WavelogV2QsoPage {
        var components = URLComponents(url: root.appendingPathComponent("qso"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "station_id", value: stationID),
            URLQueryItem(name: "page", value: String(page)), URLQueryItem(name: "per_page", value: String(perPage))]
        let value = try await request(url: components.url!, method: "GET")
        let meta = value["meta"] as? [String: Any] ?? [:]
        return WavelogV2QsoPage(rows: value["data"] as? [[String: Any]] ?? [],
            page: (meta["page"] as? NSNumber)?.intValue ?? page, hasMore: Self.flag(meta["has_more"]))
    }

    func create(stationID: String, adif: String) async throws {
        guard let numericID = Int64(stationID) else { throw WavelogV2ClientError.response(0, "invalid station id", nil) }
        let body = try JSONSerialization.data(withJSONObject: ["station_profile_id": numericID, "import_type": "adif", "adif": adif])
        _ = try await request(url: root.appendingPathComponent("qso"), method: "POST", body: body)
    }

    private func request(_ resource: String) async throws -> [String: Any] {
        try await request(url: root.appendingPathComponent(resource), method: "GET")
    }

    private func request(url: URL, method: String, body: Data? = nil) async throws -> [String: Any] {
        var request = URLRequest(url: url); request.httpMethod = method; request.timeoutInterval = 30; request.httpBody = body
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let data: Data; let response: URLResponse
        do { (data, response) = try await URLSession.shared.data(for: request) }
        catch { throw WavelogV2ClientError.network(error.localizedDescription) }
        guard let http = response as? HTTPURLResponse else { throw WavelogV2ClientError.response(0, "invalid response", nil) }
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard 200..<300 ~= http.statusCode else {
            let error = object["error"] as? [String: Any] ?? [:]
            let message = Self.text(error["message"]).isEmpty ? "request rejected" : Self.text(error["message"])
            throw WavelogV2ClientError.response(http.statusCode, String(message.prefix(300)),
                http.value(forHTTPHeaderField: "Retry-After").flatMap(UInt32.init))
        }
        return object
    }

    private static func first(_ row: [String: Any], _ keys: String...) -> String {
        keys.lazy.map { text(row[$0]) }.first(where: { !$0.isEmpty && $0.lowercased() != "null" }) ?? ""
    }
    private static func text(_ value: Any?) -> String {
        if let value = value as? String { return value }
        if let value = value as? NSNumber { return value.stringValue }
        return ""
    }
    private static func flag(_ value: Any?) -> Bool {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        return ["1", "true", "yes"].contains(text(value).lowercased())
    }
}
