package dev.aarso.mail.smtp

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

sealed interface SmtpAuth {
    val user: String
    data class Password(override val user: String, val password: String) : SmtpAuth
    data class OAuth(override val user: String, val accessToken: String) : SmtpAuth
}

/** An SMTP reply that was not success. [permanent] (5xx) means retrying the same thing will not help. */
class SmtpException(val code: Int, val text: String) : MailException("SMTP $code: $text") {
    val permanent: Boolean get() = code in 500..599
}

data class SmtpReply(val code: Int, val lines: List<String>) {
    val text: String get() = lines.joinToString("\n")
    val ok: Boolean get() = code in 200..399
}

/**
 * RFC 5321 submission (port 587 STARTTLS or 465 implicit TLS) with the extensions that matter:
 * AUTH PLAIN / LOGIN / XOAUTH2 / OAUTHBEARER, SIZE (refuse early rather than after uploading 20 MB),
 * 8BITMIME and SMTPUTF8 (internationalised addresses), and correct dot-stuffing.
 */
class SmtpClient(
    private val endpoint: ServerEndpoint,
    private val transports: TransportFactory = TransportFactory.DEFAULT,
    private val log: ProtocolLog = ProtocolLog.NONE,
    private val heloName: String = "[127.0.0.1]",
) : AutoCloseable {

    private var transport: Transport? = null
    private val mutex = Mutex()
    var extensions: Map<String, String> = emptyMap()
        private set

    fun has(ext: String) = extensions.containsKey(ext.uppercase())
    val maxSize: Long? get() = extensions["SIZE"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    suspend fun connect() = io {
        val t = transports.open(endpoint.host, endpoint.port, endpoint.security)
        transport = t
        expect(read(), 220)
        ehlo()
        if (endpoint.security == Security.STARTTLS) {
            if (!has("STARTTLS")) throw MailException("${endpoint.host} does not offer STARTTLS; refusing to send credentials in clear")
            expect(command("STARTTLS"), 220)
            t.startTls()
            ehlo()
        }
    }

    private fun ehlo() {
        val r = command("EHLO $heloName")
        if (!r.ok) {
            expect(command("HELO $heloName"), 250)
            extensions = emptyMap()
            return
        }
        extensions = r.lines.drop(1).associate { line ->
            val key = line.substringBefore(' ').uppercase()
            key to line.substringAfter(' ', "")
        }
    }

    suspend fun authenticate(auth: SmtpAuth) = io {
        val mechanisms = extensions["AUTH"]?.uppercase()?.split(' ')?.toSet().orEmpty()
        val reply = when (auth) {
            is SmtpAuth.OAuth -> when {
                "XOAUTH2" in mechanisms || mechanisms.isEmpty() ->
                    saslInitial("XOAUTH2", "user=${auth.user}\u0001auth=Bearer ${auth.accessToken}\u0001\u0001")
                "OAUTHBEARER" in mechanisms ->
                    saslInitial("OAUTHBEARER", "n,a=${auth.user},\u0001host=${endpoint.host}\u0001port=${endpoint.port}\u0001auth=Bearer ${auth.accessToken}\u0001\u0001")
                else -> throw AuthenticationException("${endpoint.host} does not accept OAuth for sending")
            }
            is SmtpAuth.Password -> when {
                "PLAIN" in mechanisms || mechanisms.isEmpty() -> saslInitial("PLAIN", "\u0000${auth.user}\u0000${auth.password}")
                "LOGIN" in mechanisms -> {
                    val r1 = command("AUTH LOGIN")
                    if (r1.code != 334) throw SmtpException(r1.code, r1.text)
                    val r2 = command(b64(auth.user), secret = true)
                    if (r2.code != 334) throw SmtpException(r2.code, r2.text)
                    command(b64(auth.password), secret = true)
                }
                else -> throw AuthenticationException("No password mechanism offered by ${endpoint.host}")
            }
        }
        if (reply.code != 235) throw AuthenticationException("Sending server rejected sign-in", reply.text)
    }

    private fun saslInitial(mech: String, payload: String): SmtpReply {
        var r = command("AUTH $mech ${b64(payload)}", secret = true)
        if (r.code == 334) {
            // An error challenge (base64 JSON from Gmail/Microsoft); acknowledge to get the final reply.
            r = command("", secret = true)
        }
        return r
    }

    /**
     * Send one message. [message] is the RFC 5322 bytes from MimeBuilder (no Bcc header). Recipients
     * are the envelope — To, Cc and Bcc — and partial rejection fails the whole send, because a
     * message that silently skipped one recipient is worse than one that visibly failed.
     */
    suspend fun send(from: String, recipients: List<String>, message: ByteArray) = io {
        require(recipients.isNotEmpty()) { "No recipients" }
        val limit = maxSize
        if (limit != null && message.size > limit) {
            throw SmtpException(552, "Message is ${message.size / 1024} KB; ${endpoint.host} accepts at most ${limit / 1024} KB")
        }
        val needsUtf8 = (listOf(from) + recipients).any { a -> a.any { it.code > 0x7e } }
        if (needsUtf8 && !has("SMTPUTF8")) throw SmtpException(553, "${endpoint.host} cannot send to internationalised addresses")
        val eightBit = message.any { it < 0 }
        val params = buildString {
            if (has("SIZE")) append(" SIZE=").append(message.size)
            if (eightBit && has("8BITMIME")) append(" BODY=8BITMIME")
            if (needsUtf8) append(" SMTPUTF8")
        }
        expect(command("MAIL FROM:<$from>$params"), 250)
        for (rcpt in recipients) {
            val r = command("RCPT TO:<$rcpt>")
            if (r.code !in setOf(250, 251)) {
                runCatching { command("RSET") }
                throw SmtpException(r.code, "Recipient <$rcpt> refused: ${r.text}")
            }
        }
        expect(command("DATA"), 354)
        val out = transport!!.output
        out.write(dotStuff(message))
        out.write(".\r\n".toByteArray())
        out.flush()
        log.line('>', ".  (${message.size} bytes)")
        expect(read(), 250)
    }

    suspend fun quit() {
        runCatching { io { command("QUIT") } }
        close()
    }

    override fun close() {
        transport?.close()
        transport = null
    }

    // ------------------------------------------------------------------------------------------

    private fun command(line: String, secret: Boolean = false): SmtpReply {
        val t = transport ?: throw MailException("Not connected")
        log.line('>', if (secret) line.substringBefore(' ').let { if (it == line) "••••" else "$it ••••" } else line)
        t.output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        t.output.flush()
        return read()
    }

    private fun read(): SmtpReply {
        val t = transport ?: throw MailException("Not connected")
        val lines = ArrayList<String>()
        var code: Int
        while (true) {
            val line = LineReader.readLine(t.input) ?: throw MailException("Connection closed by ${endpoint.host}")
            log.line('<', line)
            code = line.take(3).toIntOrNull() ?: throw MailException("Malformed SMTP reply: $line")
            lines += line.drop(4)
            if (line.length < 4 || line[3] != '-') break
        }
        return SmtpReply(code, lines)
    }

    private fun expect(r: SmtpReply, code: Int) {
        if (r.code != code) throw SmtpException(r.code, r.text)
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private suspend fun <T> io(block: suspend () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    companion object {
        /** CRLF-normalise and dot-stuff (RFC 5321 §4.5.2). Always ends with CRLF. */
        fun dotStuff(message: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(message.size + 64)
            var atLineStart = true
            var i = 0
            while (i < message.size) {
                val b = message[i]
                if (atLineStart && b == '.'.code.toByte()) out.write('.'.code)
                when (b) {
                    '\r'.code.toByte() -> {
                        out.write(13); out.write(10)
                        if (i + 1 < message.size && message[i + 1] == '\n'.code.toByte()) i++
                        atLineStart = true
                    }
                    '\n'.code.toByte() -> { out.write(13); out.write(10); atLineStart = true }
                    else -> { out.write(b.toInt()); atLineStart = false }
                }
                i++
            }
            if (!atLineStart) { out.write(13); out.write(10) }
            return out.toByteArray()
        }
    }
}
