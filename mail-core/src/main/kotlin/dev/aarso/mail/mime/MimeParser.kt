package dev.aarso.mail.mime

import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.PartInfo

/**
 * One node of a parsed message. Leaves carry their decoded bytes (transfer encoding removed, charset
 * untouched); multiparts carry children; `message/rfc822` carries the embedded message.
 *
 * [partId] follows IMAP section numbering (RFC 3501 §6.4.5) so a part found by parsing a full
 * download and a part named by BODYSTRUCTURE are the same id — "2.1" means the same bytes either way.
 */
class MimeEntity(
    val headers: Headers,
    val contentType: ContentType,
    val partId: String,
    val body: ByteArray,
    val children: List<MimeEntity> = emptyList(),
    val embedded: MimeEntity? = null,
) {
    val disposition: ContentDisposition? by lazy { headers.disposition() }
    val fileName: String?
        get() = disposition?.fileName?.takeIf { it.isNotBlank() } ?: contentType.name?.takeIf { it.isNotBlank() }
    val contentId: String? get() = headers["Content-ID"]?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }
    val transferEncoding: String? get() = headers["Content-Transfer-Encoding"]?.trim()?.lowercase()

    val isAttachment: Boolean
        get() = when {
            contentType.isMultipart -> false
            disposition?.isAttachment == true -> true
            contentType.isType("message/rfc822") -> true
            contentType.isText && fileName == null -> false
            contentId != null && disposition?.isAttachment != true -> false // inline image of an HTML body
            fileName != null -> true
            !contentType.isText -> true
            else -> false
        }

    val isInline: Boolean get() = !isAttachment && !contentType.isMultipart

    /** Decoded text of a text part, honouring the declared charset or sniffing undeclared 8-bit. */
    fun text(): String {
        val cs = contentType.charset
        return if (cs == null || cs.equals("us-ascii", true) && body.any { it < 0 }) {
            MailCharsets.decode(body, MailCharsets.sniff(body))
        } else {
            MailCharsets.decode(body, cs)
        }.let { if (contentType.params["format"].equals("flowed", true)) Flowed.decode(it, contentType.params["delsp"].equals("yes", true)) else it }
    }

    /** Depth-first walk of every entity, including embedded messages. */
    fun walk(): Sequence<MimeEntity> = sequence {
        yield(this@MimeEntity)
        for (c in children) yieldAll(c.walk())
        embedded?.let { yieldAll(it.walk()) }
    }

    fun toPartInfo(): PartInfo = PartInfo(
        partId = partId,
        mimeType = contentType.mimeType,
        charset = contentType.charset,
        fileName = fileName,
        contentId = contentId,
        disposition = disposition?.type,
        encoding = transferEncoding,
        size = body.size.toLong(),
    )
}

/** A whole parsed message with the accessors every reader needs. */
class ParsedMessage(val root: MimeEntity) {
    val headers: Headers get() = root.headers
    val subject: String get() = headers.text("Subject").orEmpty()
    val from: EmailAddress? get() = headers.addresses("From").firstOrNull()
    val sender: EmailAddress? get() = headers.addresses("Sender").firstOrNull()
    val to: List<EmailAddress> get() = headers.addresses("To")
    val cc: List<EmailAddress> get() = headers.addresses("Cc")
    val bcc: List<EmailAddress> get() = headers.addresses("Bcc")
    val replyTo: List<EmailAddress> get() = headers.addresses("Reply-To")
    val date: Long? get() = headers.date()
    val messageId: String? get() = headers.messageIds("Message-ID").firstOrNull()
    val inReplyTo: String? get() = headers.messageIds("In-Reply-To").firstOrNull()
    val references: List<String> get() = headers.messageIds("References")

    /**
     * The plain-text reading: an explicit text/plain alternative, else the HTML flattened. Parts
     * that are attachments (a .txt someone attached) are never mistaken for the body.
     */
    fun bestText(): String? {
        val plain = bodyCandidates().firstOrNull { it.contentType.isType("text/plain") }
        if (plain != null) return plain.text()
        return bestHtml()?.let { HtmlText.toText(it) }
    }

    fun bestHtml(): String? = bodyCandidates().firstOrNull { it.contentType.isType("text/html") }?.text()

    /**
     * Text leaves that form the body, in reading preference: inside multipart/alternative the
     * richest representation the reader can show wins, in order of appearance otherwise.
     */
    private fun bodyCandidates(): List<MimeEntity> = buildList { collectBodies(root, this) }

    private fun collectBodies(e: MimeEntity, out: MutableList<MimeEntity>) {
        when {
            e.contentType.isMultipart && e.contentType.subtype == "alternative" -> {
                // Later alternatives are richer (RFC 2046 §5.1.4); keep both html and plain reachable.
                val html = e.children.lastOrNull { it.contentType.isType("text/html") || it.contentType.isMultipart }
                val plain = e.children.firstOrNull { it.contentType.isType("text/plain") }
                html?.let { collectBodies(it, out) }
                plain?.let { if (!out.contains(it)) out += it }
            }
            e.contentType.isMultipart -> e.children.forEach { collectBodies(it, out) }
            e.contentType.isText && !e.isAttachment -> out += e
        }
    }

    fun attachments(): List<MimeEntity> = root.walk().filter { it !== root && it.isAttachment }.toList()

    /** Inline parts by Content-ID, for resolving `cid:` URLs in the HTML. */
    fun inlineByCid(): Map<String, MimeEntity> =
        root.walk().mapNotNull { e -> e.contentId?.let { it to e } }.toMap()

    fun calendarParts(): List<MimeEntity> = root.walk().filter { it.contentType.isType("text/calendar") || it.contentType.isType("application/ics") }.toList()

    fun toBody(localMessageId: Long, nowMillis: Long): MessageBody {
        val html = bestHtml()
        return MessageBody(
            messageId = localMessageId,
            text = bestText(),
            html = html,
            attachments = attachments().map { it.toPartInfo() },
            structuredData = html?.let { HtmlText.jsonLd(it) }.orEmpty(),
            fetchedAt = nowMillis,
        )
    }
}

object MimeParser {

    /** Nested multiparts deeper than this are flattened to their raw bytes — a zip-bomb guard. */
    private const val MAX_DEPTH = 24

    fun parse(raw: ByteArray): ParsedMessage = ParsedMessage(parseEntity(raw, "", 0, ContentType.DEFAULT))

    /** Parse just a header block (IMAP `BODY[HEADER]`). */
    fun parseHeaders(raw: ByteArray): Headers = Headers.parse(decodeHeaderBytes(raw))

    internal fun parseEntity(raw: ByteArray, partId: String, depth: Int, defaultType: ContentType): MimeEntity {
        val split = findHeaderEnd(raw)
        val headerBytes = if (split.first >= 0) raw.copyOfRange(0, split.first) else raw
        val bodyStart = if (split.first >= 0) split.second else raw.size
        val headers = Headers.parse(decodeHeaderBytes(headerBytes))
        val ct = if (headers.has("Content-Type")) headers.contentType() else defaultType
        val rawBody = if (bodyStart < raw.size) raw.copyOfRange(bodyStart, raw.size) else ByteArray(0)

        // A top-level non-multipart message's body is IMAP part "1"; nested ids come from the caller.
        val myId = partId.ifEmpty { "1" }
        if (ct.isMultipart && depth < MAX_DEPTH) {
            val boundary = ct.boundary
            if (!boundary.isNullOrEmpty()) {
                val childDefault = if (ct.subtype == "digest") ContentType("message", "rfc822", emptyMap()) else ContentType.DEFAULT
                val chunks = splitMultipart(rawBody, boundary)
                val children = chunks.mapIndexed { i, chunk ->
                    val id = if (partId.isEmpty()) "${i + 1}" else "$partId.${i + 1}"
                    parseEntity(chunk, id, depth + 1, childDefault)
                }
                return MimeEntity(headers, ct, partId.ifEmpty { "" }, ByteArray(0), children = children)
            }
        }
        val decoded = TransferEncoding.decode(rawBody, headers["Content-Transfer-Encoding"])
        if (ct.isType("message/rfc822") && depth < MAX_DEPTH) {
            // The embedded message's own top-level parts are numbered beneath this part.
            val inner = parseEntity(decoded, myId, depth + 1, ContentType.DEFAULT).let { innerEntity ->
                if (innerEntity.contentType.isMultipart) innerEntity
                else MimeEntity(innerEntity.headers, innerEntity.contentType, "$myId.1", innerEntity.body, innerEntity.children, innerEntity.embedded)
            }
            return MimeEntity(headers, ct, myId, decoded, embedded = inner)
        }
        return MimeEntity(headers, ct, myId, decoded)
    }

    /** Header bytes may be raw UTF-8 (RFC 6532) or legacy 8-bit; decode accordingly. */
    private fun decodeHeaderBytes(bytes: ByteArray): String =
        if (bytes.all { it >= 0 }) String(bytes, Charsets.US_ASCII) else MailCharsets.decode(bytes, MailCharsets.sniff(bytes))

    /** (end of headers, start of body), or (-1, 0) when the whole thing is headers. */
    private fun findHeaderEnd(raw: ByteArray): Pair<Int, Int> {
        // A body that starts immediately with a blank line has no headers at all.
        if (raw.size >= 2 && raw[0] == CR && raw[1] == LF) return 0 to 2
        if (raw.isNotEmpty() && raw[0] == LF) return 0 to 1
        var i = 0
        while (i < raw.size) {
            if (raw[i] == LF) {
                val next = i + 1
                if (next < raw.size && raw[next] == LF) return i to next + 1
                if (next + 1 < raw.size && raw[next] == CR && raw[next + 1] == LF) return i to next + 2
            }
            i++
        }
        return -1 to raw.size
    }

    /**
     * Split a multipart body on its boundary. The CRLF *before* a delimiter belongs to the delimiter
     * (RFC 2046 §5.1.1), a missing close-delimiter ends the last part at EOF, and preamble/epilogue
     * are dropped.
     */
    internal fun splitMultipart(body: ByteArray, boundary: String): List<ByteArray> {
        val delim = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
        val parts = ArrayList<ByteArray>()
        var partStart = -1
        var lineStart = 0
        while (lineStart <= body.size) {
            var lineEnd = lineStart
            while (lineEnd < body.size && body[lineEnd] != LF) lineEnd++
            if (startsWith(body, lineStart, delim)) {
                val after = lineStart + delim.size
                val isClose = after + 1 < body.size && body[after] == DASH && body[after + 1] == DASH
                // The delimiter line must be only the delimiter (plus optional "--" and whitespace).
                val restOk = (after until lineEnd).all { idx ->
                    val b = body[idx]
                    b == CR || b == SP || b == TAB || (isClose && (idx == after || idx == after + 1))
                }
                if (restOk) {
                    if (partStart >= 0) {
                        var end = lineStart
                        // Remove the CRLF that precedes the delimiter.
                        if (end > partStart && body[end - 1] == LF) end--
                        if (end > partStart && body[end - 1] == CR) end--
                        parts += body.copyOfRange(partStart, maxOf(partStart, end))
                    }
                    if (isClose) return parts
                    partStart = minOf(lineEnd + 1, body.size)
                }
            }
            if (lineEnd >= body.size) break
            lineStart = lineEnd + 1
        }
        if (partStart in 0 until body.size) parts += body.copyOfRange(partStart, body.size)
        return parts
    }

    private fun startsWith(a: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at + prefix.size > a.size) return false
        for (i in prefix.indices) if (a[at + i] != prefix[i]) return false
        return true
    }

    private const val CR: Byte = 13
    private const val LF: Byte = 10
    private const val DASH: Byte = 45
    private const val SP: Byte = 32
    private const val TAB: Byte = 9
}

/** RFC 3676 format=flowed: soft-broken lines (ending in a space) are rejoined for display. */
object Flowed {
    fun decode(text: String, delSp: Boolean): String {
        val out = StringBuilder(text.length)
        val lines = text.replace("\r\n", "\n").split('\n')
        var paragraph = StringBuilder()
        var paragraphQuote = -1
        fun flush() {
            if (paragraphQuote >= 0) {
                out.append(">".repeat(paragraphQuote))
                if (paragraphQuote > 0) out.append(' ')
                out.append(paragraph).append('\n')
            }
            paragraph = StringBuilder()
            paragraphQuote = -1
        }
        for (raw in lines) {
            var line = raw
            var depth = 0
            while (line.startsWith(">")) { depth++; line = line.substring(1) }
            if (line.startsWith(" ")) line = line.substring(1) // space-stuffing
            if (paragraphQuote >= 0 && depth != paragraphQuote) flush()
            val soft = line.endsWith(" ") && line != "-- "
            if (soft && delSp) line = line.dropLast(1)
            if (paragraphQuote < 0) paragraphQuote = depth
            paragraph.append(line)
            if (!soft) flush()
        }
        flush()
        return out.toString().trimEnd('\n')
    }
}
