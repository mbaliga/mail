package dev.aarso.mail.rules

import dev.aarso.mail.model.Condition
import dev.aarso.search.Op
import dev.aarso.search.ParsedQuery
import dev.aarso.search.QueryNode

/**
 * Splits a condition into what the index/SQL can answer and what must be checked in memory.
 *
 * `narrow` is a search-core tree handed to the search planner (FTS, indexed SQL); `residual` is the part of the
 * condition left to evaluate exactly over the messages `narrow` selected. A message matches the condition iff it is in
 * `narrow`'s result and satisfies `residual`. Either may be null: no narrowing means "every message", no residual
 * means the search alone is exact — which is what lets a preview of a query-only rule be a single count.
 */
internal object ConditionPlanner {

    class Plan(val narrow: QueryNode?, val residual: Condition?)

    fun plan(c: Condition, parse: (String) -> ParsedQuery): Plan = when (c) {
        is Condition.Query -> Plan(parse(c.q).root, null)
        is Condition.AgeOver ->
            // The age facet counts whole hours; any other duration is checked in memory.
            if (c.minutes > 0 && c.minutes % 60 == 0L) Plan(QueryNode.Facet("older_than", Op.EQ, "${c.minutes / 60}h"), null) else Plan(null, c)
        is Condition.SizeOver -> Plan(QueryNode.Facet("larger", Op.EQ, c.bytes.toString()), null)
        is Condition.ListId -> Plan(QueryNode.Facet("list", Op.EQ, c.value), null)
        is Condition.InCategory -> Plan(QueryNode.Facet("category", Op.EQ, c.category.key), null)
        is Condition.Header, Condition.IsReply, Condition.FromKnownContact -> Plan(null, c)
        is Condition.All -> {
            val ps = c.of.map { plan(it, parse) }
            val narrows = ps.mapNotNull { it.narrow }
            val residuals = ps.mapNotNull { it.residual }
            Plan(
                when (narrows.size) { 0 -> null; 1 -> narrows[0]; else -> QueryNode.And(narrows) },
                when (residuals.size) { 0 -> null; 1 -> residuals[0]; else -> Condition.All(residuals) },
            )
        }
        is Condition.AnyOf -> {
            val ps = c.of.map { plan(it, parse) }
            when {
                ps.isEmpty() -> Plan(null, c)
                ps.all { it.narrow != null } && ps.all { it.residual == null } -> Plan(QueryNode.Or(ps.map { it.narrow!! }), null)
                // Some branch needs checking in memory: narrow by the union of the branches (if every one narrows) and check the whole thing.
                ps.all { it.narrow != null } -> Plan(QueryNode.Or(ps.map { it.narrow!! }), c)
                else -> Plan(null, c)
            }
        }
        is Condition.Not -> {
            val p = plan(c.of, parse)
            if (p.residual == null && p.narrow != null) Plan(QueryNode.Not(p.narrow), null) else Plan(null, c)
        }
    }
}
