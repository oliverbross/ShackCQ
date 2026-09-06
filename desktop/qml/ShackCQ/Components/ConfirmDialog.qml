import QtQuick
import QtQuick.Controls

Dialog {
    id: root
    property string message: "Confirm action"
    width: 480
    modal: true; standardButtons: Dialog.Ok | Dialog.Cancel
    contentItem: Label { width: 420; padding: 16; text: root.message; wrapMode: Text.WordWrap; color: "#f2efe7" }
}
