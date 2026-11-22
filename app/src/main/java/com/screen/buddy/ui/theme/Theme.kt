package com.screen.buddy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BuddyColors = darkColorScheme(
    primary = Seafoam,
    onPrimary = Ink950,
    primaryContainer = Ink700,
    onPrimaryContainer = Mist,
    secondary = Amber,
    onSecondary = Ink950,
    tertiary = Coral,
    background = Ink900,
    onBackground = Mist,
    surface = Ink800,
    onSurface = Mist,
    surfaceVariant = Ink700,
    onSurfaceVariant = MistDim,
    outline = Color(0xFF3A4656),
    error = Coral,
)

@Composable
fun ScreenBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BuddyColors,
        typography = Typography,
        content = content,
    )
}
