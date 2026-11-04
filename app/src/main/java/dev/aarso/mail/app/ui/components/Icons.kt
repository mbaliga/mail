package dev.aarso.mail.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hyle's icon language, ported: sharp-edged and blocky on a 24-unit grid, miter joins, flat caps,
 * 2-unit strokes, no rounding. The first block is Hyle's own set; the rest are the mail glyphs it
 * lacks, drawn to the same rules so the two are indistinguishable.
 */
object HyIcons {
    private fun glyph(name: String, lines: List<String> = emptyList(), fills: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in lines) addPath(
                pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter, strokeLineMiter = 10f,
            )
            for (d in fills) addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        }.build()

    // ---- Hyle ----
    val play = glyph("play", fills = listOf("M6 4 L20 12 L6 20 Z"))
    val pause = glyph("pause", fills = listOf("M6 4h4v16H6z M14 4h4v16h-4z"))
    val stop = glyph("stop", fills = listOf("M5 5h14v14H5z"))
    val next = glyph("next", fills = listOf("M5 4 L15 12 L5 20 Z M17 4h3v16h-3z"))
    val prev = glyph("prev", fills = listOf("M19 4 L9 12 L19 20 Z M4 4h3v16H4z"))
    val check = glyph("check", lines = listOf("M4 12 L10 18 L20 6"))
    val close = glyph("close", lines = listOf("M6 6 L18 18 M18 6 L6 18"))
    val plus = glyph("plus", lines = listOf("M12 4 V20 M4 12 H20"))
    val minus = glyph("minus", lines = listOf("M4 12 H20"))
    val chevronDown = glyph("chevronDown", lines = listOf("M4 8 L12 16 L20 8"))
    val chevronRight = glyph("chevronRight", lines = listOf("M8 4 L16 12 L8 20"))
    val arrowRight = glyph("arrowRight", lines = listOf("M3 12 H20 M14 6 L20 12 L14 18"))
    val menu = glyph("menu", lines = listOf("M4 6 H20 M4 12 H20 M4 18 H20"))
    val layers = glyph("layers", lines = listOf("M12 3 L21 8 L12 13 L3 8 Z", "M3 13 L12 18 L21 13"))
    val bolt = glyph("bolt", fills = listOf("M13 2 L4 14 H11 L10 22 L20 9 H13 Z"))
    val folder = glyph("folder", lines = listOf("M3 6h6l2 2h10v12H3z"))
    val lock = glyph("lock", lines = listOf("M6 11h12v9H6z", "M9 11 V8 a3 3 0 0 1 6 0 v3"))
    val search = glyph("search", lines = listOf("M4 4h10v10H4z", "M14 14 L20 20"))
    val settings = glyph("settings", lines = listOf("M4 7h9 M17 7h3 M4 12h3 M11 12h9 M4 17h7 M15 17h5"), fills = listOf("M13 5h2v4h-2z M5 10h2v4H5z M11 15h2v4h-2z"))
    val copy = glyph("copy", lines = listOf("M9 9h11v11H9z", "M4 4h11v2 M4 4v11h2"))

    // ---- mail ----
    val arrowLeft = glyph("arrowLeft", lines = listOf("M21 12 H4 M10 6 L4 12 L10 18"))
    val mail = glyph("mail", lines = listOf("M3 5h18v14H3z", "M3 5 L12 13 L21 5"))
    val inbox = glyph("inbox", lines = listOf("M3 4h18v16H3z", "M3 13h5l1 3h6l1-3h5"))
    val archive = glyph("archive", lines = listOf("M3 4h18v5H3z", "M5 9v11h14V9", "M9 13h6"))
    val trash = glyph("trash", lines = listOf("M4 6h16 M9 6V3h6v3", "M6 6l1 15h10l1-15", "M10 10v7 M14 10v7"))
    val star = glyph("star", lines = listOf("M12 3 L14.6 9 L21 9.5 L16 13.8 L17.6 20.5 L12 17 L6.4 20.5 L8 13.8 L3 9.5 L9.4 9 Z"))
    val starFilled = glyph("starFilled", fills = listOf("M12 3 L14.6 9 L21 9.5 L16 13.8 L17.6 20.5 L12 17 L6.4 20.5 L8 13.8 L3 9.5 L9.4 9 Z"))
    val reply = glyph("reply", lines = listOf("M10 5 L4 11 L10 17", "M4 11 H14 a6 6 0 0 1 6 6 v2"))
    val replyAll = glyph("replyAll", lines = listOf("M9 5 L3 11 L9 17", "M14 5 L8 11 L14 17", "M8 11 H15 a5 5 0 0 1 5 5 v3"))
    val forward = glyph("forward", lines = listOf("M14 5 L20 11 L14 17", "M20 11 H10 a6 6 0 0 0 -6 6 v2"))
    val send = glyph("send", fills = listOf("M3 3 L22 12 L3 21 L6 12 Z"))
    val clock = glyph("clock", lines = listOf("M12 3 a9 9 0 1 0 0.01 0 Z", "M12 7 V12 L16 14"))
    val snooze = glyph("snooze", lines = listOf("M12 4 a8 8 0 1 0 0.01 0 Z", "M9 9h6l-6 6h6", "M3 4 L6 2 M21 4 L18 2"))
    val tag = glyph("tag", lines = listOf("M3 3h9l9 9-9 9-9-9z"), fills = listOf("M6.5 6.5h3v3h-3z"))
    val pencil = glyph("pencil", lines = listOf("M4 20 L5 15 L16 4 L20 8 L9 19 Z", "M13 7 L17 11"))
    val paperclip = glyph("paperclip", lines = listOf("M16 7 V17 a4 4 0 0 1 -8 0 V6 a2.5 2.5 0 0 1 5 0 V16 a1 1 0 0 1 -2 0 V8"))
    val shield = glyph("shield", lines = listOf("M12 3 L20 6 V12 C20 16 16.5 19.5 12 21 C7.5 19.5 4 16 4 12 V6 Z", "M12 8 V13"), fills = listOf("M11 15h2v2h-2z"))
    val pin = glyph("pin", lines = listOf("M9 3h6v6l3 4H6l3-4z", "M12 13 V21"))
    val bellOff = glyph("bellOff", lines = listOf("M6 17 V10 a6 6 0 0 1 10.5 -4 M18 10 V17 H8", "M10 20h4", "M3 3 L21 21"))
    val bell = glyph("bell", lines = listOf("M6 17 V10 a6 6 0 0 1 12 0 V17 Z M4 17h16", "M10 20h4"))
    val dot = glyph("dot", fills = listOf("M8 8h8v8H8z"))
    val square = glyph("square", lines = listOf("M5 5h14v14H5z"))
    val squareChecked = glyph("squareChecked", lines = listOf("M5 5h14v14H5z", "M8 12 L11 15 L16 9"))
    val squarePartial = glyph("squarePartial", lines = listOf("M5 5h14v14H5z", "M8 12h8"))
    val more = glyph("more", fills = listOf("M4 10h4v4H4z M10 10h4v4h-4z M16 10h4v4h-4z"))
    val filter = glyph("filter", lines = listOf("M3 5h18 L14 13 V20 L10 18 V13 Z"))
    val sort = glyph("sort", lines = listOf("M7 4 V20 M3 16 L7 20 L11 16", "M17 20 V4 M13 8 L17 4 L21 8"))
    val undo = glyph("undo", lines = listOf("M8 4 L3 9 L8 14", "M3 9 H15 a5 5 0 0 1 0 10 H9"))
    val refresh = glyph("refresh", lines = listOf("M20 12 a8 8 0 1 1 -2.3 -5.7", "M20 3 V8 H15"))
    val sparkle = glyph("sparkle", fills = listOf("M12 2 L14 10 L22 12 L14 14 L12 22 L10 14 L2 12 L10 10 Z"))
    val calendar = glyph("calendar", lines = listOf("M3 5h18v16H3z", "M3 10h18 M8 3v4 M16 3v4"))
    val person = glyph("person", lines = listOf("M8 7 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0", "M4 21 V18 a4 4 0 0 1 4 -4 h8 a4 4 0 0 1 4 4 v3"))
    val people = glyph("people", lines = listOf("M5 8 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0", "M2 20 V17 a3 3 0 0 1 3 -3 h6 a3 3 0 0 1 3 3 v3", "M15 5 a3 3 0 0 1 0 6 M17 14 a3 3 0 0 1 3 3 v3"))
    val globe = glyph("globe", lines = listOf("M12 3 a9 9 0 1 0 0.01 0 Z", "M3 12h18", "M12 3 C8 7 8 17 12 21 C16 17 16 7 12 3"))
    val eye = glyph("eye", lines = listOf("M2 12 C5 6 19 6 22 12 C19 18 5 18 2 12 Z"), fills = listOf("M10 10h4v4h-4z"))
    val image = glyph("image", lines = listOf("M3 4h18v16H3z", "M3 17 L9 11 L14 16 L17 13 L21 17"), fills = listOf("M15 7h3v3h-3z"))
    val download = glyph("download", lines = listOf("M12 3 V15 M6 9 L12 15 L18 9", "M4 20h16"))
    val share = glyph("share", lines = listOf("M12 15 V3 M7 8 L12 3 L17 8", "M4 13 V21 H20 V13"))
    val external = glyph("external", lines = listOf("M14 4h6v6 M20 4 L11 13", "M18 14 V20 H4 V6 H10"))
    val code = glyph("code", lines = listOf("M8 6 L2 12 L8 18 M16 6 L22 12 L16 18"))
    val unsubscribe = glyph("unsubscribe", lines = listOf("M3 5h18v14H3z", "M3 5 L12 13 L21 5", "M15 15 L21 21 M21 15 L15 21"))
    val outbox = glyph("outbox", lines = listOf("M3 10v10h18V10", "M12 15 V3 M7 8 L12 3 L17 8"))
    val drafts = glyph("drafts", lines = listOf("M4 4h11l5 5v11H4z", "M15 4v5h5", "M8 14h8 M8 17h5"))
    val sent = glyph("sent", lines = listOf("M3 3 L22 12 L3 21 L6 12 Z", "M6 12 H13"))
    val all = glyph("all", lines = listOf("M3 6h18v14H3z", "M5 3h14"))
    val list = glyph("list", lines = listOf("M9 6 H21 M9 12 H21 M9 18 H21"), fills = listOf("M3 5h3v3H3z M3 11h3v3H3z M3 17h3v3H3z"))
    val thread = glyph("thread", lines = listOf("M4 4h12v9H8l-4 4z", "M8 16v1h8l4 4V8h-4"))
    val key = glyph("key", lines = listOf("M3 15 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0", "M10 12 L20 2 M16 6 L19 9 M14 8 L16 10"))
    val chip = glyph("chip", lines = listOf("M6 6h12v12H6z", "M9 2v4 M15 2v4 M9 18v4 M15 18v4 M2 9h4 M2 15h4 M18 9h4 M18 15h4"), fills = listOf("M10 10h4v4h-4z"))
    val cloud = glyph("cloud", lines = listOf("M6 19 H18 a4 4 0 0 0 0 -8 a6 6 0 0 0 -11.5 -1 A4.5 4.5 0 0 0 6 19 Z"))
    val wand = glyph("wand", lines = listOf("M4 20 L16 8", "M14 6 L18 10"), fills = listOf("M18 2 L19 4 L21 5 L19 6 L18 8 L17 6 L15 5 L17 4 Z"))
    val ruler = glyph("ruler", lines = listOf("M3 7h18v10H3z", "M7 7v4 M11 7v5 M15 7v4 M19 7v3"))
    val keyboard = glyph("keyboard", lines = listOf("M2 6h20v12H2z", "M6 14h12"), fills = listOf("M5 9h2v2H5z M9 9h2v2H9z M13 9h2v2h-2z M17 9h2v2h-2z"))

    /** The listen button's ear, matching the reference. */
    val ear = glyph(
        "ear",
        lines = listOf(
            "M7 9.5 C7 5.9 9.4 3.5 12.5 3.5 C15.6 3.5 18 5.9 18 9 C18 11.6 16.6 12.8 15.4 14 C14.4 15 14.2 16 14 17.5 C13.7 19.4 12.4 20.5 10.7 20.5 C9.1 20.5 8 19.6 7.6 18.3",
            "M10.2 9.6 C10.2 8 11.2 6.9 12.6 6.9 C14 6.9 15 8 15 9.3 C15 10.6 14.1 11.2 13.3 11.8",
        ),
    )

    /** The tl;dr switch: two stacked bars, the reference's grey-over-light. Drawn by [TldrToggle]. */
    val tldr = glyph("tldr", fills = listOf("M3 6h18v4H3z M3 14h12v4H3z"))
}

@Composable
fun HyIcon(icon: ImageVector, contentDescription: String?, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}
