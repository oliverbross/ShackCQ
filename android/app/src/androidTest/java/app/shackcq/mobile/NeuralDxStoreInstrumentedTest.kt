package app.shackcq.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class NeuralDxStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "neural-dx-store-${System.nanoTime()}.sqlite"
    private lateinit var store: NeuralDxStore

    @Before fun createVersionThreeDatabase() {
        context.deleteDatabase(databaseName)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE spot(id TEXT PRIMARY KEY, ts INTEGER NOT NULL, call TEXT NOT NULL, spotter TEXT NOT NULL,
                frequency_hz INTEGER NOT NULL, band TEXT NOT NULL, mode TEXT NOT NULL, country TEXT NOT NULL,
                continent TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, score INTEGER NOT NULL,
                watchlisted INTEGER NOT NULL, comment TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX spot_ts_idx ON spot(ts DESC)")
            db.execSQL("CREATE INDEX spot_band_ts_idx ON spot(band,ts DESC)")
            db.execSQL("CREATE INDEX spot_call_ts_idx ON spot(call,ts DESC)")
            db.execSQL("CREATE TABLE prediction_result(key TEXT PRIMARY KEY)")
            db.execSQL("ALTER TABLE spot ADD COLUMN dxcc TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN confidence INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE spot ADD COLUMN samples INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE spot ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            val timestamp = Instant.now().epochSecond - 60
            db.execSQL("""INSERT INTO spot(id,ts,call,spotter,frequency_hz,band,mode,country,continent,latitude,longitude,
                score,watchlisted,comment,dxcc,confidence,samples,reason,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(
                "existing", timestamp, "JA1OLD", "W1AW", 14_074_000, "20m", "FT8", "", "", 0.0, 0.0, 45, 0, "legacy comment",
                "", 0, 0, "", timestamp,
            ))
            db.version = 3
        }
        store = NeuralDxStore(context, databaseName)
        store.writableDatabase
    }

    @After fun closeAndDeleteDatabase() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun migratesVersionThreeAndUpsertsWithoutErasingEnrichmentOrCreatingFreshAlerts() {
        val db = store.writableDatabase
        assertEquals(5, db.version)
        val columns = db.rawQuery("PRAGMA table_info(spot)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(columns.containsAll(setOf("dxcc", "confidence", "samples", "reason", "updated_at")))
        assertFalse(tableExists("prediction_result"))
        assertTrue(tableExists("evidence_bucket"))
        assertTrue(tableExists("outlook_prediction"))
        assertTrue(tableExists("outlook_calibration"))
        assertTrue(tableExists("outlook_meta"))
        val evidenceColumns = db.rawQuery("PRAGMA table_info(evidence_bucket)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        assertTrue("receiver_keys" in evidenceColumns)
        assertEquals(readLong("existing", "ts"), readLong("existing", "updated_at"))

        val enriched = spot(
            id = "existing", call = "JA1NEW", score = 77, confidence = 66, samples = 12,
            country = "Japan", dxcc = "339", continent = "AS", latitude = 35.68, longitude = 139.76,
            comment = "new useful comment", reason = "Current evidence",
        )
        assertTrue(store.ingest(listOf(enriched)).isEmpty())
        assertEquals(1, rowCount())
        assertEquals("Japan", readText("existing", "country"))
        assertEquals("339", readText("existing", "dxcc"))
        assertEquals("AS", readText("existing", "continent"))
        assertEquals(35.68, readDouble("existing", "latitude"), 0.001)
        assertEquals(139.76, readDouble("existing", "longitude"), 0.001)
        assertEquals("new useful comment", readText("existing", "comment"))

        val rescored = enriched.copy(
            callsign = "JA1LATEST", score = 83, confidence = 72, samples = 19,
            country = "", dxcc = "", continent = "", latitude = 0.0, longitude = 0.0, comment = "", reason = "Latest evidence",
        )
        assertTrue(store.ingest(listOf(rescored)).isEmpty())
        assertEquals(1, rowCount())
        assertEquals("JA1LATEST", readText("existing", "call"))
        assertEquals(83, readLong("existing", "score").toInt())
        assertEquals(72, readLong("existing", "confidence").toInt())
        assertEquals(19, readLong("existing", "samples").toInt())
        assertEquals("Latest evidence", readText("existing", "reason"))
        assertEquals("Japan", readText("existing", "country"))
        assertEquals("339", readText("existing", "dxcc"))
        assertEquals("AS", readText("existing", "continent"))
        assertEquals(35.68, readDouble("existing", "latitude"), 0.001)
        assertEquals(139.76, readDouble("existing", "longitude"), 0.001)
        assertEquals("new useful comment", readText("existing", "comment"))

        val fresh = spot(id = "fresh", call = "VK8NEW", score = 60, confidence = 50, samples = 5)
        assertEquals(listOf("fresh"), store.ingest(listOf(fresh)).map { it.id })
        assertEquals(2, rowCount())

        val outlook = NeuralOutlookController(store)
        outlook.runBackfillBatchForTest()
        val firstBackfillCount = tableCount("evidence_bucket")
        outlook.runBackfillBatchForTest()
        assertEquals(firstBackfillCount, tableCount("evidence_bucket"))
        assertTrue(firstBackfillCount > 0)
        outlook.close()
    }

    @Test fun historySearchAggregatesByCallsignAndUsesTheCallTimeIndex() {
        val now = Instant.now().epochSecond
        store.ingest(listOf(
            spot("history-one", "K1ABC", 60, 50, 3).copy(receivedEpoch = now - 3_600, band = "20m", mode = "FT8"),
            spot("history-two", "K1ABC", 65, 55, 4).copy(receivedEpoch = now - 60, spotter = "W3LPL", band = "40m", mode = "CW"),
            spot("history-other", "K1ABD", 55, 45, 2).copy(receivedEpoch = now - 30),
        ))

        val rows = store.searchHistory("K1ABC")
        assertEquals(1, rows.size)
        assertEquals("K1ABC", rows.single().callsign)
        assertEquals(2, rows.single().observations)
        assertEquals(listOf("20m", "40m"), rows.single().bands)
        assertEquals(listOf("CW", "FT8"), rows.single().modes)
        assertEquals(2, rows.single().spotters)

        val plan = store.readableDatabase.rawQuery(
            "EXPLAIN QUERY PLAN SELECT call,COUNT(*) FROM spot WHERE call=? GROUP BY call",
            arrayOf("K1ABC"),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
        assertTrue(plan.any { it.contains("spot_call_ts_idx") })
    }

    @Test fun backfillUnionsExactKeysAcrossBatchBoundaryAndIsIdempotent() {
        val bucket = (Instant.now().epochSecond / 300L) * 300L
        val db = store.writableDatabase
        db.execSQL("UPDATE spot SET ts=?,latitude=?,longitude=? WHERE id='existing'", arrayOf<Any?>(bucket, -12.46, 130.84))
        db.beginTransaction()
        try {
            repeat(1_000) { index ->
                db.execSQL("""INSERT INTO spot(id,ts,call,spotter,frequency_hz,band,mode,country,continent,latitude,longitude,
                    score,watchlisted,comment,dxcc,confidence,samples,reason,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf<Any?>("history-$index", bucket, "VK8${index % 3}", "RX${index % 2}", 14_074_000, "20m", "FT8",
                        "", "", -12.46, 130.84, 45, 0, "", "", 0, 0, "", bucket))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }

        val outlook = NeuralOutlookController(store)
        assertTrue(outlook.runBackfillBatchForTest())
        assertFalse(outlook.runBackfillBatchForTest())
        val facts = db.rawQuery("""SELECT observation_count,unique_call_count,unique_receiver_count,call_keys,receiver_keys
            FROM evidence_bucket WHERE station_key=? AND bucket_start=? AND band='20m' AND mode_family='DIGITAL'""",
            arrayOf(NEURAL_OUTLOOK_SHARED_HISTORY_KEY, bucket.toString())).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getString(3).split(',').size,
                cursor.getString(4).split(',').size)
        }
        assertEquals(listOf(1_001, 4, 3, 4, 3), facts)
        val count = tableCount("evidence_bucket")
        assertFalse(outlook.runBackfillBatchForTest())
        assertEquals(count, tableCount("evidence_bucket"))
        outlook.close()
    }

    @Test fun persistenceDeduplicatesAndCompactionProtectsUnendedPendingRows() {
        val now = Instant.now().epochSecond
        val outlook = NeuralOutlookController(store) { now }
        val base = forecast(now, OutlookWindow.MINUTES_60, "20m")
        outlook.persistForecastForTest(base, "profile|OM0RX|JN88TQ")
        outlook.persistForecastForTest(base.copy(targetStartEpoch = base.targetStartEpoch - 300L), "profile|OM0RX|JN88TQ")
        outlook.persistForecastForTest(base.copy(label = OutlookLabel.INSUFFICIENT_EVIDENCE), "profile|OM0RX|JN88TQ")
        outlook.persistForecastForTest(base.copy(row = 1, column = 1), "profile|OM0RX|JN88TQ")
        assertEquals(1, tableCount("outlook_prediction"))

        listOf(
            forecast(now, OutlookWindow.MINUTES_30, "20m"),
            forecast(now, OutlookWindow.MINUTES_120, "20m"),
            forecast(now, OutlookWindow.MINUTES_60, "40m"),
            forecast(now, OutlookWindow.MINUTES_60, "10m"),
        ).forEach { outlook.persistForecastForTest(it, "profile|OM0RX|JN88TQ") }
        assertEquals(5, tableCount("outlook_prediction"))
        store.writableDatabase.execSQL("""UPDATE outlook_prediction SET verification_state='HIT',verified_epoch=?
            WHERE id IN (SELECT id FROM outlook_prediction ORDER BY window_minutes,band LIMIT 3)""", arrayOf(now))
        outlook.runCompactionForTest(now, hardCap = 2)
        assertEquals(2, tableCount("outlook_prediction"))
        assertEquals(2, store.readableDatabase.rawQuery("""SELECT COUNT(*) FROM outlook_prediction
            WHERE verification_state='PENDING' AND target_end>?""", arrayOf(now.toString())).use { it.moveToFirst(); it.getInt(0) })
        outlook.close()
    }

    @Test fun migratesVersionFourWithoutLosingExistingTablesOrRows() {
        store.close()
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DROP TABLE evidence_bucket")
            db.execSQL("""CREATE TABLE evidence_bucket(
                bucket_start INTEGER NOT NULL, station_key TEXT NOT NULL, station_call TEXT NOT NULL, station_grid TEXT NOT NULL,
                band TEXT NOT NULL, mode_family TEXT NOT NULL, region_row INTEGER NOT NULL, region_col INTEGER NOT NULL,
                source TEXT NOT NULL, observation_count INTEGER NOT NULL, unique_call_count INTEGER NOT NULL,
                unique_receiver_count INTEGER NOT NULL, snr_count INTEGER NOT NULL, snr_sum INTEGER NOT NULL,
                distance_count INTEGER NOT NULL, distance_sum INTEGER NOT NULL, call_keys TEXT NOT NULL, source_state TEXT NOT NULL,
                PRIMARY KEY(station_key,bucket_start,band,mode_family,region_row,region_col,source))""")
            db.execSQL("""INSERT INTO evidence_bucket VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(
                1_000L, "legacy|station", "OM0RX", "JN88TQ", "20m", "DIGITAL", -1, -1, "CLUSTER", 3, 2, 1,
                0, 0, 0, 0, "A,B", "HISTORICAL"))
            db.execSQL("""INSERT INTO outlook_prediction(id,model_version,created_epoch,target_start,target_end,window_minutes,
                station_key,band,mode_family,region_row,region_col,raw_score,label,confidence,calibrated_probability,
                calibration_samples,source_mask,reasons,verification_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("legacy-prediction", NEURAL_OUTLOOK_MODEL_VERSION, 1_000L, 1_800L, 3_600L, 30,
                    "legacy|station", "20m", "DIGITAL", -1, -1, 60, "FAVOURABLE", "MEDIUM", null, 0,
                    "CLUSTER", "legacy", "PENDING"))
            db.execSQL("""INSERT INTO outlook_calibration VALUES(?,?,?,?,?,?,?,?)""", arrayOf<Any?>(
                NEURAL_OUTLOOK_MODEL_VERSION, 30, "HIGH_HF", 6, 2, 1, 0, 1_000L))
            db.version = 4
        }
        store = NeuralDxStore(context, databaseName)
        val db = store.writableDatabase
        assertEquals(5, db.version)
        assertEquals(1, rowCount())
        assertEquals(1, tableCount("evidence_bucket"))
        assertEquals(1, tableCount("outlook_prediction"))
        assertEquals(1, tableCount("outlook_calibration"))
        assertEquals(NEURAL_OUTLOOK_SHARED_HISTORY_KEY, db.rawQuery("SELECT station_key FROM evidence_bucket", null).use {
            it.moveToFirst(); it.getString(0)
        })
        assertEquals("UNVERIFIABLE", db.rawQuery("SELECT verification_state FROM outlook_prediction", null).use {
            it.moveToFirst(); it.getString(0)
        })
        assertTrue(db.rawQuery("PRAGMA table_info(evidence_bucket)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "receiver_keys") found = true
            found
        })
    }

    private fun spot(
        id: String, call: String, score: Int, confidence: Int, samples: Int,
        country: String = "Australia", dxcc: String = "150", continent: String = "OC",
        latitude: Double = -12.46, longitude: Double = 130.84, comment: String = "live comment", reason: String = "Live evidence",
    ) = AndroidDXSpot(
        id, call, "W1AW", 14_074_000, Instant.now().epochSecond, "20m", "FT8", country, continent,
        29, 55, latitude, longitude, comment, score, confidence, samples, false,
        false, false, false, false, false, false, 0, 0, "", reason, dxcc = dxcc,
    )

    private fun forecast(now: Long, window: OutlookWindow, band: String) = OutlookForecast(
        id = "$band-${window.minutes}", window = window, targetStartEpoch = ((now + 899L) / 900L) * 900L,
        targetEndEpoch = now + window.minutes * 60L, band = band, modeFamily = "DIGITAL", row = -1, column = -1,
        supportScore = 60, label = OutlookLabel.FAVOURABLE, confidence = OutlookConfidence.MEDIUM,
        calibratedHitRate = null, calibrationSamples = 0, sourceCount = 1, baselineSamples = 8,
        reasons = listOf("instrumented"), generatedEpoch = now, currentObservations = 2,
        contributingSources = setOf("CLUSTER"),
    )

    private fun tableExists(name: String): Boolean = store.readableDatabase.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name),
    ).use { it.moveToFirst() }

    private fun rowCount(): Int = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM spot", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    private fun tableCount(table: String): Int = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    private fun readText(id: String, column: String): String = store.readableDatabase.rawQuery(
        "SELECT $column FROM spot WHERE id=?", arrayOf(id),
    ).use { it.moveToFirst(); it.getString(0) }

    private fun readLong(id: String, column: String): Long = store.readableDatabase.rawQuery(
        "SELECT $column FROM spot WHERE id=?", arrayOf(id),
    ).use { it.moveToFirst(); it.getLong(0) }

    private fun readDouble(id: String, column: String): Double = store.readableDatabase.rawQuery(
        "SELECT $column FROM spot WHERE id=?", arrayOf(id),
    ).use { it.moveToFirst(); it.getDouble(0) }
}
