package dev.aarso.mail

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A one-connection line-oriented server on localhost for protocol tests. The handler sees every line
 * the client sends and answers through [Session]; everything the client sent is kept in [received].
 */
class ScriptedServer(private val handler: Session.(String) -> Unit, private val greeting: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val received: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile var error: Throwable? = null

    class Session(private val input: BufferedInputStream, private val output: OutputStream) {
        var closed = false
        fun send(line: String) {
            output.write((line + "\r\n").toByteArray(Charsets.UTF_8)); output.flush()
        }
        fun sendRaw(bytes: ByteArray) {
            output.write(bytes); output.flush()
        }
        fun readLine(): String? {
            val sb = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.size() == 0) null else sb.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.write(b)
            }
            return sb.toString(Charsets.UTF_8.name())
        }
        fun readBytes(n: Int): ByteArray {
            val buf = ByteArray(n); var off = 0
            while (off < n) { val r = input.read(buf, off, n - off); if (r < 0) break; off += r }
            return buf
        }
        fun close() { closed = true }
    }

    private val worker = thread(isDaemon = true) {
        try {
            server.accept().use { s ->
                val session = Session(BufferedInputStream(s.getInputStream()), s.getOutputStream())
                session.send(greeting)
                while (!session.closed) {
                    val line = session.readLine() ?: break
                    received += line
                    session.handler(line)
                }
            }
        } catch (t: Throwable) {
            if (!server.isClosed) error = t
        }
    }

    override fun close() {
        server.close()
        worker.join(2000)
    }
}
