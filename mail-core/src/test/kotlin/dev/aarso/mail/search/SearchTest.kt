package dev.aarso.mail.search

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.aarso.mail.digest.DigestBuilder
import dev.aarso.mail.digest.DigestWindow
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.Identity
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.PartInfo
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.rules.RuleEngine
import dev.aarso.mail.rules.SieveExport
import dev.aarso.mail.select.Selection
import dev.aarso.mail.select.SelectionEngine
import dev.aarso.mail.select.SelectionMode
import dev.aarso.mail.select.SelectionRule
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import dev.aarso.mail.sync.MailActions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId

class SearchTest {

    private val zone = ZoneId.of("UTC")
    private val now = 1_793_534_400_000L // 2026-11-02T12:00:00Z
    private val clock = Clock { now }
    private val day = 86_400_000L
    private lateinit var dir: File
    private lateinit var store: MailStore
    private lateinit var search: MailSearch
    private lateinit var account: Account
    private lateinit var inbox: Mailbox
    private lateinit var trash: Mailbox
    private val ids = HashMap<String, Long>()

    @Before fun setUp() = runBlocking {
        dir = createTempDir("search")
        store = MailStore(BundledSQLiteDriver(), File(dir, "m.db").absolutePath)
        account = store.saveAccount(
            Account(email = "me@example.org", displayName = "Me", providerId = "gmail", protocol = IncomingProtocol.IMAP,
                auth = AuthKind.PASSWORD, secret = SecretRef("s"), identities = listOf(Identity(EmailAddress("me@example.org"), isDefault = true))),
        )
        val boxes = store.upsertMailboxes(account.id, listOf(
            Mailbox(accountId = account.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX),
            Mailbox(accountId = account.id, remoteId = "Trash", name = "Trash", role = MailboxRole.TRASH),
            Mailbox(accountId = account.id, remoteId = "Receipts", name = "Receipts"),
        ), removeOthers = true)
        inbox = boxes.first { it.role == MailboxRole.INBOX }
        trash = boxes.first { it.role == MailboxRole.TRASH }
        search = MailSearch(store, zone, clock)

        suspend fun add(key: String, h: MessageHeader, body: String? = null) {
            val stored = store.insertMessages(listOf(h.copy(accountId = account.id, mailboxId = h.mailboxId.takeIf { it > 0 } ?: inbox.id))).single()
            if (body != null) store.saveBody(MessageBody(stored.id, text = body), stored)
            ids[key] = stored.id
        }
        add("alice", MessageHeader(accountId = 0, mailboxId = 0, uid = 1, subject = "Lunch on Friday?", from = EmailAddress("alice@friends.net", "Alice Smith"),
            to = listOf(EmailAddress("me@example.org")), receivedAt = now - 2 * 3_600_000, category = Category.PEOPLE), "Shall we grab lunch at the café on Friday?")
        add("invoice", MessageHeader(accountId = 0, mailboxId = 0, uid = 2, subject = "Invoice INV-2041 for October", from = EmailAddress("billing@acme.io", "Acme Billing"),
            receivedAt = now - 40 * day, flags = Flags.SEEN, attachmentCount = 1, size = 7_000_000, category = Category.RECEIPTS,
            parts = listOf(PartInfo("2", "application/pdf", fileName = "INV-2041.pdf", disposition = "attachment"))), "Your invoice is attached. Total €42.00")
        add("draftnote", MessageHeader(accountId = 0, mailboxId = 0, uid = 3, subject = "Draft of the proposal", from = EmailAddress("bob@work.com", "Bob"),
            receivedAt = now - 3 * day, category = Category.PEOPLE), "Here is a draft of the proposal for your review.")
        add("news", MessageHeader(accountId = 0, mailboxId = 0, uid = 4, subject = "This week in widgets", from = EmailAddress("hello@substack.com", "Widget Weekly"),
            listId = "widgets.substack.com", listUnsubscribe = "<https://x/u>", isBulk = true, receivedAt = now - 5 * 3_600_000, category = Category.NEWSLETTERS), "The weekly newsletter")
        add("gh", MessageHeader(accountId = 0, mailboxId = 0, uid = 5, subject = "[repo] Fix flaky test (#123)", from = EmailAddress("notifications@github.com", "GitHub"),
            receivedAt = now - 20 * 3_600_000, flags = Flags.FLAGGED, category = Category.TICKETS), "Merged pull request")
        add("trashed", MessageHeader(accountId = 0, mailboxId = trash.id, uid = 6, subject = "Old invoice reminder", from = EmailAddress("billing@acme.io"),
            receivedAt = now - 90 * day, category = Category.RECEIPTS), "Reminder about invoice")
        add("gmailInbox", MessageHeader(accountId = 0, mailboxId = boxes.first { it.name == "Receipts" }.id, uid = 7, subject = "Your receipt",
            from = EmailAddress("receipts@shop.example"), labels = listOf("\\Inbox", "Receipts"), receivedAt = now - 4 * 3_600_000, category = Category.RECEIPTS), "Thanks for your order")
    }

    @After fun tearDown() { store.close(); dir.deleteRecursively() }

    private fun keys(r: List<MessageHeader>) = r.map { h -> ids.entries.first { it.value == h.id }.key }.toSet()
    private suspend fun q(text: String, natural: Boolean = true) = keys(search.search(text).headers.let {
        if (natural) it else search.search(search.parse(text, natural = false)).headers
    })

    @Test fun `structured queries push down and stay exact`() = runBlocking {
        assertEquals(setOf("alice"), q("from:alice"))
        assertEquals(setOf("invoice"), q("from:@acme.io"))                     // trash excluded by default
        assertEquals(setOf("invoice", "trashed"), q("from:@acme.io in:anywhere"))
        assertEquals(setOf("trashed"), q("in:trash"))
        assertEquals(setOf("invoice"), q("has:pdf"))
        assertEquals(setOf("invoice"), q("size:>5mb"))
        assertEquals(setOf("invoice"), q("ext:pdf older_than:30d"))
        assertEquals(setOf("gh"), q("is:starred"))
        assertEquals(setOf("news"), q("has:unsubscribe"))
        assertEquals(setOf("alice", "draftnote", "news", "gh", "gmailInbox"), q("is:unread"))
        assertEquals(setOf("gmailInbox"), q("label:Receipts in:inbox"))
        assertEquals(setOf("invoice", "gmailInbox"), q("category:receipts"))
    }

    @Test fun `text, negation, phrases, regex and mixed or`() = runBlocking {
        assertEquals(setOf("alice"), q("cafe"))                                // diacritics folded by FTS5
        assertEquals(setOf("draftnote"), q("proposal"))
        assertEquals(setOf("invoice"), q("invoice -reminder"))
        assertTrue("negative-only text query works", "draftnote" !in q("-draft"))
        assertEquals(setOf("alice"), q("\"grab lunch\""))
        assertEquals(setOf("invoice"), q("/INV-\\d{4}/"))
        assertEquals(setOf("gh", "alice"), q("from:github OR lunch"))          // lossy shape, still exact
        assertEquals(setOf("news"), q("subject:widgets from:substack"))
    }

    @Test fun `natural language becomes structured syntax`() = runBlocking {
        assertEquals(setOf("news"), q("unread newsletters from substack"))
        assertEquals(setOf("invoice"), q("pdfs older than 30 days"))
        assertEquals(setOf("invoice"), q("bigger than 5 mb"))
        assertEquals(setOf("alice", "news", "gh", "gmailInbox"), q("today OR yesterday"))
        val parsed = search.parse("unread from alice with attachments")
        assertEquals(setOf("is", "from", "has"), parsed.facets.map { it.key }.toSet())
    }

    @Test fun `selection rules compose without materialising`() = runBlocking {
        val engine = SelectionEngine(search)
        var sel = Selection.allMatching("is:unread")
        assertEquals(5, engine.count(sel))
        sel = sel.toggle(ids.getValue("news"), matchesQuery = true)
        assertEquals(4, engine.count(sel))
        sel = engine.apply(sel, SelectionRule("people only", "category:people", SelectionMode.INTERSECT), viewQuery = null)
        assertEquals(setOf(ids["alice"], ids["draftnote"]), engine.resolve(sel).toSet())
        sel = engine.apply(sel, SelectionRule("+receipts", "category:receipts", SelectionMode.ADD), viewQuery = null)
        assertEquals(setOf(ids["alice"], ids["draftnote"], ids["invoice"], ids["gmailInbox"]), engine.resolve(sel).toSet())
        sel = engine.apply(sel, SelectionRule("-acme", "from:@acme.io", SelectionMode.REMOVE), viewQuery = null)
        assertTrue(ids["invoice"] !in engine.resolve(sel))
        val inverted = engine.invert(Selection.of(listOf(ids.getValue("alice"))), "in:inbox")
        assertTrue(ids["alice"] !in engine.resolve(inverted))
        assertTrue(ids["gh"] in engine.resolve(inverted))
        val stats = engine.stats(store.headers(engine.resolve(Selection.allMatching("in:inbox"))))
        assertEquals(6, stats.count)
        assertEquals(1, stats.unsubscribable)
    }

    @Test fun `rules dry run, act on new mail, and export to sieve`() = runBlocking {
        val actions = MailActions(store, clock)
        val rules = RuleEngine(store, search, actions, clock = clock)
        val rule = store.saveRule(MailRule(name = "Archive newsletters", query = "category:newsletters", actions = listOf(RuleAction.MarkRead, RuleAction.Notify(NotifyLevel.NEVER))))
        assertEquals(1, rules.dryRun(rule).matched)
        val outcome = rules.onNewMail(account, store.headers(ids.values))
        assertEquals(NotifyLevel.NEVER, outcome.notify[ids["news"]])
        assertTrue(store.header(ids.getValue("news"))!!.isSeen)
        assertEquals(1, store.rules().single().matchCount)
        val sieve = SieveExport.export(listOf(MailRule(name = "gh", query = "from:github.com subject:\"[repo]\"", actions = listOf(RuleAction.MarkRead))) ) { null }
        assertTrue(sieve.script.contains("address :contains \"from\" \"github.com\""))
        assertTrue(sieve.script.contains("addflag \"\\\\Seen\";"))
        assertTrue(rules.diagnose("form:alice").isNotEmpty())
    }

    @Test fun `digest counts the inbox by category since yesterday`() = runBlocking {
        val digest = DigestBuilder(search, clock, zone).build(setOf(account.id), DigestWindow.SINCE_YESTERDAY, lastLookAt = 0)
        // alice, news, gh (yesterday), gmailInbox (labelled \Inbox) — not the 40-day-old invoice, not trash.
        assertEquals(4, digest.total)
        assertEquals(listOf("TICKETS", "PEOPLE", "RECEIPTS", "NEWSLETTERS", "EVERYTHING"), digest.tabs.map { it.label })
        val people = digest.tabs.first { it.category == Category.PEOPLE }
        assertEquals(setOf("alice"), keys(search.search(people.query).headers))
    }

    @Test fun `typeahead completes operators, people, folders and values`() = runBlocking {
        store.recordContacts(listOf(EmailAddress("priya@acme.io", "Priya Raman")), sentByMe = true, at = now)
        val suggest = SearchSuggest(store, search)
        // Operators as you start a word.
        assertTrue(suggest.suggest("fr").any { it.insert == "from:" })
        assertTrue(suggest.suggest("older").any { it.insert == "older_than:" })
        // People after from:, and for a bare name.
        assertEquals("from:priya@acme.io", suggest.suggest("from:pri").first().insert)
        assertTrue(suggest.suggest("priy").any { it.insert == "from:priya@acme.io" })
        // Replacement is applied to the token under the caret only.
        val s = suggest.suggest("invoice from:pri").first()
        assertEquals("invoice from:priya@acme.io", s.apply("invoice from:pri"))
        // Folders, categories and fixed words.
        assertTrue(suggest.suggest("in:tr").any { it.insert == "in:trash" })
        assertTrue(suggest.suggest("in:rec").any { it.insert == "in:Receipts" })
        assertTrue(suggest.suggest("category:news").any { it.insert == "category:newsletters" })
        assertTrue(suggest.suggest("is:un").any { it.insert == "is:unread" })
        assertTrue(suggest.suggest("has:att").any { it.insert == "has:attachment" })
        // Negation is kept; an open quote or a trailing space offers nothing.
        assertEquals("-from:priya@acme.io", suggest.suggest("-from:pri").first().insert)
        assertTrue(suggest.suggest("\"half a phr").isEmpty())
        assertTrue(suggest.suggest("invoice ").isEmpty())
    }

    @Test fun `snippets show why a message matched and facets break results down`() = runBlocking {
        val parsed = search.parse("invoice", natural = false)
        val hits = search.search(parsed).headers
        assertTrue(hits.isNotEmpty())
        val snippets = search.snippets(parsed, hits.map { it.id })
        val s = snippets.getValue(ids.getValue("invoice"))
        assertTrue("matched word is marked: $s", s.contains("${MailSearch.HIT_START}") && s.contains("${MailSearch.HIT_END}"))
        // Pure filters have no words to highlight.
        assertTrue(search.snippets(search.parse("is:unread", natural = false), listOf(ids.getValue("alice"))).isEmpty())

        val f = search.facets(search.parse("in:inbox", natural = false))
        assertEquals(6, f.sampled)
        assertTrue(f.senders.any { it.first == "alice@friends.net" })
        assertEquals(1, f.withAttachments)
        assertEquals(5, f.unread)
        assertTrue(f.years.any { it.first == 2026 })
        assertTrue(f.categories.any { it.first == Category.PEOPLE && it.second == 2 })
    }

    // ---- the rest of the search engineering ----

    /** A stand-in for the ICU word breaker the app injects: every ideograph is its own word. */
    private val perIdeograph = com.asystemofcells.search.Segmenter.BoundarySource { text, _ ->
        val b = ArrayList<Int>(); b += 0
        for (i in 1 until text.length) if (Character.UnicodeScript.of(text[i].code) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(text[i - 1].code) == Character.UnicodeScript.HAN || text[i].isWhitespace() || text[i - 1].isWhitespace()) b += i
        b += text.length; b.distinct()
    }

    @Test fun `mail in scripts without spaces is searchable by its words`() = runBlocking {
        val d = createTempDir("cjk")
        val s = MailStore(BundledSQLiteDriver(), File(d, "c.db").absolutePath, dev.aarso.mail.search.IndexText(perIdeograph, "test"))
        val acct = s.saveAccount(Account(email = "me@example.org", displayName = "Me", providerId = "x", protocol = IncomingProtocol.IMAP, auth = AuthKind.PASSWORD, secret = SecretRef("s"), identities = listOf(Identity(EmailAddress("me@example.org"), isDefault = true))))
        val box = s.upsertMailboxes(acct.id, listOf(Mailbox(accountId = acct.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX)), true).single()
        val m = s.insertMessages(listOf(MessageHeader(accountId = acct.id, mailboxId = box.id, uid = 1, subject = "東京駅で会った", from = EmailAddress("a@b.jp"), receivedAt = now, preview = "会議の資料")))
        val ms = MailSearch(s, zone, clock)
        fun hits(q: String) = runBlocking { ms.search(ms.parse(q, natural = false)).headers.size }
        assertEquals("a word inside the run", 1, hits("駅"))
        assertEquals("a two-character word", 1, hits("東京"))
        assertEquals("the preview too", 1, hits("資料"))
        assertEquals("no false hit", 0, hits("大阪"))
        assertEquals("a phrase", 1, hits("\"東京駅\""))
        s.close(); d.deleteRecursively()
        assertTrue(m.isNotEmpty())
    }

    @Test fun `an index built the old way is rebuilt in the background when the format changes`() = runBlocking {
        val d = createTempDir("reindex")
        val path = File(d, "r.db").absolutePath
        val old = MailStore(BundledSQLiteDriver(), path, dev.aarso.mail.search.IndexText(perIdeograph, "v1"))
        old.prepareIndex()
        val acct = old.saveAccount(Account(email = "me@example.org", displayName = "Me", providerId = "x", protocol = IncomingProtocol.IMAP, auth = AuthKind.PASSWORD, secret = SecretRef("s"), identities = listOf(Identity(EmailAddress("me@example.org"), isDefault = true))))
        val box = old.upsertMailboxes(acct.id, listOf(Mailbox(accountId = acct.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX)), true).single()
        // Indexed with a source that never splits (the JDK default on a lone ideograph run), as an older build would have.
        val plain = MailStore(BundledSQLiteDriver(), File(d, "unused.db").absolutePath).also { it.close() }
        old.insertMessages(listOf(MessageHeader(accountId = acct.id, mailboxId = box.id, uid = 1, subject = "東京駅で会った", from = EmailAddress("a@b.jp"), receivedAt = now, preview = "x")))
        old.close()

        val fresh = MailStore(BundledSQLiteDriver(), path, dev.aarso.mail.search.IndexText(perIdeograph, "v2"))
        fresh.prepareIndex()
        assertTrue("a different format marks the index for rebuilding", fresh.indexNeedsRebuild())
        assertTrue(fresh.indexStatus().rebuilding)
        var steps = 0
        while (fresh.reindexStep(batch = 1)) steps++
        assertTrue(steps >= 1)
        assertFalse(fresh.indexNeedsRebuild())
        assertEquals("v2".let { "${dev.aarso.mail.search.IndexText.SCHEME}:$it" }, fresh.indexStatus().version)
        assertEquals(1, fresh.indexStatus().indexed)
        assertEquals(null, fresh.checkIndex())
        fresh.optimizeIndex()
        val ms = MailSearch(fresh, zone, clock)
        assertEquals(1, ms.search(ms.parse("駅", natural = false)).headers.size)
        fresh.close(); d.deleteRecursively()
        assertNotNull(plain)
    }

    @Test fun `html-only mail is searchable`() = runBlocking {
        val h = store.header(ids.getValue("alice"))!!
        store.saveBody(MessageBody(h.id, html = "<html><body><p>The quarterly <b>zephyr</b> report is attached.</p></body></html>"), h)
        assertEquals(setOf("alice"), q("zephyr"))
    }

    @Test fun `a misspelt word is corrected to one that exists`() = runBlocking {
        val parsed = search.parse("invoce", natural = false)
        val fixes = search.didYouMean(parsed)
        assertEquals(listOf(MailSearch.Correction("invoce", "invoice")), fixes)
        assertEquals("invoice acme", search.applyCorrections("Invoce acme", fixes).lowercase())
        assertTrue("a word that exists is left alone", search.didYouMean(search.parse("invoice", natural = false)).isEmpty())
        assertTrue("so is one nothing resembles", search.didYouMean(search.parse("qzxwvk", natural = false)).isEmpty())
        assertEquals("a swapped pair of letters", listOf("proposal"), search.didYouMean(search.parse("porposal", natural = false)).map { it.to })
    }
}
