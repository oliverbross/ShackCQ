package app.shackcq.mobile

internal data class DxLocalSummary(val qsos:Int,val confirmed:Int,val lastWorked:Long?)

internal class OperationsLogRepository(private val database:QsoDatabase){
    fun callsigns(values:Collection<String>):Map<String,DxLocalSummary>{val calls=values.map{it.trim().uppercase()}.filter(String::isNotBlank).distinct();if(calls.isEmpty())return emptyMap();val placeholders=calls.joinToString(","){"?"};return database.readableDatabase.rawQuery("SELECT callsign_norm,COUNT(*),SUM(CASE WHEN paper_received=1 OR lotw_received=1 THEN 1 ELSE 0 END),MAX(created_at) FROM qso_projection WHERE callsign_norm IN ($placeholders) GROUP BY callsign_norm",calls.toTypedArray()).use{c->buildMap{while(c.moveToNext())put(c.getString(0),DxLocalSummary(c.getInt(1),c.getInt(2),c.getLong(3).takeUnless{c.isNull(3)}))}}}
    fun contests(values:Collection<String>):Map<String,Int>{val ids=values.map{it.trim().uppercase()}.filter(String::isNotBlank).distinct();if(ids.isEmpty())return emptyMap();val placeholders=ids.joinToString(","){"?"};return database.readableDatabase.rawQuery("SELECT contest_id_norm,COUNT(*) FROM qso_projection WHERE contest_id_norm IN ($placeholders) GROUP BY contest_id_norm",ids.toTypedArray()).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}}
}
