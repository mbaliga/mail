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
import androidx.compose.ui.graphics.vector.group
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
                pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter, strokeLineMiter = 10f,
            )
            for (d in fills) addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        }.build()


    private fun stream(name: String, vararg d: String, mirrored: Boolean = false) = stream(name, mirrored, arrayOf(*d))
    private fun stream(name: String, mirrored: Boolean, d: Array<String>): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 48f, 48f).apply {
            fun draw() { for (p in d) addPath(pathData = addPathNodes(p), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 3f, strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter, strokeLineMiter = 10f) }
            if (mirrored) group(scaleX = -1f, pivotX = 24f) { draw() } else draw()
        }.build()

    // ---- Hyle ----
    val play = glyph("play", fills = listOf("M6 4 L20 12 L6 20 Z"))
    val pause = glyph("pause", fills = listOf("M6 4h4v16H6z M14 4h4v16h-4z"))
    val stop = glyph("stop", fills = listOf("M5 5h14v14H5z"))
    val next = glyph("next", fills = listOf("M5 4 L15 12 L5 20 Z M17 4h3v16h-3z"))
    val prev = glyph("prev", fills = listOf("M19 4 L9 12 L19 20 Z M4 4h3v16H4z"))
    val check = glyph("check", lines = listOf("M4 12 L10 18 L20 6"))
    val close = glyph("close", lines = listOf("M6 6 L18 18 M18 6 L6 18"))
    val minus = glyph("minus", lines = listOf("M4 12 H20"))
    val chevronDown = glyph("chevronDown", lines = listOf("M4 8 L12 16 L20 8"))
    val chevronRight = glyph("chevronRight", lines = listOf("M8 4 L16 12 L8 20"))
    val menu = glyph("menu", lines = listOf("M4 6 H20 M4 12 H20 M4 18 H20"))
    val layers = glyph("layers", lines = listOf("M12 3 L21 8 L12 13 L3 8 Z", "M3 13 L12 18 L21 13"))
    val bolt = glyph("bolt", fills = listOf("M13 2 L4 14 H11 L10 22 L20 9 H13 Z"))
    val folder = glyph("folder", lines = listOf("M3 6h6l2 2h10v12H3z"))
    val lock = glyph("lock", lines = listOf("M6 11h12v9H6z", "M9 11 V8 a3 3 0 0 1 6 0 v3"))
    val copy = glyph("copy", lines = listOf("M9 9h11v11H9z", "M4 4h11v2 M4 4v11h2"))

    // ---- mail ----
    val archive = glyph("archive", lines = listOf("M3 4h18v5H3z", "M5 9v11h14V9", "M9 13h6"))
    val star = glyph("star", lines = listOf("M12 3 L14.6 9 L21 9.5 L16 13.8 L17.6 20.5 L12 17 L6.4 20.5 L8 13.8 L3 9.5 L9.4 9 Z"))
    val starFilled = glyph("starFilled", fills = listOf("M12 3 L14.6 9 L21 9.5 L16 13.8 L17.6 20.5 L12 17 L6.4 20.5 L8 13.8 L3 9.5 L9.4 9 Z"))
    val replyAll = glyph("replyAll", lines = listOf("M9 5 L3 11 L9 17", "M14 5 L8 11 L14 17", "M8 11 H15 a5 5 0 0 1 5 5 v3"))
    val snooze = glyph("snooze", lines = listOf("M12 4 a8 8 0 1 0 0.01 0 Z", "M9 9h6l-6 6h6", "M3 4 L6 2 M21 4 L18 2"))
    val tag = glyph("tag", lines = listOf("M3 3h9l9 9-9 9-9-9z"), fills = listOf("M6.5 6.5h3v3h-3z"))
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
    val person = glyph("person", lines = listOf("M8 7 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0", "M4 21 V18 a4 4 0 0 1 4 -4 h8 a4 4 0 0 1 4 4 v3"))
    val people = glyph("people", lines = listOf("M5 8 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0", "M2 20 V17 a3 3 0 0 1 3 -3 h6 a3 3 0 0 1 3 3 v3", "M15 5 a3 3 0 0 1 0 6 M17 14 a3 3 0 0 1 3 3 v3"))
    val globe = glyph("globe", lines = listOf("M12 3 a9 9 0 1 0 0.01 0 Z", "M3 12h18", "M12 3 C8 7 8 17 12 21 C16 17 16 7 12 3"))
    val eye = glyph("eye", lines = listOf("M2 12 C5 6 19 6 22 12 C19 18 5 18 2 12 Z"), fills = listOf("M10 10h4v4h-4z"))
    val download = glyph("download", lines = listOf("M12 3 V15 M6 9 L12 15 L18 9", "M4 20h16"))
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

    // ---- the supplied set: drawn on a 48 grid, 3-unit strokes (1.5 at 24), kept as supplied ----
    val mail = stream("mail", "M1 13L23.75 27H24.25L47 13M47 8V41H46C40 40 30 39.5 24 39.5C18 39.5 8 40 2 41H1V8C7 7 18 6.5 24 6.5C30 6.5 41 7 47 8Z")
    val inbox = stream("inbox", "M22 43V28.652C22 22.54 18.116 17 12 17C11.5994 17 11.2084 17.0238 10.8274 17.0699M22 43H2V28.652C2 22.9385 5.39187 17.7279 10.8274 17.0699M22 43H23L46 39V23.918C46 18.228 41.3 14.286 35.674 14.05C35.3173 14.0385 34.9603 14.0539 34.606 14.096L32 14.384M10.8274 17.0699C15.0674 16.4335 23.2463 15.3868 28 14.838M10.8274 17.0699C10.8183 17.0713 10.8091 17.0726 10.8 17.074M28 14.838V33M28 14.838V9.5M28 9.5V3.5L38 2C38 2 39 5 38 8L28 9.5ZM6 28C6 28 9 29 12 29C15 29 18 28 18 28")
    val calendar = stream("calendar", "M15 12V2M35 12V2M43 34V43H7V37M42.726 17H6.704M1 36.5V37H36.8L37.1 36.5L37.568 35.518C41.1443 28.0014 43 19.782 43 11.458V7H7V11.256C7.00008 19.6444 5.11568 27.9256 1.486 35.488L1 36.5Z")
    val clock = stream("clock", "M34.9999 34.9999L34.1919 33.8679C31.4206 29.9881 27.9672 26.6444 23.9999 23.9999L24.1099 23.0139C24.7013 17.6901 24.6644 12.3151 23.9999 6.99991M44.9959 23.9999C44.9959 12.4039 35.5959 3.00391 23.9999 3.00391C12.4039 3.00391 3.00391 12.4039 3.00391 23.9999C3.00391 35.5959 12.4039 44.9959 23.9999 44.9959C35.5959 44.9959 44.9959 35.5959 44.9959 23.9999Z")
    val image = stream("image", "M45 37H44.392C42.512 37 40.638 36.452 39.264 35.166C37.6295 33.6379 36.2393 31.868 35.142 29.918L33.502 27H32.502L30.858 29.918C29.9032 31.6137 28.7261 33.1741 27.358 34.558C25.9262 34.1695 24.6096 33.4408 23.52 32.434C21.2438 30.2921 19.3093 27.814 17.784 25.086L15.5 21H14.504L12.222 25.086C10.5116 28.1444 8.28863 30.8862 5.65 33.192C4.88071 33.8582 3.97685 34.3507 3 34.636M3 45V3H45V45H3ZM33 19C31.9391 19 30.9217 18.5786 30.1716 17.8284C29.4214 17.0783 29 16.0609 29 15C29 13.9391 29.4214 12.9217 30.1716 12.1716C30.9217 11.4214 31.9391 11 33 11C34.0609 11 35.0783 11.4214 35.8284 12.1716C36.5786 12.9217 37 13.9391 37 15C37 16.0609 36.5786 17.0783 35.8284 17.8284C35.0783 18.5786 34.0609 19 33 19Z")
    val settings = stream("settings", "M21.0005 3L20.6385 4.086C20.284 5.149 19.8032 6.16561 19.2065 7.114C18.4055 8.37608 17.3111 9.42561 16.0166 10.173C14.7221 10.9204 13.2659 11.3434 11.7725 11.406C10.6535 11.4484 9.53349 11.3565 8.43645 11.132L7.31445 10.902L4.31445 16.098L5.07445 16.956C5.82245 17.8 6.46045 18.724 6.98045 19.708C7.67298 21.0327 8.03468 22.5052 8.03468 24C8.03468 25.4948 7.67298 26.9673 6.98045 28.292C6.45747 29.2823 5.81757 30.2062 5.07445 31.044L4.31445 31.902L7.31445 37.098L8.43445 36.868C9.53279 36.6432 10.6542 36.5512 11.7745 36.594C13.2676 36.6569 14.7233 37.0801 16.0174 37.8275C17.3116 38.5748 18.4057 39.6242 19.2065 40.886C19.7985 41.826 20.2805 42.844 20.6385 43.914L21.0005 45H27.0005L27.3625 43.914C27.7205 42.842 28.2025 41.828 28.7945 40.886C29.5954 39.6239 30.6898 38.5744 31.9843 37.827C33.2789 37.0796 34.735 36.6566 36.2285 36.594C37.3474 36.5515 38.4674 36.6435 39.5645 36.868L40.6865 37.098L43.6865 31.902L42.9265 31.044C42.1834 30.2061 41.5435 29.2822 41.0205 28.292C40.3279 26.9673 39.9662 25.4948 39.9662 24C39.9662 22.5052 40.3279 21.0327 41.0205 19.708C41.5405 18.724 42.1785 17.8 42.9265 16.956L43.6865 16.098L40.6865 10.902L39.5665 11.132C38.4681 11.3568 37.3467 11.4487 36.2265 11.406C34.7333 11.3431 33.2776 10.9199 31.9835 10.1725C30.6893 9.42517 29.5952 8.37581 28.7945 7.114C28.1976 6.16564 27.7168 5.14903 27.3625 4.086L27.0005 3H21.0005Z", "M31.0041 24.0001C31.0041 25.8577 30.2662 27.6392 28.9527 28.9527C27.6392 30.2662 25.8577 31.0041 24.0001 31.0041C22.1425 31.0041 20.361 30.2662 19.0475 28.9527C17.734 27.6392 16.9961 25.8577 16.9961 24.0001C16.9961 22.1425 17.734 20.361 19.0475 19.0475C20.361 17.734 22.1425 16.9961 24.0001 16.9961C25.8577 16.9961 27.6392 17.734 28.9527 19.0475C30.2662 20.361 31.0041 22.1425 31.0041 24.0001Z")
    val search = stream("search", "M36.85 36.85L47 47M22 43C10.402 43 1 33.598 1 22C1 10.402 10.402 1 22 1C33.598 1 43 10.402 43 22C43 33.598 33.598 43 22 43Z")
    val pencil = stream("pencil", "M20 45H46M7 32L36 3C38.3869 3 40.6761 3.94821 42.364 5.63604C44.0518 7.32387 45 9.61305 45 12L16 41H14C10.052 41 7.246 42.168 3.96 44.36L3 45L3.64 44.04C5.834 40.752 7 37.946 7 34V32Z")
    val share = stream("share", "M31.1336 33.8904C31.673 32.9887 32.3846 32.2019 33.228 31.5752C34.0714 30.9484 35.03 30.4939 36.049 30.2377C37.068 29.9814 38.1276 29.9283 39.1671 30.0815C40.2066 30.2347 41.2058 30.5911 42.1076 31.1304C43.0094 31.6698 43.7961 32.3815 44.4229 33.2248C45.0496 34.0682 45.5041 35.0268 45.7604 36.0458C46.0167 37.0649 46.0698 38.1244 45.9166 39.1639C45.7634 40.2035 45.407 41.2027 44.8676 42.1044C43.7784 43.9257 42.0103 45.2396 39.9522 45.7572C37.8942 46.2748 35.7149 45.9537 33.8936 44.8644C32.0724 43.7752 30.7584 42.0071 30.2408 39.9491C29.7232 37.891 30.0444 35.7117 31.1336 33.8904ZM31.1336 33.8904C27.1418 31.1272 22.7539 28.9855 18.1196 27.5384L17.2996 27.2804M31.1336 14.1084C30.5943 13.2067 30.2378 12.2075 30.0847 11.1679C29.9315 10.1284 29.9845 9.06886 30.2408 8.04983C30.4971 7.0308 30.9516 6.07223 31.5784 5.22885C32.2051 4.38546 32.9918 3.67379 33.8936 3.13445C34.7954 2.59511 35.7946 2.23867 36.8341 2.08548C37.8737 1.9323 38.9332 1.98537 39.9522 2.24166C40.9713 2.49795 41.9298 2.95244 42.7732 3.57919C43.6166 4.20593 44.3283 4.99266 44.8676 5.89445C45.9569 7.71569 46.278 9.89504 45.7604 11.9531C45.2428 14.0111 43.9289 15.7792 42.1076 16.8684C40.2864 17.9577 38.107 18.2788 36.049 17.7612C33.991 17.2436 32.2229 15.9297 31.1336 14.1084ZM31.1336 14.1084C27.1417 16.871 22.7538 19.0121 18.1196 20.4584L17.2996 20.7164M17.2996 20.7164C17.7496 21.7164 17.9996 22.8264 17.9996 23.9964C17.9996 25.1664 17.7496 26.2764 17.2996 27.2764C16.5491 28.9459 15.2489 30.3076 13.6157 31.1342C11.9826 31.9608 10.1154 32.2024 8.32565 31.8186C6.53592 31.4349 4.93191 30.449 3.78126 29.0254C2.63062 27.6019 2.00293 25.8269 2.00293 23.9964C2.00293 22.166 2.63062 20.391 3.78126 18.9674C4.93191 17.5439 6.53592 16.558 8.32565 16.1743C10.1154 15.7905 11.9826 16.0321 13.6157 16.8587C15.2489 17.6853 16.5491 19.047 17.2996 20.7164Z")
    val send = stream("send", "M11 25.9966L36 11.9966M32.5 46.9966H33C34.9876 31.7598 39.3781 16.9345 46.008 3.07256L46.52 1.99656L46 1.47656L44.924 1.98856C31.0621 8.61851 16.2368 13.009 1 14.9966V15.4966L11 25.4966V40.9966H11.5L15.048 37.6166C16.4458 36.286 17.999 35.129 19.674 34.1706L32.5 46.9966Z")
    val plus = stream("plus", "M19 19V1H19.5L20.04 1.108C22.6541 1.63061 25.3459 1.63061 27.96 1.108L28.5 1H29V19H47V19.5L46.892 20.04C46.3694 22.6541 46.3694 25.3459 46.892 27.96L47 28.5V29H29V47H28.5L27.96 46.892C25.3459 46.3694 22.6541 46.3694 20.04 46.892L19.5 47H19V29H1V28.5L1.108 27.96C1.63061 25.3459 1.63061 22.6541 1.108 20.04L1 19.5V19H19Z")
    val arrowLeft = stream("arrowLeft", "M48 24H0M0 24C1.91124 24 4.28974 22.8496 6.24752 21.6524C8.86212 20.0536 11.1417 17.9586 13.0509 15.5612C14.5337 13.6992 16 11.483 16 10M0 24C1.91124 24 4.28974 25.1504 6.24752 26.3476C8.86212 27.9464 11.1417 30.0414 13.0509 32.4388C14.5337 34.3008 16 36.517 16 38")
    val arrowRight = stream("arrowRight", "M0 24H48M48 24C46.0888 24 43.7102 22.8496 41.7524 21.6524C39.1378 20.0536 36.8582 17.9586 34.9492 15.5612C33.4662 13.6992 32 11.483 32 10M48 24C46.0888 24 43.7102 25.1504 41.7524 26.3476C39.1378 27.9464 36.8582 30.0414 34.9492 32.4388C33.4662 34.3008 32 36.517 32 38")
    val trash = stream("trash", "M22 40H45V39L37.528 25.514M22 40C25.866 40 29 33 29 33M22 40C25.866 40 29 47 29 47M35.58 22L35.549 21.944M35.549 21.944L24.5 2H23.5L16.05 15.46M35.549 21.944C35.5584 21.9627 35.5681 21.9814 35.578 22C33.646 18.652 26.016 19.438 26.016 19.438M35.549 21.944C33.8663 18.6161 38.14 12.438 38.14 12.438M14.1 18.968L3 39V40H18M4.538 21.53C4.538 21.53 12.166 22.314 14.1 18.968C12.34 22.282 16.664 28.53 16.664 28.53")
    val reply = stream("reply", "M16 20C16 21.483 14.5337 23.6992 13.0509 25.5612C11.1417 27.9586 8.86212 30.0536 6.24752 31.6524C4.28974 32.8496 1.91124 34 0 34M0 34C1.91124 34 4.28974 35.1504 6.24752 36.3476C8.86212 37.9464 11.1417 40.0414 13.0509 42.4388C14.5337 44.3008 16 46.517 16 48M0 34H23C36.2548 34 47 23.2548 47 10V0")
    val info = stream("info", "M24 14V10M18 19H24V35M24 35H16M24 35H32M24 47C11.298 47 1 36.702 1 24C1 11.298 11.298 1 24 1C36.702 1 47 11.298 47 24C47 36.702 36.702 47 24 47Z")
    val alert = stream("alert", "M24 12V29M24 32V36M14.888 2C14.888 2 18.436 2.5 24 2.5C29.564 2.5 33.112 2 33.112 2C33.112 2 35.268 4.862 39.202 8.798C43.14 12.732 46 14.888 46 14.888C46 14.888 45.5 18.436 45.5 24C45.5 29.564 46 33.112 46 33.112C46 33.112 43.138 35.268 39.202 39.202C35.268 43.14 33.112 46 33.112 46C33.112 46 29.564 45.5 24 45.5C18.436 45.5 14.888 46 14.888 46C14.888 46 12.732 43.138 8.798 39.202C4.86 35.268 2 33.112 2 33.112C2 33.112 2.5 29.564 2.5 24C2.5 18.436 2 14.888 2 14.888C2 14.888 4.862 12.732 8.798 8.798C12.732 4.86 14.888 2 14.888 2Z")
    val important = stream("important", "M24 39C25.0609 39 26.0783 39.4214 26.8284 40.1716C27.5786 40.9217 28 41.9391 28 43C28 44.0609 27.5786 45.0783 26.8284 45.8284C26.0783 46.5786 25.0609 47 24 47C22.9391 47 21.9217 46.5786 21.1716 45.8284C20.4214 45.0783 20 44.0609 20 43C20 41.9391 20.4214 40.9217 21.1716 40.1716C21.9217 39.4214 22.9391 39 24 39Z", "M28 33H20V32.742C19.9996 22.2901 18.6778 11.8803 16.066 1.76L16 1.5V1H32V1.5L31.934 1.758C29.3222 11.8783 28.0003 22.2881 28 32.74V33Z")
    val logout = stream("logout", "M48 24H16M37 14C37 15.154 38.33 17.124 39.456 18.588C40.4324 19.877 41.611 20.9995 42.946 21.912C44.4 22.89 46.4 24 47.98 24C46.4 24 44.4 25.112 42.946 26.088C41.611 27.0005 40.4324 28.123 39.456 29.412C38.33 30.876 37 32.846 37 34M29 14V5H28.342C19.4931 5.00017 10.6904 3.72369 2.206 1.21L1.5 1H1V47H1.5L2.206 46.79C10.6904 44.2763 19.4931 42.9998 28.342 43H29V34")
    val tools = stream("tools", "M13.5 13.5015L20.995 20.9965M13.5 13.5015L17 10.0015V9.50152L14.12 8.30152C12.05 7.43887 10.1112 6.29037 8.36 4.88952L6 3.00152H5.5L3 5.50152V6.00152L4.888 8.36152C6.28885 10.1127 7.43736 12.0515 8.3 14.1215L9.5 17.0015H10L13.5 13.5015ZM20.995 20.9965L21 21.0015M20.995 20.9965C21.331 20.6671 21.6662 20.3353 22 20.0015L25.594 16.4075C24.9266 14.5684 24.8133 12.574 25.2681 10.6712C25.723 8.76834 26.726 7.04074 28.153 5.70232C29.58 4.3639 31.3682 3.47347 33.2963 3.14129C35.2243 2.80911 37.2074 3.04979 39 3.83352V5.00152L33 11.0015V15.0015H37L43 9.00152H44.168C44.8323 10.5235 45.107 12.1871 44.9675 13.8419C44.8279 15.4967 44.2785 17.0906 43.3687 18.4799C42.4589 19.8692 41.2174 21.0101 39.7564 21.7995C38.2953 22.5889 36.6607 23.002 35 23.0015C33.8388 23.0032 32.6861 22.8022 31.594 22.4075L28 26.0015C19 35.0015 11.5 45.0015 11.5 45.0015H11L3 37.0015V36.5015C3 36.5015 12.2719 29.5476 20.995 20.9965ZM27.468 34.0015C30.6056 37.5621 33.6181 41.231 36.5 45.0015H37L45 37.0015V36.5015C45 36.5015 40.06 32.7975 34 27.4695")
    val angleDown = stream("angleDown", "M48 32C46.518 32 44.3 33.466 42.44 34.95C40.04 36.858 37.946 39.138 36.348 41.752C35.15 43.712 34 46.088 34 48M34 48C34 46.088 32.85 43.71 31.652 41.752C30.052 39.138 27.958 36.858 25.562 34.95C23.7 33.466 21.48 32 20 32M34 48V25C34 11.746 23.254 1 10 1H0")
    val inbound = stream("inbound", "M6.02 41.98L42 6M8.14 10.158C9.71 11.73 10.504 15.632 10.902 19.178C11.418 23.742 11.22 28.378 10.14 32.846C9.332 36.192 8.028 39.932 6 41.96M37.84 39.858C36.27 38.288 32.366 37.496 28.82 37.096C24.256 36.58 19.62 36.778 15.152 37.858C11.806 38.666 8.066 39.97 6.038 41.998")
    /** Forward is the supplied reply arrow, mirrored. */
    val forward = stream("forward", mirrored = true, d = arrayOf("M16 20C16 21.483 14.5337 23.6992 13.0509 25.5612C11.1417 27.9586 8.86212 30.0536 6.24752 31.6524C4.28974 32.8496 1.91124 34 0 34M0 34C1.91124 34 4.28974 35.1504 6.24752 36.3476C8.86212 37.9464 11.1417 40.0414 13.0509 42.4388C14.5337 44.3008 16 46.517 16 48M0 34H23C36.2548 34 47 23.2548 47 10V0"))
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
