package dev.aarso.mail.sieve

import dev.aarso.mail.model.Category
import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.HeaderOp
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.search.MailFields
import dev.aarso.search.EvalContext
import dev.aarso.search.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Golden files: each case's script is compared with `src/test/resources/sieve/<name>.sieve`. After an intended change, regenerate with
 * `./gradlew :mail-core:test --tests '*SieveCompilerTest*' -Dsieve.update=true` and read the diff.
 */
class SieveCompilerTest {

    private val fields = MailFields()
    private val compiler = SieveCompiler { QueryParser.parse(it, fields.registry, EvalContext(0L), ZoneId.of("UTC")) }
    private val caps = ServerCapabilities(
        ServerCapabilities.Transport.MANAGESIEVE, setOf("fileinto", "imap4flags", "copy"),
        accountAddresses = setOf("me@example.org", "alias@example.org"),
        folderName = { role, name -> name ?: when (role) { MailboxRole.ARCHIVE -> "Archive"; MailboxRole.TRASH -> "Trash"; MailboxRole.JUNK -> "Junk"; else -> null } },
    )

    private fun rule(c: Condition, vararg a: RuleAction, id: Long = 1, name: String = "Rule", stop: Boolean = false, confirmed: Boolean = false) =
        MailRule(id = id, name = name, query = "", condition = c, actions = a.toList(), stopProcessing = stop, confirmed = confirmed)

    private fun q(text: String) = Condition.Query(text)

    private fun golden(name: String, actual: String) {
        val f = File("src/test/resources/sieve/$name.sieve")
        if (System.getProperty("sieve.update") == "true") { f.parentFile.mkdirs(); f.writeText(actual); return }
        assertTrue("missing golden file ${f.path}; run with -Dsieve.update=true", f.exists())
        assertEquals("golden $name", f.readText(), actual)
    }

    private fun body(r: MailRule): String = (compiler.compile(r, caps) as? Compiled ?: error("not portable: ${compiler.compile(r, caps)}")).body

    private fun reasons(r: MailRule, caps: ServerCapabilities = this.caps): List<String> = (compiler.compile(r, caps) as? NotPortable ?: error("expected NotPortable, got ${compiler.compile(r, caps)}")).reasons

    // ---- one golden per condition ----

    @Test fun `conditions in the search grammar`() {
        val star = RuleAction.Star
        val cases = linkedMapOf(
            "cond-from-plain" to q("from:alice"),
            "cond-from-domain" to q("from:@substack.com"),
            "cond-from-me" to q("from:me"),
            "cond-to-plain" to q("to:bob"),
            "cond-to-me" to q("to:me"),
            "cond-cc" to q("cc:carol"),
            "cond-subject" to q("subject:\"weekly digest\""),
            "cond-list" to q("list:widgets.example"),
            "cond-list-any" to q("list:any"),
            "cond-domain" to q("domain:acme.io"),
            "cond-size-over" to q("size:>5mb"),
            "cond-size-under" to q("smaller:10k"),
            "cond-size-gte" to q("size:>=1mb"),
            "cond-larger" to q("larger:2mb"),
            "cond-not" to q("-from:alice"),
            "cond-and" to q("from:alice subject:lunch"),
            "cond-or" to q("from:alice OR from:@acme.io"),
            "cond-nested" to q("(from:alice OR from:bob) -subject:spam larger:1mb"),
            "cond-blank" to q(""),
        )
        for ((name, c) in cases) golden(name, body(rule(c, star)))
    }

    @Test fun `conditions that are leaves of the tree`() {
        val star = RuleAction.Star
        val cases = linkedMapOf(
            "leaf-header-contains" to Condition.Header("subject", HeaderOp.CONTAINS, "invoice"),
            "leaf-header-equals-address" to Condition.Header("from", HeaderOp.EQUALS, "billing@acme.io"),
            "leaf-header-equals-plain" to Condition.Header("list-id", HeaderOp.EQUALS, "widgets.example"),
            "leaf-header-exists" to Condition.Header("list-unsubscribe", HeaderOp.EXISTS),
            "leaf-size-over" to Condition.SizeOver(2_000_000),
            "leaf-list-id" to Condition.ListId("substack"),
            "tree-all" to Condition.All(listOf(q("from:alice"), Condition.SizeOver(1000))),
            "tree-any" to Condition.AnyOf(listOf(Condition.ListId("a"), Condition.ListId("b"))),
            "tree-not" to Condition.Not(Condition.Header("subject", HeaderOp.CONTAINS, "re:")),
        )
        for ((name, c) in cases) golden(name, body(rule(c, star)))
    }

    // ---- one golden per action ----

    @Test fun `each action`() {
        val c = q("from:alice")
        val cases = linkedMapOf(
            "act-mark-read" to rule(c, RuleAction.MarkRead),
            "act-mark-unread" to rule(c, RuleAction.MarkUnread),
            "act-star" to rule(c, RuleAction.Star),
            "act-unstar" to rule(c, RuleAction.Unstar),
            "act-archive" to rule(c, RuleAction.Archive),
            "act-trash" to rule(c, RuleAction.Trash),
            "act-spam" to rule(c, RuleAction.Spam),
            "act-move-name" to rule(c, RuleAction.MoveTo(mailbox = "Receipts/2026")),
            "act-move-role" to rule(c, RuleAction.MoveTo(role = MailboxRole.ARCHIVE)),
            "act-copy-name" to rule(c, RuleAction.CopyTo(mailbox = "Backup")),
            "act-forward-confirmed" to rule(c, RuleAction.Forward("boss@corp.com"), confirmed = true),
            "act-combined-order" to rule(c, RuleAction.Archive, RuleAction.Forward("x@y.z"), RuleAction.MarkRead, RuleAction.CopyTo(mailbox = "Backup"), RuleAction.Star, confirmed = true),
            "act-stop" to rule(c, RuleAction.MarkRead, stop = true),
            "act-escaping" to rule(Condition.Header("subject", HeaderOp.CONTAINS, "say \"hi\" \\ now"), RuleAction.MoveTo(mailbox = "A \"quoted\" folder")),
        )
        for ((name, r) in cases) golden(name, body(r))
    }

    @Test fun `a whole script`() {
        val rules = listOf(
            rule(q("from:@github.com"), RuleAction.MarkRead, RuleAction.MoveTo(mailbox = "GitHub"), id = 1, name = "GitHub to its folder"),
            rule(q("category:newsletters"), RuleAction.Archive, id = 2, name = "Newsletters (a tab, so it stays on the phone)"),
            rule(Condition.AnyOf(listOf(Condition.ListId("substack"), Condition.SizeOver(5_000_000))), RuleAction.Star, id = 3, name = "Lists or big\nmultiline name", stop = true),
        ).mapIndexed { i, r -> r.copy(order = i) }
        val s = compiler.script(rules, caps)
        golden("script-three-rules", s.text)
        assertEquals(listOf(1L, 3L), s.included.map { it.id })
        assertEquals(setOf(2L), s.notPortable.keys.map { it.id }.toSet())
        assertTrue(s.notPortable.values.single().single().contains("tabs"))
        golden("script-empty", compiler.script(emptyList(), caps).text)
    }

    // ---- what must NOT compile ----

    @Test fun `what a server cannot do is refused, with the reason, never approximated`() {
        val star = RuleAction.Star
        fun r(c: Condition, vararg a: RuleAction = arrayOf(star)) = rule(c, *a)
        assertTrue(reasons(r(q("invoice"))).single().contains("words"))                               // full text
        assertTrue(reasons(r(q("\"exact phrase\""))).single().contains("words"))
        assertTrue(reasons(r(q("/re+gex/"))).single().contains("regular"))
        assertTrue(reasons(r(q("category:receipts"))).single().contains("category"))
        assertTrue(reasons(r(q("older_than:7d"))).single().contains("older_than"))
        assertTrue(reasons(r(q("has:attachment"))).single().contains("has"))
        assertTrue(reasons(r(q("is:unread"))).single().contains("is"))
        assertTrue(reasons(r(q("size:5mb"))).single().contains("exact size"))
        assertTrue(reasons(r(q("from:al*"))).single().contains("*"))
        assertTrue(reasons(r(Condition.AgeOver(60))).single().contains("older than"))
        assertTrue(reasons(r(Condition.IsReply)).single().contains("reply"))
        assertTrue(reasons(r(Condition.FromKnownContact)).single().contains("contacts"))
        assertTrue(reasons(r(Condition.InCategory(Category.RECEIPTS))).single().contains("Receipts"))
        assertTrue(reasons(r(Condition.Header("subject", HeaderOp.REGEX, "^a"))).single().contains("regular"))
        assertTrue("one unportable leaf makes the whole tree unportable", reasons(r(Condition.All(listOf(q("from:alice"), Condition.IsReply)))).isNotEmpty())
        assertTrue(reasons(r(Condition.Not(Condition.FromKnownContact))).isNotEmpty())
        // actions
        for ((a, word) in listOf<Pair<RuleAction, String>>(
            RuleAction.AddLabel("x") to "labels", RuleAction.RemoveLabel("x") to "labels", RuleAction.Categorize(Category.RECEIPTS) to "tabs", RuleAction.Snooze(60) to "snoozing",
            RuleAction.Notify(NotifyLevel.NEVER) to "notifications", RuleAction.Pin to "pinning", RuleAction.Mute to "pinning", RuleAction.Summarize to "summaries",
            RuleAction.AutoReply(body = "hi") to "automatic replies",
        )) assertTrue("${a} → ${word}", reasons(rule(q("from:alice"), a)).any { it.contains(word) })
        assertTrue("forwarding waits for confirmation", reasons(rule(q("from:alice"), RuleAction.Forward("a@b.c"), confirmed = false)).single().contains("confirm"))
        assertTrue("two destinations", reasons(rule(q("from:alice"), RuleAction.Archive, RuleAction.Trash)).single().contains("more than one folder"))
        assertTrue("no actions", reasons(rule(q("from:alice"))).single().contains("nothing to do"))
        // trigger and scope
        assertTrue(reasons(rule(q("from:alice"), star).copy(triggers = setOf(RuleTrigger.SCHEDULED))).single().contains("schedule"))
        assertTrue(reasons(rule(q("from:alice"), star).copy(triggers = setOf(RuleTrigger.MANUAL))).single().contains("when you ask"))
        assertTrue(reasons(rule(q("from:alice"), star).copy(mailboxIds = setOf(4))).single().contains("folders"))
        // capabilities
        val bare = ServerCapabilities(ServerCapabilities.Transport.MANAGESIEVE, setOf("fileinto"), setOf("me@example.org"), caps.folderName)
        assertTrue(reasons(rule(q("from:alice"), star), bare).single().contains("imap4flags"))
        assertTrue(reasons(rule(q("from:alice"), RuleAction.CopyTo(mailbox = "B")), bare).single().contains("copy"))
        assertTrue("an unknown folder", reasons(rule(q("from:alice"), RuleAction.Archive), ServerCapabilities(ServerCapabilities.Transport.MANAGESIEVE, setOf("fileinto"), emptySet(), { _, _ -> null })).single().contains("folder"))
        assertTrue("from:me needs the account's addresses", reasons(rule(q("from:me"), star), ServerCapabilities(ServerCapabilities.Transport.MANAGESIEVE, setOf("imap4flags"))).single().contains("addresses"))
    }

    @Test fun `warnings are notes, not refusals`() {
        val r = compiler.compile(rule(q("from:alice"), RuleAction.Star), caps) as Compiled
        assertTrue(r.warnings.single().contains("ASCII"))
        assertTrue((compiler.compile(rule(q("subject:x"), RuleAction.Star), caps) as Compiled).warnings.isEmpty())
    }

    @Test fun `the stubs for Gmail and Outlook refuse politely and do no I-O`() {
        assertTrue(GmailFilterCompiler.compile(rule(q("from:a"), RuleAction.Star), caps) is NotPortable)
        assertTrue(GraphRuleCompiler.compile(rule(q("from:a"), RuleAction.Star), caps) is NotPortable)
    }
}
