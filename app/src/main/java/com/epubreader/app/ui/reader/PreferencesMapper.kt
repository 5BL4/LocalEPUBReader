package com.epubreader.app.ui.reader

import androidx.compose.ui.graphics.toArgb
import com.epubreader.app.data.prefs.AppPreferences
import com.epubreader.app.data.prefs.ThemeMode
import com.epubreader.app.ui.theme.DarkBackground
import com.epubreader.app.ui.theme.LightBackground
import com.epubreader.app.ui.theme.SepiaBackground
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.Color as ReadiumColor

fun AppPreferences.toEpubPreferences(systemDark: Boolean = false): EpubPreferences {
    // Align Readium WebView background with Compose surface/background color.
    // Dark uses #1C1B1F (not Readium's default #000000) to avoid OLED smearing.
    val readiumBgColor = when (theme) {
        ThemeMode.SEPIA -> SepiaBackground
        ThemeMode.DARK -> DarkBackground
        ThemeMode.LIGHT -> LightBackground
        ThemeMode.SYSTEM -> if (systemDark) DarkBackground else LightBackground
    }

    return EpubPreferences(
        fontFamily = fontFamily.toFontFamily(),
        fontSize = (fontSize / 16.0).coerceIn(0.5, 3.0),
        backgroundColor = ReadiumColor(readiumBgColor.toArgb()),
        textColor = null,
        theme = theme.toReadiumTheme(systemDark),
        scroll = scroll,
        pageMargins = pageMargins.toDouble(),
        publisherStyles = null
    )
}

private fun String.toFontFamily(): FontFamily = when (this) {
    "serif" -> FontFamily.SERIF
    "sans-serif" -> FontFamily.SANS_SERIF
    else -> FontFamily.SANS_SERIF
}

private fun ThemeMode.toReadiumTheme(systemDark: Boolean): Theme = when (this) {
    ThemeMode.SEPIA -> Theme.SEPIA
    ThemeMode.DARK -> Theme.DARK
    ThemeMode.LIGHT -> Theme.LIGHT
    ThemeMode.SYSTEM -> if (systemDark) Theme.DARK else Theme.LIGHT
}


