package app.rigweave.mobile

import android.os.CancellationSignal
import java.util.Locale

internal data class ProjectionQuery(val where: String, val args: List<String>, val order: String, val planLabel: String)

class LogbookRepository(private val database: QsoDatabase) {
    private data class CountKey(val filter: LogbookFilter, val stationId: String?, val revision: Long)
    private val countCache = object : LinkedHashMap<CountKey,Int>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CountKey,Int>?) = size > 12
    }
    fun health(): ProjectionHealth = database.projectionHealth()

    fun count(filter: LogbookFilter, stationId: String?, signal: CancellationSignal? = null): Int {
        val query = buildProjectionQuery(filter, stationId)
        val filterHash = (31 * filter.hashCode() + stationId.hashCode()).toUInt().toString(16)
        return synchronized(countCache) {
            val key = CountKey(filter, stationId, database.changeToken())
            countCache[key] ?: StabilityDiagnostics.timedQuery("LOGBOOK_COUNT", filterHash, query.planLabel, { 1 }) {
                database.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM qso_projection p WHERE ${query.where}",
                    query.args.toTypedArray(),
                    signal,
                ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            }.also { countCache[key] = it }
        }
    }

    fun page(filter: LogbookFilter, stationId: String?, pageSize: Int = 50, cursor: LogbookCursor? = null,
        offsetPage: Int = 0, exactCount: Boolean = false, signal: CancellationSignal? = null): LogbookQueryPage {
        val health = database.projectionHealth()
        if (health.state != ProjectionState.READY) return LogbookQueryPage(emptyList(), null, true, null, "projection-${health.state.name.lowercase()}")
        val size = pageSize.coerceIn(1,250); val query = buildProjectionQuery(filter, stationId)
        val keyset = filter.sort == LogbookSort.TIME && cursor != null
        val where = if (!keyset) query.where else query.where + if (filter.direction == LogbookSortDirection.DESCENDING)
            " AND (p.created_at<? OR (p.created_at=? AND p.qso_id>?))" else " AND (p.created_at>? OR (p.created_at=? AND p.qso_id>?))"
        val args = query.args.toMutableList()
        if (keyset) { args += cursor!!.createdAt.toString(); args += cursor.createdAt.toString(); args += cursor.qsoId }
        args += (size + 1).toString()
        val offset = if (keyset) 0 else offsetPage.coerceAtLeast(0) * size
        val sql = "SELECT p.qso_id,p.created_at FROM qso_projection p WHERE $where ORDER BY ${query.order} LIMIT ?" +
            if (offset > 0) " OFFSET $offset" else ""
        val filterHash = (31 * filter.hashCode() + stationId.hashCode()).toUInt().toString(16)
        val ids = StabilityDiagnostics.timedQuery("LOGBOOK_PAGE", filterHash, query.planLabel, { rows: List<Pair<String, Long>> -> rows.size }) {
            database.readableDatabase.rawQuery(sql, args.toTypedArray(), signal).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
            }
        }
        val visible = ids.take(size); val rows = database.qsos(visible.map(Pair<String,Long>::first))
        val total = if (exactCount) count(filter, stationId, signal) else null
        val last = visible.lastOrNull()
        return LogbookQueryPage(rows,total,ids.size>size,last?.let { LogbookCursor(it.second,it.first) },query.planLabel)
    }

    fun explain(filter: LogbookFilter, stationId: String?): String {
        val query=buildProjectionQuery(filter,stationId)
        return database.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT p.qso_id FROM qso_projection p WHERE ${query.where} ORDER BY ${query.order} LIMIT 50",query.args.toTypedArray())
            .use { c -> buildList { while(c.moveToNext()) add(c.getString(3)) }.joinToString(" | ") }
    }

    private fun buildProjectionQuery(filter: LogbookFilter, stationId: String?): ProjectionQuery {
        val clauses=mutableListOf("1=1"); val args=mutableListOf<String>()
        fun text(column:String,value:String){ if(value.isBlank())return; if(value.trim()=="*") clauses += "$column<>''" else { clauses += "$column LIKE ? ESCAPE '\\'"; args += "%"+norm(value).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%" } }
        fun choice(column:String,value:String){ if(value.isNotBlank()){clauses += "$column=?";args += norm(value)} }
        fun flag(column:String,value:String){ if(value.isBlank())return; clauses += if(value.equals("Y",true)) "$column=1" else if(value.equals("N",true)) "$column=0" else "$column=?"; if(!value.equals("Y",true)&&!value.equals("N",true))args += value }
        fun numeric(column:String,value:String){ val raw=value.trim().replace(',','.'); if(raw.isBlank()||raw=="*")return; Regex("^(-?\\d+(?:\\.\\d+)?)\\s*(?:\\.\\.|-)\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(raw)?.let { clauses += "$column BETWEEN ? AND ?"; args += minOf(it.groupValues[1].toDouble(),it.groupValues[2].toDouble()).toString(); args += maxOf(it.groupValues[1].toDouble(),it.groupValues[2].toDouble()).toString(); return }; val m=Regex("^(>=|<=|>|<|=)?\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(raw)?:return; clauses += "$column ${m.groupValues[1].ifBlank { ">=" }} ?";args += m.groupValues[2] }
        stationId?.takeIf(String::isNotBlank)?.let { clauses += "p.station_profile_id=?"; args += it }
        filter.fromEpochSeconds?.let { clauses += "p.created_at>=?";args += it.toString() }; filter.toEpochSecondsExclusive?.let { clauses += "p.created_at<?";args += it.toString() }
        text("p.callsign_norm",filter.callsign);text("p.station_profile_id",filter.stationProfile);text("p.station_callsign_norm",filter.stationCallsign)
        text("p.name_norm",filter.name);text("p.qth_norm",filter.qth);text("p.email_norm",filter.email);text("p.dxcc",filter.dxcc);text("p.country_norm",filter.country)
        text("p.state_norm",filter.state);text("p.grid_norm",filter.grid);text("p.cq_zone",filter.cqZone);text("p.itu_zone",filter.ituZone)
        choice("p.mode_norm",filter.mode);choice("p.mode_family",filter.modeFamily);choice("p.submode_norm",filter.submode);choice("p.band_norm",filter.band)
        numeric("p.frequency_hz/1000000.0",filter.frequency);numeric("p.frequency_rx_hz/1000000.0",filter.frequencyRx);choice("p.band_rx_norm",filter.bandRx)
        choice("p.propagation_mode",filter.propagation);text("p.county_norm",filter.county);text("p.dok_norm",filter.dok);text("p.sota_ref_norm",filter.sota)
        if(filter.pota.isNotBlank()){clauses += "EXISTS(SELECT 1 FROM qso_reference pr WHERE pr.qso_id=p.qso_id AND pr.program='POTA' AND pr.direction='WORKED' AND pr.reference_norm LIKE ? ESCAPE '\\')";args += "%"+norm(filter.pota).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"}
        text("p.iota_norm",filter.iota);text("p.wwff_ref_norm",filter.wwff);text("p.operator_norm",filter.operator)
        text("p.radio_model_norm",filter.radioModel);text("p.contest_id_norm",filter.contest);choice("p.continent",filter.continent);text("p.satellite_name",filter.satellite)
        text("p.satellite_mode",filter.satelliteMode);text("p.orbit",filter.orbit);text("p.comment_norm",filter.comment);text("p.qsl_message_norm",filter.qslMessage);text("p.notes_norm",filter.notes)
        numeric("p.distance_km",filter.distance);numeric("p.duration_seconds/60.0",filter.duration);numeric("p.tx_power_w",filter.txPower)
        choice("p.qsl_sent_status",filter.qslSent);choice("p.qsl_received_status",filter.qslReceived);flag("p.lotw_sent",filter.lotwSent);flag("p.lotw_received",filter.lotwReceived)
        flag("p.clublog_sent",filter.clublogSent);flag("p.clublog_received",filter.clublogReceived);flag("p.eqsl_sent",filter.eqslSent);flag("p.eqsl_received",filter.eqslReceived)
        flag("p.dcl_sent",filter.dclSent);flag("p.dcl_received",filter.dclReceived);flag("p.qrz_sent",filter.qrzSent);flag("p.qrz_received",filter.qrzReceived)
        choice("p.qsl_method_norm",filter.qslSentMethod);choice("p.qsl_received_method_norm",filter.qslReceivedMethod);text("p.qsl_via_norm",filter.qslVia)
        if(filter.qslImages.equals("Y",true))clauses += "p.has_qsl_images=1" else if(filter.qslImages.equals("N",true))clauses += "p.has_qsl_images=0"
        when(filter.provenance.uppercase(Locale.US)){"LOCAL"->clauses += "p.remote_id='' AND NOT EXISTS(SELECT 1 FROM wavelog_remote_link pl WHERE pl.local_qso_id=p.qso_id)";"REMOTE","LINKED"->clauses += "(p.remote_id<>'' OR EXISTS(SELECT 1 FROM wavelog_remote_link pl WHERE pl.local_qso_id=p.qso_id))"}
        when(filter.confirmationSource.uppercase(Locale.US)){"PAPER","QSL"->clauses += "p.paper_received=1";"LOTW"->clauses += "p.lotw_received=1";"EQSL"->clauses += "p.eqsl_received=1";"QRZ"->clauses += "p.qrz_received=1";"CLUBLOG"->clauses += "p.clublog_received=1";"DCL"->clauses += "p.dcl_received=1";"AWARD"->clauses += "(p.paper_received=1 OR p.lotw_received=1)";"UNCONFIRMED"->clauses += "p.paper_received=0 AND p.lotw_received=0"}
        when(filter.recordState.uppercase(Locale.US)){"VALID"->clauses += "p.is_valid=1";"INCOMPLETE","INVALID"->clauses += "p.is_valid=0"}
        when(filter.recordVisibility.uppercase(Locale.US)){"ACTIVE"->clauses += "p.sync_state NOT IN ('CONFLICT','TOMBSTONE','REMOTE_DELETED')";"ACTIVE_AND_CONFLICTS"->clauses += "p.sync_state NOT IN ('TOMBSTONE','REMOTE_DELETED')";"DELETED"->clauses += "0=1"}
        filter.callsignPrefix.takeIf(String::isNotBlank)?.let { clauses += "p.wpx_prefix=?";args += norm(it) }
        filter.portableProgram.takeIf(String::isNotBlank)?.let { program -> clauses += "EXISTS(SELECT 1 FROM qso_reference r WHERE r.qso_id=p.qso_id AND r.program${if(program.equals("ANY",true)) " IN ('POTA','SOTA','WWFF','IOTA')" else "=?"})";if(!program.equals("ANY",true))args += norm(program) }
        when(filter.syncRelation.uppercase(Locale.US)){"LOCAL_ONLY"->clauses += "p.remote_id='' AND NOT EXISTS(SELECT 1 FROM wavelog_remote_link l WHERE l.local_qso_id=p.qso_id)";"LINKED"->clauses += "EXISTS(SELECT 1 FROM wavelog_remote_link l WHERE l.local_qso_id=p.qso_id)";"OUTBOX"->clauses += "EXISTS(SELECT 1 FROM wavelog_outbox o WHERE o.local_qso_id=p.qso_id AND o.state<>'ACCEPTED')";"CONFLICT"->clauses += "EXISTS(SELECT 1 FROM wavelog_conflict c WHERE c.local_qso_id=p.qso_id AND c.state='OPEN')";"ATTENTION"->clauses += "(EXISTS(SELECT 1 FROM wavelog_outbox o WHERE o.local_qso_id=p.qso_id AND o.state<>'ACCEPTED') OR EXISTS(SELECT 1 FROM wavelog_conflict c WHERE c.local_qso_id=p.qso_id AND c.state='OPEN'))";"TOMBSTONE","REMOTE_DELETED"->clauses += "0=1"}
        if(filter.duplicateState.equals("CANDIDATE",true))clauses += "EXISTS(SELECT 1 FROM qso_projection d WHERE d.qso_id<>p.qso_id AND d.callsign_norm=p.callsign_norm AND d.frequency_hz=p.frequency_hz AND d.mode_norm=p.mode_norm AND ABS(d.created_at-p.created_at)<=15)"
        val sort=when(filter.sort){LogbookSort.TIME->"p.created_at";LogbookSort.CALLSIGN->"p.callsign_norm";LogbookSort.NAME->"p.name_norm";LogbookSort.COUNTRY->"p.country_norm";LogbookSort.DXCC->"p.dxcc";LogbookSort.MODE->"p.mode_norm";LogbookSort.SUBMODE->"p.submode_norm";LogbookSort.BAND->"CASE p.band_norm WHEN '2190M' THEN 1 WHEN '630M' THEN 2 WHEN '160M' THEN 3 WHEN '80M' THEN 4 WHEN '60M' THEN 5 WHEN '40M' THEN 6 WHEN '30M' THEN 7 WHEN '20M' THEN 8 WHEN '17M' THEN 9 WHEN '15M' THEN 10 WHEN '12M' THEN 11 WHEN '10M' THEN 12 WHEN '8M' THEN 13 WHEN '6M' THEN 14 WHEN '5M' THEN 15 WHEN '4M' THEN 16 WHEN '2M' THEN 17 WHEN '1.25M' THEN 18 WHEN '70CM' THEN 19 WHEN '33CM' THEN 20 WHEN '23CM' THEN 21 ELSE 999 END";LogbookSort.FREQUENCY->"p.frequency_hz";LogbookSort.GRID->"p.grid_norm";LogbookSort.DISTANCE->"p.distance_km";LogbookSort.DURATION->"p.duration_seconds"}
        val direction=if(filter.direction==LogbookSortDirection.ASCENDING)"ASC" else "DESC"
        val order=if(filter.sort==LogbookSort.BAND)"$sort $direction,p.frequency_hz $direction,p.qso_id ASC" else "$sort $direction,p.qso_id ASC"
        return ProjectionQuery(clauses.joinToString(" AND "),args,order,"projection-${filter.sort.name.lowercase()}")
    }

    private fun norm(value:String)=value.trim().uppercase(Locale.US)
}
