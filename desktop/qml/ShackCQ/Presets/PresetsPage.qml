import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    id:root
    workspaceKey:"Presets"
    CanvasPanel { panelKey:"safety"; title:"Preset safety state"; defaultWidth:parent?parent.width:1200; defaultHeight:96
        SafetyBanner { anchors.fill:parent; text:"Preset recall opens a capability review. Memory writes, PTT, TUNE and unsupported native controls are never implied by selecting a preset." }
    }
    CanvasPanel { panelKey:"tools"; title:"Preset tools"; defaultY:108; defaultWidth:parent?parent.width:1200; defaultHeight:96; panelMinimumHeight:90
        RowLayout { anchors.fill:parent
            TextField { placeholderText:"Search folders, tags or frequency"; Layout.fillWidth:true }
            Button { text:"New preset"; onClicked:presetEditor.open() }
            Button { text:"Import…"; enabled:false; ToolTip.visible:hovered; ToolTip.text:"No bounded preset import owner is available" }
            Button { text:"Export…"; enabled:false; ToolTip.visible:hovered; ToolTip.text:"No bounded preset export owner is available" }
        }
    }
    Repeater {
        model:Parity.presets
        delegate:CanvasPanel {
            required property int index
            required property var item
            panelKey:"preset-"+index
            title:item.title
            defaultX:parent?(index%2)*(parent.width+12)/2:0
            defaultY:216+Math.floor(index/2)*204
            defaultWidth:parent?(parent.width-12)/2:580
            defaultHeight:192
            ColumnLayout { anchors.fill:parent
                Label { text:item.detail; color:"#98a0a6" }
                Label { text:item.state; color:item.state === "READY" ? "#42c77b" : "#f4c94e" }
                Button { text:"Review recall"; onClicked:Parity.reviewPresetRecall(item.key) }
                Item { Layout.fillHeight:true }
            }
        }
    }
    Dialog { id:presetEditor; title:"New receive-safe preset"; modal:true; standardButtons:Dialog.Cancel|Dialog.Ok; width:500
        ColumnLayout { anchors.fill:parent
            TextField { id:presetTitle; placeholderText:"Preset title"; Layout.fillWidth:true }
            TextField { id:presetFrequency; placeholderText:"Frequency in Hz"; inputMethodHints:Qt.ImhDigitsOnly; Layout.fillWidth:true }
            TextField { id:presetDetail; placeholderText:"Mode, filter and notes"; Layout.fillWidth:true }
            Label { text:"Saving does not connect a radio or issue any command."; color:"#98a0a6" }
        }
        onAccepted:Parity.savePreset({id:"preset-"+Date.now(),title:presetTitle.text,frequencyHz:Number(presetFrequency.text),detail:presetDetail.text})
    }
}
