package app.shackcq.mobile.groupsio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

internal const val GROUPS_IO_API_BASE = "https://groups.io/api/v1"
internal const val GROUPS_IO_ATTACHMENT_CEILING = 100L * 1024L * 1024L
internal const val GROUPS_IO_API_REVISION = "2026-07-31"

internal fun copyGroupsIoBinary(input: InputStream, destination: File, ceiling: Long): Long {
    require(ceiling >= 0)
    destination.parentFile?.mkdirs()
    return try {
        var total = 0L
        BufferedInputStream(input).use { source -> FileOutputStream(destination).buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                if (total > ceiling) throw GroupsIoApiException("storage", "Download exceeds the mobile safety ceiling")
                output.write(buffer, 0, read)
            }
        } }
        total
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}

enum class GroupsIoDraftType(val wire: String) {
    NEW_TOPIC("new_topic"), REPLY("reply");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: NEW_TOPIC }
}

enum class GroupsIoReplyDestination(val wire: String, val label: String) {
    GROUP("", "Reply to Group"), SENDER("sender", "Reply to Sender"),
    GROUP_AND_SENDER("group_and_sender", "Reply to Group and Sender"), MODS("mods", "Reply to Moderators");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: GROUP }
}

enum class GroupsIoOutboxState(val wire: String) {
    DRAFT_LOCAL("draft_local"), QUEUED("queued"), CREATING_REMOTE("creating_remote"),
    UPDATING_REMOTE("updating_remote"), UPLOADING("uploading"), READY_TO_POST("ready_to_post"),
    POSTING("posting"), POSTED("posted"), PENDING_MODERATION("pending_moderation"),
    FAILED_RETRYABLE("failed_retryable"), NEEDS_ATTENTION("needs_attention"), DELIVERY_UNKNOWN("delivery_unknown");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: NEEDS_ATTENTION }
}

data class GroupsIoCapabilities(
    val archivesVisible: Boolean,
    val canPost: Boolean,
    val canReply: Boolean,
    val downloadArchives: Boolean,
    val postStatus: String = "",
    val maxAttachmentSize: Long? = null,
    val defaultReplyPolicy: String? = null,
    val syncedAtMillis: Long? = null,
) {
    val stale: Boolean get() = syncedAtMillis == null || System.currentTimeMillis() - syncedAtMillis > 6 * 60 * 60 * 1000L
}

data class GroupsIoLocalDraft(
    val localId: String = UUID.randomUUID().toString(),
    val groupId: Long,
    val topicId: Long? = null,
    val replyMessageNumber: Long? = null,
    val replyApiMessageId: Long? = null,
    val remoteDraftId: Long? = null,
    val type: GroupsIoDraftType,
    val subject: String = "",
    val bodyPlain: String = "",
    val replyDestination: GroupsIoReplyDestination? = null,
    val state: GroupsIoOutboxState = GroupsIoOutboxState.DRAFT_LOCAL,
    val sendWhenOnline: Boolean = false,
    val pendingModeration: Boolean = false,
    val deliveryUnknown: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val lastAttemptAtMillis: Long? = null,
    val lastErrorCategory: String? = null,
    val lastErrorText: String? = null,
)

data class GroupsIoDraftAttachment(
    val localId: String = UUID.randomUUID().toString(),
    val draftLocalId: String,
    val remoteAttachmentId: Long? = null,
    val filename: String,
    val mediaType: String,
    val byteSize: Long,
    val localRelativePath: String,
    val sha256: String,
    val uploadState: String = "queued",
)

data class GroupsIoRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val form: Map<String, String> = emptyMap(),
    val multipart: GroupsIoMultipart? = null,
    val absoluteHttpsUrl: String? = null,
) {
    init {
        require(path.startsWith("/") && !path.contains(".."))
        absoluteHttpsUrl?.let { require(URL(it).protocol == "https") }
        require("csrf" !in query && "csrf" !in form)
        require(query.keys.none { it.equals("api_key", true) || it.equals("password", true) })
        require(form.keys.none { it.equals("api_key", true) || it.equals("password", true) })
    }
}

data class GroupsIoMultipart(
    val draftId: Long,
    val file: File,
    val filename: String,
    val mediaType: String,
    val inline: Boolean = false,
)

internal fun groupsIoFormBody(values: Map<String, String>): String = values.entries.joinToString("&") {
    "${URLEncoder.encode(it.key, Charsets.UTF_8.name())}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}"
}

internal fun groupsIoSafeHtml(body: String, quote: String? = null): String {
    fun escaped(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
    val paragraphs = body.trim().split(Regex("\\n\\s*\\n")).filter(String::isNotBlank)
        .joinToString("\n") { "<p>${escaped(it).replace("\n", "<br>")}</p>" }
    val quoted = quote?.trim()?.takeIf(String::isNotBlank)?.let { "<blockquote>${escaped(it).replace("\n", "<br>")}</blockquote>\n" }.orEmpty()
    return quoted + paragraphs
}

internal fun groupsIoSanitizeFilename(raw: String): String {
    val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}/\\\\:]"), "_").replace("..", "_").trim().trim('.')
    return leaf.take(120).ifBlank { "attachment" }
}

internal fun groupsIoSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun groupsIoPostingMessage(type: String, extra: String): String = when {
    extra == "pending post" -> "Submitted successfully and awaiting moderator approval."
    type == "need hashtag" -> "This group requires an approved hashtag in the subject."
    type == "restricted hashtag" -> "You do not have permission to use one of the selected hashtags."
    type == "mod only hashtag" -> "Only moderators may use one of the selected hashtags."
    type == "post too big" -> "The message is larger than this group permits."
    type == "announcement group" -> "Only moderators can post to this announcement group."
    type == "group out of space" -> "This group has no remaining storage space."
    type == "bad attachment" -> "An attachment was rejected by Groups.io."
    type == "not subscribed" -> "This account is not subscribed to the group."
    type == "invalid reply" -> "This message can no longer be replied to."
    type == "no subject" -> "Enter a subject."
    type == "no body" -> "Enter a message body."
    type == "private message" -> "This private-message destination is not available."
    else -> "Groups.io could not complete the request."
}

interface GroupsIoTransport {
    suspend fun json(request: GroupsIoRequest, bearerKey: String): JSONObject
    suspend fun binary(request: GroupsIoRequest, bearerKey: String, destination: File, ceiling: Long = GROUPS_IO_ATTACHMENT_CEILING): Long
}

internal class GroupsIoHttpTransport : GroupsIoTransport {
    override suspend fun json(request: GroupsIoRequest, bearerKey: String): JSONObject {
        val attempts = if (request.method == "GET") 3 else 1
        var last: GroupsIoApiException? = null
        repeat(attempts) { attempt ->
            try { return jsonOnce(request, bearerKey) }
            catch (error: GroupsIoApiException) {
                last = error
                if (request.method != "GET" || error.category !in setOf("temporary", "rate_limited") || attempt == attempts - 1) throw error
                delay(min(250L shl attempt, 1_000L))
            }
        }
        throw last ?: GroupsIoApiException("server", "Groups.io request failed")
    }

    private fun open(request: GroupsIoRequest, key: String): HttpURLConnection {
        val query = request.query.takeIf { it.isNotEmpty() }?.let { "?${groupsIoFormBody(it)}" }.orEmpty()
        val target = request.absoluteHttpsUrl?.let(::URL) ?: URL("$GROUPS_IO_API_BASE${request.path}$query")
        return (target.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            // Attachment URLs are supplied by the service and may be hosted on a CDN.
            // Never forward the Groups.io bearer credential away from the API origin.
            if (request.absoluteHttpsUrl == null) setRequestProperty("Authorization", "Bearer $key")
        }
    }

    private fun jsonOnce(request: GroupsIoRequest, key: String): JSONObject {
        val connection = open(request, key)
        try {
            if (request.form.isNotEmpty()) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.bufferedWriter().use { it.write(groupsIoFormBody(request.form)) }
            } else if (request.multipart != null) writeMultipart(connection, request.multipart)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val root = if (body.isBlank()) JSONObject().put("object", "success") else runCatching { JSONObject(body) }
                .getOrElse { throw GroupsIoApiException("compatibility", "Groups.io response format changed") }
            if (status !in 200..299 || root.optString("object") == "error") {
                val type = root.optString("type")
                val extra = root.optString("extra")
                if (type.isNotBlank() || extra.isNotBlank()) throw GroupsIoApiException(
                    when { status == 401 -> "credential"; status == 403 -> "permission"; status == 429 -> "rate_limited"; status >= 500 -> "temporary"; else -> "server" },
                    groupsIoPostingMessage(type, extra)
                )
                throw GroupsIoApiException.from(status, body)
            }
            return root
        } catch (error: SocketTimeoutException) {
            if (request.path == "/postdraft") throw GroupsIoDeliveryUnknownException()
            throw GroupsIoApiException("temporary", "Groups.io request timed out")
        } catch (error: GroupsIoApiException) { throw error }
        catch (error: IOException) {
            if (request.path == "/postdraft") throw GroupsIoDeliveryUnknownException()
            throw GroupsIoApiException("network", "Network request failed")
        } finally { connection.disconnect() }
    }

    private fun writeMultipart(connection: HttpURLConnection, value: GroupsIoMultipart) {
        val boundary = "ShackCQ-${UUID.randomUUID()}"
        connection.doOutput = true; connection.setChunkedStreamingMode(64 * 1024)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        BufferedOutputStream(connection.outputStream).use { output ->
            fun text(name: String, content: String) {
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$content\r\n".toByteArray())
            }
            text("draft_id", value.draftId.toString()); text("inline", value.inline.toString())
            output.write("--$boundary\r\nContent-Disposition: form-data; name=\"fileupload\"; filename=\"${groupsIoSanitizeFilename(value.filename)}\"\r\nContent-Type: ${value.mediaType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray())
            value.file.inputStream().buffered().use { it.copyTo(output, 64 * 1024) }
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }
    }

    override suspend fun binary(request: GroupsIoRequest, bearerKey: String, destination: File, ceiling: Long): Long {
        require(request.method == "GET")
        val connection = open(request, bearerKey)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw GroupsIoApiException.from(status, connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            val length = connection.contentLengthLong
            if (length > ceiling) throw GroupsIoApiException("storage", "Download exceeds the 100 MiB mobile safety ceiling")
            return copyGroupsIoBinary(connection.inputStream, destination, ceiling)
        } catch (error: Throwable) { destination.delete(); throw error }
        finally { connection.disconnect() }
    }
}

internal class GroupsIoDeliveryUnknownException : IOException("Delivery could not be confirmed")

data class GroupsIoRemoteDraft(val id: Long, val groupId: Long, val type: String, val messageId: Long?, val subject: String, val body: String, val attachmentCount: Int, val updatedAtMillis: Long = 0)
internal data class GroupsIoPostResult(val pendingModeration: Boolean)
data class GroupsIoIncomingAttachment(
    val id: Long,
    val filename: String,
    val mediaType: String,
    val size: Long?,
    internal val transientHttpsUrl: String?,
    internal val transientThumbnailHttpsUrl: String? = null,
    val localRelativePath: String? = null,
    val localPreviewRelativePath: String? = null,
)

internal class GroupsIoPhase2Api(private val transport: GroupsIoTransport = GroupsIoHttpTransport()) {
    suspend fun permissions(key: String, groupId: Long): GroupsIoCapabilities {
        val perms = transport.json(GroupsIoRequest("GET", "/getperms", query = mapOf("group_id" to groupId.toString())), key)
        val feed = transport.json(GroupsIoRequest("GET", "/getsinglefeed", query = mapOf("group_id" to groupId.toString())), key)
        val member = feed.optJSONObject("member_info") ?: JSONObject()
        val group = feed.optJSONObject("group") ?: feed.optJSONObject("group_info") ?: JSONObject()
        val postStatus = member.optString("post_status")
        return GroupsIoCapabilities(
            archivesVisible = perms.optBoolean("archives_visible"),
            canPost = when { perms.has("can_post") -> perms.optBoolean("can_post"); perms.has("post") -> perms.optBoolean("post"); else -> postStatus !in setOf("sub_poststatus_cannotpost", "sub_poststatus_none", "announcement") },
            canReply = when { perms.has("can_reply") -> perms.optBoolean("can_reply"); perms.has("reply") -> perms.optBoolean("reply"); else -> postStatus !in setOf("sub_poststatus_cannotpost", "sub_poststatus_none") },
            downloadArchives = perms.optBoolean("download_archives"), postStatus = postStatus,
            maxAttachmentSize = member.optLong("max_attachment_size").takeIf { it > 0 } ?: group.optLong("max_attachment_size").takeIf { it > 0 },
            defaultReplyPolicy = group.optString("reply_to").takeIf(String::isNotBlank), syncedAtMillis = System.currentTimeMillis()
        )
    }

    suspend fun createDraft(key: String, draft: GroupsIoLocalDraft): Long {
        val form = linkedMapOf("group_id" to draft.groupId.toString(), "draft_type" to if (draft.type == GroupsIoDraftType.NEW_TOPIC) "draft_type_post" else "draft_type_reply")
        if (draft.type == GroupsIoDraftType.REPLY) form["message_id"] = requireNotNull(draft.replyApiMessageId).toString()
        return transport.json(GroupsIoRequest("POST", "/newdraft", form = form), key).requiredLong("id", "draft_id")
    }

    suspend fun updateDraft(key: String, remoteId: Long, subject: String, bodyPlain: String, quote: String?, destination: GroupsIoReplyDestination?) {
        val form = linkedMapOf("draft_id" to remoteId.toString(), "subject" to subject, "body" to groupsIoSafeHtml(bodyPlain, quote), "body_type" to "html")
        destination?.wire?.takeIf(String::isNotBlank)?.let { form["reply_to"] = it }
        transport.json(GroupsIoRequest("POST", "/updatedraft", form = form), key)
    }

    suspend fun uploadAttachment(key: String, remoteId: Long, attachment: GroupsIoDraftAttachment, root: File): Long {
        val file = root.resolve(attachment.localRelativePath)
        val rootJson = transport.json(GroupsIoRequest("POST", "/uploadattachments", multipart = GroupsIoMultipart(remoteId, file, attachment.filename, attachment.mediaType)), key)
        val values = rootJson.optJSONArray("data") ?: JSONArray()
        return (0 until values.length()).map { values.getJSONObject(it) }.firstOrNull { it.optString("filename") == attachment.filename }
            ?.requiredLong("id", "attachment_id") ?: throw GroupsIoApiException("compatibility", "Groups.io did not confirm the attachment")
    }

    suspend fun postDraft(key: String, remoteId: Long): GroupsIoPostResult {
        val root = transport.json(GroupsIoRequest("POST", "/postdraft", form = mapOf("draft_id" to remoteId.toString())), key)
        return GroupsIoPostResult(root.optString("extra") == "pending post")
    }

    suspend fun deleteRemoteDraft(key: String, remoteId: Long) { transport.json(GroupsIoRequest("POST", "/deletedraft", form = mapOf("draft_id" to remoteId.toString())), key) }
    suspend fun deleteRemoteAttachment(key: String, remoteId: Long, attachmentId: Long) { transport.json(GroupsIoRequest("POST", "/deleteattachment", form = mapOf("draft_id" to remoteId.toString(), "attachment_id" to attachmentId.toString())), key) }

    suspend fun drafts(key: String, pageToken: String? = null): Pair<List<GroupsIoRemoteDraft>, Pair<String?, Boolean>> {
        val query = linkedMapOf("limit" to "50"); pageToken?.let { query["page_token"] = it }
        val root = transport.json(GroupsIoRequest("GET", "/getdrafts", query = query), key)
        val data = root.optJSONArray("data") ?: JSONArray()
        val values = (0 until data.length()).map { data.getJSONObject(it) }.map { value -> GroupsIoRemoteDraft(
            value.requiredLong("id", "draft_id"), value.requiredLong("group_id"), value.optString("draft_type"),
            value.optLong("message_id").takeIf { it > 0 }, value.optString("subject"), normaliseBody(value.optString("body")), value.optInt("num_attachments"),
            value.instantMillis("updated", "created", "date")
        ) }
        return values to groupsIoPagination(root)
    }

    suspend fun remoteDraftAttachments(key: String, remoteDraftId: Long): JSONObject = transport.json(
        GroupsIoRequest("GET", "/getattachments", query = mapOf("draft_id" to remoteDraftId.toString())), key)

    suspend fun search(key: String, groupId: Long, query: String, newest: Boolean, pageToken: String? = null): JSONObject {
        val values = linkedMapOf("group_id" to groupId.toString(), "q" to query, "limit" to "50", "exclude_sigs" to "true", "collapse_topics" to "false", "sort_dir" to if (newest) "desc" else "relevance")
        pageToken?.let { values["page_token"] = it }
        return transport.json(GroupsIoRequest("GET", "/searcharchives", query = values), key)
    }

    suspend fun message(key: String, groupId: Long, number: Long): JSONObject = transport.json(
        GroupsIoRequest("GET", "/getmessage", query = mapOf("group_id" to groupId.toString(), "msg_num" to number.toString())), key)

    suspend fun incomingAttachments(key: String, groupId: Long, number: Long): List<GroupsIoIncomingAttachment> {
        val root = message(key, groupId, number)
        val message = root.optJSONObject("message") ?: root.optJSONObject("data") ?: root
        val values = message.optJSONArray("attachments") ?: root.optJSONArray("attachments") ?: JSONArray()
        return (0 until values.length()).map { values.getJSONObject(it) }.mapIndexed { index, value ->
            val filename = groupsIoSanitizeFilename(value.optString("filename").ifBlank { "attachment" })
            val downloadUrl = value.firstString("download_url", "url")
            val stableLocalId = groupsIoAttachmentId(value, filename, downloadUrl, index)
            GroupsIoIncomingAttachment(
            id = stableLocalId, filename = filename,
            mediaType = value.firstString("media_type", "content_type").ifBlank { "application/octet-stream" }, size = value.firstLongOrNull("size", "byte_size"),
            transientHttpsUrl = downloadUrl.takeIf(String::isNotBlank),
            transientThumbnailHttpsUrl = value.firstString("image_thumbnail_url", "thumbnail_url").takeIf(String::isNotBlank)
        ) }
    }

    suspend fun downloadIncomingPreview(key: String, value: GroupsIoIncomingAttachment, partial: File): Long {
        val url = value.transientThumbnailHttpsUrl ?: throw GroupsIoApiException("compatibility", "Groups.io did not provide an image preview")
        return transport.binary(GroupsIoRequest("GET", "/attachment-preview", absoluteHttpsUrl = url), key, partial, 8L * 1024 * 1024)
    }

    suspend fun downloadIncomingAttachment(key: String, groupId: Long, number: Long, attachmentId: Long, partial: File): GroupsIoIncomingAttachment {
        val current = incomingAttachments(key, groupId, number).firstOrNull { it.id == attachmentId }
            ?: throw GroupsIoApiException("compatibility", "Groups.io no longer lists this attachment")
        val url = current.transientHttpsUrl ?: throw GroupsIoApiException("compatibility", "Groups.io did not provide a download URL")
        if (current.size != null && current.size > GROUPS_IO_ATTACHMENT_CEILING) throw GroupsIoApiException("storage", "Attachment exceeds the 100 MiB mobile safety ceiling")
        transport.binary(GroupsIoRequest("GET", "/attachment-download", absoluteHttpsUrl = url), key, partial)
        return current.copy(transientHttpsUrl = null)
    }

    suspend fun archivePage(key: String, groupId: Long, token: String? = null): JSONObject {
        val values = linkedMapOf("group_id" to groupId.toString(), "limit" to "100", "sort_dir" to "desc"); token?.let { values["page_token"] = it }
        return transport.json(GroupsIoRequest("GET", "/getmessages", query = values), key)
    }

    suspend fun downloadOfficialArchive(key: String, groupId: Long, start: Long?, temporary: File): Long {
        val query = linkedMapOf("group_id" to groupId.toString()); start?.let { query["start_msg_num"] = it.toString() }
        return transport.binary(GroupsIoRequest("GET", "/downloadarchives", query = query), key, temporary, Long.MAX_VALUE)
    }
}

internal fun groupsIoAttachmentId(value: JSONObject, filename: String, downloadUrl: String, index: Int): Long =
    value.firstLongOrNull("id", "attachment_id")
        ?: ("$filename|$downloadUrl|$index".fold(1_125_899_906_842_597L) { hash, character -> hash * 31 + character.code } and Long.MAX_VALUE).coerceAtLeast(1L)

internal class GroupsIoAttachmentStore(private val context: Context) {
    private val root = context.filesDir.resolve("GroupsIO")
    val filesRoot: File get() = context.filesDir

    fun importDraftAttachment(draftId: String, uri: Uri): GroupsIoDraftAttachment {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val safe = groupsIoSanitizeFilename(displayName)
        val directory = root.resolve("outbox/$draftId").apply { mkdirs() }
        var destination = directory.resolve(safe); var suffix = 1
        while (destination.exists()) { destination = directory.resolve("${safe.substringBeforeLast('.', safe)}-$suffix${safe.substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()}"); suffix++ }
        val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
        resolver.openInputStream(uri)?.buffered()?.use { input -> FileOutputStream(destination).buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; total += read; if (total > GROUPS_IO_ATTACHMENT_CEILING) throw IOException("Attachment exceeds the 100 MiB mobile safety ceiling"); digest.update(buffer, 0, read); output.write(buffer, 0, read) }
        } } ?: throw IOException("Unable to open selected attachment")
        return GroupsIoDraftAttachment(draftLocalId = draftId, filename = destination.name, mediaType = resolver.getType(uri).orEmpty(), byteSize = total,
            localRelativePath = destination.relativeTo(context.filesDir).path, sha256 = digest.digest().joinToString("") { "%02x".format(it) })
    }

    fun incomingFile(groupId: Long, messageNumber: Long, attachmentId: Long, name: String): Pair<File, File> {
        val final = root.resolve("attachments/$groupId/$messageNumber/${attachmentId}-${groupsIoSanitizeFilename(name)}")
        return final.resolveSibling("${final.name}.partial") to final
    }

    fun incomingPreviewFile(groupId: Long, messageNumber: Long, attachmentId: Long, name: String): Pair<File, File> {
        val final = root.resolve("previews/$groupId/$messageNumber/${attachmentId}-${groupsIoSanitizeFilename(name)}")
        return final.resolveSibling("${final.name}.partial") to final
    }

    fun officialArchiveFile(groupId: Long, nowMillis: Long): Pair<File, File> {
        val final = root.resolve("archive-exports/$groupId/$nowMillis-archive.zip")
        return final.resolveSibling("${final.name}.partial") to final
    }
}

internal interface GroupsIoOutboxPersistence {
    fun save(draft: GroupsIoLocalDraft)
    fun attachments(localId: String): List<GroupsIoDraftAttachment>
    fun markAttachmentUploaded(localId: String, remoteId: Long)
}

internal class GroupsIoOutbox(
    private val api: GroupsIoPhase2Api,
    private val persistence: GroupsIoOutboxPersistence,
    private val attachmentRoot: File,
) {
    private val mutex = Mutex()

    suspend fun process(draft: GroupsIoLocalDraft, key: String, connected: Boolean, enabled: Boolean): GroupsIoLocalDraft = mutex.withLock {
        if (!enabled || !connected || !draft.sendWhenOnline || draft.state == GroupsIoOutboxState.DELIVERY_UNKNOWN) return draft
        require(draft.subject.isNotBlank() && draft.bodyPlain.isNotBlank()) { "Subject and body are required" }
        var current = draft.copy(state = GroupsIoOutboxState.CREATING_REMOTE, lastAttemptAtMillis = System.currentTimeMillis()).also(persistence::save)
        return try {
            val remoteId = current.remoteDraftId ?: api.createDraft(key, current)
            current = current.copy(remoteDraftId = remoteId, state = GroupsIoOutboxState.UPDATING_REMOTE).also(persistence::save)
            api.updateDraft(key, remoteId, current.subject, current.bodyPlain, null, current.replyDestination)
            current = current.copy(state = GroupsIoOutboxState.UPLOADING).also(persistence::save)
            persistence.attachments(current.localId).filter { it.remoteAttachmentId == null && it.uploadState != "uploaded" }.forEach {
                persistence.markAttachmentUploaded(it.localId, api.uploadAttachment(key, remoteId, it, attachmentRoot))
            }
            current = current.copy(state = GroupsIoOutboxState.READY_TO_POST).also(persistence::save)
            current = current.copy(state = GroupsIoOutboxState.POSTING).also(persistence::save)
            val result = api.postDraft(key, remoteId)
            current.copy(state = if (result.pendingModeration) GroupsIoOutboxState.PENDING_MODERATION else GroupsIoOutboxState.POSTED,
                pendingModeration = result.pendingModeration, sendWhenOnline = false, updatedAtMillis = System.currentTimeMillis()).also(persistence::save)
        } catch (_: GroupsIoDeliveryUnknownException) {
            current.copy(state = GroupsIoOutboxState.DELIVERY_UNKNOWN, deliveryUnknown = true, sendWhenOnline = false,
                lastErrorCategory = "delivery_unknown", lastErrorText = "Delivery could not be confirmed").also(persistence::save)
        } catch (error: GroupsIoApiException) {
            current.copy(state = if (error.category in setOf("network", "temporary", "rate_limited")) GroupsIoOutboxState.FAILED_RETRYABLE else GroupsIoOutboxState.NEEDS_ATTENTION,
                lastErrorCategory = error.category, lastErrorText = error.message, sendWhenOnline = false).also(persistence::save)
        }
    }
}

data class GroupsIoArchiveProgress(val state: String = "not_started", val downloaded: Int = 0, val total: Int? = null, val nextPageToken: String? = null) {
    fun applyPage(count: Int, totalCount: Int?, next: String?, hasMore: Boolean): GroupsIoArchiveProgress {
        if (hasMore && next == null) throw GroupsIoApiException("compatibility", "Groups.io pagination omitted the next page token")
        return copy(state = if (hasMore) "partial" else "complete", downloaded = downloaded + count, total = totalCount ?: total, nextPageToken = next)
    }
    fun paused() = copy(state = if (state == "complete") state else "paused")
}

private fun JSONObject.requiredLong(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
    if (!has(name) || isNull(name)) null else optLong(name).takeIf { it != 0L }
} ?: throw GroupsIoApiException("compatibility", "Groups.io response omitted ${names.first()}")
