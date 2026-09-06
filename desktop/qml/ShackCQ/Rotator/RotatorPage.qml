import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    workspaceKey:"Rotator"
    ConfirmDialog { id:confirm; title:"Confirm physical movement"; message:"Move the explicitly connected rotator to AZ "+Rotator.preparedAzimuth.toFixed(1)+" / EL "+Rotator.preparedElevation.toFixed(1)+"? Preparation alone never moves."; onAccepted:Rotator.confirmMove() }
    Connections { target:Rotator; function onConfirmationRequired(){confirm.open()} }

    CanvasPanel { panelKey:"safety"; title:"Rotator safety state"; defaultWidth:parent?parent.width:1200; defaultHeight:96
        SafetyBanner { anchors.fill:parent; text:"Default disconnected and automation disarmed. Manual, prompt and selected-target policies all require explicit target, arm, fresh position, safe path, band assignment and TX policy." }
    }
    CanvasPanel { panelKey:"connection"; title:"Rotator connection"; defaultY:108; defaultWidth:parent?parent.width:1200; defaultHeight:104
        RowLayout { anchors.fill:parent
            StatusChip { text:"Remote "+RemoteClient.state; kind:RemoteClient.connected?"healthy":"neutral" }
            ComboBox { id:protocol; model:Parity.nativeRotatorProtocols; textRole:"title"; valueRole:"key"; Layout.preferredWidth:230 }
            TextField {
                id:modelId
                placeholderText:"Hamlib model ID"
                validator:IntValidator{bottom:1}
                visible:protocol.currentValue && protocol.currentValue.toString().startsWith("HAMLIB")
            }
            TextField { id:route; Layout.fillWidth:true; placeholderText:"COM port or network route" }
            SpinBox { id:baud; from:1200; to:921600; value:9600; editable:true }
            Button { text:"Connect"; enabled:protocol.currentValue && route.text.trim().length>0 && protocol.currentValue!=="ARCO" && (!protocol.currentValue.toString().startsWith("HAMLIB") || Number(modelId.text)>0); onClicked:protocol.currentValue.toString().startsWith("HAMLIB")?Rotator.connectRotator(Number(modelId.text),route.text,baud.value):Rotator.connectNative(protocol.currentValue,route.text,baud.value) }
            Button { text:"Disconnect"; onClicked:Rotator.disconnectRotator() }
            Button { text:"STOP"; palette.button:"#8c2525"; palette.buttonText:"white"; onClicked:Rotator.stop() }
        }
    }
    CanvasPanel { panelKey:"telemetry"; title:"Rotator telemetry"; defaultY:224; defaultWidth:parent?parent.width:1200; defaultHeight:150
        Flow { anchors.fill:parent; spacing:12
            MetricTile{label:"State";value:Rotator.state.startsWith("Connected")?"CONNECTED":"DISCONNECTED";truth:Rotator.state}
            MetricTile{label:"Azimuth";value:Rotator.azimuth.toFixed(1)+"°";truth:"Observed telemetry"}
            MetricTile{label:"Elevation";value:Rotator.elevation.toFixed(1)+"°";truth:"Observed telemetry"}
            MetricTile{label:"Automation";value:"DISARMED";truth:"No AUTO_SELECTED_TARGET"}
        }
    }
    CanvasPanel { panelKey:"manual-target"; title:"Prepare manual target"; defaultY:386; defaultWidth:parent?(parent.width-12)*0.58:690; defaultHeight:130
        RowLayout { anchors.fill:parent
            Label{text:"Az"} SpinBox{id:az;from:0;to:450;value:0;editable:true}
            Label{text:"El"} SpinBox{id:el;from:-10;to:180;value:0;editable:true}
            Button{text:"Prepare";onClicked:Rotator.prepareTarget(az.value,el.value)}
            Button{text:"Park (explicit)";enabled:Rotator.state.startsWith("Connected");onClicked:Rotator.park()}
            Item{Layout.fillWidth:true}
            Label{text:"Prepared: "+Rotator.preparedAzimuth+" / "+Rotator.preparedElevation;color:"#e3c765"}
        }
    }
    CanvasPanel { panelKey:"limits"; title:"Limits and assignment"; defaultX:parent?(parent.width+12)*0.58:702; defaultY:386; defaultWidth:parent?(parent.width-12)*0.42:500; defaultHeight:130
        GridLayout { anchors.fill:parent; columns:4
            Label{text:"Az limits"} Label{text:"0°–450°";color:"#f2efe7"}
            Label{text:"El limits"} Label{text:"0°–180°";color:"#f2efe7"}
            Label{text:"Forbidden sectors"} Label{text:"None configured";color:"#e3c765"}
            Label{text:"Automation arm"} Label{text:"SESSION ONLY / OFF";color:"#e3c765"}
        }
    }
    CanvasPanel { panelKey:"acceptance"; title:"Physical acceptance boundary"; defaultY:528; defaultWidth:parent?parent.width:1200; defaultHeight:parent?parent.height-528:120
        EmptyState { anchors.fill:parent; title:"No physical movement evidence"; detail:"Native protocol codecs and Hamlib dummy fixtures validate state/safety. Real Windows movement, forbidden-sector path proof and PTT/RF coexistence remain LIVE_ACCEPTANCE_PENDING." }
    }
}
