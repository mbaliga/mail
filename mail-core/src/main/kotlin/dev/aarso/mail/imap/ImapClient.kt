package dev.aarso.mail.imap

import dev.aarso.mail.mime.MailDates
import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.net.MailException
import dev.aarso.mail.net.ProtocolLog
import dev.aarso.mail.net.Transport
import dev.aarso.mail.net.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** How an IMAP session authenticates. Secrets live only for the duration of [ImapClient.login]. */
sealed interface ImapAuth {
    val user: String

    data class Password(override val user: String, val password: String) : ImapAuth

    /** An OAuth 2.0 bearer token, sent as SASL XOAUTH2 (Gmail, Microsoft) or OAUTHBEARER (RFC 7628). */
    data class OAuth(override val user: String, val accessToken: String) : ImapAuth
}

/** A failed command: the server answered NO or BAD. [code] carries e.g. TRYCREATE, ALREADYEXISTS. */
class ImapCommandException(val command: String, val status: ImapResponse.Status) :
    MailException("${command.substringBefore(' ')} failed: ${status.kind} ${status.code?.name ?: ""} ${status.text}".trim())

/**
 * One IMAP4rev1 (RFC 3501) / IMAP4rev2 (RFC 9051) connection.
 *
 * Covers what a client that wants to be *better* than desktop webmail needs: CONDSTORE and QRESYNC
 * for cheap resync, UIDPLUS and MOVE for exact, undoable moves, ESEARCH for compact result sets on
 * huge mailboxes, SPECIAL-USE and LIST-STATUS, IDLE for push, COMPRESS=DEFLATE for mobile links,
 * UTF8=ACCEPT, LITERAL+, ID (NetEase refuses SELECT without it), and Gmail's X-GM-EXT-1 for labels,
 * thread ids and server-side Gmail search syntax.
 *
 * Every public call is a suspend function that runs its blocking socket work on [Dispatchers.IO]
 * under a mutex: one command in flight per connection, by construction.
 */
class ImapClient(
    private val endpoint: ServerEndpoint,
    private val transports: TransportFactory = TransportFactory.DEFAULT,
    private val log: ProtocolLog = ProtocolLog.NONE,
    private val clientName: String = "You've got Mail",
    private val clientVersion: String = "1.0",
) : AutoCloseable {

    private var transport: Transport? = null
    private lateinit var reader: ImapReader
    private val mutex = Mutex()
    private var tagCounter = 0

    var capabilities: Set<String> = emptySet()
        private set
    var selected: SelectedMailbox? = null
        private set
    var utf8Enabled = false
        private set
    var qresyncEnabled = false
        private set
    var condstoreEnabled = false
        private set
    var compressed = false
        private set

    /** Unsolicited mailbox changes seen during any command (EXISTS, EXPUNGE, VANISHED, FETCH). */
    var onEvent: (MailboxEvent) -> Unit = {}

    val isConnected: Boolean get() = transport != null

    fun has(cap: String): Boolean = capabilities.any { it.equals(cap, ignoreCase = true) }
    val isGmail: Boolean get() = has("X-GM-EXT-1")

    // ------------------------------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------------------------------

    suspend fun connect() = io {
        val t = transports.open(endpoint.host, endpoint.port, endpoint.security)
        transport = t
        reader = ImapReader(t.input, log)
        val greeting = reader.readResponse()
        if (greeting is ImapResponse.Status) {
            if (greeting.kind == "BYE") throw MailException("Server refused the connection: ${greeting.text}")
            greeting.code?.takeIf { it.name == "CAPABILITY" }?.let { capabilities = it.values.mapNotNull { v -> v.text }.toSet() }
        }
        if (capabilities.isEmpty()) refreshCapabilities()
        if (endpoint.security == Security.STARTTLS) {
            if (!has("STARTTLS")) throw MailException("${endpoint.host} does not offer STARTTLS; refusing to send credentials in clear")
            run("STARTTLS")
            t.startTls()
            reader = ImapReader(t.input, log)
            refreshCapabilities()
        }
    }

    /** Authenticate, then negotiate the extensions this client uses. Idempotent extras are best-effort. */
    suspend fun login(auth: ImapAuth) = io {
        val result = when (auth) {
            is ImapAuth.OAuth -> when {
                has("AUTH=XOAUTH2") -> sasl("XOAUTH2", xoauth2(auth.user, auth.accessToken))
                has("AUTH=OAUTHBEARER") -> sasl("OAUTHBEARER", oauthBearer(auth.user, auth.accessToken))
                else -> throw AuthenticationException("${endpoint.host} does not accept OAuth sign-in")
            }
            is ImapAuth.Password -> {
                if (has("LOGINDISABLED") && transport?.isSecure != true) throw AuthenticationException("Server disallows login without TLS")
                if (has("AUTH=PLAIN")) {
                    val plain = "\u0000${auth.user}\u0000${auth.password}".toByteArray(Charsets.UTF_8)
                    sasl("PLAIN", plain)
                } else {
                    runCatching { runCommand(listOf(Part.Text("LOGIN "), astring(auth.user), Part.Text(" "), astring(auth.password)), "LOGIN") }
                        .getOrElse { e ->
                            if (e is ImapCommandException) throw AuthenticationException("Sign-in rejected", e.status.text)
                            throw e
                        }
                }
            }
        }
        val code = result.status.code
        if (code?.name == "CAPABILITY") capabilities = code.values.mapNotNull { it.text }.toSet() else refreshCapabilities()
        negotiate()
    }

    private fun negotiate() {
        if (has("ID")) {
            runCatching {
                run("ID (\"name\" ${ImapQuote.quote(clientName)} \"version\" ${ImapQuote.quote(clientVersion)} \"vendor\" \"aarso\")")
            }
        }
        val enable = buildList {
            if (has("QRESYNC")) add("QRESYNC")
            if (has("CONDSTORE")) add("CONDSTORE")
            if (has("UTF8=ACCEPT")) add("UTF8=ACCEPT")
        }
        if (enable.isNotEmpty() && has("ENABLE")) {
            val r = runCatching { run("ENABLE " + enable.joinToString(" ")) }.getOrNull()
            val enabled = r?.untagged?.filterIsInstance<ImapResponse.Data>()?.filter { it.keyword == "ENABLED" }
                ?.flatMap { d -> d.values.mapNotNull { it.text?.uppercase() } }.orEmpty().toSet()
            qresyncEnabled = "QRESYNC" in enabled
            condstoreEnabled = "CONDSTORE" in enabled || qresyncEnabled
            utf8Enabled = "UTF8=ACCEPT" in enabled
        } else {
            condstoreEnabled = has("CONDSTORE")
        }
        if (has("COMPRESS=DEFLATE") && !compressed) {
            val ok = runCatching { run("COMPRESS DEFLATE") }.isSuccess
            if (ok) {
                transport!!.enableCompression()
                reader = ImapReader(transport!!.input, log)
                compressed = true
            }
        }
    }

    private fun refreshCapabilities() {
        val r = run("CAPABILITY")
        val caps = r.untagged.filterIsInstance<ImapResponse.Data>().firstOrNull { it.keyword == "CAPABILITY" }
        capabilities = caps?.values?.mapNotNull { it.text }?.toSet() ?: capabilities
    }

    private fun xoauth2(user: String, token: String): ByteArray =
        "user=$user\u0001auth=Bearer $token\u0001\u0001".toByteArray(Charsets.UTF_8)

    private fun oauthBearer(user: String, token: String): ByteArray =
        "n,a=$user,\u0001host=${endpoint.host}\u0001port=${endpoint.port}\u0001auth=Bearer $token\u0001\u0001".toByteArray(Charsets.UTF_8)

    /**
     * SASL with an initial response. On an OAuth failure Gmail and Microsoft answer the initial
     * response with a `+` carrying a base64 JSON error; the client must send an empty line to get
     * the tagged NO — otherwise the connection hangs forever.
     */
    private fun sasl(mechanism: String, initial: ByteArray): CommandResult {
        val b64 = Base64.getEncoder().encodeToString(initial)
        val tag = nextTag()
        val out = transport!!.output
        val useIr = has("SASL-IR")
        val first = if (useIr) "$tag AUTHENTICATE $mechanism $b64" else "$tag AUTHENTICATE $mechanism"
        log.line('>', "$tag AUTHENTICATE $mechanism ••••")
        out.write((first + "\r\n").toByteArray(Charsets.US_ASCII)); out.flush()
        var sentInitial = useIr
        var serverError: String? = null
        val untagged = ArrayList<ImapResponse>()
        while (true) {
            when (val resp = reader.readResponse()) {
                is ImapResponse.Continuation -> {
                    if (!sentInitial) {
                        out.write((b64 + "\r\n").toByteArray(Charsets.US_ASCII)); out.flush(); sentInitial = true
                    } else {
                        serverError = runCatching { String(Base64.getDecoder().decode(resp.text.trim())) }.getOrNull()
                        out.write("\r\n".toByteArray()); out.flush()
                    }
                }
                is ImapResponse.Status -> if (resp.tag == tag) {
                    if (!resp.isOk) throw AuthenticationException("Sign-in rejected", serverError ?: resp.text)
                    return CommandResult(resp, untagged)
                } else handleUntagged(resp, untagged)
                is ImapResponse.Data -> handleUntagged(resp, untagged)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Mailboxes
    // ------------------------------------------------------------------------------------------

    /** Every mailbox, with SPECIAL-USE attributes and — when LIST-STATUS allows — counts in one round trip. */
    suspend fun list(): List<ListedMailbox> = io {
        val withStatus = has("LIST-STATUS") && has("LIST-EXTENDED")
        val statusItems = buildString {
            append("MESSAGES UNSEEN UIDNEXT UIDVALIDITY")
            if (condstoreEnabled) append(" HIGHESTMODSEQ")
        }
        val cmd = when {
            withStatus && has("SPECIAL-USE") -> "LIST \"\" \"*\" RETURN (SPECIAL-USE STATUS ($statusItems))"
            withStatus -> "LIST \"\" \"*\" RETURN (STATUS ($statusItems))"
            else -> "LIST \"\" \"*\""
        }
        val r = run(cmd)
        val statuses = HashMap<String, Map<String, Long>>()
        r.untagged.filterIsInstance<ImapResponse.Data>().filter { it.keyword == "STATUS" }.forEach { d ->
            val name = decodeName(d.values.getOrNull(0)?.text ?: return@forEach)
            statuses[name] = pairs(d.values.getOrNull(1))
        }
        r.untagged.filterIsInstance<ImapResponse.Data>().filter { it.keyword == "LIST" || it.keyword == "XLIST" }.mapNotNull { d ->
            val attrs = d.values.getOrNull(0)?.items?.mapNotNull { it.text }?.toSet() ?: emptySet()
            val delim = d.values.getOrNull(1)?.text?.firstOrNull()
            val raw = d.values.getOrNull(2)?.text ?: return@mapNotNull null
            val name = decodeName(raw)
            ListedMailbox(name, delim, attrs, statuses[name].orEmpty())
        }
    }

    suspend fun status(mailbox: String, items: String = "MESSAGES UNSEEN UIDNEXT UIDVALIDITY"): Map<String, Long> = io {
        val all = if (condstoreEnabled && !items.contains("HIGHESTMODSEQ")) "$items HIGHESTMODSEQ" else items
        val r = runCommand(listOf(Part.Text("STATUS "), mailboxArg(mailbox), Part.Text(" ($all)")), "STATUS")
        r.untagged.filterIsInstance<ImapResponse.Data>().firstOrNull { it.keyword == "STATUS" }?.let { pairs(it.values.getOrNull(1)) }.orEmpty()
    }

    /**
     * SELECT (or EXAMINE). With QRESYNC and a known (uidValidity, modSeq) the response itself
     * carries every flag change and every expunge since — one round trip to resynchronise.
     */
    suspend fun select(mailbox: String, readOnly: Boolean = false, knownUidValidity: Long = 0, knownModSeq: Long = 0): SelectedMailbox = io {
        val verb = if (readOnly) "EXAMINE " else "SELECT "
        val suffix = when {
            qresyncEnabled && knownUidValidity > 0 && knownModSeq > 0 -> " (QRESYNC ($knownUidValidity $knownModSeq))"
            condstoreEnabled -> " (CONDSTORE)"
            else -> ""
        }
        val collected = ArrayList<FetchItem>()
        val vanished = ArrayList<Long>()
        val r = runCommand(listOf(Part.Text(verb), mailboxArg(mailbox), Part.Text(suffix)), verb.trim()) { resp ->
            if (resp is ImapResponse.Data && resp.keyword == "FETCH") collected += FetchItem.from(resp.number ?: 0, resp.values)
            if (resp is ImapResponse.Data && resp.keyword == "VANISHED") vanished += parseVanished(resp).first
        }
        var exists = 0L
        var uidValidity = 0L
        var uidNext = 0L
        var modSeq = 0L
        var noModSeq = false
        var flags = emptyList<String>()
        var permanent = emptyList<String>()
        for (u in r.untagged) {
            when (u) {
                is ImapResponse.Data -> when (u.keyword) {
                    "EXISTS" -> exists = u.number ?: 0
                    "FLAGS" -> flags = u.values.firstOrNull()?.items?.mapNotNull { it.text }.orEmpty()
                }
                is ImapResponse.Status -> when (u.code?.name) {
                    "UIDVALIDITY" -> uidValidity = u.code.arg(0)?.toLongOrNull() ?: 0
                    "UIDNEXT" -> uidNext = u.code.arg(0)?.toLongOrNull() ?: 0
                    "HIGHESTMODSEQ" -> modSeq = u.code.arg(0)?.toLongOrNull() ?: 0
                    "NOMODSEQ" -> noModSeq = true
                    "PERMANENTFLAGS" -> permanent = u.code.values.firstOrNull()?.items?.mapNotNull { it.text }.orEmpty()
                }
                else -> {}
            }
        }
        val ro = r.status.code?.name == "READ-ONLY" || readOnly
        SelectedMailbox(mailbox, exists, uidValidity, uidNext, modSeq, flags, permanent, ro, noModSeq, vanished, collected).also { selected = it }
    }

    suspend fun create(name: String) = io { runCommand(listOf(Part.Text("CREATE "), mailboxArg(name)), "CREATE"); Unit }
    suspend fun delete(name: String) = io { runCommand(listOf(Part.Text("DELETE "), mailboxArg(name)), "DELETE"); Unit }
    suspend fun rename(from: String, to: String) = io {
        runCommand(listOf(Part.Text("RENAME "), mailboxArg(from), Part.Text(" "), mailboxArg(to)), "RENAME"); Unit
    }
    suspend fun subscribe(name: String) = io { runCommand(listOf(Part.Text("SUBSCRIBE "), mailboxArg(name)), "SUBSCRIBE"); Unit }

    // ------------------------------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------------------------------

    /**
     * UID FETCH. [items] is the parenthesised item list ("UID FLAGS ENVELOPE …"). With [changedSince]
     * only messages whose MODSEQ moved are returned, and with QRESYNC [vanished] also reports
     * expunges in the range as a VANISHED (EARLIER) response delivered to [onVanished].
     */
    suspend fun uidFetch(
        uidSet: String,
        items: String,
        changedSince: Long = 0,
        vanished: Boolean = false,
        onVanished: (List<Long>) -> Unit = {},
    ): List<FetchItem> = io {
        val modifiers = buildList {
            if (changedSince > 0 && condstoreEnabled) add("CHANGEDSINCE $changedSince")
            if (vanished && qresyncEnabled && changedSince > 0) add("VANISHED")
        }
        val cmd = "UID FETCH $uidSet ($items)" + if (modifiers.isEmpty()) "" else " (" + modifiers.joinToString(" ") + ")"
        val out = ArrayList<FetchItem>()
        run(cmd) { resp ->
            if (resp is ImapResponse.Data && resp.keyword == "FETCH") {
                val item = FetchItem.from(resp.number ?: 0, resp.values)
                if (item.uid != null) out += item
            }
            if (resp is ImapResponse.Data && resp.keyword == "VANISHED") onVanished(parseVanished(resp).first)
        }
        out
    }

    /**
     * UID SEARCH, returning matching UIDs. [criteria] is raw IMAP search syntax; use [searchString]
     * to embed user text. ESEARCH (RFC 4731) is used when present so a 200,000-message ALL comes
     * back as a few ranges instead of a million bytes.
     */
    suspend fun uidSearch(criteria: List<Part>): List<Long> = io {
        val esearch = has("ESEARCH")
        val needsCharset = !utf8Enabled && criteria.any { it is Part.Literal || (it is Part.Text && it.text.any { c -> c.code > 0x7e }) }
        val head = buildString {
            append("UID SEARCH ")
            if (esearch) append("RETURN (ALL) ")
            if (needsCharset) append("CHARSET UTF-8 ")
        }
        val r = runCommand(listOf(Part.Text(head)) + criteria, "UID SEARCH")
        val uids = ArrayList<Long>()
        for (d in r.untagged.filterIsInstance<ImapResponse.Data>()) {
            when (d.keyword) {
                "SEARCH" -> d.values.mapNotNullTo(uids) { it.long }
                "ESEARCH" -> {
                    val i = d.values.indexOfFirst { it.text.equals("ALL", true) }
                    if (i >= 0) d.values.getOrNull(i + 1)?.text?.let { uids += SequenceSet.parse(it) }
                }
            }
        }
        uids
    }

    suspend fun uidSearch(criteria: String): List<Long> = uidSearch(listOf(Part.Text(criteria)))

    /** A user-supplied string inside search criteria: quoted when safe, a UTF-8 literal otherwise. */
    fun searchString(s: String): Part = astring(s)

    /** Gmail's own search grammar, evaluated server-side over the whole account (X-GM-RAW). */
    suspend fun gmailSearch(query: String): List<Long> {
        if (!isGmail) return emptyList()
        return uidSearch(listOf(Part.Text("X-GM-RAW "), astring(query)))
    }

    /** UID STORE ±FLAGS.SILENT. With [unchangedSince] the server refuses to clobber a newer change. */
    suspend fun uidStoreFlags(uidSet: String, add: Boolean, flags: List<String>, unchangedSince: Long = 0) = io {
        if (flags.isEmpty()) return@io Unit
        val cond = if (unchangedSince > 0 && condstoreEnabled) "(UNCHANGEDSINCE $unchangedSince) " else ""
        run("UID STORE $uidSet $cond${if (add) "+" else "-"}FLAGS.SILENT (${flags.joinToString(" ")})")
        Unit
    }

    /** Gmail labels: add or remove, by name. `\\Inbox`, `\\Important`, `\\Starred` are system labels. */
    suspend fun uidStoreGmailLabels(uidSet: String, add: Boolean, labels: List<String>) = io {
        if (labels.isEmpty() || !isGmail) return@io Unit
        val parts = ArrayList<Part>()
        parts += Part.Text("UID STORE $uidSet ${if (add) "+" else "-"}X-GM-LABELS.SILENT (")
        labels.forEachIndexed { i, l ->
            if (i > 0) parts += Part.Text(" ")
            parts += if (l.startsWith("\\")) Part.Text(l) else astring(if (utf8Enabled) l else ModifiedUtf7.encode(l))
        }
        parts += Part.Text(")")
        runCommand(parts, "UID STORE")
        Unit
    }

    suspend fun uidCopy(uidSet: String, destination: String): CopyUid? = io {
        val r = runCommand(listOf(Part.Text("UID COPY $uidSet "), mailboxArg(destination)), "UID COPY")
        r.status.code?.takeIf { it.name == "COPYUID" }?.let { copyUid(it) }
    }

    /**
     * Move with the best primitive the server has: MOVE (RFC 6851); else COPY, flag \Deleted and
     * UID EXPUNGE (UIDPLUS) so only these messages are expunged; else, as a last resort, a plain
     * EXPUNGE — which also removes anything else already marked \Deleted, as every client does.
     */
    suspend fun uidMove(uidSet: String, destination: String): CopyUid? = io {
        if (has("MOVE")) {
            var copy: CopyUid? = null
            val r = runCommand(listOf(Part.Text("UID MOVE $uidSet "), mailboxArg(destination)), "UID MOVE") { resp ->
                if (resp is ImapResponse.Status && resp.code?.name == "COPYUID") copy = copyUid(resp.code)
            }
            return@io copy ?: r.status.code?.takeIf { it.name == "COPYUID" }?.let { copyUid(it) }
        }
        val r = runCommand(listOf(Part.Text("UID COPY $uidSet "), mailboxArg(destination)), "UID COPY")
        run("UID STORE $uidSet +FLAGS.SILENT (\\Deleted)")
        if (has("UIDPLUS")) run("UID EXPUNGE $uidSet") else run("EXPUNGE")
        r.status.code?.takeIf { it.name == "COPYUID" }?.let { copyUid(it) }
    }

    suspend fun uidExpunge(uidSet: String) = io {
        if (has("UIDPLUS")) run("UID EXPUNGE $uidSet") else run("EXPUNGE")
        Unit
    }

    /** APPEND a whole message (drafts, the Sent copy). Returns the new UID when UIDPLUS says so. */
    suspend fun append(mailbox: String, message: ByteArray, flags: List<String> = emptyList(), dateMillis: Long? = null): AppendUid? = io {
        val parts = ArrayList<Part>()
        parts += Part.Text("APPEND ")
        parts += mailboxArg(mailbox)
        if (flags.isNotEmpty()) parts += Part.Text(" (${flags.joinToString(" ")})")
        if (dateMillis != null) parts += Part.Text(" \"${MailDates.formatImap(dateMillis)}\"")
        parts += Part.Text(" ")
        parts += Part.Literal(message, binary = utf8Enabled && has("UTF8=ACCEPT"))
        val r = runCommand(parts, "APPEND")
        r.status.code?.takeIf { it.name == "APPENDUID" }?.let { c ->
            val v = c.arg(0)?.toLongOrNull(); val u = c.arg(1)?.toLongOrNull()
            if (v != null && u != null) AppendUid(v, u) else null
        }
    }

    suspend fun noop() = io { run("NOOP"); Unit }

    suspend fun expunge() = io { run("EXPUNGE"); Unit }

    // ------------------------------------------------------------------------------------------
    // IDLE
    // ------------------------------------------------------------------------------------------

    private val idleStop = AtomicBoolean(false)

    /** Ask a running [idle] to finish at its next poll (within a few seconds). Safe from any thread. */
    fun stopIdle() = idleStop.set(true)

    /**
     * RFC 2177 IDLE on the selected mailbox for up to [maxMillis] (servers drop idlers at ~29 min,
     * so callers re-idle every ~25). [onEvent] returns true to stop early — e.g. on EXISTS, to go and
     * fetch the new mail. Falls back to a single NOOP poll on servers without IDLE.
     */
    suspend fun idle(maxMillis: Long = 25 * 60_000L, pollMillis: Int = 10_000, onEvent: (MailboxEvent) -> Boolean) = io {
        if (!has("IDLE")) {
            run("NOOP") { resp -> toEvent(resp)?.let { onEvent(it) } }
            return@io Unit
        }
        idleStop.set(false)
        val t = transport!!
        val tag = nextTag()
        log.line('>', "$tag IDLE")
        t.output.write("$tag IDLE\r\n".toByteArray()); t.output.flush()
        // Wait for the continuation.
        while (true) {
            val resp = reader.readResponse()
            if (resp is ImapResponse.Continuation) break
            if (resp is ImapResponse.Status && resp.tag == tag) throw ImapCommandException("IDLE", resp)
            toEvent(resp)?.let { onEvent(it) }
        }
        val deadline = System.currentTimeMillis() + maxMillis
        var stop = false
        try {
            t.setReadTimeout(pollMillis)
            while (!stop && !idleStop.get() && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val resp = try { reader.readResponse() } catch (_: SocketTimeoutException) { continue }
                if (resp is ImapResponse.Status && resp.kind == "BYE") throw MailException("Server closed the session: ${resp.text}")
                val ev = toEvent(resp) ?: continue
                stop = onEvent(ev)
            }
        } finally {
            t.setReadTimeout(60_000)
            runCatching {
                log.line('>', "DONE")
                t.output.write("DONE\r\n".toByteArray()); t.output.flush()
                while (true) {
                    val resp = reader.readResponse()
                    if (resp is ImapResponse.Status && resp.tag == tag) break
                    toEvent(resp)?.let { onEvent(it) }
                }
            }
        }
        Unit
    }

    suspend fun logout() {
        runCatching { io { run("LOGOUT") } }
        close()
    }

    override fun close() {
        transport?.close()
        transport = null
        selected = null
    }

    // ------------------------------------------------------------------------------------------
    // Command plumbing
    // ------------------------------------------------------------------------------------------

    sealed interface Part {
        data class Text(val text: String) : Part

        /** Raw bytes sent as `{n}` (or `{n+}` with LITERAL+, `~{n}` for BINARY appends). */
        class Literal(val bytes: ByteArray, val binary: Boolean = false) : Part
    }

    class CommandResult(val status: ImapResponse.Status, val untagged: List<ImapResponse>)

    private fun nextTag(): String = "Y${++tagCounter}"

    private fun run(command: String, onUntagged: (ImapResponse) -> Unit = {}): CommandResult =
        runCommand(listOf(Part.Text(command)), command.substringBefore(' '), onUntagged)

    private fun runCommand(parts: List<Part>, label: String, onUntagged: (ImapResponse) -> Unit = {}): CommandResult {
        val t = transport ?: throw MailException("Not connected")
        val tag = nextTag()
        val out = t.output
        val literalPlus = has("LITERAL+")
        val literalMinus = has("LITERAL-")
        val untagged = ArrayList<ImapResponse>()
        val logLine = StringBuilder("$tag ")
        out.write("$tag ".toByteArray(Charsets.US_ASCII))
        for (p in parts) {
            when (p) {
                is Part.Text -> {
                    out.write(p.text.toByteArray(Charsets.UTF_8))
                    logLine.append(if (label == "LOGIN") "••••" else p.text)
                }
                is Part.Literal -> {
                    val nonSync = literalPlus || (literalMinus && p.bytes.size <= 4096)
                    val prefix = (if (p.binary) "~" else "") + "{${p.bytes.size}${if (nonSync) "+" else ""}}"
                    out.write("$prefix\r\n".toByteArray(Charsets.US_ASCII))
                    logLine.append(prefix)
                    if (!nonSync) {
                        out.flush()
                        // Wait for the go-ahead; a tagged NO here means the server refused the literal.
                        while (true) {
                            val resp = reader.readResponse()
                            if (resp is ImapResponse.Continuation) break
                            if (resp is ImapResponse.Status && resp.tag == tag) {
                                log.line('>', logLine.toString())
                                throw ImapCommandException(label, resp)
                            }
                            handleUntagged(resp, untagged); onUntagged(resp)
                        }
                    }
                    out.write(p.bytes)
                }
            }
        }
        out.write("\r\n".toByteArray()); out.flush()
        log.line('>', logLine.toString().take(500))
        while (true) {
            val resp = reader.readResponse()
            if (resp is ImapResponse.Status && resp.tag == tag) {
                if (!resp.isOk) throw ImapCommandException(label, resp)
                resp.code?.takeIf { it.name == "CAPABILITY" }?.let { c -> capabilities = c.values.mapNotNull { it.text }.toSet() }
                return CommandResult(resp, untagged)
            }
            if (resp is ImapResponse.Continuation) continue
            handleUntagged(resp, untagged)
            onUntagged(resp)
        }
    }

    private fun handleUntagged(resp: ImapResponse, sink: MutableList<ImapResponse>) {
        sink += resp
        if (resp is ImapResponse.Status && resp.kind == "BYE") {
            onEvent(MailboxEvent.Bye(resp.text))
            return
        }
        if (resp is ImapResponse.Data && resp.keyword == "CAPABILITY") {
            capabilities = resp.values.mapNotNull { it.text }.toSet()
        }
        toEvent(resp)?.let(onEvent)
    }

    private fun toEvent(resp: ImapResponse): MailboxEvent? = when {
        resp is ImapResponse.Data && resp.keyword == "EXISTS" -> MailboxEvent.Exists(resp.number ?: 0)
        resp is ImapResponse.Data && resp.keyword == "RECENT" -> MailboxEvent.Recent(resp.number ?: 0)
        resp is ImapResponse.Data && resp.keyword == "EXPUNGE" -> MailboxEvent.Expunged(resp.number ?: 0)
        resp is ImapResponse.Data && resp.keyword == "FETCH" -> MailboxEvent.FlagsChanged(FetchItem.from(resp.number ?: 0, resp.values))
        resp is ImapResponse.Data && resp.keyword == "VANISHED" -> parseVanished(resp).let { (uids, earlier) -> MailboxEvent.Vanished(uids, earlier) }
        resp is ImapResponse.Status && resp.kind == "BYE" -> MailboxEvent.Bye(resp.text)
        else -> null
    }

    private fun parseVanished(d: ImapResponse.Data): Pair<List<Long>, Boolean> {
        val earlier = d.values.firstOrNull()?.items?.any { it.text.equals("EARLIER", true) } == true
        val set = d.values.lastOrNull()?.text ?: return emptyList<Long>() to earlier
        return SequenceSet.parse(set) to earlier
    }

    private fun pairs(v: ImapValue?): Map<String, Long> {
        val items = v?.items ?: return emptyMap()
        val out = HashMap<String, Long>()
        var i = 0
        while (i + 1 < items.size) {
            val k = items[i].text?.uppercase(); val value = items[i + 1].long
            if (k != null && value != null) out[k] = value
            i += 2
        }
        return out
    }

    private fun copyUid(c: ResponseCode): CopyUid? {
        val v = c.arg(0)?.toLongOrNull() ?: return null
        return CopyUid(v, SequenceSet.parse(c.arg(1) ?: ""), SequenceSet.parse(c.arg(2) ?: ""))
    }

    private fun decodeName(raw: String): String = if (utf8Enabled) raw else ModifiedUtf7.decode(raw)

    private fun mailboxArg(name: String): Part {
        val wire = if (utf8Enabled) name else ModifiedUtf7.encode(name)
        return if (name.equals("INBOX", true)) Part.Text("INBOX") else astring(wire)
    }

    private fun astring(s: String): Part =
        if (ImapQuote.needsLiteral(s, utf8Enabled)) Part.Literal(s.toByteArray(Charsets.UTF_8)) else Part.Text(ImapQuote.quote(s))

    private suspend fun <T> io(block: suspend () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }
}
