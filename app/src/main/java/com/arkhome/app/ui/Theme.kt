package com.arkhome.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArkBlue = Color(0xFF4FC3F7)
private val ArkBlueDark = Color(0xFF0277BD)

private val DarkScheme = darkColorScheme(
    primary = ArkBlue,
    onPrimary = Color(0xFF00243A),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF101418),
    surface = Color(0xFF171C21),
    surfaceVariant = Color(0xFF232A31),
    onBackground = Color(0xFFE3E7EA),
    onSurface = Color(0xFFE3E7EA),
)

private val LightScheme = lightColorScheme(
    primary = ArkBlueDark,
    secondary = Color(0xFF00695C),
    background = Color(0xFFF5F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7ECF0),
)

@Composable
fun ArkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
