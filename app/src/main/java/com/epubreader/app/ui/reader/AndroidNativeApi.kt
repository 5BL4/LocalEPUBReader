package com.epubreader.app.ui.reader

import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import com.epubreader.app.core.log.AppLogger

/**
 * JS Bridge interface exposed to EPUB content's WebView.
 *
 * Security (NEVER #8):
 * - Every method receives [origin] (JS passes `window.location.origin`).
 * - [isAllowedOrigin] validates against the fixed allowlist of Readium WebViewServer origins.
 * - Defense-in-depth: the factory in [ReaderHostFragment] only registers this interface
 *   for HTML resources ([Link.mediaType] is HTML).
 *
 * R8 (NEVER #23):
 * - [Keep] on the class + ProGuard rule `-keepclassmembers class * { @JavascriptInterface <methods>; }`.
 *
 * Lifecycle (NEVER #20):
 * - The [BridgeCallbackHolder] is set by [ReaderHostFragment] in [setupNavigatorObserver]
 *   and cleared in [onDestroyView]. The callback only delegates to ViewModel methods
 *   (thread-safe StateFlow updates) — it MUST NOT access Fragment views.
 *
 * Thread safety (Oracle Q2):
 * - [JavascriptInterface] methods are called on a WebView thread.
 * - [BridgeCallbackHolder.callback] is `@Volatile` — sufficient for this use case
 *   because the callback only delegates to thread-safe ViewModel StateFlow updates.
 */
@Keep
class AndroidNativeApi(private val callbackHolder: BridgeCallbackHolder) {

    /**
     * Called when the user taps the horizontal center of the reading area.
     * Used to toggle the reader toolbar visibility.
     */
    @JavascriptInterface
    fun onCenterTap(origin: String) {
        if (!isAllowedOrigin(origin)) return
        callbackHolder.callback?.onCenterTap()
    }

    @JavascriptInterface
    fun onSelectionChanged(origin: String, text: String) {
        if (!isAllowedOrigin(origin)) return
        callbackHolder.callback?.onSelectionChanged(text)
    }

    /**
     * Phase 6 (TTS): Called when JS finishes extracting sentences from the
     * current chapter. [json] is a JSON object: {"firstVisibleSentenceId": N, "sentences": [{id, text, href, progression, cssSelector}, ...]}
     *
     * Security (NEVER #8): [origin] is validated against [ALLOWED_ORIGINS].
     */
    @JavascriptInterface
    fun onSentencesExtracted(origin: String, json: String) {
        if (!isAllowedOrigin(origin)) {
            AppLogger.w("AndroidNativeApi", "onSentencesExtracted: rejected origin=$origin")
            return
        }
        val cb = callbackHolder.callback
        if (cb == null) {
            AppLogger.w("AndroidNativeApi", "onSentencesExtracted: callback holder is null")
            return
        }
        cb.onSentencesExtracted(json)
    }

    /**
     * Called when JS finds the first visible block element for bookmark anchoring.
     * [json] is a JSON object: {"text":"...","cssSelector":"...","href":"...","progression":0.05}
     * or empty string if no visible block was found.
     *
     * Security (NEVER #8): [origin] is validated against [ALLOWED_ORIGINS].
     */
    @JavascriptInterface
    fun onFirstVisibleBlock(origin: String, json: String) {
        if (!isAllowedOrigin(origin)) {
            AppLogger.w("AndroidNativeApi", "onFirstVisibleBlock: rejected origin=$origin")
            return
        }
        val cb = callbackHolder.callback
        if (cb == null) {
            AppLogger.w("AndroidNativeApi", "onFirstVisibleBlock: callback holder is null")
            return
        }
        cb.onFirstVisibleBlock(json)
    }

    companion object {
        /** Readium WebViewServer virtual hosts (verified from WebViewServer.kt). */
        val ALLOWED_ORIGINS = setOf("https://readium_package", "https://readium_assets")

        fun isAllowedOrigin(origin: String): Boolean = origin in ALLOWED_ORIGINS
    }
}

/** Callbacks invoked from the WebView thread (via [AndroidNativeApi]). */
interface BridgeCallback {
    fun onCenterTap()
    fun onSelectionChanged(text: String)

    /**
     * Phase 6 (TTS): Called when JS sentence extraction completes.
     * [json] is a JSON object: {"firstVisibleSentenceId": N, "sentences": [{id, text, href, progression, cssSelector}, ...]}
     * The implementation should parse on a background thread (Oracle S5).
     */
    fun onSentencesExtracted(json: String)

    /**
     * Called when JS finds the first visible block element for bookmark anchoring.
     * [json] is a JSON object or empty string. Parse on a background thread.
     */
    fun onFirstVisibleBlock(json: String)
}

/**
 * Holds the current [BridgeCallback]. The JS interface factory captures this holder
 * (a small, leak-free object); the Fragment sets/clears the callback to avoid
 * leaking the Fragment into the WebView.
 */
class BridgeCallbackHolder {
    @Volatile
    var callback: BridgeCallback? = null
}
