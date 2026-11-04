package dev.aarso.mail.select

import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.search.SearchScope

/**
 * A selection that can hold 50,000 messages without holding 50,000 ids.
 *
 * Effective set = (everything [query] matches ∪ [included]) − [excluded]. Tapping toggles ids in and
 * out; "select all N matching" sets the query; selection *rules* rewrite the query (union,
 * difference, intersection) in the same grammar as search. Nothing is materialised until an action
 * runs, and then in batches — which is what lets "select everything from these 40 senders older
 * than a year and archive it" be one gesture on a phone.
 */
data class Selection(
    val query: String? = null,
    val included: Set<Long> = emptySet(),
    val excluded: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean get() = query == null && included.isEmpty()
    val isQueryBased: Boolean get() = query != null

    /** Whether one visible row is selected, given whether it matches [query] (the list already knows). */
    fun contains(id: Long, matchesQuery: Boolean): Boolean = id !in excluded && (id in included || (query != null && matchesQuery))

    fun toggle(id: Long, matchesQuery: Boolean): Selection =
        if (contains(id, matchesQuery)) copy(included = included - id, excluded = if (query != null && matchesQuery) excluded + id else excluded)
        else copy(included = if (query != null && matchesQuery) included else included + id, excluded = excluded - id)

    /** Range select (shift-click, or drag down the gutter): every id in [ids] becomes selected. */
    fun addAll(ids: Collection<Long>): Selection = copy(included = included + ids, excluded = excluded - ids.toSet())

    fun removeAll(ids: Collection<Long>): Selection = copy(included = included - ids.toSet(), excluded = if (query != null) excluded + ids else excluded)

    companion object {
        val NONE = Selection()
        fun of(ids: Collection<Long>) = Selection(included = ids.toSet())
        fun allMatching(query: String) = Selection(query = query)
    }
}

enum class SelectionMode { REPLACE, ADD, REMOVE, INTERSECT }

/** A named, reusable way to select — "old newsletters", "receipts this year", "unread from people". */
data class SelectionRule(val name: String, val query: String, val mode: SelectionMode = SelectionMode.ADD)

data class SelectionStats(
    val count: Int,
    val unread: Int,
    val bytes: Long,
    val withAttachments: Int,
    val senders: Int,
    val topSenders: List<Pair<EmailAddress, Int>>,
    val oldest: Long,
    val newest: Long,
    val accounts: Int,
    val unsubscribable: Int,
)

class SelectionEngine(private val search: MailSearch) {

    private fun group(q: String) = "($q)"

    /** Apply a selection rule against the current view ([viewQuery] is what the list is showing). */
    suspend fun apply(sel: Selection, rule: SelectionRule, viewQuery: String?, scope: SearchScope = SearchScope()): Selection {
        val r = rule.query.trim()
        val inView = if (viewQuery.isNullOrBlank()) r else "${group(viewQuery)} ${group(r)}"
        return when (rule.mode) {
            SelectionMode.REPLACE -> Selection(query = inView)
            SelectionMode.ADD -> {
                val q = if (sel.query == null) inView else "${group(sel.query)} OR ${group(inView)}"
                val revive = search.matchesAmong(search.parse(inView), sel.excluded)
                sel.copy(query = q, excluded = sel.excluded - revive)
            }
            SelectionMode.REMOVE -> {
                val drop = search.matchesAmong(search.parse(r), sel.included)
                sel.copy(query = sel.query?.let { "${group(it)} -${group(r)}" }, included = sel.included - drop)
            }
            SelectionMode.INTERSECT -> {
                val keep = search.matchesAmong(search.parse(r), sel.included)
                sel.copy(query = sel.query?.let { "${group(it)} ${group(r)}" }, included = keep)
            }
        }
    }

    /** Everything in the view that is not selected now. */
    suspend fun invert(sel: Selection, viewQuery: String, scope: SearchScope = SearchScope()): Selection {
        if (sel.query == null) return Selection(query = viewQuery, excluded = sel.included)
        if (sel.query == viewQuery && sel.included.isEmpty()) return Selection(included = sel.excluded)
        val current = resolve(sel, scope).toSet()
        return Selection(query = viewQuery, excluded = current)
    }

    /** The concrete ids, newest first, for an action. */
    suspend fun resolve(sel: Selection, scope: SearchScope = SearchScope(), max: Int = 200_000): List<Long> {
        val fromQuery = sel.query?.let { search.ids(search.parse(it), scope, max) }.orEmpty()
        return (fromQuery + sel.included).distinct().filter { it !in sel.excluded }.take(max)
    }

    suspend fun count(sel: Selection, scope: SearchScope = SearchScope()): Int {
        if (sel.query == null) return (sel.included - sel.excluded).size
        val n = search.count(search.parse(sel.query), scope)
        val extra = sel.included.size - search.matchesAmong(search.parse(sel.query), sel.included).size
        return n + extra - search.matchesAmong(search.parse(sel.query), sel.excluded).size
    }

    /** One-tap rules seeded from a message: same sender, same domain, same list, same thread, same subject. */
    fun rulesLike(h: MessageHeader): List<SelectionRule> = buildList {
        h.from?.let { add(SelectionRule("From ${it.displayName()}", "from:${quote(it.address)}")) }
        h.from?.domain?.takeIf { it.isNotBlank() }?.let { add(SelectionRule("From anyone at $it", "domain:$it")) }
        h.listId?.let { add(SelectionRule("Same mailing list", "list:${quote(it)}")) }
        if (h.threadKey.isNotBlank()) add(SelectionRule("This conversation", "thread:${quote(h.threadKey)}"))
        val base = h.subject.replace(Regex("(?i)^((re|fwd?|aw|wg|tr|sv|vs)\\s*:\\s*)+"), "").trim()
        if (base.length >= 4) add(SelectionRule("Same subject", "subject:${quote(base)}"))
        add(SelectionRule("Same category", "category:${h.category.key}"))
    }

    /** Built-in rules shown in the selection sheet; the person's own saved ones follow. */
    val presets: List<SelectionRule> = listOf(
        SelectionRule("Unread", "is:unread"),
        SelectionRule("Read", "is:read"),
        SelectionRule("Starred", "is:starred"),
        SelectionRule("Not starred", "-is:starred"),
        SelectionRule("With attachments", "has:attachment"),
        SelectionRule("Larger than 5 MB", "size:>5mb"),
        SelectionRule("Older than 30 days", "older_than:30d"),
        SelectionRule("Older than a year", "older_than:1y"),
        SelectionRule("Newsletters", "category:newsletters"),
        SelectionRule("Promotions", "category:promotions"),
        SelectionRule("Can unsubscribe", "has:unsubscribe"),
        SelectionRule("Expired codes", "category:otp older_than:1d"),
        SelectionRule("With trackers", "has:tracker"),
        SelectionRule("Unanswered from people", "is:person is:unanswered -is:mine"),
    )

    fun stats(headers: List<MessageHeader>): SelectionStats {
        val bySender = headers.mapNotNull { it.from }.groupingBy { it.normalized }.eachCount()
        val firstAddress = headers.mapNotNull { it.from }.associateBy { it.normalized }
        return SelectionStats(
            count = headers.size,
            unread = headers.count { !it.isSeen },
            bytes = headers.sumOf { it.size },
            withAttachments = headers.count { it.hasAttachments },
            senders = bySender.size,
            topSenders = bySender.entries.sortedByDescending { it.value }.take(5).mapNotNull { e -> firstAddress[e.key]?.let { it to e.value } },
            oldest = headers.minOfOrNull { it.receivedAt } ?: 0,
            newest = headers.maxOfOrNull { it.receivedAt } ?: 0,
            accounts = headers.map { it.accountId }.distinct().size,
            unsubscribable = headers.filter { it.listUnsubscribe != null }.distinctBy { it.from?.normalized }.size,
        )
    }

    private fun quote(s: String) = if (s.any { it.isWhitespace() || it == '(' || it == ')' }) "\"${s.replace("\"", "")}\"" else s
}
