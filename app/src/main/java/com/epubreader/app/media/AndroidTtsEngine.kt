package com.epubreader.app.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.epubreader.app.core.tts.TtsEngine
import com.epubreader.app.core.tts.TtsEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Concrete [TtsEngine] wrapping [TextToSpeech].
 *
 * **Key safety mechanisms:**
 *
 * 1. **State machine (NEVER #28):** [speak] is a no-op unless [state] is
 *    [TtsEngineState.Ready]. The [OnInitListener] must return SUCCESS and
 *    [isLanguageAvailable] must confirm language data before transitioning
 *    to Ready.
 *
 * 2. **Watchdog timer (Council M13):** Native TTS engines can silently crash
 *    — `speak()` returns SUCCESS but `onStart` never fires. After each
 *    `speak()`, a [WATCHDOG_TIMEOUT_MS] timer starts. If `onStart` is not
 *    received in time, the engine transitions to [TtsEngineState.Error],
 *    shuts down, and re-initializes.
 *
 * 3. **Thread safety (Council M11):** [UtteranceProgressListener] callbacks
 *    fire on a TTS Binder background thread. All state updates and callback
 *    invocations are dispatched to [Dispatchers.Main.immediate] via [scope].
 *
 * 4. **Long utterance chunking (Council S11):** Sentences exceeding
 *    [MAX_UTTERANCE_LENGTH] are split into sequential chunks to avoid
 *    engine truncation.
 *
 * NOT Hilt-injected — constructed by [TtsPlayer] in the service scope.
 */
class AndroidTtsEngine(
    private val context: Context,
    private val enginePackage: String? = null
) : TtsEngine {

    private val tag = "AndroidTtsEngine"

    private val _state = MutableStateFlow<TtsEngineState>(TtsEngineState.Uninitialized)
    override val state: StateFlow<TtsEngineState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var callbacks: TtsEngine.Callbacks? = null
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    /** Watchdog job — started on speak(), cancelled on onStart(). */
    private var watchdogJob: Job? = null

    /** Scope for watchdog + main-thread dispatch (Council M11). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun setCallbacks(callbacks: TtsEngine.Callbacks) {
        this.callbacks = callbacks
    }

    override fun initialize() {
        if (_state.value is TtsEngineState.Initializing ||
            _state.value is TtsEngineState.Ready
        ) {
            Log.d(tag, "initialize() called but already initializing/ready — skipping")
            return
        }

        _state.value = TtsEngineState.Initializing
        Log.i(tag, "Initializing TTS engine${enginePackage?.let { " ($it)" } ?: ""}")

        tts?.shutdown()
        tts = if (enginePackage != null) {
            TextToSpeech(context, initListener, enginePackage)
        } else {
            TextToSpeech(context, initListener)
        }
    }

    private val initListener = TextToSpeech.OnInitListener { status ->
        scope.launch {
            if (status == TextToSpeech.SUCCESS) {
                checkLanguageSupport()
            } else {
                Log.e(tag, "TTS init failed: status=$status")
                _state.value = TtsEngineState.Error("TTS initialization failed: $status")
            }
        }
    }

    private fun checkLanguageSupport() {
        val tts = this.tts ?: run {
            _state.value = TtsEngineState.Error("TTS engine is null after init")
            return
        }

        val locale = Locale.getDefault()
        val langResult = tts.isLanguageAvailable(locale)
        Log.i(tag, "Language check for $locale: result=$langResult")

        when (langResult) {
            TextToSpeech.LANG_MISSING_DATA -> {
                _state.value = TtsEngineState.LanguageMissing(locale.toLanguageTag())
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                // Try English as fallback
                val fallback = Locale.ENGLISH
                val fallbackResult = tts.isLanguageAvailable(fallback)
                if (fallbackResult >= TextToSpeech.LANG_AVAILABLE) {
                    tts.language = fallback
                    applyParams()
                    _state.value = TtsEngineState.Ready
                    Log.i(tag, "Using fallback language: $fallback")
                } else {
                    _state.value = TtsEngineState.LanguageMissing(locale.toLanguageTag())
                }
            }
            else -> {
                // LANG_AVAILABLE, LANG_COUNTRY_AVAILABLE, LANG_COUNTRY_VAR_AVAILABLE
                tts.language = locale
                applyParams()
                _state.value = TtsEngineState.Ready
                Log.i(tag, "TTS engine ready, language: $locale")
            }
        }

        tts.setOnUtteranceProgressListener(utteranceListener)
    }

    override fun speak(text: String, utteranceId: String) {
        val currentState = _state.value
        if (currentState !is TtsEngineState.Ready) {
            Log.w(tag, "speak() called in state $currentState — dropping (NEVER #28)")
            return
        }

        val tts = this.tts ?: run {
            Log.w(tag, "speak() called but TTS is null")
            return
        }

        // Council S11: chunk long utterances
        val chunks = chunkText(text, TtsEngine.MAX_UTTERANCE_LENGTH)
        for ((i, chunk) in chunks.withIndex()) {
            val chunkId = if (chunks.size > 1) "${utteranceId}_chunk$i" else utteranceId
            val result = tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, chunkId)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(tag, "tts.speak() returned $result for $chunkId")
            }
        }

        // Council M13: start watchdog
        startWatchdog(utteranceId)
    }

    override fun stop() {
        watchdogJob?.cancel()
        tts?.stop()
        Log.d(tag, "TTS stopped")
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.1f, 4.0f)
        tts?.setSpeechRate(speechRate)
        Log.d(tag, "Speech rate set to $speechRate")
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch.coerceIn(0.1f, 4.0f)
        tts?.setPitch(this.pitch)
        Log.d(tag, "Pitch set to ${this.pitch}")
    }

    override fun shutdown() {
        watchdogJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        _state.value = TtsEngineState.Uninitialized
        Log.i(tag, "TTS engine shut down")
    }

    // -- Watchdog (Council M13) --

    private fun startWatchdog(utteranceId: String) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(TtsEngine.WATCHDOG_TIMEOUT_MS)
            // onStart was not received in time — native TTS likely crashed
            Log.e(tag, "Watchdog timeout for $utteranceId — forcing reinit")
            _state.value = TtsEngineState.Error("Watchdog timeout: TTS engine unresponsive")
            shutdown()
            delay(500) // brief cooldown before reinit
            initialize()
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // -- UtteranceProgressListener (Council M11: main thread dispatch) --

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            scope.launch {
                cancelWatchdog()
                Log.d(tag, "onStart: $utteranceId")
                callbacks?.onStart(utteranceId ?: "")
            }
        }

        override fun onDone(utteranceId: String?) {
            scope.launch {
                Log.d(tag, "onDone: $utteranceId")
                callbacks?.onDone(utteranceId ?: "")
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, 0)"))
        override fun onError(utteranceId: String?) {
            scope.launch {
                Log.e(tag, "onError: $utteranceId")
                _state.value = TtsEngineState.Error("Utterance error: $utteranceId")
                callbacks?.onError(utteranceId ?: "")
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            scope.launch {
                Log.e(tag, "onError: $utteranceId code=$errorCode")
                _state.value = TtsEngineState.Error("Utterance error: $utteranceId (code=$errorCode)")
                callbacks?.onError(utteranceId ?: "")
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            scope.launch {
                cancelWatchdog()
                Log.d(tag, "onStop: $utteranceId interrupted=$interrupted")
            }
        }

        // API 26+ (Oracle S3): word-level highlighting
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                scope.launch {
                    Log.v(tag, "onRangeStart: $utteranceId [$start-$end]")
                }
            }
        }
    }

    // -- Helpers --

    private fun applyParams() {
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)
    }

    /** Council S11: splits text into chunks <= [maxLen] at word boundaries. */
    private fun chunkText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxLen) {
            val splitAt = remaining.lastIndexOf(' ', maxLen).takeIf { it > 0 } ?: maxLen
            chunks.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trim()
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }

    /**
     * Creates an [Intent] to install TTS language data.
     * Called by the UI when [TtsEngineState.LanguageMissing] is observed.
     */
    fun createInstallLanguageDataIntent(): Intent =
        Intent().apply { action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA }

    /** Releases coroutine scope resources. Called by TtsPlayer.handleRelease(). */
    fun release() {
        shutdown()
        scope.cancel()
    }
}
