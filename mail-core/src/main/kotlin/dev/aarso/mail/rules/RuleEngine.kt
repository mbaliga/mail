package dev.aarso.mail.rules

import dev.aarso.mail.model.Account
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.leaves
import dev.aarso.mail.model.normalized
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import dev.aarso.mail.sync.MailActions
import dev.aarso.mail.sync.UndoToken
import dev.aarso.search.Diagnostic
import dev.aarso.search.ParsedQuery
import java.time.ZoneId

/** Things a rule can cause that are not local state: sending mail, calling a model. Implemented by the app. */
interface RuleEffects {
    suspend fun forward(message: MessageHeader, to: String) {}
    suspend fun autoReply(message: MessageHeader, subjectPrefix: String, body: String) {}
    suspend fun summarize(messages: List<MessageHeader>) {}
}

data class RuleRunResult(val rule: MailRule, val matched: Int, val sample: List<MessageHeader>)

/** Per-message notification decision after rules ran (the strongest rule wins; NEVER beats all). */
data class RuleOutcome(val notify: Map<Long, NotifyLevel>, val applied: Map<Long, List<String>>)

/** One rule's action chosen for a message — what [RuleEngine.evaluateDetailed] returns. */
data class AppliedAction(val rule: MailRule, val action: RuleAction)

/** How many messages a rule would touch, and a few of them. */
data class RulePreview(val count: Int, val sampleIds: List<Long>)

/** What applying a rule to existing mail did. [undo] undoes all of it at once; [skipped] says what could not be carried out here and why. */
data class ApplyResult(val matched: Int, val undo: UndoToken?, val skipped: List<String>)

/**
 * Rules: when (a trigger), which mail (a condition tree), do what (actions) — on new mail, on demand, or
 * on a schedule ("every night, archive newsletters older than a week").
 *
 * The condition is, at heart, a search query: "test this rule" runs it as a search and shows exactly the
 * messages it would touch before anything happens. Rules run in order; `stopProcessing` hides a matched message
 * from the rules after it. Actions go through [MailActions], so everything a rule does is queued, synced and
 * undoable like a person's own action.
 *
 * Two ways in, kept consistent by tests: the pure [evaluate] (one message in, actions out — no database), and the
 * batch paths on this class, which resolve whole sets of messages with the index and are what actually run.
 */
class RuleEngine(
    private val store: MailStore,
    private val search: MailSearch,
    private val actions: MailActions,
    private val effects: RuleEffects = object : RuleEffects {},
    private val clock: Clock = Clock.SYSTEM,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Problems a rule's query has, for the editor to show before saving. */
    fun diagnose(query: String): List<Diagnostic> = search.parse(query, natural = false).diagnostics

    /** Problems any query leaf of [c] has. */
    fun diagnose(c: Condition): List<Diagnostic> = c.leaves().filterIsInstance<Condition.Query>().flatMap { diagnose(it.q) }

    suspend fun dryRun(rule: MailRule, limit: Int = 50): RuleRunResult {
        val p = preview(rule, limit)
        return RuleRunResult(rule, p.count, store.headers(p.sampleIds))
    }

    // ---- matching ----

    private suspend fun ruleContext(): RuleContext {
        val known = store.knownAddresses()
        return RuleContext(search.queryMatcher(), clock.now(), zone) { it in known }
    }

    /** The members of [candidates] that satisfy [c]: exact, with full-text leaves answered by the index. */
    internal suspend fun matchIds(c: Condition, candidates: Set<Long>, ctx: RuleContext? = null): Set<Long> {
        if (candidates.isEmpty()) return emptySet()
        return when (c) {
            is Condition.All -> { var acc = candidates; for (child in c.of) { if (acc.isEmpty()) break; acc = matchIds(child, acc, ctx) }; acc }
            is Condition.AnyOf -> c.of.fold(emptySet<Long>()) { acc, child -> acc + matchIds(child, candidates - acc, ctx) }
            is Condition.Not -> candidates - matchIds(c.of, candidates, ctx)
            is Condition.Query -> if (c.q.isBlank()) candidates else {
                val parsed = search.parse(c.q, natural = false)
                candidates.chunked(5_000).fold(emptySet<Long>()) { acc, chunk -> acc + search.matchesAmong(parsed, chunk.toSet()) }
            }
            else -> {
                val rc = ctx ?: ruleContext()
                // Ask SQL first where it can: an exact column test is the whole answer, a superset leaves only those rows to check.
                var pool = candidates
                StructuralSql.of(c, rc.now)?.let { part ->
                    val hit = store.filterIds(candidates, part.where, part.args)
                    if (part.exact) return hit.toSet()
                    pool = hit.toSet()
                }
                pool.chunked(2_000).fold(HashSet<Long>()) { acc, chunk ->
                    store.headers(chunk).filterTo(ArrayList()) { ConditionEval.matches(c, it, rc) }.mapTo(acc) { it.id }; acc
                }
            }
        }
    }

    private suspend fun within(rule: MailRule, candidates: Set<Long>, ctx: RuleContext?): Set<Long> {
        var ids = matchIds(rule.effectiveCondition, candidates, ctx)
        if (ids.isNotEmpty() && rule.mailboxIds.isNotEmpty()) ids = store.headers(ids).filter { it.mailboxId in rule.mailboxIds }.mapTo(HashSet()) { it.id }
        if (ids.isNotEmpty() && rule.accountIds.isNotEmpty()) ids = store.headers(ids).filter { it.accountId in rule.accountIds }.mapTo(HashSet()) { it.id }
        return ids
    }

    private fun parsedOf(narrow: dev.aarso.search.QueryNode?) = ParsedQuery("", narrow, emptyList(), null, emptyList(), emptyList())

    /**
     * Every message the condition matches within [scope], unordered. Each branch of an "any of" is answered on its own —
     * a search for the branch the index can answer, a column scan for the others — so one branch with no index does not
     * drag the whole rule through every message.
     */
    private suspend fun matchSet(c: Condition, scope: SearchScope, max: Int): Set<Long> {
        val plan = ConditionPlanner.plan(c) { search.parse(it, natural = false) }
        if (plan.residual == null) return search.ids(parsedOf(plan.narrow), scope, max).toSet()
        if (c is Condition.AnyOf) return c.of.fold(HashSet<Long>()) { acc, child -> acc.addAll(matchSet(child, scope, max)); acc }
        val narrowed = search.ids(parsedOf(plan.narrow), scope, max)
        return matchIds(plan.residual, narrowed.toSet())
    }

    /** Every message the rule matches in the whole store, newest first. */
    suspend fun idsFor(rule: MailRule, max: Int = 100_000): List<Long> =
        store.newestFirst(matchSet(rule.effectiveCondition.normalized(), SearchScope(accountIds = rule.accountIds, mailboxIds = rule.mailboxIds), max), max)

    /** How many messages [rule] matches, and [sampleSize] of them — without changing anything. */
    suspend fun preview(rule: MailRule, sampleSize: Int = 20): RulePreview {
        val c = rule.effectiveCondition.normalized()
        val scope = SearchScope(accountIds = rule.accountIds, mailboxIds = rule.mailboxIds)
        val plan = ConditionPlanner.plan(c) { search.parse(it, natural = false) }
        if (plan.residual == null) {
            // Everything the rule says is something the index/SQL can answer: count there, list the newest few.
            val parsed = parsedOf(plan.narrow)
            return RulePreview(search.count(parsed, scope), search.search(parsed, scope, limit = sampleSize, countTotal = false).headers.map { it.id })
        }
        val set = matchSet(c, scope, 100_000)
        return RulePreview(set.size, store.newestFirst(set, sampleSize))
    }

    // ---- running ----

    suspend fun onNewMail(account: Account, fresh: List<MessageHeader>): RuleOutcome {
        val notify = HashMap<Long, NotifyLevel>()
        val applied = HashMap<Long, MutableList<String>>()
        var remaining = fresh.map { it.id }.toSet()
        val ctx = ruleContext()
        for (rule in store.rules().filter { it.enabled && RuleTrigger.NEW_MAIL in it.triggers && (it.accountIds.isEmpty() || account.id in it.accountIds) }) {
            if (remaining.isEmpty()) break
            val hits = within(rule, remaining, ctx)
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
    suspend fun run(rule: MailRule, max: Int = 100_000): Int = applyToExisting(rule, max).matched

    /**
     * Run [rule] over everything it matches now, as one batch through the op queue. Every change it makes is
     * covered by the single [ApplyResult.undo].
     */
    suspend fun applyToExisting(rule: MailRule, max: Int = 100_000): ApplyResult {
        val ids = idsFor(rule, max)
        if (ids.isEmpty()) return ApplyResult(0, null, emptyList())
        val tokens = ArrayList<UndoToken>()
        val skipped = ArrayList<String>()
        for (chunk in ids.chunked(2000)) execute(rule, store.headers(chunk), HashMap(), tokens, skipped)
        store.saveRule(rule.copy(matchCount = rule.matchCount + ids.size, lastRunAt = clock.now()))
        return ApplyResult(ids.size, if (tokens.isEmpty()) null else MailActions.combine("Ran “${rule.name}”", tokens), skipped.distinct())
    }

    /** Scheduled rules whose interval has elapsed. */
    suspend fun runScheduled(): Int {
        var total = 0
        for (rule in RuleScheduler.due(store.rules(), clock.now())) total += run(rule)
        return total
    }

    private suspend fun execute(
        rule: MailRule, headers: List<MessageHeader>, notify: MutableMap<Long, NotifyLevel>,
        tokens: MutableList<UndoToken> = ArrayList(), skipped: MutableList<String> = ArrayList(),
    ) {
        val ids = headers.map { it.id }
        // Mail leaves the phone only for a rule the person has confirmed.
        val todo = if (rule.confirmed) rule.actions else rule.actions.filterNot { it.needsConfirmation }
        for (action in todo) {
            when (action) {
                RuleAction.MarkRead -> tokens += actions.markRead(ids, true)
                RuleAction.MarkUnread -> tokens += actions.markRead(ids, false)
                RuleAction.Star -> tokens += actions.star(ids, true)
                RuleAction.Unstar -> tokens += actions.star(ids, false)
                RuleAction.Archive -> tokens += actions.archive(ids)
                RuleAction.Trash -> tokens += actions.trash(ids)
                RuleAction.Spam -> tokens += actions.spam(ids)
                RuleAction.Pin -> tokens += actions.pin(ids)
                RuleAction.Mute -> tokens += actions.mute(ids)
                RuleAction.Summarize -> effects.summarize(headers)
                is RuleAction.MoveTo -> for ((acc, hs) in headers.groupBy { it.accountId }) {
                    val box = action.role?.let { store.mailboxByRole(acc, it) }
                        ?: action.mailbox?.let { name -> store.mailboxes(acc).firstOrNull { it.remoteId == name || it.name.equals(name, true) } }
                    if (box != null) tokens += actions.moveTo(hs.map { it.id }, box.id)
                }
                is RuleAction.CopyTo -> for ((acc, hs) in headers.groupBy { it.accountId }) {
                    val account = store.account(acc)
                    val name = action.mailbox ?: action.role?.name
                    if (account != null && name != null && usesLabels(account)) tokens += actions.label(hs.map { it.id }, listOf(name))
                    else skipped += "Copy to a folder isn't done on this phone for ${account?.email ?: "that account"}; it needs the server"
                }
                is RuleAction.AddLabel -> tokens += actions.label(ids, listOf(action.label))
                is RuleAction.RemoveLabel -> tokens += actions.label(ids, emptyList(), listOf(action.label))
                is RuleAction.Categorize -> store.setCategory(ids, action.category, ClassificationSource.RULE)
                is RuleAction.Snooze -> tokens += actions.snooze(ids, clock.now() + action.minutes * 60_000L)
                is RuleAction.Notify -> ids.forEach { id ->
                    val prev = notify[id]
                    notify[id] = if (prev == null || action.level == NotifyLevel.NEVER || action.level.ordinal < prev.ordinal) action.level else prev
                }
                is RuleAction.Forward -> headers.forEach { effects.forward(it, action.to) }
                is RuleAction.AutoReply -> headers.filter { !it.isBulk && !it.isAutomated }.forEach { effects.autoReply(it, action.subjectPrefix, action.body) }
            }
        }
    }

    /** Label-style accounts (Gmail, JMAP) have no folders to copy into; a copy is a label. */
    private suspend fun usesLabels(a: Account): Boolean =
        a.protocol == IncomingProtocol.JMAP || a.providerId.contains("gmail", true) || a.providerId.contains("google", true) ||
            store.mailboxByRole(a.id, MailboxRole.ALL) != null

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

        /**
         * The actions that apply to one message, in rule order — a pure function. Disabled rules, rules whose trigger
         * is not [on], and rules whose scope excludes the message are skipped; a matching rule with
         * `stopProcessing` ends the walk. Actions that send mail outward (needsConfirmation) are left out for a
         * rule the person has not confirmed once.
         */
        fun evaluateDetailed(rules: List<MailRule>, message: MessageHeader, ctx: RuleContext, on: RuleTrigger = RuleTrigger.NEW_MAIL): List<AppliedAction> {
            val out = ArrayList<AppliedAction>()
            for (rule in rules.sortedWith(compareBy({ it.order }, { it.id }))) {
                if (!rule.enabled || on !in rule.triggers) continue
                if (rule.accountIds.isNotEmpty() && message.accountId !in rule.accountIds) continue
                if (rule.mailboxIds.isNotEmpty() && message.mailboxId !in rule.mailboxIds) continue
                if (!ConditionEval.matches(rule.effectiveCondition, message, ctx)) continue
                for (a in rule.actions) if (rule.confirmed || !a.needsConfirmation) out += AppliedAction(rule, a)
                if (rule.stopProcessing) break
            }
            return out
        }

        fun evaluate(rules: List<MailRule>, message: MessageHeader, ctx: RuleContext, on: RuleTrigger = RuleTrigger.NEW_MAIL): List<RuleAction> =
            evaluateDetailed(rules, message, ctx, on).map { it.action }

        /** Pairs of rules whose actions fight over the same mail. See [RuleConflicts]. */
        fun conflicts(rules: List<MailRule>): List<RuleConflict> = RuleConflicts.find(rules)
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
