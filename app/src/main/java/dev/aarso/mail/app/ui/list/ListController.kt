package dev.aarso.mail.app.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.aarso.mail.app.AppGraph
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.search.SearchResult
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.search.SortOrder
import dev.aarso.mail.select.Selection
import dev.aarso.mail.select.SelectionRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One list's state: what it shows (a query in the shared grammar, a scope, an order), how much of it
 * is loaded, and what is selected.
 *
 * Selection is query-backed: "select all" selects *everything that matches*, not the 50 rows that
 * happen to be loaded — 12,000 newsletters can be archived in one go — and individual rows can still
 * be ticked off or on around it. Selection rules (presets, "everything from this sender", any query)
 * add to, remove from, intersect with or replace the selection.
 */
@Stable
@OptIn(ExperimentalCoroutinesApi::class)
class ListController(
    val graph: AppGraph,
    parent: CoroutineScope,
    initialQuery: String,
    initialScope: SearchScope,
    initialSort: SortOrder = SortOrder.NEWEST,
    val natural: Boolean = false,
) {
    /** Its own scope, so a list that is replaced (another tab, another folder) stops observing the store. */
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    fun close() = scope.cancel()

    var query by mutableStateOf(initialQuery)
    var searchScope by mutableStateOf(initialScope)
    var sort by mutableStateOf(initialSort)
    var limit by mutableStateOf(PAGE)
        private set

    private val _result = MutableStateFlow<SearchResult?>(null)
    val result: StateFlow<SearchResult?> = _result.asStateFlow()

    val items: StateFlow<List<MessageHeader>> = snapshotFlow { Quad(query, searchScope, sort, limit) }
        .distinctUntilChanged()
        .flatMapLatest { (q, s, o, l) ->
            graph.store.observeMessages {
                runCatching { graph.search.search(graph.search.parse(q, natural), s, o, l) }.getOrNull()
            }
        }
        .map { r -> _result.value = r; r?.headers.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun loadMore() {
        val r = _result.value ?: return
        if (r.headers.size >= limit && (r.total == null || r.total!! > r.headers.size)) limit += PAGE
    }

    // ---- selection ----

    private val _selection = MutableStateFlow(Selection.NONE)
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    /** Loaded ids the selection's query matches, so a query-backed selection can tick loaded rows. */
    private val _matching = MutableStateFlow<Set<Long>>(emptySet())
    private val _count = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _count.asStateFlow()

    var anchor: Long? = null

    init {
        scope.launch {
            combine(_selection, items) { sel, rows -> sel to rows }.collect { (sel, rows) ->
                _matching.value = if (sel.query == null) emptySet() else
                    graph.search.matchesAmong(graph.search.parse(sel.query!!, natural), rows.map { it.id }.toSet())
                _count.value = if (sel.isEmpty) 0 else runCatching { graph.selection.count(sel, searchScope) }.getOrDefault(sel.included.size)
            }
        }
    }

    val matching: StateFlow<Set<Long>> = _matching.asStateFlow()

    fun isSelected(sel: Selection, matching: Set<Long>, id: Long) = sel.contains(id, id in matching)

    fun toggle(id: Long) {
        _selection.value = _selection.value.toggle(id, id in _matching.value)
        anchor = id
    }

    /** Long-press: select (never unselect — the row's handler and the list's drag handler both call this). */
    fun select(id: Long) {
        _selection.value = _selection.value.addAll(listOf(id))
        anchor = id
    }

    /** Shift-click / long-press-drag: everything between the anchor and [id], in list order. */
    fun selectRange(id: Long) {
        val rows = items.value
        val a = rows.indexOfFirst { it.id == (anchor ?: id) }
        val b = rows.indexOfFirst { it.id == id }
        if (a < 0 || b < 0) { toggle(id); return }
        val range = rows.subList(minOf(a, b), maxOf(a, b) + 1).map { it.id }
        _selection.value = _selection.value.addAll(range)
    }

    fun setRange(fromId: Long, toId: Long, base: Selection) {
        val rows = items.value
        val a = rows.indexOfFirst { it.id == fromId }
        val b = rows.indexOfFirst { it.id == toId }
        if (a < 0 || b < 0) return
        _selection.value = base.addAll(rows.subList(minOf(a, b), maxOf(a, b) + 1).map { it.id })
    }

    /** Everything this list's query matches — loaded or not. */
    fun selectAllMatching() { _selection.value = Selection.allMatching(query) }

    fun selectLoaded() { _selection.value = Selection.of(items.value.map { it.id }) }

    fun clear() { _selection.value = Selection.NONE; anchor = null }

    fun set(sel: Selection) { _selection.value = sel }

    fun applyRule(rule: SelectionRule) = scope.launch {
        _selection.value = graph.selection.apply(_selection.value, rule, query, searchScope)
    }

    fun invert() = scope.launch { _selection.value = graph.selection.invert(_selection.value, query, searchScope) }

    suspend fun resolve(): List<Long> = graph.selection.resolve(_selection.value, searchScope)

    /** The query the current selection stands for — for "make a rule from this selection". */
    fun selectionQuery(): String? = _selection.value.query?.takeIf { _selection.value.included.isEmpty() && _selection.value.excluded.isEmpty() }

    private data class Quad(val q: String, val s: SearchScope, val o: SortOrder, val l: Int)

    companion object {
        const val PAGE = 150
    }
}

@Composable
fun rememberListController(key: String, query: String, scope: SearchScope, sort: SortOrder = SortOrder.NEWEST, natural: Boolean = false): ListController {
    val app = LocalApp.current
    val cs = rememberCoroutineScope()
    val c = remember(key) { ListController(app.graph, cs, query, scope, sort, natural) }
    DisposableEffect(c) { onDispose { c.close() } }
    return c
}
