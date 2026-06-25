package com.epubreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.data.prefs.PreferencesRepository
import com.epubreader.app.ui.EpubReaderApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var errorChannel: ErrorChannel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EpubReaderApp(preferencesRepository, errorChannel)
        }
    }
}
