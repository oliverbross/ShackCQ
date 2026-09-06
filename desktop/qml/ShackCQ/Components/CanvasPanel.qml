import QtQuick
import QtQuick.Controls

Item {
    id: root

    required property string panelKey
    required property string title
    property real defaultX: 0
    property real defaultY: 0
    property real defaultWidth: 420
    property real defaultHeight: 280
    property real panelMinimumWidth: 240
    property real panelMinimumHeight: 150
    property bool geometryReady: false
    property bool usingSavedGeometry: false
    property bool applyingGeometry: false
    property rect intendedGeometry: Qt.rect(defaultX, defaultY, defaultWidth, defaultHeight)
    property point dragOrigin
    property rect dragGeometry
    property rect savedRatios: Qt.rect(0, 0, 1, 1)
    property bool usingRatioGeometry: false
    readonly property var canvas: parent
    readonly property string workspaceKey: canvas ? canvas.workspaceKey : ""
    readonly property bool editable: canvas ? canvas.editLayoutMode : false
    default property alias contentData: contentHost.data

    objectName: "canvasPanel-" + panelKey
    x: defaultX
    y: defaultY
    width: defaultWidth
    height: defaultHeight
    z: 1
    Accessible.role: Accessible.Pane
    Accessible.name: title + (editable ? " editable panel" : " panel")
    Accessible.description: editable ? "Drag the title bar to move. Drag an edge or corner to resize. Press Escape or Done Editing to lock the workspace." : "Locked operational region. Use Edit Layout to customise its bounded geometry."

    function bounded(value, lower, upper) {
        return Math.max(lower, Math.min(value, upper))
    }

    function applyGeometry(nextX, nextY, nextWidth, nextHeight) {
        if (!parent)
            return
        applyingGeometry = true
        const availableWidth = Math.max(panelMinimumWidth, parent.width)
        const availableHeight = Math.max(panelMinimumHeight, parent.height)
        width = bounded(nextWidth, Math.min(panelMinimumWidth, availableWidth), availableWidth)
        height = bounded(nextHeight, Math.min(panelMinimumHeight, availableHeight), availableHeight)
        x = bounded(nextX, 0, Math.max(0, parent.width - width))
        y = bounded(nextY, 0, Math.max(0, parent.height - height))
        applyingGeometry = false
    }

    function applyUserGeometry(nextX, nextY, nextWidth, nextHeight) {
        if (!editable)
            return
        const grid = canvas ? canvas.gridSize : 8
        const proposed = Qt.rect(Math.round(nextX / grid) * grid,
                                 Math.round(nextY / grid) * grid,
                                 Math.max(panelMinimumWidth, Math.round(nextWidth / grid) * grid),
                                 Math.max(panelMinimumHeight, Math.round(nextHeight / grid) * grid))
        if (canvas && canvas.panelOverlaps(root, proposed))
            return
        applyGeometry(proposed.x, proposed.y, proposed.width, proposed.height)
        intendedGeometry = Qt.rect(x, y, width, height)
        usingRatioGeometry = true
        savedRatios = Qt.rect(parent && parent.width ? x / parent.width : 0,
                              parent && parent.height ? y / parent.height : 0,
                              parent && parent.width ? width / parent.width : 1,
                              parent && parent.height ? height / parent.height : 1)
    }

    function restoreDefaults() {
        geometryReady = false
        usingSavedGeometry = false
        usingRatioGeometry = false
        intendedGeometry = Qt.rect(defaultX, defaultY, defaultWidth, defaultHeight)
        applyGeometry(intendedGeometry.x, intendedGeometry.y,
                      intendedGeometry.width, intendedGeometry.height)
        geometryReady = true
    }

    function restoreSavedGeometry() {
        const fallback = {"x": defaultX, "y": defaultY,
                          "width": defaultWidth, "height": defaultHeight}
        const saved = Desktop.panelGeometry(workspaceKey, panelKey, fallback)
        geometryReady = false
        usingSavedGeometry = saved.stored === true
        usingRatioGeometry = usingSavedGeometry && saved.layoutVersion === 2
        savedRatios = usingRatioGeometry
                ? Qt.rect(saved.xRatio, saved.yRatio, saved.widthRatio, saved.heightRatio)
                : Qt.rect(0, 0, 1, 1)
        intendedGeometry = usingRatioGeometry && parent
                ? Qt.rect(savedRatios.x * parent.width, savedRatios.y * parent.height,
                          savedRatios.width * parent.width, savedRatios.height * parent.height)
                : usingSavedGeometry
                  ? Qt.rect(saved.x, saved.y, saved.width, saved.height)
                  : Qt.rect(defaultX, defaultY, defaultWidth, defaultHeight)
        applyGeometry(intendedGeometry.x, intendedGeometry.y,
                      intendedGeometry.width, intendedGeometry.height)
        geometryReady = true
    }

    function raisePanel() {
        if (canvas)
            canvas.raisePanel(root)
        forceActiveFocus()
    }

    function schedulePersist() {
        if (geometryReady && usingSavedGeometry && !applyingGeometry)
            persistTimer.restart()
    }

    function persistGeometry() {
        if (!geometryReady || !usingSavedGeometry || workspaceKey.length === 0)
            return
        intendedGeometry = Qt.rect(x, y, width, height)
        savedRatios = Qt.rect(parent && parent.width ? x / parent.width : 0,
                              parent && parent.height ? y / parent.height : 0,
                              parent && parent.width ? width / parent.width : 1,
                              parent && parent.height ? height / parent.height : 1)
        Desktop.savePanelGeometry(workspaceKey, panelKey,
                                  {"x": Math.round(x), "y": Math.round(y),
                                   "width": Math.round(width), "height": Math.round(height),
                                   "layoutVersion": 2,
                                   "xRatio": savedRatios.x, "yRatio": savedRatios.y,
                                   "widthRatio": savedRatios.width,
                                   "heightRatio": savedRatios.height})
    }

    function beginPointer(handle, mouse) {
        if (!editable)
            return
        usingSavedGeometry = true
        const scenePoint = handle.mapToItem(root.parent, mouse.x, mouse.y)
        dragOrigin = Qt.point(scenePoint.x, scenePoint.y)
        dragGeometry = Qt.rect(x, y, width, height)
        raisePanel()
    }

    function resizeFrom(handle, mouse, edges) {
        const scenePoint = handle.mapToItem(root.parent, mouse.x, mouse.y)
        const dx = scenePoint.x - dragOrigin.x
        const dy = scenePoint.y - dragOrigin.y
        let nextX = dragGeometry.x
        let nextY = dragGeometry.y
        let nextWidth = dragGeometry.width
        let nextHeight = dragGeometry.height
        if (edges.left) {
            nextX += dx
            nextWidth -= dx
            if (nextWidth < panelMinimumWidth) {
                nextX = dragGeometry.x + dragGeometry.width - panelMinimumWidth
                nextWidth = panelMinimumWidth
            }
        }
        if (edges.right)
            nextWidth += dx
        if (edges.top) {
            nextY += dy
            nextHeight -= dy
            if (nextHeight < panelMinimumHeight) {
                nextY = dragGeometry.y + dragGeometry.height - panelMinimumHeight
                nextHeight = panelMinimumHeight
            }
        }
        if (edges.bottom)
            nextHeight += dy
        applyUserGeometry(nextX, nextY, nextWidth, nextHeight)
    }

    Component.onCompleted: restoreSavedGeometry()
    onXChanged: schedulePersist()
    onYChanged: schedulePersist()
    onWidthChanged: schedulePersist()
    onHeightChanged: schedulePersist()
    onDefaultXChanged: if (geometryReady && !usingSavedGeometry) restoreDefaults()
    onDefaultYChanged: if (geometryReady && !usingSavedGeometry) restoreDefaults()
    onDefaultWidthChanged: if (geometryReady && !usingSavedGeometry) restoreDefaults()
    onDefaultHeightChanged: if (geometryReady && !usingSavedGeometry) restoreDefaults()

    Connections {
        target: root.canvas
        function onResetRequested() { root.restoreDefaults() }
    }

    Connections {
        target: root.parent
        function onWidthChanged() {
            if (root.usingRatioGeometry)
                root.applyGeometry(root.savedRatios.x * root.parent.width,
                                   root.savedRatios.y * root.parent.height,
                                   root.savedRatios.width * root.parent.width,
                                   root.savedRatios.height * root.parent.height)
            else if (root.usingSavedGeometry)
                root.applyGeometry(root.intendedGeometry.x, root.intendedGeometry.y,
                                   root.intendedGeometry.width, root.intendedGeometry.height)
            else
                root.restoreDefaults()
        }
        function onHeightChanged() {
            if (root.usingRatioGeometry)
                root.applyGeometry(root.savedRatios.x * root.parent.width,
                                   root.savedRatios.y * root.parent.height,
                                   root.savedRatios.width * root.parent.width,
                                   root.savedRatios.height * root.parent.height)
            else if (root.usingSavedGeometry)
                root.applyGeometry(root.intendedGeometry.x, root.intendedGeometry.y,
                                   root.intendedGeometry.width, root.intendedGeometry.height)
            else
                root.restoreDefaults()
        }
    }

    Timer {
        id: persistTimer
        interval: 240
        repeat: false
        onTriggered: root.persistGeometry()
    }

    Rectangle {
        anchors.fill: parent
        radius: 5
        color: "#22272b"
        border.width: root.editable && root.activeFocus ? 2 : 1
        border.color: root.editable && root.activeFocus ? "#e3c765" : "#3a4147"
    }

    Rectangle {
        id: titleBar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 34
        radius: 5
        color: root.editable && (headerDrag.containsMouse || root.activeFocus) ? "#34302a" : "#292f34"
        border.color: root.editable && root.activeFocus ? "#d38b22" : "#3a4147"

        Label {
            anchors.left: parent.left
            anchors.leftMargin: 12
            anchors.right: resetButton.left
            anchors.rightMargin: 8
            anchors.verticalCenter: parent.verticalCenter
            text: root.title
            color: "#f2efe7"
            font.pixelSize: 12
            font.weight: Font.DemiBold
            elide: Text.ElideRight
        }

        MouseArea {
            id: headerDrag
            anchors.fill: parent
            enabled: root.editable
            acceptedButtons: Qt.LeftButton | Qt.RightButton
            cursorShape: root.editable ? Qt.SizeAllCursor : Qt.ArrowCursor
            hoverEnabled: true
            onPressed: root.beginPointer(headerDrag, mouse)
            onPositionChanged: {
                if (!pressed)
                    return
                const point = mapToItem(root.parent, mouse.x, mouse.y)
                root.applyUserGeometry(root.dragGeometry.x + point.x - root.dragOrigin.x,
                                       root.dragGeometry.y + point.y - root.dragOrigin.y,
                                       root.dragGeometry.width, root.dragGeometry.height)
            }
            onReleased: root.persistGeometry()
            onClicked: function(mouse) {
                if (root.editable && mouse.button === Qt.RightButton)
                    panelMenu.popup()
            }
        }

        ToolButton {
            id: resetButton
            visible: root.editable
            anchors.right: parent.right
            anchors.rightMargin: 4
            anchors.verticalCenter: parent.verticalCenter
            width: 28
            height: 28
            z: 2
            Accessible.name: "Restore " + root.title + " panel"
            ToolTip.visible: hovered
            ToolTip.text: Accessible.name
            onClicked: {
                root.raisePanel()
                root.restoreDefaults()
                root.usingSavedGeometry = true
                root.persistGeometry()
            }
            background: Rectangle {
                radius: 4
                color: resetButton.down ? "#4b351c" : resetButton.hovered ? "#34302a" : "transparent"
                border.width: resetButton.activeFocus ? 1 : 0
                border.color: "#f0ce68"
            }
            contentItem: FlightlineIcon {
                name: "reset"
                color: parent.hovered ? "#f2efe7" : "#aeb5ba"
            }
        }
    }

    Menu {
        id: panelMenu
        MenuItem { text: "Restore official panel position"; onTriggered: { root.restoreDefaults(); root.usingSavedGeometry = true; root.persistGeometry() } }
        MenuItem { text: "Bring Forward"; onTriggered: root.raisePanel() }
        MenuItem { text: "Send Back"; onTriggered: root.z = 1 }
    }

    Item {
        id: contentHost
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: titleBar.bottom
        anchors.bottom: parent.bottom
        anchors.margins: 10
        clip: true
    }

    Repeater {
        model: root.editable ? [
            {"left": true,  "top": false, "right": false, "bottom": false, "cursor": Qt.SizeHorCursor},
            {"left": false, "top": false, "right": true,  "bottom": false, "cursor": Qt.SizeHorCursor},
            {"left": false, "top": true,  "right": false, "bottom": false, "cursor": Qt.SizeVerCursor},
            {"left": false, "top": false, "right": false, "bottom": true,  "cursor": Qt.SizeVerCursor},
            {"left": true,  "top": true,  "right": false, "bottom": false, "cursor": Qt.SizeFDiagCursor},
            {"left": false, "top": true,  "right": true,  "bottom": false, "cursor": Qt.SizeBDiagCursor},
            {"left": true,  "top": false, "right": false, "bottom": true,  "cursor": Qt.SizeBDiagCursor},
            {"left": false, "top": false, "right": true,  "bottom": true,  "cursor": Qt.SizeFDiagCursor}
        ] : []
        delegate: MouseArea {
            required property var modelData
            z: 30
            hoverEnabled: true
            cursorShape: modelData.cursor
            x: modelData.left ? -4 : modelData.right ? root.width - width + 4 : 10
            y: modelData.top ? -4 : modelData.bottom ? root.height - height + 4 : 10
            width: modelData.left || modelData.right ? 10 : root.width - 20
            height: modelData.top || modelData.bottom ? 10 : root.height - 20
            onPressed: root.beginPointer(this, mouse)
            onPositionChanged: {
                if (pressed)
                    root.resizeFrom(this, mouse, modelData)
            }
            onReleased: root.persistGeometry()
        }
    }
}
