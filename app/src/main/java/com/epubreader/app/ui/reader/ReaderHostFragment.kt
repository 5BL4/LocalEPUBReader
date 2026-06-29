package com.epubreader.app.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.epubreader.app.core.log.AppLogger
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.epubreader.app.R
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl

private const val NAVIGATOR_TAG = "epub_navigator"

@OptIn(ExperimentalReadiumApi::class)
@AndroidEntryPoint
class ReaderHostFragment : Fragment(), EpubNavigatorFragment.Listener, BridgeCallback {

    // S9: @Volatile — BridgeCallback methods are called from WebView thread
    @Volatile
    private var viewModel: ReaderViewModel? = null
    private var navigatorAdded = false
    private var locatorCollectionJob: Job? = null

    // M3: Track whether DefaultReaderFragmentFactory was used (process-death restoration)
    private var usedDefaultFactory = false

    // JS Bridge callback holder — Fragment sets/clears callback (NEVER #20)
    private val bridgeCallbackHolder = BridgeCallbackHolder()

    // Safety net: prevents app crashes if setupNavigator or any collector throws.
    // viewLifecycleOwner.lifecycleScope has no CoroutineExceptionHandler by default,
    // so an uncaught exception would propagate to Thread.uncaughtExceptionHandler
    // and crash the app. This handler logs and swallows non-cancellation exceptions.
    private val collectorExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e("ReaderHostFragment", "Uncaught exception in navigator collector", throwable)
    }

    /**
     * Stores the ViewModel reference. Safe to call multiple times (idempotent).
     * If the view is already created, starts collectors immediately.
     */
    fun bind(vm: ReaderViewModel) {
        if (viewModel != null) return
        viewModel = vm
        if (view != null) {
            startCollectors()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val existingFactory = viewModel?.navigatorFactory?.value
        if (existingFactory != null) {
            childFragmentManager.fragmentFactory = existingFactory.createFragmentFactory(
                initialLocator = viewModel?.initialLocator,
                initialPreferences = viewModel?.epubPreferences?.value ?: EpubPreferences(),
                listener = this,
                configuration = buildConfiguration()
            )
            usedDefaultFactory = false
        } else {
            // viewModel not bound yet (AndroidViewBinding calls bind() after onViewCreated).
            // Do NOT install DefaultReaderFragmentFactory — EpubNavigatorFragment has no
            // no-arg constructor, so any restoration attempt would throw NoSuchMethodException.
            usedDefaultFactory = true
        }
        // Pass null to skip child FragmentManager restoration. The EpubNavigatorFragment
        // cannot be reinstantiated without the Publication (only available after bind()),
        // so it is recreated fresh by setupNavigator() once the ViewModel binds.
        // Reading position is restored from Room in openPublication().
        super.onCreate(null)
    }

    /**
     * Builds the EpubNavigatorFragment Configuration with JS Bridge registration.
     * Defense-in-depth: the bridge is only exposed to HTML resources.
     */
    private fun buildConfiguration(): EpubNavigatorFragment.Configuration =
        EpubNavigatorFragment.Configuration {
            servedAssets = listOf("fonts/.*")
            // Disable Readium's automatic inset padding — it applies asymmetric system
            // window insets (display cutout, rounded corners) as WebView padding, causing
            // unequal left/right margins. The app handles immersive mode itself.
            shouldApplyInsetsPadding = false
            registerJavascriptInterface("AndroidNativeApi") { link ->
                if (link.mediaType?.isHtml == true) {
                    AndroidNativeApi(bridgeCallbackHolder)
                } else {
                    null
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_reader_host, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Fixed system bar height padding — does NOT depend on dynamic insets.
        // Rationale: under BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, transient bars
        // appear as a semi-transparent overlay WITHOUT changing window insets
        // (systemBars() returns 0). Relying on insets means padding is removed
        // and text gets occluded by the transient status/nav bars. Using fixed
        // system resource dimensions keeps a safe reading area in all states
        // (hidden / transient / visible), while still feeling immersive because
        // the padding area shares the content background when bars are hidden.
        val res = view.resources
        val statusH = res.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { res.getDimensionPixelSize(it) } ?: 0
        val navH = res.getIdentifier("navigation_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { res.getDimensionPixelSize(it) } ?: 0

        // Minimum safe bottom padding for gesture-navigation devices where
        // navigation_bar_height may report 0 or a very small value.
        val minBottomPadding = (16f * res.displayMetrics.density).toInt()

        val basePaddingTop = statusH
        val basePaddingBottom = maxOf(navH, minBottomPadding)

        // Apply base padding immediately so the first frame is already safe.
        view.setPadding(view.paddingLeft, basePaddingTop, view.paddingRight, basePaddingBottom)

        // Keep the insets listener to supplement with displayCutout (notches,
        // punch-holes, rounded corners) which can vary at runtime (e.g. foldables).
        // Take the max of base system-bar padding and cutout so whichever is
        // larger wins. We do NOT consume insets so children can still react.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                maxOf(cutout.left, v.paddingLeft),
                maxOf(cutout.top, basePaddingTop),
                maxOf(cutout.right, v.paddingRight),
                maxOf(cutout.bottom, basePaddingBottom)
            )
            insets
        }

        val factory = viewModel?.navigatorFactory?.value
        if (factory != null && !navigatorAdded) {
            try {
                setupNavigator(factory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ReaderHostFragment", "setupNavigator failed in onViewCreated", e)
            }
        }
        if (viewModel != null) {
            startCollectors()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locatorCollectionJob?.cancel()
        locatorCollectionJob = null
        navigatorAdded = false
        // NEVER #20: Clear JS Bridge callback in onDestroyView (not onDestroy)
        bridgeCallbackHolder.callback = null
    }

    /**
     * Starts collecting navigatorFactory and epubPreferences flows.
     * Only safe to call after onViewCreated() (viewLifecycleOwner is initialized).
     */
    private fun startCollectors() {
        val vm = viewModel ?: return
        viewLifecycleOwner.lifecycleScope.launch(collectorExceptionHandler) {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.navigatorFactory.collectLatest { factory ->
                        if (factory != null && !navigatorAdded) {
                            try {
                                setupNavigator(factory)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("ReaderHostFragment", "setupNavigator failed", e)
                            }
                        }
                    }
                }
                launch {
                    vm.epubPreferences.collectLatest { prefs ->
                        val navigator = childFragmentManager
                            .findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
                        navigator?.submitPreferences(prefs)
                    }
                }
            }
        }
    }

    private fun setupNavigator(factory: EpubNavigatorFactory) {
        if (navigatorAdded) return

        childFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = viewModel?.initialLocator,
            initialPreferences = viewModel?.epubPreferences?.value ?: EpubPreferences(),
            listener = this,
            configuration = buildConfiguration()
        )

        val existing = childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
        if (existing != null && !usedDefaultFactory) {
            // Properly configured fragment (e.g., survived rotation) — reuse
            setupNavigatorObserver(existing)
            navigatorAdded = true
            return
        }
        if (existing != null && usedDefaultFactory) {
            // M3: Remove unconfigured fragment (created by DefaultReaderFragmentFactory
            // during process-death restoration) and recreate with proper configuration.
            childFragmentManager.beginTransaction()
                .remove(existing)
                .commitNow()
            // Fall through to create new fragment with proper configuration
        }

        val navigatorFragment = childFragmentManager.fragmentFactory.instantiate(
            requireContext().classLoader,
            EpubNavigatorFragment::class.java.name
        ) as EpubNavigatorFragment

        childFragmentManager.beginTransaction()
            .replace(R.id.reader_container, navigatorFragment, NAVIGATOR_TAG)
            .commitNow()

        // S1: submit initial preferences immediately to avoid CSS flicker
        viewModel?.epubPreferences?.value?.let { prefs ->
            navigatorFragment.submitPreferences(prefs)
        }

        setupNavigatorObserver(navigatorFragment)
        navigatorAdded = true
    }

    private fun setupNavigatorObserver(navigator: EpubNavigatorFragment) {
        locatorCollectionJob?.cancel()
        // Set JS Bridge callback when navigator is ready
        bridgeCallbackHolder.callback = this

        locatorCollectionJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Existing: currentLocator → VM progress save
                launch {
                    navigator.currentLocator.collectLatest { locator ->
                        viewModel?.onLocatorChanged(locator)
                    }
                }
                // M6: Re-inject JS scripts on resource (href) change.
                // evaluateJavascript only affects the current resource's WebView;
                // navigating to a new chapter creates a new WebView without our scripts.
                //
                // Bug fix: On the first chapter, currentLocator emits before the WebView
                // is ready (currentReflowablePageFragment is null → evaluateJavascript
                // returns null). When onPageLoaded re-emits the same href,
                // distinctUntilChanged filters it out, so the listener is never installed.
                // A bounded retry loop bridges this gap. Scripts are idempotent (safe to
                // re-inject via window.__epub* guards).
                launch {
                    navigator.currentLocator
                        .map { it.href.toString() }
                        .distinctUntilChanged()
                        .collect { href ->
                            // Phase 6 (M7): Notify VM of chapter change
                            viewModel?.onChapterChanged(href)
                            injectJsWithRetry(navigator, ReaderJsScripts.SELECTION_LISTENER, "selection listener")
                            injectJsWithRetry(navigator, ReaderJsScripts.CENTER_TAP_LISTENER, "center-tap listener")
                            injectJsWithRetry(navigator, ReaderJsScripts.INJECT_READER_CSS, "reader css")
                        }
                }
                // M7: Commands collector — only after navigator is confirmed ready.
                // With M1's buffer, commands emitted during the gap are held and delivered here.
                launch {
                    AppLogger.d("ReaderHost", "Commands collector started")
                    viewModel?.commands?.collect { command ->
                        handleCommand(navigator, command)
                    }
                }
                // Highlight decorations — collect directly from VM StateFlow.
                // This avoids SharedFlow timing issues (replay=0 would lose initial emissions).
                launch {
                    viewModel?.highlightDecorations?.collect { decorations ->
                        try {
                            navigator.applyDecorations(decorations, "highlights")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("ReaderHostFragment", "Failed to apply highlight decorations", e)
                        }
                    }
                }
                // Phase 6 (M2): TTS sentence highlighting — state-driven (not command).
                // currentSentenceIndex changes every few seconds; using a command would
                // flood the SharedFlow buffer (DROP_OLDEST would drop navigation commands).
                // color is grey during normal playback, yellow briefly during seeking.
                // Navigator.go() is gated by column-index change to avoid premature page
                // turns on same-page sentences. text.highlight makes scrollToLocator idempotent.
                launch {
                    val vm = viewModel ?: return@launch
                    // Throttle: process at most one highlight every 100ms to prevent
                    // navigator.go() from flooding the Readium navigator with rapid page
                    // flips. 100ms is tuned for sentence-level updates (faster TTS speeds
                    // can complete sentences in <1s; the old 300ms throttle caused visual lag,
                    // and throttleLatest is not available in this kotlinx-coroutines version).
                    var lastProcessedTime = 0L
                    var lastNavigatedColIndex = -1
                    var lastGenId = ""
                    combine(
                        vm.ttsCurrentSentenceIndex,
                        vm.ttsHighlightColor,
                        vm.ttsGenerationId
                    ) { index, color, genId -> Triple(index, color, genId) }
                        .distinctUntilChanged()
                        .collect { triple ->
                            // Reset page tracker when a new TTS session/chapter starts (new generationId),
                            // so the first non-suppressed sentence can navigate freely.
                            if (triple.third != lastGenId) {
                                lastGenId = triple.third
                                lastNavigatedColIndex = -1
                            }
                            if (triple.first >= 0) {
                                val now = System.currentTimeMillis()
                                if (now - lastProcessedTime < 100L) return@collect
                                lastProcessedTime = now
                                try {
                                    val locator = vm.currentSentenceLocator(triple.first)
                                    // isTtsStarting suppresses the first sentence after start/chapter-transition.
                                    // Use column-index dedup: only navigate when colIndex changes (paginated)
                                    // or always (scroll mode), avoiding premature page turns on same-page sentences.
                                    if (locator != null && vm.isLocatorInCurrentChapter(locator) &&
                                        !vm.isTtsStarting) {
                                        val colCount = vm.currentColCount
                                        val colIndex = vm.currentSentenceColIndex(triple.first)
                                        // Scroll mode (colCount <= 1): always go() — scrollToLocator
                                        //   is idempotent via text.highlight (no-op when visible).
                                        // Paginated mode (colCount > 1): only go() when column
                                        //   changes — colIndex is the DOM-measured column, so
                                        //   same-column = same visible page. This also protects
                                        //   against TextQuoteAnchor failure (CJK content) by
                                        //   preventing spurious same-column setCurrentItem calls.
                                        if (colCount <= 1 || colIndex < 0 ||
                                            lastNavigatedColIndex < 0 ||
                                            colIndex != lastNavigatedColIndex) {
                                            navigator.go(locator, animated = false)
                                            lastNavigatedColIndex = colIndex
                                        }
                                    }
                                    // Highlight is UNCONDITIONAL — every sentence gets highlighted
                                    val highlightResult = navigator.evaluateJavascript(
                                        TtsJsScripts.highlightSentence(triple.first, triple.second)
                                    )
                                    AppLogger.d("ReaderHost", "TTS highlight: idx=${triple.first}, result=$highlightResult")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e("ReaderHostFragment", "TTS highlight/nav failed", e)
                                }
                            }
                        }
                }
            }
        }
    }

    /**
     * Injects JavaScript with bounded retry. evaluateJavascript returns null when the
     * WebView page fragment isn't attached yet (first chapter race). Once the fragment
     * exists, awaitLoaded() suspends until the page is loaded, then the script runs.
     *
     * Scripts are idempotent (check window.__epub* flags), so re-injection is safe.
     */
    private suspend fun injectJsWithRetry(
        navigator: EpubNavigatorFragment,
        script: String,
        label: String
    ): Boolean {
        repeat(5) { attempt ->
            try {
                val result = navigator.evaluateJavascript(script)
                if (result != null) {
                    // Script executed — WebView was ready (result is "null" string for
                    // undefined-returning IIFEs, but non-null means it ran)
                    return true
                }
                // result is null — page fragment not attached yet
                if (attempt < 4) delay(200)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.d("ReaderHostFragment", "$label attempt ${attempt + 1} failed", e)
                if (attempt < 4) delay(200)
            }
        }
        AppLogger.w("ReaderHostFragment", "$label: WebView not ready after 5 retries")
        return false
    }

    // M5 (NEVER #26): Wrap each command in try-catch to prevent crashes
    private suspend fun handleCommand(navigator: EpubNavigatorFragment, command: ReaderCommand) {
        try {
            when (command) {
                is ReaderCommand.NavigateToLocator -> navigator.go(command.locator)
                is ReaderCommand.NavigateToLink -> {
                    val href = command.link.href
                    AppLogger.d("ReaderHost", "handleCommand NavigateToLink href=$href")
                    val success = navigator.go(command.link)
                    if (!success) {
                        AppLogger.w("ReaderHost", "NavigateToLink failed for href=$href, trying reading-order resolution")
                        // Fallback: try to resolve via publication's reading order.
                        // go(link) navigates by reading-order index; if that failed,
                        // the link's href may not match reading order. Try finding a
                        // reading-order link with the same href and navigate via go(locator).
                        val pub = viewModel?.publication
                        val readingOrderLink = pub?.readingOrder?.firstOrNull { it.href == command.link.href }
                        if (readingOrderLink != null) {
                            val locator = pub.locatorFromLink(readingOrderLink)
                            if (locator != null) {
                                navigator.go(locator)
                            } else {
                                AppLogger.e("ReaderHost", "Cannot resolve locator from reading-order link: $href")
                            }
                        } else {
                            AppLogger.e("ReaderHost", "href not found in reading order: $href")
                        }
                    }
                }
                is ReaderCommand.RequestCurrentSelection -> {
                    val selection = navigator.currentSelection()
                    viewModel?.onSelectionRetrieved(selection)
                }
                is ReaderCommand.ApplyDecorations -> navigator.applyDecorations(
                    command.decorations, command.group
                )
                is ReaderCommand.ClearSelection -> navigator.clearSelection()
                // Phase 6 (TTS) commands
                is ReaderCommand.ExtractSentences -> {
                    AppLogger.i("ReaderHost", "TTS: evaluating ExtractSentences JS")
                    val ok = injectJsWithRetry(navigator, TtsJsScripts.EXTRACT_SENTENCES, "extract sentences")
                    if (!ok) {
                        AppLogger.e("ReaderHost", "TTS: ExtractSentences injection failed after retries")
                        viewModel?.onSentencesExtracted("{\"error\":\"WebView not ready after retries\",\"sentences\":[]}")
                    }
                }
                is ReaderCommand.ClearTtsHighlight -> {
                    navigator.evaluateJavascript(TtsJsScripts.CLEAR_TTS_HIGHLIGHT)
                }
                is ReaderCommand.RequestFirstVisibleBlock -> {
                    navigator.evaluateJavascript(BookmarkJsScripts.GET_FIRST_VISIBLE_BLOCK)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("ReaderHostFragment", "Command failed: $command", e)
        }
    }

    // -- BridgeCallback (called from WebView thread) --
    // IMPORTANT: Only delegate to ViewModel methods (thread-safe StateFlow updates).
    // MUST NOT access Fragment views, requireContext(), or childFragmentManager.

    override fun onCenterTap() {
        viewModel?.onCenterTap()
    }

    override fun onSelectionChanged(text: String) {
        viewModel?.onSelectionChanged(text)
    }

    // Phase 6 (TTS): BridgeCallback — called from WebView thread
    override fun onSentencesExtracted(json: String) {
        AppLogger.i("ReaderHost", "TTS: received sentences JSON, length=${json.length}")
        viewModel?.onSentencesExtracted(json)
    }

    override fun onFirstVisibleBlock(json: String) {
        AppLogger.d("ReaderHost", "Bookmark: onFirstVisibleBlock received, json length=${json.length}")
        viewModel?.onFirstVisibleBlock(json)
    }

    // -- EpubNavigatorFragment.Listener --

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        // S10: Owned by Fragment only (not VM)
        // M5 (NEVER #26): try-catch for ActivityNotFoundException
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.w("ReaderHostFragment", "No browser app to open $url")
        }
    }
}
