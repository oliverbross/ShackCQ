import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Pane {
    property alias title: heading.text
    padding: 14
    background: Rectangle { color: "#22272b"; border.color: "#3a4147"; radius: 4 }
    contentItem: ColumnLayout { spacing: 10
        Label { id: heading; color: "#d38b22"; font.pixelSize: 16; font.weight: Font.Bold }
    }
}
