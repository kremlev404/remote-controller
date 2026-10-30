package com.remotecontroller.web

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF2E7D32)
private val GreenOnDark = Color(0xFF81C784)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F3E6),
    onPrimaryContainer = Color(0xFF0E3B14),
    secondary = Color(0xFF3F3F46),
    onSecondary = Color.White,
    background = Color(0xFFF4F4F5),
    onBackground = Color(0xFF141416),
    surface = Color.White,
    onSurface = Color(0xFF141416),
    surfaceVariant = Color(0xFFECECEE),
    onSurfaceVariant = Color(0xFF5E5E66),
    outline = Color(0xFFE2E2E6),
    outlineVariant = Color(0xFFE2E2E6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = GreenOnDark,
    onPrimary = Color(0xFF0C1F10),
    primaryContainer = Color(0xFF1B3D22),
    onPrimaryContainer = Color(0xFFD8F3DC),
    secondary = Color(0xFFC4C4CC),
    onSecondary = Color(0xFF1A1A1E),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF242428),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF2E2E34),
    outlineVariant = Color(0xFF2E2E34),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppTypography = Typography(
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun WebTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
