import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id:root
    workspaceKey:"Shack"
    property date now:new Date()
    Timer{interval:1000;running:true;repeat:true;onTriggered:root.now=new Date()}

    CanvasPanel {
        panelKey:"clock-controls"
        title:"Shack clock and safety"
        defaultWidth:parent?parent.width:1200
        defaultHeight:132
        RowLayout { anchors.fill:parent
            Label{text:Qt.formatDateTime(root.now,"HH:mm:ss");color:"#f2efe7";font.pixelSize:54;font.bold:true}
            Label{text:"UTC";color:"#d38b22";font.pixelSize:22}
            Item{Layout.fillWidth:true}
            StatusChip{text:Parity.safetyState;kind:Parity.safetyState.includes("STOPPED")?"hold":"neutral"}
            Button{text:"GLOBAL STOP";palette.button:"#8c2525";palette.buttonText:"white";font.weight:Font.Bold;onClicked:Desktop.invokeCommand("radio.stop");Accessible.name:"Global Stop"}
            Button{text:"Exit Shack";onClicked:if(ApplicationWindow.window)ApplicationWindow.window.shackMode=false}
        }
    }

    Repeater {
        model:[
            {key:"station",label:"Station",value:Desktop.demoMode?"GALLERY FIXTURE":"LOCAL",truth:Desktop.demoMode?"Private deterministic profile":"Private station configuration"},
            {key:"radio",label:"Radio",value:Radio.state.startsWith("Connected")?"CONNECTED":"DISCONNECTED",truth:Radio.state},
            {key:"dx",label:"DX observations",value:Spots.count,truth:"One repository"},
            {key:"neural",label:"Neural opportunity",value:Parity.neuralOpportunities.count>0?Parity.neuralOpportunities.item(0).title:"—",truth:"Empirical evidence / no CAT"},
            {key:"pass",label:"Next pass",value:Parity.satellitePasses.count>0?Parity.satellitePasses.item(0).title:"—",truth:"Local SGP4 receive preview"},
            {key:"portable",label:"Portable",value:Parity.portableActivity.count,truth:"Provider cache"}
        ]
        delegate:CanvasPanel {
            required property int index
            required property var modelData
            panelKey:"metric-"+modelData.key
            title:modelData.label
            defaultX:parent?(index%3)*(parent.width+12)/3:0
            defaultY:144+Math.floor(index/3)*160
            defaultWidth:parent?(parent.width-24)/3:380
            defaultHeight:148
            panelMinimumWidth:220
            panelMinimumHeight:130
            ColumnLayout { anchors.fill:parent
                Label{text:modelData.value;color:"#f2efe7";font.pixelSize:24;font.bold:true;Layout.fillWidth:true;elide:Text.ElideRight}
                Label{text:modelData.truth;color:"#98a0a6";wrapMode:Text.WordWrap;Layout.fillWidth:true}
                Item{Layout.fillHeight:true}
            }
        }
    }

    CanvasPanel {
        panelKey:"dx-watch"
        title:"DX / propagation watch"
        defaultY:476
        defaultWidth:parent?(parent.width-12)*0.68:800
        defaultHeight:parent?parent.height-476:190
        WorkspaceList{anchors.fill:parent;sourceModel:Parity.neuralOpportunities;actionsVisible:false}
    }
    CanvasPanel {
        panelKey:"alerts"
        title:"Alerts and health"
        defaultX:parent?(parent.width+12)*0.68:812
        defaultY:476
        defaultWidth:parent?(parent.width-12)*0.32:380
        defaultHeight:parent?parent.height-476:190
        Label{text:"Review System Health for owner-reported state\nProvider caches isolated\nTX controls intentionally absent";color:"#aeb5ba";wrapMode:Text.WordWrap;anchors.fill:parent}
    }
}
