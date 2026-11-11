package dev.aarso.mail.sync

import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.OpTarget
import dev.aarso.mail.model.PendingOp
import dev.aarso.mail.store.MailStore

/** What an action did, so it can be undone: the queued ops and the rows as they were before. */
class UndoToken internal constructor(
    val label: String,
    val count: Int,
    internal val opIds: List<Pair<Long, Long>>,
    internal val before: List<MessageHeader>,
)

/**
 * Every change a person (or a rule) makes to mail, in one place.
 *
 * Each action is applied to the local store at once — the list redraws in the same frame — and a
 * [PendingOp] carrying the server's coordinates is queued for the account. [onQueued] is how the
 * app schedules a flush (an expedited WorkManager job). Every action returns an [UndoToken]; undo
 * cancels the queued op if it has not reached the server yet, and queues the inverse if it has.
 *
 * Provider semantics live here so nothing above has to know them: on Gmail "archive" removes the
 * `\Inbox` label and "move to X" is add-label-X-and-archive; on folder servers both are moves; on
 * POP3 only permanent deletion ever reaches the server.
 */
class MailActions(
    private val store: MailStore,
    private val clock: Clock = Clock.SYSTEM,
    var onQueued: (accountIds: Set<Long>) -> Unit = {},
) {

    private suspend fun load(ids: Collection<Long>): List<MessageHeader> = store.headers(ids.distinct())

    private fun target(h: MessageHeader) = OpTarget(h.id, h.mailboxId, h.uid, h.remoteId)

    private suspend fun isGmail(accountId: Long, cache: MutableMap<Long, Boolean>): Boolean = cache.getOrPut(accountId) {
        // Gmail accounts sync "[Gmail]/All Mail" as their \All mailbox; that is the signature.
        store.mailboxByRole(accountId, MailboxRole.ALL)?.let { it.syncEnabled && (it.remoteId.startsWith("[Gmail]") || it.remoteId.startsWith("[Google Mail]")) } == true
    }

    private suspend fun queue(accountId: Long, op: PendingOp): Pair<Long, Long> = accountId to store.enqueueOp(accountId, op, clock.now())

    private fun done(label: String, before: List<MessageHeader>, ops: List<Pair<Long, Long>>): UndoToken {
        onQueued(before.map { it.accountId }.toSet())
        return UndoToken(label, before.size, ops, before)
    }

    // ---- flags ----

    suspend fun markRead(ids: Collection<Long>, read: Boolean = true): UndoToken =
        setFlags(ids, if (read) Flags.SEEN else 0, if (read) 0 else Flags.SEEN, if (read) "Marked read" else "Marked unread")

    suspend fun star(ids: Collection<Long>, starred: Boolean = true): UndoToken =
        setFlags(ids, if (starred) Flags.FLAGGED else 0, if (starred) 0 else Flags.FLAGGED, if (starred) "Starred" else "Unstarred")

    suspend fun setFlags(ids: Collection<Long>, add: Int, remove: Int, label: String = "Updated"): UndoToken {
        val before = load(ids).filter { (it.flags or add) and remove.inv() != it.flags }
        val ops = before.groupBy { it.accountId }.map { (acc, hs) -> queue(acc, PendingOp.SetFlags(hs.map(::target), add, remove)) }
        store.applyFlags(before.map { it.id }, add, remove)
        return done(label, before, ops)
    }

    // ---- moves ----

    suspend fun archive(ids: Collection<Long>): UndoToken = relocate(ids, "Archived") { acc, gmail ->
        if (gmail) Destination.RemoveLabel("\\Inbox") else store.mailboxByRole(acc, MailboxRole.ARCHIVE)?.let { Destination.Folder(it) }
            ?: store.mailboxes(acc).firstOrNull { it.name.equals("Archive", true) }?.let { Destination.Folder(it) }
            ?: Destination.Hide
    }

    suspend fun trash(ids: Collection<Long>): UndoToken {
        val all = load(ids)
        // Already in Trash: "delete" means delete for good.
        val boxes = all.map { it.mailboxId }.distinct().associateWith { store.mailbox(it) }
        val inTrash = all.filter { boxes[it.mailboxId]?.role == MailboxRole.TRASH }
        if (inTrash.isNotEmpty() && inTrash.size == all.size) return deleteForever(ids)
        return relocate(all.filterNot { it in inTrash }.map { it.id }, "Moved to Trash") { acc, _ ->
            store.mailboxByRole(acc, MailboxRole.TRASH)?.let { Destination.Folder(it) } ?: Destination.Expunge
        }
    }

    suspend fun spam(ids: Collection<Long>, isSpam: Boolean = true): UndoToken = relocate(ids, if (isSpam) "Reported spam" else "Not spam") { acc, gmail ->
        if (isSpam) store.mailboxByRole(acc, MailboxRole.JUNK)?.let { Destination.Folder(it) } ?: Destination.Hide
        else if (gmail) store.mailboxByRole(acc, MailboxRole.ALL)?.let { Destination.FolderWithLabel(it, "\\Inbox") }
        else store.mailboxByRole(acc, MailboxRole.INBOX)?.let { Destination.Folder(it) }
    }

    suspend fun moveTo(ids: Collection<Long>, mailboxId: Long): UndoToken {
        val dest = store.mailbox(mailboxId) ?: return UndoToken("Nothing moved", 0, emptyList(), emptyList())
        return relocate(ids, "Moved to ${dest.name}") { acc, gmail ->
            when {
                acc != dest.accountId -> null // cross-account moves are copy+delete; handled by the caller explicitly
                gmail && dest.role == MailboxRole.NONE -> Destination.AddLabelAndArchive(dest.remoteId)
                gmail && dest.role == MailboxRole.INBOX -> store.mailboxByRole(acc, MailboxRole.ALL)?.let { Destination.FolderWithLabel(it, "\\Inbox") }
                else -> Destination.Folder(dest)
            }
        }
    }

    suspend fun moveToInbox(ids: Collection<Long>): UndoToken = relocate(ids, "Moved to Inbox") { acc, gmail ->
        if (gmail) store.mailboxByRole(acc, MailboxRole.ALL)?.let { Destination.FolderWithLabel(it, "\\Inbox") }
        else store.mailboxByRole(acc, MailboxRole.INBOX)?.let { Destination.Folder(it) }
    }

    suspend fun label(ids: Collection<Long>, add: List<String>, remove: List<String> = emptyList()): UndoToken {
        val before = load(ids)
        val ops = before.groupBy { it.accountId }.map { (acc, hs) -> queue(acc, PendingOp.SetLabels(hs.map(::target), add, remove)) }
        store.applyLabels(before.map { it.id }, add, remove)
        return done("Labelled", before, ops)
    }

    suspend fun deleteForever(ids: Collection<Long>): UndoToken {
        val before = load(ids)
        val ops = before.groupBy { it.accountId }.map { (acc, hs) -> queue(acc, PendingOp.Expunge(hs.map(::target))) }
        store.setHidden(before.map { it.id }, true)
        return done("Deleted forever", before, ops)
    }

    private sealed interface Destination {
        data class Folder(val box: Mailbox) : Destination
        data class FolderWithLabel(val box: Mailbox, val label: String) : Destination
        data class RemoveLabel(val label: String) : Destination
        data class AddLabelAndArchive(val label: String) : Destination
        data object Expunge : Destination
        /** No server-side home for it (POP3 archive, a server with no Junk folder): hide locally. */
        data object Hide : Destination
    }

    private suspend fun relocate(ids: Collection<Long>, label: String, resolve: suspend (accountId: Long, gmail: Boolean) -> Destination?): UndoToken {
        val before = load(ids)
        val gmailCache = HashMap<Long, Boolean>()
        val ops = ArrayList<Pair<Long, Long>>()
        for ((acc, hs) in before.groupBy { it.accountId }) {
            val account = store.account(acc)
            val gmail = isGmail(acc, gmailCache)
            val dest = resolve(acc, gmail) ?: continue
            val pop = account?.protocol == IncomingProtocol.POP3
            when (dest) {
                is Destination.Folder -> {
                    val moving = hs.filter { it.mailboxId != dest.box.id }
                    if (moving.isEmpty()) continue
                    if (!pop) ops += queue(acc, PendingOp.Move(moving.map(::target), dest.box.id))
                    store.applyMove(moving.map { it.id }, dest.box.id)
                }
                is Destination.FolderWithLabel -> {
                    val moving = hs.filter { it.mailboxId != dest.box.id }
                    if (moving.isNotEmpty()) {
                        ops += queue(acc, PendingOp.Move(moving.map(::target), dest.box.id))
                        store.applyMove(moving.map { it.id }, dest.box.id)
                    }
                    val staying = hs.filter { it.mailboxId == dest.box.id }
                    if (staying.isNotEmpty()) ops += queue(acc, PendingOp.SetLabels(staying.map(::target), listOf(dest.label), emptyList()))
                    store.applyLabels(hs.map { it.id }, listOf(dest.label), emptyList())
                }
                is Destination.RemoveLabel -> {
                    ops += queue(acc, PendingOp.SetLabels(hs.map(::target), emptyList(), listOf(dest.label)))
                    store.applyLabels(hs.map { it.id }, emptyList(), listOf(dest.label))
                }
                is Destination.AddLabelAndArchive -> {
                    ops += queue(acc, PendingOp.SetLabels(hs.map(::target), listOf(dest.label), listOf("\\Inbox")))
                    store.applyLabels(hs.map { it.id }, listOf(dest.label), listOf("\\Inbox"))
                }
                Destination.Expunge -> {
                    ops += queue(acc, PendingOp.Expunge(hs.map(::target)))
                    store.setHidden(hs.map { it.id }, true)
                }
                Destination.Hide -> store.setHidden(hs.map { it.id }, true)
            }
        }
        return done(label, before, ops)
    }

    // ---- local-only state ----

    suspend fun snooze(ids: Collection<Long>, until: Long): UndoToken {
        val before = load(ids)
        store.setSnooze(before.map { it.id }, until)
        return UndoToken("Snoozed", before.size, emptyList(), before)
    }

    suspend fun unsnooze(ids: Collection<Long>) = store.setSnooze(ids, 0)

    suspend fun pin(ids: Collection<Long>, pinned: Boolean = true): UndoToken {
        val before = load(ids)
        store.setPinned(before.map { it.id }, pinned)
        return UndoToken(if (pinned) "Pinned" else "Unpinned", before.size, emptyList(), before)
    }

    suspend fun mute(ids: Collection<Long>, muted: Boolean = true): UndoToken {
        val before = load(ids)
        store.setMuted(before.map { it.threadKey }.toSet(), muted)
        return UndoToken(if (muted) "Muted" else "Unmuted", before.size, emptyList(), before)
    }

    /** A person's correction. Recorded as USER provenance so no model ever overwrites it. */
    suspend fun recategorize(ids: Collection<Long>, category: Category): UndoToken {
        val before = load(ids)
        store.setCategory(before.map { it.id }, category, ClassificationSource.USER)
        return UndoToken("Moved to ${category.label}", before.size, emptyList(), before)
    }

    suspend fun undo(token: UndoToken) {
        // The rows as the action left them — for anything the server already applied, these are the
        // coordinates the inverse op must name (a moved message now lives in its destination).
        val after = store.headers(token.before.map { it.id }).associateBy { it.id }
        val cancelled = token.opIds.map { (acc, id) -> acc to store.cancelOp(id) }
        val reached = cancelled.filter { !it.second }.map { it.first }.toSet()
        for (prev in token.before.filter { it.accountId in reached }) {
            val now = after[prev.id] ?: continue
            if (prev.mailboxId != now.mailboxId) store.enqueueOp(prev.accountId, PendingOp.Move(listOf(target(now)), prev.mailboxId), clock.now())
            if (prev.flags != now.flags) store.enqueueOp(prev.accountId, PendingOp.SetFlags(listOf(target(now)), prev.flags and now.flags.inv(), now.flags and prev.flags.inv()), clock.now())
            if (prev.labels.toSet() != now.labels.toSet()) store.enqueueOp(prev.accountId, PendingOp.SetLabels(listOf(target(now)), prev.labels - now.labels.toSet(), now.labels - prev.labels.toSet()), clock.now())
        }
        // Local state exactly as it was. A row whose move already reached the server comes back with
        // its UID unknown; the next sync adopts it by Message-ID instead of duplicating it.
        store.restore(token.before.map { prev ->
            if (prev.accountId in reached && after[prev.id]?.mailboxId != prev.mailboxId) prev.copy(uid = 0) else prev
        })
        if (reached.isNotEmpty()) onQueued(reached)
    }

    /** For rules and "select all": resolve an [Account] lookup once per batch. */
    suspend fun accountOf(h: MessageHeader): Account? = store.account(h.accountId)
}
