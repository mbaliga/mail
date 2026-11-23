package dev.aarso.mail.app.ui.rules

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.PageFooter
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.SwitchRow
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.find.explain
import dev.aarso.mail.app.ui.list.CategorySheet
import dev.aarso.mail.app.ui.list.ConfirmDialog
import dev.aarso.mail.app.ui.list.MailBottomSheet
import dev.aarso.mail.app.ui.list.SheetRow
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.HeaderOp
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.model.RunLocation
import dev.aarso.mail.model.describe
import dev.aarso.mail.rules.ConditionEval
import dev.aarso.mail.rules.ConditionForm
import dev.aarso.mail.rules.RuleConflict
import dev.aarso.mail.rules.RulePreview
import dev.aarso.mail.sieve.SieveSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One rule, as a sentence: **When mail arrives, if [search], then [actions].** The search is the same box as Find, with the same
 * suggestions; more conditions are behind "Add condition", and "all of / any of" shows up only once there are two. Underneath, what the
 * rule would do right now, where it runs, and whether it fights another rule — each one line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditor(initial: MailRule?, onClose: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    val accounts by g.accounts.collectAsState()
    val outcomes by g.serverRules.collectAsState()

    var rule by remember { mutableStateOf(initial ?: MailRule(name = "", query = "", actions = listOf(RuleAction.Archive))) }
    // The condition lives in the form; the rule is rebuilt from it, so the two never drift.
    var form by remember { mutableStateOf(initial?.let { ConditionForm.from(it) } ?: ConditionForm(initial?.query.orEmpty())) }
    val tooComplex = initial != null && ConditionForm.from(initial) == null
    val built = remember(rule, form, tooComplex) { if (tooComplex) rule else form.toRule(rule) }

    var addCondition by remember { mutableStateOf(false) }
    var addAction by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmForward by remember { mutableStateOf(false) }
    var showSample by remember { mutableStateOf(false) }
    var triggerMenu by remember { mutableStateOf(false) }
    val hasTrash = RuleAction.Trash in built.actions
    var applyNow by remember { mutableStateOf((initial == null || initial.id == 0L) && RuleAction.Trash !in (initial?.actions ?: listOf(RuleAction.Archive))) }
    LaunchedEffect(hasTrash) { if (hasTrash) applyNow = false }

    val diagnostics = remember(built) { if (built.effectiveCondition.let { it is Condition.Query && it.q.isBlank() }) emptyList() else g.rules.diagnose(built.effectiveCondition) }
    val suggestions by produceState(emptyList<dev.aarso.mail.search.Suggestion>(), form.query) {
        value = if (form.query.isBlank()) emptyList() else runCatching { g.suggest.suggest(form.query) }.getOrDefault(emptyList())
    }

    // What the rule would touch right now, debounced.
    var preview by remember { mutableStateOf<Pair<RulePreview, List<dev.aarso.mail.model.MessageHeader>>?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { built }.collect { r ->
            delay(250)
            preview = if (r.query.isBlank() && r.condition == null) null else runCatching { g.rules.preview(r, 5).let { it to g.store.headers(it.sampleIds) } }.getOrNull()
        }
    }
    val conflicts by produceState(emptyList<RuleConflict>(), built.actions, built.query, built.condition, built.accountIds, built.stopProcessing, built.enabled) {
        delay(300)
        value = if (!built.enabled || built.actions.isEmpty()) emptyList() else runCatching { g.rules.conflictsFor(built) }.getOrDefault(emptyList())
    }
    val place = remember(built, accounts) { g.sieve.previewPlacement(built, accounts) }
    val foreign = place.account?.let { outcomes[it.id] } as? SieveSync.Outcome.ForeignScript

    fun forwardTargets(r: MailRule?) = r?.actions?.filterIsInstance<RuleAction.Forward>()?.map { it.to }?.toSet().orEmpty()
    val needsForwardConfirm = forwardTargets(built).isNotEmpty() && (!built.confirmed || forwardTargets(built) != forwardTargets(initial))

    fun save(confirmed: Boolean) {
        scope.launch {
            val toSave = built.copy(name = built.name.ifBlank { built.query.take(40) }, createdAt = built.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                confirmed = if (forwardTargets(built).isEmpty()) built.confirmed else confirmed)
            val saved = g.store.saveRule(toSave)
            if (applyNow) {
                val res = g.rules.applyToExisting(saved)
                app.say(if (res.skipped.isNotEmpty()) res.skipped.first() else "Applied to ${countPhrase(res.matched, "message")}")
                res.undo?.let { app.offerUndo(it) }
            }
            onClose()
        }
    }

    Page(
        title = if ((initial?.id ?: 0) > 0) "Edit rule" else "New rule",
        onBack = onClose,
        footer = {
            PageFooter(
                primary = "Save",
                onPrimary = { if (needsForwardConfirm) confirmForward = true else save(built.confirmed) },
                // A rule with no condition at all would touch every message; it needs at least one.
                enabled = built.actions.isNotEmpty() && diagnostics.isEmpty() && !(built.condition == null && built.query.isBlank()),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            LineField(built.name, { rule = rule.copy(name = it) }, "Name (optional)")

            // ---- the sentence: when ----
            Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box {
                    Text(
                        triggerPhrase(built), style = Type.heading.copy(color = Palette.Window, textDecoration = TextDecoration.Underline),
                        modifier = Modifier.clickable { triggerMenu = true },
                    )
                    DropdownMenu(triggerMenu, { triggerMenu = false }) {
                        for ((label, mins) in listOf("Every hour" to 60, "Every 6 hours" to 360, "Every day" to 1440, "Every week" to 10080)) {
                            DropdownMenuItem(text = { Text(label) }, onClick = { rule = rule.withTrigger(dev.aarso.mail.model.Trigger.OnSchedule(mins)); triggerMenu = false })
                        }
                        DropdownMenuItem(text = { Text("When mail arrives") }, onClick = { rule = rule.withTrigger(dev.aarso.mail.model.Trigger.OnArrival); triggerMenu = false })
                        DropdownMenuItem(text = { Text("Only when I say") }, onClick = { rule = rule.withTrigger(dev.aarso.mail.model.Trigger.Manual); triggerMenu = false })
                    }
                }
                Text(", if", style = Type.heading)
            }

            // ---- if ----
            if (tooComplex) {
                Text(built.effectiveCondition.describe(), style = Type.body, modifier = Modifier.padding(top = 10.dp))
                Text("This condition has groups inside groups; it is kept as it is.", style = Type.small.copy(color = Palette.SheetInkDim))
            } else {
                LineField(form.query, { form = form.copy(query = it) }, if (form.extras.isEmpty()) "from:@github.com subject:\"[PR]\" -is:starred" else "a search (optional)", style = Type.mono, singleLine = false)
                if (suggestions.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sg in suggestions.take(6)) SheetChip(sg.label, false, { form = form.copy(query = sg.apply(form.query)) })
                }
                for (d in diagnostics) Text(d.explain(), style = Type.small.copy(color = Palette.Danger), modifier = Modifier.padding(top = 4.dp))

                if (form.isGroup) {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (form.any) "any of these" else "all of these", style = Type.body.copy(color = Palette.Window, textDecoration = TextDecoration.Underline),
                            modifier = Modifier.clickable { form = form.copy(any = !form.any) })
                        Text(" must match", style = Type.body.copy(color = Palette.SheetInkDim))
                    }
                }
                for ((i, item) in form.extras.withIndex()) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SheetChip("not", item.negated, { form = form.copy(extras = form.extras.mapIndexed { j, it -> if (j == i) it.copy(negated = !it.negated) else it }) })
                    Spacer(Modifier.width(10.dp))
                    Text(item.leaf.describe(), style = Type.body, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconAction(HyIcons.close, "Remove", Palette.SheetInk, { form = form.copy(extras = form.extras.filterIndexed { j, _ -> j != i }) })
                }
                TextAction("+ Add condition", Palette.Navy, { addCondition = true }, Modifier.padding(top = 10.dp))
            }

            // ---- what it would touch ----
            preview?.let { (p, sample) ->
                Text(
                    "Matches ${countPhrase(p.count, "message")}" + if (p.count > 0) (if (showSample) "  ▾" else "  ▸") else "",
                    style = Type.body.copy(color = Palette.Window), modifier = Modifier.padding(top = 14.dp).clickable(enabled = p.count > 0) { showSample = !showSample },
                )
                if (showSample) for (h in sample) Text("· ${h.from?.shortName() ?: ""} — ${h.subject}", style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }

            // ---- then ----
            Text("then", style = Type.heading, modifier = Modifier.padding(top = 22.dp))
            for ((i, a) in built.actions.withIndex()) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.describe().replaceFirstChar { it.uppercase() }, style = Type.body, modifier = Modifier.weight(1f))
                IconAction(HyIcons.close, "Remove", Palette.SheetInk, { rule = rule.copy(actions = rule.actions.filterIndexed { j, _ -> j != i }) })
            }
            TextAction("+ Add action", Palette.Navy, { addAction = true })

            // ---- where it runs, and whether it fights ----
            val onServer = place.location == RunLocation.SERVER && foreign == null
            Text(
                if (onServer) "Runs on the server" else "Runs on this phone",
                style = Type.body.copy(color = Palette.SheetInk), modifier = Modifier.padding(top = 22.dp),
            )
            val why = when {
                foreign != null -> "your server already runs a script of its own (“${foreign.name}”), which this app won't replace"
                !onServer -> place.reasons.firstOrNull()
                else -> "so it works while the phone is off"
            }
            if (why != null) Text(why.replaceFirstChar { it.uppercase() }, style = Type.small.copy(color = Palette.SheetInkDim))
            if (place.location == RunLocation.SERVER || built.runLocation == RunLocation.DEVICE) {
                TextAction(
                    if (built.runLocation == RunLocation.DEVICE) "Let the server do it when it can" else "Keep it on this phone",
                    Palette.Navy, { rule = rule.copy(runLocation = if (built.runLocation == RunLocation.DEVICE) RunLocation.AUTO else RunLocation.DEVICE) }, Modifier.padding(top = 4.dp),
                )
            }
            conflicts.firstOrNull()?.let { c ->
                Text(c.summary.replaceFirstChar { it.uppercase() } + if (conflicts.size > 1) " (and ${conflicts.size - 1} more)" else "", style = Type.small.copy(color = Palette.Danger), modifier = Modifier.padding(top = 10.dp))
            }

            // ---- the rest, quietly ----
            if (accounts.size > 1) {
                SectionLabel("On")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetChip("Every account", built.accountIds.isEmpty(), { rule = rule.copy(accountIds = emptySet()) })
                    for (a in accounts) SheetChip(a.email, a.id in built.accountIds, {
                        rule = rule.copy(accountIds = if (a.id in built.accountIds) built.accountIds - a.id else built.accountIds + a.id)
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("Stop here", built.stopProcessing, { rule = rule.copy(stopProcessing = it) }, subtitle = "Later rules won't see mail this one matched")
            SwitchRow("On", built.enabled, { rule = rule.copy(enabled = it) })
            Row(Modifier.fillMaxWidth().clickable { applyNow = !applyNow }.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                HyIcon(if (applyNow) HyIcons.squareChecked else HyIcons.square, null, Palette.SheetInk, size = 22.dp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Also apply to existing mail", style = Type.body)
                    Text(if (hasTrash) "Left unticked because it deletes — tick it if you mean it" else "One undo covers all of it", style = Type.small.copy(color = Palette.SheetInkDim))
                }
            }
            if ((initial?.id ?: 0) > 0) TextAction("Delete rule", Palette.Danger, { confirmDelete = true }, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(32.dp))
        }
    }

    if (addCondition) ConditionPicker({ addCondition = false }) { c -> addCondition = false; form = form.copy(extras = form.extras + ConditionForm.Item(c)) }
    if (addAction) ActionPicker(onDismiss = { addAction = false }, onPick = { a -> addAction = false; rule = rule.copy(actions = rule.actions + a) })
    if (confirmDelete) ConfirmDialog("Delete this rule?", "Mail it already moved stays where it is.", "Delete", { confirmDelete = false }) {
        confirmDelete = false
        scope.launch { g.store.deleteRule(rule.id); onClose() }
    }
    if (confirmForward) ConfirmDialog(
        "Forward mail automatically?", "Every message this rule matches will be sent on to ${forwardTargets(built).joinToString(", ")}. A rule never forwards anything until you confirm it.", "Confirm and save",
        { confirmForward = false },
    ) { confirmForward = false; save(true) }
}

private fun triggerPhrase(r: MailRule): String = when (val t = r.trigger) {
    dev.aarso.mail.model.Trigger.OnArrival -> "When mail arrives"
    dev.aarso.mail.model.Trigger.Manual -> "Only when I say"
    is dev.aarso.mail.model.Trigger.OnSchedule -> when (t.intervalMinutes) { 60 -> "Every hour"; 360 -> "Every 6 hours"; 1440 -> "Every day"; 10080 -> "Every week"; else -> "Every ${t.intervalMinutes} minutes" }
}

/** More conditions, one kind at a time. Each becomes a row under the search box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionPicker(onDismiss: () -> Unit, onPick: (Condition) -> Unit) {
    var step by remember { mutableStateOf<String?>(null) }
    var header by remember { mutableStateOf("subject") }
    var op by remember { mutableStateOf(HeaderOp.CONTAINS) }
    var value by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(false) }
    MailBottomSheet(onDismiss) {
        Text("And", style = Type.heading)
        when (step) {
            null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetRow("Another search…", { step = "search" }, HyIcons.search)
                SheetRow("A header says…", { step = "header" }, HyIcons.code)
                SheetRow("Older than…", { step = "age" }, HyIcons.clock)
                SheetRow("Larger than…", { step = "size" }, HyIcons.download)
                SheetRow("From a mailing list…", { step = "list" }, HyIcons.list)
                SheetRow("From someone I've written to", { onPick(Condition.FromKnownContact) }, HyIcons.person)
                SheetRow("Is a reply", { onPick(Condition.IsReply) }, HyIcons.reply)
                SheetRow("In a tab…", { category = true }, HyIcons.layers)
            }
            "search" -> { LineField(value, { value = it }, "from:@github.com", style = Type.mono); SolidButton("Add", { if (value.isNotBlank()) onPick(Condition.Query(value.trim())) }, Modifier.padding(top = 12.dp)) }
            "list" -> { LineField(value, { value = it }, "a list id, like news.example.com"); SolidButton("Add", { if (value.isNotBlank()) onPick(Condition.ListId(value.trim())) }, Modifier.padding(top = 12.dp)) }
            "age" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                for ((l, mins) in listOf("1 day" to 1440L, "1 week" to 10080L, "2 weeks" to 20160L, "1 month" to 43200L, "3 months" to 129600L, "1 year" to 525600L)) SheetChip(l, false, { onPick(Condition.AgeOver(mins)) })
            }
            "size" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                for ((l, b) in listOf("100 KB" to 100_000L, "1 MB" to 1_000_000L, "5 MB" to 5_000_000L, "10 MB" to 10_000_000L, "25 MB" to 25_000_000L)) SheetChip(l, false, { onPick(Condition.SizeOver(b)) })
            }
            "header" -> Column {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in ConditionEval.KNOWN_HEADERS.take(8)) SheetChip(h, header == h, { header = h })
                }
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetChip("contains", op == HeaderOp.CONTAINS, { op = HeaderOp.CONTAINS })
                    SheetChip("is exactly", op == HeaderOp.EQUALS, { op = HeaderOp.EQUALS })
                    SheetChip("is there", op == HeaderOp.EXISTS, { op = HeaderOp.EXISTS })
                }
                if (op != HeaderOp.EXISTS) LineField(value, { value = it }, "text")
                SolidButton("Add", { if (op == HeaderOp.EXISTS || value.isNotBlank()) onPick(Condition.Header(header, op, value.trim())) }, Modifier.padding(top = 12.dp))
            }
        }
    }
    if (category) CategorySheet(null, { category = false }) { c -> category = false; onPick(Condition.InCategory(c)) }
}
