package dev.aarso.mail.sieve

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.aarso.mail.jmap.JmapAuth
import dev.aarso.mail.jmap.JmapClient
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Identity
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RunLocation
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.rules.RuleEffects
import dev.aarso.mail.rules.RuleEngine
import dev.aarso.mail.search.MailFields
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import dev.aarso.mail.sync.MailActions
import dev.aarso.search.EvalContext
import dev.aarso.search.QueryParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId

class SieveSyncTest {
    private val now = 1_793_534_400_000L
    private lateinit var dir: File
    private lateinit var store: MailStore
    private lateinit var account: Account
    private lateinit var inbox: Mailbox
    private val fields = MailFields()
    private val compiler = SieveCompiler { QueryParser.parse(it, fields.registry, EvalContext(0L), ZoneId.of("UTC")) }
    private val servers = ArrayList<AutoCloseable>()

    @Before fun setUp() = runBlocking {
        dir = createTempDir("sieve")
        store = MailStore(BundledSQLiteDriver(), File(dir, "m.db").absolutePath)
        account = store.saveAccount(Account(email = "me@example.org", displayName = "Me", providerId = "mailboxorg", protocol = IncomingProtocol.IMAP,
            auth = AuthKind.PASSWORD, secret = SecretRef("s"), identities = listOf(Identity(EmailAddress("me@example.org"), isDefault = true))))
        val boxes = store.upsertMailboxes(account.id, listOf(
            Mailbox(accountId = account.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX),
            Mailbox(accountId = account.id, remoteId = "Archive", name = "Archive", role = MailboxRole.ARCHIVE),
            Mailbox(accountId = account.id, remoteId = "Trash", name = "Trash", role = MailboxRole.TRASH),
            Mailbox(accountId = account.id, remoteId = "Work", name = "Work"),
        ), true)
        inbox = boxes.first { it.role == MailboxRole.INBOX }
    }

    @After fun tearDown() { servers.forEach { it.close() }; store.close(); dir.deleteRecursively() }

    private fun manage(s: FakeManageSieveServer = FakeManageSieveServer()) = s.also { servers += it }
    private fun sync(s: FakeManageSieveServer, creds: SieveCredentials = SieveCredentials { "me@example.org" to "secret" }) = SieveSync(store, compiler, creds, transports = s.factory(), clock = Clock { now })

    private fun rule(id: Long, name: String, q: String, vararg a: RuleAction, loc: RunLocation = RunLocation.AUTO, confirmed: Boolean = false) =
        MailRule(id = id, name = name, query = q, actions = a.toList(), runLocation = loc, confirmed = confirmed, order = id.toInt())

    private val work = rule(1, "Work mail", "from:@corp.com", RuleAction.MarkRead, RuleAction.MoveTo(mailbox = "Work"))
    private val tabs = rule(2, "Newsletters", "category:newsletters", RuleAction.Archive)
    private val both = rule(3, "Mixed", "from:@acme.io", RuleAction.Star, RuleAction.AddLabel("Acme"), RuleAction.Notify(dev.aarso.mail.model.NotifyLevel.NEVER), loc = RunLocation.BOTH)
    private val phone = rule(4, "On the phone", "from:@home.org", RuleAction.Star, loc = RunLocation.DEVICE)

    @Test fun `portable rules become one active script, the rest stay on the phone with their reasons`() = runBlocking {
        val s = manage()
        val out = sync(s).sync(account, listOf(work, tabs, both, phone)) as SieveSync.Outcome.Synced
        assertTrue(out.uploaded)
        val script = s.scripts.getValue(SieveSync.SCRIPT_NAME)
        assertEquals(SieveSync.SCRIPT_NAME, s.active)
        assertTrue(script.contains("fileinto \"Work\"")); assertTrue(script.contains("# Work mail (rule 1)"))
        assertTrue("the server half of the mixed rule", script.contains("# Mixed (rule 3)") && script.contains("addflag \"\\\\Flagged\""))
        assertFalse("the tab rule is not on the server", script.contains("Newsletters"))
        assertFalse(script.contains("home.org"))
        val p = out.placements.associateBy { it.ruleId }
        assertEquals(RunLocation.SERVER, p.getValue(1).location); assertEquals(listOf(0, 1), p.getValue(1).serverActions)
        assertEquals(RunLocation.DEVICE, p.getValue(2).location); assertTrue(p.getValue(2).reasons.single().contains("tabs"))
        assertEquals(RunLocation.BOTH, p.getValue(3).location); assertEquals(listOf(0), p.getValue(3).serverActions)
        assertEquals(RunLocation.DEVICE, p.getValue(4).location)
        assertEquals("remembered for the engine", p, sync(s).placements(account.id))
    }

    @Test fun `a second sync with nothing changed uploads nothing`() = runBlocking {
        val s = manage(); val sy = sync(s)
        sy.sync(account, listOf(work))
        val puts = s.commands.count { it.startsWith("PUTSCRIPT") }
        val again = sy.sync(account, listOf(work)) as SieveSync.Outcome.Synced
        assertFalse(again.uploaded); assertEquals(puts, s.commands.count { it.startsWith("PUTSCRIPT") })
        val changed = sy.sync(account, listOf(work.copy(actions = listOf(RuleAction.Archive)))) as SieveSync.Outcome.Synced
        assertTrue(changed.uploaded); assertTrue(s.scripts.getValue(SieveSync.SCRIPT_NAME).contains("fileinto \"Archive\""))
    }

    @Test fun `a script somebody else made is never replaced unless asked`() = runBlocking {
        val s = manage().also { it.scripts["theirs"] = "keep;\r\n"; it.active = "theirs" }
        val out = sync(s).sync(account, listOf(work))
        assertEquals(SieveSync.Outcome.ForeignScript("theirs"), out)
        assertEquals("theirs", s.active); assertFalse(s.scripts.containsKey(SieveSync.SCRIPT_NAME))
        assertTrue("the phone keeps running the rule", sync(s).placements(account.id).isEmpty())
        val taken = sync(s).sync(account, listOf(work), allowTakeover = true)
        assertTrue(taken is SieveSync.Outcome.Synced); assertEquals(SieveSync.SCRIPT_NAME, s.active)
        assertTrue("their script is still stored, just not active", s.scripts.containsKey("theirs"))
    }

    @Test fun `when nothing is left for the server, its script is taken away`() = runBlocking {
        val s = manage(); val sy = sync(s)
        sy.sync(account, listOf(work))
        assertNotNull(s.active)
        val out = sy.sync(account, listOf(work.copy(runLocation = RunLocation.DEVICE))) as SieveSync.Outcome.Synced
        assertFalse(out.uploaded); assertNull(s.active); assertFalse(s.scripts.containsKey(SieveSync.SCRIPT_NAME))
    }

    @Test fun `no connection is made when no rule could run on a server`() = runBlocking {
        val s = manage()
        val out = sync(s).sync(account, listOf(tabs, phone)) as SieveSync.Outcome.Synced
        assertTrue(s.commands.isEmpty()); assertTrue(out.placements.all { it.location == RunLocation.DEVICE })
    }

    @Test fun `providers with no server rules are said so, without connecting`() = runBlocking {
        val s = manage()
        for (id in listOf("gmail", "icloud", "outlook", "m365", "yahoo", "proton")) {
            val out = sync(s).sync(account.copy(providerId = id), listOf(work))
            assertTrue("$id", out is SieveSync.Outcome.Unsupported)
        }
        assertTrue(sync(s).sync(account.copy(auth = AuthKind.OAUTH2), listOf(work)) is SieveSync.Outcome.Unsupported)
        assertTrue(sync(s).sync(account.copy(protocol = IncomingProtocol.POP3), listOf(work)) is SieveSync.Outcome.Unsupported)
        assertTrue(s.commands.isEmpty())
        assertEquals(SieveEndpoint.Manage("imap.mailbox.org", 4190), SieveSupport.endpointFor(account))
        assertEquals(SieveEndpoint.Manage("posteo.de", 4190), SieveSupport.endpointFor(account.copy(providerId = "posteo")))
        assertEquals(SieveEndpoint.Jmap, SieveSupport.endpointFor(account.copy(protocol = IncomingProtocol.JMAP, providerId = "fastmail")))
    }

    @Test fun `failures are reported, never thrown, and leave the rules on the phone`() = runBlocking {
        val s = manage()
        assertTrue(sync(s, SieveCredentials { "me@example.org" to "wrong" }).sync(account, listOf(work)) is SieveSync.Outcome.Failed)
        assertTrue(sync(s, SieveCredentials { null }).sync(account, listOf(work)) is SieveSync.Outcome.Unsupported)
        assertTrue(sync(s).placements(account.id).isEmpty())
        val noTls = manage(FakeManageSieveServer(offerStartTls = false))
        assertTrue(sync(noTls).sync(account, listOf(work)) is SieveSync.Outcome.Failed)
        val noFileinto = manage(FakeManageSieveServer(extensions = "vacation"))
        val out = sync(noFileinto).sync(account, listOf(work)) as SieveSync.Outcome.Synced
        assertEquals(RunLocation.DEVICE, out.placements.single().location); assertTrue(out.placements.single().reasons.any { it.contains("fileinto") })
    }

    @Test fun `if the person switches the script off elsewhere, the phone takes its rules back`() = runBlocking {
        val s = manage(); val sy = sync(s)
        sy.sync(account, listOf(work))
        assertTrue(sy.stillActive(account))
        s.active = null
        assertFalse(sy.stillActive(account))
        assertTrue(sy.placements(account.id).isEmpty())
    }

    // ---- the engine honours placements: nothing runs twice ----

    @Test fun `a rule the server carries is not run again on the phone, a split rule runs only what the server could not`() = runBlocking {
        val search = MailSearch(store, ZoneId.of("UTC"), Clock { now })
        val clock = Clock { now }
        val s = manage(); val sy = sync(s)
        val saved = listOf(work, both, phone).map { store.saveRule(it.copy(id = 0)) }
        sy.sync(account, saved)
        val engine = RuleEngine(store, search, MailActions(store, clock), object : RuleEffects {}, clock, ZoneId.of("UTC")) { sy.placements(it) }
        fun add(uid: Long, from: String) = runBlocking { store.insertMessages(listOf(MessageHeader(accountId = account.id, mailboxId = inbox.id, uid = uid, subject = "s$uid", from = EmailAddress(from), receivedAt = now - 1000, category = Category.PEOPLE))).single() }
        val a = add(1, "x@corp.com"); val b = add(2, "y@acme.io"); val c = add(3, "z@home.org")
        engine.onNewMail(account, listOf(a, b, c))
        assertEquals("the server's rule is the server's: the phone left it alone", inbox.id, store.header(a.id)!!.mailboxId)
        assertFalse(store.header(a.id)!!.isSeen)
        assertFalse("the server half (star) is not done here", store.header(b.id)!!.isFlagged)
        assertTrue("the phone's own rule ran", store.header(c.id)!!.isFlagged)
        assertEquals("the phone's half of the split rule: the label", listOf("Acme"), store.header(b.id)!!.labels.filter { it == "Acme" })
    }

    // ---- JMAP ----

    @Test fun `over JMAP the script is created, activated, then updated in place`() = runBlocking {
        val calls = ArrayList<String>()
        val scripts = ArrayList<Pair<String, String>>() // id, name
        var activeId: String? = null
        val server = MockWebServer().also { servers += AutoCloseable { it.shutdown() } }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                return when {
                    r.path == "/session" -> MockResponse().setBody("""{"capabilities":{"urn:ietf:params:jmap:core":{"maxSizeUpload":50000000},"urn:ietf:params:jmap:mail":{},"urn:ietf:params:jmap:sieve":{}},
                        "accounts":{"A1":{"accountCapabilities":{"urn:ietf:params:jmap:sieve":{"sieveExtensions":["fileinto","imap4flags","copy"]}}}},
                        "primaryAccounts":{"urn:ietf:params:jmap:mail":"A1"},"username":"me","apiUrl":"$base/api","downloadUrl":"$base/dl","uploadUrl":"$base/up/{accountId}","state":"s"}""")
                    r.path!!.startsWith("/up/") -> { calls += "upload:" + r.body.readUtf8().take(30).replace("\n", " "); MockResponse().setBody("""{"blobId":"B${calls.size}"}""") }
                    r.path == "/api" -> {
                        val body = r.body.readUtf8()
                        when {
                            body.contains("SieveScript/get") -> MockResponse().setBody("""{"methodResponses":[["SieveScript/get",{"list":[${scripts.joinToString(",") { """{"id":"${it.first}","name":"${it.second}","isActive":${it.first == activeId}}""" }}]},"g"]]}""")
                            body.contains("SieveScript/set") -> {
                                calls += "set:" + (if (body.contains("\"create\"")) "create" else "update") + (if (body.contains("onSuccessActivateScript")) "+activate" else "")
                                if (body.contains("\"create\"")) { scripts += "S1" to SieveSync.SCRIPT_NAME; activeId = "S1" }
                                MockResponse().setBody("""{"methodResponses":[["SieveScript/set",{"created":{"script":{"id":"S1"}}},"s"]]}""")
                            }
                            else -> MockResponse().setResponseCode(400)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val acct = account.copy(protocol = IncomingProtocol.JMAP, providerId = "fastmail")
        val client = JmapClient(server.url("/session").toString(), JmapAuth.Basic("me", "pw"))
        val sy = SieveSync(store, compiler, SieveCredentials { null }, JmapProvider { client }, clock = Clock { now })
        val first = sy.sync(acct, listOf(work)) as SieveSync.Outcome.Synced
        assertTrue(first.uploaded)
        assertEquals(listOf("upload", "set:create+activate"), calls.map { it.substringBefore(':', "").let { k -> if (k == "upload") k else it } })
        assertEquals("a second sync with the same rules changes nothing", false, (sy.sync(acct, listOf(work)) as SieveSync.Outcome.Synced).uploaded)
        val changed = sy.sync(acct, listOf(work.copy(actions = listOf(RuleAction.Archive)))) as SieveSync.Outcome.Synced
        assertTrue(changed.uploaded); assertTrue("updated in place", calls.last() == "set:update")
        // a server that doesn't offer Sieve
        val plain = MockWebServer().also { servers += AutoCloseable { it.shutdown() } }
        plain.enqueue(MockResponse().setBody("""{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},"accounts":{"A1":{}},"primaryAccounts":{"urn:ietf:params:jmap:mail":"A1"},"username":"me","apiUrl":"${plain.url("/api")}","downloadUrl":"x","uploadUrl":"y","state":"s"}"""))
        val sy2 = SieveSync(store, compiler, SieveCredentials { null }, JmapProvider { JmapClient(plain.url("/session").toString(), JmapAuth.Basic("me", "pw")) })
        assertTrue(sy2.sync(acct, listOf(work)) is SieveSync.Outcome.Unsupported)
    }
}
