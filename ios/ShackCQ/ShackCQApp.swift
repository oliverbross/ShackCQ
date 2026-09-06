import SwiftUI
import AVFAudio
import Foundation

@main
struct ShackCQApp: App {
    @StateObject private var radio = RadioModel()
    @StateObject private var logbook = QSOStore()
    @StateObject private var features = FeatureModel()
    @StateObject private var groupsIo = GroupsIoController()
    @StateObject private var remote = RemoteStationModel()
    @State private var hardwareSelfTestStarted = false

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(radio)
                .environmentObject(logbook)
                .environmentObject(features)
                .environmentObject(groupsIo)
                .environmentObject(remote)
                .preferredColorScheme(.dark)
                .task { features.bind(logbook: logbook) }
                .task {
                    guard !hardwareSelfTestStarted else { return }
                    let arguments = ProcessInfo.processInfo.arguments
                    if arguments.contains("--open-driver-settings") {
                        hardwareSelfTestStarted = true
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            await UIApplication.shared.open(url)
                        }
                        return
                    }
                    if arguments.contains("--cat-lifecycle-self-test") {
                        hardwareSelfTestStarted = true
                        await HardwareSelfTest.runCATLifecycle(radio: radio)
                        return
                    }
                    if arguments.contains("--cluster-self-test") {
                        hardwareSelfTestStarted = true
                        await HardwareSelfTest.runCluster(features: features)
                        return
                    }
                    guard arguments.contains("--hardware-self-test") else { return }
                    hardwareSelfTestStarted = true
                    await HardwareSelfTest.run(radio: radio, features: features)
                }
                .task {
                    while !Task.isCancelled {
                        radio.maintainConnection()
                        try? await Task.sleep(for: .seconds(1))
                    }
                }
        }
    }
}

@MainActor
private enum HardwareSelfTest {
    static func run(radio: RadioModel, features: FeatureModel) async {
        report("BEGIN build=\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown")")

        for _ in 0..<20 {
            radio.refreshPorts()
            if !radio.serialPorts.isEmpty { break }
            try? await Task.sleep(for: .milliseconds(500))
        }
        report("SERIAL_DISCOVERY ports=\(radio.serialPorts.map { ($0 as NSString).lastPathComponent }.joined(separator: ",")) status=\(radio.transportStatus)")

        if !radio.serialPorts.isEmpty {
            radio.connect()
            for _ in 0..<30 {
                if radio.snapshot.connected,
                   radio.snapshot.model.localizedCaseInsensitiveContains("KX"),
                   radio.snapshot.frequencyHz > 0 { break }
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
        report("CAT connected=\(radio.snapshot.connected) model=\(radio.snapshot.model) frequency=\(radio.snapshot.frequencyHz) status=\(radio.transportStatus)")

        report("AUDIO_PERMISSION value=\(AVAudioApplication.shared.recordPermission.rawValue)")
        await features.refreshAudioInputs()
        report("AUDIO_DISCOVERY inputs=\(features.audioInputs.map { "\($0.name)[\($0.type)]" }.joined(separator: ",")) status=\(features.audioStatus)")

        let iqInput = features.audioInputs.first(where: {
            $0.type.localizedCaseInsensitiveContains("USBAudio") ||
                $0.name.localizedCaseInsensitiveContains("ICUSBAUDIO2D")
        })
        if let iqInput {
            features.selectedAudioInputUID = iqInput.id
            await features.startAudioCapture()
            for _ in 0..<30 {
                if features.audioCapturedFrames > 0 { break }
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
        report("IQ frames=\(features.audioCapturedFrames) bins=\(features.spectrumDb.count) rate=\(Int(features.audioSampleRate)) peak=\(features.audioPeak) i=\(features.audioIRms) q=\(features.audioQRms) correlation=\(features.audioIQCorrelation) floor=\(features.audioNoiseFloor) status=\(features.audioStatus)")

        if ProcessInfo.processInfo.arguments.contains("--hardware-soak-test") {
            for checkpoint in 1...6 {
                try? await Task.sleep(for: .seconds(5))
                radio.refreshPorts()
                radio.maintainConnection()
                report("SOAK checkpoint=\(checkpoint) ports=\(radio.serialPorts.count) connected=\(radio.snapshot.connected) frequency=\(radio.snapshot.frequencyHz) frames=\(features.audioCapturedFrames) status=\(radio.transportStatus)")
            }
        }

        let catPassed = radio.snapshot.connected &&
            radio.snapshot.model.localizedCaseInsensitiveContains("KX") &&
            radio.snapshot.frequencyHz > 0
        let iqLevelsValid = features.audioIRms.isFinite && features.audioQRms.isFinite &&
            features.audioIRms > -118 && features.audioQRms > -118
        let iqPairValid = features.audioIQCorrelation.isFinite && abs(features.audioIQCorrelation) < 0.999
        let iqPassed = iqInput != nil && features.audioCapturedFrames > 0 &&
            !features.spectrumDb.isEmpty && iqLevelsValid && iqPairValid
        report("RESULT cat=\(catPassed ? "PASS" : "FAIL") iq=\(iqPassed ? "PASS" : "FAIL") overall=\(catPassed && iqPassed ? "PASS" : "FAIL")")
        features.stopAudioCapture()
        radio.disconnect()
    }

    static func runCATLifecycle(radio: RadioModel) async {
        report("CAT_LIFECYCLE BEGIN build=\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown")")
        radio.refreshPorts()
        radio.connect()
        for checkpoint in 1...20 {
            if checkpoint == 6 || checkpoint == 12 { radio.refreshPorts() }
            radio.maintainConnection()
            report("CAT_LIFECYCLE checkpoint=\(checkpoint) connected=\(radio.snapshot.connected) model=\(radio.snapshot.model) frequency=\(radio.snapshot.frequencyHz) status=\(radio.transportStatus)")
            if radio.snapshot.connected, radio.snapshot.frequencyHz > 0, checkpoint >= 12 {
                report("CAT_LIFECYCLE RESULT PASS")
                return
            }
            try? await Task.sleep(for: .seconds(1))
        }
        report("CAT_LIFECYCLE RESULT FAIL")
    }

    static func runCluster(features: FeatureModel) async {
        report("CLUSTER BEGIN host=\(features.clusterHost) port=\(features.clusterPort) callsign=\(features.operatorCallsign)")
        features.connectCluster()
        for checkpoint in 1...30 {
            report("CLUSTER checkpoint=\(checkpoint) accepted=\(features.acceptedClusterLines) live=\(features.dx.liveSpots.count) opportunities=\(features.dx.opportunities.count) spots60m=\(features.dx.spots60m) status=\(features.clusterStatus) last=\(features.lastAcceptedClusterLine)")
            if !features.dx.liveSpots.isEmpty {
                report("CLUSTER RESULT PASS accepted=\(features.acceptedClusterLines) live=\(features.dx.liveSpots.count)")
                features.disconnectCluster()
                return
            }
            try? await Task.sleep(for: .seconds(1))
        }
        report("CLUSTER RESULT FAIL status=\(features.clusterStatus)")
        features.disconnectCluster()
    }

    private static func report(_ message: String) {
        let line = "SHACKCQ_HARDWARE_SELF_TEST \(message)\n"
        FileHandle.standardOutput.write(Data(line.utf8))
    }
}
