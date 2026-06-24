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
            theme = p[PreferenceKeys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM) } ?: ThemeMode.SYSTEM,
            backgroundColor = p[PreferenceKeys.BACKGROUND_COLOR] ?: 0xFFFFFFFF.toInt(),
            autoPageIntervalMs = p[PreferenceKeys.AUTO_PAGE_INTERVAL_MS] ?: 5000L,
            autoScrollSpeed = p[PreferenceKeys.AUTO_SCROLL_SPEED] ?: 1.0f,
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
    override suspend fun setAutoPageIntervalMs(value: Long) { dataStore.edit { it[PreferenceKeys.AUTO_PAGE_INTERVAL_MS] = value } }
    override suspend fun setAutoScrollSpeed(value: Float) { dataStore.edit { it[PreferenceKeys.AUTO_SCROLL_SPEED] = value } }
    override suspend fun setTtsRate(value: Float) { dataStore.edit { it[PreferenceKeys.TTS_RATE] = value } }
    override suspend fun setTtsPitch(value: Float) { dataStore.edit { it[PreferenceKeys.TTS_PITCH] = value } }
    override suspend fun setTtsEngine(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(PreferenceKeys.TTS_ENGINE) else prefs[PreferenceKeys.TTS_ENGINE] = value
        }
    }
}
