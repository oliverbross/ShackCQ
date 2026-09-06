package app.shackcq.mobile.dxchaser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

internal class DxChaserStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1), DxChaserJournalPort {
    private val databaseFile = context.getDatabasePath(DATABASE_NAME)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE dxchaser_session(
            session_id TEXT PRIMARY KEY, station_scope TEXT NOT NULL, started_epoch INTEGER NOT NULL,
            ended_epoch INTEGER, mode TEXT NOT NULL, status TEXT NOT NULL, policy TEXT NOT NULL,
            bands TEXT NOT NULL, targets_attempted INTEGER NOT NULL DEFAULT 0,
            completed_count INTEGER NOT NULL DEFAULT 0, failure_count INTEGER NOT NULL DEFAULT 0,
            stop_reason TEXT NOT NULL DEFAULT '')""")
        db.execSQL("""CREATE TABLE dxchaser_attempt(
            attempt_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, epoch INTEGER NOT NULL,
            callsign TEXT NOT NULL, band TEXT NOT NULL, mode TEXT NOT NULL, score INTEGER NOT NULL,
            priority_tier TEXT NOT NULL, reason_codes TEXT NOT NULL, local_decode_id TEXT NOT NULL,
            disposition TEXT NOT NULL, engaged INTEGER NOT NULL DEFAULT 0, outcome TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX dxchaser_attempt_session_idx ON dxchaser_attempt(session_id, epoch)")
        db.execSQL("CREATE INDEX dxchaser_attempt_retention_idx ON dxchaser_attempt(epoch)")
        db.execSQL("""CREATE TABLE dxchaser_cooldown(
            base_callsign TEXT NOT NULL, band TEXT NOT NULL, mode TEXT NOT NULL,
            reason TEXT NOT NULL, expires_epoch INTEGER NOT NULL,
            PRIMARY KEY(base_callsign, band, mode, reason))""")
        db.execSQL("""CREATE TABLE dxchaser_rarity_source(
            id INTEGER PRIMARY KEY CHECK(id=1), source_label TEXT NOT NULL, source_date TEXT NOT NULL,
            digest TEXT NOT NULL, row_count INTEGER NOT NULL, imported_epoch INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE dxchaser_rarity_entity(
            entity_id TEXT PRIMARY KEY, rank_value INTEGER, tier_value INTEGER,
            CHECK((rank_value IS NULL) != (tier_value IS NULL)))""")
        db.execSQL("CREATE TABLE dxchaser_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO dxchaser_meta(key,value) VALUES('schema_version','1')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun startSession(session: DxChaserSessionSnapshot, stationScope: String, bands: Set<String>) {
        writableDatabase.insertOrThrow("dxchaser_session", null, ContentValues().apply {
            put("session_id", session.id); put("station_scope", stationScope); put("started_epoch", session.startedEpochSeconds)
            put("mode", session.mode.name); put("status", session.state.name); put("policy", session.profile.name)
            put("bands", JSONArray(bands.sorted()).toString())
        })
    }

    override fun start(session: DxChaserSessionSnapshot, stationScope: String, bands: Set<String>) =
        startSession(session, stationScope, bands)

    fun finishSession(session: DxChaserSessionSnapshot) {
        writableDatabase.update("dxchaser_session", ContentValues().apply {
            put("ended_epoch", session.endsEpochSeconds); put("status", session.state.name)
            put("targets_attempted", session.attemptedTargets); put("completed_count", session.completedQsos)
            put("failure_count", session.failures); put("stop_reason", session.stopReason)
        }, "session_id=?", arrayOf(session.id))
    }

    override fun finish(session: DxChaserSessionSnapshot) = finishSession(session)

    fun recordAttempt(attemptId: String, sessionId: String, epoch: Long, candidate: DxChaserCandidateSnapshot,
        disposition: String, engaged: Boolean = false, outcome: String = "") {
        writableDatabase.insertWithOnConflict("dxchaser_attempt", null, ContentValues().apply {
            put("attempt_id", attemptId); put("session_id", sessionId); put("epoch", epoch)
            put("callsign", candidate.baseCallsign); put("band", candidate.band); put("mode", candidate.mode)
            put("score", candidate.breakdown.total); put("priority_tier", candidate.priorityTier.name)
            put("reason_codes", JSONArray(candidate.breakdown.reasons.take(20)).toString())
            put("local_decode_id", candidate.localDecodeId); put("disposition", disposition)
            put("engaged", if (engaged) 1 else 0); put("outcome", outcome.take(80))
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun record(sessionId: String, epochSeconds: Long, candidate: DxChaserCandidateSnapshot, disposition: String) =
        recordAttempt("$sessionId-$epochSeconds-${candidate.localDecodeId}-$disposition", sessionId, epochSeconds,
            candidate, disposition)

    fun upsertCooldown(value: DxChaserCooldownSnapshot) {
        writableDatabase.insertWithOnConflict("dxchaser_cooldown", null, ContentValues().apply {
            put("base_callsign", value.baseCallsign); put("band", value.band); put("mode", value.mode)
            put("reason", value.reason); put("expires_epoch", value.expiresEpochSeconds)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun cooldown(value: DxChaserCooldownSnapshot) = upsertCooldown(value)

    fun activeCooldowns(nowEpochSeconds: Long): List<DxChaserCooldownSnapshot> {
        writableDatabase.delete("dxchaser_cooldown", "expires_epoch<=?", arrayOf(nowEpochSeconds.toString()))
        return readableDatabase.query("dxchaser_cooldown", arrayOf("base_callsign", "band", "mode", "reason", "expires_epoch"),
            "expires_epoch>?", arrayOf(nowEpochSeconds.toString()), null, null, "expires_epoch ASC", "100").use { cursor ->
            buildList { while (cursor.moveToNext()) add(DxChaserCooldownSnapshot(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getString(3), cursor.getLong(4))) }
        }
    }

    fun replaceRarity(value: DxChaserRarityImport, importedEpochSeconds: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("dxchaser_rarity_entity", null, null)
            value.rows.forEach { row -> writableDatabase.insertOrThrow("dxchaser_rarity_entity", null, ContentValues().apply {
                put("entity_id", row.entityId); if (row.rank != null) put("rank_value", row.rank) else putNull("rank_value")
                if (row.tier != null) put("tier_value", row.tier) else putNull("tier_value")
            }) }
            writableDatabase.insertWithOnConflict("dxchaser_rarity_source", null, ContentValues().apply {
                put("id", 1); put("source_label", value.sourceLabel); put("source_date", value.sourceDate)
                put("digest", value.digest); put("row_count", value.rows.size); put("imported_epoch", importedEpochSeconds)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun rarity(): Map<String, DxChaserRarity> {
        val source = readableDatabase.rawQuery("SELECT source_label,source_date,digest FROM dxchaser_rarity_source WHERE id=1", null).use {
            if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.getString(2)) else null
        } ?: return emptyMap()
        return readableDatabase.rawQuery("SELECT entity_id,rank_value,tier_value FROM dxchaser_rarity_entity ORDER BY entity_id", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), DxChaserRarity(cursor.getString(0),
                cursor.getInt(1).takeUnless { cursor.isNull(1) }, cursor.getInt(2).takeUnless { cursor.isNull(2) },
                DxChaserRarityOrigin.USER_IMPORTED, source.first, source.second, source.third)) }
        }
    }

    fun clearRarity() {
        writableDatabase.beginTransaction()
        try { writableDatabase.delete("dxchaser_rarity_entity", null, null); writableDatabase.delete("dxchaser_rarity_source", null, null)
            writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }

    override fun compact(nowEpochSeconds: Long, settings: DxChaserSettingsDocument) {
        val attemptCutoff = nowEpochSeconds - settings.attemptRetentionDays * 86_400L
        val sessionCutoff = nowEpochSeconds - settings.sessionRetentionDays * 86_400L
        writableDatabase.delete("dxchaser_attempt", "epoch<? AND session_id NOT IN (SELECT session_id FROM dxchaser_session WHERE ended_epoch IS NULL)",
            arrayOf(attemptCutoff.toString()))
        writableDatabase.execSQL("""DELETE FROM dxchaser_attempt WHERE attempt_id IN (
            SELECT attempt_id FROM dxchaser_attempt WHERE session_id NOT IN
            (SELECT session_id FROM dxchaser_session WHERE ended_epoch IS NULL) ORDER BY epoch DESC LIMIT -1 OFFSET 10000)""")
        writableDatabase.delete("dxchaser_session", "ended_epoch IS NOT NULL AND ended_epoch<?", arrayOf(sessionCutoff.toString()))
        writableDatabase.delete("dxchaser_cooldown", "expires_epoch<=?", arrayOf(nowEpochSeconds.toString()))
        writableDatabase.insertWithOnConflict("dxchaser_meta", null, ContentValues().apply {
            put("key", "last_compaction_epoch"); put("value", nowEpochSeconds.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun counts(): Map<String, Long> = listOf("dxchaser_session", "dxchaser_attempt", "dxchaser_cooldown", "dxchaser_rarity_entity")
        .associateWith { table -> readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) } } +
        ("database_bytes" to databaseFile.length())

    fun resetChaserOnly() {
        writableDatabase.beginTransaction()
        try {
            listOf("dxchaser_attempt", "dxchaser_session", "dxchaser_cooldown", "dxchaser_rarity_entity", "dxchaser_rarity_source")
                .forEach { writableDatabase.delete(it, null, null) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    companion object { const val DATABASE_NAME = "shackcq-dxchaser.sqlite" }
}

internal class DxChaserSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("dxchaser-settings", Context.MODE_PRIVATE)
    fun load(): DxChaserSettingsDocument = DxChaserSettingsDocument.parse(preferences.getString("document_v1", null))
    fun save(value: DxChaserSettingsDocument) { preferences.edit().putString("document_v1", value.clamped().toJson()).apply() }
    fun export(): String = load().toJson()
    fun import(value: String): DxChaserSettingsDocument = DxChaserSettingsDocument.parse(value).also(::save)
}
