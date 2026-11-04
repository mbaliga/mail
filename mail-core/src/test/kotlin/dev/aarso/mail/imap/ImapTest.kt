package dev.aarso.mail.imap

import dev.aarso.mail.ScriptedServer
import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import dev.aarso.mail.net.AuthenticationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class ImapTest {

    private fun reader(s: String) = ImapReader(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)))

    @Test fun `status responses carry codes and text`() {
        val r = reader("* OK [CAPABILITY IMAP4rev1 IDLE AUTH=XOAUTH2] Gimap ready\r\nY1 NO [TRYCREATE] No such mailbox\r\n")
        val g = r.readResponse() as ImapResponse.Status
        assertNull(g.tag)
        assertEquals("CAPABILITY", g.code!!.name)
        assertEquals(listOf("IMAP4rev1", "IDLE", "AUTH=XOAUTH2"), g.code!!.values.map { it.text })
        assertEquals("Gimap ready", g.text)
        val t = r.readResponse() as ImapResponse.Status
        assertEquals("Y1", t.tag); assertEquals("NO", t.kind); assertEquals("TRYCREATE", t.code!!.name)
    }

    @Test fun `fetch with literal section and envelope and bodystructure`() {
        val header = "From: a@b.c\r\nSubject: Hi\r\n\r\n"
        val wire = "* 12 FETCH (UID 345 FLAGS (\\Seen \$Forwarded) INTERNALDATE \"03-Nov-2026 14:05:09 +0000\" RFC822.SIZE 4021 " +
            "X-GM-THRID 1780000000000000001 X-GM-LABELS (\\Inbox \"Receipts &AOk-t&AOk-\") MODSEQ (998877) " +
            "ENVELOPE (\"Tue, 3 Nov 2026 14:05:09 +0000\" \"=?UTF-8?Q?Caf=C3=A9?=\" ((\"Ren\\\"ee\" NIL \"renee\" \"example.com\")) NIL NIL " +
            "((NIL NIL \"me\" \"example.org\")) NIL NIL \"<parent@x>\" \"<abc@example.com>\") " +
            "BODYSTRUCTURE ((\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"quoted-printable\" 120 4 NIL NIL NIL)" +
            "(\"text\" \"html\" (\"charset\" \"utf-8\") NIL NIL \"base64\" 900 12 NIL NIL NIL) \"alternative\" (\"boundary\" \"b1\") NIL NIL) " +
            "BODY[HEADER.FIELDS (FROM SUBJECT)] {${header.length}}\r\n$header)\r\n"
        val d = reader(wire).readResponse() as ImapResponse.Data
        assertEquals("FETCH", d.keyword); assertEquals(12L, d.number)
        val f = FetchItem.from(d.number!!, d.values)
        assertEquals(345L, f.uid)
        assertEquals(listOf("\\Seen", "\$Forwarded"), f.flags)
        assertEquals(998877L, f.modSeq)
        assertEquals(4021L, f.size)
        assertEquals("1780000000000000001", f.gmailThreadId)
        assertEquals(listOf("\\Inbox", "Receipts été"), f.gmailLabels)
        val env = f.envelope!!
        assertEquals("Café", env.subject)
        assertEquals("renee@example.com", env.from.single().address)
        assertEquals("Ren\"ee", env.from.single().name)
        assertEquals("parent@x", env.inReplyTo)
        assertEquals("abc@example.com", env.messageId)
        val bs = f.bodyStructure!!
        assertEquals("alternative", bs.subtype)
        assertEquals(listOf("1", "2"), bs.children.map { it.partId })
        assertEquals("2", bs.textSection(preferHtml = true)!!.partId)
        assertEquals("1", bs.textSection(preferHtml = false)!!.partId)
        assertEquals(header, String(f.section("HEADER.FIELDS (FROM SUBJECT)")!!))
        assertTrue(f.internalDate!! > 0)
    }

    @Test fun `bodystructure attachments and rfc822 numbering`() {
        val bs = "* 1 FETCH (BODYSTRUCTURE ((\"text\" \"plain\" (\"charset\" \"us-ascii\") NIL NIL \"7bit\" 10 1 NIL NIL NIL)" +
            "(\"application\" \"pdf\" (\"name\" \"a.pdf\") NIL NIL \"base64\" 3000 NIL (\"attachment\" (\"filename\" \"a.pdf\")) NIL)" +
            "(\"message\" \"rfc822\" NIL NIL NIL \"7bit\" 500 (NIL \"inner\" NIL NIL NIL NIL NIL NIL NIL NIL) " +
            "(\"text\" \"plain\" NIL NIL NIL \"7bit\" 20 2 NIL NIL NIL) 9 NIL NIL NIL) \"mixed\" (\"boundary\" \"x\") NIL NIL))\r\n"
        val d = reader(bs).readResponse() as ImapResponse.Data
        val root = FetchItem.from(1, d.values).bodyStructure!!
        val leaves = root.leaves()
        // An attached message is itself the leaf (it downloads as .eml); its own body is section 3.1.
        assertEquals(listOf("1", "2", "3"), leaves.map { it.partId })
        assertEquals("3.1", root.children[2].children.single().partId)
        assertTrue(root.children[1].isAttachment)
        assertEquals("a.pdf", root.children[1].fileName)
        assertTrue(root.children[2].isAttachment)
    }

    @Test fun `list esearch and vanished parse`() {
        val r = reader(
            "* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Sent Mail\"\r\n" +
                "* ESEARCH (TAG \"Y5\") UID ALL 1:3,7,10:11\r\n" +
                "* VANISHED (EARLIER) 41,43:45\r\n",
        )
        val list = r.readResponse() as ImapResponse.Data
        assertEquals("[Gmail]/Sent Mail", list.values[2].text)
        val es = r.readResponse() as ImapResponse.Data
        assertEquals("1:3,7,10:11", es.values.last().text)
        assertEquals(listOf(1L, 2, 3, 7, 10, 11), SequenceSet.parse(es.values.last().text!!))
        val v = r.readResponse() as ImapResponse.Data
        assertEquals("VANISHED", v.keyword)
    }

    @Test fun `modified utf7 round trips`() {
        for (s in listOf("Entwürfe", "日本語", "A&B", "Sent Items", "Корзина", "~peter/mail/台北/日本語")) {
            assertEquals(s, ModifiedUtf7.decode(ModifiedUtf7.encode(s)))
        }
        assertEquals("&ZeVnLIqe-", ModifiedUtf7.encode("日本語"))
        assertEquals("A&-B", ModifiedUtf7.encode("A&B"))
    }

    @Test fun `sequence sets compress and chunk`() {
        assertEquals("1:3,7,9:10", SequenceSet.of(listOf(10L, 9, 7, 3, 2, 1)))
        assertEquals(3, SequenceSet.chunked((1L..1200L).toList(), 500).size)
    }

    // ------------------------------------------------------------------ end-to-end over a socket

    private fun endpoint(port: Int) = ServerEndpoint("127.0.0.1", port, Security.NONE, "me@example.org")

    @Test fun `xoauth2 login, qresync select, fetch, append with sync literal, idle`() = runBlocking {
        val server = ScriptedServer(greeting = "* OK [CAPABILITY IMAP4rev1 SASL-IR AUTH=XOAUTH2 ID ENABLE QRESYNC CONDSTORE UIDPLUS MOVE IDLE ESEARCH] ready", handler = { line ->
            val tag = line.substringBefore(' ')
            val cmd = line.substringAfter(' ')
            when {
                cmd.startsWith("AUTHENTICATE XOAUTH2 ") -> {
                    val decoded = String(Base64.getDecoder().decode(cmd.substringAfterLast(' ')))
                    if (decoded == "user=me@example.org\u0001auth=Bearer tok\u0001\u0001") {
                        send("$tag OK [CAPABILITY IMAP4rev1 ID ENABLE QRESYNC CONDSTORE UIDPLUS MOVE IDLE ESEARCH] signed in")
                    } else send("$tag NO bad")
                }
                cmd.startsWith("ID ") -> { send("* ID (\"name\" \"Fake\")"); send("$tag OK ID done") }
                cmd.startsWith("ENABLE") -> { send("* ENABLED QRESYNC CONDSTORE"); send("$tag OK enabled") }
                cmd.startsWith("SELECT INBOX (QRESYNC (42 100))") -> {
                    send("* 3 EXISTS")
                    send("* OK [UIDVALIDITY 42] v")
                    send("* OK [UIDNEXT 9] n")
                    send("* OK [HIGHESTMODSEQ 120] m")
                    send("* VANISHED (EARLIER) 2,4")
                    send("* 1 FETCH (UID 5 FLAGS (\\Seen) MODSEQ (119))")
                    send("$tag OK [READ-WRITE] selected")
                }
                cmd.startsWith("UID SEARCH RETURN (ALL) UNSEEN") -> { send("* ESEARCH (TAG \"$tag\") UID ALL 7:8"); send("$tag OK search") }
                cmd.startsWith("UID FETCH 7:8") -> {
                    send("* 2 FETCH (UID 7 FLAGS () RFC822.SIZE 10)")
                    send("* 3 FETCH (UID 8 FLAGS (\\Flagged) RFC822.SIZE 20)")
                    send("$tag OK fetched")
                }
                cmd.startsWith("APPEND \"Drafts\" (\\Draft) ") && cmd.endsWith("{11}") -> {
                    send("+ go ahead")
                    val body = readBytes(11)
                    if (String(body) != "Hello World") send("$tag NO wrong body") else {
                        readLine() // the CRLF that ends the command
                        send("$tag OK [APPENDUID 42 9] appended")
                    }
                }
                cmd == "IDLE" -> {
                    send("+ idling")
                    send("* 4 EXISTS")
                    val done = readLine()
                    if (done == "DONE") send("$tag OK idle done") else send("$tag BAD expected DONE")
                }
                cmd == "LOGOUT" -> { send("* BYE"); send("$tag OK bye"); close() }
                else -> send("$tag BAD unknown $cmd")
            }
        })
        server.use {
            val c = ImapClient(endpoint(server.port))
            c.connect()
            c.login(ImapAuth.OAuth("me@example.org", "tok"))
            assertTrue(c.qresyncEnabled)
            val sel = c.select("INBOX", knownUidValidity = 42, knownModSeq = 100)
            assertEquals(3L, sel.exists); assertEquals(42L, sel.uidValidity); assertEquals(9L, sel.uidNext)
            assertEquals(120L, sel.highestModSeq)
            assertEquals(listOf(2L, 4L), sel.vanished)
            assertEquals(5L, sel.changed.single().uid)
            assertEquals(listOf(7L, 8L), c.uidSearch("UNSEEN"))
            val items = c.uidFetch("7:8", "UID FLAGS RFC822.SIZE")
            assertEquals(listOf(7L, 8L), items.map { it.uid })
            assertEquals(AppendUid(42, 9), c.append("Drafts", "Hello World".toByteArray(), listOf("\\Draft")))
            var sawExists = 0L
            c.idle(maxMillis = 5_000, pollMillis = 500) { ev -> if (ev is MailboxEvent.Exists) { sawExists = ev.count; true } else false }
            assertEquals(4L, sawExists)
            c.logout()
            assertNull(server.error)
        }
    }

    @Test fun `oauth failure challenge is acknowledged and reported`() = runBlocking {
        val server = ScriptedServer(greeting = "* OK [CAPABILITY IMAP4rev1 AUTH=XOAUTH2] ready", handler = { line ->
            val tag = line.substringBefore(' ')
            when {
                line.endsWith("AUTHENTICATE XOAUTH2") -> send("+ ")
                line.isEmpty() -> send("Y1 NO [AUTHENTICATIONFAILED] Invalid credentials")
                line.matches(Regex("[A-Za-z0-9+/=]+")) -> send("+ " + Base64.getEncoder().encodeToString("{\"status\":\"401\"}".toByteArray()))
                else -> send("$tag BAD")
            }
        })
        server.use {
            val c = ImapClient(endpoint(server.port))
            c.connect()
            try {
                c.login(ImapAuth.OAuth("me@example.org", "expired"))
                fail("expected an authentication failure")
            } catch (e: AuthenticationException) {
                assertTrue(e.serverText!!.contains("401"))
            }
            c.close()
        }
    }
}
