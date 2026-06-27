package com.epubreader.app.core.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge between the UI layer ([com.epubreader.app.ui.reader.ReaderViewModel])
 * and the Media3 service layer ([com.epubreader.app.media.TtsPlaybackService]).
 *
 * **Architecture (Oracle D5, NEVER #2):**
 * - Interface lives in `core/tts/` (pure Kotlin, no Android types).
 * - Implementation [com.epubreader.app.media.TtsControllerImpl] lives in
 *   `media/` and injects `@ApplicationContext` for [androidx.media3.session.SessionToken].
 * - ReaderViewModel injects this interface — never Context (NEVER #2).
 *
 * **Lifecycle (Architect M3 correction):**
 * - [connect] is called in ReaderVM `init {}`.
 * - [disconnect] is called in ReaderVM `onCleared()` — this releases the
 *   MediaController binding but does NOT stop playback. The service continues
 *   in the background via its foreground notification.
 * - [stop] is called only on: explicit user stop, book switch, or end of book.
 *
 * **Not-connected contract (Oracle S8):**
 * [MediaController.buildAsync] is asynchronous. If [play] is called before
 * connection completes, the request is queued and replayed on connect.
 * Other commands (pause/stop/seek) are dropped with a log when not connected.
 */
interface TtsController {

    /** TTS engine state (from [TtsBus]). */
    val engineState: StateFlow<TtsEngineState>

    /**
     * Playback state derived from Media3 Player listeners (Oracle S1).
     * NOT from TtsBus — avoids dual-source-of-truth.
     */
    val playbackState: StateFlow<TtsPlaybackState>

    /** Current sentence index (from [TtsBus]). -1 = not playing. */
    val currentSentenceIndex: StateFlow<Int>

    /** Current generation ID (from [TtsBus]). For stale-highlight guard. */
    val generationId: StateFlow<String>

    /** Whether the MediaController is connected to the service. */
    val isConnected: StateFlow<Boolean>

    /**
     * Connects to [TtsPlaybackService] via [MediaController].
     * Idempotent: safe to call multiple times (config change rebind).
     * Initializes speed/pitch from saved preferences (Oracle S4).
     *
     * @param ttsRate Initial speech rate from preferences.
     * @param ttsPitch Initial pitch from preferences.
     */
    fun connect(ttsRate: Float = 1.0f, ttsPitch: Float = 1.0f)

    /**
     * Starts playback. Writes sentences to [TtsBus] (synchronous, same-process),
     * then calls MediaController.setMediaItems + play.
     *
     * @param sentences Sentence list for the current chapter (with Locators).
     * @param chapterTitle Chapter title for the notification.
     * @param bookTitle Book title for the notification.
     * @param startIndex Zero-based index of the first sentence to speak. Defaults to 0
     *   (beginning of chapter). When starting TTS from the user's current viewport, this
     *   is set to the sentence nearest the visible page (Fix A).
     */
    fun play(sentences: List<TtsSentence>, chapterTitle: String, bookTitle: String, startIndex: Int = 0)

    /** Pauses playback. */
    fun pause()

    /**
     * Resumes playback without re-extracting sentences.
     * Distinct from [play] — does NOT write to TtsBus or set media items.
     * Just calls MediaController.play() if connected.
     */
    fun resume()

    /**
     * Stops playback and clears TtsBus (sentences, generationId, etc.).
     * Called on: explicit user stop, book switch, end of book.
     * The service will stopSelf if no controllers are connected (Oracle S7).
     */
    fun stop()

    /** Sets speech rate. Applies to the next utterance. */
    fun setSpeed(rate: Float)

    /** Sets pitch. Applies to the next utterance. */
    fun setPitch(pitch: Float)

    /**
     * Seeks to a specific sentence index within the current chapter.
     * Maps to Player.seekTo(index * SENTENCE_DURATION_MS) (Architect 4.1).
     */
    fun seekToSentence(index: Int)

    /**
     * Releases the MediaController binding. Does NOT stop playback.
     * The service continues in background (Architect M3).
     */
    fun disconnect()

    companion object {
        /** Faked duration per sentence for notification progress bar (Architect 4.1, S13). */
        const val SENTENCE_DURATION_MS = 5_000L
    }
}
