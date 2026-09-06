import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ShackCQ.Controls 1.0
import "../Components"

WorkspaceCanvas {
    id:root
    workspaceKey:"Panadapter"
    property real cursorFrequency:0
    property real selectedMarkerFrequency:0
    property var activeSnapshot:Radio.receiverSnapshot(Radio.activeReceiverId)
    property real dragStartX:0
    property real dragStartPan:0
    property string secondaryReceiver:{const ids=Panadapter.receiverIds;for(let i=0;i<ids.length;i++)if(ids[i]!==Panadapter.currentReceiverId)return ids[i];return ""}
    Connections{target:Radio;function onSnapshotChanged(){root.activeSnapshot=Radio.receiverSnapshot(Radio.activeReceiverId)}}

    CanvasPanel { panelKey:"safety"; title:"Panadapter safety state"; defaultWidth:parent?parent.width:1200; defaultHeight:88; panelMinimumHeight:84
        SafetyBanner{anchors.fill:parent;text:"Receive-only. Sources are exact local stereo I/Q or observed TCI float32 I/Q. Display pause does not claim capture stopped; no microphone fallback and no generated production spectrum."}
    }
    CanvasPanel { panelKey:"source"; title:"I/Q source"; defaultY:100; defaultWidth:parent?parent.width:1200; defaultHeight:84; panelMinimumHeight:80
        RowLayout{anchors.fill:parent
            ComboBox{id:source;Layout.preferredWidth:220;model:Panadapter.receiverIds;onActivated:Panadapter.currentReceiverId=currentText}
            ComboBox{id:device;Layout.fillWidth:true;model:Panadapter.devices();textRole:"description";valueRole:"id";onActivated:Panadapter.selectedDeviceId=currentValue}
            ComboBox{id:rate;model:[48000,96000,192000];currentIndex:1}
            CheckBox{id:swap;text:"Swap I/Q"}
            Button{text:"Start local I/Q";onClicked:Panadapter.start(rate.currentValue,swap.checked)}
            Button{text:"Stop local";onClicked:Panadapter.stop()}
        }
    }
    CanvasPanel { panelKey:"display-controls"; title:"Display inspector"; defaultY:196; defaultWidth:parent?parent.width:1200; defaultHeight:156; panelMinimumHeight:148
        ColumnLayout{anchors.fill:parent
            RowLayout{Layout.fillWidth:true
                ComboBox{model:[1024,2048,4096,8192];currentIndex:2;onActivated:Panadapter.fftSize=currentValue}
                ComboBox{model:["Spectrum + waterfall","Spectrum only","Waterfall only"];onActivated:Panadapter.displayMode=currentText}
                ComboBox{model:["Flightline warm","High contrast","Viridis","Thermal mono"];onActivated:Panadapter.colourMap=currentText}
                CheckBox{text:"FIT";checked:Panadapter.fitAutoContrast;onToggled:Panadapter.fitAutoContrast=checked}
                ComboBox{model:[1,2,4,8,16,32,64];currentIndex:1;onActivated:Panadapter.averageFrames=currentValue;ToolTip.visible:hovered;ToolTip.text:"Averaging frames"}
                CheckBox{text:"Peak hold";checked:Panadapter.peakHold;onToggled:Panadapter.peakHold=checked}
                CheckBox{text:Panadapter.paused?"Display frozen":"Live display";checked:Panadapter.paused;onToggled:Panadapter.paused=checked}
                Button{text:"Clear waterfall";onClicked:Panadapter.clearWaterfall()}
                Button{text:"Reset peak";onClicked:Panadapter.resetPeak()}
                Button{text:"Reset view";onClicked:{scene.zoom=1;scene.pan=0}}
            }
            RowLayout{Layout.fillWidth:true;visible:!Panadapter.fitAutoContrast
                Label{text:"Manual floor";color:"#98a0a6"} SpinBox{from:-140;to:-20;value:Panadapter.manualFloorDb;onValueModified:Panadapter.manualFloorDb=value}
                Label{text:"Manual top";color:"#98a0a6"} SpinBox{from:-120;to:20;value:Panadapter.manualTopDb;onValueModified:Panadapter.manualTopDb=value}
                Label{text:"Peak decay dB/s";color:"#98a0a6"} Slider{Layout.fillWidth:true;from:0;to:20;value:Panadapter.peakDecay;onMoved:Panadapter.peakDecay=value}
            }
            RowLayout{Layout.fillWidth:true
                StatusChip{text:Panadapter.state;kind:Panadapter.state.startsWith("Receiving")?"healthy":"neutral"}
                Label{text:"Peak "+Panadapter.peakDb.toFixed(1)+" dB";color:"#f2efe7"}
                Label{text:Panadapter.validStereo?"Stereo I/Q observed":"I/Q validity pending";color:Panadapter.validStereo?"#4ec47b":"#e3c765"}
                Label{text:scene.rendererHealth;color:"#98a0a6"}
                Item{Layout.fillWidth:true}
                Label{text:root.cursorFrequency?(root.cursorFrequency/1000).toFixed(3)+" kHz":"Cursor frequency pending centre readback";color:"#f2efe7"}
                Button{text:root.selectedMarkerFrequency>0?"QSY selected marker":"QSY cursor";enabled:(root.selectedMarkerFrequency>0||root.cursorFrequency>0)&&Radio.state.startsWith("Connected");onClicked:{Radio.requestFrequency(root.selectedMarkerFrequency>0?root.selectedMarkerFrequency:root.cursorFrequency);root.selectedMarkerFrequency=0}}
            }
        }
    }
    CanvasPanel { panelKey:"spectrum"; title:"Spectrum and waterfall"; defaultY:364; defaultWidth:parent?parent.width:1200; defaultHeight:parent?parent.height-364:390; panelMinimumHeight:260
        ColumnLayout{anchors.fill:parent;spacing:6
            Rectangle{Layout.fillWidth:true;implicitHeight:28;color:"#171b1e";border.color:"#3a4147"
                Row{anchors.fill:parent;Repeater{model:scene.zoom<4?["LOW","MID","HIGH","SAT"]:["CW","DIGITAL","PHONE","BEACONS"];Rectangle{required property string modelData;required property int index;width:parent.width/4;height:parent.height;color:index%2?"#23292d":"#1c2226";Label{anchors.centerIn:parent;text:modelData;color:"#98a0a6";font.pixelSize:11}}}}
                Rectangle{anchors.verticalCenter:parent.verticalCenter;x:parent.width/2-1;width:2;height:parent.height;color:"#d38b22"}
            }
            Rectangle{id:viewport;Layout.fillWidth:true;Layout.fillHeight:true;color:"#101316";border.color:"#3a4147";clip:true;layer.enabled:true;layer.smooth:false
                PanadapterScene{id:scene;objectName:"primaryPanadapterScene";anchors.fill:parent;source:Panadapter;receiverId:Panadapter.currentReceiverId}
                Rectangle{id:passband;anchors.horizontalCenter:parent.horizontalCenter;y:0;width:Math.max(18,parent.width*.08*scene.zoom);height:parent.height*scene.spectrumRatio;color:"#263f4a66";border.color:"#5ca6c8";opacity:.8;ToolTip.visible:passHover.hovered;ToolTip.text:"Passband overlay · filter drag disabled until capability and readback are proven";HoverHandler{id:passHover}Label{anchors.top:parent.top;anchors.horizontalCenter:parent.horizontalCenter;text:"PASSBAND VIEW ONLY";color:"#5ca6c8";font.pixelSize:9}}
                Rectangle{property real normalized:Panadapter.normalizedForFrequency(root.activeSnapshot.vfoAHz||0,scene.zoom,scene.pan);visible:normalized>=0&&normalized<=1;x:normalized*parent.width-1;y:0;width:2;height:parent.height*scene.spectrumRatio;color:"#f2efe7";Label{text:"A";color:"#f2efe7";anchors.top:parent.top}}
                Rectangle{property real normalized:Panadapter.normalizedForFrequency(root.activeSnapshot.vfoBHz||0,scene.zoom,scene.pan);visible:normalized>=0&&normalized<=1;x:normalized*parent.width-1;y:0;width:2;height:parent.height*scene.spectrumRatio;color:"#5ca6c8";Label{text:"B";color:"#5ca6c8";anchors.top:parent.top}}
                Repeater{model:Spots;Rectangle{required property real frequencyHz;required property string callsign;property real normalized:Panadapter.normalizedForFrequency(frequencyHz,scene.zoom,scene.pan);visible:normalized>=0&&normalized<=1;x:normalized*viewport.width-3;y:22;width:7;height:7;radius:4;color:root.selectedMarkerFrequency===frequencyHz?"#f2efe7":"#d38b22";TapHandler{onTapped:root.selectedMarkerFrequency=frequencyHz}ToolTip.visible:markerHover.hovered;ToolTip.text:callsign+" · select, then explicit QSY";HoverHandler{id:markerHover}}}
                Rectangle{anchors.horizontalCenter:parent.horizontalCenter;y:parent.height*scene.spectrumRatio-2;width:parent.width;height:4;color:"#3a4147";opacity:Panadapter.displayMode==="Spectrum + waterfall"?1:0;DragHandler{id:splitDrag;yAxis.enabled:true;xAxis.enabled:false;onActiveChanged:if(!active)scene.spectrumRatio=Math.max(.2,Math.min(.8,centroid.position.y/viewport.height))}}
                MouseArea{anchors.fill:parent;acceptedButtons:Qt.LeftButton;hoverEnabled:true
                    onPositionChanged:function(mouse){root.cursorFrequency=Panadapter.frequencyAt(mouse.x/width,scene.zoom,scene.pan);if(pressed)scene.pan=Math.max(-1,Math.min(1,root.dragStartPan-(mouse.x-root.dragStartX)/width*2/Math.max(.01,1-1/scene.zoom)))}
                    onPressed:function(mouse){root.dragStartX=mouse.x;root.dragStartPan=scene.pan}
                    onWheel:function(wheel){const cursor=wheel.x/width;const oldZoom=scene.zoom;const oldVisible=1/oldZoom;const oldLeft=(1-oldVisible)*(scene.pan+1)/2;const anchor=oldLeft+cursor*oldVisible;const nextZoom=Math.max(1,Math.min(32,oldZoom*(wheel.angleDelta.y>0?1.25:.8)));const nextVisible=1/nextZoom;scene.zoom=nextZoom;scene.pan=nextZoom===1?0:Math.max(-1,Math.min(1,(anchor-cursor*nextVisible)/(1-nextVisible)*2-1));root.cursorFrequency=Panadapter.frequencyAt(cursor,scene.zoom,scene.pan)}
                }
                Rectangle{visible:root.secondaryReceiver.length>0;z:4;anchors.right:parent.right;anchors.bottom:parent.bottom;anchors.margins:12;width:parent.width*.38;height:parent.height*.32;color:"#101316";border.color:"#d38b22";PanadapterScene{anchors.fill:parent;anchors.margins:2;source:Panadapter;receiverId:root.secondaryReceiver;spectrumRatio:.38}Label{anchors.left:parent.left;anchors.top:parent.top;anchors.margins:5;text:"SECOND RECEIVER · "+root.secondaryReceiver;color:"#e3c765";font.bold:true;font.pixelSize:10}}
                Label{anchors.centerIn:parent;visible:!Panadapter.hasFrame;text:"OFFLINE — no observed I/Q display frame";color:"#98a0a6"}
            }
            Slider{Layout.fillWidth:true;from:.2;to:.8;value:scene.spectrumRatio;visible:Panadapter.displayMode==="Spectrum + waterfall";onMoved:scene.spectrumRatio=value;ToolTip.visible:hovered;ToolTip.text:"Resizable spectrum / waterfall split"}
            Rectangle{Layout.fillWidth:true;implicitHeight:12;radius:2;gradient:Gradient{orientation:Gradient.Horizontal;GradientStop{position:0;color:"#101316"}GradientStop{position:.5;color:Panadapter.colourMap==="Viridis"?"#2c7d78":"#7a4d24"}GradientStop{position:1;color:Panadapter.colourMap==="Thermal mono"?"white":"#f0b24c"}}Label{anchors.right:parent.right;anchors.bottom:parent.top;text:"Colour scale · older rows downward";color:"#98a0a6";font.pixelSize:10}}
        }
    }
}
