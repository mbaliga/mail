package dev.aarso.mail.smtp

import dev.aarso.mail.ScriptedServer
import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class SmtpTest {

    @Test fun `dot stuffing and crlf normalisation`() {
        val out = String(SmtpClient.dotStuff("a\n.b\r\n..c\rd".toByteArray()))
        assertEquals("a\r\n..b\r\n...c\r\nd\r\n", out)
    }

    @Test fun `auth plain then send with envelope and size`() = runBlocking {
        val data = StringBuilder()
        var inData = false
        val server = ScriptedServer(greeting = "220 fake ESMTP", handler = { line ->
            if (inData) {
                if (line == ".") { inData = false; send("250 2.0.0 queued as X") } else data.append(line).append('\n')
                return@ScriptedServer
            }
            when {
                line.startsWith("EHLO") -> { send("250-fake"); send("250-SIZE 1000000"); send("250-8BITMIME"); send("250 AUTH PLAIN LOGIN") }
                line.startsWith("AUTH PLAIN ") -> {
                    val creds = String(Base64.getDecoder().decode(line.substringAfterLast(' ')))
                    send(if (creds == "\u0000me@x.org\u0000pw") "235 ok" else "535 no")
                }
                line.startsWith("MAIL FROM:<me@x.org> SIZE=") -> send("250 ok")
                line.startsWith("RCPT TO:<") -> send("250 ok")
                line == "DATA" -> { inData = true; send("354 go") }
                line == "QUIT" -> { send("221 bye"); close() }
                else -> send("500 what")
            }
        })
        server.use {
            val c = SmtpClient(ServerEndpoint("127.0.0.1", server.port, Security.NONE, "me@x.org"))
            c.connect()
            assertEquals(1_000_000L, c.maxSize)
            c.authenticate(SmtpAuth.Password("me@x.org", "pw"))
            c.send("me@x.org", listOf("a@y.com", "b@z.com"), "Subject: hi\r\n\r\n.dot line\r\nbody\r\n".toByteArray())
            c.quit()
            assertNull(server.error)
            assertTrue(data.toString().contains("..dot line"))
            assertEquals(2, server.received.count { it.startsWith("RCPT TO") })
        }
    }

    @Test fun `refused recipient fails the whole send`() = runBlocking {
        val server = ScriptedServer(greeting = "220 fake", handler = { line ->
            when {
                line.startsWith("EHLO") -> send("250 fake")
                line.startsWith("MAIL FROM") -> send("250 ok")
                line == "RCPT TO:<bad@y.com>" -> send("550 5.1.1 no such user")
                line.startsWith("RCPT") -> send("250 ok")
                line == "RSET" -> send("250 ok")
                else -> send("500 ?")
            }
        })
        server.use {
            val c = SmtpClient(ServerEndpoint("127.0.0.1", server.port, Security.NONE, "u"))
            c.connect()
            try {
                c.send("me@x.org", listOf("ok@y.com", "bad@y.com"), "x".toByteArray())
                fail("expected refusal")
            } catch (e: SmtpException) {
                assertEquals(550, e.code)
                assertTrue(e.permanent)
            }
            c.close()
        }
    }
}
