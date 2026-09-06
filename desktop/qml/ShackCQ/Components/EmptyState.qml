import QtQuick
import QtQuick.Controls

Item {
    id: root
    property string title: "Nothing observed"
    property string detail: "No source data is available."
    implicitHeight: 160
    Column { anchors.centerIn: parent; spacing: 8
        Label { anchors.horizontalCenter: parent.horizontalCenter; text: root.title; color: "#f2efe7"; font.pixelSize: 18 }
        Label { width: Math.min(520, root.width - 32); text: root.detail; color: "#aeb5ba"; wrapMode: Text.WordWrap; horizontalAlignment: Text.AlignHCenter }
    }
    Accessible.name: title + ". " + detail
    Accessible.role: Accessible.StaticText
}
