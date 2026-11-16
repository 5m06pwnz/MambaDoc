package com.mambadoc.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF002B6B),
    secondary = Color(0xFF9FB4FF),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF21262D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    error = Color(0xFFFF7B72)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    onBackground = Color(0xFF1F2328),
    onSurface = Color(0xFF1F2328)
)

@Composable
fun MambaDocTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
