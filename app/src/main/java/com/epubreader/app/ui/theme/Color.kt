package com.epubreader.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Light Material3 palette
private val LightPrimary = Color(0xFF1F6FEB)
private val LightOnPrimary = Color.White
private val LightPrimaryContainer = Color(0xFFD6E3FF)
private val LightOnPrimaryContainer = Color(0xFF001B3E)
private val LightSecondary = Color(0xFF565F71)
private val LightOnSecondary = Color.White
private val LightSecondaryContainer = Color(0xFFDAE2F9)
private val LightOnSecondaryContainer = Color(0xFF131C2B)
private val LightSurface = Color(0xFFFFFBFE)
private val LightOnSurface = Color(0xFF1C1B1F)
private val LightBackground = Color(0xFFFFFBFE)
private val LightOnBackground = Color(0xFF1C1B1F)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color.White

// Dark Material3 palette
private val DarkPrimary = Color(0xFF7B9CFF)
private val DarkOnPrimary = Color.Black
private val DarkPrimaryContainer = Color(0xFF0043A0)
private val DarkOnPrimaryContainer = Color(0xFFD6E3FF)
private val DarkSecondary = Color(0xFFBEC6DC)
private val DarkOnSecondary = Color(0xFF283041)
private val DarkSecondaryContainer = Color(0xFF3E4759)
private val DarkOnSecondaryContainer = Color(0xFFDAE2F9)
private val DarkSurface = Color(0xFF1C1B1F)
private val DarkOnSurface = Color(0xFFE6E1E5)
private val DarkBackground = Color(0xFF1C1B1F)
private val DarkOnBackground = Color(0xFFE6E1E5)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)

// Sepia palette — warm, paper-like tones
private val SepiaPrimary = Color(0xFF8B6914)
private val SepiaOnPrimary = Color.White
private val SepiaPrimaryContainer = Color(0xFFFFDEA2)
private val SepiaOnPrimaryContainer = Color(0xFF2C1F00)
private val SepiaSecondary = Color(0xFF6B5E40)
private val SepiaOnSecondary = Color.White
private val SepiaSecondaryContainer = Color(0xFFF5E2BB)
private val SepiaOnSecondaryContainer = Color(0xFF241B05)
private val SepiaSurface = Color(0xFFF4ECD8)
private val SepiaOnSurface = Color(0xFF5B4636)
private val SepiaBackground = Color(0xFFF4ECD8)
private val SepiaOnBackground = Color(0xFF5B4636)
private val SepiaError = Color(0xFFBA1A1A)
private val SepiaOnError = Color.White

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    surface = LightSurface,
    onSurface = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnBackground,
    error = LightError,
    onError = LightOnError,
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    error = DarkError,
    onError = DarkOnError,
)

val SepiaColorScheme = lightColorScheme(
    primary = SepiaPrimary,
    onPrimary = SepiaOnPrimary,
    primaryContainer = SepiaPrimaryContainer,
    onPrimaryContainer = SepiaOnPrimaryContainer,
    secondary = SepiaSecondary,
    onSecondary = SepiaOnSecondary,
    secondaryContainer = SepiaSecondaryContainer,
    onSecondaryContainer = SepiaOnSecondaryContainer,
    surface = SepiaSurface,
    onSurface = SepiaOnSurface,
    background = SepiaBackground,
    onBackground = SepiaOnBackground,
    error = SepiaError,
    onError = SepiaOnError,
)
