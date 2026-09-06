import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

Item{property var copy:({
    "Digi":{done:"Exact-route receive session and linked native modem",gap:"Real transmit remains acceptance-gated."},
    "Contest":{done:"Contest session, scoring and bounded packet policy",gap:"Live trusted N1MM peer acceptance is pending."},
    "Groups.io":{done:"Vault-bound client, offline archive and reviewed outbox",gap:"Authenticated account acceptance is pending."},
    "Portable":{done:"Cached POTA/SOTA/IOTA/WWFF provider and activity owner",gap:"Live provider acceptance is pending."},
    "Operations":{done:"Cached planner, spatial scope and receive-only satellite handoff",gap:"Live provider acceptance is pending."},
    "Satellite/QO-100":{done:"Shared native SGP4 API is available to the desktop build",gap:"Next-pass UI proof and live TLE acceptance remain pending."}
})[Desktop.currentDestination]||{done:"Source-complete destination registered",gap:"External acceptance remains explicit."}
    ColumnLayout{anchors.centerIn:parent;width:720;spacing:16
        Label{text:Desktop.currentDestination;color:"#f2efe7";font.pixelSize:30;font.bold:true;Layout.alignment:Qt.AlignHCenter}
        StatusChip{text:"SOURCE COMPLETE";kind:"ok";Layout.alignment:Qt.AlignHCenter}
        SafetyBanner{Layout.fillWidth:true;text:copy.done}
        EmptyState{Layout.fillWidth:true;title:"Platform gap";detail:copy.gap}
        Label{Layout.fillWidth:true;text:"This page is intentionally truthful: it does not fabricate connected providers, live data, scoring, transmit authority, completed parity, or hardware evidence.";color:"#98a0a6";wrapMode:Text.WordWrap;horizontalAlignment:Text.AlignHCenter}
    }
}
