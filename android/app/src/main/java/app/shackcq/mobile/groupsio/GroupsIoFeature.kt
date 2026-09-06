package app.shackcq.mobile.groupsio

import app.shackcq.mobile.BuildConfig

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val GROUPS_IO_DATABASE_NAME = "shackcq-groupsio.sqlite"

data class GroupsIoGroup(
    val id: Long,
    val name: String,
    val title: String,
    val summary: String,
    val status: String,
    val archivesVisible: Boolean,
    val active: Boolean,
    val lastSyncMillis: Long,
    val canPost: Boolean = false,
    val canReply: Boolean = false,
    val downloadArchives: Boolean = false,
)

data class GroupsIoTopic(
    val id: Long,
    val groupId: Long,
    val subject: String,
    val updatedMillis: Long,
    val messageCount: Int,
    val closed: Boolean,
    val firstMessageNumber: Long?,
    val latestMessageNumber: Long?,
)

data class GroupsIoMessage(
    val apiId: Long?,
    val groupId: Long,
    val topicId: Long,
    val number: Long,
    val replyToNumber: Long?,
    val subject: String,
    val author: String,
    val createdMillis: Long,
    val body: String,
    val moderated: Boolean,
    val deleted: Boolean,
    val hasAttachments: Boolean,
)

data class GroupsIoSearchResult(
    val groupId: Long,
    val topicId: Long,
    val messageNumber: Long,
    val groupName: String,
    val topicSubject: String,
    val author: String,
    val createdMillis: Long,
    val snippet: String,
)

data class GroupsIoCacheStats(val topics: Int = 0, val messages: Int = 0, val downloadedAttachments: Int = 0)

data class GroupsIoHomeSummary(
    val recent: List<GroupsIoSearchResult> = emptyList(),
    val needsAttention: Int = 0,
    val archiveState: String = "not_started",
    val archiveDownloaded: Int = 0,
    val lastRefreshMillis: Long = 0,
    val offline: Boolean = true,
)

internal data class GroupsIoTimestampText(val row: String, val detail: String, val accessibility: String)

internal fun groupsIoTimestampText(
    epochMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): GroupsIoTimestampText {
    if (epochMillis <= 0) return GroupsIoTimestampText("TIME UNKNOWN", "TIME UNKNOWN", "Time unknown")
    val instant = Instant.ofEpochMilli(epochMillis)
    val local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(instant.atZone(zone))
    val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").format(instant.atZone(ZoneOffset.UTC))
    val ageSeconds = ((nowMillis - epochMillis) / 1_000L).coerceAtLeast(0)
    val relative = when {
        ageSeconds < 60 -> "just now"
        ageSeconds < 3_600 -> "${ageSeconds / 60}m ago"
        ageSeconds < 86_400 -> "${ageSeconds / 3_600}h ago"
        ageSeconds < 7 * 86_400 -> "${ageSeconds / 86_400}d ago"
        else -> ""
    }
    val row = if (relative.isBlank()) local else "$local · $relative"
    return GroupsIoTimestampText(row, "$local local · $utc", "$local local time; $utc")
}

internal data class GroupsIoPage<T>(val values: List<T>, val nextPageToken: String?, val hasMore: Boolean)
internal fun groupsIoDestinationVisible(enabled: Boolean, compact: Boolean): Boolean = enabled && !compact
internal fun groupsIoPagination(root: JSONObject): Pair<String?, Boolean> {
    val hasMore = root.optBoolean("has_more", false)
    val next = root.opt("next_page_token")?.toString()?.takeUnless { it == "null" || it == "0" || it.isBlank() }
    if (hasMore && next == null) throw GroupsIoApiException("compatibility", "Groups.io pagination omitted the next page token")
    return next to hasMore
}

internal class GroupsIoDatabase(private val appContext: Context, private val databaseName: String = GROUPS_IO_DATABASE_NAME) :
    SQLiteOpenHelper(appContext, databaseName, null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE groups(
            group_id INTEGER PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '',
            parent_group_id INTEGER, membership_status TEXT NOT NULL DEFAULT '', archive_visibility TEXT NOT NULL DEFAULT '',
            can_read INTEGER NOT NULL DEFAULT 0, can_post INTEGER NOT NULL DEFAULT 0, last_activity INTEGER,
            active INTEGER NOT NULL DEFAULT 1, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
            last_successful_sync INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE topics(
            topic_id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
            subject TEXT NOT NULL, created INTEGER, updated INTEGER NOT NULL, message_count INTEGER NOT NULL DEFAULT 0,
            closed INTEGER NOT NULL DEFAULT 0, followed INTEGER, muted INTEGER, first_message_number INTEGER,
            latest_message_number INTEGER, last_successful_sync INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE messages(
            row_id INTEGER PRIMARY KEY AUTOINCREMENT, api_message_id INTEGER, group_id INTEGER NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
            topic_id INTEGER NOT NULL REFERENCES topics(topic_id) ON DELETE CASCADE, message_number INTEGER NOT NULL,
            reply_to_number INTEGER, subject TEXT NOT NULL, author_name TEXT NOT NULL, created INTEGER NOT NULL,
            updated INTEGER, body_plain TEXT NOT NULL, display_body TEXT NOT NULL, moderated INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0, has_attachments INTEGER NOT NULL DEFAULT 0, last_successful_sync INTEGER NOT NULL,
            UNIQUE(group_id, message_number))""")
        db.execSQL("""CREATE TABLE sync_state(
            scope TEXT NOT NULL, scope_id TEXT NOT NULL DEFAULT '', last_attempt INTEGER, last_success INTEGER,
            current_cursor TEXT, completed_cursor TEXT, last_error_category TEXT, last_error_text TEXT,
            has_more INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope, scope_id))""")
        db.execSQL("CREATE INDEX topics_group_latest ON topics(group_id, updated DESC)")
        db.execSQL("CREATE INDEX messages_topic_number ON messages(topic_id, message_number)")
        db.execSQL("CREATE INDEX messages_group_created ON messages(group_id, created DESC)")
        db.execSQL("CREATE INDEX sync_state_scope ON sync_state(scope, scope_id)")
        // Android's framework SQLite is not guaranteed to include FTS5. FTS4 is available on
        // every Android version supported by ShackCQ and keeps local archive search indexed.
        db.execSQL("CREATE VIRTUAL TABLE message_search USING fts4(group_name, topic_subject, message_subject, author_name, body_plain)")
        createPhase2Schema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion > 2 || newVersion > 2) throw GroupsIoApiException("compatibility", "Groups.io cache schema is newer than this app")
        if (oldVersion == 1 && newVersion == 2) db.transaction {
            execSQL("ALTER TABLE groups ADD COLUMN can_reply INTEGER NOT NULL DEFAULT 0")
            execSQL("ALTER TABLE groups ADD COLUMN download_archives INTEGER NOT NULL DEFAULT 0")
            execSQL("ALTER TABLE groups ADD COLUMN post_status TEXT NOT NULL DEFAULT ''")
            execSQL("ALTER TABLE groups ADD COLUMN max_attachment_size INTEGER")
            execSQL("ALTER TABLE groups ADD COLUMN default_reply_policy TEXT")
            execSQL("ALTER TABLE groups ADD COLUMN permissions_synced_at INTEGER")
            execSQL("ALTER TABLE messages ADD COLUMN reply_policy TEXT")
            execSQL("ALTER TABLE messages ADD COLUMN quoted_plain TEXT")
            execSQL("ALTER TABLE messages ADD COLUMN remainder_plain TEXT")
            execSQL("ALTER TABLE messages ADD COLUMN attachments_synced_at INTEGER")
            createPhase2Schema(this)
        }
    }

    private fun createPhase2Schema(db: SQLiteDatabase) {
        fun addColumn(table: String, definition: String) {
            val name = definition.substringBefore(' ')
            val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(1) == name) found = true
                found
            }
            if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $definition")
        }
        addColumn("groups", "can_reply INTEGER NOT NULL DEFAULT 0")
        addColumn("groups", "download_archives INTEGER NOT NULL DEFAULT 0")
        addColumn("groups", "post_status TEXT NOT NULL DEFAULT ''")
        addColumn("groups", "max_attachment_size INTEGER")
        addColumn("groups", "default_reply_policy TEXT")
        addColumn("groups", "permissions_synced_at INTEGER")
        addColumn("messages", "reply_policy TEXT")
        addColumn("messages", "quoted_plain TEXT")
        addColumn("messages", "remainder_plain TEXT")
        addColumn("messages", "attachments_synced_at INTEGER")
        db.execSQL("""CREATE TABLE IF NOT EXISTS message_attachments(
            group_id INTEGER NOT NULL, message_number INTEGER NOT NULL, attachment_id INTEGER NOT NULL,
            filename TEXT NOT NULL, media_type TEXT NOT NULL DEFAULT '', reported_size INTEGER,
            local_relative_path TEXT, local_size INTEGER, sha256 TEXT, download_state TEXT NOT NULL DEFAULT 'remote',
            downloaded_at INTEGER, last_error TEXT, PRIMARY KEY(group_id,message_number,attachment_id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS local_drafts(
            local_id TEXT PRIMARY KEY, group_id INTEGER NOT NULL, topic_id INTEGER, reply_message_number INTEGER,
            reply_api_message_id INTEGER, remote_draft_id INTEGER, draft_type TEXT NOT NULL,
            subject TEXT NOT NULL DEFAULT '', body_plain TEXT NOT NULL DEFAULT '', reply_destination TEXT,
            state TEXT NOT NULL DEFAULT 'draft_local', send_when_online INTEGER NOT NULL DEFAULT 0,
            pending_moderation INTEGER NOT NULL DEFAULT 0, delivery_unknown INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_attempt_at INTEGER,
            last_error_category TEXT, last_error_text TEXT)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS draft_attachments(
            local_id TEXT PRIMARY KEY, draft_local_id TEXT NOT NULL REFERENCES local_drafts(local_id) ON DELETE CASCADE,
            remote_attachment_id INTEGER, filename TEXT NOT NULL, media_type TEXT NOT NULL DEFAULT '', byte_size INTEGER NOT NULL,
            local_relative_path TEXT NOT NULL, sha256 TEXT NOT NULL, upload_state TEXT NOT NULL DEFAULT 'queued', last_error TEXT)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS server_drafts(
            remote_draft_id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL, draft_type TEXT NOT NULL, message_id INTEGER,
            subject TEXT NOT NULL DEFAULT '', body_plain TEXT NOT NULL DEFAULT '', attachment_count INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER, updated_at INTEGER, synced_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS archive_exports(
            group_id INTEGER NOT NULL, relative_path TEXT NOT NULL, requested_at INTEGER NOT NULL, completed_at INTEGER,
            start_message_number INTEGER, byte_size INTEGER, sha256 TEXT, state TEXT NOT NULL, last_error TEXT,
            PRIMARY KEY(group_id,relative_path))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS local_drafts_state_updated ON local_drafts(state,updated_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS local_drafts_group ON local_drafts(group_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS draft_attachments_draft ON draft_attachments(draft_local_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS message_attachments_message ON message_attachments(group_id,message_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS archive_exports_group ON archive_exports(group_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS server_drafts_group_updated ON server_drafts(group_id,updated_at DESC)")
    }

    fun applyMemberships(values: List<GroupsIoGroup>, completed: Boolean, syncedAt: Long) = writableDatabase.transaction {
        values.forEach { group ->
            val row = ContentValues().apply {
                put("group_id", group.id); put("name", group.name); put("title", group.title); put("summary", group.summary)
                put("membership_status", group.status); put("archive_visibility", if (group.archivesVisible) "visible" else "restricted")
                put("can_read", group.archivesVisible); put("can_post", group.canPost); put("can_reply", group.canReply); put("download_archives", group.downloadArchives)
                put("active", true); put("last_seen", syncedAt); put("last_successful_sync", syncedAt)
                put("first_seen", syncedAt)
            }
            insertWithOnConflict("groups", null, row, SQLiteDatabase.CONFLICT_IGNORE)
            row.remove("first_seen")
            update("groups", row, "group_id=?", arrayOf(group.id.toString()))
            refreshSearchForGroup(this, group.id)
        }
        if (completed) {
            val ids = values.map { it.id }.toSet()
            rawQuery("SELECT group_id FROM groups WHERE active=1", null).use { cursor ->
                while (cursor.moveToNext()) if (cursor.getLong(0) !in ids) {
                    execSQL("UPDATE groups SET active=0 WHERE group_id=?", arrayOf(cursor.getLong(0)))
                }
            }
        }
        recordSuccess(this, "memberships", "", syncedAt, false, null)
    }

    fun applyTopics(groupId: Long, values: List<GroupsIoTopic>, next: String?, hasMore: Boolean, syncedAt: Long) = writableDatabase.transaction {
        values.forEach { topic ->
            val row = ContentValues().apply {
                put("topic_id", topic.id); put("group_id", groupId); put("subject", topic.subject)
                put("updated", topic.updatedMillis); put("message_count", topic.messageCount); put("closed", topic.closed)
                topic.firstMessageNumber?.let { put("first_message_number", it) }
                topic.latestMessageNumber?.let { put("latest_message_number", it) }
                put("last_successful_sync", syncedAt)
            }
            if (insertWithOnConflict("topics", null, row, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                update("topics", row, "topic_id=?", arrayOf(topic.id.toString()))
            }
            refreshSearchForTopic(this, topic.id)
        }
        recordSuccess(this, "topics", groupId.toString(), syncedAt, hasMore, next)
    }

    fun applyMessages(groupId: Long, topicId: Long, values: List<GroupsIoMessage>, next: String?, hasMore: Boolean, syncedAt: Long) = writableDatabase.transaction {
        values.forEach { message ->
            val existing = rawQuery("SELECT row_id FROM messages WHERE group_id=? AND message_number=?", arrayOf(groupId.toString(), message.number.toString()))
                .use { if (it.moveToFirst()) it.getLong(0) else null }
            val row = ContentValues().apply {
                message.apiId?.let { put("api_message_id", it) }; put("group_id", groupId); put("topic_id", topicId)
                put("message_number", message.number); message.replyToNumber?.let { put("reply_to_number", it) }
                put("subject", message.subject); put("author_name", message.author); put("created", message.createdMillis)
                put("body_plain", message.body); put("display_body", message.body); put("moderated", message.moderated)
                put("deleted", message.deleted); put("has_attachments", message.hasAttachments); put("last_successful_sync", syncedAt)
            }
            val rowId = if (existing == null) insertOrThrow("messages", null, row) else { update("messages", row, "row_id=?", arrayOf(existing.toString())); existing }
            refreshSearchRow(this, rowId)
        }
        recordSuccess(this, "messages", topicId.toString(), syncedAt, hasMore, next)
    }

    fun groups(limit: Int = 100): List<GroupsIoGroup> = readableDatabase.rawQuery(
        "SELECT group_id,name,title,summary,membership_status,can_read,active,last_successful_sync FROM groups ORDER BY active DESC,title COLLATE NOCASE LIMIT ?",
        arrayOf(limit.coerceIn(1, 100).toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoGroup(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getInt(5) != 0, cursor.getInt(6) != 0, cursor.getLong(7))) } }

    fun topics(groupId: Long, limit: Int = 50, offset: Int = 0): List<GroupsIoTopic> = readableDatabase.rawQuery(
        "SELECT topic_id,group_id,subject,updated,message_count,closed,first_message_number,latest_message_number FROM topics WHERE group_id=? ORDER BY updated DESC LIMIT ? OFFSET ?",
        arrayOf(groupId.toString(), limit.coerceIn(1, 100).toString(), offset.coerceAtLeast(0).toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoTopic(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4), cursor.getInt(5) != 0, cursor.longOrNull(6), cursor.longOrNull(7))) } }

    fun messages(topicId: Long, limit: Int = 100, offset: Int = 0): List<GroupsIoMessage> = readableDatabase.rawQuery(
        "SELECT api_message_id,group_id,topic_id,message_number,reply_to_number,subject,author_name,created,body_plain,moderated,deleted,has_attachments FROM messages WHERE topic_id=? ORDER BY message_number LIMIT ? OFFSET ?",
        arrayOf(topicId.toString(), limit.coerceIn(1, 100).toString(), offset.coerceAtLeast(0).toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoMessage(cursor.longOrNull(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), cursor.longOrNull(4), cursor.getString(5), cursor.getString(6), cursor.getLong(7), cursor.getString(8), cursor.getInt(9) != 0, cursor.getInt(10) != 0, cursor.getInt(11) != 0)) } }

    fun recentMessages(limit: Int = 5): List<GroupsIoSearchResult> = readableDatabase.rawQuery(
        """SELECT m.group_id,m.topic_id,m.message_number,g.title,t.subject,m.author_name,m.created,
            substr(m.body_plain,1,180) FROM messages m JOIN groups g ON g.group_id=m.group_id
            JOIN topics t ON t.topic_id=m.topic_id WHERE m.deleted=0 ORDER BY m.created DESC LIMIT ?""",
        arrayOf(limit.coerceIn(1, 20).toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoSearchResult(cursor.getLong(0), cursor.getLong(1),
        cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6), cursor.getString(7))) } }

    fun search(query: String, groupId: Long? = null, topicId: Long? = null, limit: Int = 40): List<GroupsIoSearchResult> {
        val match = Regex("[\\p{L}\\p{N}_]+").findAll(query).map { "\"${it.value}\"" }.joinToString(" AND ")
        if (match.isBlank()) return emptyList()
        val where = buildString { append("message_search MATCH ?"); if (groupId != null) append(" AND m.group_id=?"); if (topicId != null) append(" AND m.topic_id=?") }
        val args = mutableListOf(match); groupId?.let { args += it.toString() }; topicId?.let { args += it.toString() }; args += limit.coerceIn(1, 100).toString()
        val indexed = readableDatabase.rawQuery("""SELECT m.group_id,m.topic_id,m.message_number,g.title,t.subject,m.author_name,m.created,
            snippet(message_search,'[',']',' … ',4,18) FROM message_search JOIN messages m ON m.row_id=message_search.rowid
            JOIN groups g ON g.group_id=m.group_id JOIN topics t ON t.topic_id=m.topic_id WHERE $where ORDER BY m.created DESC LIMIT ?""", args.toTypedArray())
            .use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoSearchResult(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6), cursor.getString(7))) } }
        if (indexed.isNotEmpty()) return indexed
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val fallbackWhere = buildString {
            append("(g.title || ' ' || t.subject || ' ' || m.subject || ' ' || m.author_name || ' ' || m.body_plain) LIKE ? ESCAPE '\\' COLLATE NOCASE")
            if (groupId != null) append(" AND m.group_id=?")
            if (topicId != null) append(" AND m.topic_id=?")
        }
        val fallbackArgs = mutableListOf("%$escaped%")
        groupId?.let { fallbackArgs += it.toString() }; topicId?.let { fallbackArgs += it.toString() }
        fallbackArgs += limit.coerceIn(1, 100).toString()
        return readableDatabase.rawQuery("""SELECT m.group_id,m.topic_id,m.message_number,g.title,t.subject,m.author_name,m.created,
            substr(m.body_plain,1,180) FROM messages m JOIN groups g ON g.group_id=m.group_id
            JOIN topics t ON t.topic_id=m.topic_id WHERE $fallbackWhere ORDER BY m.created DESC LIMIT ?""", fallbackArgs.toTypedArray())
            .use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoSearchResult(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6), cursor.getString(7))) } }
    }

    fun sizeBytes(): Long = listOf("", "-wal", "-shm").sumOf { suffix -> appContext.getDatabasePath(databaseName + suffix).takeIf { it.isFile }?.length() ?: 0L }

    fun cacheStats(): GroupsIoCacheStats = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM topics),(SELECT COUNT(*) FROM messages),(SELECT COUNT(*) FROM message_attachments WHERE download_state='downloaded')", null
    ).use { cursor -> cursor.moveToFirst(); GroupsIoCacheStats(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)) }

    fun capabilities(groupId: Long): GroupsIoCapabilities? = readableDatabase.rawQuery(
        "SELECT can_read,can_post,can_reply,download_archives,post_status,max_attachment_size,default_reply_policy,permissions_synced_at FROM groups WHERE group_id=?",
        arrayOf(groupId.toString())
    ).use { cursor -> if (!cursor.moveToFirst()) null else GroupsIoCapabilities(
        archivesVisible = cursor.getInt(0) != 0, canPost = cursor.getInt(1) != 0, canReply = cursor.getInt(2) != 0,
        downloadArchives = cursor.getInt(3) != 0, postStatus = cursor.getString(4), maxAttachmentSize = cursor.longOrNull(5),
        defaultReplyPolicy = cursor.getString(6), syncedAtMillis = cursor.longOrNull(7)
    ) }

    fun updateCapabilities(groupId: Long, value: GroupsIoCapabilities) = writableDatabase.transaction {
        val row = ContentValues().apply {
            put("can_read", value.archivesVisible); put("can_post", value.canPost); put("can_reply", value.canReply)
            put("download_archives", value.downloadArchives); put("post_status", value.postStatus)
            value.maxAttachmentSize?.let { put("max_attachment_size", it) } ?: putNull("max_attachment_size")
            value.defaultReplyPolicy?.let { put("default_reply_policy", it) } ?: putNull("default_reply_policy")
            value.syncedAtMillis?.let { put("permissions_synced_at", it) } ?: putNull("permissions_synced_at")
        }
        update("groups", row, "group_id=?", arrayOf(groupId.toString()))
    }

    fun saveDraft(draft: GroupsIoLocalDraft) = writableDatabase.transaction {
        val row = ContentValues().apply {
            put("local_id", draft.localId); put("group_id", draft.groupId); draft.topicId?.let { put("topic_id", it) } ?: putNull("topic_id")
            draft.replyMessageNumber?.let { put("reply_message_number", it) } ?: putNull("reply_message_number")
            draft.replyApiMessageId?.let { put("reply_api_message_id", it) } ?: putNull("reply_api_message_id")
            draft.remoteDraftId?.let { put("remote_draft_id", it) } ?: putNull("remote_draft_id")
            put("draft_type", draft.type.wire); put("subject", draft.subject); put("body_plain", draft.bodyPlain)
            draft.replyDestination?.let { put("reply_destination", it.wire) } ?: putNull("reply_destination")
            put("state", draft.state.wire); put("send_when_online", draft.sendWhenOnline)
            put("pending_moderation", draft.pendingModeration); put("delivery_unknown", draft.deliveryUnknown)
            put("created_at", draft.createdAtMillis); put("updated_at", draft.updatedAtMillis)
            draft.lastAttemptAtMillis?.let { put("last_attempt_at", it) } ?: putNull("last_attempt_at")
            draft.lastErrorCategory?.let { put("last_error_category", it) } ?: putNull("last_error_category")
            draft.lastErrorText?.let { put("last_error_text", it.take(160)) } ?: putNull("last_error_text")
        }
        if (insertWithOnConflict("local_drafts", null, row, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            update("local_drafts", row, "local_id=?", arrayOf(draft.localId))
        }
    }

    fun drafts(limit: Int = 100): List<GroupsIoLocalDraft> = readableDatabase.rawQuery(
        "SELECT local_id,group_id,topic_id,reply_message_number,reply_api_message_id,remote_draft_id,draft_type,subject,body_plain,reply_destination,state,send_when_online,pending_moderation,delivery_unknown,created_at,updated_at,last_attempt_at,last_error_category,last_error_text FROM local_drafts ORDER BY updated_at DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 100).toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoLocalDraft(
        localId = cursor.getString(0), groupId = cursor.getLong(1), topicId = cursor.longOrNull(2),
        replyMessageNumber = cursor.longOrNull(3), replyApiMessageId = cursor.longOrNull(4), remoteDraftId = cursor.longOrNull(5),
        type = GroupsIoDraftType.fromWire(cursor.getString(6)), subject = cursor.getString(7), bodyPlain = cursor.getString(8),
        replyDestination = cursor.getString(9)?.takeIf(String::isNotBlank)?.let(GroupsIoReplyDestination::fromWire),
        state = GroupsIoOutboxState.fromWire(cursor.getString(10)), sendWhenOnline = cursor.getInt(11) != 0,
        pendingModeration = cursor.getInt(12) != 0, deliveryUnknown = cursor.getInt(13) != 0,
        createdAtMillis = cursor.getLong(14), updatedAtMillis = cursor.getLong(15), lastAttemptAtMillis = cursor.longOrNull(16),
        lastErrorCategory = cursor.getString(17), lastErrorText = cursor.getString(18)
    )) } }

    fun deleteDraft(localId: String) { writableDatabase.delete("local_drafts", "local_id=?", arrayOf(localId)) }
    fun unsentDraftCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM local_drafts WHERE state NOT IN ('posted','pending_moderation')", null).use { it.moveToFirst(); it.getInt(0) }

    fun saveDraftAttachment(value: GroupsIoDraftAttachment) {
        val row = ContentValues().apply {
            put("local_id", value.localId); put("draft_local_id", value.draftLocalId)
            value.remoteAttachmentId?.let { put("remote_attachment_id", it) } ?: putNull("remote_attachment_id")
            put("filename", value.filename); put("media_type", value.mediaType); put("byte_size", value.byteSize)
            put("local_relative_path", value.localRelativePath); put("sha256", value.sha256); put("upload_state", value.uploadState)
        }
        writableDatabase.insertWithOnConflict("draft_attachments", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun draftAttachments(localId: String): List<GroupsIoDraftAttachment> = readableDatabase.rawQuery(
        "SELECT local_id,draft_local_id,remote_attachment_id,filename,media_type,byte_size,local_relative_path,sha256,upload_state FROM draft_attachments WHERE draft_local_id=? ORDER BY filename COLLATE NOCASE",
        arrayOf(localId)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoDraftAttachment(
        localId = cursor.getString(0), draftLocalId = cursor.getString(1), remoteAttachmentId = cursor.longOrNull(2), filename = cursor.getString(3),
        mediaType = cursor.getString(4), byteSize = cursor.getLong(5), localRelativePath = cursor.getString(6), sha256 = cursor.getString(7), uploadState = cursor.getString(8)
    )) } }

    fun markDraftAttachmentUploaded(localId: String, remoteId: Long) {
        writableDatabase.update("draft_attachments", ContentValues().apply { put("remote_attachment_id", remoteId); put("upload_state", "uploaded"); putNull("last_error") }, "local_id=?", arrayOf(localId))
    }

    fun upsertIncomingAttachment(groupId: Long, messageNumber: Long, value: GroupsIoIncomingAttachment, relativePath: String? = null, localSize: Long? = null, sha256: String? = null) {
        val saved = readableDatabase.rawQuery(
            "SELECT local_relative_path,local_size,sha256 FROM message_attachments WHERE group_id=? AND message_number=? AND attachment_id=?",
            arrayOf(groupId.toString(), messageNumber.toString(), value.id.toString())
        ).use { cursor -> if (cursor.moveToFirst()) Triple(
            if (cursor.isNull(0)) null else cursor.getString(0), cursor.longOrNull(1), if (cursor.isNull(2)) null else cursor.getString(2)
        ) else null }
        val effectivePath = relativePath ?: saved?.first
        val effectiveSize = localSize ?: saved?.second
        val effectiveSha = sha256 ?: saved?.third
        val row = ContentValues().apply {
            put("group_id", groupId); put("message_number", messageNumber); put("attachment_id", value.id); put("filename", value.filename); put("media_type", value.mediaType)
            value.size?.let { put("reported_size", it) } ?: putNull("reported_size")
            effectivePath?.let { put("local_relative_path", it); put("download_state", "downloaded"); put("downloaded_at", System.currentTimeMillis()) } ?: put("download_state", "remote")
            effectiveSize?.let { put("local_size", it) }; effectiveSha?.let { put("sha256", it) }; putNull("last_error")
        }
        writableDatabase.insertWithOnConflict("message_attachments", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun incomingAttachments(groupId: Long, messageNumber: Long): List<GroupsIoIncomingAttachment> = readableDatabase.rawQuery(
        "SELECT attachment_id,filename,media_type,reported_size,local_relative_path FROM message_attachments WHERE group_id=? AND message_number=? ORDER BY filename COLLATE NOCASE",
        arrayOf(groupId.toString(), messageNumber.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GroupsIoIncomingAttachment(
        id = cursor.getLong(0), filename = cursor.getString(1), mediaType = cursor.getString(2), size = cursor.longOrNull(3), transientHttpsUrl = null,
        localRelativePath = if (cursor.isNull(4)) null else cursor.getString(4)
    )) } }

    fun saveArchiveExport(groupId: Long, relativePath: String, requestedAt: Long, completedAt: Long, byteSize: Long, sha256: String) {
        writableDatabase.insertWithOnConflict("archive_exports", null, ContentValues().apply {
            put("group_id", groupId); put("relative_path", relativePath); put("requested_at", requestedAt); put("completed_at", completedAt)
            put("byte_size", byteSize); put("sha256", sha256); put("state", "complete"); putNull("last_error")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearDownloadedCache() = writableDatabase.transaction {
        delete("message_attachments", null, null); delete("message_search", null, null); delete("messages", null, null); delete("topics", null, null)
        delete("archive_exports", null, null); delete("server_drafts", null, null)
        delete("sync_state", "scope NOT IN ('outbox','server_drafts')", null)
    }

    fun removeGroupArchive(groupId: Long) = writableDatabase.transaction {
        execSQL("DELETE FROM message_search WHERE rowid IN (SELECT row_id FROM messages WHERE group_id=?)", arrayOf(groupId))
        delete("message_attachments", "group_id=?", arrayOf(groupId.toString()))
        delete("messages", "group_id=?", arrayOf(groupId.toString()))
        delete("topics", "group_id=?", arrayOf(groupId.toString()))
        delete("archive_exports", "group_id=?", arrayOf(groupId.toString()))
        delete("sync_state", "scope IN ('complete_archive','archive_export') AND scope_id=?", arrayOf(groupId.toString()))
    }

    fun deleteAllLocalData() {
        close()
        appContext.deleteDatabase(databaseName)
        appContext.filesDir.resolve("GroupsIO").deleteRecursively()
    }

    @Deprecated("Use clearDownloadedCache or deleteAllLocalData explicitly")
    fun deleteDownloadedData() = clearDownloadedCache()

    fun recordFailure(scope: String, scopeId: String, category: String, text: String) = writableDatabase.transaction {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply { put("scope", scope); put("scope_id", scopeId); put("last_attempt", now); put("last_error_category", category); put("last_error_text", text.take(160)) }
        insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        update("sync_state", values, "scope=? AND scope_id=?", arrayOf(scope, scopeId))
    }

    private fun refreshSearchForGroup(db: SQLiteDatabase, groupId: Long) {
        db.rawQuery("SELECT row_id FROM messages WHERE group_id=?", arrayOf(groupId.toString())).use { while (it.moveToNext()) refreshSearchRow(db, it.getLong(0)) }
    }

    private fun refreshSearchForTopic(db: SQLiteDatabase, topicId: Long) {
        db.rawQuery("SELECT row_id FROM messages WHERE topic_id=?", arrayOf(topicId.toString())).use { while (it.moveToNext()) refreshSearchRow(db, it.getLong(0)) }
    }

    private fun refreshSearchRow(db: SQLiteDatabase, rowId: Long) {
        db.execSQL("DELETE FROM message_search WHERE rowid=?", arrayOf(rowId))
        db.execSQL("""INSERT INTO message_search(rowid,group_name,topic_subject,message_subject,author_name,body_plain)
            SELECT m.row_id,g.title,t.subject,m.subject,m.author_name,m.body_plain FROM messages m
            JOIN groups g ON g.group_id=m.group_id JOIN topics t ON t.topic_id=m.topic_id WHERE m.row_id=?""", arrayOf(rowId))
    }

    private fun recordSuccess(db: SQLiteDatabase, scope: String, scopeId: String, at: Long, hasMore: Boolean, cursor: String?) {
        val values = ContentValues().apply { put("scope", scope); put("scope_id", scopeId); put("last_attempt", at); put("last_success", at); put("current_cursor", cursor); put("has_more", hasMore); putNull("last_error_category"); putNull("last_error_text") }
        db.insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try { val value = block(); setTransactionSuccessful(); value } finally { endTransaction() }
}

private fun android.database.Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

internal class GroupsIoCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("shackcq-groupsio-credentials", Context.MODE_PRIVATE)
    fun exists(): Boolean = prefs.contains("api_key")
    fun load(): String = decrypt(prefs.getString("api_key", "").orEmpty())
    fun save(value: String) { prefs.edit().putString("api_key", encrypt(value)).apply() }
    fun clear() { prefs.edit().remove("api_key").apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String = runCatching {
        if (value.isEmpty()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }.getOrDefault("")

    private companion object { const val ALIAS = "app.shackcq.mobile.groupsio.api-key" }
}

internal class GroupsIoLiveApi {
    fun groups(key: String, pageToken: String? = null): GroupsIoPage<GroupsIoGroup> = page("getsubs", key, pageToken) { value ->
        val id = value.requiredLong("group_id", "id")
        val name = value.requiredString("group_name", "name")
        val perms = value.optJSONObject("perms") ?: value.optJSONObject("permissions")
        GroupsIoGroup(id, name, value.firstString("title", "nice_group_name", "display_name").ifBlank { name }, value.firstString("description", "desc", "summary"),
            value.firstString("status", "subscription_status"), perms?.optBoolean("archives_visible", value.optBoolean("archives_visible", false)) ?: value.optBoolean("archives_visible", false), true, System.currentTimeMillis(),
            canPost = perms?.optBoolean("can_post", value.optBoolean("can_post", false)) ?: value.optBoolean("can_post", false),
            canReply = perms?.optBoolean("can_reply", value.optBoolean("can_reply", false)) ?: value.optBoolean("can_reply", false),
            downloadArchives = perms?.optBoolean("download_archives", value.optBoolean("download_archives", false)) ?: value.optBoolean("download_archives", false))
    }

    fun topics(key: String, groupId: Long, pageToken: String? = null): GroupsIoPage<GroupsIoTopic> = page("gettopics", key, pageToken, mapOf("group_id" to groupId.toString(), "sort_dir" to "desc")) { value ->
        GroupsIoTopic(value.requiredLong("id", "topic_id"), groupId, value.requiredString("subject", "title"), value.instantMillis("updated", "last_message_time", "created"),
            value.firstInt("message_count", "num_messages", "message_cnt"), value.optBoolean("is_closed", value.optBoolean("closed", value.optBoolean("locked", false))), value.firstLongOrNull("first_msg_num", "first_message_number"), value.firstLongOrNull("last_msg_num", "latest_message_number"))
    }

    fun messages(key: String, groupId: Long, topicId: Long, pageToken: String? = null): GroupsIoPage<GroupsIoMessage> = page("gettopic", key, pageToken, mapOf("topic_id" to topicId.toString(), "sort_dir" to "asc")) { value ->
        val number = value.requiredLong("msg_num", "message_number", "num")
        val rawBody = value.firstString("body", "html_body", "text", "snippet")
        GroupsIoMessage(value.firstLongOrNull("id", "message_id"), groupId, topicId, number, value.firstLongOrNull("reply_to", "reply_to_msg_num"),
            value.firstString("subject"), value.firstString("name", "author_name", "sender_name", "from_name").ifBlank { "Unknown author" },
            value.instantMillis("created", "date"), normaliseBody(rawBody), value.optBoolean("is_moderated", value.optBoolean("moderated", false)), value.optBoolean("deleted", false),
            value.optBoolean("has_attachments", false) || value.optJSONArray("attachments")?.length()?.let { it > 0 } == true)
    }

    private fun <T> page(path: String, key: String, token: String?, extra: Map<String, String> = emptyMap(), map: (JSONObject) -> T): GroupsIoPage<T> {
        val query = linkedMapOf("limit" to "50").apply { putAll(extra); token?.let { put("page_token", it) } }
        val encoded = query.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}" }
        val connection = URL("https://groups.io/api/v1/$path?$encoded").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"; connection.connectTimeout = 15_000; connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $key")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw GroupsIoApiException.from(status, body)
            val root = JSONObject(body)
            if (root.optString("object") == "error") throw GroupsIoApiException.from(status, body)
            val data = root.optJSONArray("data") ?: throw GroupsIoApiException("compatibility", "Groups.io returned an incompatible list response")
            val values = buildList { for (index in 0 until data.length()) add(map(data.getJSONObject(index))) }
            val (next, hasMore) = groupsIoPagination(root)
            return GroupsIoPage(values, next, hasMore)
        } catch (error: GroupsIoApiException) { throw error }
        catch (error: UnknownHostException) { throw GroupsIoApiException("network", "No network connection") }
        catch (error: SocketTimeoutException) { throw GroupsIoApiException("temporary", "Groups.io request timed out") }
        catch (error: IOException) { throw GroupsIoApiException("network", "Network request failed") }
        catch (error: org.json.JSONException) { throw GroupsIoApiException("compatibility", "Groups.io response format changed") }
        finally { connection.disconnect() }
    }
}

internal class GroupsIoApiException(val category: String, override val message: String) : IOException(message) {
    companion object {
        fun from(status: Int, body: String): GroupsIoApiException {
            val type = runCatching { JSONObject(body).optString("type") }.getOrDefault("")
            return when {
                status == 401 || type == "unauthorized_error" -> GroupsIoApiException("credential", "API key is invalid or revoked")
                status == 403 || type == "inadequate_permissions" -> GroupsIoApiException("permission", "Groups.io permission is inadequate")
                status == 429 -> GroupsIoApiException("rate_limited", "Groups.io rate limit reached; try later")
                status >= 500 -> GroupsIoApiException("temporary", "Groups.io is temporarily unavailable")
                else -> GroupsIoApiException("server", "Groups.io request failed (HTTP $status)")
            }
        }
    }
}

internal fun normaliseBody(raw: String): String = raw
    .replace(Regex("(?is)<(script|style|iframe|form)[^>]*>.*?</\\1>"), "")
    .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</blockquote>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace('\u00a0', ' ')
    .replace(Regex("&#(x?[0-9A-Fa-f]+);")) { match ->
        val token = match.groupValues[1]
        val codePoint = runCatching { if (token.startsWith("x", true)) token.drop(1).toInt(16) else token.toInt() }.getOrNull()
        codePoint?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString() ?: match.value
    }
    .replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

internal fun JSONObject.firstString(vararg names: String): String = names.firstNotNullOfOrNull { name -> optString(name).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
private fun JSONObject.requiredString(vararg names: String): String = firstString(*names).ifBlank { throw GroupsIoApiException("compatibility", "Groups.io response omitted ${names.first()}") }
internal fun JSONObject.firstLongOrNull(vararg names: String): Long? = names.firstNotNullOfOrNull { name -> if (has(name) && !isNull(name)) optLong(name).takeIf { it != 0L } else null }
private fun JSONObject.requiredLong(vararg names: String): Long = firstLongOrNull(*names) ?: throw GroupsIoApiException("compatibility", "Groups.io response omitted ${names.first()}")
private fun JSONObject.firstInt(vararg names: String): Int = names.firstNotNullOfOrNull { name -> if (has(name)) optInt(name) else null } ?: 0
internal fun JSONObject.instantMillis(vararg names: String): Long = names.firstNotNullOfOrNull { name -> firstString(name).takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } } ?: 0L

data class GroupsIoSyncSettings(
    val defaultLookbackDays: Int = 30,
    val defaultMaximumMessages: Int = 500,
    val defaultArchiveDepth: Int = 2_000,
    val attachmentAutoDownload: Boolean = false,
    val maximumAttachmentBytes: Long = 8L * 1024 * 1024,
    val offlineRetentionDays: Int = 180,
    val refreshOnOpen: Boolean = true,
    val foregroundRefreshMinutes: Int = 15,
    val includeMutedGroups: Boolean = false,
    val draftRetentionDays: Int = 365,
    val showNewMessagePreview: Boolean = false,
)

data class GroupsIoGroupOverride(
    val enabled: Boolean = true,
    val inheritGlobal: Boolean = true,
    val lookbackDays: Int = 30,
    val maximumMessages: Int = 500,
    val attachments: Boolean = false,
    val retentionDays: Int = 180,
)

class GroupsIoController(context: Context) {
    private val appContext = context.applicationContext
    private val settings = appContext.getSharedPreferences("shackcq-groupsio", Context.MODE_PRIVATE)
    private val credentials = GroupsIoCredentialStore(appContext)
    private val api = GroupsIoLiveApi()
    private val phase2Api = GroupsIoPhase2Api()
    private val attachmentStore = GroupsIoAttachmentStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboxMutex = Mutex()
    private var database: GroupsIoDatabase? = null
    private var operation: Job? = null
    var enabled by mutableStateOf(settings.getBoolean("enabled", false)); private set
    var connected by mutableStateOf(credentials.exists()); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(if (connected) "Connected · cached content available" else "Not connected"); private set
    var lastSyncMillis by mutableLongStateOf(settings.getLong("last_sync", 0L)); private set
    var groups by mutableStateOf<List<GroupsIoGroup>>(emptyList()); private set
    var topics by mutableStateOf<List<GroupsIoTopic>>(emptyList()); private set
    var messages by mutableStateOf<List<GroupsIoMessage>>(emptyList()); private set
    var searchResults by mutableStateOf<List<GroupsIoSearchResult>>(emptyList()); private set
    var onlineSearchResults by mutableStateOf<List<GroupsIoSearchResult>>(emptyList()); private set
    var onlineSearchHasMore by mutableStateOf(false); private set
    var selectedGroupId by mutableStateOf<Long?>(null); private set
    var selectedTopicId by mutableStateOf<Long?>(null); private set
    var storageBytes by mutableLongStateOf(0L); private set
    var cacheStats by mutableStateOf(GroupsIoCacheStats()); private set
    var topicsHaveMore by mutableStateOf(false); private set
    var messagesHaveMore by mutableStateOf(false); private set
    var capabilities by mutableStateOf<GroupsIoCapabilities?>(null); private set
    var localDrafts by mutableStateOf<List<GroupsIoLocalDraft>>(emptyList()); private set
    var serverDrafts by mutableStateOf<List<GroupsIoRemoteDraft>>(emptyList()); private set
    var composerDraft by mutableStateOf<GroupsIoLocalDraft?>(null); private set
    var showComposer by mutableStateOf(false); private set
    var showDraftsOutbox by mutableStateOf(false); private set
    var archiveProgress by mutableStateOf(GroupsIoArchiveProgress()); private set
    var archiveRangeDays by mutableStateOf<Int?>(365); private set
    var selectedMessage by mutableStateOf<GroupsIoMessage?>(null); private set
    var incomingAttachments by mutableStateOf<List<GroupsIoIncomingAttachment>>(emptyList()); private set
    var syncSettings by mutableStateOf(loadSyncSettings()); private set
    var groupOverrides by mutableStateOf(loadGroupOverrides()); private set
    var newMessagePreview by mutableStateOf<GroupsIoMessage?>(null); private set
    private val newMessageQueue = ArrayDeque<GroupsIoMessage>()
    private val alertedMessageKeys = LinkedHashSet<String>()
    private var mutedAlertGroups: Set<Long> = settings.getStringSet("muted_alert_groups", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()
    var showAttachments by mutableStateOf(false); private set
    private var homeRecent by mutableStateOf<List<GroupsIoSearchResult>>(emptyList())
    val homeSummary: GroupsIoHomeSummary get() = GroupsIoHomeSummary(
        recent = homeRecent,
        needsAttention = localDrafts.count { it.state !in setOf(GroupsIoOutboxState.POSTED, GroupsIoOutboxState.PENDING_MODERATION) },
        archiveState = archiveProgress.state,
        archiveDownloaded = archiveProgress.downloaded,
        lastRefreshMillis = lastSyncMillis,
        offline = !connected,
    )
    private var topicNextToken: String? = null
    private var messageNextToken: String? = null
    private var onlineSearchNextToken: String? = null
    private var lastOnlineSearch: String = ""

    fun updateEnabled(value: Boolean) {
        enabled = value; settings.edit().putBoolean("enabled", value).apply()
        if (!value) { if (composerDraft != null) autosaveComposer(); operation?.cancel(); archiveProgress = archiveProgress.paused(); busy = false; status = "Groups.io disabled · drafts and downloaded data preserved" }
        else loadCachedGroups()
    }

    fun updateSyncSettings(value: GroupsIoSyncSettings) {
        syncSettings = value.copy(
            defaultLookbackDays = value.defaultLookbackDays.coerceIn(1, 3650), defaultMaximumMessages = value.defaultMaximumMessages.coerceIn(50, 20_000),
            defaultArchiveDepth = value.defaultArchiveDepth.coerceIn(100, 100_000), maximumAttachmentBytes = value.maximumAttachmentBytes.coerceIn(256_000, 50L * 1024 * 1024),
            offlineRetentionDays = value.offlineRetentionDays.coerceIn(7, 3650), foregroundRefreshMinutes = value.foregroundRefreshMinutes.coerceIn(5, 240),
            draftRetentionDays = value.draftRetentionDays.coerceIn(7, 3650))
        settings.edit().putString("sync_settings_v1", JSONObject().apply {
            put("lookback", syncSettings.defaultLookbackDays); put("maximum", syncSettings.defaultMaximumMessages); put("archive", syncSettings.defaultArchiveDepth)
            put("attachments", syncSettings.attachmentAutoDownload); put("attachment_bytes", syncSettings.maximumAttachmentBytes); put("retention", syncSettings.offlineRetentionDays)
            put("open", syncSettings.refreshOnOpen); put("cadence", syncSettings.foregroundRefreshMinutes); put("muted", syncSettings.includeMutedGroups)
            put("drafts", syncSettings.draftRetentionDays); put("preview", syncSettings.showNewMessagePreview)
        }.toString()).apply()
    }

    fun updateGroupOverride(groupId: Long, value: GroupsIoGroupOverride) {
        groupOverrides = groupOverrides + (groupId to value.copy(lookbackDays = value.lookbackDays.coerceIn(1, 3650),
            maximumMessages = value.maximumMessages.coerceIn(50, 20_000), retentionDays = value.retentionDays.coerceIn(7, 3650)))
        saveGroupOverrides()
    }

    fun applyGlobalToAll() { groupOverrides = groups.associate { it.id to GroupsIoGroupOverride() }; saveGroupOverrides() }
    fun applyGlobalToGroups(groupIds: Set<Long>) {
        groupOverrides = groupOverrides + groupIds.associateWith { GroupsIoGroupOverride() }
        saveGroupOverrides()
    }
    fun resetGroupOverride(groupId: Long) { groupOverrides = groupOverrides - groupId; saveGroupOverrides() }

    fun maybeForegroundRefresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!enabled || !connected || busy || (!force && now - lastSyncMillis < syncSettings.foregroundRefreshMinutes * 60_000L)) return
        replaceOperation {
            val key = credentials.load().takeIf(String::isNotBlank) ?: return@replaceOperation
            val discovered = mutableListOf<GroupsIoMessage>()
            val activeGroups = db().groups().filter { groupOverrides[it.id]?.enabled != false }.take(50)
            activeGroups.forEach { group ->
                ensureActive()
                val topicPage = api.topics(key, group.id)
                val syncAt = System.currentTimeMillis()
                db().applyTopics(group.id, topicPage.values, topicPage.nextPageToken, topicPage.hasMore, syncAt)
                val override = groupOverrides[group.id]
                val lookback = if (override?.inheritGlobal == false) override.lookbackDays else syncSettings.defaultLookbackDays
                val maximum = if (override?.inheritGlobal == false) override.maximumMessages else syncSettings.defaultMaximumMessages
                val cutoff = syncAt - lookback * 86_400_000L
                topicPage.values.asSequence().filter { it.updatedMillis >= cutoff }.take(12).forEach { topic ->
                    ensureActive()
                    val before = db().messages(topic.id, limit = 1_000).maxOfOrNull(GroupsIoMessage::number) ?: 0L
                    val page = api.messages(key, group.id, topic.id)
                    db().applyMessages(group.id, topic.id, page.values.take(maximum), page.nextPageToken, page.hasMore, syncAt)
                    if (before > 0) discovered += page.values.filter { it.number > before }
                }
            }
            settings.edit().putLong("last_sync", now).apply()
            val savedGroups = db().groups(); val stats = db().cacheStats(); val size = db().sizeBytes(); val recent = db().recentMessages()
            withContext(Dispatchers.Main) {
                groups = savedGroups; cacheStats = stats; storageBytes = size; homeRecent = recent; lastSyncMillis = now
                if (syncSettings.showNewMessagePreview) discovered.sortedBy(GroupsIoMessage::createdMillis).forEach(::enqueueNewMessage)
                status = "Foreground sync complete · ${activeGroups.size} groups · no background polling"
            }
        }
    }

    private fun enqueueNewMessage(message: GroupsIoMessage) {
        val key = "${message.groupId}:${message.apiId ?: message.number}"
        if (message.groupId in mutedAlertGroups || selectedTopicId == message.topicId || !alertedMessageKeys.add(key)) return
        while (alertedMessageKeys.size > 512) alertedMessageKeys.remove(alertedMessageKeys.first())
        if (newMessagePreview == null) newMessagePreview = message
        else {
            if (newMessageQueue.size >= 20) newMessageQueue.removeFirst()
            newMessageQueue.addLast(message)
        }
    }

    private fun advanceNewMessagePreview() { newMessagePreview = newMessageQueue.removeFirstOrNull() }
    fun dismissNewMessagePreview() = advanceNewMessagePreview()
    fun openNewMessagePreview() { newMessagePreview?.let { message ->
        advanceNewMessagePreview(); selectGroup(message.groupId); selectTopic(message.topicId); selectedMessage = message
    } }
    fun muteNewMessageGroup() { newMessagePreview?.let { message ->
        mutedAlertGroups = mutedAlertGroups + message.groupId
        settings.edit().putStringSet("muted_alert_groups", mutedAlertGroups.map(Long::toString).toSet()).apply()
        newMessageQueue.removeAll { it.groupId == message.groupId }
        advanceNewMessagePreview()
    } }

    internal fun injectNewMessageAlertForDebug() {
        if (!BuildConfig.DEBUG) return
        enqueueNewMessage(GroupsIoMessage(null, selectedGroupId ?: -1, selectedTopicId ?: -1, System.currentTimeMillis(), null,
            "Debug new-message alert", "ShackCQ diagnostics", System.currentTimeMillis(), "Synthetic debug-only preview; not written to the database.", false, false, false))
    }

    fun loadCachedGroups() {
        if (!enabled) return
        scope.launch { val db = db(); val loaded = db.groups(); val savedDrafts = db.drafts(); val stats = db.cacheStats(); val size = db.sizeBytes()
            val summary = buildHomeSummary(db, savedDrafts)
            withContext(Dispatchers.Main) { groups = loaded; localDrafts = savedDrafts; cacheStats = stats; storageBytes = size; homeRecent = summary.recent } }
    }

    fun connectAndVerify(candidate: String) = replaceOperation {
        val key = candidate.trim()
        require(key.isNotEmpty()) { "Enter a Groups.io API key" }
        val first = api.groups(key)
        val all = first.values.toMutableList(); var next = first.nextPageToken; var more = first.hasMore
        while (more) { ensureActive(); val page = api.groups(key, next); all += page.values; more = page.hasMore; next = page.nextPageToken }
        val now = System.currentTimeMillis(); db().applyMemberships(all, true, now); credentials.save(key)
        settings.edit().putLong("last_sync", now).apply()
        val stats = db().cacheStats(); val size = db().sizeBytes(); val savedGroups = db().groups()
        withContext(Dispatchers.Main) { connected = true; groups = savedGroups; lastSyncMillis = now; status = "Connected · memberships synced"; cacheStats = stats; storageBytes = size }
    }

    fun syncMemberships() = replaceOperation {
        val key = credentials.load().takeIf(String::isNotBlank) ?: throw GroupsIoApiException("credential", "Connect Groups.io first")
        val all = mutableListOf<GroupsIoGroup>(); var next: String? = null; var more: Boolean
        do { ensureActive(); val page = api.groups(key, next); all += page.values; more = page.hasMore; next = page.nextPageToken } while (more)
        val now = System.currentTimeMillis(); db().applyMemberships(all, true, now); settings.edit().putLong("last_sync", now).apply()
        val stats = db().cacheStats(); val size = db().sizeBytes(); val savedGroups = db().groups()
        withContext(Dispatchers.Main) { groups = savedGroups; lastSyncMillis = now; status = "Memberships synced"; cacheStats = stats; storageBytes = size }
    }

    fun selectGroup(groupId: Long) {
        if (selectedGroupId != groupId) { archiveProgress = GroupsIoArchiveProgress(); archiveRangeDays = 365 }
        selectedGroupId = groupId; selectedTopicId = null; messages = emptyList(); topicsHaveMore = false; topicNextToken = null
        operation?.cancel(); operation = scope.launch {
            val cached = db().topics(groupId); val cachedCapabilities = db().capabilities(groupId)
            withContext(Dispatchers.Main) { topics = cached; capabilities = cachedCapabilities; status = if (cached.isEmpty()) "No downloaded topics" else "Showing downloaded topics${if (cachedCapabilities?.stale == true) " · permissions stale" else ""}" }
            if (!connected) return@launch
            runCatching { phase2Api.permissions(credentials.load(), groupId) }.onSuccess { value ->
                db().updateCapabilities(groupId, value); withContext(Dispatchers.Main) { capabilities = value }
            }.onFailure { publishFailure("permissions", groupId.toString(), it) }
            runCatching { api.topics(credentials.load(), groupId) }.onSuccess { page ->
                val now = System.currentTimeMillis(); db().applyTopics(groupId, page.values, page.nextPageToken, page.hasMore, now)
                val savedTopics = db().topics(groupId); val stats = db().cacheStats(); val size = db().sizeBytes()
                withContext(Dispatchers.Main) { topics = savedTopics; topicNextToken = page.nextPageToken; topicsHaveMore = page.hasMore; status = "Newest topics synced"; cacheStats = stats; storageBytes = size }
            }.onFailure { publishFailure("topics", groupId.toString(), it) }
        }
    }

    fun selectTopic(topicId: Long) {
        val groupId = selectedGroupId ?: return
        selectedTopicId = topicId; messagesHaveMore = false; messageNextToken = null; operation?.cancel(); operation = scope.launch {
            val cached = db().messages(topicId)
            withContext(Dispatchers.Main) { messages = cached; status = if (cached.isEmpty()) "No downloaded messages" else "Showing downloaded messages" }
            if (!connected) return@launch
            runCatching { api.messages(credentials.load(), groupId, topicId) }.onSuccess { page ->
                val now = System.currentTimeMillis(); db().applyMessages(groupId, topicId, page.values, page.nextPageToken, page.hasMore, now)
                val savedMessages = db().messages(topicId); val stats = db().cacheStats(); val size = db().sizeBytes()
                val recent = db().recentMessages()
                withContext(Dispatchers.Main) { messages = savedMessages; messageNextToken = page.nextPageToken; messagesHaveMore = page.hasMore; status = "Thread synced"; cacheStats = stats; storageBytes = size; homeRecent = recent }
            }.onFailure { publishFailure("messages", topicId.toString(), it) }
        }
    }

    fun selectMessage(message: GroupsIoMessage) { selectedMessage = message }

    fun openAttachments(message: GroupsIoMessage) {
        selectedMessage = message; showAttachments = true
        replaceOperation {
            val local = db().incomingAttachments(message.groupId, message.number)
            withContext(Dispatchers.Main) { incomingAttachments = local }
            if (!connected) {
                withContext(Dispatchers.Main) { status = "Offline · previously downloaded attachments remain available" }
                return@replaceOperation
            }
            val values = phase2Api.incomingAttachments(credentials.load(), message.groupId, message.number)
            values.forEach { db().upsertIncomingAttachment(message.groupId, message.number, it) }
            val previews = values.map { value ->
                if (!value.mediaType.startsWith("image/") || value.transientThumbnailHttpsUrl == null) return@map value
                runCatching {
                    val (partial, final) = attachmentStore.incomingPreviewFile(message.groupId, message.number, value.id, value.filename)
                    phase2Api.downloadIncomingPreview(credentials.load(), value, partial)
                    final.parentFile?.mkdirs()
                    if (!partial.renameTo(final)) { partial.copyTo(final, overwrite = true); partial.delete() }
                    value.copy(localPreviewRelativePath = final.relativeTo(appContext.filesDir).path)
                }.getOrDefault(value)
            }
            val cached = db().incomingAttachments(message.groupId, message.number).associateBy { it.id }
            withContext(Dispatchers.Main) {
                incomingAttachments = previews.map { remote -> remote.copy(localRelativePath = cached[remote.id]?.localRelativePath) }
                status = "Attachment metadata and image previews refreshed"
            }
        }
    }

    fun closeAttachments() { showAttachments = false }

    fun downloadAttachment(value: GroupsIoIncomingAttachment) {
        val message = selectedMessage ?: return
        replaceOperation {
            val (partial, final) = attachmentStore.incomingFile(message.groupId, message.number, value.id, value.filename)
            val refreshed = phase2Api.downloadIncomingAttachment(credentials.load(), message.groupId, message.number, value.id, partial)
            final.parentFile?.mkdirs(); if (!partial.renameTo(final)) { partial.copyTo(final, overwrite = false); partial.delete() }
            db().upsertIncomingAttachment(message.groupId, message.number, refreshed, final.relativeTo(appContext.filesDir).path, final.length(), groupsIoSha256(final))
            withContext(Dispatchers.Main) {
                incomingAttachments = incomingAttachments.map { if (it.id == value.id) it.copy(localRelativePath = final.relativeTo(appContext.filesDir).path) else it }
                status = "Attachment downloaded for offline access"
            }
        }
    }

    fun attachmentFile(relativePath: String?): File? = relativePath?.let { appContext.filesDir.resolve(it) }?.takeIf(File::isFile)

    fun openNewTopic() {
        val groupId = selectedGroupId ?: return
        if (capabilities?.canPost != true) { status = "This group is read-only for the current account"; return }
        composerDraft = GroupsIoLocalDraft(groupId = groupId, type = GroupsIoDraftType.NEW_TOPIC)
        showComposer = true
    }

    fun openReply(message: GroupsIoMessage) {
        if (capabilities?.canReply != true) { status = "Replies are not permitted in this group"; return }
        val topic = topics.firstOrNull { it.id == message.topicId }
        if (topic?.closed == true) { status = "This topic is closed"; return }
        composerDraft = GroupsIoLocalDraft(groupId = message.groupId, topicId = message.topicId, replyMessageNumber = message.number,
            replyApiMessageId = message.apiId, type = GroupsIoDraftType.REPLY, subject = message.subject, replyDestination = GroupsIoReplyDestination.GROUP)
        showComposer = true
    }

    fun updateComposer(subject: String, body: String, destination: GroupsIoReplyDestination? = composerDraft?.replyDestination) {
        composerDraft = composerDraft?.copy(subject = subject, bodyPlain = body, replyDestination = destination, updatedAtMillis = System.currentTimeMillis())
    }

    fun autosaveComposer() {
        val draft = composerDraft ?: return
        scope.launch { db().saveDraft(draft.copy(state = GroupsIoOutboxState.DRAFT_LOCAL, sendWhenOnline = false)); withContext(Dispatchers.Main) { localDrafts = db().drafts(); status = "Draft saved locally" } }
    }

    fun closeComposer() { autosaveComposer(); showComposer = false }

    fun addComposerAttachments(uris: List<android.net.Uri>) {
        val draft = composerDraft ?: return
        scope.launch {
            runCatching {
                db().saveDraft(draft)
                uris.forEach { db().saveDraftAttachment(attachmentStore.importDraftAttachment(draft.localId, it)) }
            }.onSuccess { withContext(Dispatchers.Main) { status = "${uris.size} attachment(s) copied to private storage" } }
                .onFailure { publishFailure("draft_attachment", draft.localId, it) }
        }
    }

    fun queueComposer(sendNow: Boolean) {
        val draft = composerDraft ?: return
        if (draft.subject.isBlank() || draft.bodyPlain.isBlank()) { status = "Subject and body are required"; return }
        val queued = draft.copy(state = GroupsIoOutboxState.QUEUED, sendWhenOnline = true, updatedAtMillis = System.currentTimeMillis())
        composerDraft = queued; scope.launch {
            db().saveDraft(queued); withContext(Dispatchers.Main) { localDrafts = db().drafts(); showComposer = false; status = if (sendNow) "Sending authorised message…" else "Queued for explicit foreground sending" }
            if (sendNow) processDraft(queued)
        }
    }

    fun openDraftsOutbox() { showDraftsOutbox = true; scope.launch { val values = db().drafts(); withContext(Dispatchers.Main) { localDrafts = values } } }
    fun closeDraftsOutbox() { showDraftsOutbox = false }
    fun openLocalDraft(draft: GroupsIoLocalDraft) { composerDraft = draft; showDraftsOutbox = false; showComposer = true }
    fun processQueuedExplicitly() { scope.launch { db().drafts().filter { it.state in setOf(GroupsIoOutboxState.QUEUED, GroupsIoOutboxState.FAILED_RETRYABLE) }.forEach { processDraft(it) } } }

    private suspend fun processDraft(draft: GroupsIoLocalDraft) {
        outboxMutex.withLock {
            val current = db().drafts().firstOrNull { it.localId == draft.localId } ?: return@withLock
            if (current.state !in setOf(GroupsIoOutboxState.QUEUED, GroupsIoOutboxState.FAILED_RETRYABLE)) return@withLock
            val persistence = object : GroupsIoOutboxPersistence {
                override fun save(draft: GroupsIoLocalDraft) { db().saveDraft(draft) }
                override fun attachments(localId: String) = db().draftAttachments(localId)
                override fun markAttachmentUploaded(localId: String, remoteId: Long) = db().markDraftAttachmentUploaded(localId, remoteId)
            }
            val result = GroupsIoOutbox(phase2Api, persistence, attachmentStore.filesRoot)
                .process(current, credentials.load(), connected, enabled)
            withContext(Dispatchers.Main) { localDrafts = db().drafts(); status = when (result.state) {
                GroupsIoOutboxState.POSTED -> "Message posted"
                GroupsIoOutboxState.PENDING_MODERATION -> "Submitted successfully and awaiting moderator approval"
                GroupsIoOutboxState.DELIVERY_UNKNOWN -> "Delivery could not be confirmed · review before retrying"
                else -> result.lastErrorText ?: "Outbox item needs attention"
            } }
        }
    }

    fun refreshServerDrafts() = replaceOperation {
        val key = credentials.load().takeIf(String::isNotBlank) ?: throw GroupsIoApiException("credential", "Reconnect to view server drafts")
        val all = mutableListOf<GroupsIoRemoteDraft>(); var token: String? = null; var more: Boolean
        do { val page = phase2Api.drafts(key, token); all += page.first; token = page.second.first; more = page.second.second } while (more && all.size < 200)
        withContext(Dispatchers.Main) { serverDrafts = all; status = "Server drafts refreshed · ${all.size}" }
    }

    fun startCompleteArchiveDownload() = startArchiveDownload(null)

    fun startArchiveDownload(days: Int?) {
        val groupId = selectedGroupId ?: return
        if (capabilities?.archivesVisible != true) { status = "Archive access is not available"; return }
        if (archiveRangeDays != days) { archiveRangeDays = days; archiveProgress = GroupsIoArchiveProgress() }
        replaceOperation {
            val since = days?.let { System.currentTimeMillis() - it * 86_400_000L }
            val key = credentials.load(); var progress = archiveProgress.copy(state = "syncing"); var token = progress.nextPageToken
            do {
                ensureActive(); val root = phase2Api.archivePage(key, groupId, token); val data = root.optJSONArray("data") ?: org.json.JSONArray()
                val now = System.currentTimeMillis(); val byTopic = mutableMapOf<Long, MutableList<GroupsIoMessage>>(); var oldest = Long.MAX_VALUE
                for (index in 0 until data.length()) {
                    val value = data.getJSONObject(index); val topicId = value.optLong("topic_id").takeIf { it > 0 } ?: value.optLong("thread_id").takeIf { it > 0 } ?: continue
                    val number = value.optLong("msg_num").takeIf { it > 0 } ?: continue
                    val created = value.instantMillis("created", "date"); if (created > 0) oldest = minOf(oldest, created)
                    if (since != null && created > 0 && created < since) continue
                    val message = GroupsIoMessage(value.optLong("id").takeIf { it > 0 }, groupId, topicId, number, value.optLong("reply_to").takeIf { it > 0 },
                        value.optString("subject"), value.optString("name").ifBlank { "Unknown author" }, created, normaliseBody(value.optString("body")),
                        value.optBoolean("is_moderated", value.optBoolean("moderated")), false,
                        value.optBoolean("has_attachments") || (value.optJSONArray("attachments")?.length() ?: 0) > 0)
                    byTopic.getOrPut(topicId) { mutableListOf() } += message
                }
                byTopic.forEach { (topicId, values) ->
                    if (db().topics(groupId, 100).none { it.id == topicId }) db().applyTopics(groupId, listOf(GroupsIoTopic(topicId, groupId, values.firstOrNull()?.subject.orEmpty(), values.maxOfOrNull { it.createdMillis } ?: 0L, values.size, false, values.minOfOrNull { it.number }, values.maxOfOrNull { it.number })), null, false, now)
                    db().applyMessages(groupId, topicId, values, null, false, now)
                }
                val (next, remoteMore) = groupsIoPagination(root)
                val reachedRange = since != null && oldest != Long.MAX_VALUE && oldest < since
                val more = remoteMore && !reachedRange
                progress = progress.applyPage(byTopic.values.sumOf { it.size }, if (days == null) root.optInt("total_count").takeIf { it > 0 } else null, if (more) next else null, more); token = if (more) next else null
                val stats = db().cacheStats(); val size = db().sizeBytes()
                withContext(Dispatchers.Main) { archiveProgress = progress; cacheStats = stats; storageBytes = size
                    status = "Offline ${days?.let { "$it days" } ?: "full archive"} · ${progress.downloaded} messages this run" }
            } while (progress.state != "complete")
        }
    }

    fun pauseArchiveDownload() { operation?.cancel(); archiveProgress = archiveProgress.paused(); status = "Archive download paused · completed pages preserved" }

    fun downloadOfficialArchive() {
        val groupId = selectedGroupId ?: return
        if (capabilities?.downloadArchives != true) { status = "Official archive export is not permitted for this group"; return }
        replaceOperation {
            val requested = System.currentTimeMillis(); val (partial, final) = attachmentStore.officialArchiveFile(groupId, requested)
            phase2Api.downloadOfficialArchive(credentials.load(), groupId, null, partial)
            final.parentFile?.mkdirs(); if (!partial.renameTo(final)) { partial.copyTo(final, overwrite = false); partial.delete() }
            val completed = System.currentTimeMillis(); db().saveArchiveExport(groupId, final.relativeTo(appContext.filesDir).path, requested, completed, final.length(), groupsIoSha256(final))
            withContext(Dispatchers.Main) { status = "Official archive ZIP downloaded · manual share available"; storageBytes = db().sizeBytes() }
        }
    }

    fun loadOlderTopics() {
        val groupId = selectedGroupId ?: return; val token = topicNextToken ?: return
        replaceOperation {
            val page = api.topics(credentials.load(), groupId, token); val now = System.currentTimeMillis()
            db().applyTopics(groupId, page.values, page.nextPageToken, page.hasMore, now)
            withContext(Dispatchers.Main) { topics = db().topics(groupId, 100); topicNextToken = page.nextPageToken; topicsHaveMore = page.hasMore; status = "Older topic page downloaded"; storageBytes = db().sizeBytes() }
        }
    }

    fun loadMoreMessages() {
        val groupId = selectedGroupId ?: return; val topicId = selectedTopicId ?: return; val token = messageNextToken ?: return
        replaceOperation {
            val page = api.messages(credentials.load(), groupId, topicId, token); val now = System.currentTimeMillis()
            db().applyMessages(groupId, topicId, page.values, page.nextPageToken, page.hasMore, now)
            withContext(Dispatchers.Main) { messages = db().messages(topicId, 100); messageNextToken = page.nextPageToken; messagesHaveMore = page.hasMore; status = "Additional message page downloaded"; storageBytes = db().sizeBytes() }
        }
    }

    fun search(query: String, groupOnly: Boolean = false) {
        operation?.cancel(); operation = scope.launch {
            delay(250)
            val results = db().search(query, if (groupOnly) selectedGroupId else null, null)
            withContext(Dispatchers.Main) { searchResults = results; status = "Searching downloaded content · ${results.size} results" }
        }
    }

    fun searchOnline(query: String, loadMore: Boolean = false) {
        val groupId = selectedGroupId ?: run { status = "Select one group for Groups.io online search"; return }
        if (!connected) { status = "Reconnect to search Groups.io"; return }
        if (!loadMore) { onlineSearchNextToken = null; onlineSearchResults = emptyList(); lastOnlineSearch = query }
        val token = if (loadMore) onlineSearchNextToken else null
        replaceOperation {
            val root = phase2Api.search(credentials.load(), groupId, if (loadMore) lastOnlineSearch else query, newest = false, pageToken = token)
            val data = root.optJSONArray("data") ?: org.json.JSONArray(); val parsed = buildList {
                for (index in 0 until data.length()) {
                    val value = data.getJSONObject(index); val number = value.optLong("msg_num").takeIf { it > 0 } ?: continue
                    add(GroupsIoSearchResult(groupId, value.optLong("topic_id"), number, groups.firstOrNull { it.id == groupId }?.title.orEmpty(),
                        value.optString("subject"), value.optString("name").ifBlank { "Unknown author" }, System.currentTimeMillis(), normaliseBody(value.optString("snippet", value.optString("body")))))
                }
            }
            val (next, more) = groupsIoPagination(root); onlineSearchNextToken = next
            withContext(Dispatchers.Main) { onlineSearchResults = if (loadMore) onlineSearchResults + parsed else parsed; onlineSearchHasMore = more; status = "Groups.io online search · ${onlineSearchResults.size} results" }
        }
    }

    fun openOnlineSearchResult(result: GroupsIoSearchResult) = replaceOperation {
        val root = phase2Api.message(credentials.load(), result.groupId, result.messageNumber)
        val value = root.optJSONObject("message") ?: root.optJSONObject("data") ?: root
        val topicId = value.optLong("topic_id").takeIf { it > 0 } ?: result.topicId
        val now = System.currentTimeMillis(); val subject = value.optString("subject", result.topicSubject)
        db().applyTopics(result.groupId, listOf(GroupsIoTopic(topicId, result.groupId, subject, now, 1, false, result.messageNumber, result.messageNumber)), null, false, now)
        db().applyMessages(result.groupId, topicId, listOf(GroupsIoMessage(value.optLong("id").takeIf { it > 0 }, result.groupId, topicId, result.messageNumber,
            value.optLong("reply_to").takeIf { it > 0 }, subject, value.optString("name").ifBlank { result.author }, now, normaliseBody(value.optString("body")), false, false, value.optBoolean("has_attachments"))), null, false, now)
        withContext(Dispatchers.Main) { selectedGroupId = result.groupId; selectedTopicId = topicId; topics = db().topics(result.groupId); messages = db().messages(topicId); onlineSearchResults = emptyList(); status = "Online result cached for offline reading" }
    }

    fun openSearchResult(result: GroupsIoSearchResult) {
        selectedGroupId = result.groupId; selectedTopicId = result.topicId
        scope.launch { val loadedTopics = db().topics(result.groupId); val loadedMessages = db().messages(result.topicId); withContext(Dispatchers.Main) { topics = loadedTopics; messages = loadedMessages; searchResults = emptyList() } }
    }

    fun disconnect() {
        operation?.cancel(); credentials.clear(); connected = false; busy = false; status = "Disconnected · downloaded data preserved"
    }

    fun clearDownloadedCache() {
        operation?.cancel(); database?.clearDownloadedCache()
        groups = emptyList(); topics = emptyList(); messages = emptyList(); searchResults = emptyList(); selectedGroupId = null; selectedTopicId = null; storageBytes = 0
        localDrafts = database?.drafts().orEmpty(); status = "Downloaded Groups.io cache cleared · drafts and credential preserved"
    }

    fun deleteAllLocalData() {
        operation?.cancel(); database?.deleteAllLocalData(); database = null
        groups = emptyList(); topics = emptyList(); messages = emptyList(); searchResults = emptyList(); localDrafts = emptyList(); serverDrafts = emptyList(); selectedGroupId = null; selectedTopicId = null; storageBytes = 0
        status = "All local Groups.io data deleted · credential preserved"
    }

    @Deprecated("Use clearDownloadedCache") fun deleteDownloadedData() = clearDownloadedCache()

    fun close() { operation?.cancel(); scope.cancel(); database?.close() }
    private fun buildHomeSummary(database: GroupsIoDatabase, drafts: List<GroupsIoLocalDraft> = database.drafts()) = GroupsIoHomeSummary(
        recent = database.recentMessages(),
        needsAttention = drafts.count { it.state !in setOf(GroupsIoOutboxState.POSTED, GroupsIoOutboxState.PENDING_MODERATION) },
        archiveState = archiveProgress.state,
        archiveDownloaded = archiveProgress.downloaded,
        lastRefreshMillis = lastSyncMillis,
        offline = !connected,
    )
    private fun db(): GroupsIoDatabase = database ?: GroupsIoDatabase(appContext).also { database = it }

    private fun loadSyncSettings(): GroupsIoSyncSettings = settings.getString("sync_settings_v1", null)?.let { text -> runCatching {
        JSONObject(text).let { value -> GroupsIoSyncSettings(value.optInt("lookback", 30), value.optInt("maximum", 500), value.optInt("archive", 2_000),
            value.optBoolean("attachments"), value.optLong("attachment_bytes", 8L * 1024 * 1024), value.optInt("retention", 180),
            value.optBoolean("open", true), value.optInt("cadence", 15), value.optBoolean("muted"), value.optInt("drafts", 365), value.optBoolean("preview")) }
    }.getOrNull() } ?: GroupsIoSyncSettings()

    private fun loadGroupOverrides(): Map<Long, GroupsIoGroupOverride> = settings.getString("group_overrides_v1", null)?.let { text -> runCatching {
        val rows = JSONArray(text); buildMap { (0 until rows.length()).forEach { index -> rows.getJSONObject(index).let { value -> put(value.getLong("id"),
            GroupsIoGroupOverride(value.optBoolean("enabled", true), value.optBoolean("inherit", true), value.optInt("lookback", 30),
                value.optInt("maximum", 500), value.optBoolean("attachments"), value.optInt("retention", 180))) } } }
    }.getOrNull() } ?: emptyMap()

    private fun saveGroupOverrides() { settings.edit().putString("group_overrides_v1", JSONArray().apply { groupOverrides.forEach { (id, value) -> put(JSONObject().apply {
        put("id", id); put("enabled", value.enabled); put("inherit", value.inheritGlobal); put("lookback", value.lookbackDays)
        put("maximum", value.maximumMessages); put("attachments", value.attachments); put("retention", value.retentionDays)
    }) } }.toString()).apply() }

    private fun replaceOperation(block: suspend CoroutineScope.() -> Unit) {
        operation?.cancel(); operation = scope.launch {
            withContext(Dispatchers.Main) { busy = true; status = "Contacting Groups.io…" }
            try { block() }
            catch (_: CancellationException) { withContext(Dispatchers.Main) { status = "Operation cancelled" } }
            catch (error: Throwable) { publishFailure("memberships", "", error) }
            finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    private suspend fun publishFailure(scopeName: String, scopeId: String, error: Throwable) {
        val apiError = error as? GroupsIoApiException
        val category = apiError?.category ?: "server"
        val message = apiError?.message ?: error.message?.take(120).orEmpty().ifBlank { "Groups.io request failed" }
        runCatching { db().recordFailure(scopeName, scopeId, category, message) }
        withContext(Dispatchers.Main) { status = "$message · downloaded content preserved" }
    }
}

@Composable
fun GroupsIoScreen(controller: GroupsIoController, compact: Boolean) {
    var query by remember { mutableStateOf("") }
    var showOffline by remember { mutableStateOf(false) }
    var onlineSearch by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Groups.io", color = Color(0xFFF4F0E8), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(controller.status, color = Color(0xFFA5ADB2), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(controller::openNewTopic, enabled = controller.capabilities?.canPost == true) { Text("New Topic") }
                TextButton(controller::openDraftsOutbox) { Text("Drafts & Outbox") }
                TextButton({ showOffline = true }, enabled = controller.selectedGroupId != null) { Text("Offline Storage") }
                TextButton({ controller.syncMemberships() }, enabled = controller.connected && !controller.busy) { Text("Sync") }
                Text("${controller.groups.size} groups · ${controller.cacheStats.topics} topics · ${controller.cacheStats.messages} messages${controller.cacheStats.downloadedAttachments.takeIf { it > 0 }?.let { " · $it files" }.orEmpty()} · ${controller.storageBytes / 1024} KiB",
                    color = Color(0xFFA5ADB2), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        if (!controller.connected) {
            Text("Connect an API key in Settings → Integrations. Downloaded content remains available offline.", color = Color(0xFFA5ADB2))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !onlineSearch, onClick = { onlineSearch = false; controller.search(query) }, label = { Text("Downloaded") })
            FilterChip(selected = onlineSearch, onClick = { onlineSearch = true }, label = { Text("Groups.io") })
            OutlinedTextField(query, { query = it; if (!onlineSearch) controller.search(it) }, label = { Text(if (onlineSearch) "Search selected group online" else "Search downloaded content") }, modifier = Modifier.weight(1f), singleLine = true)
            if (onlineSearch) Button({ controller.searchOnline(query) }, enabled = query.isNotBlank() && controller.selectedGroupId != null && controller.connected) { Text("Search") }
        }
        val visibleResults = if (onlineSearch) controller.onlineSearchResults else controller.searchResults
        if (visibleResults.isNotEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(visibleResults, key = { "${it.groupId}:${it.messageNumber}" }) { result ->
                val timestamp = groupsIoTimestampText(result.createdMillis)
                ListItem(headlineContent = { Text(result.topicSubject) }, supportingContent = { Text("${result.groupName} · ${result.author} · ${timestamp.row}\n${result.snippet}", maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.semantics { contentDescription = "${result.topicSubject}; ${timestamp.accessibility}" }
                        .clickable { query = ""; if (onlineSearch) controller.openOnlineSearchResult(result) else controller.openSearchResult(result) })
            }
            if (onlineSearch && controller.onlineSearchHasMore) item { OutlinedButton({ controller.searchOnline(query, loadMore = true) }) { Text("Load More") } }
            }
        } else if (!compact) {
            AdjustableGroupsDetail(controller, Modifier.fillMaxSize())
        } else {
            when {
                controller.selectedTopicId != null -> GroupsMessages(controller, Modifier.fillMaxSize())
                controller.selectedGroupId != null -> GroupsTopics(controller, Modifier.fillMaxSize())
                else -> GroupsList(controller, Modifier.fillMaxSize())
            }
        }
    }
    GroupsIoPhase2Overlays(controller)
    if (showOffline) GroupsIoOfflineDialog(controller) { showOffline = false }
    }
}

@Composable
fun GroupsIoNewMessageAlert(controller: GroupsIoController, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    controller.newMessagePreview?.let { message ->
        val group = controller.groups.firstOrNull { it.id == message.groupId }
        Card(modifier.fillMaxWidth(.34f).widthIn(min = 300.dp, max = 520.dp).heightIn(max = 280.dp).padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF25323A))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NEW GROUPS.IO MESSAGE", color = Color(0xFFE9A72B), fontWeight = FontWeight.Black)
                Text(group?.title ?: "Group ${message.groupId}", style = MaterialTheme.typography.labelMedium)
                Text(message.subject.ifBlank { "Message #${message.number}" }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${message.author} · ${groupsIoTimestampText(message.createdMillis).row}", style = MaterialTheme.typography.labelSmall)
                Text(normaliseBody(message.body).take(240), maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row {
                    TextButton({ controller.openNewMessagePreview(); onOpen() }) { Text("OPEN") }
                    TextButton(controller::dismissNewMessagePreview) { Text("DISMISS") }
                    TextButton(controller::muteNewMessageGroup) { Text("MUTE THIS GROUP") }
                }
            }
        }
    }
}

@Composable private fun GroupsList(controller: GroupsIoController, modifier: Modifier) = LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (controller.groups.isEmpty()) item { Text("No downloaded memberships. Connect and sync, or reconnect when online.", color = Color(0xFFA5ADB2), modifier = Modifier.padding(12.dp)) }
    items(controller.groups, key = { it.id }) { group ->
        val accent = groupsIoAccent(group.name)
        ListItem(headlineContent = { Text(group.title, fontWeight = if (controller.selectedGroupId == group.id) FontWeight.Bold else FontWeight.Medium) },
            supportingContent = { Text(if (group.active) group.name else "${group.name} · cached/inactive") },
            leadingContent = { Surface(shape = CircleShape, color = accent.copy(alpha = .22f), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(groupInitials(group.title), color = accent, fontWeight = FontWeight.Black) }
            } },
            trailingContent = { if (!group.archivesVisible) Text("Restricted", color = Color(0xFFF4C94E)) },
            colors = ListItemDefaults.colors(containerColor = if (controller.selectedGroupId == group.id) accent.copy(alpha = .12f) else Color(0xFF1B2228)),
            modifier = Modifier.clickable { controller.selectGroup(group.id) })
    }
}

@Composable private fun AdjustableGroupsDetail(controller: GroupsIoController, modifier: Modifier) {
    var groupsWidth by rememberSaveable { mutableFloatStateOf(310f) }
    var topicsWidth by rememberSaveable { mutableFloatStateOf(360f) }
    val density = LocalDensity.current
    Row(modifier) {
        GroupsList(controller, Modifier.width(groupsWidth.dp).fillMaxHeight())
        GroupsResizeHandle { pixels -> with(density) { groupsWidth = (groupsWidth + pixels.toDp().value).coerceIn(230f, 560f) } }
        GroupsTopics(controller, Modifier.width(topicsWidth.dp).fillMaxHeight())
        GroupsResizeHandle { pixels -> with(density) { topicsWidth = (topicsWidth + pixels.toDp().value).coerceIn(260f, 680f) } }
        GroupsMessages(controller, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable private fun GroupsResizeHandle(onDrag: (Float) -> Unit) = Box(
    Modifier.width(12.dp).fillMaxHeight().pointerInput(Unit) {
        detectHorizontalDragGestures { change, amount -> change.consume(); onDrag(amount) }
    }.background(Color(0xFF38424A)), contentAlignment = Alignment.Center
) { Box(Modifier.width(3.dp).height(56.dp).background(Color(0xFFE9A72B), CircleShape)) }

@Composable private fun GroupsTopics(controller: GroupsIoController, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(controller.selectedGroupId) { listState.scrollToItem(0) }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (controller.selectedGroupId == null) item { Text("Select a group", color = Color(0xFFA5ADB2), modifier = Modifier.padding(16.dp)) }
    else if (controller.topics.isEmpty()) item { Text("No downloaded topics for this group", color = Color(0xFFA5ADB2), modifier = Modifier.padding(16.dp)) }
    items(controller.topics, key = { it.id }) { topic ->
        val accent = groupsIoAccent(controller.groups.firstOrNull { it.id == topic.groupId }?.name.orEmpty())
        val timestamp = groupsIoTimestampText(topic.updatedMillis)
        ListItem(headlineContent = { Text(topic.subject, fontWeight = if (controller.selectedTopicId == topic.id) FontWeight.Bold else FontWeight.Medium) },
            supportingContent = { Text("${topic.messageCount} messages${if (topic.closed) " · closed" else ""} · ${timestamp.row}") },
            leadingContent = { Box(Modifier.width(5.dp).height(46.dp).background(accent, CircleShape)) },
            colors = ListItemDefaults.colors(containerColor = if (controller.selectedTopicId == topic.id) accent.copy(alpha = .12f) else Color(0xFF1B2228)),
            modifier = Modifier.semantics { contentDescription = "${topic.subject}; ${timestamp.accessibility}" }.clickable { controller.selectTopic(topic.id) })
    }
    if (controller.topicsHaveMore) item { OutlinedButton(controller::loadOlderTopics, enabled = !controller.busy, modifier = Modifier.padding(12.dp)) { Text("Load Older Topics") } }
    }
}

@Composable private fun GroupsMessages(controller: GroupsIoController, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(controller.selectedTopicId) { listState.scrollToItem(0) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (controller.selectedTopicId == null) item { Text("Select a topic", color = Color(0xFFA5ADB2)) }
    else if (controller.messages.isEmpty()) item { Text("No downloaded messages in this thread", color = Color(0xFFA5ADB2)) }
    val newest = controller.messages.maxOfOrNull(GroupsIoMessage::number)
    items(controller.messages, key = { "${it.groupId}:${it.number}" }) { message ->
        val accent = groupsIoAccent(controller.groups.firstOrNull { it.id == message.groupId }?.name.orEmpty())
        val timestamp = groupsIoTimestampText(message.createdMillis)
        Surface(color = Color(0xFF1B2228), shape = MaterialTheme.shapes.medium) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = CircleShape, color = accent.copy(alpha = .22f), modifier = Modifier.size(46.dp)) { Box(contentAlignment = Alignment.Center) {
                Text(if (message.number == newest) "N" else message.author.firstOrNull()?.uppercase().orEmpty(), color = accent, fontWeight = FontWeight.Black)
            } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(message.author, fontWeight = FontWeight.Bold)
                    Text("${if (message.number == newest) "NEWEST · " else ""}#${message.number}", color = if (message.number == newest) accent else Color(0xFFA5ADB2))
                }
                if (message.subject.isNotBlank()) Text(message.subject, color = accent)
                Text(timestamp.detail, color = Color(0xFFA5ADB2), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.semantics { contentDescription = timestamp.accessibility })
                Text(if (message.deleted) "Message unavailable or deleted" else message.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ controller.openReply(message) }, enabled = controller.capabilities?.canReply == true) { Text("Reply") }
                    if (message.hasAttachments) TextButton({ controller.openAttachments(message) }) { Text("Attachments / images") }
                }
            }
        } }
    }
    if (controller.messagesHaveMore) item { OutlinedButton(controller::loadMoreMessages, enabled = !controller.busy) { Text("Load More Messages") } }
    }
}

private val groupsIoAccents = listOf(Color(0xFFE9A72B), Color(0xFF42C77B), Color(0xFF65A6C7), Color(0xFFC481D8), Color(0xFFE47D72), Color(0xFF8FA7E8))
private fun groupsIoAccent(key: String): Color = groupsIoAccents[(key.hashCode() and Int.MAX_VALUE) % groupsIoAccents.size]
private fun groupInitials(value: String): String = value.split(Regex("[^A-Za-z0-9]+")).filter(String::isNotBlank).take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifBlank { "G" }

@Composable
fun GroupsIoSettingsPanel(controller: GroupsIoController, openGroupsIo: () -> Unit) {
    var candidate by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf<Set<Long>>(emptySet()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Groups.io enabled", fontWeight = FontWeight.Bold); Text("Disabled by default; no sync or startup work when off", style = MaterialTheme.typography.bodySmall) }
            Switch(controller.enabled, controller::updateEnabled)
        }
        Text("Use a Groups.io API key, not your password. The key carries your account permissions; revoke it at Groups.io if compromised.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(candidate, { candidate = it }, label = { Text("Groups.io API key") }, visualTransformation = PasswordVisualTransformation(), enabled = controller.enabled && !controller.connected, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ controller.connectAndVerify(candidate); candidate = "" }, enabled = controller.enabled && !controller.connected && candidate.isNotBlank() && !controller.busy) { Text("Connect and Verify") }
            OutlinedButton({ controller.syncMemberships() }, enabled = controller.enabled && controller.connected && !controller.busy) { Text("Sync Now") }
            OutlinedButton(openGroupsIo, enabled = controller.enabled) { Text("Open Groups.io") }
        }
        Text(controller.status, color = if (controller.connected) Color(0xFF42C77B) else Color(0xFFA5ADB2))
        Text("Downloaded storage: ${controller.storageBytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("SYNC & STORAGE DEFAULTS", fontWeight = FontWeight.Black)
        Text("Automatic work runs only while ShackCQ is foregrounded. No permanent background polling.", style = MaterialTheme.typography.bodySmall)
        Text("New-message alerts are shown when ShackCQ refreshes Groups.io while active.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(7, 30, 90, 365).forEach { days -> FilterChip(controller.syncSettings.defaultLookbackDays == days,
                { controller.updateSyncSettings(controller.syncSettings.copy(defaultLookbackDays = days)) }, { Text("$days day lookback") }) }
            listOf(250, 500, 1_000, 5_000).forEach { count -> FilterChip(controller.syncSettings.defaultMaximumMessages == count,
                { controller.updateSyncSettings(controller.syncSettings.copy(defaultMaximumMessages = count)) }, { Text("$count messages") }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(controller.syncSettings.refreshOnOpen, { controller.updateSyncSettings(controller.syncSettings.copy(refreshOnOpen = !controller.syncSettings.refreshOnOpen)) }, { Text("REFRESH ON OPEN") })
            listOf(5, 15, 30, 60).forEach { minutes -> FilterChip(controller.syncSettings.foregroundRefreshMinutes == minutes,
                { controller.updateSyncSettings(controller.syncSettings.copy(foregroundRefreshMinutes = minutes)) }, { Text("${minutes}m foreground") }) }
            FilterChip(controller.syncSettings.attachmentAutoDownload, { controller.updateSyncSettings(controller.syncSettings.copy(attachmentAutoDownload = !controller.syncSettings.attachmentAutoDownload)) }, { Text("AUTO-DOWNLOAD ATTACHMENTS") })
            FilterChip(controller.syncSettings.showNewMessagePreview, { controller.updateSyncSettings(controller.syncSettings.copy(showNewMessagePreview = !controller.syncSettings.showNewMessagePreview)) }, { Text("TOP-RIGHT NEW MESSAGE PREVIEW") })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(30, 90, 180, 365).forEach { days -> FilterChip(controller.syncSettings.offlineRetentionDays == days,
                { controller.updateSyncSettings(controller.syncSettings.copy(offlineRetentionDays = days)) }, { Text("$days day retention") }) }
            listOf(2L, 8L, 20L).forEach { mib -> FilterChip(controller.syncSettings.maximumAttachmentBytes == mib * 1024 * 1024,
                { controller.updateSyncSettings(controller.syncSettings.copy(maximumAttachmentBytes = mib * 1024 * 1024)) }, { Text("$mib MiB attachments") }) }
            FilterChip(controller.syncSettings.includeMutedGroups, { controller.updateSyncSettings(controller.syncSettings.copy(includeMutedGroups = !controller.syncSettings.includeMutedGroups)) }, { Text("INCLUDE MUTED") })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(500, 2_000, 10_000).forEach { depth -> FilterChip(controller.syncSettings.defaultArchiveDepth == depth,
                { controller.updateSyncSettings(controller.syncSettings.copy(defaultArchiveDepth = depth)) }, { Text("$depth archive depth") }) }
            listOf(30, 180, 365, 730).forEach { days -> FilterChip(controller.syncSettings.draftRetentionDays == days,
                { controller.updateSyncSettings(controller.syncSettings.copy(draftRetentionDays = days)) }, { Text("$days day drafts") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(controller::applyGlobalToAll, enabled = controller.groups.isNotEmpty()) { Text("APPLY GLOBAL TO ALL") }
            OutlinedButton({ controller.applyGlobalToGroups(selectedGroups); selectedGroups = emptySet() }, enabled = selectedGroups.isNotEmpty()) { Text("APPLY TO SELECTED") }
            OutlinedButton({ selectedGroups.forEach(controller::resetGroupOverride); selectedGroups = emptySet() }, enabled = selectedGroups.isNotEmpty()) { Text("RESET SELECTED OVERRIDES") }
            OutlinedButton({ controller.maybeForegroundRefresh(force = true) }, enabled = controller.connected && !controller.busy) { Text("REFRESH ALL ENABLED") }
        }
        Text("PER-GROUP OVERRIDES", fontWeight = FontWeight.Black)
        Text("Group | Enabled | Inherit | Lookback | Max messages | Attachments | Retention | Reset",
            style = MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = when { maxWidth >= 1_050.dp -> 3; maxWidth >= 680.dp -> 2; else -> 1 }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            controller.groups.chunked(columns).forEach { groupRow ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                groupRow.forEach { group ->
            val override = controller.groupOverrides[group.id] ?: GroupsIoGroupOverride()
            Card(Modifier.weight(1f)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group.id in selectedGroups, { checked -> selectedGroups = if (checked) selectedGroups + group.id else selectedGroups - group.id })
                        Text(group.title, fontWeight = FontWeight.Bold)
                    }
                    Switch(override.enabled, { controller.updateGroupOverride(group.id, override.copy(enabled = it)) })
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column { Text("INHERIT", style = MaterialTheme.typography.labelSmall); Switch(override.inheritGlobal, { controller.updateGroupOverride(group.id, override.copy(inheritGlobal = it)) }) }
                    if (!override.inheritGlobal) {
                        OutlinedTextField(override.lookbackDays.toString(), { controller.updateGroupOverride(group.id, override.copy(lookbackDays = it.toIntOrNull() ?: override.lookbackDays)) }, label = { Text("Lookback days") }, modifier = Modifier.width(130.dp), singleLine = true)
                        OutlinedTextField(override.maximumMessages.toString(), { controller.updateGroupOverride(group.id, override.copy(maximumMessages = it.toIntOrNull() ?: override.maximumMessages)) }, label = { Text("Max messages") }, modifier = Modifier.width(140.dp), singleLine = true)
                        Column { Text("ATTACHMENTS", style = MaterialTheme.typography.labelSmall); Switch(override.attachments, { controller.updateGroupOverride(group.id, override.copy(attachments = it)) }) }
                        OutlinedTextField(override.retentionDays.toString(), { controller.updateGroupOverride(group.id, override.copy(retentionDays = it.toIntOrNull() ?: override.retentionDays)) }, label = { Text("Retention days") }, modifier = Modifier.width(130.dp), singleLine = true)
                    }
                    OutlinedButton({ controller.resetGroupOverride(group.id) }) { Text("RESET") }
                    TextButton({ controller.selectGroup(group.id) }) { Text("EDIT DETAILS") }
                }
                Text("Archive ${if (group.downloadArchives) "available" else "not downloaded"} · last sync ${groupsIoTimestampText(group.lastSyncMillis).row}", style = MaterialTheme.typography.bodySmall)
            } }
                }
                repeat(columns - groupRow.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            }
        }
        TextButton({ controller.disconnect() }, enabled = controller.connected) { Text("Disconnect Groups.io") }
        TextButton({ confirmDelete = true }) { Text("Clear Downloaded Groups.io Cache") }
        TextButton({ confirmDeleteAll = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete All Local Groups.io Data") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Clear downloaded Groups.io cache?") },
        text = { Text("Remote topics, messages, search rows, incoming attachments and archive exports are removed. Local drafts, queued messages, outgoing attachments and the API key are preserved.") },
        confirmButton = { Button({ controller.clearDownloadedCache(); confirmDelete = false }) { Text("Clear Cache") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    if (confirmDeleteAll) AlertDialog(onDismissRequest = { confirmDeleteAll = false }, title = { Text("Delete all local Groups.io data?") },
        text = { Text("This permanently deletes local drafts, queued messages, outgoing and incoming files, exports and the separate Groups.io database. The API key remains until Disconnect. Unsent items require deliberate confirmation.") },
        confirmButton = { Button({ controller.deleteAllLocalData(); confirmDeleteAll = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete All Local Data") } },
        dismissButton = { TextButton({ confirmDeleteAll = false }) { Text("Cancel") } })
}
