import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id:root
    workspaceKey:"Health"
    property var status:Desktop.health()
    function describeRadio(){const r=status.radio||{};const t=r.tci||{};return ["State: "+(r.state||"Unknown"),"TCI: "+(t.state||"Disabled"),"Protocol: "+(t.protocolVersion||"not negotiated"),"Device: "+(t.deviceIdentity||"not reported"),"Receivers: "+((t.receivers||[]).length||0),"Last update (ms UTC): "+(t.lastUpdateMs||"never"),"Decode off UI / queue: "+(t.binaryDecodedOffOwnerThread||false)+" / "+(t.binaryQueueDepth||0)+"/"+(t.binaryQueueCapacity||0),"RX audio under/overflows: "+(t.rxAudioUnderflows||0)+" / "+(t.rxAudioOverflows||0),"Last error: "+(r.lastSanitizedError||"none")].join("\n")}
    function describePanadapter(){const p=status.panadapter||{};return ["Source: "+(p.source||"none"),"FFT / renderer: "+(p.fftSize||0)+" / "+(p.renderer||"unknown"),"FFT off UI / queue: "+(p.fftExecutedOffOwnerThread||false)+" / "+(p.workerQueueDepth||0)+"/"+(p.workerQueueCapacity||0),"Effective frame rate: "+(p.effectiveFrameRateHz||0)+" Hz","Waterfall: "+(p.waterfallWidth||0)+" × "+(p.waterfallRows||0)+" ("+(p.waterfallBytesPerContext||0)+" bytes/context)","Receiver contexts: "+((p.contexts||[]).length||0)].join("\n")}
    function describeRf(){const r=status.rfObservations||{};return ["Visible / stored: "+(r.count||0)+" / "+(r.storedCount||0),"Renderer cap / dropped: "+(r.rendererRecordCap||0)+" / "+(r.droppedObservations||0),"Freshness: "+(r.sourceFreshness||"none"),"Renderer: "+(r.renderer||"unknown"),"Selected: "+(r.selectedId||"none")].join("\n")}

    Repeater {
        model:[{n:"Database / projection",v:root.status.database+" • "+root.status.projection},{n:"Wavelog",v:root.status.wavelog},{n:"DX Cluster",v:root.status.cluster},{n:"Radio / Hamlib / TCI",v:root.describeRadio()},{n:"Remote Station host",v:JSON.stringify(RemoteStation.health(),null,2)},{n:"Remote Station client",v:JSON.stringify(RemoteClient.health(),null,2)},{n:"Rotator",v:root.status.rotator},{n:"Panadapter / waterfall",v:root.describePanadapter()},{n:"RF observations",v:root.describeRf()},{n:"Providers",v:root.status.providers},{n:"Configuration",v:root.status.configuration}]
        delegate: CanvasPanel {
            required property int index
            required property var modelData
            panelKey:"health-"+index
            title:modelData.n
            defaultX:parent?(index%3)*(parent.width+12)/3:0
            defaultY:Math.floor(index/3)*192
            defaultWidth:parent?(parent.width-24)/3:380
            defaultHeight:180
            panelMinimumWidth:280
            ColumnLayout { anchors.fill:parent
                Label { Layout.fillWidth:true; text:modelData.v; color:"#f2efe7"; wrapMode:Text.WordWrap }
                Item { Layout.fillHeight:true }
                RowLayout {
                    Button { text:"Retry"; visible:modelData.n==="Wavelog"; onClicked:Wavelog.retryPending() }
                    Button { text:"Open workspace"; visible:modelData.n==="Configuration"; onClicked:Desktop.currentDestination="Settings" }
                }
            }
        }
    }

    CanvasPanel {
        panelKey:"health-actions"
        title:"Health actions"
        defaultY:576
        defaultWidth:parent?parent.width:1200
        defaultHeight:96
        panelMinimumHeight:90
        RowLayout { anchors.fill:parent
            Button { text:"Verify projection"; onClicked:root.status=Desktop.health() }
            Button { text:"Rebuild projection"; enabled:false; ToolTip.visible:hovered; ToolTip.text:"Available through bounded database service; disabled while no repair is required." }
            Button { text:"Clear re-fetchable cache"; enabled:false }
            Button { text:"Export sanitized support ZIP"; onClicked:SupportBundle.create(Desktop.health()) }
            Item { Layout.fillWidth:true }
            Label { text:"Generic actions never clear credentials"; color:"#e3c765" }
        }
    }
}
