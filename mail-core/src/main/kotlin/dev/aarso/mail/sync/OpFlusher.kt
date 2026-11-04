package dev.aarso.mail.sync

import dev.aarso.mail.imap.ImapClient
import dev.aarso.mail.imap.ImapCommandException
import dev.aarso.mail.imap.SequenceSet
import dev.aarso.mail.jmap.JmapClient
import dev.aarso.mail.jmap.JmapException
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.OpTarget
import dev.aarso.mail.model.PendingOp
import dev.aarso.mail.pop3.Pop3Client
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.store.QueuedOp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/**
 * Replays queued local changes against the server, in order, batched by folder.
 *
 * The contract with the UI: every action is already visible locally; this only makes the server
 * agree. A server refusal (NO/BAD, a JMAP setError) is retried with backoff and, after five tries,
 * dropped — the next sync then shows the server's truth, which is the honest outcome. A network
 * error stops the flush and leaves the queue intact for next time.
 */
class OpFlusher(private val store: MailStore, private val clock: Clock) {

    private fun backoff(attempts: Int): Long = clock.now() + (30_000L shl attempts.coerceAtMost(6))

    private suspend fun each(account: Account, block: suspend (QueuedOp) -> Unit) {
        for (q in store.pendingOps(account.id, clock.now())) {
            try {
                block(q)
                store.completeOp(q.id)
            } catch (e: ImapCommandException) {
                if (q.attempts >= 4) store.completeOp(q.id) else store.failOp(q.id, e.message ?: "refused", backoff(q.attempts))
            } catch (e: JmapException) {
                if (q.attempts >= 4) store.completeOp(q.id) else store.failOp(q.id, e.message ?: "refused", backoff(q.attempts))
            } catch (e: IOException) {
                store.failOp(q.id, e.message ?: "network", clock.now())
                throw e
            }
        }
    }

    // ---- IMAP ----

    suspend fun flushImap(account: Account, imap: ImapClient) = each(account) { q ->
        when (val op = q.op) {
            is PendingOp.SetFlags -> forEachBox(op.targets) { _, box, uids ->
                imap.select(box)
                for (set in SequenceSet.chunked(uids)) {
                    if (op.add != 0) imap.uidStoreFlags(set, add = true, Flags.toImap(op.add))
                    if (op.remove != 0) imap.uidStoreFlags(set, add = false, Flags.toImap(op.remove))
                }
            }
            is PendingOp.SetLabels -> forEachBox(op.targets) { _, box, uids ->
                if (!imap.isGmail) return@forEachBox
                imap.select(box)
                for (set in SequenceSet.chunked(uids)) {
                    if (op.add.isNotEmpty()) imap.uidStoreGmailLabels(set, add = true, op.add)
                    if (op.remove.isNotEmpty()) imap.uidStoreGmailLabels(set, add = false, op.remove)
                }
            }
            is PendingOp.Move -> {
                val dest = store.mailbox(op.toMailboxId) ?: return@each
                val byId = op.targets.associateBy { it.uid to it.mailboxId }
                forEachBox(op.targets) { boxId, box, uids ->
                    if (boxId == dest.id) return@forEachBox
                    imap.select(box)
                    for (chunk in uids.toSortedSet().chunked(500)) {
                        val copy = imap.uidMove(SequenceSet.of(chunk), dest.remoteId) ?: continue
                        // COPYUID pairs source and destination UIDs position by position (RFC 4315).
                        val pairs = copy.sourceUids.zip(copy.destUids).mapNotNull { (src, dst) -> byId[src to boxId]?.messageId?.let { it to dst } }
                        store.assignUids(pairs)
                    }
                }
            }
            is PendingOp.Expunge -> forEachBox(op.targets) { _, box, uids ->
                imap.select(box)
                for (set in SequenceSet.chunked(uids)) {
                    imap.uidStoreFlags(set, add = true, listOf("\\Deleted"))
                    imap.uidExpunge(set)
                }
            }
        }
    }

    /** Targets grouped by the folder the server still has them in. UID 0 (not yet known) is skipped. */
    private suspend fun forEachBox(targets: List<OpTarget>, block: suspend (boxId: Long, remoteName: String, uids: List<Long>) -> Unit) {
        for ((boxId, group) in targets.filter { it.uid > 0 }.groupBy { it.mailboxId }) {
            val box = store.mailbox(boxId) ?: continue
            block(boxId, box.remoteId, group.map { it.uid })
        }
    }

    // ---- JMAP ----

    suspend fun flushJmap(account: Account, jmap: JmapClient) = each(account) { q ->
        val targets = q.op.targets.filter { it.remoteId != null }
        when (val op = q.op) {
            is PendingOp.SetFlags -> {
                val patch = buildMap<String, kotlinx.serialization.json.JsonElement> {
                    Flags.toJmap(op.add).forEach { put("keywords/$it", JsonPrimitive(true)) }
                    Flags.toJmap(op.remove).forEach { put("keywords/$it", JsonNull) }
                }
                jmap.setEmails(update = targets.associate { it.remoteId!! to JsonObject(patch) })
            }
            is PendingOp.Move -> {
                val dest = store.mailbox(op.toMailboxId)?.remoteId ?: return@each
                val update = targets.associate { t ->
                    val from = store.mailbox(t.mailboxId)?.remoteId
                    t.remoteId!! to JsonObject(buildMap {
                        if (from != null && from != dest) put("mailboxIds/$from", JsonNull)
                        put("mailboxIds/$dest", JsonPrimitive(true))
                    })
                }
                jmap.setEmails(update = update)
            }
            is PendingOp.SetLabels -> {
                val boxes = store.mailboxes(account.id).associateBy { it.name }
                val patch = buildMap<String, kotlinx.serialization.json.JsonElement> {
                    op.add.mapNotNull { boxes[it]?.remoteId }.forEach { put("mailboxIds/$it", JsonPrimitive(true)) }
                    op.remove.mapNotNull { boxes[it]?.remoteId }.forEach { put("mailboxIds/$it", JsonNull) }
                }
                if (patch.isNotEmpty()) jmap.setEmails(update = targets.associate { it.remoteId!! to JsonObject(patch) })
            }
            is PendingOp.Expunge -> jmap.setEmails(destroy = targets.map { it.remoteId!! })
        }
    }

    // ---- POP3: only deletion reaches the server; everything else is local state ----

    suspend fun flushPop3(account: Account, pop: Pop3Client, uidl: Map<Int, String>) = each(account) { q ->
        val op = q.op
        if (op is PendingOp.Expunge) {
            val numbers = uidl.entries.associate { it.value to it.key }
            for (t in op.targets) t.remoteId?.let { numbers[it] }?.let { pop.delete(it) }
        }
    }
}
