import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    property string workspace: "Home"
    property bool compact: false
    property date now: new Date()
    function utcText() {
        const pad = function(value) { return value < 10 ? "0" + value : String(value) }
        return pad(now.getUTCHours()) + ":" + pad(now.getUTCMinutes()) + ":" + pad(now.getUTCSeconds())
    }
    implicitHeight: compact ? 58 : 66
    color: "#22272b"
    border.color: "#3a4147"

    Timer { interval: 1000; running: true; repeat: true; onTriggered: root.now = new Date() }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 12
        anchors.topMargin: 7
        anchors.bottomMargin: 7
        spacing: compact ? 8 : 12
        Item {
            Layout.minimumWidth: 172
            Layout.preferredWidth: 172
            Layout.maximumWidth: 172
            Layout.fillHeight: true
            Label { anchors.left: parent.left; anchors.top: parent.top; text: root.workspace.toUpperCase(); color: "#e9a72b"; font.pixelSize: 11; font.weight: Font.Bold }
            Label { anchors.left: parent.left; anchors.bottom: parent.bottom; text: "OM0RX  ·  JN88TQ"; color: "#f4f0e7"; font.pixelSize: compact ? 17 : 20; font.weight: Font.DemiBold }
        }
        Rectangle { visible: !compact; Layout.preferredWidth: 1; Layout.fillHeight: true; color: "#3a4147" }
        Item {
            visible: !compact
            Layout.minimumWidth: 68
            Layout.preferredWidth: 68
            Layout.fillHeight: true
            Label { anchors.left: parent.left; anchors.top: parent.top; text: "UTC"; color: "#929ba2"; font.pixelSize: 10 }
            Label { anchors.left: parent.left; anchors.bottom: parent.bottom; text: root.utcText(); color: "#f4f0e7"; font.family: "monospace"; font.pixelSize: 17 }
        }
        Label {
            visible: Radio.state.startsWith("Connected") && !compact
            Layout.minimumWidth: 146
            Layout.preferredWidth: 146
            text: (Radio.frequencyHz ? (Radio.frequencyHz / 1000).toFixed(3) + " kHz" : "—") + "  " + (Radio.mode || "—")
            color: "#f4c94e"
            font.family: "monospace"
            font.pixelSize: 15
        }
        Item { Layout.fillWidth: true }
        StatusChip { visible: !compact; text: Radio.state.startsWith("Connected") ? "RADIO RX ONLY" : "RADIO " + Radio.state; kind: Radio.state.startsWith("Connected") ? "healthy" : "neutral" }
        StatusChip { visible: !compact; text: "CLUSTER " + Cluster.state; kind: Cluster.state.startsWith("Connected") ? "healthy" : Cluster.state === "Error" ? "danger" : "neutral" }
        Button {
            text: compact ? "STOP" : "GLOBAL STOP"
            palette.button: "#8f1d24"
            palette.buttonText: "white"
            font.weight: Font.Bold
            onClicked: Desktop.invokeCommand("radio.stop")
            Accessible.name: "Global Stop"
            Accessible.description: "Immediately cancels radio mutations and returns receive systems to their safe state"
        }
    }
}
