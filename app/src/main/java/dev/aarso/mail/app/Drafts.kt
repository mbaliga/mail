package dev.aarso.mail.app

import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Draft
import dev.aarso.mail.model.DraftAttachment
import dev.aarso.mail.model.DraftKind
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Replies, reply-alls and forwards built the way a careful desktop client builds them. */
object Drafts {

    private val prefix = Regex("(?i)^((re|fwd?|aw|wg|tr|sv|vs|antw)\\s*(\\[\\d+])?\\s*:\\s*)+")

    fun baseSubject(s: String) = s.replace(prefix, "").trim()

    private fun quoteHeader(h: MessageHeader, zone: ZoneId): String {
        val at = DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(h.sentAt.takeIf { it > 0 } ?: h.receivedAt).atZone(zone))
        return "On $at, ${h.from?.displayName() ?: "someone"} wrote:"
    }

    private fun quoted(body: MessageBody?, h: MessageHeader): String {
        val text = body?.text ?: body?.html?.let { HtmlText.toText(it) } ?: h.preview
        return text.trimEnd().lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    }

    private fun signature(account: Account, from: EmailAddress): String {
        val sig = account.identities.firstOrNull { it.address.normalized == from.normalized }?.signature ?: account.primaryIdentity.signature
        return if (sig.isBlank()) "" else "\n\n-- \n$sig"
    }

    /** Which of my addresses this message reached, so the reply goes out from the same one. */
    fun replyFrom(account: Account, h: MessageHeader): EmailAddress {
        val mine = (h.to + h.cc + h.bcc).map { it.normalized }.toSet()
        return account.identities.firstOrNull { it.address.normalized in mine }?.address ?: account.primaryIdentity.address
    }

    fun new(account: Account, to: List<EmailAddress> = emptyList(), subject: String = "", body: String = ""): Draft {
        val from = account.primaryIdentity.address
        return Draft(accountId = account.id, from = from, to = to, subject = subject, textBody = body + signature(account, from))
    }

    fun reply(account: Account, h: MessageHeader, body: MessageBody?, all: Boolean, zone: ZoneId = ZoneId.systemDefault()): Draft {
        val from = replyFrom(account, h)
        val me = account.myAddresses
        val fromMe = h.from?.normalized in me
        val primary = when {
            fromMe -> h.to // replying to my own sent message continues to its recipients
            h.replyTo.isNotEmpty() -> h.replyTo
            else -> listOfNotNull(h.from)
        }
        val to = primary.filter { it.normalized !in me || fromMe }.distinctBy { it.normalized }
        val cc = if (!all) emptyList() else (h.to + h.cc).filter { it.normalized !in me && to.none { t -> t.normalized == it.normalized } }.distinctBy { it.normalized }
        val refs = (h.references + listOfNotNull(h.messageId)).distinct().takeLast(20)
        val text = signature(account, from) + "\n\n" + quoteHeader(h, zone) + "\n" + quoted(body, h)
        return Draft(
            accountId = account.id, from = from, to = to, cc = cc,
            subject = "Re: " + baseSubject(h.subject),
            textBody = text, inReplyTo = h.messageId, references = refs,
            sourceMessageId = h.id, kind = if (all) DraftKind.REPLY_ALL else DraftKind.REPLY,
        )
    }

    fun forward(account: Account, h: MessageHeader, body: MessageBody?, attachments: List<DraftAttachment>, zone: ZoneId = ZoneId.systemDefault()): Draft {
        val from = replyFrom(account, h)
        val at = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(h.receivedAt).atZone(zone))
        val header = buildString {
            append("---------- Forwarded message ----------\n")
            append("From: ${h.from?.let { if (it.name.isNullOrBlank()) it.address else "${it.name} <${it.address}>" } ?: ""}\n")
            append("Date: $at\n")
            append("Subject: ${h.subject}\n")
            if (h.to.isNotEmpty()) append("To: ${h.to.joinToString(", ") { it.address }}\n")
            if (h.cc.isNotEmpty()) append("Cc: ${h.cc.joinToString(", ") { it.address }}\n")
        }
        val text = body?.text ?: body?.html?.let { HtmlText.toText(it) } ?: h.preview
        return Draft(
            accountId = account.id, from = from,
            subject = "Fwd: " + baseSubject(h.subject),
            textBody = signature(account, from) + "\n\n" + header + "\n" + text,
            attachments = attachments,
            references = (h.references + listOfNotNull(h.messageId)).distinct().takeLast(20),
            sourceMessageId = h.id, kind = DraftKind.FORWARD,
        )
    }

    /** "Edit as new": same content and recipients, no threading. */
    fun editAsNew(account: Account, h: MessageHeader, body: MessageBody?): Draft = Draft(
        accountId = account.id, from = replyFrom(account, h), to = h.to, cc = h.cc, bcc = h.bcc,
        subject = h.subject, textBody = body?.text ?: body?.html?.let { HtmlText.toText(it) }.orEmpty(),
        kind = DraftKind.EDIT_AS_NEW,
    )

    /** A mailto: URI (RFC 6068) as a draft: to, cc, bcc, subject, body. */
    fun fromMailto(account: Account, uri: String): Draft {
        val raw = uri.removePrefix("mailto:")
        val (addrPart, query) = raw.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        fun dec(s: String) = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        fun list(s: String?) = s?.split(',')?.map { dec(it).trim() }?.filter { it.contains('@') }?.map { EmailAddress(it) }.orEmpty()
        val params = query?.split('&')?.mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase() to dec(it[1]) } }?.toMap().orEmpty()
        val d = new(account, to = list(addrPart) + list(params["to"]), subject = params["subject"].orEmpty(), body = params["body"].orEmpty())
        return d.copy(cc = list(params["cc"]), bcc = list(params["bcc"]))
    }
}
