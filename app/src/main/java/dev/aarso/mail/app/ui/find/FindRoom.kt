@file:OptIn(ExperimentalLayoutApi::class, FlowPreview::class)

package dev.aarso.mail.app.ui.find

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.aarso.mail.ai.AiResult
import dev.aarso.mail.ai.Answer
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.MailSheet
import dev.aarso.mail.app.ui.components.ProvenanceMark
import dev.aarso.mail.app.ui.components.Room
import dev.aarso.mail.app.ui.components.RoomChip
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.list.MessageList
import dev.aarso.mail.app.ui.list.rememberListController
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.SavedSearch
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.search.SortOrder
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val FIELDS = listOf(
    "from:" to "From", "to:" to "To", "subject:" to "Subject", "has:attachment" to "Attachment", "is:unread" to "Unread",
    "is:starred" to "Starred", "date:this-week" to "This week", "older_than:1y" to "Older than a year", "size:>5mb" to "Over 5 MB",
    "in:anywhere" to "Everywhere", "is:person" to "From people", "has:unsubscribe" to "Lists",
)

/**
 * The right room: search that does what a desktop client does and more. The query language
 * (or a plain sentence, which is rewritten into it), live results with select-all-matching and
 * bulk actions, the server's own search for mail older than the device holds, saved searches,
 * and — with a model — questions answered from the mail itself, with sources.
 */
@Composable
fun FindRoom(onOpen: (Screen) -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    val settings by g.settings.collectAsState()
    val saved by remember { g.store.observeSavedSearches() }.collectAsState(initial = emptyList())
    var text by rememberSaveable { mutableStateOf("") }
    var live by remember { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<AiResult<Answer>?>(null) }
    var serverHits by remember { mutableStateOf<Int?>(null) }
    var serverBusy by remember { mutableStateOf(false) }
    val json = remember { Json }
    val recent by produceState(emptyList<String>(), live) {
        value = g.store.setting(RECENT)?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
    }

    LaunchedEffect(Unit) { snapshotFlow { text }.debounce(220).collect { live = it.trim() } }

    val list = rememberListController(key = "find", query = "", scope = SearchScope(), sort = SortOrder.RELEVANCE, natural = true)
    if (list.query != live) list.query = live
    val result by list.result.collectAsState()

    fun addRecent(q: String) = scope.launch {
        if (q.isBlank()) return@launch
        val next = (listOf(q) + recent.filter { it != q }).take(12)
        g.store.putSetting(RECENT, json.encodeToString(ListSerializer(String.serializer()), next))
    }

    Room("Find", actions = { IconAction(HyIcons.list, "How to search", Palette.RoomInk, { help = !help }) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            LineField(
                text, { text = it; answer = null; serverHits = null }, "Search, or ask in a sentence", dark = true,
                style = Type.heading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { live = text.trim(); addRecent(live) }),
                trailing = { if (text.isNotEmpty()) IconAction(HyIcons.close, "Clear", Palette.RoomInk, { text = ""; live = ""; answer = null }) },
            )
            val parsed = result?.parsed
            if (parsed != null && live.isNotEmpty()) {
                val chips = parsed.chips
                if (chips.size > 1 || chips.any { it.kind == dev.aarso.search.ChipKind.FACET }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in chips) RoomChip(c.text, false, {
                            // Tap a chip to drop that part of the query.
                            text = chips.filter { it != c }.joinToString(" ") { it.text }
                        })
                    }
                }
                for (d in parsed.diagnostics.take(2)) Text(d.explain(), style = Type.small.copy(color = Palette.Warning), modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((term, label) in FIELDS) RoomChip(label, text.split(' ').contains(term), {
                    text = if (text.split(' ').contains(term)) text.split(' ').filter { it != term }.joinToString(" ") else (text.trimEnd() + " " + term).trimStart()
                })
            }
        }

        if (help) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                for ((q, what) in QUERY_HELP) {
                    Row(Modifier.fillMaxWidth().clickable { text = (text.trimEnd() + " " + q.substringBefore("  ").substringBefore('|')).trimStart(); help = false }.padding(vertical = 8.dp)) {
                        Text(q, style = Type.mono.copy(color = Palette.RoomInk), modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(what, style = Type.small.copy(color = Palette.RoomInkDim), modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        } else if (live.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                if (saved.isNotEmpty()) {
                    SectionLabel("Saved", dark = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in saved) RoomChip(s.name, false, { onOpen(Screen.Mailbox(s.name, s.query)) })
                    }
                }
                if (recent.isNotEmpty()) {
                    SectionLabel("Recent", dark = true)
                    for (q in recent) Text(q, style = Type.body.copy(color = Palette.RoomInk), modifier = Modifier.fillMaxWidth().clickable { text = q }.padding(vertical = 8.dp))
                }
                SectionLabel("Try", dark = true)
                for (q in listOf("invoices from last month with a pdf", "from:me has:attachment larger:10mb", "is:unread older_than:7d is:person", "flight confirmation 2024", "category:newsletters is:unread")) {
                    Text(q, style = Type.body.copy(color = Palette.RoomInkDim), modifier = Modifier.fillMaxWidth().clickable { text = q }.padding(vertical = 8.dp))
                }
            }
        } else {
            // ---- results ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(result?.total?.let { countPhrase(it, "result") } ?: "", style = Type.label.copy(color = Palette.RoomInkDim), modifier = Modifier.padding(start = 10.dp).weight(1f))
                TextAction(if (serverBusy) "Searching servers…" else serverHits?.let { "Servers: $it more" } ?: "Search servers too", Palette.Window, {
                    if (serverBusy) return@TextAction
                    serverBusy = true
                    scope.launch {
                        var n = 0
                        for (a in g.accountsNow().filter { it.enabled }) n += runCatching { g.sync.serverSearch(a, live).size }.getOrDefault(0)
                        serverHits = n; serverBusy = false
                    }
                })
                TextAction(if (asking) "Thinking…" else "Ask", Palette.Window, {
                    if (asking) return@TextAction
                    asking = true
                    scope.launch {
                        answer = g.ai.ask(live, g.search, g.store, SearchScope(), java.time.LocalDate.now().toString())
                        if (answer == null) app.say("No model can answer questions — set one up in Settings → Intelligence")
                        asking = false
                    }
                })
                IconAction(HyIcons.star, "Save this search", Palette.RoomInk, {
                    scope.launch { g.store.saveSearch(SavedSearch(name = live.take(40), query = live)); app.say("Saved") }
                })
            }
            answer?.let { a ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        ProvenanceMark(a.onDevice, modifier = Modifier.padding(top = 7.dp)); Spacer(Modifier.width(10.dp))
                        Text(a.value.text, style = Type.body.copy(color = Palette.RoomInk))
                    }
                    for (s in a.value.sources) Text(
                        "→ ${s.from?.shortName() ?: ""}: ${s.subject}", style = Type.small.copy(color = Palette.Window),
                        modifier = Modifier.clickable { onOpen(Screen.Reader(s.id, a.value.sources.map { it.id })) }.padding(vertical = 4.dp),
                    )
                }
            }
            MailSheet(Modifier.fillMaxSize().imePadding()) {
                MessageList(
                    controller = list,
                    tldr = settings.tldr,
                    onOpen = { id, siblings -> addRecent(live); onOpen(Screen.Reader(id, siblings)) },
                    emptyText = "No mail matches. Try “Search servers too” for older mail.",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val RECENT = "find.recent"
