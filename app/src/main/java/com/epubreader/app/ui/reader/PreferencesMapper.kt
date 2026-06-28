package com.epubreader.app.ui.reader

import com.epubreader.app.data.prefs.AppPreferences
import com.epubreader.app.data.prefs.ThemeMode
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

fun AppPreferences.toEpubPreferences(systemDark: Boolean = false): EpubPreferences {
    // lineHeight, paragraphSpacing and paragraphIndent only take effect when
    // publisher styles are disabled. Turn them off only when the user has
    // customised paragraph typography, so the default rendering still respects
    // the publisher's CSS.
    val customTypography = lineSpacing != DEFAULT_LINE_SPACING ||
        paragraphSpacing != 0f ||
        paragraphIndent != 0f

    return EpubPreferences(
        fontFamily = fontFamily.toFontFamily(),
        fontSize = (fontSize / 16.0).coerceIn(0.5, 3.0),
        lineHeight = lineSpacing.toDouble(),
        backgroundColor = null,
        textColor = null,
        theme = theme.toReadiumTheme(systemDark),
        scroll = scroll,
        paragraphSpacing = paragraphSpacing.toDouble(),
        paragraphIndent = paragraphIndent.toDouble(),
        pageMargins = pageMargins.toDouble(),
        publisherStyles = if (customTypography) false else null
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

private const val DEFAULT_LINE_SPACING = 1.4f
