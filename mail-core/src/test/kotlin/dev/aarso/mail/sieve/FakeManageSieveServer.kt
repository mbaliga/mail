package dev.aarso.mail.sieve

import dev.aarso.mail.model.Security
import dev.aarso.mail.net.Transport
import dev.aarso.mail.net.TransportFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * A small in-process ManageSieve server (RFC 5804 subset): enough of the real protocol to test the client over a socket.
 * "TLS" is simulated: STARTTLS flips the session to secure and the client's transport upgrade is a no-op.
 */
class FakeManageSieveServer(
    private val offerStartTls: Boolean = true,
    private val user: String = "me@example.org",
    private val password: String = "secret",
    private val extensions: String = "fileinto imap4flags copy",
    private val requireTls: Boolean = true,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val scripts = ConcurrentHashMap<String, String>()
    @Volatile var active: String? = null
    val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var sawSecureAuth = false

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream()); val out = s.getOutputStream()
        var secure = false; var authed = false
        fun w(text: String) { out.write((text + "\r\n").toByteArray()); out.flush() }
        fun caps() {
            w("\"IMPLEMENTATION\" \"Fake Pigeonhole\"")
            w("\"SIEVE\" \"$extensions\"")
            if (!secure && offerStartTls) w("\"STARTTLS\"")
            w("\"SASL\" \"${if (secure || !requireTls) "PLAIN" else ""}\"")
            w("\"VERSION\" \"1.0\"")
            w("OK \"ready\"")
        }
        caps()
        while (true) {
            val line = readLine(input) ?: break
            commands += line.take(40)
            val cmd = line.substringBefore(' ').uppercase()
            when (cmd) {
                "STARTTLS" -> { w("OK \"Begin TLS negotiation now.\""); secure = true; caps() }
                "AUTHENTICATE" -> {
                    if (requireTls && !secure) { w("NO \"Authentication requires a secure connection\""); continue }
                    val b64 = Regex("\"([^\"]*)\"\\s*$").find(line)?.groupValues?.get(1).orEmpty()
                    val parts = String(java.util.Base64.getDecoder().decode(b64)).split('\u0000')
                    if (parts.size == 3 && parts[1] == user && parts[2] == password) { authed = true; sawSecureAuth = secure; w("OK \"Authenticated\"") } else w("NO \"Authentication failed.\"")
                }
                else -> if (!authed) w("NO \"Authenticate first\"") else when (cmd) {
                    "LISTSCRIPTS" -> { scripts.keys.sorted().forEach { w("\"$it\"" + if (it == active) " ACTIVE" else "") }; w("OK \"Listscripts completed.\"") }
                    "GETSCRIPT" -> {
                        val name = quoted(line)
                        val body = scripts[name]
                        if (body == null) w("NO (NONEXISTENT) \"There is no script by that name\"") else { val b = body.toByteArray(); w("{${b.size}}"); out.write(b); out.write("\r\n".toByteArray()); w("OK \"Getscript completed.\"") }
                    }
                    "PUTSCRIPT", "CHECKSCRIPT" -> {
                        val name = if (cmd == "PUTSCRIPT") quoted(line) else ""
                        val n = Regex("\\{(\\d+)\\+?}$").find(line)!!.groupValues[1].toInt()
                        val bytes = ByteArray(n); var off = 0
                        while (off < n) off += input.read(bytes, off, n - off)
                        readLine(input) // the CRLF after the literal
                        val text = String(bytes)
                        if (text.contains("syntaxerror")) w("NO \"line 3: syntax error, unexpected identifier\"")
                        else { if (cmd == "PUTSCRIPT") scripts[name] = text; w("OK \"${cmd.lowercase()} completed.\"") }
                    }
                    "SETACTIVE" -> { val n = quoted(line); if (n.isEmpty() || scripts.containsKey(n)) { active = n.ifEmpty { null }; w("OK \"Setactive completed.\"") } else w("NO (NONEXISTENT) \"There is no script by that name\"") }
                    "DELETESCRIPT" -> { val n = quoted(line); if (n == active) w("NO (ACTIVE) \"You may not delete an active script\"") else { scripts.remove(n); w("OK \"Deletescript completed.\"") } }
                    "LOGOUT" -> { w("OK \"Logout completed.\""); break }
                    else -> w("NO \"Unknown command\"")
                }
            }
        }
        runCatching { s.close() }
    }

    private fun quoted(line: String) = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\").orEmpty()

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) { val c = i.read(); if (c < 0) return if (b.size() == 0) null else b.toString(); if (c == '\n'.code) return b.toString().removeSuffix("\r"); b.write(c) }
    }

    override fun close() { runCatching { server.close() } }

    /** Connects to this server; the "TLS upgrade" does nothing because the server already treats STARTTLS as the switch. */
    fun factory(): TransportFactory = object : TransportFactory {
        override fun open(host: String, port: Int, security: Security, connectTimeoutMs: Int, readTimeoutMs: Int): Transport {
            val sock = Socket("127.0.0.1", this@FakeManageSieveServer.port); sock.soTimeout = 5_000
            val ins = BufferedInputStream(sock.getInputStream()); val outs = sock.getOutputStream()
            return object : Transport {
                override val input: InputStream = ins
                override val output: OutputStream = outs
                override val isSecure = false
                override fun startTls() {}
                override fun enableCompression() {}
                override fun setReadTimeout(millis: Int) { sock.soTimeout = millis }
                override fun close() { runCatching { sock.close() } }
            }
        }
    }
}
