import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    property string label: "Metric"; property string value: "—"; property string truth: "Observed local"
    implicitWidth: 190; implicitHeight: 104; color: "#22272b"; border.color: "#3a4147"; radius: 4
    ColumnLayout { anchors.fill: parent; anchors.margins: 12; spacing: 4
        Label { text: label.toUpperCase(); color: "#d38b22"; font.pixelSize: 11; font.weight: Font.Bold; elide: Text.ElideRight; Layout.fillWidth: true }
        Label { text: value; color: "#f2efe7"; font.pixelSize: 25; elide: Text.ElideRight; Layout.fillWidth: true }
        Label { text: truth; color: "#aeb5ba"; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
    }
    Accessible.name: label + ": " + value + ". " + truth
    Accessible.role: Accessible.StaticText
}
