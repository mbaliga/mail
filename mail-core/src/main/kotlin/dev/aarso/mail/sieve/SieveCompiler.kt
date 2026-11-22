package dev.aarso.mail.sieve

import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.HeaderOp
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.Trigger
import dev.aarso.mail.model.normalized
import dev.aarso.mail.search.MailFields
import dev.aarso.search.Op
import dev.aarso.search.ParsedQuery
import dev.aarso.search.QueryNode

/**
 * Rules → Sieve (RFC 5228, with `fileinto`, `imap4flags` RFC 5232 and `copy` RFC 3894).
 *
 * The rule is the source of truth and this is a translation, held to one standard: **a rule compiles only if the server
 * would do exactly what the phone would**. Anything Sieve cannot say — a full-text word, a category, "older than", a label, a
 * snooze — makes the whole rule [NotPortable] with the reason, and it stays on the phone. It is never approximated.
 *
 * What compiles:
 *  - Conditions: `from:`, `to:`, `cc:`, `subject:`, `list:`, `domain:`, `size:`/`larger:`/`smaller:` in the search grammar, with
 *    and / or / not; and the leaves [Condition.Header] (contains, equals, exists), [Condition.SizeOver] and [Condition.ListId].
 *  - Actions: mark read/unread, star/unstar (flags), archive / trash / spam / move (`fileinto`), copy to a folder
 *    (`fileinto :copy`), forward (`redirect :copy`, only for a rule the person has confirmed).
 *  - Only for mail arriving: a rule that runs on a schedule or on demand has nothing for a server to do.
 *
 * Small differences that are noted as warnings rather than refusals: Sieve folds case for ASCII only (the phone folds all of Unicode),
 * and a rule that sends the same message to two different folders files it in both on a server (the phone ends with the last).
 * [dev.aarso.mail.rules.RuleConflicts] already reports the second case.
 */
class SieveCompiler(private val parse: (String) -> ParsedQuery) : RuleCompiler {

    private class Ctx(val caps: ServerCapabilities) {
        val reasons = ArrayList<String>()
        val extensions = linkedSetOf<String>()
        val warnings = ArrayList<String>()
    }

    override fun compile(rule: MailRule, capabilities: ServerCapabilities): CompileResult {
        val c = Ctx(capabilities)
        if (rule.trigger != Trigger.OnArrival) c.reasons += "it runs ${if (rule.trigger == Trigger.Manual) "only when you ask" else "on a schedule"}, and a server only sees mail as it arrives"
        if (rule.mailboxIds.isNotEmpty()) c.reasons += "it is limited to some folders, and a server filters mail as it is delivered"
        val test = test(rule.effectiveCondition.normalized(), c)
        val acts = actions(rule, c)
        c.extensions.forEach { if (it !in capabilities.extensions) c.reasons += "the server doesn't offer the “$it” Sieve extension" }
        if (c.reasons.isNotEmpty() || test == null || acts == null) return NotPortable(c.reasons.distinct().ifEmpty { listOf("it can't be expressed for a server") })
        val body = buildString {
            append("if ").append(test).append(" {\n")
            acts.forEach { append("    ").append(it).append('\n') }
            if (rule.stopProcessing) append("    stop;\n")
            append("}\n")
        }
        return Compiled(body, c.extensions, c.warnings.distinct())
    }

    /** The part of a rule a server can carry for a rule split between server and phone: the actions that compile, or null if the condition itself doesn't. */
    fun compileActionsOnly(rule: MailRule, capabilities: ServerCapabilities): Pair<Compiled, List<Int>>? {
        val portable = rule.actions.indices.filter { i -> compile(rule.copy(actions = listOf(rule.actions[i]), stopProcessing = false), capabilities) is Compiled }
        if (portable.isEmpty()) return null
        val r = compile(rule.copy(actions = portable.map { rule.actions[it] }), capabilities)
        return (r as? Compiled)?.let { it to portable }
    }

    // ---- a whole script ----

    data class Script(val text: String, val included: List<MailRule>, val notPortable: Map<MailRule, List<String>>, val warnings: Map<MailRule, List<String>>)

    /** One script for an account: every rule that compiles, in rule order, each as its own block. Rules that don't compile are reported and left out. */
    fun script(rules: List<MailRule>, caps: ServerCapabilities): Script {
        val included = ArrayList<MailRule>(); val out = LinkedHashMap<MailRule, List<String>>(); val warn = LinkedHashMap<MailRule, List<String>>()
        val blocks = ArrayList<String>(); val required = linkedSetOf<String>()
        for (r in rules.sortedWith(compareBy({ it.order }, { it.id }))) {
            when (val res = compile(r, caps)) {
                is Compiled -> { included += r; blocks += "# ${comment(r.name)} (rule ${r.id})\n" + res.body; required += res.extensions; if (res.warnings.isNotEmpty()) warn[r] = res.warnings }
                is NotPortable -> out[r] = res.reasons
            }
        }
        val text = buildString {
            append("# Generated by You've got Mail. Change the rules in the app; this script is replaced when they change.\n")
            if (required.isNotEmpty()) append("require [").append(required.joinToString(", ") { "\"$it\"" }).append("];\n")
            append('\n')
            blocks.forEach { append(it).append('\n') }
        }
        return Script(text, included, out, warn)
    }

    private fun comment(s: String) = s.replace(Regex("[\\r\\n]+"), " ").take(120)

    // ---- conditions ----

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun list(xs: List<String>) = "[" + xs.joinToString(", ") { q(it) } + "]"

    private fun test(c: Condition, x: Ctx): String? = when (c) {
        is Condition.All -> joinTests("allof", c.of.map { test(it, x) })
        is Condition.AnyOf -> joinTests("anyof", c.of.map { test(it, x) })
        is Condition.Not -> test(c.of, x)?.let { "not $it" }
        is Condition.Query -> if (c.q.isBlank()) "true" else queryTest(parse(c.q).root, x)
        is Condition.Header -> header(c, x)
        is Condition.SizeOver -> "size :over ${c.bytes}"
        is Condition.ListId -> if (c.value == "*" || c.value.equals("any", true)) { x.reasons += "“any list” can't be told apart from the literal text on a server"; null } else "header :contains \"list-id\" ${q(c.value)}"
        is Condition.AgeOver -> { x.reasons += "“older than” has no meaning for mail that is just arriving"; null }
        Condition.IsReply -> { x.reasons += "telling a reply from a new message isn't something a server filter does the same way"; null }
        Condition.FromKnownContact -> { x.reasons += "your contacts live on this phone, not on the server"; null }
        is Condition.InCategory -> { x.reasons += "tabs like “${c.category.label}” are decided on this phone"; null }
    }

    private fun joinTests(kind: String, tests: List<String?>): String? {
        if (tests.any { it == null }) return null
        val t = tests.filterNotNull()
        return when (t.size) { 0 -> if (kind == "allof") "true" else "false"; 1 -> t[0]; else -> "$kind(${t.joinToString(", ")})" }
    }

    private fun header(c: Condition.Header, x: Ctx): String? {
        val name = c.name.trim().lowercase()
        val addressy = name in setOf("from", "to", "cc", "bcc", "reply-to", "sender")
        return when (c.op) {
            HeaderOp.EXISTS -> "exists ${q(name)}"
            HeaderOp.CONTAINS -> "header :contains ${q(name)} ${q(c.value)}"
            // On the phone "equals" accepts the whole header or just the address; so must the server.
            HeaderOp.EQUALS -> if (addressy) "anyof(header :is ${q(name)} ${q(c.value)}, address :is ${q(name)} ${q(c.value)})" else "header :is ${q(name)} ${q(c.value)}"
            HeaderOp.REGEX -> { x.reasons += "regular expressions differ between the phone and servers, so “$name matches …” stays on the phone"; null }
        }
    }

    private fun queryTest(n: QueryNode?, x: Ctx): String? = when (n) {
        null -> "true"
        is QueryNode.And -> joinTests("allof", n.children.map { queryTest(it, x) })
        is QueryNode.Or -> joinTests("anyof", n.children.map { queryTest(it, x) })
        is QueryNode.Not -> queryTest(n.child, x)?.let { "not $it" }
        is QueryNode.Facet -> facet(n, x)
        is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Semantic -> { x.reasons += "searching for words anywhere in a message (not in one field) can't be done by a server filter"; null }
        is QueryNode.Regex -> { x.reasons += "regular expressions differ between the phone and servers"; null }
    }

    private val FROM = setOf("from", "sender", "f"); private val TO = setOf("to", "rcpt", "recipient")
    private val SUBJECT = setOf("subject", "title", "s"); private val LIST = setOf("list", "mailinglist")

    private fun facet(f: QueryNode.Facet, x: Ctx): String? {
        val key = f.key.lowercase(); val v = f.value
        val base: String? = when {
            key in FROM -> when {
                v.equals("me", true) -> mine("from", x)
                v.startsWith("@") -> domain(v.drop(1), x)
                hasWildcard(v) -> wild(x)
                else -> { x.warnings += "Sieve compares letters case-insensitively for plain ASCII only"; "anyof(header :contains \"from\" ${q(v)}, address :contains \"sender\" ${q(v)})" }
            }
            key == "domain" -> domain(v.removePrefix("@"), x)
            key in TO -> if (v.equals("me", true)) mine("to", x) else if (hasWildcard(v)) wild(x) else "header :contains ${list(listOf("to", "cc", "bcc"))} ${q(v)}"
            key == "cc" -> if (hasWildcard(v)) wild(x) else "header :contains \"cc\" ${q(v)}"
            key in SUBJECT -> "header :contains \"subject\" ${q(v)}"
            key in LIST -> if (v == "*" || v.equals("any", true)) "exists \"list-id\"" else "header :contains \"list-id\" ${q(v)}"
            key == "size" || key == "bytes" -> size(f.op, v, x)
            key == "larger" || key == "bigger" -> MailFields.parseSize(v)?.let { "size :over $it" } ?: unknown(f, x)
            key == "smaller" -> MailFields.parseSize(v)?.let { "size :under $it" } ?: unknown(f, x)
            key == "category" || key == "cat" || key == "tab" -> { x.reasons += "tabs (the “category:” filter) are decided on this phone"; null }
            else -> { x.reasons += "“${f.key}:” isn't something a server can test"; null }
        }
        if (base == null) return null
        // size already folds its own comparison; every other facet means "has it" for EQ and "doesn't" for NE.
        return if (f.op == Op.NE && !(key == "size" || key == "bytes")) "not $base" else base
    }

    private fun unknown(f: QueryNode.Facet, x: Ctx): String? { x.reasons += "“${f.key}:${f.value}” isn't a size a server can test"; return null }
    private fun hasWildcard(v: String) = '*' in v || '?' in v
    private fun wild(x: Ctx): String? { x.reasons += "a * or ? in a name means something different to a server"; return null }

    private fun mine(field: String, x: Ctx): String? {
        if (x.caps.accountAddresses.isEmpty()) { x.reasons += "it doesn't know which addresses are yours"; return null }
        val addrs = x.caps.accountAddresses.sorted()
        return if (field == "from") "address :is \"from\" ${list(addrs)}" else "address :is ${list(listOf("to", "cc"))} ${list(addrs)}"
    }

    private fun domain(d: String, x: Ctx): String? {
        if (d.isBlank() || hasWildcard(d)) return wild(x)
        // The phone matches the domain itself and anything below it.
        return "address :domain :matches \"from\" ${list(listOf(d, "*.$d"))}"
    }

    private fun size(op: Op, v: String, x: Ctx): String? {
        val n = MailFields.parseSize(v) ?: return unknown(QueryNode.Facet("size", op, v), x)
        return when (op) {
            Op.GT -> "size :over $n"
            Op.LT -> "size :under $n"
            Op.GTE -> "not size :under $n"
            Op.LTE -> "not size :over $n"
            else -> { x.reasons += "an exact size can't be tested by a server"; null }
        }
    }

    // ---- actions ----

    private fun dest(a: RuleAction, x: Ctx): String? = when (a) {
        RuleAction.Archive -> x.caps.folderName(MailboxRole.ARCHIVE, null)
        RuleAction.Trash -> x.caps.folderName(MailboxRole.TRASH, null)
        RuleAction.Spam -> x.caps.folderName(MailboxRole.JUNK, null)
        is RuleAction.MoveTo -> x.caps.folderName(a.role, a.mailbox)
        is RuleAction.CopyTo -> x.caps.folderName(a.role, a.mailbox)
        else -> null
    }

    private fun actions(rule: MailRule, x: Ctx): List<String>? {
        val flags = ArrayList<String>(); val copies = ArrayList<String>(); var move: String? = null; var moves = 0
        for (a in rule.actions) when (a) {
            RuleAction.MarkRead -> { flags += "addflag \"\\\\Seen\";"; x.extensions += "imap4flags" }
            RuleAction.MarkUnread -> { flags += "removeflag \"\\\\Seen\";"; x.extensions += "imap4flags" }
            RuleAction.Star -> { flags += "addflag \"\\\\Flagged\";"; x.extensions += "imap4flags" }
            RuleAction.Unstar -> { flags += "removeflag \"\\\\Flagged\";"; x.extensions += "imap4flags" }
            RuleAction.Archive, RuleAction.Trash, RuleAction.Spam, is RuleAction.MoveTo -> {
                val name = dest(a, x)
                if (name == null) x.reasons += "it files mail into a folder the server doesn't have" else { move = "fileinto ${q(name)};"; x.extensions += "fileinto" }
                moves++
            }
            is RuleAction.CopyTo -> {
                val name = dest(a, x)
                if (name == null) x.reasons += "it copies into a folder the server doesn't have" else { copies += "fileinto :copy ${q(name)};"; x.extensions += setOf("fileinto", "copy") }
            }
            is RuleAction.Forward ->
                if (!rule.confirmed) x.reasons += "forwarding needs you to confirm the rule first"
                else { copies += "redirect :copy ${q(a.to)};"; x.extensions += "copy" }
            is RuleAction.AddLabel, is RuleAction.RemoveLabel -> x.reasons += "labels are kept on this phone"
            is RuleAction.Categorize -> x.reasons += "tabs are decided on this phone"
            is RuleAction.Snooze -> x.reasons += "snoozing is done on this phone"
            is RuleAction.Notify -> x.reasons += "notifications are decided on this phone"
            RuleAction.Pin, RuleAction.Mute -> x.reasons += "pinning and muting are done on this phone"
            RuleAction.Summarize -> x.reasons += "summaries are written on this phone"
            is RuleAction.AutoReply -> x.reasons += "automatic replies are sent from this phone"
        }
        if (moves > 1) x.reasons += "it files the same mail into more than one folder, which a server would do all at once, not in turn"
        if (rule.actions.isEmpty()) x.reasons += "it has nothing to do"
        if (x.reasons.isNotEmpty()) return null
        return flags + copies + listOfNotNull(move)
    }
}
