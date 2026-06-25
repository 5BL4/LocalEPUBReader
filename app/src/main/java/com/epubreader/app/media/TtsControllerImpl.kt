package com.epubreader.app.media

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.epubreader.app.core.tts.TtsBus
import com.epubreader.app.core.tts.TtsController
import com.epubreader.app.core.tts.TtsEngineState
import com.epubreader.app.core.tts.TtsPlaybackState
import com.epubreader.app.core.tts.TtsSentence
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TtsController] implementation managing the [MediaController] lifecycle.
 *
 * **Lifecycle (Architect M3):**
 * - [connect] builds MediaController async; queues play requests until connected (Oracle S8).
 * - [disconnect] releases MediaController but does NOT stop playback — the service
 *   continues in background via foreground notification.
 * - [stop] stops playback + clears TtsBus (called on explicit stop / book switch / end of book).
 *
 * **State derivation (Oracle S1):**
 * - [playbackState] is derived from [Player.Listener] callbacks, NOT from TtsBus.
 * - [engineState], [currentSentenceIndex], [generationId] are forwarded from TtsBus.
 *
 * **NEVER #2:** Injects [ApplicationContext] for SessionToken — ReaderVM injects
 * the [TtsController] interface, never Context.
 */
@Singleton
class TtsControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsBus: TtsBus
) : TtsController {

    private val tag = "TtsControllerImpl"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Oracle S1: playbackState derived from Player.Listener, not TtsBus
    private val _playbackState: MutableStateFlow<TtsPlaybackState> = MutableStateFlow(TtsPlaybackState.Idle)
    override val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    // Forwarded from TtsBus
    override val engineState: StateFlow<TtsEngineState> = ttsBus.engineState
    override val currentSentenceIndex: StateFlow<Int> = ttsBus.currentSentenceIndex
    override val generationId: StateFlow<String> = ttsBus.generationId

    // Oracle S8: pending play request queued during async connect
    private var pendingPlay: PendingPlayRequest? = null
    private var pendingRate: Float = 1.0f
    private var pendingPitch: Float = 1.0f

    override fun connect(ttsRate: Float, ttsPitch: Float) {
        if (_isConnected.value || controllerFuture != null) {
            Log.d(tag, "connect() called but already connected/connecting — skipping")
            return
        }

        pendingRate = ttsRate
        pendingPitch = ttsPitch

        val sessionToken = SessionToken(
            context,
            ComponentName(context, TtsPlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken)
            .buildAsync()
            .also { future ->
                future.addListener({
                    try {
                        val controller = future.get()
                        mediaController = controller
                        _isConnected.value = true
                        attachListener(controller)
                        Log.i(tag, "MediaController connected")

                        // Oracle S4: apply saved speed/pitch
                        controller.setPlaybackParameters(
                            PlaybackParameters(ttsRate, ttsPitch)
                        )

                        // Oracle S8: replay pending play request
                        pendingPlay?.let { req ->
                            pendingPlay = null
                            play(req.sentences, req.chapterTitle, req.bookTitle)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to connect MediaController", e)
                        _isConnected.value = false
                    }
                }, ContextCompat_mainExecutor(context))
            }
    }

    private fun attachListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playerState: Int) {
                _playbackState.value = when (playerState) {
                    Player.STATE_IDLE -> TtsPlaybackState.Idle
                    Player.STATE_BUFFERING -> TtsPlaybackState.Paused
                    Player.STATE_READY -> {
                        if (controller.playWhenReady) TtsPlaybackState.Playing
                        else TtsPlaybackState.Paused
                    }
                    Player.STATE_ENDED -> TtsPlaybackState.Ended
                    else -> TtsPlaybackState.Idle
                }
                Log.d(tag, "Playback state → ${_playbackState.value}")
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (controller.playbackState == Player.STATE_READY) {
                    _playbackState.value = if (playWhenReady) TtsPlaybackState.Playing
                    else TtsPlaybackState.Paused
                }
            }

            override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
                if (error != null) {
                    Log.e(tag, "Player error", error)
                    _playbackState.value = TtsPlaybackState.Idle
                }
            }
        })
    }

    override fun play(sentences: List<TtsSentence>, chapterTitle: String, bookTitle: String) {
        val controller = mediaController
        if (controller == null) {
            // Oracle S8: queue the request
            Log.w(tag, "play() called before connected — queuing request")
            pendingPlay = PendingPlayRequest(sentences, chapterTitle, bookTitle)
            return
        }

        // Oracle M1: write sentences to TtsBus BEFORE setMediaItems (synchronous, same-process)
        val genId = java.util.UUID.randomUUID().toString()
        ttsBus.setSentences(sentences)
        ttsBus.setGenerationId(genId)
        ttsBus.setCurrentSentenceIndex(-1)

        // Council M9: 1 MediaItem = 1 chapter
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId("tts_chapter")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(chapterTitle)
                    .setArtist(bookTitle)
                    .build()
            )
            .build()

        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
        Log.i(tag, "play() — ${sentences.size} sentences, chapter='$chapterTitle'")
    }

    override fun pause() {
        mediaController?.pause() ?: Log.w(tag, "pause() called but not connected — dropping")
    }

    override fun stop() {
        val controller = mediaController
        if (controller != null) {
            controller.stop()
            controller.clearMediaItems()
        }
        ttsBus.clear()
        _playbackState.value = TtsPlaybackState.Idle
        Log.i(tag, "stop() — TtsBus cleared, playback stopped")
    }

    override fun setSpeed(rate: Float) {
        pendingRate = rate
        // M3: Use PlaybackParameters to trigger handleSetPlaybackParameters on TtsPlayer
        mediaController?.setPlaybackParameters(
            PlaybackParameters(rate, pendingPitch)
        )
    }

    override fun setPitch(pitch: Float) {
        pendingPitch = pitch
        // M3: Route through MediaController — TtsPlayer.handleSetPlaybackParameters applies both
        // speed and pitch to the TtsEngine. We set speed to current value to keep it unchanged.
        mediaController?.setPlaybackParameters(
            PlaybackParameters(pendingRate, pitch)
        )
    }

    override fun seekToSentence(index: Int) {
        val controller = mediaController ?: run {
            Log.w(tag, "seekToSentence() called but not connected — dropping")
            return
        }
        controller.seekTo(index * TtsController.SENTENCE_DURATION_MS)
    }

    override fun disconnect() {
        // Architect M3: release MediaController but do NOT stop playback
        val controller = mediaController
        mediaController = null
        _isConnected.value = false

        controller?.let { ctrl ->
            ctrl.release()
        }
        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        controllerFuture = null
        Log.i(tag, "disconnect() — MediaController released (playback continues in background)")
    }

    private data class PendingPlayRequest(
        val sentences: List<TtsSentence>,
        val chapterTitle: String,
        val bookTitle: String
    )

    companion object {
        // Helper to avoid import issues in listener
        private fun ContextCompat_mainExecutor(context: Context): java.util.concurrent.Executor =
            androidx.core.content.ContextCompat.getMainExecutor(context)
    }
}
