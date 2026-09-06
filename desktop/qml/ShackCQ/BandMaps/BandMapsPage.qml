import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id: root
    workspaceKey: "Band Maps"
    property real lowHz: 14000000
    property real highHz: 14350000

    CanvasPanel {
        panelKey: "filters"
        title: "Band map filters and safety"
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: 142
        ColumnLayout { anchors.fill: parent
            RowLayout { Layout.fillWidth: true
                ComboBox { id: layoutMode; objectName: "bandMapLayout"; currentIndex: Parity.galleryBandMapLayout; model: ["Multi Vertical","Multi Horizontal","Grid Overview","Single Expanded"]; onCurrentIndexChanged: mapCanvas.requestPaint() }
                ComboBox { id: band; model: ["20m","40m","80m","15m","10m"]; onCurrentTextChanged: { const ranges={"20m":[14000000,14350000],"40m":[7000000,7300000],"80m":[3500000,4000000],"15m":[21000000,21450000],"10m":[28000000,29700000]}; root.lowHz=ranges[currentText][0]; root.highHz=ranges[currentText][1] } }
                ComboBox { model: ["All modes","CW","Phone","Digital"] }
                ComboBox { model: ["All ages","5 min","15 min","60 min"] }
                CheckBox { text: "Needed" }
                CheckBox { text: "Watchlist" }
                CheckBox { text: "Historical context" }
                Item { Layout.fillWidth: true }
                Label { text: Parity.bandMapRows.count + " ranked observations"; color: "#98a0a6" }
            }
            SafetyBanner { Layout.fillWidth: true; text: "Frequency selection opens receive review only. Contest and DX Chaser filters are disabled until their desktop controllers are fixture-tested." }
        }
    }

    CanvasPanel {
        panelKey: "band-map"
        title: "Live band map"
        defaultY: 154
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: parent ? parent.height - 154 : 470
        Rectangle { id: map; anchors.fill: parent; color: "#101316"; border.color: "#3a4147"; clip: true
            Canvas { id: mapCanvas; anchors.fill: parent; onPaint: { const c=getContext("2d"); c.reset(); c.fillStyle="#101316"; c.fillRect(0,0,width,height); c.strokeStyle="#59636b"; c.fillStyle="#98a0a6"; c.font="11px monospace"; if(layoutMode.currentIndex===1){c.fillStyle="#4b351c";c.fillRect(0,height-52,width,16);for(let i=0;i<=10;i++){const x=i/10*width;c.beginPath();c.moveTo(x,0);c.lineTo(x,height-58);c.stroke();const f=(root.lowHz+(root.highHz-root.lowHz)*i/10)/1000;c.fillStyle="#98a0a6";c.fillText(f.toFixed(0),x+2,height-18)}}else if(layoutMode.currentIndex===2){const names=["20 m","40 m","15 m","10 m"];for(let i=0;i<4;i++){const x=(i%2)*width/2,y=Math.floor(i/2)*height/2;c.strokeRect(x+8,y+8,width/2-16,height/2-16);c.fillStyle="#d38b22";c.fillText(names[i]+" · shared repository",x+20,y+30);for(let j=1;j<5;j++){c.beginPath();c.moveTo(x+12,y+j*height/10);c.lineTo(x+width/2-12,y+j*height/10);c.stroke()}}}else{c.fillStyle=layoutMode.currentIndex===3?"#6a4a20":"#4b351c";c.fillRect(layoutMode.currentIndex===3?86:58,0,layoutMode.currentIndex===3?24:16,height);for(let i=0;i<=10;i++){const y=height-i/10*height;c.beginPath();c.moveTo(50,y);c.lineTo(width,y);c.stroke();const f=(root.lowHz+(root.highHz-root.lowHz)*i/10)/1000;c.fillStyle="#98a0a6";c.fillText(f.toFixed(layoutMode.currentIndex===3?1:0),2,y-2)}} } }
            ListView { anchors.fill: parent; anchors.leftMargin: layoutMode.currentIndex===0?76:0; interactive: false; model: Parity.bandMapRows
                delegate: Item { required property int index; required property var item; readonly property double frequencyHz:Number(item.value); readonly property string callsign:item.title; readonly property string mode:item.subtitle; width:layoutMode.currentIndex===2?map.width/2-40:ListView.view.width; height:1; visible:frequencyHz>=root.lowHz&&frequencyHz<=root.highHz; x:layoutMode.currentIndex===1?((frequencyHz-root.lowHz)/(root.highHz-root.lowHz))*(map.width-180):layoutMode.currentIndex===2?(index%2)*map.width/2+24:0; y:layoutMode.currentIndex===1?60+index*42:layoutMode.currentIndex===2?Math.floor(index/2)*map.height/2+64:(1-(frequencyHz-root.lowHz)/(root.highHz-root.lowHz))*(map.height-30)
                    Rectangle { width:10; height:2; color:"#d38b22" }
                    Rectangle { x:12; y:-10; width:Math.max(120,label.implicitWidth+12); height:22; color:"#22272b"; border.color:"#d38b22"; Label { id:label; anchors.centerIn:parent; text:callsign+"  "+mode+"  "+item.state; color:"#f2efe7"; font.pixelSize:11; font.bold:true } }
                    MouseArea { anchors.fill:parent; onClicked:review.open() }
                    ConfirmDialog { id:review; message:"Receive-review "+callsign+" at "+frequencyHz+" Hz? No automatic CAT or transmit action occurs."; onAccepted:Radio.requestFrequency(frequencyHz) }
                }
            }
            Label { anchors.centerIn:parent; visible:Parity.bandMapRows.count===0; text:"NO OBSERVED SPOTS — BAND SCALE ONLY"; color:"#98a0a6" }
        }
    }
}
