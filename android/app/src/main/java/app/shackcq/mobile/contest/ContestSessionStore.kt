package app.shackcq.mobile.contest

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class ContestSessionStore(context: Context, name: String = "shackcq-contest.sqlite") : SQLiteOpenHelper(context, name, null, 2), ContestSerialStore {
    data class QsoLinkCursor(val qsoId: String, val linkedAt: Long)
    data class StagedQso(
        val draft: ContestQsoDraft,
        val sessionId: ContestSessionId,
        val submode: String = "",
        val operatorCallsign: String = "",
        val stationCallsign: String = "",
        val networkRevision: String = "",
        val mergeState: String = "STAGED",
        val canonicalQsoId: String? = null,
        val canonicalRevision: String? = null,
        val issue: String = "",
    )
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE contest_session(
            id TEXT PRIMARY KEY, definition_id TEXT NOT NULL, rule_version TEXT NOT NULL, name TEXT NOT NULL,
            utc_start INTEGER NOT NULL, utc_end INTEGER NOT NULL, station_callsign TEXT NOT NULL, station_grid TEXT NOT NULL,
            state TEXT NOT NULL, role TEXT NOT NULL, initial_serial INTEGER NOT NULL, network_armed INTEGER NOT NULL DEFAULT 0,
            keyer_armed INTEGER NOT NULL DEFAULT 0, session_json TEXT NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE contest_serial_reservation(
            id TEXT PRIMARY KEY, session_id TEXT NOT NULL, serial INTEGER NOT NULL, owner TEXT NOT NULL,
            reserved_at INTEGER NOT NULL, state TEXT NOT NULL, qso_id TEXT,
            FOREIGN KEY(session_id) REFERENCES contest_session(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX contest_serial_committed_idx ON contest_serial_reservation(session_id,serial) WHERE state='COMMITTED'")
        db.execSQL("CREATE INDEX contest_serial_session_idx ON contest_serial_reservation(session_id,state,serial)")
        db.execSQL("""CREATE TABLE contest_qso_revision_link(
            session_id TEXT NOT NULL, qso_id TEXT NOT NULL, revision TEXT NOT NULL, linked_at INTEGER NOT NULL,
            PRIMARY KEY(session_id,qso_id), FOREIGN KEY(session_id) REFERENCES contest_session(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX contest_qso_link_session_idx ON contest_qso_revision_link(session_id,linked_at,qso_id)")
        db.execSQL("CREATE TABLE contest_score_snapshot(session_id TEXT PRIMARY KEY, calculation_version TEXT NOT NULL, status TEXT NOT NULL, generated_at INTEGER NOT NULL, payload_json TEXT NOT NULL, FOREIGN KEY(session_id) REFERENCES contest_session(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE contest_rate_bucket(session_id TEXT NOT NULL, bucket_start INTEGER NOT NULL, qso_count INTEGER NOT NULL, points INTEGER NOT NULL, multipliers INTEGER NOT NULL, PRIMARY KEY(session_id,bucket_start), FOREIGN KEY(session_id) REFERENCES contest_session(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE contest_rule_pack_state(definition_id TEXT NOT NULL, rule_version TEXT NOT NULL, digest TEXT NOT NULL, verified_at INTEGER NOT NULL, PRIMARY KEY(definition_id,rule_version))")
        db.execSQL("CREATE TABLE n1mm_peer(id TEXT PRIMARY KEY, station_hash TEXT NOT NULL, last_seen INTEGER NOT NULL, trusted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE n1mm_network_state(session_id TEXT PRIMARY KEY, mode TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(session_id) REFERENCES contest_session(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE n1mm_frame_dedupe(frame_hash TEXT PRIMARY KEY, seen_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX n1mm_frame_dedupe_expiry_idx ON n1mm_frame_dedupe(expires_at)")
        db.execSQL("CREATE TABLE n1mm_remote_qso_link(source_hash TEXT NOT NULL, remote_id TEXT NOT NULL, remote_revision TEXT NOT NULL, canonical_qso_id TEXT NOT NULL, origin_revision TEXT NOT NULL, PRIMARY KEY(source_hash,remote_id,remote_revision))")
        db.execSQL("CREATE TABLE n1mm_claim(call_hash TEXT NOT NULL, station_hash TEXT NOT NULL, expires_at INTEGER NOT NULL, PRIMARY KEY(call_hash,station_hash))")
        createContestQsoEntry(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createContestQsoEntry(db)
    }

    private fun createContestQsoEntry(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS contest_qso_entry(
            entry_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created_at INTEGER NOT NULL,
            callsign TEXT NOT NULL, frequency_hz INTEGER NOT NULL, band TEXT NOT NULL,
            mode TEXT NOT NULL, submode TEXT NOT NULL DEFAULT '', rst_sent TEXT NOT NULL, rst_received TEXT NOT NULL,
            sent_json TEXT NOT NULL, received_json TEXT NOT NULL, station_json TEXT NOT NULL, worked_json TEXT NOT NULL,
            operator_callsign TEXT NOT NULL DEFAULT '', station_callsign TEXT NOT NULL DEFAULT '',
            network_origin TEXT NOT NULL DEFAULT '', network_revision TEXT NOT NULL DEFAULT '',
            dupe_override INTEGER NOT NULL DEFAULT 0, merge_state TEXT NOT NULL DEFAULT 'STAGED',
            canonical_qso_id TEXT, canonical_revision TEXT, issue TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL,
            FOREIGN KEY(session_id) REFERENCES contest_session(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS contest_qso_session_time_idx ON contest_qso_entry(session_id,created_at,entry_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS contest_qso_merge_idx ON contest_qso_entry(session_id,merge_state,created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS contest_qso_call_band_mode_idx ON contest_qso_entry(session_id,callsign,band,mode)")
    }

    fun saveSession(session: ContestSession) {
        val values = ContentValues().apply {
            put("id", session.id.value); put("definition_id", session.definitionId.value); put("rule_version", session.ruleVersion.value)
            put("name", session.name); put("utc_start", session.utcStart); put("utc_end", session.utcEnd)
            put("station_callsign", session.stationCallsign); put("station_grid", session.stationGrid); put("state", session.state.name)
            put("role", session.role.name); put("initial_serial", session.initialSerial); put("network_armed", 0); put("keyer_armed", 0); put("session_json",encodeSession(session))
            put("updated_at", System.currentTimeMillis() / 1_000)
        }
        writableDatabase.run {
            if (insertWithOnConflict("contest_session", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                update("contest_session", values, "id=?", arrayOf(session.id.value))
            }
        }
    }

    fun loadSession(id: ContestSessionId): ContestSession? = readableDatabase.rawQuery("SELECT session_json FROM contest_session WHERE id=?",arrayOf(id.value)).use { c -> if(c.moveToFirst())decodeSession(JSONObject(c.getString(0))) else null }

    fun linkQso(sessionId: ContestSessionId, qsoId: String, revision: String) {
        writableDatabase.insertWithOnConflict("contest_qso_revision_link", null, ContentValues().apply {
            put("session_id", sessionId.value); put("qso_id", qsoId); put("revision", revision); put("linked_at", System.currentTimeMillis() / 1_000)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun linkedQsoIds(sessionId: ContestSessionId, afterTime: Long = Long.MIN_VALUE, afterId: String = "", limit: Int = 250): List<String> {
        return linkedQsoPage(sessionId,afterTime,afterId,limit).map { it.qsoId }
    }

    fun linkedQsoPage(sessionId: ContestSessionId, afterTime: Long = Long.MIN_VALUE, afterId: String = "", limit: Int = 250): List<QsoLinkCursor> {
        require(limit in 1..500)
        return readableDatabase.rawQuery("""SELECT qso_id,linked_at FROM contest_qso_revision_link
            WHERE session_id=? AND (linked_at>? OR (linked_at=? AND qso_id>?)) ORDER BY linked_at,qso_id LIMIT ?""".trimIndent(),
            arrayOf(sessionId.value, afterTime.toString(), afterTime.toString(), afterId, limit.toString())).use { c -> buildList { while (c.moveToNext()) add(QsoLinkCursor(c.getString(0),c.getLong(1))) } }
    }

    fun qsoLinkQueryPlan(sessionId: ContestSessionId): String = readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT qso_id FROM contest_qso_revision_link WHERE session_id=? AND (linked_at>? OR (linked_at=? AND qso_id>?)) ORDER BY linked_at,qso_id LIMIT 250",arrayOf(sessionId.value,"0","0","")).use { c -> buildList { while(c.moveToNext())add(c.getString(3)) }.joinToString("\n") }

    fun stageQso(session: ContestSession, draft: ContestQsoDraft, submode: String = "", networkRevision: String = ""): Boolean {
        val values = ContentValues().apply {
            put("entry_id", draft.qsoId); put("session_id", session.id.value); put("created_at", draft.createdAt)
            put("callsign", draft.callsign.trim().uppercase()); put("frequency_hz", draft.frequencyHz); put("band", draft.band.name)
            put("mode", draft.mode.name); put("submode", submode.take(24)); put("rst_sent", draft.rstSent); put("rst_received", draft.rstReceived)
            put("sent_json", exchangeJson(draft.sent)); put("received_json", exchangeJson(draft.received))
            put("station_json", entityJson(draft.station)); put("worked_json", entityJson(draft.worked))
            put("operator_callsign", session.operators.firstOrNull().orEmpty()); put("station_callsign", session.stationCallsign)
            put("network_origin", draft.networkOriginId); put("network_revision", networkRevision.take(120)); put("dupe_override", if (draft.explicitDupeOverride) 1 else 0)
            put("merge_state", "STAGED"); put("issue", ""); put("updated_at", System.currentTimeMillis() / 1_000)
        }
        return writableDatabase.insertWithOnConflict("contest_qso_entry", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun stagedQsos(sessionId: ContestSessionId, includeMerged: Boolean = true, limit: Int = 10_000): List<StagedQso> {
        require(limit in 1..10_000)
        val stateClause = if (includeMerged) "" else " AND merge_state!='MERGED'"
        return readableDatabase.rawQuery("""SELECT entry_id,created_at,callsign,frequency_hz,band,mode,submode,rst_sent,rst_received,
            sent_json,received_json,station_json,worked_json,operator_callsign,station_callsign,network_origin,network_revision,
            dupe_override,merge_state,canonical_qso_id,canonical_revision,issue
            FROM contest_qso_entry WHERE session_id=?$stateClause ORDER BY created_at,entry_id LIMIT ?""".trimIndent(),
            arrayOf(sessionId.value, limit.toString())).use { c -> buildList {
            while (c.moveToNext()) {
                val draft = ContestQsoDraft(c.getString(0), c.getString(2), c.getLong(1), c.getLong(3),
                    ContestBand.valueOf(c.getString(4)), ContestMode.valueOf(c.getString(5)), c.getString(7), c.getString(8),
                    exchangeMap(c.getString(9)), exchangeMap(c.getString(10)), entity(c.getString(11)), entity(c.getString(12)),
                    c.getInt(17) != 0, c.getString(15))
                add(StagedQso(draft, sessionId, c.getString(6), c.getString(13), c.getString(14), c.getString(16),
                    c.getString(18), c.getString(19), c.getString(20), c.getString(21)))
            }
        } }
    }

    fun updateStagedQso(sessionId: ContestSessionId, draft: ContestQsoDraft): Boolean {
        val values = ContentValues().apply {
            put("callsign", draft.callsign.trim().uppercase()); put("frequency_hz", draft.frequencyHz); put("band", draft.band.name); put("mode", draft.mode.name)
            put("rst_sent", draft.rstSent); put("rst_received", draft.rstReceived); put("sent_json", exchangeJson(draft.sent)); put("received_json", exchangeJson(draft.received))
            put("worked_json", entityJson(draft.worked)); put("dupe_override", if (draft.explicitDupeOverride) 1 else 0); put("updated_at", System.currentTimeMillis() / 1_000)
        }
        return writableDatabase.update("contest_qso_entry", values, "entry_id=? AND session_id=? AND merge_state!='MERGED'", arrayOf(draft.qsoId, sessionId.value)) == 1
    }

    fun deleteStagedQso(sessionId: ContestSessionId, entryId: String): Boolean =
        writableDatabase.delete("contest_qso_entry", "entry_id=? AND session_id=? AND merge_state!='MERGED'", arrayOf(entryId, sessionId.value)) == 1

    fun markMergeResult(sessionId: ContestSessionId, entryId: String, canonicalId: String?, revision: String?, issue: String) {
        writableDatabase.update("contest_qso_entry", ContentValues().apply {
            put("merge_state", if (canonicalId == null) "FAILED" else "MERGED"); put("canonical_qso_id", canonicalId)
            put("canonical_revision", revision); put("issue", issue.take(240)); put("updated_at", System.currentTimeMillis() / 1_000)
        }, "entry_id=? AND session_id=? AND merge_state!='MERGED'", arrayOf(entryId, sessionId.value))
    }

    fun contestQsoQueryPlan(sessionId: ContestSessionId): String = readableDatabase.rawQuery(
        "EXPLAIN QUERY PLAN SELECT entry_id FROM contest_qso_entry WHERE session_id=? AND merge_state=? ORDER BY created_at,entry_id LIMIT 250",
        arrayOf(sessionId.value, "STAGED")).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString("\n") }

    override fun reservations(sessionId: ContestSessionId): List<ContestSerialReservation> = readableDatabase.rawQuery(
        "SELECT id,serial,owner,reserved_at,state,qso_id FROM contest_serial_reservation WHERE session_id=? ORDER BY serial,id", arrayOf(sessionId.value)).use { c ->
        buildList { while (c.moveToNext()) add(ContestSerialReservation(c.getString(0), sessionId, c.getInt(1), c.getString(2), c.getLong(3), ContestSerialState.valueOf(c.getString(4)), c.getString(5))) }
    }

    override fun put(reservation: ContestSerialReservation) {
        writableDatabase.insertWithOnConflict("contest_serial_reservation", null, ContentValues().apply {
            put("id", reservation.id); put("session_id", reservation.sessionId.value); put("serial", reservation.serial); put("owner", reservation.owner)
            put("reserved_at", reservation.reservedAt); put("state", reservation.state.name); put("qso_id", reservation.qsoId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun encodeSession(s:ContestSession)=JSONObject().apply{
        put("id",s.id.value);put("definitionId",s.definitionId.value);put("ruleVersion",s.ruleVersion.value);put("name",s.name);put("utcStart",s.utcStart);put("utcEnd",s.utcEnd)
        put("stationCallsign",s.stationCallsign);put("stationGrid",s.stationGrid);put("station",JSONObject().apply{put("country",s.station.country);put("dxcc",s.station.dxcc);put("continent",s.station.continent);put("cqZone",s.station.cqZone);put("ituZone",s.station.ituZone);put("state",s.station.stateProvince);put("section",s.station.arrlSection);put("wve",s.station.isWve);put("oceania",s.station.isOceania)})
        put("category",JSONObject().apply{put("operator",s.category.operator);put("assisted",s.category.assisted);put("band",s.category.band);put("mode",s.category.mode.name);put("power",s.category.power);put("station",s.category.station);put("transmitter",s.category.transmitter);put("overlay",s.category.overlay)})
        put("operators",JSONArray(s.operators));put("initialSerial",s.initialSerial);put("role",s.role.name);put("state",s.state.name)
        put("score",JSONObject().apply{put("accepted",s.score.acceptedQsos);put("scored",s.score.scoredQsos);put("duplicates",s.score.duplicates);put("zero",s.score.zeroPointValidQsos);put("review",s.score.reviewQsos);put("points",s.score.points);put("claimed",s.score.claimedScore);put("version",s.score.calculationVersion);put("generated",s.score.generatedAt);put("status",s.score.status.name);put("multipliers",JSONObject(s.score.multipliers.mapKeys{it.key.name}))})
    }.toString()

    private fun decodeSession(j:JSONObject):ContestSession{
        val e=j.getJSONObject("station");val c=j.getJSONObject("category");val score=j.getJSONObject("score");val mult=score.getJSONObject("multipliers")
        val multipliers=buildMap{mult.keys().forEach{key->put(ContestMultiplierType.valueOf(key),mult.getInt(key))}}
        return ContestSession(ContestSessionId(j.getString("id")),ContestDefinitionId(j.getString("definitionId")),ContestRuleVersion(j.getString("ruleVersion")),j.getString("name"),j.getLong("utcStart"),j.getLong("utcEnd"),j.getString("stationCallsign"),j.getString("stationGrid"),
            ContestEntityInfo(country=e.getString("country"),dxcc=e.getString("dxcc"),continent=e.getString("continent"),cqZone=e.getString("cqZone"),ituZone=e.getString("ituZone"),stateProvince=e.getString("state"),arrlSection=e.getString("section"),isWve=e.optBoolean("wve").takeIf{e.has("wve")&&!e.isNull("wve")},isOceania=e.optBoolean("oceania").takeIf{e.has("oceania")&&!e.isNull("oceania")}),
            ContestCategory(c.getString("operator"),c.getString("assisted"),c.getString("band"),ContestMode.valueOf(c.getString("mode")),c.getString("power"),c.getString("station"),c.getString("transmitter"),c.getString("overlay")),
            (0 until j.getJSONArray("operators").length()).map{j.getJSONArray("operators").getString(it)},j.getInt("initialSerial"),ContestOperatingRole.valueOf(j.getString("role")),ContestSessionState.valueOf(j.getString("state")),score=ContestScoreSnapshot(acceptedQsos=score.getInt("accepted"),scoredQsos=score.getInt("scored"),duplicates=score.getInt("duplicates"),zeroPointValidQsos=score.getInt("zero"),reviewQsos=score.getInt("review"),points=score.getInt("points"),multipliers=multipliers,claimedScore=score.getLong("claimed"),calculationVersion=score.getString("version"),generatedAt=score.getLong("generated"),status=ContestScoreStatus.valueOf(score.getString("status"))))
    }

    private fun exchangeJson(values: Map<ContestExchangeField, String>) = JSONObject(values.mapKeys { it.key.name }).toString()
    private fun exchangeMap(text: String) = buildMap {
        val json = JSONObject(text); json.keys().forEach { key -> runCatching { ContestExchangeField.valueOf(key) }.getOrNull()?.let { put(it, json.getString(key)) } }
    }
    private fun entityJson(value: ContestEntityInfo) = JSONObject().apply {
        put("country", value.country); put("dxcc", value.dxcc); put("continent", value.continent); put("cq", value.cqZone); put("itu", value.ituZone)
        put("state", value.stateProvince); put("section", value.arrlSection); put("wpx", value.wpxPrefix); put("hq", value.hqSociety)
    }.toString()
    private fun entity(text: String): ContestEntityInfo = JSONObject(text).let { value -> ContestEntityInfo(
        country = value.optString("country"), dxcc = value.optString("dxcc"), continent = value.optString("continent"),
        cqZone = value.optString("cq"), ituZone = value.optString("itu"), stateProvince = value.optString("state"),
        arrlSection = value.optString("section"), wpxPrefix = value.optString("wpx"), hqSociety = value.optString("hq")) }
}
