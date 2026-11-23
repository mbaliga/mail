package dev.aarso.mail.perf

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
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.search.SortOrder
import dev.aarso.mail.select.Selection
import dev.aarso.mail.select.SelectionEngine
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Random

/**
 * Speed is a feature, so it has a test. A realistic mailbox (default 100,000 messages with bodies, a
 * few hundred senders, four folders, two years) is seeded once and every operation the screens
 * depend on is timed. The budgets are deliberately loose so a slow CI machine passes; the printed
 * table is what to read. Set -Dmail.perf.n=250000 for a bigger mailbox.
 */
class PerfTest {
    private val n = System.getProperty("mail.perf.n")?.toInt() ?: 100_000
    private val now = 1_793_534_400_000L
    private val day = 86_400_000L
    private lateinit var dir: File
    private lateinit var store: MailStore
    private lateinit var search: MailSearch
    private lateinit var inbox: Mailbox
    private lateinit var account: Account
    private val results = ArrayList<Triple<String, Long, Long>>()
    private val over = ArrayList<String>()

    private val words = ("invoice receipt order shipped delivery meeting lunch friday proposal draft review quarterly report budget travel flight " +
        "hotel booking confirmation password security alert newsletter weekly digest update release notes pull request merged comment " +
        "thanks please schedule call agenda minutes contract signed renewal subscription payment refund statement account balance").split(' ')
    private val senders = (0 until 400).map { EmailAddress("person$it@${listOf("example.com", "work.org", "shop.io", "bank.net", "news.co")[it % 5]}", "Person $it") }

    @Before fun seed() = runBlocking {
        dir = createTempDir("perf")
        store = MailStore(BundledSQLiteDriver(), File(dir, "m.db").absolutePath)
        account = store.saveAccount(Account(email = "me@example.org", displayName = "Me", providerId = "gmail", protocol = IncomingProtocol.IMAP,
            auth = AuthKind.PASSWORD, secret = SecretRef("s"), identities = listOf(Identity(EmailAddress("me@example.org"), isDefault = true))))
        val boxes = store.upsertMailboxes(account.id, listOf(
            Mailbox(accountId = account.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX),
            Mailbox(accountId = account.id, remoteId = "Archive", name = "Archive", role = MailboxRole.ARCHIVE),
            Mailbox(accountId = account.id, remoteId = "Sent", name = "Sent", role = MailboxRole.SENT),
            Mailbox(accountId = account.id, remoteId = "Trash", name = "Trash", role = MailboxRole.TRASH),
        ), removeOthers = true)
        inbox = boxes.first { it.role == MailboxRole.INBOX }
        search = MailSearch(store, ZoneId.of("UTC"), Clock { now })
        val rnd = Random(7)
        val t0 = System.nanoTime()
        var uid = 1L
        val cats = Category.entries
        for (batch in 0 until n / 1000) {
            val hs = (0 until 1000).map {
                val r = rnd.nextInt(100)
                val box = when { r < 55 -> boxes[0]; r < 90 -> boxes[1]; r < 96 -> boxes[2]; else -> boxes[3] }
                val s = senders[rnd.nextInt(senders.size)]
                val subj = (0 until 4).joinToString(" ") { words[rnd.nextInt(words.size)] }
                MessageHeader(accountId = account.id, mailboxId = box.id, uid = uid++, messageId = "<$uid@x>", threadKey = "t${uid / 3}",
                    subject = subj, from = s, to = listOf(EmailAddress("me@example.org")), receivedAt = now - rnd.nextInt(730) * day - rnd.nextInt(86_400) * 1000L,
                    flags = if (rnd.nextInt(100) < 60) Flags.SEEN else 0, attachmentCount = if (rnd.nextInt(100) < 12) 1 else 0, size = 2_000L + rnd.nextInt(900_000),
                    category = cats[rnd.nextInt(cats.size)], preview = (0 until 12).joinToString(" ") { words[rnd.nextInt(words.size)] },
                    isBulk = rnd.nextInt(100) < 30, listId = if (rnd.nextInt(100) < 20) "list${rnd.nextInt(30)}.example" else null)
            }
            store.insertMessages(hs)
        }
        val seedMs = (System.nanoTime() - t0) / 1_000_000
        println("PERF seeded $n messages in $seedMs ms (${n * 1000L / seedMs.coerceAtLeast(1)} msgs/s)")
        search.refreshAccounts()
    }

    @After fun tearDown() { store.close(); dir.deleteRecursively() }

    private suspend fun time(name: String, budgetMs: Long, runs: Int = 7, block: suspend () -> Unit) {
        println("PERF >> $name")
        val first = System.nanoTime()
        block() // warm: JIT, page cache, prepared statements
        println("PERF    first run ${(System.nanoTime() - first) / 1_000_000} ms")
        val ts = (0 until runs).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1000 }.sorted()
        val median = ts[ts.size / 2]
        results += Triple(name, median, ts.last())
        if (median / 1000 > budgetMs) over += "$name: median ${median / 1000.0} ms > $budgetMs ms budget"
    }

    @Test fun `every operation the screens depend on is fast`() = runBlocking {
        val inboxScope = SearchScope(mailboxIds = setOf(inbox.id))
        time("list: inbox, newest 150", 25) { search.search(search.parse("in:inbox", natural = false), limit = 150, countTotal = false) }
        time("list: inbox, 1,500 rows (scrolled far)", 80) { search.search(search.parse("in:inbox", natural = false), limit = 1500, countTotal = false) }
        time("list: inbox, deep page (offset 20k)", 250) { search.search(search.parse("in:inbox", natural = false), limit = 150, offset = 20_000, countTotal = false) }
        time("list: one folder by mailbox id", 25) { search.search(search.parse("", natural = false), inboxScope, limit = 150, countTotal = false) }
        time("count: in:inbox", 120) { search.count(search.parse("in:inbox", natural = false)) }
        time("count: unread inbox", 100) { search.count(search.parse("in:inbox is:unread", natural = false)) }
        time("digest: since yesterday + tabs", 120) { DigestBuilder(search, Clock { now }, ZoneId.of("UTC")).build(emptySet(), DigestWindow.LAST_30_DAYS, 0) }
        time("search: one word (FTS, newest)", 150) { search.search(search.parse("invoice", natural = false), limit = 150) }
        time("search: two words (FTS, newest)", 120) { search.search(search.parse("invoice refund", natural = false), limit = 150) }
        time("search: one word, relevance order", 250) { search.search(search.parse("invoice", natural = false), sort = SortOrder.RELEVANCE, limit = 150) }
        time("search: prefix as you type (inv*)", 150) { search.search(search.parse("inv", natural = false), limit = 50) }
        time("search: from + has + older_than", 250) { search.search(search.parse("from:person7 has:attachment older_than:30d", natural = false), limit = 150, countTotal = false) }
        time("search: natural language", 60) { search.search(search.parse("unread newsletters from last month with attachments"), limit = 150, countTotal = false) }
        time("search: word + structured filters", 80) { search.search(search.parse("budget category:finance is:unread", natural = false), limit = 150, countTotal = false) }
        time("search: regex (residual verification)", 200) { search.search(search.parse("/inv(oice)?[a-z]*/", natural = false), limit = 150, countTotal = false) }
        time("select all matching: count", 100) { SelectionEngine(search).count(Selection.allMatching("is:unread older_than:90d")) }
        time("select all matching: resolve ids", 400) { SelectionEngine(search).resolve(Selection.allMatching("in:inbox older_than:365d")) }
        time("facets: sender, category, account, year", 400) { search.facets(search.parse("in:inbox", natural = false)) }
        time("facet counts by category", 400) { search.groupCounts(search.parse("in:inbox", natural = false), SearchScope(), "m.category") }
        // ---- rules: what the editor's "Matches N messages" line costs ----
        val rules = dev.aarso.mail.rules.RuleEngine(store, search, dev.aarso.mail.sync.MailActions(store, Clock { now }), clock = Clock { now }, zone = ZoneId.of("UTC"))
        fun rule(c: dev.aarso.mail.model.Condition) = dev.aarso.mail.model.MailRule(name = "perf", query = "", condition = c, actions = emptyList())
        val old = "category:newsletters older_than:30d is:unread"
        time("rule preview, before (query: search + count)", 300) { search.search(search.parse(old, natural = false), limit = 50).total }
        time("rule preview: query only", 300) { rules.preview(rule(dev.aarso.mail.model.Condition.Query(old))) }
        time("rule preview: query + age + size (all pushed down)", 300) {
            rules.preview(rule(dev.aarso.mail.model.Condition.All(listOf(dev.aarso.mail.model.Condition.Query("category:finance"), dev.aarso.mail.model.Condition.AgeOver(30 * 24 * 60), dev.aarso.mail.model.Condition.SizeOver(100_000)))))
        }
        time("rule preview: mixed (query + header + reply)", 800) {
            rules.preview(rule(dev.aarso.mail.model.Condition.All(listOf(dev.aarso.mail.model.Condition.Query("has:attachment older_than:30d"), dev.aarso.mail.model.Condition.Header("from", dev.aarso.mail.model.HeaderOp.CONTAINS, "person7"), dev.aarso.mail.model.Condition.Not(dev.aarso.mail.model.Condition.IsReply)))))
        }
        time("rule preview: any-of, one branch has no index (full scan)", 800) {
            rules.preview(rule(dev.aarso.mail.model.Condition.AnyOf(listOf(dev.aarso.mail.model.Condition.Query("from:person7"), dev.aarso.mail.model.Condition.IsReply))))
        }
        time("rule preview: any-of with a header branch (narrowed)", 800) {
            rules.preview(rule(dev.aarso.mail.model.Condition.AnyOf(listOf(dev.aarso.mail.model.Condition.Query("from:person7"), dev.aarso.mail.model.Condition.Header("subject", dev.aarso.mail.model.HeaderOp.CONTAINS, "flight")))))
        }
        val ids = search.search(search.parse("in:inbox", natural = false), limit = 150).headers.map { it.id }
        time("matchesAmong 150 ids", 40) { search.matchesAmong(search.parse("is:unread", natural = false), ids.toSet()) }
        time("headers(150 ids)", 30) { store.headers(ids) }
        time("thread messages", 20) { store.threadMessages("t5000", account.id) }
        for (q in listOf("in:inbox", "from:person7 has:attachment older_than:30d", "is:unread older_than:90d")) {
            println("PERF explain $q"); search.explain(q).forEach { println("PERF    $it") }
        }
        println("PERF table (median / worst, ms):")
        for ((name, med, worst) in results) println("PERF  %-42s %8.2f / %8.2f".format(name, med / 1000.0, worst / 1000.0))
        assertTrue("over budget:\n" + over.joinToString("\n"), over.isEmpty())
    }

    /**
     * Not a pass/fail test: tries alternative SQL shapes for the queries that matter, on the real
     * bundled SQLite, with and without table statistics, and prints the timings. The planner in
     * Python's SQLite does not behave like Android's, so this is where such questions are answered.
     */
    @Test fun `sql shape experiments`() = runBlocking {
        if (System.getProperty("mail.perf.experiments") != "true") return@runBlocking
        fun ms(block: () -> Unit): Double { block(); val ts = (0 until 5).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1000 }.sorted(); return ts[2] / 1000.0 }
        val queries = listOf("in:inbox", "from:person7 has:attachment older_than:30d", "is:unread older_than:90d", "in:inbox is:unread")
        suspend fun round(label: String, create: String) {
            for (q in queries) {
                val (where, args) = search.pushedDown(q)
                val list = { join: String, hint: String -> "SELECT m.id FROM message m $hint $join mailbox mb ON mb.id = m.mailbox_id WHERE $where ORDER BY m.received_at DESC, m.id DESC LIMIT 150" }
                val variants = listOf("JOIN" to list("JOIN", ""), "CROSS JOIN" to list("CROSS JOIN", ""),
                    "date-index hint" to list("JOIN", "INDEXED BY message_date_full").replace("FROM message m INDEXED BY message_date_full JOIN", "FROM message m INDEXED BY message_date_full JOIN"))
                for ((name, sql) in variants) {
                    val t = runCatching { ms { runBlocking { store.db.read { query(sql, *args.toTypedArray()) { it.long(0) } } } } }.getOrNull()
                    println("PERF exp  %-28s %-38s %-16s %s".format(label, q, name, t?.let { "%.1f ms".format(it) } ?: "n/a"))
                }
                val count = ms { runBlocking { search.count(search.parse(q, natural = false)) } }
                println("PERF exp  %-28s %-38s %-16s %.1f ms".format(label, q, "count()", count))
            }
        }
        suspend fun counts(label: String) {
            for (q in listOf("in:inbox", "in:inbox is:unread", "is:unread older_than:90d", "category:newsletters", "has:attachment")) {
                val t = ms { runBlocking { search.count(search.parse(q, natural = false)) } }
                println("PERF exp  %-28s %-38s %-16s %.1f ms".format(label, q, "count()", t))
            }
        }
        counts("count, current indexes")
        store.db.write(setOf("message")) {
            exec("CREATE INDEX IF NOT EXISTS message_cover ON message(received_at DESC, mailbox_id, flags, hidden, snoozed_until, attachment_count, category)")
        }
        counts("count, covering index")
        if (true) { println("PERF exp done"); return@runBlocking }
        round("current indexes, no stats", "")
        store.db.write(setOf("message")) {
            exec("DROP INDEX IF EXISTS message_date_full"); exec("CREATE INDEX message_date_full ON message(received_at DESC, id DESC)")
            exec("DROP INDEX IF EXISTS message_mailbox_date_full"); exec("CREATE INDEX message_mailbox_date_full ON message(mailbox_id, received_at DESC, id DESC)")
        }
        round("+ composite date indexes", "")
        store.db.write(emptySet()) { exec("ANALYZE") }
        round("+ full ANALYZE", "")
        println("PERF exp done")
    }
}
