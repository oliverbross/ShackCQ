package app.shackcq.mobile

import java.time.Instant
import java.time.ZoneOffset

internal class PortableLogRepository(private val database:QsoDatabase){
    private data class History(val callsign:String,val band:String,val mode:String,val createdAt:Long)
    fun states(spots:List<PortableSpot>,now:Long):Map<String,Map<PortableProgram,PortableWorkedState>>{
        if(spots.isEmpty())return emptyMap();val refs=spots.flatMap{it.references}.distinctBy{it.program to it.code};val keys=refs.map{"${it.program.name}:${it.code.uppercase()}"}
        val history=if(keys.isEmpty())emptyMap()else{val placeholders=keys.joinToString(","){"?"};database.readableDatabase.rawQuery("SELECT r.program||':'||r.reference_norm,p.callsign_norm,p.band_norm,p.mode_family,p.created_at FROM qso_reference r JOIN qso_projection p ON p.qso_id=r.qso_id WHERE r.direction='WORKED' AND r.program||':'||r.reference_norm IN ($placeholders)",keys.toTypedArray()).use{c->buildMap<String,MutableList<History>>{while(c.moveToNext())getOrPut(c.getString(0)){mutableListOf()}.add(History(c.getString(1),c.getString(2),c.getString(3),c.getLong(4)))}}}
        val calls=spots.map{it.callsign.trim().uppercase()}.distinct();val workedCalls=if(calls.isEmpty())emptySet()else{val placeholders=calls.joinToString(","){"?"};database.readableDatabase.rawQuery("SELECT DISTINCT callsign_norm FROM qso_projection WHERE callsign_norm IN ($placeholders)",calls.toTypedArray()).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}}
        val day=Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return spots.associate{spot->spot.id to spot.references.associate{ref->val rows=history["${ref.program.name}:${ref.code.uppercase()}"].orEmpty();val family=modeFamily(spot.mode);ref.program to PortableWorkedState(rows.isNotEmpty(),rows.any{it.band.equals(spot.band,true)},rows.any{it.mode==family},spot.callsign.uppercase() in workedCalls,rows.any{it.callsign.equals(spot.callsign,true)&&it.band.equals(spot.band,true)&&it.mode==family&&it.createdAt>=day})}}
    }
    fun potaStates(spots:List<PotaSpot>,now:Long):Map<String,PotaWorkedState>{val portable=spots.map(PotaSpot::toPortable);val states=states(portable,now);return spots.associate{spot->val state=states[spot.id]?.get(PortableProgram.POTA)?:PortableWorkedState();spot.id to PotaWorkedState(state.referenceWorked,state.bandWorked,state.modeWorked,state.callWorked,state.workedToday)}}
}
