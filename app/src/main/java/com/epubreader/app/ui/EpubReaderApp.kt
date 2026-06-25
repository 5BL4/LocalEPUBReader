package com.epubreader.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.data.prefs.AppPreferences
import com.epubreader.app.data.prefs.PreferencesRepository
import com.epubreader.app.navigation.EpubReaderNavHost
import com.epubreader.app.ui.theme.EpubReaderTheme

@Composable
fun EpubReaderApp(
    preferencesRepository: PreferencesRepository,
    errorChannel: ErrorChannel
) {
    val prefs by preferencesRepository.preferences
        .collectAsStateWithLifecycle(initialValue = AppPreferences())

    val snackbarHostState = remember { SnackbarHostState() }

    val error by errorChannel.errors.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it.message) }
    }

    EpubReaderTheme(themeMode = prefs.theme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            EpubReaderNavHost()
        }
    }
}
