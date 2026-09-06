import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ShackCQ.Controls 1.0
import "../Components"

Rectangle { id: root; color: "#171b1e"; border.color: "#3a4147"
    property real dragLon: 0; property real dragLat: 0; property real dragX: 0; property real dragY: 0
    function applyGalleryProjection(){if(Desktop.demoMode)projection.currentIndex=Desktop.galleryVariant>=2&&Desktop.galleryVariant<=4?1:0}
    Connections { target: Desktop; function onGalleryVariantChanged(){root.applyGalleryProjection()} }
    Component.onCompleted: applyGalleryProjection()
    RowLayout { anchors.fill: parent; anchors.margins: 10; spacing: 10
        ColumnLayout { Layout.preferredWidth: 310; Layout.fillHeight: true
            Label { text: "RF OBSERVATION FILTERS"; color: "#d38b22"; font.bold: true }
            ComboBox { Layout.fillWidth: true; model: ["All","PSK Reporter demo","Logged QSO demo","Empirical Outlook demo"]; onActivated: RfObservations.setFilter("source", currentText) }
            ComboBox { Layout.fillWidth: true; model: ["All","160m","80m","40m","20m","15m","10m"]; onActivated: RfObservations.setFilter("band", currentText) }
            ComboBox { Layout.fillWidth: true; model: ["All","FT8","CW","USB","LSB"]; onActivated: RfObservations.setFilter("mode", currentText) }
            ComboBox { Layout.fillWidth: true; model: ["All","LIVE","HISTORICAL","OUTLOOK"]; onActivated: RfObservations.setFilter("evidence", currentText) }
            ComboBox { Layout.fillWidth: true; model: ["All","Worked","Unworked"]; onActivated: RfObservations.setFilter("worked", currentText) }
            ComboBox { Layout.fillWidth: true; model: ["All","Confirmed","Unconfirmed"]; onActivated: RfObservations.setFilter("confirmed", currentText) }
            ComboBox { Layout.fillWidth: true; model: ["All","Needed DXCC","Not needed DXCC"]; onActivated: RfObservations.setFilter("neededDxcc", currentText) }
            ComboBox { Layout.fillWidth: true; model: [30,60,120]; currentIndex: 2; onActivated: RfObservations.setFilter("maximumAgeMinutes", currentValue) }
            TextField { Layout.fillWidth: true; placeholderText: "Callsign"; onTextEdited: RfObservations.setFilter("callsign", text) }
            CheckBox { text: "Fresh sources only"; onToggled: RfObservations.setFilter("freshOnly", checked) }
            CheckBox { text: "Explicit long path"; onToggled: RfObservations.setFilter("longPath", checked) }
            Button { text: "Reset Filters"; onClicked: RfObservations.resetFilters() }
            Label { Layout.fillWidth: true; text: RfObservations.filterSummary; color: "#f2efe7"; wrapMode: Text.WordWrap }
            Label { Layout.fillWidth: true; text: "Available now: explicit demo fixtures in demo mode. Cluster, RBN, PSK Reporter, WSPR and decoder locations remain unavailable until canonical records carry defensible coordinates."; color: "#e3c765"; wrapMode: Text.WordWrap; font.pixelSize: 11 }
            ListView { Layout.fillWidth: true; Layout.fillHeight: true; model: RfObservations; clip: true
                delegate: ItemDelegate { required property string observationId; required property string callsign; required property string evidenceClass; required property real distanceKm; required property string endpointPrecision; width: ListView.view.width; text: callsign+" · "+evidenceClass+"\n"+distanceKm.toFixed(0)+" km · "+endpointPrecision; highlighted: RfObservations.selectedId===observationId; onClicked: RfObservations.selectedId=observationId }
            }
        }
        ColumnLayout { Layout.fillWidth: true; Layout.fillHeight: true
            RowLayout { Layout.fillWidth: true
                ComboBox { id: projection; objectName: "rfProjection"; model: ["Flat","Globe"] }
                Binding { target: projection; property: "currentIndex"; when: Desktop.demoMode; value: Desktop.galleryVariant>=2&&Desktop.galleryVariant<=4?1:0 }
                Button { text: "Reset view"; onClicked: { map.zoom=1; map.longitude=0; map.latitude=0 } }
                Label { text: projection.currentText==="Globe"?"Drag to rotate · wheel to zoom":"Drag to pan · wheel to zoom"; color: "#98a0a6" }
                Item { Layout.fillWidth: true }
                Button { text: "Explicit QSY"; enabled: RfObservations.selectedObservation().frequencyHz>0&&Radio.state.startsWith("Connected"); onClicked: Radio.requestFrequency(RfObservations.selectedObservation().frequencyHz) }
                Button { text: "Logbook"; onClicked: Desktop.currentDestination="Logbook" }
                Button { text: "DX"; onClicked: Desktop.currentDestination="DX" }
                Button { text: "Band Maps"; onClicked: Desktop.currentDestination="Band Maps" }
            }
            Label { Layout.fillWidth: true; visible: RfObservations.count===0; text: "NO MATCHING RF OBSERVATIONS — filters, stale sources, or providers may be offline"; color: "#e3c765"; horizontalAlignment: Text.AlignHCenter }
            RfMapScene { id: map; objectName: "rfMapScene"; Layout.fillWidth: true; Layout.fillHeight: true; model: RfObservations; projection: Desktop.demoMode?(Desktop.galleryVariant>=2&&Desktop.galleryVariant<=4?"Globe":"Flat"):projection.currentText
                PinchHandler { id: pinch; property real startingZoom: 1; onActiveChanged: if(active) startingZoom=map.zoom; onActiveScaleChanged: map.zoom=Math.max(1,Math.min(8,startingZoom*activeScale)) }
                MouseArea { anchors.fill: parent
                    onPressed: function(mouse){root.dragX=mouse.x;root.dragY=mouse.y;root.dragLon=map.longitude;root.dragLat=map.latitude}
                    onPositionChanged: function(mouse){if(!pressed)return;map.longitude=root.dragLon-(mouse.x-root.dragX)/width*180/map.zoom;map.latitude=root.dragLat+(mouse.y-root.dragY)/height*90/map.zoom}
                    onWheel: function(wheel){map.zoom=Math.max(1,Math.min(8,map.zoom*(wheel.angleDelta.y>0?1.18:.85)))}
                }
            }
            Rectangle { Layout.fillWidth: true; implicitHeight: 64; color: "#22272b"; border.color: "#3a4147"; property var selected: RfObservations.selectedId.length>0?RfObservations.selectedObservation():({})
                Label { anchors.fill: parent; anchors.margins: 8; text: parent.selected.id?(parent.selected.callsign+" · "+parent.selected.source+" · "+parent.selected.evidenceClass+" · "+parent.selected.endpointPrecision+"\n"+Number(parent.selected.distanceKm).toFixed(0)+" km / "+Number(parent.selected.bearingDeg).toFixed(0)+"°"+(parent.selected.snrReported!==undefined?" · reported SNR "+parent.selected.snrReported+" dB":" · no SNR claimed")):"Select a path from the bounded result list. Selection never tunes, logs, rotates, transmits, or publishes."; color: "#f2efe7"; wrapMode: Text.WordWrap }
            }
            RowLayout { Layout.fillWidth: true
                Repeater { model: [{c:"#4ec47b",t:"Observed live/network + control-point heat"},{c:"#d38b22",t:"Historical logged path"},{c:"#5ca6c8",t:"Empirical outlook (separate)"},{c:"transparent",t:"COARSE is hollow · no exact MUF"}]
                    Row { required property var modelData; spacing: 4; Rectangle { width: 10; height: 10; radius: 5; color: modelData.c; border.color: modelData.c==="transparent"?"#f2efe7":modelData.c } Label { text: modelData.t; color: "#98a0a6"; font.pixelSize: 10 } }
                }
            }
        }
    }
}
