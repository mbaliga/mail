package dev.aarso.mail.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.Pulse
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.ListenFab
import dev.aarso.mail.app.ui.components.MailSheet
import dev.aarso.mail.app.ui.components.PhraseButton
import dev.aarso.mail.app.ui.components.TabItem
import dev.aarso.mail.app.ui.components.TabStrip
import dev.aarso.mail.app.ui.components.TldrToggle
import dev.aarso.mail.app.ui.components.countLine
import dev.aarso.mail.app.ui.list.MessageList
import dev.aarso.mail.app.ui.list.rememberListController
import dev.aarso.mail.app.ui.theme.LocalMetrics
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.digest.Digest
import dev.aarso.mail.digest.DigestBuilder
import dev.aarso.mail.digest.DigestWindow
import dev.aarso.mail.search.SearchScope
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Home: "You've got / 298 new mail / on Gmail ▾ / since yesterday ▾", the category tabs, and the
 * sheet of one-line summaries. The two phrases are controls — which inboxes, and since when — so
 * the headline is always a true sentence about what the sheet below shows.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val g = app.graph
    val m = LocalMetrics.current
    val scope = rememberCoroutineScope()
    val settings by g.settings.collectAsState()
    val accounts by g.accounts.collectAsState()
    val syncing by g.sync.state.collectAsState()

    val selectedAccounts = remember(settings.digestAccounts, accounts) {
        if (settings.digestAccounts.isEmpty()) accounts else accounts.filter { it.id in settings.digestAccounts }
    }
    val digest by remember(settings.digestWindow, settings.digestAccounts, settings.lastLookAt) {
        g.store.observeMessages {
            g.digest.build(settings.digestAccounts, settings.digestWindow, settings.lastLookAt, g.store.savedSearches())
        }
    }.collectAsState(initial = Digest(0, 0, 0, emptyList()))

    var tabId by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = digest.tabs.firstOrNull { it.id == tabId } ?: digest.tabs.firstOrNull()

    val list = rememberListController(
        key = "home:${tab?.id}:${settings.digestWindow}:${settings.digestAccounts}",
        query = tab?.query ?: "in:inbox -is:mine",
        scope = SearchScope(accountIds = settings.digestAccounts),
    )

    val speaking by g.speaking.collectAsState()
    val items by list.items.collectAsState()

    Box(modifier.fillMaxSize().background(Palette.Field)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- the sentence ----
            // The toggle shares only the first line's corner; every phrase gets the full width.
            Box(Modifier.fillMaxWidth().padding(start = m.gutter, end = m.gutter - 8.dp, top = 18.dp)) {
                Column(Modifier.fillMaxWidth().padding(end = 8.dp).widthIn(max = 720.dp)) {
                    val style = Type.headline(m)
                    Text("You've got", style = style.copy(color = Palette.Ink))
                    val busy = syncing.values.any { it.running }
                    Text(
                        countLine(digest.total, "new mail"),
                        style = style.copy(color = Palette.Ink),
                        modifier = Modifier.alpha(if (busy) breathing() else 1f),
                    )
                    var accountMenu by remember { mutableStateOf(false) }
                    Box {
                        PhraseButton(DigestBuilder.accountPhrase(selectedAccounts, accounts), Palette.Accounts, style, { accountMenu = true })
                        DropdownMenu(accountMenu, { accountMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Every inbox") },
                                onClick = { g.updateSettings { it.copy(digestAccounts = emptySet()) }; accountMenu = false },
                                trailingIcon = { if (settings.digestAccounts.isEmpty()) HyIcon(HyIcons.check, null, Palette.RoomInk, size = 18.dp) },
                            )
                            for (a in accounts) {
                                val on = a.id in settings.digestAccounts
                                DropdownMenuItem(
                                    text = { Column { Text(DigestBuilder.accountName(a, accounts)); Text(a.email, style = Type.small.copy(color = Palette.RoomInkDim)) } },
                                    onClick = {
                                        g.updateSettings { s ->
                                            val next = if (on) s.digestAccounts - a.id else s.digestAccounts + a.id
                                            s.copy(digestAccounts = if (next.size == accounts.size) emptySet() else next)
                                        }
                                    },
                                    trailingIcon = { if (on) HyIcon(HyIcons.check, null, Palette.RoomInk, size = 18.dp) },
                                )
                            }
                            DropdownMenuItem(text = { Text("Add an account…") }, onClick = { accountMenu = false; app.open(Screen.AccountSetup()) })
                        }
                    }
                    var windowMenu by remember { mutableStateOf(false) }
                    Box {
                        PhraseButton(settings.digestWindow.phrase, Palette.Window, style, { windowMenu = true })
                        DropdownMenu(windowMenu, { windowMenu = false }) {
                            for (w in DigestWindow.entries) {
                                DropdownMenuItem(
                                    text = { Text(w.phrase) },
                                    onClick = { g.updateSettings { it.copy(digestWindow = w) }; windowMenu = false },
                                    trailingIcon = { if (w == settings.digestWindow) HyIcon(HyIcons.check, null, Palette.RoomInk, size = 18.dp) },
                                )
                            }
                        }
                    }
                }
                TldrToggle(settings.tldr, { g.updateSettings { it.copy(tldr = !it.tldr) } }, Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
            }

            Spacer(Modifier.height(18.dp))
            TabStrip(
                tabs = digest.tabs.map { TabItem(it.id, it.label, it.unread) },
                selected = tab?.id,
                onSelect = { tabId = it },
            )
            Spacer(Modifier.height(16.dp))

            MailSheet(Modifier.fillMaxSize()) {
                MessageList(
                    controller = list,
                    tldr = settings.tldr,
                    speakingId = speaking?.toLongOrNull(),
                    emptyText = if (accounts.isEmpty()) "Add an account to begin." else "Nothing new here ${settings.digestWindow.phrase}.",
                    onOpen = { id, siblings -> app.open(Screen.Reader(id, siblings)) },
                    modifier = Modifier.fillMaxSize(),
                    bottomPadding = 104.dp,
                )
            }
        }

        // The digest is being read: the button becomes stop.
        ListenFab(
            playing = speaking != null,
            onClick = {
                if (speaking != null) {
                    g.speech.stop()
                } else scope.launch {
                    val heard = items.take(12)
                    val hour = LocalTime.now().hour
                    val greeting = when { hour < 5 -> "Hello"; hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
                    val phrase = DigestBuilder.accountPhrase(selectedAccounts, accounts)
                    val (template, aiBriefing) = g.ai.briefing(greeting, digest, settings.digestWindow.phrase, phrase, emptyList())
                    val intro = aiBriefing?.value ?: template
                    val lines = listOf("intro" to intro) + heard.map { h ->
                        val who = h.from?.shortName() ?: "Someone"
                        h.id.toString() to (h.tldr ?: "$who: ${h.subject}")
                    }
                    g.speech.speak(lines)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = m.gutter, bottom = 24.dp),
        )
    }

    // Model-written summaries for what is on screen, a screenful at a time.
    val visible = items.take(24).map { it.id }
    LaunchedEffect(visible) {
        if (visible.isNotEmpty()) g.scope.launch { runCatching { g.tldrs.upgrade(visible) } }
    }
}

@Composable
private fun breathing(): Float {
    val p = Pulse.WATCHED
    val t = rememberInfiniteTransition(label = "sync")
    return t.animateFloat(p.minAlphaPct / 100f + 0.2f, 1f, infiniteRepeatable(tween(p.periodMs / 2, easing = LinearEasing), RepeatMode.Reverse), label = "a").value
}
