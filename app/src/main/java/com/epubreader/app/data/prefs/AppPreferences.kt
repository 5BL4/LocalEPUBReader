package com.epubreader.app.data.prefs

import androidx.compose.runtime.Immutable

@Immutable
data class AppPreferences(
    val fontSize: Float = 16f,
    val fontFamily: String = "sans-serif",
    val lineSpacing: Float = 1.4f,
    val theme: ThemeMode = ThemeMode.SEPIA,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val paragraphSpacing: Float = 0f,
    val paragraphIndent: Float = 0f,
    val pageMargins: Float = 1f,
    val scroll: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsEngine: String? = null
)
