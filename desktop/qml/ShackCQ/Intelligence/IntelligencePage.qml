import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id:root
    workspaceKey:"Intelligence"
    property var summary:Desktop.intelligence()

    CanvasPanel {
        panelKey:"summary"
        title:"Intelligence summary"
        defaultWidth:parent?parent.width:1200
        defaultHeight:150
        Flow { anchors.fill:parent; spacing:12
            MetricTile{label:"QSOs";value:root.summary.qsos??0;truth:"Local canonical projection"}
            MetricTile{label:"Operators";value:root.summary.callsigns??0;truth:"Distinct normalized callsigns"}
            MetricTile{label:"Entities";value:root.summary.entities??0;truth:"Stored DXCC/entity fields"}
            MetricTile{label:"RF paths";value:RfObservations.count;truth:"Filtered, provenance-labelled observations"}
        }
    }

    CanvasPanel {
        panelKey:"explorer"
        title:"Intelligence explorer"
        defaultY:162
        defaultWidth:parent?parent.width:1200
        defaultHeight:parent?parent.height-162:460
        ColumnLayout { anchors.fill:parent
            TabBar { id:tabs; objectName:"intelligenceTabs"; Layout.fillWidth:true; Repeater { model:["Overview","Activity","Geography","Confirmations","Operators","Portable","Needs","Awards","Satellite","Live RF / Outlook"]; TabButton { required property string modelData; text:modelData } } }
            StackLayout { Layout.fillWidth:true; Layout.fillHeight:true; currentIndex:tabs.currentIndex
                Repeater { model:9; Loader { required property int index; active:true; sourceComponent:index===2?rfPanel:placeholder } }
                Loader { sourceComponent:rfPanel }
            }
            Component { id:rfPanel; RfMapPanel{} }
            Component { id:placeholder; Rectangle { color:"#22272b"; border.color:"#3a4147"; Column { anchors.centerIn:parent; spacing:10; Label{text:tabs.itemAt(tabs.currentIndex)?tabs.itemAt(tabs.currentIndex).text:"Overview";color:"#d38b22";font.pixelSize:22;font.bold:true;anchors.horizontalCenter:parent.horizontalCenter} Label{width:620;text:"Indexed keyset projection and bounded drill-through use canonical QSO authority. Award results are local estimates, not official programme credit.";color:"#f2efe7";wrapMode:Text.WordWrap;horizontalAlignment:Text.AlignHCenter} } } }
        }
    }
}
