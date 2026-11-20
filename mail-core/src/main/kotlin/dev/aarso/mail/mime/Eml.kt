package dev.aarso.mail.mime

import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader

/**
 * A message as a standalone `.eml` (RFC 5322) file — what you attach when you want someone to see an
 * email, not a quote of it.
 *
 * The device keeps parsed parts, not the original bytes, so the file is rebuilt from the stored
 * headers and body: sender, recipients, date, subject, Message-ID and threading headers are the
 * originals; the text (and HTML, when there is some) is the stored body. It opens in any mail
 * client. When the server's original is reachable the caller should prefer it (it keeps inline
 * images and attachments); this is the offline path.
 */
object Eml {

    fun build(h: MessageHeader, body: MessageBody?): ByteArray {
        val text = body?.text ?: body?.html?.let { HtmlText.toText(it) } ?: h.preview
        val date = h.sentAt.takeIf { it > 0 } ?: h.receivedAt
        val id = h.messageId?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }
            ?: MimeBuilder.newMessageId(h.from?.address ?: "local", date)
        return MimeBuilder.build(
            OutgoingMessage(
                from = h.from ?: EmailAddress("unknown@invalid"),
                to = h.to, cc = h.cc, bcc = emptyList(), replyTo = h.replyTo,
                subject = h.subject, text = text, html = body?.html,
                inReplyTo = h.inReplyTo?.trim('<', '>'), references = h.references.map { it.trim('<', '>') },
                dateMillis = date, messageId = id,
            ),
        )
    }

    /** "Lease renewal — Priya Raman — 2026-03-02.eml": readable in a list of attachments, safe on any filesystem. */
    fun fileName(h: MessageHeader, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val day = java.time.Instant.ofEpochMilli(h.sentAt.takeIf { it > 0 } ?: h.receivedAt).atZone(zone).toLocalDate()
        val subject = h.subject.replace(Regex("(?i)^((re|fwd?|aw|wg|tr|sv)\\s*:\\s*)+"), "").trim().ifBlank { "message" }
        val who = h.from?.displayName()?.takeIf { it.isNotBlank() } ?: "unknown"
        val raw = "$subject — $who — $day"
        return raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(110).trim() + ".eml"
    }

    /** The conversation as plain text for quoting in a draft, oldest first, each message under its own header block. */
    fun quote(messages: List<Pair<MessageHeader, MessageBody?>>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        messages.sortedBy { it.first.receivedAt }.joinToString("\n\n") { (h, b) ->
            val at = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm").format(java.time.Instant.ofEpochMilli(h.receivedAt).atZone(zone))
            val text = HtmlText.newContent(b?.text ?: b?.html?.let { HtmlText.toText(it) } ?: h.preview).trim()
            "From: ${h.from?.displayName() ?: ""}\nDate: $at\nSubject: ${h.subject}\n\n" + text.lines().joinToString("\n") { "> $it" }
        }
}
