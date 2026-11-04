package dev.aarso.mail.jmap

import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.net.MailException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** JMAP credentials: a bearer API token (Fastmail) or HTTP Basic (Stalwart, Cyrus, self-hosted). */
sealed interface JmapAuth {
    data class Bearer(val token: String) : JmapAuth
    data class Basic(val user: String, val password: String) : JmapAuth
}

data class JmapSession(
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val eventSourceUrl: String?,
    val accountId: String,
    val username: String,
    val state: String,
    val capabilities: Set<String>,
    val maxObjectsInGet: Int,
    val maxSizeUpload: Long,
)

data class JmapMailbox(
    val id: String,
    val name: String,
    val parentId: String?,
    val role: String?,
    val totalEmails: Int,
    val unreadEmails: Int,
    val sortOrder: Int,
)

data class JmapBodyPart(
    val partId: String?,
    val blobId: String?,
    val type: String,
    val charset: String?,
    val name: String?,
    val disposition: String?,
    val cid: String?,
    val size: Long,
)

data class JmapEmail(
    val id: String,
    val blobId: String,
    val threadId: String,
    val mailboxIds: Set<String>,
    val keywords: Set<String>,
    val size: Long,
    val receivedAt: Long,
    val sentAt: Long?,
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val from: List<EmailAddress>,
    val sender: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    val subject: String,
    val preview: String,
    val hasAttachment: Boolean,
    val textBody: String?,
    val htmlBody: String?,
    val attachments: List<JmapBodyPart>,
    val headers: Map<String, String>,
)

data class JmapChanges(
    val oldState: String,
    val newState: String,
    val hasMoreChanges: Boolean,
    val created: List<String>,
    val updated: List<String>,
    val destroyed: List<String>,
)

data class JmapQueryResult(val ids: List<String>, val total: Int?, val queryState: String?)

/** A StateChange push (RFC 8620 §7.1): which data types changed, per account. */
data class JmapStateChange(val changed: Map<String, Map<String, String>>)

class JmapException(val type: String, description: String?) : MailException("JMAP error $type${description?.let { ": $it" } ?: ""}")

/**
 * A JMAP Mail client (RFC 8620 core, RFC 8621 mail, RFC 8621 §7 submission).
 *
 * JMAP is what IMAP would be if it were designed today: batched method calls over HTTPS, opaque
 * state strings for exact incremental sync, server-side threading, and push over EventSource. When
 * an account offers it (Fastmail, Stalwart, Cyrus 3.x) this is used instead of IMAP.
 */
class JmapClient(
    private val sessionUrl: String,
    private val auth: JmapAuth,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private var session: JmapSession? = null

    private fun authorize(b: Request.Builder): Request.Builder = when (auth) {
        is JmapAuth.Bearer -> b.header("Authorization", "Bearer ${auth.token}")
        is JmapAuth.Basic -> b.header("Authorization", Credentials.basic(auth.user, auth.password))
    }

    suspend fun session(refresh: Boolean = false): JmapSession = withContext(Dispatchers.IO) {
        session?.takeIf { !refresh }?.let { return@withContext it }
        val req = authorize(Request.Builder().url(sessionUrl).get()).build()
        val body = http.newCall(req).execute().use { resp -> checkOk(resp); resp.body!!.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val caps = root["capabilities"]?.jsonObject ?: JsonObject(emptyMap())
        val accountId = root["primaryAccounts"]?.jsonObject?.get(MAIL)?.jsonPrimitive?.contentOrNull
            ?: root["accounts"]?.jsonObject?.keys?.firstOrNull()
            ?: throw MailException("JMAP session has no mail account")
        val core = caps[CORE]?.jsonObject
        JmapSession(
            apiUrl = root.str("apiUrl") ?: throw MailException("JMAP session has no apiUrl"),
            downloadUrl = root.str("downloadUrl").orEmpty(),
            uploadUrl = root.str("uploadUrl").orEmpty(),
            eventSourceUrl = root.str("eventSourceUrl"),
            accountId = accountId,
            username = root.str("username").orEmpty(),
            state = root.str("state").orEmpty(),
            capabilities = caps.keys,
            maxObjectsInGet = core?.get("maxObjectsInGet")?.jsonPrimitive?.longOrNull?.toInt() ?: 256,
            maxSizeUpload = core?.get("maxSizeUpload")?.jsonPrimitive?.longOrNull ?: 50_000_000,
        ).also { session = it }
    }

    /** One JMAP request: `[[method, args, callId], …]` → the matching responses, errors raised. */
    suspend fun call(vararg calls: Triple<String, JsonObject, String>): List<Triple<String, JsonObject, String>> = withContext(Dispatchers.IO) {
        val s = session()
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(JsonPrimitive(CORE)); add(JsonPrimitive(MAIL))
                if (s.capabilities.contains(SUBMISSION)) add(JsonPrimitive(SUBMISSION))
            }
            putJsonArray("methodCalls") {
                for ((name, args, id) in calls) add(buildJsonArray { add(JsonPrimitive(name)); add(args); add(JsonPrimitive(id)) })
            }
        }
        val req = authorize(Request.Builder().url(s.apiUrl).post(payload.toString().toRequestBody(JSON_TYPE))).build()
        val text = http.newCall(req).execute().use { resp -> checkOk(resp); resp.body!!.string() }
        val responses = json.parseToJsonElement(text).jsonObject["methodResponses"]?.jsonArray ?: JsonArray(emptyList())
        responses.map { r ->
            val a = r.jsonArray
            val name = a[0].jsonPrimitive.content
            val args = a[1].jsonObject
            if (name == "error") throw JmapException(args.str("type") ?: "unknown", args.str("description"))
            Triple(name, args, a[2].jsonPrimitive.content)
        }
    }

    suspend fun mailboxes(): List<JmapMailbox> {
        val s = session()
        val r = call(Triple("Mailbox/get", buildJsonObject { put("accountId", s.accountId) }, "m"))
        return r.first().second["list"]?.jsonArray.orEmpty().map { e ->
            val o = e.jsonObject
            JmapMailbox(
                id = o.str("id")!!, name = o.str("name").orEmpty(), parentId = o.str("parentId"), role = o.str("role"),
                totalEmails = o.int("totalEmails"), unreadEmails = o.int("unreadEmails"), sortOrder = o.int("sortOrder"),
            )
        }
    }

    suspend fun queryEmails(
        filter: JsonObject,
        position: Int = 0,
        limit: Int = 200,
        collapseThreads: Boolean = false,
        sortByReceivedDesc: Boolean = true,
    ): JmapQueryResult {
        val s = session()
        val args = buildJsonObject {
            put("accountId", s.accountId)
            put("filter", filter)
            putJsonArray("sort") {
                add(buildJsonObject { put("property", "receivedAt"); put("isAscending", !sortByReceivedDesc) })
            }
            put("position", position)
            put("limit", limit)
            put("collapseThreads", collapseThreads)
            put("calculateTotal", true)
        }
        val r = call(Triple("Email/query", args, "q")).first().second
        return JmapQueryResult(
            ids = r["ids"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
            total = r["total"]?.jsonPrimitive?.longOrNull?.toInt(),
            queryState = r.str("queryState"),
        )
    }

    suspend fun getEmails(ids: List<String>, withBodies: Boolean, maxBodyBytes: Int = 256 * 1024): List<JmapEmail> {
        if (ids.isEmpty()) return emptyList()
        val s = session()
        val out = ArrayList<JmapEmail>()
        for (chunk in ids.chunked(s.maxObjectsInGet.coerceIn(1, 500))) {
            val args = buildJsonObject {
                put("accountId", s.accountId)
                putJsonArray("ids") { chunk.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("properties") { EMAIL_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("bodyProperties") { BODY_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
                if (withBodies) {
                    put("fetchTextBodyValues", true)
                    put("fetchHTMLBodyValues", true)
                    put("maxBodyValueBytes", maxBodyBytes)
                }
            }
            val r = call(Triple("Email/get", args, "g")).first().second
            r["list"]?.jsonArray.orEmpty().mapTo(out) { parseEmail(it.jsonObject) }
        }
        return out
    }

    suspend fun emailChanges(sinceState: String, maxChanges: Int = 500): JmapChanges = changes("Email/changes", sinceState, maxChanges)
    suspend fun mailboxChanges(sinceState: String): JmapChanges = changes("Mailbox/changes", sinceState, 500)

    private suspend fun changes(method: String, sinceState: String, maxChanges: Int): JmapChanges {
        val s = session()
        val args = buildJsonObject { put("accountId", s.accountId); put("sinceState", sinceState); put("maxChanges", maxChanges) }
        val r = call(Triple(method, args, "c")).first().second
        fun ids(k: String) = r[k]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        return JmapChanges(
            r.str("oldState").orEmpty(), r.str("newState").orEmpty(),
            r["hasMoreChanges"]?.jsonPrimitive?.booleanOrNull ?: false,
            ids("created"), ids("updated"), ids("destroyed"),
        )
    }

    /** The current Email state string, without fetching anything else. */
    suspend fun emailState(): String {
        val s = session()
        val args = buildJsonObject { put("accountId", s.accountId); putJsonArray("ids") {} }
        return call(Triple("Email/get", args, "s")).first().second.str("state").orEmpty()
    }

    /**
     * Patch many emails at once: keyword and mailbox changes use JSON-pointer patch keys
     * (`keywords/$seen`, `mailboxIds/<id>`), so one round trip updates thousands of messages.
     */
    suspend fun setEmails(update: Map<String, JsonObject> = emptyMap(), destroy: List<String> = emptyList()) {
        if (update.isEmpty() && destroy.isEmpty()) return
        val s = session()
        for (chunk in update.entries.chunked(250)) {
            val args = buildJsonObject {
                put("accountId", s.accountId)
                putJsonObject("update") { chunk.forEach { (id, patch) -> put(id, patch) } }
            }
            checkSet(call(Triple("Email/set", args, "u")).first().second)
        }
        for (chunk in destroy.chunked(250)) {
            val args = buildJsonObject {
                put("accountId", s.accountId)
                putJsonArray("destroy") { chunk.forEach { add(JsonPrimitive(it)) } }
            }
            checkSet(call(Triple("Email/set", args, "d")).first().second)
        }
    }

    private fun checkSet(r: JsonObject) {
        val notUpdated = r["notUpdated"]?.jsonObject
        val notDestroyed = r["notDestroyed"]?.jsonObject
        val notCreated = r["notCreated"]?.jsonObject
        val first = (notUpdated ?: notDestroyed ?: notCreated)?.values?.firstOrNull()?.jsonObject
        if (first != null) throw JmapException(first.str("type") ?: "setError", first.str("description"))
    }

    suspend fun upload(bytes: ByteArray, type: String = "message/rfc822"): String = withContext(Dispatchers.IO) {
        val s = session()
        val url = s.uploadUrl.replace("{accountId}", s.accountId)
        val req = authorize(Request.Builder().url(url).post(bytes.toRequestBody(type.toMediaType()))).build()
        val text = http.newCall(req).execute().use { resp -> checkOk(resp); resp.body!!.string() }
        json.parseToJsonElement(text).jsonObject.str("blobId") ?: throw MailException("Upload returned no blobId")
    }

    suspend fun download(blobId: String, type: String = "application/octet-stream", name: String = "file"): ByteArray = withContext(Dispatchers.IO) {
        val s = session()
        val url = s.downloadUrl
            .replace("{accountId}", enc(s.accountId)).replace("{blobId}", enc(blobId))
            .replace("{type}", enc(type)).replace("{name}", enc(name))
        val req = authorize(Request.Builder().url(url).get()).build()
        http.newCall(req).execute().use { resp -> checkOk(resp); resp.body!!.bytes() }
    }

    /** Store a raw message (e.g. a draft) into mailboxes. Returns the new email id. */
    suspend fun importEmail(blobId: String, mailboxIds: Collection<String>, keywords: Collection<String>, receivedAt: Long? = null): String {
        val s = session()
        val args = buildJsonObject {
            put("accountId", s.accountId)
            putJsonObject("emails") {
                putJsonObject("imp") {
                    put("blobId", blobId)
                    putJsonObject("mailboxIds") { mailboxIds.forEach { put(it, true) } }
                    putJsonObject("keywords") { keywords.forEach { put(it, true) } }
                    receivedAt?.let { put("receivedAt", java.time.Instant.ofEpochMilli(it).toString()) }
                }
            }
        }
        val r = call(Triple("Email/import", args, "i")).first().second
        r["notCreated"]?.jsonObject?.get("imp")?.jsonObject?.let { throw JmapException(it.str("type") ?: "notCreated", it.str("description")) }
        return r["created"]?.jsonObject?.get("imp")?.jsonObject?.str("id") ?: throw MailException("Import returned no id")
    }

    suspend fun identities(): List<Pair<String, EmailAddress>> {
        val s = session()
        if (SUBMISSION !in s.capabilities) return emptyList()
        val r = call(Triple("Identity/get", buildJsonObject { put("accountId", s.accountId) }, "id")).first().second
        return r["list"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val id = o.str("id") ?: return@mapNotNull null
            id to EmailAddress(o.str("email").orEmpty(), o.str("name"))
        }
    }

    /**
     * Send: upload, import into Drafts, submit, and on success move it to Sent and clear `$draft` —
     * one batched request after the upload, so a send either completes or visibly fails.
     */
    suspend fun send(raw: ByteArray, identityId: String, draftsId: String, sentId: String?, from: String, recipients: List<String>) {
        val s = session()
        val blob = upload(raw)
        val importArgs = buildJsonObject {
            put("accountId", s.accountId)
            putJsonObject("emails") {
                putJsonObject("draft") {
                    put("blobId", blob)
                    putJsonObject("mailboxIds") { put(draftsId, true) }
                    putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                }
            }
        }
        val submitArgs = buildJsonObject {
            put("accountId", s.accountId)
            putJsonObject("create") {
                putJsonObject("sub") {
                    put("identityId", identityId)
                    put("emailId", "#draft")
                    putJsonObject("envelope") {
                        putJsonObject("mailFrom") { put("email", from) }
                        putJsonArray("rcptTo") { recipients.forEach { r -> add(buildJsonObject { put("email", r) }) } }
                    }
                }
            }
            putJsonObject("onSuccessUpdateEmail") {
                putJsonObject("#sub") {
                    put("keywords/\$draft", JsonNull)
                    put("mailboxIds/$draftsId", JsonNull)
                    sentId?.let { put("mailboxIds/$it", true) }
                }
            }
        }
        val r = call(Triple("Email/import", importArgs, "0"), Triple("EmailSubmission/set", submitArgs, "1"))
        r.firstOrNull { it.first == "EmailSubmission/set" }?.second?.let { checkSet(it) }
    }

    /**
     * Push over EventSource (RFC 8620 §7.3). Emits one [JmapStateChange] per server event; the
     * collector decides what to resync. Completes when the server closes the stream.
     */
    fun events(types: String = "Email,Mailbox"): Flow<JmapStateChange> = flow {
        val s = session()
        val base = s.eventSourceUrl ?: return@flow
        val url = base.replace("{types}", types).replace("{closeafter}", "no").replace("{ping}", "60")
        val req = authorize(Request.Builder().url(url).header("Accept", "text/event-stream").get()).build()
        val streaming = http.newBuilder().readTimeout(java.time.Duration.ofMinutes(3)).build()
        streaming.newCall(req).execute().use { resp ->
            checkOk(resp)
            val source = resp.body!!.source()
            var event = ""
            val data = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (event == "state" || (event.isEmpty() && data.isNotEmpty())) {
                            runCatching {
                                val o = json.parseToJsonElement(data.toString()).jsonObject
                                val changed = o["changed"]?.jsonObject.orEmpty().mapValues { (_, v) ->
                                    v.jsonObject.mapValues { it.value.jsonPrimitive.content }
                                }
                                emit(JmapStateChange(changed))
                            }
                        }
                        event = ""; data.setLength(0)
                    }
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data.append(line.substringAfter(':').trim())
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------------------------------

    private fun parseEmail(o: JsonObject): JmapEmail {
        val values = o["bodyValues"]?.jsonObject
        fun bodyText(key: String): String? {
            val parts = o[key]?.jsonArray ?: return null
            val text = parts.mapNotNull { p -> p.jsonObject.str("partId")?.let { id -> values?.get(id)?.jsonObject?.str("value") } }
            return text.joinToString("\n").ifEmpty { null }
        }
        return JmapEmail(
            id = o.str("id")!!,
            blobId = o.str("blobId").orEmpty(),
            threadId = o.str("threadId").orEmpty(),
            mailboxIds = o["mailboxIds"]?.jsonObject?.keys.orEmpty(),
            keywords = o["keywords"]?.jsonObject?.keys.orEmpty(),
            size = o["size"]?.jsonPrimitive?.longOrNull ?: 0,
            receivedAt = o.str("receivedAt")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
            sentAt = o.str("sentAt")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            messageId = o["messageId"]?.firstString(),
            inReplyTo = o["inReplyTo"]?.firstString(),
            references = o["references"]?.let { r -> (r as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } }.orEmpty(),
            from = addresses(o["from"]), sender = addresses(o["sender"]), to = addresses(o["to"]),
            cc = addresses(o["cc"]), bcc = addresses(o["bcc"]), replyTo = addresses(o["replyTo"]),
            subject = o.str("subject").orEmpty(),
            preview = o.str("preview").orEmpty(),
            hasAttachment = o["hasAttachment"]?.jsonPrimitive?.booleanOrNull ?: false,
            textBody = bodyText("textBody"),
            htmlBody = bodyText("htmlBody")?.takeIf { o["htmlBody"]?.jsonArray?.any { it.jsonObject.str("type") == "text/html" } == true },
            attachments = o["attachments"]?.jsonArray.orEmpty().map { p ->
                val po = p.jsonObject
                JmapBodyPart(po.str("partId"), po.str("blobId"), po.str("type") ?: "application/octet-stream", po.str("charset"),
                    po.str("name"), po.str("disposition"), po.str("cid"), po["size"]?.jsonPrimitive?.longOrNull ?: 0)
            },
            headers = buildMap {
                for (h in HEADER_PROPERTIES) o["header:$h:asText"]?.jsonPrimitive?.contentOrNull?.let { put(h, it) }
            },
        )
    }

    private fun addresses(e: JsonElement?): List<EmailAddress> = (e as? JsonArray)?.mapNotNull { a ->
        val o = a.jsonObject
        o.str("email")?.let { EmailAddress(it, o.str("name")) }
    }.orEmpty()

    private fun JsonElement.firstString(): String? = (this as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull

    private fun checkOk(resp: Response) {
        if (resp.code == 401 || resp.code == 403) throw AuthenticationException("JMAP server rejected the token (${resp.code})")
        if (!resp.isSuccessful) throw MailException("JMAP HTTP ${resp.code}: ${resp.message}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val CORE = "urn:ietf:params:jmap:core"
        const val MAIL = "urn:ietf:params:jmap:mail"
        const val SUBMISSION = "urn:ietf:params:jmap:submission"
        private val JSON_TYPE = "application/json".toMediaType()
        private val HEADER_PROPERTIES = listOf("List-Id", "List-Unsubscribe", "List-Unsubscribe-Post", "Precedence", "Auto-Submitted", "Authentication-Results")
        private val EMAIL_PROPERTIES = listOf(
            "id", "blobId", "threadId", "mailboxIds", "keywords", "size", "receivedAt", "sentAt", "messageId", "inReplyTo",
            "references", "from", "sender", "to", "cc", "bcc", "replyTo", "subject", "preview", "hasAttachment",
            "textBody", "htmlBody", "attachments", "bodyValues",
        ) + HEADER_PROPERTIES.map { "header:$it:asText" }
        private val BODY_PROPERTIES = listOf("partId", "blobId", "size", "name", "type", "charset", "disposition", "cid")

        /** Build a JMAP patch object from key/value pairs; a null value removes the key. */
        fun patch(vararg pairs: Pair<String, Boolean?>): JsonObject = buildJsonObject {
            for ((k, v) in pairs) put(k, if (v == null) JsonNull else JsonPrimitive(v))
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
