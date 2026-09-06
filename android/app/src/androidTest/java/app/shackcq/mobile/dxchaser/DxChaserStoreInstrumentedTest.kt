package app.shackcq.mobile.dxchaser

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DxChaserStoreInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: DxChaserStore

    @Before fun setUp() {
        context.deleteDatabase(DxChaserStore.DATABASE_NAME)
        store = DxChaserStore(context)
    }

    @After fun tearDown() { store.close(); context.deleteDatabase(DxChaserStore.DATABASE_NAME) }

    @Test fun schemaRetentionRarityAndResetStayChaserLocalAndBounded() {
        val active = DxChaserSessionSnapshot("active", DxChaserMode.CHASE_SESSION, DxChaserSessionState.ENGAGED,
            startedEpochSeconds = 1, endsEpochSeconds = 0)
        val completed = DxChaserSessionSnapshot("old", DxChaserMode.DRY_RUN, DxChaserSessionState.STOPPED,
            startedEpochSeconds = 1, endsEpochSeconds = 2)
        store.startSession(active, "station", setOf("20m")); store.startSession(completed, "station", setOf("40m")); store.finishSession(completed)
        val candidate = DxChaserCandidateSnapshot("K1ABC", "K1ABC", "291", "FN31", "20m", "FT8", 14_074_000,
            1_200, "decode", "slot", -10, 1, 1, true, emptyList(), DxChaserPriorityTier.HIGH,
            DxChaserPriorityBreakdown(value = 50, localQuality = 20, currentObservedSupport = 0,
                futureEmpiricalSupport = 0, historicalPersonalValue = 0, rarity = 0, penalties = 0,
                total = 70, reasons = listOf("CALLING_CQ"), penaltyReasons = emptyList()),
            false, false, false, emptyList(), "NO CURRENT CORROBORATION", "OUTLOOK UNAVAILABLE", DxChaserRarity("291"))
        store.recordAttempt("active-attempt", "active", 1, candidate, "ENGAGED", engaged = true)
        store.recordAttempt("old-attempt", "old", 1, candidate, "DONE")
        store.upsertCooldown(DxChaserCooldownSnapshot("K1ABC", "20m", "FT8", "ACTIVE", 20_000_000))
        val rarity = DxChaserRarityImport("operator", "2026-08-01", "digest",
            listOf(DxChaserRarity("291", rank = 10, origin = DxChaserRarityOrigin.USER_IMPORTED)))
        store.replaceRarity(rarity, 10)
        store.compact(10_000_000, DxChaserSettingsDocument(attemptRetentionDays = 1, sessionRetentionDays = 7))
        val counts = store.counts()
        assertEquals(1L, counts["dxchaser_attempt"])
        assertEquals(1L, counts["dxchaser_session"])
        assertEquals(1, store.activeCooldowns(10_000_000).size)
        assertEquals(10, store.rarity()["291"]?.rank)
        assertTrue((counts["database_bytes"] ?: 0) > 0)
        store.resetChaserOnly()
        assertEquals(0L, store.counts()["dxchaser_attempt"])
        assertTrue(store.rarity().isEmpty())
    }
}
