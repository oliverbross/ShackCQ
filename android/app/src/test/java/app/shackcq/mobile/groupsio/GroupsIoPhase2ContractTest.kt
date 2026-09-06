package app.shackcq.mobile.groupsio

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GroupsIoPhase2ContractTest {
    @Test fun bearerFormAndMultipartContractsNeverContainLoginCookiePasswordOrCsrf() {
        val form = GroupsIoRequest("POST", "/updatedraft", form = mapOf("draft_id" to "7", "subject" to "Test"))
        val multipart = GroupsIoRequest("POST", "/uploadattachments", multipart = GroupsIoMultipart(7, File("fixture.bin"), "fixture.bin", "application/octet-stream"))
        listOf(form, multipart).forEach { request ->
            assertFalse(request.form.keys.any { it.equals("csrf", true) || it.equals("password", true) })
            assertFalse(groupsIoFormBody(request.form).contains("csrf", true))
            assertFalse(request.path.contains("login")); assertFalse(request.query.containsKey("api_key"))
        }
    }

    @Test fun permissionsMapAndGatePostReplyAndArchiveExport() = runBlocking {
        val transport = FakeTransport().apply {
            replies["/getperms"] = JSONObject("""{"archives_visible":true,"post":true,"reply":false,"download_archives":true}""")
            replies["/getsinglefeed"] = JSONObject("""{"member_info":{"post_status":"sub_poststatus_normal","max_attachment_size":4096},"group":{"reply_to":"thread_reply_group_default"}}""")
        }
        val value = GroupsIoPhase2Api(transport).permissions("synthetic", 12)
        assertTrue(value.archivesVisible); assertTrue(value.canPost); assertFalse(value.canReply); assertTrue(value.downloadArchives)
        assertEquals(4096L, value.maxAttachmentSize); assertEquals("thread_reply_group_default", value.defaultReplyPolicy)
    }

    @Test fun newTopicSequenceIsDraftUpdateUploadThenPost() = runBlocking {
        val transport = FakeTransport().apply {
            replies["/newdraft"] = JSONObject("""{"id":88}""")
            replies["/updatedraft"] = JSONObject("""{"id":88}""")
            replies["/postdraft"] = JSONObject("""{"object":"success"}""")
        }
        val api = GroupsIoPhase2Api(transport); val persistence = MemoryPersistence()
        val draft = GroupsIoLocalDraft(groupId = 12, type = GroupsIoDraftType.NEW_TOPIC, subject = "Portable antenna", bodyPlain = "Synthetic body", state = GroupsIoOutboxState.QUEUED, sendWhenOnline = true)
        val result = GroupsIoOutbox(api, persistence, File(".")).process(draft, "synthetic", connected = true, enabled = true)
        assertEquals(GroupsIoOutboxState.POSTED, result.state)
        assertEquals(listOf("/newdraft", "/updatedraft", "/postdraft"), transport.requests.map { it.path })
        assertEquals("draft_type_post", transport.requests.first().form["draft_type"])
    }

    @Test fun replyUsesAuthoritativeMessageIdAndAllowedDestination() = runBlocking {
        val transport = FakeTransport().apply { replies["/newdraft"] = JSONObject("""{"draft_id":91}"""); replies["/updatedraft"] = JSONObject("""{}""") }
        val draft = GroupsIoLocalDraft(groupId = 4, topicId = 8, replyMessageNumber = 22, replyApiMessageId = 12345, type = GroupsIoDraftType.REPLY,
            subject = "Re: Test", bodyPlain = "Reply", replyDestination = GroupsIoReplyDestination.SENDER)
        val api = GroupsIoPhase2Api(transport); val id = api.createDraft("synthetic", draft); api.updateDraft("synthetic", id, draft.subject, draft.bodyPlain, null, draft.replyDestination)
        assertEquals("12345", transport.requests[0].form["message_id"]); assertEquals("draft_type_reply", transport.requests[0].form["draft_type"])
        assertEquals("sender", transport.requests[1].form["reply_to"])
    }

    @Test fun pendingPostBecomesPendingModeration() = runBlocking {
        val transport = FakeTransport().apply { replies["/newdraft"] = JSONObject("""{"id":1}"""); replies["/updatedraft"] = JSONObject("""{}"""); replies["/postdraft"] = JSONObject("""{"extra":"pending post"}""") }
        val result = GroupsIoOutbox(GroupsIoPhase2Api(transport), MemoryPersistence(), File(".")).process(
            GroupsIoLocalDraft(groupId = 1, type = GroupsIoDraftType.NEW_TOPIC, subject = "S", bodyPlain = "B", state = GroupsIoOutboxState.QUEUED, sendWhenOnline = true), "synthetic", true, true)
        assertEquals(GroupsIoOutboxState.PENDING_MODERATION, result.state); assertTrue(result.pendingModeration)
    }

    @Test fun ambiguousPostBecomesDeliveryUnknownAndIsNeverRetried() = runBlocking {
        val transport = FakeTransport().apply { replies["/newdraft"] = JSONObject("""{"id":2}"""); replies["/updatedraft"] = JSONObject("""{}"""); failures["/postdraft"] = GroupsIoDeliveryUnknownException() }
        val outbox = GroupsIoOutbox(GroupsIoPhase2Api(transport), MemoryPersistence(), File("."))
        val first = outbox.process(GroupsIoLocalDraft(groupId = 1, type = GroupsIoDraftType.NEW_TOPIC, subject = "S", bodyPlain = "B", state = GroupsIoOutboxState.QUEUED, sendWhenOnline = true), "synthetic", true, true)
        assertEquals(GroupsIoOutboxState.DELIVERY_UNKNOWN, first.state)
        outbox.process(first, "synthetic", true, true)
        assertEquals(1, transport.requests.count { it.path == "/postdraft" })
    }

    @Test fun safeHtmlEscapesInjectionAndPreservesParagraphsAndQuote() {
        val html = groupsIoSafeHtml("One & <two>\n\nSecond\nline", "Quoted <unsafe>")
        assertEquals("<blockquote>Quoted &lt;unsafe&gt;</blockquote>\n<p>One &amp; &lt;two&gt;</p>\n<p>Second<br>line</p>", html)
        assertFalse(html.contains("<script")); assertFalse(html.contains("<two>"))
    }

    @Test fun filenameSanitisationPreventsTraversalAndControlCharacters() {
        assertEquals("secret.txt", groupsIoSanitizeFilename("../../secret.txt"))
        assertEquals("name_.zip", groupsIoSanitizeFilename("evil/name\u0000.zip"))
        assertFalse(groupsIoSanitizeFilename("../..").contains(".."))
    }

    @Test fun archivePaginationPreservesPagesAndCompletesOnlyAtFinalPage() {
        val first = GroupsIoArchiveProgress(state = "syncing").applyPage(100, 125, "opaque-2", true)
        assertEquals("partial", first.state); assertEquals(100, first.downloaded); assertEquals("opaque-2", first.nextPageToken)
        assertEquals("paused", first.paused().state)
        val final = first.applyPage(25, 125, null, false)
        assertEquals("complete", final.state); assertEquals(125, final.downloaded)
    }

    @Test fun onlineSearchIsBoundedToSelectedGroupAndOpeningUsesGetMessage() = runBlocking {
        val transport = FakeTransport().apply { replies["/searcharchives"] = JSONObject("""{"object":"list","has_more":false,"data":[]}"""); replies["/getmessage"] = JSONObject("""{"message":{"id":7}}""") }
        val api = GroupsIoPhase2Api(transport); api.search("synthetic", 55, "antenna", newest = false); api.message("synthetic", 55, 90)
        assertEquals("55", transport.requests[0].query["group_id"]); assertEquals("antenna", transport.requests[0].query["q"])
        assertEquals("true", transport.requests[0].query["exclude_sigs"]); assertEquals("90", transport.requests[1].query["msg_num"])
    }

    private class MemoryPersistence : GroupsIoOutboxPersistence {
        val saved = mutableListOf<GroupsIoLocalDraft>()
        override fun save(draft: GroupsIoLocalDraft) { saved += draft }
        override fun attachments(localId: String) = emptyList<GroupsIoDraftAttachment>()
        override fun markAttachmentUploaded(localId: String, remoteId: Long) = Unit
    }

    private class FakeTransport : GroupsIoTransport {
        val requests = mutableListOf<GroupsIoRequest>()
        val replies = mutableMapOf<String, JSONObject>()
        val failures = mutableMapOf<String, Throwable>()
        override suspend fun json(request: GroupsIoRequest, bearerKey: String): JSONObject {
            requests += request; failures[request.path]?.let { throw it }; return replies[request.path] ?: JSONObject()
        }
        override suspend fun binary(request: GroupsIoRequest, bearerKey: String, destination: File, ceiling: Long): Long = 0
    }
}
