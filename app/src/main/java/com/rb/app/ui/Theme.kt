package com.rb.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection

private val Colors = lightColorScheme(
    primary = Color(0xFF14685D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE7), onPrimaryContainer = Color(0xFF0A443D),
    secondary = Color(0xFF53645E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0ECE5), onSecondaryContainer = Color(0xFF24483D),
    surfaceTint = Color(0xFF14685D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F5F2),
    surfaceContainer = Color(0xFFEDF2EF), surfaceContainerHigh = Color(0xFFE6EEE8),
    surfaceContainerHighest = Color(0xFFDFE8E2),
    background = Color(0xFFF6F8F7), onBackground = Color(0xFF192D28),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF192D28),
    surfaceVariant = Color(0xFFEAF0ED), onSurfaceVariant = Color(0xFF50635B),
    outline = Color(0xFF71837B), outlineVariant = Color(0xFFD7E1DB),
    error = Color(0xFFAC2424),
)

@Composable
fun RbTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
    }
}
val NumberFont = FontFamily.Monospace
