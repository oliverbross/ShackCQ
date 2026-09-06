package app.shackcq.mobile.contest

import org.junit.Assert.*
import org.junit.Test

class ContestEsmExportTest {
    private val definition=ContestRuleRegistry().require(ContestDefinitionId("cq-wpx-cw")).definition
    private val session=ContestSession(ContestSessionId("s"),definition.id,definition.version,"WPX",0,999999,"OM0RX","JN88",ContestEntityInfo(),ContestCategory(mode=ContestMode.CW),listOf("OM0RX"))
    @Test fun blankEnterEmitsIntentOnlyAndNeverLogs(){
        val engine=ContestEsmEngine();val run=engine.transition(ContestEsmSnapshot(),ContestEsmEvent.EnterPressed,session,definition,7)
        assertEquals(ContestKeyerIntentType.CQ,(run.actions.single() as ContestEsmAction.EmitKeyerIntent).intent.type);assertFalse(run.actions.any{it is ContestEsmAction.LogQso})
        val sp=engine.transition(ContestEsmSnapshot(role=ContestOperatingRole.SEARCH_AND_POUNCE),ContestEsmEvent.EnterPressed,session,definition,7)
        assertEquals(ContestKeyerIntentType.MY_CALL,(sp.actions.single() as ContestEsmAction.EmitKeyerIntent).intent.type)
    }
    @Test fun dupeBlocksSilentLogAndEscapeEmitsStop(){
        val engine=ContestEsmEngine();val dupe=engine.transition(ContestEsmSnapshot(state=ContestEsmState.CALL_ENTERED),ContestEsmEvent.CallValidated(ContestDupeState.DUPLICATE),session,definition,1)
        assertEquals(ContestEsmState.DUPE_REVIEW,dupe.snapshot.state);assertFalse(dupe.actions.any{it is ContestEsmAction.LogQso})
        val escape=engine.transition(dupe.snapshot,ContestEsmEvent.EscapePressed,session,definition,1);assertEquals(ContestKeyerIntentType.STOP,(escape.actions.first() as ContestEsmAction.EmitKeyerIntent).intent.type)
    }
    @Test fun exportsAreBoundedSequencesWithValidation(){
        val qso=ContestQsoDraft("q","K1ABC",1000,14_050_000,ContestBand.B20,ContestMode.CW,"599","599",sent=mapOf(ContestExchangeField.SERIAL to "1"),received=mapOf(ContestExchangeField.SERIAL to "2"))
        val cab=ContestExport.cabrillo(session,definition,ContestScoreSnapshot(claimedScore=3),sequenceOf(qso));val lines=cab.lines.toList()
        assertEquals(ContestExportState.VALID,cab.state);assertEquals("START-OF-LOG: 3.0",lines.first());assertEquals("END-OF-LOG:",lines.last());assertTrue(lines.any{it.startsWith("QSO:")})
        val adif=ContestExport.adif(session,definition,sequenceOf(qso)).toList();assertTrue(adif[1].contains("<CONTEST_ID:9>CQ-WPX-CW"));assertTrue(adif[1].contains("<STX:1>1"))
    }
    @Test fun restorePausesRunningSessionWithoutArmingNetworkOrKeyer(){val restored=ContestSessionController().restored(session.copy(state=ContestSessionState.RUNNING,networkArmed=true,keyerArmed=true));assertEquals(ContestSessionState.PAUSED,restored.state);assertFalse(restored.networkArmed);assertFalse(restored.keyerArmed)}
}
