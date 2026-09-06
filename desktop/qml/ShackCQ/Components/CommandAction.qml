import QtQuick
import QtQuick.Controls

Action {
    id: root
    required property string commandId
    readonly property var commandData: Desktop.commands.find(function(command) { return command.id === root.commandId }) || ({})
    text: commandData.label || commandId
    shortcut: commandData.shortcut || ""
    enabled: commandData.enabled === true
    onTriggered: Desktop.invokeCommand(commandId)
}
