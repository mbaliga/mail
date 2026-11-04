package dev.aarso.mail.sync

import dev.aarso.mail.imap.ImapClient
import dev.aarso.mail.mime.MimeBuilder
import dev.aarso.mail.mime.OutgoingMessage
import dev.aarso.mail.mime.OutgoingPart
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Draft
import dev.aarso.mail.model.DraftAttachment
import dev.aarso.mail.model.DraftKind
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.smtp.SmtpException
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.store.OutboxItem
import kotlinx.coroutines.delay
import java.io.IOException

/** Reads a draft attachment's bytes (a content:// URI on Android, a file in tests). */
fun interface AttachmentLoader {
    suspend fun load(attachment: DraftAttachment): ByteArray
}

data class SendResult(val sent: Int, val failed: List<Pair<OutboxItem, String>>)

/**
 * Outgoing mail: queue, hold for the undo window or the scheduled time, send, file the Sent copy,
 * mark what it answered, and remember who was written to.
 *
 * Undo-send and send-later are the same mechanism — an outbox row whose `send_at` is in the future
 * — so "undo" is simply taking the row back before it is due, and nothing can half-send.
 */
class Outbox(
    private val store: MailStore,
    private val connector: AccountConnector,
    private val actions: MailActions,
    private val loader: AttachmentLoader,
    private val clock: Clock = Clock.SYSTEM,
) {
    /** Queue [draft]; it goes out at its own sendAt, or after [undoSeconds] — whichever is later. */
    suspend fun queue(draft: Draft, undoSeconds: Int): Long {
        val now = clock.now()
        val at = maxOf(draft.sendAt, now + undoSeconds * 1000L)
        if (draft.id > 0) store.deleteDraft(draft.id)
        return store.enqueueOutgoing(draft.copy(updatedAt = now), at, now)
    }

    /** Take a queued message back (undo send, or edit a scheduled one). Null once it has gone. */
    suspend fun recall(outboxId: Long): Draft? {
        val item = store.outbox().firstOrNull { it.id == outboxId && it.state == "QUEUED" } ?: return null
        store.removeOutgoing(outboxId)
        return store.saveDraft(item.draft.copy(id = 0, sendAt = 0, updatedAt = clock.now()))
    }

    suspend fun sendDue(): SendResult {
        var sent = 0
        val failed = ArrayList<Pair<OutboxItem, String>>()
        for (item in store.dueOutgoing(clock.now())) {
            val account = store.account(item.draft.accountId) ?: continue
            store.setOutboxState(item.id, "SENDING")
            try {
                send(account, item.draft)
                store.removeOutgoing(item.id)
                sent++
            } catch (e: SmtpException) {
                if (e.permanent) {
                    store.setOutboxState(item.id, "FAILED", e.text)
                    failed += item to e.text
                } else retry(item, e)
            } catch (e: IOException) {
                retry(item, e)
            }
        }
        return SendResult(sent, failed)
    }

    private suspend fun retry(item: OutboxItem, e: Exception) {
        val backoff = (60_000L shl item.attempts.coerceAtMost(6))
        if (item.attempts >= 8) store.setOutboxState(item.id, "FAILED", e.message)
        else store.setOutboxState(item.id, "QUEUED", e.message, clock.now() + backoff)
    }

    private suspend fun build(draft: Draft, now: Long): OutgoingMessage = OutgoingMessage(
        from = draft.from,
        to = draft.to, cc = draft.cc, bcc = draft.bcc, replyTo = draft.replyTo,
        subject = draft.subject,
        text = draft.textBody,
        html = draft.htmlBody,
        parts = draft.attachments.map { a -> OutgoingPart(a.fileName, a.mimeType, loader.load(a), a.inline, a.contentId) },
        inReplyTo = draft.inReplyTo,
        references = draft.references,
        dateMillis = now,
        messageId = MimeBuilder.newMessageId(draft.from.address, now),
        requestReadReceipt = draft.requestReadReceipt,
    )

    private suspend fun send(account: Account, draft: Draft) {
        val now = clock.now()
        val msg = build(draft, now)
        val recipients = msg.allRecipients.map { it.address }
        when (account.protocol) {
            IncomingProtocol.JMAP -> if (account.outgoing == null) {
                val jmap = connector.jmap(account)
                val identity = jmap.identities().let { ids -> ids.firstOrNull { it.second.normalized == draft.from.normalized } ?: ids.firstOrNull() }
                    ?: throw SmtpException(550, "No sending identity on this account")
                val drafts = store.mailboxByRole(account.id, MailboxRole.DRAFTS) ?: throw SmtpException(550, "No Drafts mailbox")
                val sent = store.mailboxByRole(account.id, MailboxRole.SENT)
                jmap.send(MimeBuilder.build(msg, includeBcc = true), identity.first, drafts.remoteId, sent?.remoteId, draft.from.address, recipients)
            } else smtpSend(account, msg, recipients)
            else -> smtpSend(account, msg, recipients)
        }
        afterSend(account, draft, msg)
    }

    private suspend fun smtpSend(account: Account, msg: OutgoingMessage, recipients: List<String>) {
        val smtp = connector.smtp(account)
        try {
            smtp.send(msg.from.address, recipients, MimeBuilder.build(msg, includeBcc = false))
        } finally {
            smtp.quit()
        }
        if (account.protocol == IncomingProtocol.IMAP) fileSentCopy(account, msg)
    }

    /**
     * Gmail and Microsoft file a Sent copy themselves; most other servers do not. Rather than guess
     * per provider (and either lose the copy or duplicate it), look for this Message-ID in Sent and
     * append only if the server did not already put it there.
     */
    private suspend fun fileSentCopy(account: Account, msg: OutgoingMessage) {
        val sent = store.mailboxByRole(account.id, MailboxRole.SENT) ?: return
        runCatching {
            val imap = connector.imap(account)
            try {
                delay(1500)
                imap.select(sent.remoteId)
                val found = imap.uidSearch(listOf(ImapClient.Part.Text("HEADER Message-ID "), imap.searchString(msg.messageId)))
                if (found.isEmpty()) imap.append(sent.remoteId, MimeBuilder.build(msg, includeBcc = true), listOf("\\Seen"), msg.dateMillis)
            } finally {
                imap.logout()
            }
        }
    }

    private suspend fun afterSend(account: Account, draft: Draft, msg: OutgoingMessage) {
        draft.sourceMessageId?.let { source ->
            val flag = if (draft.kind == DraftKind.FORWARD) Flags.FORWARDED else Flags.ANSWERED
            actions.setFlags(listOf(source), add = flag, remove = 0, label = "Answered")
        }
        store.recordContacts(msg.to + msg.cc + msg.bcc, sentByMe = true, at = msg.dateMillis)
        if (draft.followUpAt > 0) store.addReminder(draft.sourceMessageId ?: 0, "followup", draft.followUpAt, msg.messageId)
    }
}
