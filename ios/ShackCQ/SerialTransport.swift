import Darwin
import Foundation

final class SerialTransport {
    enum TransportError: LocalizedError {
        case noPort
        case openFailed(Int32)
        case configureFailed(Int32)
        case writeFailed(Int32)

        var errorDescription: String? {
            switch self {
            case .noPort: "No active PL2303/KXUSB DriverKit service is available."
            case .openFailed(let code): "Could not open the KXUSB driver service (IOReturn 0x\(String(UInt32(bitPattern: code), radix: 16)))."
            case .configureFailed(let code): "The KXUSB driver did not become ready (IOReturn 0x\(String(UInt32(bitPattern: code), radix: 16)))."
            case .writeFailed(let code): "CAT write failed (IOReturn 0x\(String(UInt32(bitPattern: code), radix: 16)))."
            }
        }
    }

    static let readOnlyQueries = [
        "K3;", "OM;", "ID;", "FA;", "FB;", "MD;", "IF;", "TQ;", "SM;", "SW;", "PO;",
        "AG;", "RG;", "BW;", "PC;", "PA;", "RA;", "RT;", "XT;", "FR;", "FT;"
    ]

    private static let virtualPath = "driverkit://pl2303-kxusb"

    private let queue = DispatchQueue(label: "app.shackcq.serial", qos: .userInitiated)
    private var connection: UInt32 = 0
    private var pollTimer: DispatchSourceTimer?
    private var queryIndex = 0
    private var pollTick = 0

    var onData: ((Data) -> Void)?
    var onStatus: ((String) -> Void)?

    var isConnected: Bool { queue.sync { connection != 0 } }

    static func serialPorts() -> [String] {
        shackcq_kxusb_available() == 1 ? [virtualPath] : []
    }

    func connect(path: String) throws {
        try queue.sync {
            disconnectLocked()
            guard path == Self.virtualPath else {
                throw TransportError.noPort
            }
            var newConnection: UInt32 = 0
            let result = shackcq_kxusb_open(&newConnection)
            guard result == 0, newConnection != 0 else {
                throw TransportError.openFailed(result)
            }
            connection = newConnection
            startPollingLocked()
        }
        onStatus?("Connected: Elecraft KXUSB · PL2303GC · 38400 8N1")
    }

    func disconnect() {
        queue.sync { disconnectLocked() }
    }

    private func disconnectLocked() {
        pollTimer?.cancel()
        pollTimer = nil
        let oldConnection = connection
        connection = 0
        if oldConnection != 0 { shackcq_kxusb_close(oldConnection) }
        queryIndex = 0
        pollTick = 0
    }

    func send(_ command: String) throws {
        guard command.hasSuffix(";"), command.utf8.count <= 128 else {
            throw TransportError.writeFailed(EINVAL)
        }
        try queue.sync { try sendLocked(command) }
    }

    private func sendLocked(_ command: String) throws {
        let bytes = Array(command.utf8)
        guard connection != 0 else { throw TransportError.noPort }

        let result = bytes.withUnsafeBytes { pointer in
            shackcq_kxusb_write(connection,
                                 pointer.bindMemory(to: UInt8.self).baseAddress,
                                 pointer.count)
        }
        guard result == 0 else {
            throw TransportError.writeFailed(result)
        }
    }

    private func startPollingLocked() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(25), leeway: .milliseconds(5))
        timer.setEventHandler { [weak self] in self?.pollOnce() }
        pollTimer = timer
        timer.resume()
    }

    private func pollOnce() {
        let currentConnection = connection
        guard currentConnection != 0 else { return }

        var bytes = [UInt8](repeating: 0, count: 512)
        var count = bytes.count
        let result = bytes.withUnsafeMutableBytes { pointer in
            shackcq_kxusb_read(currentConnection,
                                pointer.bindMemory(to: UInt8.self).baseAddress,
                                pointer.count, &count)
        }
        if result == 0, count > 0 {
            onData?(Data(bytes.prefix(count)))
        } else if result != 0 {
            let code = String(UInt32(bitPattern: result), radix: 16)
            disconnectLocked()
            onStatus?("KXUSB connection lost (IOReturn 0x\(code)); waiting for the physical driver to return")
            return
        }

        pollTick += 1
        if pollTick >= 7 {
            pollTick = 0
            let command = Self.readOnlyQueries[queryIndex % Self.readOnlyQueries.count]
            queryIndex += 1
            do { try sendLocked(command) }
            catch { onStatus?(error.localizedDescription) }
        }
    }
}
