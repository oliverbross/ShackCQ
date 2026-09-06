package app.shackcq.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class NexusDigiSessionStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: DigiSessionStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("shackcq-digi.sqlite")
        File(context.filesDir, "digi/sstv").deleteRecursively()
        store = DigiSessionStore(context)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase("shackcq-digi.sqlite")
        File(context.filesDir, "digi/sstv").deleteRecursively()
    }

    @Test fun decodeHistoryIsSeparateAndHardCapped() {
        val now = System.currentTimeMillis() / 1_000
        val rows = (0 until 1_005).map { index ->
            event("id-$index", now + index, 15_000L * index)
        }
        store.appendDecodes(rows, hardCap = 1_000)
        assertEquals(1_000, store.recentDecodes(3_000).size)
    }

    @Test fun expiredDecodeRowsAreRemoved() {
        val now = System.currentTimeMillis() / 1_000
        store.appendDecodes(listOf(
            event("old", now - 3 * 86_400, 7_500L),
            event("new", now, 22_500L),
        ), retentionDays = 1)
        assertEquals(listOf("new"), store.recentDecodes().map { it.id })
    }

    @Test fun ft4HalfSecondSlotsRoundTripExactly() {
        val now = System.currentTimeMillis() / 1_000
        store.appendDecodes(listOf(event("a", now, 7_500L), event("b", now + 1, 22_500L)))
        val rows = store.recentDecodes()
        assertEquals(listOf(7_500L, 22_500L), rows.map { it.slotStartMillis })
        assertEquals(listOf(1, 1), rows.map { slotParity(it.slotStartMillis, 7_500L) })
        assertTrue(rows.all { it.decodeSource == DigiDecodeSource.LIVE_CAPTURE && it.exactSlotTiming })
    }

    @Test fun schemaOneMigrationPreservesRowsButMarksTimingIneligible() {
        store.close()
        context.deleteDatabase("shackcq-digi.sqlite")
        val db = context.openOrCreateDatabase("shackcq-digi.sqlite", Context.MODE_PRIVATE, null)
        db.execSQL("""CREATE TABLE decode_event(
            id TEXT PRIMARY KEY,session_id TEXT NOT NULL,epoch INTEGER NOT NULL,mode TEXT NOT NULL,
            period_start_epoch INTEGER NOT NULL,snr REAL NOT NULL,dt REAL NOT NULL,audio_hz REAL NOT NULL,
            text TEXT NOT NULL,callsign TEXT NOT NULL,grid TEXT NOT NULL,country TEXT NOT NULL,
            continent TEXT NOT NULL,distance_km REAL NOT NULL,bearing_degrees REAL NOT NULL,
            worked INTEGER NOT NULL,confirmed INTEGER NOT NULL,needs_json TEXT NOT NULL,watchlisted INTEGER NOT NULL)""")
        db.execSQL("INSERT INTO decode_event VALUES('legacy','old-session',100,'FT4',7,-10,0.1,1000,'CQ K1ABC FN31','K1ABC','FN31','','',0,0,0,0,'[]',0)")
        db.version = 1
        db.close()
        store = DigiSessionStore(context)
        val migrated = store.recentDecodes().single()
        assertEquals(7_000L, migrated.slotStartMillis)
        assertEquals(DigiDecodeSource.LIVE_CAPTURE, migrated.decodeSource)
        assertFalse(migrated.exactSlotTiming)
        assertFalse(migrated.automaticFtEligible("FT4", 14_080_000, "old-session", 7_000L))
    }

    @Test fun galleryUsesAtomicPrivatePngAndMetadata() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val item = store.saveSstvPng(bitmap, "Martin 1", 100, 14_230_000, "Home", "F1ABC", "source.jpg", 25)
        assertTrue(File(item.path).isFile)
        assertTrue(item.path.startsWith(context.filesDir.absolutePath))
        assertFalse(File(item.path + ".tmp").exists())
        assertEquals("F1ABC", store.gallery().single().fskId)
    }

    @Test fun pinnedGalleryItemSurvivesExplicitQuotaSweep() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val item = store.saveSstvPng(bitmap, "Scottie 1", 100, 14_230_000, "Home", "", "", 25)
        store.updateGallery(item.id, caption = "keeper", pinned = true)
        store.enforceGalleryQuota(0)
        assertEquals("keeper", store.gallery().single().caption)
    }

    private fun event(id: String, epoch: Long, slotStartMillis: Long) = DigiDecodeEvent(
        id = id, sessionId = "session", epoch = epoch, mode = "FT4", slotStartMillis = slotStartMillis,
        decodeSource = DigiDecodeSource.LIVE_CAPTURE, exactSlotTiming = true, dialFrequencyHz = 14_080_000,
        snr = -10f, dt = .1f, audioHz = 1_000f, text = "CQ K1ABC FN31", callsign = "K1ABC",
    )
}
