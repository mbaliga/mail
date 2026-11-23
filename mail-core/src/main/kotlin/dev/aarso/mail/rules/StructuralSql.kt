package dev.aarso.mail.rules

import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.HeaderOp

/**
 * SQL for the structural leaves, so a rule over a whole mailbox does not decode every message to test one column.
 *
 * [exact] says the SQL is the whole answer; otherwise it selects a *superset* and [ConditionEval] settles the rest in
 * memory over just those rows. Each exact one mirrors [ConditionEval] and is pinned to it by the equivalence test.
 */
internal object StructuralSql {

    class Part(val where: String, val args: List<Any?>, val exact: Boolean)

    private fun like(v: String) = "%" + v.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private val REPLY_STARTS = listOf("re", "aw", "sv", "antw", "vs", "odp", "res", "rv")

    fun of(c: Condition, now: Long): Part? = when (c) {
        is Condition.AgeOver -> Part("m.received_at < ?", listOf(now - c.minutes * 60_000L), true)
        is Condition.SizeOver -> Part("m.size > ?", listOf(c.bytes), true)
        is Condition.InCategory -> Part("m.category = ?", listOf(c.category.key), true)
        is Condition.ListId -> Part("LOWER(m.list_id) LIKE ? ESCAPE '\\'", listOf(like(c.value)), true)
        Condition.FromKnownContact -> Part("LOWER(m.from_addr) IN (SELECT address FROM contact WHERE sent_count > 0)", emptyList(), true)
        // A reply has an In-Reply-To, or a subject that starts "Re:" and the like; the subject test here only has to select a superset.
        Condition.IsReply -> Part(
            "(m.in_reply_to IS NOT NULL AND m.in_reply_to != '') OR (" + REPLY_STARTS.joinToString(" OR ") { "LOWER(LTRIM(m.subject)) LIKE '$it%'" } + ")",
            emptyList(), false,
        )
        is Condition.Header -> header(c)
        else -> null
    }

    /** Subject, From and List-Id "contains": the SQL columns hold the same text, minus the display-name decoration, so it selects a superset. */
    private fun header(c: Condition.Header): Part? {
        if (c.op != HeaderOp.CONTAINS && c.op != HeaderOp.EQUALS || c.value.isBlank() || '<' in c.value || '>' in c.value) return null
        return when (c.name.trim().lowercase()) {
            "subject" -> Part("LOWER(m.subject) LIKE ? ESCAPE '\\'", listOf(like(c.value)), false)
            "from" -> Part("(LOWER(m.from_addr) LIKE ? ESCAPE '\\' OR LOWER(m.from_name) LIKE ? ESCAPE '\\')", listOf(like(c.value), like(c.value)), false)
            "list-id" -> Part("LOWER(m.list_id) LIKE ? ESCAPE '\\'", listOf(like(c.value)), false)
            else -> null
        }
    }
}
