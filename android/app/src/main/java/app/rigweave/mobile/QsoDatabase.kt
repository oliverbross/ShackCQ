package app.rigweave.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class Qso(
    val id: String, val callsign: String, val frequencyHz: Long, val mode: String,
    val rstSent: String, val rstReceived: String, val createdAt: Long,
    val name: String = "", val qth: String = "", val notes: String = "", val country: String = "",
    val band: String = "", val grid: String = "", val iota: String = "", val sotaRef: String = "",
    val wwffRef: String = "", val potaRef: String = "", val comment: String = "",
    val frequencyRxHz: Long = 0, val bandRx: String = "", val txPowerW: Int = 0,
    val operatorCallsign: String = "", val stationCallsign: String = "", val stationProfileId: String = "",
    val stationLocation: String = "", val myGrid: String = "", val myCountry: String = "",
    val myDxcc: String = "", val myCqZone: String = "", val myItuZone: String = "", val myState: String = "",
    val myIota: String = "", val mySotaRef: String = "", val myWwffRef: String = "", val myPotaRef: String = "",
    val radioModel: String = "", val dxcc: String = "", val continent: String = "", val region: String = "",
    val cqZone: String = "", val ituZone: String = "", val state: String = "", val email: String = "",
    val propagationMode: String = "", val antennaPath: String = "", val qslSent: String = "N",
    val qslMethod: String = "", val qslVia: String = "", val qslMessage: String = "",
    val submode: String = "", val county: String = "", val dok: String = "", val contestId: String = "",
    val distanceKm: Double = 0.0, val durationSeconds: Long = 0,
    val qslReceived: String = "N", val qslReceivedMethod: String = "",
    val lotwSent: String = "N", val lotwReceived: String = "N",
    val clublogSent: String = "N", val clublogReceived: String = "N",
    val eqslSent: String = "N", val eqslReceived: String = "N",
    val dclSent: String = "N", val dclReceived: String = "N",
    val qrzSent: String = "N", val qrzReceived: String = "N", val qslImages: String = "",
    val syncState: String = "local", val remoteId: String = "",
    val activationSessionId: String = "", val activationProgram: String = "",
    val myPotaRefs: List<String> = emptyList(), val potaRefs: List<String> = emptyList(),
    val extraAdifFields: Map<String, String> = emptyMap(),
)

enum class QsoOrigin { OPERATOR, IMPORT, REMOTE_SYNC }

data class QsoPage(val rows: List<Qso>, val total: Int, val page: Int, val pageSize: Int) {
    val pageCount get() = logbookPageCount(total, pageSize)
}

data class CallsignHistory(val rows: List<Qso>, val total: Int)
data class DxccCell(val worked: Boolean = false, val confirmed: Boolean = false)
data class DxccSummary(val dxcc: String, val country: String, val cells: Map<String, DxccCell>)
data class StationInsight(val record: AndroidCallbookRecord, val history: CallsignHistory, val dxcc: DxccSummary)
data class QsoCatchUpCandidate(val id:String,val stationCallsign:String)
data class HamClockRecentQso(
    val id: String,
    val callsign: String,
    val frequencyHz: Long,
    val band: String,
    val mode: String,
    val country: String,
    val grid: String,
    val createdAt: Long,
)
data class HamClockContestQsoFilter(
    val contestId: String = "",
    val startEpoch: Long,
    val endEpoch: Long,
    val band: String = "",
    val mode: String = "",
    val stationProfileId: String? = null,
    val confirmedOnly: Boolean = false,
    val maximumRows: Int = 200,
)
data class HamClockContestQso(
    val id: String,
    val contestId: String,
    val callsign: String,
    val band: String,
    val mode: String,
    val grid: String,
    val createdAt: Long,
    val confirmed: Boolean,
)
data class NeuralLogSummary(
    val qsos: Int = 0, val calls: Int = 0, val dxccs: Int = 0, val confirmedDxccs: Int = 0,
    val bands: Map<String, Int> = emptyMap(), val modes: Map<String, Int> = emptyMap(),
    val continents: Map<String, Int> = emptyMap(), val countries: Map<String, Int> = emptyMap(),
)

data class WorkedLogQso(
    val callsign: String, val entity: String, val band: String, val mode: String,
    val submode: String, val epoch: Long, val fromWavelog: Boolean,
)

val insightBands = listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m",
    "4m", "2m", "70cm", "23cm", "3cm")
val insightModes = listOf("CW", "FT8", "RTTY", "SSB", "LSB", "USB")

internal fun insightMode(mode: String, submode: String = ""): String {
    val value = submode.ifBlank { mode }.trim().uppercase(java.util.Locale.US)
    return when (value) {
        "CW-R", "CWR" -> "CW"
        else -> value
    }
}

fun bandForFrequency(frequencyHz: Long): String = when (frequencyHz) {
    in 135_700L..137_800L -> "2190m"; in 472_000L..479_000L -> "630m"
    in 1_800_000L..2_000_000L -> "160m"; in 3_500_000L..4_000_000L -> "80m"
    in 5_250_000L..5_450_000L -> "60m"; in 7_000_000L..7_300_000L -> "40m"
    in 10_100_000L..10_150_000L -> "30m"; in 14_000_000L..14_350_000L -> "20m"
    in 18_068_000L..18_168_000L -> "17m"; in 21_000_000L..21_450_000L -> "15m"
    in 24_890_000L..24_990_000L -> "12m"; in 28_000_000L..29_700_000L -> "10m"
    in 50_000_000L..54_000_000L -> "6m"
    in 70_000_000L..70_999_999L -> "4m"; in 144_000_000L..147_999_999L -> "2m"
    in 420_000_000L..449_999_999L -> "70cm"; in 1_240_000_000L..1_299_999_999L -> "23cm"
    in 10_000_000_000L..10_499_999_999L -> "3cm"; else -> ""
}

class QsoDatabase(context: Context, databaseName: String = "rigweave.sqlite") : SQLiteOpenHelper(context, databaseName, null, 17) {
    companion object {
        @Volatile private var sharedInstance: QsoDatabase? = null

        fun shared(context:Context):QsoDatabase = sharedInstance ?: synchronized(this) {
            sharedInstance ?: QsoDatabase(context.applicationContext).also { sharedInstance = it }
        }
    }

    private val changeRevision = AtomicLong(0)
    var operatorSaveHandler: ((Qso) -> Unit)? = null
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA busy_timeout=3000", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE radio_profile(id TEXT PRIMARY KEY, model TEXT NOT NULL)")
        db.execSQL("CREATE TABLE qso(id TEXT PRIMARY KEY, callsign TEXT NOT NULL, frequency_hz INTEGER NOT NULL, mode TEXT NOT NULL, rst_sent TEXT NOT NULL, rst_received TEXT NOT NULL, created_at INTEGER NOT NULL, name TEXT NOT NULL DEFAULT '', qth TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', country TEXT NOT NULL DEFAULT '', details_json TEXT NOT NULL DEFAULT '{}')")
        createPagingIndexes(db)
        createDeliveryTable(db)
        createWavelogSyncTables(db)
        QsoProjectionStore.createSchema(db, ProjectionState.READY)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE qso ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE qso ADD COLUMN qth TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE qso ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE qso ADD COLUMN country TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 4) db.execSQL("ALTER TABLE qso ADD COLUMN details_json TEXT NOT NULL DEFAULT '{}'")
        if (oldVersion < 5) createPagingIndexes(db)
        if (oldVersion < 6) createSpotStatusIndexes(db)
        if (oldVersion < 7) createDeliveryTable(db)
        if (oldVersion < 8) createWavelogSyncTables(db)
        if (oldVersion < 9) migrateWavelogSyncV9(db)
        if (oldVersion < 10) migrateWavelogSyncV10(db)
        if (oldVersion < 11) createAdvancedLogbookIndexes(db)
        if (oldVersion < 12) QsoProjectionStore.createSchema(db, ProjectionState.OPTIMISING)
        if (oldVersion < 13) QsoProjectionStore.migrateV2(db)
        if (oldVersion < 14) QsoProjectionStore.migrateV3(db)
        if (oldVersion < 15) QsoProjectionStore.migrateV4(db)
        if (oldVersion < 16) QsoProjectionStore.migrateV5(db)
        if (oldVersion < 17) QsoProjectionStore.migrateV6(db)
    }

    private fun createPagingIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_created_at_idx ON qso(created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_station_idx ON qso(json_extract(details_json,'$.stationProfileId'))")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_sync_state_idx ON qso(json_extract(details_json,'$.syncState'))")
        createAdvancedLogbookIndexes(db)
        createSpotStatusIndexes(db)
    }

    private fun createAdvancedLogbookIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_frequency_idx ON qso(frequency_hz)")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_mode_time_idx ON qso(UPPER(mode),created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_name_upper_idx ON qso(UPPER(name))")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_qth_upper_idx ON qso(UPPER(qth))")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_station_call_idx ON qso(UPPER(COALESCE(json_extract(details_json,'$.stationCallsign'),'')))")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_remote_id_idx ON qso(COALESCE(json_extract(details_json,'$.remoteId'),''))")
    }

    private fun createSpotStatusIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_callsign_upper_idx ON qso(UPPER(callsign))")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_country_upper_idx ON qso(UPPER(country))")
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_dxcc_upper_idx ON qso(UPPER(COALESCE(json_extract(details_json,'$.dxcc'),'')))")
    }

    private fun createDeliveryTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS qso_delivery(
            qso_id TEXT NOT NULL,
            provider TEXT NOT NULL,
            state TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            attempt_count INTEGER NOT NULL DEFAULT 0,
            last_attempt_at INTEGER,
            next_attempt_at INTEGER,
            payload_hash TEXT NOT NULL DEFAULT '',
            remote_id TEXT NOT NULL DEFAULT '',
            provider_message TEXT NOT NULL DEFAULT '',
            http_status INTEGER,
            PRIMARY KEY(qso_id, provider),
            FOREIGN KEY(qso_id) REFERENCES qso(id) ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS qso_delivery_queue_idx ON qso_delivery(provider,state,next_attempt_at,created_at)")
    }

    fun save(qso: Qso, origin: QsoOrigin = QsoOrigin.OPERATOR): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM qso WHERE callsign=? AND frequency_hz=? AND mode=? AND created_at BETWEEN ? AND ? LIMIT 1",
            arrayOf(qso.callsign, qso.frequencyHz.toString(), qso.mode, (qso.createdAt - 15).toString(), (qso.createdAt + 15).toString())).use {
            if (it.moveToFirst()) return false
        }
        insert(qso)
        if (origin == QsoOrigin.OPERATOR) operatorSaveHandler?.invoke(qso)
        return true
    }

    fun mergeRemote(remote: Qso): Boolean {
        val existing = findNatural(remote)
        if (existing == null) { insert(remote.copy(syncState = "synced")); return true }
        val merged = remote.copy(
            id = existing.id, name = remote.name.ifBlank { existing.name }, qth = remote.qth.ifBlank { existing.qth },
            notes = remote.notes.ifBlank { existing.notes }, country = remote.country.ifBlank { existing.country },
            grid = remote.grid.ifBlank { existing.grid }, comment = remote.comment.ifBlank { existing.comment },
            iota = remote.iota.ifBlank { existing.iota }, sotaRef = remote.sotaRef.ifBlank { existing.sotaRef },
            wwffRef = remote.wwffRef.ifBlank { existing.wwffRef }, potaRef = remote.potaRef.ifBlank { existing.potaRef },
            operatorCallsign = remote.operatorCallsign.ifBlank { existing.operatorCallsign },
            stationCallsign = remote.stationCallsign.ifBlank { existing.stationCallsign },
            stationProfileId = remote.stationProfileId.ifBlank { existing.stationProfileId },
            stationLocation = remote.stationLocation.ifBlank { existing.stationLocation },
            myGrid = remote.myGrid.ifBlank { existing.myGrid }, myCountry = remote.myCountry.ifBlank { existing.myCountry },
            radioModel = remote.radioModel.ifBlank { existing.radioModel }, dxcc = remote.dxcc.ifBlank { existing.dxcc },
            continent = remote.continent.ifBlank { existing.continent }, region = remote.region.ifBlank { existing.region },
            cqZone = remote.cqZone.ifBlank { existing.cqZone }, ituZone = remote.ituZone.ifBlank { existing.ituZone },
            state = remote.state.ifBlank { existing.state }, email = remote.email.ifBlank { existing.email },
            propagationMode = remote.propagationMode.ifBlank { existing.propagationMode },
            antennaPath = remote.antennaPath.ifBlank { existing.antennaPath },
            qslMethod = remote.qslMethod.ifBlank { existing.qslMethod }, qslVia = remote.qslVia.ifBlank { existing.qslVia },
            qslMessage = remote.qslMessage.ifBlank { existing.qslMessage }, submode = remote.submode.ifBlank { existing.submode },
            county = remote.county.ifBlank { existing.county }, dok = remote.dok.ifBlank { existing.dok },
            contestId = remote.contestId.ifBlank { existing.contestId },
            distanceKm = remote.distanceKm.takeIf { it > 0 } ?: existing.distanceKm,
            durationSeconds = remote.durationSeconds.takeIf { it > 0 } ?: existing.durationSeconds,
            qslReceived = remote.qslReceived.ifBlank { existing.qslReceived },
            qslReceivedMethod = remote.qslReceivedMethod.ifBlank { existing.qslReceivedMethod },
            lotwSent = remote.lotwSent.ifBlank { existing.lotwSent }, lotwReceived = remote.lotwReceived.ifBlank { existing.lotwReceived },
            clublogSent = remote.clublogSent.ifBlank { existing.clublogSent }, clublogReceived = remote.clublogReceived.ifBlank { existing.clublogReceived },
            eqslSent = remote.eqslSent.ifBlank { existing.eqslSent }, eqslReceived = remote.eqslReceived.ifBlank { existing.eqslReceived },
            dclSent = remote.dclSent.ifBlank { existing.dclSent }, dclReceived = remote.dclReceived.ifBlank { existing.dclReceived },
            qrzSent = remote.qrzSent.ifBlank { existing.qrzSent }, qrzReceived = remote.qrzReceived.ifBlank { existing.qrzReceived },
            qslImages = remote.qslImages.ifBlank { existing.qslImages },
            activationSessionId = remote.activationSessionId.ifBlank { existing.activationSessionId },
            activationProgram = remote.activationProgram.ifBlank { existing.activationProgram },
            myPotaRefs = remote.myPotaRefs.ifEmpty { existing.myPotaRefs }, potaRefs = remote.potaRefs.ifEmpty { existing.potaRefs },
            extraAdifFields = existing.extraAdifFields + remote.extraAdifFields,
            syncState = "synced",
            remoteId = remote.remoteId.ifBlank { existing.remoteId })
        update(merged); return false
    }

    fun markSynced(id: String) { findById(id)?.let { update(it.copy(syncState = "synced")) } }
    fun changeToken(): Long = changeRevision.get()
    fun list(): List<Qso> = query(" LIMIT 100")
    fun recent(limit: Int = 120): List<Qso> = query(" LIMIT ${limit.coerceIn(1, 500)}")
    fun recentHamClockProjection(limit: Int = 120): List<HamClockRecentQso> {
        val size = limit.coerceIn(1, 200)
        return buildList {
            readableDatabase.rawQuery(
                """SELECT qso_id,callsign_norm,frequency_hz,band_norm,mode_norm,country_norm,grid_norm,created_at
                    FROM qso_projection WHERE is_valid=1
                    ORDER BY created_at DESC,qso_id LIMIT $size""".trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(HamClockRecentQso(
                        id = cursor.getString(0),
                        callsign = cursor.getString(1),
                        frequencyHz = cursor.getLong(2),
                        band = cursor.getString(3),
                        mode = cursor.getString(4),
                        country = cursor.getString(5),
                        grid = cursor.getString(6),
                        createdAt = cursor.getLong(7),
                    ))
                }
            }
        }
    }

    fun contestHamClockProjection(filter: HamClockContestQsoFilter): List<HamClockContestQso> {
        require(filter.startEpoch >= 0 && filter.endEpoch > filter.startEpoch) { "Invalid contest time window" }
        val clauses = mutableListOf("is_valid=1", "created_at>=?", "created_at<?")
        val args = mutableListOf(filter.startEpoch.toString(), filter.endEpoch.toString())
        fun optional(expression: String, value: String) {
            value.trim().uppercase(java.util.Locale.US).takeIf(String::isNotBlank)?.let {
                clauses += "$expression=?"; args += it
            }
        }
        optional("contest_id_norm", filter.contestId)
        optional("band_norm", filter.band)
        optional("mode_family", filter.mode)
        filter.stationProfileId?.trim()?.takeIf(String::isNotBlank)?.let {
            clauses += "station_profile_id=?"; args += it
        }
        if (filter.confirmedOnly) clauses += "(paper_received=1 OR lotw_received=1 OR eqsl_received=1 OR qrz_received=1 OR clublog_received=1 OR dcl_received=1)"
        val limit = filter.maximumRows.coerceIn(1, 200)
        return readableDatabase.rawQuery(
            """SELECT qso_id,contest_id_norm,callsign_norm,band_norm,mode_family,grid_norm,created_at,
                CASE WHEN paper_received=1 OR lotw_received=1 OR eqsl_received=1 OR qrz_received=1 OR clublog_received=1 OR dcl_received=1 THEN 1 ELSE 0 END
                FROM qso_projection WHERE ${clauses.joinToString(" AND ")}
                ORDER BY created_at DESC,qso_id LIMIT $limit""".trimIndent(), args.toTypedArray(),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(HamClockContestQso(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getLong(6), cursor.getInt(7) == 1,
            ))
        } }
    }
    // Compatibility/export helper only: interactive screens must use bounded projection queries.
    fun all(): List<Qso> = query("")
    fun workedLog(stationId: String?, resolveEntity: (String) -> String): List<WorkedLogQso> {
        val scope = stationScope(stationId)
        val sql = """SELECT callsign,country,frequency_hz,mode,created_at,
            COALESCE(json_extract(details_json,'$.band'),''),
            COALESCE(json_extract(details_json,'$.submode'),'')
            FROM qso WHERE ${scope.first} ORDER BY created_at""".trimIndent()
        val rows = mutableListOf<WorkedLogQso>()
        readableDatabase.rawQuery(sql, scope.second.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val callsign = cursor.getString(0).orEmpty()
                val frequency = cursor.getLong(2)
                val storedBand = cursor.getString(5).orEmpty().trim().lowercase(java.util.Locale.US)
                rows += WorkedLogQso(
                    callsign = callsign,
                    entity = resolveEntity(callsign).ifBlank { cursor.getString(1).orEmpty() },
                    band = storedBand.takeIf(insightBands::contains).orEmpty()
                        .ifBlank { bandForFrequency(frequency) },
                    mode = cursor.getString(3).orEmpty(),
                    submode = cursor.getString(6).orEmpty(),
                    epoch = cursor.getLong(4),
                    fromWavelog = stationId != null,
                )
            }
        }
        return rows
    }
    fun projectionHealth(): ProjectionHealth {
        val db = readableDatabase
        fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return ProjectionHealth(QsoProjectionStore.state(db), count("qso"), count("qso_projection"), count("qso_reference"),
            QsoProjectionStore.processed(db), QsoProjectionStore.lastError(db))
    }

    fun backfillProjectionBatch(batchSize: Int = 500): Boolean {
        val db = writableDatabase
        if (QsoProjectionStore.state(db) == ProjectionState.READY) return false
        return try {
            val size = batchSize.coerceIn(100, 1_000)
            val (cursorTime, cursorId) = QsoProjectionStore.cursor(db)
            val rows = queryWhere("(created_at>? OR (created_at=? AND id>?)) ORDER BY created_at,id LIMIT $size",
                arrayOf(cursorTime.toString(), cursorTime.toString(), cursorId))
            if (rows.isEmpty()) {
                val total = db.rawQuery("SELECT COUNT(*) FROM qso", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                transaction { QsoProjectionStore.markReady(db, total) }
                false
            } else {
                transaction {
                    rows.forEach { QsoProjectionStore.write(db, it) }
                    val last = rows.last()
                    QsoProjectionStore.markProgress(db, last.createdAt, last.id, QsoProjectionStore.processed(db) + rows.size)
                }
                true
            }
        } catch (error: Throwable) {
            QsoProjectionStore.markRepairRequired(db, "BACKFILL_${error.javaClass.simpleName.uppercase().take(80)}")
            false
        }
    }

    /** Bounded repair for older projections whose canonical QSO has a worked grid but grid_norm is empty. */
    fun repairProjectionGridBatch(batchSize: Int = 250): Int {
        val size = batchSize.coerceIn(50, 500)
        val ids = readableDatabase.rawQuery(
            """SELECT q.id FROM qso q JOIN qso_projection p ON p.qso_id=q.id
                WHERE p.grid_norm='' AND TRIM(COALESCE(json_extract(q.details_json,'$.grid'),''))<>''
                ORDER BY q.created_at,q.id LIMIT $size""".trimIndent(), null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (ids.isEmpty()) return 0
        transaction { qsos(ids).forEach { QsoProjectionStore.write(writableDatabase, it) } }
        return ids.size
    }

    fun verifyProjection(): ProjectionHealth {
        val db = writableDatabase
        val missing = db.rawQuery("SELECT COUNT(*) FROM qso q LEFT JOIN qso_projection p ON p.qso_id=q.id WHERE p.qso_id IS NULL", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val orphan = db.rawQuery("SELECT COUNT(*) FROM qso_projection p LEFT JOIN qso q ON q.id=p.qso_id WHERE q.id IS NULL", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val orphanReferences = db.rawQuery("SELECT COUNT(*) FROM qso_reference r LEFT JOIN qso q ON q.id=r.qso_id WHERE q.id IS NULL", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (missing > 0 || orphan > 0 || orphanReferences > 0) QsoProjectionStore.markRepairRequired(db, "missing=$missing orphan=$orphan refs=$orphanReferences")
        else if (QsoProjectionStore.state(db) != ProjectionState.OPTIMISING) QsoProjectionStore.markReady(db, projectionHealth().canonicalRows)
        return projectionHealth()
    }

    fun repairMissingProjectionRows(limit: Int = 500): Int {
        val ids = readableDatabase.rawQuery("SELECT q.id FROM qso q LEFT JOIN qso_projection p ON p.qso_id=q.id WHERE p.qso_id IS NULL ORDER BY q.created_at,q.id LIMIT ${limit.coerceIn(1,1_000)}", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (ids.isEmpty()) { QsoProjectionStore.markRepaired(writableDatabase); verifyProjection(); return 0 }
        val rows = ids.mapNotNull(::findById)
        transaction { rows.forEach { QsoProjectionStore.write(writableDatabase, it) }; QsoProjectionStore.markRepaired(writableDatabase) }
        return rows.size
    }

    fun repairProjectionFully(batchSize: Int = 500): ProjectionHealth {
        val db = writableDatabase
        return try {
            transaction {
                db.delete("qso_reference", "qso_id NOT IN (SELECT id FROM qso)", null)
                db.delete("qso_projection", "qso_id NOT IN (SELECT id FROM qso)", null)
            }
            while (repairMissingProjectionRows(batchSize) > 0) Unit
            verifyProjection()
        } catch (error: Throwable) {
            QsoProjectionStore.markRepairRequired(db, "REPAIR_${error.javaClass.simpleName.uppercase().take(80)}")
            projectionHealth()
        }
    }

    fun rebuildProjection() = transaction { QsoProjectionStore.reset(writableDatabase) }
    fun allForProgress(): List<Qso> = buildList {
        val fields = listOf("band", "grid", "iota", "sotaRef", "wwffRef", "potaRef", "comment", "txPowerW",
            "operatorCallsign", "stationCallsign", "stationProfileId", "myGrid", "myState", "myIota", "mySotaRef",
            "myWwffRef", "myPotaRef", "radioModel", "dxcc", "continent", "cqZone", "ituZone", "state",
            "propagationMode", "antennaPath", "submode", "contestId", "distanceKm", "qslReceived", "lotwReceived",
            "clublogReceived", "eqslReceived", "dclReceived", "qrzReceived", "syncState", "activationSessionId",
            "activationProgram", "myPotaRefs", "potaRefs")
        val projection = fields.joinToString(",") { "json_extract(details_json,'$.${it}')" } +
            ",json_extract(details_json,'$.extraAdifFields.SAT_NAME')" +
            ",json_extract(details_json,'$.extraAdifFields.SAT_MODE')" +
            ",json_extract(details_json,'$.extraAdifFields.ORBIT')"
        readableDatabase.rawQuery("SELECT id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at,name,notes,country,$projection FROM qso ORDER BY created_at DESC", null).use { cursor ->
            fun text(index: Int): String = cursor.getString(index)?.takeIf(String::isNotBlank) ?: ""
            fun list(index: Int): List<String> = text(index).takeIf(String::isNotBlank)?.let { raw ->
                runCatching { org.json.JSONArray(raw).jsonStringList() }.getOrDefault(emptyList())
            }.orEmpty()
            while (cursor.moveToNext()) {
                val extra = buildMap {
                    text(49).takeIf(String::isNotBlank)?.let { put("SAT_NAME", it) }
                    text(50).takeIf(String::isNotBlank)?.let { put("SAT_MODE", it) }
                    text(51).takeIf(String::isNotBlank)?.let { put("ORBIT", it) }
                }
                add(Qso(id=text(0), callsign=text(1), frequencyHz=cursor.getLong(2), mode=text(3), rstSent=text(4), rstReceived=text(5),
                    createdAt=cursor.getLong(6), name=text(7), notes=text(8), country=text(9), band=text(10), grid=text(11),
                    iota=text(12), sotaRef=text(13), wwffRef=text(14), potaRef=text(15), comment=text(16), txPowerW=cursor.getInt(17),
                    operatorCallsign=text(18), stationCallsign=text(19), stationProfileId=text(20), myGrid=text(21), myState=text(22),
                    myIota=text(23), mySotaRef=text(24), myWwffRef=text(25), myPotaRef=text(26), radioModel=text(27),
                    dxcc=text(28), continent=text(29), cqZone=text(30), ituZone=text(31), state=text(32), propagationMode=text(33),
                    antennaPath=text(34), submode=text(35), contestId=text(36), distanceKm=cursor.getDouble(37),
                    qslReceived=text(38).ifBlank { "N" }, lotwReceived=text(39).ifBlank { "N" },
                    clublogReceived=text(40).ifBlank { "N" }, eqslReceived=text(41).ifBlank { "N" },
                    dclReceived=text(42).ifBlank { "N" }, qrzReceived=text(43).ifBlank { "N" },
                    syncState=text(44).ifBlank { "local" }, activationSessionId=text(45), activationProgram=text(46),
                    myPotaRefs=list(47), potaRefs=list(48), extraAdifFields=extra))
            }
        }
    }
    fun page(page: Int, pageSize: Int, filter: LogbookFilter = LogbookFilter(), stationId: String? = null): QsoPage {
        val size = normalizedLogbookPageSize(pageSize)
        val result = LogbookRepository(this).page(filter, stationId, size, offsetPage = page.coerceAtLeast(0), exactCount = true)
        val total = result.exactTotal ?: 0
        return QsoPage(result.rows, total, page.coerceIn(0, logbookPageCount(total,size)-1), size)
    }

    fun spotStatuses(spots: List<SpotLogIdentity>, stationId: String? = null): Map<String, SpotLogStatus> {
        if (spots.isEmpty()) return emptyMap()
        val calls = projectionDimensions("callsign_norm", spots.map { it.callsign }, stationId)
        val countries = projectionDimensions("country_norm", spots.map { it.country }, stationId)
        val dxccs = projectionDimensions("dxcc", spots.map { it.dxcc }, stationId)
        return spots.associate { spot ->
            val call = calls[spot.callsign.normalizedStatusKey()] ?: WorkedDimensions()
            val dxcc = spot.dxcc.normalizedStatusKey()
            val entity = if (dxcc.isNotBlank() && dxccs.containsKey(dxcc)) dxccs.getValue(dxcc)
                else countries[spot.country.normalizedStatusKey()] ?: WorkedDimensions()
            spot.id to classifySpotStatus(spot, call, entity)
        }
    }

    fun stationInsight(record: AndroidCallbookRecord, stationId: String?): StationInsight {
        val scope = projectionStationScope(stationId = stationId)
        val call = record.callsign.trim().uppercase(java.util.Locale.US)
        val callWhere = "p.callsign_norm=? AND ${scope.first}"
        val callArgs = (listOf(call) + scope.second).toTypedArray()
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM qso_projection p WHERE $callWhere", callArgs).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val historyIds = readableDatabase.rawQuery("SELECT p.qso_id FROM qso_projection p WHERE $callWhere ORDER BY p.created_at DESC,p.qso_id LIMIT 20", callArgs).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val history = if (historyIds.isEmpty()) emptyList() else queryWhere(
            "id IN (${historyIds.joinToString(",") { "?" }}) ORDER BY created_at DESC,id", historyIds.toTypedArray())
        val resolvedRecord = history.firstOrNull()?.let { latest ->
            record.copy(
                callsign = call,
                name = record.name.ifBlank { latest.name }, qth = record.qth.ifBlank { latest.qth },
                country = record.country.ifBlank { latest.country }, grid = record.grid.ifBlank { latest.grid },
                dxcc = record.dxcc.ifBlank { latest.dxcc }, continent = record.continent.ifBlank { latest.continent },
                region = record.region.ifBlank { latest.region }, cqZone = record.cqZone.ifBlank { latest.cqZone },
                ituZone = record.ituZone.ifBlank { latest.ituZone }, state = record.state.ifBlank { latest.state },
                email = record.email.ifBlank { latest.email }, source = record.source.ifBlank { "LOG" },
            )
        } ?: record.copy(callsign = call)

        val numericDxcc = resolvedRecord.dxcc.trim().uppercase(java.util.Locale.US)
        val country = resolvedRecord.country.trim().uppercase(java.util.Locale.US)
        val entityExpression = if (numericDxcc.isNotBlank()) "p.dxcc=?" else "p.country_norm=?"
        val entityValue = numericDxcc.ifBlank { country }
        val cells = mutableMapOf<String, DxccCell>()
        if (entityValue.isNotBlank()) {
            val sql = """SELECT p.band_norm,p.submode_norm,p.mode_norm,
                MAX(CASE WHEN p.paper_received=1 OR p.lotw_received=1 THEN 1 ELSE 0 END)
                FROM qso_projection p WHERE $entityExpression AND ${scope.first}
                GROUP BY 1,2,3""".trimIndent()
            val args = (listOf(entityValue) + scope.second).toTypedArray()
            readableDatabase.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val band = cursor.getString(0).orEmpty().lowercase(java.util.Locale.US)
                    val mode = insightMode(cursor.getString(2).orEmpty(), cursor.getString(1).orEmpty())
                    if (band.isNotBlank() && mode.isNotBlank()) {
                        val key = "$mode|$band"
                        val old = cells[key] ?: DxccCell()
                        cells[key] = DxccCell(worked = true, confirmed = old.confirmed || cursor.getInt(3) == 1)
                    }
                }
            }
        }
        return StationInsight(resolvedRecord, CallsignHistory(history, total),
            DxccSummary(resolvedRecord.dxcc, resolvedRecord.country, cells))
    }

    fun neuralLogSummary(stationId: String?): NeuralLogSummary {
        val scope = projectionStationScope(stationId = stationId)
        val args = scope.second.toTypedArray()
        fun scalar(expression: String): Int = readableDatabase.rawQuery(
            "SELECT $expression FROM qso_projection p WHERE ${scope.first}", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        fun grouped(expression: String, limit: Int = 32): Map<String, Int> {
            val output = linkedMapOf<String, Int>()
            readableDatabase.rawQuery("SELECT $expression AS value, COUNT(*) AS total FROM qso_projection p " +
                "WHERE ${scope.first} AND TRIM($expression)<>'' GROUP BY value ORDER BY total DESC LIMIT $limit", args).use { cursor ->
                while (cursor.moveToNext()) output[cursor.getString(0).orEmpty()] = cursor.getInt(1)
            }
            return output
        }
        return NeuralLogSummary(
            qsos = scalar("COUNT(*)"), calls = scalar("COUNT(DISTINCT NULLIF(p.callsign_norm,''))"),
            dxccs = scalar("COUNT(DISTINCT NULLIF(p.dxcc,''))"),
            confirmedDxccs = scalar("COUNT(DISTINCT CASE WHEN p.paper_received=1 OR p.lotw_received=1 THEN NULLIF(p.dxcc,'') END)"),
            bands = grouped("p.band_norm"), modes = grouped("COALESCE(NULLIF(p.submode_norm,''),p.mode_norm)"),
            continents = grouped("p.continent"), countries = grouped("p.country_norm", 12),
        )
    }

    private fun projectionStationScope(alias: String = "p", stationId: String? = null): Pair<String, List<String>> = if (stationId == null)
        "$alias.station_profile_id=''" to emptyList()
    else "$alias.station_profile_id=?" to listOf(stationId)

    private fun stationScope(stationId: String?): Pair<String, List<String>> = if (stationId == null)
        "COALESCE(json_extract(details_json,'$.stationProfileId'),'')=''" to emptyList()
    else "COALESCE(json_extract(details_json,'$.stationProfileId'),'')=?" to listOf(stationId)

    private fun projectionDimensions(column: String, rawKeys: List<String>, stationId: String?): Map<String, WorkedDimensions> {
        require(column in setOf("callsign_norm", "country_norm", "dxcc"))
        val keys = rawKeys.map { it.normalizedStatusKey() }.filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return emptyMap()
        val placeholders = keys.joinToString(",") { "?" }
        val scope = projectionStationScope(stationId = stationId)
        val sql = """SELECT p.$column,p.band_norm,p.submode_norm,p.mode_norm,
            MAX(CASE WHEN p.paper_received=1 OR p.lotw_received=1 THEN 1 ELSE 0 END)
            FROM qso_projection p WHERE p.$column IN ($placeholders) AND ${scope.first}
            GROUP BY p.$column,p.band_norm,p.submode_norm,p.mode_norm""".trimIndent()
        val mutable = mutableMapOf<String, MutableWorkedDimensions>()
        val args = (keys + scope.second).toTypedArray()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0).orEmpty().normalizedStatusKey()
                val band = cursor.getString(1).orEmpty().normalizedStatusKey()
                val mode = canonicalSpotMode(cursor.getString(2).orEmpty().ifBlank { cursor.getString(3).orEmpty() })
                val confirmed = cursor.getInt(4) == 1
                mutable.getOrPut(key, ::MutableWorkedDimensions).add(band, mode, confirmed)
            }
        }
        return mutable.mapValues { it.value.freeze() }
    }

    private class MutableWorkedDimensions {
        var any = false
        var confirmedAny = false
        val bands = mutableSetOf<String>()
        val confirmedBands = mutableSetOf<String>()
        val bandModes = mutableSetOf<String>()
        val confirmedBandModes = mutableSetOf<String>()

        fun add(band: String, mode: String, confirmed: Boolean) {
            any = true
            if (confirmed) confirmedAny = true
            if (band.isNotBlank()) {
                bands += band
                if (confirmed) confirmedBands += band
                if (mode.isNotBlank()) {
                    val bandMode = "$band|$mode"
                    bandModes += bandMode
                    if (confirmed) confirmedBandModes += bandMode
                }
            }
        }

        fun freeze() = WorkedDimensions(any, confirmedAny, bands, confirmedBands, bandModes, confirmedBandModes)
    }

    private fun String.normalizedStatusKey() = trim().uppercase(java.util.Locale.US)
    fun <T> transaction(block: () -> T): T {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            block().also { database.setTransactionSuccessful() }
        } finally {
            database.endTransaction()
        }
    }
    fun exportADIF(): String = all().asReversed().joinToString("") { toADIF(it) }

    fun exportFilteredADIF(filter: LogbookFilter, stationId: String? = null): String {
        val spec = pageQuery(filter, stationId)
        return queryWhere("${spec.where} ORDER BY created_at ASC,id ASC", spec.args.toTypedArray()).joinToString("") { toADIF(it) }
    }

    fun exportSelectedADIF(ids: Collection<String>): String {
        if (ids.isEmpty()) return ""
        val placeholders = ids.joinToString(",") { "?" }
        return queryWhere("id IN ($placeholders) ORDER BY created_at ASC,id ASC", ids.toTypedArray()).joinToString("") { toADIF(it) }
    }

    fun streamExportADIF(output: java.io.OutputStream, filter: LogbookFilter = LogbookFilter(), stationId: String? = null,
        selectedIds: Collection<String>? = null, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}) {
        output.write("Generated by RigWeave <ADIF_VER:5>3.1.4 <PROGRAMID:8>RigWeave <EOH>\n".toByteArray(Charsets.UTF_8))
        var written=0
        fun writeRows(rows:List<Qso>){rows.forEach { qso -> if(cancelled())throw java.util.concurrent.CancellationException("ADIF export cancelled");output.write((toADIF(qso)+"\n").toByteArray(Charsets.UTF_8));written++;progress(written)}}
        if(selectedIds!=null){selectedIds.chunked(250).forEach { writeRows(qsos(it)) }} else {
            val repository=LogbookRepository(this);var cursor:LogbookCursor?=null;var more:Boolean
            val exportFilter=filter.copy(sort=LogbookSort.TIME,direction=LogbookSortDirection.ASCENDING,limit=250)
            do { val page=repository.page(exportFilter,stationId,250,cursor=cursor);writeRows(page.rows);cursor=page.nextCursor;more=page.hasMore } while(more&&!cancelled())
        }
        output.flush()
    }

    fun toADIF(qso: Qso): String {
        val at = java.time.Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC)
        var adif = NativeCore.adif(qso.id, qso.callsign, at.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
            at.format(DateTimeFormatter.ofPattern("HHmmss")), qso.frequencyHz, qso.mode, qso.rstSent, qso.rstReceived)
        val fields = listOf(
            "BAND" to qso.band.ifBlank { bandForFrequency(qso.frequencyHz) }, "NAME" to qso.name, "QTH" to qso.qth,
            "COUNTRY" to qso.country, "GRIDSQUARE" to qso.grid, "IOTA" to qso.iota, "SOTA_REF" to qso.sotaRef,
            "WWFF_REF" to qso.wwffRef, "POTA_REF" to qso.potaRef, "COMMENT" to qso.comment, "NOTES" to qso.notes,
            "FREQ_RX" to qso.frequencyRxHz.takeIf { it > 0 }?.let { "%.6f".format(java.util.Locale.US, it / 1_000_000.0) }.orEmpty(),
            "BAND_RX" to qso.bandRx, "TX_PWR" to qso.txPowerW.takeIf { it > 0 }?.toString().orEmpty(),
            "OPERATOR" to qso.operatorCallsign, "STATION_CALLSIGN" to qso.stationCallsign, "MY_GRIDSQUARE" to qso.myGrid,
            "MY_COUNTRY" to qso.myCountry, "MY_DXCC" to qso.myDxcc, "MY_CQ_ZONE" to qso.myCqZone,
            "MY_ITU_ZONE" to qso.myItuZone, "MY_STATE" to qso.myState, "MY_IOTA" to qso.myIota,
            "MY_SOTA_REF" to qso.mySotaRef, "MY_WWFF_REF" to qso.myWwffRef, "MY_POTA_REF" to qso.myPotaRef,
            "RIG" to qso.radioModel, "DXCC" to qso.dxcc, "CONT" to qso.continent,
            "APP_RIGWEAVE_REGION" to qso.region, "CQZ" to qso.cqZone, "ITUZ" to qso.ituZone,
            "STATE" to qso.state, "EMAIL" to qso.email, "PROP_MODE" to qso.propagationMode,
            "ANT_PATH" to qso.antennaPath, "QSL_SENT" to qso.qslSent, "QSL_SENT_VIA" to qso.qslMethod,
            "QSL_VIA" to qso.qslVia, "QSLMSG" to qso.qslMessage, "SUBMODE" to qso.submode,
            "CNTY" to qso.county, "DARC_DOK" to qso.dok, "CONTEST_ID" to qso.contestId,
            "DISTANCE" to qso.distanceKm.takeIf { it > 0 }?.toString().orEmpty(),
            "APP_RIGWEAVE_DURATION_SECONDS" to qso.durationSeconds.takeIf { it > 0 }?.toString().orEmpty(),
            "QSL_RCVD" to qso.qslReceived, "QSL_RCVD_VIA" to qso.qslReceivedMethod,
            "LOTW_QSL_SENT" to qso.lotwSent, "LOTW_QSL_RCVD" to qso.lotwReceived,
            "CLUBLOG_QSO_UPLOAD_STATUS" to qso.clublogSent, "CLUBLOG_QSO_DOWNLOAD_STATUS" to qso.clublogReceived,
            "EQSL_QSL_SENT" to qso.eqslSent, "EQSL_QSL_RCVD" to qso.eqslReceived,
            "DCL_QSL_SENT" to qso.dclSent, "DCL_QSL_RCVD" to qso.dclReceived,
            "QRZCOM_QSO_UPLOAD_STATUS" to qso.qrzSent, "QRZCOM_QSO_DOWNLOAD_STATUS" to qso.qrzReceived,
            "APP_RIGWEAVE_QSL_IMAGES" to qso.qslImages)
        fields.forEach { (name, value) -> if (value.isNotBlank())
            adif = adif.replace("<EOR>", "<$name:${value.toByteArray(Charsets.UTF_8).size}>$value<EOR>") }
        qso.extraAdifFields.toSortedMap().forEach { (rawName, value) ->
            val name = rawName.uppercase(java.util.Locale.US)
            if (name !in WavelogCanonicalizer.rigWeaveFields && name !in WavelogCanonicalizer.excludedFields && value.isNotBlank())
                adif = adif.replace("<EOR>", "<$name:${value.toByteArray(Charsets.UTF_8).size}>$value<EOR>")
        }
        return adif
    }

    fun parseADIF(text: String): Pair<List<Qso>, Int> {
        val rows = mutableListOf<Qso>(); var skipped = 0
        Regex("(?is)(.*?<EOR>)").findAll(text.substringAfter("<EOH>", text)).forEach { match ->
            val record = match.value
            val parsed = WavelogCanonicalizer.fromAdif(record).fields
            fun field(name: String) = parsed[name.uppercase(java.util.Locale.US)].orEmpty()
            val extras = parsed.filterKeys { it !in WavelogCanonicalizer.rigWeaveFields && it !in WavelogCanonicalizer.excludedFields }
            val qso = qsoFromFields(::field, "", "", extras)
            if (qso == null) skipped++ else rows += qso
        }
        return rows to skipped
    }

    fun importADIF(text: String): Pair<Int, Int> {
        val (rows, invalid) = parseADIF(text); var added = 0; var skipped = invalid
        rows.forEach { if (save(it, QsoOrigin.IMPORT)) added++ else skipped++ }
        return added to skipped
    }

    fun qsoFromFields(field: (String) -> String, remoteId: String, stationProfileId: String,
        extraAdifFields: Map<String, String> = emptyMap()): Qso? {
        val call = field("CALL").uppercase(); val mode = field("MODE").uppercase()
        val frequency = field("FREQ").toDoubleOrNull(); val date = field("QSO_DATE"); val time = field("TIME_ON")
        val epoch = runCatching { LocalDateTime.parse(date + time.padEnd(6, '0').take(6),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
        if (call.isBlank() || mode.isBlank() || frequency == null || epoch == null) return null
        val frequencyHz = (frequency * 1_000_000).toLong()
        val offDate = field("QSO_DATE_OFF").ifBlank { date }; val offTime = field("TIME_OFF")
        val offEpoch = offTime.takeIf { it.isNotBlank() }?.let { value -> runCatching {
            LocalDateTime.parse(offDate + value.padEnd(6, '0').take(6), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .toEpochSecond(ZoneOffset.UTC)
        }.getOrNull() }
        return Qso(
            id = field("APP_KX3TOUCH_UUID").ifBlank { field("APP_RIGWEAVE_UUID") }
                .ifBlank { if (remoteId.isBlank()) UUID.randomUUID().toString() else "wavelog-$stationProfileId-$remoteId" },
            callsign = call, frequencyHz = frequencyHz, mode = mode, rstSent = field("RST_SENT"),
            rstReceived = field("RST_RCVD"), createdAt = epoch, name = field("NAME"), qth = field("QTH"),
            notes = field("NOTES"), country = field("COUNTRY"), band = field("BAND").ifBlank { bandForFrequency(frequencyHz) },
            grid = field("GRIDSQUARE"), iota = field("IOTA"), sotaRef = field("SOTA_REF"),
            wwffRef = field("WWFF_REF"), potaRef = field("POTA_REF"), comment = field("COMMENT"),
            frequencyRxHz = field("FREQ_RX").toDoubleOrNull()?.let { (it * 1_000_000).toLong() } ?: 0,
            bandRx = field("BAND_RX"), txPowerW = field("TX_PWR").toDoubleOrNull()?.toInt() ?: 0,
            operatorCallsign = field("OPERATOR"), stationCallsign = field("STATION_CALLSIGN"),
            stationProfileId = stationProfileId, myGrid = field("MY_GRIDSQUARE"), myCountry = field("MY_COUNTRY"),
            myDxcc = field("MY_DXCC"), myCqZone = field("MY_CQ_ZONE"), myItuZone = field("MY_ITU_ZONE"),
            myState = field("MY_STATE"), myIota = field("MY_IOTA"), mySotaRef = field("MY_SOTA_REF"),
            myWwffRef = field("MY_WWFF_REF"), myPotaRef = field("MY_POTA_REF"), radioModel = field("RIG"),
            dxcc = field("DXCC"), continent = field("CONT"), region = field("APP_RIGWEAVE_REGION"),
            cqZone = field("CQZ"), ituZone = field("ITUZ"), state = field("STATE"), email = field("EMAIL"),
            propagationMode = field("PROP_MODE"), antennaPath = field("ANT_PATH"),
            qslSent = field("QSL_SENT"), qslMethod = field("QSL_SENT_VIA"), qslVia = field("QSL_VIA"),
            qslMessage = field("QSLMSG"), submode = field("SUBMODE"), county = field("CNTY"),
            dok = field("DARC_DOK"), contestId = field("CONTEST_ID"), distanceKm = field("DISTANCE").toDoubleOrNull() ?: 0.0,
            durationSeconds = field("APP_RIGWEAVE_DURATION_SECONDS").toLongOrNull()
                ?: offEpoch?.minus(epoch)?.coerceAtLeast(0) ?: 0,
            qslReceived = field("QSL_RCVD"), qslReceivedMethod = field("QSL_RCVD_VIA"),
            lotwSent = field("LOTW_QSL_SENT"), lotwReceived = field("LOTW_QSL_RCVD"),
            clublogSent = field("CLUBLOG_QSO_UPLOAD_STATUS"), clublogReceived = field("CLUBLOG_QSO_DOWNLOAD_STATUS"),
            eqslSent = field("EQSL_QSL_SENT"), eqslReceived = field("EQSL_QSL_RCVD"),
            dclSent = field("DCL_QSL_SENT"), dclReceived = field("DCL_QSL_RCVD"),
            qrzSent = field("QRZCOM_QSO_UPLOAD_STATUS"), qrzReceived = field("QRZCOM_QSO_DOWNLOAD_STATUS"),
            qslImages = field("APP_RIGWEAVE_QSL_IMAGES"), syncState = if (remoteId.isBlank()) "local" else "synced",
            remoteId = remoteId, extraAdifFields = extraAdifFields)
    }

    private fun insert(qso: Qso) {
        writeAtomically { db ->
            db.execSQL("INSERT INTO qso(id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at,name,qth,notes,country,details_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(qso.id, qso.callsign, qso.frequencyHz, qso.mode, qso.rstSent, qso.rstReceived,
                    qso.createdAt, qso.name, qso.qth, qso.notes, qso.country, details(qso).toString()))
            QsoProjectionStore.write(db, qso)
        }
        changeRevision.incrementAndGet()
    }
    private fun update(qso: Qso) {
        writeAtomically { db ->
            db.execSQL("UPDATE qso SET callsign=?,frequency_hz=?,mode=?,rst_sent=?,rst_received=?,created_at=?,name=?,qth=?,notes=?,country=?,details_json=? WHERE id=?",
                arrayOf<Any>(qso.callsign, qso.frequencyHz, qso.mode, qso.rstSent, qso.rstReceived, qso.createdAt,
                    qso.name, qso.qth, qso.notes, qso.country, details(qso).toString(), qso.id))
            QsoProjectionStore.write(db, qso)
        }
        changeRevision.incrementAndGet()
    }
    private inline fun writeAtomically(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        if (db.inTransaction()) { block(db); return }
        db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun findNatural(qso: Qso): Qso? = queryWhere("callsign=? AND frequency_hz=? AND mode=? AND created_at BETWEEN ? AND ? LIMIT 1",
        arrayOf(qso.callsign, qso.frequencyHz.toString(), qso.mode, (qso.createdAt - 15).toString(), (qso.createdAt + 15).toString())).firstOrNull()
    private fun findById(id: String): Qso? = queryWhere("id=? LIMIT 1", arrayOf(id)).firstOrNull()
    fun qso(id: String): Qso? = findById(id)
    fun qsos(ids: Collection<String>): List<Qso> {
        if (ids.isEmpty()) return emptyList()
        val ordered = ids.toList(); val placeholders = ordered.joinToString(",") { "?" }
        val rows = queryWhere("id IN ($placeholders)", ordered.toTypedArray()).associateBy(Qso::id)
        return ordered.mapNotNull(rows::get)
    }

    fun stationProfileIds(): List<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT station_profile_id FROM qso_projection WHERE station_profile_id<>'' ORDER BY station_profile_id", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    fun satelliteObserverProfiles(): List<Pair<String,String>> = readableDatabase.rawQuery(
        "SELECT station_profile_id,my_grid_norm FROM qso_projection WHERE station_profile_id<>'' AND my_grid_norm<>'' GROUP BY station_profile_id,my_grid_norm ORDER BY COUNT(*) DESC LIMIT 50", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
    fun activationQsos(sessionId:String):List<Qso>{val ids=readableDatabase.rawQuery("SELECT qso_id FROM qso_projection WHERE activation_session_id=? ORDER BY created_at,qso_id",arrayOf(sessionId)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}};return qsos(ids)}
    fun catchUpCandidates(fromEpoch:Long,toEpochExclusive:Long,station:String=""):List<QsoCatchUpCandidate>{val args=mutableListOf(fromEpoch.toString(),toEpochExclusive.toString());val stationClause=if(station.isBlank())"" else " AND station_callsign_norm=?".also{args+=station.trim().uppercase()};return readableDatabase.rawQuery("SELECT qso_id,station_callsign_norm FROM qso_projection WHERE created_at>=? AND created_at<?$stationClause ORDER BY created_at,qso_id",args.toTypedArray()).use{c->buildList{while(c.moveToNext())add(QsoCatchUpCandidate(c.getString(0),c.getString(1)))}}}

    fun naturalCandidates(qso: Qso): List<Qso> = queryWhere(
        "UPPER(callsign)=? AND frequency_hz=? AND UPPER(mode)=? AND created_at BETWEEN ? AND ? ORDER BY created_at",
        arrayOf(qso.callsign.uppercase(), qso.frequencyHz.toString(), qso.mode.uppercase(),
            (qso.createdAt - 15).toString(), (qso.createdAt + 15).toString()))

    fun insertRemoteDistinct(qso: Qso): Boolean {
        if (findById(qso.id) != null) return false
        insert(qso.copy(syncState = "synced"))
        return true
    }

    fun enqueueDelivery(qsoId: String, provider: SyncProvider, state: DeliveryState = DeliveryState.QUEUED,
        now: Long = System.currentTimeMillis() / 1_000): Boolean {
        val values = ContentValues().apply {
            put("qso_id", qsoId); put("provider", provider.name); put("state", state.name)
            put("created_at", now); put("updated_at", now)
        }
        val inserted = writableDatabase.insertWithOnConflict("qso_delivery", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) changeRevision.incrementAndGet()
        return inserted
    }

    fun deliveries(provider: SyncProvider? = null): List<DeliveryRecord> {
        val where = if (provider == null) "1=1" else "provider=?"
        val args = provider?.let { arrayOf(it.name) }
        return deliveryQuery("$where ORDER BY created_at ASC, qso_id ASC", args)
    }

    fun deliveriesForQsoIds(ids: List<String>): Map<String, List<DeliveryRecord>> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return deliveryQuery("qso_id IN ($placeholders) ORDER BY provider", ids.toTypedArray()).groupBy { it.qsoId }
    }

    fun nextDelivery(provider: SyncProvider, now: Long): DeliveryRecord? = deliveryQuery(
        "provider=? AND state IN ('QUEUED','RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY created_at ASC,qso_id ASC LIMIT 1",
        arrayOf(provider.name, now.toString())).firstOrNull()

    fun recoverInterruptedDeliveries(now: Long = System.currentTimeMillis() / 1_000) {
        writableDatabase.execSQL("UPDATE qso_delivery SET state='RETRY_WAIT',updated_at=?,next_attempt_at=? WHERE state='SENDING'",
            arrayOf(now, now + 60))
    }

    fun updateDelivery(record: DeliveryRecord) {
        writableDatabase.execSQL("""UPDATE qso_delivery SET state=?,updated_at=?,attempt_count=?,last_attempt_at=?,next_attempt_at=?,
            payload_hash=?,remote_id=?,provider_message=?,http_status=? WHERE qso_id=? AND provider=?""".trimIndent(),
            arrayOf<Any?>(record.state.name, record.updatedAt, record.attemptCount, record.lastAttemptAt, record.nextAttemptAt,
                record.payloadHash, record.remoteId, record.providerMessage.take(1_000), record.httpStatus, record.qsoId, record.provider.name))
        changeRevision.incrementAndGet()
    }

    fun setProviderQueueState(provider: SyncProvider, from: Set<DeliveryState>, state: DeliveryState,
        now: Long = System.currentTimeMillis() / 1_000) {
        if (from.isEmpty()) return
        val names = from.joinToString(",") { "'${it.name}'" }
        writableDatabase.execSQL("UPDATE qso_delivery SET state=?,updated_at=? WHERE provider=? AND state IN ($names)",
            arrayOf<Any?>(state.name, now, provider.name))
        changeRevision.incrementAndGet()
    }

    fun removeUnsentDelivery(qsoId: String, provider: SyncProvider) {
        writableDatabase.execSQL("DELETE FROM qso_delivery WHERE qso_id=? AND provider=? AND state NOT IN ('ACCEPTED','ACCEPTED_DUPLICATE','ACCEPTED_MODIFIED','SUBMITTED_BATCH')",
            arrayOf(qsoId, provider.name))
        changeRevision.incrementAndGet()
    }

    fun markProviderAccepted(qsoId: String, provider: SyncProvider) {
        val qso = findById(qsoId) ?: return
        update(applyAcceptedFlag(qso, provider))
    }

    fun updateLocal(qso: Qso) {
        val old = findById(qso.id) ?: return
        update(qso)
        if (toADIF(old) != toADIF(qso)) {
            writableDatabase.execSQL("UPDATE qso_delivery SET state='LOCAL_CHANGED',updated_at=? WHERE qso_id=? AND state IN ('ACCEPTED','ACCEPTED_DUPLICATE','ACCEPTED_MODIFIED','SUBMITTED_BATCH')",
                arrayOf<Any?>(System.currentTimeMillis() / 1_000, qso.id))
        }
    }

    fun deleteLocal(id: String) {
        writableDatabase.delete("qso", "id=?", arrayOf(id))
        changeRevision.incrementAndGet()
    }

    private fun deliveryQuery(where: String, args: Array<String>?): List<DeliveryRecord> = buildList {
        readableDatabase.rawQuery("SELECT qso_id,provider,state,created_at,updated_at,attempt_count,last_attempt_at,next_attempt_at,payload_hash,remote_id,provider_message,http_status FROM qso_delivery WHERE $where", args).use { cursor ->
            while (cursor.moveToNext()) add(DeliveryRecord(
                qsoId = cursor.getString(0), provider = SyncProvider.valueOf(cursor.getString(1)),
                state = DeliveryState.valueOf(cursor.getString(2)), createdAt = cursor.getLong(3), updatedAt = cursor.getLong(4),
                attemptCount = cursor.getInt(5), lastAttemptAt = cursor.getLong(6).takeIf { !cursor.isNull(6) },
                nextAttemptAt = cursor.getLong(7).takeIf { !cursor.isNull(7) }, payloadHash = cursor.getString(8),
                remoteId = cursor.getString(9), providerMessage = cursor.getString(10),
                httpStatus = cursor.getInt(11).takeIf { !cursor.isNull(11) }))
        }
    }
    private fun query(limit: String): List<Qso> = queryWhere("1=1 ORDER BY created_at DESC$limit", null)

    private data class PageQuery(val where: String, val args: List<String>, val order: String)

    private fun pageQuery(filter: LogbookFilter, stationId: String?): PageQuery {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        fun json(key: String) = "COALESCE(json_extract(details_json,'$.${key}'),'')"
        fun extra(key: String) = "COALESCE(json_extract(details_json,'$.extraAdifFields.${key}'),'')"
        fun text(expression: String, value: String) {
            if (value.isBlank()) return
            if (value.trim() == "*") { clauses += "TRIM($expression) <> ''"; return }
            clauses += "$expression LIKE ? ESCAPE '\\' COLLATE NOCASE"
            args += "%" + value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        fun choice(expression: String, value: String) {
            if (value.isBlank()) return
            clauses += "$expression = ? COLLATE NOCASE"; args += value.trim()
        }
        fun status(expression: String, value: String) {
            if (value.isBlank()) return
            val normalized = "UPPER(TRIM($expression))"
            if (value.equals("Y", true)) clauses += "$normalized IN ('Y','YES','S','SENT','UPLOADED','1','TRUE')"
            else if (value.equals("N", true)) clauses += "($normalized = '' OR $normalized IN ('N','NO','0','FALSE'))"
            else { clauses += "$normalized = ?"; args += value.trim().uppercase() }
        }
        fun numeric(expression: String, value: String, scale: Double = 1.0) {
            val raw = value.trim().replace(',', '.')
            if (raw.isBlank() || raw == "*") return
            Regex("^(-?\\d+(?:\\.\\d+)?)\\s*(?:\\.\\.|-)\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(raw)?.let {
                val first = it.groupValues[1].toDouble(); val second = it.groupValues[2].toDouble()
                clauses += "CAST($expression AS REAL) BETWEEN ? AND ?"
                args += (minOf(first, second) * scale).toString(); args += (maxOf(first, second) * scale).toString(); return
            }
            val match = Regex("^(>=|<=|>|<|=)?\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(raw) ?: return
            clauses += "CAST($expression AS REAL) ${match.groupValues[1].ifBlank { ">=" }} ?"
            args += (match.groupValues[2].toDouble() * scale).toString()
        }

        clauses += "1=1"
        stationId?.takeIf { it.isNotBlank() }?.let { station ->
            clauses += "(${json("stationProfileId")} = ? OR ${json("syncState")} = 'pending' OR (${json("stationProfileId")} = '' AND ${json("remoteId")} = ''))"
            args += station
        }
        filter.fromEpochSeconds?.let { clauses += "created_at >= ?"; args += it.toString() }
        filter.toEpochSecondsExclusive?.let { clauses += "created_at < ?"; args += it.toString() }
        text("callsign", filter.callsign); text(json("stationProfileId"), filter.stationProfile)
        text(json("stationCallsign"), filter.stationCallsign); text("name", filter.name); text("qth", filter.qth)
        text(json("email"), filter.email); text(json("dxcc"), filter.dxcc); text("country", filter.country)
        text(json("state"), filter.state); text(json("grid"), filter.grid); text(json("cqZone"), filter.cqZone)
        text(json("ituZone"), filter.ituZone); choice("mode", filter.mode)
        when (filter.modeFamily.uppercase()) {
            "CW" -> clauses += "UPPER(COALESCE(NULLIF(${json("submode")},''),mode)) IN ('CW','CW-R','CWR')"
            "PHONE" -> clauses += "UPPER(COALESCE(NULLIF(${json("submode")},''),mode)) IN ('SSB','USB','LSB','FM','AM')"
            "DIGITAL" -> clauses += "UPPER(COALESCE(NULLIF(${json("submode")},''),mode)) IN ('FT8','FT4','RTTY','DIGITAL','DATA','PSK31','JS8')"
        }
        choice(json("submode"), filter.submode)
        choice(json("band"), filter.band); numeric("(frequency_hz / 1000000.0)", filter.frequency)
        numeric("(${json("frequencyRxHz")} / 1000000.0)", filter.frequencyRx); choice(json("bandRx"), filter.bandRx)
        choice(json("propagationMode"), filter.propagation); text(json("county"), filter.county)
        text(json("dok"), filter.dok); text(json("sotaRef"), filter.sota)
        text("(${json("potaRef")} || ' ' || ${json("potaRefs")})", filter.pota)
        text(json("iota"), filter.iota); text(json("wwffRef"), filter.wwff); text(json("operatorCallsign"), filter.operator)
        text(json("radioModel"), filter.radioModel)
        text(json("contestId"), filter.contest); choice(json("continent"), filter.continent)
        text(extra("SAT_NAME"), filter.satellite); text(extra("SAT_MODE"), filter.satelliteMode)
        text(extra("ORBIT"), filter.orbit); text("(${json("comment")} || ' ' || notes)", filter.comment)
        text(json("qslMessage"), filter.qslMessage); text("notes", filter.notes)
        numeric(json("distanceKm"), filter.distance); numeric(json("durationSeconds"), filter.duration, 60.0)
        status(json("qslSent"), filter.qslSent); status(json("qslReceived"), filter.qslReceived)
        choice(json("qslMethod"), filter.qslSentMethod); choice(json("qslReceivedMethod"), filter.qslReceivedMethod)
        status(json("lotwSent"), filter.lotwSent); status(json("lotwReceived"), filter.lotwReceived)
        status(json("clublogSent"), filter.clublogSent); status(json("clublogReceived"), filter.clublogReceived)
        status(json("eqslSent"), filter.eqslSent); status(json("eqslReceived"), filter.eqslReceived)
        status(json("dclSent"), filter.dclSent); status(json("dclReceived"), filter.dclReceived)
        status(json("qrzSent"), filter.qrzSent); status(json("qrzReceived"), filter.qrzReceived)
        text(json("qslVia"), filter.qslVia)
        if (filter.callsignPrefix.isNotBlank()) { clauses += "UPPER(callsign) LIKE ?"; args += filter.callsignPrefix.uppercase() + "%" }
        numeric(json("txPowerW"), filter.txPower)
        when (filter.confirmationSource.uppercase()) {
            "PAPER", "QSL" -> status(json("qslReceived"), "Y")
            "LOTW" -> status(json("lotwReceived"), "Y")
            "EQSL" -> status(json("eqslReceived"), "Y")
            "QRZ" -> status(json("qrzReceived"), "Y")
            "CLUBLOG" -> status(json("clublogReceived"), "Y")
            "DCL" -> status(json("dclReceived"), "Y")
            "AWARD" -> clauses += "(UPPER(TRIM(${json("qslReceived")})) IN ('Y','V') OR UPPER(TRIM(${json("lotwReceived")})) IN ('Y','V'))"
            "UNCONFIRMED" -> clauses += "(UPPER(TRIM(${json("qslReceived")})) NOT IN ('Y','V') AND UPPER(TRIM(${json("lotwReceived")})) NOT IN ('Y','V'))"
        }
        when (filter.portableProgram.uppercase()) {
            "POTA" -> clauses += "(${json("potaRef")}<>'' OR ${json("myPotaRef")}<>'' OR json_array_length(CASE WHEN json_valid(${json("potaRefs")}) THEN ${json("potaRefs")} ELSE '[]' END)>0 OR json_array_length(CASE WHEN json_valid(${json("myPotaRefs")}) THEN ${json("myPotaRefs")} ELSE '[]' END)>0)"
            "SOTA" -> clauses += "(${json("sotaRef")}<>'' OR ${json("mySotaRef")}<>'')"
            "WWFF" -> clauses += "(${json("wwffRef")}<>'' OR ${json("myWwffRef")}<>'')"
            "IOTA" -> clauses += "(${json("iota")}<>'' OR ${json("myIota")}<>'')"
            "ANY" -> clauses += "(${json("potaRef")}<>'' OR ${json("myPotaRef")}<>'' OR ${json("sotaRef")}<>'' OR ${json("mySotaRef")}<>'' OR ${json("wwffRef")}<>'' OR ${json("myWwffRef")}<>'')"
        }
        when (filter.recordVisibility.uppercase()) {
            "ACTIVE" -> clauses += "LOWER(${json("syncState")}) NOT IN ('conflict','tombstone','remote_deleted')"
            "ACTIVE_AND_CONFLICTS" -> clauses += "LOWER(${json("syncState")}) NOT IN ('tombstone','remote_deleted')"
            "DELETED" -> clauses += "LOWER(${json("syncState")}) IN ('tombstone','remote_deleted')"
        }
        if (filter.qslImages.equals("Y", true)) clauses += "${json("qslImages")} <> '' AND UPPER(${json("qslImages")}) NOT IN ('N','NO','0','FALSE')"
        if (filter.qslImages.equals("N", true)) clauses += "(${json("qslImages")} = '' OR UPPER(${json("qslImages")}) IN ('N','NO','0','FALSE'))"
        when (filter.provenance.uppercase()) {
            "LOCAL" -> clauses += "${json("remoteId")} = ''"
            "REMOTE", "LINKED" -> clauses += "${json("remoteId")} <> ''"
        }
        val invalid = "(TRIM(callsign)='' OR frequency_hz<=0 OR TRIM(mode)='' OR created_at<=0)"
        when (filter.recordState.uppercase()) {
            "INCOMPLETE", "INVALID" -> clauses += invalid
            "VALID" -> clauses += "NOT $invalid"
        }
        if (filter.duplicateState.equals("CANDIDATE", true)) clauses += """EXISTS(
            SELECT 1 FROM qso duplicate WHERE duplicate.id<>qso.id
            AND UPPER(duplicate.callsign)=UPPER(qso.callsign) AND duplicate.frequency_hz=qso.frequency_hz
            AND UPPER(duplicate.mode)=UPPER(qso.mode) AND ABS(duplicate.created_at-qso.created_at)<=15)""".trimIndent()
        when (filter.syncRelation.uppercase()) {
            "LOCAL_ONLY" -> clauses += "${json("remoteId")}='' AND NOT EXISTS(SELECT 1 FROM wavelog_remote_link l WHERE l.local_qso_id=qso.id)"
            "LINKED" -> clauses += "EXISTS(SELECT 1 FROM wavelog_remote_link l WHERE l.local_qso_id=qso.id)"
            "OUTBOX" -> clauses += "EXISTS(SELECT 1 FROM wavelog_outbox o WHERE o.local_qso_id=qso.id AND o.state<>'ACCEPTED')"
            "CONFLICT" -> clauses += "EXISTS(SELECT 1 FROM wavelog_conflict c WHERE c.local_qso_id=qso.id AND c.state='OPEN')"
            "ATTENTION" -> clauses += "(EXISTS(SELECT 1 FROM wavelog_outbox o WHERE o.local_qso_id=qso.id AND o.state<>'ACCEPTED') OR EXISTS(SELECT 1 FROM wavelog_conflict c WHERE c.local_qso_id=qso.id AND c.state='OPEN') OR LOWER(${json("syncState")}) IN ('pending','queued','retry','failed','conflict'))"
            "TOMBSTONE" -> clauses += "EXISTS(SELECT 1 FROM wavelog_tombstone t WHERE t.local_qso_id=qso.id AND t.acknowledged_at IS NULL)"
            "REMOTE_DELETED" -> clauses += "(${json("syncState")}='remote_deleted' OR EXISTS(SELECT 1 FROM wavelog_tombstone t WHERE t.local_qso_id=qso.id AND t.acknowledged_at IS NOT NULL))"
        }

        val sortExpression = when (filter.sort) {
            LogbookSort.TIME -> "created_at"; LogbookSort.CALLSIGN -> "callsign COLLATE NOCASE"
            LogbookSort.NAME -> "name COLLATE NOCASE"; LogbookSort.COUNTRY -> "country COLLATE NOCASE"
            LogbookSort.DXCC -> "${json("dxcc")} COLLATE NOCASE"; LogbookSort.MODE -> "mode COLLATE NOCASE"
            LogbookSort.SUBMODE -> "${json("submode")} COLLATE NOCASE"
            LogbookSort.BAND -> "frequency_hz"; LogbookSort.FREQUENCY -> "frequency_hz"
            LogbookSort.GRID -> "${json("grid")} COLLATE NOCASE"
            LogbookSort.DISTANCE -> "CAST(${json("distanceKm")} AS REAL)"
            LogbookSort.DURATION -> "CAST(${json("durationSeconds")} AS INTEGER)"
        }
        val direction = if (filter.direction == LogbookSortDirection.ASCENDING) "ASC" else "DESC"
        return PageQuery(clauses.joinToString(" AND "), args, "$sortExpression $direction, created_at DESC, id ASC")
    }

    private fun queryWhere(where: String, args: Array<String>?): List<Qso> = buildList {
        readableDatabase.rawQuery("SELECT id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at,name,qth,notes,country,details_json FROM qso WHERE $where", args).use { cursor ->
            while (cursor.moveToNext()) add(fromRow(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getLong(6), cursor.getString(7), cursor.getString(8),
                cursor.getString(9), cursor.getString(10), cursor.getString(11)))
        }
    }
    private fun fromRow(id: String, call: String, frequency: Long, mode: String, sent: String, received: String,
        created: Long, name: String, qth: String, notes: String, country: String, raw: String): Qso {
        val row = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        fun value(key: String) = row.optString(key).takeUnless { it.equals("null", true) } ?: ""
        return Qso(
            id = id, callsign = call, frequencyHz = frequency, mode = mode, rstSent = sent, rstReceived = received,
            createdAt = created, name = name, qth = qth, notes = notes, country = country, band = value("band"),
            grid = value("grid"), iota = value("iota"), sotaRef = value("sotaRef"), wwffRef = value("wwffRef"),
            potaRef = value("potaRef"), comment = value("comment"), frequencyRxHz = row.optLong("frequencyRxHz"),
            bandRx = value("bandRx"), txPowerW = row.optInt("txPowerW"), operatorCallsign = value("operatorCallsign"),
            stationCallsign = value("stationCallsign"), stationProfileId = value("stationProfileId"),
            stationLocation = value("stationLocation"), myGrid = value("myGrid"), myCountry = value("myCountry"),
            myDxcc = value("myDxcc"), myCqZone = value("myCqZone"), myItuZone = value("myItuZone"),
            myState = value("myState"), myIota = value("myIota"), mySotaRef = value("mySotaRef"),
            myWwffRef = value("myWwffRef"), myPotaRef = value("myPotaRef"), radioModel = value("radioModel"),
            dxcc = value("dxcc"), continent = value("continent"), region = value("region"), cqZone = value("cqZone"),
            ituZone = value("ituZone"), state = value("state"), email = value("email"),
            propagationMode = value("propagationMode"), antennaPath = value("antennaPath"),
            qslSent = value("qslSent").ifBlank { "N" }, qslMethod = value("qslMethod"), qslVia = value("qslVia"),
            qslMessage = value("qslMessage"), submode = value("submode"), county = value("county"), dok = value("dok"),
            contestId = value("contestId"), distanceKm = row.optDouble("distanceKm", 0.0),
            durationSeconds = row.optLong("durationSeconds"), qslReceived = value("qslReceived").ifBlank { "N" },
            qslReceivedMethod = value("qslReceivedMethod"), lotwSent = value("lotwSent").ifBlank { "N" },
            lotwReceived = value("lotwReceived").ifBlank { "N" }, clublogSent = value("clublogSent").ifBlank { "N" },
            clublogReceived = value("clublogReceived").ifBlank { "N" }, eqslSent = value("eqslSent").ifBlank { "N" },
            eqslReceived = value("eqslReceived").ifBlank { "N" }, dclSent = value("dclSent").ifBlank { "N" },
            dclReceived = value("dclReceived").ifBlank { "N" }, qrzSent = value("qrzSent").ifBlank { "N" },
            qrzReceived = value("qrzReceived").ifBlank { "N" }, qslImages = value("qslImages"),
            syncState = value("syncState").ifBlank { "local" }, remoteId = value("remoteId"),
            activationSessionId = value("activationSessionId"), activationProgram = value("activationProgram"),
            myPotaRefs = row.optJSONArray("myPotaRefs").jsonStringList(), potaRefs = row.optJSONArray("potaRefs").jsonStringList(),
            extraAdifFields = row.optJSONObject("extraAdifFields").jsonStringMap())
    }
    private fun details(qso: Qso) = JSONObject().apply {
        put("band", qso.band); put("grid", qso.grid); put("iota", qso.iota); put("sotaRef", qso.sotaRef)
        put("wwffRef", qso.wwffRef); put("potaRef", qso.potaRef); put("comment", qso.comment)
        put("frequencyRxHz", qso.frequencyRxHz); put("bandRx", qso.bandRx); put("txPowerW", qso.txPowerW)
        put("operatorCallsign", qso.operatorCallsign); put("stationCallsign", qso.stationCallsign)
        put("stationProfileId", qso.stationProfileId); put("stationLocation", qso.stationLocation)
        put("myGrid", qso.myGrid); put("myCountry", qso.myCountry); put("myDxcc", qso.myDxcc)
        put("myCqZone", qso.myCqZone); put("myItuZone", qso.myItuZone); put("myState", qso.myState)
        put("myIota", qso.myIota); put("mySotaRef", qso.mySotaRef); put("myWwffRef", qso.myWwffRef); put("myPotaRef", qso.myPotaRef)
        put("radioModel", qso.radioModel); put("dxcc", qso.dxcc); put("continent", qso.continent); put("region", qso.region)
        put("cqZone", qso.cqZone); put("ituZone", qso.ituZone); put("state", qso.state); put("email", qso.email)
        put("propagationMode", qso.propagationMode); put("antennaPath", qso.antennaPath)
        put("qslSent", qso.qslSent); put("qslMethod", qso.qslMethod); put("qslVia", qso.qslVia); put("qslMessage", qso.qslMessage)
        put("submode", qso.submode); put("county", qso.county); put("dok", qso.dok); put("contestId", qso.contestId)
        put("distanceKm", qso.distanceKm); put("durationSeconds", qso.durationSeconds)
        put("qslReceived", qso.qslReceived); put("qslReceivedMethod", qso.qslReceivedMethod)
        put("lotwSent", qso.lotwSent); put("lotwReceived", qso.lotwReceived)
        put("clublogSent", qso.clublogSent); put("clublogReceived", qso.clublogReceived)
        put("eqslSent", qso.eqslSent); put("eqslReceived", qso.eqslReceived)
        put("dclSent", qso.dclSent); put("dclReceived", qso.dclReceived)
        put("qrzSent", qso.qrzSent); put("qrzReceived", qso.qrzReceived); put("qslImages", qso.qslImages)
        put("syncState", qso.syncState); put("remoteId", qso.remoteId)
        put("activationSessionId", qso.activationSessionId); put("activationProgram", qso.activationProgram)
        put("myPotaRefs", org.json.JSONArray(qso.myPotaRefs)); put("potaRefs", org.json.JSONArray(qso.potaRefs))
        put("extraAdifFields", JSONObject(qso.extraAdifFields))
    }
}

internal fun org.json.JSONArray?.jsonStringList(): List<String> = if (this == null) emptyList() else buildList {
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

internal fun org.json.JSONObject?.jsonStringMap(): Map<String, String> = if (this == null) emptyMap() else buildMap {
    keys().forEach { key -> optString(key).takeIf(String::isNotBlank)?.let { put(key, it) } }
}
