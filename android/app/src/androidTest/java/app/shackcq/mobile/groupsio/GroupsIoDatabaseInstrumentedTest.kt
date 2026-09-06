package app.shackcq.mobile.groupsio

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class GroupsIoDatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val featureName = "groupsio-test-${System.nanoTime()}.sqlite"
    private val mainName = "groupsio-main-fixture-${System.nanoTime()}.sqlite"
    private lateinit var database: GroupsIoDatabase

    @Before fun open() { database = GroupsIoDatabase(context, featureName) }
    @After fun close() { database.close(); context.deleteDatabase(featureName); context.deleteDatabase(mainName) }

    @Test fun schemaIsPhysicallyIsolatedAndContainsOnlyFeatureTables() {
        val names = database.writableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue(names.containsAll(setOf("groups", "topics", "messages", "sync_state", "message_search", "message_attachments", "local_drafts", "draft_attachments", "server_drafts", "archive_exports")))
        assertFalse(names.any { it == "qso" || it == "settings" || it == "radio_profile" })
        assertNotEquals(context.getDatabasePath("shackcq.sqlite"), context.getDatabasePath(featureName))
    }

    @Test fun repeatedPagesRemainIdempotentReadableOfflineAndSearchable() {
        val now = System.currentTimeMillis()
        val group = GroupsIoGroup(1001, "field-operators", "Field Operators", "", "sub_status_normal", true, true, now)
        val topic = GroupsIoTopic(2001, 1001, "Portable antennas", now, 1, false, 41, 41)
        val message = GroupsIoMessage(3001, 1001, 2001, 41, null, "Portable antennas", "Synthetic Operator", now, "Try the linked dipole.", false, false, false)
        repeat(2) {
            database.applyMemberships(listOf(group), true, now)
            database.applyTopics(1001, listOf(topic), null, false, now)
            database.applyMessages(1001, 2001, listOf(message), null, false, now)
        }
        assertEquals(1, database.groups().size)
        assertEquals(1, database.topics(1001).size)
        assertEquals(1, database.messages(2001).size)
        assertEquals(41, database.search("linked dipole").single().messageNumber)
    }

    @Test fun oversizedBinaryImportDeletesPartialFile() {
        val partial = context.cacheDir.resolve("groupsio-oversized-${System.nanoTime()}.part")
        assertThrows(GroupsIoApiException::class.java) {
            copyGroupsIoBinary(ByteArrayInputStream(ByteArray(2_049) { 7 }), partial, 2_048)
        }
        assertFalse(partial.exists())
    }

    @Test fun versionOneMigratesTransactionallyWithoutLosingRowsOrFts() {
        database.close(); context.deleteDatabase(featureName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(featureName), null).use { v1 ->
            v1.execSQL("CREATE TABLE groups(group_id INTEGER PRIMARY KEY,name TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL DEFAULT '',parent_group_id INTEGER,membership_status TEXT NOT NULL DEFAULT '',archive_visibility TEXT NOT NULL DEFAULT '',can_read INTEGER NOT NULL DEFAULT 0,can_post INTEGER NOT NULL DEFAULT 0,last_activity INTEGER,active INTEGER NOT NULL DEFAULT 1,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,last_successful_sync INTEGER NOT NULL)")
            v1.execSQL("CREATE TABLE topics(topic_id INTEGER PRIMARY KEY,group_id INTEGER NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,subject TEXT NOT NULL,created INTEGER,updated INTEGER NOT NULL,message_count INTEGER NOT NULL DEFAULT 0,closed INTEGER NOT NULL DEFAULT 0,followed INTEGER,muted INTEGER,first_message_number INTEGER,latest_message_number INTEGER,last_successful_sync INTEGER NOT NULL)")
            v1.execSQL("CREATE TABLE messages(row_id INTEGER PRIMARY KEY AUTOINCREMENT,api_message_id INTEGER,group_id INTEGER NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,topic_id INTEGER NOT NULL REFERENCES topics(topic_id) ON DELETE CASCADE,message_number INTEGER NOT NULL,reply_to_number INTEGER,subject TEXT NOT NULL,author_name TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER,body_plain TEXT NOT NULL,display_body TEXT NOT NULL,moderated INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,has_attachments INTEGER NOT NULL DEFAULT 0,last_successful_sync INTEGER NOT NULL,UNIQUE(group_id,message_number))")
            v1.execSQL("CREATE TABLE sync_state(scope TEXT NOT NULL,scope_id TEXT NOT NULL DEFAULT '',last_attempt INTEGER,last_success INTEGER,current_cursor TEXT,completed_cursor TEXT,last_error_category TEXT,last_error_text TEXT,has_more INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,scope_id))")
            v1.execSQL("CREATE VIRTUAL TABLE message_search USING fts4(group_name,topic_subject,message_subject,author_name,body_plain)")
            v1.execSQL("INSERT INTO groups VALUES(1,'synthetic','Synthetic Group','',NULL,'normal','visible',1,1,NULL,1,1,1,1)")
            v1.execSQL("INSERT INTO topics VALUES(2,1,'Synthetic Topic',1,1,2,0,NULL,NULL,1,2,1)")
            v1.execSQL("INSERT INTO messages(api_message_id,group_id,topic_id,message_number,subject,author_name,created,body_plain,display_body,last_successful_sync) VALUES(11,1,2,1,'Synthetic Topic','Fixture One',1,'migrated searchable phrase','migrated searchable phrase',1)")
            v1.execSQL("INSERT INTO messages(api_message_id,group_id,topic_id,message_number,subject,author_name,created,body_plain,display_body,last_successful_sync) VALUES(12,1,2,2,'Synthetic Topic','Fixture Two',2,'second invented row','second invented row',2)")
            v1.execSQL("INSERT INTO message_search(rowid,group_name,topic_subject,message_subject,author_name,body_plain) SELECT row_id,'Synthetic Group','Synthetic Topic',subject,author_name,body_plain FROM messages")
            v1.execSQL("INSERT INTO sync_state(scope,scope_id,last_success,current_cursor,has_more) VALUES('messages','2',2,'opaque-v1',1)")
            v1.version = 1
        }
        database = GroupsIoDatabase(context, featureName)
        assertEquals(2, database.writableDatabase.version)
        assertEquals(2, database.messages(2).size)
        assertEquals(1, database.search("migrated searchable").size)
        val columns = database.readableDatabase.rawQuery("PRAGMA table_info(local_drafts)", null).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) } }
        assertTrue(columns.containsAll(setOf("local_id", "remote_draft_id", "delivery_unknown")))
        database.close(); database = GroupsIoDatabase(context, featureName)
        assertEquals(2, database.writableDatabase.version)
    }

    @Test fun cacheClearPreservesDraftOutboxAndMainFixture() {
        val now = System.currentTimeMillis()
        database.saveDraft(GroupsIoLocalDraft(groupId = 99, type = GroupsIoDraftType.NEW_TOPIC, subject = "Synthetic unsent", bodyPlain = "Preserve me", state = GroupsIoOutboxState.QUEUED, sendWhenOnline = true, createdAtMillis = now, updatedAtMillis = now))
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(mainName), null).use { it.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)"); it.execSQL("INSERT INTO sentinel VALUES('preserve')") }
        database.clearDownloadedCache()
        assertEquals("Synthetic unsent", database.drafts().single().subject)
        SQLiteDatabase.openDatabase(context.getDatabasePath(mainName).path, null, SQLiteDatabase.OPEN_READONLY).use { main -> assertEquals("preserve", main.rawQuery("SELECT value FROM sentinel", null).use { it.moveToFirst(); it.getString(0) }) }
    }

    @Test fun deleteAllTargetsOnlyGroupsIoDatabaseAndFeatureDirectory() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(mainName), null).use { it.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)") }
        val featureFile = context.filesDir.resolve("GroupsIO/outbox/synthetic/file.txt").apply { parentFile?.mkdirs(); writeText("fixture") }
        assertTrue(featureFile.exists())
        database.deleteAllLocalData()
        assertFalse(context.getDatabasePath(featureName).exists()); assertFalse(context.filesDir.resolve("GroupsIO").exists()); assertTrue(context.getDatabasePath(mainName).exists())
        database = GroupsIoDatabase(context, featureName)
    }
}
