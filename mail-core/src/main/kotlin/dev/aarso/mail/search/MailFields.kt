package dev.aarso.mail.search

import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.search.EvalContext
import dev.aarso.search.FacetField
import dev.aarso.search.FacetValueKind
import dev.aarso.search.FieldRegistry
import dev.aarso.search.Op
import dev.aarso.search.RelativeDate
import java.time.ZoneId

/** One message as search sees it: the header, the folder it is in, its account, and (lazily) its text. */
class MailItem(
    val header: MessageHeader,
    val mailbox: Mailbox?,
    val account: Account?,
    val text: Map<String, String>? = null,
) {
    val me: Set<String> get() = account?.myAddresses.orEmpty()
}

/** A WHERE fragment (aliases `m.` = message, `mb.` = mailbox). [exact] = no in-memory re-check needed. */
class SqlPart(val sql: String, val args: List<Any?> = emptyList(), val exact: Boolean = true)

/** A facet that can also push itself down into SQL. */
abstract class MailFacet(
    final override val key: String,
    final override val valueKind: FacetValueKind,
    final override val aliases: Set<String> = emptySet(),
) : FacetField<MailItem> {
    override fun isBacked(value: String): Boolean = true
    open fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? = null
}

/**
 * The mail vocabulary of the one query language used by search, selection rules, saved views and
 * automation rules. Every field has an exact in-memory predicate (used by search-core's evaluator)
 * and, where possible, an SQL pushdown so a query over 200,000 messages touches only the rows it
 * can match. Values are matched case-insensitively; `me` means any of the account's own addresses.
 */
class MailFields(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val accounts: () -> List<Account> = { emptyList() },
) {

    private fun like(v: String) = "%" + v.lowercase().replace("%", "\\%").replace("_", "\\_") + "%"

    private fun cmpText(op: Op, actual: Boolean): Boolean = if (op == Op.NE) !actual else actual

    private fun addrMatches(a: EmailAddress, v: String, me: Set<String>): Boolean {
        val needle = v.lowercase()
        if (needle == "me") return a.normalized in me
        if (needle.startsWith("@")) return a.domain == needle.drop(1) || a.domain.endsWith("." + needle.drop(1))
        return a.normalized.contains(needle) || a.name?.lowercase()?.contains(needle) == true
    }

    private fun meSql(column: String, me: Set<String>): SqlPart =
        if (me.isEmpty()) SqlPart("0") else SqlPart(me.joinToString(" OR ", "(", ")") { "LOWER($column) LIKE ?" }, me.map { like(it) })

    private fun allMe(): Set<String> = accounts().flatMap { it.myAddresses }.toSet()

    // ---- people ----

    val from = object : MailFacet("from", FacetValueKind.TEXT, setOf("sender", "f")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean =
            cmpText(op, subject.header.from?.let { addrMatches(it, value, subject.me) } == true ||
                subject.header.sender?.let { addrMatches(it, value, subject.me) } == true)

        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val v = value.lowercase()
            val p = when {
                v == "me" -> meSql("m.from_addr", allMe())
                v.startsWith("@") -> SqlPart("(LOWER(m.from_addr) LIKE ? ESCAPE '\\' OR LOWER(m.from_addr) LIKE ? ESCAPE '\\')", listOf("%" + v, "%." + v.drop(1)), exact = false)
                else -> SqlPart("(LOWER(m.from_addr) LIKE ? ESCAPE '\\' OR LOWER(m.from_name) LIKE ? ESCAPE '\\' OR LOWER(m.sender_addr) LIKE ? ESCAPE '\\')", listOf(like(v), like(v), like(v)))
            }
            return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args, p.exact) else p
        }
    }

    private fun recipientField(key: String, aliases: Set<String>, pick: (MessageHeader) -> List<EmailAddress>, columns: List<String>) =
        object : MailFacet(key, FacetValueKind.TEXT, aliases) {
            override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean =
                cmpText(op, pick(subject.header).any { addrMatches(it, value, subject.me) })

            override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
                val v = value.lowercase()
                val p = if (v == "me") {
                    val me = allMe()
                    if (me.isEmpty()) SqlPart("0") else SqlPart(columns.flatMap { c -> me.map { "LOWER($c) LIKE ?" } }.joinToString(" OR ", "(", ")"), columns.flatMap { me.map { like(it) } })
                } else SqlPart(columns.joinToString(" OR ", "(", ")") { "LOWER($it) LIKE ? ESCAPE '\\'" }, columns.map { like(v) }, exact = false)
                return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args, false) else p
            }
        }

    val to = recipientField("to", setOf("rcpt", "recipient"), { it.to + it.cc + it.bcc }, listOf("m.to_list", "m.cc_list", "m.bcc_list"))
    val cc = recipientField("cc", emptySet(), { it.cc }, listOf("m.cc_list"))
    val bcc = recipientField("bcc", emptySet(), { it.bcc }, listOf("m.bcc_list"))

    val domain = object : MailFacet("domain", FacetValueKind.TEXT) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val d = subject.header.from?.domain.orEmpty(); val v = value.lowercase().removePrefix("@")
            return cmpText(op, d == v || d.endsWith(".$v"))
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val v = value.lowercase().removePrefix("@")
            val p = SqlPart("(LOWER(m.from_addr) LIKE ? OR LOWER(m.from_addr) LIKE ?)", listOf("%@$v", "%.$v"))
            return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args) else p
        }
    }

    // ---- content ----

    val subject = object : MailFacet("subject", FacetValueKind.TEXT, setOf("title", "s")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean =
            cmpText(op, subject.header.subject.lowercase().contains(value.lowercase()))
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val p = SqlPart("LOWER(m.subject) LIKE ? ESCAPE '\\'", listOf(like(value)))
            return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args) else p
        }
    }

    /** `body:` searches only what the author wrote (the FTS body column holds new content, not quotes). */
    val body = object : MailFacet("body", FacetValueKind.TEXT, setOf("text", "content")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val text = subject.text?.get("body") ?: subject.header.preview
            return cmpText(op, text.lowercase().contains(value.lowercase()))
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val p = SqlPart("m.id IN (SELECT rowid FROM message_fts WHERE message_fts MATCH ?)", listOf("body : " + ftsQuote(value) + "*"), exact = false)
            return if (op == Op.NE) SqlPart("NOT (${p.sql})", p.args, false) else p
        }
    }

    val list = object : MailFacet("list", FacetValueKind.TEXT, setOf("mailinglist")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val id = subject.header.listId
            val actual = if (value == "*" || value.equals("any", true)) id != null else id?.lowercase()?.contains(value.lowercase()) == true
            return cmpText(op, actual)
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val p = if (value == "*" || value.equals("any", true)) SqlPart("m.list_id IS NOT NULL") else SqlPart("LOWER(m.list_id) LIKE ? ESCAPE '\\'", listOf(like(value)))
            return if (op == Op.NE) SqlPart("NOT COALESCE(${p.sql}, 0)", p.args) else p
        }
    }

    // ---- state ----

    private val IS_VALUES = setOf(
        "unread", "read", "starred", "flagged", "unstarred", "answered", "replied", "unanswered", "forwarded", "draft", "pinned",
        "snoozed", "muted", "important", "bulk", "automated", "person", "human", "mine", "sent", "attachment", "otp", "tracked",
    )

    val isField = object : MailFacet("is", FacetValueKind.ENUM, setOf("state")) {
        override fun isBacked(value: String) = value.lowercase() in IS_VALUES
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val h = subject.header
            val actual = when (value.lowercase()) {
                "unread" -> !h.isSeen
                "read" -> h.isSeen
                "starred", "flagged" -> h.isFlagged
                "unstarred" -> !h.isFlagged
                "answered", "replied" -> h.isAnswered
                "unanswered" -> !h.isAnswered
                "forwarded" -> h.flags and Flags.FORWARDED != 0
                "draft" -> h.isDraft || subject.mailbox?.role == MailboxRole.DRAFTS
                "pinned" -> h.pinned
                "snoozed" -> h.snoozedUntil > ctx.nowMillis
                "muted" -> h.muted
                "important" -> h.flags and Flags.IMPORTANT != 0 || h.labels.any { it.equals("\\Important", true) }
                "bulk" -> h.isBulk || h.listId != null
                "automated" -> h.isAutomated
                "person", "human" -> h.category == Category.PEOPLE || h.category == Category.REPLIES
                "mine", "sent" -> h.from?.normalized in subject.me || subject.mailbox?.role == MailboxRole.SENT
                "attachment" -> h.hasAttachments
                "otp" -> h.otpCode != null || h.category == Category.OTP
                "tracked" -> h.trackerCount > 0
                else -> false
            }
            return cmpText(op, actual)
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? {
            val p = when (value.lowercase()) {
                "unread" -> SqlPart("(m.flags & 1) = 0")
                "read" -> SqlPart("(m.flags & 1) != 0")
                "starred", "flagged" -> SqlPart("(m.flags & 2) != 0")
                "unstarred" -> SqlPart("(m.flags & 2) = 0")
                "answered", "replied" -> SqlPart("(m.flags & 4) != 0")
                "unanswered" -> SqlPart("(m.flags & 4) = 0")
                "forwarded" -> SqlPart("(m.flags & 32) != 0")
                "draft" -> SqlPart("((m.flags & 8) != 0 OR mb.role = 'DRAFTS')")
                "pinned" -> SqlPart("m.pinned = 1")
                "snoozed" -> SqlPart("m.snoozed_until > ?", listOf(ctx.nowMillis))
                "muted" -> SqlPart("m.muted = 1")
                "important" -> SqlPart("((m.flags & 256) != 0 OR m.labels LIKE ?)", listOf("%\u001F\\Important\u001F%"))
                "bulk" -> SqlPart("(m.is_bulk = 1 OR m.list_id IS NOT NULL)")
                "automated" -> SqlPart("m.is_automated = 1")
                "person", "human" -> SqlPart("m.category IN ('people', 'replies')")
                "attachment" -> SqlPart("m.attachment_count > 0")
                "otp" -> SqlPart("(m.otp_code IS NOT NULL OR m.category = 'otp')")
                "tracked" -> SqlPart("m.tracker_count > 0")
                "mine", "sent" -> {
                    val me = meSql("m.from_addr", allMe())
                    SqlPart("(${me.sql} OR mb.role = 'SENT')", me.args)
                }
                else -> return null
            }
            return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args) else p
        }
    }

    /** Attachment kinds by MIME prefix or extension — `has:pdf`, `type:image`. */
    private val KINDS: Map<String, (String, String?) -> Boolean> = mapOf(
        "image" to { mime, _ -> mime.startsWith("image/") },
        "pdf" to { mime, name -> mime == "application/pdf" || name?.endsWith(".pdf", true) == true },
        "document" to { mime, name -> mime.contains("word") || mime.contains("opendocument.text") || mime == "application/rtf" || listOf(".doc", ".docx", ".odt", ".rtf", ".pages").any { name?.endsWith(it, true) == true } },
        "spreadsheet" to { mime, name -> mime.contains("sheet") || mime.contains("excel") || mime == "text/csv" || listOf(".xls", ".xlsx", ".ods", ".csv", ".numbers").any { name?.endsWith(it, true) == true } },
        "presentation" to { mime, name -> mime.contains("presentation") || mime.contains("powerpoint") || listOf(".ppt", ".pptx", ".odp", ".key").any { name?.endsWith(it, true) == true } },
        "video" to { mime, _ -> mime.startsWith("video/") },
        "audio" to { mime, _ -> mime.startsWith("audio/") },
        "archive" to { mime, name -> mime.contains("zip") || mime.contains("compressed") || mime.contains("x-tar") || listOf(".zip", ".rar", ".7z", ".tar", ".gz").any { name?.endsWith(it, true) == true } },
        "calendar" to { mime, name -> mime == "text/calendar" || mime == "application/ics" || name?.endsWith(".ics", true) == true },
        "eml" to { mime, _ -> mime == "message/rfc822" },
    )
    private val KIND_SQL = mapOf(
        "image" to "%\"mimeType\":\"image/%", "pdf" to "%application/pdf%", "video" to "%\"mimeType\":\"video/%",
        "audio" to "%\"mimeType\":\"audio/%", "calendar" to "%text/calendar%", "eml" to "%message/rfc822%",
    )
    private val HAS_EXTRA = setOf("attachment", "attachments", "file", "files", "invite", "unsubscribe", "tracker", "trackers", "otp", "code", "tldr", "calendar")

    val has = object : MailFacet("has", FacetValueKind.ENUM, setOf("type", "kind")) {
        override fun isBacked(value: String) = value.lowercase().removeSuffix("s") in KINDS.keys || value.lowercase() in HAS_EXTRA || value.lowercase().removeSuffix("s") in HAS_EXTRA
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val h = subject.header
            val v = value.lowercase()
            val actual = when (v) {
                "attachment", "attachments", "file", "files" -> h.hasAttachments
                "invite", "calendar" -> h.hasCalendar
                "unsubscribe" -> h.listUnsubscribe != null
                "tracker", "trackers" -> h.trackerCount > 0
                "otp", "code" -> h.otpCode != null
                "tldr" -> h.tldr != null
                else -> KINDS[v.removeSuffix("s")]?.let { k -> h.parts.any { it.isAttachment && k(it.mimeType.lowercase(), it.fileName) } } ?: false
            }
            return cmpText(op, actual)
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? {
            val v = value.lowercase()
            val p = when (v) {
                "attachment", "attachments", "file", "files" -> SqlPart("m.attachment_count > 0")
                "invite", "calendar" -> SqlPart("m.has_calendar = 1")
                "unsubscribe" -> SqlPart("m.list_unsubscribe IS NOT NULL")
                "tracker", "trackers" -> SqlPart("m.tracker_count > 0")
                "otp", "code" -> SqlPart("m.otp_code IS NOT NULL")
                "tldr" -> SqlPart("m.tldr IS NOT NULL")
                else -> {
                    val pattern = KIND_SQL[v.removeSuffix("s")]
                    if (pattern != null) SqlPart("(m.attachment_count > 0 AND m.parts LIKE ?)", listOf(pattern), exact = false)
                    else SqlPart("m.attachment_count > 0", exact = false)
                }
            }
            return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args, false) else p
        }
    }

    // ---- where ----

    private val ROLE_WORDS = mapOf(
        "inbox" to MailboxRole.INBOX, "sent" to MailboxRole.SENT, "drafts" to MailboxRole.DRAFTS, "draft" to MailboxRole.DRAFTS,
        "trash" to MailboxRole.TRASH, "bin" to MailboxRole.TRASH, "deleted" to MailboxRole.TRASH, "spam" to MailboxRole.JUNK,
        "junk" to MailboxRole.JUNK, "archive" to MailboxRole.ARCHIVE, "archived" to MailboxRole.ARCHIVE,
    )
    private val GMAIL_ROLE_LABELS = mapOf(MailboxRole.INBOX to "\\Inbox", MailboxRole.SENT to "\\Sent", MailboxRole.DRAFTS to "\\Draft")

    val inField = object : MailFacet("in", FacetValueKind.TEXT, setOf("folder", "mailbox", "box")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val v = value.lowercase()
            val h = subject.header
            val box = subject.mailbox
            val actual = when {
                v == "anywhere" || v == "all" || v == "everywhere" -> true
                v == "starred" -> h.isFlagged
                v == "important" -> h.flags and Flags.IMPORTANT != 0
                v == "snoozed" -> h.snoozedUntil > ctx.nowMillis
                ROLE_WORDS.containsKey(v) -> {
                    val role = ROLE_WORDS.getValue(v)
                    if (role == MailboxRole.ARCHIVE && box?.role == MailboxRole.ALL) h.labels.none { it.equals("\\Inbox", true) }
                    else box?.role == role || GMAIL_ROLE_LABELS[role]?.let { l -> h.labels.any { it.equals(l, true) } } == true
                }
                else -> box?.name?.lowercase() == v || box?.remoteId?.lowercase() == v || h.labels.any { it.lowercase() == v }
            }
            return cmpText(op, actual)
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? {
            val v = value.lowercase()
            val p = when {
                v == "anywhere" || v == "all" || v == "everywhere" -> SqlPart("1")
                v == "starred" -> SqlPart("(m.flags & 2) != 0")
                v == "important" -> SqlPart("(m.flags & 256) != 0")
                v == "snoozed" -> SqlPart("m.snoozed_until > ?", listOf(ctx.nowMillis))
                ROLE_WORDS.containsKey(v) -> {
                    val role = ROLE_WORDS.getValue(v)
                    val label = GMAIL_ROLE_LABELS[role]
                    when {
                        role == MailboxRole.ARCHIVE -> SqlPart("(mb.role = 'ARCHIVE' OR (mb.role = 'ALL' AND m.labels NOT LIKE ?))", listOf("%\u001F\\Inbox\u001F%"))
                        label != null -> SqlPart("(mb.role = ? OR m.labels LIKE ?)", listOf(role.name, "%\u001F$label\u001F%"))
                        else -> SqlPart("mb.role = ?", listOf(role.name))
                    }
                }
                else -> SqlPart("(LOWER(mb.name) = ? OR LOWER(mb.remote_id) = ? OR LOWER(m.labels) LIKE ?)", listOf(v, v, "%\u001F$v\u001F%"))
            }
            return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args) else p
        }
    }

    val label = object : MailFacet("label", FacetValueKind.TEXT, setOf("l", "tag")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean =
            cmpText(op, subject.header.labels.any { it.equals(value, true) } || subject.mailbox?.name.equals(value, true))
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val p = SqlPart("(LOWER(m.labels) LIKE ? OR LOWER(mb.name) = ?)", listOf("%\u001F" + value.lowercase() + "\u001F%", value.lowercase()))
            return if (op == Op.NE) SqlPart("NOT ${p.sql}", p.args) else p
        }
    }

    val category = object : MailFacet("category", FacetValueKind.ENUM, setOf("cat", "tab")) {
        override fun isBacked(value: String) = Category.fromKey(value) != null || Category.fromKey(value.removeSuffix("s")) != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val c = Category.fromKey(value) ?: Category.fromKey(value.removeSuffix("s"))
            return cmpText(op, subject.header.category == c)
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? {
            val c = Category.fromKey(value) ?: Category.fromKey(value.removeSuffix("s")) ?: return null
            return SqlPart(if (op == Op.NE) "m.category != ?" else "m.category = ?", listOf(c.key))
        }
    }

    val account = object : MailFacet("account", FacetValueKind.TEXT, setOf("acct", "on")) {
        private fun ids(value: String): Set<Long> {
            val v = value.lowercase()
            return accounts().filter {
                it.email.lowercase() == v || it.email.lowercase().contains(v) || it.providerId == v || it.displayName.lowercase() == v ||
                    dev.aarso.mail.discovery.Providers.byId(it.providerId)?.name?.lowercase() == v
            }.map { it.id }.toSet()
        }
        override fun isBacked(value: String) = ids(value).isNotEmpty()
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) = cmpText(op, subject.header.accountId in ids(value))
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val set = ids(value)
            if (set.isEmpty()) return SqlPart(if (op == Op.NE) "1" else "0")
            return SqlPart("m.account_id ${if (op == Op.NE) "NOT IN" else "IN"} (${set.joinToString(",")})")
        }
    }

    // ---- time ----

    /** `date:`, `received:` — EQ means "within that day/week/month", comparisons use its start. */
    private fun dateField(key: String, aliases: Set<String>, forcedOp: Op? = null) = object : MailFacet(key, FacetValueKind.DATE, aliases) {
        private fun range(value: String, ctx: EvalContext) = RelativeDate.resolveRange(value, ctx, zone)
        override fun isBacked(value: String) = true
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val r = range(value, ctx) ?: return false
            val t = subject.header.receivedAt
            return when (forcedOp ?: op) {
                Op.EQ -> t in r
                Op.NE -> t !in r
                Op.GT -> t >= r.endExclusiveMillis
                Op.GTE -> t >= r.startInclusiveMillis
                Op.LT -> t < r.startInclusiveMillis
                Op.LTE -> t < r.endExclusiveMillis
            }
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? {
            val r = range(value, ctx) ?: return null
            return when (forcedOp ?: op) {
                Op.EQ -> SqlPart("m.received_at >= ? AND m.received_at < ?", listOf(r.startInclusiveMillis, r.endExclusiveMillis))
                Op.NE -> SqlPart("(m.received_at < ? OR m.received_at >= ?)", listOf(r.startInclusiveMillis, r.endExclusiveMillis))
                Op.GT -> SqlPart("m.received_at >= ?", listOf(r.endExclusiveMillis))
                Op.GTE -> SqlPart("m.received_at >= ?", listOf(r.startInclusiveMillis))
                Op.LT -> SqlPart("m.received_at < ?", listOf(r.startInclusiveMillis))
                Op.LTE -> SqlPart("m.received_at < ?", listOf(r.endExclusiveMillis))
            }
        }
    }

    val date = dateField("date", setOf("modified", "received", "on", "during", "when"))
    val after = dateField("after", setOf("since", "from_date"), Op.GTE)
    val before = dateField("before", setOf("until", "till"), Op.LT)

    /** `older_than:7d`, `newer_than:2w` (h, d, w, m, y), Gmail-compatible. */
    private fun ageField(key: String, aliases: Set<String>, older: Boolean) = object : MailFacet(key, FacetValueKind.TEXT, aliases) {
        override fun isBacked(value: String) = parseAge(value) != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val ms = parseAge(value) ?: return false
            val cutoff = ctx.nowMillis - ms
            return if (older) subject.header.receivedAt < cutoff else subject.header.receivedAt >= cutoff
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? {
            val ms = parseAge(value) ?: return null
            return SqlPart(if (older) "m.received_at < ?" else "m.received_at >= ?", listOf(ctx.nowMillis - ms))
        }
    }

    val olderThan = ageField("older_than", setOf("older"), older = true)
    val newerThan = ageField("newer_than", setOf("newer", "within"), older = false)

    // ---- sizes and counts ----

    val size = object : MailFacet("size", FacetValueKind.NUMBER, setOf("bytes")) {
        override fun isBacked(value: String) = parseSize(value) != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val n = parseSize(value) ?: return false
            return compare(subject.header.size, op, n)
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? = parseSize(value)?.let { SqlPart("m.size ${sqlOp(op)} ?", listOf(it)) }
    }

    val larger = object : MailFacet("larger", FacetValueKind.NUMBER, setOf("bigger")) {
        override fun isBacked(value: String) = parseSize(value) != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) = parseSize(value)?.let { subject.header.size > it } ?: false
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? = parseSize(value)?.let { SqlPart("m.size > ?", listOf(it)) }
    }

    val smaller = object : MailFacet("smaller", FacetValueKind.NUMBER) {
        override fun isBacked(value: String) = parseSize(value) != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) = parseSize(value)?.let { subject.header.size < it } ?: false
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? = parseSize(value)?.let { SqlPart("m.size < ?", listOf(it)) }
    }

    val attachments = object : MailFacet("attachments", FacetValueKind.NUMBER, setOf("files")) {
        override fun isBacked(value: String) = value.toLongOrNull() != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) =
            value.toLongOrNull()?.let { compare(subject.header.attachmentCount.toLong(), op, it) } ?: false
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? = value.toLongOrNull()?.let { SqlPart("m.attachment_count ${sqlOp(op)} ?", listOf(it)) }
    }

    val recipients = object : MailFacet("recipients", FacetValueKind.NUMBER, setOf("rcpts")) {
        override fun isBacked(value: String) = value.toLongOrNull() != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) =
            value.toLongOrNull()?.let { compare((subject.header.to.size + subject.header.cc.size).toLong(), op, it) } ?: false
    }

    val filename = object : MailFacet("filename", FacetValueKind.TEXT, setOf("file", "attachment")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean =
            cmpText(op, subject.header.parts.any { it.isAttachment && it.fileName?.lowercase()?.contains(value.lowercase()) == true })
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart =
            SqlPart("LOWER(m.parts) LIKE ? ESCAPE '\\'", listOf("%\"filename\":\"%" + value.lowercase().replace("%", "\\%") + "%"), exact = false)
                .let { if (op == Op.NE) SqlPart("NOT COALESCE(${it.sql}, 0)", it.args, false) else it }
    }

    val ext = object : MailFacet("ext", FacetValueKind.TEXT, setOf("extension")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext): Boolean {
            val e = "." + value.lowercase().removePrefix(".")
            return cmpText(op, subject.header.parts.any { it.isAttachment && it.fileName?.lowercase()?.endsWith(e) == true })
        }
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart =
            SqlPart("LOWER(m.parts) LIKE ?", listOf("%." + value.lowercase().removePrefix(".") + "\"%"), exact = false)
    }

    val lang = object : MailFacet("lang", FacetValueKind.TEXT, setOf("language")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) = cmpText(op, subject.header.language.equals(value, true))
        override fun sql(op: Op, value: String, ctx: EvalContext) = SqlPart(if (op == Op.NE) "COALESCE(m.language, '') != ?" else "m.language = ?", listOf(value.lowercase()))
    }

    val thread = object : MailFacet("thread", FacetValueKind.TEXT, setOf("conversation")) {
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) = cmpText(op, subject.header.threadKey == value)
        override fun sql(op: Op, value: String, ctx: EvalContext) = SqlPart(if (op == Op.NE) "m.thread_key != ?" else "m.thread_key = ?", listOf(value))
    }

    /** Exact epoch-millis bound on arrival — `ts:>=1793000000000`. Used by the digest's "since you last looked". */
    val ts = object : MailFacet("ts", FacetValueKind.NUMBER, setOf("received_at")) {
        override fun isBacked(value: String) = value.toLongOrNull() != null
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) =
            value.toLongOrNull()?.let { compare(subject.header.receivedAt, op, it) } ?: false
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart? = value.toLongOrNull()?.let { SqlPart("m.received_at ${sqlOp(op)} ?", listOf(it)) }
    }

    val priority = object : MailFacet("priority", FacetValueKind.ENUM, setOf("prio")) {
        override fun isBacked(value: String) = value.lowercase() in setOf("high", "medium", "normal", "low")
        private fun band(value: String): ClosedFloatingPointRange<Float> = when (value.lowercase()) {
            "high" -> 0.6f..1f
            "low" -> 0f..0.299f
            else -> 0.3f..0.599f
        }
        override fun matches(subject: MailItem, op: Op, value: String, ctx: EvalContext) = cmpText(op, subject.header.priority in band(value))
        override fun sql(op: Op, value: String, ctx: EvalContext): SqlPart {
            val b = band(value)
            return SqlPart(if (op == Op.NE) "(m.priority < ? OR m.priority > ?)" else "m.priority BETWEEN ? AND ?", listOf(b.start, b.endInclusive))
        }
    }

    val all: List<MailFacet> = listOf(
        from, to, cc, bcc, domain, subject, body, list, isField, has, inField, label, category, account, date, after, before,
        olderThan, newerThan, size, larger, smaller, attachments, recipients, filename, ext, lang, thread, priority, ts,
    )

    val registry: FieldRegistry<MailItem> = FieldRegistry(all)

    fun facet(key: String): MailFacet? = registry[key] as? MailFacet

    companion object {
        fun parseSize(raw: String): Long? {
            val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*(b|k|kb|m|mb|g|gb)?$", RegexOption.IGNORE_CASE).matchEntire(raw.trim()) ?: return null
            val n = m.groupValues[1].toDouble()
            val mult = when (m.groupValues[2].lowercase()) {
                "k", "kb" -> 1024.0; "m", "mb" -> 1024.0 * 1024; "g", "gb" -> 1024.0 * 1024 * 1024; else -> 1.0
            }
            return (n * mult).toLong()
        }

        fun parseAge(raw: String): Long? {
            val m = Regex("^(\\d+)\\s*(h|d|w|m|y)$", RegexOption.IGNORE_CASE).matchEntire(raw.trim()) ?: return null
            val n = m.groupValues[1].toLong()
            return n * when (m.groupValues[2].lowercase()) {
                "h" -> 3_600_000L; "d" -> 86_400_000L; "w" -> 7 * 86_400_000L; "m" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
            }
        }

        fun compare(actual: Long, op: Op, n: Long) = when (op) {
            Op.EQ -> actual == n; Op.NE -> actual != n; Op.GT -> actual > n; Op.GTE -> actual >= n; Op.LT -> actual < n; Op.LTE -> actual <= n
        }

        fun sqlOp(op: Op) = when (op) { Op.EQ -> "="; Op.NE -> "!="; Op.GT -> ">"; Op.GTE -> ">="; Op.LT -> "<"; Op.LTE -> "<=" }

        fun ftsQuote(text: String) = "\"" + text.replace("\"", "\"\"") + "\""
    }
}
