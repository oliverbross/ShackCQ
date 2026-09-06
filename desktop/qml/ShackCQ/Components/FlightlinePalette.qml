import QtQuick

QtObject {
    // Flightline surfaces and semantic colour roles.
    readonly property color graphite: "#171a1d"
    readonly property color graphiteDeep: "#15181b"
    readonly property color graphiteRaised: "#22272b"
    readonly property color graphiteHover: "#292f34"
    readonly property color amber: "#d38b22"
    readonly property color amberBright: "#d89631"
    readonly property color amberDark: "#4b351c"
    readonly property color primary: "#f2efe7"
    readonly property color secondary: "#e3c765"
    readonly property color healthy: "#4ec47b"
    readonly property color danger: "#e05252"
    readonly property color muted: "#aeb5ba"
    readonly property color subdued: "#929ba2"
    readonly property color divider: "#3a4147"
    readonly property color focus: "#f0ce68"

    // Compact desktop rhythm: dense enough for operation, large enough for touchpads.
    readonly property int spaceXs: 4
    readonly property int spaceSm: 8
    readonly property int spaceMd: 12
    readonly property int spaceLg: 16
    readonly property int spaceXl: 24
    readonly property int controlHeight: 36
    readonly property int tableRowHeight: 34
    readonly property int panelRadius: 4
    readonly property int lineWidth: 1
    readonly property int compactHeaderBreakpoint: 1360
    readonly property int minimumPaneWidth: 280
    readonly property int motionFastMs: 120
    readonly property int motionStandardMs: 180

    // Type roles use each platform's system UI face; measurement remains tabular.
    readonly property int titleSize: 24
    readonly property int sectionSize: 13
    readonly property int bodySize: 13
    readonly property int captionSize: 11
}
