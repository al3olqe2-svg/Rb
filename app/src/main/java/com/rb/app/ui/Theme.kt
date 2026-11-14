package com.rb.app.ui

import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
        MaterialTheme(colorScheme = Colors, shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp)),
            typography = Typography(
                titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
                labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)), content = content)
    }
}
val NumberFont = FontFamily.Monospace
