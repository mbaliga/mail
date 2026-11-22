package dev.aarso.mail.rules

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.HeaderOp
import dev.aarso.mail.model.Identity
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.model.RunLocation
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.model.Trigger
import dev.aarso.mail.model.describe
import dev.aarso.mail.model.normalized
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import dev.aarso.mail.sync.MailActions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

class RulesV2Test {

    private val now = 1_793_534_400_000L
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val zone = ZoneId.of("UTC")
    private lateinit var dir: File
    private lateinit var store: MailStore
    private lateinit var search: MailSearch
    private lateinit var engine: RuleEngine
    private lateinit var actions: MailActions
    private lateinit var account: Account
    private lateinit var inbox: Mailbox
    private lateinit var archive: Mailbox
    private val ids = HashMap<String, Long>()
    private val forwarded = ArrayList<String>()

    @Before fun setUp() = runBlocking {
        dir = createTempDir("rules2")
        store = MailStore(BundledSQLiteDriver(), File(dir, "m.db").absolutePath)
        account = store.saveAccount(Account(email = "me@example.org", displayName = "Me", providerId = "other", protocol = IncomingProtocol.IMAP,
            auth = AuthKind.PASSWORD, secret = SecretRef("s"), identities = listOf(Identity(EmailAddress("me@example.org"), isDefault = true))))
        val boxes = store.upsertMailboxes(account.id, listOf(
            Mailbox(accountId = account.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX),
            Mailbox(accountId = account.id, remoteId = "Archive", name = "Archive", role = MailboxRole.ARCHIVE),
            Mailbox(accountId = account.id, remoteId = "Trash", name = "Trash", role = MailboxRole.TRASH),
        ), true)
        inbox = boxes.first { it.role == MailboxRole.INBOX }; archive = boxes.first { it.role == MailboxRole.ARCHIVE }
        val clock = Clock { now }
        search = MailSearch(store, zone, clock)
        actions = MailActions(store, clock)
        engine = RuleEngine(store, search, actions, object : RuleEffects { override suspend fun forward(message: MessageHeader, to: String) { forwarded += "${message.id}->$to" } }, clock, zone)

        suspend fun add(key: String, h: MessageHeader) { ids[key] = store.insertMessages(listOf(h.copy(accountId = account.id, mailboxId = inbox.id))).single().id }
        add("alice", MessageHeader(accountId = 0, mailboxId = 0, uid = 1, subject = "Lunch Friday?", from = EmailAddress("alice@friends.net", "Alice"), receivedAt = now - 2 * hour, size = 2_000, category = Category.PEOPLE, preview = "Shall we?"))
        add("news", MessageHeader(accountId = 0, mailboxId = 0, uid = 2, subject = "This week in widgets", from = EmailAddress("hello@substack.com", "Widget Weekly"), listId = "widgets.substack.com", isBulk = true, receivedAt = now - 5 * hour, size = 30_000, category = Category.NEWSLETTERS, preview = "The weekly newsletter"))
        add("invoice", MessageHeader(accountId = 0, mailboxId = 0, uid = 3, subject = "Invoice INV-2041", from = EmailAddress("billing@acme.io", "Acme"), receivedAt = now - 40 * day, flags = Flags.SEEN, attachmentCount = 1, size = 7_000_000, category = Category.RECEIPTS, preview = "Your invoice is attached"))
        add("priya", MessageHeader(accountId = 0, mailboxId = 0, uid = 4, subject = "Re: Sprint review", from = EmailAddress("priya@example.com", "Priya"), inReplyTo = "<abc@x>", receivedAt = now - day, size = 4_000, category = Category.REPLIES, preview = "Thursday works"))
        add("gh", MessageHeader(accountId = 0, mailboxId = 0, uid = 5, subject = "[repo] Fix flaky test (#123)", from = EmailAddress("notifications@github.com", "GitHub"), receivedAt = now - 20 * hour, flags = Flags.FLAGGED, size = 9_000, category = Category.TICKETS, preview = "Merged pull request"))
        store.recordContacts(listOf(EmailAddress("alice@friends.net"), EmailAddress("priya@example.com")), sentByMe = true, at = now)
    }

    @After fun tearDown() { store.close(); dir.deleteRecursively() }

    private fun ctx(matcher: QueryMatcher = QueryMatcher { q, h -> q.lowercase().removePrefix("from:").let { w -> h.from?.address?.contains(w, true) == true || h.subject.contains(w, true) } }) =
        RuleContext(matcher, now, zone) { it in setOf("alice@friends.net", "priya@example.com") }

    private fun h(f: MessageHeader.() -> MessageHeader = { this }) = MessageHeader(
        id = 1, accountId = 1, mailboxId = 1, subject = "Hello", from = EmailAddress("sam@corp.com", "Sam"), receivedAt = now - hour, size = 1_000, category = Category.PEOPLE,
    ).f()

    private fun rule(name: String, c: Condition, vararg a: RuleAction, order: Int = 0, stop: Boolean = false, confirmed: Boolean = true) =
        MailRule(name = name, query = "", condition = c, actions = a.toList(), order = order, stopProcessing = stop, confirmed = confirmed)

    // ---- the tree ----

    @Test fun `all, any and not combine leaves`() {
        val m = h()
        val yes = Condition.Header("subject", HeaderOp.CONTAINS, "hello")
        val no = Condition.Header("subject", HeaderOp.CONTAINS, "nope")
        assertTrue(ConditionEval.matches(Condition.All(listOf(yes, Condition.Not(no))), m, ctx()))
        assertFalse(ConditionEval.matches(Condition.All(listOf(yes, no)), m, ctx()))
        assertTrue(ConditionEval.matches(Condition.AnyOf(listOf(no, yes)), m, ctx()))
        assertFalse(ConditionEval.matches(Condition.AnyOf(listOf(no, no)), m, ctx()))
        assertTrue(ConditionEval.matches(Condition.Not(Condition.Not(yes)), m, ctx()))
        assertTrue("an empty all is true", ConditionEval.matches(Condition.All(emptyList()), m, ctx()))
        assertFalse("an empty any is false", ConditionEval.matches(Condition.AnyOf(emptyList()), m, ctx()))
    }

    @Test fun `each leaf`() {
        val c = ctx()
        // Header
        val m = h { copy(to = listOf(EmailAddress("me@example.org", "Me")), listId = "widgets.substack.com") }
        assertTrue(ConditionEval.matches(Condition.Header("to", HeaderOp.CONTAINS, "example.org"), m, c))
        assertTrue(ConditionEval.matches(Condition.Header("from", HeaderOp.EQUALS, "sam@corp.com"), m, c))
        assertFalse(ConditionEval.matches(Condition.Header("from", HeaderOp.EQUALS, "corp.com"), m, c))
        assertTrue(ConditionEval.matches(Condition.Header("subject", HeaderOp.REGEX, "^hel+o$"), m, c))
        assertFalse("a bad regex never matches", ConditionEval.matches(Condition.Header("subject", HeaderOp.REGEX, "("), m, c))
        assertTrue(ConditionEval.matches(Condition.Header("list-id", HeaderOp.EXISTS), m, c))
        assertFalse(ConditionEval.matches(Condition.Header("cc", HeaderOp.EXISTS), m, c))
        assertFalse("a header the store does not keep cannot match", ConditionEval.matches(Condition.Header("x-spam-score", HeaderOp.EXISTS), m, c))
        // age, size, list, category, reply, contact
        assertTrue(ConditionEval.matches(Condition.AgeOver(30), m, c)); assertFalse(ConditionEval.matches(Condition.AgeOver(120), m, c))
        assertTrue(ConditionEval.matches(Condition.SizeOver(999), m, c)); assertFalse(ConditionEval.matches(Condition.SizeOver(1_000), m, c))
        assertTrue(ConditionEval.matches(Condition.ListId("SUBSTACK"), m, c)); assertFalse(ConditionEval.matches(Condition.ListId("github"), m, c))
        assertTrue(ConditionEval.matches(Condition.InCategory(Category.PEOPLE), m, c)); assertFalse(ConditionEval.matches(Condition.InCategory(Category.TICKETS), m, c))
        assertFalse(ConditionEval.matches(Condition.IsReply, m, c))
        assertTrue(ConditionEval.matches(Condition.IsReply, m.copy(inReplyTo = "<a@b>"), c))
        assertTrue("a Re: subject", ConditionEval.matches(Condition.IsReply, m.copy(subject = "RE: lunch"), c))
        assertTrue(ConditionEval.matches(Condition.IsReply, m.copy(subject = "AW: Mittag"), c))
        assertFalse(ConditionEval.matches(Condition.FromKnownContact, m, c))
        assertTrue(ConditionEval.matches(Condition.FromKnownContact, m.copy(from = EmailAddress("Alice@Friends.net")), c))
        // query
        assertTrue(ConditionEval.matches(Condition.Query("from:sam"), m, c)); assertFalse(ConditionEval.matches(Condition.Query("from:zed"), m, c))
        assertTrue("a blank query matches everything", ConditionEval.matches(Condition.Query(""), m, c))
    }

    @Test fun `trees normalise and read aloud`() {
        val c = Condition.All(listOf(Condition.All(listOf(Condition.Query("a"), Condition.Query("b"))), Condition.Not(Condition.Not(Condition.Query("c")))))
        assertEquals(Condition.All(listOf(Condition.Query("a"), Condition.Query("b"), Condition.Query("c"))), c.normalized())
        assertEquals("older than 2 weeks and in Newsletters", Condition.All(listOf(Condition.AgeOver(14 * 24 * 60), Condition.InCategory(Category.NEWSLETTERS))).describe())
    }

    // ---- ordering, stopping, scope ----

    @Test fun `rules run in their order, and a stop hides the message from later ones`() {
        val a = rule("A", Condition.Query("sam"), RuleAction.Star, order = 2)
        val b = rule("B", Condition.Query("sam"), RuleAction.MarkRead, order = 1, stop = true)
        val c = rule("C", Condition.Query("sam"), RuleAction.Archive, order = 3)
        assertEquals(listOf(RuleAction.MarkRead), RuleEngine.evaluate(listOf(a, b, c), h(), ctx()))
        assertEquals("without the stop, all three run, by order", listOf(RuleAction.MarkRead, RuleAction.Star, RuleAction.Archive), RuleEngine.evaluate(listOf(a, b.copy(stopProcessing = false), c), h(), ctx()))
        assertEquals(listOf("B", "A", "C"), RuleEngine.evaluateDetailed(listOf(a, b.copy(stopProcessing = false), c), h(), ctx()).map { it.rule.name })
    }

    @Test fun `disabled, wrong trigger and out-of-scope rules do not run`() {
        val base = rule("R", Condition.Query("sam"), RuleAction.Star)
        assertTrue(RuleEngine.evaluate(listOf(base.copy(enabled = false)), h(), ctx()).isEmpty())
        assertTrue(RuleEngine.evaluate(listOf(base.withTrigger(Trigger.Manual)), h(), ctx()).isEmpty())
        assertEquals(listOf(RuleAction.Star), RuleEngine.evaluate(listOf(base.withTrigger(Trigger.OnSchedule(60))), h(), ctx(), on = RuleTrigger.SCHEDULED))
        assertTrue(RuleEngine.evaluate(listOf(base.copy(accountIds = setOf(99))), h(), ctx()).isEmpty())
        assertTrue(RuleEngine.evaluate(listOf(base.copy(mailboxIds = setOf(99))), h(), ctx()).isEmpty())
        assertEquals(listOf(RuleAction.Star), RuleEngine.evaluate(listOf(base.copy(accountIds = setOf(1), mailboxIds = setOf(1))), h(), ctx()))
    }

    @Test fun `a rule has one trigger view, and keeps the sets it was saved with`() {
        val old = MailRule(name = "x", query = "a", triggers = setOf(RuleTrigger.NEW_MAIL, RuleTrigger.SCHEDULED), intervalMinutes = 30, actions = emptyList())
        assertEquals(Trigger.OnArrival, old.trigger)
        assertEquals(setOf(RuleTrigger.NEW_MAIL, RuleTrigger.SCHEDULED), old.triggers)
        assertEquals(Trigger.OnSchedule(30), old.copy(triggers = setOf(RuleTrigger.SCHEDULED)).trigger)
        assertEquals(Trigger.Manual, old.copy(triggers = emptySet()).trigger)
        assertEquals(Condition.Query("a"), old.effectiveCondition)
    }

    @Test fun `forwarding waits for the person to confirm the rule once`() {
        val fwd = rule("F", Condition.Query("sam"), RuleAction.Forward("boss@corp.com"), RuleAction.MarkRead, confirmed = false)
        assertTrue(RuleAction.Forward("x@y.z").needsConfirmation)
        assertFalse(RuleAction.Archive.needsConfirmation)
        assertEquals("the rest of the rule still runs", listOf(RuleAction.MarkRead), RuleEngine.evaluate(listOf(fwd), h(), ctx()))
        assertEquals(listOf(RuleAction.Forward("boss@corp.com"), RuleAction.MarkRead), RuleEngine.evaluate(listOf(fwd.copy(confirmed = true)), h(), ctx()))
    }

    // ---- conflicts ----

    @Test fun `rules that fight are reported`() {
        val q = Condition.Query("from:acme")
        val toA = rule("To receipts", q, RuleAction.MoveTo(mailbox = "Receipts"), order = 1)
        val toB = rule("To finance", q, RuleAction.MoveTo(mailbox = "Finance"), order = 2)
        val c1 = RuleEngine.conflicts(listOf(toA, toB)).single()
        assertEquals(RuleConflict.Kind.CONFLICTING_ACTIONS, c1.kind); assertTrue(c1.certain)
        assertTrue(c1.summary.contains("different folders"))

        val trash = rule("Trash it", q, RuleAction.Trash, order = 1)
        val star = rule("Star it", Condition.All(listOf(q, Condition.IsReply)), RuleAction.Star, order = 2)
        val c2 = RuleEngine.conflicts(listOf(trash, star)).single()
        assertTrue("star is narrower than trash's condition, so they certainly overlap", c2.certain)

        assertTrue("both do the same thing", RuleEngine.conflicts(listOf(rule("a", q, RuleAction.Archive), rule("b", q, RuleAction.Archive, order = 1))).isEmpty())
        assertTrue("different categories cannot overlap", RuleEngine.conflicts(listOf(
            rule("a", Condition.InCategory(Category.NEWSLETTERS), RuleAction.Trash), rule("b", Condition.Query("category:receipts"), RuleAction.Star, order = 1))).isEmpty())
        assertTrue("different accounts cannot overlap", RuleEngine.conflicts(listOf(
            rule("a", q, RuleAction.Trash).copy(accountIds = setOf(1)), rule("b", q, RuleAction.Star, order = 1).copy(accountIds = setOf(2)))).isEmpty())
        assertEquals(1, RuleEngine.conflicts(listOf(rule("a", q, RuleAction.MarkRead), rule("b", q, RuleAction.MarkUnread, order = 1))).size)
        assertEquals(1, RuleEngine.conflicts(listOf(rule("a", q, RuleAction.AddLabel("X")), rule("b", q, RuleAction.RemoveLabel("x"), order = 1))).size)
        assertEquals(1, RuleEngine.conflicts(listOf(rule("a", q, RuleAction.Notify(NotifyLevel.URGENT)), rule("b", q, RuleAction.Notify(NotifyLevel.NEVER), order = 1))).size)
        assertTrue("disabled rules do not conflict", RuleEngine.conflicts(listOf(toA, toB.copy(enabled = false))).isEmpty())
    }

    @Test fun `a rule that stops processing shadows a later one it covers`() {
        val q = Condition.Query("from:acme")
        val first = rule("First", q, RuleAction.Archive, order = 1, stop = true)
        val second = rule("Second", Condition.All(listOf(q, Condition.IsReply)), RuleAction.Star, order = 2)
        val c = RuleEngine.conflicts(listOf(first, second)).single()
        assertEquals(RuleConflict.Kind.SHADOWED, c.kind)
    }

    @Test fun `a might-overlap pair is settled on real mail`() {
        val a = rule("A", Condition.Query("from:sam"), RuleAction.Trash, order = 1)
        val b = rule("B", Condition.Header("subject", HeaderOp.CONTAINS, "hello"), RuleAction.Star, order = 2)
        val found = RuleEngine.conflicts(listOf(a, b)).single()
        assertFalse(found.certain)
        assertEquals("a message that both match makes it certain", 1, RuleConflicts.confirmOnSample(listOf(found), listOf(h()), ctx()).size)
        assertTrue(RuleConflicts.confirmOnSample(listOf(found), listOf(h { copy(subject = "other") }), ctx()).isEmpty())
    }

    // ---- the batch engine agrees with the pure one ----

    @Test fun `the index-backed engine and the pure evaluator pick the same messages`() = runBlocking {
        val trees = mapOf(
            "newsletters" to Condition.Query("category:newsletters"),
            "substack, not today" to Condition.All(listOf(Condition.Query("from:@substack.com"), Condition.Not(Condition.AgeOver(24 * 60)))),
            "list or big" to Condition.AnyOf(listOf(Condition.ListId("substack"), Condition.SizeOver(1_000_000))),
            "reply from someone I know" to Condition.All(listOf(Condition.IsReply, Condition.FromKnownContact)),
            "not people" to Condition.Not(Condition.InCategory(Category.PEOPLE)),
            "lunch or flaky" to Condition.AnyOf(listOf(Condition.Query("lunch"), Condition.Header("subject", HeaderOp.CONTAINS, "flaky"))),
            "older than 30 days" to Condition.AgeOver(30 * 24 * 60),
            "older than 90 minutes, by hours" to Condition.AgeOver(90),
            "alice or reply, not starred" to Condition.All(listOf(Condition.AnyOf(listOf(Condition.Query("from:alice"), Condition.IsReply)), Condition.Not(Condition.Query("is:starred")))),
            "header only" to Condition.Header("from", HeaderOp.CONTAINS, "github"),
            "nested not/any" to Condition.Not(Condition.AnyOf(listOf(Condition.InCategory(Category.PEOPLE), Condition.InCategory(Category.REPLIES)))),
        )
        val all = store.headers(ids.values)
        val known = store.knownAddresses()
        val pure = RuleContext(search.queryMatcher(), now, zone) { it in known }
        for ((label, c) in trees) {
            val rule = MailRule(name = label, query = "", condition = c, actions = emptyList())
            val expected = all.filter { ConditionEval.matches(c, it, pure) }.map { it.id }.toSet()
            assertEquals("$label (listing)", expected, engine.idsFor(rule).toSet())
            val p = engine.preview(rule, sampleSize = 3)
            assertEquals("$label (count)", expected.size, p.count)
            assertTrue("$label (sample is part of the matches)", p.sampleIds.all { it in expected } && p.sampleIds.size == minOf(3, expected.size))
            assertEquals("$label (set algebra on a candidate set)", expected, engine.matchIds(c, all.map { it.id }.toSet()))
        }
    }

    @Test fun `scope limits what a rule touches`() = runBlocking {
        val r = MailRule(name = "x", query = "", condition = Condition.InCategory(Category.PEOPLE), actions = emptyList(), mailboxIds = setOf(archive.id))
        assertEquals(0, engine.preview(r).count)
        assertEquals(1, engine.preview(r.copy(mailboxIds = setOf(inbox.id))).count)
        assertEquals(0, engine.preview(r.copy(mailboxIds = emptySet(), accountIds = setOf(account.id + 100))).count)
    }

    // ---- running ----

    @Test fun `applying to existing mail is one batch with one undo`() = runBlocking {
        val r = MailRule(name = "Tidy", query = "", condition = Condition.Query("category:newsletters"), actions = listOf(RuleAction.MarkRead, RuleAction.Star, RuleAction.Archive), triggers = setOf(RuleTrigger.MANUAL))
        val before = store.header(ids.getValue("news"))!!
        val result = engine.applyToExisting(r)
        assertEquals(1, result.matched)
        val after = store.header(ids.getValue("news"))!!
        assertTrue(after.isSeen && after.isFlagged); assertEquals(archive.id, after.mailboxId)
        assertNotNull(result.undo)
        actions.undo(result.undo!!)
        val restored = store.header(ids.getValue("news"))!!
        assertEquals(before.flags, restored.flags); assertEquals(inbox.id, restored.mailboxId)
    }

    @Test fun `a rule forwards nothing until it is confirmed, then forwards on arrival`() = runBlocking {
        val r = store.saveRule(MailRule(name = "Fwd", query = "from:alice", actions = listOf(RuleAction.Forward("boss@corp.com"))))
        val fresh = store.headers(listOf(ids.getValue("alice")))
        engine.onNewMail(account, fresh)
        assertTrue("unconfirmed: nothing sent", forwarded.isEmpty())
        engine.run(r)
        assertTrue("and not on existing mail either", forwarded.isEmpty())
        store.saveRule(r.copy(confirmed = true))
        engine.onNewMail(account, fresh)
        assertEquals(listOf("${ids.getValue("alice")}->boss@corp.com"), forwarded)
    }

    @Test fun `copy to a folder is skipped, and said so, on a folder-style account`() = runBlocking {
        val r = MailRule(name = "Copy", query = "", condition = Condition.Query("from:alice"), actions = listOf(RuleAction.CopyTo(mailbox = "Receipts")), triggers = setOf(RuleTrigger.MANUAL))
        val res = engine.applyToExisting(r)
        assertEquals(1, res.matched); assertEquals(1, res.skipped.size); assertNull(res.undo)
    }

    @Test fun `an on-arrival rule using a tree runs on fresh mail`() = runBlocking {
        store.saveRule(MailRule(name = "Replies", query = "", condition = Condition.All(listOf(Condition.IsReply, Condition.FromKnownContact)), actions = listOf(RuleAction.Star)))
        engine.onNewMail(account, store.headers(ids.values))
        assertTrue(store.header(ids.getValue("priya"))!!.isFlagged)
        assertFalse(store.header(ids.getValue("alice"))!!.isFlagged)
    }

    // ---- storage and migration ----

    @Test fun `the new fields round-trip and rules keep their order`() = runBlocking {
        val a = store.saveRule(MailRule(name = "A", query = "x", actions = listOf(RuleAction.Star)), sortOrder = 5)
        val b = store.saveRule(MailRule(name = "B", query = "", condition = Condition.AnyOf(listOf(Condition.IsReply, Condition.AgeOver(60))), actions = listOf(RuleAction.CopyTo(role = MailboxRole.ARCHIVE)),
            runLocation = RunLocation.SERVER, mailboxIds = setOf(3), confirmed = true), sortOrder = 2)
        val read = store.rules()
        assertEquals(listOf("B", "A"), read.map { it.name })
        assertEquals(listOf(2, 5), read.map { it.order })
        assertEquals(b.condition, read[0].condition); assertEquals(RunLocation.SERVER, read[0].runLocation); assertEquals(setOf(3L), read[0].mailboxIds); assertTrue(read[0].confirmed)
        assertNotNull(a)
    }

    private val oldFixture = listOf(
        """{"id":0,"name":"Archive newsletters","enabled":true,"query":"category:newsletters older_than:7d","accountIds":[],"triggers":["SCHEDULED"],"actions":[{"type":"mark_read"},{"type":"archive"}],"stopProcessing":false,"intervalMinutes":720,"lastRunAt":1700000000000,"matchCount":42,"createdAt":1690000000000}""",
        """{"id":0,"name":"Work to folder","enabled":false,"query":"from:@corp.com","accountIds":[7],"triggers":["NEW_MAIL"],"actions":[{"type":"move","mailbox":"Work"},{"type":"label","label":"Work"},{"type":"snooze","minutes":120}],"stopProcessing":true,"intervalMinutes":1440,"lastRunAt":0,"matchCount":0,"createdAt":0}""",
        """{"id":0,"name":"Forward bills","enabled":true,"query":"from:billing","accountIds":[],"triggers":["NEW_MAIL","MANUAL"],"actions":[{"type":"forward","to":"accounts@home.org"},{"type":"notify","level":"NEVER"},{"type":"auto_reply","subjectPrefix":"Re: ","body":"Thanks"}],"stopProcessing":false,"intervalMinutes":1440,"lastRunAt":0,"matchCount":3,"createdAt":5}""",
    )

    @Test fun `rules saved before v2 migrate without losing anything`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        oldFixture.forEachIndexed { i, old ->
            val before = json.decodeFromString(MailRule.serializer(), old)
            val migrated = json.decodeFromString(MailRule.serializer(), RuleMigration.upgrade(old, i + 10))
            // Everything that was there is exactly as it was.
            assertEquals(before.copy(order = i + 10, runLocation = RunLocation.DEVICE, confirmed = migrated.confirmed), migrated)
            assertNull(migrated.condition); assertEquals(Condition.Query(before.query), migrated.effectiveCondition)
        }
        val fwd = json.decodeFromString(MailRule.serializer(), RuleMigration.upgrade(oldFixture[2], 12))
        assertTrue("a rule that already forwarded keeps forwarding", fwd.confirmed)
        assertFalse(json.decodeFromString(MailRule.serializer(), RuleMigration.upgrade(oldFixture[0], 10)).confirmed)
        // Idempotent: upgrading an upgraded rule changes nothing.
        val once = RuleMigration.upgrade(oldFixture[1], 3)
        assertEquals(once, RuleMigration.upgrade(once, 99))
    }

    @Test fun `opening a version-3 database upgrades its saved rules in place`() = runBlocking {
        val path = File(dir, "v3.db").absolutePath
        val s1 = MailStore(BundledSQLiteDriver(), path)
        s1.db.write(setOf("rule")) {
            oldFixture.forEachIndexed { i, j -> exec("INSERT INTO rule(json, sort_order) VALUES (?, ?)", j, i * 3) }
            execScript("PRAGMA user_version = 3")
        }
        s1.close()
        val s2 = MailStore(BundledSQLiteDriver(), path)
        val rules = s2.rules()
        assertEquals(listOf("Archive newsletters", "Work to folder", "Forward bills"), rules.map { it.name })
        assertEquals(listOf(0, 3, 6), rules.map { it.order })
        assertEquals(setOf(7L), rules[1].accountIds); assertEquals(42L, rules[0].matchCount); assertEquals(720, rules[0].intervalMinutes)
        assertTrue(rules.all { it.runLocation == RunLocation.DEVICE })
        assertTrue(rules[2].confirmed)
        assertEquals(RuleAction.MoveTo(mailbox = "Work"), rules[1].actions[0])
        s2.close()
        val s3 = MailStore(BundledSQLiteDriver(), path)   // a second open does nothing more
        assertEquals(rules, s3.rules()); s3.close()
    }

    @Test fun `scheduled rules come due by their interval`() {
        val r = MailRule(name = "S", query = "x", triggers = setOf(RuleTrigger.SCHEDULED), intervalMinutes = 60, actions = emptyList(), lastRunAt = now - 2 * hour)
        assertEquals(listOf("S"), RuleScheduler.due(listOf(r, r.copy(name = "recent", lastRunAt = now - 10 * 60_000), r.copy(name = "off", enabled = false), r.copy(name = "arrival", triggers = setOf(RuleTrigger.NEW_MAIL))), now).map { it.name })
        assertEquals(now - hour, RuleScheduler.nextRunAt(r, now))
        assertEquals(now, RuleScheduler.nextRunAt(r.copy(lastRunAt = 0), now))
        assertNull(RuleScheduler.nextRunAt(r.copy(triggers = setOf(RuleTrigger.MANUAL)), now))
        assertEquals(30, RuleScheduler.wakeEveryMinutes(listOf(r, r.copy(intervalMinutes = 30))))
    }
}
