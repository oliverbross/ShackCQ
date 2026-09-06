// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shackcq.mobile.contest.*
import app.shackcq.mobile.dxchaser.DxChaserStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KeyerContestDxChaserInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun serialCommitsOnlyAfterCanonicalMutationAndLink() {
        val qsoName = "integration-qso-${System.nanoTime()}.sqlite"
        val contestName = "integration-contest-${System.nanoTime()}.sqlite"
        val database = QsoDatabase(context, qsoName)
        val store = ContestSessionStore(context, contestName)
        try {
            val (session, definition) = sessionAndDefinition(store)
            val serials = ContestSerialAuthority(store)
            val qsoId = UUID.randomUUID().toString()
            val reservation = serials.reserve(session, ContestKeyerAdapter.reservationOwner(qsoId, 7))
            val receipt = ContestQsoMutationAdapter(QsoMutationCoordinator(database), store, serials)
                .save(session, definition, draft(qsoId, session, definition, reservation.serial), reservation)
            assertTrue(receipt.saved)
            assertTrue(receipt.serialCommitted)
            assertNotNull(database.qso(receipt.qsoId))
            assertEquals(listOf(receipt.qsoId), store.linkedQsoIds(session.id))
            assertEquals(ContestSerialState.COMMITTED, store.reservations(session.id).single().state)
        } finally {
            store.close(); database.close(); context.deleteDatabase(contestName); context.deleteDatabase(qsoName)
        }
    }

    @Test fun rejectedCanonicalMutationReleasesSerialAndDoesNotLink() {
        val qsoName = "integration-qso-fail-${System.nanoTime()}.sqlite"
        val contestName = "integration-contest-fail-${System.nanoTime()}.sqlite"
        val database = QsoDatabase(context, qsoName)
        val store = ContestSessionStore(context, contestName)
        try {
            val (session, definition) = sessionAndDefinition(store)
            val serials = ContestSerialAuthority(store)
            val qsoId = UUID.randomUUID().toString()
            val reservation = serials.reserve(session, ContestKeyerAdapter.reservationOwner(qsoId, 8))
            val invalid = draft(qsoId, session, definition, reservation.serial).copy(frequencyHz = 0)
            val receipt = ContestQsoMutationAdapter(QsoMutationCoordinator(database), store, serials)
                .save(session, definition, invalid, reservation)
            assertFalse(receipt.saved)
            assertNull(database.qso(qsoId))
            assertTrue(store.linkedQsoIds(session.id).isEmpty())
            assertEquals(ContestSerialState.RELEASED, store.reservations(session.id).single().state)
        } finally {
            store.close(); database.close(); context.deleteDatabase(contestName); context.deleteDatabase(qsoName)
        }
    }

    @Test fun configurationIncludesSafeSectionsAndExcludesRuntimeArms() {
        val contest = context.getSharedPreferences("shackcq-contest-settings", Context.MODE_PRIVATE)
        val chaser = context.getSharedPreferences("dxchaser-settings", Context.MODE_PRIVATE)
        contest.edit().clear().putBoolean("n1mm_enabled", true).putBoolean("network_armed", true).commit()
        chaser.edit().clear().putString("document_v1", "{\"version\":1}").putBoolean("active_session", true).commit()
        val text = ConfigurationRecovery(context).export()
        assertTrue(text.contains("contest_n1mm"))
        assertTrue(text.contains("dx_chaser"))
        assertTrue(text.contains("n1mm_enabled"))
        assertFalse(text.contains("network_armed"))
        assertFalse(text.contains("active_session"))
        contest.edit().clear().commit(); chaser.edit().clear().commit()
    }

    @Test fun contestAndChaserDatabasesRemainSeparateAndContainNoQsoTable() {
        val contestName = "integration-isolation-${System.nanoTime()}.sqlite"
        val contest = ContestSessionStore(context, contestName)
        val chaser = DxChaserStore(context)
        try {
            val contestTables = tables(contest.readableDatabase)
            val chaserTables = tables(chaser.readableDatabase)
            assertTrue("contest_session" in contestTables)
            assertTrue("dxchaser_session" in chaserTables)
            assertFalse("qso" in contestTables)
            assertFalse("qso" in chaserTables)
            assertTrue(contestTables.intersect(chaserTables).all { it.startsWith("sqlite_") })
        } finally {
            contest.close(); chaser.close(); context.deleteDatabase(contestName)
        }
    }

    private fun sessionAndDefinition(store: ContestSessionStore): Pair<ContestSession, ContestDefinition> {
        val definition = ContestRuleRegistry().all().first().definition
        val session = ContestSession(ContestSessionId(UUID.randomUUID().toString()), definition.id, definition.version,
            definition.humanName, 1, Long.MAX_VALUE, "OM0RX", "JN88", ContestEntityInfo(),
            ContestCategory(mode = definition.mode), listOf("OM0RX"), state = ContestSessionState.RUNNING)
        store.saveSession(session)
        return session to definition
    }

    private fun draft(id: String, session: ContestSession, definition: ContestDefinition, serial: Int): ContestQsoDraft {
        fun value(field: ContestExchangeField) = when (field) {
            ContestExchangeField.SERIAL -> "1"
            ContestExchangeField.CQ_ZONE -> "14"
            ContestExchangeField.ITU_ZONE -> "28"
            ContestExchangeField.POWER -> "100"
            ContestExchangeField.CLASS -> "1A"
            ContestExchangeField.STATE_PROVINCE, ContestExchangeField.ARRL_SECTION,
            ContestExchangeField.HQ_ABBREVIATION, ContestExchangeField.MEMBER_SOCIETY -> "NT"
            else -> "ABC"
        }
        val received = definition.receivedExchange.filterNot { it == ContestExchangeField.RST }.associateWith(::value)
        val sent = buildMap { if (definition.serialRequired) put(ContestExchangeField.SERIAL, serial.toString()) }
        return ContestQsoDraft(id, "K1ABC", 10, 14_074_000, definition.allowedBands.first(),
            definition.allowedModes.first(), "599", "599", sent, received, station = session.station)
    }

    private fun tables(database: android.database.sqlite.SQLiteDatabase): Set<String> = database.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
}
