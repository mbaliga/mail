package dev.aarso.mail.pop3

import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.net.LineReader
import dev.aarso.mail.net.MailException
import dev.aarso.mail.net.ProtocolLog
import dev.aarso.mail.net.Transport
import dev.aarso.mail.net.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * POP3 (RFC 1939) with STLS (RFC 2595), UIDL, TOP and SASL (PLAIN, XOAUTH2) — for the few providers
 * that still offer nothing else. POP has one inbox and no flags, so the sync layer treats it as a
 * read-mostly download queue and keeps read/flag state locally.
 */
class Pop3Client(
    private val endpoint: ServerEndpoint,
    private val transports: TransportFactory = TransportFactory.DEFAULT,
    private val log: ProtocolLog = ProtocolLog.NONE,
) : AutoCloseable {

    private var transport: Transport? = null
    private val mutex = Mutex()
    var capabilities: Set<String> = emptySet()
        private set

    suspend fun connect() = io {
        val t = transports.open(endpoint.host, endpoint.port, endpoint.security)
        transport = t
        status(readLine())
        capabilities = runCatching { multiline("CAPA").map { it.uppercase() }.toSet() }.getOrDefault(emptySet())
        if (endpoint.security == Security.STARTTLS) {
            if ("STLS" !in capabilities) throw MailException("${endpoint.host} does not offer STLS")
            status(command("STLS"))
            t.startTls()
            capabilities = runCatching { multiline("CAPA").map { it.uppercase() }.toSet() }.getOrDefault(capabilities)
        }
    }

    suspend fun login(user: String, password: String) = io {
        val sasl = capabilities.firstOrNull { it.startsWith("SASL") }.orEmpty()
        val ok = if (sasl.contains("PLAIN")) {
            command("AUTH PLAIN " + b64("\u0000$user\u0000$password"), secret = true)
        } else {
            val u = command("USER $user")
            if (!u.startsWith("+OK")) u else command("PASS $password", secret = true)
        }
        if (!ok.startsWith("+OK")) throw AuthenticationException("Sign-in rejected", ok)
    }

    suspend fun loginOAuth(user: String, token: String) = io {
        val r = command("AUTH XOAUTH2 " + b64("user=$user\u0001auth=Bearer $token\u0001\u0001"), secret = true)
        val final = if (r.startsWith("+ ")) command("", secret = true) else r
        if (!final.startsWith("+OK")) throw AuthenticationException("Sign-in rejected", final)
    }

    /** message number → unique id. The UIDL is the only stable identity POP3 offers. */
    suspend fun uidl(): Map<Int, String> = io {
        multiline("UIDL").mapNotNull { line ->
            val (n, id) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            n.toIntOrNull()?.let { it to id.trim() }
        }.toMap()
    }

    suspend fun sizes(): Map<Int, Long> = io {
        multiline("LIST").mapNotNull { line ->
            val (n, size) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            n.toIntOrNull()?.let { it to (size.trim().toLongOrNull() ?: 0) }
        }.toMap()
    }

    /** Headers plus the first [lines] body lines — enough for list, preview and categorisation. */
    suspend fun top(number: Int, lines: Int = 40): ByteArray = io { multilineBytes("TOP $number $lines") }

    suspend fun retrieve(number: Int): ByteArray = io { multilineBytes("RETR $number") }

    suspend fun delete(number: Int) = io { status(command("DELE $number")); Unit }

    suspend fun quit() {
        runCatching { io { command("QUIT") } }
        close()
    }

    override fun close() {
        transport?.close(); transport = null
    }

    // ------------------------------------------------------------------------------------------

    private fun command(line: String, secret: Boolean = false): String {
        val t = transport ?: throw MailException("Not connected")
        log.line('>', if (secret) line.substringBefore(' ') + " ••••" else line)
        t.output.write((line + "\r\n").toByteArray(Charsets.UTF_8)); t.output.flush()
        return readLine()
    }

    private fun readLine(): String {
        val line = LineReader.readLine(transport!!.input) ?: throw MailException("Connection closed")
        log.line('<', line)
        return line
    }

    private fun status(line: String): String {
        if (!line.startsWith("+OK")) throw MailException("POP3: $line")
        return line
    }

    private fun multiline(cmd: String): List<String> =
        String(multilineBytes(cmd), Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }

    /** Reads a dot-terminated response, un-stuffing leading dots. */
    private fun multilineBytes(cmd: String): ByteArray {
        status(command(cmd))
        val out = ByteArrayOutputStream()
        val input = transport!!.input
        val line = ByteArrayOutputStream()
        while (true) {
            line.reset()
            while (true) {
                val b = input.read()
                if (b < 0) throw MailException("Connection closed during $cmd")
                if (b == '\n'.code) break
                line.write(b)
            }
            var bytes = line.toByteArray()
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
            if (bytes.size == 1 && bytes[0] == '.'.code.toByte()) break
            if (bytes.isNotEmpty() && bytes[0] == '.'.code.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
            out.write(bytes); out.write(13); out.write(10)
        }
        return out.toByteArray()
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private suspend fun <T> io(block: suspend () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }
}
