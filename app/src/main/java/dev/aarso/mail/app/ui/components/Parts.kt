package dev.aarso.mail.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.hyle.Provenance
import dev.aarso.hyle.Pulse
import dev.aarso.mail.app.ui.theme.LocalMetrics
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A tappable phrase inside a headline: coloured, underlined, with a drawn ▾ (the grotesk has no
 * such glyph). "on Gmail ▾", "since yesterday ▾".
 */
@Composable
fun PhraseButton(text: String, color: Color, style: TextStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        // The largest size, down to 60%, at which the phrase and its triangle fit on one line —
        // "since you last looked" and long addresses shrink a little instead of losing their end.
        val size = remember(text, style, maxPx) {
            val full = style.fontSize.value
            var s = full
            while (s > full * 0.6f) {
                val w = measurer.measure(text, style.copy(fontSize = s.sp), maxLines = 1, softWrap = false).size.width
                val triangle = with(density) { (s * 0.42f * 1.6f).sp.toPx() }
                if (w + triangle <= maxPx) break
                s -= 1f
            }
            s
        }
        val fitted = style.copy(fontSize = size.sp, lineHeight = style.lineHeight)
        Row(
            Modifier.clickable(role = Role.DropdownList, onClick = onClick).semantics { contentDescription = "$text, change" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = fitted.copy(color = color, textDecoration = TextDecoration.Underline), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            val h = with(density) { size.sp.toDp() } * 0.42f
            Spacer(Modifier.width(h * 0.45f))
            Canvas(Modifier.size(h * 1.15f, h)) {
                val p = Path().apply { moveTo(0f, 0f); lineTo(this@Canvas.size.width, 0f); lineTo(this@Canvas.size.width / 2f, this@Canvas.size.height); close() }
                drawPath(p, color)
            }
        }
    }
}

data class TabItem(val id: String, val label: String, val badge: Int = 0)

/** The bold caps strip under the headline; scrolls to keep the selected tab in view. */
@Composable
fun TabStrip(tabs: List<TabItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, active: Color = Palette.TabActive, idle: Color = Palette.TabIdle) {
    val m = LocalMetrics.current
    val scroll = rememberScrollState()
    val positions = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(selected, positions[selected]) {
        positions[selected]?.let { x -> scroll.animateScrollTo((x - 0).coerceAtLeast(0)) }
    }
    Row(modifier.horizontalScroll(scroll).padding(horizontal = m.gutter), horizontalArrangement = Arrangement.spacedBy(m.gutter * 0.9f)) {
        for (t in tabs) {
            Text(
                t.label.uppercase(Locale.getDefault()),
                style = Type.tab(m).copy(color = if (t.id == selected) active else idle),
                maxLines = 1,
                modifier = Modifier
                    .onGloballyPositioned { positions[t.id] = it.positionInParent().x.toInt() }
                    .clickable(role = Role.Tab) { onSelect(t.id) }
                    .semantics { contentDescription = "${t.label}, ${t.badge} new" },
            )
        }
    }
}

/** The white sheet that holds mail, rounded where it meets the black field. */
@Composable
fun MailSheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Palette.Sheet),
        content = content,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Palette.Hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * Where a model's words came from, by silhouette and hue together (never hue alone): a filled
 * radium disc for this phone, a hollow cyan ring for a cloud model. Breathes while it works.
 */
@Composable
fun ProvenanceMark(onDevice: Boolean, working: Boolean = false, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val p = if (onDevice) Provenance.OnDevice else Provenance.Cloud
    val pulse = Pulse.WATCHED
    val alpha = if (working) {
        val t = rememberInfiniteTransition(label = "breath")
        t.animateFloat(
            pulse.minAlphaPct / 100f, pulse.maxAlphaPct / 100f,
            infiniteRepeatable(tween(pulse.periodMs / 2, easing = LinearEasing), RepeatMode.Reverse), label = "alpha",
        ).value
    } else 1f
    val c = Color(p.hue)
    Canvas(modifier.size(size).alpha(alpha).semantics { contentDescription = if (onDevice) "Written on this phone" else "Written by a cloud model" }) {
        if (onDevice) drawCircle(c) else drawCircle(c, radius = this.size.minDimension / 2f - 1.2.dp.toPx(), style = Stroke(1.6.dp.toPx()))
    }
}

/** The ear: a 64dp gradient disc that reads the digest aloud (and stops it). */
@Composable
fun ListenFab(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(64.dp).clip(CircleShape).background(Palette.fab)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Stop reading" else "Read my mail aloud" },
        contentAlignment = Alignment.Center,
    ) {
        HyIcon(if (playing) HyIcons.stop else HyIcons.ear, null, Color.White, size = if (playing) 22.dp else 30.dp)
    }
}

/** The tl;dr switch from the reference: grey bar over light bar; inverted when showing raw subjects. */
@Composable
fun TldrToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier.size(40.dp).clickable(role = Role.Switch, onClick = onToggle)
            .semantics { contentDescription = if (on) "Showing summaries; show subjects" else "Showing subjects; show summaries" },
    ) {
        val w = size.width
        val bar = size.height * 0.16f
        val top = if (on) Palette.ToggleTop else Palette.ToggleBottom
        val bottom = if (on) Palette.ToggleBottom else Palette.ToggleTop
        drawRect(top, Offset(w * 0.14f, size.height * 0.30f), Size(w * 0.72f, bar))
        drawRect(bottom, Offset(w * 0.14f, size.height * 0.56f), Size(w * (if (on) 0.48f else 0.72f), bar))
    }
}

/** A blocky square with the sender's initial — for list rows when summaries are off. */
@Composable
fun Monogram(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '·'
    val hues = listOf(0xFF1F3354, 0xFF4A5CBA, 0xFF005475, 0xFF2C3870, 0xFF5B4B8A, 0xFF22577A, 0xFF3D405B, 0xFF6D597A)
    val bg = Color(hues[(name.lowercase().hashCode() and 0x7fffffff) % hues.size])
    Box(modifier.size(size).background(bg), contentAlignment = Alignment.Center) {
        Text(letter.toString(), style = Type.label.copy(color = Color.White))
    }
}

/** A chip on the white sheet: outlined, or solid black when on. */
@Composable
fun SheetChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) Palette.SheetInk else Color.Transparent)
            .border(1.dp, if (selected) Palette.SheetInk else Palette.Hairline, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, style = Type.label.copy(color = if (selected) Color.White else Palette.SheetInk), maxLines = 1)
    }
}

/** A chip in a dark room. */
@Composable
fun RoomChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.label.copy(color = if (selected) Color.Black else Palette.RoomInk),
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) Palette.RoomInk else Color.Transparent)
            .border(1.dp, if (selected) Palette.RoomInk else Palette.RoomHairline, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        maxLines = 1,
    )
}

/** A plain text action: black on the sheet, white in a room. */
@Composable
fun TextAction(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(
        text,
        style = Type.label.copy(color = if (enabled) color else color.copy(alpha = 0.35f)),
        modifier = modifier.clip(RoundedCornerShape(2.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** A solid button. */
@Composable
fun SolidButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, background: Color = Palette.SheetInk, ink: Color = Color.White) {
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (enabled) background else background.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.label.copy(color = ink)) }
}

@Composable
fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(2.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { HyIcon(icon, null, if (enabled) tint else tint.copy(alpha = 0.3f), size = 22.dp) }
}

/** A labelled switch row, for settings on either surface. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, dark: Boolean = false, enabled: Boolean = true) {
    val ink = if (dark) Palette.RoomInk else Palette.SheetInk
    val dim = if (dark) Palette.RoomInkDim else Palette.SheetInkDim
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(color = if (enabled) ink else dim))
            if (subtitle != null) Text(subtitle, style = Type.small.copy(color = dim))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = if (dark) Palette.Accent else Palette.SheetInk,
                uncheckedThumbColor = if (dark) Palette.RoomInkDim else Color.White, uncheckedTrackColor = if (dark) Palette.RoomCard else Palette.Hairline,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** One choice among a few, shown as chips under a label. */
@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, dark: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = Type.body.copy(color = if (dark) Palette.RoomInk else Palette.SheetInk))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in options) {
                if (dark) RoomChip(label(o), o == selected, { onSelect(o) }) else SheetChip(label(o), o == selected, { onSelect(o) })
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, dark: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = Type.caps.copy(color = if (dark) Palette.RoomInkDim else Palette.SheetInkDim),
        modifier = modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

/** A single-line text field drawn as an underlined line, the way the headline phrases are. */
@Composable
fun LineField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    singleLine: Boolean = true,
    style: TextStyle = Type.body,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val ink = if (dark) Palette.RoomInk else Palette.SheetInk
    val dim = if (dark) Palette.RoomInkDim else Palette.SheetInkDim
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = dim), maxLines = 1)
                BasicTextField(
                    value = value, onValueChange = onChange, singleLine = singleLine,
                    textStyle = style.copy(color = ink), cursorBrush = SolidColor(if (dark) Palette.Accent else Palette.Navy),
                    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke(this)
        }
        Hairline(color = if (dark) Palette.RoomHairline else Palette.Hairline)
    }
}

/**
 * The frame for pages that open over home (a mailbox, a message, settings): the black field
 * with a large title, and the white sheet below it.
 */
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = LocalMetrics.current
    Column(modifier.fillMaxSize().background(Palette.Field).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = m.gutter - 12.dp, end = m.gutter - 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(HyIcons.arrowLeft, "Back", Palette.Ink, onBack)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(Modifier.padding(horizontal = m.gutter).widthIn(max = 900.dp)) {
            Text(title, style = Type.title.copy(color = Palette.Ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            subtitle?.invoke()
        }
        header()
        Spacer(Modifier.height(14.dp))
        MailSheet(Modifier.fillMaxSize(), content = content)
    }
}

/** A dark room's frame (Places, Find, Rules, Write): Hyle's raised field and a quiet title. */
@Composable
fun Room(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(Palette.Room).statusBarsPadding().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.title.copy(color = Palette.RoomInk), modifier = Modifier.weight(1f))
            actions()
        }
        content()
    }
}

// ---- formatting ----

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val yearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val fullFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault())

fun shortWhen(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (ms <= 0) return ""
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    val d = t.toLocalDate()
    return when {
        d == today -> timeFmt.format(t)
        d.isAfter(today.minusDays(6)) -> dayFmt.format(t)
        d.year == today.year -> dateFmt.format(t)
        else -> yearFmt.format(t)
    }
}

fun fullWhen(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = if (ms <= 0) "" else fullFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    n < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", n / 1048576.0)
    else -> String.format(Locale.getDefault(), "%.2f GB", n / 1073741824.0)
}

fun countPhrase(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "${"%,d".format(n)} $many"

/** "298 new mail" with the count in the count colour. */
fun countLine(n: Int, rest: String) = buildAnnotatedString {
    withStyle(SpanStyle(color = Palette.Count)) { append(if (n == 0) "no" else "%,d".format(n)) }
    append(" ")
    append(rest)
}

val SheetPadding = PaddingValues(horizontal = 24.dp)
