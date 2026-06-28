package com.epubreader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.epubreader.app.data.prefs.ThemeMode

@Composable
fun EpubReaderTheme(
    themeMode: ThemeMode = ThemeMode.SEPIA,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkColorScheme else LightColorScheme
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.LIGHT -> LightColorScheme
        ThemeMode.SEPIA -> SepiaColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
