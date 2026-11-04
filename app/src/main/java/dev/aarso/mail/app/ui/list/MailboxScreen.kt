package dev.aarso.mail.app.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.find.explain
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.TldrToggle
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.reader.ReaderContent
import dev.aarso.mail.app.ui.theme.LocalMetrics
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.SavedSearch
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.search.SortOrder
import kotlinx.coroutines.launch

/** Quick filters: each toggles one term in the list's query, so they compose with anything typed. */
private val QUICK = listOf(
    "Unread" to "is:unread", "Starred" to "is:starred", "Attachments" to "has:attachment", "People" to "is:person",
    "Unanswered" to "is:unanswered", "Lists" to "is:bulk", "This week" to "date:this-week", "Big" to "size:>5mb",
)

/**
 * A folder, a label, a saved view or a search, as a page: the list's query is right there to edit
 * (with quick filters that just add or remove terms), any order, threads collapsed or not, and on a
 * wide window the message opens beside the list instead of over it.
 */
@Composable
fun MailboxScreen(screen: Screen.Mailbox, onBack: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val m = LocalMetrics.current
    val scope = rememberCoroutineScope()
    val settings by g.settings.collectAsState()
    val base = SearchScope(accountIds = screen.accountIds, mailboxIds = screen.mailboxIds, collapseThreads = settings.conversationView)
    val list = rememberListController(key = "box:${screen.title}:${screen.query}:${screen.accountIds}:${screen.mailboxIds}", query = screen.query, scope = base, natural = true)
    if (list.searchScope.collapseThreads != settings.conversationView) list.searchScope = list.searchScope.copy(collapseThreads = settings.conversationView)
    val result by list.result.collectAsState()
    var text by rememberSaveable(screen) { mutableStateOf(screen.query) }
    var sortMenu by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Long?>(null) }

    fun apply(q: String) { text = q; list.query = q }
    fun toggleTerm(term: String) {
        val terms = text.split(' ').filter { it.isNotBlank() }
        apply(if (term in terms) terms.filter { it != term }.joinToString(" ") else (terms + term).joinToString(" "))
    }

    Page(
        title = screen.title,
        onBack = onBack,
        subtitle = {
            val total = result?.total
            Text(if (total != null) countPhrase(total, "message") else " ", style = Type.body.copy(color = Palette.InkDim))
        },
        actions = {
            TldrToggle(settings.tldr, { g.updateSettings { it.copy(tldr = !it.tldr) } })
            IconAction(HyIcons.thread, if (settings.conversationView) "Show every message" else "Group conversations", if (settings.conversationView) Palette.Ink else Palette.InkDim, {
                g.updateSettings { it.copy(conversationView = !it.conversationView) }
            })
            Box {
                IconAction(HyIcons.sort, "Order", Palette.Ink, { sortMenu = true })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    for (o in SortOrder.entries.filter { it != SortOrder.RELEVANCE }) DropdownMenuItem(
                        text = { Text(when (o) { SortOrder.NEWEST -> "Newest first"; SortOrder.OLDEST -> "Oldest first"; SortOrder.SENDER -> "By sender"; SortOrder.SUBJECT -> "By subject"; SortOrder.LARGEST -> "Largest first"; SortOrder.PRIORITY -> "Most important first"; else -> o.name }) },
                        onClick = { list.sort = o; sortMenu = false },
                        trailingIcon = { if (list.sort == o) HyIcon(HyIcons.check, null, Palette.RoomInk, size = 18.dp) },
                    )
                }
            }
            Box {
                IconAction(HyIcons.more, "More", Palette.Ink, { saveMenu = true })
                DropdownMenu(saveMenu, { saveMenu = false }) {
                    DropdownMenuItem(text = { Text("Save as a view") }, onClick = {
                        saveMenu = false
                        scope.launch { g.store.saveSearch(SavedSearch(name = screen.title, query = text)); app.say("Saved to Places") }
                    })
                    DropdownMenuItem(text = { Text("Save as a home tab") }, onClick = {
                        saveMenu = false
                        scope.launch { g.store.saveSearch(SavedSearch(name = screen.title, query = text, showAsTab = true)); app.say("Added to home") }
                    })
                    DropdownMenuItem(text = { Text("Select everything here") }, onClick = { saveMenu = false; list.selectAllMatching() })
                    DropdownMenuItem(text = { Text("Make a rule for this") }, onClick = {
                        saveMenu = false
                        app.open(Screen.RuleEditor(dev.aarso.mail.model.MailRule(name = screen.title, query = text, actions = listOf(dev.aarso.mail.model.RuleAction.Archive))))
                    })
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = m.gutter)) {
            LineField(
                text, { text = it }, "Filter: words, from:, has:attachment, older_than:30d…",
                style = Type.body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { list.query = text }),
                trailing = { if (text != list.query) IconAction(HyIcons.arrowRight, "Apply filter", Palette.SheetInk, { list.query = text }) },
            )
            val diagnostics = result?.parsed?.diagnostics.orEmpty()
            for (d in diagnostics.take(2)) Text(d.explain(), style = Type.small.copy(color = Palette.Danger))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val terms = text.split(' ').toSet()
                for ((label, term) in QUICK) SheetChip(label, term in terms, { toggleTerm(term) })
            }
        }
        Hairline()
        if (m.wide) {
            Row(Modifier.fillMaxSize()) {
                MessageList(list, settings.tldr, onOpen = { id, _ -> opened = id }, modifier = Modifier.weight(0.42f).fillMaxHeight(), focusedId = opened)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Hairline))
                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                    val id = opened
                    val h by remember(id) { g.store.observeMessages { id?.let { g.store.header(it) } } }.collectAsState(initial = null)
                    val header = h
                    if (header != null) ReaderContent(header, onDone = {
                        val rows = list.items.value
                        val i = rows.indexOfFirst { it.id == header.id }
                        opened = rows.getOrNull(i + 1)?.id ?: rows.getOrNull(i - 1)?.id
                    }, onNavigate = { d ->
                        val rows = list.items.value
                        val i = rows.indexOfFirst { it.id == header.id }
                        rows.getOrNull(i + d)?.let { opened = it.id }
                    })
                    else Text("Choose a message", style = Type.body.copy(color = Palette.SheetInkDim), modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            MessageList(list, settings.tldr, onOpen = { id, siblings -> app.open(Screen.Reader(id, siblings)) }, modifier = Modifier.fillMaxSize())
        }
    }
    Spacer(Modifier.height(0.dp))
}
