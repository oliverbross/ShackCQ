package app.shackcq.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class DigiGalleryItem(
    val id: String, val path: String, val caption: String, val mode: String,
    val width: Int, val height: Int, val completedEpoch: Long, val dialFrequencyHz: Long,
    val stationProfile: String, val fskId: String, val sourceFilename: String, val pinned: Boolean,
)

class DigiSessionStore(
    context: Context,
    databaseName: String = "shackcq-digi.sqlite",
    private val filesRoot: File = context.filesDir,
) : SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE decode_event(
            id TEXT PRIMARY KEY,session_id TEXT NOT NULL,epoch INTEGER NOT NULL,mode TEXT NOT NULL,
            slot_start_millis INTEGER NOT NULL,decode_source TEXT NOT NULL,timing_exact INTEGER NOT NULL,
            dial_frequency_hz INTEGER NOT NULL,snr REAL NOT NULL,dt REAL NOT NULL,audio_hz REAL NOT NULL,
            text TEXT NOT NULL,callsign TEXT NOT NULL,grid TEXT NOT NULL,country TEXT NOT NULL,
            continent TEXT NOT NULL,distance_km REAL NOT NULL,bearing_degrees REAL NOT NULL,
            worked INTEGER NOT NULL,confirmed INTEGER NOT NULL,needs_json TEXT NOT NULL,watchlisted INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX decode_event_time_idx ON decode_event(epoch DESC,id)")
        db.execSQL("CREATE INDEX decode_event_call_idx ON decode_event(callsign,epoch DESC)")
        db.execSQL("""CREATE TABLE digi_session(
            id TEXT PRIMARY KEY,mode TEXT NOT NULL,started_epoch INTEGER NOT NULL,ended_epoch INTEGER NOT NULL DEFAULT 0,
            selected_call TEXT NOT NULL DEFAULT '',state TEXT NOT NULL DEFAULT 'IDLE')""")
        db.execSQL("""CREATE TABLE qso_draft(
            id TEXT PRIMARY KEY,created_epoch INTEGER NOT NULL,completed INTEGER NOT NULL DEFAULT 0,payload_json TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE sstv_gallery(
            id TEXT PRIMARY KEY,path TEXT NOT NULL UNIQUE,caption TEXT NOT NULL,mode TEXT NOT NULL,width INTEGER NOT NULL,
            height INTEGER NOT NULL,completed_epoch INTEGER NOT NULL,dial_frequency_hz INTEGER NOT NULL,
            station_profile TEXT NOT NULL,fsk_id TEXT NOT NULL,source_filename TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX sstv_gallery_time_idx ON sstv_gallery(completed_epoch DESC,id)")
        db.execSQL("CREATE TABLE digi_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE decode_event ADD COLUMN slot_start_millis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE decode_event ADD COLUMN decode_source TEXT NOT NULL DEFAULT 'LIVE_CAPTURE'")
            db.execSQL("ALTER TABLE decode_event ADD COLUMN timing_exact INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE decode_event ADD COLUMN dial_frequency_hz INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE decode_event SET slot_start_millis=period_start_epoch*1000")
        }
    }

    fun beginSession(mode: String, epoch: Long): String = UUID.randomUUID().toString().also { id ->
        writableDatabase.insertOrThrow("digi_session", null, ContentValues().apply {
            put("id", id); put("mode", mode); put("started_epoch", epoch); put("ended_epoch", 0)
            put("selected_call", ""); put("state", "IDLE")
        })
    }

    fun endSession(id: String, epoch: Long, state: String) {
        writableDatabase.update("digi_session", ContentValues().apply { put("ended_epoch", epoch); put("state", state) }, "id=?", arrayOf(id))
    }

    fun appendDecodes(rows: List<DigiDecodeEvent>, retentionDays: Int = 7, hardCap: Int = 20_000) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row -> db.insertWithOnConflict("decode_event", null, ContentValues().apply {
                put("id", row.id); put("session_id", row.sessionId); put("epoch", row.epoch); put("mode", row.mode)
                put("slot_start_millis", row.slotStartMillis); put("decode_source", row.decodeSource.name)
                put("timing_exact", if (row.exactSlotTiming) 1 else 0); put("dial_frequency_hz", row.dialFrequencyHz)
                put("snr", row.snr); put("dt", row.dt); put("audio_hz", row.audioHz)
                put("text", row.text); put("callsign", row.callsign); put("grid", row.grid); put("country", row.country)
                put("continent", row.continent); put("distance_km", row.distanceKm); put("bearing_degrees", row.bearingDegrees)
                put("worked", if (row.worked) 1 else 0); put("confirmed", if (row.confirmed) 1 else 0)
                put("needs_json", JSONArray(row.needs).toString()); put("watchlisted", if (row.watchlisted) 1 else 0)
            }, SQLiteDatabase.CONFLICT_IGNORE) }
            val cutoff = System.currentTimeMillis() / 1_000 - retentionDays.coerceIn(1, 30) * 86_400L
            db.delete("decode_event", "epoch<?", arrayOf(cutoff.toString()))
            db.execSQL("DELETE FROM decode_event WHERE id IN (SELECT id FROM decode_event ORDER BY epoch DESC,id DESC LIMIT -1 OFFSET ?)", arrayOf(hardCap.coerceIn(1_000, 20_000)))
            val completedCutoff = System.currentTimeMillis() / 1_000 - 90L * 86_400L
            db.delete("digi_session", "ended_epoch>0 AND ended_epoch<?", arrayOf(completedCutoff.toString()))
            db.delete("qso_draft", "completed=1 AND created_epoch<?", arrayOf(completedCutoff.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun recentDecodes(limit: Int = 3_000): List<DigiDecodeEvent> = readableDatabase.rawQuery("""
        SELECT id,session_id,epoch,mode,slot_start_millis,decode_source,timing_exact,dial_frequency_hz,
        snr,dt,audio_hz,text,callsign,grid,country,continent,
        distance_km,bearing_degrees,worked,confirmed,needs_json,watchlisted
        FROM decode_event ORDER BY epoch DESC,id DESC LIMIT ?
    """.trimIndent(), arrayOf(limit.coerceIn(1, 3_000).toString())).use { cursor -> buildList {
        while (cursor.moveToNext()) add(DigiDecodeEvent(
            id = cursor.getString(0), sessionId = cursor.getString(1), epoch = cursor.getLong(2), mode = cursor.getString(3),
            slotStartMillis = cursor.getLong(4),
            decodeSource = runCatching { DigiDecodeSource.valueOf(cursor.getString(5)) }.getOrDefault(DigiDecodeSource.REFERENCE_RECORDING),
            exactSlotTiming = cursor.getInt(6) != 0, dialFrequencyHz = cursor.getLong(7),
            snr = cursor.getFloat(8), dt = cursor.getFloat(9), audioHz = cursor.getFloat(10), text = cursor.getString(11),
            callsign = cursor.getString(12), grid = cursor.getString(13), country = cursor.getString(14), continent = cursor.getString(15),
            distanceKm = cursor.getDouble(16), bearingDegrees = cursor.getDouble(17), worked = cursor.getInt(18) != 0,
            confirmed = cursor.getInt(19) != 0,
            needs = runCatching { JSONArray(cursor.getString(20)).stringList() }.getOrDefault(emptyList()),
            watchlisted = cursor.getInt(21) != 0,
        ))
    }.asReversed() }

    fun saveDraft(draft: DigiQsoDraft, completed: Boolean = false): String = UUID.randomUUID().toString().also { id ->
        writableDatabase.insertOrThrow("qso_draft", null, ContentValues().apply {
            put("id", id); put("created_epoch", draft.startEpoch); put("completed", if (completed) 1 else 0); put("payload_json", draft.toJson())
        })
    }

    fun completeDraft(id: String) { writableDatabase.update("qso_draft", ContentValues().apply { put("completed", 1) }, "id=?", arrayOf(id)) }

    fun saveSstvPng(
        bitmap: Bitmap, mode: String, epoch: Long, dialFrequencyHz: Long, stationProfile: String,
        fskId: String, sourceFilename: String, quotaMb: Int,
    ): DigiGalleryItem {
        val directory = File(filesRoot, "digi/sstv").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.png")
        val temporary = File(directory, "$id.tmp")
        try {
            FileOutputStream(temporary).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.fd.sync() }
            check(temporary.renameTo(file)) { "Could not atomically save SSTV image" }
            val item = DigiGalleryItem(id, file.absolutePath, "", mode, bitmap.width, bitmap.height, epoch, dialFrequencyHz, stationProfile, fskId, sourceFilename, false)
            writableDatabase.insertOrThrow("sstv_gallery", null, item.values())
            enforceGalleryQuota(quotaMb)
            return item
        } catch (failure: Throwable) {
            temporary.delete(); file.delete(); throw failure
        }
    }

    fun gallery(): List<DigiGalleryItem> = readableDatabase.rawQuery("""
        SELECT id,path,caption,mode,width,height,completed_epoch,dial_frequency_hz,station_profile,fsk_id,source_filename,pinned
        FROM sstv_gallery ORDER BY completed_epoch DESC,id DESC
    """.trimIndent(), null).use { cursor -> buildList { while (cursor.moveToNext()) {
        val item = DigiGalleryItem(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
            cursor.getInt(4), cursor.getInt(5), cursor.getLong(6), cursor.getLong(7), cursor.getString(8), cursor.getString(9),
            cursor.getString(10), cursor.getInt(11) != 0)
        if (File(item.path).isFile) add(item) else writableDatabase.delete("sstv_gallery", "id=?", arrayOf(item.id))
    } } }

    fun updateGallery(id: String, caption: String? = null, pinned: Boolean? = null) {
        val values = ContentValues().apply { caption?.let { put("caption", it.take(120)) }; pinned?.let { put("pinned", if (it) 1 else 0) } }
        if (values.size() > 0) writableDatabase.update("sstv_gallery", values, "id=?", arrayOf(id))
    }

    fun deleteGallery(id: String): Boolean {
        val path = readableDatabase.rawQuery("SELECT path FROM sstv_gallery WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else return false }
        val removed = !File(path).exists() || File(path).delete()
        if (removed) writableDatabase.delete("sstv_gallery", "id=?", arrayOf(id))
        return removed
    }

    fun enforceGalleryQuota(quotaMb: Int) {
        val limit = quotaMb.coerceIn(25, 250).toLong() * 1024L * 1024L
        val rows = gallery()
        var total = rows.sumOf { File(it.path).length() }
        rows.asReversed().filterNot(DigiGalleryItem::pinned).forEach { item ->
            if (total <= limit) return
            val size = File(item.path).length()
            if (deleteGallery(item.id)) total -= size
        }
    }

    fun counts(): Triple<Int, Int, Long> {
        val decodes = readableDatabase.rawQuery("SELECT COUNT(*) FROM decode_event", null).use { it.moveToFirst(); it.getInt(0) }
        val images = readableDatabase.rawQuery("SELECT COUNT(*) FROM sstv_gallery", null).use { it.moveToFirst(); it.getInt(0) }
        val bytes = gallery().sumOf { File(it.path).length() }
        return Triple(decodes, images, bytes)
    }
}

private fun DigiGalleryItem.values() = ContentValues().apply {
    put("id", id); put("path", path); put("caption", caption); put("mode", mode); put("width", width); put("height", height)
    put("completed_epoch", completedEpoch); put("dial_frequency_hz", dialFrequencyHz); put("station_profile", stationProfile)
    put("fsk_id", fskId); put("source_filename", sourceFilename); put("pinned", if (pinned) 1 else 0)
}

private fun DigiQsoDraft.toJson() = JSONObject().apply {
    put("callsign", callsign); put("grid", grid); put("sentReport", sentReport); put("receivedReport", receivedReport)
    put("startEpoch", startEpoch); put("endEpoch", endEpoch); put("dialFrequencyHz", dialFrequencyHz); put("band", band)
    put("mode", mode); put("submode", submode); put("audioFrequencyHz", audioFrequencyHz); put("stationCallsign", stationCallsign)
    put("stationProfile", stationProfile); put("stationLocation", stationLocation); put("stationGrid", stationGrid)
    put("operatorCallsign", operatorCallsign); put("activationContext", activationContext); put("contestId", contestId); put("comment", comment)
}.toString()
