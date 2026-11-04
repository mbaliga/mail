package dev.aarso.mail.mime

import dev.aarso.mail.model.EmailAddress
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** One attachment or inline part of an outgoing message, with its bytes already in hand. */
class OutgoingPart(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val inline: Boolean = false,
    val contentId: String? = null,
)

data class OutgoingMessage(
    val from: EmailAddress,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val replyTo: List<EmailAddress> = emptyList(),
    val subject: String,
    val text: String,
    val html: String? = null,
    val parts: List<OutgoingPart> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val dateMillis: Long,
    val messageId: String,
    val requestReadReceipt: Boolean = false,
    val extraHeaders: List<Pair<String, String>> = emptyList(),
) {
    val allRecipients: List<EmailAddress> get() = (to + cc + bcc).distinctBy { it.normalized }
}

/**
 * Serialises an [OutgoingMessage] to RFC 5322 bytes.
 *
 * Structure is the minimum the content needs — `text/plain` alone, then `multipart/alternative`
 * once there is HTML, `multipart/related` around it once there are inline images, and
 * `multipart/mixed` around everything once there are attachments — because every extra layer is a
 * chance for some other client to render it wrong.
 */
object MimeBuilder {

    private val random = SecureRandom()

    fun newMessageId(fromAddress: String, nowMillis: Long): String {
        val domain = fromAddress.substringAfterLast('@').ifBlank { "localhost" }
        val rnd = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return "ygm.${java.lang.Long.toString(nowMillis, 36)}.$rnd@$domain"
    }

    /**
     * @param includeBcc true for the copy filed in Sent (so the sender can see who was blind-copied),
     *   false for the bytes handed to SMTP — a Bcc header on the wire defeats the point of Bcc.
     */
    fun build(msg: OutgoingMessage, includeBcc: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun header(name: String, value: String) {
            out.write(fold("$name: $value").toByteArray(Charsets.UTF_8))
            out.write(CRLF)
        }
        header("Date", MailDates.format(msg.dateMillis))
        header("From", AddressParser.render(listOf(msg.from)))
        if (msg.to.isNotEmpty()) header("To", AddressParser.render(msg.to))
        if (msg.cc.isNotEmpty()) header("Cc", AddressParser.render(msg.cc))
        if (includeBcc && msg.bcc.isNotEmpty()) header("Bcc", AddressParser.render(msg.bcc))
        if (msg.replyTo.isNotEmpty()) header("Reply-To", AddressParser.render(msg.replyTo))
        header("Message-ID", "<${msg.messageId}>")
        msg.inReplyTo?.let { header("In-Reply-To", "<$it>") }
        if (msg.references.isNotEmpty()) header("References", msg.references.joinToString(" ") { "<$it>" })
        header("Subject", EncodedWords.encode(msg.subject))
        if (msg.requestReadReceipt) header("Disposition-Notification-To", AddressParser.render(listOf(msg.from)))
        header("MIME-Version", "1.0")
        header("User-Agent", "You've got Mail")
        for ((k, v) in msg.extraHeaders) header(k, v)

        val inline = msg.parts.filter { it.inline && it.contentId != null }
        val attachments = msg.parts.filterNot { it.inline && it.contentId != null }

        val bodyEntity: Entity = run {
            val textPart = Entity.Leaf("text/plain; charset=UTF-8; format=flowed", "quoted-printable", TransferEncoding.encodeQuotedPrintable(spaceStuff(msg.text)).toByteArray())
            val core: Entity = if (msg.html == null) textPart else {
                val htmlPart = Entity.Leaf("text/html; charset=UTF-8", "quoted-printable", TransferEncoding.encodeQuotedPrintable(msg.html).toByteArray())
                val alt = Entity.Multipart("alternative", listOf(textPart, htmlPart))
                if (inline.isEmpty()) alt else Entity.Multipart(
                    "related",
                    listOf(alt) + inline.map { p ->
                        Entity.Leaf(
                            contentTypeFor(p), "base64", TransferEncoding.encodeBase64Lines(p.bytes).toByteArray(),
                            extra = listOf("Content-ID" to "<${p.contentId}>", "Content-Disposition" to "inline; " + HeaderParams.render("filename", p.fileName)),
                        )
                    },
                )
            }
            if (attachments.isEmpty()) core else Entity.Multipart(
                "mixed",
                listOf(core) + attachments.map { p ->
                    Entity.Leaf(
                        contentTypeFor(p), "base64", TransferEncoding.encodeBase64Lines(p.bytes).toByteArray(),
                        extra = listOf("Content-Disposition" to "attachment; " + HeaderParams.render("filename", p.fileName)),
                    )
                },
            )
        }
        writeEntity(bodyEntity, out)
        return out.toByteArray()
    }

    private fun contentTypeFor(p: OutgoingPart): String = "${p.mimeType}; " + HeaderParams.render("name", p.fileName)

    private sealed interface Entity {
        class Leaf(val contentType: String, val encoding: String, val body: ByteArray, val extra: List<Pair<String, String>> = emptyList()) : Entity
        class Multipart(val subtype: String, val children: List<Entity>) : Entity
    }

    private fun writeEntity(e: Entity, out: ByteArrayOutputStream) {
        when (e) {
            is Entity.Leaf -> {
                out.write(fold("Content-Type: ${e.contentType}").toByteArray(Charsets.UTF_8)); out.write(CRLF)
                out.write("Content-Transfer-Encoding: ${e.encoding}".toByteArray()); out.write(CRLF)
                for ((k, v) in e.extra) { out.write(fold("$k: $v").toByteArray(Charsets.UTF_8)); out.write(CRLF) }
                out.write(CRLF)
                out.write(e.body)
                if (e.body.isEmpty() || e.body.last() != '\n'.code.toByte()) out.write(CRLF)
            }
            is Entity.Multipart -> {
                val boundary = "=_ygm_" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                out.write("Content-Type: multipart/${e.subtype}; boundary=\"$boundary\"".toByteArray()); out.write(CRLF)
                out.write(CRLF)
                for (child in e.children) {
                    out.write("--$boundary".toByteArray()); out.write(CRLF)
                    writeEntity(child, out)
                }
                out.write("--$boundary--".toByteArray()); out.write(CRLF)
            }
        }
    }

    /** RFC 3676 space-stuffing for format=flowed: lines starting with space, ">" or "From " get a space. */
    private fun spaceStuff(text: String): String = text.replace("\r\n", "\n").split('\n').joinToString("\n") { line ->
        val l = line.trimEnd()
        if (l.startsWith(" ") || l.startsWith("From ")) " $l" else l
    }

    /**
     * Fold a header line to <= 78 columns at whitespace (RFC 5322 §2.2.3). Encoded words are never
     * split because they contain no whitespace; a single unbreakable token longer than the limit is
     * left long, which the RFC permits up to 998.
     */
    fun fold(line: String): String {
        if (line.length <= 78 && !line.contains("\r\n")) return line
        val out = StringBuilder(line.length + 16)
        // Respect pre-folded content (encoded-word runs come pre-joined with "\r\n ").
        for ((idx, segment) in line.split("\r\n").withIndex()) {
            if (idx > 0) out.append("\r\n")
            var col = 0
            val words = segment.split(' ')
            for ((i, w) in words.withIndex()) {
                val piece = if (i == 0) w else " $w"
                if (col + piece.length > 78 && col > 0) {
                    out.append("\r\n ").append(w)
                    col = 1 + w.length
                } else {
                    out.append(piece)
                    col += piece.length
                }
            }
        }
        return out.toString()
    }

    private val CRLF = byteArrayOf(13, 10)
}
