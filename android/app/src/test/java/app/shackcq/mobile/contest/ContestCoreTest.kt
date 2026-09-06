package app.shackcq.mobile.contest

import org.junit.Assert.*
import org.junit.Test

class ContestCoreTest {
    private val registry=ContestRuleRegistry()
    private fun definition(id:String)=registry.require(ContestDefinitionId(id)).definition
    private fun qso(id:String="q1",band:ContestBand=ContestBand.B20,mode:ContestMode=ContestMode.CW,station:ContestEntityInfo=ContestEntityInfo(dxcc="291",continent="NA",ituZone="8",cqZone="5",isWve=true,isOceania=false),worked:ContestEntityInfo=ContestEntityInfo(dxcc="230",continent="EU",ituZone="28",cqZone="15",wpxPrefix="OM0",isWve=false,isOceania=false),created:Long=1_000)=ContestQsoDraft(id,"OM0RX",created,14_050_000,band,mode,"599","599",worked=worked,station=station,received=mapOf(ContestExchangeField.CQ_ZONE to "15"))
    private fun session(id:String="cq-ww-cw")=ContestSession(ContestSessionId("s1"),ContestDefinitionId(id),ContestRuleVersion("1.0.0"),"Test",0,999999,"N0CALL","FN31",ContestEntityInfo(dxcc="291",continent="NA",ituZone="8",cqZone="5",stateProvince="CT",isWve=true,isOceania=false),ContestCategory(mode=ContestMode.CW),listOf("N0CALL"))

    @Test fun allInitialPacksLoadWithOfficialAuthorityAndGoldenVectors(){
        assertEquals(13,registry.all().size)
        registry.all().forEach{pack->assertTrue(pack.testVectorIds.isNotEmpty());assertTrue(pack.definition.officialSources.all{it.sha256.length==64&&it.sha256.toSet()!=setOf('0')});assertFalse(ContestRuleValidator.validate(pack).any{it.truth==ContestTruth.INVALID})}
    }

    @Test fun representativeOfficialPointFamiliesAreDeterministic(){
        val engine=ContestScoringEngine();assertEquals(3,engine.points(definition("cq-ww-cw"),qso()).points)
        assertEquals(6,engine.points(definition("cq-wpx-cw"),qso(band=ContestBand.B40)).points)
        assertEquals(3,engine.points(definition("arrl-dx-cw"),qso()).points)
        assertEquals(5,engine.points(definition("iaru-hf"),qso()).points)
        assertEquals(2,engine.points(definition("arrl-field-day"),qso()).points)
        assertEquals(10,engine.points(definition("cq-160-cw"),qso(band=ContestBand.B160)).points)
        val oceania=qso(station=ContestEntityInfo(dxcc="150",continent="OC",isOceania=true),worked=ContestEntityInfo(dxcc="291",continent="NA",wpxPrefix="K1",isOceania=false))
        assertEquals(1,engine.points(definition("oceania-dx-cw"),oceania).points)
    }

    @Test fun duplicateAndMultiplierScopesMatchScoreAuthority(){
        val d=definition("cq-ww-cw");val first=qso();val same=qso("q2",created=2_000);val otherBand=qso("q3",ContestBand.B40,created=3_000)
        assertEquals(ContestDupeState.DUPLICATE,ContestDupeEngine().evaluate(d,same,listOf(first)))
        assertEquals(ContestDupeState.NEW,ContestDupeEngine().evaluate(d,otherBand,listOf(first)))
        assertTrue(ContestMultiplierEngine().evaluate(d,first,emptyList()).all{it.isNew})
        assertTrue(ContestMultiplierEngine().evaluate(d,same,listOf(first)).none{it.isNew})
    }

    @Test fun incrementalAndFullRebuildRemainEqualAfterEditDelete(){
        val d=definition("cq-ww-cw");val rows=listOf(qso(),qso("q2",ContestBand.B40,created=2_000));val engine=ContestScoreEngine()
        val incremental=engine.incremental(d,listOf(rows[0]),rows[1],3_000);val rebuilt=engine.rebuild(d,rows,3_000)
        assertEquals(rebuilt.copy(generatedAt=0),incremental.copy(generatedAt=0))
        assertEquals(3,engine.rebuild(d,rows.drop(1),3_000).points)
        assertEquals(0,engine.rebuild(d,listOf(rows[0],rows[0].copy(worked=rows[0].station)),3_000).zeroPointValidQsos)
    }

    @Test fun serialCommitsOnlyAfterCanonicalSave(){
        val store=InMemoryContestSerialStore();val authority=ContestSerialAuthority(store){100};val session=session("cq-wpx-cw");val definition=definition("cq-wpx-cw")
        val failing=ContestRepository(object:ContestQsoMutationPort{override fun create(qso:app.shackcq.mobile.Qso,networkOrigin:Boolean)=false;override fun update(qso:app.shackcq.mobile.Qso,networkOrigin:Boolean){};override fun delete(id:String,networkOrigin:Boolean){}},null,authority)
        val draft=qso().copy(received=mapOf(ContestExchangeField.SERIAL to "12"));assertFalse(failing.save(session,definition,draft,"radio1").accepted)
        assertTrue(store.reservations(session.id).all{it.state!=ContestSerialState.COMMITTED})
    }

    @Test fun canonicalMapperPreservesStandardContestFieldsAndPrivateSessionLink(){
        val mapped=ContestQsoMapper.toCanonical(session("cq-wpx-cw"),definition("cq-wpx-cw"),qso().copy(sent=mapOf(ContestExchangeField.SERIAL to "7"),received=mapOf(ContestExchangeField.SERIAL to "12")))
        assertEquals("CQ-WPX-CW",mapped.contestId);assertEquals("7",mapped.extraAdifFields["STX"]);assertEquals("12",mapped.extraAdifFields["SRX"]);assertEquals("s1",mapped.extraAdifFields["APP_SHACKCQ_CONTEST_SESSION"])
    }
}
