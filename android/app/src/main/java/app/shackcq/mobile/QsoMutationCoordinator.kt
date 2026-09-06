package app.shackcq.mobile

const val DEFAULT_LOCAL_STATION = "LOCAL_DEFAULT"
data class FastEntryImportReceipt(val qsoIds: List<String>, val skipped: Int, val revision: Long)

class QsoMutationCoordinator(
    private val database: QsoDatabase,
    private val store: WavelogSyncStore = WavelogSyncStore(database),
) {
    fun save(qso: Qso, origin: QsoOrigin = QsoOrigin.OPERATOR): Boolean = database.transaction { saveInTransaction(qso, origin) }

    fun saveBatch(rows: List<Qso>, origin: QsoOrigin): Pair<Int, Int> = database.transaction {
        var added = 0; var skipped = 0
        rows.forEach { if (saveInTransaction(it, origin)) added++ else skipped++ }
        added to skipped
    }

    fun importFastEntry(rows: List<Qso>): FastEntryImportReceipt = database.transaction {
        val inserted = mutableListOf<String>(); var skipped = 0
        rows.forEach { qso -> if (saveInTransaction(qso, QsoOrigin.IMPORT)) inserted += qso.id else skipped++ }
        FastEntryImportReceipt(inserted, skipped, database.changeToken())
    }

    /** Undo is deliberately bounded: any later local mutation invalidates the receipt. */
    fun undoFastEntry(receipt: FastEntryImportReceipt): Boolean = database.transaction {
            if (database.changeToken() != receipt.revision) return@transaction false
            val binding = store.configuredBinding()
            if (binding != null) {
                val queued = store.outboxEntries(binding.id).groupBy { it.localQsoId }
                val unsafe = receipt.qsoIds.any { id ->
                    val qso = database.qso(id) ?: return@any true
                    if (!applies(binding, qso)) false else store.link(binding.id, id) != null ||
                        queued[id].orEmpty().any { it.operation != WavelogOperation.CREATE || it.attemptCount != 0 || it.state == WavelogOutboxState.ACCEPTED }
                }
                if (unsafe) return@transaction false
            }
            receipt.qsoIds.forEach { id ->
                binding?.let { store.cancelUnattemptedCreate(it.id, id) }
                database.deleteLocal(id)
            }
            true
        }

    private fun saveInTransaction(qso: Qso, origin: QsoOrigin): Boolean {
        val inserted = database.save(qso, origin)
        if (inserted) recordMutation(qso, origin, WavelogOperation.CREATE)
        return inserted
    }

    fun update(qso: Qso, origin: QsoOrigin = QsoOrigin.OPERATOR) = database.transaction {
        database.updateLocal(qso)
        recordMutation(qso, origin, WavelogOperation.UPDATE)
    }

    fun delete(id: String, intent: QsoDeleteIntent, origin: QsoOrigin = QsoOrigin.OPERATOR) = database.transaction {
        val qso = database.qso(id) ?: return@transaction
        val binding = store.configuredBinding()?.takeIf { applies(it, qso) }
        if (origin == QsoOrigin.REMOTE_SYNC) {
            require(intent == QsoDeleteIntent.REMOTE_SYNC)
        } else if (intent == QsoDeleteIntent.DELETE_REMOTE_IF_UNCHANGED) {
            requireNotNull(binding) { "This QSO is not mapped to Wavelog" }
            require(binding.state != WavelogBindingState.READ_ONLY) { "Read-only Wavelog tokens cannot delete remote QSOs" }
            require(binding.capabilities.canDeleteQsos) { "The Wavelog token is missing qso:delete; no QSO was deleted" }
        }
        if (origin != QsoOrigin.REMOTE_SYNC && binding != null) {
            val link = store.link(binding.id, id)
            if (link == null) {
                store.cancelUnsentCreate(binding.id, id)
            } else {
                store.saveTombstone(WavelogTombstone(binding.id, id, link.remoteQsoId, link.baselineHash,
                    System.currentTimeMillis() / 1_000, intent = intent), link.baselineCanonical)
                if (intent == QsoDeleteIntent.DELETE_REMOTE_IF_UNCHANGED) {
                    store.enqueue(binding.id, id, WavelogOperation.DELETE, CanonicalQso(emptyMap()),
                        state = if (binding.state == WavelogBindingState.PAUSED)
                            WavelogOutboxState.PAUSED else WavelogOutboxState.PENDING)
                }
            }
        }
        database.deleteLocal(id)
    }

    fun importADIF(text: String): Pair<Int, Int> {
        val (rows, invalid) = database.parseADIF(text)
        val result = saveBatch(rows, QsoOrigin.IMPORT)
        return result.first to result.second + invalid
    }

    fun importADIF(input:java.io.InputStream,batchSize:Int=250,cancelled:()->Boolean={false},progress:(AdifImportProgress)->Unit={}):AdifImportProgress {
        val pending=ArrayList<Qso>(batchSize.coerceIn(50,500));var parsed=0;var inserted=0;var duplicates=0;var invalid=0
        fun commit(){if(pending.isEmpty())return;val result=saveBatch(pending,QsoOrigin.IMPORT);inserted+=result.first;duplicates+=result.second;pending.clear();progress(AdifImportProgress(parsed,inserted,duplicates,invalid))}
        val stream=streamAdifRecords(input,cancelled){record->
            val result=database.parseADIF(record);parsed++;invalid+=result.second
            result.first.forEach { pending+=it;if(pending.size>=batchSize.coerceIn(50,500))commit() }
        }
        invalid+=stream.second;commit()
        return AdifImportProgress(parsed,inserted,duplicates,invalid).also(progress)
    }

    fun localStationIds(): List<String> = listOf(DEFAULT_LOCAL_STATION) + database.stationProfileIds()

    fun isMapped(qso: Qso): Boolean = store.configuredBinding()?.let { applies(it, qso) } == true

    fun remoteDeleteUnavailableReason(qso: Qso): String? {
        val binding = store.configuredBinding()?.takeIf { applies(it, qso) }
            ?: return "This QSO is not mapped to Wavelog"
        if (store.link(binding.id, qso.id) == null) return "This QSO has no accepted remote link"
        if (binding.state == WavelogBindingState.READ_ONLY) return "The Wavelog binding is read-only"
        if (!binding.capabilities.canDeleteQsos) return "The token is missing qso:delete"
        return null
    }

    private fun recordMutation(qso: Qso, origin: QsoOrigin, operation: WavelogOperation) {
        if (origin == QsoOrigin.REMOTE_SYNC) return
        val binding = store.configuredBinding()?.takeIf { applies(it, qso) } ?: return
        val canonical = WavelogCanonicalizer.fromAdif(database.toADIF(qso))
        if (binding.state != WavelogBindingState.READ_ONLY) {
            val actual = if (operation == WavelogOperation.UPDATE && store.link(binding.id, qso.id) == null)
                WavelogOperation.CREATE else operation
            store.enqueue(binding.id, qso.id, actual, canonical,
                state = if (binding.state == WavelogBindingState.PAUSED)
                    WavelogOutboxState.PAUSED else WavelogOutboxState.PENDING)
        } else {
            store.enqueue(binding.id, qso.id, operation, canonical, WavelogOutboxState.BLOCKED,
                "Read-only Wavelog binding; local divergence recorded", WavelogErrorClass.MISSING_SCOPE)
        }
    }

    private fun applies(binding: WavelogBinding, qso: Qso): Boolean =
        (binding.localStationProfileId == DEFAULT_LOCAL_STATION && qso.stationProfileId.isBlank()) ||
            (binding.localStationProfileId.isNotBlank() && binding.localStationProfileId == qso.stationProfileId)
}
