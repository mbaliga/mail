package dev.aarso.mail.search

import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import dev.aarso.search.CoverageRecencyScorer
import dev.aarso.search.Diagnostic
import dev.aarso.search.EvalContext
import dev.aarso.search.FacetEvaluator
import dev.aarso.search.FieldName
import dev.aarso.search.Matcher
import dev.aarso.search.NaturalQuery
import dev.aarso.search.ParsedQuery
import dev.aarso.search.QueryCompiler
import dev.aarso.search.QueryNode
import dev.aarso.search.QueryParser
import dev.aarso.search.SearchDoc
import dev.aarso.search.DocId
import java.time.ZoneId

data class SearchScope(
    val accountIds: Set<Long> = emptySet(),
    val mailboxIds: Set<Long> = emptySet(),
    /** Restrict to these messages (selection rules evaluated over an explicit set). */
    val messageIds: Set<Long>? = null,
    /** Show one row per conversation — its newest message. */
    val collapseThreads: Boolean = false,
    val includeHidden: Boolean = false,
)

enum class SortOrder(val sql: String) {
    NEWEST("m.received_at DESC, m.id DESC"),
    OLDEST("m.received_at ASC, m.id ASC"),
    SENDER("LOWER(COALESCE(m.from_name, m.from_addr)) ASC, m.received_at DESC"),
    SUBJECT("LOWER(m.subject) ASC, m.received_at DESC"),
    LARGEST("m.size DESC, m.received_at DESC"),
    PRIORITY("m.priority DESC, m.received_at DESC"),
    RELEVANCE("m.received_at DESC, m.id DESC"),
}

data class SearchResult(
    val headers: List<MessageHeader>,
    /** Exact count when cheap to know; null when only "at least [headers.size]" is known. */
    val total: Int?,
    val parsed: ParsedQuery,
    /** Honest notes for the UI ("body text not searched for mixed OR"), never errors. */
    val notes: List<String> = emptyList(),
)

/**
 * The search engine: search-core's grammar and evaluator over the store's SQL and FTS5.
 *
 * Each top-level conjunct of a query is routed to the cheapest place that can answer it exactly —
 * an indexed SQL predicate, the FTS5 index, or (for what neither can express: regexes, OR across
 * text and facets, approximate facets) search-core's in-memory evaluator over the rows the first two
 * already narrowed. The SQL path makes `from:x is:unread` over 200,000 messages instant; the
 * in-memory path keeps every query the grammar can express *exact*.
 */
class MailSearch(
    private val store: MailStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.SYSTEM,
) {
    @Volatile private var accounts: List<Account> = emptyList()
    @Volatile private var labelFolders = true
    val fields = MailFields(zone, { accounts }, { labelFolders })
    private val matcher = Matcher(fields.registry, CoverageRecencyScorer(mapOf(FieldName("subject") to 2.0, FieldName("sender") to 1.5)))

    private fun ctx() = EvalContext(clock.now())

    suspend fun refreshAccounts() {
        accounts = store.accounts()
        // Folders-as-labels: Gmail's All Mail, Google accounts not yet synced, and JMAP (extra mailboxes are labels).
        labelFolders = store.mailboxes().any { it.role == MailboxRole.ALL } ||
            accounts.any { it.protocol == dev.aarso.mail.model.IncomingProtocol.JMAP || it.providerId.contains("gmail", true) || it.providerId.contains("google", true) }
    }

    /** Parse as typed, with natural-language rewriting unless [natural] is false. Never throws. */
    fun parse(text: String, natural: Boolean = true): ParsedQuery {
        val c = ctx()
        if (!natural) return QueryParser.parse(text, fields.registry, c, zone)
        return NaturalQuery.interpret(MailNatural.rewrite(text), MailNatural.vocabulary, fields.registry, c, zone)
    }

    // ------------------------------------------------------------------------------------------

    private class Plan(
        val where: String,
        val args: List<Any?>,
        val fts: String?,
        /** The conjuncts SQL/FTS could not answer exactly; null when nothing is left to check. */
        val residual: ParsedQuery?,
        val residualNeedsText: Boolean,
        val notes: List<String>,
    )

    private fun isLexical(n: QueryNode): Boolean = when (n) {
        is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Semantic -> true
        is QueryNode.Not -> isLexical(n.child)
        is QueryNode.Or -> n.children.all(::isLexical)
        is QueryNode.And -> n.children.all(::isLexical)
        else -> false
    }

    private fun hasText(n: QueryNode?): Boolean = when (n) {
        null -> false
        is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Semantic, is QueryNode.Regex -> true
        is QueryNode.Not -> hasText(n.child)
        is QueryNode.Or -> n.children.any(::hasText)
        is QueryNode.And -> n.children.any(::hasText)
        is QueryNode.Facet -> n.key.equals("body", true) || n.key.equals("text", true)
    }

    private fun sqlFor(n: QueryNode, c: EvalContext): SqlPart? = when (n) {
        is QueryNode.Facet -> {
            val f = fields.facet(n.key)
            val bounds = n.rangeBounds
            if (f != null && bounds != null && (f.valueKind == dev.aarso.search.FacetValueKind.DATE || f.valueKind == dev.aarso.search.FacetValueKind.NUMBER)) {
                val lo = f.sql(dev.aarso.search.Op.GTE, bounds.first.trim(), c)
                val hi = f.sql(dev.aarso.search.Op.LTE, bounds.second.trim(), c)
                if (lo != null && hi != null) SqlPart("(${lo.sql}) AND (${hi.sql})", lo.args + hi.args, lo.exact && hi.exact) else null
            } else f?.sql(n.op, n.value.trim(), c)
        }
        is QueryNode.Not -> sqlFor(n.child, c)?.let { SqlPart("NOT (${it.sql})", it.args, it.exact) }
        is QueryNode.Or -> {
            val parts = n.children.map { sqlFor(it, c) }
            if (parts.any { it == null }) null else SqlPart(parts.joinToString(" OR ", "(", ")") { "(${it!!.sql})" }, parts.flatMap { it!!.args }, parts.all { it!!.exact })
        }
        is QueryNode.And -> {
            val parts = n.children.map { sqlFor(it, c) }
            if (parts.any { it == null }) null else SqlPart(parts.joinToString(" AND ", "(", ")") { "(${it!!.sql})" }, parts.flatMap { it!!.args }, parts.all { it!!.exact })
        }
        else -> null
    }

    private fun plan(parsed: ParsedQuery, scope: SearchScope, c: EvalContext): Plan {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        val notes = ArrayList<String>()
        if (!scope.includeHidden) where += "m.hidden = 0"
        if (scope.accountIds.isNotEmpty()) where += "m.account_id IN (${scope.accountIds.joinToString(",")})"
        if (scope.mailboxIds.isNotEmpty()) where += "m.mailbox_id IN (${scope.mailboxIds.joinToString(",")})"
        scope.messageIds?.let { ids -> where += if (ids.isEmpty()) "0" else "m.id IN (${ids.joinToString(",")})" }

        val facetValues = parsed.facets.map { it.key.lowercase() to it.value.lowercase() }
        val inWords = setOf("in", "folder", "mailbox", "box")
        val wantsBin = facetValues.any { (k, v) -> k in inWords && v in setOf("trash", "bin", "deleted", "spam", "junk", "anywhere", "all", "everywhere") } ||
            scope.mailboxIds.isNotEmpty() || scope.messageIds != null
        if (!wantsBin) where += "mb.role NOT IN ('TRASH', 'JUNK')"
        val wantsSnoozed = facetValues.any { (k, v) -> (k == "is" || k in inWords) && v == "snoozed" } || scope.messageIds != null
        if (!wantsSnoozed) { where += "m.snoozed_until <= ?"; args += c.nowMillis }
        if (scope.collapseThreads) {
            where += "m.received_at = (SELECT MAX(m2.received_at) FROM message m2 WHERE m2.thread_key = m.thread_key AND m2.account_id = m.account_id AND m2.hidden = 0)"
        }

        val root = parsed.root ?: return Plan(where.joinToString(" AND "), args, null, null, false, notes)
        val conjuncts = if (root is QueryNode.And) root.children else listOf(root)
        val lexical = ArrayList<QueryNode>()
        val residual = ArrayList<QueryNode>()
        for (n in conjuncts) {
            if (isLexical(n)) { lexical += n; continue }
            val sql = sqlFor(n, c)
            if (sql != null) {
                where += "(${sql.sql})"; args.addAll(sql.args)
                if (!sql.exact) residual += n
            } else residual += n
        }

        var fts: String? = null
        if (lexical.isNotEmpty()) {
            val positive = lexical.filterNot { it is QueryNode.Not }
            if (positive.isNotEmpty()) {
                fts = QueryCompiler.compileFts(if (lexical.size == 1) lexical[0] else QueryNode.And(lexical), prefixBareTerms = true)
                fts?.let { where += "m.id IN (SELECT rowid FROM message_fts WHERE message_fts MATCH ?)"; args += it }
            } else {
                // Only exclusions ("-draft"): FTS5 has no standalone NOT, so exclude each match set.
                for (neg in lexical) {
                    val expr = QueryCompiler.compileFts((neg as QueryNode.Not).child, prefixBareTerms = true) ?: continue
                    where += "m.id NOT IN (SELECT rowid FROM message_fts WHERE message_fts MATCH ?)"; args += expr
                }
            }
        }
        if (residual.any { FacetEvaluator.hasLossyDisjunction(it) }) notes += "Mixed text-and-filter OR is checked against subjects, senders and message text."
        if (parsed.diagnostics.any { it is Diagnostic.SemanticUnavailable }) notes += "Meaning-based search is matching words for now."

        val residualQuery = if (residual.isEmpty()) null else {
            val r = if (residual.size == 1) residual[0] else QueryNode.And(residual)
            ParsedQuery(parsed.rawText, r, parsed.facets, parsed.semanticText, emptyList(), emptyList())
        }
        return Plan(where.joinToString(" AND "), args, fts, residualQuery, residualQuery?.root?.let(::hasText) == true, notes)
    }

    // ------------------------------------------------------------------------------------------

    private suspend fun mailboxMap(): Map<Long, Mailbox> = store.mailboxes().associateBy { it.id }

    private suspend fun verify(candidates: List<MessageHeader>, plan: Plan, c: EvalContext): List<MessageHeader> {
        val residual = plan.residual ?: return candidates
        if (candidates.isEmpty()) return candidates
        val boxes = mailboxMap()
        val accts = accounts.associateBy { it.id }
        val text = if (plan.residualNeedsText) store.searchText(candidates.map { it.id }) else emptyMap()
        return candidates.filter { h ->
            val item = MailItem(h, boxes[h.mailboxId], accts[h.accountId], text[h.id])
            if (plan.residualNeedsText) {
                val doc = SearchDoc(
                    id = DocId(h.id.toString()), timestampMillis = h.receivedAt,
                    text = (text[h.id] ?: mapOf("subject" to h.subject, "sender" to listOfNotNull(h.from?.name, h.from?.address).joinToString(" "), "body" to h.preview))
                        .mapKeys { FieldName(it.key) },
                )
                matcher.matches(item, doc, residual, c)
            } else FacetEvaluator.matches(item, residual, fields.registry, c)
        }
    }

    /**
     * @param countTotal whether to also count every match when the page came back full. The count scans
     *   the matches (tens to hundreds of ms on a big mailbox); a list that only needs its first rows
     *   passes false and asks [count] separately, so the rows never wait for the number.
     */
    suspend fun search(
        parsed: ParsedQuery, scope: SearchScope = SearchScope(), sort: SortOrder = SortOrder.NEWEST, limit: Int = 100, offset: Int = 0,
        countTotal: Boolean = true,
    ): SearchResult {
        refreshAccounts()
        val c = ctx()
        val plan = plan(parsed, scope, c)
        if (plan.residual == null) {
            val ranked = sort == SortOrder.RELEVANCE && plan.fts != null
            val rows = store.queryHeaders(plan.where, plan.args, if (ranked) "f.r, m.received_at DESC" else sort.sql, limit, offset, rankedBy = if (ranked) plan.fts else null)
            val total = if (offset == 0 && rows.size < limit) rows.size else if (countTotal) store.countWhere(plan.where, plan.args) else null
            return SearchResult(rows, total, parsed, plan.notes)
        }
        // Residual present: page through SQL-narrowed candidates, verifying each page exactly.
        val out = ArrayList<MessageHeader>()
        var skipped = 0
        var page = 0
        val pageSize = 400
        var exhausted = false
        while (out.size < limit) {
            val batch = store.queryHeaders(plan.where, plan.args, sort.sql, pageSize, page * pageSize)
            if (batch.isEmpty()) { exhausted = true; break }
            for (h in verify(batch, plan, c)) {
                if (skipped < offset) { skipped++; continue }
                if (out.size < limit) out += h
            }
            if (batch.size < pageSize) { exhausted = true; break }
            page++
            if (page > 250) break // 100k candidates: say "at least" rather than stall
        }
        return SearchResult(out, if (exhausted) skipped + out.size else null, parsed, plan.notes)
    }

    /** The SQL a query pushes down and SQLite's plan for it — for finding out why something is slow. */
    suspend fun explain(text: String, scope: SearchScope = SearchScope(), sort: SortOrder = SortOrder.NEWEST): List<String> {
        val (where, args) = pushedDown(text, scope)
        val sql = "SELECT m.id FROM message m CROSS JOIN mailbox mb ON mb.id = m.mailbox_id WHERE $where ORDER BY ${sort.sql} LIMIT 150"
        val lines = store.db.read { query("EXPLAIN QUERY PLAN $sql", *args.toTypedArray()) { it.text(3) } }
        return listOf("WHERE $where", "ARGS $args") + lines
    }

    /** The WHERE clause and arguments a query pushes down to SQL (before any residual verification). */
    suspend fun pushedDown(text: String, scope: SearchScope = SearchScope()): Pair<String, List<Any?>> {
        refreshAccounts()
        val plan = plan(parse(text, natural = false), scope, ctx())
        return plan.where to plan.args
    }

    suspend fun search(text: String, scope: SearchScope = SearchScope(), sort: SortOrder = SortOrder.NEWEST, limit: Int = 100): SearchResult =
        search(parse(text), scope, sort, limit)

    /** Every matching id (for "select all N", bulk actions and rules). Capped at [max]. */
    suspend fun ids(parsed: ParsedQuery, scope: SearchScope = SearchScope(), max: Int = 200_000): List<Long> {
        refreshAccounts()
        val c = ctx()
        val plan = plan(parsed, scope, c)
        val out = ArrayList<Long>()
        if (plan.residual == null) {
            store.forEachHeader(plan.where, plan.args) { out += it.id; out.size < max }
            return out
        }
        val pending = ArrayList<MessageHeader>(500)
        val all = ArrayList<MessageHeader>()
        store.forEachHeader(plan.where, plan.args) { h -> all += h; all.size < max * 4 }
        for (chunk in all.chunked(500)) {
            pending.clear(); pending += chunk
            verify(pending, plan, c).mapTo(out) { it.id }
            if (out.size >= max) break
        }
        return out.take(max)
    }

    suspend fun count(parsed: ParsedQuery, scope: SearchScope = SearchScope()): Int {
        refreshAccounts()
        val plan = plan(parsed, scope, ctx())
        return if (plan.residual == null) store.countWhere(plan.where, plan.args) else ids(parsed, scope).size
    }

    /**
     * Counts grouped by a message column (category, account) — the digest's tab badges in one query.
     * Value is (total, unread). Falls back to exact in-memory grouping when the query has a residual.
     */
    suspend fun groupCounts(parsed: ParsedQuery, scope: SearchScope, column: String): Map<String, Pair<Int, Int>> {
        refreshAccounts()
        val c = ctx()
        val plan = plan(parsed, scope, c)
        if (plan.residual == null) {
            return store.db.read {
                query(
                    "SELECT $column, COUNT(*), SUM(CASE WHEN (m.flags & 1) = 0 THEN 1 ELSE 0 END) FROM message m CROSS JOIN mailbox mb ON mb.id = m.mailbox_id WHERE ${plan.where} GROUP BY $column",
                    *plan.args.toTypedArray(),
                ) { r -> r.text(0) to (r.int(1) to r.int(2)) }.toMap()
            }
        }
        val headers = store.headers(ids(parsed, scope))
        val key: (MessageHeader) -> String = when (column) {
            "m.category" -> { h -> h.category.key }
            "m.account_id" -> { h -> h.accountId.toString() }
            else -> { h -> h.mailboxId.toString() }
        }
        return headers.groupBy(key).mapValues { (_, v) -> v.size to v.count { !it.isSeen } }
    }

    /**
     * The matching passage for each of [ids], with the matched words wrapped in [HIT_START]/[HIT_END]
     * — what a result shows under its subject so you can tell *why* it matched. Empty for queries with
     * no words to match (pure filters).
     */
    suspend fun snippets(parsed: ParsedQuery, ids: Collection<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        refreshAccounts()
        val fts = plan(parsed, SearchScope(), ctx()).fts ?: return emptyMap()
        return store.db.read {
            query(
                "SELECT rowid, snippet(message_fts, -1, char(2), char(3), '…', 16) FROM message_fts WHERE message_fts MATCH ? AND rowid IN (${ids.joinToString(",")})",
                fts,
            ) { it.long(0) to it.text(1) }
        }.toMap()
    }

    /** What the newest matches break down into — the "refine" chips under a result list. */
    data class Facets(
        val sampled: Int,
        val senders: List<Triple<String, String?, Int>>,
        val categories: List<Pair<Category, Int>>,
        val accounts: List<Pair<Long, Int>>,
        val years: List<Pair<Int, Int>>,
        val withAttachments: Int,
        val unread: Int,
    )

    /**
     * Break the newest [sample] matches down by sender, category, account and year. One date-ordered
     * scan that stops at [sample] rows (so it costs the same on a million messages as on ten thousand)
     * and is aggregated here; the counts are "of the newest [sample]" when there are more.
     */
    suspend fun facets(parsed: ParsedQuery, scope: SearchScope = SearchScope(), top: Int = 6, sample: Int = 20_000): Facets {
        refreshAccounts()
        val plan = plan(parsed, scope, ctx())
        val rows: List<Array<Any?>> = if (plan.residual == null) {
            store.db.read {
                query(
                    "SELECT m.from_addr, m.from_name, m.category, m.account_id, m.received_at, m.attachment_count, m.flags FROM message m CROSS JOIN mailbox mb ON mb.id = m.mailbox_id WHERE ${plan.where} ORDER BY m.received_at DESC, m.id DESC LIMIT ?",
                    *(plan.args + sample).toTypedArray(),
                ) { r -> arrayOf(r.textOrNull(0), r.textOrNull(1), r.text(2), r.long(3), r.long(4), r.int(5), r.int(6)) }
            }
        } else {
            store.headers(ids(parsed, scope, sample)).map { h -> arrayOf(h.from?.address, h.from?.name, h.category.key, h.accountId, h.receivedAt, h.attachmentCount, h.flags) }
        }
        val bySender = HashMap<String, Pair<String?, Int>>()
        val byCategory = HashMap<Category, Int>()
        val byAccount = HashMap<Long, Int>()
        val byYear = HashMap<Int, Int>()
        var attach = 0
        var unread = 0
        for (r in rows) {
            (r[0] as String?)?.let { a -> val key = a.lowercase(); val cur = bySender[key]; bySender[key] = ((cur?.first ?: r[1] as String?) to ((cur?.second ?: 0) + 1)) }
            Category.fromKey(r[2] as String)?.let { byCategory.merge(it, 1, Int::plus) }
            byAccount.merge(r[3] as Long, 1, Int::plus)
            byYear.merge(java.time.Instant.ofEpochMilli(r[4] as Long).atZone(zone).year, 1, Int::plus)
            if ((r[5] as Int) > 0) attach++
            if ((r[6] as Int) and Flags.SEEN == 0) unread++
        }
        return Facets(
            rows.size,
            bySender.entries.sortedByDescending { it.value.second }.take(top).map { Triple(it.key, it.value.first, it.value.second) },
            byCategory.entries.sortedByDescending { it.value }.take(top).map { it.key to it.value },
            byAccount.entries.sortedByDescending { it.value }.map { it.key to it.value },
            byYear.entries.sortedByDescending { it.key }.take(top).map { it.key to it.value },
            attach, unread,
        )
    }

    /** Which of [ids] satisfy [parsed] — selection rules over an explicit set. */
    suspend fun matchesAmong(parsed: ParsedQuery, ids: Set<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet() else ids(parsed, SearchScope(messageIds = ids)).toSet()

    companion object {
        /** Wrap matched words in [snippets] (control characters that cannot occur in mail text). */
        const val HIT_START = '\u0002'
        const val HIT_END = '\u0003'
    }
}
