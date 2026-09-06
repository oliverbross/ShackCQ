import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id: root
    workspaceKey: "EQ"
    property var rxDraft: [0,0,0,0,0,0,0,0]
    property var txDraft: [0,0,0,0,0,0,0,0]
    property bool txDirection: false

    CanvasPanel {
        panelKey: "safety"
        title: "EQ capability and provenance"
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: 82
        panelMinimumHeight: 78
        SafetyBanner { anchors.fill: parent; text: "Radio readback is green; local draft is yellow. Apply remains unavailable until a connected native profile proves exact EQ capability and verified readback." }
    }

    CanvasPanel {
        panelKey: "audio-bench"
        title: "Flightline audio bench"
        defaultY: 94
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: parent ? parent.height - 94 : 620
        ColumnLayout {
            anchors.fill: parent
            spacing: 10
            RowLayout {
                Layout.fillWidth: true
                ComboBox { model: ["RX EQ", "TX EQ"]; Accessible.name: "Equalizer direction"; onCurrentIndexChanged: root.txDirection = currentIndex === 1 }
                ComboBox { model: ["Current profile", "Speech", "Field", "Flat"]; Accessible.name: "Equalizer profile" }
                StatusChip { text: Radio.state; kind: Radio.state.startsWith("Connected") ? "healthy" : "hold" }
                Label { text: "READBACK  UNAVAILABLE"; color: "#42c77b"; font.family: "monospace"; font.weight: Font.Bold }
                Label { text: "DRAFT  FLAT"; color: "#f4c94e"; font.family: "monospace"; font.weight: Font.Bold }
                Item { Layout.fillWidth: true }
                Button { text: "Record finite sample"; enabled: false; ToolTip.visible: hovered; ToolTip.text: "Exact audio route owner is unavailable" }
                Button { text: "A / B"; enabled: false }
            }
            RowLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: 164
                Layout.minimumHeight: 164
                Layout.maximumHeight: 200
                Rectangle {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    color: "#101316"
                    border.color: "#3a4147"
                    Canvas {
                        anchors.fill: parent
                        anchors.margins: 12
                        onPaint: {
                            const painter = getContext("2d")
                            painter.clearRect(0, 0, width, height)
                            painter.strokeStyle = "#283139"
                            painter.lineWidth = 1
                            for (let x = 0; x <= width; x += width / 8) { painter.beginPath(); painter.moveTo(x, 0); painter.lineTo(x, height); painter.stroke() }
                            for (let y = 0; y <= height; y += height / 4) { painter.beginPath(); painter.moveTo(0, y); painter.lineTo(width, y); painter.stroke() }
                            painter.strokeStyle = "#e9a72b"
                            painter.beginPath(); painter.moveTo(0, height / 2); painter.lineTo(width, height / 2); painter.stroke()
                        }
                    }
                    Label { anchors.centerIn: parent; text: "DRAFT RESPONSE · FLAT\nNo measured curve captured"; color: "#aeb5ba"; horizontalAlignment: Text.AlignHCenter; font.family: "monospace" }
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    color: "#101316"
                    border.color: "#3a4147"
                    Label { anchors.centerIn: parent; text: "WAVEFORM / SPECTRUM\nNO OBSERVED SAMPLE"; color: "#aeb5ba"; horizontalAlignment: Text.AlignHCenter; font.family: "monospace" }
                }
            }
            Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: "#3a4147" }
            RowLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 260
                spacing: 12
                Repeater {
                    id: bandControls
                    model: ["50", "100", "200", "400", "800", "1.6k", "3.2k", "6.4k"]
                    Rectangle {
                        required property int index
                        required property string modelData
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        color: index % 2 ? "#1b2228" : "#171a1d"
                        border.color: "#3a4147"
                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 8
                            Label { text: gain.value.toFixed(0) + " dB"; color: "#f4c94e"; font.family: "monospace"; Layout.alignment: Qt.AlignHCenter }
                            Slider {
                                id: gain
                                from: -12
                                to: 12
                                value: root.txDirection ? root.txDraft[index] : root.rxDraft[index]
                                orientation: Qt.Vertical
                                onMoved: { const values=(root.txDirection ? root.txDraft : root.rxDraft).slice(); values[index]=Math.round(value); if (root.txDirection) root.txDraft=values; else root.rxDraft=values }
                                Layout.fillHeight: true
                                Layout.preferredWidth: 28
                                Layout.alignment: Qt.AlignHCenter
                                background: Rectangle {
                                    x: (parent.width - width) / 2
                                    y: 4
                                    width: 6
                                    height: parent.height - 8
                                    radius: 3
                                    color: "#101316"
                                    border.color: "#3a4147"
                                    Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: parent.height / 2; radius: 3; color: "#4b351c" }
                                }
                                handle: Rectangle {
                                    x: (parent.width - width) / 2
                                    y: parent.visualPosition * (parent.height - height)
                                    width: 18
                                    height: 6
                                    radius: 3
                                    color: "#e9a72b"
                                    border.color: "#f4c94e"
                                }
                            }
                            Label { text: modelData + " Hz"; color: "#f4f0e7"; font.family: "monospace"; Layout.alignment: Qt.AlignHCenter }
                            Label { text: "READ —"; color: "#42c77b"; font.pixelSize: 10; Layout.alignment: Qt.AlignHCenter }
                        }
                    }
                }
            }
            RowLayout {
                Layout.fillWidth: true
                Label { text: "Source: no accepted capture route"; color: "#aeb5ba" }
                Label { text: "Baseline: radio readback unavailable"; color: "#aeb5ba" }
                Item { Layout.fillWidth: true }
                Button { text: "Reset draft"; onClicked: { root.rxDraft=[0,0,0,0,0,0,0,0]; root.txDraft=[0,0,0,0,0,0,0,0] } }
                Button { text: "Review apply"; highlighted: true; onClicked: { if (Parity.saveEqDraft(root.rxDraft,root.txDraft)) Parity.reviewEqApply() } ToolTip.visible: hovered; ToolTip.text: "Opens a capability/readback review; no command is sent" }
            }
        }
    }
}
