@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.aarso.mail.app.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.EdgeTimelineScrubber
import dev.aarso.cellshell.ScrubberStop
import dev.aarso.mail.app.SwipeAction
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.Monogram
import dev.aarso.mail.app.ui.components.ProvenanceMark
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.countPhrase
import dev.aarso.mail.app.ui.components.shortWhen
import dev.aarso.mail.app.ui.theme.LocalMetrics
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.MessageHeader
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The list every surface shares — home's sheet, folders, searches.
 *
 * Tap opens; long-press selects, and keep holding and drag to sweep a range; while selecting, taps
 * tick and untick. The bar that appears offers "all N matching" — the whole result, not the loaded
 * page — and the rules sheet grows or trims the selection by query. Rows swipe for the two actions
 * the person chose. On a keyboard: j/k, x, shift-x for a range, e, #, s, ctrl-a.
 */
@Composable
fun MessageList(
    controller: ListController,
    tldr: Boolean,
    onOpen: (Long, List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    speakingId: Long? = null,
    focusedId: Long? = null,
    emptyText: String = "Nothing here.",
    bottomPadding: Dp = 24.dp,
    header: (LazyListScope.() -> Unit)? = null,
) {
    val app = LocalApp.current
    val g = app.graph
    val m = LocalMetrics.current
    val items by controller.items.collectAsState()
    val result by controller.result.collectAsState()
    val selection by controller.selection.collectAsState()
    val matching by controller.matching.collectAsState()
    val selectedCount by controller.selectedCount.collectAsState()
    val settings by g.settings.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selecting = !selection.isEmpty
    var cursor by remember { mutableStateOf(-1) }
    var bulk by remember { mutableStateOf(false) }
    var rulesSheet by remember { mutableStateOf(false) }
    var snoozeFor by remember { mutableStateOf<List<Long>?>(null) }

    // Load the next page as the end comes into view.
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= items.size - 25) controller.loadMore() }
    }

    // Keyboard.
    val currentItems by rememberUpdatedState(items)
    DisposableEffect(controller) {
        val handler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            val rows = currentItems
            fun at(i: Int) = rows.getOrNull(i)
            when {
                e.key == Key.J || e.key == Key.DirectionDown -> { cursor = (cursor + 1).coerceAtMost(rows.lastIndex); scope.launch { listState.animateScrollToItem(cursor.coerceAtLeast(0)) }; true }
                e.key == Key.K || e.key == Key.DirectionUp -> { cursor = (cursor - 1).coerceAtLeast(0); scope.launch { listState.animateScrollToItem(cursor) }; true }
                e.key == Key.Enter || e.key == Key.O -> { at(cursor)?.let { onOpen(it.id, rows.map { r -> r.id }) }; true }
                e.key == Key.X && e.isShiftPressed -> { at(cursor)?.let { controller.selectRange(it.id) }; true }
                e.key == Key.X -> { at(cursor)?.let { controller.toggle(it.id) }; true }
                e.key == Key.A && e.isCtrlPressed -> { controller.selectAllMatching(); true }
                e.key == Key.Escape && selecting -> { controller.clear(); true }
                e.key == Key.E -> { targets(controller, rows, cursor) { ids -> app.act { g.actions.archive(ids) } }; true }
                e.key == Key.Pound || (e.key == Key.Three && e.isShiftPressed) || e.key == Key.Delete -> { targets(controller, rows, cursor) { ids -> app.act { g.actions.trash(ids) } }; true }
                e.key == Key.S -> { targets(controller, rows, cursor) { ids -> app.act { g.actions.star(ids, rows.firstOrNull { it.id in ids }?.isFlagged != true) } }; true }
                e.key == Key.I && e.isShiftPressed -> { targets(controller, rows, cursor) { ids -> app.act { g.actions.markRead(ids, true) } }; true }
                e.key == Key.U && e.isShiftPressed -> { targets(controller, rows, cursor) { ids -> app.act { g.actions.markRead(ids, false) } }; true }
                e.key == Key.Z -> { app.undoLast(); true }
                else -> false
            }
        }
        app.keyHandlers.add(handler)
        onDispose { app.keyHandlers.remove(handler) }
    }

    Column(modifier) {
        if (selecting) {
            SelectionBar(
                count = selectedCount,
                total = result?.total,
                allMatching = selection.isQueryBased && selection.included.isEmpty() && selection.excluded.isEmpty(),
                onClose = { controller.clear() },
                onSelectAll = { controller.selectAllMatching() },
                onArchive = { scope.launch { val ids = controller.resolve(); controller.clear(); app.act { g.actions.archive(ids) } } },
                onTrash = { scope.launch { val ids = controller.resolve(); controller.clear(); app.act { g.actions.trash(ids) } } },
                onRead = { scope.launch { val ids = controller.resolve(); val anyUnread = g.store.headers(ids.take(500)).any { !it.isSeen }; controller.clear(); app.act { g.actions.markRead(ids, anyUnread) } } },
                onMore = { bulk = true },
                onRules = { rulesSheet = true },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty() && result != null) {
                Text(emptyText, style = Type.body.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(horizontal = m.gutter, vertical = 28.dp))
            }
            var dragAnchor by remember { mutableStateOf<Long?>(null) }
            var dragBase by remember { mutableStateOf(selection) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().pointerInput(controller) {
                    fun idAt(y: Float): Long? = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { y.toInt() in it.offset until it.offset + it.size }?.key as? Long
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o ->
                            val id = idAt(o.y) ?: return@detectDragGesturesAfterLongPress
                            controller.select(id)
                            dragBase = controller.selection.value
                            dragAnchor = id
                        },
                        onDrag = { change, _ ->
                            val a = dragAnchor ?: return@detectDragGesturesAfterLongPress
                            val y = change.position.y
                            idAt(y)?.let { id -> if (id != a) controller.setRange(a, id, dragBase) }
                            val h = size.height
                            if (y < 64f) scope.launch { listState.scrollBy(-24f) } else if (y > h - 64f) scope.launch { listState.scrollBy(24f) }
                        },
                        onDragEnd = { dragAnchor = null },
                        onDragCancel = { dragAnchor = null },
                    )
                },
            ) {
                header?.invoke(this)
                item(key = "top") { Spacer(Modifier.height(6.dp)) }
                itemsIndexed(items, key = { _, h -> h.id }) { i, h ->
                    MessageRow(
                        h = h,
                        tldr = tldr,
                        selected = controller.isSelected(selection, matching, h.id),
                        selecting = selecting,
                        speaking = h.id == speakingId,
                        focused = h.id == focusedId || i == cursor,
                        swipeRight = settings.swipeRight,
                        swipeLeft = settings.swipeLeft,
                        onClick = { if (selecting) controller.toggle(h.id) else { cursor = i; onOpen(h.id, items.map { it.id }) } },
                        onLongClick = { controller.select(h.id) },
                        onSwipe = { action -> swipe(app, h, action) { snoozeFor = listOf(h.id) } },
                        modifier = Modifier.animateItem(),
                    )
                    Hairline(Modifier.padding(horizontal = m.gutter))
                }
                item(key = "end") {
                    val r = result
                    if (r != null && r.notes.isNotEmpty()) {
                        Text(r.notes.joinToString(" · "), style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(horizontal = m.gutter, vertical = 12.dp))
                    }
                    Spacer(Modifier.height(bottomPadding))
                }
            }
            val stops = remember(items) { scrubStops(items) }
            if (items.size > 40 && stops.size > 1) {
                EdgeTimelineScrubber(
                    stops = stops,
                    itemCount = items.size,
                    currentIndex = listState.firstVisibleItemIndex,
                    onScrubTo = { scope.launch { listState.scrollToItem(it + 1) } },
                    inkColor = Palette.SheetInkDim,
                    accentColor = Palette.Navy,
                    bubbleTextColor = Color.White,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }

    if (bulk) BulkSheet(controller, onDismiss = { bulk = false }, onSnooze = { ids -> bulk = false; snoozeFor = ids })
    if (rulesSheet) SelectionRulesSheet(controller, items, onDismiss = { rulesSheet = false })
    snoozeFor?.let { ids ->
        MomentSheet("Snooze until", onDismiss = { snoozeFor = null }, onPick = { at -> snoozeFor = null; controller.clear(); app.act { g.actions.snooze(ids, at) } })
    }
}

private fun targets(c: ListController, rows: List<MessageHeader>, cursor: Int, run: (List<Long>) -> Unit) {
    val sel = c.selection.value
    if (!sel.isEmpty) {
        val ids = if (sel.query == null) sel.included.toList() else null
        if (ids != null) { c.clear(); run(ids) } else c.graph.scope.launch { val all = c.resolve(); c.clear(); run(all) }
    } else rows.getOrNull(cursor)?.let { run(listOf(it.id)) }
}

private fun swipe(app: dev.aarso.mail.app.ui.AppState, h: MessageHeader, action: SwipeAction, snooze: () -> Unit) {
    val g = app.graph
    val ids = listOf(h.id)
    when (action) {
        SwipeAction.ARCHIVE -> app.act { g.actions.archive(ids) }
        SwipeAction.TRASH -> app.act { g.actions.trash(ids) }
        SwipeAction.READ -> app.act { g.actions.markRead(ids, !h.isSeen) }
        SwipeAction.STAR -> app.act { g.actions.star(ids, !h.isFlagged) }
        SwipeAction.SNOOZE -> snooze()
        SwipeAction.NONE -> Unit
    }
}

private fun swipeLook(a: SwipeAction): Triple<Color, ImageVector, String> = when (a) {
    SwipeAction.ARCHIVE -> Triple(Palette.Navy, HyIcons.archive, "Archive")
    SwipeAction.TRASH -> Triple(Palette.Danger, HyIcons.trash, "Delete")
    SwipeAction.READ -> Triple(Palette.Window, HyIcons.mail, "Read")
    SwipeAction.SNOOZE -> Triple(Palette.FabTop, HyIcons.snooze, "Snooze")
    SwipeAction.STAR -> Triple(Palette.Warning, HyIcons.star, "Star")
    SwipeAction.NONE -> Triple(Color.Transparent, HyIcons.minus, "")
}

@Composable
fun MessageRow(
    h: MessageHeader,
    tldr: Boolean,
    selected: Boolean,
    selecting: Boolean,
    speaking: Boolean,
    focused: Boolean,
    swipeRight: SwipeAction,
    swipeLeft: SwipeAction,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastFired by remember { mutableStateOf(0L) }
    // The state outlives recompositions; read the latest row (read/starred flips) through this.
    val swipeNow by rememberUpdatedState(onSwipe)
    val right by rememberUpdatedState(swipeRight)
    val left by rememberUpdatedState(swipeLeft)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            val now = System.currentTimeMillis()
            if (now - lastFired > 700) {
                when (v) {
                    SwipeToDismissBoxValue.StartToEnd -> { lastFired = now; swipeNow(right) }
                    SwipeToDismissBoxValue.EndToStart -> { lastFired = now; swipeNow(left) }
                    else -> Unit
                }
            }
            false
        },
        positionalThreshold = { it * 0.32f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = !selecting && swipeRight != SwipeAction.NONE,
        enableDismissFromEndToStart = !selecting && swipeLeft != SwipeAction.NONE,
        backgroundContent = {
            val dir = state.dismissDirection
            val action = if (dir == SwipeToDismissBoxValue.StartToEnd) swipeRight else swipeLeft
            val (color, icon, label) = swipeLook(action)
            Row(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (dir == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
            ) {
                HyIcon(icon, null, Color.White, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(label, style = Type.label.copy(color = Color.White))
            }
        },
    ) {
        RowBody(h, tldr, selected, selecting, speaking, focused, onClick, onLongClick)
    }
}

@Composable
private fun RowBody(h: MessageHeader, tldr: Boolean, selected: Boolean, selecting: Boolean, speaking: Boolean, focused: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val m = LocalMetrics.current
    val bg = when { selected -> Palette.Select; speaking -> Palette.Select.copy(alpha = 0.6f); focused -> Palette.SheetRaised; else -> Palette.Sheet }
    val ink = if (h.isSeen) Palette.SheetInkRead else Palette.SheetInk
    Row(
        // combinedClickable, not clickable: a long press must swallow its own release, or the
        // release would arrive as a tap and untick the row that was just selected.
        Modifier.fillMaxWidth().background(bg).combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics { contentDescription = (if (selected) "Selected. " else "") + (h.from?.displayName() ?: "") + ". " + (h.tldr ?: h.subject) }
            .padding(start = m.gutter, end = m.gutter, top = 13.dp, bottom = 13.dp),
        verticalAlignment = if (tldr) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (selecting) {
            HyIcon(if (selected) HyIcons.squareChecked else HyIcons.square, null, Palette.SheetInk, size = 22.dp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(14.dp))
        }
        if (tldr) {
            val line = h.tldr?.takeIf { it.isNotBlank() } ?: listOfNotNull(h.from?.shortName(), h.subject.ifBlank { null }).joinToString(": ")
            Text(line, style = Type.row(m).copy(color = ink), modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            val model = h.tldrSource == ClassificationSource.ON_DEVICE_MODEL || h.tldrSource == ClassificationSource.CLOUD_MODEL
            if (model || h.isFlagged || h.hasAttachments) {
                Column(Modifier.padding(start = 10.dp, top = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (model) ProvenanceMark(onDevice = h.tldrSource == ClassificationSource.ON_DEVICE_MODEL, size = 7.dp)
                    if (h.isFlagged) HyIcon(HyIcons.starFilled, "Starred", Palette.SheetInk, size = 12.dp)
                    if (h.hasAttachments) HyIcon(HyIcons.paperclip, "Attachment", Palette.SheetInkDim, size = 12.dp)
                }
            }
        } else {
            val who = h.from?.displayName() ?: "(no sender)"
            Monogram(who)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(who, style = Type.body.copy(color = ink, fontWeight = if (h.isSeen) FontWeight.Normal else FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (h.isFlagged) { HyIcon(HyIcons.starFilled, "Starred", Palette.SheetInk, size = 13.dp); Spacer(Modifier.width(6.dp)) }
                    if (h.hasAttachments) { HyIcon(HyIcons.paperclip, "Attachment", Palette.SheetInkDim, size = 13.dp); Spacer(Modifier.width(6.dp)) }
                    Text(shortWhen(h.receivedAt), style = Type.small.copy(color = Palette.SheetInkDim))
                }
                Text(h.subject.ifBlank { "(no subject)" }, style = Type.body.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (h.preview.isNotBlank()) Text(h.preview, style = Type.small.copy(color = Palette.SheetInkDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int, total: Int?, allMatching: Boolean,
    onClose: () -> Unit, onSelectAll: () -> Unit, onArchive: () -> Unit, onTrash: () -> Unit, onRead: () -> Unit, onMore: () -> Unit, onRules: () -> Unit,
) {
    val m = LocalMetrics.current
    Column(Modifier.fillMaxWidth().background(Palette.SheetRaised)) {
        Row(Modifier.fillMaxWidth().padding(start = m.gutter - 12.dp, end = m.gutter - 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(HyIcons.close, "Stop selecting", Palette.SheetInk, onClose)
            Text(countPhrase(count, "selected", "selected"), style = Type.heading, modifier = Modifier.weight(1f))
            IconAction(HyIcons.archive, "Archive selected", Palette.SheetInk, onArchive)
            IconAction(HyIcons.trash, "Delete selected", Palette.SheetInk, onTrash)
            IconAction(HyIcons.mail, "Read or unread", Palette.SheetInk, onRead)
            IconAction(HyIcons.more, "More actions", Palette.SheetInk, onMore)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = m.gutter - 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!allMatching && total != null && total > count) TextAction("Select all ${"%,d".format(total)}", Palette.Navy, onSelectAll)
            if (allMatching) Text("All matching", style = Type.label.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(horizontal = 10.dp))
            TextAction("Rules for selection…", Palette.Navy, onRules)
        }
        Hairline()
    }
}

/** Date stops for the scrubber: today, yesterday, this week, then months. */
private fun scrubStops(items: List<MessageHeader>, zone: ZoneId = ZoneId.systemDefault()): List<ScrubberStop> {
    val today = LocalDate.now(zone)
    val out = ArrayList<ScrubberStop>()
    var last = ""
    val month = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
    val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    items.forEachIndexed { i, h ->
        val d = Instant.ofEpochMilli(h.receivedAt).atZone(zone).toLocalDate()
        val label = when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(7)) -> "This week"
            d.year == today.year -> month.format(d)
            else -> monthYear.format(d)
        }
        if (label != last) { out += ScrubberStop(label, i); last = label }
    }
    return out
}
