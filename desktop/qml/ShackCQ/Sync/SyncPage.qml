import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../Components"

WorkspaceCanvas {
    workspaceKey:"Sync"
    CanvasPanel { panelKey:"safety"; title:"Wavelog credential and sync safety"; defaultWidth:parent?parent.width:1200; defaultHeight:96
        SafetyBanner { anchors.fill:parent; text:"The stock Wavelog API key or v2 token is stored only under a Credential Manager alias. SQLite stores the alias, never the secret. Legacy v1 supports read/create only; it does not supply update, delete, rotor, audio, digital-mode or PTT control." }
    }
    CanvasPanel { panelKey:"binding"; title:"One Wavelog binding"; defaultY:108; defaultWidth:parent?parent.width:1200; defaultHeight:300
        GridLayout { anchors.fill:parent; columns:2
            Label{text:"Server URL (HTTPS)"} TextField{id:server;Layout.fillWidth:true;placeholderText:"https://log.example/index.php"}
            Label{text:"API mode"} ComboBox{id:apiMode;Layout.fillWidth:true;model:["Auto detect","Stock Wavelog v1","Wavelog v2"]}
            Label{text:"Credential alias"} TextField{id:alias;Layout.fillWidth:true;placeholderText:"wavelog-primary"}
            Label{text:"API key / token"} TextField{id:token;Layout.fillWidth:true;echoMode:TextInput.Password;placeholderText:"Stock API key or wl2_… token"}
            Label{text:"Local station profile"} TextField{id:localStation;Layout.fillWidth:true;placeholderText:"Your local station profile"}
            Label{text:"Remote station ID"} TextField{id:remoteStation;Layout.fillWidth:true}
            Label{text:"Permission"} CheckBox{id:write;checked:true;text:"qso:write (read remains required)"}
            Item{} RowLayout{Button{text:"Store credential in vault";onClicked:CredentialVault.store(alias.text,"ShackCQ standalone Wavelog",token.text)} Button{text:"Save binding";onClicked:Wavelog.configureBinding(server.text,alias.text,localStation.text,remoteStation.text,write.checked,apiMode.currentIndex===1?"LEGACY":apiMode.currentIndex===2?"V2":"AUTO")}}
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
    CanvasPanel { panelKey:"wavelog-radio"; title:"Wavelog radio compatibility"; defaultY:536; defaultWidth:parent?parent.width:1200; defaultHeight:132
        GridLayout { anchors.fill:parent; columns:2
            Label{text:"Published radio name"} TextField{id:radioName;Layout.fillWidth:true;placeholderText:"ShackCQ Desktop"}
            Label{text:"Capabilities"} Label{Layout.fillWidth:true;wrapMode:Text.WordWrap;text:"Radio state publish. Legacy v1 can advertise a loopback click-to-tune callback. Standard Wavelog does not grant rotor, digital audio, transmit or PTT control."}
            Label{text:WavelogRadio.state} RowLayout{Button{text:"Publish using this profile";onClicked:WavelogRadio.configure(server.text,alias.text,radioName.text,apiMode.currentIndex===1?"LEGACY":apiMode.currentIndex===2?"V2":"AUTO")} Button{text:"Stop publishing";onClicked:WavelogRadio.stop()}}
        }
    }
    CanvasPanel { panelKey:"conflicts"; title:"Conflict and ambiguous-write review"; defaultY:680; defaultWidth:parent?parent.width:1200; defaultHeight:parent?parent.height-680:110
        EmptyState { anchors.fill:parent; title:"Conflict and ambiguous-write review"; detail:"Keep Local, Keep Remote, and Merge are supported by the sync engine. Ambiguous create/delete results remain blocked until a scan identifies the exact remote QSO; blind retries are never issued." }
    }
}
