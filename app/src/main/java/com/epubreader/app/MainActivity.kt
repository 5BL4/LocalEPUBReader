package com.epubreader.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.data.prefs.PreferencesRepository
import com.epubreader.app.data.sync.SyncTrigger
import com.epubreader.app.ui.EpubReaderApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// FragmentActivity (not ComponentActivity) is required because ReaderScreen uses
// AndroidViewBinding + FragmentContainerView (android:name) + getFragment() to host
// ReaderHostFragment. FragmentManager.findFragmentManager() throws IllegalStateException
// ("View ... is not within a subclass of FragmentActivity") unless the Activity is a
// FragmentActivity. FragmentActivity extends ComponentActivity, so setContent /
// enableEdgeToEdge / @AndroidEntryPoint all continue to work. AppCompat theme is NOT
// required (FragmentActivity works with framework themes).
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var errorChannel: ErrorChannel

    @Inject
    lateinit var syncTrigger: SyncTrigger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(syncTrigger)
        setContent {
            EpubReaderApp(preferencesRepository, errorChannel)
        }
    }
}
