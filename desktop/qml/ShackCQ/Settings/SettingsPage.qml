import QtQuick
import QtQuick.Controls
import QtQuick.Dialogs
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id: root
    workspaceKey: "Settings"
    property string currentCategory: "station"
    property var importPreview: ({})
    readonly property var categories: [
        {id:"station", label:"Station", icon:"home"},
        {id:"remote", label:"Remote Station", icon:"radio"},
        {id:"radio", label:"Radio", icon:"radio"},
        {id:"audio", label:"Audio / Panadapter", icon:"panadapter"},
        {id:"digi", label:"Digi", icon:"digi"},
        {id:"keyer", label:"Keyer", icon:"keyboard"},
        {id:"cluster", label:"Cluster", icon:"dx"},
        {id:"alerts", label:"Alerts", icon:"health"},
        {id:"contest", label:"Contest", icon:"contest"},
        {id:"bandmaps", label:"Band Maps", icon:"bandmaps"},
        {id:"wavelog", label:"Wavelog", icon:"sync"},
        {id:"groups", label:"Groups.io", icon:"groups"},
        {id:"providers", label:"Providers / Portable", icon:"portable"},
        {id:"operations", label:"Operations / Satellite", icon:"operations"},
        {id:"rotator", label:"Rotator", icon:"rotator"},
        {id:"appearance", label:"Appearance / Accessibility", icon:"settings"},
        {id:"health", label:"Health / About", icon:"about"}
    ]
    function handleCommand(commandId) {
        if (commandId === "file.exportConfig") exportBundle.open()
    }
    function categoryLabel() {
        const category = categories.find(function(item) { return item.id === currentCategory })
        return category ? category.label : "Settings"
    }
    function categoryDestination() {
        const map = {digi:"Digi", keyer:"Radio", cluster:"DX", alerts:"Health", contest:"Contest", bandmaps:"Band Maps", wavelog:"Sync", groups:"Groups.io", operations:"Operations", rotator:"Rotator"}
        return map[currentCategory] || "Settings"
    }

    FileDialog { id: importBundle; title: "Preview configuration bundle"; nameFilters: ["ShackCQ JSON (*.json)"]; onAccepted: root.importPreview = DesktopConfig.previewImport(Desktop.localFilePath(selectedFile)) }
    FileDialog { id: exportBundle; title: "Export safe configuration"; fileMode: FileDialog.SaveFile; nameFilters: ["ShackCQ JSON (*.json)"]; onAccepted: DesktopConfig.exportBundle(Desktop.localFilePath(selectedFile)) }

    CanvasPanel {
        panelKey: "categories"
        title: "Settings categories"
        defaultWidth: 280
        defaultHeight: parent ? parent.height : 620
        panelMinimumWidth: 230
            ColumnLayout {
                anchors.fill: parent
                spacing: 8
                TextField {
                    id: categorySearch
                    Layout.fillWidth: true
                    placeholderText: "Find a setting category"
                    Accessible.name: "Search settings categories"
                }
                ListView {
                    id: categoryList
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    model: root.categories.filter(function(category) { return category.label.toLowerCase().includes(categorySearch.text.toLowerCase()) })
                    delegate: ItemDelegate {
                        required property var modelData
                        width: categoryList.width
                        height: 40
                        highlighted: root.currentCategory === modelData.id
                        Accessible.name: modelData.label + " settings"
                        onClicked: root.currentCategory = modelData.id
                        background: Rectangle {
                            color: parent.highlighted ? "#4b351c" : parent.hovered ? "#292f34" : "transparent"
                            border.width: parent.activeFocus ? 2 : 0
                            border.color: "#e3c765"
                        }
                        contentItem: RowLayout {
                            spacing: 10
                            FlightlineIcon { name: modelData.icon; Layout.preferredWidth: 20; Layout.preferredHeight: 20 }
                            Label { text: modelData.label; color: parent.parent.highlighted ? "#f3d98b" : "#f2efe7"; Layout.fillWidth: true; elide: Text.ElideRight }
                        }
                    }
                }
            }
        }

    CanvasPanel {
        panelKey: "category-detail"
        title: root.categoryLabel()
        defaultX: 292
        defaultWidth: parent ? parent.width - 292 : 900
        defaultHeight: parent ? parent.height : 620
        panelMinimumWidth: 620
        ScrollView {
            anchors.fill: parent
            contentWidth: availableWidth
            ColumnLayout {
                width: parent.width
                spacing: 12
                Label { text: root.categoryLabel(); color: "#f2efe7"; font.pixelSize: 22; font.weight: Font.DemiBold }
                Label { Layout.fillWidth: true; text: "Changes autosave when a real owner exposes a safe preference. Connection, transmit, automation and movement state never restore armed."; color: "#aeb5ba"; wrapMode: Text.WordWrap }
                SafetyBanner { Layout.fillWidth: true; text: "Configuration bundles exclude credentials, QSO data, active radio state, PTT/TUNE, rotator motion/arm, pending commands, live spots and provider bodies. Import restores disconnected and disarmed." }

                GroupBox {
                    visible: root.currentCategory === "station"
                    title: "Station and configuration recovery"
                    Layout.fillWidth: true
                    GridLayout {
                        anchors.fill: parent; columns: 2
                        Label { text: "Last destination" } Label { text: DesktopConfig.lastDestination; color: "#f2efe7" }
                        Label { text: "Restore contract" } Label { text: "Disconnected · TX off · automation disarmed"; color: "#4ec47b" }
                        Label { text: "Configuration" }
                        RowLayout {
                            Button { text: "Choose import for preview"; onClicked: importBundle.open() }
                            Button { text: "Export safe bundle"; onClicked: exportBundle.open() }
                        }
                        Label { text: "Import preview" }
                        Label { Layout.fillWidth: true; text: root.importPreview.valid ? (root.importPreview.requiresReview ? "Valid · explicit review required · unknown sections: " + root.importPreview.unknownSections : "Valid safe bundle · preview only") : (root.importPreview.error || "No bundle selected"); color: root.importPreview.valid ? "#e3c765" : "#aeb5ba"; wrapMode: Text.WordWrap }
                    }
                }
                GroupBox {
                    id: remoteGroup
                    visible: root.currentCategory === "remote"
                    title: "Secure Remote Station service"
                    Layout.fillWidth: true
                    property var cfg: RemoteStation.configuration()
                    property string pairingOffer: ""
                    property var pending: RemoteStation.pendingDevices()
                    property var paired: RemoteStation.pairedDevices()
                    GridLayout {
                        anchors.fill: parent; columns: 2
                        Label { text: "State" } StatusChip { text: RemoteStation.state; kind: RemoteStation.running ? "healthy" : "neutral" }
                        Label { text: "Identity" } Label { text: remoteGroup.cfg.stationId || "Created on first start"; color: "#aeb5ba" }
                        Label { text: "TLS / protocol" } Label { text: "TLS 1.3 · certificate pinning · ShackCQ Remote Protocol v1"; color: "#4ec47b" }
                        CheckBox { id: remoteEnabled; text: "Enable service"; checked: remoteGroup.cfg.enabled }
                        CheckBox { id: remoteLan; text: "Allow configured LAN/VPN address"; checked: remoteGroup.cfg.lanEnabled }
                        Label { text: "Station name" } TextField { id: remoteName; text: remoteGroup.cfg.stationName || "ShackCQ Station"; Layout.fillWidth: true }
                        Label { text: "Listen address / port" }
                        RowLayout { TextField { id: remoteAddress; text: remoteGroup.cfg.listenAddress || "127.0.0.1"; Layout.fillWidth: true }
                            SpinBox { id: remotePort; from: 1; to: 65535; value: remoteGroup.cfg.port || 7443 } }
                        Label { text: "Observed RX channels" } SpinBox { id: remoteChannels; from: 1; to: 2; value: remoteGroup.cfg.audioChannels || 1 }
                        CheckBox { id: remoteTci; text: "TCI server · OFF / loopback / read-only default"; checked: remoteGroup.cfg.tciEnabled }
                        CheckBox { id: remoteRigctld; text: "rigctld server · OFF / loopback / read-only default"; checked: remoteGroup.cfg.rigctldEnabled }
                        Button { text: "Save safe settings"; enabled: !RemoteStation.running; onClicked: RemoteStation.applyLocalSettings({enabled:remoteEnabled.checked,stationName:remoteName.text,listenAddress:remoteAddress.text,port:remotePort.value,lanEnabled:remoteLan.checked,tciEnabled:remoteTci.checked,rigctldEnabled:remoteRigctld.checked,audioChannels:remoteChannels.value}) }
                        RowLayout { Button { text: RemoteStation.running ? "Stop + Global Stop" : "Start service"; onClicked: RemoteStation.running ? RemoteStation.stop() : RemoteStation.startFromUi() }
                            Button { text: "Arm bridge writer · 30 s"; enabled: RemoteStation.running; onClicked: RemoteStation.armThirdPartyWriter(30000) }
                            Button { text: "Global Stop"; onClicked: RemoteStation.globalStop() } }
                        Label { text: "Remote TX / Tune" } Label { text: "Unavailable: desktop radio owner exposes no accepted PTT/Tune authority"; color: "#e3c765"; wrapMode: Text.WordWrap }
                        Label { text: "Clients" } Label { text: RemoteStation.sessionCount + " active · " + RemoteStation.pairedDevices().length + " paired"; color: "#f2efe7" }
                        Label { text: "Pairing" }
                        ColumnLayout { Layout.fillWidth: true
                            Button { text: "Create 2-minute OBSERVER offer"; enabled: RemoteStation.running; onClicked: remoteGroup.pairingOffer = JSON.stringify(RemoteStation.createPairingOffer("OBSERVER")) }
                            TextArea { Layout.fillWidth: true; readOnly: true; wrapMode: TextEdit.WrapAnywhere; text: remoteGroup.pairingOffer || "No active offer" }
                        }
                        Label { text: "Pending approval" }
                        RowLayout { Layout.fillWidth: true
                            Label { text: remoteGroup.pending.length ? remoteGroup.pending[0].deviceId + " · requested " + remoteGroup.pending[0].requestedRole : "None"; color: "#aeb5ba"; Layout.fillWidth: true }
                            Button { text: "Approve observer"; enabled: remoteGroup.pending.length > 0; onClicked: { RemoteStation.approvePendingDevice(remoteGroup.pending[0].deviceId,"OBSERVER"); remoteGroup.pending=RemoteStation.pendingDevices(); remoteGroup.paired=RemoteStation.pairedDevices() } }
                            Button { text: "Approve operator"; enabled: remoteGroup.pending.length > 0; onClicked: { RemoteStation.approvePendingDevice(remoteGroup.pending[0].deviceId,"OPERATOR"); remoteGroup.pending=RemoteStation.pendingDevices(); remoteGroup.paired=RemoteStation.pairedDevices() } }
                            Button { text: "Approve admin"; enabled: remoteGroup.pending.length > 0; onClicked: { RemoteStation.approvePendingDevice(remoteGroup.pending[0].deviceId,"ADMIN"); remoteGroup.pending=RemoteStation.pendingDevices(); remoteGroup.paired=RemoteStation.pairedDevices() } }
                        }
                        Label { text: "Paired device" }
                        RowLayout { Layout.fillWidth: true
                            Label { text: remoteGroup.paired.length ? remoteGroup.paired[0].deviceId + " · " + remoteGroup.paired[0].role : "None"; color: "#aeb5ba"; Layout.fillWidth: true }
                            Button { text: "Revoke"; enabled: remoteGroup.paired.length > 0; onClicked: { RemoteStation.revokeDevice(remoteGroup.paired[0].deviceId); remoteGroup.paired=RemoteStation.pairedDevices() } }
                        }
                    }
                }
                GroupBox {
                    id: remoteClientGroup
                    visible: root.currentCategory === "remote"
                    title: "Remote Station client"
                    Layout.fillWidth: true
                    property string offer: ""
                    GridLayout {
                        anchors.fill: parent; columns: 2
                        Label { text: "State" }
                        RowLayout { StatusChip { text: RemoteClient.state; kind: RemoteClient.connected ? "healthy" : "neutral" }
                            Label { text: RemoteClient.certificatePinned ? "Certificate pinned" : "Not connected"; color: RemoteClient.certificatePinned ? "#4ec47b" : "#aeb5ba" } }
                        Label { text: "Paired station" }
                        ComboBox { Layout.fillWidth: true; model: RemoteClient.profiles; textRole: "name"; valueRole: "stationId"
                            onActivated: RemoteClient.selectedStationId = currentValue }
                        Label { text: "Session" }
                        RowLayout { Button { text: RemoteClient.connected ? "Disconnect" : "Connect"; onClicked: RemoteClient.connected ? RemoteClient.disconnectClient() : RemoteClient.connectSelected() }
                            Button { text: RemoteClient.writerLease ? "Writer held" : "Acquire writer"; enabled: RemoteClient.connected && !RemoteClient.writerLease; onClicked: RemoteClient.acquireWriter() }
                            Button { text: "GLOBAL STOP"; enabled: RemoteClient.connected; onClicked: RemoteClient.globalStop() } }
                        Label { text: "Profile / media" }
                        RowLayout {
                            Button { text: "Remove selected"; enabled: !RemoteClient.connected && RemoteClient.selectedStationId.length > 0; onClicked: RemoteClient.removeSelectedProfile() }
                            CheckBox { text: "Request raw I/Q"; enabled: RemoteClient.connected; onToggled: if (enabled) RemoteClient.configureMedia(checked) }
                        }
                        Label { text: "Station state" }
                        Label { text: (RemoteClient.frequencyHz ? RemoteClient.frequencyHz + " Hz" : "No frequency") + " · " + (RemoteClient.mode || "No mode"); color: "#f2efe7" }
                        Label { text: "Safe controls" }
                        RowLayout { TextField { id: remoteFrequency; placeholderText: "Frequency Hz"; inputMethodHints: Qt.ImhDigitsOnly }
                            Button { text: "Set frequency"; enabled: RemoteClient.writerLease; onClicked: RemoteClient.setRemoteFrequency(remoteFrequency.text) }
                            TextField { id: remoteMode; placeholderText: "Mode" }
                            Button { text: "Set mode"; enabled: RemoteClient.writerLease; onClicked: RemoteClient.setRemoteMode(remoteMode.text) } }
                        Label { text: "Pairing offer" }
                        ColumnLayout { Layout.fillWidth: true
                            TextArea { id: clientOffer; Layout.fillWidth: true; placeholderText: "Paste station pairing-offer JSON"; wrapMode: TextEdit.WrapAnywhere }
                            RowLayout { ComboBox { id: clientRole; model: ["OBSERVER", "OPERATOR", "ADMIN"] }
                                Button { text: "Submit signed request"; enabled: clientOffer.text.length > 0; onClicked: RemoteClient.importPairingOffer(clientOffer.text, clientRole.currentText) } }
                        }
                        Label { text: "RX / TX safety" }
                        Label { Layout.fillWidth: true; text: "Pinned TLS · signed P-256 identity · Opus/PCM RX audio · optional raw I/Q. PTT, TUNE and rotator movement remain unavailable until policy and physical acceptance permit them."; color: "#e3c765"; wrapMode: Text.WordWrap }
                        Label { text: "Status" } Label { Layout.fillWidth: true; text: RemoteClient.status; color: "#aeb5ba"; wrapMode: Text.WordWrap }
                    }
                }
                GroupBox {
                    visible: root.currentCategory === "radio"
                    title: "Radio profiles and safety"
                    Layout.fillWidth: true
                    GridLayout {
                        anchors.fill: parent; columns: 2
                        Label { text: "State" } StatusChip { text: Radio.state; kind: Radio.state.startsWith("Connected") ? "healthy" : "neutral" }
                        Label { text: "TCI profiles" } Label { text: Radio.tciProfiles.length + " saved · explicit connect · PTT/TUNE locked"; color: "#f2efe7" }
                        Label { text: "Receiver authority" } Label { text: "Control " + (Radio.activeReceiverId || "—") + " · listening " + (Radio.listeningReceiverId || "—") + " · TX " + (Radio.transmitReceiverId || "—"); color: "#aeb5ba" }
                        Label { text: "Credentials" } Label { text: "Aliases only; secret values remain in the platform credential vault"; color: "#aeb5ba" }
                    }
                }
                GroupBox {
                    visible: root.currentCategory === "audio"
                    title: "Audio and Panadapter"
                    Layout.fillWidth: true
                    GridLayout {
                        anchors.fill: parent; columns: 2
                        Label { text: "Spectrum" } Label { text: Panadapter.fftSize + " FFT · " + Panadapter.waterfallRows + " rows · " + Panadapter.colourMap; color: "#f2efe7" }
                        Label { text: "Contrast" } Label { text: Panadapter.fitAutoContrast ? "FIT auto contrast" : "Manual floor / top"; color: "#aeb5ba" }
                        Label { text: "Audio route" } Label { text: "Stable platform identity required; microphone fallback for I/Q prohibited"; color: "#e3c765"; wrapMode: Text.WordWrap }
                    }
                }
                GroupBox {
                    visible: ["digi","keyer","cluster","alerts","contest","bandmaps","wavelog","groups","operations","rotator"].includes(root.currentCategory)
                    title: root.categoryLabel() + " lifecycle"
                    Layout.fillWidth: true
                    ColumnLayout {
                        anchors.fill: parent
                        Label { Layout.fillWidth: true; text: Parity.workspaceSummary(root.currentCategory === "bandmaps" ? "Band Maps" : root.currentCategory === "groups" ? "Groups.io" : root.currentCategory === "operations" ? "Operations" : root.currentCategory.charAt(0).toUpperCase() + root.currentCategory.slice(1)).detail || "Settings are exposed only when the owning service supplies a safe, validated preference."; color: "#aeb5ba"; wrapMode: Text.WordWrap }
                        Label { Layout.fillWidth: true; text: root.currentCategory === "rotator" ? "Rotator remains disconnected and disarmed; no target, tracking or movement state restores." : root.currentCategory === "keyer" ? "Keyer restore is stopped; foreground shortcut and transmit acceptance are required." : "No unavailable capability is guessed or represented by fabricated state."; color: "#e3c765"; wrapMode: Text.WordWrap }
                        Button { text: "Open " + root.categoryLabel() + " workspace"; onClicked: Desktop.currentDestination = root.categoryDestination() }
                    }
                }
                GroupBox {
                    visible: root.currentCategory === "providers"
                    title: "Provider lifecycle"
                    Layout.fillWidth: true
                    ColumnLayout {
                        anchors.fill: parent
                        Label { Layout.fillWidth: true; text: "Disabled by default, one in-flight request per key, explicit enable/refresh, and written CURRENT / STALE / OFFLINE_CACHE / EMPTY / ERROR / UNAVAILABLE state."; color: "#aeb5ba"; wrapMode: Text.WordWrap }
                        ListView {
                            Layout.fillWidth: true
                            implicitHeight: 340
                            model: Parity.providers
                            clip: true
                            delegate: RowLayout {
                                required property var item
                                width: ListView.view.width
                                height: 38
                                CheckBox { checked: item.enabled; text: item.title; Layout.preferredWidth: 280; onToggled: Parity.setProviderEnabled(item.key, checked) }
                                StatusChip { text: item.state; kind: item.state === "CURRENT" ? "healthy" : item.state === "ERROR" ? "danger" : "neutral" }
                                Label { text: item.detail; color: "#aeb5ba"; elide: Text.ElideRight; Layout.fillWidth: true }
                                Button { text: "Refresh"; enabled: item.enabled; onClicked: Parity.refreshProvider(item.key) }
                            }
                        }
                    }
                }
                GroupBox {
                    visible: root.currentCategory === "appearance"
                    title: "Appearance and accessibility"
                    Layout.fillWidth: true
                    GridLayout {
                        anchors.fill: parent; columns: 2
                        Label { text: "Global navigation" } Label { text: "Grouped Flightline sidebar plus native Navigate menu and command palette"; color: "#4ec47b" }
                        Label { text: "Official layouts" } Label { text: "Locked by default · View → Edit Layout unlocks bounded grid editing · Reset restores official geometry"; color: "#4ec47b"; wrapMode: Text.WordWrap }
                        Label { text: "Scale evidence" } Label { text: "1366×768 · 1440×900 · 1512×982 · 1920×1080 · 2560×1440 · 150%"; color: "#aeb5ba"; wrapMode: Text.WordWrap }
                        Label { text: "Status semantics" } Label { text: "Written state plus colour; native-menu focus and accessible control labels"; color: "#4ec47b"; wrapMode: Text.WordWrap }
                    }
                }
                GroupBox {
                    visible: root.currentCategory === "health"
                    title: "Health, support and acknowledgements"
                    Layout.fillWidth: true
                    RowLayout {
                        anchors.fill: parent
                        Button { text: "System Health"; onClicked: Desktop.currentDestination = "Health" }
                        Button { text: "About / Licences"; onClicked: Desktop.currentDestination = "About" }
                        Item { Layout.fillWidth: true }
                        Label { Layout.fillWidth: true; text: "Support bundle UI remains disabled until chooser/result lifecycle is complete"; color: "#e3c765"; wrapMode: Text.WordWrap }
                    }
                }
                Item { Layout.fillWidth: true; Layout.preferredHeight: 20 }
            }
        }
    }
}
