package com.epubreader.app.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.epubreader.app.core.log.AppLogger
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.epubreader.app.EpubReaderApplication
import com.epubreader.app.core.tts.TtsBus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Media3 [MediaSessionService] hosting [TtsPlayer].
 *
 * **Notification Channel (NEVER #25, Oracle M5):**
 * Created AFTER `super.onCreate()` (Hilt field injection happens in
 * `Hilt_TtsPlaybackService.onCreate()`). NEVER #25 requires the channel
 * to exist before the service becomes foreground (startForeground), not
 * before super.onCreate(). This ordering satisfies both constraints.
 *
 * **Background start compliance (NEVER #16):**
 * Media3 handles this automatically — the service is started via
 * [MediaController] binding, and `startForeground()` is called by Media3
 * when playback begins. No direct `startForegroundService()` calls.
 *
 * **Self-stop (Oracle S7):**
 * When playback ends (STATE_ENDED) and no controllers are connected,
 * the service calls `stopSelf()` to remove the lingering notification.
 *
 * **TtsBus reset (Oracle S9):**
 * On [onCreate], TtsBus is reset to clear stale state from a prior session
 * (process death recovery).
 */
@AndroidEntryPoint
class TtsPlaybackService : MediaSessionService() {

    private val tag = "TtsPlaybackService"

    @Inject
    lateinit var ttsBus: TtsBus

    private var mediaSession: MediaSession? = null
    private var ttsPlayer: TtsPlayer? = null
    private var ttsEngine: AndroidTtsEngine? = null

    override fun onCreate() {
        super.onCreate() // Hilt injection happens here (Oracle M5)

        // Oracle S9: reset TtsBus to clear stale state from prior session
        ttsBus.clear()

        // NEVER #25: create Notification Channel before MediaSession build
        createNotificationChannel()

        // Create TTS engine + player
        val engine = AndroidTtsEngine(this)
        ttsEngine = engine
        val player = TtsPlayer(this, ttsBus, engine)
        ttsPlayer = player

        // Configure notification provider with our channel
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(EpubReaderApplication.TTS_CHANNEL_ID)
            .setChannelName(com.epubreader.app.R.string.tts_notification_channel_name)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        notificationProvider.setSmallIcon(com.epubreader.app.R.mipmap.ic_launcher)

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    packageManager.getLaunchIntentForPackage(packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        // Attach the custom notification provider so the channel matches TTS_CHANNEL_ID
        setMediaNotificationProvider(notificationProvider)

        // Observe player state for self-stop (Oracle S7)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    AppLogger.i(tag, "Playback ended — checking for self-stop")
                    maybeStopSelf()
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLogger.i(tag, "onDestroy — releasing session")
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        ttsPlayer = null
        ttsEngine = null
        super.onDestroy()
    }

    /**
     * Oracle S7: stop the service when playback ended and no controllers connected.
     * Prevents a lingering foreground notification after TTS finishes.
     */
    private fun maybeStopSelf() {
        val session = mediaSession ?: return
        val connectedControllers = session.connectedControllers
        if (connectedControllers.isEmpty()) {
            AppLogger.i(tag, "No controllers connected and playback ended — stopping self")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EpubReaderApplication.TTS_CHANNEL_ID,
                getString(com.epubreader.app.R.string.tts_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(com.epubreader.app.R.string.tts_notification_channel_desc)
                setShowBadge(false)
            }
            val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            AppLogger.d(tag, "Notification channel created: ${EpubReaderApplication.TTS_CHANNEL_ID}")
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
