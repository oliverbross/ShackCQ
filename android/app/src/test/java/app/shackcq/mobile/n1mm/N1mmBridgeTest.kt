package app.shackcq.mobile.n1mm

import app.shackcq.mobile.contest.*
import org.junit.Assert.*
import org.junit.Test

class N1mmBridgeTest {
    private val definition=ContestRuleRegistry().require(ContestDefinitionId("cq-ww-cw")).definition
    private val session=ContestSession(ContestSessionId("s"),definition.id,definition.version,"CQWW",0,9_999_999_999,"OM0RX","JN88",ContestEntityInfo(cqZone="15"),ContestCategory(mode=ContestMode.CW),listOf("OM0RX"))
    private fun command(id:String="0123456789abcdef0123456789abcdef"):N1mmTypedCommand{val values=N1mmContact.FIELD_NAMES.associateWith{when(it){"Timestamp"->"2026-08-22 12:00:00";"CallSign"->"K1ABC";"Freq","XmitFrequency"->"1407400";"Mode"->"CW";"SNT","RCV"->"599";"ZN"->"5";"Continent"->"NA";"Id"->id;"IsOriginal"->"1";else->""}};return N1mmTypedCommand(N1mmCommand.QSO,mapOf("oldTimestamp" to "old")+values)}
    @Test fun trustedSafeAddStagesAndPairedReplayIsDeduped(){val staged=mutableListOf<ContestQsoDraft>();val bridge=N1mmQsoBridge(N1mmContestStagingPort{_,draft,_->staged+=draft;true});val context=N1mmPolicyContext(N1mmMode.TRUSTED_LAN_AUTO_ACCEPT_SAFE_ADDS,true,true,true);val first=bridge.receiveAdd(command(),"STATION-A",context,session,definition,"peer");assertEquals(N1mmBridgeState.ACCEPTED,first.state);assertEquals(1,staged.size);assertTrue(staged.single().networkOriginId.contains("STATION-A"));val replay=bridge.receiveAdd(command(),"STATION-A",context,session,definition,"peer");assertEquals(N1mmBridgeState.DUPLICATE,replay.state);assertEquals(1,staged.size)}
    @Test fun threeStationEchoesDoNotCollapseDistinctRemoteIdentities(){val ids=mutableSetOf<String>();val bridge=N1mmQsoBridge(N1mmContestStagingPort{_,draft,_->ids.add(draft.qsoId)});val context=N1mmPolicyContext(N1mmMode.TRUSTED_LAN_AUTO_ACCEPT_SAFE_ADDS,true,true,true);assertEquals(N1mmBridgeState.ACCEPTED,bridge.receiveAdd(command(),"A",context,session,definition,"peer").state);assertEquals(N1mmBridgeState.ACCEPTED,bridge.receiveAdd(command(),"B",context,session,definition,"peer").state);assertEquals(2,ids.size);assertEquals(N1mmBridgeState.REVIEW_REQUIRED,bridge.receiveEditOrDelete(N1mmTypedCommand(N1mmCommand.QSODELETE,emptyMap())).state)}
}
