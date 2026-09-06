import XCTest

final class ShackCQHardwareUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments.append("--disable-radio-autoconnect")
        addUIInterruptionMonitor(withDescription: "ShackCQ hardware permissions") { alert in
            for label in ["Allow", "OK", "Continue"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
        app.launch()
    }

    func testPhysicalCAT() throws {
        openDestination("Radio")
        let scanSerial = app.buttons["Scan serial devices"]
        XCTAssertTrue(scanSerial.waitForExistence(timeout: 8))
        scanSerial.tap()

        let serialStatus = app.descendants(matching: .any)["serialStatus"]
        XCTAssertTrue(serialStatus.waitForExistence(timeout: 5))
        XCTAssertTrue(waitForText(serialStatus, containing: "Found", timeout: 15),
                      "DriverKit did not publish a physical serial endpoint: \(text(of: serialStatus))")

        let connect = app.buttons["Connect KX3"]
        XCTAssertTrue(connect.isEnabled)
        connect.tap()
        XCTAssertTrue(waitForText(serialStatus, containing: "Connected", timeout: 12),
                      "Could not open the PL2303/KXUSB serial endpoint: \(text(of: serialStatus))")

        let model = app.descendants(matching: .any)["radioModel"]
        XCTAssertTrue(model.waitForExistence(timeout: 5))
        XCTAssertTrue(waitForText(model, containing: "KX", timeout: 15),
                      "No live Elecraft ID response arrived: \(text(of: model))")

        let frequency = app.descendants(matching: .any)["frequencyDisplay"]
        XCTAssertTrue(frequency.waitForExistence(timeout: 5))
        XCTAssertTrue(waitForPositiveFrequency(frequency, timeout: 15),
                      "No live CAT frequency was decoded: \(text(of: frequency))")
    }

    func testCATPersistsAcrossSettingsAndRadioTabs() throws {
        openDestination("Settings")
        openSettingsTab("Diag")
        let scan = app.buttons["Scan"]
        XCTAssertTrue(scan.waitForExistence(timeout: 8))
        scan.tap()
        let settingsStatus = app.descendants(matching: .any)["settingsSerialStatus"]
        XCTAssertTrue(waitForText(settingsStatus, containingAny: ["Found", "Connected"], timeout: 15),
                      "No physical port or active connection: \(text(of: settingsStatus))")
        if !text(of: settingsStatus).localizedCaseInsensitiveContains("Connected") {
            app.buttons["Connect"].tap()
        }
        XCTAssertTrue(waitForText(settingsStatus, containing: "Connected", timeout: 15))
        XCTAssertTrue(waitForText(app.descendants(matching: .any)["settingsRadioModel"], containing: "KX", timeout: 15))

        openDestination("Home")
        XCTAssertTrue(waitForPositiveFrequency(app.descendants(matching: .any)["homeFrequencyDisplay"], timeout: 15))
        openDestination("Radio")
        let radioStatus = app.descendants(matching: .any)["serialStatus"]
        XCTAssertTrue(waitForText(radioStatus, containing: "Connected", timeout: 10))
        XCTAssertTrue(waitForPositiveFrequency(app.descendants(matching: .any)["frequencyDisplay"], timeout: 15))
        openDestination("Settings")
        openSettingsTab("Diag")
        XCTAssertTrue(waitForText(app.descendants(matching: .any)["settingsSerialStatus"], containing: "Connected", timeout: 10))
    }

    func testDXClusterReceivesRealSpots() throws {
        openDestination("Settings")
        let save = app.buttons["Save settings"]
        XCTAssertTrue(save.waitForExistence(timeout: 8))
        save.tap()
        XCTAssertTrue(waitForText(app.descendants(matching: .any)["settingsSaveStatus"], containing: "saved", timeout: 5))
        openSettingsTab("Cluster")
        let connect = app.buttons["Connect"]
        XCTAssertTrue(connect.waitForExistence(timeout: 5))
        scrollToHittable(connect)
        connect.tap()
        let status = app.descendants(matching: .any)["clusterStatus"]
        XCTAssertTrue(waitForText(status, containing: "live", timeout: 30), "No real DX spot stream: \(text(of: status))")
        openDestination("Spots")
        XCTAssertFalse(app.staticTexts["No live spots"].exists, "Cluster connected but no parsed spots reached the Spots screen")
    }

    func testSettingsTabsAndConfiguredServices() throws {
        openDestination("Settings")
        for name in ["Default", "Log", "Cluster", "Macros", "Alerts", "Safety", "Audio", "Health", "Diag", "About"] {
            XCTAssertTrue(app.buttons["settingsTab\(name)"].waitForExistence(timeout: 5), "Missing Settings tab \(name)")
        }
        openSettingsTab("Diag")
        let wavelog = app.descendants(matching: .any)["wavelogStatus"]
        let callbook = app.descendants(matching: .any)["callbookStatus"]
        app.buttons["Test Wavelog"].tap()
        XCTAssertTrue(waitForText(wavelog, containing: "passed", timeout: 30), "Wavelog test did not pass: \(text(of: wavelog))")
        app.buttons["Test QRZ / HamQTH"].tap()
        XCTAssertTrue(waitForText(callbook, containing: "passed", timeout: 30), "Callbook test did not pass: \(text(of: callbook))")
        app.buttons["Check time sync"].tap()
        XCTAssertTrue(waitForText(wavelog, containing: "synchronized", timeout: 30), "Time check did not pass: \(text(of: wavelog))")
        app.buttons["Load stations"].tap()
        XCTAssertTrue(waitForText(wavelog, containing: "stations loaded", timeout: 30), "Station load did not pass: \(text(of: wavelog))")
    }

    func testSettingsTabNavigation() throws {
        openDestination("Settings")
        let sections = [
            ("Default", "Radio profile"), ("Log", "Local tablet log"),
            ("Cluster", "DX cluster endpoints"), ("Macros", "CW macros"),
            ("Alerts", "Alerts"), ("Safety", "Transmit safety"),
            ("Audio", "USB receive audio"), ("Health", "System health"),
            ("Diag", "Required service tests"), ("About", "ShackCQ"),
        ]
        for (tab, section) in sections {
            openSettingsTab(tab)
            XCTAssertTrue(app.staticTexts[section].waitForExistence(timeout: 5),
                          "Settings tab \(tab) did not show \(section)")
        }
    }

    func testSettingsLayoutImprovements() throws {
        openDestination("Settings")

        openSettingsTab("Default")
        XCTAssertTrue(app.staticTexts["CTY.DAT"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["ctyStatus"].exists)

        openSettingsTab("Log")
        XCTAssertFalse(app.staticTexts["CTY.DAT"].exists)

        openSettingsTab("Macros")
        XCTAssertTrue(app.descendants(matching: .any)["cwMacroGrid"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["cwMacroMessage2"].exists)

        openSettingsTab("Audio")
        XCTAssertTrue(app.descendants(matching: .any)["audioSettingsLayout"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["audioInputPicker"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["audioCaptureControls"].exists)

        openSettingsTab("About")
        XCTAssertTrue(app.descendants(matching: .any)["developerInformation"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Oliver Bross, OM0RX"].exists)
        XCTAssertTrue(app.images["developerPortrait"].exists)
    }

    func testPhysicalIQ() throws {
        openDestination("Panadapter")
        app.tap()
        let scanAudio = app.buttons["Scan audio devices"]
        XCTAssertTrue(scanAudio.waitForExistence(timeout: 8))
        scanAudio.tap()
        app.tap()

        let audioStatus = app.descendants(matching: .any)["audioStatus"]
        XCTAssertTrue(audioStatus.waitForExistence(timeout: 5))
        XCTAssertTrue(waitForText(audioStatus, containing: "USB", timeout: 15),
                      "iPadOS did not expose ICUSBAUDIO2D: \(text(of: audioStatus))")

        let startCapture = app.buttons["Start I/Q capture"]
        XCTAssertTrue(startCapture.waitForExistence(timeout: 5))
        startCapture.tap()
        app.tap()
        XCTAssertTrue(waitForText(audioStatus, containing: "Capturing", timeout: 15),
                      "USB I/Q capture did not start: \(text(of: audioStatus))")

        let frames = app.descendants(matching: .any)["metric.frames"]
        XCTAssertTrue(frames.waitForExistence(timeout: 15))
        XCTAssertTrue(waitForPositiveFrameCount(frames, timeout: 15),
                      "ICUSBAUDIO2D route opened but delivered no audio frames: \(text(of: frames))")
    }

    private func openDestination(_ name: String) {
        let button = app.buttons[name]
        XCTAssertTrue(button.waitForExistence(timeout: 8), "Missing \(name) destination")
        button.tap()
    }

    private func openSettingsTab(_ name: String) {
        let identifier = "settingsTab\(name)"
        XCTAssertTrue(app.buttons[identifier].waitForExistence(timeout: 5), "Missing Settings tab \(name)")
        let tabs = app.scrollViews["settingsTabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 5), "Missing Settings tab strip")
        for _ in 0..<8 {
            let button = app.buttons[identifier]
            let buttonFrame = button.frame
            let tabsFrame = tabs.frame
            if button.exists, buttonFrame.width > 0,
               buttonFrame.minX >= tabsFrame.minX, buttonFrame.maxX <= tabsFrame.maxX {
                button.tap()
                return
            }
            let start = tabs.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.8))
            let end = tabs.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.8))
            start.press(forDuration: 0.01, thenDragTo: end, withVelocity: .fast, thenHoldForDuration: 0)
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        XCTFail("Could not reach Settings tab \(name)")
    }

    private func scrollToHittable(_ element: XCUIElement) {
        for _ in 0..<8 where !element.isHittable { app.swipeUp() }
        XCTAssertTrue(element.isHittable, "Could not scroll to \(element)")
    }

    private func waitForText(_ element: XCUIElement, containing needle: String, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if text(of: element).localizedCaseInsensitiveContains(needle) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return false
    }

    private func waitForText(_ element: XCUIElement, containingAny needles: [String], timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            let value = text(of: element)
            if needles.contains(where: { value.localizedCaseInsensitiveContains($0) }) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return false
    }

    private func waitForPositiveFrameCount(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            let digits = text(of: element).filter(\.isNumber)
            if let count = UInt64(digits), count > 0 { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return false
    }

    private func waitForPositiveFrequency(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            let values = text(of: element)
                .split(whereSeparator: { $0.isWhitespace })
                .compactMap { Double($0) }
            if values.contains(where: { $0 > 0 }) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return false
    }

    private func text(of element: XCUIElement) -> String {
        [element.label, element.value as? String].compactMap { $0 }.joined(separator: " ")
    }
}
