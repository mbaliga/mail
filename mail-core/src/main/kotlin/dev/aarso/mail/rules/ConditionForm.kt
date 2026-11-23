package dev.aarso.mail.rules

import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.describe
import dev.aarso.mail.model.normalized

/**
 * The editor's view of a condition: one search box, plus any extra conditions, joined by "all of" or "any of" — which is the
 * shape of nearly every rule a person writes. A tree the form cannot hold (groups inside groups) is [from]-null: the editor shows
 * it as text and leaves it alone rather than flatten it.
 *
 * With no extras the rule is just its search ([toRule] leaves `condition` null), so a plain rule stays exactly what it always was.
 */
data class ConditionForm(val query: String, val extras: List<Item> = emptyList(), val any: Boolean = false) {

    /** One extra condition: a leaf, optionally negated. [leaf] is never a group. */
    data class Item(val leaf: Condition, val negated: Boolean = false) {
        fun toCondition(): Condition = if (negated) Condition.Not(leaf) else leaf
    }

    /** True once there is more than one condition — the moment the editor starts to show "all / any". */
    val isGroup: Boolean get() = extras.isNotEmpty() && (query.isNotBlank() || extras.size > 1)

    fun toCondition(): Condition {
        val parts = buildList { if (query.isNotBlank()) add(Condition.Query(query)); extras.forEach { add(it.toCondition()) } }
        return when (parts.size) { 0 -> Condition.Query(""); 1 -> parts[0]; else -> if (any) Condition.AnyOf(parts) else Condition.All(parts) }
    }

    /** [rule] with this condition: `query` and `condition` kept in step (a lone search stays a plain query rule). */
    fun toRule(rule: MailRule): MailRule {
        val c = toCondition()
        return if (c is Condition.Query) rule.copy(query = c.q, condition = null) else rule.copy(query = c.describe(), condition = c)
    }

    companion object {
        fun from(rule: MailRule): ConditionForm? = from(rule.effectiveCondition)

        fun from(c: Condition): ConditionForm? {
            val n = c.normalized()
            return when (n) {
                is Condition.Query -> ConditionForm(n.q)
                is Condition.All -> group(n.of, any = false)
                is Condition.AnyOf -> group(n.of, any = true)
                else -> item(n)?.let { ConditionForm("", listOf(it)) }
            }
        }

        private fun item(c: Condition): Item? = when {
            c is Condition.All || c is Condition.AnyOf -> null
            c is Condition.Not -> if (c.of is Condition.All || c.of is Condition.AnyOf || c.of is Condition.Not) null else Item(c.of, negated = true)
            else -> Item(c)
        }

        private fun group(of: List<Condition>, any: Boolean): ConditionForm? {
            val items = of.map { item(it) ?: return null }
            // The first plain search becomes the search box; any further searches stay as extras.
            val i = items.indexOfFirst { !it.negated && it.leaf is Condition.Query }
            val q = if (i >= 0) (items[i].leaf as Condition.Query).q else ""
            return ConditionForm(q, items.filterIndexed { j, _ -> j != i }, any)
        }
    }
}
