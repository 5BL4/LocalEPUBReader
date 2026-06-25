package com.epubreader.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp

/**
 * Application shell.
 *
 * Only hosts @HiltAndroidApp (S8). The global CoroutineExceptionHandler is NOT
 * registered here at the JVM/Application level; it is provided via CoroutineModule
 * and injected per viewModelScope.launch. Local try-catch in repositories is the
 * primary exception guard (NEVER #26); the handler is the backstop.
 *
 * Phase 6: Notification Channel created here as belt-and-suspenders (NEVER #25).
 * The primary creation is in [com.epubreader.app.media.TtsPlaybackService.onCreate]
 * after Hilt injection. This ensures the channel exists even if the service is
 * started via media button before the app process is fully initialized.
 */
@HiltAndroidApp
class EpubReaderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createTtsNotificationChannel()
    }

    private fun createTtsNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TTS_CHANNEL_ID,
                getString(R.string.tts_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.tts_notification_channel_desc)
                setShowBadge(false)
            }
            val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val TTS_CHANNEL_ID = "tts_playback_channel"
    }
}
