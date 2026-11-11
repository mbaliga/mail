package dev.aarso.mail.rules

import dev.aarso.mail.model.Account
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import dev.aarso.mail.sync.MailActions
import dev.aarso.search.Diagnostic

/** Things a rule can cause that are not local state: sending mail, calling a model. Implemented by the app. */
interface RuleEffects {
    suspend fun forward(message: MessageHeader, to: String) {}
    suspend fun autoReply(message: MessageHeader, subjectPrefix: String, body: String) {}
    suspend fun summarize(messages: List<MessageHeader>) {}
}

data class RuleRunResult(val rule: MailRule, val matched: Int, val sample: List<MessageHeader>)

/** Per-message notification decision after rules ran (the strongest rule wins; NEVER beats all). */
data class RuleOutcome(val notify: Map<Long, NotifyLevel>, val applied: Map<Long, List<String>>)

/**
 * Rules: a saved query and the actions to take on whatever it matches — on new mail, on demand, or
 * on a schedule ("every night, archive newsletters older than a week").
 *
 * The condition *is* a search query, so "test this rule" runs it as a search and shows exactly the
 * messages it would touch before anything happens. Rules run in order; `stopProcessing` hides a
 * matched message from the rules after it. Actions go through [MailActions], so everything a rule
 * does is queued, synced and undoable like a person's own action.
 */
class RuleEngine(
    private val store: MailStore,
    private val search: MailSearch,
    private val actions: MailActions,
    private val effects: RuleEffects = object : RuleEffects {},
    private val clock: Clock = Clock.SYSTEM,
) {
    /** Problems a rule's query has, for the editor to show before saving. */
    fun diagnose(query: String): List<Diagnostic> = search.parse(query, natural = false).diagnostics

    suspend fun dryRun(rule: MailRule, limit: Int = 50): RuleRunResult {
        val parsed = search.parse(rule.query, natural = false)
        val scope = SearchScope(accountIds = rule.accountIds)
        val result = search.search(parsed, scope, limit = limit)
        return RuleRunResult(rule, result.total ?: search.count(parsed, scope), result.headers)
    }

    suspend fun onNewMail(account: Account, fresh: List<MessageHeader>): RuleOutcome {
        val notify = HashMap<Long, NotifyLevel>()
        val applied = HashMap<Long, MutableList<String>>()
        var remaining = fresh.map { it.id }.toSet()
        for (rule in store.rules().filter { it.enabled && RuleTrigger.NEW_MAIL in it.triggers && (it.accountIds.isEmpty() || account.id in it.accountIds) }) {
            if (remaining.isEmpty()) break
            val hits = search.matchesAmong(search.parse(rule.query, natural = false), remaining)
            if (hits.isEmpty()) continue
            val headers = store.headers(hits)
            execute(rule, headers, notify)
            hits.forEach { applied.getOrPut(it) { mutableListOf() } += rule.name }
            store.saveRule(rule.copy(matchCount = rule.matchCount + hits.size, lastRunAt = clock.now()))
            if (rule.stopProcessing) remaining = remaining - hits
        }
        return RuleOutcome(notify, applied)
    }

    /** Run a rule over existing mail now. Returns how many messages it acted on. */
    suspend fun run(rule: MailRule, max: Int = 100_000): Int {
        val ids = search.ids(search.parse(rule.query, natural = false), SearchScope(accountIds = rule.accountIds), max)
        if (ids.isEmpty()) return 0
        for (chunk in ids.chunked(2000)) execute(rule, store.headers(chunk), HashMap())
        store.saveRule(rule.copy(matchCount = rule.matchCount + ids.size, lastRunAt = clock.now()))
        return ids.size
    }

    /** Scheduled rules whose interval has elapsed. */
    suspend fun runScheduled(): Int {
        val now = clock.now()
        var total = 0
        for (rule in store.rules().filter { it.enabled && RuleTrigger.SCHEDULED in it.triggers }) {
            if (now - rule.lastRunAt >= rule.intervalMinutes * 60_000L) total += run(rule)
        }
        return total
    }

    private suspend fun execute(rule: MailRule, headers: List<MessageHeader>, notify: MutableMap<Long, NotifyLevel>) {
        val ids = headers.map { it.id }
        for (action in rule.actions) {
            when (action) {
                RuleAction.MarkRead -> actions.markRead(ids, true)
                RuleAction.MarkUnread -> actions.markRead(ids, false)
                RuleAction.Star -> actions.star(ids, true)
                RuleAction.Unstar -> actions.star(ids, false)
                RuleAction.Archive -> actions.archive(ids)
                RuleAction.Trash -> actions.trash(ids)
                RuleAction.Spam -> actions.spam(ids)
                RuleAction.Pin -> actions.pin(ids)
                RuleAction.Mute -> actions.mute(ids)
                RuleAction.Summarize -> effects.summarize(headers)
                is RuleAction.MoveTo -> for ((acc, hs) in headers.groupBy { it.accountId }) {
                    val box = action.role?.let { store.mailboxByRole(acc, it) }
                        ?: action.mailbox?.let { name -> store.mailboxes(acc).firstOrNull { it.remoteId == name || it.name.equals(name, true) } }
                    if (box != null) actions.moveTo(hs.map { it.id }, box.id)
                }
                is RuleAction.AddLabel -> actions.label(ids, listOf(action.label))
                is RuleAction.RemoveLabel -> actions.label(ids, emptyList(), listOf(action.label))
                is RuleAction.Categorize -> store.setCategory(ids, action.category, ClassificationSource.RULE)
                is RuleAction.Snooze -> actions.snooze(ids, clock.now() + action.minutes * 60_000L)
                is RuleAction.Notify -> ids.forEach { id ->
                    val prev = notify[id]
                    notify[id] = if (prev == null || action.level == NotifyLevel.NEVER || action.level.ordinal < prev.ordinal) action.level else prev
                }
                is RuleAction.Forward -> headers.forEach { effects.forward(it, action.to) }
                is RuleAction.AutoReply -> headers.filter { !it.isBulk && !it.isAutomated }.forEach { effects.autoReply(it, action.subjectPrefix, action.body) }
            }
        }
    }

    /** Rules people tend to want, offered from the rule editor and from a selection ("always do this"). */
    fun suggestionsFor(h: MessageHeader): List<MailRule> = buildList {
        val from = h.from?.address ?: return@buildList
        add(MailRule(name = "Archive mail from ${h.from.displayName()}", query = "from:$from", actions = listOf(RuleAction.MarkRead, RuleAction.Archive)))
        h.listId?.let { add(MailRule(name = "Skip the inbox for this list", query = "list:\"$it\"", actions = listOf(RuleAction.Archive))) }
        add(MailRule(name = "Always notify for ${h.from.displayName()}", query = "from:$from", actions = listOf(RuleAction.Notify(NotifyLevel.URGENT))))
        add(MailRule(name = "Never notify for ${h.from.displayName()}", query = "from:$from", actions = listOf(RuleAction.Notify(NotifyLevel.NEVER))))
        add(MailRule(name = "Move ${h.category.label.lowercase()} to Archive after a week", query = "category:${h.category.key} older_than:7d in:inbox",
            triggers = setOf(RuleTrigger.SCHEDULED), actions = listOf(RuleAction.Archive)))
    }

    companion object {
        /** Defaults installed on first run — useful, conservative, all off-switchable. */
        val DEFAULTS: List<MailRule> = listOf(
            MailRule(name = "Codes expire", query = "category:otp older_than:1d in:inbox", triggers = setOf(RuleTrigger.SCHEDULED),
                intervalMinutes = 360, actions = listOf(RuleAction.MarkRead, RuleAction.Archive), enabled = false),
            MailRule(name = "Quiet promotions", query = "category:promotions", actions = listOf(RuleAction.Notify(NotifyLevel.NEVER))),
            MailRule(name = "Codes notify urgently", query = "category:otp", actions = listOf(RuleAction.Notify(NotifyLevel.URGENT))),
            MailRule(name = "Replies notify", query = "category:replies", actions = listOf(RuleAction.Notify(NotifyLevel.URGENT))),
        )
    }
}

/**
 * Export rules as Sieve (RFC 5228) for servers that filter server-side (Fastmail, mailbox.org, Posteo,
 * Dovecot): the subset of the grammar Sieve can express — from/to/subject/list conditions combined
 * with AND, and fileinto/addflag/discard actions. Rules it cannot express are left out and named.
 */
object SieveExport {

    data class Result(val script: String, val skipped: List<String>)

    fun export(rules: List<MailRule>, mailboxNameFor: (MailboxRole) -> String?): Result {
        val skipped = ArrayList<String>()
        val body = StringBuilder()
        val requires = linkedSetOf("fileinto", "imap4flags")
        for (r in rules.filter { it.enabled && RuleTrigger.NEW_MAIL in it.triggers }) {
            val tests = conditions(r.query) ?: run { skipped += r.name; null } ?: continue
            val acts = r.actions.mapNotNull { a ->
                when (a) {
                    RuleAction.MarkRead -> "addflag \"\\\\Seen\";"
                    RuleAction.Star -> "addflag \"\\\\Flagged\";"
                    RuleAction.Trash -> mailboxNameFor(MailboxRole.TRASH)?.let { "fileinto \"${esc(it)}\";" }
                    RuleAction.Archive -> mailboxNameFor(MailboxRole.ARCHIVE)?.let { "fileinto \"${esc(it)}\";" }
                    RuleAction.Spam -> mailboxNameFor(MailboxRole.JUNK)?.let { "fileinto \"${esc(it)}\";" }
                    is RuleAction.MoveTo -> (a.mailbox ?: a.role?.let(mailboxNameFor))?.let { "fileinto \"${esc(it)}\";" }
                    is RuleAction.Forward -> "redirect :copy \"${esc(a.to)}\";".also { requires += "copy" }
                    else -> null
                }
            }
            if (acts.isEmpty()) { skipped += r.name; continue }
            body.append("# ").append(r.name.replace("\n", " ")).append('\n')
            body.append("if allof(").append(tests.joinToString(", ")).append(") {\n")
            acts.forEach { body.append("    ").append(it).append('\n') }
            if (r.stopProcessing) body.append("    stop;\n")
            body.append("}\n\n")
        }
        val header = "# Generated by You've got Mail\nrequire [" + requires.joinToString(", ") { "\"$it\"" } + "];\n\n"
        return Result(header + body.toString(), skipped)
    }

    /** `from:x subject:"y z" to:me list:foo` → Sieve tests; null when anything else is present. */
    private fun conditions(query: String): List<String>? {
        val tokens = Regex("(-?)(\\w+):(\"[^\"]*\"|\\S+)|(\\S+)").findAll(query).toList()
        val out = ArrayList<String>()
        for (t in tokens) {
            if (t.groupValues[4].isNotEmpty()) return null
            val negate = t.groupValues[1] == "-"
            val key = t.groupValues[2].lowercase()
            val value = esc(t.groupValues[3].trim('"'))
            val test = when (key) {
                "from" -> "address :contains \"from\" \"$value\""
                "to" -> "address :contains [\"to\", \"cc\"] \"$value\""
                "subject" -> "header :contains \"subject\" \"$value\""
                "list" -> "header :contains \"list-id\" \"$value\""
                "domain" -> "address :domain :is \"from\" \"$value\""
                else -> return null
            }
            out += if (negate) "not $test" else test
        }
        return out.ifEmpty { null }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
