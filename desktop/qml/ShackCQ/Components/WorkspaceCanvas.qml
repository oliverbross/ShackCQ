import QtQuick

Item {
    id: root

    property string workspaceKey
    property int highestZ: 1
    readonly property bool editLayoutMode: Desktop.editLayoutMode
    readonly property int layoutVersion: 2
    readonly property int gridSize: 8
    signal resetRequested()

    clip: true
    Accessible.role: Accessible.Pane
    Accessible.name: workspaceKey + (editLayoutMode ? " layout editor" : " official workspace layout")

    function raisePanel(panel) {
        highestZ += 1
        panel.z = highestZ
    }

    function panelOverlaps(panel, geometry) {
        for (let index = 0; index < children.length; ++index) {
            const sibling = children[index]
            if (sibling === panel || !sibling.visible || !sibling.objectName
                    || !sibling.objectName.startsWith("canvasPanel-"))
                continue
            const separated = geometry.x + geometry.width + gridSize <= sibling.x
                    || sibling.x + sibling.width + gridSize <= geometry.x
                    || geometry.y + geometry.height + gridSize <= sibling.y
                    || sibling.y + sibling.height + gridSize <= geometry.y
            if (!separated)
                return true
        }
        return false
    }

    Rectangle {
        anchors.fill: parent
        color: "#15181b"
        z: -100
    }

    Canvas {
        anchors.fill: parent
        visible: root.editLayoutMode
        opacity: 0.28
        z: -90
        onPaint: {
            const painter = getContext("2d")
            painter.clearRect(0, 0, width, height)
            painter.strokeStyle = "#4a555d"
            painter.lineWidth = 1
            for (let x = root.gridSize; x < width; x += root.gridSize) {
                painter.beginPath(); painter.moveTo(x, 0); painter.lineTo(x, height); painter.stroke()
            }
            for (let y = root.gridSize; y < height; y += root.gridSize) {
                painter.beginPath(); painter.moveTo(0, y); painter.lineTo(width, y); painter.stroke()
            }
        }
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    Connections {
        target: Desktop
        function onWorkspaceLayoutReset(workspace) {
            if (workspace === root.workspaceKey)
                root.resetRequested()
        }
    }
}
