import QtQuick
import QtQuick.Effects

Item {
    id: root
    property string name: "home"
    property color color: "#b7bec3"
    implicitWidth: 22
    implicitHeight: 22
    Accessible.ignored: true

    Image {
        id: sourceIcon
        anchors.fill: parent
        source: "qrc:/ShackCQ/App/Icons/" + root.name + ".svg"
        sourceSize.width: Math.round(root.width * Screen.devicePixelRatio)
        sourceSize.height: Math.round(root.height * Screen.devicePixelRatio)
        fillMode: Image.PreserveAspectFit
        smooth: true
    }
    MultiEffect {
        anchors.fill: parent
        source: sourceIcon
        colorization: 1.0
        colorizationColor: root.color
    }
}
