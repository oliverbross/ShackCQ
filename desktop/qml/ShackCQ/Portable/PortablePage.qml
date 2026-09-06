import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    workspaceKey:"Portable"
    CanvasPanel { panelKey:"safety"; title:"Portable safety state"; defaultWidth:parent?parent.width:1200; defaultHeight:96
        SafetyBanner { anchors.fill:parent; text:"Portable providers are receive-only and truth-labelled. Logger handoff opens a review; programme spots never operate CAT or create a QSO." }
    }
    CanvasPanel { panelKey:"filters"; title:"Portable filters"; defaultY:108; defaultWidth:parent?parent.width:1200; defaultHeight:96; panelMinimumHeight:90
        RowLayout { anchors.fill:parent
            ComboBox { model:["All programmes","POTA","SOTA","WWFF","IOTA","WWBOTA","Castles / Lighthouses"] }
            TextField { placeholderText:"Reference, name, entity, location"; Layout.fillWidth:true }
            Button { text:"Refresh visible providers"; enabled:false; ToolTip.text:"Enable configured providers in Settings"; ToolTip.visible:hovered }
            StatusChip { text:"MAPLESS MODE"; kind:"neutral" }
        }
    }
    CanvasPanel { panelKey:"activity"; title:"Portable activity"; defaultY:216; defaultWidth:parent?parent.width-392:800; defaultHeight:parent?parent.height-216:400
        WorkspaceList { anchors.fill:parent; sourceModel:Parity.portableActivity; actionText:"Logger review"; emptyTitle:"No portable activity"; emptyDetail:"Provider caches are empty or disabled; no page is scraped."; onActionRequested:item=>Parity.prepareReceiveReview("Portable logger handoff",item) }
    }
    CanvasPanel { panelKey:"provider-truth"; title:"Provider / map truth"; defaultX:parent?parent.width-380:812; defaultY:216; defaultWidth:380; defaultHeight:parent?parent.height-216:400
        Label { anchors.fill:parent; text:"Qt Location is not configured in this build. The explicit low-data mapless mode preserves anchored reference detail and official HTTPS links without embedding a browser."; color:"#98a0a6"; wrapMode:Text.WordWrap }
    }
}
