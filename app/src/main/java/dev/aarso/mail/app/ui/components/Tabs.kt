package dev.aarso.mail.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.ui.Tab
import dev.aarso.mail.app.ui.theme.Type

/** A tab leaning to the right, like a file-folder index: the top edge is shorter than the bottom. */
private val SlantedTab = GenericShape { size, _ ->
    val lean = size.height * 0.45f
    moveTo(0f, 0f); lineTo(size.width - lean, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
}

/**
 * The open tabs along the foot of the screen: Inbox, then whatever is open — conversations, drafts,
 * the outbox. Tap to switch; the current tab's × (or a long press on any tab) closes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabBar(tabs: List<Tab>, active: Tab, onSelect: (Tab) -> Unit, onClose: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(active, tabs.size) { tabs.indexOf(active).takeIf { it >= 0 }?.let { state.animateScrollToItem(it) } }
    LazyRow(
        modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(top = 6.dp),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(tabs, key = { _, t -> t.id }) { _, t ->
            val on = t === active
            Box(
                Modifier.height(40.dp).widthIn(min = 88.dp, max = 168.dp).clip(SlantedTab)
                    .background(if (on) Brush.horizontalGradient(listOf(Color.White, Color(0xFFD9DADF))) else Brush.horizontalGradient(listOf(Color(0xFF454545), Color(0xFF383838))))
                    .combinedClickable(
                        role = Role.Tab,
                        onClick = { onSelect(t) },
                        onLongClick = { if (t.closable) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClose(t) } },
                    )
                    .semantics { contentDescription = t.title },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.padding(start = 14.dp, end = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.title, style = Type.body.copy(color = if (on) Color(0xFF111111) else Color.White),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (on && t.closable) {
                        Text(
                            "×", style = Type.heading.copy(color = Color(0xFF111111)),
                            modifier = Modifier.padding(start = 8.dp).combinedClickable(onClick = { onClose(t) }),
                        )
                    }
                }
            }
        }
    }
}
