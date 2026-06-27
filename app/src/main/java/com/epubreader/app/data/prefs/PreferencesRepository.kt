package com.epubreader.app.data.prefs

import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val preferences: Flow<AppPreferences>
    suspend fun setFontSize(value: Float)
    suspend fun setFontFamily(value: String)
    suspend fun setLineSpacing(value: Float)
    suspend fun setTheme(value: ThemeMode)
    suspend fun setBackgroundColor(value: Int)
    suspend fun setParagraphSpacing(value: Float)
    suspend fun setParagraphIndent(value: Float)
    suspend fun setPageMargins(value: Float)
    suspend fun setScrollMode(value: Boolean)
    suspend fun setTtsRate(value: Float)
    suspend fun setTtsPitch(value: Float)
    suspend fun setTtsEngine(value: String?)
}
