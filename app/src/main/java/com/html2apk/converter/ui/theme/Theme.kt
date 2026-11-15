package com.html2apk.converter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF06223A),
    primaryContainer = Color(0xFF10395A),
    onPrimaryContainer = Color(0xFFD5EBFF),
    secondary = Color(0xFF9BD8A8),
    onSecondary = Color(0xFF0B2413),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF141922),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1C2330),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF2C3543),
    outlineVariant = Color(0xFF212936),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3B0908)
)

@Composable
fun Html2ApkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
