package com.watchemu.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val WatchEmuColors = Colors(
    primary = Color(0xFFE8A820),
    primaryVariant = Color(0xFFD4920A),
    secondary = Color(0xFF8BAC0F),
    secondaryVariant = Color(0xFF6E8A0C),
    background = Color(0xFF0C0D10),
    surface = Color(0xFF1A1C20),
    error = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF3E2800),
    onSecondary = Color(0xFF1E2600),
    onBackground = Color(0xFFE6E1D9),
    onSurface = Color(0xFFE6E1D9),
    onSurfaceVariant = Color(0xFFC8C0B0),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun WatchEmuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WatchEmuColors,
        content = content
    )
}
