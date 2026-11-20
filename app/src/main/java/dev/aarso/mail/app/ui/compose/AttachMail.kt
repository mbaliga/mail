@file:OptIn(FlowPreview::class)

package dev.aarso.mail.app.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.platform.AttachSuggestions
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.components.shortWhen
import dev.aarso.mail.app.ui.list.MailBottomSheet
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.search.SortOrder
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** What to do with the conversations the person picked. */
enum class AttachMode { EML, QUOTE, CONTEXT }

/**
 * "Attach the conversation with Priya about the lease from March": type that, watch the matches
 * appear as you type (the sentence is rewritten into the search language and the rewrite is shown),
 * tick the conversations, and choose what to do with them — attach each message as an `.eml` the
 * recipient can open, quote them into the draft, or hand them to the writing assistant as background.
 */
@Composable
fun AttachMailSheet(onDismiss: () -> Unit, onPick: (List<MessageHeader>, AttachMode, Boolean) -> Unit) {
    MailBottomSheet(onDismiss) { AttachMailContent(onPick) }
}

/** The sheet's contents, separate from the modal frame (so they can be shown, and tested, in place). */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.AttachMailContent(onPick: (List<MessageHeader>, AttachMode, Boolean) -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageHeader>>(emptyList()) }
    var rewritten by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Long>() }
    var wholeThread by remember { mutableStateOf(true) }

    // Live: a result list that follows the typing, newest-first until there are words to rank by.
    LaunchedEffect(Unit) {
        snapshotFlow { query }.debounce(90).distinctUntilChanged().collect { q ->
            searching = true
            val parsed = g.search.parse(q.trim(), natural = true)
            val hasWords = parsed.root != null
            val r = runCatching {
                g.search.search(parsed, SearchScope(collapseThreads = true), if (q.isBlank()) SortOrder.NEWEST else SortOrder.RELEVANCE, limit = 40)
            }.getOrNull()
            results = r?.headers.orEmpty()
            rewritten = if (hasWords && q.isNotBlank()) parsed.chips.joinToString(" ") { it.text } else ""
            searching = false
        }
    }

    run {
        Text("Attach mail", style = Type.heading)
        LineField(
            query, { query = it }, "the conversation with Priya about the lease in March",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {}),
        )
        if (rewritten.isNotBlank() && rewritten != query.trim()) {
            Text("Searching: $rewritten", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetChip("Whole conversation", wholeThread, { wholeThread = true })
            SheetChip("Just this message", !wholeThread, { wholeThread = false })
        }
        Spacer(Modifier.height(6.dp))
        if (results.isEmpty() && !searching) {
            Text(if (query.isBlank()) "Nothing yet." else "No mail matches that.", style = Type.body.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(vertical = 18.dp))
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(results, key = { it.id }) { h ->
                val on = h.id in picked
                Row(
                    Modifier.fillMaxWidth().clickable { if (on) picked.remove(h.id) else picked.add(h.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    HyIcon(if (on) HyIcons.squareChecked else HyIcons.square, null, Palette.SheetInk, size = 22.dp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(h.from?.displayName() ?: "", style = Type.body.copy(color = Palette.SheetInk), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(shortWhen(h.receivedAt), style = Type.small.copy(color = Palette.SheetInkDim))
                        }
                        Text(h.subject.ifBlank { "(no subject)" }, style = Type.small.copy(color = Palette.SheetInk), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        (h.tldr ?: h.preview).takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small.copy(color = Palette.SheetInkDim), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Hairline()
            }
        }
        Spacer(Modifier.height(12.dp))
        val chosen = results.filter { it.id in picked }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SolidButton("Attach .eml", { onPick(chosen, AttachMode.EML, wholeThread) }, enabled = chosen.isNotEmpty())
            TextAction("Quote", Palette.Navy, { onPick(chosen, AttachMode.QUOTE, wholeThread) }, enabled = chosen.isNotEmpty())
            TextAction("Use as context", Palette.Navy, { onPick(chosen, AttachMode.CONTEXT, wholeThread) }, enabled = chosen.isNotEmpty())
        }
        if (chosen.isNotEmpty()) Text(countPhrase(chosen.size, "conversation") + if (wholeThread) " (every message in each)" else "", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 6.dp))
    }
}

/** A thin prompt above the message body: "You just took a screenshot — attach it?" */
@Composable
fun SuggestionRow(
    text: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    acceptLabel: String = "Attach",
    secondLabel: String? = null,
    onSecond: () -> Unit = {},
    thumbnail: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().background(Palette.SheetRaised).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) { thumbnail(); Spacer(Modifier.width(12.dp)) }
        Text(text, style = Type.small.copy(color = Palette.SheetInk), modifier = Modifier.weight(1f), maxLines = 2)
        TextAction(acceptLabel, Palette.Navy, onAccept)
        if (secondLabel != null) TextAction(secondLabel, Palette.Navy, onSecond)
        TextAction("✕", Palette.SheetInkDim, onDismiss)
    }
}

/** A small preview of the screenshot, loaded off the main thread. */
@Composable
fun ScreenshotThumb(shot: AttachSuggestions.Screenshot) {
    val app = LocalApp.current
    val bmp by produceState<ImageBitmap?>(null, shot.uri) {
        value = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.graph.context.contentResolver.loadThumbnail(shot.uri, android.util.Size(160, 160), null).asImageBitmap()
            }
        }.getOrNull()
    }
    Box(Modifier.size(44.dp).background(Palette.Hairline)) {
        bmp?.let { Image(it, null, Modifier.size(44.dp), contentScale = ContentScale.Crop) }
    }
}
