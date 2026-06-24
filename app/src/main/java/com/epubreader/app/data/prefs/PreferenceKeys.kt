package com.epubreader.app.data.prefs

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object PreferenceKeys {
    val FONT_SIZE = floatPreferencesKey("font_size")
    val FONT_FAMILY = stringPreferencesKey("font_family")
    val LINE_SPACING = floatPreferencesKey("line_spacing")
    val THEME = stringPreferencesKey("theme")
    val BACKGROUND_COLOR = intPreferencesKey("background_color")
    val AUTO_PAGE_INTERVAL_MS = longPreferencesKey("auto_page_interval_ms")
    val AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
    val TTS_RATE = floatPreferencesKey("tts_rate")
    val TTS_PITCH = floatPreferencesKey("tts_pitch")
    val TTS_ENGINE = stringPreferencesKey("tts_engine")
}
