import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    workspaceKey: "Groups.io"

    CanvasPanel {
        panelKey: "safety"
        title: "Groups.io safety state"
        defaultWidth: parent ? parent.width : 1200
        defaultHeight: 96
        SafetyBanner { anchors.fill:parent; text:"Groups.io uses its own schema-2 store and credential alias. Refresh is foreground-only; ambiguous delivery and moderation remain visible; nothing posts automatically." }
    }

    CanvasPanel {
        panelKey: "archive-tools"
        title: "Offline archive tools"
        defaultY:108
        defaultWidth:parent?parent.width:1200
        defaultHeight:100
        RowLayout { anchors.fill:parent
            TextField { id:credentialAlias; text:Parity.groupsCredentialAlias; placeholderText:"Credential alias"; Layout.preferredWidth:180 }
            Button { text:"Bind"; onClicked:Parity.setGroupsCredentialAlias(credentialAlias.text) }
            TextField { id:search; placeholderText:"Search offline archive"; Layout.fillWidth:true }
            Button { text:"Refresh"; onClicked:Parity.refreshGroupsMemberships(); ToolTip.text:"Foreground authenticated refresh"; ToolTip.visible:hovered }
            Button { text:"New topic"; onClicked:composer.open() }
            StatusChip { text:"OUTBOX "+Parity.groupsOutbox.count; kind:Parity.groupsOutbox.count > 0 ? "hold" : "neutral" }
        }
    }

    CanvasPanel {
        panelKey:"messages"
        title:"Groups.io messages"
        defaultY:220
        defaultWidth:parent?parent.width:1200
        defaultHeight:parent?parent.height-220:400
        ColumnLayout { anchors.fill:parent
            RowLayout { Layout.fillWidth:true
                ComboBox { id:membership; Layout.fillWidth:true; model:Parity.groupsMemberships; textRole:"title"; valueRole:"key" }
                Button { text:"Topics"; enabled:membership.currentValue !== undefined; onClicked:Parity.refreshGroupsTopics(String(membership.currentValue)) }
                ComboBox { id:topic; Layout.fillWidth:true; model:Parity.groupsTopics; textRole:"title"; valueRole:"key" }
                Button { text:"Messages"; enabled:membership.currentValue !== undefined && topic.currentValue !== undefined; onClicked:Parity.refreshGroupsMessages(String(membership.currentValue),String(topic.currentValue)) }
                ComboBox { id:outbox; Layout.preferredWidth:180; model:Parity.groupsOutbox; textRole:"title"; valueRole:"key" }
                Button { text:"Send reviewed"; enabled:outbox.currentValue !== undefined; onClicked:Parity.sendGroupsOutbox(String(outbox.currentValue)) }
            }
            WorkspaceList { Layout.fillWidth:true; Layout.fillHeight:true; sourceModel:Parity.groupsMessages; actionText:"Open"; emptyTitle:"Offline archive is empty"; emptyDetail:"Configure an account credential alias and refresh explicitly."; onActionRequested:item=>Parity.prepareReceiveReview("Groups.io topic",item) }
        }
    }

    Dialog { id:composer; title:"Prepare Groups.io topic"; modal:true; standardButtons:Dialog.Cancel|Dialog.Ok; width:560
        ColumnLayout { anchors.fill:parent
            TextField { id:groupId; placeholderText:"Exact group ID"; Layout.fillWidth:true }
            TextField { id:subject; placeholderText:"Subject"; Layout.fillWidth:true }
            TextArea { id:body; placeholderText:"Message"; Layout.fillWidth:true; Layout.preferredHeight:180; wrapMode:TextEdit.Wrap }
            Label { text:"OK saves a reviewed draft only. Sending requires authenticated foreground confirmation."; color:"#98a0a6"; wrapMode:Text.WordWrap; Layout.fillWidth:true }
        }
        onAccepted:Parity.queueGroupsDraft({groupId:groupId.text,subject:subject.text,body:body.text})
    }
}
