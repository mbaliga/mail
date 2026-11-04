package dev.aarso.mail.store

import androidx.sqlite.SQLiteDriver
import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.BodyState
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.Contact
import dev.aarso.mail.model.Draft
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.PartInfo
import dev.aarso.mail.model.PendingOp
import dev.aarso.mail.model.SavedSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Label lists are stored unit-separator delimited so membership is a single indexed-friendly LIKE. */
internal object Labels {
    const val SEP = '\u001F'
    fun encode(labels: List<String>): String = if (labels.isEmpty()) "" else labels.joinToString(SEP.toString(), SEP.toString(), SEP.toString())
    fun decode(s: String?): List<String> = s?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()
    fun likePattern(label: String): String = "%$SEP$label$SEP%"
}

data class QueuedOp(val id: Long, val accountId: Long, val op: PendingOp, val attempts: Int, val lastError: String?)

data class OutboxItem(val id: Long, val draft: Draft, val state: String, val sendAt: Long, val attempts: Int, val lastError: String?)

data class Reminder(val id: Long, val messageId: Long, val kind: String, val dueAt: Long, val note: String?)

/**
 * The device's copy of every account's mail, and everything derived from it.
 *
 * Writes name their tables so [observe]-based flows refresh exactly the screens that could change.
 * Bulk paths (sync inserting 500 headers, a rule archiving 10,000 messages) run as one transaction.
 */
class MailStore(driver: SQLiteDriver, path: String) : AutoCloseable {

    val db = Db(driver, path)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val addrList = ListSerializer(EmailAddress.serializer())
    private val partList = ListSerializer(PartInfo.serializer())
    private val strList = ListSerializer(String.serializer())

    override fun close() = db.close()

    // ------------------------------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------------------------------

    suspend fun accounts(): List<Account> = db.read { readAccounts() }

    fun observeAccounts(): Flow<List<Account>> = db.observe(setOf("account")) { accounts() }

    suspend fun account(id: Long): Account? = db.read {
        queryOne("SELECT id, json FROM account WHERE id = ?", id) { decodeAccount(it) }
    }

    private fun Conn.readAccounts(): List<Account> =
        query("SELECT id, json FROM account ORDER BY sort_order, id") { decodeAccount(it) }

    private fun decodeAccount(r: Row): Account = json.decodeFromString(Account.serializer(), r.text(1)).copy(id = r.long(0))

    suspend fun saveAccount(a: Account): Account = db.write(setOf("account")) {
        if (a.id == 0L) {
            val id = insert("INSERT INTO account(json, sort_order) VALUES (?, ?)", json.encodeToString(Account.serializer(), a), a.sortOrder)
            a.copy(id = id).also { exec("UPDATE account SET json = ? WHERE id = ?", json.encodeToString(Account.serializer(), it), id) }
        } else {
            exec("UPDATE account SET json = ?, sort_order = ? WHERE id = ?", json.encodeToString(Account.serializer(), a), a.sortOrder, a.id)
            a
        }
    }

    suspend fun deleteAccount(id: Long) = db.write(Schema.ALL_TABLES) {
        exec("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM message WHERE account_id = ?)", id)
        exec("DELETE FROM pending_op WHERE account_id = ?", id)
        exec("DELETE FROM outbox WHERE account_id = ?", id)
        exec("DELETE FROM draft WHERE account_id = ?", id)
        exec("DELETE FROM account WHERE id = ?", id)
    }

    // ------------------------------------------------------------------------------------------
    // Mailboxes
    // ------------------------------------------------------------------------------------------

    private val mailboxColumns = "id, account_id, remote_id, name, delimiter, role, selectable, subscribed, sync_enabled, " +
        "uid_validity, uid_next, highest_modseq, sync_state, total, unread, last_sync_at, sort_order"

    private fun mapMailbox(r: Row) = Mailbox(
        id = r.long(0), accountId = r.long(1), remoteId = r.text(2), name = r.text(3), delimiter = r.textOrNull(4)?.firstOrNull(),
        role = runCatching { MailboxRole.valueOf(r.text(5)) }.getOrDefault(MailboxRole.NONE),
        selectable = r.bool(6), subscribed = r.bool(7), syncEnabled = r.bool(8),
        uidValidity = r.long(9), uidNext = r.long(10), highestModSeq = r.long(11), syncState = r.textOrNull(12),
        totalCount = r.int(13), unreadCount = r.int(14), lastSyncAt = r.long(15), sortOrder = r.int(16),
    )

    suspend fun mailboxes(accountId: Long? = null): List<Mailbox> = db.read {
        if (accountId == null) query("SELECT $mailboxColumns FROM mailbox ORDER BY account_id, sort_order, name", map = ::mapMailbox)
        else query("SELECT $mailboxColumns FROM mailbox WHERE account_id = ? ORDER BY sort_order, name", accountId, map = ::mapMailbox)
    }

    fun observeMailboxes(): Flow<List<Mailbox>> = db.observe(setOf("mailbox")) { mailboxes() }

    suspend fun mailbox(id: Long): Mailbox? = db.read { queryOne("SELECT $mailboxColumns FROM mailbox WHERE id = ?", id, map = ::mapMailbox) }

    suspend fun mailboxByRole(accountId: Long, role: MailboxRole): Mailbox? = db.read {
        queryOne("SELECT $mailboxColumns FROM mailbox WHERE account_id = ? AND role = ? ORDER BY id LIMIT 1", accountId, role.name, map = ::mapMailbox)
    }

    suspend fun mailboxByRemote(accountId: Long, remoteId: String): Mailbox? = db.read {
        queryOne("SELECT $mailboxColumns FROM mailbox WHERE account_id = ? AND remote_id = ?", accountId, remoteId, map = ::mapMailbox)
    }

    /** Insert or update by (account, remote id), preserving sync cursors the caller did not supply. */
    suspend fun upsertMailboxes(accountId: Long, boxes: List<Mailbox>, removeOthers: Boolean): List<Mailbox> = db.write(setOf("mailbox", "message")) {
        val keep = HashSet<String>()
        for (m in boxes) {
            keep += m.remoteId
            exec(
                """INSERT INTO mailbox(account_id, remote_id, name, delimiter, role, selectable, subscribed, sync_enabled, sort_order, total, unread)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(account_id, remote_id) DO UPDATE SET name = excluded.name, delimiter = excluded.delimiter,
                     role = excluded.role, selectable = excluded.selectable, subscribed = excluded.subscribed,
                     sort_order = excluded.sort_order""",
                accountId, m.remoteId, m.name, m.delimiter?.toString(), m.role.name, m.selectable, m.subscribed, m.syncEnabled,
                m.sortOrder, m.totalCount, m.unreadCount,
            )
        }
        if (removeOthers) {
            val existing = query("SELECT id, remote_id FROM mailbox WHERE account_id = ?", accountId) { it.long(0) to it.text(1) }
            for ((id, remote) in existing) if (remote !in keep) {
                exec("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM message WHERE mailbox_id = ?)", id)
                exec("DELETE FROM mailbox WHERE id = ?", id)
            }
        }
        query("SELECT $mailboxColumns FROM mailbox WHERE account_id = ? ORDER BY sort_order, name", accountId, map = ::mapMailbox)
    }

    suspend fun updateMailboxCursor(id: Long, uidValidity: Long, uidNext: Long, highestModSeq: Long, syncState: String?, at: Long) =
        db.write(setOf("mailbox")) {
            exec(
                "UPDATE mailbox SET uid_validity = ?, uid_next = ?, highest_modseq = ?, sync_state = ?, last_sync_at = ? WHERE id = ?",
                uidValidity, uidNext, highestModSeq, syncState, at, id,
            )
        }

    suspend fun setMailboxSyncEnabled(id: Long, enabled: Boolean) = db.write(setOf("mailbox")) {
        exec("UPDATE mailbox SET sync_enabled = ? WHERE id = ?", enabled, id)
    }

    /** Wipe a mailbox's local messages — UIDVALIDITY changed, so every UID we hold is meaningless. */
    suspend fun resetMailbox(id: Long) = db.write(setOf("mailbox", "message")) {
        exec("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM message WHERE mailbox_id = ?)", id)
        exec("DELETE FROM message WHERE mailbox_id = ?", id)
        exec("UPDATE mailbox SET uid_validity = 0, uid_next = 0, highest_modseq = 0, sync_state = NULL WHERE id = ?", id)
    }

    /** Recount totals from the message table (after a sync pass), so Places shows true numbers. */
    suspend fun recountMailboxes(accountId: Long) = db.write(setOf("mailbox")) {
        exec(
            """UPDATE mailbox SET
                 total = (SELECT COUNT(*) FROM message m WHERE m.mailbox_id = mailbox.id AND m.hidden = 0),
                 unread = (SELECT COUNT(*) FROM message m WHERE m.mailbox_id = mailbox.id AND m.hidden = 0 AND (m.flags & 1) = 0)
               WHERE account_id = ?""",
            accountId,
        )
    }

    // ------------------------------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------------------------------

    internal val messageColumns = "m.id, m.account_id, m.mailbox_id, m.uid, m.remote_id, m.provider_msg_id, m.message_id, m.in_reply_to, " +
        "m.refs, m.thread_key, m.subject, m.from_addr, m.from_name, m.sender_addr, m.reply_to, m.to_list, m.cc_list, m.bcc_list, " +
        "m.sent_at, m.received_at, m.size, m.flags, m.labels, m.preview, m.attachment_count, m.has_calendar, m.list_id, " +
        "m.list_unsubscribe, m.list_unsubscribe_post, m.is_bulk, m.is_automated, m.parts, m.body_state, m.category, m.category_conf, " +
        "m.category_src, m.tldr, m.tldr_src, m.otp_code, m.language, m.priority, m.snoozed_until, m.pinned, m.muted, m.tracker_count, " +
        "m.auth_results, m.hints"

    internal fun mapHeader(r: Row): MessageHeader = MessageHeader(
        id = r.long(0), accountId = r.long(1), mailboxId = r.long(2), uid = r.long(3), remoteId = r.textOrNull(4),
        providerMessageId = r.textOrNull(5), messageId = r.textOrNull(6), inReplyTo = r.textOrNull(7),
        references = r.textOrNull(8)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
        threadKey = r.text(9), subject = r.text(10),
        from = r.textOrNull(11)?.let { EmailAddress(it, r.textOrNull(12)) },
        sender = r.textOrNull(13)?.let { EmailAddress(it) },
        replyTo = addresses(r.textOrNull(14)), to = addresses(r.textOrNull(15)), cc = addresses(r.textOrNull(16)), bcc = addresses(r.textOrNull(17)),
        sentAt = r.long(18), receivedAt = r.long(19), size = r.long(20), flags = r.int(21), labels = Labels.decode(r.textOrNull(22)),
        preview = r.text(23), attachmentCount = r.int(24), hasCalendar = r.bool(25), listId = r.textOrNull(26),
        listUnsubscribe = r.textOrNull(27), listUnsubscribePost = r.bool(28), isBulk = r.bool(29), isAutomated = r.bool(30),
        parts = r.textOrNull(31)?.let { runCatching { json.decodeFromString(partList, it) }.getOrNull() }.orEmpty(),
        bodyState = BodyState.entries.getOrElse(r.int(32)) { BodyState.NONE },
        category = Category.fromKey(r.text(33)) ?: Category.OTHER, categoryConfidence = r.double(34).toFloat(),
        categorySource = ClassificationSource.entries.getOrElse(r.int(35)) { ClassificationSource.NONE },
        tldr = r.textOrNull(36), tldrSource = ClassificationSource.entries.getOrElse(r.int(37)) { ClassificationSource.NONE },
        otpCode = r.textOrNull(38), language = r.textOrNull(39), priority = r.double(40).toFloat(), snoozedUntil = r.long(41),
        pinned = r.bool(42), muted = r.bool(43), trackerCount = r.int(44), authResults = r.textOrNull(45),
        importanceHint = r.textOrNull(46)?.substringAfter('|', "")?.ifEmpty { null },
        serviceHints = r.textOrNull(46)?.substringBefore('|')?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
    )

    private fun hints(h: MessageHeader): String? =
        if (h.serviceHints.isEmpty() && h.importanceHint == null) null else h.serviceHints.joinToString(",") + "|" + (h.importanceHint ?: "")

    private fun addresses(s: String?): List<EmailAddress> =
        if (s.isNullOrEmpty()) emptyList() else runCatching { json.decodeFromString(addrList, s) }.getOrDefault(emptyList())

    private fun enc(list: List<EmailAddress>): String? = if (list.isEmpty()) null else json.encodeToString(addrList, list)

    suspend fun header(id: Long): MessageHeader? = db.read {
        queryOne("SELECT $messageColumns FROM message m WHERE m.id = ?", id, map = ::mapHeader)
    }

    suspend fun headers(ids: Collection<Long>): List<MessageHeader> = if (ids.isEmpty()) emptyList() else db.read {
        ids.chunked(500).flatMap { chunk ->
            query("SELECT $messageColumns FROM message m WHERE m.id IN (${chunk.joinToString(",")})", map = ::mapHeader)
        }
    }

    /** Arbitrary filtered listing, used by the search engine's SQL pushdown. [where] uses `m.` and `mb.` aliases. */
    suspend fun queryHeaders(where: String, args: List<Any?>, orderBy: String = "m.received_at DESC", limit: Int = 200, offset: Int = 0): List<MessageHeader> =
        db.read {
            query(
                "SELECT $messageColumns FROM message m JOIN mailbox mb ON mb.id = m.mailbox_id WHERE $where ORDER BY $orderBy LIMIT ? OFFSET ?",
                *(args + listOf(limit, offset)).toTypedArray(), map = ::mapHeader,
            )
        }

    suspend fun countWhere(where: String, args: List<Any?>): Int = db.read {
        scalarLong("SELECT COUNT(*) FROM message m JOIN mailbox mb ON mb.id = m.mailbox_id WHERE $where", *args.toTypedArray())?.toInt() ?: 0
    }

    /** Stream headers matching [where] in [orderBy] order; [each] returns false to stop. For select-all and bulk. */
    suspend fun forEachHeader(where: String, args: List<Any?>, orderBy: String = "m.received_at DESC", each: (MessageHeader) -> Boolean) = db.read {
        forEach("SELECT $messageColumns FROM message m JOIN mailbox mb ON mb.id = m.mailbox_id WHERE $where ORDER BY $orderBy", *args.toTypedArray()) {
            each(mapHeader(it))
        }
    }

    suspend fun ftsIds(match: String, limit: Int = 50_000): Set<Long> = db.read {
        query("SELECT rowid FROM message_fts WHERE message_fts MATCH ? LIMIT ?", match, limit) { it.long(0) }.toHashSet()
    }

    /** Full searchable text for one message (FTS content), for exact in-memory verification. */
    suspend fun searchText(ids: Collection<Long>): Map<Long, Map<String, String>> = if (ids.isEmpty()) emptyMap() else db.read {
        ids.chunked(500).flatMap { chunk ->
            query("SELECT rowid, subject, sender, recipients, body, attachments, labels FROM message_fts WHERE rowid IN (${chunk.joinToString(",")})") { r ->
                r.long(0) to mapOf(
                    "subject" to r.text(1), "sender" to r.text(2), "recipients" to r.text(3), "body" to r.text(4),
                    "attachments" to r.text(5), "labels" to r.text(6),
                )
            }
        }.toMap()
    }

    suspend fun threadMessages(threadKey: String, accountId: Long? = null): List<MessageHeader> = db.read {
        if (accountId == null) query("SELECT $messageColumns FROM message m WHERE m.thread_key = ? AND m.hidden = 0 ORDER BY m.received_at", threadKey, map = ::mapHeader)
        else query("SELECT $messageColumns FROM message m WHERE m.thread_key = ? AND m.account_id = ? AND m.hidden = 0 ORDER BY m.received_at", threadKey, accountId, map = ::mapHeader)
    }

    /** Thread key of the message with this Message-ID, for threading a newcomer onto its parent. */
    suspend fun threadKeyFor(accountId: Long, messageIds: List<String>): String? = if (messageIds.isEmpty()) null else db.read {
        messageIds.firstNotNullOfOrNull { mid -> scalarText("SELECT thread_key FROM message WHERE account_id = ? AND message_id = ? LIMIT 1", accountId, mid) }
    }

    /** Whether any of [messageIds] is a message I sent — the core of the REPLIES signal. */
    suspend fun anySentByMe(accountId: Long, messageIds: List<String>, myAddresses: Set<String>): Boolean =
        if (messageIds.isEmpty() || myAddresses.isEmpty()) false else db.read {
            val ids = messageIds.take(50)
            val me = myAddresses.take(20)
            scalarLong(
                "SELECT 1 FROM message WHERE account_id = ? AND message_id IN (${ids.joinToString(",") { "?" }}) AND LOWER(from_addr) IN (${me.joinToString(",") { "?" }}) LIMIT 1",
                *(listOf<Any?>(accountId) + ids + me).toTypedArray(),
            ) != null
        }

    suspend fun uids(mailboxId: Long): Map<Long, Long> = db.read {
        query("SELECT uid, id FROM message WHERE mailbox_id = ? AND uid > 0", mailboxId) { it.long(0) to it.long(1) }.toMap()
    }

    suspend fun remoteIds(accountId: Long): Map<String, Long> = db.read {
        query("SELECT remote_id, id FROM message WHERE account_id = ? AND remote_id IS NOT NULL", accountId) { it.text(0) to it.long(1) }.toMap()
    }

    suspend fun maxUid(mailboxId: Long): Long = db.read { scalarLong("SELECT MAX(uid) FROM message WHERE mailbox_id = ?", mailboxId) ?: 0 }
    suspend fun minUid(mailboxId: Long): Long = db.read { scalarLong("SELECT MIN(uid) FROM message WHERE mailbox_id = ? AND uid > 0", mailboxId) ?: 0 }

    /**
     * Insert new messages, or adopt a placeholder row (a message moved here locally whose UID was not
     * yet known) by Message-ID, so an optimistic move never shows up twice once the server catches up.
     * Returns the stored rows with ids.
     */
    suspend fun insertMessages(headers: List<MessageHeader>): List<MessageHeader> = if (headers.isEmpty()) emptyList() else db.write(setOf("message", "message_fts", "mailbox")) {
        headers.map { h ->
            val placeholder = h.messageId?.let { mid ->
                scalarLong("SELECT id FROM message WHERE mailbox_id = ? AND uid = 0 AND remote_id IS NULL AND message_id = ? LIMIT 1", h.mailboxId, mid)
            }
            val byUid = if (h.uid > 0) scalarLong("SELECT id FROM message WHERE mailbox_id = ? AND uid = ?", h.mailboxId, h.uid) else null
            val byRemote = h.remoteId?.let { scalarLong("SELECT id FROM message WHERE account_id = ? AND remote_id = ?", h.accountId, it) }
            val existing = placeholder ?: byUid ?: byRemote
            if (existing != null) {
                exec(
                    "UPDATE message SET uid = ?, remote_id = COALESCE(?, remote_id), flags = ?, labels = ?, mailbox_id = ?, hidden = 0 WHERE id = ?",
                    h.uid, h.remoteId, h.flags, Labels.encode(h.labels), h.mailboxId, existing,
                )
                h.copy(id = existing)
            } else {
                val id = insert(
                    """INSERT INTO message(account_id, mailbox_id, uid, remote_id, provider_msg_id, message_id, in_reply_to, refs, thread_key,
                         subject, from_addr, from_name, sender_addr, reply_to, to_list, cc_list, bcc_list, sent_at, received_at, size, flags,
                         labels, preview, attachment_count, has_calendar, list_id, list_unsubscribe, list_unsubscribe_post, is_bulk,
                         is_automated, parts, body_state, category, category_conf, category_src, tldr, tldr_src, otp_code, language,
                         priority, tracker_count, auth_results, hints)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    h.accountId, h.mailboxId, h.uid, h.remoteId, h.providerMessageId, h.messageId, h.inReplyTo,
                    h.references.joinToString(" ").ifEmpty { null }, h.threadKey, h.subject, h.from?.address, h.from?.name,
                    h.sender?.address, enc(h.replyTo), enc(h.to), enc(h.cc), enc(h.bcc), h.sentAt, h.receivedAt, h.size, h.flags,
                    Labels.encode(h.labels), h.preview, h.attachmentCount, h.hasCalendar, h.listId, h.listUnsubscribe,
                    h.listUnsubscribePost, h.isBulk, h.isAutomated, if (h.parts.isEmpty()) null else json.encodeToString(partList, h.parts),
                    h.bodyState.ordinal, h.category.key, h.categoryConfidence, h.categorySource.ordinal, h.tldr, h.tldrSource.ordinal,
                    h.otpCode, h.language, h.priority, h.trackerCount, h.authResults, hints(h),
                )
                indexFts(id, h, h.preview)
                h.copy(id = id)
            }
        }
    }

    private fun Conn.indexFts(id: Long, h: MessageHeader, body: String) {
        exec("DELETE FROM message_fts WHERE rowid = ?", id)
        exec(
            "INSERT INTO message_fts(rowid, subject, sender, recipients, body, attachments, labels) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, h.subject, listOfNotNull(h.from?.name, h.from?.address, h.sender?.address).joinToString(" "),
            (h.to + h.cc + h.bcc).joinToString(" ") { listOfNotNull(it.name, it.address).joinToString(" ") },
            body, h.parts.filter { it.isAttachment }.mapNotNull { it.fileName }.joinToString(" "),
            h.labels.filterNot { it.startsWith("\\") }.joinToString(" "),
        )
    }

    /** Server-reported flag/label state for existing rows (the cheap half of a resync). */
    suspend fun updateServerState(mailboxId: Long, updates: List<Triple<Long, Int, List<String>?>>) = if (updates.isEmpty()) Unit else db.write(setOf("message", "mailbox")) {
        for ((uid, flags, labels) in updates) {
            if (labels == null) exec("UPDATE message SET flags = ? WHERE mailbox_id = ? AND uid = ?", flags, mailboxId, uid)
            else exec("UPDATE message SET flags = ?, labels = ? WHERE mailbox_id = ? AND uid = ?", flags, Labels.encode(labels), mailboxId, uid)
        }
    }

    suspend fun updateRemoteState(accountId: Long, updates: List<Triple<String, Int, Long?>>) = if (updates.isEmpty()) Unit else db.write(setOf("message", "mailbox")) {
        for ((remoteId, flags, mailboxId) in updates) {
            if (mailboxId == null) exec("UPDATE message SET flags = ? WHERE account_id = ? AND remote_id = ?", flags, accountId, remoteId)
            else exec("UPDATE message SET flags = ?, mailbox_id = ? WHERE account_id = ? AND remote_id = ?", flags, mailboxId, accountId, remoteId)
        }
    }

    suspend fun deleteByUids(mailboxId: Long, uids: Collection<Long>) = if (uids.isEmpty()) Unit else db.write(setOf("message", "message_fts", "mailbox")) {
        for (chunk in uids.chunked(500)) {
            val list = chunk.joinToString(",")
            exec("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM message WHERE mailbox_id = ? AND uid IN ($list))", mailboxId)
            exec("DELETE FROM message WHERE mailbox_id = ? AND uid IN ($list)", mailboxId)
        }
    }

    suspend fun deleteByRemoteIds(accountId: Long, remoteIds: Collection<String>) = if (remoteIds.isEmpty()) Unit else db.write(setOf("message", "message_fts", "mailbox")) {
        for (r in remoteIds) {
            exec("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM message WHERE account_id = ? AND remote_id = ?)", accountId, r)
            exec("DELETE FROM message WHERE account_id = ? AND remote_id = ?", accountId, r)
        }
    }

    suspend fun deleteMessages(ids: Collection<Long>) = if (ids.isEmpty()) Unit else db.write(setOf("message", "message_fts", "mailbox", "body")) {
        for (chunk in ids.chunked(500)) {
            val list = chunk.joinToString(",")
            exec("DELETE FROM message_fts WHERE rowid IN ($list)")
            exec("DELETE FROM message WHERE id IN ($list)")
        }
    }

    // ---- optimistic local mutations (each mirrored by a PendingOp) ----

    suspend fun applyFlags(ids: Collection<Long>, add: Int, remove: Int) = if (ids.isEmpty()) Unit else db.write(setOf("message", "mailbox")) {
        for (chunk in ids.chunked(500)) exec("UPDATE message SET flags = (flags | ?) & ~? WHERE id IN (${chunk.joinToString(",")})", add, remove)
    }

    suspend fun applyLabels(ids: Collection<Long>, add: List<String>, remove: List<String>) = if (ids.isEmpty()) Unit else db.write(setOf("message", "message_fts", "mailbox")) {
        for (h in ids.chunked(500).flatMap { chunk -> query("SELECT id, labels FROM message WHERE id IN (${chunk.joinToString(",")})") { it.long(0) to Labels.decode(it.textOrNull(1)) } }) {
            val next = (h.second - remove.toSet() + add).distinct()
            exec("UPDATE message SET labels = ? WHERE id = ?", Labels.encode(next), h.first)
        }
    }

    /** Move locally: the row changes mailbox at once; its UID there is unknown until the server answers. */
    suspend fun applyMove(ids: Collection<Long>, toMailboxId: Long) = if (ids.isEmpty()) Unit else db.write(setOf("message", "mailbox")) {
        for (chunk in ids.chunked(500)) {
            exec("UPDATE message SET mailbox_id = ?, uid = 0 WHERE id IN (${chunk.joinToString(",")}) AND remote_id IS NULL", toMailboxId)
            exec("UPDATE message SET mailbox_id = ? WHERE id IN (${chunk.joinToString(",")}) AND remote_id IS NOT NULL", toMailboxId)
        }
    }

    /** Record the server's COPYUID answer so a moved row gets its real UID back. */
    suspend fun assignUids(pairs: List<Pair<Long, Long>>) = if (pairs.isEmpty()) Unit else db.write(setOf("message")) {
        for ((id, uid) in pairs) exec("UPDATE OR IGNORE message SET uid = ? WHERE id = ?", uid, id)
    }

    suspend fun setHidden(ids: Collection<Long>, hidden: Boolean) = if (ids.isEmpty()) Unit else db.write(setOf("message", "mailbox")) {
        for (chunk in ids.chunked(500)) exec("UPDATE message SET hidden = ? WHERE id IN (${chunk.joinToString(",")})", hidden)
    }

    suspend fun setSnooze(ids: Collection<Long>, until: Long) = if (ids.isEmpty()) Unit else db.write(setOf("message", "reminder")) {
        for (chunk in ids.chunked(500)) exec("UPDATE message SET snoozed_until = ? WHERE id IN (${chunk.joinToString(",")})", until)
        if (until > 0) for (id in ids) exec("INSERT INTO reminder(message_id, kind, due_at) VALUES (?, 'snooze', ?)", id, until)
    }

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean) = if (ids.isEmpty()) Unit else db.write(setOf("message")) {
        for (chunk in ids.chunked(500)) exec("UPDATE message SET pinned = ? WHERE id IN (${chunk.joinToString(",")})", pinned)
    }

    suspend fun setMuted(threadKeys: Collection<String>, muted: Boolean) = if (threadKeys.isEmpty()) Unit else db.write(setOf("message")) {
        for (k in threadKeys) exec("UPDATE message SET muted = ? WHERE thread_key = ?", muted, k)
    }

    suspend fun setCategory(ids: Collection<Long>, category: Category, source: ClassificationSource, confidence: Float = 1f) =
        if (ids.isEmpty()) Unit else db.write(setOf("message")) {
            for (chunk in ids.chunked(500)) exec(
                "UPDATE message SET category = ?, category_src = ?, category_conf = ? WHERE id IN (${chunk.joinToString(",")})",
                category.key, source.ordinal, confidence,
            )
        }

    /** Derived facts, written by the intelligence pipeline after a message (or its body) arrives. */
    suspend fun updateDerived(
        id: Long,
        category: Category? = null,
        categoryConfidence: Float? = null,
        categorySource: ClassificationSource? = null,
        tldr: String? = null,
        tldrSource: ClassificationSource? = null,
        otp: String? = null,
        language: String? = null,
        priority: Float? = null,
        trackerCount: Int? = null,
    ) = db.write(setOf("message")) {
        val sets = ArrayList<String>(); val args = ArrayList<Any?>()
        category?.let { sets += "category = ?"; args += it.key }
        categoryConfidence?.let { sets += "category_conf = ?"; args += it }
        categorySource?.let { sets += "category_src = ?"; args += it.ordinal }
        tldr?.let { sets += "tldr = ?"; args += it }
        tldrSource?.let { sets += "tldr_src = ?"; args += it.ordinal }
        otp?.let { sets += "otp_code = ?"; args += it.ifEmpty { null } }
        language?.let { sets += "language = ?"; args += it }
        priority?.let { sets += "priority = ?"; args += it }
        trackerCount?.let { sets += "tracker_count = ?"; args += it }
        if (sets.isNotEmpty()) exec("UPDATE message SET ${sets.joinToString(", ")} WHERE id = ?", *(args + id).toTypedArray())
    }

    // ------------------------------------------------------------------------------------------
    // Bodies
    // ------------------------------------------------------------------------------------------

    suspend fun saveBody(body: MessageBody, header: MessageHeader) = db.write(setOf("body", "message", "message_fts")) {
        exec(
            "INSERT OR REPLACE INTO body(message_id, text, html, attachments, structured, fetched_at) VALUES (?, ?, ?, ?, ?, ?)",
            body.messageId, body.text, body.html, json.encodeToString(partList, body.attachments),
            json.encodeToString(strList, body.structuredData), body.fetchedAt,
        )
        val attachments = body.attachments
        val newText = body.text?.let { HtmlText.newContent(it) }.orEmpty()
        val preview = if (header.preview.isBlank() && newText.isNotBlank()) HtmlText.preview(newText) else header.preview
        exec(
            "UPDATE message SET body_state = ?, attachment_count = ?, preview = ?, parts = COALESCE(parts, ?) WHERE id = ?",
            BodyState.FULL.ordinal, maxOf(header.attachmentCount, attachments.size), preview,
            if (attachments.isEmpty()) null else json.encodeToString(partList, attachments), body.messageId,
        )
        indexFts(body.messageId, header.copy(parts = header.parts.ifEmpty { attachments }), newText.take(64_000))
    }

    suspend fun body(messageId: Long): MessageBody? = db.read {
        queryOne("SELECT message_id, text, html, attachments, structured, fetched_at FROM body WHERE message_id = ?", messageId) { r ->
            MessageBody(
                messageId = r.long(0), text = r.textOrNull(1), html = r.textOrNull(2),
                attachments = r.textOrNull(3)?.let { runCatching { json.decodeFromString(partList, it) }.getOrNull() }.orEmpty(),
                structuredData = r.textOrNull(4)?.let { runCatching { json.decodeFromString(strList, it) }.getOrNull() }.orEmpty(),
                fetchedAt = r.long(5),
            )
        }
    }

    /** Messages still missing a body, newest first — the prefetch queue. */
    suspend fun missingBodies(accountId: Long, limit: Int, sinceMillis: Long): List<MessageHeader> = db.read {
        query(
            "SELECT $messageColumns FROM message m WHERE m.account_id = ? AND m.body_state < 2 AND m.received_at >= ? AND m.hidden = 0 ORDER BY m.received_at DESC LIMIT ?",
            accountId, sinceMillis, limit, map = ::mapHeader,
        )
    }

    /** Drop downloaded bodies older than [beforeMillis] to bound storage; headers and FTS previews stay. */
    suspend fun pruneBodies(beforeMillis: Long) = db.write(setOf("body", "message")) {
        exec("UPDATE message SET body_state = 0 WHERE id IN (SELECT b.message_id FROM body b JOIN message m ON m.id = b.message_id WHERE m.received_at < ? AND m.pinned = 0 AND (m.flags & 2) = 0)", beforeMillis)
        exec("DELETE FROM body WHERE message_id IN (SELECT id FROM message WHERE body_state = 0)")
    }

    // ------------------------------------------------------------------------------------------
    // Op queue and outbox
    // ------------------------------------------------------------------------------------------

    suspend fun enqueueOp(accountId: Long, op: PendingOp, now: Long): Long = db.write(setOf("pending_op")) {
        insert("INSERT INTO pending_op(account_id, kind, payload, created_at) VALUES (?, ?, ?, ?)",
            accountId, op::class.simpleName ?: "op", json.encodeToString(PendingOp.serializer(), op), now)
    }

    suspend fun pendingOps(accountId: Long, now: Long, limit: Int = 200): List<QueuedOp> = db.read {
        query("SELECT id, account_id, payload, attempts, last_error FROM pending_op WHERE account_id = ? AND not_before <= ? ORDER BY id LIMIT ?", accountId, now, limit) { r ->
            QueuedOp(r.long(0), r.long(1), json.decodeFromString(PendingOp.serializer(), r.text(2)), r.int(3), r.textOrNull(4))
        }
    }

    suspend fun pendingOpCount(): Int = db.read { scalarLong("SELECT COUNT(*) FROM pending_op")?.toInt() ?: 0 }

    fun observePendingOpCount(): Flow<Int> = db.observe(setOf("pending_op")) { pendingOpCount() }

    suspend fun completeOp(id: Long) = db.write(setOf("pending_op")) { exec("DELETE FROM pending_op WHERE id = ?", id) }

    /** Cancel a queued op if the server has not seen it yet. Returns true when it was still queued. */
    suspend fun cancelOp(id: Long): Boolean = db.write(setOf("pending_op")) {
        exec("DELETE FROM pending_op WHERE id = ?", id)
        changes() > 0
    }

    /** Put rows back exactly as [snapshot] captured them — the local half of undo. */
    suspend fun restore(snapshot: List<MessageHeader>) = if (snapshot.isEmpty()) Unit else db.write(setOf("message", "mailbox")) {
        for (h in snapshot) {
            exec(
                "UPDATE message SET mailbox_id = ?, uid = ?, flags = ?, labels = ?, snoozed_until = ?, pinned = ?, muted = ?, hidden = 0, category = ?, category_src = ? WHERE id = ?",
                h.mailboxId, h.uid, h.flags, Labels.encode(h.labels), h.snoozedUntil, h.pinned, h.muted, h.category.key, h.categorySource.ordinal, h.id,
            )
        }
    }

    suspend fun failOp(id: Long, error: String, notBefore: Long) = db.write(setOf("pending_op")) {
        exec("UPDATE pending_op SET attempts = attempts + 1, last_error = ?, not_before = ? WHERE id = ?", error.take(500), notBefore, id)
    }

    suspend fun enqueueOutgoing(draft: Draft, sendAt: Long, now: Long): Long = db.write(setOf("outbox")) {
        insert("INSERT INTO outbox(account_id, draft, state, send_at, created_at) VALUES (?, ?, 'QUEUED', ?, ?)",
            draft.accountId, json.encodeToString(Draft.serializer(), draft), sendAt, now)
    }

    suspend fun outbox(): List<OutboxItem> = db.read {
        query("SELECT id, draft, state, send_at, attempts, last_error FROM outbox ORDER BY send_at, id") { r ->
            OutboxItem(r.long(0), json.decodeFromString(Draft.serializer(), r.text(1)), r.text(2), r.long(3), r.int(4), r.textOrNull(5))
        }
    }

    fun observeOutbox(): Flow<List<OutboxItem>> = db.observe(setOf("outbox")) { outbox() }

    suspend fun dueOutgoing(now: Long): List<OutboxItem> = outbox().filter { it.state == "QUEUED" && it.sendAt <= now }

    suspend fun setOutboxState(id: Long, state: String, error: String? = null, retryAt: Long? = null) = db.write(setOf("outbox")) {
        if (retryAt != null) exec("UPDATE outbox SET state = ?, last_error = ?, attempts = attempts + 1, send_at = ? WHERE id = ?", state, error, retryAt, id)
        else exec("UPDATE outbox SET state = ?, last_error = ? WHERE id = ?", state, error, id)
    }

    suspend fun removeOutgoing(id: Long) = db.write(setOf("outbox")) { exec("DELETE FROM outbox WHERE id = ?", id) }

    // ------------------------------------------------------------------------------------------
    // Drafts, rules, saved searches
    // ------------------------------------------------------------------------------------------

    suspend fun saveDraft(d: Draft): Draft = db.write(setOf("draft")) {
        val text = json.encodeToString(Draft.serializer(), d)
        if (d.id == 0L) {
            val id = insert("INSERT INTO draft(account_id, json, updated_at) VALUES (?, ?, ?)", d.accountId, text, d.updatedAt)
            d.copy(id = id).also { exec("UPDATE draft SET json = ? WHERE id = ?", json.encodeToString(Draft.serializer(), it), id) }
        } else {
            exec("UPDATE draft SET json = ?, updated_at = ?, account_id = ? WHERE id = ?", text, d.updatedAt, d.accountId, d.id); d
        }
    }

    suspend fun drafts(): List<Draft> = db.read { query("SELECT json FROM draft ORDER BY updated_at DESC") { json.decodeFromString(Draft.serializer(), it.text(0)) } }
    fun observeDrafts(): Flow<List<Draft>> = db.observe(setOf("draft")) { drafts() }
    suspend fun draft(id: Long): Draft? = db.read { queryOne("SELECT json FROM draft WHERE id = ?", id) { json.decodeFromString(Draft.serializer(), it.text(0)) } }
    suspend fun deleteDraft(id: Long) = db.write(setOf("draft")) { exec("DELETE FROM draft WHERE id = ?", id) }

    suspend fun rules(): List<MailRule> = db.read {
        query("SELECT id, json FROM rule ORDER BY sort_order, id") { r -> json.decodeFromString(MailRule.serializer(), r.text(1)).copy(id = r.long(0)) }
    }
    fun observeRules(): Flow<List<MailRule>> = db.observe(setOf("rule")) { rules() }

    suspend fun saveRule(rule: MailRule, sortOrder: Int = 0): MailRule = db.write(setOf("rule")) {
        if (rule.id == 0L) {
            val id = insert("INSERT INTO rule(json, sort_order) VALUES (?, ?)", json.encodeToString(MailRule.serializer(), rule), sortOrder)
            rule.copy(id = id)
        } else {
            exec("UPDATE rule SET json = ? WHERE id = ?", json.encodeToString(MailRule.serializer(), rule), rule.id); rule
        }
    }

    suspend fun deleteRule(id: Long) = db.write(setOf("rule")) { exec("DELETE FROM rule WHERE id = ?", id) }

    suspend fun savedSearches(): List<SavedSearch> = db.read {
        query("SELECT id, json FROM saved_search ORDER BY sort_order, id") { r -> json.decodeFromString(SavedSearch.serializer(), r.text(1)).copy(id = r.long(0)) }
    }
    fun observeSavedSearches(): Flow<List<SavedSearch>> = db.observe(setOf("saved_search")) { savedSearches() }

    suspend fun saveSearch(s: SavedSearch): SavedSearch = db.write(setOf("saved_search")) {
        if (s.id == 0L) s.copy(id = insert("INSERT INTO saved_search(json, sort_order) VALUES (?, ?)", json.encodeToString(SavedSearch.serializer(), s), s.sortOrder))
        else { exec("UPDATE saved_search SET json = ?, sort_order = ? WHERE id = ?", json.encodeToString(SavedSearch.serializer(), s), s.sortOrder, s.id); s }
    }

    suspend fun deleteSavedSearch(id: Long) = db.write(setOf("saved_search")) { exec("DELETE FROM saved_search WHERE id = ?", id) }

    // ------------------------------------------------------------------------------------------
    // Contacts, settings, reminders, caches
    // ------------------------------------------------------------------------------------------

    suspend fun recordContacts(addresses: List<EmailAddress>, sentByMe: Boolean, at: Long) = if (addresses.isEmpty()) Unit else db.write(setOf("contact")) {
        for (a in addresses.distinctBy { it.normalized }) {
            exec(
                """INSERT INTO contact(address, name, sent_count, received_count, last_contact_at) VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT(address) DO UPDATE SET name = COALESCE(excluded.name, contact.name),
                     sent_count = contact.sent_count + excluded.sent_count, received_count = contact.received_count + excluded.received_count,
                     last_contact_at = MAX(contact.last_contact_at, excluded.last_contact_at)""",
                a.normalized, a.name?.takeIf { it.isNotBlank() }, if (sentByMe) 1 else 0, if (sentByMe) 0 else 1, at,
            )
        }
    }

    /** Autocomplete: people I write to first, then by recency. */
    suspend fun suggestContacts(prefix: String, limit: Int = 8): List<Contact> = db.read {
        val p = prefix.trim().lowercase()
        query(
            """SELECT address, name, sent_count, received_count, last_contact_at FROM contact
               WHERE address LIKE ? OR LOWER(name) LIKE ? OR LOWER(name) LIKE ?
               ORDER BY sent_count DESC, last_contact_at DESC LIMIT ?""",
            "$p%", "$p%", "% $p%", limit,
        ) { Contact(it.text(0), it.textOrNull(1), it.int(2), it.int(3), it.long(4)) }
    }

    suspend fun contact(address: String): Contact? = db.read {
        queryOne("SELECT address, name, sent_count, received_count, last_contact_at FROM contact WHERE address = ?", address.lowercase()) {
            Contact(it.text(0), it.textOrNull(1), it.int(2), it.int(3), it.long(4))
        }
    }

    suspend fun setting(key: String): String? = db.read { scalarText("SELECT value FROM setting WHERE key = ?", key) }
    suspend fun putSetting(key: String, value: String?) = db.write(setOf("setting")) {
        if (value == null) exec("DELETE FROM setting WHERE key = ?", key)
        else exec("INSERT OR REPLACE INTO setting(key, value) VALUES (?, ?)", key, value)
    }
    fun observeSetting(key: String): Flow<String?> = db.observe(setOf("setting")) { setting(key) }

    suspend fun addReminder(messageId: Long, kind: String, dueAt: Long, note: String? = null) = db.write(setOf("reminder")) {
        insert("INSERT INTO reminder(message_id, kind, due_at, note) VALUES (?, ?, ?, ?)", messageId, kind, dueAt, note)
    }

    suspend fun dueReminders(now: Long): List<Reminder> = db.read {
        query("SELECT id, message_id, kind, due_at, note FROM reminder WHERE done = 0 AND due_at <= ? ORDER BY due_at", now) {
            Reminder(it.long(0), it.long(1), it.text(2), it.long(3), it.textOrNull(4))
        }
    }

    suspend fun nextReminderAt(): Long? = db.read { scalarLong("SELECT MIN(due_at) FROM reminder WHERE done = 0") }

    suspend fun completeReminder(id: Long) = db.write(setOf("reminder")) { exec("UPDATE reminder SET done = 1 WHERE id = ?", id) }

    suspend fun cacheGet(key: String): String? = db.read { scalarText("SELECT value FROM ai_cache WHERE key = ?", key) }
    suspend fun cachePut(key: String, value: String, source: ClassificationSource, at: Long) = db.write(setOf("ai_cache")) {
        exec("INSERT OR REPLACE INTO ai_cache(key, value, source, created_at) VALUES (?, ?, ?, ?)", key, value, source.ordinal, at)
    }

    suspend fun putEmbedding(messageId: Long, model: String, vector: FloatArray) = db.write(setOf("embedding")) {
        val bytes = java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> vector.forEach { b.putFloat(it) } }.array()
        exec("INSERT OR REPLACE INTO embedding(message_id, model, vector) VALUES (?, ?, ?)", messageId, model, bytes)
    }

    suspend fun embeddings(model: String): Map<Long, FloatArray> = db.read {
        query("SELECT message_id, vector FROM embedding WHERE model = ?", model) { r ->
            val b = java.nio.ByteBuffer.wrap(r.blob(1)!!).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            r.long(0) to FloatArray(b.remaining() / 4) { b.getFloat() }
        }.toMap()
    }

    suspend fun messagesWithoutEmbedding(model: String, limit: Int): List<MessageHeader> = db.read {
        query(
            "SELECT $messageColumns FROM message m WHERE m.hidden = 0 AND NOT EXISTS (SELECT 1 FROM embedding e WHERE e.message_id = m.id AND e.model = ?) ORDER BY m.received_at DESC LIMIT ?",
            model, limit, map = ::mapHeader,
        )
    }

    /** One reactive read over messages, re-run when messages or mailboxes change. */
    fun <T> observeMessages(query: suspend () -> T): Flow<T> = db.observe(setOf("message", "mailbox")) { query() }
}
