import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    workspaceKey:"Sync"
    CanvasPanel { panelKey:"safety"; title:"Wavelog credential and sync safety"; defaultWidth:parent?parent.width:1200; defaultHeight:96
        SafetyBanner { anchors.fill:parent; text:"API-v2 token is stored only under a Credential Manager alias. SQLite and configuration store the alias, never the token. No authenticated success is claimed until a real operator test." }
    }
    CanvasPanel { panelKey:"binding"; title:"One Wavelog binding"; defaultY:108; defaultWidth:parent?parent.width:1200; defaultHeight:300
        GridLayout { anchors.fill:parent; columns:2
            Label{text:"Server URL (HTTPS)"} TextField{id:server;Layout.fillWidth:true;text:"https://om0rx.wavelog.online/index.php"}
            Label{text:"Credential alias"} TextField{id:alias;Layout.fillWidth:true;text:"wavelog-primary"}
            Label{text:"API-v2 token"} TextField{id:token;Layout.fillWidth:true;echoMode:TextInput.Password;placeholderText:"wl2_… (never persisted outside vault)"}
            Label{text:"Local station profile"} TextField{id:localStation;Layout.fillWidth:true;text:"OM0RX"}
            Label{text:"Remote station ID"} TextField{id:remoteStation;Layout.fillWidth:true}
            Label{text:"Permission"} CheckBox{id:write;checked:true;text:"qso:write (read remains required)"}
            Item{} RowLayout{Button{text:"Store token in vault";onClicked:CredentialVault.store(alias.text,"ShackCQ Wavelog API v2",token.text)} Button{text:"Save binding";onClicked:Wavelog.configureBinding(server.text,alias.text,localStation.text,remoteStation.text,write.checked)}}
        }
    }
    CanvasPanel { panelKey:"sync-actions"; title:"Synchronization actions"; defaultY:420; defaultWidth:parent?parent.width:1200; defaultHeight:104
        RowLayout { anchors.fill:parent
            StatusChip{text:Wavelog.state;kind:Wavelog.state==="Synchronized"?"healthy":Wavelog.state==="Error"?"danger":"hold"}
            Label{text:Wavelog.pendingCount+" outbox • "+Wavelog.conflictCount+" conflicts";color:"#f2efe7"}
            Item{Layout.fillWidth:true}
            Button{text:"Initial sync";onClicked:Wavelog.synchronize("INITIAL")}
            Button{text:"Quick sync";onClicked:Wavelog.synchronize("QUICK")}
            Button{text:"Full reconciliation";onClicked:Wavelog.synchronize("FULL")}
            Button{text:"Retry safe operations";onClicked:Wavelog.retryPending()}
        }
    }
    CanvasPanel { panelKey:"conflicts"; title:"Conflict and ambiguous-write review"; defaultY:536; defaultWidth:parent?parent.width:1200; defaultHeight:parent?parent.height-536:110
        EmptyState { anchors.fill:parent; title:"Conflict and ambiguous-write review"; detail:"Keep Local, Keep Remote, and Merge are supported by the sync engine. Ambiguous create/delete results remain blocked until a scan identifies the exact remote QSO; blind retries are never issued." }
    }
}
