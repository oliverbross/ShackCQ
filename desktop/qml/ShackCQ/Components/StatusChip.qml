import QtQuick
import QtQuick.Controls

Rectangle {
    id: root
    property string text: "Unknown"
    property string kind: "neutral"
    implicitHeight: 28; implicitWidth: label.implicitWidth + 34; radius: 4
    color: kind === "healthy" ? "#18452d" : kind === "danger" ? "#4f2020" : kind === "hold" ? "#493c18" : "#2c3338"
    border.color: kind === "healthy" ? "#4ec47b" : kind === "danger" ? "#e05252" : kind === "hold" ? "#e3c765" : "#68727a"
    Row {
        anchors.centerIn: parent
        spacing: 7
        Rectangle { width: 7; height: 7; radius: 4; anchors.verticalCenter: parent.verticalCenter; color: root.kind === "healthy" ? "#4ec47b" : root.kind === "danger" ? "#e05252" : root.kind === "hold" ? "#e3c765" : "#aeb5ba"; Accessible.ignored: true }
        Label { id: label; text: root.text; color: "#f2efe7"; font.pixelSize: 12; font.weight: Font.DemiBold }
    }
    Accessible.name: text + " status"
    Accessible.role: Accessible.StaticText
}
