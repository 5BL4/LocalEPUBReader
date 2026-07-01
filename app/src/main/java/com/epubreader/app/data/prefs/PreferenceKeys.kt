package com.epubreader.app.data.prefs

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object PreferenceKeys {
    val FONT_SIZE = floatPreferencesKey("font_size")
    val FONT_FAMILY = stringPreferencesKey("font_family")
    val LINE_SPACING = floatPreferencesKey("line_spacing")
    val THEME = stringPreferencesKey("theme")
    val BACKGROUND_COLOR = intPreferencesKey("background_color")
    val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
    val PARAGRAPH_INDENT = floatPreferencesKey("paragraph_indent")
    val PAGE_MARGINS = floatPreferencesKey("page_margins")
    val SCROLL = booleanPreferencesKey("scroll")
    val CONTINUOUS_SCROLL = booleanPreferencesKey("continuous_scroll")
    val TTS_RATE = floatPreferencesKey("tts_rate")
    val TTS_PITCH = floatPreferencesKey("tts_pitch")
    val TTS_ENGINE = stringPreferencesKey("tts_engine")
}
