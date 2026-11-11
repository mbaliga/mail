package dev.aarso.mail.sync

import dev.aarso.mail.imap.BodyPart
import dev.aarso.mail.imap.FetchItem
import dev.aarso.mail.imap.ImapClient
import dev.aarso.mail.imap.MailboxEvent
import dev.aarso.mail.imap.SequenceSet
import dev.aarso.mail.intel.Analysis
import dev.aarso.mail.intel.MessageAnalyzer
import dev.aarso.mail.intel.MessageContext
import dev.aarso.mail.jmap.JmapClient
import dev.aarso.mail.jmap.JmapEmail
import dev.aarso.mail.jmap.JmapException
import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.mime.MailCharsets
import dev.aarso.mail.mime.MailDates
import dev.aarso.mail.mime.MimeParser
import dev.aarso.mail.mime.TransferEncoding
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.BodyState
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.PartInfo
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.store.MailStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** What an account is doing right now, for the material "alive" signal in the UI (never a spinner). */
data class AccountSyncState(
    val running: Boolean = false,
    val phase: String = "",
    val lastSuccessAt: Long = 0,
    val lastError: String? = null,
    val needsReauth: Boolean = false,
)

data class SyncReport(val accountId: Long, val newMessages: List<MessageHeader>, val error: Throwable? = null)

/**
 * Brings the device copy of each account level with its server, and keeps it there.
 *
 * Order of work per account: push queued local changes first (so what is read back reflects them),
 * then mailboxes, then per-mailbox expunges → flag changes → new mail (newest first, so the digest
 * fills from the top), then bodies for the newest messages, then analysis. Every step is
 * incremental on the protocol's own cursor: QRESYNC/CONDSTORE mod-sequences on IMAP, state strings
 * on JMAP, UIDLs on POP3.
 */
class MailSync(
    private val store: MailStore,
    private val connector: AccountConnector,
    private val analyzer: MessageAnalyzer,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val _state = MutableStateFlow<Map<Long, AccountSyncState>>(emptyMap())
    val state: StateFlow<Map<Long, AccountSyncState>> = _state.asStateFlow()

    /** Called with each account's genuinely new messages after they are stored and analysed. */
    var onNewMail: suspend (Account, List<MessageHeader>) -> Unit = { _, _ -> }

    private val locks = ConcurrentHashMap<Long, Mutex>()
    private fun lock(accountId: Long) = locks.getOrPut(accountId) { Mutex() }
    private val ops = OpFlusher(store, clock)

    private fun setState(accountId: Long, f: (AccountSyncState) -> AccountSyncState) =
        _state.update { it + (accountId to f(it[accountId] ?: AccountSyncState())) }

    suspend fun syncAll(inboxOnly: Boolean = false): List<SyncReport> =
        store.accounts().filter { it.enabled }.map { sync(it, inboxOnly) }

    suspend fun sync(account: Account, inboxOnly: Boolean = false): SyncReport = lock(account.id).withLock {
        setState(account.id) { it.copy(running = true, phase = "connecting", lastError = null) }
        try {
            val fresh = when (account.protocol) {
                IncomingProtocol.IMAP -> syncImap(account, inboxOnly)
                IncomingProtocol.JMAP -> syncJmap(account)
                IncomingProtocol.POP3 -> syncPop3(account)
            }
            store.recountMailboxes(account.id)
            setState(account.id) { it.copy(running = false, phase = "", lastSuccessAt = clock.now(), needsReauth = false) }
            if (fresh.isNotEmpty()) onNewMail(account, fresh)
            SyncReport(account.id, fresh)
        } catch (e: Exception) {
            setState(account.id) {
                it.copy(running = false, phase = "", lastError = e.message ?: e::class.simpleName, needsReauth = e is AuthenticationException)
            }
            SyncReport(account.id, emptyList(), e)
        }
    }

    // ==========================================================================================
    // IMAP
    // ==========================================================================================

    private suspend fun syncImap(account: Account, inboxOnly: Boolean): List<MessageHeader> {
        val imap = connector.imap(account)
        try {
            setState(account.id) { it.copy(phase = "sending changes") }
            ops.flushImap(account, imap)
            setState(account.id) { it.copy(phase = "folders") }
            val isGmail = imap.isGmail
            val listed = imap.list()
            val boxes = store.upsertMailboxes(account.id, listed.map { Mapping.mailbox(account.id, it, isGmail) }, removeOthers = true)
            var targets = boxes.filter { it.syncEnabled && it.selectable }
                .sortedBy { if (it.role == MailboxRole.INBOX || it.role == MailboxRole.ALL) 0 else Roles.order(it.role) + 1 }
            if (inboxOnly) targets = targets.filter { it.role == MailboxRole.INBOX || (isGmail && it.role == MailboxRole.ALL) }
            val fresh = ArrayList<MessageHeader>()
            for (box in targets) {
                setState(account.id) { it.copy(phase = box.name) }
                fresh += syncImapMailbox(account, imap, box, isGmail)
            }
            if (account.sync.prefetchBodies) prefetchImapBodies(account, imap, boxes)
            reviewReplies(account, fresh)
            return fresh
        } finally {
            imap.logout()
        }
    }

    private fun fetchItems(imap: ImapClient, isGmail: Boolean): String = buildString {
        append("UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS ($HEADER_FIELDS)]")
        if (isGmail) append(" X-GM-LABELS X-GM-THRID X-GM-MSGID")
        if (imap.has("OBJECTID")) append(" EMAILID THREADID")
        if (imap.has("PREVIEW")) append(" PREVIEW")
        if (imap.condstoreEnabled) append(" MODSEQ")
    }

    private suspend fun syncImapMailbox(account: Account, imap: ImapClient, initial: Mailbox, isGmail: Boolean): List<MessageHeader> {
        var box = initial
        val sel = imap.select(box.remoteId, knownUidValidity = box.uidValidity, knownModSeq = box.highestModSeq)
        if (box.uidValidity != 0L && sel.uidValidity != box.uidValidity) {
            store.resetMailbox(box.id)
            box = box.copy(uidValidity = 0, highestModSeq = 0)
        }
        val local = store.uids(box.id)
        val flagItems = "UID FLAGS" + if (isGmail) " X-GM-LABELS" else ""

        if (local.isNotEmpty()) {
            val minUid = local.keys.min()
            val vanished = ArrayList(sel.vanished)
            val changed: List<FetchItem> = when {
                sel.changed.isNotEmpty() || (imap.qresyncEnabled && box.highestModSeq > 0 && sel.highestModSeq == box.highestModSeq) -> sel.changed
                imap.condstoreEnabled && box.highestModSeq > 0 && !sel.noModSeq ->
                    if (sel.highestModSeq == box.highestModSeq) emptyList()
                    else imap.uidFetch("$minUid:*", flagItems, changedSince = box.highestModSeq, vanished = imap.qresyncEnabled) { vanished += it }
                else -> SequenceSet.chunked(local.keys, 1000).flatMap { set -> imap.uidFetch(set, flagItems) }
            }
            store.updateServerState(box.id, changed.mapNotNull { f ->
                val uid = f.uid ?: return@mapNotNull null
                if (uid !in local) return@mapNotNull null
                var flags = dev.aarso.mail.model.Flags.fromImap(f.flags.orEmpty())
                if (isGmail && f.gmailLabels?.any { it.equals("\\Important", true) } == true) flags = flags or dev.aarso.mail.model.Flags.IMPORTANT
                Triple(uid, flags, f.gmailLabels)
            })
            if (!imap.qresyncEnabled) {
                // No QRESYNC: the only way to see expunges is to compare UID sets (ESEARCH keeps it small).
                val onServer = imap.uidSearch("UID $minUid:*").toHashSet()
                vanished += local.keys.filter { it !in onServer }
            }
            if (vanished.isNotEmpty()) store.deleteByUids(box.id, vanished)
        }

        val maxLocal = local.keys.maxOrNull() ?: 0
        val windowStart = clock.now() - account.sync.windowDays * DAY
        val newUids = if (maxLocal > 0) {
            imap.uidSearch("UID ${maxLocal + 1}:*").filter { it > maxLocal }
        } else {
            imap.uidSearch("SINCE ${MailDates.formatImapSearch(windowStart)}")
        }
        val fresh = ArrayList<MessageHeader>()
        val items = fetchItems(imap, isGmail)
        for (batch in newUids.sortedDescending().chunked(100)) {
            val fetched = imap.uidFetch(SequenceSet.of(batch), items)
            val mapped = fetched.mapNotNull { Mapping.header(account.id, box.id, it, isGmail) }.map { resolveThread(it) }
            val stored = store.insertMessages(mapped)
            val bodies = if (account.sync.prefetchBodies) fetchTextBodies(imap, stored, fetched) else emptyMap()
            for (h in stored) analyzeAndStore(account, h, bodies[h.id])
            fresh += stored.filter { it.receivedAt >= windowStart }
        }
        store.updateMailboxCursor(box.id, sel.uidValidity, sel.uidNext, sel.highestModSeq, null, clock.now())
        return fresh
    }

    /** Fetch the readable text parts (not attachments) of [stored], batched by section id. */
    private suspend fun fetchTextBodies(imap: ImapClient, stored: List<MessageHeader>, fetched: List<FetchItem>): Map<Long, MessageBody> {
        val byUid = fetched.associateBy { it.uid }
        data class Plan(val h: MessageHeader, val structure: BodyPart, val plain: BodyPart?, val html: BodyPart?)
        val plans = stored.mapNotNull { h ->
            val bs = byUid[h.uid]?.bodyStructure ?: return@mapNotNull null
            val plain = bs.textSection(preferHtml = false)?.takeIf { it.subtype == "plain" && it.size < MAX_TEXT_PART }
            val html = bs.textSection(preferHtml = true)?.takeIf { it.subtype == "html" && it.size < MAX_TEXT_PART }
            Plan(h, bs, plain, html)
        }
        val texts = HashMap<Long, String>()
        val htmls = HashMap<Long, String>()
        suspend fun fetchGroup(parts: List<Pair<Plan, BodyPart>>, into: HashMap<Long, String>) {
            for ((partId, group) in parts.groupBy { it.second.partId }) {
                for (chunk in group.chunked(50)) {
                    val result = imap.uidFetch(SequenceSet.of(chunk.map { it.first.h.uid }), "UID BODY.PEEK[$partId]").associateBy { it.uid }
                    for ((plan, part) in chunk) {
                        val raw = result[plan.h.uid]?.section(partId) ?: continue
                        into[plan.h.id] = decodePart(raw, part.encoding, part.params["charset"])
                    }
                }
            }
        }
        fetchGroup(plans.mapNotNull { p -> p.plain?.let { p to it } }, texts)
        fetchGroup(plans.mapNotNull { p -> p.html?.let { p to it } }, htmls)
        val now = clock.now()
        val out = HashMap<Long, MessageBody>()
        for (p in plans) {
            val html = htmls[p.h.id]
            val text = texts[p.h.id] ?: html?.let { HtmlText.toText(it) }
            if (text == null && html == null) continue
            val body = MessageBody(
                messageId = p.h.id, text = text, html = html,
                attachments = p.structure.leaves().filter { it.isAttachment }.map { it.toPartInfo() },
                structuredData = html?.let { HtmlText.jsonLd(it) }.orEmpty(), fetchedAt = now,
            )
            store.saveBody(body, p.h)
            out[p.h.id] = body
        }
        return out
    }

    /** Older messages in the window that synced as headers only — fill in their text in the background. */
    private suspend fun prefetchImapBodies(account: Account, imap: ImapClient, boxes: List<Mailbox>) {
        val since = clock.now() - minOf(account.sync.windowDays, 30) * DAY
        val missing = store.missingBodies(account.id, 150, since)
        if (missing.isEmpty()) return
        val byBox = missing.groupBy { it.mailboxId }
        for ((boxId, headers) in byBox) {
            val box = boxes.firstOrNull { it.id == boxId } ?: continue
            if (!box.selectable) continue
            runCatching {
                imap.select(box.remoteId, readOnly = true)
                for (chunk in headers.filter { it.uid > 0 }.chunked(50)) {
                    val fetched = imap.uidFetch(SequenceSet.of(chunk.map { it.uid }), "UID BODYSTRUCTURE")
                    val bodies = fetchTextBodies(imap, chunk, fetched)
                    for (h in chunk) bodies[h.id]?.let { analyzeAndStore(account, h, it) }
                }
            }
        }
    }

    // ==========================================================================================
    // JMAP
    // ==========================================================================================

    private suspend fun syncJmap(account: Account): List<MessageHeader> {
        val jmap = connector.jmap(account)
        setState(account.id) { it.copy(phase = "sending changes") }
        ops.flushJmap(account, jmap)
        setState(account.id) { it.copy(phase = "folders") }
        val remote = jmap.mailboxes()
        val boxes = store.upsertMailboxes(account.id, remote.map { Mapping.mailbox(account.id, it, it.name) }, removeOthers = true)
        val byRemote = boxes.associateBy { it.remoteId }
        val names = remote.associate { it.id to it.name }
        val stateKey = "jmap.emailState.${account.id}"
        val fresh = ArrayList<MessageHeader>()
        val since = store.setting(stateKey)
        if (since == null) {
            val newState = jmap.emailState()
            val after = java.time.Instant.ofEpochMilli(clock.now() - account.sync.windowDays * DAY).toString()
            var position = 0
            while (position < MAX_INITIAL) {
                val q = jmap.queryEmails(buildJsonObject { put("after", after) }, position, 200)
                if (q.ids.isEmpty()) break
                fresh += storeJmap(account, jmap.getEmails(q.ids, withBodies = account.sync.prefetchBodies), byRemote, names, analyse = true)
                position += q.ids.size
                if (q.total != null && position >= q.total) break
            }
            store.putSetting(stateKey, newState)
        } else {
            var cursor: String = since
            while (true) {
                val ch = try {
                    jmap.emailChanges(cursor)
                } catch (e: JmapException) {
                    if (e.type == "cannotCalculateChanges") {
                        store.putSetting(stateKey, null)
                        return syncJmap(account)
                    }
                    throw e
                }
                store.deleteByRemoteIds(account.id, ch.destroyed)
                if (ch.updated.isNotEmpty()) storeJmap(account, jmap.getEmails(ch.updated, withBodies = false), byRemote, names, analyse = false)
                if (ch.created.isNotEmpty()) fresh += storeJmap(account, jmap.getEmails(ch.created, withBodies = account.sync.prefetchBodies), byRemote, names, analyse = true)
                cursor = ch.newState
                if (!ch.hasMoreChanges) break
            }
            store.putSetting(stateKey, cursor)
        }
        reviewReplies(account, fresh)
        return fresh
    }

    private suspend fun storeJmap(
        account: Account,
        emails: List<JmapEmail>,
        byRemote: Map<String, Mailbox>,
        names: Map<String, String>,
        analyse: Boolean,
    ): List<MessageHeader> {
        val headers = emails.mapNotNull { e ->
            val primary = e.mailboxIds.mapNotNull { byRemote[it] }.minByOrNull { Roles.order(it.role) } ?: return@mapNotNull null
            e to Mapping.header(account.id, primary.id, e, names)
        }
        val stored = store.insertMessages(headers.map { it.second })
        if (!analyse) return emptyList()
        val now = clock.now()
        for ((i, h) in stored.withIndex()) {
            val e = headers[i].first
            val body = if (e.textBody != null || e.htmlBody != null) {
                MessageBody(
                    messageId = h.id, text = e.textBody ?: e.htmlBody?.let { HtmlText.toText(it) }, html = e.htmlBody,
                    attachments = h.parts, structuredData = e.htmlBody?.let { HtmlText.jsonLd(it) }.orEmpty(), fetchedAt = now,
                ).also { store.saveBody(it, h) }
            } else null
            analyzeAndStore(account, h, body)
        }
        return stored
    }

    // ==========================================================================================
    // POP3
    // ==========================================================================================

    private suspend fun syncPop3(account: Account): List<MessageHeader> {
        val pop = connector.pop3(account)
        try {
            val inbox = store.upsertMailboxes(account.id, listOf(Mailbox(accountId = account.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX)), removeOthers = false)
                .first { it.role == MailboxRole.INBOX }
            val uidl = pop.uidl()
            ops.flushPop3(account, pop, uidl)
            val known = store.remoteIds(account.id)
            val onServer = uidl.values.toHashSet()
            store.deleteByRemoteIds(account.id, known.keys.filter { it !in onServer })
            val sizes = pop.sizes()
            val fresh = ArrayList<MessageHeader>()
            val now = clock.now()
            for ((number, id) in uidl.entries.filter { it.value !in known }.sortedByDescending { it.key }.take(MAX_INITIAL)) {
                val size = sizes[number] ?: 0
                val full = account.sync.prefetchBodies && size < 2_000_000
                val raw = if (full) pop.retrieve(number) else pop.top(number, 60)
                val parsed = MimeParser.parse(raw)
                val h = resolveThread(Mapping.header(account.id, inbox.id, id, parsed, parsed.date ?: now, size))
                val stored = store.insertMessages(listOf(h.copy(bodyState = if (full) BodyState.FULL else BodyState.PARTIAL))).first()
                val body = parsed.toBody(stored.id, now)
                store.saveBody(body, stored)
                analyzeAndStore(account, stored, body)
                fresh += stored
            }
            return fresh
        } finally {
            pop.quit()
        }
    }

    // ==========================================================================================
    // Shared
    // ==========================================================================================

    /**
     * A reply can be analysed before the message it answers is on the device (Inbox syncs before
     * Sent; Gmail's All Mail is processed newest first). Once every folder is in, look again at the
     * fresh messages that reference something and promote the ones that turn out to answer me.
     */
    private suspend fun reviewReplies(account: Account, fresh: List<MessageHeader>) {
        for (h in fresh) {
            if (h.inReplyTo == null && h.references.isEmpty()) continue
            val current = store.header(h.id) ?: continue
            if (current.category == dev.aarso.mail.model.Category.REPLIES) continue
            if (contextFor(account, current).repliesToMe) analyzeAndStore(account, current, store.body(h.id))
        }
    }

    /** Join a reply to the conversation its parent is already in (works across folders and arrival order). */
    private suspend fun resolveThread(h: MessageHeader): MessageHeader {
        if (h.threadKey.startsWith("gm:") || h.threadKey.startsWith("jm:") || h.threadKey.startsWith("ob:")) return h
        val parents = (listOfNotNull(h.inReplyTo) + h.references.reversed()).distinct()
        val existing = store.threadKeyFor(h.accountId, parents)
        return if (existing != null) h.copy(threadKey = existing) else h
    }

    private suspend fun contextFor(account: Account, h: MessageHeader): MessageContext {
        val me = account.myAddresses
        val parents = (listOfNotNull(h.inReplyTo) + h.references).distinct()
        val from = h.from?.normalized
        return MessageContext(
            repliesToMe = parents.isNotEmpty() && store.anySentByMe(account.id, parents, me),
            knownCorrespondent = from != null && (store.contact(from)?.sentCount ?: 0) > 0,
            directToMe = h.to.any { it.normalized in me },
            fromMe = from != null && from in me,
        )
    }

    private suspend fun analyzeAndStore(account: Account, h: MessageHeader, body: MessageBody?): Analysis {
        val ctx = contextFor(account, h)
        val a = analyzer.analyze(h, body, ctx)
        // A person's own correction outranks every model; never overwrite it.
        val keepCategory = h.categorySource == dev.aarso.mail.model.ClassificationSource.USER || h.categorySource == dev.aarso.mail.model.ClassificationSource.RULE
        store.updateDerived(
            id = h.id,
            category = if (keepCategory) null else a.category,
            categoryConfidence = if (keepCategory) null else a.categoryConfidence,
            categorySource = if (keepCategory) null else a.categorySource,
            tldr = a.tldr, tldrSource = a.tldrSource,
            otp = a.otp?.code ?: "",
            language = a.language, priority = a.priority, trackerCount = a.trackerCount,
        )
        h.from?.let { store.recordContacts(listOf(it), sentByMe = ctx.fromMe, at = h.receivedAt) }
        if (ctx.fromMe) store.recordContacts(h.to + h.cc, sentByMe = true, at = h.receivedAt)
        return a
    }

    /** Re-run analysis for one message (e.g. after its body arrived, or a model became available). */
    suspend fun reanalyze(messageId: Long) {
        val h = store.header(messageId) ?: return
        val account = store.account(h.accountId) ?: return
        analyzeAndStore(account, h, store.body(messageId))
    }

    // ---- on demand ----

    /** The body for the reader, downloading it if it is not on the device yet. */
    suspend fun fetchBody(messageId: Long): MessageBody? {
        store.body(messageId)?.let { if (it.text != null || it.html != null) return it }
        val h = store.header(messageId) ?: return null
        val account = store.account(h.accountId) ?: return null
        val box = store.mailbox(h.mailboxId) ?: return null
        val body = when (account.protocol) {
            IncomingProtocol.IMAP -> {
                val imap = connector.imap(account)
                try {
                    imap.select(box.remoteId, readOnly = true)
                    val fetched = imap.uidFetch(h.uid.toString(), "UID BODYSTRUCTURE")
                    fetchTextBodies(imap, listOf(h), fetched)[h.id]
                } finally { imap.logout() }
            }
            IncomingProtocol.JMAP -> {
                val jmap = connector.jmap(account)
                val e = jmap.getEmails(listOfNotNull(h.remoteId), withBodies = true).firstOrNull() ?: return null
                MessageBody(h.id, e.textBody ?: e.htmlBody?.let { HtmlText.toText(it) }, e.htmlBody, h.parts,
                    e.htmlBody?.let { HtmlText.jsonLd(it) }.orEmpty(), clock.now()).also { store.saveBody(it, h) }
            }
            IncomingProtocol.POP3 -> {
                val pop = connector.pop3(account)
                try {
                    val number = pop.uidl().entries.firstOrNull { it.value == h.remoteId }?.key ?: return null
                    MimeParser.parse(pop.retrieve(number)).toBody(h.id, clock.now()).also { store.saveBody(it, h) }
                } finally { pop.quit() }
            }
        }
        if (body != null) analyzeAndStore(account, h, body)
        return body
    }

    /** Raw bytes of one part (attachment, inline image), decoded from its transfer encoding. */
    suspend fun fetchPart(messageId: Long, part: PartInfo): ByteArray? {
        val h = store.header(messageId) ?: return null
        val account = store.account(h.accountId) ?: return null
        return when (account.protocol) {
            IncomingProtocol.IMAP -> {
                val box = store.mailbox(h.mailboxId) ?: return null
                val imap = connector.imap(account)
                try {
                    imap.select(box.remoteId, readOnly = true)
                    val raw = imap.uidFetch(h.uid.toString(), "UID BODY.PEEK[${part.partId}]").firstOrNull()?.section(part.partId) ?: return null
                    TransferEncoding.decode(raw, part.encoding)
                } finally { imap.logout() }
            }
            IncomingProtocol.JMAP -> connector.jmap(account).download(part.blobId ?: part.partId, part.mimeType, part.fileName ?: "file")
            IncomingProtocol.POP3 -> {
                val pop = connector.pop3(account)
                try {
                    val number = pop.uidl().entries.firstOrNull { it.value == h.remoteId }?.key ?: return null
                    MimeParser.parse(pop.retrieve(number)).root.walk().firstOrNull { it.partId == part.partId }?.body
                } finally { pop.quit() }
            }
        }
    }

    /** The whole message as RFC 5322 bytes — for "show original", export to .eml, and forward-as-attachment. */
    suspend fun fetchRaw(messageId: Long): ByteArray? {
        val h = store.header(messageId) ?: return null
        val account = store.account(h.accountId) ?: return null
        return when (account.protocol) {
            IncomingProtocol.IMAP -> {
                val box = store.mailbox(h.mailboxId) ?: return null
                val imap = connector.imap(account)
                try {
                    imap.select(box.remoteId, readOnly = true)
                    imap.uidFetch(h.uid.toString(), "UID BODY.PEEK[]").firstOrNull()?.section("")
                } finally { imap.logout() }
            }
            IncomingProtocol.JMAP -> h.providerMessageId?.let { connector.jmap(account).download(it, "message/rfc822", "message.eml") }
            IncomingProtocol.POP3 -> {
                val pop = connector.pop3(account)
                try {
                    val number = pop.uidl().entries.firstOrNull { it.value == h.remoteId }?.key ?: return null
                    pop.retrieve(number)
                } finally { pop.quit() }
            }
        }
    }

    /**
     * Search the *server* — for mail older than the sync window or never downloaded. Gmail gets its
     * own query language (X-GM-RAW), JMAP its filter, other IMAP servers TEXT search. Hits not already
     * on the device are fetched as headers and stored, so they behave like any other message.
     */
    suspend fun serverSearch(account: Account, text: String, limit: Int = 100): List<MessageHeader> = lock(account.id).withLock {
        when (account.protocol) {
            IncomingProtocol.IMAP -> {
                val imap = connector.imap(account)
                try {
                    val isGmail = imap.isGmail
                    val boxes = store.mailboxes(account.id)
                    val target = boxes.firstOrNull { it.role == MailboxRole.ALL } ?: boxes.firstOrNull { it.role == MailboxRole.INBOX } ?: return@withLock emptyList()
                    imap.select(target.remoteId, readOnly = true)
                    val uids = if (isGmail) imap.gmailSearch(text) else imap.uidSearch(listOf(ImapClient.Part.Text("TEXT "), imap.searchString(text)))
                    val local = store.uids(target.id)
                    val missing = uids.sortedDescending().take(limit).filter { it !in local }
                    val fetched = if (missing.isEmpty()) emptyList() else imap.uidFetch(SequenceSet.of(missing), fetchItems(imap, isGmail))
                    val stored = store.insertMessages(fetched.mapNotNull { Mapping.header(account.id, target.id, it, isGmail) }.map { resolveThread(it) })
                    for (h in stored) analyzeAndStore(account, h, null)
                    store.headers(uids.sortedDescending().take(limit).mapNotNull { local[it] } + stored.map { it.id })
                } finally { imap.logout() }
            }
            IncomingProtocol.JMAP -> {
                val jmap = connector.jmap(account)
                val remote = jmap.mailboxes()
                val boxes = store.upsertMailboxes(account.id, remote.map { Mapping.mailbox(account.id, it, it.name) }, removeOthers = false)
                val q = jmap.queryEmails(buildJsonObject { put("text", text) }, 0, limit)
                storeJmap(account, jmap.getEmails(q.ids, withBodies = false), boxes.associateBy { it.remoteId }, remote.associate { it.id to it.name }, analyse = false)
                val ids = store.remoteIds(account.id)
                store.headers(q.ids.mapNotNull { ids[it] })
            }
            IncomingProtocol.POP3 -> emptyList()
        }
    }

    /**
     * Push: hold IDLE on the inbox (or JMAP EventSource) and run a quick sync whenever the server
     * announces new mail. Returns when [keepGoing] says stop or the connection drops; the caller
     * reconnects with backoff.
     */
    suspend fun push(account: Account, keepGoing: () -> Boolean) {
        when (account.protocol) {
            IncomingProtocol.IMAP -> {
                val imap = connector.imap(account)
                try {
                    val boxes = store.mailboxes(account.id)
                    val inbox = if (imap.isGmail) boxes.firstOrNull { it.role == MailboxRole.ALL } else boxes.firstOrNull { it.role == MailboxRole.INBOX }
                    inbox ?: return
                    while (keepGoing()) {
                        imap.select(inbox.remoteId, readOnly = true)
                        var changed = false
                        imap.idle(maxMillis = 24 * 60_000L) { ev ->
                            if (ev is MailboxEvent.Exists || ev is MailboxEvent.Expunged || ev is MailboxEvent.FlagsChanged || ev is MailboxEvent.Vanished) {
                                changed = true
                            }
                            val stop = !keepGoing() || ev is MailboxEvent.Exists
                            stop
                        }
                        if (changed) sync(account, inboxOnly = true)
                    }
                } finally { imap.logout() }
            }
            IncomingProtocol.JMAP -> {
                connector.jmap(account).events().collect { change ->
                    if (!keepGoing()) throw kotlinx.coroutines.CancellationException("push stopped")
                    if (change.changed.values.any { it.containsKey("Email") }) sync(account)
                }
            }
            IncomingProtocol.POP3 -> Unit // POP3 has no push; the periodic worker polls it.
        }
    }

    /** Push queued local changes now (after a bulk action) without a full sync. */
    suspend fun flushOps(account: Account) = lock(account.id).withLock {
        when (account.protocol) {
            IncomingProtocol.IMAP -> { val imap = connector.imap(account); try { ops.flushImap(account, imap) } finally { imap.logout() } }
            IncomingProtocol.JMAP -> ops.flushJmap(account, connector.jmap(account))
            IncomingProtocol.POP3 -> {
                val pop = connector.pop3(account)
                try { ops.flushPop3(account, pop, pop.uidl()) } finally { pop.quit() }
            }
        }
    }

    companion object {
        const val DAY = 86_400_000L
        private const val MAX_TEXT_PART = 768L * 1024
        private const val MAX_INITIAL = 5000

        internal fun decodePart(raw: ByteArray, encoding: String?, charset: String?): String {
            val bytes = TransferEncoding.decode(raw, encoding)
            return if (charset == null) MailCharsets.decode(bytes, MailCharsets.sniff(bytes)) else MailCharsets.decode(bytes, charset)
        }
    }
}
