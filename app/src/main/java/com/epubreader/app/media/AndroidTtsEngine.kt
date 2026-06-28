package com.epubreader.app.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.epubreader.app.core.log.AppLogger
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

    /** Tracks which engine we're currently probing. */
    private var engineProbeIndex = 0

    /** Cached list of installed engines. */
    private var engineList: List<TextToSpeech.EngineInfo> = emptyList()

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
            AppLogger.d(tag, "initialize() called but already initializing/ready — skipping")
            return
        }

        _state.value = TtsEngineState.Initializing

        // Discover engines using default TTS — create one real async instance
        // (avoids the sync TextToSpeech(context, null) probe which can corrupt
        // the TTS service connection on some devices)
        engineProbeIndex = 0
        tts = TextToSpeech(context, { status ->
            scope.launch {
                if (status == TextToSpeech.SUCCESS) {
                    engineList = tts!!.engines  // get engines from the live instance
                    AppLogger.i(tag, "Found ${engineList.size} installed TTS engine(s)")

                    if (engineList.isEmpty()) {
                        _state.value = TtsEngineState.Error("No TTS engine installed")
                        return@launch
                    }

                    // Check if current (default) engine supports a usable language
                    checkLanguageSupport(engineList[0])
                } else {
                    _state.value = TtsEngineState.Error("Failed to initialize default TTS engine")
                }
            }
        })  // no engine name = default engine
    }

    private fun probeNextEngine() {
        if (engineProbeIndex >= engineList.size) {
            // All engines probed, none support a usable language
            AppLogger.w(tag, "No engine supports a usable language among ${engineList.size} installed engines")
            _state.value = TtsEngineState.LanguageMissing(Locale.getDefault().toLanguageTag())
            return
        }

        val engineInfo = engineList[engineProbeIndex]
        AppLogger.i(tag, "Probing engine ${engineProbeIndex + 1}/${engineList.size}: ${engineInfo.label} (${engineInfo.name})")

        tts?.shutdown()
        tts = TextToSpeech(context, { status ->
            scope.launch {
                if (status == TextToSpeech.SUCCESS) {
                    checkLanguageSupport(engineInfo)
                } else {
                    AppLogger.w(tag, "Engine ${engineInfo.label} init failed: status=$status — trying next")
                    engineProbeIndex++
                    probeNextEngine()
                }
            }
        }, engineInfo.name)
    }

    private fun checkLanguageSupport(engineInfo: TextToSpeech.EngineInfo) {
        val tts = this.tts ?: run {
            engineProbeIndex++
            probeNextEngine()
            return
        }

        // Prefer Chinese when available; fall back to device default, then English
        val localesToTry = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINESE,
            Locale.getDefault(),
            Locale.ENGLISH
        ).distinct()  // avoid duplicate probes when device default IS one of the above

        for (locale in localesToTry) {
            val result = tts.isLanguageAvailable(locale)
            AppLogger.i(tag, "Engine ${engineInfo.label}: isLanguageAvailable($locale) = $result")
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
                configureAudioAttributes()
                applyParams()
                tts.setOnUtteranceProgressListener(utteranceListener)
                _state.value = TtsEngineState.Ready
                AppLogger.i(tag, "Selected engine: ${engineInfo.label} (${engineInfo.name}), language: $locale")
                return
            }
        }

        // This engine doesn't support a usable language — try next
        AppLogger.i(tag, "Engine ${engineInfo.label} does not support a usable language — trying next")
        tts.shutdown()
        this.tts = null
        engineProbeIndex++
        probeNextEngine()
    }

    override fun speak(text: String, utteranceId: String) {
        val currentState = _state.value
        if (currentState !is TtsEngineState.Ready) {
            AppLogger.w(tag, "speak() called in state $currentState — dropping (NEVER #28)")
            return
        }

        val tts = this.tts ?: run {
            AppLogger.w(tag, "speak() called but TTS is null")
            return
        }

        // Council S11: chunk long utterances
        val chunks = chunkText(text, TtsEngine.MAX_UTTERANCE_LENGTH)
        for ((i, chunk) in chunks.withIndex()) {
            val chunkId = if (chunks.size > 1) "${utteranceId}_chunk$i" else utteranceId
            val result = tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, chunkId)
            if (result != TextToSpeech.SUCCESS) {
                AppLogger.e(tag, "tts.speak() returned $result for $chunkId")
            }
        }

        // Council M13: start watchdog
        startWatchdog(utteranceId)
    }

    override fun stop() {
        watchdogJob?.cancel()
        tts?.stop()
        AppLogger.d(tag, "TTS stopped")
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.1f, 4.0f)
        tts?.setSpeechRate(speechRate)
        AppLogger.d(tag, "Speech rate set to $speechRate")
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch.coerceIn(0.1f, 4.0f)
        tts?.setPitch(this.pitch)
        AppLogger.d(tag, "Pitch set to ${this.pitch}")
    }

    override fun shutdown() {
        watchdogJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        engineProbeIndex = 0
        engineList = emptyList()
        _state.value = TtsEngineState.Uninitialized
        AppLogger.i(tag, "TTS engine shut down")
    }

    // -- Watchdog (Council M13) --

    private fun startWatchdog(utteranceId: String) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(TtsEngine.WATCHDOG_TIMEOUT_MS)
            // onStart was not received in time — native TTS likely crashed
            AppLogger.e(tag, "Watchdog timeout for $utteranceId — forcing reinit")
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
                AppLogger.d(tag, "onStart: $utteranceId")
                callbacks?.onStart(utteranceId ?: "")
            }
        }

        override fun onDone(utteranceId: String?) {
            scope.launch {
                AppLogger.d(tag, "onDone: $utteranceId")
                callbacks?.onDone(utteranceId ?: "")
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, 0)"))
        override fun onError(utteranceId: String?) {
            scope.launch {
                AppLogger.e(tag, "onError: $utteranceId")
                _state.value = TtsEngineState.Error("Utterance error: $utteranceId")
                callbacks?.onError(utteranceId ?: "")
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            scope.launch {
                AppLogger.e(tag, "onError: $utteranceId code=$errorCode")
                _state.value = TtsEngineState.Error("Utterance error: $utteranceId (code=$errorCode)")
                callbacks?.onError(utteranceId ?: "")
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            scope.launch {
                cancelWatchdog()
                AppLogger.d(tag, "onStop: $utteranceId interrupted=$interrupted")
            }
        }

        // API 26+ (Oracle S3): word-level highlighting
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                scope.launch {
                    AppLogger.v(tag, "onRangeStart: $utteranceId [$start-$end]")
                }
            }
        }
    }

    // -- Helpers --

    private fun applyParams() {
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)
    }

    private fun configureAudioAttributes() {
        val tts = this.tts ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        tts.setAudioAttributes(attrs)
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
