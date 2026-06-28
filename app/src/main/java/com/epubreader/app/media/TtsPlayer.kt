package com.epubreader.app.media

import android.content.Context
import android.os.Build
import android.os.Looper
import com.epubreader.app.core.log.AppLogger
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.epubreader.app.core.tts.TtsBus
import com.epubreader.app.core.tts.TtsEngine
import com.epubreader.app.core.tts.TtsEngineState
import com.epubreader.app.core.tts.TtsPlaybackState
import com.epubreader.app.core.tts.TtsSentence
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Media3 [SimpleBasePlayer] that drives TTS playback (Council M9: 1 MediaItem = 1 chapter).
 *
 * **Architecture:**
 * - The Media3 timeline contains **chapter-level** MediaItems (not sentences).
 *   This prevents notification rebuild storms (Council Top Risk #1).
 * - Sentence progress is tracked internally via [currentSentenceIndex] and
 *   exposed through [TtsBus] (not through MediaItem transitions).
 * - [TtsBus.sentences] is read on demand in [handleSetMediaItems] (Oracle M1:
 *   single control path — no StateFlow collection that would race with
 *   MediaController commands).
 *
 * **Audio focus (NEVER #11, Council #7):**
 * - [AudioFocusManager] is used directly (SimpleBasePlayer doesn't auto-handle
 *   focus like ExoPlayer's setHandleAudioFocus).
 * - AudioAttributes: USAGE_MEDIA + CONTENT_TYPE_SPEECH → pause on transient
 *   loss (not duck), correct for speech content.
 *
 * **Seek handling (Architect 4.1):**
 * - contentPositionMs = currentSentenceIndex * SENTENCE_DURATION_MS
 * - contentDurationMs = sentenceCount * SENTENCE_DURATION_MS
 * - handleSeek maps positionMs back to sentenceIndex.
 *
 * @OptIn(UnstableApi) required for all SimpleBasePlayer methods.
 */
@OptIn(UnstableApi::class)
class TtsPlayer(
    private val context: Context,
    private val ttsBus: TtsBus,
    private val ttsEngine: TtsEngine,
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper), TtsEngine.Callbacks {

    private val tag = "TtsPlayer"

    private var currentSentenceIndex = -1
    private var chapterTitle: String = ""
    private var bookTitle: String = ""

    // Tracked locally so getState() can build State without reading the
    // inherited `state` property (which resolves to the overridden getState()
    // and would recurse infinitely — see getState()).
    private var playWhenReady = false

    // Audio focus (NEVER #11) — manual AudioManager approach for Media3 1.10.1
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val audioFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                AppLogger.d(tag, "Audio focus lost permanently — pausing")
                pausePlayback()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLogger.d(tag, "Audio focus transient loss — pausing")
                pausePlayback()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // For speech: pause instead of duck
                AppLogger.d(tag, "Audio focus can-duck — pausing (speech content)")
                pausePlayback()
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                AppLogger.d(tag, "Audio focus regained — resuming")
                resumePlayback()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
            result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    // M2: Dedicated scope for observing engine state transitions
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        ttsEngine.setCallbacks(this)

        // M2: Observe engine state — resume playback when engine becomes Ready
        playerScope.launch {
            ttsEngine.state.collect { engineState ->
                // Sync engine state to TtsBus so the UI layer can observe errors/language-missing
                ttsBus.setEngineState(engineState)

                if (engineState is TtsEngineState.Ready && playWhenReady) {
                    val sentences = ttsBus.sentences.value
                    if (sentences.isNotEmpty() && currentSentenceIndex >= 0) {
                        val hasFocus = requestAudioFocus()
                        if (!hasFocus) {
                            AppLogger.w(tag, "Audio focus denied on engine-ready resume — cannot start playback")
                            return@collect
                        }
                        speakCurrentSentence()
                        invalidateState()
                    }
                }
            }
        }
    }

    // -- SimpleBasePlayer overrides --

    override fun getState(): State {
        val sentences = ttsBus.sentences.value
        val sentenceCount = sentences.size
        val positionMs = if (currentSentenceIndex >= 0) {
            currentSentenceIndex * TtsPlaybackState_SENTENCE_MS
        } else {
            0L
        }

        val playlist = if (sentenceCount > 0) {
            listOf<MediaItemData>(
                MediaItemData.Builder("tts_chapter")
                    .setMediaItem(
                        androidx.media3.common.MediaItem.Builder()
                            .setMediaId("tts_chapter")
                            .build()
                    )
                    .build()
            )
        } else {
            emptyList<MediaItemData>()
        }

        // Media3 contract: an empty playlist is only allowed in STATE_IDLE or
        // STATE_ENDED, so force STATE_IDLE when no sentences are loaded.
        val playbackState = when {
            sentenceCount == 0 -> STATE_IDLE
            currentSentenceIndex < 0 && !playWhenReady -> STATE_IDLE
            currentSentenceIndex >= sentenceCount -> STATE_ENDED
            playWhenReady && ttsEngine.state.value is TtsEngineState.Ready -> STATE_READY
            ttsEngine.state.value is TtsEngineState.Initializing -> STATE_BUFFERING
            else -> STATE_IDLE
        }

        // Build from a fresh State.Builder() — do NOT call state.buildUpon(),
        // because `state` resolves to this overridden getState() (virtual
        // dispatch) and would recurse until StackOverflowError.
        return State.Builder()
            .setAvailableCommands(buildAvailableCommands())
            .setPlayWhenReady(
                playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(playbackState)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionMs)
            .build()
    }

    private fun buildAvailableCommands(): Player.Commands =
        Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_RELEASE
            )
            .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            startPlayback()
        } else {
            pausePlayback()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        stopPlayback()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<androidx.media3.common.MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        // Oracle M1: read sentences from TtsBus on demand (not StateFlow collection)
        val sentences = ttsBus.sentences.value
        currentSentenceIndex = if (startPositionMs > 0 && sentences.isNotEmpty()) {
            (startPositionMs / TtsPlaybackState_SENTENCE_MS).toInt()
                .coerceIn(0, sentences.lastIndex)
        } else {
            0
        }
        AppLogger.i(tag, "handleSetMediaItems: ${sentences.size} sentences, startIdx=$currentSentenceIndex")
        ttsBus.setCurrentSentenceIndex(currentSentenceIndex)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val sentences = ttsBus.sentences.value
        if (sentences.isEmpty()) return Futures.immediateVoidFuture()

        val targetIndex = (positionMs / TtsPlaybackState_SENTENCE_MS).toInt()
            .coerceIn(0, sentences.lastIndex)
        AppLogger.i(tag, "handleSeek: positionMs=$positionMs → sentenceIdx=$targetIndex")
        currentSentenceIndex = targetIndex
        ttsBus.setCurrentSentenceIndex(targetIndex)

        if (playWhenReady) {
            speakCurrentSentence()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        ttsEngine.setSpeechRate(playbackParameters.speed)
        ttsEngine.setPitch(playbackParameters.pitch)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        AppLogger.i(tag, "handleRelease — shutting down TTS")
        abandonAudioFocus()
        ttsEngine.shutdown()
        return Futures.immediateVoidFuture()
    }

    // -- TTS playback control --

    private fun startPlayback() {
        val engineState = ttsEngine.state.value
        AppLogger.d(tag, "startPlayback: engine=${engineState}, sentences=${ttsBus.sentences.value.size}, index=$currentSentenceIndex")
        if (engineState !is TtsEngineState.Ready) {
            AppLogger.w(tag, "startPlayback: engine not ready ($engineState) — initializing")
            ttsEngine.initialize()
            return // M2: will resume when engine becomes Ready (observed in init block)
        }

        val sentences = ttsBus.sentences.value
        if (sentences.isEmpty()) {
            AppLogger.w(tag, "startPlayback: no sentences loaded")
            return
        }

        // Audio focus (NEVER #11)
        val hasFocus = requestAudioFocus()
        if (!hasFocus) {
            AppLogger.w(tag, "Audio focus denied — cannot start playback")
            return
        }

        currentSentenceIndex = ttsBus.currentSentenceIndex.value
        if (currentSentenceIndex < 0) currentSentenceIndex = 0
        speakCurrentSentence()
        invalidateState()
    }

    private fun pausePlayback() {
        ttsEngine.stop()
        invalidateState()
    }

    private fun resumePlayback() {
        if (playWhenReady) {
            speakCurrentSentence()
            invalidateState()
        }
    }

    private fun stopPlayback() {
        ttsEngine.stop()
        currentSentenceIndex = -1
        ttsBus.setCurrentSentenceIndex(-1)
        abandonAudioFocus()
        invalidateState()
    }

    private fun speakCurrentSentence() {
        val sentences = ttsBus.sentences.value
        if (currentSentenceIndex < 0 || currentSentenceIndex >= sentences.size) {
            AppLogger.w(tag, "speakCurrentSentence: index $currentSentenceIndex out of range")
            return
        }
        val sentence = sentences[currentSentenceIndex]
        AppLogger.d(tag, "speakCurrentSentence: index=$currentSentenceIndex, text='${sentence.text.take(50)}'")
        ttsBus.setCurrentSentenceIndex(currentSentenceIndex)
        ttsEngine.speak(sentence.text, "sentence_$currentSentenceIndex")
        AppLogger.d(tag, "Speaking sentence $currentSentenceIndex/${sentences.size}")
    }

    // -- TtsEngine.Callbacks (Council M11: already on main thread via AndroidTtsEngine) --

    override fun onStart(utteranceId: String) {
        // Watchdog cancelled in AndroidTtsEngine; update state
        invalidateState()
    }

    override fun onDone(utteranceId: String) {
        val sentences = ttsBus.sentences.value
        val nextIndex = currentSentenceIndex + 1

        if (nextIndex < sentences.size) {
            // Next sentence in same chapter
            currentSentenceIndex = nextIndex
            ttsBus.setCurrentSentenceIndex(nextIndex)
            if (playWhenReady) {
                // Small delay to let Fragment's navigator.go() settle after the
                // sentence index update before starting the next utterance.
                // Prevents race between navigation and the next speak call.
                playerScope.launch {
                    delay(80L)
                    speakCurrentSentence()
                }
            }
            invalidateState()
        } else {
            // End of chapter (Council M9: chapter-level MediaItem ends)
            AppLogger.i(tag, "End of chapter reached")
            currentSentenceIndex = sentences.size // marks STATE_ENDED
            ttsBus.setCurrentSentenceIndex(-1)
            invalidateState()
            // ReaderVM observes STATE_ENDED via playbackState and handles chapter transition
        }
    }

    override fun onError(utteranceId: String) {
        AppLogger.e(tag, "TTS error on $utteranceId — pausing")
        pausePlayback()
    }

    // -- Public API for TtsControllerImpl --

    fun setChapterInfo(chapter: String, book: String) {
        chapterTitle = chapter
        bookTitle = book
    }

    companion object {
        // Architect 4.1: faked duration per sentence for progress bar
        private const val TtsPlaybackState_SENTENCE_MS = 5_000L
    }
}
