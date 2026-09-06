import Foundation

struct CallbookRecord: Equatable {
    let callsign: String
    let name: String
    let location: String
    let country: String
    let grid: String
    let latitude: String
    let longitude: String
}

private final class XMLFields: NSObject, XMLParserDelegate {
    private(set) var values: [String: String] = [:]
    private var key = ""
    private var text = ""
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName: String?, attributes attributeDict: [String : String] = [:]) {
        key = elementName.lowercased(); text = ""
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !value.isEmpty { values[elementName.lowercased()] = value }
        key = ""; text = ""
    }
}

@MainActor
final class CallbookService: ObservableObject {
    @Published var provider: String { didSet { defaults.set(provider, forKey: "callbookProvider"); session = "" } }
    @Published var username: String { didSet { defaults.set(username, forKey: "callbookUsername"); session = "" } }
    @Published var password: String { didSet { KeychainValue.save(password, account: "callbookPassword"); session = "" } }
    @Published private(set) var result: CallbookRecord?
    @Published private(set) var status = "Callbook not queried"
    private let defaults = UserDefaults.standard
    private var session = ""

    init() {
        provider = defaults.string(forKey: "callbookProvider") ?? "QRZ"
        username = defaults.string(forKey: "callbookUsername") ?? ""
        password = KeychainValue.load("callbookPassword")
    }

    func lookup(_ callsign: String) async {
        let call = callsign.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !call.isEmpty else { status = "Enter a callsign"; return }
        guard !username.isEmpty, !password.isEmpty else { status = "\(provider) username and password required"; return }
        status = "Querying \(provider)…"; result = nil
        do {
            if session.isEmpty { session = try await login() }
            var fields = try await request(call: call)
            if let error = fields["error"], error.lowercased().contains("session") {
                session = try await login(); fields = try await request(call: call)
            }
            if let error = fields["error"] { throw CallbookError.remote(error) }
            let resolvedCall = fields["call"] ?? fields["callsign"] ?? call
            let name = [fields["fname"], fields["name"], fields["nick"]].compactMap { $0 }.joined(separator: " ")
            result = CallbookRecord(callsign: resolvedCall, name: name,
                location: fields["addr2"] ?? fields["qth"] ?? "", country: fields["country"] ?? "",
                grid: fields["grid"] ?? "", latitude: fields["lat"] ?? "", longitude: fields["lon"] ?? "")
            status = "Live \(provider) result"
        } catch { status = error.localizedDescription }
    }

    func testConnection() async {
        guard !username.isEmpty, !password.isEmpty else {
            status = "\(provider) username and password required"
            return
        }
        status = "Testing \(provider)…"
        do {
            session = try await login()
            status = "\(provider) connection passed"
        } catch { status = "\(provider) test failed: \(error.localizedDescription)" }
    }

    private func login() async throws -> String {
        let url: URL
        if provider == "HamQTH" {
            url = try endpoint("https://www.hamqth.com/xml.php", ["u": username, "p": password])
        } else {
            url = try endpoint("https://xmldata.qrz.com/xml/current/", ["username": username, "password": password, "agent": "ShackCQ-0.1"])
        }
        let fields = try await xml(url)
        if let error = fields["error"] { throw CallbookError.remote(error) }
        guard let key = fields[provider == "HamQTH" ? "session_id" : "key"], !key.isEmpty else { throw CallbookError.noSession }
        return key
    }

    private func request(call: String) async throws -> [String: String] {
        if provider == "HamQTH" {
            return try await xml(endpoint("https://www.hamqth.com/xml.php", ["id": session, "callsign": call, "prg": "ShackCQ"] as [String: String]))
        }
        return try await xml(endpoint("https://xmldata.qrz.com/xml/current/", ["s": session, "callsign": call]))
    }

    private func endpoint(_ base: String, _ query: [String: String]) throws -> URL {
        guard var components = URLComponents(string: base) else { throw CallbookError.invalidURL }
        components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        guard let url = components.url else { throw CallbookError.invalidURL }
        return url
    }

    private func xml(_ url: URL) async throws -> [String: String] {
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw CallbookError.http }
        let delegate = XMLFields(); let parser = XMLParser(data: data); parser.delegate = delegate
        guard parser.parse() else { throw CallbookError.invalidXML }
        return delegate.values
    }

    enum CallbookError: LocalizedError {
        case invalidURL, noSession, http, invalidXML, remote(String)
        var errorDescription: String? {
            switch self { case .invalidURL: "Invalid callbook URL"; case .noSession: "Callbook login returned no session"; case .http: "Callbook HTTP request failed"; case .invalidXML: "Callbook returned invalid XML"; case .remote(let value): value }
        }
    }
}

@MainActor
final class CtyDatabase: ObservableObject {
    @Published private(set) var status = "CTY.DAT not installed"
    @Published private(set) var prefixCount = 0
    private var prefixes: [(String, String)] = []
    private var installedRawText: String?
    private let fileURL: URL
    var onInstalledDataChanged: (() -> Void)?

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("cty.dat")
        load()
    }

    func update() async {
        guard let url = URL(string: "https://www.country-files.com/cty/cty.dat") else { return }
        status = "Downloading CTY.DAT…"
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode,
                  data.count > 50_000, data.count <= Self.maximumCtyBytes,
                  let text = String(data: data, encoding: .utf8),
                  text.contains(":") && text.contains(";") else {
                status = "CTY.DAT update rejected: invalid content"; return
            }
            let temporary = fileURL.appendingPathExtension("tmp")
            try data.write(to: temporary, options: .atomic)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                _ = try FileManager.default.replaceItemAt(fileURL, withItemAt: temporary,
                    backupItemName: "cty.dat.previous", options: .usingNewMetadataOnly)
            } else {
                try FileManager.default.moveItem(at: temporary, to: fileURL)
            }
            guard load() else { status = "CTY.DAT update rejected: invalid content"; return }
            status = "CTY.DAT updated · \(prefixCount) prefixes"
            onInstalledDataChanged?()
        } catch {
            if prefixes.isEmpty { status = "CTY.DAT update failed: \(error.localizedDescription)" }
        }
    }

    func country(for callsign: String) -> String {
        let call = callsign.uppercased()
        return prefixes.first(where: { call.hasPrefix($0.0) })?.1 ?? ""
    }

    func installedText() -> String? { installedRawText }

    @discardableResult private func load() -> Bool {
        guard let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size > 0, size <= Self.maximumCtyBytes,
              let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return false }
        var loaded: [(String, String)] = []
        for record in text.split(separator: ";") {
            let fields = record.split(separator: ":", maxSplits: 8, omittingEmptySubsequences: false)
            guard fields.count == 9 else { continue }
            let country = fields[0].trimmingCharacters(in: .whitespacesAndNewlines)
            for raw in fields[8].split(separator: ",") {
                var prefix = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                if prefix.hasPrefix("=") { prefix.removeFirst() }
                if let modifier = prefix.firstIndex(where: { "([<{~".contains($0) }) { prefix.removeSubrange(modifier...) }
                if !prefix.isEmpty { loaded.append((prefix, country)) }
            }
        }
        guard !loaded.isEmpty else { return false }
        prefixes = loaded.sorted { $0.0.count > $1.0.count }
        installedRawText = text
        prefixCount = prefixes.count
        if !prefixes.isEmpty { status = "CTY.DAT ready · \(prefixCount) prefixes" }
        return true
    }

    private static let maximumCtyBytes = 2 * 1024 * 1024
}
