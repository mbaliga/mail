package dev.aarso.mail.sieve

import dev.aarso.mail.model.Security
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.net.MailException
import dev.aarso.mail.net.ProtocolLog
import dev.aarso.mail.net.Transport
import dev.aarso.mail.net.TransportFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/** The server said NO (or BYE): [text] is its own explanation, for example the line and column of a script error. */
class ManageSieveException(message: String, val text: String = message) : MailException(message)

/**
 * A ManageSieve client (RFC 5804): the protocol a Dovecot/Pigeonhole-style mail server uses to let a client upload and
 * switch on the person's Sieve script.
 *
 * Where it exists — what is *usually* offered, not what is guaranteed; the app finds out by asking the server, never by assuming:
 *
 * | Provider            | ManageSieve                                        | In this app |
 * |---------------------|----------------------------------------------------|-------------|
 * | mailbox.org         | yes, imap.mailbox.org:4190, STARTTLS               | used |
 * | Posteo              | yes, posteo.de:4190, STARTTLS                      | used |
 * | Custom Dovecot      | usually, on the IMAP host, port 4190, STARTTLS     | tried on the IMAP host |
 * | Fastmail            | scripts are edited over JMAP (RFC 9661); not verified to also offer ManageSieve | JMAP Sieve (see [dev.aarso.mail.jmap.JmapClient.sieveScripts]) |
 * | iCloud, Gmail, Outlook, Microsoft 365, Yahoo, AOL, Proton, Zoho, GMX, … | no server-side Sieve | rules stay on the phone |
 *
 * Sign-in is PLAIN over a TLS-protected connection (STARTTLS is required; a server that does not offer it is refused, never used in the
 * clear). Accounts that sign in with OAuth are not supported here.
 */
class ManageSieveClient(private val transport: Transport, private val log: ProtocolLog = ProtocolLog.NONE) : AutoCloseable {

    data class Capabilities(
        val implementation: String?,
        /** The Sieve extensions the server supports, lower-case. */
        val sieve: Set<String>,
        val startTls: Boolean,
        val sasl: Set<String>,
        val version: String?,
        val raw: Map<String, String>,
    )

    data class Script(val name: String, val active: Boolean)

    private val input: InputStream get() = transport.input
    var capabilities: Capabilities = Capabilities(null, emptySet(), false, emptySet(), null, emptyMap())
        private set

    // ---- session ----

    /** Read the server's greeting (its capabilities, ended by OK). Call once after connecting. */
    fun greeting(): Capabilities = readCapabilities().also { capabilities = it }

    /** Upgrade to TLS; refused if the server does not offer it. Capabilities are read again afterwards, as the RFC requires. */
    fun startTls(): Capabilities {
        if (!capabilities.startTls) throw ManageSieveException("The server doesn't offer a secure connection (STARTTLS) for Sieve, so nothing was sent")
        send("STARTTLS")
        expectOk()
        transport.startTls()
        return readCapabilities().also { capabilities = it }
    }

    fun authenticatePlain(user: String, password: String) {
        if ("PLAIN" !in capabilities.sasl) throw ManageSieveException("The server doesn't accept a password sign-in for Sieve")
        val token = Base64.getEncoder().encodeToString("\u0000$user\u0000$password".toByteArray(Charsets.UTF_8))
        send("AUTHENTICATE \"PLAIN\" ${quote(token)}", secret = true)
        val r = readResponse()
        when (r.status) {
            "OK" -> Unit
            else -> throw AuthenticationException("The Sieve server rejected the sign-in", r.text)
        }
        // Some servers repeat their capabilities after authentication, as a response code on the OK.
    }

    fun listScripts(): List<Script> {
        send("LISTSCRIPTS")
        val out = ArrayList<Script>()
        while (true) {
            val line = readLine()
            val t = tokens(line)
            if (isStatus(t)) { check(Response(t)); break }
            out += Script(t[0].text, t.drop(1).any { it.text.equals("ACTIVE", true) })
        }
        return out
    }

    fun getScript(name: String): String {
        send("GETSCRIPT ${quote(name)}")
        val first = tokens(readLine())
        val content = first.firstOrNull()?.text ?: throw ManageSieveException("The server sent no script")
        expectOk()
        return content
    }

    /** Upload (create or replace) a script. A script the server cannot parse comes back as a [ManageSieveException] with its message. */
    fun putScript(name: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        send("PUTSCRIPT ${quote(name)} {${bytes.size}+}")
        transport.output.write(bytes); transport.output.write(CRLF); transport.output.flush()
        log.line('C', "<${bytes.size} bytes of script>")
        expectOk()
    }

    /** Ask the server whether [content] is a valid script, without storing it. */
    fun checkScript(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        send("CHECKSCRIPT {${bytes.size}+}")
        transport.output.write(bytes); transport.output.write(CRLF); transport.output.flush()
        expectOk()
    }

    /** Make [name] the active script (the one the server runs). An empty name switches all scripts off. */
    fun setActive(name: String) { send("SETACTIVE ${quote(name)}"); expectOk() }

    fun deleteScript(name: String) { send("DELETESCRIPT ${quote(name)}"); expectOk() }

    override fun close() {
        runCatching { send("LOGOUT"); readResponse() }
        runCatching { transport.close() }
    }

    // ---- wire ----

    private fun send(command: String, secret: Boolean = false) {
        log.line('C', if (secret) command.substringBefore(' ') + " …" else command)
        transport.output.write(command.toByteArray(Charsets.UTF_8)); transport.output.write(CRLF); transport.output.flush()
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private class Tok(val text: String, val literal: Boolean = false, val quoted: Boolean = false)

    private class Response(val tokens: List<Tok>) {
        val status: String = tokens[0].text.uppercase()
        val text: String get() = tokens.drop(1).lastOrNull { it.literal || it.text.isNotEmpty() && !it.text.startsWith("(") }?.text.orEmpty()
    }

    /** A status line starts with a bare OK, NO or BYE — a *quoted* "OK" is a script that happens to be called that. */
    private fun isStatus(t: List<Tok>) = t.isNotEmpty() && !t[0].literal && !t[0].quoted && t[0].text.uppercase() in setOf("OK", "NO", "BYE")

    private fun check(r: Response): Response {
        if (r.status != "OK") throw ManageSieveException("The Sieve server said ${r.status}: ${r.text.ifBlank { "(no explanation)" }}", r.text)
        return r
    }

    private fun readResponse(): Response {
        while (true) {
            val t = tokens(readLine())
            if (isStatus(t)) return Response(t)
        }
    }

    private fun expectOk() { check(readResponse()) }

    private fun readCapabilities(): Capabilities {
        val kv = LinkedHashMap<String, String>()
        while (true) {
            val t = tokens(readLine())
            if (isStatus(t)) { check(Response(t)); break }
            if (t.isNotEmpty()) kv[t[0].text.uppercase()] = t.getOrNull(1)?.text.orEmpty()
        }
        return Capabilities(
            implementation = kv["IMPLEMENTATION"],
            sieve = kv["SIEVE"].orEmpty().split(' ').filter { it.isNotBlank() }.map { it.lowercase() }.toSet(),
            startTls = "STARTTLS" in kv,
            sasl = kv["SASL"].orEmpty().split(' ').filter { it.isNotBlank() }.map { it.uppercase() }.toSet(),
            version = kv["VERSION"],
            raw = kv,
        )
    }

    /** One logical response line, with any `{n}` literal's bytes read in place of the literal marker. Returns the line text with literals spliced as quoted tokens. */
    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val line = readRaw() ?: throw ManageSieveException("The Sieve server closed the connection")
            log.line('S', line)
            val m = Regex("\\{(\\d+)\\+?}$").find(line)
            if (m == null) { sb.append(line); return sb.toString() }
            val n = m.groupValues[1].toInt()
            val bytes = ByteArray(n)
            var off = 0
            while (off < n) { val r = input.read(bytes, off, n - off); if (r < 0) throw ManageSieveException("The Sieve server closed the connection"); off += r }
            sb.append(line.substring(0, m.range.first)).append(LITERAL).append(String(bytes, Charsets.UTF_8).replace("\\", "\\\\").replace("\"", "\\\"")).append(LITERAL_END)
            // the rest of the line (usually just CRLF) follows the literal
            sb.append(readRaw() ?: "")
            return sb.toString()
        }
    }

    private fun readRaw(): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) return String(out.toByteArray(), Charsets.UTF_8).removeSuffix("\r")
            out.write(b)
        }
    }

    /** Quoted strings, atoms, parenthesised response codes (kept as one token), and spliced literals. */
    private fun tokens(line: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == ' ' -> i++
                c == '"' || c == LITERAL -> {
                    val literal = c == LITERAL
                    val end = if (literal) LITERAL_END else '"'
                    val sb = StringBuilder(); i++
                    while (i < line.length && line[i] != end) {
                        if (line[i] == '\\' && i + 1 < line.length) { sb.append(line[i + 1]); i += 2 } else { sb.append(line[i]); i++ }
                    }
                    i++
                    out += Tok(sb.toString(), literal, quoted = !literal)
                }
                c == '(' -> {
                    var depth = 0; val start = i
                    while (i < line.length) { if (line[i] == '(') depth++ else if (line[i] == ')') { depth--; if (depth == 0) { i++; break } }; i++ }
                    out += Tok(line.substring(start, i))
                }
                else -> { val start = i; while (i < line.length && line[i] != ' ') i++; out += Tok(line.substring(start, i)) }
            }
        }
        return out
    }

    companion object {
        private val CRLF = "\r\n".toByteArray()
        private const val LITERAL = '\u0001'
        private const val LITERAL_END = '\u0002'

        /** Open a session: connect, read capabilities, upgrade with STARTTLS (or use a TLS-from-the-start port), sign in. */
        fun connect(factory: TransportFactory, host: String, port: Int, user: String, password: String, security: Security = Security.STARTTLS, log: ProtocolLog = ProtocolLog.NONE): ManageSieveClient {
            val transport = factory.open(host, port, if (security == Security.SSL_TLS) Security.SSL_TLS else Security.NONE)
            val client = ManageSieveClient(transport, log)
            try {
                client.greeting()
                if (security != Security.SSL_TLS) client.startTls()
                client.authenticatePlain(user, password)
                return client
            } catch (e: Exception) { runCatching { transport.close() }; throw e }
        }
    }
}
