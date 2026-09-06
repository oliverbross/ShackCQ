import Foundation

struct FastEntryCanonical: Identifiable, Equatable {
    let id: String
    var callsign: String
    var frequencyHz: UInt64
    var band: String
    var mode: String
    var submode: String
    var rstSent: String
    var rstReceived: String
    var createdAt: Date
    var name: String = ""
    var grid: String = ""
    var qth: String = ""
    var country: String = ""
    var notes: String = ""
    var fields: [String: String] = [:]
}

struct AppleFastEntryRow: Identifiable, Equatable {
    var id: Int { line }
    let line: Int
    var qso: FastEntryCanonical
    var inherited: Set<String>
}

struct AppleFastEntryIssue: Identifiable, Equatable {
    var id: String { "\(line)-\(message)-\(warning)" }
    let line: Int
    let message: String
    var warning = false
}

struct AppleFastEntryResult: Equatable {
    var rows: [AppleFastEntryRow]
    var issues: [AppleFastEntryIssue]
    var errors: [AppleFastEntryIssue] { issues.filter { !$0.warning } }
    var warnings: [AppleFastEntryIssue] { issues.filter(\.warning) }
}

struct AppleFastEntryDefaults {
    var date: Date
    var operatorCallsign = ""
    var stationCallsign = ""
    var myGrid = ""
    var duplicateKeys: Set<String> = []
}

enum AppleFastEntryParser {
    private static let modes = Set("AM ARDOP ATV C4FM CW CWR DATA DIGITALVOICE DIGI DOMINO DSTAR FAX FM FREEDV FSK441 FT4 FT8 HELL ISCAT JS8 JT4 JT6M JT9 JT44 JT65 MFSK MSK144 MT63 OLIVIA OPERA PAC PAX PKT PSK PSK31 PSK63 Q15 QRA64 ROS RTTY RTTYM SSB SSTV T10 THOR THRB TOR USB LSB V4 VOI WINMOR WSPR".split(separator: " ").map(String.init))
    private static let bands = Set("2190m 630m 560m 160m 80m 60m 40m 30m 20m 17m 15m 12m 10m 8m 6m 5m 4m 2m 1.25m 70cm 33cm 23cm 13cm 9cm 6cm 3cm 1.25cm 6mm 4mm 2.5mm 2mm 1mm submm sat".split(separator: " ").map { $0.lowercased() })

    static func parse(_ source: String, defaults: AppleFastEntryDefaults) -> AppleFastEntryResult {
        let calendar = Calendar(identifier: .gregorian)
        var date = calendar.startOfDay(for: defaults.date)
        var timezoneMinutes = 0
        var band = "", mode = "", time = "", operatorCall = defaults.operatorCallsign
        var frequencyMHz: Double?
        var retained: [String: String] = defaults.myGrid.isEmpty ? [:] : ["MY_GRIDSQUARE": defaults.myGrid]
        var rows: [AppleFastEntryRow] = [], issues: [AppleFastEntryIssue] = []
        var seen = defaults.duplicateKeys

        for (offset, original) in source.components(separatedBy: .newlines).enumerated() {
            let number = offset + 1
            var line = original.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.hasPrefix("//") || line.hasPrefix("# ") { continue }
            if let groups = captures("(?i)^(?:TIMEZONE|TZOFS)\\s+([+-])(\\d{1,2})(?::?(\\d{2}))?$", line) {
                let minutes = (Int(groups[2]) ?? 0) * 60 + (Int(groups[3]) ?? 0)
                timezoneMinutes = groups[1] == "-" ? -minutes : minutes
                if abs(timezoneMinutes) > 840 { issues.append(.init(line: number, message: "Timezone offset must be between -14:00 and +14:00")) }
                continue
            }
            if let groups = captures("(?i)^(?:DATE\\s+)?(\\d{4}-\\d{2}-\\d{2}|\\d{8})$", line) {
                let raw = groups[1], formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
                formatter.timeZone = .gmt; formatter.dateFormat = raw.contains("-") ? "yyyy-MM-dd" : "yyyyMMdd"
                if let parsed = formatter.date(from: raw) { date = parsed } else { issues.append(.init(line: number, message: "Invalid date")) }
                continue
            }
            if let groups = captures("(?i)^DAY\\s*(\\++)$", line) {
                date = calendar.date(byAdding: .day, value: groups[1].count, to: date) ?? date; continue
            }

            var fields: [String: String] = [:]
            for match in bracketFields(line).reversed() {
                line.removeSubrange(match.range)
                switch match.kind {
                case "[": append(&fields, "QSLMSG", match.value)
                case "{": append(&fields, "COMMENT", match.value)
                default:
                    if let pair = captures("^([A-Za-z0-9_]+)\\s*[:=]\\s*(.*)$", match.value) {
                        let key = pair[1].uppercased(), value = pair[2].trimmingCharacters(in: .whitespaces)
                        if value.isEmpty { retained.removeValue(forKey: key) }
                        else { fields[key] = value; if key.hasPrefix("MY_") || ["TX_PWR", "OPERATOR", "BAND", "MODE", "SUBMODE", "FREQ"].contains(key) { retained[key] = value } }
                    } else { append(&fields, "COMMENT", match.value) }
                }
            }
            let tokens = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            if tokens.isEmpty { retained.merge(fields) { _, new in new }; continue }
            let callIndex = tokens.firstIndex { isCall($0) && normalizedBand($0) == nil && normalizedMode($0) == nil }
            guard let callIndex else {
                for token in tokens {
                    if let value = normalizedBand(token) { band = value; frequencyMHz = nil }
                    else if let value = normalizedMode(token) { mode = value }
                    else if let value = explicitFrequency(token) { frequencyMHz = value; band = bandFor(value) }
                    else if token.uppercased().hasPrefix("OPERATOR=") { operatorCall = String(token.dropFirst(9)).uppercased() }
                    else if isReference(token) { append(&retained, "FAST_ENTRY_REFS", token.uppercased()) }
                    else { issues.append(.init(line: number, message: "Header has no callsign; unrecognized token: \(token)")) }
                }
                retained.merge(fields) { _, new in new }; continue
            }

            var call = "", grid = "", sent = "", received = "", name = ""
            var references: [String] = [], inherited: Set<String> = ["date"]
            var explicitBand = false, explicitMode = false, explicitFreq = false, explicitTime = false
            for (index, token) in tokens.enumerated() {
                if let value = normalizedBand(token) { band = value; frequencyMHz = nil; explicitBand = true }
                else if let value = normalizedMode(token) { mode = value; explicitMode = true }
                else if let value = explicitFrequency(token) { frequencyMHz = value; band = bandFor(value); explicitFreq = true }
                else if matches("^[0-2]\\d[0-5]\\d(?:[0-5]\\d)?$", token) { time = String(token.prefix(4)); explicitTime = true }
                else if index < callIndex && !time.isEmpty && matches("^\\d$", token) { time = String(time.dropLast()) + token; explicitTime = true }
                else if index < callIndex && !time.isEmpty && matches("^[0-5]\\d$", token) { time = String(time.dropLast(2)) + token; explicitTime = true }
                else if call.isEmpty && isCall(token) { call = token.uppercased() }
                else if matches("(?i)^#?[A-R]{2}\\d{2}(?:[A-X]{2}(?:\\d{2}(?:[A-X]{2})?)?)?$", token) { grid = token.replacingOccurrences(of: "#", with: "").uppercased() }
                else if !call.isEmpty && matches("^[-+]?\\d{1,3}$", token) && sent.isEmpty { sent = token }
                else if !call.isEmpty && matches("^[-+]?\\d{1,3}$", token) && received.isEmpty { received = token }
                else if !call.isEmpty && token.hasPrefix("@") { name = String(token.dropFirst()).replacingOccurrences(of: "_", with: " ") }
                else if !call.isEmpty && token.hasPrefix(",") { exchange(String(token.dropFirst()), sent: true, into: &fields) }
                else if !call.isEmpty && token.hasPrefix(".") { exchange(String(token.dropFirst()), sent: false, into: &fields) }
                else if isReference(token) { references.append(token.uppercased()) }
                else { issues.append(.init(line: number, message: "Unrecognized token: \(token)", warning: true)) }
            }
            if band.isEmpty { issues.append(.init(line: number, message: "Band or frequency is required")) } else if !explicitBand && !explicitFreq { inherited.insert("band") }
            if mode.isEmpty { issues.append(.init(line: number, message: "Mode is required")) } else if !explicitMode { inherited.insert("mode") }
            if time.isEmpty { issues.append(.init(line: number, message: "UTC/local time is required")) } else if !explicitTime { inherited.insert("time") }
            if issues.contains(where: { $0.line == number && !$0.warning }) { continue }
            let hz = frequencyMHz.map { UInt64(($0 * 1_000_000).rounded()) } ?? defaultFrequency(band, mode)
            if hz == 0 { issues.append(.init(line: number, message: "Frequency is unknown for \(band) \(mode); enter an explicit MHz value")); continue }
            let components = calendar.dateComponents([.year, .month, .day], from: date)
            var local = DateComponents(); local.calendar = calendar; local.timeZone = .gmt; local.year = components.year; local.month = components.month; local.day = components.day
            local.hour = Int(time.prefix(2)); local.minute = Int(time.suffix(2))
            guard let localDate = local.date else { issues.append(.init(line: number, message: "Invalid time")); continue }
            let utc = localDate.addingTimeInterval(TimeInterval(-timezoneMinutes * 60))
            let combined = retained.merging(fields) { _, new in new }
            let refs = (referencesFrom(retained) + references).uniqued()
            var canonical = FastEntryCanonical(id: UUID().uuidString.lowercased(), callsign: call, frequencyHz: hz, band: band,
                mode: mainMode(mode), submode: mainMode(mode) == mode.uppercased() ? "" : mode.uppercased(),
                rstSent: sent.isEmpty ? defaultReport(mode) : sent, rstReceived: received.isEmpty ? defaultReport(mode) : received,
                createdAt: utc, name: name, grid: grid, fields: combined)
            canonical.fields["OPERATOR"] = combined["OPERATOR"] ?? operatorCall
            canonical.fields["STATION_CALLSIGN"] = defaults.stationCallsign
            canonical.fields["FAST_ENTRY_REFS"] = refs.joined(separator: ",")
            canonical.fields["POTA_REF"] = refs.first { matches("(?i)^[A-Z0-9]{1,4}-\\d{4,5}$", $0) && !$0.contains("FF-") } ?? ""
            canonical.fields["WWFF_REF"] = refs.first { $0.contains("FF-") } ?? ""
            canonical.fields["SOTA_REF"] = refs.first { $0.contains("/") } ?? ""
            canonical.fields["IOTA"] = refs.first { matches("(?i)^[A-Z]*[FNSUACA]-\\d{3}$", $0) } ?? ""
            let row = AppleFastEntryRow(line: number, qso: canonical, inherited: inherited)
            let key = duplicateKey(canonical)
            if !seen.insert(key).inserted { issues.append(.init(line: number, message: "Duplicate candidate: same callsign, frequency, mode and 15-second window", warning: true)) }
            rows.append(row)
        }
        return .init(rows: rows, issues: issues)
    }

    static func duplicateKey(_ qso: FastEntryCanonical) -> String { "\(qso.callsign.uppercased())|\(qso.frequencyHz)|\(qso.mode.uppercased())|\(Int(qso.createdAt.timeIntervalSince1970) / 15)" }
    private static func normalizedBand(_ value: String) -> String? { bands.first { $0.caseInsensitiveCompare(value) == .orderedSame } }
    private static func normalizedMode(_ value: String) -> String? { let upper = value.uppercased(); return modes.contains(upper) ? upper : nil }
    private static func explicitFrequency(_ value: String) -> Double? { (value.contains(".") || value.contains(",")) ? Double(value.replacingOccurrences(of: ",", with: ".")) : nil }
    private static func isCall(_ value: String) -> Bool { matches("(?i)^(?:[A-Z0-9]{1,4}/)?[A-Z0-9]{1,3}\\d[A-Z0-9]{1,5}(?:/[A-Z0-9]{1,4})?$", value) }
    private static func isReference(_ value: String) -> Bool { matches("(?i)^(?:[A-Z0-9]{1,4}/[A-Z]{2}-\\d{3}|[A-Z]*[FNSUACA]-\\d{3}|[A-Z0-9]{1,4}-\\d{4,5}|[A-Z0-9]{1,4}FF-\\d{4,5})$", value) }
    private static func mainMode(_ value: String) -> String { switch value.uppercased() { case "USB", "LSB": return "SSB"; case "FT8", "FT4", "JS8", "JT4", "JT9", "JT44", "JT65", "WSPR": return "MFSK"; case "PSK31", "PSK63": return "PSK"; case "CWR": return "CW"; default: return value.uppercased() } }
    private static func defaultReport(_ value: String) -> String { ["MFSK", "PSK", "RTTY", "DATA", "DIGI"].contains(mainMode(value)) ? "+0" : mainMode(value) == "CW" ? "599" : "59" }
    private static func defaultFrequency(_ band: String, _ mode: String) -> UInt64 {
        let values: [String: UInt64] = ["2190m":136_000,"630m":475_000,"160m":1_900_000,"80m":3_700_000,"60m":5_357_000,"40m":7_100_000,"30m":10_120_000,"20m":14_200_000,"17m":18_100_000,"15m":21_250_000,"12m":24_930_000,"10m":28_500_000,"6m":50_150_000,"4m":70_200_000,"2m":145_000_000,"1.25m":223_500_000,"70cm":433_500_000,"33cm":915_000_000,"23cm":1_296_000_000]
        guard let value = values[band.lowercased()] else { return 0 }; return ["CW","MFSK","PSK","RTTY"].contains(mainMode(mode)) ? value - min(100_000, value / 100) : value
    }
    private static func bandFor(_ mhz: Double) -> String { let hz = UInt64(mhz * 1_000_000); return [(135_000...138_000,"2190m"),(472_000...479_000,"630m"),(1_800_000...2_000_000,"160m"),(3_500_000...4_000_000,"80m"),(5_000_000...5_500_000,"60m"),(7_000_000...7_300_000,"40m"),(10_100_000...10_150_000,"30m"),(14_000_000...14_350_000,"20m"),(18_068_000...18_168_000,"17m"),(21_000_000...21_450_000,"15m"),(24_890_000...24_990_000,"12m"),(28_000_000...29_700_000,"10m"),(50_000_000...54_000_000,"6m"),(70_000_000...71_000_000,"4m"),(144_000_000...148_000_000,"2m"),(420_000_000...450_000_000,"70cm"),(902_000_000...928_000_000,"33cm"),(1_240_000_000...1_300_000_000,"23cm")].first { $0.0.contains(Int(hz)) }?.1 ?? "" }
    private static func exchange(_ value: String, sent: Bool, into fields: inout [String: String]) { let parts = value.split(whereSeparator: { $0 == "." || $0 == "," }).map(String.init); if let first = parts.first, Int(first) != nil { fields[sent ? "STX" : "SRX"] = first }; if parts.count > 1 { fields[sent ? "STX_STRING" : "SRX_STRING"] = parts.dropFirst().joined(separator: " ") } }
    private static func referencesFrom(_ fields: [String: String]) -> [String] { fields["FAST_ENTRY_REFS"]?.split(whereSeparator: { $0 == "," || $0 == ";" || $0.isWhitespace }).map(String.init) ?? [] }
    private static func append(_ fields: inout [String: String], _ key: String, _ value: String) { fields[key] = [fields[key], value].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ") }
    private static func matches(_ pattern: String, _ value: String) -> Bool { value.range(of: pattern, options: .regularExpression) != nil }
    private static func captures(_ pattern: String, _ value: String) -> [String]? { let expression = try! NSRegularExpression(pattern: pattern); let range = NSRange(value.startIndex..., in: value); guard let match = expression.firstMatch(in: value, range: range), match.range == range else { return nil }; return (0..<match.numberOfRanges).map { Range(match.range(at: $0), in: value).map { String(value[$0]) } ?? "" } }
    private struct BracketField { let range: Range<String.Index>; let kind: Character; let value: String }
    private static func bracketFields(_ value: String) -> [BracketField] { var result: [BracketField] = []; let expression = try! NSRegularExpression(pattern: "<([^>]*)>|\\[([^\\]]*)\\]|\\{([^\\}]*)\\}"); for match in expression.matches(in: value, range: NSRange(value.startIndex..., in: value)) { guard let whole = Range(match.range, in: value) else { continue }; let raw = String(value[whole]); result.append(.init(range: whole, kind: raw.first!, value: String(raw.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces))) }; return result }
}

private extension Array where Element == String { func uniqued() -> [String] { var seen = Set<String>(); return filter { seen.insert($0).inserted } } }
