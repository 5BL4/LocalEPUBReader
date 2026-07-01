package com.epubreader.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : PreferencesRepository {

    override val preferences: Flow<AppPreferences> = dataStore.data.map { p ->
        AppPreferences(
            fontSize = p[PreferenceKeys.FONT_SIZE] ?: 16f,
            fontFamily = p[PreferenceKeys.FONT_FAMILY] ?: "sans-serif",
            lineSpacing = p[PreferenceKeys.LINE_SPACING] ?: 1.4f,
            theme = p[PreferenceKeys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SEPIA) } ?: ThemeMode.SEPIA,
            backgroundColor = p[PreferenceKeys.BACKGROUND_COLOR] ?: 0xFFFFFFFF.toInt(),
            paragraphSpacing = p[PreferenceKeys.PARAGRAPH_SPACING] ?: 0f,
            paragraphIndent = p[PreferenceKeys.PARAGRAPH_INDENT] ?: 0f,
            pageMargins = p[PreferenceKeys.PAGE_MARGINS] ?: 1f,
            scroll = p[PreferenceKeys.SCROLL] ?: false,
            continuousScroll = p[PreferenceKeys.CONTINUOUS_SCROLL] ?: false,
            ttsRate = p[PreferenceKeys.TTS_RATE] ?: 1.0f,
            ttsPitch = p[PreferenceKeys.TTS_PITCH] ?: 1.0f,
            ttsEngine = p[PreferenceKeys.TTS_ENGINE]
        )
    }

    override suspend fun setFontSize(value: Float) { dataStore.edit { it[PreferenceKeys.FONT_SIZE] = value } }
    override suspend fun setFontFamily(value: String) { dataStore.edit { it[PreferenceKeys.FONT_FAMILY] = value } }
    override suspend fun setLineSpacing(value: Float) { dataStore.edit { it[PreferenceKeys.LINE_SPACING] = value } }
    override suspend fun setTheme(value: ThemeMode) { dataStore.edit { it[PreferenceKeys.THEME] = value.name } }
    override suspend fun setBackgroundColor(value: Int) { dataStore.edit { it[PreferenceKeys.BACKGROUND_COLOR] = value } }
    override suspend fun setParagraphSpacing(value: Float) { dataStore.edit { it[PreferenceKeys.PARAGRAPH_SPACING] = value } }
    override suspend fun setParagraphIndent(value: Float) { dataStore.edit { it[PreferenceKeys.PARAGRAPH_INDENT] = value } }
    override suspend fun setPageMargins(value: Float) { dataStore.edit { it[PreferenceKeys.PAGE_MARGINS] = value } }
    override suspend fun setScrollMode(value: Boolean) { dataStore.edit { it[PreferenceKeys.SCROLL] = value } }
    override suspend fun setContinuousScroll(value: Boolean) { dataStore.edit { it[PreferenceKeys.CONTINUOUS_SCROLL] = value } }
    override suspend fun setTtsRate(value: Float) { dataStore.edit { it[PreferenceKeys.TTS_RATE] = value } }
    override suspend fun setTtsPitch(value: Float) { dataStore.edit { it[PreferenceKeys.TTS_PITCH] = value } }
    override suspend fun setTtsEngine(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(PreferenceKeys.TTS_ENGINE) else prefs[PreferenceKeys.TTS_ENGINE] = value
        }
    }
}
