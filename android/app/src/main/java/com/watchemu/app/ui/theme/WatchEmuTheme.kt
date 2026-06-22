package com.watchemu.app.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Uses regular Jetpack Compose Material (not Wear Compose) so the app runs on
// plain Android 5.1 (Amazfit Stratos), which has no Wear OS runtime.
private val WatchEmuColors = darkColors(
    primary = Color(0xFFE8A820),
    primaryVariant = Color(0xFFD4920A),
    secondary = Color(0xFF8BAC0F),
    secondaryVariant = Color(0xFF6E8A0C),
    background = Color(0xFF000000),
    surface = Color(0xFF1A1C20),
    error = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF3E2800),
    onSecondary = Color(0xFF1E2600),
    onBackground = Color(0xFFE6E1D9),
    onSurface = Color(0xFFE6E1D9),
    onError = Color(0xFFFFFFFF),
)

// Regular Material's Colors has no onSurfaceVariant (it is a Wear/Material3
// slot), so the muted caption color the screens use is exposed separately.
val OnSurfaceVariant = Color(0xFFC8C0B0)

@Composable
fun WatchEmuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WatchEmuColors,
        content = content
    )
}
