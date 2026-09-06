import Foundation
import SQLite3

private let qsoSQLiteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

struct QSO: Identifiable, Equatable {
    let id: String
    let callsign: String
    let frequencyHz: UInt64
    let mode: String
    let rstSent: String
    let rstReceived: String
    let createdAt: Date
    let name: String
    let qth: String
    let country: String
    let notes: String
    let fields: [String: String]

    init(id: String, callsign: String, frequencyHz: UInt64, mode: String,
         rstSent: String, rstReceived: String, createdAt: Date, name: String = "",
         qth: String = "", country: String = "", notes: String = "", fields: [String: String] = [:]) {
        self.id = id; self.callsign = callsign; self.frequencyHz = frequencyHz; self.mode = mode
        self.rstSent = rstSent; self.rstReceived = rstReceived; self.createdAt = createdAt
        self.name = name; self.qth = qth; self.country = country; self.notes = notes; self.fields = fields
    }
}

struct AppleFastEntryImportReceipt { let qsoIDs: [String]; let skipped: Int; let revision: Int }

@MainActor
final class QSOStore: ObservableObject {
    @Published private(set) var records: [QSO] = []
    @Published private(set) var message = ""
    private var database: OpaquePointer?
    var onWorkedLogChanged: (() -> Void)?
    private var workedLogNotificationBatchDepth = 0
    private var workedLogNotificationPending = false
    private var revision = 0

    init() {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let path = directory.appendingPathComponent("shackcq.sqlite").path
        guard sqlite3_open(path, &database) == SQLITE_OK else { message = "Log database unavailable"; return }
        sqlite3_exec(database, "PRAGMA journal_mode=WAL", nil, nil, nil)
        sqlite3_exec(database, "CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)", nil, nil, nil)
        sqlite3_exec(database, "CREATE TABLE IF NOT EXISTS radio_profile(id TEXT PRIMARY KEY, model TEXT NOT NULL)", nil, nil, nil)
        sqlite3_exec(database, "CREATE TABLE IF NOT EXISTS qso(id TEXT PRIMARY KEY, callsign TEXT NOT NULL, frequency_hz INTEGER NOT NULL, mode TEXT NOT NULL, rst_sent TEXT NOT NULL, rst_received TEXT NOT NULL, created_at INTEGER NOT NULL)", nil, nil, nil)
        sqlite3_exec(database, "ALTER TABLE qso ADD COLUMN name TEXT NOT NULL DEFAULT ''", nil, nil, nil)
        sqlite3_exec(database, "ALTER TABLE qso ADD COLUMN qth TEXT NOT NULL DEFAULT ''", nil, nil, nil)
        sqlite3_exec(database, "ALTER TABLE qso ADD COLUMN country TEXT NOT NULL DEFAULT ''", nil, nil, nil)
        sqlite3_exec(database, "ALTER TABLE qso ADD COLUMN notes TEXT NOT NULL DEFAULT ''", nil, nil, nil)
        sqlite3_exec(database, "ALTER TABLE qso ADD COLUMN details_json TEXT NOT NULL DEFAULT '{}'", nil, nil, nil)
        sqlite3_exec(database, "CREATE INDEX IF NOT EXISTS apple_qso_time_idx ON qso(created_at DESC)", nil, nil, nil)
        sqlite3_exec(database, "CREATE INDEX IF NOT EXISTS apple_qso_identity_idx ON qso(UPPER(callsign),frequency_hz,UPPER(mode),created_at)", nil, nil, nil)
        reload()
    }

    deinit { sqlite3_close(database) }

    @discardableResult
    func save(_ qso: QSO, reload shouldReload: Bool = true) -> Bool {
        guard database != nil else { message = "Log database unavailable"; return false }
        let duplicateSQL = "SELECT 1 FROM qso WHERE UPPER(callsign)=UPPER(?) AND frequency_hz=? AND UPPER(mode)=UPPER(?) AND created_at BETWEEN ? AND ? LIMIT 1"
        var duplicate: OpaquePointer?
        sqlite3_prepare_v2(database, duplicateSQL, -1, &duplicate, nil)
        bind(qso.callsign, to: duplicate, at: 1)
        sqlite3_bind_int64(duplicate, 2, sqlite3_int64(qso.frequencyHz))
        bind(qso.mode, to: duplicate, at: 3)
        sqlite3_bind_int64(duplicate, 4, sqlite3_int64(qso.createdAt.timeIntervalSince1970) - 15)
        sqlite3_bind_int64(duplicate, 5, sqlite3_int64(qso.createdAt.timeIntervalSince1970) + 15)
        if sqlite3_step(duplicate) == SQLITE_ROW {
            sqlite3_finalize(duplicate); message = "Immediate duplicate not saved"; return false
        }
        sqlite3_finalize(duplicate)

        let sql = "INSERT INTO qso(id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at,name,qth,country,notes,details_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        var statement: OpaquePointer?
        sqlite3_prepare_v2(database, sql, -1, &statement, nil)
        bind(qso.id, to: statement, at: 1); bind(qso.callsign, to: statement, at: 2)
        sqlite3_bind_int64(statement, 3, sqlite3_int64(qso.frequencyHz)); bind(qso.mode, to: statement, at: 4)
        bind(qso.rstSent, to: statement, at: 5); bind(qso.rstReceived, to: statement, at: 6)
        sqlite3_bind_int64(statement, 7, sqlite3_int64(qso.createdAt.timeIntervalSince1970))
        bind(qso.name, to: statement, at: 8); bind(qso.qth, to: statement, at: 9)
        bind(qso.country, to: statement, at: 10); bind(qso.notes, to: statement, at: 11)
        bind(json(qso.fields), to: statement, at: 12)
        let saved = sqlite3_step(statement) == SQLITE_DONE
        sqlite3_finalize(statement)
        message = saved ? "QSO saved locally" : "QSO save failed"
        if saved {
            revision += 1
            if shouldReload {
                if workedLogNotificationBatchDepth > 0 { workedLogNotificationPending = true }
                else { reload() }
            }
        }
        return saved
    }

    func importFastEntry(_ rows: [FastEntryCanonical], wavelog: WavelogSync, serialize: (QSO) -> String) -> AppleFastEntryImportReceipt {
        guard database != nil else { return .init(qsoIDs: [], skipped: rows.count, revision: revision) }
        guard sqlite3_exec(database, "BEGIN IMMEDIATE", nil, nil, nil) == SQLITE_OK else {
            message = "Fast Entry could not start a database transaction"
            return .init(qsoIDs: [], skipped: rows.count, revision: revision)
        }
        let startingRevision = revision
        var inserted: [QSO] = []; var skipped = 0
        for row in rows {
            let qso = QSO(id: row.id, callsign: row.callsign, frequencyHz: row.frequencyHz, mode: row.mode,
                rstSent: row.rstSent, rstReceived: row.rstReceived, createdAt: row.createdAt,
                name: row.name, qth: row.qth, country: row.country, notes: row.notes,
                fields: row.fields.merging(["BAND": row.band, "SUBMODE": row.submode, "GRIDSQUARE": row.grid]) { current, _ in current })
            if save(qso, reload: false) { inserted.append(qso) } else { skipped += 1 }
        }
        guard sqlite3_exec(database, "COMMIT", nil, nil, nil) == SQLITE_OK else {
            sqlite3_exec(database, "ROLLBACK", nil, nil, nil)
            revision = startingRevision
            reload(); message = "Fast Entry transaction failed; no QSOs were queued"
            return .init(qsoIDs: [], skipped: rows.count, revision: revision)
        }
        wavelog.enqueueBatch(inserted.map { ($0.id, serialize($0)) })
        reload(); message = "Fast Entry imported \(inserted.count) · skipped \(skipped)"
        return .init(qsoIDs: inserted.map(\.id), skipped: skipped, revision: revision)
    }

    func undoFastEntry(_ receipt: AppleFastEntryImportReceipt, wavelog: WavelogSync) -> Bool {
        guard revision == receipt.revision, database != nil else { message = "Undo expired after a later log mutation"; return false }
        guard wavelog.canCancelUnsent(ids: Set(receipt.qsoIDs)) else { message = "Undo expired because Wavelog delivery was attempted"; return false }
        guard sqlite3_exec(database, "BEGIN IMMEDIATE", nil, nil, nil) == SQLITE_OK else {
            message = "Undo could not start a database transaction"; return false
        }
        var statement: OpaquePointer?; sqlite3_prepare_v2(database, "DELETE FROM qso WHERE id=?", -1, &statement, nil)
        var deleted = true
        for id in receipt.qsoIDs {
            sqlite3_reset(statement); sqlite3_clear_bindings(statement); bind(id, to: statement, at: 1)
            if sqlite3_step(statement) != SQLITE_DONE { deleted = false; break }
        }
        sqlite3_finalize(statement)
        guard deleted, sqlite3_exec(database, "COMMIT", nil, nil, nil) == SQLITE_OK else {
            sqlite3_exec(database, "ROLLBACK", nil, nil, nil)
            message = "Undo failed; local QSOs and Wavelog queue were preserved"; return false
        }
        wavelog.cancelUnsent(ids: Set(receipt.qsoIDs)); revision += 1; reload(); message = "Fast Entry import undone"
        return true
    }

    func reload() {
        guard database != nil else { return }
        var statement: OpaquePointer?
        sqlite3_prepare_v2(database, "SELECT id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at,name,qth,country,notes,details_json FROM qso ORDER BY created_at DESC LIMIT 100", -1, &statement, nil)
        var loaded: [QSO] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            loaded.append(QSO(id: text(statement, 0), callsign: text(statement, 1),
                frequencyHz: UInt64(sqlite3_column_int64(statement, 2)), mode: text(statement, 3),
                rstSent: text(statement, 4), rstReceived: text(statement, 5),
                createdAt: Date(timeIntervalSince1970: TimeInterval(sqlite3_column_int64(statement, 6))),
                name: text(statement, 7), qth: text(statement, 8), country: text(statement, 9), notes: text(statement, 10), fields: fields(text(statement, 11))))
        }
        sqlite3_finalize(statement); records = loaded
        if workedLogNotificationBatchDepth > 0 { workedLogNotificationPending = true }
        else { onWorkedLogChanged?() }
    }

    func exportADIF(using serialize: (QSO) -> String) -> URL? {
        let records = allRecords()
        let content = records.map(serialize).joined()
        guard !content.isEmpty else { message = "No local QSOs to export"; return nil }
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            .appendingPathComponent("Exports", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let formatter = DateFormatter(); formatter.timeZone = .gmt; formatter.dateFormat = "yyyyMMdd-HHmmss"
            let url = directory.appendingPathComponent("SHACKCQ-LOCAL-\(formatter.string(from: Date())).adi")
            try content.write(to: url, atomically: true, encoding: .utf8)
            message = "Exported \(records.count) local QSOs"
            return url
        } catch { message = "ADIF export failed: \(error.localizedDescription)"; return nil }
    }

    @discardableResult
    func importADIF(from url: URL) -> Int {
        guard url.startAccessingSecurityScopedResource() else { message = "ADIF file access denied"; return 0 }
        defer { url.stopAccessingSecurityScopedResource() }
        workedLogNotificationBatchDepth += 1
        var databaseChanged = false
        defer {
            if databaseChanged { reload() }
            workedLogNotificationBatchDepth -= 1
            if workedLogNotificationBatchDepth == 0, workedLogNotificationPending {
                workedLogNotificationPending = false
                onWorkedLogChanged?()
            }
        }
        do {
            let data = try Data(contentsOf: url)
            let records = parseAdifRecords(data)
            var imported = 0; var skipped = 0
            for fields in records {
                func field(_ name: String) -> String { fields[name.uppercased()] ?? "" }
                let call = field("CALL").uppercased(), mode = field("MODE").uppercased()
                let day = field("QSO_DATE"), clock = field("TIME_ON")
                guard !call.isEmpty, !mode.isEmpty, day.count == 8, clock.count >= 4,
                      let mhz = Double(field("FREQ")) else { skipped += 1; continue }
                let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
                formatter.timeZone = .gmt; formatter.dateFormat = clock.count >= 6 ? "yyyyMMddHHmmss" : "yyyyMMddHHmm"
                guard let date = formatter.date(from: day + String(clock.prefix(6))) else { skipped += 1; continue }
                let canonicalIdentity = field("APP_KX3TOUCH_UUID")
                let legacyIdentity = field("APP_SHACKCQ_UUID")
                let identity = canonicalIdentity.isEmpty ? (legacyIdentity.isEmpty ? UUID().uuidString.lowercased() : legacyIdentity) : canonicalIdentity
                let reserved = Set(["APP_KX3TOUCH_UUID", "APP_SHACKCQ_UUID", "CALL", "QSO_DATE", "TIME_ON", "FREQ", "MODE",
                                    "RST_SENT", "RST_RCVD", "NAME", "QTH", "COUNTRY", "COMMENT", "NOTES"])
                let extra = fields.filter { !reserved.contains($0.key) }
                let qso = QSO(id: identity, callsign: call, frequencyHz: UInt64((mhz * 1_000_000).rounded()),
                    mode: mode, rstSent: field("RST_SENT"), rstReceived: field("RST_RCVD"), createdAt: date,
                    name: field("NAME"), qth: field("QTH"), country: field("COUNTRY"),
                    notes: field("NOTES").isEmpty ? field("COMMENT") : field("NOTES"), fields: extra)
                if save(qso) { imported += 1; databaseChanged = true } else { skipped += 1 }
            }
            message = "ADIF import complete · \(imported) added · \(skipped) skipped"
            return imported
        } catch { message = "ADIF import failed: \(error.localizedDescription)"; return 0 }
    }

    private func allRecords() -> [QSO] {
        guard database != nil else { return [] }
        var statement: OpaquePointer?
        sqlite3_prepare_v2(database, "SELECT id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at,name,qth,country,notes,details_json FROM qso ORDER BY created_at DESC", -1, &statement, nil)
        var loaded: [QSO] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            loaded.append(QSO(id: text(statement, 0), callsign: text(statement, 1),
                frequencyHz: UInt64(sqlite3_column_int64(statement, 2)), mode: text(statement, 3),
                rstSent: text(statement, 4), rstReceived: text(statement, 5),
                createdAt: Date(timeIntervalSince1970: TimeInterval(sqlite3_column_int64(statement, 6))),
                name: text(statement, 7), qth: text(statement, 8), country: text(statement, 9), notes: text(statement, 10), fields: fields(text(statement, 11))))
        }
        sqlite3_finalize(statement); return loaded
    }

    func workedLogRecords() -> [QSO] { allRecords() }

    private func parseAdifRecords(_ data: Data) -> [[String: String]] {
        let bytes = [UInt8](data)
        var records: [[String: String]] = []
        var record: [String: String] = [:]
        var cursor = 0
        while cursor < bytes.count {
            guard bytes[cursor] == 0x3c else { cursor += 1; continue }
            guard let close = bytes[(cursor + 1)...].firstIndex(of: 0x3e) else { break }
            guard let descriptor = String(bytes: bytes[(cursor + 1)..<close], encoding: .ascii) else {
                cursor = close + 1; continue
            }
            let parts = descriptor.split(separator: ":", omittingEmptySubsequences: false)
            let name = parts[0].trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            cursor = close + 1
            if name == "EOH" { record.removeAll(keepingCapacity: true); continue }
            if name == "EOR" {
                if !record.isEmpty { records.append(record) }
                record.removeAll(keepingCapacity: true)
                continue
            }
            guard parts.count >= 2, let length = Int(parts[1]), length >= 0,
                  length <= bytes.count - cursor else { continue }
            let end = cursor + length
            record[name] = String(bytes: bytes[cursor..<end], encoding: .utf8) ?? ""
            cursor = end
        }
        return records
    }

    private func bind(_ value: String, to statement: OpaquePointer?, at index: Int32) {
        sqlite3_bind_text(statement, index, (value as NSString).utf8String, -1, qsoSQLiteTransient)
    }
    private func text(_ statement: OpaquePointer?, _ index: Int32) -> String {
        guard let value = sqlite3_column_text(statement, index) else { return "" }
        return String(cString: value)
    }
    private func json(_ fields: [String: String]) -> String {
        guard JSONSerialization.isValidJSONObject(fields), let data = try? JSONSerialization.data(withJSONObject: fields) else { return "{}" }
        return String(data: data, encoding: .utf8) ?? "{}"
    }
    private func fields(_ json: String) -> [String: String] {
        guard let data = json.data(using: .utf8), let result = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return [:] }
        return result
    }
}
