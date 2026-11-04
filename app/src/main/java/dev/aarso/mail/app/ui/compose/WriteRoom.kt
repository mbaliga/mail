package dev.aarso.mail.app.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.Drafts
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.Room
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.fullWhen
import dev.aarso.mail.app.ui.components.shortWhen
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.store.OutboxItem
import kotlinx.coroutines.launch

/**
 * The bottom room: writing. A new message (or one drafted from a sentence), the drafts kept on this
 * phone, and the outbox — what is waiting for its undo window, its scheduled time, or a connection.
 */
@Composable
fun WriteRoom(onOpen: (Screen) -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    val accounts by g.accounts.collectAsState()
    val drafts by remember { g.store.observeDrafts() }.collectAsState(initial = emptyList())
    val outbox by remember { g.store.observeOutbox() }.collectAsState(initial = emptyList())
    var intent by remember { mutableStateOf("") }

    Room("Write", actions = {
        IconAction(HyIcons.pencil, "New message", Palette.RoomInk, { accounts.firstOrNull()?.let { app.compose(Drafts.new(it)) } ?: onOpen(Screen.AccountSetup()) })
    }) {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 24.dp)) {
            item {
                LineField(intent, { intent = it }, "What do you want to say, and to whom?", dark = true, singleLine = false)
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SolidButton("New message", {
                        val a = accounts.firstOrNull() ?: return@SolidButton onOpen(Screen.AccountSetup())
                        app.compose(Drafts.new(a))
                    }, background = Palette.RoomInk, ink = Palette.Field)
                    TextAction("Draft it for me", Palette.Window, {
                        val a = accounts.firstOrNull() ?: return@TextAction
                        val ask = intent.trim()
                        if (ask.isEmpty()) return@TextAction
                        scope.launch {
                            val r = g.ai.draft(ask, null, a.displayName)
                            if (r == null) app.say("No model can write right now — check Settings → Intelligence")
                            else { intent = ""; app.compose(Drafts.new(a, body = r.value.trim() + "\n")) }
                        }
                    }, enabled = intent.isNotBlank())
                }
            }
            if (outbox.isNotEmpty()) {
                item { SectionLabel("Outbox", dark = true) }
                items(outbox, key = { "o${it.id}" }) { o -> OutboxRow(o, dark = true) }
            }
            if (drafts.isNotEmpty()) {
                item { SectionLabel("Drafts on this phone", dark = true) }
                items(drafts, key = { "d${it.id}" }) { d ->
                    Row(Modifier.fillMaxWidth().clickable { app.compose(d) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.subject.ifBlank { "(no subject)" }, style = Type.body.copy(color = Palette.RoomInk), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((d.to.joinToString(", ") { it.shortName() }.ifBlank { "no recipients yet" }) + " · " + d.textBody.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                                style = Type.small.copy(color = Palette.RoomInkDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(shortWhen(d.updatedAt), style = Type.small.copy(color = Palette.RoomInkDim))
                        IconAction(HyIcons.trash, "Discard draft", Palette.RoomInkDim, { scope.launch { g.store.deleteDraft(d.id) } })
                    }
                    Hairline(color = Palette.RoomHairline)
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun OutboxRow(o: OutboxItem, dark: Boolean) {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    val ink = if (dark) Palette.RoomInk else Palette.SheetInk
    val dim = if (dark) Palette.RoomInkDim else Palette.SheetInkDim
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(o.draft.subject.ifBlank { "(no subject)" }, style = Type.body.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val state = when (o.state) {
                "QUEUED" -> if (o.sendAt > System.currentTimeMillis() + 60_000) "sends ${fullWhen(o.sendAt)}" else "sending"
                "FAILED" -> "not sent: ${o.lastError ?: "error"}"
                else -> o.state.lowercase()
            }
            Text("to ${o.draft.to.joinToString(", ") { it.shortName() }} · $state", style = Type.small.copy(color = if (o.state == "FAILED") Palette.Danger else dim), maxLines = 2)
        }
        TextAction("Edit", if (dark) Palette.Window else Palette.Navy, {
            scope.launch { g.outbox.recall(o.id)?.let { app.compose(g.store.saveDraft(it)) } }
        })
        if (o.state == "FAILED") TextAction("Retry", if (dark) Palette.Window else Palette.Navy, {
            scope.launch { g.store.setOutboxState(o.id, "QUEUED", null, System.currentTimeMillis()); g.scheduleOutbox() }
        })
    }
    Hairline(color = if (dark) Palette.RoomHairline else Palette.Hairline)
}

@Composable
fun OutboxScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val outbox by remember { app.graph.store.observeOutbox() }.collectAsState(initial = emptyList())
    Page("Outbox", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            if (outbox.isEmpty()) item { Text("Nothing waiting to send.", style = Type.body.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(vertical = 24.dp)) }
            items(outbox, key = { it.id }) { OutboxRow(it, dark = false) }
        }
    }
}
