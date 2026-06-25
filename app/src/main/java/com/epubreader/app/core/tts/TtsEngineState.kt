package com.epubreader.app.core.tts

/**
 * State machine for the TTS engine (NEVER #28).
 *
 * The native [android.speech.tts.TextToSpeech] initialization is asynchronous.
 * Calling `speak()` before [android.speech.tts.TextToSpeech.OnInitListener]
 * returns [android.speech.tts.TextToSpeech.SUCCESS] silently fails.
 *
 * This sealed hierarchy enforces that `speak()` is only attempted in [Ready].
 *
 * Transitions:
 * - Uninitialized → Initializing (on `initialize()`)
 * - Initializing → Ready (onInit SUCCESS + language available)
 * - Initializing → LanguageMissing (onInit SUCCESS + LANG_MISSING_DATA)
 * - Initializing → Error (onInit ERROR, or watchdog timeout)
 * - Ready → Error (watchdog timeout, native crash)
 * - Any → Uninitialized (on `shutdown()`)
 */
sealed interface TtsEngineState {

    /** Engine not yet created. */
    data object Uninitialized : TtsEngineState

    /** [TextToSpeech] constructor called, waiting for [OnInitListener] callback. */
    data object Initializing : TtsEngineState

    /** Engine ready to speak. Language data is available. */
    data object Ready : TtsEngineState

    /**
     * Language data is missing on the device.
     *
     * The UI should prompt the user to install the language pack via
     * [android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA].
     */
    data class LanguageMissing(val locale: String) : TtsEngineState

    /**
     * Engine error — initialization failure, native crash, or watchdog timeout.
     *
     * [reason] is a human-readable diagnostic string (logged, not shown to user directly).
     */
    data class Error(val reason: String) : TtsEngineState
}
