package dev.aarso.mail.rules

import dev.aarso.mail.model.Category
import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.leaves
import dev.aarso.mail.model.normalized

/** Two rules that disagree about the same mail. */
data class RuleConflict(
    val first: MailRule,
    val second: MailRule,
    val kind: Kind,
    /** One line for the editor: what they disagree about. */
    val summary: String,
    /** True when the two conditions provably match some of the same mail; false when they merely might. */
    val certain: Boolean,
) {
    enum class Kind { CONFLICTING_ACTIONS, SHADOWED }
}

/**
 * Finds rules that fight. A pair is reported when (a) their conditions can match the same mail and (b) their actions
 * cannot both hold: moving to two folders, trash with star, read with unread, a label added and removed, two notification
 * levels. Whether two conditions overlap is decided structurally where that is possible (equal, one implies the other,
 * provably disjoint categories or scopes) and otherwise left as "might" — [confirmOnSample] settles those against real mail.
 * A rule that stops processing and matches everything a later rule does *shadows* it: the later one never runs.
 */
object RuleConflicts {

    private enum class Overlap { CERTAIN, POSSIBLE, NONE }

    fun find(rules: List<MailRule>): List<RuleConflict> {
        val live = rules.filter { it.enabled }.sortedWith(compareBy({ it.order }, { it.id }))
        val out = ArrayList<RuleConflict>()
        for (i in live.indices) for (j in i + 1 until live.size) {
            val a = live[i]; val b = live[j]
            val overlap = overlap(a, b)
            if (overlap == Overlap.NONE) continue
            if (a.stopProcessing && implies(b.effectiveCondition, a.effectiveCondition) && scopeCovers(a, b)) {
                out += RuleConflict(a, b, RuleConflict.Kind.SHADOWED, "“${a.name}” stops processing first, so “${b.name}” never runs on that mail", true)
                continue
            }
            val clash = clashes(a.actions, b.actions) ?: continue
            out += RuleConflict(a, b, RuleConflict.Kind.CONFLICTING_ACTIONS, "“${a.name}” and “${b.name}” ${clash}", overlap == Overlap.CERTAIN)
        }
        return out
    }

    /**
     * Keep the conflicts that hold on real mail: a "might overlap" pair stays only if some message in [sample] matches both
     * conditions (and becomes certain); one that no sample message triggers is dropped.
     */
    fun confirmOnSample(conflicts: List<RuleConflict>, sample: List<MessageHeader>, ctx: RuleContext): List<RuleConflict> =
        conflicts.mapNotNull { c ->
            if (c.certain || c.kind == RuleConflict.Kind.SHADOWED) c
            else if (sample.any { ConditionEval.matches(c.first.effectiveCondition, it, ctx) && ConditionEval.matches(c.second.effectiveCondition, it, ctx) }) c.copy(certain = true)
            else null
        }

    // ---- actions ----

    private fun destination(a: RuleAction): String? = when (a) {
        RuleAction.Archive -> "role:" + MailboxRole.ARCHIVE
        RuleAction.Trash -> "role:" + MailboxRole.TRASH
        RuleAction.Spam -> "role:" + MailboxRole.JUNK
        is RuleAction.MoveTo -> a.role?.let { "role:$it" } ?: a.mailbox?.let { "box:" + it.lowercase() }
        else -> null
    }

    private fun clashes(xs: List<RuleAction>, ys: List<RuleAction>): String? {
        for (x in xs) for (y in ys) clash(x, y)?.let { return it }
        return null
    }

    private fun clash(x: RuleAction, y: RuleAction): String? {
        val dx = destination(x); val dy = destination(y)
        if (dx != null && dy != null && dx != dy) {
            return if (x == RuleAction.Trash || y == RuleAction.Trash) "disagree: one trashes, the other files it elsewhere" else "send it to different folders"
        }
        val trash = x == RuleAction.Trash || y == RuleAction.Trash
        val other = if (x == RuleAction.Trash) y else x
        if (trash && (other == RuleAction.Star || other == RuleAction.Pin || other is RuleAction.Snooze || other is RuleAction.AddLabel)) return "disagree: one trashes it, the other keeps it"
        if (x == RuleAction.MarkRead && y == RuleAction.MarkUnread || x == RuleAction.MarkUnread && y == RuleAction.MarkRead) return "disagree about read and unread"
        if (x == RuleAction.Star && y == RuleAction.Unstar || x == RuleAction.Unstar && y == RuleAction.Star) return "disagree about starring"
        if (x is RuleAction.AddLabel && y is RuleAction.RemoveLabel && x.label.equals(y.label, true) || x is RuleAction.RemoveLabel && y is RuleAction.AddLabel && x.label.equals(y.label, true)) return "add and remove the same label"
        if (x is RuleAction.Notify && y is RuleAction.Notify && x.level != y.level) return "ask for different notifications (${level(x.level)} and ${level(y.level)})"
        if (x is RuleAction.Categorize && y is RuleAction.Categorize && x.category != y.category) return "file it under different tabs"
        return null
    }

    private fun level(l: NotifyLevel) = when (l) { NotifyLevel.URGENT -> "urgent"; NotifyLevel.NORMAL -> "normal"; NotifyLevel.SILENT -> "silent"; NotifyLevel.NEVER -> "never" }

    // ---- conditions ----

    private fun scopesDisjoint(a: MailRule, b: MailRule) =
        a.accountIds.isNotEmpty() && b.accountIds.isNotEmpty() && a.accountIds.intersect(b.accountIds).isEmpty() ||
            a.mailboxIds.isNotEmpty() && b.mailboxIds.isNotEmpty() && a.mailboxIds.intersect(b.mailboxIds).isEmpty()

    /** [a] covers everything [b] could see. */
    private fun scopeCovers(a: MailRule, b: MailRule) =
        (a.accountIds.isEmpty() || b.accountIds.isNotEmpty() && a.accountIds.containsAll(b.accountIds)) &&
            (a.mailboxIds.isEmpty() || b.mailboxIds.isNotEmpty() && a.mailboxIds.containsAll(b.mailboxIds))

    private fun overlap(a: MailRule, b: MailRule): Overlap {
        if (scopesDisjoint(a, b)) return Overlap.NONE
        val ca = a.effectiveCondition.normalized(); val cb = b.effectiveCondition.normalized()
        if (disjoint(ca, cb)) return Overlap.NONE
        return if (implies(ca, cb) || implies(cb, ca)) Overlap.CERTAIN else Overlap.POSSIBLE
    }

    private fun conjuncts(c: Condition): List<Condition> = if (c is Condition.All) c.of else listOf(c)

    /** Every match of [a] is a match of [b]: [b] is a conjunct of [a], or equal to it. */
    private fun implies(a: Condition, b: Condition): Boolean {
        val an = a.normalized(); val bn = b.normalized()
        if (an == bn) return true
        val have = conjuncts(an).toSet()
        return conjuncts(bn).all { it in have || it is Condition.Query && it.q.isBlank() }
    }

    private fun categoryOf(c: Condition): Category? = when (c) {
        is Condition.InCategory -> c.category
        is Condition.Query -> Regex("^\\s*category:(\\w+)\\s*$").matchEntire(c.q)?.groupValues?.get(1)?.let { k -> Category.entries.firstOrNull { it.key == k } }
        else -> null
    }

    /** Provably no message satisfies both. Deliberately small: different single categories, or x versus not-x. */
    private fun disjoint(a: Condition, b: Condition): Boolean {
        val ac = conjuncts(a); val bc = conjuncts(b)
        for (x in ac) for (y in bc) {
            val cx = categoryOf(x); val cy = categoryOf(y)
            if (cx != null && cy != null && cx != cy) return true
            if (x is Condition.Not && x.of == y || y is Condition.Not && y.of == x) return true
        }
        return false
    }

    /** All leaves of a rule's condition, for callers that list what it looks at. */
    fun leavesOf(rule: MailRule): List<Condition> = rule.effectiveCondition.leaves()
}
