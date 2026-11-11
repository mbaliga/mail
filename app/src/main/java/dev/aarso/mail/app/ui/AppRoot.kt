package dev.aarso.mail.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.RoomEdge
import dev.aarso.cellshell.ShakeToRefresh
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import dev.aarso.mail.app.Drafts
import dev.aarso.mail.app.ui.accounts.AccountSetupScreen
import dev.aarso.mail.app.ui.compose.ComposeScreen
import dev.aarso.mail.app.ui.compose.OutboxScreen
import dev.aarso.mail.app.ui.compose.WriteRoom
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.bytes
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.find.FindRoom
import dev.aarso.mail.app.ui.home.HomeScreen
import dev.aarso.mail.app.ui.list.MailboxScreen
import dev.aarso.mail.app.ui.places.PlacesRoom
import dev.aarso.mail.app.ui.reader.HtmlBody
import dev.aarso.mail.app.ui.reader.PlainBody
import dev.aarso.mail.app.ui.reader.ReaderScreen
import dev.aarso.mail.app.ui.reader.linkIntent
import dev.aarso.mail.app.ui.rules.RuleEditor
import dev.aarso.mail.app.ui.rules.RulesRoom
import dev.aarso.mail.app.ui.settings.SettingsScreen
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.mime.MimeParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home is always underneath; its four rooms are parked off its edges (Places left, Find right,
 * Rules above, Write below); pages open over it. Back closes a page, then a room.
 */
@Composable
fun AppRoot(app: AppState) {
    val g = app.graph
    val settings by g.settings.collectAsState()
    val accounts by g.accounts.collectAsState()
    val shell = rememberSpatialController()
    val focus = remember { FocusRequester() }

    fun go(screen: Screen) {
        shell.closeAll()
        app.open(screen)
    }

    val needsSetup = accounts.isEmpty() && !settings.onboarded

    Box(
        Modifier.fillMaxSize().background(Palette.Field)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (app.keyHandlers.lastOrNull()?.invoke(e) == true) return@onPreviewKeyEvent true
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.C && !e.isCtrlPressed -> { accounts.firstOrNull()?.let { app.compose(Drafts.new(it)) }; true }
                    e.key == Key.Slash -> { shell.open(RoomEdge.RIGHT); true }
                    e.key == Key.Escape || (e.key == Key.U && app.stack.isNotEmpty()) -> { if (!app.back()) shell.closeAll(); true }
                    e.key == Key.G -> { shell.open(RoomEdge.LEFT); true }
                    e.key == Key.Comma && e.isCtrlPressed -> { app.open(Screen.Settings); true }
                    else -> false
                }
            },
    ) {
        if (needsSetup) {
            Welcome { app.open(Screen.AccountSetup()) }
        } else {
            SpatialShell(
                controller = shell,
                accentColor = Palette.Accent,
                scrimColor = Palette.Room,
                cardColor = Palette.Field,
                left = { PlacesRoom(::go) },
                right = { FindRoom(::go) },
                top = { RulesRoom(::go) },
                bottom = { WriteRoom(::go) },
                home = { HomeScreen() },
            )
            ShakeToRefresh { app.scope.launch { g.sync.syncAll(inboxOnly = true) }; app.say("Checking for mail") }
        }

        // ---- pages over home ----
        val top = app.stack.lastOrNull()
        AnimatedContent(
            targetState = top,
            transitionSpec = {
                if (targetState is Screen.Compose) (slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(160))
                else (slideInHorizontally(tween(240)) { it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(160)))
            },
            label = "pages",
        ) { screen ->
            if (screen != null) Box(Modifier.fillMaxSize().background(Palette.Field)) {
                when (screen) {
                    is Screen.Mailbox -> MailboxScreen(screen) { app.back() }
                    is Screen.Reader -> ReaderScreen(screen.messageId, screen.siblings) { app.back() }
                    is Screen.Compose -> ComposeScreen(screen.draft) { app.back() }
                    Screen.Settings -> SettingsScreen { app.back() }
                    is Screen.AccountSetup -> AccountSetupScreen(screen.reauthAccountId) { app.back() }
                    is Screen.RuleEditor -> RuleEditor(screen.rule) { app.back() }
                    Screen.Outbox -> OutboxScreen { app.back() }
                    is Screen.External -> ExternalMessage(screen) { app.back() }
                    Screen.Shortcuts -> Shortcuts { app.back() }
                }
            }
        }

        BackHandler(enabled = app.stack.isNotEmpty() || shell.anyRoomVisible) {
            if (!app.back()) shell.closeAll()
        }

        Bars(app, Modifier.align(Alignment.BottomCenter))
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Undo, undo-send, and short notes — one bar at a time, over everything. */
@Composable
private fun Bars(app: AppState, modifier: Modifier) {
    val undo by app.undo.collectAsState()
    val sending by app.sending.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.notes.collect { note = it; delay(2600); if (note == it) note = null } }
    LaunchedEffect(undo) { if (undo != null) { delay(6000); app.dismissUndo() } }
    LaunchedEffect(sending) { sending?.let { s -> delay((s.until - System.currentTimeMillis()).coerceAtLeast(0)); app.clearSending() } }

    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp)) {
        AnimatedVisibility(sending != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            sending?.let { s -> Bar("Sending “${s.subject.ifBlank { "(no subject)" }}”", "Undo") { app.recall() } }
        }
        AnimatedVisibility(undo != null && sending == null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            undo?.let { t -> Bar("${t.label} · ${countPhrase(t.count, "message")}", "Undo") { app.undoLast() } }
        }
        AnimatedVisibility(note != null && undo == null && sending == null, enter = fadeIn(), exit = fadeOut()) {
            note?.let { Bar(it, null) {} }
        }
    }
}

@Composable
private fun Bar(text: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.RoomCard).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.body.copy(color = Palette.RoomInk), modifier = Modifier.weight(1f).padding(vertical = 8.dp), maxLines = 2)
        if (action != null) TextAction(action, Palette.Window, onAction)
    }
}

/** First run: the sentence from home, before there is mail to count. */
@Composable
private fun Welcome(onAdd: () -> Unit) {
    val m = dev.aarso.mail.app.ui.theme.LocalMetrics.current
    Column(Modifier.fillMaxSize().background(Palette.Field).padding(horizontal = m.gutter).padding(top = 96.dp)) {
        val style = Type.headline(m)
        Text("You've got", style = style.copy(color = Palette.Ink))
        Text("mail.", style = style.copy(color = Palette.Count))
        Text("Every account,", style = style.copy(color = Palette.Ink))
        Text("one sentence.", style = style.copy(color = Palette.Window))
        Spacer(Modifier.padding(top = 32.dp))
        Text("Gmail, iCloud, Outlook, Yahoo, Fastmail, Proton (via Bridge), and any IMAP, POP3 or JMAP server. Summaries are written on this phone when it can.", style = Type.body.copy(color = Palette.InkDim))
        Spacer(Modifier.padding(top = 28.dp))
        dev.aarso.mail.app.ui.components.SolidButton("Add an account", onAdd, background = Palette.Ink, ink = Palette.Field)
    }
}

/** A .eml opened from Files or another app: read-only, nothing touches an account. */
@Composable
private fun ExternalMessage(screen: Screen.External, onBack: () -> Unit) {
    val app = LocalApp.current
    val bridge = LocalBridge.current
    val parsed = remember(screen) { runCatching { MimeParser.parse(screen.raw) }.getOrNull() }
    Page(parsed?.subject?.ifBlank { screen.name } ?: screen.name, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            if (parsed == null) { Text("This file isn't a message.", style = Type.body); return@Column }
            Text(parsed.from?.let { "${it.displayName()} <${it.address}>" }.orEmpty(), style = Type.body.copy(fontWeight = FontWeight.Medium))
            Text("to " + parsed.to.joinToString(", ") { it.address }, style = Type.small.copy(color = Palette.SheetInkDim))
            parsed.date?.let { Text(dev.aarso.mail.app.ui.components.fullWhen(it), style = Type.small.copy(color = Palette.SheetInkDim)) }
            Spacer(Modifier.padding(top = 16.dp))
            val html = parsed.bestHtml()
            if (html != null) HtmlBody(HtmlText.sanitize(html, allowRemote = false), allowNetwork = false, onLink = { bridge.launch(linkIntent(it)) })
            else PlainBody(parsed.bestText().orEmpty())
            for (a in parsed.attachments()) {
                TextAction("${a.fileName ?: "attachment"} · ${bytes(a.body.size.toLong())}", Palette.Navy, {
                    app.scope.launch {
                        val uri = app.graph.files.shareable(a.body, a.fileName ?: "attachment", "eml")
                        bridge.launch(app.graph.files.openIntent(uri, a.contentType.mimeType))
                    }
                })
            }
        }
    }
}

@Composable
private fun Shortcuts(onBack: () -> Unit) {
    val rows = listOf(
        "c" to "New message", "/" to "Find", "g" to "Places", "u, Esc" to "Back", "Ctrl+," to "Settings",
        "j / k" to "Next / previous", "Enter, o" to "Open", "x" to "Select", "Shift+x" to "Select range", "Ctrl+a" to "Select all matching",
        "e" to "Archive", "#, Delete" to "Delete", "s" to "Star", "Shift+i / Shift+u" to "Mark read / unread", "z" to "Undo",
        "r / a / f" to "Reply / reply all / forward",
    )
    Page("Keyboard", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            for ((k, what) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(k, style = Type.mono, modifier = Modifier.widthIn(min = 150.dp))
                Text(what, style = Type.body)
            }
            Text("Gestures: long-press a message to select, keep holding and drag to sweep a range. Pull in from the left for Places, the right for Find, the top for Rules, the bottom for Write. Shake to check for mail.",
                style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 16.dp))
        }
    }
}
