package dev.aarso.mail.sync

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.aarso.mail.intel.HeuristicAnalyzer
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.Identity
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import dev.aarso.mail.store.MailStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SyncTest {

    private lateinit var dir: File
    private lateinit var store: MailStore
    private val secrets = object : SecretStore {
        val map = HashMap<String, String>()
        override suspend fun get(ref: SecretRef) = map[ref.key]
        override suspend fun put(ref: SecretRef, value: String) { map[ref.key] = value }
        override suspend fun delete(ref: SecretRef) { map.remove(ref.key) }
    }
    private val tokens = object : TokenProvider { override suspend fun accessToken(account: Account, forceRefresh: Boolean) = "unused" }

    @Before fun setUp() {
        dir = createTempDir("mailtest")
        store = MailStore(BundledSQLiteDriver(), File(dir, "mail.db").absolutePath)
        secrets.map["acct"] = "pw"
    }

    @After fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun account(port: Int) = Account(
        email = "me@example.org", displayName = "Me", providerId = "custom", protocol = IncomingProtocol.IMAP,
        incoming = ServerEndpoint("127.0.0.1", port, Security.NONE, "me@example.org"),
        outgoing = null, auth = AuthKind.PASSWORD, secret = SecretRef("acct"),
        identities = listOf(Identity(EmailAddress("me@example.org", "Me"), isDefault = true)),
    )

    private fun seed(server: FakeImapServer) {
        server.deliver("Sent", """
            From: Me <me@example.org>
            To: Fin <fin@anthropic.com>
            Subject: Startup program question
            Date: Mon, 2 Nov 2026 09:00:00 +0000
            Message-ID: <mine-1@example.org>
            Content-Type: text/plain; charset=utf-8

            Hi, could you tell me more about your startup program?
        """, setOf("\\Seen"))
        server.deliver("INBOX", """
            From: Fin <fin@anthropic.com>
            To: me@example.org
            Subject: Re: Startup program question
            Date: Tue, 3 Nov 2026 09:00:00 +0000
            Message-ID: <reply-1@anthropic.com>
            In-Reply-To: <mine-1@example.org>
            References: <mine-1@example.org>
            Content-Type: text/plain; charset=utf-8

            Thanks for reaching out about the startup program. Could you share more details so I can connect you to a human agent?
        """)
        server.deliver("INBOX", """
            From: Acme Login <no-reply@acme.example>
            To: me@example.org
            Subject: Your Acme verification code
            Date: Tue, 3 Nov 2026 09:05:00 +0000
            Message-ID: <otp-1@acme.example>
            Content-Type: text/plain; charset=utf-8

            Your verification code is 482913. It expires in 10 minutes.
        """)
        server.deliver("INBOX", """
            From: Weekly Digest <news@digest.example>
            To: me@example.org
            Subject: This week in widgets
            Date: Tue, 3 Nov 2026 08:00:00 +0000
            Message-ID: <news-1@digest.example>
            List-Id: <weekly.digest.example>
            List-Unsubscribe: <https://digest.example/u/1>
            Content-Type: multipart/alternative; boundary=b

            --b
            Content-Type: text/plain; charset=utf-8

            The weekly newsletter about widgets. Read in browser.
            --b
            Content-Type: text/html; charset=utf-8

            <p>The weekly newsletter about widgets.</p><img src="https://digest.example/track/open.gif?u=1" width="1" height="1">
            --b--
        """)
    }

    @Test fun `initial sync stores, threads, categorises and summarises`() = runBlocking {
        val server = FakeImapServer().also(::seed)
        server.start().use { socket ->
            val acct = store.saveAccount(account(socket.port))
            val sync = MailSync(store, AccountConnector(secrets, tokens), HeuristicAnalyzer())
            // Sent first, so the reply can be recognised as a reply to me.
            val report = sync.sync(acct)
            assertNull(report.error?.let { it.stackTraceToString() }, report.error)
            val boxes = store.mailboxes(acct.id)
            assertEquals(setOf(MailboxRole.INBOX, MailboxRole.SENT, MailboxRole.ARCHIVE, MailboxRole.TRASH), boxes.map { it.role }.toSet())
            val inbox = boxes.first { it.role == MailboxRole.INBOX }
            val msgs = store.queryHeaders("m.mailbox_id = ?", listOf(inbox.id))
            assertEquals(3, msgs.size)

            val reply = msgs.first { it.subject.startsWith("Re:") }
            val sent = store.queryHeaders("mb.role = 'SENT'", emptyList()).single()
            assertEquals("the reply joins the thread of my message", sent.threadKey, reply.threadKey)
            assertEquals(Category.REPLIES, reply.category)
            assertNotNull(reply.tldr)
            assertTrue(reply.tldr!!, reply.tldr!!.contains("connect you to a human agent") || reply.tldr!!.contains("startup program"))

            val otp = msgs.first { it.subject.contains("verification") }
            assertEquals(Category.OTP, otp.category)
            assertEquals("482913", otp.otpCode)

            val news = msgs.first { it.subject.contains("widgets") }
            assertEquals(Category.NEWSLETTERS, news.category)
            assertEquals(1, news.trackerCount)
            val body = store.body(news.id)!!
            assertTrue(body.html!!.contains("weekly newsletter"))
            assertTrue(store.ftsIds("\"widgets\"*").contains(news.id))
            assertEquals(3, store.mailbox(inbox.id)!!.unreadCount)
        }
    }

    @Test fun `actions apply locally, flush to the server, and survive a resync`() = runBlocking {
        val server = FakeImapServer().also(::seed)
        server.start().use { socket ->
            val acct = store.saveAccount(account(socket.port))
            val sync = MailSync(store, AccountConnector(secrets, tokens), HeuristicAnalyzer())
            sync.sync(acct)
        }
        // A second connection for the second sync: the fake serves one connection per start().
        val acct = store.accounts().single()
        val inbox = store.mailboxByRole(acct.id, MailboxRole.INBOX)!!
        val archive = store.mailboxByRole(acct.id, MailboxRole.ARCHIVE)!!
        val msgs = store.queryHeaders("m.mailbox_id = ?", listOf(inbox.id))
        val news = msgs.first { it.subject.contains("widgets") }
        val otp = msgs.first { it.subject.contains("verification") }

        val actions = MailActions(store)
        actions.markRead(listOf(otp.id))
        val undo = actions.archive(listOf(news.id))
        assertEquals(archive.id, store.header(news.id)!!.mailboxId)
        assertTrue(store.header(otp.id)!!.isSeen)
        assertEquals(2, store.pendingOpCount())

        // Undo before the flush: nothing reaches the server and the row is back where it was.
        actions.undo(undo)
        assertEquals(inbox.id, store.header(news.id)!!.mailboxId)
        assertEquals(1, store.pendingOpCount())
        actions.archive(listOf(news.id))

        server.start().use { socket ->
            val moved = acct.copy(incoming = acct.incoming!!.copy(port = socket.port))
            store.saveAccount(moved)
            val sync = MailSync(store, AccountConnector(secrets, tokens), HeuristicAnalyzer())
            val report = sync.sync(moved)
            assertNull(report.error)
        }
        assertEquals(0, store.pendingOpCount())
        assertTrue(server.boxes.getValue("INBOX").messages.first { String(it.raw).contains("verification") }.flags.contains("\\Seen"))
        assertFalse(server.boxes.getValue("INBOX").messages.any { String(it.raw).contains("widgets") })
        assertTrue(server.boxes.getValue("Archive").messages.any { String(it.raw).contains("widgets") })
        // The archived row has its server UID now, and was not duplicated by the resync.
        val archived = store.queryHeaders("m.mailbox_id = ?", listOf(archive.id))
        assertEquals(1, archived.size)
        assertTrue(archived.single().uid > 0)
        assertEquals(Flags.SEEN, store.header(otp.id)!!.flags and Flags.SEEN)
    }
}
