import QtQuick
import QtQuick.Controls

Rectangle {
    id: root
    property string text: "Safety state"
    implicitHeight: label.implicitHeight + 20; color: "#352b18"; border.color: "#e3c765"; radius: 4
    Label { id: label; anchors.fill: parent; anchors.margins: 10; text: root.text; color: "#f2efe7"; wrapMode: Text.WordWrap }
    Accessible.name: "Safety: " + text
    Accessible.role: Accessible.AlertMessage
}
