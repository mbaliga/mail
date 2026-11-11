@file:OptIn(ExperimentalLayoutApi::class, FlowPreview::class)

package dev.aarso.mail.app.ui.rules

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.Room
import dev.aarso.mail.app.ui.components.RoomChip
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.SwitchRow
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.components.shortWhen
import dev.aarso.mail.app.ui.find.explain
import dev.aarso.mail.app.ui.list.CategorySheet
import dev.aarso.mail.app.ui.list.ConfirmDialog
import dev.aarso.mail.app.ui.list.MailBottomSheet
import dev.aarso.mail.app.ui.list.MoveSheet
import dev.aarso.mail.app.ui.list.SheetRow
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.rules.RuleEngine
import dev.aarso.mail.rules.RuleRunResult
import dev.aarso.mail.rules.SieveExport
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

fun RuleAction.describe(): String = when (this) {
    RuleAction.MarkRead -> "mark read"
    RuleAction.MarkUnread -> "mark unread"
    RuleAction.Star -> "star"
    RuleAction.Unstar -> "unstar"
    RuleAction.Archive -> "archive"
    RuleAction.Trash -> "delete"
    RuleAction.Spam -> "mark as spam"
    RuleAction.Pin -> "pin"
    RuleAction.Mute -> "mute the conversation"
    is RuleAction.MoveTo -> "move to ${mailbox ?: role?.name?.lowercase() ?: "?"}"
    is RuleAction.AddLabel -> "label “$label”"
    is RuleAction.RemoveLabel -> "remove label “$label”"
    is RuleAction.Categorize -> "put in ${category.label}"
    is RuleAction.Snooze -> "snooze ${if (minutes % 1440 == 0) "${minutes / 1440} d" else if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"}"
    is RuleAction.Notify -> when (level) { NotifyLevel.URGENT -> "notify urgently"; NotifyLevel.NORMAL -> "notify"; NotifyLevel.SILENT -> "notify silently"; NotifyLevel.NEVER -> "never notify" }
    is RuleAction.Forward -> "forward to $to"
    is RuleAction.AutoReply -> "reply automatically"
    RuleAction.Summarize -> "summarise with a model"
}

/**
 * The top room: rules. Each is a query — the same language as search — and what to do with what it
 * matches, on new mail, on a schedule ("anything older than a week"), or on demand. Describe one
 * in a sentence and a model writes it; export them all as Sieve for servers that filter themselves.
 */
@Composable
fun RulesRoom(onOpen: (Screen) -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val scope = rememberCoroutineScope()
    val rules by remember { g.store.observeRules() }.collectAsState(initial = emptyList())
    var sentence by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    Room("Rules", actions = {
        IconAction(HyIcons.plus, "New rule", Palette.RoomInk, { onOpen(Screen.RuleEditor(null)) })
        IconAction(HyIcons.code, "Export as Sieve", Palette.RoomInk, {
            scope.launch {
                val all = g.store.rules()
                val names = g.store.mailboxes().associateBy { it.role }
                val r = SieveExport.export(all) { role -> names[role]?.remoteId }
                bridge.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Mail rules (Sieve)").putExtra(Intent.EXTRA_TEXT, r.script), "Export rules").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (r.skipped.isNotEmpty()) app.say("Sieve can't express: ${r.skipped.joinToString()}")
            }
        })
    }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            LineField(sentence, { sentence = it }, "Describe a rule: “archive GitHub mail older than a week”", dark = true, singleLine = false, trailing = {
                TextAction(if (thinking) "Writing…" else "Write it", Palette.Window, {
                    if (thinking || sentence.isBlank()) return@TextAction
                    thinking = true
                    scope.launch {
                        val r = g.ai.toRule(sentence)
                        thinking = false
                        if (r != null) { sentence = ""; onOpen(Screen.RuleEditor(r.value)) } else app.say("No model can write rules right now — add one in Settings → Intelligence, or build it yourself with +")
                    }
                }, enabled = sentence.isNotBlank())
            })
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(top = 8.dp)) {
            items(rules, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(Screen.RuleEditor(r)) }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name.ifBlank { r.query }, style = Type.body.copy(color = if (r.enabled) Palette.RoomInk else Palette.RoomInkDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.query, style = Type.mono.copy(color = Palette.RoomInkDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (RuleTrigger.SCHEDULED in r.triggers) "every ${r.intervalMinutes / 60} h → " else "new mail → ") + r.actions.joinToString(", ") { it.describe() } +
                                (if (r.matchCount > 0) " · ${"%,d".format(r.matchCount)} so far" else "") + (if (r.lastRunAt > 0) " · ${shortWhen(r.lastRunAt)}" else ""),
                            style = Type.small.copy(color = Palette.RoomInkDim), maxLines = 2,
                        )
                    }
                    Switch(
                        checked = r.enabled, onCheckedChange = { on -> scope.launch { g.store.saveRule(r.copy(enabled = on)) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Color.White, uncheckedTrackColor = Palette.RoomCard, uncheckedThumbColor = Palette.RoomInkDim, uncheckedBorderColor = Color.Transparent),
                    )
                }
                Hairline(Modifier.padding(horizontal = 24.dp), color = Palette.RoomHairline)
            }
            item(key = "templates") {
                val missing = RuleEngine.DEFAULTS.filter { d -> rules.none { it.query == d.query } }
                if (missing.isNotEmpty()) Column(Modifier.padding(horizontal = 24.dp)) {
                    SectionLabel("Starting points", dark = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in missing) RoomChip(d.name, false, { onOpen(Screen.RuleEditor(d)) })
                        RoomChip("Archive newsletters after a week", false, {
                            onOpen(Screen.RuleEditor(MailRule(name = "Old newsletters", query = "category:newsletters older_than:7d in:inbox", triggers = setOf(RuleTrigger.SCHEDULED), intervalMinutes = 1440, actions = listOf(RuleAction.Archive))))
                        })
                        RoomChip("Summarise long threads", false, {
                            onOpen(Screen.RuleEditor(MailRule(name = "Summaries", query = "is:person -is:bulk", actions = listOf(RuleAction.Summarize))))
                        })
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Build or change one rule, with a live count of what it would match right now. */
@Composable
fun RuleEditor(initial: MailRule?, onClose: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    val accounts by g.accounts.collectAsState()
    var rule by remember { mutableStateOf(initial ?: MailRule(name = "", query = "", actions = listOf(RuleAction.Archive))) }
    var dry by remember { mutableStateOf<RuleRunResult?>(null) }
    var addAction by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRun by remember { mutableStateOf(false) }
    val diagnostics = remember(rule.query) { if (rule.query.isBlank()) emptyList() else g.rules.diagnose(rule.query) }

    LaunchedEffect(Unit) {
        snapshotFlow { rule.query to rule.accountIds }.debounce(400).collect { (q, _) ->
            dry = if (q.isBlank()) null else runCatching { g.rules.dryRun(rule, 20) }.getOrNull()
        }
    }

    Page(
        title = if (initial?.id ?: 0 > 0) "Edit rule" else "New rule",
        onBack = onClose,
        actions = {
            TextAction("Save", Palette.Ink, {
                scope.launch {
                    g.store.saveRule(rule.copy(name = rule.name.ifBlank { rule.query.take(40) }, createdAt = rule.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
                    onClose()
                }
            }, enabled = rule.query.isNotBlank() && rule.actions.isNotEmpty() && diagnostics.isEmpty())
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            LineField(rule.name, { rule = rule.copy(name = it) }, "Name")
            SectionLabel("When mail matches")
            LineField(rule.query, { rule = rule.copy(query = it) }, "from:@github.com subject:\"[PR]\" -is:starred", style = Type.mono, singleLine = false)
            for (d in diagnostics) Text(d.explain(), style = Type.small.copy(color = Palette.Danger), modifier = Modifier.padding(top = 4.dp))
            dry?.let { d ->
                Text("Right now this matches ${countPhrase(d.matched, "message")}", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 8.dp))
                for (h in d.sample.take(4)) Text("· ${h.from?.shortName() ?: ""} — ${h.subject}", style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (accounts.size > 1) {
                SectionLabel("On")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetChip("Every account", rule.accountIds.isEmpty(), { rule = rule.copy(accountIds = emptySet()) })
                    for (a in accounts) SheetChip(a.email, a.id in rule.accountIds, {
                        rule = rule.copy(accountIds = if (a.id in rule.accountIds) rule.accountIds - a.id else rule.accountIds + a.id)
                    })
                }
            }
            SectionLabel("Run")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetChip("As mail arrives", RuleTrigger.NEW_MAIL in rule.triggers, { rule = rule.copy(triggers = toggle(rule.triggers, RuleTrigger.NEW_MAIL)) })
                SheetChip("On a schedule", RuleTrigger.SCHEDULED in rule.triggers, { rule = rule.copy(triggers = toggle(rule.triggers, RuleTrigger.SCHEDULED)) })
                SheetChip("Only when I say", rule.triggers == setOf(RuleTrigger.MANUAL), { rule = rule.copy(triggers = setOf(RuleTrigger.MANUAL)) })
            }
            if (RuleTrigger.SCHEDULED in rule.triggers) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, mins) in listOf("Hourly" to 60, "Every 6 h" to 360, "Daily" to 1440, "Weekly" to 10080)) SheetChip(label, rule.intervalMinutes == mins, { rule = rule.copy(intervalMinutes = mins) })
                }
            }
            SectionLabel("Do")
            for ((i, a) in rule.actions.withIndex()) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.describe().replaceFirstChar { it.uppercase() }, style = Type.body, modifier = Modifier.weight(1f))
                IconAction(HyIcons.close, "Remove", Palette.SheetInk, { rule = rule.copy(actions = rule.actions.filterIndexed { j, _ -> j != i }) })
            }
            TextAction("+ Add an action", Palette.Navy, { addAction = true })
            SwitchRow("Stop here", rule.stopProcessing, { rule = rule.copy(stopProcessing = it) }, subtitle = "Later rules won't see mail this one matched")
            SwitchRow("On", rule.enabled, { rule = rule.copy(enabled = it) })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SolidButton("Run on existing mail", { confirmRun = true }, enabled = rule.query.isNotBlank() && rule.actions.isNotEmpty() && diagnostics.isEmpty())
                if (rule.id > 0) TextAction("Delete rule", Palette.Danger, { confirmDelete = true })
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (addAction) ActionPicker(onDismiss = { addAction = false }, onPick = { a -> addAction = false; rule = rule.copy(actions = rule.actions + a) })
    if (confirmDelete) ConfirmDialog("Delete this rule?", "Mail it already moved stays where it is.", "Delete", { confirmDelete = false }) {
        confirmDelete = false
        scope.launch { g.store.deleteRule(rule.id); onClose() }
    }
    if (confirmRun) ConfirmDialog(
        "Run now?", "This applies “${rule.actions.joinToString(", ") { it.describe() }}” to ${countPhrase(dry?.matched ?: 0, "message")} that match today.", "Run",
        { confirmRun = false },
    ) {
        confirmRun = false
        scope.launch {
            val saved = g.store.saveRule(rule.copy(name = rule.name.ifBlank { rule.query.take(40) }))
            rule = saved
            val n = g.rules.run(saved)
            app.say("Applied to ${countPhrase(n, "message")}")
        }
    }
}

private fun <T> toggle(s: Set<T>, v: T): Set<T> = (if (v in s) s - v else s + v).ifEmpty { setOf(v) }

@Composable
private fun ActionPicker(onDismiss: () -> Unit, onPick: (RuleAction) -> Unit) {
    var needs by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var move by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(false) }
    MailBottomSheet(onDismiss) {
        Text("Then", style = Type.heading)
        when (needs) {
            null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetRow("Archive", { onPick(RuleAction.Archive) }, HyIcons.archive)
                SheetRow("Delete", { onPick(RuleAction.Trash) }, HyIcons.trash)
                SheetRow("Mark read", { onPick(RuleAction.MarkRead) }, HyIcons.mail)
                SheetRow("Star", { onPick(RuleAction.Star) }, HyIcons.star)
                SheetRow("Pin", { onPick(RuleAction.Pin) }, HyIcons.pin)
                SheetRow("Mute the conversation", { onPick(RuleAction.Mute) }, HyIcons.bellOff)
                SheetRow("Mark as spam", { onPick(RuleAction.Spam) }, HyIcons.shield)
                SheetRow("Move to a folder…", { move = true }, HyIcons.folder)
                SheetRow("Label…", { needs = "label" }, HyIcons.tag)
                SheetRow("Put in a tab…", { category = true }, HyIcons.layers)
                SheetRow("Snooze for…", { needs = "snooze" }, HyIcons.snooze)
                SheetRow("Notify urgently", { onPick(RuleAction.Notify(NotifyLevel.URGENT)) }, HyIcons.bell)
                SheetRow("Notify silently", { onPick(RuleAction.Notify(NotifyLevel.SILENT)) }, HyIcons.bell)
                SheetRow("Never notify", { onPick(RuleAction.Notify(NotifyLevel.NEVER)) }, HyIcons.bellOff)
                SheetRow("Forward to…", { needs = "forward" }, HyIcons.forward)
                SheetRow("Reply automatically…", { needs = "reply" }, HyIcons.reply)
                SheetRow("Summarise with a model", { onPick(RuleAction.Summarize) }, HyIcons.sparkle)
            }
            "label" -> { LineField(value, { value = it }, "Label"); SolidButton("Add", { if (value.isNotBlank()) onPick(RuleAction.AddLabel(value.trim())) }, Modifier.padding(top = 12.dp)) }
            "forward" -> { LineField(value, { value = it }, "someone@example.com"); SolidButton("Add", { if (value.contains('@')) onPick(RuleAction.Forward(value.trim())) }, Modifier.padding(top = 12.dp)) }
            "snooze" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                for ((l, mins) in listOf("1 hour" to 60, "3 hours" to 180, "1 day" to 1440, "3 days" to 4320, "1 week" to 10080)) SheetChip(l, false, { onPick(RuleAction.Snooze(mins)) })
            }
            "reply" -> {
                LineField(body, { body = it }, "Thanks — I'm away until Monday and will reply then.", singleLine = false)
                Text("Never sent to mailing lists, automated mail, or yourself.", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 6.dp))
                SolidButton("Add", { if (body.isNotBlank()) onPick(RuleAction.AutoReply(body = body.trim())) }, Modifier.padding(top = 12.dp))
            }
        }
    }
    if (move) MoveSheet(emptySet(), { move = false }) { b -> move = false; onPick(if (b.role != MailboxRole.NONE) RuleAction.MoveTo(role = b.role) else RuleAction.MoveTo(mailbox = b.name)) }
    if (category) CategorySheet(null, { category = false }) { c -> category = false; onPick(RuleAction.Categorize(c)) }
}
