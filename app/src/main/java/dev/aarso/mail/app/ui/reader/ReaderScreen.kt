@file:OptIn(ExperimentalLayoutApi::class)

package dev.aarso.mail.app.ui.reader

import android.content.Intent
import android.provider.CalendarContract
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.mail.ai.AiResult
import dev.aarso.mail.ai.Extraction
import dev.aarso.mail.app.Drafts
import dev.aarso.mail.app.platform.Unsubscriber
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.Monogram
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.ProvenanceMark
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.bytes
import dev.aarso.mail.app.ui.components.fullWhen
import dev.aarso.mail.app.ui.components.shortWhen
import dev.aarso.mail.app.ui.list.CategorySheet
import dev.aarso.mail.app.ui.list.LabelSheet
import dev.aarso.mail.app.ui.list.MailBottomSheet
import dev.aarso.mail.app.ui.list.MomentSheet
import dev.aarso.mail.app.ui.list.MoveSheet
import dev.aarso.mail.app.ui.theme.LocalMetrics
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.intel.StructuredData
import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.PartInfo
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** A message on its own page. */
@Composable
fun ReaderScreen(messageId: Long, siblings: List<Long>, onBack: () -> Unit) {
    var current by remember { mutableStateOf(messageId) }
    val app = LocalApp.current
    val header by remember(current) { app.graph.store.observeMessages { app.graph.store.header(current) } }.collectAsState(initial = null)
    val h = header
    val idx = siblings.indexOf(current)
    Page(
        title = h?.subject?.ifBlank { "(no subject)" } ?: "",
        onBack = onBack,
        actions = {
            IconAction(HyIcons.prev, "Newer", Palette.Ink, { siblings.getOrNull(idx - 1)?.let { current = it } }, enabled = idx > 0)
            IconAction(HyIcons.next, "Older", Palette.Ink, { siblings.getOrNull(idx + 1)?.let { current = it } }, enabled = idx in 0 until siblings.lastIndex)
        },
    ) {
        if (h != null) ReaderContent(h, onDone = {
            val next = siblings.getOrNull(idx + 1) ?: siblings.getOrNull(idx - 1)
            if (next != null && next != current) current = next else onBack()
        }, onNavigate = { d -> siblings.getOrNull(idx + d)?.let { current = it } })
    }
}

/**
 * The message itself — shared by the full-page reader and the wide-screen second pane.
 * [onDone] runs after an action that takes the message out of the list (archive, delete, move).
 */
@Composable
fun ReaderContent(h: MessageHeader, onDone: () -> Unit, onNavigate: (Int) -> Unit = {}, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val m = LocalMetrics.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val settings by g.settings.collectAsState()

    val body by produceState<MessageBody?>(null, h.id) {
        value = g.store.body(h.id)?.takeIf { it.text != null || it.html != null } ?: runCatching { g.sync.fetchBody(h.id) }.getOrNull()
    }
    val thread by remember(h.threadKey) { g.store.observeMessages { g.store.threadMessages(h.threadKey, h.accountId) } }.collectAsState(initial = emptyList())
    val account by produceState(g.accountsNow().firstOrNull { it.id == h.accountId }, h.accountId) { value = g.store.account(h.accountId) }

    LaunchedEffect(h.id) { if (!h.isSeen) g.actions.markRead(listOf(h.id)) }

    var allowImages by remember(h.id) { mutableStateOf(settings.loadRemoteImages) }
    var showDetails by remember(h.id) { mutableStateOf(false) }
    var summary by remember(h.threadKey) { mutableStateOf<AiResult<String>?>(null) }
    var summarising by remember { mutableStateOf(false) }
    var replies by remember(h.id) { mutableStateOf<AiResult<List<String>>?>(null) }
    var extraction by remember(h.id) { mutableStateOf<AiResult<Extraction>?>(null) }
    var translated by remember(h.id) { mutableStateOf<Pair<String, String>?>(null) }
    var working by remember(h.id) { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var snooze by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }

    fun reply(all: Boolean, text: String = "") = scope.launch {
        val a = g.store.account(h.accountId) ?: return@launch
        val d = Drafts.reply(a, h, body, all)
        app.compose(d.copy(textBody = text + d.textBody))
    }
    fun forward() = scope.launch {
        val a = g.store.account(h.accountId) ?: return@launch
        // Attachments travel with a forward: fetch them now so the outbox can send offline later.
        val atts = h.parts.filter { it.isAttachment }.mapNotNull { p ->
            val bytes = runCatching { g.sync.fetchPart(h.id, p) }.getOrNull() ?: return@mapNotNull null
            val uri = g.files.shareable(bytes, p.fileName ?: "attachment", "forward/${h.id}")
            dev.aarso.mail.model.DraftAttachment(uri.toString(), p.fileName ?: "attachment", p.mimeType, bytes.size.toLong())
        }
        app.compose(Drafts.forward(a, h, body, atts))
    }

    // Keyboard.
    val currentH by rememberUpdatedState(h)
    DisposableEffect(h.id) {
        val handler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            val ids = listOf(currentH.id)
            when (e.key) {
                Key.R -> { reply(false); true }
                Key.A -> { reply(true); true }
                Key.F -> { forward(); true }
                Key.E -> { app.act { g.actions.archive(ids) }; onDone(); true }
                Key.Delete, Key.Pound -> { app.act { g.actions.trash(ids) }; onDone(); true }
                Key.S -> { app.act { g.actions.star(ids, !currentH.isFlagged) }; true }
                Key.J -> { onNavigate(1); true }
                Key.K -> { onNavigate(-1); true }
                Key.U -> if (e.isShiftPressed) { app.act { g.actions.markRead(ids, false) }; onDone(); true } else false
                else -> false
            }
        }
        app.keyHandlers.add(handler)
        onDispose { app.keyHandlers.remove(handler) }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter)) {
            Spacer(Modifier.height(18.dp))
            // ---- who, when ----
            Row(Modifier.fillMaxWidth().clickable { showDetails = !showDetails }, verticalAlignment = Alignment.CenterVertically) {
                Monogram(h.from?.displayName() ?: "?", size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.from?.displayName() ?: "(no sender)", style = Type.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (showDetails) h.from?.address.orEmpty() else "to " + (h.to + h.cc).take(3).joinToString(", ") { it.shortName() } + if (h.to.size + h.cc.size > 3) " +${h.to.size + h.cc.size - 3}" else "",
                        style = Type.small.copy(color = Palette.SheetInkDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(shortWhen(h.receivedAt), style = Type.small.copy(color = Palette.SheetInkDim))
            }
            if (showDetails) {
                Column(Modifier.padding(top = 10.dp)) {
                    Detail("From", h.from?.let { "${it.name ?: ""} <${it.address}>" }.orEmpty())
                    if (h.replyTo.isNotEmpty()) Detail("Reply to", h.replyTo.joinToString(", ") { it.address })
                    if (h.to.isNotEmpty()) Detail("To", h.to.joinToString(", ") { it.displayName() + if (it.name != null) " <${it.address}>" else "" })
                    if (h.cc.isNotEmpty()) Detail("Cc", h.cc.joinToString(", ") { it.address })
                    if (h.bcc.isNotEmpty()) Detail("Bcc", h.bcc.joinToString(", ") { it.address })
                    Detail("Date", fullWhen(h.sentAt.takeIf { it > 0 } ?: h.receivedAt))
                    account?.let { Detail("Account", it.email) }
                    h.listId?.let { Detail("List", it) }
                    if (h.size > 0) Detail("Size", bytes(h.size))
                }
            }
            Spacer(Modifier.height(12.dp))
            // ---- where it is, what it is ----
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetChip(h.category.label, false, { category = true })
                for (l in h.labels.filterNot { it.startsWith("\\") }.take(6)) SheetChip(l, false, { labels = true })
                if (h.isSnoozed) SheetChip("Snoozed until ${shortWhen(h.snoozedUntil)}", false, { scope.launch { g.actions.unsnooze(listOf(h.id)) } })
                if (h.muted) SheetChip("Muted", false, { app.act { g.actions.mute(listOf(h.id), false) } })
            }

            // ---- warnings and quiet facts ----
            val auth = h.authResults?.lowercase().orEmpty()
            if (auth.contains("dmarc=fail") || (auth.contains("spf=fail") && auth.contains("dkim=fail"))) {
                Note("This message failed the sender's own verification. It may not be from ${h.from?.domain ?: "who it says"}.", danger = true)
            }
            if (h.trackerCount > 0) Note("${h.trackerCount} tracking ${if (h.trackerCount == 1) "pixel" else "pixels"} removed")

            // ---- the summary ----
            val model = h.tldrSource == ClassificationSource.ON_DEVICE_MODEL || h.tldrSource == ClassificationSource.CLOUD_MODEL
            h.tldr?.takeIf { it.isNotBlank() && it != h.subject }?.let { t ->
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                    if (model) { ProvenanceMark(h.tldrSource == ClassificationSource.ON_DEVICE_MODEL, modifier = Modifier.padding(top = 7.dp)); Spacer(Modifier.width(10.dp)) }
                    Text(t, style = Type.heading.copy(fontWeight = FontWeight.Normal, fontSize = 19.sp))
                }
            }

            // ---- a code, front and centre ----
            h.otpCode?.let { code ->
                Row(
                    Modifier.padding(top = 16.dp).fillMaxWidth().border(1.dp, Palette.Hairline).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(code, style = Type.title.copy(letterSpacing = 4.sp), modifier = Modifier.weight(1f))
                    TextAction("Copy", Palette.Navy, {
                        scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("Code", code))); app.say("Copied") }
                    })
                }
            }

            // ---- schema.org cards: flights, orders, parcels, events ----
            val cards = remember(body) { body?.structuredData?.let { StructuredData.cards(it) }.orEmpty() }
            for (c in cards.take(3)) {
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().background(Palette.SheetRaised).padding(14.dp)) {
                    Text(c.title, style = Type.body.copy(fontWeight = FontWeight.Medium))
                    for ((k, v) in c.fields.entries.take(6)) Detail(k, v)
                }
            }

            // ---- help from a model ----
            Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (thread.size > 1) SheetChip(if (summarising) "Summarising…" else "Summarise conversation", summary != null, {
                    if (summary == null && !summarising) scope.launch {
                        summarising = true
                        summary = g.ai.threadSummary(thread.map { it to g.store.body(it.id) })
                        if (summary == null) app.say("No model can summarise this right now")
                        summarising = false
                    }
                }, leading = { HyIcon(HyIcons.sparkle, null, if (summary != null) androidx.compose.ui.graphics.Color.White else Palette.SheetInk, size = 14.dp) })
                if (!h.isBulk && !h.isAutomated) SheetChip("Quick replies", replies != null, {
                    if (replies == null) scope.launch {
                        working = "replies"
                        replies = g.ai.replySuggestions(h, body, account?.displayName ?: "")
                        if (replies == null) app.say("No model can suggest replies right now")
                        working = null
                    }
                })
                SheetChip("Dates & tasks", extraction != null, {
                    if (extraction == null) scope.launch {
                        working = "extract"
                        extraction = g.ai.extract(h, body)
                        if (extraction == null) app.say("Nothing found")
                        working = null
                    }
                })
                SheetChip(if (translated != null) "Original" else "Translate", translated != null, {
                    if (translated != null) translated = null else scope.launch {
                        working = "translate"
                        val text = body?.text ?: body?.html?.let { HtmlText.toText(it) } ?: h.preview
                        val local = g.translator.translate(text)
                        translated = if (local != null) "Translated on this phone from ${java.util.Locale.forLanguageTag(local.sourceLanguage).displayLanguage}" to local.text
                        else g.ai.translate(text, java.util.Locale.getDefault().displayLanguage)?.let { "Translated by ${it.engineLabel}" to it.value }
                        if (translated == null) app.say("Already in your language, or no translator is available")
                        working = null
                    }
                })
            }
            if (working != null || summarising) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) { ProvenanceMark(true, working = true) }
            summary?.let { s ->
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().background(Palette.SheetRaised).padding(14.dp)) {
                    ProvenanceMark(s.onDevice, modifier = Modifier.padding(top = 6.dp)); Spacer(Modifier.width(10.dp))
                    Text(s.value, style = Type.body)
                }
            }
            replies?.let { r ->
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProvenanceMark(r.onDevice, modifier = Modifier.padding(top = 10.dp))
                    for (s in r.value) SheetChip(s, false, { reply(false, s) })
                }
            }
            extraction?.let { x ->
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().background(Palette.SheetRaised).padding(14.dp)) {
                    for (ev in x.value.events) Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ev.title, style = Type.body)
                            Text(listOfNotNull(ev.start, ev.location).joinToString(" · "), style = Type.small.copy(color = Palette.SheetInkDim))
                        }
                        TextAction("Add to calendar", Palette.Navy, { bridge.launch(calendarIntent(ev.title, ev.start, ev.end, ev.location, h.subject)) })
                    }
                    for (t in x.value.tasks) Text("□ ${t.text}" + (t.due?.let { " — $it" } ?: ""), style = Type.body, modifier = Modifier.padding(top = 4.dp))
                    if (x.value.events.isEmpty() && x.value.tasks.isEmpty()) Text("No dates or tasks in this one.", style = Type.small.copy(color = Palette.SheetInkDim))
                }
            }

            // ---- the words ----
            Spacer(Modifier.height(18.dp))
            translated?.let { (label, text) ->
                Text(label, style = Type.small.copy(color = Palette.SheetInkDim))
                Spacer(Modifier.height(6.dp))
                PlainBody(text)
            } ?: MessageBodyView(h, body, allowImages, onAllowImages = { allowImages = true })

            // ---- attachments ----
            val parts = (body?.attachments?.takeIf { it.isNotEmpty() } ?: h.parts).filter { it.isAttachment }
            if (parts.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Hairline()
                for (p in parts) AttachmentRow(h, p)
            }

            // ---- the rest of the conversation ----
            val others = thread.filter { it.id != h.id }.sortedByDescending { it.receivedAt }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text("In this conversation", style = Type.caps.copy(color = Palette.SheetInkDim))
                for (o in others) ThreadItem(o)
            }
            Spacer(Modifier.height(96.dp))
        }

        // ---- the action bar ----
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Palette.Sheet).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconAction(HyIcons.reply, "Reply", Palette.SheetInk, { reply(false) })
            IconAction(HyIcons.replyAll, "Reply all", Palette.SheetInk, { reply(true) })
            IconAction(HyIcons.forward, "Forward", Palette.SheetInk, { forward() })
            IconAction(HyIcons.archive, "Archive", Palette.SheetInk, { app.act { g.actions.archive(listOf(h.id)) }; onDone() })
            IconAction(HyIcons.trash, "Delete", Palette.SheetInk, { app.act { g.actions.trash(listOf(h.id)) }; onDone() })
            IconAction(HyIcons.snooze, "Snooze", Palette.SheetInk, { snooze = true })
            IconAction(if (h.isFlagged) HyIcons.starFilled else HyIcons.star, if (h.isFlagged) "Unstar" else "Star", Palette.SheetInk, { app.act { g.actions.star(listOf(h.id), !h.isFlagged) } })
            Box {
                IconAction(HyIcons.more, "More", Palette.SheetInk, { more = true })
                DropdownMenu(more, { more = false }) {
                    @Composable fun item(text: String, run: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = { more = false; run() })
                    item("Mark unread") { app.act { g.actions.markRead(listOf(h.id), false) }; onDone() }
                    item("Move to…") { move = true }
                    item("Labels…") { labels = true }
                    item("Belongs in…") { category = true }
                    item(if (h.pinned) "Unpin" else "Pin to top") { app.act { g.actions.pin(listOf(h.id), !h.pinned) } }
                    item(if (h.muted) "Unmute conversation" else "Mute conversation") { app.act { g.actions.mute(listOf(h.id), !h.muted) } }
                    item("Report spam") { app.act { g.actions.spam(listOf(h.id), true) }; onDone() }
                    if (h.listUnsubscribe != null || body?.html?.let { HtmlText.unsubscribeLink(it) } != null) item("Unsubscribe") {
                        scope.launch {
                            when (val r = Unsubscriber(g).unsubscribe(h)) {
                                Unsubscriber.Result.Done -> app.say("Unsubscribed")
                                is Unsubscriber.Result.OpenPage -> bridge.launch(linkIntent(android.net.Uri.parse(r.url)))
                                Unsubscriber.Result.NotOffered -> body?.html?.let { HtmlText.unsubscribeLink(it) }?.let { bridge.launch(linkIntent(android.net.Uri.parse(it))) }
                            }
                        }
                    }
                    item("Edit as new") { scope.launch { g.store.account(h.accountId)?.let { app.compose(Drafts.editAsNew(it, h, body)) } } }
                    item("Always do this with mail like this…") {
                        app.open(Screen.RuleEditor(MailRule(name = "From ${h.from?.shortName() ?: "sender"}", query = h.from?.let { "from:${it.address}" } ?: "subject:\"${Drafts.baseSubject(h.subject)}\"", actions = listOf(RuleAction.Archive), triggers = setOf(RuleTrigger.NEW_MAIL))))
                    }
                    item("Block sender") {
                        scope.launch {
                            val from = h.from?.address ?: return@launch
                            g.store.saveRule(MailRule(name = "Block $from", query = "from:$from", actions = listOf(RuleAction.Trash), triggers = setOf(RuleTrigger.NEW_MAIL)))
                            app.act { g.actions.trash(listOf(h.id)) }
                            onDone()
                        }
                    }
                    item("Show original") { scope.launch { source = g.sync.fetchRaw(h.id)?.let { String(it, Charsets.UTF_8) } ?: run { app.say("Couldn't fetch the original"); null } } }
                    item("Save as .eml") {
                        scope.launch {
                            val raw = g.sync.fetchRaw(h.id) ?: return@launch app.say("Couldn't fetch the original")
                            val uri = bridge.createDocument(Drafts.baseSubject(h.subject).take(60).ifBlank { "message" } + ".eml", "message/rfc822") ?: return@launch
                            runCatching { g.context.contentResolver.openOutputStream(uri)?.use { it.write(raw) } }.onSuccess { app.say("Saved") }
                        }
                    }
                }
            }
        }
    }

    if (snooze) MomentSheet("Snooze until", { snooze = false }, { at -> snooze = false; app.act { g.actions.snooze(listOf(h.id), at) }; onDone() })
    if (move) MoveSheet(setOf(h.accountId), { move = false }) { box -> move = false; app.act { g.actions.moveTo(listOf(h.id), box.id) }; onDone() }
    if (labels) {
        val known by produceState(emptyList<String>(), h.accountId) { value = g.store.mailboxes(h.accountId).filter { it.role == MailboxRole.NONE }.map { it.name } }
        LabelSheet(h.labels.filterNot { it.startsWith("\\") }.toSet(), known, { labels = false }) { add, remove -> labels = false; app.act { g.actions.label(listOf(h.id), add, remove) } }
    }
    if (category) CategorySheet(h.category, { category = false }) { c -> category = false; app.act { g.actions.recategorize(listOf(h.id), c) } }
    source?.let { raw ->
        MailBottomSheet({ source = null }) {
            Text("Original", style = Type.heading)
            Text(raw.take(200_000), style = Type.mono, modifier = Modifier.padding(top = 8.dp).height(520.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
        }
    }
}

/** HTML (sanitised, images on request) or plain text. */
@Composable
fun MessageBodyView(h: MessageHeader, body: MessageBody?, allowImages: Boolean, onAllowImages: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val raw = body?.html
    if (raw != null) {
        val remote = remember(raw) { HtmlText.remoteImageCount(raw) }
        val html by produceState<String?>(null, raw, allowImages) {
            val inline = HashMap<String, String>()
            for (p in h.parts.filter { it.contentId != null && it.size in 1..1_500_000 }.take(12)) {
                runCatching { g.sync.fetchPart(h.id, p) }.getOrNull()?.let { inline[p.contentId!!.trim('<', '>')] = "data:${p.mimeType};base64," + Base64.encodeToString(it, Base64.NO_WRAP) }
            }
            value = HtmlText.sanitize(raw, allowImages) { cid -> inline[cid] }
        }
        if (remote > 0 && !allowImages) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$remote ${if (remote == 1) "image" else "images"} not loaded", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.weight(1f))
                TextAction("Load", Palette.Navy, onAllowImages)
            }
        }
        html?.let {
            HtmlBody(it, allowNetwork = allowImages, onLink = { uri ->
                if (uri.scheme == "mailto") app.scope.launch {
                    g.store.account(h.accountId)?.let { a -> app.compose(Drafts.fromMailto(a, uri.toString())) }
                } else bridge.launch(linkIntent(uri))
            })
        }
    } else if (body?.text != null) {
        PlainBody(body.text!!)
    } else {
        Text(h.preview.ifBlank { "Fetching…" }, style = Type.body.copy(color = Palette.SheetInkDim))
    }
}

@Composable
private fun AttachmentRow(h: MessageHeader, p: PartInfo) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val name = p.fileName ?: "attachment"
    suspend fun fetch(): ByteArray? = runCatching { g.sync.fetchPart(h.id, p) }.getOrNull().also { if (it == null) app.say("Couldn't download $name") }
    Row(Modifier.fillMaxWidth().clickable {
        scope.launch { busy = true; fetch()?.let { bridge.launch(g.files.openIntent(g.files.shareable(it, name), p.mimeType)) }; busy = false }
    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        HyIcon(if (p.mimeType.startsWith("image/")) HyIcons.image else HyIcons.paperclip, null, Palette.SheetInk, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(p.mimeType.substringAfter('/').uppercase(), bytes(p.size)).joinToString(" · "), style = Type.small.copy(color = Palette.SheetInkDim))
        }
        if (busy) ProvenanceMark(true, working = true)
        IconAction(HyIcons.download, "Save $name to Downloads", Palette.SheetInk, {
            scope.launch { fetch()?.let { g.files.saveToDownloads(it, name, p.mimeType) }?.let { app.say("Saved to Downloads") } }
        })
        IconAction(HyIcons.share, "Share $name", Palette.SheetInk, {
            scope.launch { fetch()?.let { bridge.launch(g.files.shareIntent(g.files.shareable(it, name), p.mimeType)) } }
        })
    }
    Hairline()
}

/** An earlier (or later) message in the thread: collapsed to a line, expands in place. */
@Composable
private fun ThreadItem(o: MessageHeader) {
    val app = LocalApp.current
    var open by remember { mutableStateOf(false) }
    val body by produceState<MessageBody?>(null, open) {
        if (open) value = app.graph.store.body(o.id) ?: runCatching { app.graph.sync.fetchBody(o.id) }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.from?.displayName() ?: "", style = Type.body.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 1)
            Text(shortWhen(o.receivedAt), style = Type.small.copy(color = Palette.SheetInkDim))
        }
        if (!open) Text(o.tldr ?: o.preview, style = Type.small.copy(color = Palette.SheetInkDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        else {
            Spacer(Modifier.height(8.dp))
            val text = body?.text ?: body?.html?.let { HtmlText.toText(it) } ?: o.preview
            PlainBody(HtmlText.newContent(text))
            TextAction("Open", Palette.Navy, { app.open(Screen.Reader(o.id)) })
        }
    }
    Hairline()
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.width(76.dp))
        Text(value, style = Type.small)
    }
}

@Composable
private fun Note(text: String, danger: Boolean = false) {
    Text(text, style = Type.small.copy(color = if (danger) Palette.Danger else Palette.SheetInkDim), modifier = Modifier.padding(top = 10.dp))
}

private fun calendarIntent(title: String, start: String?, end: String?, location: String?, description: String): Intent {
    fun parse(s: String?): Long? = s?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
    return Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.Events.DESCRIPTION, description)
        .apply {
            parse(start)?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            parse(end)?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
