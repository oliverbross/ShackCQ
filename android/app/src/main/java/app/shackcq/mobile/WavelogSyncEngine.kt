package app.shackcq.mobile

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class WavelogConnectionInspection(
    val token: WavelogTokenMetadata,
    val stations: List<WavelogV2Station>,
)

data class WavelogSyncSummary(
    val imported: Int = 0,
    val linked: Int = 0,
    val pulled: Int = 0,
    val queuedForPush: Int = 0,
    val merged: Int = 0,
    val ambiguous: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val deleted: Int = 0,
    val pages: Int = 0,
    val resumedFromPage: Int = 1,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val inventoryStable: Boolean = false,
    val scope: String = "",
) {
    operator fun plus(other: WavelogSyncSummary) = copy(
        imported = imported + other.imported,
        linked = linked + other.linked,
        pulled = pulled + other.pulled,
        queuedForPush = queuedForPush + other.queuedForPush,
        merged = merged + other.merged,
        ambiguous = ambiguous + other.ambiguous,
        conflicts = conflicts + other.conflicts,
        rejected = rejected + other.rejected,
        deleted = deleted + other.deleted,
        pages = pages + other.pages,
        completed = completed || other.completed,
        cancelled = cancelled || other.cancelled,
    )
}

class WavelogSyncEngine(
    private val database: QsoDatabase,
    private val store: WavelogSyncStore,
    private val client: WavelogApiV2Client,
    private val mutations: QsoMutationCoordinator = QsoMutationCoordinator(database, store),
) {
    fun inspectConnection(): WavelogConnectionInspection {
        val token = client.tokenMetadata()
        return WavelogConnectionInspection(
            token,
            if (token.capabilities.canReadStations) client.stations() else emptyList(),
        )
    }

    fun initialSync(
        binding: WavelogBinding,
        onProgress: (Int, WavelogSyncSummary) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): WavelogSyncSummary = pagedSync(binding, "INITIAL", 10_000, isCancelled, onProgress)

    fun quickSync(
        binding: WavelogBinding,
        overlapPages: Int = 3,
        onProgress: (Int, WavelogSyncSummary) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): WavelogSyncSummary {
        drainOutbox(binding)
        return pagedSync(binding, "QUICK", overlapPages.coerceIn(1, 10), isCancelled, onProgress)
    }

    fun fullReconcile(
        binding: WavelogBinding,
        onProgress: (Int, WavelogSyncSummary) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): WavelogSyncSummary {
        drainOutbox(binding)
        val first = pagedSync(binding, "FULL_A", 10_000, isCancelled, onProgress, restartAtFirstPage = true)
        if (!first.completed || first.cancelled) return first.copy(scope = "FULL", completed = false)
        val firstSeen = store.seenIds(binding.id, "FULL_A")
        val second = pagedSync(binding, "FULL_B", 10_000, isCancelled, onProgress, restartAtFirstPage = true)
        var combined = first + second
        if (!second.completed || second.cancelled) {
            return combined.copy(scope = "FULL", completed = false, inventoryStable = false)
        }
        val secondSeen = store.seenIds(binding.id, "FULL_B")
        val stable = firstSeen == secondSeen
        if (stable) combined += reconcileRemoteDeletions(binding, secondSeen)
        return combined.copy(scope = "FULL", completed = stable, inventoryStable = stable, cancelled = false,
            resumedFromPage = 1)
    }

    fun drainOutbox(binding: WavelogBinding): Int {
        if (binding.state != WavelogBindingState.ENABLED || !binding.capabilities.canWriteQsos) return 0
        var accepted = 0
        store.pending(binding.id).forEach { entry ->
            val now = System.currentTimeMillis() / 1_000
            try {
                when (entry.operation) {
                    WavelogOperation.CREATE -> {
                        val qso = requireNotNull(database.qso(entry.localQsoId)) { "Local QSO no longer exists" }
                        val canonical = canonical(qso)
                        val row = client.createQso(binding.remoteStationId, canonical)
                        val remoteId = row.optString("id")
                        require(remoteId.isNotBlank()) { "Wavelog create response did not contain a QSO id" }
                        val remoteCanonical = canonical(remoteQso(binding, row)
                            ?: remoteQso(binding, client.qso(remoteId))
                            ?: error("Wavelog create response could not be decoded"))
                        store.acceptWithLink(entry, WavelogRemoteLink(
                            binding.id, entry.localQsoId, remoteId, remoteCanonical.hash, remoteCanonical.encoded,
                        ))
                    }
                    WavelogOperation.UPDATE -> {
                        val qso = requireNotNull(database.qso(entry.localQsoId)) { "Local QSO no longer exists" }
                        val link = store.link(binding.id, entry.localQsoId)
                            ?: error("Remote link required before update")
                        val baseline = CanonicalQso.decode(link.baselineCanonical)
                        val row = client.patchQso(link.remoteQsoId, baseline, canonical(qso))
                        val remote = remoteQso(binding, row)
                            ?: remoteQso(binding, client.qso(link.remoteQsoId))
                            ?: error("Wavelog update response could not be decoded")
                        val remoteCanonical = canonical(remote)
                        store.acceptWithLink(entry, link.copy(
                            baselineHash = remoteCanonical.hash,
                            baselineCanonical = remoteCanonical.encoded,
                        ))
                    }
                    WavelogOperation.DELETE -> {
                        val storedTombstone = store.tombstone(binding.id, entry.localQsoId)
                            ?: error("Deletion baseline is missing")
                        val tombstone = storedTombstone.first
                        val baseline = CanonicalQso.decode(storedTombstone.second)
                        require(tombstone.remoteQsoId.isNotBlank()) { "Remote QSO id is missing from tombstone" }
                        val current = try {
                            remoteQso(binding, client.qso(tombstone.remoteQsoId))
                                ?: error("Remote QSO could not be decoded")
                        } catch (error: WavelogApiException) {
                            if (error.status == 404) null else throw error
                        }
                        if (current == null) {
                            store.acceptDelete(entry)
                        } else {
                            val remoteCanonical = canonical(current)
                            if (remoteCanonical.hash != tombstone.canonicalHash) {
                                saveConflict(binding, entry.localQsoId, tombstone.remoteQsoId,
                                    baseline, CanonicalQso(emptyMap()),
                                    remoteCanonical, setOf("LOCAL_DELETE"))
                                store.updateOutbox(entry.copy(
                                    state = WavelogOutboxState.BLOCKED,
                                    attemptCount = entry.attemptCount + 1,
                                    nextAttemptAt = null,
                                    lastError = "Remote QSO changed after local deletion; operator decision required",
                                    errorClass = WavelogErrorClass.CONFLICT,
                                    updatedAt = now,
                                ))
                                return@forEach
                            }
                            client.deleteQso(tombstone.remoteQsoId)
                            store.acceptDelete(entry)
                        }
                    }
                }
                accepted++
            } catch (error: WavelogApiException) {
                val uncertainWrite = error.status == 0 || error.status >= 500
                val ambiguous = uncertainWrite && entry.operation in setOf(WavelogOperation.CREATE, WavelogOperation.DELETE)
                val retryable = error.status == 429 || (uncertainWrite && entry.operation == WavelogOperation.UPDATE)
                val delay = error.retryAfterSeconds ?: (60L shl entry.attemptCount.coerceIn(0, 6))
                store.updateOutbox(entry.copy(
                    state = if (retryable) WavelogOutboxState.RETRY_WAIT else WavelogOutboxState.BLOCKED,
                    attemptCount = entry.attemptCount + 1,
                    nextAttemptAt = if (retryable) now + delay else null,
                    lastError = if (ambiguous)
                        "Ambiguous ${entry.operation.name.lowercase()} result; scan Wavelog and reconcile before retry"
                    else error.message.orEmpty().take(500),
                    errorClass = if (ambiguous) WavelogErrorClass.AMBIGUOUS_WRITE else error.errorClass,
                    updatedAt = now,
                ))
            } catch (error: Exception) {
                store.updateOutbox(entry.copy(
                    state = WavelogOutboxState.BLOCKED,
                    attemptCount = entry.attemptCount + 1,
                    nextAttemptAt = null,
                    lastError = error.message.orEmpty().take(500),
                    errorClass = WavelogErrorClass.VALIDATION,
                    updatedAt = now,
                ))
            }
        }
        return accepted
    }

    fun resolveConflict(
        binding: WavelogBinding,
        conflict: WavelogConflict,
        resolution: WavelogConflictState,
        merged: CanonicalQso? = null,
    ) {
        require(resolution != WavelogConflictState.OPEN)
        conflict.resolutionIntent?.let { existing ->
            require(existing == resolution) { "A different conflict resolution is already pending" }
            drainOutbox(binding)
            return
        }
        val local = CanonicalQso.decode(conflict.localCanonical)
        val remote = CanonicalQso.decode(conflict.remoteCanonical)
        when (resolution) {
            WavelogConflictState.KEEP_LOCAL -> {
                val outboxId = when {
                    "LOCAL_DELETE" in conflict.conflictingFields -> store.resumeDelete(binding.id, conflict.localQsoId)
                    "REMOTE_DELETE" in conflict.conflictingFields -> {
                    store.deleteLink(binding.id, conflict.localQsoId)
                    store.enqueue(binding.id, conflict.localQsoId, WavelogOperation.CREATE, local)
                    }
                    else -> store.enqueue(binding.id, conflict.localQsoId, WavelogOperation.UPDATE, local)
                }
                store.setConflictResolutionIntent(conflict.id, resolution, local, outboxId)
                drainOutbox(binding)
            }
            WavelogConflictState.KEEP_REMOTE -> {
                when {
                    "REMOTE_DELETE" in conflict.conflictingFields -> {
                        store.cancelWrites(binding.id, conflict.localQsoId, "Superseded by Keep Remote deletion")
                        mutations.delete(conflict.localQsoId, QsoDeleteIntent.REMOTE_SYNC, QsoOrigin.REMOTE_SYNC)
                    }
                    "LOCAL_DELETE" in conflict.conflictingFields -> {
                        val restored = qsoFromCanonical(binding, remote, conflict.remoteQsoId, conflict.localQsoId)
                            ?: error("Remote conflict value is not a valid QSO")
                        mutations.save(restored, QsoOrigin.REMOTE_SYNC)
                        store.cancelDelete(binding.id, conflict.localQsoId)
                        store.saveLink(WavelogRemoteLink(binding.id, conflict.localQsoId, conflict.remoteQsoId,
                            remote.hash, remote.encoded))
                    }
                    else -> {
                        store.cancelWrites(binding.id, conflict.localQsoId, "Superseded by Keep Remote resolution")
                        val replacement = qsoFromCanonical(binding, remote, conflict.remoteQsoId, conflict.localQsoId)
                            ?: error("Remote conflict value is not a valid QSO")
                        mutations.update(replacement, QsoOrigin.REMOTE_SYNC)
                        store.saveLink(WavelogRemoteLink(binding.id, conflict.localQsoId, conflict.remoteQsoId,
                            remote.hash, remote.encoded))
                    }
                }
                store.resolveConflict(conflict.id, resolution)
            }
            WavelogConflictState.MERGED -> {
                val chosen = requireNotNull(merged) { "Merged field values are required" }
                val replacement = qsoFromCanonical(binding, chosen, conflict.remoteQsoId, conflict.localQsoId)
                    ?: error("Merged conflict value is not a valid QSO")
                if (database.qso(conflict.localQsoId) == null) mutations.save(replacement, QsoOrigin.REMOTE_SYNC)
                else mutations.update(replacement, QsoOrigin.REMOTE_SYNC)
                val outboxId = if ("REMOTE_DELETE" in conflict.conflictingFields) {
                    store.deleteLink(binding.id, conflict.localQsoId)
                    store.enqueue(binding.id, conflict.localQsoId, WavelogOperation.CREATE, chosen)
                } else {
                    store.enqueue(binding.id, conflict.localQsoId, WavelogOperation.UPDATE, chosen)
                }
                store.setConflictResolutionIntent(conflict.id, resolution, chosen, outboxId)
                drainOutbox(binding)
            }
            WavelogConflictState.OPEN -> Unit
        }
    }

    private fun pagedSync(
        binding: WavelogBinding,
        kind: String,
        maxPages: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int, WavelogSyncSummary) -> Unit,
        restartAtFirstPage: Boolean = false,
    ): WavelogSyncSummary {
        require(binding.capabilities.canReadQsos) { "qso:read scope is required" }
        require(binding.remoteStationId.isNotBlank()) { "Select a Wavelog station before syncing" }
        val resumable = kind != "QUICK"
        val saved = if (resumable && !restartAtFirstPage) store.checkpoint(binding.id, kind)?.takeUnless { it.completed } else null
        val startPage = saved?.page?.coerceAtLeast(1) ?: 1
        if (startPage == 1 && resumable) store.clearSeen(binding.id, kind)
        var summary = WavelogSyncSummary(resumedFromPage = startPage, scope = kind)
        var pageNumber = startPage
        var completed = false
        while (pageNumber < startPage + maxPages) {
            if (isCancelled()) {
                return summary.copy(cancelled = true, completed = false)
            }
            val page = client.qsoPage(binding.remoteStationId, pageNumber)
            var pageSummary = WavelogSyncSummary(pages = 1)
            page.rows.forEach { row -> pageSummary += reconcileRow(binding, row) }
            summary += pageSummary
            val ids = page.rows.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
            val hasNextWithinScope = page.hasMore && pageNumber + 1 < startPage + maxPages
            if (resumable) {
                database.transaction {
                    store.markSeen(binding.id, kind, ids)
                    store.saveCheckpoint(WavelogSyncCheckpoint(
                        binding.id, kind, pageNumber + 1,
                        ids.maxByOrNull { it.toLongOrNull() ?: Long.MIN_VALUE }.orEmpty(),
                        ids.joinToString(","), completed = !page.hasMore,
                        updatedAt = System.currentTimeMillis() / 1_000,
                    ))
                }
            } else {
                store.saveCheckpoint(WavelogSyncCheckpoint(
                    binding.id, kind, pageNumber + 1, ids.firstOrNull().orEmpty(),
                    ids.joinToString(","), completed = !hasNextWithinScope,
                    updatedAt = System.currentTimeMillis() / 1_000,
                ))
            }
            onProgress(pageNumber, summary)
            if (!page.hasMore) {
                completed = true
                break
            }
            if (!hasNextWithinScope) break
            pageNumber++
        }
        return summary.copy(completed = completed || kind == "QUICK", cancelled = false)
    }

    private fun reconcileRow(binding: WavelogBinding, row: JSONObject): WavelogSyncSummary {
        val remoteId = row.optString("id")
        if (remoteId.isBlank()) return WavelogSyncSummary(rejected = 1)
        val remote = remoteQso(binding, row) ?: return WavelogSyncSummary(rejected = 1)
        val remoteCanonical = canonical(remote)
        store.linkByRemote(binding.id, remoteId)?.let {
            return reconcileLinked(binding, it, remote, remoteCanonical)
        }

        val candidates = database.naturalCandidates(remote)
        val blocked = candidates.mapNotNull { store.blockedCreate(binding.id, it.id) }
        if (candidates.size == 1 && blocked.size == 1) {
            val local = candidates.single()
            store.acceptWithLink(blocked.single(), WavelogRemoteLink(
                binding.id, local.id, remoteId, remoteCanonical.hash, remoteCanonical.encoded,
            ))
            return WavelogSyncSummary(linked = 1) + reconcileLinked(
                binding, requireNotNull(store.link(binding.id, local.id)), remote, remoteCanonical,
            )
        }
        if (blocked.isNotEmpty()) {
            return WavelogSyncSummary(ambiguous = 1)
        }
        if (candidates.size == 1) {
            val local = candidates.single()
            val localCanonical = canonical(local)
            if (localCanonical.hash == remoteCanonical.hash) {
                store.saveLink(WavelogRemoteLink(
                    binding.id, local.id, remoteId, remoteCanonical.hash, remoteCanonical.encoded,
                ))
                return WavelogSyncSummary(linked = 1)
            }
            saveConflict(binding, local.id, remoteId, CanonicalQso(emptyMap()), localCanonical,
                remoteCanonical, localCanonical.changedFields(remoteCanonical))
            return WavelogSyncSummary(ambiguous = 1, conflicts = 1)
        }
        if (candidates.isNotEmpty()) return WavelogSyncSummary(ambiguous = 1)
        val inserted = remote.copy(id = "wavelog-${binding.id}-$remoteId")
        mutations.save(inserted, QsoOrigin.REMOTE_SYNC)
        store.saveLink(WavelogRemoteLink(
            binding.id, inserted.id, remoteId, remoteCanonical.hash, remoteCanonical.encoded,
        ))
        return WavelogSyncSummary(imported = 1, ambiguous = if (candidates.isEmpty()) 0 else 1)
    }

    private fun reconcileLinked(
        binding: WavelogBinding,
        link: WavelogRemoteLink,
        remote: Qso,
        remoteCanonical: CanonicalQso,
    ): WavelogSyncSummary {
        val local = database.qso(link.localQsoId) ?: return WavelogSyncSummary(rejected = 1)
        store.openConflict(binding.id, link.localQsoId)?.let { conflict ->
            val intended = conflict.resolutionIntent?.let { CanonicalQso.decode(conflict.resolutionCanonical) }
            if (intended != null && intended.hash == remoteCanonical.hash) {
                store.acceptConverged(conflict, link.copy(
                    baselineHash = remoteCanonical.hash,
                    baselineCanonical = remoteCanonical.encoded,
                ))
                return WavelogSyncSummary(linked = 1)
            }
        }
        val base = CanonicalQso.decode(link.baselineCanonical)
        val localCanonical = canonical(local)
        val result = WavelogCanonicalizer.merge(base, localCanonical, remoteCanonical)
        return when (result.disposition) {
            "UNCHANGED", "CONVERGED" -> {
                store.saveLink(link.copy(baselineHash = remoteCanonical.hash, baselineCanonical = remoteCanonical.encoded))
                WavelogSyncSummary(linked = 1)
            }
            "PUSH_LOCAL" -> {
                store.enqueue(binding.id, local.id, WavelogOperation.UPDATE, localCanonical)
                WavelogSyncSummary(queuedForPush = 1)
            }
            "PULL_REMOTE" -> {
                mutations.update(remote.copy(id = local.id, syncState = "synced"), QsoOrigin.REMOTE_SYNC)
                store.saveLink(link.copy(baselineHash = remoteCanonical.hash, baselineCanonical = remoteCanonical.encoded))
                WavelogSyncSummary(pulled = 1)
            }
            "SAFE_MERGE" -> {
                val merged = result.merged ?: return WavelogSyncSummary(rejected = 1)
                val qso = qsoFromCanonical(binding, merged, link.remoteQsoId, local.id)
                    ?: return WavelogSyncSummary(rejected = 1)
                mutations.update(qso.copy(syncState = "local"), QsoOrigin.REMOTE_SYNC)
                store.enqueue(binding.id, local.id, WavelogOperation.UPDATE, merged)
                WavelogSyncSummary(merged = 1, queuedForPush = 1)
            }
            else -> {
                saveConflict(binding, local.id, link.remoteQsoId, base, localCanonical, remoteCanonical,
                    result.conflictingFields)
                WavelogSyncSummary(conflicts = 1)
            }
        }
    }

    private fun reconcileRemoteDeletions(binding: WavelogBinding, seen: Set<String>): WavelogSyncSummary {
        var summary = WavelogSyncSummary()
        store.tombstones(binding.id)
            .filter { it.intent == QsoDeleteIntent.DELETE_REMOTE_IF_UNCHANGED && it.acknowledgedAt == null && it.remoteQsoId !in seen }
            .forEach { tombstone ->
                store.deleteDecision(binding.id, tombstone.localQsoId)?.let {
                    store.acceptDelete(it)
                    summary += WavelogSyncSummary(deleted = 1)
                }
            }
        store.remoteLinks(binding.id).filter { it.remoteQsoId !in seen }.forEach { link ->
            val local = database.qso(link.localQsoId)
            if (local == null) {
                store.deleteDecision(binding.id, link.localQsoId)?.let(store::acceptDelete)
                store.deleteLink(binding.id, link.localQsoId)
            } else {
                val baseline = CanonicalQso.decode(link.baselineCanonical)
                val current = canonical(local)
                if (current.hash == baseline.hash) {
                    mutations.delete(local.id, QsoDeleteIntent.REMOTE_SYNC, QsoOrigin.REMOTE_SYNC)
                    store.deleteLink(binding.id, local.id)
                    summary += WavelogSyncSummary(deleted = 1)
                } else {
                    saveConflict(binding, local.id, link.remoteQsoId, baseline, current,
                        CanonicalQso(emptyMap()), setOf("REMOTE_DELETE"))
                    summary += WavelogSyncSummary(conflicts = 1)
                }
            }
        }
        return summary
    }

    private fun saveConflict(
        binding: WavelogBinding,
        localId: String,
        remoteId: String,
        base: CanonicalQso,
        local: CanonicalQso,
        remote: CanonicalQso,
        fields: Set<String>,
    ) {
        if (store.openConflict(binding.id, localId) != null) return
        store.saveConflict(WavelogConflict(
            UUID.randomUUID().toString(), binding.id, localId, remoteId,
            base.encoded, local.encoded, remote.encoded, fields,
            WavelogConflictState.OPEN, System.currentTimeMillis() / 1_000,
        ))
    }

    private fun canonical(qso: Qso) = WavelogCanonicalizer.fromAdif(database.toADIF(qso))

    private fun qsoFromCanonical(
        binding: WavelogBinding,
        canonical: CanonicalQso,
        remoteId: String,
        localId: String,
    ): Qso? {
        val qso = database.qsoFromFields(
            { canonical.fields[it].orEmpty() }, remoteId, localStation(binding),
            canonical.fields.filterKeys { it !in WavelogCanonicalizer.shackCQFields },
        ) ?: return null
        return qso.copy(id = localId, remoteId = remoteId)
    }

    private fun remoteQso(binding: WavelogBinding, row: JSONObject): Qso? {
        val values = buildMap<String, String> {
            row.keys().forEach { key ->
                row.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
                    ?.let { put(key.uppercase(), it) }
            }
        }.toMutableMap()
        parseDateTime(values["QSO_DATE"].orEmpty())?.let {
            values["QSO_DATE"] = it.first
            values["TIME_ON"] = it.second
        }
        listOf("FREQ", "FREQ_RX").forEach { field ->
            values[field]?.toBigDecimalOrNull()?.takeIf { it >= 100_000.toBigDecimal() }
                ?.let { values[field] = it.movePointLeft(6).stripTrailingZeros().toPlainString() }
        }
        val remoteId = values["ID"].orEmpty()
        val extras = values.filterKeys {
            it !in WavelogCanonicalizer.shackCQFields && it !in setOf("ID", "STATION_ID")
        }
        val qso = database.qsoFromFields({ values[it].orEmpty() }, remoteId, localStation(binding), extras)
            ?: return null
        return qso.copy(remoteId = remoteId, syncState = "synced")
    }

    private fun localStation(binding: WavelogBinding) =
        binding.localStationProfileId.takeUnless { it == DEFAULT_LOCAL_STATION }.orEmpty()

    private fun parseDateTime(value: String): Pair<String, String>? {
        val normalized = value.trim().replace('T', ' ').removeSuffix("Z")
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyyMMddHHmmss")
        patterns.forEach { pattern ->
            runCatching {
                val date = LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern(pattern))
                return date.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd")) to
                    date.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HHmmss"))
            }
        }
        return null
    }
}
