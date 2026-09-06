package app.shackcq.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ProgressModelsTest {
    @Test fun generalSatelliteAndAntennaAnalyticsShareTheFilteredDataset() {
        val now = 1_700_000_000L
        val sat = qso("sat", now, dxcc = "291").copy(operatorCallsign = "OM0RX", band = "SAT", grid = "JN88TQ",
            antennaPath = "SAT-YAGI", distanceKm = 1_200.0, lotwReceived = "Y",
            extraAdifFields = mapOf("SAT_NAME" to "QO-100", "SAT_MODE" to "S"))
        val hf = qso("hf", now + 86_400, dxcc = "504").copy(operatorCallsign = "OM0RX", antennaPath = "DIPOLE",
            distanceKm = 500.0, eqslReceived = "Y")
        val result = buildProgressSnapshot(listOf(sat, hf), ProgressFilters(allStations = true), now = now + 90_000)
        assertEquals(2, result.operators["OM0RX"])
        assertEquals(1, result.confirmations["LoTW"])
        assertEquals(1, result.confirmations["eQSL"])
        assertEquals(1, result.satellite.qsos)
        assertEquals(mapOf("QO-100" to 1), result.satellite.bySatellite)
        assertEquals(setOf("SAT-YAGI", "DIPOLE"), result.antennas.map { it.path }.toSet())
    }
    private val now = LocalDate.of(2026, 8, 18).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    @Test fun stationTimeBandAndModeFiltersAreAppliedTogether() {
        val rows = listOf(qso("1", now-100, "home", "20m", "CW"), qso("2", now-100, "field", "20m", "CW"),
            qso("3", now-40*86_400, "home", "20m", "CW"), qso("4", now-100, "home", "40m", "SSB"))
        val filter = ProgressFilters(stationProfileId="home", period=ProgressPeriod.DAYS_30, band="20m", mode=ProgressMode.CW)
        assertEquals(listOf("1"), filterProgressQsos(rows, filter, now).map(Qso::id))
        assertEquals(4, filterProgressQsos(rows, ProgressFilters(allStations=true), now).size)
    }

    @Test fun includingDeletedRowsDoesNotCollapseTheSqlDataset() {
        assertNull(progressDeletedSqlClause(includeDeleted = true))
        assertEquals(
            "p.sync_state NOT IN ('TOMBSTONE','REMOTE_DELETED')",
            progressDeletedSqlClause(includeDeleted = false),
        )
    }

    @Test fun modeFamiliesReuseShackCQNormalisation() {
        assertEquals("CW", progressModeFamily("CW-R")); assertEquals("PHONE", progressModeFamily("USB"))
        assertEquals("DIGITAL", progressModeFamily("FT8")); assertEquals("OTHER", progressModeFamily("SSTV"))
    }

    @Test fun onlyLotwOrPaperQslYVConfirmAwardLayer() {
        val qrz=qso("1",now,dxcc="1").copy(qrzReceived="Y",eqslReceived="Y")
        val lotw=qso("2",now,dxcc="2").copy(lotwReceived="V")
        val paper=qso("3",now,dxcc="3").copy(qslReceived="Y")
        val uploaded=qso("4",now,dxcc="4").copy(qrzSent="Y",clublogSent="Y",eqslSent="Y")
        val result=buildProgressSnapshot(listOf(qrz,lotw,paper,uploaded),ProgressFilters(allStations=true))
        assertFalse(isAwardConfirmed(qrz));assertFalse(isAwardConfirmed(uploaded))
        assertEquals(4,result.dxcc.worked);assertEquals(2,result.dxcc.confirmed)
        assertTrue(result.unconfirmedDxcc.any{it.first=="1"&&it.second.single().eqslReceived=="Y"})
    }

    @Test fun dxccCountsAreUniqueAndSixtyMetresIsNotAnAwardBand() {
        val rows=(1..100).map{qso("$it",now,band=if(it==1)"60m" else "20m",dxcc=it.toString())}+qso("d",now,dxcc="1")
        val result=buildProgressSnapshot(rows,ProgressFilters(allStations=true))
        assertEquals(100,result.dxcc.worked);assertFalse(result.dxccByBand.containsKey("60m"))
        assertEquals(100,result.dxccByBand.getValue("20m").worked)
    }

    @Test fun wasUsesOnlyFiftyStatesAndWazOnlyZonesOneToForty() {
        val rows=listOf(qso("1",now,state="CA",zone="1"),qso("2",now,state="PR",zone="40"),
            qso("3",now,state="DC",zone="0"),qso("4",now,zone="41"))
        val result=buildProgressSnapshot(rows,ProgressFilters(allStations=true))
        assertEquals(1,result.states.worked);assertEquals(2,result.zones.worked)
        assertFalse(canonicalUsStates.contains("PR"));assertFalse(canonicalUsStates.contains("DC"))
    }

    @Test fun qrpRequiresKnownPositivePowerAtMostFiveWatts() {
        val rows=listOf(qso("1",now,dxcc="1",power=5),qso("2",now,dxcc="2",power=1),
            qso("3",now,dxcc="3",power=0),qso("4",now,dxcc="4",power=6))
        val result=buildProgressSnapshot(rows,ProgressFilters(allStations=true))
        assertEquals(2,result.qrpQsos);assertEquals(2,result.qrpDxcc);assertEquals(3,result.coverage.getValue("TX power").available)
    }

    @Test fun portableDirectionsMultiParkTotalsSuccessAndP2pStayDistinct() {
        val rows=(1..10).map{index->qso("$index",now+index,dxcc="1").copy(activationSessionId="session",
            activationProgram="POTA",myPotaRef="OM-0001",myPotaRefs=listOf("OM-0001","OM-0002"),
            potaRef="OM-0099",potaRefs=listOf("OM-0099"))}
        val result=buildProgressSnapshot(rows,ProgressFilters(allStations=true))
        assertEquals(10,result.totalQsos);assertEquals(setOf("OM-0099"),result.portable.potaHunted)
        assertEquals(setOf("OM-0001","OM-0002"),result.portable.potaActivated)
        assertEquals(2,result.portable.successfulActivations);assertEquals(10,result.portable.p2pQsos)
        assertEquals(1,result.portable.activations.size)
    }

    @Test fun olderOwnParkRowsDoNotFabricateSessions() {
        val result=buildProgressSnapshot(listOf(qso("1",now).copy(myPotaRef="OM-0001")),ProgressFilters(allStations=true))
        assertEquals(setOf("OM-0001"),result.portable.potaActivated);assertTrue(result.portable.activations.isEmpty())
    }

    @Test fun sotaCatalogueJoinsWhileWwffHasNoDenominator() {
        val summit=SotaSummit("OM/ZA-001","Slovakia","Zilina","Peak",1700,5500,8,3,49.1,19.0,"JN99","2006-01-01","")
        val row=qso("1",now).copy(sotaRef="OM/ZA-001",wwffRef="OMFF-0001")
        val result=buildProgressSnapshot(listOf(row),ProgressFilters(allStations=true),sotaSummits=mapOf(summit.code to summit))
        assertEquals(setOf("Slovakia"),result.portable.sotaAssociations);assertEquals(setOf("Zilina"),result.portable.sotaRegions)
        assertEquals(setOf("OMFF-0001"),result.portable.wwffHunted)
    }

    @Test fun liveNeedsExcludeUnresolvedDxAndMatchResolvedDxAndPortable() {
        val park=PortableSpot("p1",setOf(PortableProgram.POTA),"OM0RX/P",7_032_000,"CW",
            listOf(PortableReference(PortableProgram.POTA,"OM-0001")),now,now+3_600,"POTA")
        val result=buildProgressSnapshot(emptyList(),ProgressFilters(allStations=true),
            dxSpots=listOf(dxSpot("resolved","K1ABC"),dxSpot("unresolved","BAD")),portableSpots=listOf(park),now=now,
            ctyLookup={if(it=="K1ABC")AndroidCtyRecord("United States","291","NA",cqZone="5")else null})
        assertTrue(result.needs.any{it.id=="dx:resolved"&&it.reasons.any { reason -> reason.startsWith("NEEDED DXCC") }})
        assertFalse(result.needs.any{it.id=="dx:unresolved"});assertTrue(result.needs.any{"NEW POTA REFERENCE" in it.reasons})
    }

    @Test fun pinnedGoalsRoundTripCapAndProgressAreDeterministic() {
        val goals=(1..5).map{ProgressGoal("$it",ProgressGoalMetric.TOTAL_QSOS,it*10,"Goal $it")}
        val restored=decodeProgressGoals(encodeProgressGoals(goals))
        assertEquals(4,restored.size);assertEquals("Goal 1",restored.first().name)
        val result=buildProgressSnapshot(listOf(qso("1",now)),ProgressFilters(allStations=true),restored)
        assertEquals(1,result.goals.first().current);assertEquals(9,result.goals.first().remaining)
    }

    @Test fun coverageUnknownsStayUnknownAndProgressCreatesNoNetworkRequest() {
        var lookups=0
        val result=buildProgressSnapshot(listOf(qso("1",now,dxcc="291")),ProgressFilters(allStations=true),
            ctyLookup={lookups++;null})
        assertEquals(1,result.coverage.getValue("DXCC").available);assertEquals(0,result.coverage.getValue("Grid").available)
        assertEquals(0,lookups)
    }

    @Test fun majorAwardFamiliesShareOneFilteredSnapshot() {
        val row = qso("1", now, dxcc="291", state="CA", zone="5", power=5).copy(country="United States",
            continent="NA", ituZone="8", iota="NA-001", potaRef="K-0001", sotaRef="W1/AA-001",
            wwffRef="KFF-0001", lotwReceived="Y")
        val result = buildProgressSnapshot(listOf(row), ProgressFilters(allStations=true))
        assertEquals(AwardKind.entries.toSet(), result.awards.keys)
        assertEquals(1, result.awards.getValue(AwardKind.DXCC).count.confirmed)
        assertEquals(setOf("5"), result.awards.getValue(AwardKind.WAZ).units.map(AwardUnit::code).toSet())
        assertEquals(setOf("8"), result.awards.getValue(AwardKind.ITU).units.map(AwardUnit::code).toSet())
        assertEquals(1, result.awards.getValue(AwardKind.WAC).count.worked)
        assertEquals(1, result.awards.getValue(AwardKind.WAS).count.worked)
        assertEquals("K1", result.awards.getValue(AwardKind.WPX).units.single().code)
        assertEquals(1, result.awards.getValue(AwardKind.QRP).count.worked)
    }

    @Test fun intelligenceFiltersAndDrillThroughUseTheSameContract() {
        val filters = ProgressFilters(allStations=true, period=ProgressPeriod.DAYS_30, band="20m", mode=ProgressMode.CW,
            operator="OM0RX", confirmationSource="LOTW", portableProgram="POTA")
        val base = progressLogbookFilter(filters, now)
        assertEquals("20m", base.band); assertEquals("CW", base.modeFamily); assertEquals("OM0RX", base.operator)
        assertEquals("LOTW", base.confirmationSource); assertEquals("POTA", base.portableProgram)
        assertEquals("291", logbookFilterForDimension("dxcc", "291", base).dxcc)
        assertEquals("1..5", logbookFilterForDimension("qrp", "", base).txPower)
    }

    @Test fun geographyConfirmationsAndOperatorDimensionsRemainInspectable() {
        val rows = listOf(qso("1",now,dxcc="291").copy(country="United States",continent="NA",ituZone="8",grid="FN31",
            operatorCallsign="OM0RX",stationProfileId="home",radioModel="KX3",lotwReceived="Y",distanceKm=7000.0),
            qso("2",now+86_400,dxcc="230").copy(country="Germany",continent="EU",ituZone="28",grid="JO40",
                operatorCallsign="OM0RX",stationProfileId="field",radioModel="KX3",eqslReceived="Y",distanceKm=900.0))
        val result = buildProgressSnapshot(rows,ProgressFilters(allStations=true))
        assertEquals(2,result.activeDays); assertEquals(2,result.geography.size)
        assertEquals(1,result.confirmationDetails.getValue("LoTW").confirmed)
        assertEquals(1,result.confirmationDetails.getValue("eQSL").confirmed)
        assertEquals(2,result.operators.getValue("OM0RX")); assertEquals(2,result.radios.getValue("KX3"))
        assertEquals("K1ABC",result.bestDx.first().callsign)
    }

    @Test fun needsReuseAwardUnitsForDxAndAllPortableProgrammes() {
        val spots = listOf(
            PortableSpot("p",setOf(PortableProgram.POTA),"K1P",14_032_000,"CW",listOf(PortableReference(PortableProgram.POTA,"K-0001")),now,now+3600,"POTA"),
            PortableSpot("s",setOf(PortableProgram.SOTA),"K1S",14_032_000,"CW",listOf(PortableReference(PortableProgram.SOTA,"W1/AA-001")),now,now+3600,"SOTA"),
            PortableSpot("w",setOf(PortableProgram.WWFF),"K1W",14_032_000,"CW",listOf(PortableReference(PortableProgram.WWFF,"KFF-0001")),now,now+3600,"WWFF"))
        val result=buildProgressSnapshot(emptyList(),ProgressFilters(allStations=true),dxSpots=listOf(dxSpot("dx","K1ABC")),
            portableSpots=spots,now=now,ctyLookup={AndroidCtyRecord("United States","291","NA",cqZone="5",ituZone="8")})
        assertTrue(result.needs.first { it.id=="dx:dx" }.reasons.any { it.startsWith("NEEDED ITU ZONE") })
        assertTrue(result.needs.any { "NEW POTA REFERENCE" in it.reasons })
        assertTrue(result.needs.any { "NEW SOTA SUMMIT" in it.reasons })
        assertTrue(result.needs.any { "NEW WWFF REFERENCE" in it.reasons })
    }

    private fun qso(id:String,epoch:Long,profile:String="",band:String="20m",mode:String="CW",dxcc:String="",
        state:String="",zone:String="",power:Int=0)=Qso(id,"K${id.take(4)}ABC",
        if(band=="40m")7_032_000 else 14_032_000,mode,"599","599",epoch,band=band,stationProfileId=profile,
        stationCallsign="OM0RX",dxcc=dxcc,state=state,cqZone=zone,txPowerW=power)

    private fun dxSpot(id:String,call:String)=AndroidDXSpot(id,call,"SPOTTER",14_032_000,now,"20m","CW","","",
        0,0,0.0,0.0,"",10,90,1,false,false,false,false,false,false,false,0,0,"","")
}
