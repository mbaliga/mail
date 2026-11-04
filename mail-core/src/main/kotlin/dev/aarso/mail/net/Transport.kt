package dev.aarso.mail.net

import dev.aarso.mail.model.Security
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Thrown for anything that means "the server, the network, or the credentials said no". */
open class MailException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server rejected the credentials. Distinct so the UI can ask for a new password or token. */
class AuthenticationException(message: String, val serverText: String? = null) : MailException(message)

/** TLS could not be established or the certificate did not verify. Never silently downgraded. */
class CertificateException(message: String, cause: Throwable? = null) : MailException(message, cause)

/** What protocol traffic looks like in a debug log — with every credential already removed. */
fun interface ProtocolLog {
    fun line(direction: Char, text: String)

    companion object {
        val NONE = ProtocolLog { _, _ -> }
    }
}

/**
 * A byte stream to a mail server: TLS from the first byte, upgraded by STARTTLS, or (only when a
 * user insists, for a LAN bridge like Proton Bridge) plaintext.
 *
 * Kept behind an interface so protocol tests drive scripted servers over in-memory pipes and the
 * Android app can supply its own socket policy (network binding, traffic tagging).
 */
interface Transport : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    val isSecure: Boolean
    fun startTls()
    fun enableCompression()
    fun setReadTimeout(millis: Int)
    override fun close()
}

interface TransportFactory {
    fun open(host: String, port: Int, security: Security, connectTimeoutMs: Int = 20_000, readTimeoutMs: Int = 60_000): Transport

    companion object {
        val DEFAULT: TransportFactory = SocketTransportFactory()
    }
}

class SocketTransportFactory(
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : TransportFactory {
    override fun open(host: String, port: Int, security: Security, connectTimeoutMs: Int, readTimeoutMs: Int): Transport {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.soTimeout = readTimeoutMs
            raw.keepAlive = true
            raw.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { raw.close() }
            throw MailException("Could not reach $host:$port — ${e.message}", e)
        }
        val t = SocketTransport(raw, host, port, sslFactory)
        if (security == Security.SSL_TLS) t.upgrade()
        return t
    }
}

class SocketTransport(
    private var socket: Socket,
    private val host: String,
    private val port: Int,
    private val sslFactory: SSLSocketFactory,
) : Transport {

    private var inStream: InputStream = BufferedInputStream(socket.getInputStream(), 64 * 1024)
    private var outStream: OutputStream = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)

    override val input: InputStream get() = inStream
    override val output: OutputStream get() = outStream
    override val isSecure: Boolean get() = socket is SSLSocket

    internal fun upgrade() {
        try {
            val ssl = sslFactory.createSocket(socket, host, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                // Hostname verification is the platform's job when this is set — not optional.
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(host))
            }
            ssl.soTimeout = socket.soTimeout
            ssl.startHandshake()
            socket = ssl
            inStream = BufferedInputStream(ssl.inputStream, 64 * 1024)
            outStream = BufferedOutputStream(ssl.outputStream, 16 * 1024)
        } catch (e: javax.net.ssl.SSLException) {
            runCatching { socket.close() }
            throw CertificateException("Secure connection to $host failed: ${e.message}", e)
        }
    }

    override fun startTls() = upgrade()

    /**
     * RFC 4978 COMPRESS=DEFLATE: raw deflate both ways, sync-flushed so every command is sent at once.
     *
     * The inflater wraps the *existing* buffered stream, not the socket: the server starts
     * compressing the instant it sends its OK, and any compressed bytes the buffer already holds
     * would otherwise be lost and the session would desynchronise on the first read.
     */
    override fun enableCompression() {
        inStream = BufferedInputStream(InflaterInputStream(inStream, Inflater(true), 16 * 1024), 64 * 1024)
        outStream = DeflaterOutputStream(outStream, Deflater(Deflater.DEFAULT_COMPRESSION, true), 16 * 1024, true)
    }

    override fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        runCatching { outStream.flush() }
        runCatching { socket.close() }
    }
}

/** Reads CRLF- (or bare LF-) terminated lines from a byte stream without decoding the bytes. */
object LineReader {
    fun readLine(input: InputStream, max: Int = 1 shl 20): String? {
        val sb = java.io.ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            sb.write(b)
            if (sb.size() > max) throw MailException("Server sent an over-long line")
        }
        val bytes = sb.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.UTF_8)
    }
}
