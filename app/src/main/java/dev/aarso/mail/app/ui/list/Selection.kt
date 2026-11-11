@file:OptIn(ExperimentalLayoutApi::class)

package dev.aarso.mail.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.platform.Unsubscriber
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.find.explain
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.bytes
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.components.shortWhen
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.select.SelectionMode
import dev.aarso.mail.select.SelectionRule
import kotlinx.coroutines.launch

/** Everything that can be done to a selection, however large. */
@Composable
fun BulkSheet(controller: ListController, onDismiss: () -> Unit, onSnooze: (List<Long>) -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val scope = rememberCoroutineScope()
    var move by remember { mutableStateOf<List<Long>?>(null) }
    var label by remember { mutableStateOf<List<Long>?>(null) }
    var category by remember { mutableStateOf<List<Long>?>(null) }
    var confirmForever by remember { mutableStateOf<List<Long>?>(null) }
    var stats by remember { mutableStateOf(false) }

    fun withIds(block: suspend (List<Long>) -> Unit) = scope.launch { block(controller.resolve()) }
    fun finish(block: suspend (List<Long>) -> dev.aarso.mail.sync.UndoToken) = withIds { ids -> controller.clear(); onDismiss(); app.act { block(ids) } }

    MailBottomSheet(onDismiss) {
        Text(countPhrase(controller.selectedCount.value, "message"), style = Type.heading)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            SheetRow("Archive", { finish { g.actions.archive(it) } }, HyIcons.archive)
            SheetRow("Delete", { finish { g.actions.trash(it) } }, HyIcons.trash)
            SheetRow("Mark read", { finish { g.actions.markRead(it, true) } }, HyIcons.mail)
            SheetRow("Mark unread", { finish { g.actions.markRead(it, false) } }, HyIcons.dot)
            SheetRow("Star", { finish { g.actions.star(it, true) } }, HyIcons.star)
            SheetRow("Unstar", { finish { g.actions.star(it, false) } }, HyIcons.minus)
            SheetRow("Snooze…", { withIds { ids -> onSnooze(ids) } }, HyIcons.snooze)
            SheetRow("Move to…", { withIds { move = it } }, HyIcons.folder)
            SheetRow("Labels…", { withIds { label = it } }, HyIcons.tag)
            SheetRow("Belongs in…", { withIds { category = it } }, HyIcons.layers)
            SheetRow("Pin to top", { finish { g.actions.pin(it, true) } }, HyIcons.pin)
            SheetRow("Mute conversations", { finish { g.actions.mute(it, true) } }, HyIcons.bellOff)
            SheetRow("Move to Inbox", { finish { g.actions.moveToInbox(it) } }, HyIcons.inbox)
            SheetRow("Report spam", { finish { g.actions.spam(it, true) } }, HyIcons.shield)
            SheetRow("Not spam", { finish { g.actions.spam(it, false) } }, HyIcons.check)
            SheetRow("Unsubscribe from these lists", {
                withIds { ids ->
                    controller.clear(); onDismiss()
                    val u = Unsubscriber(g)
                    val senders = g.store.headers(ids.take(2000)).filter { it.listUnsubscribe != null }.distinctBy { it.listId ?: it.from?.normalized }
                    var done = 0
                    val pages = ArrayList<String>()
                    for (h in senders) when (val r = u.unsubscribe(h)) {
                        Unsubscriber.Result.Done -> done++
                        is Unsubscriber.Result.OpenPage -> pages += r.url
                        Unsubscriber.Result.NotOffered -> Unit
                    }
                    app.say(buildString {
                        append("Left ${countPhrase(done, "list")}")
                        if (pages.isNotEmpty()) append(" · ${pages.size} need their web page")
                    })
                    pages.firstOrNull()?.let { bridge.launch(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
                }
            }, HyIcons.unsubscribe)
            SheetRow("Make a rule from this selection", {
                scope.launch {
                    val q = controller.selectionQuery() ?: run {
                        val hs = g.store.headers(controller.resolve().take(500))
                        val senders = hs.mapNotNull { it.from?.address }.distinct()
                        if (senders.size in 1..8) senders.joinToString(" OR ") { "from:$it" } else controller.query
                    }
                    onDismiss()
                    app.open(Screen.RuleEditor(MailRule(name = "", query = q, actions = listOf(RuleAction.Archive), triggers = setOf(RuleTrigger.NEW_MAIL))))
                }
            }, HyIcons.bolt)
            SheetRow("What's in the selection", { stats = true }, HyIcons.list)
            SheetRow("Delete forever", { withIds { confirmForever = it } }, HyIcons.trash, danger = true)
        }
    }

    move?.let { ids ->
        val accts = remember(ids) { mutableStateOf(emptySet<Long>()) }
        androidx.compose.runtime.LaunchedEffect(ids) { accts.value = g.store.headers(ids.take(200)).map { it.accountId }.toSet() }
        MoveSheet(accts.value, { move = null }) { box -> move = null; controller.clear(); onDismiss(); app.act { g.actions.moveTo(ids, box.id) } }
    }
    label?.let { ids ->
        val known by produceState(emptyList<String>()) { value = g.store.mailboxes().filter { it.role == MailboxRole.NONE }.map { it.name } }
        LabelSheet(emptySet(), known, { label = null }) { add, remove -> label = null; controller.clear(); onDismiss(); app.act { g.actions.label(ids, add, remove) } }
    }
    category?.let { ids ->
        CategorySheet(null, { category = null }) { c -> category = null; controller.clear(); onDismiss(); app.act { g.actions.recategorize(ids, c) } }
    }
    confirmForever?.let { ids ->
        ConfirmDialog(
            "Delete forever?", "${countPhrase(ids.size, "message")} will be removed from the server. This can't be undone.", "Delete forever",
            onDismiss = { confirmForever = null },
            onConfirm = { confirmForever = null; controller.clear(); onDismiss(); app.act { g.actions.deleteForever(ids) } },
        )
    }
    if (stats) SelectionStatsSheet(controller) { stats = false }
}

/**
 * Rules for selection: grow, trim, intersect or replace the selection by query — presets, "more like
 * this" for a message in view, or anything typed in the search grammar.
 */
@Composable
fun SelectionRulesSheet(controller: ListController, loaded: List<MessageHeader>, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    var mode by remember { mutableStateOf(SelectionMode.ADD) }
    var query by remember { mutableStateOf("") }
    val sel = controller.selection.value
    val focus = loaded.firstOrNull { sel.contains(it.id, it.id in controller.matching.value) } ?: loaded.firstOrNull()
    val diagnostics = remember(query) { if (query.isBlank()) emptyList() else g.search.parse(query, natural = false).diagnostics }

    fun run(rule: SelectionRule) { controller.applyRule(rule.copy(mode = mode)); onDismiss() }

    MailBottomSheet(onDismiss) {
        Text("Rules for selection", style = Type.heading)
        Text("Applied to everything in this list, loaded or not.", style = Type.small.copy(color = Palette.SheetInkDim))
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mo in SelectionMode.entries) SheetChip(
                when (mo) { SelectionMode.REPLACE -> "Select only"; SelectionMode.ADD -> "Add"; SelectionMode.REMOVE -> "Remove"; SelectionMode.INTERSECT -> "Keep only" },
                mo == mode, { mode = mo },
            )
        }
        androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            SectionLabel("Any query")
            LineField(query, { query = it }, "from:shop.example older_than:30d -is:starred", style = Type.mono, trailing = {
                SolidButton("Apply", { if (query.isNotBlank()) run(SelectionRule(query, query.trim())) }, enabled = query.isNotBlank() && diagnostics.isEmpty())
            })
            for (d in diagnostics.take(3)) Text(d.explain(), style = Type.small.copy(color = Palette.Danger), modifier = Modifier.padding(top = 4.dp))
            if (focus != null) {
                SectionLabel("Like “${focus.from?.shortName() ?: focus.subject.take(24)}”")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in g.selection.rulesLike(focus)) SheetChip(r.name, false, { run(r) })
                }
            }
            SectionLabel("Presets")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in g.selection.presets) SheetChip(r.name, false, { run(r) })
            }
            SectionLabel("Also")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetChip("Invert", false, { controller.invert(); onDismiss() })
                SheetChip("All loaded", false, { controller.selectLoaded(); onDismiss() })
                SheetChip("All matching", false, { controller.selectAllMatching(); onDismiss() })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** What a selection holds, before acting on it: size, senders, unsubscribable lists, date span. */
@Composable
fun SelectionStatsSheet(controller: ListController, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val stats by produceState<dev.aarso.mail.select.SelectionStats?>(null) {
        val ids = controller.resolve()
        value = g.selection.stats(g.store.headers(ids.take(20_000)))
    }
    MailBottomSheet(onDismiss) {
        Text("In this selection", style = Type.heading)
        Spacer(Modifier.height(10.dp))
        val s = stats
        if (s == null) {
            Text("Counting…", style = Type.body.copy(color = Palette.SheetInkDim))
        } else {
            Fact("Messages", "%,d".format(s.count))
            Fact("Unread", "%,d".format(s.unread))
            Fact("Space", bytes(s.bytes))
            Fact("With attachments", "%,d".format(s.withAttachments))
            Fact("Senders", "%,d".format(s.senders))
            Fact("From lists you can leave", "%,d".format(s.unsubscribable))
            if (s.count > 0) Fact("Spanning", "${shortWhen(s.oldest)} – ${shortWhen(s.newest)}")
            if (s.accounts > 1) Fact("Accounts", s.accounts.toString())
            SectionLabel("Top senders")
            for ((who, n) in s.topSenders.take(8)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(who.displayName(), style = Type.body, modifier = Modifier.weight(1f), maxLines = 1)
                    SheetChip("%,d".format(n), false, {
                        controller.set(dev.aarso.mail.select.Selection.allMatching("${controller.query} from:${who.address}"))
                        onDismiss()
                    })
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = Type.body.copy(color = Palette.SheetInkDim), modifier = Modifier.weight(1f))
        Text(value, style = Type.body)
    }
}
