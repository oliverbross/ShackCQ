import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id: root
    workspaceKey: "Radio"
    property int selectedModel: -1
    property var snapshot: Radio.receiverSnapshot(Radio.activeReceiverId)
    Connections { target: Radio; function onSnapshotChanged() { root.snapshot = Radio.receiverSnapshot(Radio.activeReceiverId) } }

    CanvasPanel {
        panelKey: "connection"
        title: "Radio identity · connection · safety"
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: 94
        panelMinimumHeight: 90
        RowLayout {
            anchors.fill: parent
            StatusChip { text: Radio.state; kind: Radio.state.startsWith("Connected") ? "healthy" : "neutral" }
            StatusChip { text: "Remote " + RemoteClient.state; kind: RemoteClient.connected ? "healthy" : "neutral" }
            Label { text: "Explicit connect only · capability and readback are authoritative · PTT/TUNE unavailable"; color: "#e3c765"; Layout.fillWidth: true; elide: Text.ElideRight }
            Button { text: "Disconnect"; enabled: Radio.state.startsWith("Connected"); onClicked: Radio.disconnectRadio() }
            Button { text: "EMERGENCY RX"; palette.button: "#8f1d24"; palette.buttonText: "white"; font.weight: Font.Bold; onClicked: Desktop.globalStop() }
        }
    }

    CanvasPanel {
        id: backendPanel
        panelKey: "backend"
        title: "Backend · profile · receivers"
        defaultY: 106
        defaultWidth: 316
        defaultHeight: parent ? parent.height - 106 : 620
        panelMinimumWidth: 292
        ColumnLayout {
            anchors.fill: parent
            spacing: 8
            ComboBox {
                id: backend
                objectName: "radioBackend"
                Layout.fillWidth: true
                currentIndex: ApplicationWindow.window ? ApplicationWindow.window.galleryRadioBackend : 0
                model: ["Native Elecraft KX3","Native Elecraft KX2","Native FlexRadio","Native QMX","Native QMX+","Native RGO ONE V6","Conservative RGO legacy","Embedded Hamlib 4.7.2","Hamlib network","TCI receive-only SDR"]
            }
            Label {
                Layout.fillWidth: true
                text: backend.currentIndex < 7 ? "Native adapter · physical readback acceptance pending" : backend.currentIndex === 9 ? "TCI · bounded multi-receiver I/Q · TX locked" : "Capability-driven Hamlib catalogue"
                color: "#f4c94e"
                wrapMode: Text.WordWrap
            }
            ComboBox {
                id: nativeProfile
                visible: backend.currentIndex < 7
                Layout.fillWidth: true
                model: Parity.nativeRadioProfiles
                textRole: "title"
                valueRole: "key"
                Accessible.name: "Native radio profile"
            }
            TextField { id: nativeRoute; visible: backend.currentIndex < 7; Layout.fillWidth: true; placeholderText: nativeProfile.currentValue === "FLEX" ? "tcp://radio:4992" : "Exact serial port identity" }
            RowLayout {
                visible: backend.currentIndex < 7
                SpinBox { id: nativeBaud; from: 1200; to: 921600; value: 38400; editable: true; Layout.fillWidth: true }
                Button { text: "Connect"; enabled: nativeRoute.text.trim().length > 0 && nativeProfile.currentValue !== "RGO-UNKNOWN"; onClicked: Radio.connectNativeProfile(nativeProfile.currentValue, nativeRoute.text, nativeBaud.value) }
            }
            Label { visible: backend.currentIndex < 7; Layout.fillWidth: true; text: nativeProfile.currentValue === "RGO-UNKNOWN" ? "Unknown generation remains disconnected; no framing is guessed." : "Explicit route only. Readback proves state; transmit commands are rejected."; color: "#aeb5ba"; wrapMode: Text.WordWrap }
            TextField { visible: backend.currentIndex >= 7 && backend.currentIndex < 9; Layout.fillWidth: true; placeholderText: "Find manufacturer or model"; onTextChanged: RadioModels.setSearch(text) }
            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: backend.currentIndex >= 7 && backend.currentIndex < 9
                model: RadioModels
                clip: true
                delegate: ItemDelegate {
                    required property int modelId
                    required property string manufacturer
                    required property string model
                    required property string backend
                    required property string transport
                    width: ListView.view.width
                    text: manufacturer + "  " + model + "\n" + backend + " · " + transport
                    highlighted: root.selectedModel === modelId
                    onClicked: root.selectedModel = modelId
                }
            }
            TextField { id: route; visible: backend.currentIndex >= 7 && backend.currentIndex < 9; Layout.fillWidth: true; placeholderText: "COM port or Hamlib network route" }
            RowLayout {
                visible: backend.currentIndex >= 7 && backend.currentIndex < 9
                SpinBox { id: baud; from: 1200; to: 921600; value: 38400; editable: true; Layout.fillWidth: true }
                Button { text: "Connect"; enabled: root.selectedModel >= 0 && route.text.length > 0; onClicked: Radio.connectRadio(root.selectedModel, route.text, baud.value) }
            }
            Label { visible: backend.currentIndex === 9; text: "TCI PROFILES"; color: "#e9a72b"; font.weight: Font.Bold }
            ListView {
                visible: backend.currentIndex === 9
                Layout.fillWidth: true
                Layout.fillHeight: true
                model: Radio.tciProfiles
                clip: true
                delegate: ItemDelegate { width: ListView.view.width; text: modelData.displayName + "\n" + modelData.endpoint; onClicked: Radio.connectTciProfile(modelData.id) }
            }
            Label { visible: Radio.receiverCount > 0; text: "RECEIVERS"; color: "#e9a72b"; font.weight: Font.Bold }
            ListView {
                visible: Radio.receiverCount > 0
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(150, contentHeight)
                model: Radio.receivers
                clip: true
                delegate: ItemDelegate {
                    required property string receiverId
                    required property string displayLabel
                    required property bool activeControl
                    required property bool activeListening
                    required property real effectiveReceiveHz
                    required property string mode
                    width: ListView.view.width
                    text: (activeControl ? "CONTROL · " : activeListening ? "LISTEN · " : "") + displayLabel + "\n" + (effectiveReceiveHz ? (effectiveReceiveHz / 1000).toFixed(3) + " kHz" : "—") + " · " + (mode || "—")
                    onClicked: Radio.selectActiveReceiver(receiverId)
                }
            }
        }
    }

    CanvasPanel {
        id: consolePanel
        panelKey: "radio-console"
        title: "Observed VFO and receive console"
        defaultX: 328
        defaultY: 106
        defaultWidth: parent ? parent.width - 328 : 872
        defaultHeight: 332
        panelMinimumWidth: 620
        ColumnLayout {
            anchors.fill: parent
            spacing: 8
            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                color: "#e9a72b"
                border.color: "#f4c94e"
                radius: 5
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 16
                    ColumnLayout {
                        Layout.preferredWidth: 190
                        Label { text: "S / CWT"; color: "#201708"; font.weight: Font.Bold }
                        Label { text: "READBACK " + (root.snapshot.sMeter !== undefined ? root.snapshot.sMeter : "UNAVAILABLE"); color: "#201708"; font.family: "monospace" }
                        ProgressBar { Layout.fillWidth: true; value: root.snapshot.sMeter !== undefined ? Number(root.snapshot.sMeter) / 9 : 0; enabled: root.snapshot.sMeter !== undefined }
                        Label { text: "SWR / RF"; color: "#201708"; font.weight: Font.Bold }
                        Label { text: "No fabricated meter"; color: "#5a410e"; font.pixelSize: 11 }
                    }
                    Rectangle { Layout.preferredWidth: 1; Layout.fillHeight: true; color: "#7b5612" }
                    ColumnLayout {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        RowLayout {
                            Layout.fillWidth: true
                            Label { text: "VFO A"; color: "#201708"; font.weight: Font.Bold }
                            Item { Layout.fillWidth: true }
                            Label { text: Radio.state.startsWith("Connected") ? "OBSERVED" : "NO LIVE STATE"; color: "#5a410e"; font.weight: Font.Bold }
                        }
                        Label {
                            Layout.fillWidth: true
                            text: Radio.frequencyHz ? (Radio.frequencyHz / 1000).toFixed(3) : "— — — — . — — —"
                            color: "#201708"
                            font.family: "monospace"
                            font.pixelSize: Math.max(34, Math.min(62, consolePanel.width / 14))
                            font.weight: Font.Bold
                            horizontalAlignment: Text.AlignRight
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            Label { text: "MODE  " + (Radio.mode || "—"); color: "#201708"; font.family: "monospace"; font.pixelSize: 16 }
                            Label { text: "FILTER  " + (root.snapshot.filterHz || "—"); color: "#201708"; font.family: "monospace" }
                            Item { Layout.fillWidth: true }
                            Label { text: "SAFE / RX"; color: "#1f5f36"; font.weight: Font.Bold; font.pixelSize: 16 }
                        }
                        Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: "#7b5612" }
                        RowLayout {
                            Layout.fillWidth: true
                            Label { text: "VFO B"; color: "#201708"; font.weight: Font.Bold }
                            Label { text: root.snapshot.vfoBHz ? (Number(root.snapshot.vfoBHz) / 1000).toFixed(3) + " kHz" : "—"; color: "#201708"; font.family: "monospace"; font.pixelSize: 21; Layout.fillWidth: true; horizontalAlignment: Text.AlignRight }
                        }
                        Label { text: "AGC " + (root.snapshot.agc || "—") + "   PRE " + (root.snapshot.preamp || "—") + "   ATT " + (root.snapshot.attenuator || "—") + "   BW " + (root.snapshot.filterHz || "—"); color: "#5a410e"; font.family: "monospace" }
                    }
                }
            }
            RowLayout {
                Layout.fillWidth: true
                Repeater {
                    model: ["CW","USB","LSB","AM","FM","DIGU","DIGL"]
                    Button { required property string modelData; text: modelData; enabled: Radio.state.startsWith("Connected"); onClicked: Radio.requestMode(modelData) }
                }
                Item { Layout.fillWidth: true }
                Button { text: "Band Maps"; onClicked: Desktop.currentDestination = "Band Maps" }
            }
        }
    }

    CanvasPanel {
        panelKey: "operating-strip"
        title: "Receive review · spots · keyer safety"
        defaultX: 328
        defaultY: 450
        defaultWidth: parent ? parent.width - 328 : 872
        defaultHeight: parent ? parent.height - 450 : 276
        panelMinimumWidth: 620
        RowLayout {
            anchors.fill: parent
            spacing: 12
            ColumnLayout {
                Layout.preferredWidth: 300
                Label { text: "EXPLICIT RX CHANGE"; color: "#e9a72b"; font.weight: Font.Bold }
                TextField { id: frequency; Layout.fillWidth: true; placeholderText: "Frequency Hz"; validator: DoubleValidator { bottom: 100000; top: 10500000000 } }
                ComboBox { id: mode; Layout.fillWidth: true; model: ["CW","USB","LSB","AM","FM","DIGU","DIGL"] }
                Button { text: "Apply observed RX"; enabled: Radio.state.startsWith("Connected") && acceptableInput; onClicked: { Radio.requestFrequency(Number(frequency.text)); Radio.requestMode(mode.currentText) } }
                Label { text: "No write is retried without readback."; color: "#aeb5ba"; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            }
            Rectangle { Layout.preferredWidth: 1; Layout.fillHeight: true; color: "#3a4147" }
            ColumnLayout {
                Layout.fillWidth: true
                Label { text: "LIVE DX SPOTS"; color: "#e9a72b"; font.weight: Font.Bold }
                ListView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    model: Spots
                    clip: true
                    delegate: RowLayout {
                        required property string callsign
                        required property double frequencyHz
                        required property string mode
                        width: ListView.view.width
                        height: 34
                        Label { text: callsign; color: "#59cddd"; font.weight: Font.Bold; Layout.preferredWidth: 90 }
                        Label { text: (frequencyHz / 1000).toFixed(1); color: "#f4c94e"; font.family: "monospace"; Layout.preferredWidth: 110 }
                        Label { text: mode; color: "#aeb5ba"; Layout.fillWidth: true }
                    }
                }
            }
            Rectangle { Layout.preferredWidth: 1; Layout.fillHeight: true; color: "#3a4147" }
            ColumnLayout {
                Layout.preferredWidth: 210
                Label { text: "KEYER"; color: "#e9a72b"; font.weight: Font.Bold }
                Repeater { model: Parity.keyerMacros; Button { required property var item; Layout.fillWidth: true; text: item.title; onClicked: Keyer.previewMacro(item.key,{MYCALL:"OM0RX"}); ToolTip.visible: hovered; ToolTip.text: "Local preview only; foreground TX acceptance remains pending" } }
                Item { Layout.fillHeight: true }
                Button { Layout.fillWidth: true; text: "STOP"; onClicked: Desktop.globalStop() }
            }
        }
    }
}
