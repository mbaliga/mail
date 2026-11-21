package dev.aarso.mail.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.aarso.mail.app.R
import dev.aarso.mail.app.ui.theme.LocalMetrics
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.discovery.ProviderPreset
import kotlin.math.max

/**
 * The page system, taken from the account-setup and first-run designs:
 *
 * - a black header with a **centered** title, back on the left and any actions on the right;
 * - the white sheet beneath it, with the page's one primary action **pinned at the foot**: a text
 *   link on the left (the secondary choice) and a square navy button on the right;
 * - empty and first-run states as **statements** — large navy sentences whose actions are blue
 *   underlined phrases, like the home headline's controls.
 */

/** Inner margin for forms and statements: wider than list rows, as in the setup design. */
@Composable
fun formGutter(): Dp {
    val w = LocalMetrics.current.widthDp
    return (w * 0.105f).coerceIn(28f, 64f).dp
}

/** Back (start), centered title with optional subtitle, actions (end). The title stays centered whatever the actions. */
@Composable
fun HeaderBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    ink: Color = Palette.Ink,
    dim: Color = Palette.InkDim,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val m = LocalMetrics.current
    Layout(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp),
        content = {
            Box(Modifier.layoutId("back")) { if (onBack != null) IconAction(HyIcons.arrowLeft, "Back", ink, onBack) }
            Column(Modifier.layoutId("title"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title, style = Type.title.copy(color = ink, textAlign = TextAlign.Center, fontSize = if (title.length > 28) 20.sp else 26.sp, lineHeight = if (title.length > 28) 24.sp else 30.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) Text(subtitle, style = Type.small.copy(color = dim, textAlign = TextAlign.Center), maxLines = 1)
            }
            Row(Modifier.layoutId("actions"), verticalAlignment = Alignment.CenterVertically, content = actions)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val back = measurables.first { it.layoutId == "back" }.measure(loose)
        val acts = measurables.first { it.layoutId == "actions" }.measure(loose)
        val side = max(back.width, acts.width)
        val pad = 12.dp.roundToPx()
        val title = measurables.first { it.layoutId == "title" }.measure(loose.copy(maxWidth = (constraints.maxWidth - 2 * side - 2 * pad).coerceAtLeast(0)))
        val h = max(constraints.minHeight, max(title.height, max(back.height, acts.height)))
        layout(constraints.maxWidth, h) {
            back.placeRelative(0, (h - back.height) / 2)
            acts.placeRelative(constraints.maxWidth - acts.width, (h - acts.height) / 2)
            title.placeRelative((constraints.maxWidth - title.width) / 2, (h - title.height) / 2)
        }
    }
}


/** A page: header, the white sheet, and (optionally) the pinned footer. */
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    header: @Composable ColumnScope.() -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
    /** More icons than the header can carry without squeezing the title: a row under it, at the end. */
    toolbar: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Palette.Field).statusBarsPadding()) {
        HeaderBar(title, onBack, subtitle = subtitle, actions = actions)
        if (toolbar != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = toolbar)
        header()
        Spacer(Modifier.height(10.dp))
        MailSheet(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth()) { content() }
            footer?.invoke()
        }
    }
}

/** A dark room (Places, Find, Rules, Write): same header, Hyle's raised field. */
@Composable
fun Room(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(Palette.Room).statusBarsPadding().padding(top = 8.dp)) {
        HeaderBar(title, onBack = null, ink = Palette.RoomInk, dim = Palette.RoomInkDim, actions = actions)
        content()
    }
}

/** The page's pinned action: secondary text link at the start, square navy button at the end. */
@Composable
fun PageFooter(
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = formGutter(), vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (secondary != null) {
            Text(
                secondary, style = Type.body.copy(color = Palette.Navy), maxLines = 2,
                modifier = Modifier.weight(1f).padding(end = 8.dp).clickable(role = Role.Button, onClick = onSecondary).padding(vertical = 14.dp),
            )
        } else Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth(0.42f).widthIn(max = 220.dp).height(58.dp)
                .background(if (enabled) Palette.Navy else Palette.Navy.copy(alpha = 0.3f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onPrimary),
            contentAlignment = Alignment.Center,
        ) { Text(primary, style = Type.body.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp)) }
    }
}

// ---- statements ----

sealed interface Bit {
    data class Plain(val text: String) : Bit
    data class Action(val text: String, val onClick: () -> Unit) : Bit
}

fun plain(text: String): Bit = Bit.Plain(text)
fun action(text: String, onClick: () -> Unit): Bit = Bit.Action(text, onClick)

/** Large sentences with blue underlined actions inside them. */
@Composable
fun Statement(
    bits: List<Bit>,
    modifier: Modifier = Modifier,
    ink: Color = Palette.Navy,
    link: Color = Palette.Window,
    style: TextStyle = Type.headline(LocalMetrics.current),
) {
    val linkStyles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    val text = remember(bits, link) {
        buildAnnotatedString {
            for (b in bits) when (b) {
                is Bit.Plain -> append(b.text)
                is Bit.Action -> withLink(LinkAnnotation.Clickable(b.text, linkStyles) { b.onClick() }) { append(b.text) }
            }
        }
    }
    Text(text, style = style.copy(color = ink), modifier = modifier)
}

/** The first thing seen with no account: white, navy, one blue way forward. */
@Composable
fun FirstRun(onAdd: () -> Unit) {
    val view = LocalView.current
    val activity = LocalContext.current as? android.app.Activity
    // Dark system-bar icons while the page is white.
    DisposableEffect(activity) {
        val w = activity?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        val before = c?.isAppearanceLightStatusBars
        c?.isAppearanceLightStatusBars = true
        onDispose { c?.isAppearanceLightStatusBars = before ?: false }
    }
    val w = LocalMetrics.current.widthDp
    Box(Modifier.fillMaxSize().background(Palette.Sheet)) {
        Statement(
            listOf(plain("You’re not\nset up yet.\n\nGet\nstarted by\n"), action("adding an", onAdd), plain("\n"), action("account.", onAdd)),
            Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = (w * 0.12f).dp, end = (w * 0.06f).dp, bottom = 48.dp),
            style = Type.headline(LocalMetrics.current).copy(fontSize = (w * 0.124f).sp, lineHeight = (w * 0.124f * 1.27f).sp, letterSpacing = (-0.6).sp),
        )
    }
}

/** An empty list on the white sheet, said out loud. */
@Composable
fun EmptyStatement(bits: List<Bit>, modifier: Modifier = Modifier, dark: Boolean = false) {
    val m = LocalMetrics.current
    Statement(
        bits,
        modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = 32.dp),
        ink = if (dark) Palette.RoomInk else Palette.Navy,
        style = Type.title.copy(fontSize = 28.sp, lineHeight = 34.sp),
    )
}

// ---- providers ----

/** A provider as a circle: its logo if one is supplied as `provider_<id>`, else a quiet initial. */
@Composable
fun ProviderBadge(p: ProviderPreset, selected: Boolean, onClick: () -> Unit, size: Dp = 56.dp) {
    val ctx = LocalContext.current
    val res = remember(p.id) { ctx.resources.getIdentifier("provider_${p.id.replace('-', '_')}", "drawable", ctx.packageName) }
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (res != 0 || p.id in LETTER_COLOURS) Color.White else Color(0xFFD9D9D9))
            .border(if (selected) 3.dp else 1.dp, if (selected) Palette.Navy else Color(0xFFD9D9D9), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = p.name },
        contentAlignment = Alignment.Center,
    ) {
        if (res != 0) Image(painterResource(res), null, Modifier.size(size * 0.5f), contentScale = ContentScale.Fit)
        else Text(p.name.first().uppercase(), style = Type.heading.copy(color = LETTER_COLOURS[p.id] ?: Palette.Navy, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

/** Providers whose artwork isn't bundled yet: their initial, in their colour, until a `provider_<id>` drawable is added. */
private val LETTER_COLOURS = mapOf("yahoo" to Color(0xFF6001D2), "fastmail" to Color(0xFF0067B9), "yandex" to Color(0xFFFC3F1D))

/**
 * The providers, drifting sideways on their own in an endless loop. A finger takes over (drag or
 * fling, either way) and the drift resumes a moment after it lets go. With animations turned off
 * in the system settings it stays still and is only moved by hand.
 */
@Composable
fun ProviderStrip(providers: List<ProviderPreset>, selectedId: String?, onPick: (ProviderPreset) -> Unit, modifier: Modifier = Modifier) {
    if (providers.isEmpty()) return
    val n = providers.size
    val ctx = LocalContext.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (Int.MAX_VALUE / 2 / n) * n)
    val drift = remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    if (drift) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        LaunchedEffect(n) {
            val perNanoPx = with(density) { 22.dp.toPx() } / 1_000_000_000f // 22 dp a second
            var last = 0L
            var resumeAt = 0L
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val dt = if (last == 0L) 0L else now - last
                last = now
                if (state.isScrollInProgress) { resumeAt = now + 1_500_000_000L; continue }
                if (now < resumeAt) continue
                state.scrollBy(perNanoPx * dt)
            }
        }
    }
    LazyRow(
        modifier.fillMaxWidth(), state = state,
        contentPadding = PaddingValues(horizontal = formGutter()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(Int.MAX_VALUE, key = { it }) { i ->
            val p = providers[i % n]
            ProviderBadge(p, selected = p.id == selectedId, onClick = { onPick(p) })
        }
    }
}

/** A centered caps label, for onboarding pages ("WORKS WITH"). */
@Composable
fun CenteredLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = Type.label.copy(color = Palette.Navy, textAlign = TextAlign.Center, letterSpacing = 0.5.sp),
        modifier = modifier.fillMaxWidth().padding(vertical = 18.dp),
    )
}

/** The brand splash: the supplied artwork, anchored bottom, on the brand navy. */
@Composable
fun BrandSplash(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Palette.Navy)) {
        Image(
            painterResource(R.drawable.brand_splash), null,
            Modifier.fillMaxWidth().align(Alignment.BottomStart),
            contentScale = ContentScale.FillWidth, alignment = Alignment.BottomStart,
        )
    }
}

/** A search snippet with its matched words in bold; the markers are [dev.aarso.mail.search.MailSearch.HIT_START] / HIT_END. */
@Composable
fun Highlighted(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 2, hit: Color = Palette.SheetInk) {
    val annotated = remember(text, hit) {
        buildAnnotatedString {
            var inHit = false
            val sb = StringBuilder()
            fun flush() {
                if (sb.isEmpty()) return
                if (inHit) withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = hit)) { append(sb.toString()) } else append(sb.toString())
                sb.clear()
            }
            for (c in text) when (c) {
                dev.aarso.mail.search.MailSearch.HIT_START -> { flush(); inHit = true }
                dev.aarso.mail.search.MailSearch.HIT_END -> { flush(); inHit = false }
                else -> sb.append(c)
            }
            flush()
        }
    }
    Text(annotated, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
