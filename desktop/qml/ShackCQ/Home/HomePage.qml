import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ShackCQ.Controls 1.0
import "../Components"

WorkspaceCanvas {
    id: root
    workspaceKey: "Home"
    readonly property real columnHeight: Math.max(420, height - 94)

    CanvasPanel {
        panelKey: "safety"
        title: "Operational state"
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: 82
        panelMinimumHeight: 78
        SafetyBanner {
            anchors.fill: parent
            text: "SAFE / RX · radio disconnected by default · rotator disarmed · live providers remain explicitly labelled. Nothing connects, tunes, transmits, posts, or moves on open."
        }
    }

    CanvasPanel {
        id: stationRail
        panelKey: "station-instruments"
        title: "Station · weather · bands"
        defaultY: 94
        defaultWidth: parent ? Math.max(268, (parent.width - 24) * 0.24) : 286
        defaultHeight: root.columnHeight
        panelMinimumWidth: 260
        ColumnLayout {
            anchors.fill: parent
            spacing: 10
            GridLayout {
                Layout.fillWidth: true
                columns: 2
                columnSpacing: 12
                rowSpacing: 7
                Label { text: "CALL"; color: "#929ba2" }
                Label { text: "OM0RX"; color: "#59cddd"; font.family: "monospace"; font.pixelSize: 18; Layout.alignment: Qt.AlignRight }
                Label { text: "GRID"; color: "#929ba2" }
                Label { text: "JN88TQ"; color: "#f4f0e7"; font.family: "monospace"; font.pixelSize: 17; Layout.alignment: Qt.AlignRight }
                Label { text: "RADIO"; color: "#929ba2" }
                Label { text: Radio.state; color: Radio.state.startsWith("Connected") ? "#42c77b" : "#f4c94e"; Layout.alignment: Qt.AlignRight }
                Label { text: "VFO A"; color: "#929ba2" }
                Label { text: Radio.frequencyHz ? (Radio.frequencyHz / 1000).toFixed(3) + " kHz" : "NO LIVE STATE"; color: "#f4f0e7"; font.family: "monospace"; Layout.alignment: Qt.AlignRight }
                Label { text: "MODE"; color: "#929ba2" }
                Label { text: Radio.mode || "—"; color: "#f4f0e7"; font.family: "monospace"; Layout.alignment: Qt.AlignRight }
                Label { text: "TX SAFETY"; color: "#929ba2" }
                Label { text: "SAFE / RX"; color: "#42c77b"; font.weight: Font.Bold; Layout.alignment: Qt.AlignRight }
            }
            Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: "#3a4147" }
            Label { text: "LOCAL & SPACE WEATHER"; color: "#e9a72b"; font.weight: Font.Bold }
            Label { Layout.fillWidth: true; text: "Provider truth is shown in Health. No weather, solar index, or path value is fabricated when its owner is unavailable."; color: "#aeb5ba"; wrapMode: Text.WordWrap }
            RowLayout {
                Layout.fillWidth: true
                StatusChip { text: Cluster.state; kind: Cluster.state.startsWith("Connected") ? "healthy" : "neutral" }
                StatusChip { text: Wavelog.state; kind: Wavelog.state === "Synchronized" ? "healthy" : "hold" }
            }
            Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: "#3a4147" }
            Label { text: "VISIBLE MODULES"; color: "#e9a72b"; font.weight: Font.Bold }
            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                model: Parity.homeModules
                clip: true
                delegate: CheckDelegate {
                    required property var item
                    width: ListView.view.width
                    height: 36
                    text: item.title
                    checked: item.enabled
                    onToggled: Parity.setHomeModuleVisible(item.key, checked)
                }
            }
        }
    }

    CanvasPanel {
        id: mapPanel
        panelKey: "operational-map"
        title: "Operational RF geography"
        defaultX: stationRail.defaultWidth + 12
        defaultY: 94
        defaultWidth: parent ? Math.max(500, (parent.width - 24) * 0.50) : 600
        defaultHeight: root.columnHeight
        panelMinimumWidth: 480
        ColumnLayout {
            anchors.fill: parent
            spacing: 8
            RowLayout {
                Layout.fillWidth: true
                StatusChip { text: RfObservations.count + " RF PATHS"; kind: RfObservations.count > 0 ? "healthy" : "neutral" }
                Label { text: RfObservations.filterSummary; color: "#aeb5ba"; Layout.fillWidth: true; elide: Text.ElideRight }
                Button { text: "Open Intelligence"; onClicked: Desktop.currentDestination = "Intelligence" }
                Button { text: "Shack"; onClicked: Desktop.invokeCommand("view.shack") }
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                color: "#101316"
                border.color: "#3a4147"
                RfMapScene {
                    id: map
                    anchors.fill: parent
                    model: RfObservations
                    projection: "Flat"
                    MouseArea {
                        anchors.fill: parent
                        property real startX
                        property real startY
                        property real startLon
                        property real startLat
                        onPressed: function(mouse) { startX = mouse.x; startY = mouse.y; startLon = map.longitude; startLat = map.latitude }
                        onPositionChanged: function(mouse) {
                            if (!pressed) return
                            map.longitude = startLon - (mouse.x - startX) / width * 180 / map.zoom
                            map.latitude = startLat + (mouse.y - startY) / height * 90 / map.zoom
                        }
                        onWheel: function(wheel) { map.zoom = Math.max(1, Math.min(8, map.zoom * (wheel.angleDelta.y > 0 ? 1.18 : 0.85))) }
                    }
                }
                Label {
                    anchors.centerIn: parent
                    visible: RfObservations.count === 0
                    text: "NO OBSERVED RF PATHS\nConnect or refresh an authorised provider explicitly."
                    color: "#aeb5ba"
                    horizontalAlignment: Text.AlignHCenter
                }
            }
            RowLayout {
                Layout.fillWidth: true
                Label { text: "LIVE"; color: "#42c77b"; font.weight: Font.Bold }
                Label { text: "HISTORICAL"; color: "#e9a72b"; font.weight: Font.Bold }
                Label { text: "OUTLOOK"; color: "#5ca6c8"; font.weight: Font.Bold }
                Item { Layout.fillWidth: true }
                Label { text: "Selection is observational until an explicit handoff."; color: "#929ba2" }
            }
        }
    }

    CanvasPanel {
        panelKey: "activity-rail"
        title: "DX · portable · satellite activity"
        defaultX: mapPanel.defaultX + mapPanel.defaultWidth + 12
        defaultY: 94
        defaultWidth: parent ? Math.max(280, parent.width - mapPanel.defaultX - mapPanel.defaultWidth - 12) : 304
        defaultHeight: root.columnHeight
        panelMinimumWidth: 270
        ColumnLayout {
            anchors.fill: parent
            spacing: 8
            RowLayout {
                Layout.fillWidth: true
                Label { text: "DX CLUSTER"; color: "#e9a72b"; font.weight: Font.Bold }
                Item { Layout.fillWidth: true }
                Label { text: Spots.count + " OBSERVED"; color: "#929ba2" }
            }
            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                model: Spots
                clip: true
                delegate: ItemDelegate {
                    required property string callsign
                    required property double frequencyHz
                    required property string mode
                    required property int ageSeconds
                    width: ListView.view.width
                    height: 48
                    contentItem: Column {
                        Label { text: callsign + "  " + (frequencyHz / 1000).toFixed(1) + " kHz"; color: "#f4f0e7"; font.family: "monospace"; font.weight: Font.Bold }
                        Label { text: mode + " · " + ageSeconds + " s · observation only"; color: "#929ba2"; font.pixelSize: 11 }
                    }
                    onClicked: Desktop.currentDestination = "DX"
                }
                footer: EmptyState {
                    visible: Spots.count === 0
                    width: ListView.view.width
                    title: "No observed DX spots"
                    detail: "The cluster controller is disconnected. No fixture is shown outside deterministic gallery mode."
                }
            }
            Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: "#3a4147" }
            Label { text: "NEXT SATELLITE"; color: "#e9a72b"; font.weight: Font.Bold }
            Label { Layout.fillWidth: true; text: Parity.satellitePasses.count > 0 ? Parity.satellitePasses.item(0).title : "No current local pass result"; color: "#f4f0e7"; wrapMode: Text.WordWrap }
            RowLayout {
                Layout.fillWidth: true
                Button { text: "Portable"; onClicked: Desktop.currentDestination = "Portable" }
                Button { text: "Operations"; onClicked: Desktop.currentDestination = "Operations" }
                Button { text: "DX"; onClicked: Desktop.currentDestination = "DX" }
            }
        }
    }
}
