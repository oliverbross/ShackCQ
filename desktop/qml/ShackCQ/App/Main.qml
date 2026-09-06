import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "Components"

ApplicationWindow {
    id: window
    FlightlinePalette { id: flightline }
    property bool shackMode: false
    property int galleryRadioBackend: 0
    readonly property bool isMac: Qt.platform.os === "osx"
    width: 1440
    height: 900
    minimumWidth: 1180
    minimumHeight: 720
    visible: true
    title: isMac ? "ShackCQ" : "ShackCQ Desktop — " + Desktop.currentDestination
    color: flightline.graphiteDeep
    palette.window: flightline.graphiteDeep
    palette.windowText: flightline.primary
    palette.base: flightline.graphite
    palette.alternateBase: flightline.graphiteRaised
    palette.text: flightline.primary
    palette.button: flightline.graphiteRaised
    palette.buttonText: flightline.primary
    palette.brightText: flightline.primary
    palette.highlight: flightline.amberDark
    palette.highlightedText: flightline.primary
    palette.placeholderText: flightline.muted
    palette.toolTipBase: flightline.graphiteRaised
    palette.toolTipText: flightline.primary
    palette.light: flightline.divider
    palette.midlight: flightline.divider
    palette.mid: flightline.divider
    palette.dark: flightline.graphiteDeep
    palette.shadow: "#000000"

    Instantiator {
        model: Desktop.commands
        delegate: Shortcut {
            required property var modelData
            sequence: modelData.shortcut || ""
            enabled: !window.isMac && modelData.enabled === true && sequence.length > 0 && modelData.id !== "radio.stop"
            context: Qt.ApplicationShortcut
            onActivated: Desktop.invokeCommand(modelData.id)
        }
    }
    Shortcut {
        sequence: "Escape"
        context: Qt.ApplicationShortcut
        onActivated: {
            Desktop.invokeCommand("radio.stop")
            Desktop.editLayoutMode = false
            window.shackMode = false
            commandPalette.close()
            workspaceLoader.forceActiveFocus()
        }
    }

    Connections {
        target: Desktop
        function onQuitRequested() { window.close() }
        function onCommandInvoked(commandId) {
            if (commandId === "tools.palette") {
                commandPalette.open()
                commandSearch.forceActiveFocus()
            } else if (commandId === "view.fullScreen") {
                window.visibility = window.visibility === Window.FullScreen ? Window.Windowed : Window.FullScreen
            } else if (commandId === "view.shack") {
                Desktop.editLayoutMode = false
                window.shackMode = !window.shackMode
            } else if (commandId === "view.resetLayout") {
                window.shackMode = false
            } else if (commandId === "file.close") {
                window.close()
            } else if (commandId === "help.licences") {
                Desktop.currentDestination = "About"
            } else if (commandId === "help.shortcuts") {
                shortcutHelp.open()
            } else if (["file.fastEntry", "file.importAdif", "file.exportAdif", "file.exportConfig"].includes(commandId)) {
                const target = commandId === "file.exportConfig" ? "Settings" : "Logbook"
                Desktop.currentDestination = target
                Qt.callLater(function() {
                    if (workspaceLoader.item && workspaceLoader.item.handleCommand)
                        workspaceLoader.item.handleCommand(commandId)
                })
            }
        }
    }

    Item {
        anchors.fill: parent
        RowLayout {
            anchors.fill: parent
            spacing: 0
            WorkspaceSidebar {
                id: workspaceSidebar
                visible: !window.shackMode
                Layout.fillHeight: true
                Layout.preferredWidth: implicitWidth
                autoCompact: window.width < 1320
            }
            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: 0
                WorkspaceHeader {
                    visible: !window.shackMode
                    Layout.fillWidth: true
                    compact: window.width - workspaceSidebar.implicitWidth < 1120
                    workspace: Desktop.currentDestination
                }
                Rectangle {
                    visible: Desktop.editLayoutMode && !window.shackMode
                    Layout.fillWidth: true
                    Layout.preferredHeight: visible ? 42 : 0
                    color: "#4b351c"
                    border.color: "#e9a72b"
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 8
                        Label { text: "EDIT LAYOUT"; color: "#f4c94e"; font.weight: Font.Bold }
                        Label { text: "8 px grid · overlap blocked · resize handles active · custom geometry saves proportionally"; color: "#f4f0e7"; Layout.fillWidth: true }
                        Button { text: "Reset Official Layout"; onClicked: Desktop.invokeCommand("view.resetLayout") }
                        Button { text: "Done Editing"; highlighted: true; onClicked: Desktop.editLayoutMode = false }
                    }
                }
                Rectangle {
                    visible: Notifications.bannerVisible
                    Layout.fillWidth: true
                    Layout.preferredHeight: visible ? 52 : 0
                    color: "#4b351c"
                    border.color: "#e9a72b"
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 8
                        Label { text: Notifications.bannerTitle; color: "#f4c94e"; font.weight: Font.Bold }
                        Label { text: Notifications.bannerBody; color: "#f4f0e7"; Layout.fillWidth: true; elide: Text.ElideRight }
                        Button { text: "Dismiss"; flat: true; onClicked: Notifications.clearBanner() }
                    }
                }
                Loader {
                    id: workspaceLoader
                    objectName: "workspaceLoader"
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    source: {
                        if (window.shackMode) return "Home/ShackDisplay.qml"
                        const map = {"Home":"Home/HomePage.qml","Radio":"Radio/RadioPage.qml","Digi":"Digi/DigiPage.qml","Panadapter":"Panadapter/PanadapterPage.qml","EQ":"EQ/EqPage.qml","Logbook":"Logbook/LogbookPage.qml","Intelligence":"Intelligence/IntelligencePage.qml","Sync":"Sync/SyncPage.qml","Contest":"Contest/ContestPage.qml","Band Maps":"qrc:/ShackCQ/App/BandMaps/BandMapsPage.qml","Presets":"Presets/PresetsPage.qml","DX":"DX/DxPage.qml","Portable":"Portable/PortablePage.qml","Operations":"Operations/OperationsPage.qml","Groups.io":"Groups/GroupsPage.qml","Rotator":"Rotator/RotatorPage.qml","Settings":"Settings/SettingsPage.qml","Health":"Health/HealthPage.qml","About":"Settings/AboutPage.qml"}
                        return map[Desktop.currentDestination]
                    }
                }
            }
        }
    }

    Popup {
        id: commandPalette
        anchors.centerIn: Overlay.overlay
        width: Math.min(680, window.width - 80)
        height: Math.min(520, window.height - 100)
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        Accessible.name: "Command palette"
        background: Rectangle { color: "#20252a"; border.color: flightline.amberBright; border.width: 1; radius: 6 }
        contentItem: ColumnLayout {
            spacing: 8
            TextField { id: commandSearch; Layout.fillWidth: true; placeholderText: "Find a workspace or command"; Accessible.name: "Command search" }
            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                model: Desktop.commands.filter(function(command) {
                    return command.enabled && (commandSearch.text.length === 0 || (command.label + " " + command.category).toLowerCase().includes(commandSearch.text.toLowerCase()))
                })
                delegate: ItemDelegate {
                    required property var modelData
                    width: ListView.view.width
                    height: 44
                    onClicked: { Desktop.invokeCommand(modelData.id); commandPalette.close() }
                    contentItem: RowLayout {
                        FlightlineIcon { name: modelData.icon; color: flightline.amberBright; Layout.preferredWidth: 20; Layout.preferredHeight: 20 }
                        Label { text: modelData.label; color: "#f2efe7"; Layout.fillWidth: true }
                        Label { text: modelData.category; color: flightline.subdued; font.pixelSize: 11 }
                        Label { text: modelData.shortcut || ""; color: "#b7bec3"; font.pixelSize: 11 }
                    }
                }
            }
        }
    }

    Dialog {
        id: shortcutHelp
        title: "Keyboard shortcuts"
        modal: true
        standardButtons: Dialog.Close
        width: Math.min(620, window.width - 80)
        contentItem: ListView {
            implicitHeight: 420
            model: Desktop.commands.filter(function(command) { return command.shortcut && command.enabled })
            delegate: RowLayout {
                required property var modelData
                width: ListView.view.width
                height: 34
                Label { text: modelData.label; color: "#f2efe7"; Layout.fillWidth: true }
                Label { text: modelData.shortcut; color: "#e3c765" }
            }
        }
    }

    onClosing: function(close) { Desktop.shutdown(); close.accepted = true }
}
