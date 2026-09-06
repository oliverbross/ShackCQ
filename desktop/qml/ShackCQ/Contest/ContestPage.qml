import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id: root
    workspaceKey: "Contest"

    CanvasPanel {
        panelKey: "safety"
        title: "Contest safety state"
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: 96
        SafetyBanner { anchors.fill: parent; text: "Contest restore is inactive. N1MM peers, network packets and F-keys cannot operate radio, Digi or Keyer without the explicit foreground workflow." }
    }

    CanvasPanel {
        panelKey: "session"
        title: "Contest session and keyer"
        defaultY: 108
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: 142
        ColumnLayout { anchors.fill: parent
            RowLayout { Layout.fillWidth: true
                ComboBox { id: contestDefinition; model: Parity.contestDefinitions; textRole: "title"; valueRole: "key"; Layout.preferredWidth: 230 }
                TextField { id: stationProfile; placeholderText: "Station profile ID"; text: "desktop-default"; Layout.preferredWidth: 160 }
                TextField { id: qsoEntry; placeholderText: "CALL BAND MODE"; Layout.fillWidth: true }
                Button { text: Parity.contestState.startsWith("ACTIVE") ? "Session active" : "Start session"; enabled: !Parity.contestState.startsWith("ACTIVE"); onClicked: Parity.startContest(contestDefinition.currentValue, stationProfile.text) }
                Button { text: "Stage QSO"; enabled: Parity.contestState.startsWith("ACTIVE") && qsoEntry.text.trim().split(/\s+/).length >= 3; onClicked: { const fields=qsoEntry.text.trim().split(/\s+/); if (Parity.stageContestQso({callsign:fields[0],band:fields[1],mode:fields[2],points:1})) qsoEntry.clear() } }
                Button { text: "Global STOP"; onClicked: Desktop.globalStop() }
            }
            RowLayout { Layout.fillWidth: true
                Repeater { model: Parity.keyerMacros; Button { required property var item; text: item.title; onClicked: Keyer.previewMacro(item.key,{MYCALL:"OM0RX"}); ToolTip.text: "Local preview only; transmit acceptance remains pending"; ToolTip.visible: hovered } }
                Item { Layout.fillWidth: true }
                StatusChip { text: Parity.n1mmState; kind: "hold" }
            }
        }
    }

    CanvasPanel {
        panelKey: "staging-log"
        title: "Contest staging log"
        defaultY: 262
        defaultWidth: parent ? parent.width - 322 : 850
        defaultHeight: parent ? parent.height - 262 : 360
        WorkspaceList { anchors.fill: parent; sourceModel: Parity.contestLog; actionText: "Review"; emptyTitle: "Staging log is empty"; emptyDetail: "Start a session and stage QSOs before an explicit canonical merge."; onActionRequested: item => Parity.prepareContestMerge({id: item.key}) }
    }

    CanvasPanel {
        panelKey: "score"
        title: "Score and rate"
        defaultX: parent ? parent.width - 310 : 862
        defaultY: 262
        defaultWidth: 310
        defaultHeight: parent ? parent.height - 262 : 360
        ColumnLayout { anchors.fill: parent
            MetricTile { label: "QSOs"; value: Parity.contestScore().qsos; truth: "Temporary schema-2 staging log" }
            MetricTile { label: "Score"; value: Parity.contestScore().score; truth: "Session score from staged QSO owner" }
            RowLayout { Layout.fillWidth: true
                TextField { id: scpQuery; placeholderText: "SCP prefix"; maximumLength: 16; Layout.fillWidth: true }
                Button { text: "Refresh SCP"; enabled: !Parity.scpState.startsWith("REFRESHING"); onClicked: Parity.refreshScp() }
            }
            Label {
                readonly property var result: scpQuery.text.length >= 3 ? Parity.scpLookup(scpQuery.text, 6) : ({suggestions:[]})
                text: Parity.scpState + (result.suggestions.length ? " · " + result.suggestions.join(", ") : "")
                color: "#98a0a6"; wrapMode: Text.WordWrap; Layout.fillWidth: true
            }
            Item { Layout.fillHeight: true }
        }
    }
}
