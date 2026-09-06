package app.shackcq.mobile

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.util.UUID

internal fun createWavelogSyncTables(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS wavelog_binding(
        id TEXT PRIMARY KEY, provider TEXT NOT NULL DEFAULT 'WAVELOG', base_url TEXT NOT NULL,
        credential_alias TEXT NOT NULL, api_generation TEXT NOT NULL, scopes_json TEXT NOT NULL DEFAULT '[]',
        token_owner TEXT NOT NULL DEFAULT '', remote_station_id TEXT NOT NULL DEFAULT '',
        remote_station_uuid TEXT NOT NULL DEFAULT '', remote_station_name TEXT NOT NULL DEFAULT '',
        local_station_profile_id TEXT NOT NULL DEFAULT '', state TEXT NOT NULL,
        downstream_policy TEXT NOT NULL DEFAULT 'WAVELOG_AUTHORITY', last_quick_sync INTEGER,
        last_full_reconcile INTEGER, high_water TEXT NOT NULL DEFAULT '', last_error_class TEXT NOT NULL DEFAULT 'NONE',
        last_error_summary TEXT NOT NULL DEFAULT '', tested_release TEXT NOT NULL DEFAULT '')""".trimIndent())
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS one_writable_wavelog_binding ON wavelog_binding(provider) WHERE state='ENABLED'")
    db.execSQL("""CREATE TABLE IF NOT EXISTS wavelog_remote_link(
        binding_id TEXT NOT NULL, local_qso_id TEXT NOT NULL, remote_qso_id TEXT NOT NULL,
        baseline_hash TEXT NOT NULL, baseline_canonical TEXT NOT NULL, remote_updated_at TEXT NOT NULL DEFAULT '',
        PRIMARY KEY(binding_id,local_qso_id), UNIQUE(binding_id,remote_qso_id),
        FOREIGN KEY(binding_id) REFERENCES wavelog_binding(id) ON DELETE CASCADE,
        FOREIGN KEY(local_qso_id) REFERENCES qso(id) ON DELETE CASCADE)""".trimIndent())
    db.execSQL("""CREATE TABLE IF NOT EXISTS wavelog_outbox(
        id TEXT PRIMARY KEY, binding_id TEXT NOT NULL, local_qso_id TEXT NOT NULL, operation TEXT NOT NULL,
        idempotency_key TEXT NOT NULL UNIQUE, payload_hash TEXT NOT NULL, canonical_payload TEXT NOT NULL,
        state TEXT NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER,
        last_error TEXT NOT NULL DEFAULT '', error_class TEXT NOT NULL DEFAULT 'NONE',
        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
        FOREIGN KEY(binding_id) REFERENCES wavelog_binding(id) ON DELETE CASCADE)""".trimIndent())
    db.execSQL("CREATE INDEX IF NOT EXISTS wavelog_outbox_queue ON wavelog_outbox(binding_id,state,next_attempt_at,created_at)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS wavelog_checkpoint(
        binding_id TEXT NOT NULL, kind TEXT NOT NULL, page INTEGER NOT NULL DEFAULT 1,
        high_water TEXT NOT NULL DEFAULT '', overlap_hash TEXT NOT NULL DEFAULT '', completed INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL, PRIMARY KEY(binding_id,kind),
        FOREIGN KEY(binding_id) REFERENCES wavelog_binding(id) ON DELETE CASCADE)""".trimIndent())
    db.execSQL("""CREATE TABLE IF NOT EXISTS wavelog_scan_seen(
        binding_id TEXT NOT NULL, kind TEXT NOT NULL, remote_qso_id TEXT NOT NULL,
        PRIMARY KEY(binding_id,kind,remote_qso_id),
        FOREIGN KEY(binding_id) REFERENCES wavelog_binding(id) ON DELETE CASCADE)""".trimIndent())
    db.execSQL("""CREATE TABLE IF NOT EXISTS wavelog_conflict(
        id TEXT PRIMARY KEY, binding_id TEXT NOT NULL, local_qso_id TEXT NOT NULL, remote_qso_id TEXT NOT NULL,
        baseline_canonical TEXT NOT NULL, local_canonical TEXT NOT NULL, remote_canonical TEXT NOT NULL,
        conflicting_fields_json TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL, resolved_at INTEGER,
        resolution_intent TEXT, resolution_canonical TEXT NOT NULL DEFAULT '', resolution_outbox_id TEXT NOT NULL DEFAULT '',
        FOREIGN KEY(binding_id) REFERENCES wavelog_binding(id) ON DELETE CASCADE)""".trimIndent())
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS wavelog_open_conflict ON wavelog_conflict(binding_id,local_qso_id) WHERE state='OPEN'")
    db.execSQL("""CREATE TABLE IF NOT EXISTS wavelog_tombstone(
        binding_id TEXT NOT NULL, local_qso_id TEXT NOT NULL, remote_qso_id TEXT NOT NULL DEFAULT '',
        canonical_hash TEXT NOT NULL DEFAULT '', baseline_canonical TEXT NOT NULL DEFAULT '',
        deleted_at INTEGER NOT NULL, acknowledged_at INTEGER, delete_intent TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
        PRIMARY KEY(binding_id,local_qso_id), FOREIGN KEY(binding_id) REFERENCES wavelog_binding(id) ON DELETE CASCADE)""".trimIndent())
}

internal fun migrateWavelogSyncV9(db: SQLiteDatabase) {
    createWavelogSyncTables(db)
    val columns = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info(wavelog_tombstone)", null).use { cursor ->
        while (cursor.moveToNext()) columns += cursor.getString(1)
    }
    if ("baseline_canonical" !in columns) {
        db.execSQL("ALTER TABLE wavelog_tombstone ADD COLUMN baseline_canonical TEXT NOT NULL DEFAULT ''")
    }
}

internal fun migrateWavelogSyncV10(db: SQLiteDatabase) {
    migrateWavelogSyncV9(db)
    fun columns(table: String) = buildSet {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
    }
    if ("error_class" !in columns("wavelog_outbox")) {
        db.execSQL("ALTER TABLE wavelog_outbox ADD COLUMN error_class TEXT NOT NULL DEFAULT 'NONE'")
    }
    val conflictColumns = columns("wavelog_conflict")
    if ("resolution_intent" !in conflictColumns) db.execSQL("ALTER TABLE wavelog_conflict ADD COLUMN resolution_intent TEXT")
    if ("resolution_canonical" !in conflictColumns) {
        db.execSQL("ALTER TABLE wavelog_conflict ADD COLUMN resolution_canonical TEXT NOT NULL DEFAULT ''")
    }
    if ("resolution_outbox_id" !in conflictColumns) {
        db.execSQL("ALTER TABLE wavelog_conflict ADD COLUMN resolution_outbox_id TEXT NOT NULL DEFAULT ''")
    }
    if ("delete_intent" !in columns("wavelog_tombstone")) {
        db.execSQL("ALTER TABLE wavelog_tombstone ADD COLUMN delete_intent TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
    }
}

class WavelogSyncStore(private val database: QsoDatabase) {
    fun saveBinding(binding: WavelogBinding) {
        if (binding.capabilities.canWriteQsos && binding.state != WavelogBindingState.READ_ONLY) {
            database.readableDatabase.rawQuery(
                "SELECT 1 FROM wavelog_binding WHERE id<>? AND scopes_json LIKE '%\"qso:write\"%' LIMIT 1",
                arrayOf(binding.id),
            ).use { require(!it.moveToFirst()) { "Only one writable Wavelog binding is allowed" } }
        }
        val values = ContentValues().apply {
            put("provider", "WAVELOG"); put("base_url", binding.baseUrl)
            put("credential_alias", binding.credentialAlias); put("api_generation", binding.apiGeneration.name)
            put("scopes_json", JSONArray(binding.capabilities.scopes.toList()).toString()); put("token_owner", binding.tokenOwner)
            put("remote_station_id", binding.remoteStationId); put("remote_station_uuid", binding.remoteStationUuid)
            put("remote_station_name", binding.remoteStationName); put("local_station_profile_id", binding.localStationProfileId)
            put("state", binding.state.name); put("downstream_policy", binding.downstreamPolicy)
            binding.lastQuickSync?.let { put("last_quick_sync", it) }; binding.lastFullReconcile?.let { put("last_full_reconcile", it) }
            put("high_water", binding.highWater); put("last_error_class", binding.lastErrorClass.name)
            put("last_error_summary", binding.lastErrorSummary.take(500)); put("tested_release", binding.testedRelease)
        }
        val updated = database.writableDatabase.update("wavelog_binding", values, "id=?", arrayOf(binding.id))
        if (updated == 0) {
            values.put("id", binding.id)
            database.writableDatabase.insertOrThrow("wavelog_binding", null, values)
        }
    }

    fun configuredBinding(): WavelogBinding? = bindingQuery("", emptyArray())

    fun binding(id: String): WavelogBinding? = bindingQuery("WHERE id=?", arrayOf(id))

    fun activeBinding(): WavelogBinding? = configuredBinding()?.takeUnless { it.state == WavelogBindingState.PAUSED }

    private fun bindingQuery(where: String, args: Array<String>): WavelogBinding? = database.readableDatabase.rawQuery(
        "SELECT id,base_url,credential_alias,api_generation,scopes_json,token_owner,remote_station_id,remote_station_uuid,remote_station_name,local_station_profile_id,state,downstream_policy,last_quick_sync,last_full_reconcile,high_water,last_error_class,last_error_summary,tested_release FROM wavelog_binding $where ORDER BY CASE state WHEN 'ENABLED' THEN 0 WHEN 'READ_ONLY' THEN 1 ELSE 2 END LIMIT 1",
        args,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val scopes = runCatching { JSONArray(cursor.getString(4)).jsonStringList().toSet() }.getOrDefault(emptySet())
        WavelogBinding(
            id = cursor.getString(0), baseUrl = cursor.getString(1), credentialAlias = cursor.getString(2),
            apiGeneration = WavelogApiGeneration.valueOf(cursor.getString(3)), capabilities = capabilities(scopes),
            tokenOwner = cursor.getString(5), remoteStationId = cursor.getString(6), remoteStationUuid = cursor.getString(7),
            remoteStationName = cursor.getString(8), localStationProfileId = cursor.getString(9),
            state = WavelogBindingState.valueOf(cursor.getString(10)), downstreamPolicy = cursor.getString(11),
            lastQuickSync = cursor.getLong(12).takeUnless { cursor.isNull(12) },
            lastFullReconcile = cursor.getLong(13).takeUnless { cursor.isNull(13) }, highWater = cursor.getString(14),
            lastErrorClass = WavelogErrorClass.valueOf(cursor.getString(15)), lastErrorSummary = cursor.getString(16),
            testedRelease = cursor.getString(17),
        )
    }

    fun pauseBinding(id: String) = database.transaction {
        database.writableDatabase.execSQL("UPDATE wavelog_binding SET state='PAUSED' WHERE id=?", arrayOf(id))
        database.writableDatabase.execSQL(
            "UPDATE wavelog_outbox SET state='PAUSED',next_attempt_at=NULL WHERE binding_id=? AND state IN ('PENDING','RETRY_WAIT')",
            arrayOf(id),
        )
    }

    fun resumeBinding(id: String): WavelogBinding = database.transaction {
        val current = binding(id) ?: error("Wavelog binding no longer exists")
        val next = if (current.capabilities.canWriteQsos) WavelogBindingState.ENABLED else WavelogBindingState.READ_ONLY
        database.writableDatabase.execSQL("UPDATE wavelog_binding SET state=? WHERE id=?", arrayOf(next.name, id))
        database.writableDatabase.execSQL(
            "UPDATE wavelog_outbox SET state='PENDING' WHERE binding_id=? AND state='PAUSED'",
            arrayOf(id),
        )
        current.copy(state = next)
    }

    fun removeBinding(id: String) {
        database.writableDatabase.delete("wavelog_binding", "id=?", arrayOf(id))
    }

    fun resetSynchronizationMetadata(id: String) = database.transaction {
        listOf("wavelog_remote_link", "wavelog_outbox", "wavelog_checkpoint", "wavelog_scan_seen",
            "wavelog_conflict", "wavelog_tombstone").forEach { table ->
            database.writableDatabase.delete(table, "binding_id=?", arrayOf(id))
        }
    }

    fun updateBindingError(id: String, errorClass: WavelogErrorClass, summary: String) {
        database.writableDatabase.execSQL(
            "UPDATE wavelog_binding SET last_error_class=?,last_error_summary=? WHERE id=?",
            arrayOf(errorClass.name, summary.take(500), id),
        )
    }

    fun enqueue(bindingId: String, localQsoId: String, operation: WavelogOperation, canonical: CanonicalQso,
        state: WavelogOutboxState = WavelogOutboxState.PENDING, error: String = "",
        errorClass: WavelogErrorClass = WavelogErrorClass.NONE): String {
        val now = System.currentTimeMillis() / 1_000
        val candidates = activeFor(bindingId, localQsoId)
        val existing = when (operation) {
            WavelogOperation.CREATE -> candidates.firstOrNull { it.operation == WavelogOperation.CREATE }
            WavelogOperation.UPDATE -> candidates.firstOrNull { it.operation == WavelogOperation.CREATE }
                ?: candidates.firstOrNull { it.operation == WavelogOperation.UPDATE }
            WavelogOperation.DELETE -> candidates.firstOrNull { it.operation == WavelogOperation.DELETE }
        }
        val effectiveOperation = if (existing?.operation == WavelogOperation.CREATE && operation == WavelogOperation.UPDATE)
            WavelogOperation.CREATE else operation
        val id = existing?.id ?: UUID.randomUUID().toString()
        val key = existing?.operationKey ?: "$bindingId:$localQsoId:${effectiveOperation.name}:${UUID.randomUUID()}"
        val preserveAmbiguousCreate = existing?.operation == WavelogOperation.CREATE &&
            existing.state == WavelogOutboxState.BLOCKED && existing.errorClass == WavelogErrorClass.AMBIGUOUS_WRITE
        val values = ContentValues().apply {
            put("id", id); put("binding_id", bindingId); put("local_qso_id", localQsoId); put("operation", effectiveOperation.name)
            put("idempotency_key", key); put("payload_hash", canonical.hash); put("canonical_payload", canonical.encoded)
            put("state", if (preserveAmbiguousCreate) existing!!.state.name else state.name)
            put("attempt_count", existing?.attemptCount ?: 0)
            put("last_error", if (preserveAmbiguousCreate) existing!!.lastError else error.take(500))
            put("error_class", if (preserveAmbiguousCreate) existing!!.errorClass.name else errorClass.name)
            put("created_at", existing?.createdAt ?: now); put("updated_at", now)
        }
        database.writableDatabase.insertWithOnConflict("wavelog_outbox", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    fun pending(bindingId: String, now: Long = System.currentTimeMillis() / 1_000): List<WavelogOutboxEntry> =
        outbox("binding_id=? AND state IN ('PENDING','RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY created_at LIMIT 100", arrayOf(bindingId, now.toString()))

    fun pendingFor(bindingId: String, localQsoId: String): WavelogOutboxEntry? =
        outbox("binding_id=? AND local_qso_id=? AND state IN ('PENDING','RETRY_WAIT') ORDER BY created_at DESC LIMIT 1", arrayOf(bindingId, localQsoId)).firstOrNull()

    fun blockedCreates(bindingId: String): List<WavelogOutboxEntry> =
        outbox("binding_id=? AND operation='CREATE' AND state='BLOCKED' ORDER BY created_at", arrayOf(bindingId))

    fun blockedCreate(bindingId: String, localQsoId: String): WavelogOutboxEntry? =
        outbox("binding_id=? AND local_qso_id=? AND operation='CREATE' AND state='BLOCKED' ORDER BY created_at DESC LIMIT 1",
            arrayOf(bindingId, localQsoId)).firstOrNull()

    fun outboxEntries(bindingId: String): List<WavelogOutboxEntry> =
        outbox("binding_id=? ORDER BY CASE state WHEN 'BLOCKED' THEN 0 WHEN 'PAUSED' THEN 1 WHEN 'RETRY_WAIT' THEN 2 WHEN 'PENDING' THEN 3 ELSE 4 END,updated_at DESC LIMIT 200",
            arrayOf(bindingId))

    fun retrySafe(entry: WavelogOutboxEntry) {
        val ambiguous = entry.errorClass == WavelogErrorClass.AMBIGUOUS_WRITE
        require(!(ambiguous && entry.operation in setOf(WavelogOperation.CREATE, WavelogOperation.DELETE))) {
            "Ambiguous ${entry.operation.name.lowercase()} must be reconciled, not retried"
        }
        updateOutbox(entry.copy(state = WavelogOutboxState.PENDING, nextAttemptAt = null,
            lastError = "", errorClass = WavelogErrorClass.NONE, updatedAt = System.currentTimeMillis() / 1_000))
    }

    fun cancelUnsentCreate(bindingId: String, localQsoId: String): Boolean = database.writableDatabase.delete(
        "wavelog_outbox", "binding_id=? AND local_qso_id=? AND operation='CREATE' AND attempt_count=0 AND state<>'ACCEPTED'",
        arrayOf(bindingId, localQsoId)) > 0

    fun cancelUnattemptedCreate(bindingId: String, localQsoId: String): Boolean = database.writableDatabase.delete(
        "wavelog_outbox", "binding_id=? AND local_qso_id=? AND operation='CREATE' AND attempt_count=0 AND state<>'ACCEPTED'",
        arrayOf(bindingId, localQsoId)) > 0

    fun cancelWrites(bindingId: String, localQsoId: String, reason: String) {
        database.writableDatabase.execSQL(
            "UPDATE wavelog_outbox SET state='ACCEPTED',next_attempt_at=NULL,last_error=?,error_class='NONE',updated_at=? " +
                "WHERE binding_id=? AND local_qso_id=? AND operation IN ('CREATE','UPDATE') AND state<>'ACCEPTED'",
            arrayOf(reason.take(500), System.currentTimeMillis() / 1_000, bindingId, localQsoId),
        )
    }

    fun updateOutbox(entry: WavelogOutboxEntry) {
        database.writableDatabase.execSQL("UPDATE wavelog_outbox SET state=?,attempt_count=?,next_attempt_at=?,last_error=?,error_class=?,updated_at=? WHERE id=?",
            arrayOf(entry.state.name, entry.attemptCount, entry.nextAttemptAt, entry.lastError.take(500),
                entry.errorClass.name, entry.updatedAt, entry.id))
    }

    fun saveLink(link: WavelogRemoteLink) {
        database.writableDatabase.execSQL("""INSERT OR REPLACE INTO wavelog_remote_link
            (binding_id,local_qso_id,remote_qso_id,baseline_hash,baseline_canonical,remote_updated_at) VALUES(?,?,?,?,?,?)""".trimIndent(),
            arrayOf(link.bindingId, link.localQsoId, link.remoteQsoId, link.baselineHash, link.baselineCanonical, link.remoteUpdatedAt))
    }

    fun link(bindingId: String, localQsoId: String): WavelogRemoteLink? = database.readableDatabase.rawQuery(
        "SELECT remote_qso_id,baseline_hash,baseline_canonical,remote_updated_at FROM wavelog_remote_link WHERE binding_id=? AND local_qso_id=?",
        arrayOf(bindingId, localQsoId)).use { cursor -> if (!cursor.moveToFirst()) null else WavelogRemoteLink(
            bindingId, localQsoId, cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)) }

    fun linkByRemote(bindingId: String, remoteQsoId: String): WavelogRemoteLink? = database.readableDatabase.rawQuery(
        "SELECT local_qso_id,baseline_hash,baseline_canonical,remote_updated_at FROM wavelog_remote_link WHERE binding_id=? AND remote_qso_id=?",
        arrayOf(bindingId, remoteQsoId)).use { cursor -> if (!cursor.moveToFirst()) null else WavelogRemoteLink(
            bindingId, cursor.getString(0), remoteQsoId, cursor.getString(1), cursor.getString(2), cursor.getString(3)) }

    fun deleteLink(bindingId: String, localQsoId: String) {
        database.writableDatabase.delete("wavelog_remote_link", "binding_id=? AND local_qso_id=?", arrayOf(bindingId, localQsoId))
    }

    fun saveCheckpoint(checkpoint: WavelogSyncCheckpoint) {
        database.writableDatabase.execSQL("INSERT OR REPLACE INTO wavelog_checkpoint(binding_id,kind,page,high_water,overlap_hash,completed,updated_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf(checkpoint.bindingId, checkpoint.kind, checkpoint.page, checkpoint.highWater, checkpoint.overlapHash, if (checkpoint.completed) 1 else 0, checkpoint.updatedAt))
    }

    fun checkpoint(bindingId: String, kind: String): WavelogSyncCheckpoint? = database.readableDatabase.rawQuery(
        "SELECT page,high_water,overlap_hash,completed,updated_at FROM wavelog_checkpoint WHERE binding_id=? AND kind=?",
        arrayOf(bindingId, kind)).use { cursor -> if (!cursor.moveToFirst()) null else WavelogSyncCheckpoint(
            bindingId, kind, cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1, cursor.getLong(4)) }

    fun deleteCheckpoint(bindingId: String, kind: String) {
        database.writableDatabase.delete("wavelog_checkpoint", "binding_id=? AND kind=?", arrayOf(bindingId, kind))
    }

    fun clearSeen(bindingId: String, kind: String) {
        database.writableDatabase.delete("wavelog_scan_seen", "binding_id=? AND kind=?", arrayOf(bindingId, kind))
    }

    fun markSeen(bindingId: String, kind: String, remoteIds: Collection<String>) {
        remoteIds.filter(String::isNotBlank).forEach { remoteId ->
            database.writableDatabase.execSQL("INSERT OR IGNORE INTO wavelog_scan_seen(binding_id,kind,remote_qso_id) VALUES(?,?,?)",
                arrayOf(bindingId, kind, remoteId))
        }
    }

    fun seenIds(bindingId: String, kind: String): Set<String> = buildSet {
        database.readableDatabase.rawQuery("SELECT remote_qso_id FROM wavelog_scan_seen WHERE binding_id=? AND kind=?",
            arrayOf(bindingId, kind)).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    fun saveConflict(conflict: WavelogConflict) {
        database.writableDatabase.execSQL("INSERT OR REPLACE INTO wavelog_conflict(id,binding_id,local_qso_id,remote_qso_id,baseline_canonical,local_canonical,remote_canonical,conflicting_fields_json,state,created_at,resolved_at,resolution_intent,resolution_canonical,resolution_outbox_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(conflict.id, conflict.bindingId, conflict.localQsoId, conflict.remoteQsoId, conflict.baselineCanonical,
                conflict.localCanonical, conflict.remoteCanonical, JSONArray(conflict.conflictingFields.toList()).toString(),
                conflict.state.name, conflict.createdAt, conflict.resolvedAt, conflict.resolutionIntent?.name,
                conflict.resolutionCanonical, conflict.resolutionOutboxId))
    }

    fun openConflicts(bindingId: String): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM wavelog_conflict WHERE binding_id=? AND state='OPEN'", arrayOf(bindingId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun conflicts(bindingId: String): List<WavelogConflict> = buildList {
        database.readableDatabase.rawQuery("SELECT id,local_qso_id,remote_qso_id,baseline_canonical,local_canonical,remote_canonical,conflicting_fields_json,state,created_at,resolved_at,resolution_intent,resolution_canonical,resolution_outbox_id FROM wavelog_conflict WHERE binding_id=? ORDER BY created_at",
            arrayOf(bindingId)).use { cursor -> while (cursor.moveToNext()) add(WavelogConflict(
                cursor.getString(0), bindingId, cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                cursor.getString(5), JSONArray(cursor.getString(6)).jsonStringList().toSet(), WavelogConflictState.valueOf(cursor.getString(7)),
                cursor.getLong(8), cursor.getLong(9).takeUnless { cursor.isNull(9) },
                cursor.getString(10)?.let(WavelogConflictState::valueOf), cursor.getString(11), cursor.getString(12))) }
    }

    fun openConflict(bindingId: String, localQsoId: String): WavelogConflict? =
        conflicts(bindingId).firstOrNull { it.localQsoId == localQsoId && it.state == WavelogConflictState.OPEN }

    fun setConflictResolutionIntent(id: String, intent: WavelogConflictState, canonical: CanonicalQso,
        outboxId: String) {
        require(intent != WavelogConflictState.OPEN)
        database.writableDatabase.execSQL(
            "UPDATE wavelog_conflict SET resolution_intent=?,resolution_canonical=?,resolution_outbox_id=? WHERE id=? AND state='OPEN' AND resolution_intent IS NULL",
            arrayOf(intent.name, canonical.encoded, outboxId, id),
        )
    }

    fun completeConflictForOutbox(outboxId: String) {
        database.writableDatabase.execSQL(
            "UPDATE wavelog_conflict SET state=resolution_intent,resolved_at=? WHERE resolution_outbox_id=? AND state='OPEN' AND resolution_intent IS NOT NULL",
            arrayOf(System.currentTimeMillis() / 1_000, outboxId),
        )
    }

    fun resolveConflict(id: String, state: WavelogConflictState) {
        require(state != WavelogConflictState.OPEN)
        database.writableDatabase.execSQL("UPDATE wavelog_conflict SET state=?,resolved_at=? WHERE id=? AND state='OPEN'",
            arrayOf(state.name, System.currentTimeMillis() / 1_000, id))
    }

    fun saveTombstone(tombstone: WavelogTombstone, baselineCanonical: String) {
        database.writableDatabase.execSQL("INSERT OR REPLACE INTO wavelog_tombstone(binding_id,local_qso_id,remote_qso_id,canonical_hash,baseline_canonical,deleted_at,acknowledged_at,delete_intent) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(tombstone.bindingId, tombstone.localQsoId, tombstone.remoteQsoId, tombstone.canonicalHash,
                baselineCanonical, tombstone.deletedAt, tombstone.acknowledgedAt, tombstone.intent.name))
    }

    fun tombstone(bindingId: String, localQsoId: String): Pair<WavelogTombstone, String>? = database.readableDatabase.rawQuery(
        "SELECT remote_qso_id,canonical_hash,baseline_canonical,deleted_at,acknowledged_at,delete_intent FROM wavelog_tombstone WHERE binding_id=? AND local_qso_id=?",
        arrayOf(bindingId, localQsoId)).use { cursor -> if (!cursor.moveToFirst()) null else
            WavelogTombstone(bindingId, localQsoId, cursor.getString(0), cursor.getString(1), cursor.getLong(3),
                cursor.getLong(4).takeUnless { cursor.isNull(4) }, QsoDeleteIntent.valueOf(cursor.getString(5))) to cursor.getString(2) }

    fun tombstones(bindingId: String): List<WavelogTombstone> = buildList {
        database.readableDatabase.rawQuery(
            "SELECT local_qso_id,remote_qso_id,canonical_hash,deleted_at,acknowledged_at,delete_intent FROM wavelog_tombstone WHERE binding_id=? ORDER BY deleted_at DESC",
            arrayOf(bindingId),
        ).use { cursor -> while (cursor.moveToNext()) add(WavelogTombstone(
            bindingId, cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
            cursor.getLong(4).takeUnless { cursor.isNull(4) }, QsoDeleteIntent.valueOf(cursor.getString(5)),
        )) }
    }

    fun acknowledgeTombstone(bindingId: String, localQsoId: String) {
        database.writableDatabase.execSQL("UPDATE wavelog_tombstone SET acknowledged_at=? WHERE binding_id=? AND local_qso_id=?",
            arrayOf(System.currentTimeMillis() / 1_000, bindingId, localQsoId))
    }

    fun removeTombstone(bindingId: String, localQsoId: String) {
        database.writableDatabase.delete("wavelog_tombstone", "binding_id=? AND local_qso_id=?", arrayOf(bindingId, localQsoId))
    }

    fun deleteDecision(bindingId: String, localQsoId: String): WavelogOutboxEntry? =
        outbox("binding_id=? AND local_qso_id=? AND operation='DELETE' AND state<>'ACCEPTED' ORDER BY created_at DESC LIMIT 1",
            arrayOf(bindingId, localQsoId)).firstOrNull()

    fun resumeDelete(bindingId: String, localQsoId: String): String {
        val entry = requireNotNull(deleteDecision(bindingId, localQsoId)) { "Remote deletion intent is missing" }
        updateOutbox(entry.copy(state = WavelogOutboxState.PENDING, nextAttemptAt = null, lastError = "",
            errorClass = WavelogErrorClass.NONE, updatedAt = System.currentTimeMillis() / 1_000))
        return entry.id
    }

    fun cancelDelete(bindingId: String, localQsoId: String) {
        deleteDecision(bindingId, localQsoId)?.let { updateOutbox(it.copy(state = WavelogOutboxState.ACCEPTED,
            nextAttemptAt = null, lastError = "Delete cancelled by conflict resolution",
            updatedAt = System.currentTimeMillis() / 1_000)) }
    }

    fun remoteLinks(bindingId: String): List<WavelogRemoteLink> = buildList {
        database.readableDatabase.rawQuery("SELECT local_qso_id,remote_qso_id,baseline_hash,baseline_canonical,remote_updated_at FROM wavelog_remote_link WHERE binding_id=?",
            arrayOf(bindingId)).use { cursor -> while (cursor.moveToNext()) add(WavelogRemoteLink(
                bindingId, cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))) }
    }

    fun acceptWithLink(entry: WavelogOutboxEntry, link: WavelogRemoteLink) = database.transaction {
        saveLink(link)
        updateOutbox(entry.copy(state = WavelogOutboxState.ACCEPTED, attemptCount = entry.attemptCount + 1,
            nextAttemptAt = null, lastError = "", errorClass = WavelogErrorClass.NONE,
            updatedAt = System.currentTimeMillis() / 1_000))
        completeConflictForOutbox(entry.id)
    }

    fun acceptDelete(entry: WavelogOutboxEntry) = database.transaction {
        acknowledgeTombstone(entry.bindingId, entry.localQsoId)
        updateOutbox(entry.copy(state = WavelogOutboxState.ACCEPTED, attemptCount = entry.attemptCount + 1,
            nextAttemptAt = null, lastError = "", errorClass = WavelogErrorClass.NONE,
            updatedAt = System.currentTimeMillis() / 1_000))
        completeConflictForOutbox(entry.id)
    }

    fun acceptConverged(conflict: WavelogConflict, link: WavelogRemoteLink) = database.transaction {
        saveLink(link)
        val entry = outbox("id=?", arrayOf(conflict.resolutionOutboxId)).firstOrNull()
        if (entry != null) updateOutbox(entry.copy(
            state = WavelogOutboxState.ACCEPTED, nextAttemptAt = null, lastError = "",
            errorClass = WavelogErrorClass.NONE, updatedAt = System.currentTimeMillis() / 1_000,
        ))
        completeConflictForOutbox(conflict.resolutionOutboxId)
    }

    private fun activeFor(bindingId: String, localQsoId: String): List<WavelogOutboxEntry> =
        outbox("binding_id=? AND local_qso_id=? AND state<>'ACCEPTED' ORDER BY created_at", arrayOf(bindingId, localQsoId))

    private fun outbox(where: String, args: Array<String>): List<WavelogOutboxEntry> = buildList {
        database.readableDatabase.rawQuery("SELECT id,binding_id,local_qso_id,operation,idempotency_key,payload_hash,canonical_payload,state,attempt_count,next_attempt_at,last_error,created_at,updated_at,error_class FROM wavelog_outbox WHERE $where", args).use { cursor ->
            while (cursor.moveToNext()) add(WavelogOutboxEntry(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), WavelogOperation.valueOf(cursor.getString(3)),
                cursor.getString(4), cursor.getString(5), cursor.getString(6), WavelogOutboxState.valueOf(cursor.getString(7)),
                cursor.getInt(8), cursor.getLong(9).takeUnless { cursor.isNull(9) }, cursor.getString(10), cursor.getLong(11), cursor.getLong(12),
                WavelogErrorClass.valueOf(cursor.getString(13))))
        }
    }

    private fun capabilities(scopes: Set<String>) = WavelogCapabilities(
        scopes = scopes, canReadQsos = "qso:read" in scopes, canWriteQsos = "qso:write" in scopes,
        canDeleteQsos = "qso:delete" in scopes, canReadStations = "station:read" in scopes,
        canReadStatistics = "statistic:read" in scopes,
    )
}
