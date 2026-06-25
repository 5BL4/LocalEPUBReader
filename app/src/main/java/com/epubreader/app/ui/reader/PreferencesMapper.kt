package com.epubreader.app.ui.reader

import com.epubreader.app.data.prefs.AppPreferences
import com.epubreader.app.data.prefs.ThemeMode
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

fun AppPreferences.toEpubPreferences(): EpubPreferences = EpubPreferences(
    fontFamily = fontFamily.toFontFamily(),
    fontSize = (fontSize / 16.0).coerceIn(0.5, 3.0),
    lineHeight = lineSpacing.toDouble(),
    backgroundColor = Color(backgroundColor),
    textColor = null,
    theme = theme.toReadiumTheme(),
    scroll = true
)

private fun String.toFontFamily(): FontFamily = when (this) {
    "serif" -> FontFamily.SERIF
    "sans-serif" -> FontFamily.SANS_SERIF
    else -> FontFamily.SANS_SERIF
}

private fun ThemeMode.toReadiumTheme(): Theme = when (this) {
    ThemeMode.SEPIA -> Theme.SEPIA
    ThemeMode.DARK -> Theme.DARK
    ThemeMode.LIGHT, ThemeMode.SYSTEM -> Theme.LIGHT
}
