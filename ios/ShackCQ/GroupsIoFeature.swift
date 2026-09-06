import Foundation
import SwiftUI
import SQLite3
import Security
import CryptoKit
import UniformTypeIdentifiers

private let groupsIoDatabaseFilename = "shackcq-groupsio.sqlite"
private let groupsIoSQLiteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

struct GroupsIoGroup: Identifiable, Hashable {
    let id: Int64
    let name: String
    let title: String
    let summary: String
    let membershipStatus: String
    let archivesVisible: Bool
    let active: Bool
    let lastSync: Date
}

struct GroupsIoTopic: Identifiable, Hashable {
    let id: Int64
    let groupId: Int64
    let subject: String
    let updated: Date
    let messageCount: Int
    let closed: Bool
    let firstMessageNumber: Int64?
    let latestMessageNumber: Int64?
}

struct GroupsIoMessage: Identifiable, Hashable {
    var id: String { "\(groupId):\(number)" }
    let apiId: Int64?
    let groupId: Int64
    let topicId: Int64
    let number: Int64
    let replyToNumber: Int64?
    let subject: String
    let author: String
    let created: Date
    let body: String
    let moderated: Bool
    let deleted: Bool
    let hasAttachments: Bool
}

struct GroupsIoSearchHit: Identifiable, Hashable {
    var id: String { "\(groupId):\(messageNumber)" }
    let groupId: Int64
    let topicId: Int64
    let messageNumber: Int64
    let groupName: String
    let topicSubject: String
    let author: String
    let created: Date
    let snippet: String
}

enum GroupsIoDraftKind: String, CaseIterable { case newTopic = "new_topic", reply }
enum GroupsIoReplyDestination: String, CaseIterable {
    case group = "", sender, groupAndSender = "group_and_sender", moderators = "mods"
    var label: String { switch self { case .group: "Reply to Group"; case .sender: "Reply to Sender"; case .groupAndSender: "Reply to Group and Sender"; case .moderators: "Reply to Moderators" } }
}
enum GroupsIoOutboxState: String {
    case draftLocal = "draft_local", queued, creatingRemote = "creating_remote", updatingRemote = "updating_remote", uploading, readyToPost = "ready_to_post", posting, posted, pendingModeration = "pending_moderation", failedRetryable = "failed_retryable", needsAttention = "needs_attention", deliveryUnknown = "delivery_unknown"
    var label: String { switch self {
    case .draftLocal: "Local Draft"; case .queued: "Queued"; case .creatingRemote, .updatingRemote, .uploading, .readyToPost, .posting: "Sending"
    case .posted: "Recently Submitted"; case .pendingModeration: "Pending Moderation"; case .failedRetryable: "Retry Available"; case .needsAttention: "Needs Attention"; case .deliveryUnknown: "Delivery Could Not Be Confirmed"
    } }
}

struct GroupsIoLocalDraft: Identifiable, Hashable {
    let id: String
    let groupId: Int64
    var topicId: Int64?
    var replyMessageNumber: Int64?
    var replyApiMessageId: Int64?
    var remoteDraftId: Int64?
    var kind: GroupsIoDraftKind
    var subject: String
    var body: String
    var replyDestination: GroupsIoReplyDestination?
    var state: GroupsIoOutboxState
    var sendWhenOnline: Bool
    var pendingModeration: Bool
    var deliveryUnknown: Bool
    let created: Date
    var updated: Date
}

struct GroupsIoDraftAttachment: Identifiable, Hashable {
    let id: String
    let draftId: String
    var remoteId: Int64?
    let filename: String
    let mediaType: String
    let byteSize: Int64
    let relativePath: String
    let sha256: String
    var uploadState: String
}

struct GroupsIoIncomingAttachment: Identifiable, Hashable {
    let id: Int64
    let filename: String
    let mediaType: String
    let size: Int64?
    fileprivate let transientURL: URL?
}

struct GroupsIoServerDraft: Identifiable, Hashable {
    let id: Int64
    let groupId: Int64
    let draftType: String
    let messageId: Int64?
    let subject: String
    let body: String
    let attachmentCount: Int
}

struct GroupsIoCapabilities: Hashable {
    var archivesVisible = false
    var canPost = false
    var canReply = false
    var downloadArchives = false
    var postStatus = ""
    var maxAttachmentSize: Int64?
    var defaultReplyPolicy: String?
    var syncedAt: Date?
    var isStale: Bool { syncedAt.map { Date().timeIntervalSince($0) > 21_600 } ?? true }
}

private struct GroupsIoPage<Value> {
    let values: [Value]
    let nextPageToken: String?
    let hasMore: Bool
}

private final class GroupsIoDatabase {
    private var handle: OpaquePointer?
    let fileURL: URL

    init() throws {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = support.appendingPathComponent("ShackCQ/GroupsIO", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var mutableDirectory = directory; try? mutableDirectory.setResourceValues(values)
        fileURL = directory.appendingPathComponent(groupsIoDatabaseFilename)
        guard sqlite3_open_v2(fileURL.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            throw GroupsIoError.storage("Could not open the separate Groups.io database")
        }
        try execute("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;")
        try createSchema()
        var mutableFile = fileURL; try? mutableFile.setResourceValues(values)
    }

    deinit { sqlite3_close(handle) }

    private func createSchema() throws {
        let version = try scalarInt("PRAGMA user_version", [])
        if version > 2 { throw GroupsIoError.compatibility }
        if version == 1 { try migrateVersionOneToTwo(); return }
        guard version == 0 else { return }
        try transaction {
            try execute("""
        CREATE TABLE IF NOT EXISTS groups(
          group_id INTEGER PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '',
          parent_group_id INTEGER, membership_status TEXT NOT NULL DEFAULT '', archive_visibility TEXT NOT NULL DEFAULT '',
          can_read INTEGER NOT NULL DEFAULT 0, can_post INTEGER NOT NULL DEFAULT 0, last_activity REAL,
          active INTEGER NOT NULL DEFAULT 1, first_seen REAL NOT NULL, last_seen REAL NOT NULL, last_successful_sync REAL NOT NULL,
          can_reply INTEGER NOT NULL DEFAULT 0, download_archives INTEGER NOT NULL DEFAULT 0, post_status TEXT NOT NULL DEFAULT '',
          max_attachment_size INTEGER, default_reply_policy TEXT, permissions_synced_at REAL);
        CREATE TABLE IF NOT EXISTS topics(
          topic_id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
          subject TEXT NOT NULL, created REAL, updated REAL NOT NULL, message_count INTEGER NOT NULL DEFAULT 0,
          closed INTEGER NOT NULL DEFAULT 0, followed INTEGER, muted INTEGER, first_message_number INTEGER,
          latest_message_number INTEGER, last_successful_sync REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS messages(
          row_id INTEGER PRIMARY KEY AUTOINCREMENT, api_message_id INTEGER, group_id INTEGER NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
          topic_id INTEGER NOT NULL REFERENCES topics(topic_id) ON DELETE CASCADE, message_number INTEGER NOT NULL,
          reply_to_number INTEGER, subject TEXT NOT NULL, author_name TEXT NOT NULL, created REAL NOT NULL, updated REAL,
          body_plain TEXT NOT NULL, display_body TEXT NOT NULL, moderated INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
          has_attachments INTEGER NOT NULL DEFAULT 0, last_successful_sync REAL NOT NULL, reply_policy TEXT,
          quoted_plain TEXT, remainder_plain TEXT, attachments_synced_at REAL, UNIQUE(group_id,message_number));
        CREATE TABLE IF NOT EXISTS sync_state(
          scope TEXT NOT NULL, scope_id TEXT NOT NULL DEFAULT '', last_attempt REAL, last_success REAL, current_cursor TEXT,
          completed_cursor TEXT, last_error_category TEXT, last_error_text TEXT, has_more INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(scope,scope_id));
        CREATE INDEX IF NOT EXISTS topics_group_latest ON topics(group_id,updated DESC);
        CREATE INDEX IF NOT EXISTS messages_topic_number ON messages(topic_id,message_number);
        CREATE INDEX IF NOT EXISTS messages_group_created ON messages(group_id,created DESC);
        CREATE INDEX IF NOT EXISTS sync_state_scope ON sync_state(scope,scope_id);
        CREATE VIRTUAL TABLE IF NOT EXISTS message_search USING fts5(group_name,topic_subject,message_subject,author_name,body_plain);
        """)
            try createVersionTwoTables()
            try execute("PRAGMA user_version=2")
        }
    }

    private func migrateVersionOneToTwo() throws {
        try transaction {
            try execute("""
            ALTER TABLE groups ADD COLUMN can_reply INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE groups ADD COLUMN download_archives INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE groups ADD COLUMN post_status TEXT NOT NULL DEFAULT '';
            ALTER TABLE groups ADD COLUMN max_attachment_size INTEGER;
            ALTER TABLE groups ADD COLUMN default_reply_policy TEXT;
            ALTER TABLE groups ADD COLUMN permissions_synced_at REAL;
            ALTER TABLE messages ADD COLUMN reply_policy TEXT;
            ALTER TABLE messages ADD COLUMN quoted_plain TEXT;
            ALTER TABLE messages ADD COLUMN remainder_plain TEXT;
            ALTER TABLE messages ADD COLUMN attachments_synced_at REAL;
            """)
            try createVersionTwoTables()
            try execute("PRAGMA user_version=2")
        }
    }

    private func createVersionTwoTables() throws {
        try execute("""
        CREATE TABLE IF NOT EXISTS message_attachments(
          group_id INTEGER NOT NULL,message_number INTEGER NOT NULL,attachment_id INTEGER NOT NULL,filename TEXT NOT NULL,
          media_type TEXT NOT NULL DEFAULT '',reported_size INTEGER,local_relative_path TEXT,local_size INTEGER,sha256 TEXT,
          download_state TEXT NOT NULL DEFAULT 'remote',downloaded_at REAL,last_error TEXT,PRIMARY KEY(group_id,message_number,attachment_id));
        CREATE TABLE IF NOT EXISTS local_drafts(
          local_id TEXT PRIMARY KEY,group_id INTEGER NOT NULL,topic_id INTEGER,reply_message_number INTEGER,reply_api_message_id INTEGER,
          remote_draft_id INTEGER,draft_type TEXT NOT NULL,subject TEXT NOT NULL DEFAULT '',body_plain TEXT NOT NULL DEFAULT '',reply_destination TEXT,
          state TEXT NOT NULL DEFAULT 'draft_local',send_when_online INTEGER NOT NULL DEFAULT 0,pending_moderation INTEGER NOT NULL DEFAULT 0,
          delivery_unknown INTEGER NOT NULL DEFAULT 0,created_at REAL NOT NULL,updated_at REAL NOT NULL,last_attempt_at REAL,last_error_category TEXT,last_error_text TEXT);
        CREATE TABLE IF NOT EXISTS draft_attachments(
          local_id TEXT PRIMARY KEY,draft_local_id TEXT NOT NULL REFERENCES local_drafts(local_id) ON DELETE CASCADE,remote_attachment_id INTEGER,
          filename TEXT NOT NULL,media_type TEXT NOT NULL DEFAULT '',byte_size INTEGER NOT NULL,local_relative_path TEXT NOT NULL,sha256 TEXT NOT NULL,
          upload_state TEXT NOT NULL DEFAULT 'queued',last_error TEXT);
        CREATE TABLE IF NOT EXISTS server_drafts(
          remote_draft_id INTEGER PRIMARY KEY,group_id INTEGER NOT NULL,draft_type TEXT NOT NULL,message_id INTEGER,subject TEXT NOT NULL DEFAULT '',
          body_plain TEXT NOT NULL DEFAULT '',attachment_count INTEGER NOT NULL DEFAULT 0,created_at REAL,updated_at REAL,synced_at REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS archive_exports(
          group_id INTEGER NOT NULL,relative_path TEXT NOT NULL,requested_at REAL NOT NULL,completed_at REAL,start_message_number INTEGER,
          byte_size INTEGER,sha256 TEXT,state TEXT NOT NULL,last_error TEXT,PRIMARY KEY(group_id,relative_path));
        CREATE INDEX IF NOT EXISTS local_drafts_state_updated ON local_drafts(state,updated_at DESC);
        CREATE INDEX IF NOT EXISTS local_drafts_group ON local_drafts(group_id);
        CREATE INDEX IF NOT EXISTS draft_attachments_draft ON draft_attachments(draft_local_id);
        CREATE INDEX IF NOT EXISTS message_attachments_message ON message_attachments(group_id,message_number);
        CREATE INDEX IF NOT EXISTS archive_exports_group ON archive_exports(group_id);
        CREATE INDEX IF NOT EXISTS server_drafts_group_updated ON server_drafts(group_id,updated_at DESC);
        """)
    }

    func applyMemberships(_ groups: [GroupsIoGroup], completed: Bool, at: Date) throws {
        try transaction {
            for group in groups {
                try run("""
                    INSERT INTO groups(group_id,name,title,summary,membership_status,archive_visibility,can_read,active,first_seen,last_seen,last_successful_sync)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(group_id) DO UPDATE SET name=excluded.name,title=excluded.title,summary=excluded.summary,
                    membership_status=excluded.membership_status,archive_visibility=excluded.archive_visibility,can_read=excluded.can_read,active=1,
                    last_seen=excluded.last_seen,last_successful_sync=excluded.last_successful_sync
                    """,
                    [.integer(group.id), .text(group.name), .text(group.title), .text(group.summary), .text(group.membershipStatus),
                     .text(group.archivesVisible ? "visible" : "restricted"), .integer(group.archivesVisible ? 1 : 0), .integer(1),
                     .real(at.timeIntervalSince1970), .real(at.timeIntervalSince1970), .real(at.timeIntervalSince1970)])
                try refreshSearch(groupId: group.id)
            }
            if completed {
                let ids = groups.map { String($0.id) }.joined(separator: ",")
                try execute(ids.isEmpty ? "UPDATE groups SET active=0" : "UPDATE groups SET active=0 WHERE group_id NOT IN (\(ids))")
            }
            try recordSuccess(scope: "memberships", scopeId: "", at: at, next: nil, hasMore: false)
        }
    }

    func applyTopics(groupId: Int64, topics: [GroupsIoTopic], next: String?, hasMore: Bool, at: Date) throws {
        try transaction {
            for topic in topics {
                try run("""
                    INSERT INTO topics(topic_id,group_id,subject,updated,message_count,closed,first_message_number,latest_message_number,last_successful_sync)
                    VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(topic_id) DO UPDATE SET subject=excluded.subject,updated=excluded.updated,
                    message_count=excluded.message_count,closed=excluded.closed,first_message_number=excluded.first_message_number,
                    latest_message_number=excluded.latest_message_number,last_successful_sync=excluded.last_successful_sync
                    """,
                    [.integer(topic.id), .integer(groupId), .text(topic.subject), .real(topic.updated.timeIntervalSince1970),
                     .integer(Int64(topic.messageCount)), .integer(topic.closed ? 1 : 0), .optionalInteger(topic.firstMessageNumber),
                     .optionalInteger(topic.latestMessageNumber), .real(at.timeIntervalSince1970)])
                try refreshSearch(topicId: topic.id)
            }
            try recordSuccess(scope: "topics", scopeId: String(groupId), at: at, next: next, hasMore: hasMore)
        }
    }

    func applyMessages(groupId: Int64, topicId: Int64, messages: [GroupsIoMessage], next: String?, hasMore: Bool, at: Date) throws {
        try transaction {
            for message in messages {
                try run("""
                    INSERT INTO messages(api_message_id,group_id,topic_id,message_number,reply_to_number,subject,author_name,created,body_plain,display_body,moderated,deleted,has_attachments,last_successful_sync)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(group_id,message_number) DO UPDATE SET api_message_id=excluded.api_message_id,
                    topic_id=excluded.topic_id,reply_to_number=excluded.reply_to_number,subject=excluded.subject,author_name=excluded.author_name,
                    created=excluded.created,body_plain=excluded.body_plain,display_body=excluded.display_body,moderated=excluded.moderated,
                    deleted=excluded.deleted,has_attachments=excluded.has_attachments,last_successful_sync=excluded.last_successful_sync
                    """,
                    [.optionalInteger(message.apiId), .integer(groupId), .integer(topicId), .integer(message.number),
                     .optionalInteger(message.replyToNumber), .text(message.subject), .text(message.author), .real(message.created.timeIntervalSince1970),
                     .text(message.body), .text(message.body), .integer(message.moderated ? 1 : 0), .integer(message.deleted ? 1 : 0),
                     .integer(message.hasAttachments ? 1 : 0), .real(at.timeIntervalSince1970)])
                let rowId = try scalarInt("SELECT row_id FROM messages WHERE group_id=? AND message_number=?", [.integer(groupId), .integer(message.number)])
                try refreshSearch(rowId: rowId)
            }
            try recordSuccess(scope: "messages", scopeId: String(topicId), at: at, next: next, hasMore: hasMore)
        }
    }

    func groups(limit: Int = 100) throws -> [GroupsIoGroup] {
        try query("SELECT group_id,name,title,summary,membership_status,can_read,active,last_successful_sync FROM groups ORDER BY active DESC,title COLLATE NOCASE LIMIT ?", [.integer(Int64(min(max(limit, 1), 100)))]) { row in
            GroupsIoGroup(id: row.int(0), name: row.text(1), title: row.text(2), summary: row.text(3), membershipStatus: row.text(4), archivesVisible: row.int(5) != 0, active: row.int(6) != 0, lastSync: Date(timeIntervalSince1970: row.double(7)))
        }
    }

    func topics(groupId: Int64, limit: Int = 50) throws -> [GroupsIoTopic] {
        try query("SELECT topic_id,group_id,subject,updated,message_count,closed,first_message_number,latest_message_number FROM topics WHERE group_id=? ORDER BY updated DESC LIMIT ?", [.integer(groupId), .integer(Int64(min(max(limit, 1), 100)))]) { row in
            GroupsIoTopic(id: row.int(0), groupId: row.int(1), subject: row.text(2), updated: Date(timeIntervalSince1970: row.double(3)), messageCount: Int(row.int(4)), closed: row.int(5) != 0, firstMessageNumber: row.optionalInt(6), latestMessageNumber: row.optionalInt(7))
        }
    }

    func messages(topicId: Int64, limit: Int = 100) throws -> [GroupsIoMessage] {
        try query("SELECT api_message_id,group_id,topic_id,message_number,reply_to_number,subject,author_name,created,body_plain,moderated,deleted,has_attachments FROM messages WHERE topic_id=? ORDER BY message_number LIMIT ?", [.integer(topicId), .integer(Int64(min(max(limit, 1), 100)))]) { row in
            GroupsIoMessage(apiId: row.optionalInt(0), groupId: row.int(1), topicId: row.int(2), number: row.int(3), replyToNumber: row.optionalInt(4), subject: row.text(5), author: row.text(6), created: Date(timeIntervalSince1970: row.double(7)), body: row.text(8), moderated: row.int(9) != 0, deleted: row.int(10) != 0, hasAttachments: row.int(11) != 0)
        }
    }

    func search(_ text: String, groupId: Int64? = nil, limit: Int = 40) throws -> [GroupsIoSearchHit] {
        let terms = text.split(whereSeparator: { $0.isWhitespace }).map { "\"\($0.replacingOccurrences(of: "\"", with: "\"\""))\"*" }.joined(separator: " AND ")
        guard !terms.isEmpty else { return [] }
        var sql = """
            SELECT m.group_id,m.topic_id,m.message_number,g.title,t.subject,m.author_name,m.created,
            snippet(message_search,4,'[',']',' … ',18) FROM message_search JOIN messages m ON m.row_id=message_search.rowid
            JOIN groups g ON g.group_id=m.group_id JOIN topics t ON t.topic_id=m.topic_id WHERE message_search MATCH ?
            """
        var bindings: [SQLiteValue] = [.text(terms)]
        if let groupId { sql += " AND m.group_id=?"; bindings.append(.integer(groupId)) }
        sql += " ORDER BY bm25(message_search) LIMIT ?"; bindings.append(.integer(Int64(min(max(limit, 1), 100))))
        return try query(sql, bindings) { row in
            GroupsIoSearchHit(groupId: row.int(0), topicId: row.int(1), messageNumber: row.int(2), groupName: row.text(3), topicSubject: row.text(4), author: row.text(5), created: Date(timeIntervalSince1970: row.double(6)), snippet: row.text(7))
        }
    }

    func capabilities(groupId: Int64) throws -> GroupsIoCapabilities? {
        try query("SELECT can_read,can_post,can_reply,download_archives,post_status,max_attachment_size,default_reply_policy,permissions_synced_at FROM groups WHERE group_id=?", [.integer(groupId)]) { row in
            GroupsIoCapabilities(archivesVisible: row.int(0) != 0, canPost: row.int(1) != 0, canReply: row.int(2) != 0,
                downloadArchives: row.int(3) != 0, postStatus: row.text(4), maxAttachmentSize: row.optionalInt(5),
                defaultReplyPolicy: row.text(6).nonEmpty, syncedAt: row.optionalDouble(7).map(Date.init(timeIntervalSince1970:)))
        }.first
    }

    func updateCapabilities(groupId: Int64, value: GroupsIoCapabilities) throws {
        try run("UPDATE groups SET can_read=?,can_post=?,can_reply=?,download_archives=?,post_status=?,max_attachment_size=?,default_reply_policy=?,permissions_synced_at=? WHERE group_id=?",
            [.integer(value.archivesVisible ? 1 : 0), .integer(value.canPost ? 1 : 0), .integer(value.canReply ? 1 : 0), .integer(value.downloadArchives ? 1 : 0),
             .text(value.postStatus), .optionalInteger(value.maxAttachmentSize), value.defaultReplyPolicy.map(SQLiteValue.text) ?? .null,
             value.syncedAt.map { .real($0.timeIntervalSince1970) } ?? .null, .integer(groupId)])
    }

    func saveDraft(_ value: GroupsIoLocalDraft) throws {
        try run("""
            INSERT INTO local_drafts(local_id,group_id,topic_id,reply_message_number,reply_api_message_id,remote_draft_id,draft_type,subject,body_plain,reply_destination,state,send_when_online,pending_moderation,delivery_unknown,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(local_id) DO UPDATE SET topic_id=excluded.topic_id,reply_message_number=excluded.reply_message_number,
            reply_api_message_id=excluded.reply_api_message_id,remote_draft_id=excluded.remote_draft_id,subject=excluded.subject,body_plain=excluded.body_plain,
            reply_destination=excluded.reply_destination,state=excluded.state,send_when_online=excluded.send_when_online,pending_moderation=excluded.pending_moderation,
            delivery_unknown=excluded.delivery_unknown,updated_at=excluded.updated_at
            """, [.text(value.id), .integer(value.groupId), .optionalInteger(value.topicId), .optionalInteger(value.replyMessageNumber), .optionalInteger(value.replyApiMessageId),
                  .optionalInteger(value.remoteDraftId), .text(value.kind.rawValue), .text(value.subject), .text(value.body), value.replyDestination.map { .text($0.rawValue) } ?? .null,
                  .text(value.state.rawValue), .integer(value.sendWhenOnline ? 1 : 0), .integer(value.pendingModeration ? 1 : 0), .integer(value.deliveryUnknown ? 1 : 0),
                  .real(value.created.timeIntervalSince1970), .real(value.updated.timeIntervalSince1970)])
    }

    func drafts(limit: Int = 100) throws -> [GroupsIoLocalDraft] {
        try query("SELECT local_id,group_id,topic_id,reply_message_number,reply_api_message_id,remote_draft_id,draft_type,subject,body_plain,reply_destination,state,send_when_online,pending_moderation,delivery_unknown,created_at,updated_at FROM local_drafts ORDER BY updated_at DESC LIMIT ?", [.integer(Int64(min(max(limit, 1), 100)))]) { row in
            GroupsIoLocalDraft(id: row.text(0), groupId: row.int(1), topicId: row.optionalInt(2), replyMessageNumber: row.optionalInt(3), replyApiMessageId: row.optionalInt(4),
                remoteDraftId: row.optionalInt(5), kind: GroupsIoDraftKind(rawValue: row.text(6)) ?? .newTopic, subject: row.text(7), body: row.text(8),
                replyDestination: GroupsIoReplyDestination(rawValue: row.text(9)), state: GroupsIoOutboxState(rawValue: row.text(10)) ?? .needsAttention,
                sendWhenOnline: row.int(11) != 0, pendingModeration: row.int(12) != 0, deliveryUnknown: row.int(13) != 0,
                created: Date(timeIntervalSince1970: row.double(14)), updated: Date(timeIntervalSince1970: row.double(15)))
        }
    }

    func saveDraftAttachment(_ value: GroupsIoDraftAttachment) throws {
        try run("""
            INSERT INTO draft_attachments(local_id,draft_local_id,remote_attachment_id,filename,media_type,byte_size,local_relative_path,sha256,upload_state)
            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(local_id) DO UPDATE SET remote_attachment_id=excluded.remote_attachment_id,upload_state=excluded.upload_state,last_error=NULL
            """, [.text(value.id), .text(value.draftId), .optionalInteger(value.remoteId), .text(value.filename), .text(value.mediaType), .integer(value.byteSize), .text(value.relativePath), .text(value.sha256), .text(value.uploadState)])
    }

    func draftAttachments(draftId: String) throws -> [GroupsIoDraftAttachment] {
        try query("SELECT local_id,draft_local_id,remote_attachment_id,filename,media_type,byte_size,local_relative_path,sha256,upload_state FROM draft_attachments WHERE draft_local_id=? ORDER BY filename COLLATE NOCASE", [.text(draftId)]) { row in
            GroupsIoDraftAttachment(id: row.text(0), draftId: row.text(1), remoteId: row.optionalInt(2), filename: row.text(3), mediaType: row.text(4), byteSize: row.int(5), relativePath: row.text(6), sha256: row.text(7), uploadState: row.text(8))
        }
    }

    func markDraftAttachmentUploaded(id: String, remoteId: Int64) throws {
        try run("UPDATE draft_attachments SET remote_attachment_id=?,upload_state='uploaded',last_error=NULL WHERE local_id=?", [.integer(remoteId), .text(id)])
    }

    func deleteDraftAttachment(id: String) throws { try run("DELETE FROM draft_attachments WHERE local_id=?", [.text(id)]) }

    func saveServerDrafts(_ values: [GroupsIoServerDraft], syncedAt: Date = Date()) throws {
        try transaction {
            for value in values {
                try run("""
                    INSERT INTO server_drafts(remote_draft_id,group_id,draft_type,message_id,subject,body_plain,attachment_count,synced_at)
                    VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(remote_draft_id) DO UPDATE SET group_id=excluded.group_id,draft_type=excluded.draft_type,
                    message_id=excluded.message_id,subject=excluded.subject,body_plain=excluded.body_plain,attachment_count=excluded.attachment_count,synced_at=excluded.synced_at
                    """, [.integer(value.id), .integer(value.groupId), .text(value.draftType), .optionalInteger(value.messageId), .text(value.subject),
                          .text(value.body), .integer(Int64(value.attachmentCount)), .real(syncedAt.timeIntervalSince1970)])
            }
        }
    }

    func deleteServerDraft(id: Int64) throws { try run("DELETE FROM server_drafts WHERE remote_draft_id=?", [.integer(id)]) }

    func saveIncomingAttachment(groupId: Int64, messageNumber: Int64, value: GroupsIoIncomingAttachment, relativePath: String? = nil, localSize: Int64? = nil, sha256: String? = nil) throws {
        try run("""
            INSERT INTO message_attachments(group_id,message_number,attachment_id,filename,media_type,reported_size,local_relative_path,local_size,sha256,download_state,downloaded_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(group_id,message_number,attachment_id) DO UPDATE SET filename=excluded.filename,media_type=excluded.media_type,
            reported_size=excluded.reported_size,local_relative_path=COALESCE(excluded.local_relative_path,message_attachments.local_relative_path),
            local_size=COALESCE(excluded.local_size,message_attachments.local_size),sha256=COALESCE(excluded.sha256,message_attachments.sha256),
            download_state=excluded.download_state,downloaded_at=COALESCE(excluded.downloaded_at,message_attachments.downloaded_at),last_error=NULL
            """, [.integer(groupId), .integer(messageNumber), .integer(value.id), .text(value.filename), .text(value.mediaType), .optionalInteger(value.size),
                  relativePath.map(SQLiteValue.text) ?? .null, .optionalInteger(localSize), sha256.map(SQLiteValue.text) ?? .null,
                  .text(relativePath == nil ? "remote" : "downloaded"), relativePath == nil ? .null : .real(Date().timeIntervalSince1970)])
    }

    func incomingAttachments(groupId: Int64, messageNumber: Int64) throws -> [(GroupsIoIncomingAttachment, String?)] {
        try query("SELECT attachment_id,filename,media_type,reported_size,local_relative_path FROM message_attachments WHERE group_id=? AND message_number=? ORDER BY filename COLLATE NOCASE",
            [.integer(groupId), .integer(messageNumber)]) { row in
                (GroupsIoIncomingAttachment(id: row.int(0), filename: row.text(1), mediaType: row.text(2), size: row.optionalInt(3), transientURL: nil), row.text(4).nonEmpty)
            }
    }

    func clearDownloadedCache() throws {
        try transaction {
            try execute("DELETE FROM message_attachments; DELETE FROM message_search; DELETE FROM messages; DELETE FROM topics; DELETE FROM archive_exports; DELETE FROM server_drafts; DELETE FROM sync_state WHERE scope NOT IN ('outbox','server_drafts');")
        }
    }

    func removeGroupArchive(groupId: Int64) throws {
        try transaction {
            try run("DELETE FROM message_search WHERE rowid IN (SELECT row_id FROM messages WHERE group_id=?)", [.integer(groupId)])
            try run("DELETE FROM message_attachments WHERE group_id=?", [.integer(groupId)])
            try run("DELETE FROM messages WHERE group_id=?", [.integer(groupId)])
            try run("DELETE FROM topics WHERE group_id=?", [.integer(groupId)])
            try run("DELETE FROM archive_exports WHERE group_id=?", [.integer(groupId)])
            try run("DELETE FROM sync_state WHERE scope IN ('complete_archive','archive_export') AND scope_id=?", [.text(String(groupId))])
        }
    }

    var storageBytes: Int64 {
        ["", "-wal", "-shm"].reduce(0) { result, suffix in
            let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path + suffix)
            return result + ((attributes?[.size] as? NSNumber)?.int64Value ?? 0)
        }
    }

    func recordFailure(scope: String, scopeId: String, category: String, text: String) throws {
        try run("""
            INSERT INTO sync_state(scope,scope_id,last_attempt,last_error_category,last_error_text) VALUES(?,?,?,?,?)
            ON CONFLICT(scope,scope_id) DO UPDATE SET last_attempt=excluded.last_attempt,last_error_category=excluded.last_error_category,last_error_text=excluded.last_error_text
            """,
            [.text(scope), .text(scopeId), .real(Date().timeIntervalSince1970), .text(category), .text(String(text.prefix(160)))])
    }

    func close() { if let handle { sqlite3_close(handle); self.handle = nil } }

    private func refreshSearch(groupId: Int64) throws {
        let rows = try query("SELECT row_id FROM messages WHERE group_id=?", [.integer(groupId)]) { $0.int(0) }
        for row in rows { try refreshSearch(rowId: row) }
    }

    private func refreshSearch(topicId: Int64) throws {
        let rows = try query("SELECT row_id FROM messages WHERE topic_id=?", [.integer(topicId)]) { $0.int(0) }
        for row in rows { try refreshSearch(rowId: row) }
    }

    private func refreshSearch(rowId: Int64) throws {
        try run("DELETE FROM message_search WHERE rowid=?", [.integer(rowId)])
        try run("""
            INSERT INTO message_search(rowid,group_name,topic_subject,message_subject,author_name,body_plain)
            SELECT m.row_id,g.title,t.subject,m.subject,m.author_name,m.body_plain FROM messages m
            JOIN groups g ON g.group_id=m.group_id JOIN topics t ON t.topic_id=m.topic_id WHERE m.row_id=?
            """, [.integer(rowId)])
    }

    private func recordSuccess(scope: String, scopeId: String, at: Date, next: String?, hasMore: Bool) throws {
        try run("""
            INSERT INTO sync_state(scope,scope_id,last_attempt,last_success,current_cursor,has_more) VALUES(?,?,?,?,?,?)
            ON CONFLICT(scope,scope_id) DO UPDATE SET last_attempt=excluded.last_attempt,last_success=excluded.last_success,
            current_cursor=excluded.current_cursor,has_more=excluded.has_more,last_error_category=NULL,last_error_text=NULL
            """,
            [.text(scope), .text(scopeId), .real(at.timeIntervalSince1970), .real(at.timeIntervalSince1970), next.map(SQLiteValue.text) ?? .null, .integer(hasMore ? 1 : 0)])
    }

    private func transaction(_ action: () throws -> Void) throws {
        try execute("BEGIN IMMEDIATE")
        do { try action(); try execute("COMMIT") } catch { try? execute("ROLLBACK"); throw error }
    }

    private func execute(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(handle, sql, nil, nil, &error) == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? "SQLite operation failed"; sqlite3_free(error)
            throw GroupsIoError.storage(message)
        }
    }

    private func run(_ sql: String, _ values: [SQLiteValue]) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else { throw storageError() }
        defer { sqlite3_finalize(statement) }
        bind(values, to: statement)
        guard sqlite3_step(statement) == SQLITE_DONE else { throw storageError() }
    }

    private func scalarInt(_ sql: String, _ values: [SQLiteValue]) throws -> Int64 {
        try query(sql, values) { $0.int(0) }.first ?? 0
    }

    private func query<T>(_ sql: String, _ values: [SQLiteValue], map: (SQLiteRow) -> T) throws -> [T] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else { throw storageError() }
        defer { sqlite3_finalize(statement) }
        bind(values, to: statement)
        var result: [T] = []
        while sqlite3_step(statement) == SQLITE_ROW { result.append(map(SQLiteRow(statement: statement))) }
        return result
    }

    private func bind(_ values: [SQLiteValue], to statement: OpaquePointer?) {
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            switch value {
            case .integer(let value): sqlite3_bind_int64(statement, index, value)
            case .real(let value): sqlite3_bind_double(statement, index, value)
            case .text(let value): sqlite3_bind_text(statement, index, value, -1, groupsIoSQLiteTransient)
            case .null: sqlite3_bind_null(statement, index)
            }
        }
    }

    private func storageError() -> GroupsIoError { .storage(handle.map { String(cString: sqlite3_errmsg($0)) } ?? "SQLite operation failed") }
}

private enum SQLiteValue {
    case integer(Int64), real(Double), text(String), null
    static func optionalInteger(_ value: Int64?) -> SQLiteValue { value.map(SQLiteValue.integer) ?? .null }
}

private struct SQLiteRow {
    let statement: OpaquePointer?
    func int(_ index: Int32) -> Int64 { sqlite3_column_int64(statement, index) }
    func optionalInt(_ index: Int32) -> Int64? { sqlite3_column_type(statement, index) == SQLITE_NULL ? nil : int(index) }
    func double(_ index: Int32) -> Double { sqlite3_column_double(statement, index) }
    func optionalDouble(_ index: Int32) -> Double? { sqlite3_column_type(statement, index) == SQLITE_NULL ? nil : double(index) }
    func text(_ index: Int32) -> String { sqlite3_column_text(statement, index).map { String(cString: $0) } ?? "" }
}

private final class GroupsIoLiveApi {
    private let base = URL(string: "https://groups.io/api/v1/")!

    func groups(key: String, pageToken: String? = nil) async throws -> GroupsIoPage<GroupsIoGroup> {
        try await page(path: "groups", key: key, pageToken: pageToken) { value in
            let id = try value.requiredInt("id", "group_id")
            let name = try value.requiredString("name", "group_name")
            let permissions = value["perms"] as? [String: Any] ?? value["permissions"] as? [String: Any]
            let archives = permissions?["archives_visible"] as? Bool ?? value["archives_visible"] as? Bool ?? false
            return GroupsIoGroup(id: id, name: name, title: value.firstString("title", "display_name").nonEmpty ?? name,
                summary: value.firstString("description", "desc", "summary"), membershipStatus: value.firstString("status", "subscription_status"),
                archivesVisible: archives, active: true, lastSync: Date())
        }
    }

    func topics(key: String, groupId: Int64, pageToken: String? = nil) async throws -> GroupsIoPage<GroupsIoTopic> {
        try await page(path: "gettopics", key: key, pageToken: pageToken, extra: ["group_id": String(groupId), "sort_dir": "desc"]) { value in
            GroupsIoTopic(id: try value.requiredInt("id", "topic_id"), groupId: groupId, subject: try value.requiredString("subject", "title"),
                updated: value.firstDate("updated", "last_message_time", "created") ?? Date(), messageCount: value.firstInt("message_count", "num_messages", "message_cnt"),
                closed: value["closed"] as? Bool ?? value["locked"] as? Bool ?? false,
                firstMessageNumber: value.firstOptionalInt("first_msg_num", "first_message_number"), latestMessageNumber: value.firstOptionalInt("last_msg_num", "latest_message_number"))
        }
    }

    func messages(key: String, groupId: Int64, topicId: Int64, pageToken: String? = nil) async throws -> GroupsIoPage<GroupsIoMessage> {
        try await page(path: "gettopic", key: key, pageToken: pageToken, extra: ["topic_id": String(topicId), "sort_dir": "asc"]) { value in
            let body = normaliseGroupsIoBody(value.firstString("body", "html_body", "text", "snippet"))
            return GroupsIoMessage(apiId: value.firstOptionalInt("id", "message_id"), groupId: groupId, topicId: topicId,
                number: try value.requiredInt("msg_num", "message_number", "num"), replyToNumber: value.firstOptionalInt("reply_to", "reply_to_msg_num"),
                subject: value.firstString("subject"), author: value.firstString("name", "author_name", "sender_name", "from_name").nonEmpty ?? "Unknown author",
                created: value.firstDate("created", "date") ?? Date(), body: body, moderated: value["moderated"] as? Bool ?? false,
                deleted: value["deleted"] as? Bool ?? false, hasAttachments: value["has_attachments"] as? Bool ?? !(value["attachments"] as? [Any] ?? []).isEmpty)
        }
    }

    private func page<T>(path: String, key: String, pageToken: String?, extra: [String: String] = [:], map: ([String: Any]) throws -> T) async throws -> GroupsIoPage<T> {
        var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        var query = [URLQueryItem(name: "limit", value: "50")]
        query += extra.map { URLQueryItem(name: $0.key, value: $0.value) }
        if let pageToken { query.append(URLQueryItem(name: "page_token", value: pageToken)) }
        components.queryItems = query
        var request = URLRequest(url: components.url!, timeoutInterval: 25)
        request.httpMethod = "GET"; request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw GroupsIoError.server("Invalid Groups.io response") }
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            if !(200..<300).contains(http.statusCode) { throw GroupsIoError.http(status: http.statusCode, type: json?["type"] as? String ?? "") }
            guard let json, json["object"] as? String != "error", let data = json["data"] as? [[String: Any]] else {
                if json?["object"] as? String == "error" { throw GroupsIoError.http(status: http.statusCode, type: json?["type"] as? String ?? "") }
                throw GroupsIoError.compatibility
            }
            let values = try data.map(map)
            let hasMore = json["has_more"] as? Bool ?? false
            let next = json["next_page_token"].map { String(describing: $0) }.flatMap { ($0 == "0" || $0 == "<null>" || $0.isEmpty) ? nil : $0 }
            if hasMore && next == nil { throw GroupsIoError.compatibility }
            return GroupsIoPage(values: values, nextPageToken: next, hasMore: hasMore)
        } catch is CancellationError { throw GroupsIoError.cancelled }
        catch let error as GroupsIoError { throw error }
        catch let error as URLError where error.code == .notConnectedToInternet || error.code == .cannotFindHost { throw GroupsIoError.network }
        catch let error as URLError where error.code == .timedOut { throw GroupsIoError.temporary }
        catch { throw GroupsIoError.server("Groups.io request failed") }
    }
}

private enum GroupsIoError: LocalizedError {
    case network, credential, permission, rateLimited, temporary, compatibility, cancelled, storage(String), server(String)
    static func http(status: Int, type: String) -> GroupsIoError {
        if status == 401 || type == "unauthorized_error" { return .credential }
        if status == 403 || type == "inadequate_permissions" { return .permission }
        if status == 429 { return .rateLimited }
        if status >= 500 { return .temporary }
        return .server("Groups.io request failed (HTTP \(status))")
    }
    var category: String {
        switch self { case .network: "network"; case .credential: "credential"; case .permission: "permission"; case .rateLimited: "rate_limited"; case .temporary: "temporary"; case .compatibility: "compatibility"; case .cancelled: "cancelled"; case .storage: "storage"; case .server: "server" }
    }
    var errorDescription: String? {
        switch self {
        case .network: "No network connection"; case .credential: "API key is invalid or revoked"; case .permission: "Groups.io permission is inadequate"
        case .rateLimited: "Groups.io rate limit reached; try later"; case .temporary: "Groups.io is temporarily unavailable"
        case .compatibility: "Groups.io response format changed"; case .cancelled: "Operation cancelled"
        case .storage(let text), .server(let text): text
        }
    }
}

private extension Dictionary where Key == String, Value == Any {
    func firstString(_ names: String...) -> String { names.compactMap { self[$0] as? String }.first(where: { !$0.isEmpty }) ?? "" }
    func requiredString(_ names: String...) throws -> String { let value = names.compactMap { self[$0] as? String }.first(where: { !$0.isEmpty }) ?? ""; guard !value.isEmpty else { throw GroupsIoError.compatibility }; return value }
    func requiredInt(_ names: String...) throws -> Int64 { guard let value = names.lazy.compactMap({ (self[$0] as? NSNumber)?.int64Value ?? Int64(self[$0] as? String ?? "") }).first else { throw GroupsIoError.compatibility }; return value }
    func firstOptionalInt(_ names: String...) -> Int64? { names.lazy.compactMap { (self[$0] as? NSNumber)?.int64Value ?? Int64(self[$0] as? String ?? "") }.first }
    func firstInt(_ names: String...) -> Int { Int(names.lazy.compactMap { (self[$0] as? NSNumber)?.int64Value ?? Int64(self[$0] as? String ?? "") }.first ?? 0) }
    func firstDate(_ names: String...) -> Date? { names.lazy.compactMap { name in (self[name] as? String).flatMap { ISO8601DateFormatter().date(from: $0) } }.first }
}

private extension String { var nonEmpty: String? { isEmpty ? nil : self } }
private func normaliseGroupsIoBody(_ source: String) -> String {
    source.replacingOccurrences(of: "(?is)<(script|style|iframe|form)[^>]*>.*?</\\1>", with: "", options: .regularExpression)
        .replacingOccurrences(of: "(?i)<br\\s*/?>|</p>|</div>|</blockquote>", with: "\n", options: .regularExpression)
        .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        .replacingOccurrences(of: "&nbsp;", with: " ").replacingOccurrences(of: "&amp;", with: "&")
        .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
        .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'")
        .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
}

@MainActor
final class GroupsIoController: ObservableObject {
    @Published private(set) var enabled: Bool
    @Published private(set) var connected: Bool
    @Published private(set) var busy = false
    @Published private(set) var status: String
    @Published private(set) var lastSync: Date?
    @Published private(set) var groups: [GroupsIoGroup] = []
    @Published private(set) var topics: [GroupsIoTopic] = []
    @Published private(set) var messages: [GroupsIoMessage] = []
    @Published private(set) var searchResults: [GroupsIoSearchHit] = []
    @Published private(set) var selectedGroupId: Int64?
    @Published private(set) var selectedTopicId: Int64?
    @Published private(set) var storageBytes: Int64 = 0
    @Published private(set) var topicsHaveMore = false
    @Published private(set) var messagesHaveMore = false
    @Published private(set) var onlineSearchHasMore = false
    @Published private(set) var capabilities: GroupsIoCapabilities?
    @Published private(set) var localDrafts: [GroupsIoLocalDraft] = []
    @Published private(set) var composerAttachments: [GroupsIoDraftAttachment] = []
    @Published private(set) var serverDrafts: [GroupsIoServerDraft] = []
    @Published private(set) var incomingAttachments: [GroupsIoIncomingAttachment] = []
    @Published private(set) var incomingDownloadedFiles: [Int64: URL] = [:]
    @Published private(set) var archiveState = "not_started"
    @Published private(set) var archiveExportURL: URL?
    @Published var composerDraft: GroupsIoLocalDraft?
    @Published var showDraftsOutbox = false
    @Published var showOfflineStorage = false
    @Published var showIncomingAttachments = false
    private let defaults = UserDefaults.standard
    private let api = GroupsIoLiveApi()
    private let phase2API = GroupsIoPhase2API()
    private var database: GroupsIoDatabase?
    private var operation: Task<Void, Never>?
    private var composerAutosave: Task<Void, Never>?
    private var topicNextToken: String?
    private var messageNextToken: String?
    private var onlineSearchNextToken: String?
    private var lastOnlineSearch = ""
    private var attachmentMessage: GroupsIoMessage?
    private let keyAccount = "groupsIoApiKey"

    init() {
        let hasCredential = !KeychainValue.load(keyAccount).isEmpty
        enabled = defaults.bool(forKey: "groupsIoEnabled")
        connected = hasCredential
        lastSync = defaults.object(forKey: "groupsIoLastSync") as? Date
        status = hasCredential ? "Connected · cached content available" : "Not connected"
    }

    func updateEnabled(_ value: Bool) {
        enabled = value; defaults.set(value, forKey: "groupsIoEnabled")
        if value { loadCachedGroups() } else { if composerDraft != nil { saveComposer() }; operation?.cancel(); busy = false; archiveState = archiveState == "complete" ? "complete" : "paused"; status = "Groups.io disabled · drafts and downloaded data preserved" }
    }

    func loadCachedGroups() {
        guard enabled else { return }
        do { let store = try db(); groups = try store.groups(); storageBytes = store.storageBytes }
        catch { status = error.localizedDescription }
    }

    func connectAndVerify(_ candidate: String) {
        replaceOperation { [weak self] in
            guard let self else { return }
            let key = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty else { throw GroupsIoError.credential }
            var page = try await api.groups(key: key); var all = page.values
            while page.hasMore { try Task.checkCancellation(); page = try await api.groups(key: key, pageToken: page.nextPageToken); all += page.values }
            let now = Date(); try db().applyMemberships(all, completed: true, at: now)
            KeychainValue.save(key, account: keyAccount); defaults.set(now, forKey: "groupsIoLastSync")
            let store = try db(); connected = true; groups = try store.groups(); lastSync = now; storageBytes = store.storageBytes; status = "Connected · memberships synced"
        }
    }

    func syncMemberships() {
        replaceOperation { [weak self] in
            guard let self else { return }
            let key = KeychainValue.load(keyAccount); guard !key.isEmpty else { throw GroupsIoError.credential }
            var page = try await api.groups(key: key); var all = page.values
            while page.hasMore { try Task.checkCancellation(); page = try await api.groups(key: key, pageToken: page.nextPageToken); all += page.values }
            let now = Date(); try db().applyMemberships(all, completed: true, at: now); defaults.set(now, forKey: "groupsIoLastSync")
            let store = try db(); groups = try store.groups(); lastSync = now; storageBytes = store.storageBytes; status = "Memberships synced"
        }
    }

    func selectGroup(_ id: Int64) {
        selectedGroupId = id; selectedTopicId = nil; messages = []; topicsHaveMore = false; topicNextToken = nil; operation?.cancel()
        operation = Task {
            do {
                topics = try db().topics(groupId: id); capabilities = try db().capabilities(groupId: id); status = topics.isEmpty ? "No downloaded topics" : "Showing downloaded topics"
                guard connected else { return }
                let permissionValue = try await phase2API.permissions(key: KeychainValue.load(keyAccount), groupId: id)
                try db().updateCapabilities(groupId: id, value: permissionValue); capabilities = permissionValue
                let page = try await api.topics(key: KeychainValue.load(keyAccount), groupId: id)
                try db().applyTopics(groupId: id, topics: page.values, next: page.nextPageToken, hasMore: page.hasMore, at: Date())
                let store = try db(); topics = try store.topics(groupId: id); topicNextToken = page.nextPageToken; topicsHaveMore = page.hasMore; storageBytes = store.storageBytes; status = "Newest topics synced"
            } catch { preserveFailure(scope: "topics", scopeId: String(id), error: error) }
        }
    }

    func selectTopic(_ id: Int64) {
        guard let groupId = selectedGroupId else { return }
        selectedTopicId = id; messagesHaveMore = false; messageNextToken = nil; operation?.cancel()
        operation = Task {
            do {
                messages = try db().messages(topicId: id); status = messages.isEmpty ? "No downloaded messages" : "Showing downloaded messages"
                guard connected else { return }
                let page = try await api.messages(key: KeychainValue.load(keyAccount), groupId: groupId, topicId: id)
                try db().applyMessages(groupId: groupId, topicId: id, messages: page.values, next: page.nextPageToken, hasMore: page.hasMore, at: Date())
                let store = try db(); messages = try store.messages(topicId: id); messageNextToken = page.nextPageToken; messagesHaveMore = page.hasMore; storageBytes = store.storageBytes; status = "Thread synced"
            } catch { preserveFailure(scope: "messages", scopeId: String(id), error: error) }
        }
    }

    func loadOlderTopics() {
        guard let groupId = selectedGroupId, let token = topicNextToken else { return }
        replaceOperation { [weak self] in
            guard let self else { return }
            let page = try await api.topics(key: KeychainValue.load(keyAccount), groupId: groupId, pageToken: token)
            let store = try db(); try store.applyTopics(groupId: groupId, topics: page.values, next: page.nextPageToken, hasMore: page.hasMore, at: Date())
            topics = try store.topics(groupId: groupId, limit: 100); topicNextToken = page.nextPageToken; topicsHaveMore = page.hasMore; storageBytes = store.storageBytes; status = "Older topic page downloaded"
        }
    }

    func loadMoreMessages() {
        guard let groupId = selectedGroupId, let topicId = selectedTopicId, let token = messageNextToken else { return }
        replaceOperation { [weak self] in
            guard let self else { return }
            let page = try await api.messages(key: KeychainValue.load(keyAccount), groupId: groupId, topicId: topicId, pageToken: token)
            let store = try db(); try store.applyMessages(groupId: groupId, topicId: topicId, messages: page.values, next: page.nextPageToken, hasMore: page.hasMore, at: Date())
            messages = try store.messages(topicId: topicId, limit: 100); messageNextToken = page.nextPageToken; messagesHaveMore = page.hasMore; storageBytes = store.storageBytes; status = "Additional message page downloaded"
        }
    }

    func search(_ text: String, currentGroupOnly: Bool = false) {
        operation?.cancel(); operation = Task {
            try? await Task.sleep(for: .milliseconds(250)); guard !Task.isCancelled else { return }
            do { searchResults = try db().search(text, groupId: currentGroupOnly ? selectedGroupId : nil); status = "Searching downloaded content · \(searchResults.count) results" }
            catch { status = error.localizedDescription }
        }
    }

    func searchOnline(_ text: String, loadMore: Bool = false) {
        guard let groupId = selectedGroupId else { status = "Select one group before searching Groups.io"; return }
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines); guard !query.isEmpty else { searchResults = []; onlineSearchHasMore = false; return }
        if !loadMore { onlineSearchNextToken = nil; searchResults = []; lastOnlineSearch = query }
        replaceOperation { [weak self] in
            guard let self else { return }
            if !loadMore { try await Task.sleep(for: .milliseconds(250)) }
            let root = try await phase2API.onlineSearch(key: KeychainValue.load(keyAccount), groupId: groupId, query: query, pageToken: onlineSearchNextToken)
            let groupName = groups.first(where: { $0.id == groupId })?.name ?? "Group #\(groupId)"
            let values = (root["data"] as? [[String: Any]] ?? []).compactMap { value -> GroupsIoSearchHit? in
                guard let messageNumber = value.firstOptionalInt("msg_num", "message_number"), let topicId = value.firstOptionalInt("topic_id", "thread_id") else { return nil }
                return GroupsIoSearchHit(groupId: groupId, topicId: topicId, messageNumber: messageNumber, groupName: groupName,
                    topicSubject: value.firstString("subject"), author: value.firstString("name", "author_name"), created: value.firstDate("created", "date") ?? Date(),
                    snippet: normaliseGroupsIoBody(value.firstString("snippet", "body")))
            }
            searchResults = loadMore ? searchResults + values : values
            onlineSearchHasMore = root["has_more"] as? Bool ?? false
            onlineSearchNextToken = root["next_page_token"].map(String.init(describing:))
            if onlineSearchHasMore && (onlineSearchNextToken == nil || onlineSearchNextToken == "0") { throw GroupsIoError.compatibility }
            status = "Groups.io online search · \(searchResults.count) result(s)"
        }
    }

    func openSearchResult(_ hit: GroupsIoSearchHit) {
        selectedGroupId = hit.groupId; selectedTopicId = hit.topicId
        do { topics = try db().topics(groupId: hit.groupId); messages = try db().messages(topicId: hit.topicId); searchResults = [] }
        catch { status = error.localizedDescription }
    }

    func openOnlineSearchResult(_ hit: GroupsIoSearchHit) {
        replaceOperation { [weak self] in
            guard let self else { return }; let key = KeychainValue.load(keyAccount)
            _ = try await phase2API.message(key: key, groupId: hit.groupId, messageNumber: hit.messageNumber)
            let page = try await api.messages(key: key, groupId: hit.groupId, topicId: hit.topicId)
            let now = Date()
            try db().applyTopics(groupId: hit.groupId, topics: [GroupsIoTopic(id: hit.topicId, groupId: hit.groupId, subject: hit.topicSubject,
                updated: now, messageCount: page.values.count, closed: false, firstMessageNumber: page.values.map(\.number).min(), latestMessageNumber: page.values.map(\.number).max())], next: nil, hasMore: false, at: now)
            try db().applyMessages(groupId: hit.groupId, topicId: hit.topicId, messages: page.values, next: page.nextPageToken, hasMore: page.hasMore, at: now)
            let attachments = try await phase2API.messageAttachments(key: key, groupId: hit.groupId, messageNumber: hit.messageNumber)
            try attachments.forEach { try self.db().saveIncomingAttachment(groupId: hit.groupId, messageNumber: hit.messageNumber, value: $0) }
            selectedGroupId = hit.groupId; selectedTopicId = hit.topicId; topics = try db().topics(groupId: hit.groupId); messages = try db().messages(topicId: hit.topicId)
            searchResults = []; status = "Online result cached and opened"
        }
    }

    func openNewTopic() {
        guard let groupId = selectedGroupId, capabilities?.canPost == true else { status = "This group is read-only"; return }
        composerDraft = GroupsIoLocalDraft(id: UUID().uuidString, groupId: groupId, kind: .newTopic, subject: "", body: "", state: .draftLocal,
            sendWhenOnline: false, pendingModeration: false, deliveryUnknown: false, created: Date(), updated: Date())
        composerAttachments = []
    }

    func openReply(_ message: GroupsIoMessage) {
        guard capabilities?.canReply == true, topics.first(where: { $0.id == message.topicId })?.closed != true else { status = "Replies are not permitted for this topic"; return }
        composerDraft = GroupsIoLocalDraft(id: UUID().uuidString, groupId: message.groupId, topicId: message.topicId, replyMessageNumber: message.number,
            replyApiMessageId: message.apiId, kind: .reply, subject: message.subject, body: "", replyDestination: .group, state: .draftLocal,
            sendWhenOnline: false, pendingModeration: false, deliveryUnknown: false, created: Date(), updated: Date())
        composerAttachments = []
    }

    func saveComposer() {
        composerAutosave?.cancel()
        guard var draft = composerDraft else { return }; draft.updated = Date(); draft.state = .draftLocal; draft.sendWhenOnline = false
        do { try db().saveDraft(draft); localDrafts = try db().drafts(); composerDraft = draft; status = "Draft saved locally" } catch { status = error.localizedDescription }
    }

    func composerChanged() {
        composerAutosave?.cancel()
        composerAutosave = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(600)); guard !Task.isCancelled else { return }; self?.saveComposer()
        }
    }

    func importComposerFiles(_ urls: [URL]) {
        guard let draft = composerDraft else { return }
        do { let values = try GroupsIoAppleAttachmentStore.importFiles(urls, draftId: draft.id); try values.forEach { try db().saveDraftAttachment($0) }; composerAttachments = try db().draftAttachments(draftId: draft.id); saveComposer(); status = "\(values.count) attachment(s) copied to private storage" }
        catch { status = error.localizedDescription }
    }

    func openLocalDraft(_ draft: GroupsIoLocalDraft) {
        switch draft.state {
        case .deliveryUnknown: status = "Delivery could not be confirmed. Refresh server drafts and recent messages before deliberately deciding what to do."; return
        case .posting, .posted, .pendingModeration: status = draft.state.label; return
        default: break
        }
        do { composerDraft = draft; composerAttachments = try db().draftAttachments(draftId: draft.id); showDraftsOutbox = false }
        catch { status = error.localizedDescription }
    }

    func removeComposerAttachment(_ value: GroupsIoDraftAttachment) {
        guard let draft = composerDraft else { return }
        let removeLocal: () throws -> Void = {
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            try? FileManager.default.removeItem(at: support.appendingPathComponent(value.relativePath))
            try self.db().deleteDraftAttachment(id: value.id); self.composerAttachments.removeAll { $0.id == value.id }
        }
        guard let remoteId = value.remoteId, let draftId = draft.remoteDraftId else { do { try removeLocal(); status = "Attachment removed" } catch { status = error.localizedDescription }; return }
        replaceOperation { [weak self] in
            guard let self else { return }; try await phase2API.deleteAttachment(key: KeychainValue.load(keyAccount), draftId: draftId, attachmentId: remoteId)
            try removeLocal(); status = "Attachment removed locally and from the server draft"
        }
    }

    func queueComposer(sendNow: Bool) {
        guard var draft = composerDraft, !draft.subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { status = "Subject and body are required"; return }
        draft.state = .queued; draft.sendWhenOnline = true; draft.updated = Date()
        do { try db().saveDraft(draft); localDrafts = try db().drafts(); composerDraft = nil; status = sendNow ? "Sending authorised message…" : "Queued for explicit foreground sending"; if sendNow { processQueuedExplicitly(only: draft.id) } }
        catch { status = error.localizedDescription }
    }

    func openDraftsOutbox() { do { localDrafts = try db().drafts(); showDraftsOutbox = true } catch { status = error.localizedDescription } }

    func refreshServerDrafts() {
        guard connected else { status = "Reconnect to refresh server drafts"; return }
        replaceOperation { [weak self] in
            guard let self else { return }; let key = KeychainValue.load(keyAccount)
            var token: String?; var pages = 0; var all: [GroupsIoServerDraft] = []; var hasMore = false
            repeat {
                let root = try await phase2API.serverDrafts(key: key, pageToken: token)
                var values: [GroupsIoServerDraft] = []
                for value in root["data"] as? [[String: Any]] ?? [] {
                    guard let id = value.firstOptionalInt("id", "draft_id"), let groupId = value.firstOptionalInt("group_id") else { continue }
                    var count = value.firstInt("num_attachments", "attachment_count")
                    if count > 0 { let attachments = try await phase2API.draftAttachments(key: key, draftId: id); count = (attachments["data"] as? [[String: Any]])?.count ?? count }
                    values.append(GroupsIoServerDraft(id: id, groupId: groupId, draftType: value.firstString("draft_type"),
                        messageId: value.firstOptionalInt("message_id"), subject: value.firstString("subject"), body: normaliseGroupsIoBody(value.firstString("body")), attachmentCount: count))
                }
                all += values; pages += 1; hasMore = root["has_more"] as? Bool ?? false; token = root["next_page_token"].map(String.init(describing:))
                if hasMore && (token == nil || token == "0") { throw GroupsIoError.compatibility }
            } while hasMore && pages < 10
            try db().saveServerDrafts(all); serverDrafts = all
            status = hasMore ? "Server drafts refreshed to the bounded 500-item limit" : "Server drafts refreshed · \(all.count)"
        }
    }

    func importServerDraft(_ value: GroupsIoServerDraft) {
        let kind: GroupsIoDraftKind = value.draftType.contains("reply") ? .reply : .newTopic
        let draft = GroupsIoLocalDraft(id: UUID().uuidString, groupId: value.groupId, replyApiMessageId: value.messageId, remoteDraftId: value.id,
            kind: kind, subject: value.subject, body: value.body, replyDestination: kind == .reply ? .group : nil, state: .draftLocal,
            sendWhenOnline: false, pendingModeration: false, deliveryUnknown: false, created: Date(), updated: Date())
        do { try db().saveDraft(draft); localDrafts = try db().drafts(); composerAttachments = []; composerDraft = draft; showDraftsOutbox = false; status = "Server draft imported as an editable local draft" }
        catch { status = error.localizedDescription }
    }

    func deleteServerDraft(_ value: GroupsIoServerDraft) {
        replaceOperation { [weak self] in
            guard let self else { return }
            try await phase2API.deleteDraft(key: KeychainValue.load(keyAccount), draftId: value.id)
            try db().deleteServerDraft(id: value.id); serverDrafts.removeAll { $0.id == value.id }; status = "Server draft deleted after confirmation"
        }
    }

    func openIncomingAttachments(_ message: GroupsIoMessage) {
        attachmentMessage = message
        do {
            let cached = try db().incomingAttachments(groupId: message.groupId, messageNumber: message.number)
            incomingAttachments = cached.map(\.0)
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            incomingDownloadedFiles = Dictionary(uniqueKeysWithValues: cached.compactMap { item in item.1.map { (item.0.id, support.appendingPathComponent($0)) } })
            showIncomingAttachments = true
        } catch { status = error.localizedDescription }
        guard connected else { status = incomingAttachments.isEmpty ? "Reconnect to inspect message attachments" : "Showing downloaded attachments offline"; return }
        replaceOperation { [weak self] in
            guard let self else { return }
            incomingAttachments = try await phase2API.messageAttachments(key: KeychainValue.load(keyAccount), groupId: message.groupId, messageNumber: message.number)
            try incomingAttachments.forEach { try self.db().saveIncomingAttachment(groupId: message.groupId, messageNumber: message.number, value: $0) }
            showIncomingAttachments = true
            status = incomingAttachments.isEmpty ? "No downloadable attachments reported" : "Attachment metadata refreshed · choose a file to download"
        }
    }

    func downloadIncomingAttachment(_ value: GroupsIoIncomingAttachment) {
        guard let message = attachmentMessage else { status = "The message is no longer selected"; return }
        replaceOperation { [weak self] in
            guard let self else { return }
            let temporary = try await phase2API.downloadMessageAttachment(key: KeychainValue.load(keyAccount), groupId: message.groupId,
                messageNumber: message.number, attachmentId: value.id)
            defer { try? FileManager.default.removeItem(at: temporary) }
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            let relativeDirectory = "ShackCQ/GroupsIO/attachments/\(message.groupId)/\(message.number)"
            let directory = support.appendingPathComponent(relativeDirectory, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let filename = GroupsIoAppleAttachmentStore.availableFilename(GroupsIoAppleAttachmentStore.sanitize(value.filename), in: directory)
            let final = directory.appendingPathComponent(filename)
            try FileManager.default.moveItem(at: temporary, to: final)
            let attributes = try FileManager.default.attributesOfItem(atPath: final.path)
            let size = (attributes[.size] as? NSNumber)?.int64Value
            try db().saveIncomingAttachment(groupId: message.groupId, messageNumber: message.number, value: value,
                relativePath: "\(relativeDirectory)/\(filename)", localSize: size, sha256: try groupsIoFileSHA256(final))
            incomingDownloadedFiles[value.id] = final; storageBytes = try db().storageBytes; status = "Attachment downloaded to private storage"
        }
    }

    func processQueuedExplicitly(only id: String? = nil) {
        guard enabled, connected, !busy else { status = "Reconnect and enable Groups.io before sending, or wait for the current send"; return }
        replaceOperation { [weak self] in
            guard let self else { return }; let key = KeychainValue.load(keyAccount)
            var candidates = try db().drafts().filter { $0.state == .queued || $0.state == .failedRetryable }
            if let id { candidates = candidates.filter { $0.id == id } }
            for var draft in candidates {
                do {
                    let files = try db().draftAttachments(draftId: draft.id)
                    let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    draft = try await phase2API.send(draft: draft, key: key, attachments: files, root: support,
                        persist: { try self.db().saveDraft($0) }, markUploaded: { try self.db().markDraftAttachmentUploaded(id: $0, remoteId: $1) })
                } catch is GroupsIoDeliveryUnknown {
                    draft.state = .deliveryUnknown; draft.deliveryUnknown = true; draft.sendWhenOnline = false; try db().saveDraft(draft)
                } catch let error as GroupsIoError {
                    switch error { case .network, .rateLimited, .temporary: draft.state = .failedRetryable; default: draft.state = .needsAttention }
                    draft.sendWhenOnline = false; try db().saveDraft(draft)
                } catch {
                    draft.state = .needsAttention; draft.sendWhenOnline = false; try db().saveDraft(draft)
                }
            }
            localDrafts = try db().drafts(); status = "Outbox processing complete"
        }
    }

    func startCompleteArchive() {
        guard let groupId = selectedGroupId, capabilities?.archivesVisible == true else { status = "Archive access is not available"; return }
        replaceOperation { [weak self] in
            guard let self else { return }; archiveState = "syncing"; var token: String?
            repeat {
                try Task.checkCancellation(); let root = try await phase2API.archivePage(key: KeychainValue.load(keyAccount), groupId: groupId, pageToken: token)
                let rows = root["data"] as? [[String: Any]] ?? []; let now = Date(); let grouped = Dictionary(grouping: rows) { ($0["topic_id"] as? NSNumber)?.int64Value ?? ($0["thread_id"] as? NSNumber)?.int64Value ?? 0 }
                for (topicId, values) in grouped where topicId > 0 {
                    let messages = values.compactMap { value -> GroupsIoMessage? in
                        guard let number = (value["msg_num"] as? NSNumber)?.int64Value else { return nil }
                        return GroupsIoMessage(apiId: (value["id"] as? NSNumber)?.int64Value, groupId: groupId, topicId: topicId, number: number,
                            replyToNumber: (value["reply_to"] as? NSNumber)?.int64Value, subject: value["subject"] as? String ?? "",
                            author: value["name"] as? String ?? "Unknown author", created: now, body: normaliseGroupsIoBody(value["body"] as? String ?? ""),
                            moderated: false, deleted: false, hasAttachments: value["has_attachments"] as? Bool ?? false)
                    }
                    if !messages.isEmpty {
                        try db().applyTopics(groupId: groupId, topics: [GroupsIoTopic(id: topicId, groupId: groupId, subject: messages[0].subject, updated: now, messageCount: messages.count, closed: false, firstMessageNumber: messages.map(\.number).min(), latestMessageNumber: messages.map(\.number).max())], next: nil, hasMore: false, at: now)
                        try db().applyMessages(groupId: groupId, topicId: topicId, messages: messages, next: nil, hasMore: false, at: now)
                    }
                }
                let hasMore = root["has_more"] as? Bool ?? false; token = root["next_page_token"].map(String.init(describing:))
                if hasMore && (token == nil || token == "0") { throw GroupsIoError.compatibility }; archiveState = hasMore ? "partial" : "complete"
                if !hasMore { break }
            } while true
            status = "Complete offline archive \(archiveState)"
        }
    }

    func pauseArchive() { operation?.cancel(); if archiveState != "complete" { archiveState = "paused" }; status = "Archive download paused · completed pages preserved" }

    func downloadOfficialArchive() {
        guard let groupId = selectedGroupId, capabilities?.downloadArchives == true else { status = "Official archive export is not permitted"; return }
        replaceOperation { [weak self] in
            guard let self else { return }; let temporary = try await phase2API.downloadOfficialArchive(key: KeychainValue.load(keyAccount), groupId: groupId)
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            let directory = support.appendingPathComponent("ShackCQ/GroupsIO/archive-exports/\(groupId)", isDirectory: true); try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let final = directory.appendingPathComponent("\(Int(Date().timeIntervalSince1970))-archive.zip"); try FileManager.default.moveItem(at: temporary, to: final)
            _ = try groupsIoFileSHA256(final); archiveExportURL = final; status = "Official archive ZIP downloaded · manual share available"
        }
    }

    func removeSelectedGroupArchive() {
        guard let groupId = selectedGroupId else { return }
        operation?.cancel()
        do {
            try db().removeGroupArchive(groupId: groupId)
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            try? FileManager.default.removeItem(at: support.appendingPathComponent("ShackCQ/GroupsIO/attachments/\(groupId)", isDirectory: true))
            try? FileManager.default.removeItem(at: support.appendingPathComponent("ShackCQ/GroupsIO/archive-exports/\(groupId)", isDirectory: true))
            topics = []; messages = []; selectedTopicId = nil; archiveState = "not_started"; archiveExportURL = nil; storageBytes = try db().storageBytes
            status = "Downloaded archive removed for selected group · drafts preserved"
        } catch { status = error.localizedDescription }
    }

    func disconnect() {
        operation?.cancel(); deleteKeychainValue(account: keyAccount); connected = false; busy = false
        status = "Disconnected · downloaded data preserved"
    }

    func clearDownloadedCache() {
        operation?.cancel(); try? db().clearDownloadedCache()
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = support.appendingPathComponent("ShackCQ/GroupsIO", isDirectory: true)
        for name in ["attachments", "archive-exports"] { try? FileManager.default.removeItem(at: directory.appendingPathComponent(name, isDirectory: true)) }
        groups = []; topics = []; messages = []; searchResults = []; incomingAttachments = []; incomingDownloadedFiles = [:]; archiveExportURL = nil; selectedGroupId = nil; selectedTopicId = nil; storageBytes = database?.storageBytes ?? 0
        localDrafts = (try? database?.drafts()) ?? []; status = "Downloaded Groups.io cache cleared · drafts and credential preserved"
    }

    func deleteAllLocalData() {
        operation?.cancel(); database?.close(); database = nil
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = support.appendingPathComponent("ShackCQ/GroupsIO", isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        groups = []; topics = []; messages = []; searchResults = []; localDrafts = []; serverDrafts = []; incomingAttachments = []; incomingDownloadedFiles = [:]; archiveExportURL = nil; selectedGroupId = nil; selectedTopicId = nil; storageBytes = 0
        status = "All local Groups.io data deleted · credential preserved"
    }

    @available(*, deprecated, message: "Use clearDownloadedCache")
    func deleteDownloadedData() { clearDownloadedCache() }

    private func db() throws -> GroupsIoDatabase { if let database { return database }; let value = try GroupsIoDatabase(); database = value; return value }

    private func replaceOperation(_ work: @escaping @MainActor () async throws -> Void) {
        operation?.cancel(); operation = Task {
            busy = true; status = "Contacting Groups.io…"
            do { try await work() } catch { preserveFailure(scope: "memberships", scopeId: "", error: error) }
            busy = false
        }
    }

    private func preserveFailure(scope: String, scopeId: String, error: Error) {
        let typed = error as? GroupsIoError
        try? database?.recordFailure(scope: scope, scopeId: scopeId, category: typed?.category ?? "server", text: error.localizedDescription)
        status = "\(error.localizedDescription) · downloaded content preserved"
    }

    private func deleteKeychainValue(account: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.shackcq.mobile", kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
    }
}

private enum GroupsIoSearchScope: String, CaseIterable, Identifiable { case downloaded = "Downloaded", online = "Groups.io"; var id: Self { self } }

struct GroupsIoView: View {
    @EnvironmentObject private var controller: GroupsIoController
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var search = ""
    @State private var searchScope = GroupsIoSearchScope.downloaded
    @State private var currentGroupOnly = false

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading) { Text("Groups.io").font(.largeTitle.bold()); Text(controller.status).font(.caption).foregroundStyle(.secondary) }
                Spacer()
                Button("New Topic") { controller.openNewTopic() }.disabled(controller.capabilities?.canPost != true)
                Button("Drafts & Outbox") { controller.openDraftsOutbox() }
                Button("Group Offline") { controller.showOfflineStorage = true }.disabled(controller.selectedGroupId == nil)
                Button("Sync") { controller.syncMemberships() }.disabled(!controller.connected || controller.busy)
            }
            Picker("Search scope", selection: $searchScope) { ForEach(GroupsIoSearchScope.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
                .onChange(of: searchScope) { _, _ in performSearch() }
            if searchScope == .downloaded { Toggle("Current group only", isOn: $currentGroupOnly).onChange(of: currentGroupOnly) { _, _ in performSearch() } }
            TextField(searchScope == .downloaded ? "Search downloaded content" : "Search selected group on Groups.io", text: $search).textFieldStyle(.roundedBorder)
                .onChange(of: search) { _, _ in performSearch() }
            if !controller.connected { Text("Connect an API key in Settings → Integrations. Downloaded content remains available offline.").foregroundStyle(.secondary) }
            if !controller.searchResults.isEmpty { searchList }
            else if sizeClass == .regular { HStack(spacing: 0) { groupList.frame(minWidth: 240, idealWidth: 290); Divider(); topicList.frame(minWidth: 280, idealWidth: 330); Divider(); messageList.frame(minWidth: 420) } }
            else if controller.selectedTopicId != nil { messageList }
            else if controller.selectedGroupId != nil { topicList }
            else { groupList }
        }
        .padding().navigationTitle("Groups.io").onAppear { controller.loadCachedGroups() }
        .sheet(item: $controller.composerDraft) { _ in GroupsIoComposerSheet(controller: controller) }
        .sheet(isPresented: $controller.showDraftsOutbox) { GroupsIoDraftsOutboxSheet(controller: controller) }
        .sheet(isPresented: $controller.showOfflineStorage) { GroupsIoOfflineSheet(controller: controller) }
        .sheet(isPresented: $controller.showIncomingAttachments) { GroupsIoIncomingAttachmentsSheet(controller: controller) }
    }

    private var groupList: some View { List(controller.groups) { group in
        Button { controller.selectGroup(group.id) } label: { VStack(alignment: .leading) { Text(group.title); Text(group.active ? group.name : "\(group.name) · cached/inactive").font(.caption).foregroundStyle(.secondary) } }
    }.overlay { if controller.groups.isEmpty { ContentUnavailableView("No downloaded memberships", systemImage: "person.3", description: Text("Connect and sync, or reconnect when online.")) } } }

    private var topicList: some View { List {
        ForEach(controller.topics) { topic in
            Button { controller.selectTopic(topic.id) } label: { VStack(alignment: .leading) { Text(topic.subject); Text("\(topic.messageCount) messages\(topic.closed ? " · closed" : "")").font(.caption).foregroundStyle(.secondary) } }
        }
        if controller.topicsHaveMore { Button("Load Older Topics") { controller.loadOlderTopics() }.disabled(controller.busy) }
    }
    .overlay { if controller.selectedGroupId == nil { ContentUnavailableView("Select a group", systemImage: "text.bubble") } } }

    private var messageList: some View { List {
        ForEach(controller.messages) { message in
            VStack(alignment: .leading, spacing: 8) {
                HStack { Text(message.author).bold(); Spacer(); Text("#\(message.number)").font(.caption).foregroundStyle(.secondary) }
                if !message.subject.isEmpty { Text(message.subject).foregroundStyle(RigTheme.amber) }
                Text(message.deleted ? "Message unavailable or deleted" : message.body).textSelection(.enabled)
                HStack {
                    Button("Reply") { controller.openReply(message) }.disabled(controller.capabilities?.canReply != true)
                    if message.hasAttachments { Button("Attachments · manual download") { controller.openIncomingAttachments(message) }.font(.caption) }
                }
            }.padding(.vertical, 6)
        }
        if controller.messagesHaveMore { Button("Load More Messages") { controller.loadMoreMessages() }.disabled(controller.busy) }
    }
    .overlay { if controller.selectedTopicId == nil { ContentUnavailableView("Select a topic", systemImage: "text.bubble.fill") } } }

    private var searchList: some View { List {
        ForEach(controller.searchResults) { hit in
            Button { if searchScope == .online { controller.openOnlineSearchResult(hit) } else { controller.openSearchResult(hit) } } label: { VStack(alignment: .leading) { Text(hit.topicSubject); Text("\(hit.groupName) · \(hit.author)").font(.caption).foregroundStyle(.secondary); Text(hit.snippet).lineLimit(3) } }
        }
        if searchScope == .online && controller.onlineSearchHasMore { Button("Load More Groups.io Results") { controller.searchOnline(search, loadMore: true) }.disabled(controller.busy) }
    } }

    private func performSearch() {
        if searchScope == .online { controller.searchOnline(search) } else { controller.search(search, currentGroupOnly: currentGroupOnly) }
    }
}

struct GroupsIoSettingsSection: View {
    @EnvironmentObject private var controller: GroupsIoController
    @Environment(\.openURL) private var openURL
    @State private var candidate = ""
    @State private var confirmDelete = false
    @State private var confirmDeleteAll = false

    var body: some View {
        Section("Groups.io") {
            Toggle("Groups.io enabled", isOn: Binding(get: { controller.enabled }, set: controller.updateEnabled))
            Text("Disabled by default. When disabled, no Groups.io network or startup sync occurs; downloaded data and the credential remain intact.").font(.caption).foregroundStyle(.secondary)
            Text("Use a Groups.io API key, not your password. The key carries your account permissions; revoke it at Groups.io if compromised.").font(.caption)
            SecureField("Groups.io API key", text: $candidate).disabled(!controller.enabled || controller.connected)
            Button("Get an API key in Groups.io") { openURL(URL(string: "https://groups.io/settings/apikeys")!) }
            HStack {
                Button("Connect and Verify") { controller.connectAndVerify(candidate); candidate = "" }.disabled(!controller.enabled || controller.connected || candidate.isEmpty || controller.busy)
                Button("Sync Now") { controller.syncMemberships() }.disabled(!controller.enabled || !controller.connected || controller.busy)
            }
            LabeledContent("State", value: controller.connected ? "Connected" : "Disconnected")
            if let date = controller.lastSync { LabeledContent("Last membership sync", value: date.formatted()) }
            LabeledContent("Downloaded storage", value: ByteCountFormatter.string(fromByteCount: controller.storageBytes, countStyle: .file))
            Text(controller.status).font(.caption).foregroundStyle(controller.connected ? RigTheme.green : .secondary)
            if controller.enabled { NavigationLink("Open Groups.io") { GroupsIoView() } }
            Button("Disconnect Groups.io", role: .destructive) { controller.disconnect() }.disabled(!controller.connected)
            Button("Clear Downloaded Groups.io Cache") { confirmDelete = true }
            Button("Delete All Local Groups.io Data", role: .destructive) { confirmDeleteAll = true }
        }
        .confirmationDialog("Clear downloaded Groups.io cache?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Clear Cache", role: .destructive) { controller.clearDownloadedCache() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Remote cache and incoming files are removed. Local drafts, queued messages, outgoing files and the API key are preserved.") }
        .confirmationDialog("Delete all local Groups.io data?", isPresented: $confirmDeleteAll, titleVisibility: .visible) {
            Button("Delete All Local Data", role: .destructive) { controller.deleteAllLocalData() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("This permanently removes local drafts, outbox items, files, exports and the separate Groups.io database. The API key remains until Disconnect.") }
    }
}

private struct GroupsIoDeliveryUnknown: Error {}

private final class GroupsIoPhase2API {
    private let base = URL(string: "https://groups.io/api/v1/")!

    func permissions(key: String, groupId: Int64) async throws -> GroupsIoCapabilities {
        let perms = try await get("getperms", key: key, query: ["group_id": String(groupId)])
        let feed = try await get("getsinglefeed", key: key, query: ["group_id": String(groupId)])
        let member = feed["member_info"] as? [String: Any] ?? [:]
        let group = feed["group"] as? [String: Any] ?? feed["group_info"] as? [String: Any] ?? [:]
        let postStatus = member["post_status"] as? String ?? ""
        return GroupsIoCapabilities(archivesVisible: perms["archives_visible"] as? Bool ?? false,
            canPost: perms["post"] as? Bool ?? !["sub_poststatus_cannotpost", "sub_poststatus_none", "announcement"].contains(postStatus),
            canReply: perms["reply"] as? Bool ?? !["sub_poststatus_cannotpost", "sub_poststatus_none"].contains(postStatus),
            downloadArchives: perms["download_archives"] as? Bool ?? false, postStatus: postStatus,
            maxAttachmentSize: (member["max_attachment_size"] as? NSNumber)?.int64Value ?? (group["max_attachment_size"] as? NSNumber)?.int64Value,
            defaultReplyPolicy: group["reply_to"] as? String, syncedAt: Date())
    }

    func send(draft initial: GroupsIoLocalDraft, key: String, attachments: [GroupsIoDraftAttachment], root: URL,
              persist: (GroupsIoLocalDraft) throws -> Void, markUploaded: (String, Int64) throws -> Void) async throws -> GroupsIoLocalDraft {
        var draft = initial; draft.state = .creatingRemote; try persist(draft)
        var authoritativeMessageId = draft.replyApiMessageId
        if draft.kind == .reply, authoritativeMessageId == nil, let number = draft.replyMessageNumber {
            let message = try await get("getmessage", key: key, query: ["group_id": String(draft.groupId), "msg_num": String(number)])
            let object = message["message"] as? [String: Any] ?? message["data"] as? [String: Any] ?? message
            authoritativeMessageId = (object["id"] as? NSNumber)?.int64Value
        }
        if draft.remoteDraftId == nil {
            var form = ["group_id": String(draft.groupId), "draft_type": draft.kind == .newTopic ? "draft_type_post" : "draft_type_reply"]
            if draft.kind == .reply { guard let authoritativeMessageId else { throw GroupsIoError.compatibility }; form["message_id"] = String(authoritativeMessageId) }
            let created = try await post("newdraft", key: key, form: form); draft.remoteDraftId = (created["id"] as? NSNumber)?.int64Value ?? (created["draft_id"] as? NSNumber)?.int64Value
        }
        guard let remote = draft.remoteDraftId else { throw GroupsIoError.compatibility }
        draft.state = .updatingRemote; draft.replyApiMessageId = authoritativeMessageId; try persist(draft)
        var update = ["draft_id": String(remote), "subject": draft.subject, "body": groupsIoSafeHTML(draft.body), "body_type": "html"]
        if let destination = draft.replyDestination, !destination.rawValue.isEmpty { update["reply_to"] = destination.rawValue }
        _ = try await post("updatedraft", key: key, form: update)
        draft.state = .uploading; try persist(draft)
        for attachment in attachments where attachment.remoteId == nil && attachment.uploadState != "uploaded" {
            let remoteId = try await uploadAttachment(key: key, draftId: remote, value: attachment, file: root.appendingPathComponent(attachment.relativePath))
            try markUploaded(attachment.id, remoteId)
        }
        draft.state = .readyToPost; try persist(draft); draft.state = .posting; try persist(draft)
        do {
            let result = try await post("postdraft", key: key, form: ["draft_id": String(remote)], ambiguousDelivery: true)
            draft.pendingModeration = result["extra"] as? String == "pending post"
            draft.state = draft.pendingModeration ? .pendingModeration : .posted; draft.sendWhenOnline = false; draft.updated = Date(); try persist(draft); return draft
        } catch is GroupsIoDeliveryUnknown { throw GroupsIoDeliveryUnknown() }
    }

    func onlineSearch(key: String, groupId: Int64, query: String, pageToken: String?) async throws -> [String: Any] {
        var values = ["group_id": String(groupId), "q": query, "limit": "50", "exclude_sigs": "true", "collapse_topics": "false", "sort_dir": "relevance"]
        if let pageToken { values["page_token"] = pageToken }; return try await get("searcharchives", key: key, query: values)
    }

    func message(key: String, groupId: Int64, messageNumber: Int64) async throws -> [String: Any] {
        try await get("getmessage", key: key, query: ["group_id": String(groupId), "msg_num": String(messageNumber)])
    }

    func archivePage(key: String, groupId: Int64, pageToken: String?) async throws -> [String: Any] {
        var values = ["group_id": String(groupId), "limit": "100"]; if let pageToken { values["page_token"] = pageToken }
        return try await get("getmessages", key: key, query: values)
    }

    func serverDrafts(key: String, pageToken: String?) async throws -> [String: Any] {
        var values = ["limit": "50"]; if let pageToken { values["page_token"] = pageToken }; return try await get("getdrafts", key: key, query: values)
    }

    func messageAttachments(key: String, groupId: Int64, messageNumber: Int64) async throws -> [GroupsIoIncomingAttachment] {
        let root = try await message(key: key, groupId: groupId, messageNumber: messageNumber)
        let message = root["message"] as? [String: Any] ?? root["data"] as? [String: Any] ?? root
        let values = message["attachments"] as? [[String: Any]] ?? root["attachments"] as? [[String: Any]] ?? []
        return try values.map { value in
            GroupsIoIncomingAttachment(id: try value.requiredInt("id", "attachment_id"),
                filename: GroupsIoAppleAttachmentStore.sanitize(value.firstString("filename").nonEmpty ?? "attachment"),
                mediaType: value.firstString("content_type").nonEmpty ?? "application/octet-stream",
                size: value.firstOptionalInt("size"), transientURL: value.firstString("url").nonEmpty.flatMap { URL(string: $0) })
        }
    }

    func downloadMessageAttachment(key: String, groupId: Int64, messageNumber: Int64, attachmentId: Int64) async throws -> URL {
        guard let current = try await messageAttachments(key: key, groupId: groupId, messageNumber: messageNumber).first(where: { $0.id == attachmentId }),
              let url = current.transientURL, url.scheme?.lowercased() == "https" else { throw GroupsIoError.compatibility }
        if let size = current.size, size > GroupsIoAppleAttachmentStore.ceiling { throw GroupsIoError.storage("Attachment exceeds the 100 MiB mobile safety ceiling") }
        var request = URLRequest(url: url, timeoutInterval: 120); request.httpMethod = "GET"
        // The transient URL may point at a CDN. It is already authorized by the URL,
        // so forwarding the Groups.io bearer token could disclose the credential.
        let (temporary, response) = try await URLSession.shared.download(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { try? FileManager.default.removeItem(at: temporary); throw GroupsIoError.server("Attachment download failed") }
        let attributes = try? FileManager.default.attributesOfItem(atPath: temporary.path)
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        guard size <= GroupsIoAppleAttachmentStore.ceiling else { try? FileManager.default.removeItem(at: temporary); throw GroupsIoError.storage("Attachment exceeds the 100 MiB mobile safety ceiling") }
        return temporary
    }

    func downloadOfficialArchive(key: String, groupId: Int64) async throws -> URL {
        var components = URLComponents(url: base.appendingPathComponent("downloadarchives"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "group_id", value: String(groupId))]
        var request = URLRequest(url: components.url!, timeoutInterval: 300); request.httpMethod = "GET"; request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let (temporary, response) = try await URLSession.shared.download(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { try? FileManager.default.removeItem(at: temporary); throw GroupsIoError.server("Official archive download failed") }
        return temporary
    }

    func draftAttachments(key: String, draftId: Int64) async throws -> [String: Any] { try await get("getattachments", key: key, query: ["draft_id": String(draftId)]) }
    func deleteDraft(key: String, draftId: Int64) async throws { _ = try await post("deletedraft", key: key, form: ["draft_id": String(draftId)]) }
    func deleteAttachment(key: String, draftId: Int64, attachmentId: Int64) async throws { _ = try await post("deleteattachment", key: key, form: ["draft_id": String(draftId), "attachment_id": String(attachmentId)]) }

    private func uploadAttachment(key: String, draftId: Int64, value: GroupsIoDraftAttachment, file: URL) async throws -> Int64 {
        let boundary = "ShackCQ-\(UUID().uuidString)"; let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath: temporary.path, contents: nil); let output = try FileHandle(forWritingTo: temporary)
        defer { try? output.close(); try? FileManager.default.removeItem(at: temporary) }
        func write(_ text: String) throws { try output.write(contentsOf: Data(text.utf8)) }
        try write("--\(boundary)\r\nContent-Disposition: form-data; name=\"draft_id\"\r\n\r\n\(draftId)\r\n")
        try write("--\(boundary)\r\nContent-Disposition: form-data; name=\"inline\"\r\n\r\nfalse\r\n")
        try write("--\(boundary)\r\nContent-Disposition: form-data; name=\"fileupload\"; filename=\"\(GroupsIoAppleAttachmentStore.sanitize(value.filename))\"\r\nContent-Type: \(value.mediaType)\r\n\r\n")
        let input = try FileHandle(forReadingFrom: file); defer { try? input.close() }
        while let data = try input.read(upToCount: 64 * 1024), !data.isEmpty { try output.write(contentsOf: data) }
        try write("\r\n--\(boundary)--\r\n"); try output.synchronize()
        var request = URLRequest(url: base.appendingPathComponent("uploadattachments"), timeoutInterval: 120); request.httpMethod = "POST"
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization"); request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await URLSession.shared.upload(for: request, fromFile: temporary)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let rows = root["data"] as? [[String: Any]], let id = rows.first(where: { $0["filename"] as? String == value.filename })?["id"] as? NSNumber else { throw GroupsIoError.compatibility }
        return id.int64Value
    }

    private func get(_ path: String, key: String, query: [String: String]) async throws -> [String: Any] {
        var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: components.url!, timeoutInterval: 30); request.httpMethod = "GET"; request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Accept")
        return try await perform(request)
    }

    private func post(_ path: String, key: String, form: [String: String], ambiguousDelivery: Bool = false) async throws -> [String: Any] {
        precondition(form["csrf"] == nil && form["password"] == nil && form["api_key"] == nil)
        var components = URLComponents(); components.queryItems = form.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: base.appendingPathComponent(path), timeoutInterval: 30); request.httpMethod = "POST"
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization"); request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = components.percentEncodedQuery?.data(using: .utf8)
        do { return try await perform(request) }
        catch let error as URLError where ambiguousDelivery && [.timedOut, .networkConnectionLost].contains(error.code) { throw GroupsIoDeliveryUnknown() }
    }

    private func perform(_ request: URLRequest) async throws -> [String: Any] {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw GroupsIoError.server("Invalid Groups.io response") }
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if !(200..<300).contains(http.statusCode) || object["object"] as? String == "error" {
            let type = object["type"] as? String ?? ""; if http.statusCode == 401 || type == "unauthorized_error" { throw GroupsIoError.credential }
            if http.statusCode == 403 || type == "inadequate_permissions" { throw GroupsIoError.permission }
            if http.statusCode == 429 { throw GroupsIoError.rateLimited }; if http.statusCode >= 500 { throw GroupsIoError.temporary }
            throw GroupsIoError.server(groupsIoPostingError(type: type))
        }
        return object
    }
}

private func groupsIoSafeHTML(_ body: String, quote: String? = nil) -> String {
    func escape(_ value: String) -> String { value.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "'", with: "&#39;") }
    let paragraphs = body.components(separatedBy: "\n\n").filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.map { "<p>\(escape($0).replacingOccurrences(of: "\n", with: "<br>"))</p>" }.joined(separator: "\n")
    return (quote.map { "<blockquote>\(escape($0).replacingOccurrences(of: "\n", with: "<br>"))</blockquote>\n" } ?? "") + paragraphs
}

private enum GroupsIoAppleAttachmentStore {
    static let ceiling: Int64 = 100 * 1024 * 1024
    static func importFiles(_ urls: [URL], draftId: String) throws -> [GroupsIoDraftAttachment] {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = support.appendingPathComponent("ShackCQ/GroupsIO/outbox/\(draftId)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var result: [GroupsIoDraftAttachment] = []
        for source in urls {
            let scoped = source.startAccessingSecurityScopedResource(); defer { if scoped { source.stopAccessingSecurityScopedResource() } }
            let safe = sanitize(source.lastPathComponent); let target = directory.appendingPathComponent(availableFilename(safe, in: directory))
            let input = try FileHandle(forReadingFrom: source); defer { try? input.close() }
            FileManager.default.createFile(atPath: target.path, contents: nil); let output = try FileHandle(forWritingTo: target); defer { try? output.close() }
            var size: Int64 = 0; var digest = SHA256()
            while let data = try input.read(upToCount: 64 * 1024), !data.isEmpty { size += Int64(data.count); if size > ceiling { try? FileManager.default.removeItem(at: target); throw GroupsIoError.storage("Attachment exceeds the 100 MiB mobile safety ceiling") }; digest.update(data: data); try output.write(contentsOf: data) }
            let hash = digest.finalize().map { String(format: "%02x", $0) }.joined()
            result.append(GroupsIoDraftAttachment(id: UUID().uuidString, draftId: draftId, filename: target.lastPathComponent,
                mediaType: UTType(filenameExtension: source.pathExtension)?.preferredMIMEType ?? "application/octet-stream", byteSize: size,
                relativePath: "ShackCQ/GroupsIO/outbox/\(draftId)/\(target.lastPathComponent)", sha256: hash, uploadState: "queued"))
        }
        return result
    }
    static func sanitize(_ raw: String) -> String {
        let invalid = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:")); let value = raw.components(separatedBy: invalid).joined(separator: "_").replacingOccurrences(of: "..", with: "_").trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        return String(value.prefix(120)).nonEmpty ?? "attachment"
    }
    static func availableFilename(_ safe: String, in directory: URL) -> String {
        guard FileManager.default.fileExists(atPath: directory.appendingPathComponent(safe).path) else { return safe }
        let name = (safe as NSString).deletingPathExtension; let ext = (safe as NSString).pathExtension
        var suffix = 1
        while true {
            let candidate = ext.isEmpty ? "\(name)-\(suffix)" : "\(name)-\(suffix).\(ext)"
            if !FileManager.default.fileExists(atPath: directory.appendingPathComponent(candidate).path) { return candidate }
            suffix += 1
        }
    }
}

private func groupsIoFileSHA256(_ file: URL) throws -> String {
    let input = try FileHandle(forReadingFrom: file); defer { try? input.close() }; var digest = SHA256()
    while let data = try input.read(upToCount: 64 * 1024), !data.isEmpty { digest.update(data: data) }
    return digest.finalize().map { String(format: "%02x", $0) }.joined()
}

private func groupsIoPostingError(type: String) -> String {
    switch type {
    case "need hashtag": "This group requires an approved hashtag in the subject."
    case "restricted hashtag": "You do not have permission to use one of the selected hashtags."
    case "post too big": "The message is larger than this group permits."
    case "announcement group": "Only moderators can post to this announcement group."
    case "invalid reply": "This message can no longer be replied to."
    default: "Groups.io could not complete the request."
    }
}

private struct GroupsIoComposerSheet: View {
    @ObservedObject var controller: GroupsIoController
    @State private var showImporter = false
    @State private var confirmSend: Bool?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            if let draft = controller.composerDraft {
                Form {
                    Section { Text("Destination group #\(draft.groupId)"); TextField("Subject", text: Binding(get: { controller.composerDraft?.subject ?? "" }, set: { controller.composerDraft?.subject = $0; controller.composerChanged() }))
                        TextEditor(text: Binding(get: { controller.composerDraft?.body ?? "" }, set: { controller.composerDraft?.body = $0; controller.composerChanged() })).frame(minHeight: 220) }
                    if draft.kind == .reply { Picker("Reply destination", selection: Binding(get: { controller.composerDraft?.replyDestination ?? .group }, set: { controller.composerDraft?.replyDestination = $0; controller.composerChanged() })) { ForEach(GroupsIoReplyDestination.allCases, id: \.self) { Text($0.label).tag($0) } } }
                    Section("Attachments") {
                        Button("Add Files") { showImporter = true }
                        ForEach(controller.composerAttachments) { value in HStack { VStack(alignment: .leading) { Text(value.filename); Text(ByteCountFormatter.string(fromByteCount: value.byteSize, countStyle: .file)).font(.caption).foregroundStyle(.secondary) }; Spacer(); Button("Remove", role: .destructive) { controller.removeComposerAttachment(value) } } }
                        Text("Files are copied immediately into private GroupsIO/outbox storage; 100 MiB technical ceiling.").font(.caption)
                    }
                    Section { Button("Save Draft") { controller.saveComposer(); dismiss() }; Button("Send When Online") { confirmSend = false }; Button("Send Now") { confirmSend = true }.disabled(!controller.connected) }
                }
                .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in if case .success(let urls) = result { controller.importComposerFiles(urls) } }
                .confirmationDialog("Confirm message action", isPresented: Binding(get: { confirmSend != nil }, set: { if !$0 { confirmSend = nil } })) {
                    Button(confirmSend == true ? "Send Now" : "Queue for Foreground Sending") { controller.queueComposer(sendNow: confirmSend == true); confirmSend = nil; dismiss() }
                    Button("Cancel", role: .cancel) { confirmSend = nil }
                } message: { Text("\(draft.kind == .newTopic ? "New Topic" : draft.replyDestination?.label ?? "Reply") to group #\(draft.groupId), subject “\(draft.subject)”. Sending always requires this explicit confirmation.") }
            }
            else { ContentUnavailableView("No draft", systemImage: "square.and.pencil") }
        }.navigationTitle("Groups.io Composer")
    }
}

private struct GroupsIoDraftsOutboxSheet: View {
    @ObservedObject var controller: GroupsIoController
    @Environment(\.dismiss) private var dismiss
    @State private var deleteCandidate: GroupsIoServerDraft?
    var body: some View { NavigationStack { List {
        Section("Local Drafts & Outbox") { ForEach(controller.localDrafts) { draft in Button { controller.openLocalDraft(draft) } label: { VStack(alignment: .leading) { Text(draft.subject.isEmpty ? "Untitled" : draft.subject); Text(draft.state.label).font(.caption) } } } }
        Section { Button("Process Explicitly Queued Items") { controller.processQueuedExplicitly() }.disabled(!controller.connected || !controller.enabled) }
        Section("Server Drafts") {
            Button("Refresh Server Drafts") { controller.refreshServerDrafts() }.disabled(!controller.connected || controller.busy)
            Text("Fetched only on explicit refresh. Import creates a separate editable local record; local edits are never overwritten.").font(.caption)
            ForEach(controller.serverDrafts) { value in VStack(alignment: .leading, spacing: 5) {
                Text(value.subject.isEmpty ? "Untitled server draft" : value.subject)
                Text("\(value.attachmentCount) attachment(s)").font(.caption).foregroundStyle(.secondary)
                HStack { Button("Import/Open") { controller.importServerDraft(value) }; Button("Delete From Server", role: .destructive) { deleteCandidate = value } }
            }.padding(.vertical, 4) }
        }
    }.navigationTitle("Drafts & Outbox").toolbar { Button("Done") { dismiss() } }
        .confirmationDialog("Delete this draft from Groups.io?", isPresented: Binding(get: { deleteCandidate != nil }, set: { if !$0 { deleteCandidate = nil } }), titleVisibility: .visible) {
            Button("Delete From Groups.io", role: .destructive) { if let value = deleteCandidate { controller.deleteServerDraft(value) }; deleteCandidate = nil }
            Button("Cancel", role: .cancel) { deleteCandidate = nil }
        } message: { Text("This removes the remote draft and its server attachments. Any separately imported local draft remains on this device.") }
    } }
}

private struct GroupsIoIncomingAttachmentsSheet: View {
    @ObservedObject var controller: GroupsIoController
    @Environment(\.dismiss) private var dismiss
    var body: some View { NavigationStack { List(controller.incomingAttachments) { value in
        VStack(alignment: .leading, spacing: 6) {
            Text(value.filename)
            Text(value.size.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? "Size not reported").font(.caption).foregroundStyle(.secondary)
            if let file = controller.incomingDownloadedFiles[value.id] { ShareLink(item: file) { Label("Open or Share Downloaded File", systemImage: "square.and.arrow.up") } }
            else { Button("Download to Private Storage") { controller.downloadIncomingAttachment(value) }.disabled(controller.busy || !controller.connected) }
        }.padding(.vertical, 4)
    }.overlay { if controller.incomingAttachments.isEmpty { ContentUnavailableView("No attachments", systemImage: "paperclip") } }
        .navigationTitle("Message Attachments").toolbar { Button("Done") { dismiss() } } } }
}

private struct GroupsIoOfflineSheet: View {
    @ObservedObject var controller: GroupsIoController
    @Environment(\.dismiss) private var dismiss
    @State private var confirmRemove = false
    var body: some View { NavigationStack { Form {
        Section("Complete Offline Archive") { LabeledContent("State", value: controller.archiveState); Button(controller.archiveState == "partial" || controller.archiveState == "paused" ? "Resume Archive Download" : "Download Complete Offline Archive") { controller.startCompleteArchive() }.disabled(!controller.connected || controller.capabilities?.archivesVisible != true); Button("Pause/Cancel") { controller.pauseArchive() } }
        Section("Official ZIP/MBOX Export") { Text("Manual only. Groups.io limits this resource-intensive endpoint to one request per person/group per 24 hours. MBOX is not parsed.").font(.caption); Button("Download Official Archive ZIP") { controller.downloadOfficialArchive() }.disabled(!controller.connected || controller.capabilities?.downloadArchives != true); if let file = controller.archiveExportURL { ShareLink(item: file) { Label("Share Official Archive ZIP", systemImage: "square.and.arrow.up") } } }
        Section { Button("Remove Downloaded Archive for This Group", role: .destructive) { confirmRemove = true } }
    }.navigationTitle("Group Offline").toolbar { Button("Done") { dismiss() } }
        .confirmationDialog("Remove this group’s downloaded archive?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove Downloaded Archive", role: .destructive) { controller.removeSelectedGroupArchive() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Cached topics, messages, incoming files and exports for this group are removed. Membership, local drafts, queued messages and other groups are preserved.") }
    } }
}
