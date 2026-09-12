package app.shackcq.mobile

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QsoDatabaseInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: QsoDatabase

    @Before fun openIsolatedDatabase() {
        context.deleteDatabase(databaseName)
        database = QsoDatabase(context, databaseName)
    }

    @After fun closeIsolatedDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun opensDatabaseWithRuntimePragmasConfigured() {
        database.readableDatabase.rawQuery("PRAGMA busy_timeout", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getInt(0) > 0)
        }
        database.readableDatabase.rawQuery("PRAGMA synchronous", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test fun savesCompleteQsoAndRoundTripsAdifWithoutDuplicates() {
        val qso = Qso(
            id = "instrumented-qso", callsign = "VK0TEST", frequencyHz = 14_200_000, mode = "USB",
            rstSent = "59", rstReceived = "57", createdAt = 1_787_000_000, name = "Field Test",
            qth = "Darwin", notes = "Isolated instrumentation record", country = "Australia", band = "20m",
            grid = "PH57", iota = "OC-001", sotaRef = "VK8/TEST", wwffRef = "VKFF-0001", potaRef = "VK-0001",
            comment = "ShackCQ test", frequencyRxHz = 14_205_000, bandRx = "20m", txPowerW = 10,
            operatorCallsign = "VK0TEST", stationCallsign = "VK0TEST", stationProfileId = "7",
            stationLocation = "Portable", myGrid = "PH57", myCountry = "Australia", myDxcc = "150",
            myCqZone = "29", myItuZone = "55", radioModel = "Elecraft KX3", dxcc = "150",
            continent = "OC", region = "Oceania", cqZone = "29", ituZone = "55", state = "NT",
            email = "test@example.invalid", propagationMode = "F2", antennaPath = "S", qslSent = "Q",
            qslMethod = "E", qslVia = "TEST", qslMessage = "73", submode = "USB", county = "DARWIN",
            dok = "A01", contestId = "FIELD-DAY", distanceKm = 550.5, durationSeconds = 420,
            qslReceived = "Y", qslReceivedMethod = "B", lotwSent = "Y", lotwReceived = "Y",
            clublogSent = "Y", clublogReceived = "N", eqslSent = "Y", eqslReceived = "N",
            dclSent = "N", dclReceived = "Y", qrzSent = "Y", qrzReceived = "N", qslImages = "front.jpg",
            syncState = "pending")

        assertTrue(database.save(qso))
        assertFalse(database.save(qso.copy(id = "duplicate")))
        val adif = database.exportADIF()
        listOf("<CALL:7>VK0TEST", "<BAND:3>20m", "<TX_PWR:2>10", "<DXCC:3>150", "<CONT:2>OC",
            "<PROP_MODE:2>F2", "<ANT_PATH:1>S", "<QSL_SENT:1>Q", "<QSLMSG:2>73", "<CNTY:6>DARWIN",
            "<CONTEST_ID:9>FIELD-DAY", "<QSL_RCVD:1>Y", "<LOTW_QSL_SENT:1>Y",
            "<QRZCOM_QSO_UPLOAD_STATUS:1>Y").forEach { assertTrue(it, adif.contains(it)) }
        val imported = database.importADIF(adif)
        assertTrue(imported.first == 0 && imported.second == 1)
    }

    @Test fun derivesAmateurBandsFromLiveFrequency() {
        assertTrue(bandForFrequency(1_830_000) == "160m")
        assertTrue(bandForFrequency(14_200_000) == "20m")
        assertTrue(bandForFrequency(50_100_000) == "6m")
        assertTrue(bandForFrequency(100_000_000).isBlank())
    }

    @Test fun repairsMissingProjectionGridFromCanonicalDetailsJson() {
        val qso = Qso(
            id = "grid-repair", callsign = "VK0TEST", frequencyHz = 14_074_000, mode = "FT8",
            rstSent = "-10", rstReceived = "-12", createdAt = 100, grid = "JN88TQ",
        )
        assertTrue(database.save(qso))
        database.writableDatabase.execSQL(
            "UPDATE qso_projection SET grid_norm='' WHERE qso_id=?",
            arrayOf(qso.id),
        )

        assertEquals(1, database.repairProjectionGridBatch())
        database.readableDatabase.rawQuery(
            "SELECT grid_norm FROM qso_projection WHERE qso_id=?",
            arrayOf(qso.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("JN88TQ", cursor.getString(0))
        }
    }

    @Test fun pagesAndFiltersInsideSqlBeforeReturningRows() {
        val target = Qso(
            id = "target", callsign = "OM0RX", frequencyHz = 14_060_000, mode = "CW", rstSent = "599",
            rstReceived = "579", createdAt = 200, country = "Slovakia", band = "20m", grid = "JN98",
            iota = "EU-001", sotaRef = "OM/ZA-001", wwffRef = "OMFF-0001", potaRef = "OM-0001",
            comment = "Summit contact", notes = "Strong signal", operatorCallsign = "OM0RX", dxcc = "504",
            continent = "EU", state = "ZA", propagationMode = "F2", county = "ZILINA", dok = "A01",
            contestId = "CQ-WW-CW", distanceKm = 1_250.0, durationSeconds = 3_600, qslSent = "Y",
            qslReceived = "Y", qslMethod = "D", qslReceivedMethod = "B", qslVia = "OM0RX",
            lotwSent = "Y", lotwReceived = "Y", clublogSent = "Y", clublogReceived = "Y",
            eqslSent = "Y", eqslReceived = "Y", dclSent = "Y", dclReceived = "Y", qrzSent = "Y",
            qrzReceived = "Y", qslImages = "front.jpg", stationProfileId = "7", syncState = "synced")
        assertTrue(database.save(target))
        repeat(75) { index -> assertTrue(database.save(Qso(
            id = "other-$index", callsign = "VK${index}TEST", frequencyHz = 7_100_000, mode = "SSB",
            rstSent = "59", rstReceived = "59", createdAt = 1_000L + index, country = "Australia",
            band = "40m", stationProfileId = "7", syncState = "synced"))) }
        assertTrue(database.save(target.copy(id = "other-station", callsign = "OM0RX/P", createdAt = 500,
            stationProfileId = "8")))

        val first = database.page(0, 25, stationId = "7")
        val second = database.page(1, 25, stationId = "7")
        assertEquals(76, first.total); assertEquals(4, first.pageCount); assertEquals(25, first.rows.size)
        assertEquals(25, second.rows.size); assertTrue(first.rows.map { it.id }.intersect(second.rows.map { it.id }.toSet()).isEmpty())

        listOf(
            LogbookFilter(callsign = "0rx"), LogbookFilter(dxcc = "504"), LogbookFilter(state = "za"),
            LogbookFilter(grid = "jn98"), LogbookFilter(mode = "CW"), LogbookFilter(band = "20m"),
            LogbookFilter(propagation = "F2"), LogbookFilter(county = "zil"), LogbookFilter(dok = "A01"),
            LogbookFilter(sota = "ZA-001"), LogbookFilter(pota = "OM-0001"), LogbookFilter(iota = "EU-001"),
            LogbookFilter(wwff = "OMFF"), LogbookFilter(operator = "OM0RX"), LogbookFilter(contest = "CQ-WW"),
            LogbookFilter(continent = "EU"), LogbookFilter(notes = "strong signal"),
            LogbookFilter(distance = ">1000"), LogbookFilter(qslSent = "Y"),
            LogbookFilter(qslReceived = "Y"), LogbookFilter(qslSentMethod = "D"),
            LogbookFilter(qslReceivedMethod = "B"), LogbookFilter(lotwSent = "Y"),
            LogbookFilter(lotwReceived = "Y"), LogbookFilter(clublogSent = "Y"),
            LogbookFilter(clublogReceived = "Y"), LogbookFilter(eqslSent = "Y"),
            LogbookFilter(eqslReceived = "Y"), LogbookFilter(dclSent = "Y"),
            LogbookFilter(dclReceived = "Y"), LogbookFilter(qrzSent = "Y"),
            LogbookFilter(qrzReceived = "Y"), LogbookFilter(qslVia = "0rx"), LogbookFilter(qslImages = "Y"),
        ).forEach { filter -> assertEquals(filter.toString(), listOf("target"), database.page(0, 50, filter, "7").rows.map { it.id }) }
    }

    @Test fun spotStatusesAggregateOnlyTheSelectedLogStation() {
        assertTrue(database.save(Qso(id = "confirmed-40", callsign = "K1AAA", frequencyHz = 7_050_000,
            mode = "SSB", submode = "USB", rstSent = "59", rstReceived = "59", createdAt = 100,
            country = "United States", dxcc = "291", band = "40m", qslReceived = "Y",
            stationProfileId = "7", syncState = "synced")))
        assertTrue(database.save(Qso(id = "worked-20", callsign = "VE3BBB", frequencyHz = 14_074_000,
            mode = "MFSK", submode = "FT8", rstSent = "-10", rstReceived = "-12", createdAt = 200,
            country = "Canada", dxcc = "1", band = "20m", eqslReceived = "Y", qrzReceived = "Y",
            stationProfileId = "7", syncState = "synced")))
        assertTrue(database.save(Qso(id = "other-station-call", callsign = "VK8ONLY", frequencyHz = 14_200_000,
            mode = "SSB", rstSent = "59", rstReceived = "59", createdAt = 300, country = "Australia",
            dxcc = "150", band = "20m", qslReceived = "Y", stationProfileId = "8", syncState = "synced")))
        assertTrue(database.save(Qso(id = "local-only", callsign = "ZL1LOCAL", frequencyHz = 14_050_000,
            mode = "CW", rstSent = "599", rstReceived = "599", createdAt = 400, country = "New Zealand",
            dxcc = "170", band = "20m", lotwReceived = "Y", stationProfileId = "", syncState = "local")))

        val spots = listOf(
            SpotLogIdentity("new", "ZL1NEW", "170", "New Zealand", "20m", "CW"),
            SpotLogIdentity("new-band", "K1AAA", "291", "United States", "20m", "SSB"),
            SpotLogIdentity("new-mode", "VE3BBB", "1", "Canada", "20m", "CW"),
            SpotLogIdentity("isolated", "VK8ONLY", "150", "Australia", "20m", "SSB"),
        )
        val result = database.spotStatuses(spots, "7")
        assertEquals(SpotLogStatus("NC", "ATNO"), result["new"])
        assertEquals(SpotLogStatus("NB", "C/NB"), result["new-band"])
        assertEquals(SpotLogStatus("NM", "W"), result["new-mode"])
        assertEquals(SpotLogStatus("NC", "ATNO"), result["isolated"])

        val local = database.spotStatuses(listOf(
            SpotLogIdentity("local", "ZL1LOCAL", "170", "New Zealand", "20m", "CW"),
            SpotLogIdentity("cached-wavelog", "K1AAA", "291", "United States", "40m", "SSB"),
        ))
        assertEquals(SpotLogStatus("C", "C"), local["local"])
        assertEquals(SpotLogStatus("NC", "ATNO"), local["cached-wavelog"])
    }

    @Test fun workedLogUsesAuthorityScopeAndNarrowFallbacks() {
        val before = database.changeToken()
        assertTrue(database.save(Qso(id = "local", callsign = "LOCAL1", frequencyHz = 14_074_000,
            mode = "MFSK", submode = "FT8", rstSent = "-10", rstReceived = "-12", createdAt = 100,
            country = "Stored Local Entity", band = "", stationProfileId = "", syncState = "local")))
        assertTrue(database.save(Qso(id = "station-7", callsign = "REMOTE7", frequencyHz = 7_050_000,
            mode = "SSB", rstSent = "59", rstReceived = "59", createdAt = 200,
            country = "Stored Remote Entity", band = "invalid", stationProfileId = "7", syncState = "synced")))
        assertTrue(database.save(Qso(id = "station-8", callsign = "REMOTE8", frequencyHz = 3_600_000,
            mode = "CW", rstSent = "599", rstReceived = "599", createdAt = 300,
            country = "Other Station", band = "80m", stationProfileId = "8", syncState = "synced")))
        assertTrue(database.changeToken() > before)

        val local = database.workedLog(null) { "" }
        assertEquals(1, local.size)
        assertEquals(WorkedLogQso("LOCAL1", "Stored Local Entity", "20m", "MFSK", "FT8", 100, false), local.single())

        val selected = database.workedLog("7") { call -> if (call == "REMOTE7") "Current CTY Entity" else "" }
        assertEquals(1, selected.size)
        assertEquals(WorkedLogQso("REMOTE7", "Current CTY Entity", "40m", "SSB", "", 200, true), selected.single())
    }

    @Test fun stationInsightScopesHistoryAndConfirmsOnlyPaperQslOrLotw() {
        val base = Qso(id = "local-worked", callsign = "OM0AAO", frequencyHz = 14_200_000, mode = "SSB",
            submode = "USB", rstSent = "59", rstReceived = "57", createdAt = 100, country = "Slovak Republic",
            dxcc = "504", band = "20m", eqslReceived = "Y", qrzReceived = "Y", stationProfileId = "", syncState = "local")
        assertTrue(database.save(base))
        assertTrue(database.save(base.copy(id = "local-confirmed", callsign = "OM1TEST", frequencyHz = 7_030_000,
            mode = "CW", submode = "", band = "40m", createdAt = 200, lotwReceived = "Y")))
        assertTrue(database.save(base.copy(id = "remote-same-call", frequencyHz = 3_700_000, band = "80m",
            createdAt = 300, stationProfileId = "7", qslReceived = "Y", syncState = "synced")))

        val record = AndroidCallbookRecord("OM0AAO", "Viliam Petrik", "Ivanovce", "Slovak Republic", "JN98",
            "504", "EU", "Europe", "15", "28", "", "", "", "", source = "QRZ")
        val local = database.stationInsight(record, null)
        assertEquals(1, local.history.total)
        assertEquals(listOf("local-worked"), local.history.rows.map { it.id })
        assertEquals(DxccCell(true, false), local.dxcc.cells["USB|20m"])
        assertEquals(DxccCell(true, true), local.dxcc.cells["CW|40m"])
        assertEquals(null, local.dxcc.cells["USB|80m"])

        val remote = database.stationInsight(record, "7")
        assertEquals(1, remote.history.total)
        assertEquals(DxccCell(true, true), remote.dxcc.cells["USB|80m"])
    }

    @Test fun stationInsightUsesLogDetailsAndBoundsPreviousContacts() {
        repeat(25) { index ->
            assertTrue(database.save(Qso(
                id = "history-$index", callsign = "OM0AAO", frequencyHz = 14_000_000L + index * 1_000L,
                mode = "CW", rstSent = "599", rstReceived = "579", createdAt = 1_000L + index * 20L,
                name = "Viliam Petrik", qth = "Ivanovce", country = "Slovak Republic", grid = "JN98WT",
                dxcc = "504", continent = "EU", region = "Europe", cqZone = "15", ituZone = "28",
                band = "20m", stationProfileId = "",
            )))
        }
        val seed = AndroidCallbookRecord(
            callsign = "OM0AAO", name = "", qth = "", country = "", grid = "", dxcc = "",
            continent = "", region = "", cqZone = "", ituZone = "", state = "", email = "",
            latitude = "", longitude = "",
        )
        val insight = database.stationInsight(seed, null)
        assertEquals(25, insight.history.total)
        assertEquals(20, insight.history.rows.size)
        assertEquals("history-24", insight.history.rows.first().id)
        assertEquals("Viliam Petrik", insight.record.name)
        assertEquals("Ivanovce", insight.record.qth)
        assertEquals("Slovak Republic", insight.record.country)
        assertEquals("JN98WT", insight.record.grid)
        assertEquals("504", insight.record.dxcc)
    }

    @Test fun bandHistoryUsesCompactStationScopedProjectionAggregate() {
        val now = 1_700_000_000L
        assertTrue(database.save(Qso(id = "history-aggregate-1", callsign = "VK8AAA", frequencyHz = 14_074_000,
            mode = "FT8", rstSent = "-10", rstReceived = "-12", createdAt = now - 86_400,
            band = "20m", stationCallsign = "OM0RX", lotwReceived = "Y")))
        assertTrue(database.save(Qso(id = "history-aggregate-2", callsign = "VK8BBB", frequencyHz = 14_074_000,
            mode = "FT8", rstSent = "-08", rstReceived = "-09", createdAt = now - 7 * 86_400,
            band = "20m", stationCallsign = "OM0RX")))
        val row = LogIntelligenceRepository(database).bandHistory(null, "OM0RX", now)
            .single { it.band == "20M" && it.modeFamily == "DIGITAL" }
        assertEquals(2, row.qsoCount); assertEquals(2, row.uniqueCalls); assertEquals(1, row.confirmedCount)
        assertTrue(row.comparableWindowCount in 0..row.qsoCount)
    }

    @Test fun deliveryRowsRoundTripIndependentlyAndAcceptanceTouchesOnlyMatchingFlag() {
        val qso = Qso(id = "delivery-qso", callsign = "VK8ABC", frequencyHz = 14_200_000, mode = "SSB",
            rstSent = "59", rstReceived = "59", createdAt = 1_700_000_000, stationCallsign = "OM0RX",
            qrzReceived = "Y", clublogReceived = "Y", eqslReceived = "Y")
        assertTrue(database.save(qso))
        assertTrue(database.enqueueDelivery(qso.id, SyncProvider.QRZ))
        assertTrue(database.enqueueDelivery(qso.id, SyncProvider.CLUB_LOG))
        assertFalse(database.enqueueDelivery(qso.id, SyncProvider.QRZ))

        val qrz = database.deliveries(SyncProvider.QRZ).single()
        database.updateDelivery(qrz.copy(state = DeliveryState.ACCEPTED, updatedAt = qrz.updatedAt + 1,
            attemptCount = 1, payloadHash = "hash", remoteId = "123", providerMessage = "Accepted"))
        database.markProviderAccepted(qso.id, SyncProvider.QRZ)

        database.close()
        database = QsoDatabase(context, databaseName)
        val stored = database.deliveries()
        assertEquals(2, stored.size)
        assertEquals(DeliveryState.ACCEPTED, stored.single { it.provider == SyncProvider.QRZ }.state)
        assertEquals(DeliveryState.QUEUED, stored.single { it.provider == SyncProvider.CLUB_LOG }.state)
        val updated = database.qso(qso.id)!!
        assertEquals("Y", updated.qrzSent)
        assertEquals("N", updated.clublogSent)
        assertEquals("N", updated.eqslSent)
        assertEquals("Y", updated.qrzReceived)
        assertEquals("Y", updated.clublogReceived)
        assertEquals("Y", updated.eqslReceived)
    }

    @Test fun editingAcceptedQsoMarksLocalChangedWithoutResending() {
        val qso = Qso(id = "edited-qso", callsign = "VK8EDIT", frequencyHz = 7_100_000, mode = "SSB",
            rstSent = "59", rstReceived = "57", createdAt = 1_700_000_100, stationCallsign = "OM0RX")
        assertTrue(database.save(qso))
        assertTrue(database.enqueueDelivery(qso.id, SyncProvider.QRZ))
        val accepted = database.deliveries().single().copy(state = DeliveryState.ACCEPTED, updatedAt = 2_000,
            attemptCount = 1, payloadHash = "old")
        database.updateDelivery(accepted)

        database.updateLocal(qso.copy(notes = "Corrected locally"))

        assertEquals(DeliveryState.LOCAL_CHANGED, database.deliveries().single().state)
        assertEquals("Corrected locally", database.qso(qso.id)?.notes)
    }

    @Test fun deletingLocalQsoCancelsItsDeliveryWithoutRemoteAction() {
        val qso = Qso(id = "delete-qso", callsign = "VK8DEL", frequencyHz = 7_100_000, mode = "SSB",
            rstSent = "59", rstReceived = "59", createdAt = 1_700_000_200)
        assertTrue(database.save(qso))
        assertTrue(database.enqueueDelivery(qso.id, SyncProvider.CLUB_LOG))
        database.deleteLocal(qso.id)
        assertEquals(null, database.qso(qso.id))
        assertTrue(database.deliveries().isEmpty())
    }

    @Test fun schemaSeventeenReopenPreservesCanonicalQsoAndRepairsProjection() {
        val qso = Qso(
            id = "schema-17-reopen", callsign = "OM0RX", frequencyHz = 14_060_000, mode = "CW",
            rstSent = "599", rstReceived = "579", createdAt = 1_700_000_250,
            grid = "JN88TQ", stationCallsign = "OM0RX", stationProfileId = "7",
        )
        assertTrue(database.save(qso))
        database.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)",
            arrayOf("schema-17-fixture", "preserved"),
        )
        database.writableDatabase.delete("qso_projection", "qso_id=?", arrayOf(qso.id))
        assertEquals(1, database.verifyProjection().canonicalRows)
        assertEquals(0, database.projectionHealth().projectionRows)

        database.close()
        database = QsoDatabase(context, databaseName)

        database.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(17, cursor.getInt(0))
        }
        assertEquals("OM0RX", database.qso(qso.id)?.stationCallsign)
        database.readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key=?",
            arrayOf("schema-17-fixture"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("preserved", cursor.getString(0))
        }
        assertEquals(1, database.repairMissingProjectionRows())
        val health = database.verifyProjection()
        assertEquals(1, health.canonicalRows)
        assertEquals(1, health.projectionRows)
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM qso_projection p JOIN qso q ON q.id=p.qso_id WHERE p.qso_id=?",
            arrayOf(qso.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test fun versionSixMigrationPreservesQsoAndAddsDeliveryTable() {
        database.close()
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { legacy ->
            legacy.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE radio_profile(id TEXT PRIMARY KEY, model TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE qso(id TEXT PRIMARY KEY, callsign TEXT NOT NULL, frequency_hz INTEGER NOT NULL, mode TEXT NOT NULL, rst_sent TEXT NOT NULL, rst_received TEXT NOT NULL, created_at INTEGER NOT NULL, name TEXT NOT NULL DEFAULT '', qth TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', country TEXT NOT NULL DEFAULT '', details_json TEXT NOT NULL DEFAULT '{}')")
            legacy.execSQL("INSERT INTO qso(id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at) VALUES('legacy','VK8OLD',14200000,'SSB','59','59',1700000000)")
            legacy.version = 6
        }
        database = QsoDatabase(context, databaseName)

        assertEquals("VK8OLD", database.qso("legacy")?.callsign)
        assertTrue(database.enqueueDelivery("legacy", SyncProvider.EQSL))
        assertEquals(DeliveryState.QUEUED, database.deliveries().single().state)
    }

    @Test fun nativeWavelogStatePersistsUnknownAdifAndAtomicOutbox() {
        val binding = WavelogBinding(
            baseUrl = "https://example.invalid/index.php", credentialAlias = "wavelog-v2",
            apiGeneration = WavelogApiGeneration.V2,
            capabilities = WavelogCapabilities(setOf("qso:read", "qso:write"), true, true),
            remoteStationId = "11",
            localStationProfileId = DEFAULT_LOCAL_STATION,
        )
        val store = WavelogSyncStore(database)
        store.saveBinding(binding)
        val qso = Qso(id = "native-sync", callsign = "OM0RX", frequencyHz = 14_060_000, mode = "CW",
            rstSent = "599", rstReceived = "599", createdAt = 1_700_000_300,
            extraAdifFields = mapOf("APP_VENDOR_PRIVATE" to "preserved"))

        assertTrue(QsoMutationCoordinator(database, store).save(qso))
        assertEquals(binding.id, store.activeBinding()?.id)
        assertEquals(WavelogOperation.CREATE, store.pending(binding.id).single().operation)
        assertTrue(database.exportADIF().contains("<APP_VENDOR_PRIVATE:9>preserved"))

        database.updateLocal(qso.copy(notes = "Edited without replacing the parent row"))
        assertEquals(1, store.pending(binding.id).size)
        assertEquals("preserved", database.qso(qso.id)?.extraAdifFields?.get("APP_VENDOR_PRIVATE"))

        database.close()
        database = QsoDatabase(context, databaseName)
        assertEquals("preserved", database.qso(qso.id)?.extraAdifFields?.get("APP_VENDOR_PRIVATE"))
        assertEquals(1, WavelogSyncStore(database).pending(binding.id).size)
    }

    companion object { private const val databaseName = "shackcq-instrumented.sqlite" }
}
