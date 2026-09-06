import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    property bool autoCompact: false
    readonly property bool collapsed: autoCompact || Desktop.sidebarCollapsed
    implicitWidth: collapsed ? 64 : 224
    color: "#171a1d"
    border.color: "#3a4147"
    Accessible.role: Accessible.Pane
    Accessible.name: "Workspace navigation"

    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 58
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 8
                FlightlineIcon { name: "home"; color: "#e9a72b"; Layout.preferredWidth: 26; Layout.preferredHeight: 26 }
                Label { visible: !root.collapsed; text: "SHACKCQ"; color: "#f4f0e7"; font.weight: Font.Bold; font.pixelSize: 15; Layout.fillWidth: true }
                ToolButton {
                    visible: !root.autoCompact
                    Accessible.name: root.collapsed ? "Expand workspace navigation" : "Collapse workspace navigation"
                    ToolTip.visible: hovered
                    ToolTip.text: Accessible.name
                    onClicked: Desktop.sidebarCollapsed = !Desktop.sidebarCollapsed
                    contentItem: FlightlineIcon { name: "sidebar"; color: "#aeb5ba" }
                }
            }
        }
        Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: "#3a4147" }
        ListView {
            id: destinations
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            model: Desktop.commands.filter(function(command) { return command.workspace })
            section.property: "category"
            section.criteria: ViewSection.FullString
            section.delegate: Item {
                required property string section
                width: destinations.width
                height: root.collapsed ? 12 : 28
                Label {
                    visible: !root.collapsed
                    anchors.left: parent.left
                    anchors.leftMargin: 14
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 5
                    text: section
                    color: "#929ba2"
                    font.pixelSize: 10
                    font.weight: Font.DemiBold
                }
            }
            delegate: ItemDelegate {
                required property var modelData
                width: destinations.width
                height: 38
                highlighted: Desktop.currentDestination === modelData.destination
                Accessible.name: modelData.label + " workspace"
                Accessible.description: modelData.description
                ToolTip.visible: root.collapsed && hovered
                ToolTip.text: modelData.label
                onClicked: Desktop.invokeCommand(modelData.id)
                background: Rectangle {
                    color: parent.highlighted ? "#4b351c" : parent.hovered ? "#292f34" : "transparent"
                    border.width: parent.activeFocus ? 1 : 0
                    border.color: "#f0ce68"
                    Rectangle { visible: parent.parent.highlighted; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; width: 3; height: 24; radius: 1; color: "#e9a72b" }
                }
                contentItem: RowLayout {
                    spacing: 10
                    FlightlineIcon { name: modelData.icon; color: parent.parent.highlighted ? "#f4c94e" : "#aeb5ba"; Layout.preferredWidth: 20; Layout.preferredHeight: 20 }
                    Label { visible: !root.collapsed; text: modelData.label; color: parent.parent.highlighted ? "#f4f0e7" : "#d4d8da"; Layout.fillWidth: true; elide: Text.ElideRight }
                }
            }
        }
    }
}
