package com.jarvis.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonPurple = Color(0xFFB026FF)
val ElectricBlue = Color(0xFF0066FF)
val CyberCyan = Color(0xFF00F0FF)
val StatusGreen = Color(0xFF00FF9D)
val BgBlack = Color(0xFF050508)
val PanelDark = Color(0xFF0D0E17)
val SurfaceDark = Color(0xFF131524)
val TextWhite = Color(0xFFFFFFFF)
val TextGray = Color(0xFF9CA3AF)

private val DarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    secondary = NeonPurple,
    tertiary = ElectricBlue,
    background = BgBlack,
    surface = PanelDark,
    surfaceVariant = SurfaceDark,
    onPrimary = BgBlack,
    onSecondary = TextWhite,
    onTertiary = TextWhite,
    onBackground = TextWhite,
    onSurface = TextWhite,
    onSurfaceVariant = TextGray,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
