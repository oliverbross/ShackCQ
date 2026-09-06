import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    workspaceKey:"Operations"

    CanvasPanel {
        panelKey:"safety"
        title:"Operations safety state"
        defaultWidth:parent?parent.width:1200
        defaultHeight:96
        SafetyBanner { anchors.fill:parent; text:"All satellite positions and passes are calculated locally. Selection is receive preview only; no automatic Doppler follow, TX, logging or rotator movement." }
    }

    CanvasPanel {
        panelKey:"mode"
        title:"Operations mode"
        defaultY:108
        defaultWidth:parent?parent.width:1200
        defaultHeight:86
        panelMinimumHeight:80
        TabBar { id:tabs; objectName:"operationsTabs"; anchors.fill:parent
            TabButton{text:"Planner & calendars"}
            TabButton{text:"Satellites"}
            TabButton{text:"QO-100"}
        }
    }

    CanvasPanel {
        panelKey:"dx-calendar"
        title:"DX calendar"
        visible:tabs.currentIndex===0
        defaultY:206
        defaultWidth:parent?(parent.width-24)/3:380
        defaultHeight:parent?parent.height-206:410
        ColumnLayout { anchors.fill:parent
            Label { text:Parity.operationsRows.item(0).state; color:"#f4c94e"; font.weight:Font.Bold }
            Label { Layout.fillWidth:true; text:Parity.operationsRows.item(0).detail; color:"#98a0a6"; wrapMode:Text.WordWrap }
            Item { Layout.fillHeight:true }
            Button { text:"Review provider scope"; onClicked:Parity.prepareReceiveReview("DX Calendar",Parity.operationsRows.item(0)) }
        }
    }
    CanvasPanel {
        panelKey:"contest-calendar"
        title:"Contest calendar"
        visible:tabs.currentIndex===0
        defaultX:parent?(parent.width+12)/3:392
        defaultY:206
        defaultWidth:parent?(parent.width-24)/3:380
        defaultHeight:parent?parent.height-206:410
        ColumnLayout { anchors.fill:parent
            Label { text:Parity.operationsRows.item(1).state; color:"#42c77b"; font.weight:Font.Bold }
            Label { Layout.fillWidth:true; text:Parity.operationsRows.item(1).detail; color:"#98a0a6"; wrapMode:Text.WordWrap }
            Item { Layout.fillHeight:true }
            Button { text:"Review definitions"; onClicked:Parity.prepareReceiveReview("Contest Calendar",Parity.operationsRows.item(1)) }
        }
    }
    CanvasPanel {
        panelKey:"activation-planner"
        title:"Activation planner"
        visible:tabs.currentIndex===0
        defaultX:parent?2*(parent.width+12)/3:784
        defaultY:206
        defaultWidth:parent?(parent.width-24)/3:380
        defaultHeight:parent?parent.height-206:410
        ColumnLayout { anchors.fill:parent
            Label { text:Parity.operationsRows.item(2).state; color:"#f4c94e"; font.weight:Font.Bold }
            Label { Layout.fillWidth:true; text:Parity.operationsRows.item(2).detail; color:"#98a0a6"; wrapMode:Text.WordWrap }
            Item { Layout.fillHeight:true }
            Button { text:"Planner review"; onClicked:Parity.prepareReceiveReview("Activation Planner",Parity.operationsRows.item(2)) }
        }
    }

    CanvasPanel {
        panelKey:"satellite-passes"
        title:"Satellite passes"
        visible:tabs.currentIndex===1
        defaultY:206
        defaultWidth:parent?parent.width:1200
        defaultHeight:parent?parent.height-206:410
        ColumnLayout { anchors.fill:parent
            RowLayout { Layout.fillWidth:true
                Label { text:"Local SGP4 · supplied element set"; color:"#98a0a6"; Layout.fillWidth:true }
                Button { text:"Calculate passes…"; onClicked:passCalculator.open() }
            }
            WorkspaceList { Layout.fillWidth:true; Layout.fillHeight:true; sourceModel:Parity.satellitePasses; actionText:"RX preview"; emptyTitle:"No current element catalogue"; emptyDetail:"Supply a reviewed TLE and observer position; local SGP4 remains the prediction authority."; onActionRequested:item=>Parity.selectSatellitePass(item) }
        }
    }

    CanvasPanel {
        panelKey:"qo100"
        title:"QO-100 receive guidance"
        visible:tabs.currentIndex===2
        defaultY:206
        defaultWidth:parent?parent.width:1200
        defaultHeight:parent?parent.height-206:410
        ColumnLayout { anchors.fill:parent
            MetricTile { label:"Pointing"; value:"—"; truth:"Observer profile required" }
            Label { text:"Fixed azimuth/elevation, band plans, receive guidance and official links are available after an observer profile is selected. QO-100 never arms TX or moves a rotator."; color:"#98a0a6"; wrapMode:Text.WordWrap; Layout.fillWidth:true }
            Button { text:"Open receive guidance"; onClicked:Parity.prepareReceiveReview("QO-100",{title:"Fixed pointing guidance"}) }
            Item { Layout.fillHeight:true }
        }
    }

    Dialog { id:passCalculator; title:"Calculate local satellite passes"; modal:true; standardButtons:Dialog.Cancel|Dialog.Ok; width:720
        ColumnLayout { anchors.fill:parent
            TextField { id:satName; placeholderText:"Satellite name"; Layout.fillWidth:true }
            TextField { id:tle1; placeholderText:"TLE line 1"; Layout.fillWidth:true; font.family:"monospace" }
            TextField { id:tle2; placeholderText:"TLE line 2"; Layout.fillWidth:true; font.family:"monospace" }
            RowLayout { Layout.fillWidth:true
                TextField { id:observerLat; placeholderText:"Latitude"; validator:DoubleValidator{bottom:-90;top:90} Layout.fillWidth:true }
                TextField { id:observerLon; placeholderText:"Longitude"; validator:DoubleValidator{bottom:-180;top:180} Layout.fillWidth:true }
                TextField { id:observerAlt; placeholderText:"Altitude km"; text:"0"; validator:DoubleValidator{bottom:-1;top:20} Layout.fillWidth:true }
            }
            Label { text:"Calculation is receive-only. It cannot enable Doppler control, TX or rotator movement."; color:"#98a0a6"; Layout.fillWidth:true; wrapMode:Text.WordWrap }
        }
        onAccepted:{ const now=Math.floor(Date.now()/1000); Parity.calculateSatellitePasses(satName.text,tle1.text,tle2.text,Number(observerLat.text),Number(observerLon.text),Number(observerAlt.text),now,now+86400) }
    }
}
