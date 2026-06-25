package com.epubreader.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PreferencesRepositorySetterTest {

    @TempDir
    lateinit var tempDir: File

    private fun createRepo(): PreferencesRepositoryImpl {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempDir.resolve("setter_test.preferences_pb")
        }
        return PreferencesRepositoryImpl(dataStore)
    }

    @Test
    fun `setter round-trip fontSize`() = runBlocking {
        val repo = createRepo()
        repo.setFontSize(24f)
        val prefs = repo.preferences.first()
        assertEquals(24f, prefs.fontSize)
    }

    @Test
    fun `setter round-trip theme`() = runBlocking {
        val repo = createRepo()
        repo.setTheme(ThemeMode.DARK)
        val prefs = repo.preferences.first()
        assertEquals(ThemeMode.DARK, prefs.theme)
    }

    @Test
    fun `setTtsEngine value persists`() = runBlocking {
        val repo = createRepo()
        repo.setTtsEngine("com.google.android.tts")
        val prefs = repo.preferences.first()
        assertEquals("com.google.android.tts", prefs.ttsEngine)
    }

    @Test
    fun `fresh repo defaults to null ttsEngine`() = runBlocking {
        val repo = createRepo()
        // Without any setter call, ttsEngine should be null (default in AppPreferences)
        val prefs = repo.preferences.first()
        assertNull(prefs.ttsEngine)
    }
}
