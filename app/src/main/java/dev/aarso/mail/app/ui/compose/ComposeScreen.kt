@file:OptIn(ExperimentalLayoutApi::class)

package dev.aarso.mail.app.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateListOf
import dev.aarso.mail.ai.MailAi
import dev.aarso.mail.ai.RewriteStyle
import dev.aarso.mail.app.platform.AttachSuggestions
import dev.aarso.mail.mime.Eml
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.PageFooter
import dev.aarso.mail.app.ui.components.ProvenanceMark
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.bytes
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.components.fullWhen
import dev.aarso.mail.app.ui.list.MailBottomSheet
import dev.aarso.mail.app.ui.list.MomentSheet
import dev.aarso.mail.app.ui.theme.LocalMetrics
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.mime.AddressParser
import dev.aarso.mail.model.Contact
import dev.aarso.mail.model.Draft
import dev.aarso.mail.model.EmailAddress
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Writing: every identity on every account, recipients with suggestions from people actually
 * written to, attachments, send now (with the undo window) or later, a nudge if nobody answers,
 * and a model to draft or rewrite — on the phone first.
 */
@OptIn(FlowPreview::class)
@Composable
fun ComposeScreen(initial: Draft, onClose: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val m = LocalMetrics.current
    val scope = rememberCoroutineScope()
    val accounts by g.accounts.collectAsState()
    val settings by g.settings.collectAsState()

    var draft by remember { mutableStateOf(initial) }
    var showCc by remember { mutableStateOf(initial.cc.isNotEmpty() || initial.bcc.isNotEmpty()) }
    var later by remember { mutableStateOf(false) }
    var followUp by remember { mutableStateOf(false) }
    var assist by remember { mutableStateOf(false) }
    var attachMail by remember { mutableStateOf(false) }
    var context by remember { mutableStateOf(MailAi.DraftContext()) }
    var shot by remember { mutableStateOf<AttachSuggestions.Screenshot?>(null) }
    var clip by remember { mutableStateOf<AttachSuggestions.Clip?>(null) }
    val dismissed = remember { mutableStateListOf<String>() }

    // What is worth offering right now. Re-checked whenever the person comes back to this screen,
    // which is exactly when "I just took a screenshot / copied something" has happened.
    fun refreshSuggestions() {
        val sug = g.attachSuggestions
        clip = if (settings.suggestClipboard) sug.recentClip()?.takeIf { it.key !in dismissed } else null
        if (settings.suggestScreenshots && sug.hasPhotoAccess()) scope.launch { shot = sug.latestScreenshot()?.takeIf { "shot:${it.uri}" !in dismissed } } else shot = null
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(settings.suggestClipboard, settings.suggestScreenshots) {
        refreshSuggestions()
        onPauseOrDispose { }
    }

    fun addAttachment(a: dev.aarso.mail.model.DraftAttachment) { draft = draft.copy(attachments = draft.attachments + a) }

    fun takeClip(asContext: Boolean) = scope.launch {
        val c = clip ?: return@launch
        val (text, uri) = g.attachSuggestions.readClip()
        dismissed += c.key; clip = null
        when {
            uri != null -> {
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(c.mime)?.let { ".$it" }.orEmpty()
                val name = (if (c.kind == AttachSuggestions.ClipKind.IMAGE) "Copied image" else "Copied file") + ext
                g.files.stageFrom(uri, name, c.mime)?.let(::addAttachment) ?: app.say("Couldn't read what was copied")
            }
            text != null && asContext -> { context = context.copy(notes = context.notes + text); app.say("Using it as background for the draft") }
            text != null -> addAttachment(g.files.stage(text.toByteArray(), "Copied text.txt", "text/plain"))
        }
    }

    fun pickMail(headers: List<dev.aarso.mail.model.MessageHeader>, mode: AttachMode, whole: Boolean) = scope.launch {
        attachMail = false
        val all = headers.flatMap { h -> if (whole) g.store.threadMessages(h.threadKey, h.accountId).ifEmpty { listOf(h) } else listOf(h) }
            .distinctBy { it.id }.sortedBy { it.receivedAt }
        when (mode) {
            AttachMode.EML -> {
                for (m in all) {
                    // The server's original keeps inline images and attachments; offline, rebuild it from what is stored.
                    val raw = runCatching { kotlinx.coroutines.withTimeout(5_000) { g.sync.fetchRaw(m.id) } }.getOrNull() ?: Eml.build(m, g.store.body(m.id))
                    addAttachment(g.files.stage(raw, Eml.fileName(m), "message/rfc822"))
                }
                app.say("Attached ${countPhrase(all.size, "message")}")
            }
            AttachMode.QUOTE -> {
                draft = draft.copy(textBody = draft.textBody.trimEnd() + "\n\n" + Eml.quote(all.map { it to g.store.body(it.id) }))
                app.say("Quoted ${countPhrase(all.size, "message")}")
            }
            AttachMode.CONTEXT -> {
                context = context.copy(messages = (context.messages + all.map { it to g.store.body(it.id) }).distinctBy { it.first.id })
                app.say("The assistant will use ${countPhrase(all.size, "message")} as background")
            }
        }
    }
    var fromMenu by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }

    // Autosave: a second after typing stops, the draft is on disk (and survives anything).
    LaunchedEffect(Unit) {
        snapshotFlow { draft }.debounce(1000).collect { d ->
            if (!sent && (d.textBody.isNotBlank() || d.subject.isNotBlank() || d.to.isNotEmpty())) {
                val saved = g.store.saveDraft(d.copy(updatedAt = System.currentTimeMillis()))
                if (draft.id == 0L) draft = draft.copy(id = saved.id)
            }
        }
    }

    fun close() {
        scope.launch {
            val d = draft
            if (!sent && (d.textBody.isNotBlank() || d.subject.isNotBlank() || d.to.isNotEmpty())) g.store.saveDraft(d.copy(updatedAt = System.currentTimeMillis()))
        }
        onClose()
    }
    BackHandler { close() }

    fun send(at: Long = 0) {
        if (draft.to.isEmpty() && draft.cc.isEmpty() && draft.bcc.isEmpty()) { app.say("Add someone to send to"); return }
        sent = true
        val d = draft.copy(sendAt = at)
        scope.launch {
            val undo = if (at > 0) 0 else settings.undoSendSeconds
            val id = g.send(d, undo)
            if (at == 0L) app.sent(id, d.subject, undo) else app.say("Scheduled for ${fullWhen(at)}")
        }
        onClose()
    }

    val identities = remember(accounts) {
        accounts.flatMap { a -> (a.identities.ifEmpty { listOf(a.primaryIdentity) }).map { a to it } }
    }

    Page(
        title = draft.subject.ifBlank { "New message" },
        onBack = { close() },
        actions = {
            IconAction(HyIcons.trash, "Discard", Palette.Ink, {
                sent = true
                scope.launch { if (draft.id > 0) g.store.deleteDraft(draft.id) }
                onClose()
            })
        },
        toolbar = {
            IconAction(HyIcons.paperclip, "Attach", Palette.Ink, {
                scope.launch {
                    val uris = bridge.pickDocuments(arrayOf("*/*"), multiple = true)
                    if (uris.isNotEmpty()) draft = draft.copy(attachments = draft.attachments + uris.map { g.files.describe(it) })
                }
            })
            IconAction(HyIcons.mail, "Attach mail", Palette.Ink, { attachMail = true })
            IconAction(HyIcons.sparkle, "Help me write", Palette.Ink, { assist = true })
        },
        footer = { PageFooter(primary = "Send", onPrimary = { send() }, secondary = "Send later", onSecondary = { later = true }) },
    ) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter)) {
            Spacer(Modifier.height(8.dp))
            // ---- from ----
            Box {
                Row(Modifier.fillMaxWidth().clickable(enabled = identities.size > 1) { fromMenu = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("From", style = Type.body.copy(color = Palette.SheetInkDim), modifier = Modifier.width(56.dp))
                    Text(draft.from.let { if (it.name.isNullOrBlank()) it.address else "${it.name} <${it.address}>" }, style = Type.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (identities.size > 1) HyIcon(HyIcons.chevronDown, null, Palette.SheetInkDim, size = 16.dp)
                }
                DropdownMenu(fromMenu, { fromMenu = false }) {
                    for ((a, id) in identities) DropdownMenuItem(
                        text = { Column { Text(id.address.displayName()); Text(id.address.address, style = Type.small.copy(color = Palette.RoomInkDim)) } },
                        onClick = { fromMenu = false; draft = draft.copy(accountId = a.id, from = id.address) },
                    )
                }
            }
            Hairline()
            RecipientField("To", draft.to, { draft = draft.copy(to = it) }, trailing = { if (!showCc) TextAction("Cc Bcc", Palette.SheetInkDim, { showCc = true }) })
            if (showCc) {
                RecipientField("Cc", draft.cc, { draft = draft.copy(cc = it) })
                RecipientField("Bcc", draft.bcc, { draft = draft.copy(bcc = it) })
            }
            LineField(draft.subject, { draft = draft.copy(subject = it) }, "Subject", style = Type.body)

            // ---- offers: one at a time, newest thing first ----
            val s = shot
            val c = clip
            when {
                s != null -> SuggestionRow(
                    "You just took a screenshot. Attach it?",
                    onAccept = { scope.launch { g.files.stageFrom(s.uri, s.name, s.mime)?.let(::addAttachment); dismissed += "shot:${s.uri}"; shot = null } },
                    onDismiss = { dismissed += "shot:${s.uri}"; shot = null },
                    thumbnail = { ScreenshotThumb(s) },
                )
                c != null && c.kind == AttachSuggestions.ClipKind.TEXT -> SuggestionRow(
                    "You copied some text.",
                    acceptLabel = "Attach", onAccept = { takeClip(false) },
                    secondLabel = "Use as context", onSecond = { takeClip(true) },
                    onDismiss = { dismissed += c.key; clip = null },
                )
                c != null -> SuggestionRow("You copied ${c.label}. Attach it?", onAccept = { takeClip(false) }, onDismiss = { dismissed += c.key; clip = null })
                !settings.suggestScreenshots && !settings.screenshotPromptDismissed -> SuggestionRow(
                    "Offer your latest screenshot here when you've just taken one? Needs access to your photos.",
                    acceptLabel = "Turn on",
                    onAccept = {
                        scope.launch {
                            val ok = bridge.requestPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                            g.updateSettings { it.copy(suggestScreenshots = ok, screenshotPromptDismissed = true) }
                        }
                    },
                    onDismiss = { g.updateSettings { it.copy(screenshotPromptDismissed = true) } },
                )
            }
            if (!context.isEmpty) {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetChip(
                        "Background for the assistant: " + listOfNotNull(
                            context.messages.size.takeIf { it > 0 }?.let { countPhrase(it, "message") },
                            context.notes.size.takeIf { it > 0 }?.let { countPhrase(it, "note") },
                        ).joinToString(" and "),
                        true, { context = MailAi.DraftContext() },
                        leading = { HyIcon(HyIcons.close, "Clear", androidx.compose.ui.graphics.Color.White, size = 12.dp) },
                    )
                }
            }
            if (draft.attachments.isNotEmpty()) {
                FlowRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in draft.attachments) SheetChip("${a.fileName} · ${bytes(a.size)}", false, { draft = draft.copy(attachments = draft.attachments - a) }, leading = { HyIcon(HyIcons.close, "Remove", Palette.SheetInk, size = 12.dp) })
                }
                val total = draft.attachments.sumOf { it.size }
                if (total > 20L * 1024 * 1024) Text("${bytes(total)} — many servers refuse messages over 20–25 MB.", style = Type.small.copy(color = Palette.Warning))
            }
            if (draft.sendAt > 0 || draft.followUpAt > 0 || draft.requestReadReceipt) {
                Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.followUpAt > 0) SheetChip("Nudge me ${fullWhen(draft.followUpAt)} if no reply", true, { draft = draft.copy(followUpAt = 0) })
                    if (draft.requestReadReceipt) SheetChip("Read receipt requested", true, { draft = draft.copy(requestReadReceipt = false) })
                }
            }

            // ---- the words ----
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(vertical = 14.dp)) {
                if (draft.textBody.isEmpty()) Text("Write…", style = Type.body.copy(color = Palette.SheetInkDim))
                BasicTextField(
                    value = draft.textBody,
                    onValueChange = { draft = draft.copy(textBody = it) },
                    textStyle = Type.body.copy(color = Palette.SheetInk),
                    cursorBrush = SolidColor(Palette.Navy),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextAction("Nudge me if no reply…", Palette.Navy, { followUp = true })
                TextAction(if (draft.requestReadReceipt) "No read receipt" else "Ask for read receipt", Palette.Navy, { draft = draft.copy(requestReadReceipt = !draft.requestReadReceipt) })
            }
        }
    }

    if (later) MomentSheet("Send at", { later = false }, { at -> later = false; send(at) })
    if (followUp) MomentSheet("Nudge me if there's no reply by", { followUp = false }, { at -> followUp = false; draft = draft.copy(followUpAt = at) })
    if (assist) AssistSheet(draft, context, onDismiss = { assist = false }, onText = { draft = draft.copy(textBody = it) })
    if (attachMail) AttachMailSheet(onDismiss = { attachMail = false }, onPick = { hs, mode, whole -> pickMail(hs, mode, whole) })
}

/** Addresses as chips; typing shows people you have actually written to first. */
@Composable
private fun RecipientField(label: String, recipients: List<EmailAddress>, onChange: (List<EmailAddress>) -> Unit, trailing: @Composable () -> Unit = {}) {
    val app = LocalApp.current
    var text by remember { mutableStateOf("") }
    val suggestions by produceState(emptyList<Contact>(), text) {
        value = if (text.length >= 1) app.graph.store.suggestContacts(text.trim(), 6) else emptyList()
    }
    fun commit(raw: String) {
        val parsed = AddressParser.parseList(raw).filter { it.address.contains('@') }
        if (parsed.isNotEmpty()) { onChange((recipients + parsed).distinctBy { it.normalized }); text = "" }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.body.copy(color = Palette.SheetInkDim), modifier = Modifier.width(56.dp))
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in recipients) SheetChip(a.shortName(), false, { onChange(recipients - a) })
                Box(Modifier.weight(1f).widthIn(min = 120.dp).padding(vertical = 6.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { t -> if (t.endsWith(",") || t.endsWith(";") || t.endsWith(" ") && t.trim().contains('@')) commit(t.trimEnd(',', ';', ' ')) else text = t },
                        singleLine = true,
                        textStyle = Type.body.copy(color = Palette.SheetInk),
                        cursorBrush = SolidColor(Palette.Navy),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { commit(text) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            trailing()
        }
        for (c in suggestions) {
            Row(Modifier.fillMaxWidth().clickable { onChange((recipients + EmailAddress(c.address, c.name)).distinctBy { it.normalized }); text = "" }.padding(start = 56.dp, top = 6.dp, bottom = 6.dp)) {
                Text(c.name ?: c.address, style = Type.body, modifier = Modifier.weight(1f), maxLines = 1)
                if (c.name != null) Text(c.address, style = Type.small.copy(color = Palette.SheetInkDim), maxLines = 1)
            }
        }
        Hairline()
    }
}

/** Draft from a sentence, or rewrite what is there — streamed, with where it ran shown. */
@Composable
private fun AssistSheet(draft: Draft, context: MailAi.DraftContext, onDismiss: () -> Unit, onText: (String) -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    var intent by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var onDevice by remember { mutableStateOf<Boolean?>(null) }
    val signatureAndQuote = remember(draft.textBody) {
        val i = draft.textBody.indexOf("\n-- \n").takeIf { it >= 0 } ?: draft.textBody.indexOf("\nOn ").takeIf { it >= 0 } ?: draft.textBody.length
        draft.textBody.substring(0, i) to draft.textBody.substring(i)
    }
    MailBottomSheet(onDismiss) {
        Text("Help me write", style = Type.heading)
        LineField(intent, { intent = it }, "e.g. say yes, but ask to move it to Thursday", singleLine = false)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SolidButton(if (running) "Writing…" else "Write it", {
                if (running || intent.isBlank()) return@SolidButton
                running = true; out = ""
                scope.launch {
                    val replyTo = draft.sourceMessageId?.let { id -> g.store.header(id)?.let { it to g.store.body(id) } }
                    val name = g.accountsNow().firstOrNull { it.id == draft.accountId }?.displayName ?: draft.from.name ?: ""
                    runCatching {
                        g.ai.draftStream(intent, replyTo, name, context = context, onEngine = { onDevice = it.onDevice }).collect { out += it }
                    }
                    if (out.isBlank()) app.say("No model can write right now — check Settings → Intelligence")
                    running = false
                }
            }, enabled = intent.isNotBlank())
        }
        Spacer(Modifier.height(14.dp))
        Text("Or rewrite what you've written", style = Type.small.copy(color = Palette.SheetInkDim))
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in RewriteStyle.entries) SheetChip(
                when (s) { RewriteStyle.SHORTER -> "Shorter"; RewriteStyle.FRIENDLIER -> "Friendlier"; RewriteStyle.FORMAL -> "More formal"; RewriteStyle.CLEARER -> "Clearer"; RewriteStyle.LONGER -> "Longer"; RewriteStyle.PROOFREAD -> "Proofread" },
                false,
                {
                    val mine = signatureAndQuote.first
                    if (mine.isBlank() || running) return@SheetChip
                    running = true
                    scope.launch {
                        val r = g.ai.rewrite(mine, s)
                        if (r != null) { out = r.value; onDevice = r.onDevice } else app.say("No model can rewrite right now")
                        running = false
                    }
                },
            )
        }
        if (out.isNotBlank() || running) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                onDevice?.let { ProvenanceMark(it, working = running, modifier = Modifier.padding(top = 7.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(out.ifBlank { "…" }, style = Type.body, modifier = Modifier.weight(1f).heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolidButton("Use this", { onText(out.trim() + signatureAndQuote.second); onDismiss() }, enabled = !running && out.isNotBlank())
                TextAction("Insert below", Palette.Navy, { onText(signatureAndQuote.first.trimEnd() + "\n\n" + out.trim() + signatureAndQuote.second); onDismiss() }, enabled = !running && out.isNotBlank())
            }
        }
    }
}
