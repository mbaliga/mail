package dev.aarso.mail.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.mail.ai.ActionKind
import dev.aarso.mail.ai.ProposedAction
import dev.aarso.mail.app.ai.Assistant
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.ProvenanceMark
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.list.MailBottomSheet
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The assistant: say what you want done with your mail (or tap "Tidy up"), watch it look, then
 * review what it suggests. Nothing happens until you apply it, and a batch can be undone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantSheet(windowPhrase: String, onDismiss: () -> Unit) {
    MailBottomSheet(onDismiss) { AssistantContent(windowPhrase, onDismiss) }
}

/** The sheet's contents, apart from the modal frame, so they can be shown (and tested) in place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.AssistantContent(windowPhrase: String, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val a = app.graph.assistant
    val st by a.state.collectAsState()
    var goal by remember { mutableStateOf("") }

    run {
        Text("Assistant", style = Type.heading)
        when (val s = st) {
            is Assistant.State.Idle, is Assistant.State.Failed -> {
                if (s is Assistant.State.Failed) Text(s.reason, style = Type.body.copy(color = Palette.Danger), modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(10.dp))
                LineField(
                    goal, { goal = it }, "Tell it what to do with your mail…",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (goal.isNotBlank()) a.start(goal.trim()) }),
                )
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetChip("Tidy up", false, { a.start(Assistant.tidyGoal(windowPhrase)) })
                    SheetChip("What needs a reply?", false, { a.start("Find messages from real people that ask me something and have not been answered. Draft a short reply for each.") })
                    SheetChip("Clear old newsletters", false, { a.start("Archive newsletters and promotions older than a week that I have not starred, and suggest a rule if one sender keeps coming back.") })
                }
                Text(
                    "It runs on this phone when it can, and on your own key only if you allowed “the home assistant” in Settings → Intelligence. It only suggests; you decide.",
                    style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 14.dp),
                )
            }
            is Assistant.State.Running -> {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProvenanceMark(s.onDevice, working = true)
                    Spacer(Modifier.width(10.dp))
                    Text(s.engine?.let { "Working with $it" } ?: "Starting…", style = Type.body)
                }
                Column(Modifier.padding(top = 12.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    for (line in s.trace) Text(line, style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(vertical = 3.dp))
                }
                TextAction("Stop", Palette.Navy, { a.cancel() }, Modifier.padding(top = 12.dp))
            }
            is Assistant.State.Done -> Result(s, a, onDismiss)
        }
    }
}

@Composable
private fun ColumnScope.Result(s: Assistant.State.Done, a: Assistant, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val run = s.run
    val picked = remember(run) { mutableStateListOf<Int>().apply { run.proposals.forEachIndexed { i, p -> if (!p.kind.destructive) add(i) } } }

    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProvenanceMark(run.onDevice)
        Spacer(Modifier.width(10.dp))
        Text(if (run.onDevice) "Worked out on this phone" else "Worked out with your key · ${run.engineLabel}", style = Type.small.copy(color = Palette.SheetInkDim))
    }
    Text(run.summary, style = Type.body, modifier = Modifier.padding(top = 10.dp))

    if (run.proposals.isEmpty()) {
        TextAction("Ask something else", Palette.Navy, { a.dismiss() }, Modifier.padding(top = 14.dp))
        return
    }
    Column(Modifier.padding(top = 8.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
        run.proposals.forEachIndexed { i, p ->
            val on = i in picked
            Row(Modifier.fillMaxWidth().clickable { if (on) picked.remove(i) else picked.add(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                HyIcon(if (on) HyIcons.squareChecked else HyIcons.square, null, Palette.SheetInk, size = 22.dp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = Type.body)
                    if (p.why.isNotBlank()) Text(p.why, style = Type.small.copy(color = Palette.SheetInkDim))
                    detail(p)?.let { Text(it, style = Type.small.copy(color = Palette.SheetInkDim), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                }
            }
            Hairline()
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        SolidButton(if (picked.isEmpty()) "Nothing selected" else "Apply ${picked.size}", {
            val chosen = picked.sorted().map { run.proposals[it] }
            scope.launch {
                val n = a.apply(chosen, app)
                app.say(if (n > 0) "Done · ${countPhrase(n, "message")}" else "Opened for you")
                a.dismiss()
                onDismiss()
            }
        }, enabled = picked.isNotEmpty())
        TextAction("Ask something else", Palette.Navy, { a.dismiss() })
    }
    if (run.proposals.any { it.kind.destructive }) Text("Anything that moves mail to the trash starts unticked.", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 8.dp))
}

private fun detail(p: ProposedAction): String? = when (p.kind) {
    ActionKind.DRAFT_REPLY -> "“" + p.arg.orEmpty().take(200) + "” — opens as a draft; nothing is sent."
    ActionKind.SNOOZE -> "Until ${p.arg}"
    ActionKind.LABEL -> "Label: ${p.arg}"
    ActionKind.MAKE_RULE -> "Opens the rule to review: ${p.query} → ${p.arg}"
    else -> if (p.ids.size > 1) countPhrase(p.ids.size, "message") else null
}
