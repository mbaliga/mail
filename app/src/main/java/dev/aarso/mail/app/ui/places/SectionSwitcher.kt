package dev.aarso.mail.app.ui.places

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.theme.Grotesk
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.digest.DigestBuilder
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.SavedSearchKind
import dev.aarso.mail.search.SearchScope
import kotlin.math.absoluteValue

/** Where a section sits in the app's four levels. */
enum class Level(val label: String) {
    HOME("Home"), ALL("All accounts"), VIEWS("Views"), ACCOUNTS("Accounts"), FOLDERS("Folders"),
}

/** A card's colours: top, middle, bottom of its gradient. The reference's coral-to-peach is the home card. */
data class Look(val top: Color, val mid: Color, val bottom: Color)

/**
 * One place to go. [query] (with [accountIds]) is what its card previews and what opening it shows;
 * [drill] makes it a doorway into an account's own folders instead of a list.
 */
data class Section(
    val id: String,
    val title: String,
    val level: Level,
    val look: Look,
    val query: String? = null,
    val accountIds: Set<Long> = emptySet(),
    val mailboxIds: Set<Long> = emptySet(),
    val kicker: String? = null,
    val drill: Long? = null,
    val screen: Screen? = null,
)

private fun look(top: Long, mid: Long, bottom: Long) = Look(Color(top), Color(mid), Color(bottom))

private object Looks {
    val Home = look(0xFFFF5A66, 0xFFFF9BA1, 0xFFEBC8AE)
    val Inbox = look(0xFFFB6A5C, 0xFFF6A08F, 0xFFEFCDB6)
    val Outbox = look(0xFFF2A83B, 0xFFF4C672, 0xFFF3DDB0)
    val Sent = look(0xFF5E8E6F, 0xFF86B096, 0xFFB5D2BE)
    val Drafts = look(0xFF8068E0, 0xFFA796EC, 0xFFCFC6F4)
    val Trash = look(0xFF69788F, 0xFF95A2B6, 0xFFC3CCD8)
    val Spam = look(0xFFE2603A, 0xFFEE8E6B, 0xFFF5C3AE)
    val Starred = look(0xFFE5A91F, 0xFFEFC65A, 0xFFF6E2A5)
    val Snoozed = look(0xFF4B86E8, 0xFF7FAAF0, 0xFFB7D0F7)
    val Archive = look(0xFF2E9C9A, 0xFF69BDBA, 0xFFA8DAD6)
    val AllMail = look(0xFF4F5BC9, 0xFF7E88DD, 0xFFB5BBEE)
    private val views = listOf(
        look(0xFF9B59D0, 0xFFBC8CE0, 0xFFDDC2F0), look(0xFF2F8FBF, 0xFF6BB5DA, 0xFFA9D6EA),
        look(0xFFD4527F, 0xFFE58AA8, 0xFFF1BDCD), look(0xFF5AA05A, 0xFF8CC08C, 0xFFBEE0BE),
        look(0xFFC97A2B, 0xFFE0A463, 0xFFEFCDA0),
    )
    fun view(key: String) = views[(key.hashCode() and 0x7fffffff) % views.size]
    fun account(argb: Long): Look {
        val c = Color(argb.toInt())
        return Look(c, lerp(c, Color.White, 0.35f), lerp(c, Color.White, 0.7f))
    }
    private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
}

/** The folders every account has, as sections for [accountIds] (empty = all accounts). */
private fun folderSections(accountIds: Set<Long>, mailboxes: List<Mailbox>, level: Level, prefix: String, kicker: String?): List<Section> {
    fun s(id: String, title: String, query: String, look: Look) = Section("$prefix$id", title, level, look, query, accountIds, kicker = kicker)
    val own = mailboxes.filter { it.role == MailboxRole.NONE && it.selectable && (accountIds.isEmpty() || it.accountId in accountIds) }.sortedBy { it.name.lowercase() }
    return listOf(
        s("inbox", "Inbox", "in:inbox", Looks.Inbox),
        s("outbox", "Outbox", "outbox:", Looks.Outbox),
        s("sent", "Sent", "in:sent", Looks.Sent),
        s("drafts", "Drafts", "in:drafts", Looks.Drafts),
        s("trash", "Trash", "in:trash", Looks.Trash),
        s("spam", "Spam", "in:spam", Looks.Spam),
        s("starred", "Starred", "is:starred", Looks.Starred),
        s("snoozed", "Snoozed", "is:snoozed", Looks.Snoozed),
        s("archive", "Archive", "in:archive", Looks.Archive),
        s("all", "All mail", "in:anywhere", Looks.AllMail),
    ) + own.map { b -> Section("$prefix:box:${b.id}", b.name, level, Looks.view(b.name), "label:\"${b.name}\"", setOf(b.accountId), kicker = kicker) }
}

/**
 * The left room: a switcher for the app's four levels of "where".
 *
 *  1. **Home** — "You've got N mail".
 *  2. **All accounts** — Inbox, Outbox, Sent, Trash, Spam … across everything.
 *  3. **Views** — your named searches.
 *  4. **Accounts** — each provider and account, which opens onto its *own* folders.
 *
 * The labels sit at the left with the selected one in focus; beside them a stack of cards, one per
 * section, scrolls under your thumb — the card in focus previews what is in that place (its count
 * and its latest lines) in a colour of its own. Tap a card, or the focused label, to go there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlacesRoom(onOpen: (Screen) -> Unit, onHome: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val accounts by g.accounts.collectAsState()
    val boxes by remember { g.store.observeMailboxes() }.collectAsState(initial = emptyList())
    val saved by remember { g.store.observeSavedSearches() }.collectAsState(initial = emptyList())
    var drill by remember { mutableStateOf<Long?>(null) }

    val sections = remember(boxes, saved, accounts, drill) {
        val drilled = drill?.let { id -> accounts.firstOrNull { it.id == id } }
        if (drilled != null) {
            val name = DigestBuilder.accountName(drilled, accounts)
            folderSections(setOf(drilled.id), boxes, Level.FOLDERS, "acct${drilled.id}:", name).map {
                it.copy(look = if (it.id.endsWith(":inbox")) Looks.account(drilled.color) else it.look)
            }
        } else buildList {
            add(Section("home", "Home", Level.HOME, Looks.Home, kicker = "You’ve got", screen = null))
            addAll(folderSections(emptySet(), boxes, Level.ALL, "all:", "All accounts").filter { it.id.removePrefix("all:").let { k -> k in setOf("inbox", "outbox", "sent", "drafts", "trash", "spam", "starred", "snoozed", "archive", "all") } })
            for (v in saved.filter { it.kind == SavedSearchKind.VIEW }) add(Section("view:${v.id}", v.name, Level.VIEWS, Looks.view(v.name), v.query, kicker = "View"))
            for (a in accounts) add(Section("acct:${a.id}", DigestBuilder.accountName(a, accounts), Level.ACCOUNTS, Looks.account(a.color), "in:inbox", setOf(a.id), kicker = a.email, drill = a.id))
        }
    }

    fun open(s: Section) {
        when {
            s.drill != null -> drill = s.drill
            s.level == Level.HOME -> onHome()
            s.query == "outbox:" -> onOpen(Screen.Outbox)
            else -> onOpen(Screen.Mailbox(s.title, s.query.orEmpty(), s.accountIds, s.mailboxIds))
        }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE9EAED), Color(0xFFD9DBDF)))).statusBarsPadding().navigationBarsPadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxHeight
            val cardH = (total * 0.56f).coerceAtMost(520.dp)
            val pad = (total - cardH) / 2
            val pager = rememberPagerState(pageCount = { sections.size })
            val labels = rememberLazyListState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val current = pager.currentPage.coerceIn(0, (sections.size - 1).coerceAtLeast(0))

            // The label column follows the stack: the selected label rests on the vertical centre.
            val rows = remember(sections) {
                buildList {
                    sections.forEachIndexed { i, s -> if (i == 0 || sections[i - 1].level != s.level) add(i to true); add(i to false) }
                }
            }
            LaunchedEffect(current, sections) {
                val idx = rows.indexOfFirst { it.first == current && !it.second }
                if (idx >= 0) labels.animateScrollToItem(idx)
            }

            // ---- the labels ----
            LazyColumn(
                state = labels,
                modifier = Modifier.fillMaxHeight().width(this@BoxWithConstraints.maxWidth * 0.44f),
                contentPadding = PaddingValues(start = 26.dp, top = total / 2 - 16.dp, bottom = total / 2),
                userScrollEnabled = false,
            ) {
                itemsIndexed(rows) { _, (i, isHeader) ->
                    val s = sections[i]
                    if (isHeader) {
                        Text(
                            s.level.label.uppercase(), fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Color(0xFF8A8C92),
                            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                        )
                    } else {
                        val selected = i == current
                        Text(
                            s.title,
                            fontFamily = Grotesk, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            fontSize = if (selected) 22.sp else 17.sp,
                            color = if (selected) Color(0xFF1B1C1F) else Color(0xFF8C8E94),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { if (selected) open(s) else scope.launch { pager.animateScrollToPage(i) } }
                                .padding(vertical = if (selected) 9.dp else 7.dp).semantics { contentDescription = if (selected) "${s.title}, open" else s.title },
                        )
                    }
                }
            }

            // ---- the card stack ----
            val previews = remember { mutableStateMapOf<String, Preview>() }
            val sectionsNow by rememberUpdatedState(sections)
            VerticalPager(
                state = pager,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(this@BoxWithConstraints.maxWidth * 0.62f).offset(x = 0.dp),
                pageSize = PageSize.Fixed(cardH),
                contentPadding = PaddingValues(vertical = pad),
                pageSpacing = 18.dp,
                beyondViewportPageCount = 1,
                key = { sectionsNow.getOrNull(it)?.id ?: it },
            ) { page ->
                val s = sections.getOrNull(page) ?: return@VerticalPager
                val dist = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                // Only the focused card and its neighbours do any work.
                val near = (pager.currentPage - page).absoluteValue <= 1
                LaunchedEffect(s.id, near) { if (near && s.id !in previews) previews[s.id] = loadPreview(g, s) }
                SectionCard(
                    s, previews[s.id],
                    Modifier.fillMaxSize().graphicsLayer { val k = 1f - 0.06f * dist; scaleX = k; scaleY = k; alpha = 1f - 0.18f * dist; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f) }
                        .clickable { if (page == pager.currentPage) open(s) else scope.launch { pager.animateScrollToPage(page) } },
                )
            }

            // ---- the corner: menu to close, or back out of an account ----
            Row(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (drill != null) IconAction(HyIcons.arrowLeft, "Back to all places", Color(0xFF1B1C1F), { drill = null })
                else IconAction(HyIcons.menu, "Close places", Color(0xFF1B1C1F), onHome)
            }
            Column(Modifier.align(Alignment.BottomStart).width(this@BoxWithConstraints.maxWidth * 0.4f).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFDFE0E4), Color(0xFFDFE0E4)))).padding(start = 16.dp, top = 28.dp, bottom = 10.dp)) {
                Text("Settings", fontFamily = Grotesk, fontSize = 14.sp, color = Color(0xFF55575C), modifier = Modifier.clickable { onOpen(Screen.Settings) }.padding(10.dp))
                Text("Add account", fontFamily = Grotesk, fontSize = 14.sp, color = Color(0xFF55575C), modifier = Modifier.clickable { onOpen(Screen.AccountSetup()) }.padding(10.dp))
                Text("Keyboard", fontFamily = Grotesk, fontSize = 14.sp, color = Color(0xFF55575C), modifier = Modifier.clickable { onOpen(Screen.Shortcuts) }.padding(10.dp))
            }
        }
    }
}

/** What a card shows: how much is there, how much is new, and the latest few lines. */
data class Preview(val count: Int, val unread: Int, val lines: List<String>)

private suspend fun loadPreview(g: dev.aarso.mail.app.AppGraph, s: Section): Preview {
    return runCatching {
        when {
            s.level == Level.HOME -> {
                val st = g.settings.value
                val d = g.digest.build(st.digestAccounts, st.digestWindow, st.lastLookAt)
                val q = g.digest.baseQuery(d.windowStart)
                val top = g.search.search(g.search.parse(q, natural = false), SearchScope(accountIds = st.digestAccounts), limit = 3).headers
                Preview(d.total, d.unread, top.map { it.tldr ?: it.subject })
            }
            s.query == "outbox:" -> {
                val items = g.store.outbox().filter { it.draft.accountId in s.accountIds || s.accountIds.isEmpty() }
                Preview(items.size, 0, items.take(3).map { it.draft.subject.ifBlank { "(no subject)" } })
            }
            s.query == "in:drafts" -> {
                val d = g.store.drafts().filter { s.accountIds.isEmpty() || it.accountId in s.accountIds }
                Preview(d.size, 0, d.take(3).map { it.subject.ifBlank { it.textBody.lineSequence().firstOrNull { l -> l.isNotBlank() }.orEmpty() } })
            }
            else -> {
                val scope = SearchScope(accountIds = s.accountIds, mailboxIds = s.mailboxIds)
                val parsed = g.search.parse(s.query.orEmpty(), natural = false)
                val top = g.search.search(parsed, scope, limit = 3).headers
                val unread = g.search.count(g.search.parse(s.query.orEmpty() + " is:unread", natural = false), scope)
                Preview(g.search.count(parsed, scope), unread, top.map { it.tldr ?: it.subject })
            }
        }
    }.getOrDefault(Preview(0, 0, emptyList()))
}

/** A section's card: its gradient, soft arcs, its name and count, and the latest lines at the foot. */
@Composable
private fun SectionCard(s: Section, p: Preview?, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(topStart = 34.dp, bottomStart = 34.dp)).background(Brush.verticalGradient(listOf(s.look.top, s.look.mid, s.look.bottom))),
    ) {
        // Overlapping translucent arcs, like the reference's layered cards.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.16f), radius = size.width * 1.1f, center = Offset(size.width * 0.9f, size.height * 0.55f))
            drawCircle(Color.White.copy(alpha = 0.10f), radius = size.width * 0.9f, center = Offset(size.width * 0.8f, size.height * 1.05f))
        }
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 80.dp, top = 22.dp, bottom = 22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                s.kicker?.let { Text(it.uppercase(), fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.1.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(6.dp))
                Text(s.title, fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 33.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (s.level == Level.HOME) "${"%,d".format(p.count)} new mail" else "${"%,d".format(p.count)}" + if (p.unread > 0) " · ${"%,d".format(p.unread)} unread" else "",
                        fontFamily = Grotesk, fontSize = 15.sp, color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (line in p?.lines.orEmpty()) Text("• $line", fontFamily = Grotesk, fontSize = 13.sp, lineHeight = 17.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
