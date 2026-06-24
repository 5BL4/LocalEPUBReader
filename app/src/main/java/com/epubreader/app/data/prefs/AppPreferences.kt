package com.epubreader.app.data.prefs

import androidx.compose.runtime.Immutable

@Immutable
data class AppPreferences(
    val fontSize: Float = 16f,
    val fontFamily: String = "sans-serif",
    val lineSpacing: Float = 1.4f,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val autoPageIntervalMs: Long = 5000L,
    val autoScrollSpeed: Float = 1.0f,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsEngine: String? = null
)
