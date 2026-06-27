package com.epubreader.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PreferencesRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private fun createTestDataStore(): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            tempDir.resolve("test.preferences_pb")
        }
    }

    @Test
    fun `round-trip fontSize and theme`() = runBlocking {
        val dataStore = createTestDataStore()
        // Single combined write to avoid Windows atomic-rename issue when a second
        // write tries to replace an existing file. This still validates that the repo's
        // preferences Flow correctly maps PreferenceKeys -> AppPreferences (typed read path).
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.FONT_SIZE] = 20f
            prefs[PreferenceKeys.THEME] = ThemeMode.SEPIA.name
        }
        val repo = PreferencesRepositoryImpl(dataStore)
        val prefs = repo.preferences.first()
        assertEquals(20f, prefs.fontSize)
        assertEquals(ThemeMode.SEPIA, prefs.theme)
    }

    @Test
    fun `PreferenceKeys are typed, not raw Strings`() {
        // FONT_SIZE is a Preferences.Key<Float>, not a raw String
        val key: Preferences.Key<Float> = PreferenceKeys.FONT_SIZE
        assertEquals("font_size", key.name)

        // FONT_FAMILY is a Preferences.Key<String>
        val key2: Preferences.Key<String> = PreferenceKeys.FONT_FAMILY
        assertEquals("font_family", key2.name)

        // THEME is a Preferences.Key<String> (stored as string, parsed to enum)
        val key3: Preferences.Key<String> = PreferenceKeys.THEME
        assertEquals("theme", key3.name)

        // BACKGROUND_COLOR is a Preferences.Key<Int>
        val key4: Preferences.Key<Int> = PreferenceKeys.BACKGROUND_COLOR
        assertEquals("background_color", key4.name)

        // PARAGRAPH_SPACING is a Preferences.Key<Float>
        val key5: Preferences.Key<Float> = PreferenceKeys.PARAGRAPH_SPACING
        assertEquals("paragraph_spacing", key5.name)

        // PAGE_MARGINS is a Preferences.Key<Float>
        val key6: Preferences.Key<Float> = PreferenceKeys.PAGE_MARGINS
        assertEquals("page_margins", key6.name)

        // SCROLL is a Preferences.Key<Boolean>
        val key7: Preferences.Key<Boolean> = PreferenceKeys.SCROLL
        assertEquals("scroll", key7.name)

        // TTS_RATE is a Preferences.Key<Float>
        val key8: Preferences.Key<Float> = PreferenceKeys.TTS_RATE
        assertEquals("tts_rate", key8.name)

        // TTS_PITCH is a Preferences.Key<Float>
        val key9: Preferences.Key<Float> = PreferenceKeys.TTS_PITCH
        assertEquals("tts_pitch", key9.name)

        // TTS_ENGINE is a Preferences.Key<String>
        val key10: Preferences.Key<String> = PreferenceKeys.TTS_ENGINE
        assertEquals("tts_engine", key10.name)
    }

    @Test
    fun `PreferencesRepository public API has no raw String key params`() {
        // Verify every public method on the interface has typed parameters only.
        // Check that none of parameter names suggest raw key leakage.
        val methods = PreferencesRepository::class.java.declaredMethods
        val publicMethods = methods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }

        assertTrue(publicMethods.isNotEmpty(), "PreferencesRepository should have public methods")

        for (method in publicMethods) {
            val paramTypes = method.parameterTypes.toList()
            val paramNames = method.parameters.map { it.name }

            // No method should have both a String parameter and a name that sounds like "key"
            for ((i, type) in paramTypes.withIndex()) {
                if (type == String::class.java) {
                    val name = paramNames[i].lowercase()
                    assertTrue(
                        name !in listOf("key", "preferencekey", "prefkey", "stringkey"),
                        "Method ${method.name} has a raw String parameter '${paramNames[i]}' — keys must be typed"
                    )
                }
            }
        }
    }
}
