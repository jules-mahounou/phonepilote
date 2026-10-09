package bj.phonepilote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Couleurs de la marque (relevées sur le logo) + neutres de l'interface. */
object PP {
    val Blue = Color(0xFF005FE3)
    val BlueDeep = Color(0xFF003C9E)
    val BlueSoft = Color(0xFFE3EDFD)
    val Yellow = Color(0xFFFFED00)
    val YellowSoft = Color(0xFFFFF9C2)
    val Bg = Color(0xFFF3F6FC)
    val Ink = Color(0xFF0B1B3A)
    val Muted = Color(0xFF5B6B8C)
    val Line = Color(0xFFDCE3F0)
    val Success = Color(0xFF12B76A)
    val SuccessSoft = Color(0xFFE3F8EE)
    val Danger = Color(0xFFE5484D)
    val DangerSoft = Color(0xFFFDE8E8)
    val Warn = Color(0xFFB58900)

    // Verre : blanc translucide + liseré clair.
    val Glass = Color(0xA6FFFFFF)
    val GlassStrong = Color(0xD9FFFFFF)
    val GlassEdge = Color(0xE6FFFFFF)
    val GlassEdgeFade = Color(0x33FFFFFF)
}

private val scheme = lightColorScheme(
    primary = PP.Blue,
    onPrimary = Color.White,
    primaryContainer = PP.BlueSoft,
    onPrimaryContainer = PP.BlueDeep,
    secondary = PP.Yellow,
    onSecondary = PP.Ink,
    background = PP.Bg,
    onBackground = PP.Ink,
    surface = Color.White,
    onSurface = PP.Ink,
    surfaceVariant = PP.BlueSoft,
    onSurfaceVariant = PP.Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = PP.Line,
    outlineVariant = PP.Line,
    error = PP.Danger,
)

private val type = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun PhonePiloteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
