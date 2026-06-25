package com.epubreader.app.core.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over [android.speech.tts.TextToSpeech], extracted for testability
 * (Oracle S2). The concrete [AndroidTtsEngine] lives in the media layer and
 * wraps the Android TTS API.
 *
 * NOT Hilt-injected (TextToSpeech is Context-bound and service-scoped);
 * [com.epubreader.app.media.TtsPlayer] constructs it directly. Tests inject
 * a [FakeTtsEngine].
 *
 * Thread safety (Council M11):
 * All [UtteranceProgressListener] callbacks in the implementation MUST switch
 * to `Dispatchers.Main.immediate` before updating [state] or calling callbacks,
 * because the native TTS engine invokes them on a Binder background thread.
 */
interface TtsEngine {

    /** Current engine state (NEVER #28: speak() only when Ready). */
    val state: StateFlow<TtsEngineState>

    /**
     * Registers callbacks for utterance progress events.
     * The [TtsPlayer] sets itself as the callback target during initialization.
     */
    fun setCallbacks(callbacks: Callbacks)

    /**
     * Asynchronously initializes the TTS engine.
     * Sets state to [TtsEngineState.Initializing], then transitions to
     * [TtsEngineState.Ready] / [TtsEngineState.LanguageMissing] /
     * [TtsEngineState.Error] once [OnInitListener] fires.
     *
     * Idempotent: if already initialized, no-ops.
     */
    fun initialize()

    /**
     * Speaks a single sentence. The implementation MUST:
     * - Check [state] is [TtsEngineState.Ready] before calling TTS speak()
     *   (NEVER #28).
     * - Start the watchdog timer (Council M13): if [onStart] is not received
     *   within [WATCHDOG_TIMEOUT_MS], transition to [TtsEngineState.Error]
     *   and force re-initialization.
     * - Use [utteranceId] to correlate [UtteranceProgressListener] callbacks.
     *
     * If the engine is not Ready, the call is silently dropped (logged).
     *
     * @param text The sentence text to speak.
     * @param utteranceId Unique ID for this utterance (format: "sentence_$index").
     */
    fun speak(text: String, utteranceId: String)

    /** Stops current utterance and clears the TTS queue. */
    fun stop()

    /** Sets speech rate (1.0 = normal). Applies to the NEXT utterance. */
    fun setSpeechRate(rate: Float)

    /** Sets pitch (1.0 = normal). Applies to the NEXT utterance. */
    fun setPitch(pitch: Float)

    /**
     * Releases native TTS resources. Called by TtsPlayer.handleRelease().
     * After shutdown, [initialize] must be called again before [speak].
     */
    fun shutdown()

    /**
     * Callbacks for utterance progress events.
     * Set by [com.epubreader.app.media.TtsPlayer] to track sentence completion.
     *
     * All methods are invoked on the main thread (implementation switches
     * from the TTS Binder thread — Council M11).
     */
    interface Callbacks {
        /** Called when an utterance starts playing. [utteranceId] = "sentence_$index". */
        fun onStart(utteranceId: String)

        /** Called when an utterance completes. Advance to next sentence. */
        fun onDone(utteranceId: String)

        /** Called on TTS error. Implementation should transition to Error state. */
        fun onError(utteranceId: String)
    }

    companion object {
        /** Watchdog timeout (Council M13): if onStart not received within 2s, force reinit. */
        const val WATCHDOG_TIMEOUT_MS = 2_000L

        /** Max characters per utterance (Council S11): chunk long sentences. */
        const val MAX_UTTERANCE_LENGTH = 800
    }
}
