package dev.aarso.mail.sync

import dev.aarso.mail.imap.BodyPart
import dev.aarso.mail.imap.FetchItem
import dev.aarso.mail.imap.ListedMailbox
import dev.aarso.mail.jmap.JmapEmail
import dev.aarso.mail.jmap.JmapMailbox
import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.mime.Headers
import dev.aarso.mail.mime.MessageIds
import dev.aarso.mail.mime.ParsedMessage
import dev.aarso.mail.model.BodyState
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.PartInfo

/** Which part a folder plays: SPECIAL-USE attributes first, then the names servers actually use. */
object Roles {

    private val ATTRIBUTE_ROLES = mapOf(
        "\\inbox" to MailboxRole.INBOX, "\\sent" to MailboxRole.SENT, "\\drafts" to MailboxRole.DRAFTS,
        "\\trash" to MailboxRole.TRASH, "\\junk" to MailboxRole.JUNK, "\\spam" to MailboxRole.JUNK,
        "\\archive" to MailboxRole.ARCHIVE, "\\all" to MailboxRole.ALL, "\\allmail" to MailboxRole.ALL,
        "\\flagged" to MailboxRole.FLAGGED, "\\starred" to MailboxRole.FLAGGED, "\\important" to MailboxRole.IMPORTANT,
    )

    /** Leaf names in the languages accounts mostly use, lower-cased. */
    private val NAME_ROLES = mapOf(
        MailboxRole.SENT to setOf("sent", "sent items", "sent mail", "sent messages", "gesendet", "gesendete elemente", "gesendete objekte",
            "envoyés", "éléments envoyés", "messages envoyés", "enviados", "elementos enviados", "inviati", "posta inviata",
            "verzonden", "verzonden items", "skickat", "sendt", "lähetetyt", "отправленные", "已发送", "送信済み", "보낸편지함"),
        MailboxRole.DRAFTS to setOf("drafts", "draft", "entwürfe", "brouillons", "borradores", "bozze", "concepten", "utkast",
            "kladder", "luonnokset", "черновики", "草稿", "下書き", "임시보관함"),
        MailboxRole.TRASH to setOf("trash", "deleted", "deleted items", "deleted messages", "bin", "papierkorb", "gelöschte elemente",
            "corbeille", "éléments supprimés", "papelera", "elementos eliminados", "cestino", "posta eliminata", "prullenbak",
            "verwijderde items", "papperskorgen", "papirkurv", "roskakori", "корзина", "удаленные", "已删除", "ゴミ箱", "휴지통"),
        MailboxRole.JUNK to setOf("junk", "spam", "junk e-mail", "junk email", "bulk mail", "bulk", "spamverdacht", "courrier indésirable",
            "indésirables", "correo no deseado", "posta indesiderata", "ongewenste e-mail", "skräppost", "uønsket post", "roskaposti",
            "спам", "垃圾邮件", "迷惑メール", "스팸편지함"),
        MailboxRole.ARCHIVE to setOf("archive", "archives", "archiv", "archivo", "archivio", "archief", "arkiv", "arkisto", "архив", "存档", "アーカイブ", "보관함"),
    )

    fun forImap(m: ListedMailbox): MailboxRole {
        if (m.name.equals("INBOX", true)) return MailboxRole.INBOX
        m.attributes.forEach { a -> ATTRIBUTE_ROLES[a.lowercase()]?.let { return it } }
        return byName(m.leaf)
    }

    fun forJmap(m: JmapMailbox): MailboxRole = when (m.role?.lowercase()) {
        "inbox" -> MailboxRole.INBOX
        "sent" -> MailboxRole.SENT
        "drafts" -> MailboxRole.DRAFTS
        "trash" -> MailboxRole.TRASH
        "junk" -> MailboxRole.JUNK
        "archive" -> MailboxRole.ARCHIVE
        "all" -> MailboxRole.ALL
        "flagged" -> MailboxRole.FLAGGED
        "important" -> MailboxRole.IMPORTANT
        else -> byName(m.name)
    }

    fun byName(leaf: String): MailboxRole {
        val n = leaf.trim().lowercase()
        return NAME_ROLES.entries.firstOrNull { n in it.value }?.key ?: MailboxRole.NONE
    }

    /** Folders worth syncing by default. Gmail's label folders are reached through X-GM-LABELS instead. */
    fun syncByDefault(role: MailboxRole, isGmail: Boolean, name: String): Boolean = when {
        isGmail -> role in setOf(MailboxRole.ALL, MailboxRole.JUNK, MailboxRole.TRASH)
        role in setOf(MailboxRole.FLAGGED, MailboxRole.IMPORTANT, MailboxRole.ALL) -> false
        else -> !name.startsWith("[Gmail]")
    }

    /** Places order: the roles a person looks for, then their own folders alphabetically. */
    fun order(role: MailboxRole): Int = when (role) {
        MailboxRole.INBOX -> 0; MailboxRole.FLAGGED -> 1; MailboxRole.IMPORTANT -> 2; MailboxRole.DRAFTS -> 3; MailboxRole.SENT -> 4
        MailboxRole.ARCHIVE -> 5; MailboxRole.ALL -> 6; MailboxRole.JUNK -> 7; MailboxRole.TRASH -> 8; MailboxRole.OUTBOX -> 9
        MailboxRole.NONE -> 100
    }
}

/** IMAP header fields fetched alongside ENVELOPE: everything the categoriser, rules and unsubscribe need. */
const val HEADER_FIELDS = "REFERENCES LIST-ID LIST-UNSUBSCRIBE LIST-UNSUBSCRIBE-POST PRECEDENCE AUTO-SUBMITTED " +
    "X-AUTO-RESPONSE-SUPPRESS AUTHENTICATION-RESULTS FEEDBACK-ID X-CAMPAIGN X-MAILGUN-TAG X-SG-EID X-PRIORITY IMPORTANCE " +
    "X-GITHUB-REASON X-ZENDESK-FROM-ACCOUNT-ID X-FRESHDESK-TICKET-ID X-JIRA-FINGERPRINT X-LINEAR-TEAM X-MAILER X-ENTITY-ID"

object Mapping {

    fun mailbox(accountId: Long, m: ListedMailbox, isGmail: Boolean): Mailbox {
        val role = Roles.forImap(m)
        return Mailbox(
            accountId = accountId, remoteId = m.name, name = if (m.name.equals("INBOX", true)) "Inbox" else m.leaf,
            delimiter = m.delimiter, role = role, selectable = m.selectable,
            syncEnabled = m.selectable && Roles.syncByDefault(role, isGmail, m.name),
            totalCount = m.status["MESSAGES"]?.toInt() ?: 0, unreadCount = m.status["UNSEEN"]?.toInt() ?: 0,
            sortOrder = Roles.order(role),
        )
    }

    fun mailbox(accountId: Long, m: JmapMailbox, path: String): Mailbox {
        val role = Roles.forJmap(m)
        return Mailbox(
            accountId = accountId, remoteId = m.id, name = m.name, delimiter = '/', role = role,
            syncEnabled = true, totalCount = m.totalEmails, unreadCount = m.unreadEmails,
            sortOrder = Roles.order(role) * 1000 + m.sortOrder,
        ).let { if (path != m.name) it.copy(name = m.name) else it }
    }

    /**
     * FETCH → header. The thread key is provisional here ([threadKeyHint]); the sync step resolves it
     * against messages already stored so a reply joins its parent's conversation.
     */
    fun header(accountId: Long, mailboxId: Long, f: FetchItem, isGmail: Boolean): MessageHeader? {
        val uid = f.uid ?: return null
        val env = f.envelope
        val extra = f.section("HEADER.FIELDS ($HEADER_FIELDS)")?.let { Headers.parse(String(it, Charsets.UTF_8)) } ?: Headers.EMPTY
        val bs = f.bodyStructure
        val leaves = bs?.leaves().orEmpty()
        val attachments = leaves.filter { it.isAttachment }
        val labels = f.gmailLabels.orEmpty()
        var flags = Flags.fromImap(f.flags.orEmpty())
        if (isGmail && labels.any { it.equals("\\Important", true) }) flags = flags or Flags.IMPORTANT
        val refs = extra.messageIds("References")
        return MessageHeader(
            accountId = accountId, mailboxId = mailboxId, uid = uid,
            providerMessageId = f.gmailMessageId ?: f.emailId,
            messageId = env?.messageId, inReplyTo = env?.inReplyTo, references = refs,
            threadKey = f.gmailThreadId?.let { "gm:$it" } ?: f.threadId?.let { "ob:$it" } ?: threadKeyHint(refs, env?.inReplyTo, env?.messageId, uid, mailboxId),
            subject = env?.subject.orEmpty(),
            from = env?.from?.firstOrNull(), sender = env?.sender?.firstOrNull()?.takeIf { s -> s.normalized != env.from.firstOrNull()?.normalized },
            replyTo = env?.replyTo.orEmpty().filterNot { r -> env?.from?.any { it.normalized == r.normalized } == true },
            to = env?.to.orEmpty(), cc = env?.cc.orEmpty(), bcc = env?.bcc.orEmpty(),
            sentAt = env?.date ?: f.internalDate ?: 0, receivedAt = f.internalDate ?: env?.date ?: 0,
            size = f.size ?: 0, flags = flags, labels = labels,
            preview = f.preview?.let { HtmlText.preview(it) }.orEmpty(),
            attachmentCount = attachments.size,
            hasCalendar = leaves.any { it.mimeType == "text/calendar" || it.mimeType == "application/ics" },
            listId = extra["List-Id"]?.let { MessageIds.parse(it).firstOrNull() ?: it.trim() },
            listUnsubscribe = extra["List-Unsubscribe"]?.trim(),
            listUnsubscribePost = extra["List-Unsubscribe-Post"]?.contains("One-Click", true) == true,
            isBulk = extra["Precedence"]?.trim()?.lowercase() in setOf("bulk", "list", "junk") || extra.has("Feedback-ID") ||
                extra.has("X-Campaign") || extra.has("X-SG-EID") || extra.has("X-Mailgun-Tag"),
            isAutomated = extra["Auto-Submitted"]?.trim()?.lowercase()?.let { it != "no" } == true ||
                extra.has("X-Auto-Response-Suppress"),
            parts = leaves.map(BodyPart::toPartInfo),
            authResults = extra["Authentication-Results"]?.take(400),
            importanceHint = extra["Importance"] ?: extra["X-Priority"],
            serviceHints = buildServiceHints(extra),
        )
    }

    private fun buildServiceHints(h: Headers): Set<String> = buildSet {
        if (h.has("X-GitHub-Reason")) add("github")
        if (h.has("X-Zendesk-From-Account-Id")) add("zendesk")
        if (h.has("X-Freshdesk-Ticket-Id")) add("freshdesk")
        if (h.has("X-JIRA-FingerPrint")) add("jira")
        if (h.has("X-Linear-Team")) add("linear")
    }

    fun threadKeyHint(refs: List<String>, inReplyTo: String?, messageId: String?, uid: Long, mailboxId: Long): String {
        val root = refs.firstOrNull() ?: inReplyTo ?: messageId
        return if (root != null) "id:$root" else "uid:$mailboxId:$uid"
    }

    fun header(accountId: Long, mailboxId: Long, e: JmapEmail, mailboxNames: Map<String, String>): MessageHeader {
        val h = Headers(e.headers.toList())
        return MessageHeader(
            accountId = accountId, mailboxId = mailboxId, remoteId = e.id, providerMessageId = e.blobId,
            messageId = e.messageId, inReplyTo = e.inReplyTo, references = e.references,
            threadKey = "jm:${e.threadId}", subject = e.subject,
            from = e.from.firstOrNull(), sender = e.sender.firstOrNull()?.takeIf { s -> s.normalized != e.from.firstOrNull()?.normalized },
            replyTo = e.replyTo, to = e.to, cc = e.cc, bcc = e.bcc,
            sentAt = e.sentAt ?: e.receivedAt, receivedAt = e.receivedAt, size = e.size,
            flags = Flags.fromJmap(e.keywords),
            labels = e.mailboxIds.mapNotNull { mailboxNames[it] },
            preview = e.preview.take(240),
            attachmentCount = e.attachments.count { !it.disposition.equals("inline", true) || it.cid == null },
            hasCalendar = e.attachments.any { it.type == "text/calendar" || it.type == "application/ics" },
            listId = h["List-Id"]?.let { MessageIds.parse(it).firstOrNull() ?: it.trim() },
            listUnsubscribe = h["List-Unsubscribe"]?.trim(),
            listUnsubscribePost = h["List-Unsubscribe-Post"]?.contains("One-Click", true) == true,
            isBulk = h["Precedence"]?.trim()?.lowercase() in setOf("bulk", "list", "junk"),
            isAutomated = h["Auto-Submitted"]?.trim()?.lowercase()?.let { it != "no" } == true,
            parts = e.attachments.map { a ->
                PartInfo(a.partId ?: a.blobId.orEmpty(), a.type, a.charset, a.name, a.cid, a.disposition, null, a.size, a.blobId)
            },
            bodyState = if (e.textBody != null || e.htmlBody != null) BodyState.FULL else BodyState.NONE,
            authResults = h["Authentication-Results"]?.take(400),
        )
    }

    /** A fully parsed message (POP3 download, or an .eml import) → header. */
    fun header(accountId: Long, mailboxId: Long, remoteId: String, p: ParsedMessage, receivedAt: Long, size: Long): MessageHeader {
        val h = p.headers
        val refs = p.references
        val text = p.bestText().orEmpty()
        return MessageHeader(
            accountId = accountId, mailboxId = mailboxId, remoteId = remoteId,
            messageId = p.messageId, inReplyTo = p.inReplyTo, references = refs,
            threadKey = threadKeyHint(refs, p.inReplyTo, p.messageId, 0, mailboxId).let { if (it.startsWith("uid:")) "pop:$remoteId" else it },
            subject = p.subject, from = p.from, sender = p.sender, replyTo = p.replyTo, to = p.to, cc = p.cc, bcc = p.bcc,
            sentAt = p.date ?: receivedAt, receivedAt = receivedAt, size = size,
            preview = HtmlText.preview(text),
            attachmentCount = p.attachments().size,
            hasCalendar = p.calendarParts().isNotEmpty(),
            listId = h["List-Id"]?.let { MessageIds.parse(it).firstOrNull() ?: it.trim() },
            listUnsubscribe = h["List-Unsubscribe"]?.trim(),
            listUnsubscribePost = h["List-Unsubscribe-Post"]?.contains("One-Click", true) == true,
            isBulk = h["Precedence"]?.trim()?.lowercase() in setOf("bulk", "list", "junk") || h.has("Feedback-ID"),
            isAutomated = h["Auto-Submitted"]?.trim()?.lowercase()?.let { it != "no" } == true,
            parts = p.attachments().map { it.toPartInfo() },
            authResults = h["Authentication-Results"]?.take(400),
        )
    }
}
