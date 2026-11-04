package dev.aarso.mail.app.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.hyle.Provenance
import dev.aarso.hyle.tokens.HyleTokens
import dev.aarso.mail.app.R

/**
 * The look of the home screen, taken from its reference design and generalised: a black field
 * with large grotesk type, coloured underlined phrases you can tap, a bold tab strip, and a white
 * sheet that holds the mail. Everything else in the app is built from the same parts.
 */
object Palette {
    val Field = Color(0xFF000000)
    val Ink = Color(0xFFFFFFFF)
    val InkDim = Color(0xFF8A8A8A)
    val InkFaint = Color(0xFF333333)
    /** The count in "298 new mail". */
    val Count = Color(0xFF818AFF)
    /** "on Gmail ▾" — which accounts. */
    val Accounts = Color(0xFF005475)
    /** "since yesterday ▾" — which window. */
    val Window = Color(0xFF428EFF)
    val TabActive = Color(0xFFFFFFFF)
    val TabIdle = Color(0xFF333333)
    val Sheet = Color(0xFFFFFFFF)
    val SheetInk = Color(0xFF000000)
    val SheetInkRead = Color(0xFF5C5C5C)
    val SheetInkDim = Color(0xFF8C8C8C)
    val Hairline = Color(0xFFC1C1C1)
    val SheetRaised = Color(0xFFF2F2F2)
    val Select = Color(0xFFE6E8FF)
    val ToggleTop = Color(0xFF797979)
    val ToggleBottom = Color(0xFFEDEDED)
    val FabTop = Color(0xFF4A5CBA)
    val FabBottom = Color(0xFF2C3870)
    val Navy = Color(0xFF1F3354)
    val Room = Color(HyleTokens.Color.colorPaletteFieldRaised)
    val RoomCard = Color(HyleTokens.Color.controlSurface)
    val RoomInk = Color(HyleTokens.Color.colorTextPrimary)
    val RoomInkDim = Color(HyleTokens.Color.colorTextSecondary)
    val RoomHairline = Color(HyleTokens.Color.colorBorderStrong)
    val Accent = Color(HyleTokens.Color.colorActionPrimary)
    val Danger = Color(HyleTokens.Color.colorFeedbackDanger)
    val Warning = Color(HyleTokens.Color.colorFeedbackWarning)
    val Success = Color(HyleTokens.Color.colorFeedbackSuccess)
    val OnDevice = Color(Provenance.OnDevice.hue)
    val Cloud = Color(Provenance.Cloud.hue)

    val fab: Brush = Brush.linearGradient(listOf(FabTop, FabBottom))
}

val Grotesk = FontFamily(
    Font(R.font.hyle_grotesk_light, FontWeight.Light),
    Font(R.font.hyle_grotesk_regular, FontWeight.Normal),
    Font(R.font.hyle_grotesk_medium, FontWeight.Medium),
    Font(R.font.hyle_grotesk_bold, FontWeight.Bold),
)

/** Sizes that scale with the window, so the headline keeps the reference's proportions on any phone. */
@Immutable
data class Metrics(
    val widthDp: Float,
    val headline: TextUnit,
    val headlineLine: TextUnit,
    val tab: TextUnit,
    val row: TextUnit,
    val rowLine: TextUnit,
    val gutter: Dp,
    val wide: Boolean,
)

val LocalMetrics = staticCompositionLocalOf { metricsFor(400f) }

fun metricsFor(widthDp: Float): Metrics {
    val compactWidth = widthDp.coerceAtMost(600f)
    val h = (compactWidth * 0.1f).coerceIn(30f, 48f)
    return Metrics(
        widthDp = widthDp,
        headline = h.sp,
        headlineLine = (h * 1.14f).sp,
        tab = (h * 0.72f).sp,
        row = 18.sp,
        rowLine = 26.sp,
        gutter = if (widthDp >= 600f) 32.dp else 24.dp,
        wide = widthDp >= 840f,
    )
}

object Type {
    fun headline(m: Metrics) = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = m.headline, lineHeight = m.headlineLine, letterSpacing = (-0.5).sp)
    fun tab(m: Metrics) = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = m.tab, lineHeight = m.tab * 1.1f, letterSpacing = 0.sp)
    fun row(m: Metrics) = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = m.row, lineHeight = m.rowLine)
    val title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp)
    val heading = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)
    val label = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)
    val small = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp)
    val caps = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.Black,
    secondary = Palette.Window,
    background = Palette.Field,
    onBackground = Palette.Ink,
    surface = Palette.Room,
    onSurface = Palette.RoomInk,
    surfaceVariant = Palette.RoomCard,
    onSurfaceVariant = Palette.RoomInkDim,
    surfaceContainer = Palette.RoomCard,
    surfaceContainerHigh = Color(HyleTokens.Color.controlSurfaceRaised),
    surfaceContainerHighest = Color(HyleTokens.Color.controlSurfaceHigh),
    outline = Palette.RoomHairline,
    error = Palette.Danger,
)

@Composable
fun MailTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Grotesk),
        headlineLarge = base.headlineLarge.copy(fontFamily = Grotesk),
        headlineMedium = base.headlineMedium.copy(fontFamily = Grotesk),
        titleLarge = base.titleLarge.copy(fontFamily = Grotesk),
        titleMedium = base.titleMedium.copy(fontFamily = Grotesk),
        bodyLarge = base.bodyLarge.copy(fontFamily = Grotesk),
        bodyMedium = base.bodyMedium.copy(fontFamily = Grotesk),
        bodySmall = base.bodySmall.copy(fontFamily = Grotesk),
        labelLarge = base.labelLarge.copy(fontFamily = Grotesk),
        labelMedium = base.labelMedium.copy(fontFamily = Grotesk),
        labelSmall = base.labelSmall.copy(fontFamily = Grotesk),
    )
    MaterialTheme(colorScheme = scheme, typography = typography) {
        BoxWithConstraints {
            CompositionLocalProvider(LocalMetrics provides metricsFor(maxWidth.value)) { content() }
        }
    }
}
