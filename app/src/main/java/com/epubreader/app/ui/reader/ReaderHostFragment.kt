package com.epubreader.app.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.epubreader.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
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
                listener = this,
                configuration = buildConfiguration()
            )
            usedDefaultFactory = false
        } else {
            childFragmentManager.fragmentFactory = DefaultReaderFragmentFactory()
            usedDefaultFactory = true
        }
        super.onCreate(savedInstanceState)
    }

    /**
     * Builds the EpubNavigatorFragment Configuration with JS Bridge registration.
     * Defense-in-depth: the bridge is only exposed to HTML resources.
     */
    private fun buildConfiguration(): EpubNavigatorFragment.Configuration =
        EpubNavigatorFragment.Configuration {
            servedAssets = listOf("fonts/.*")
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
        val factory = viewModel?.navigatorFactory?.value
        if (factory != null && !navigatorAdded) {
            setupNavigator(factory)
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.navigatorFactory.collectLatest { factory ->
                        if (factory != null && !navigatorAdded) {
                            setupNavigator(factory)
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
            .commit()

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
                launch {
                    navigator.currentLocator
                        .map { it.href.toString() }
                        .distinctUntilChanged()
                        .collect { href ->
                            // Phase 6 (M7): Notify VM of chapter change
                            viewModel?.onChapterChanged(href)
                            try {
                                navigator.evaluateJavascript(ReaderJsScripts.SELECTION_LISTENER)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("ReaderHostFragment", "Failed to inject selection listener", e)
                            }
                            // Re-inject auto-scroll if active
                            if (viewModel?.uiState?.value?.isAutoScrollActive == true) {
                                try {
                                    navigator.evaluateJavascript(ReaderJsScripts.AUTO_SCROLL_START)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("ReaderHostFragment", "Failed to re-inject auto-scroll", e)
                                }
                            }
                        }
                }
                // M7: Commands collector — only after navigator is confirmed ready.
                // With M1's buffer, commands emitted during the gap are held and delivered here.
                launch {
                    viewModel?.commands?.collect { command ->
                        handleCommand(navigator, command)
                    }
                }
                // M2: Auto-scroll state-driven — Fragment observes isAutoScrollActive
                // and injects START/STOP JS. No command channel for auto-scroll.
                launch {
                    viewModel?.uiState
                        ?.map { it.isAutoScrollActive }
                        ?.distinctUntilChanged()
                        ?.collect { isActive ->
                            try {
                                if (isActive) {
                                    navigator.evaluateJavascript(ReaderJsScripts.AUTO_SCROLL_START)
                                } else {
                                    navigator.evaluateJavascript(ReaderJsScripts.AUTO_SCROLL_STOP)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("ReaderHostFragment", "Auto-scroll JS failed", e)
                            }
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
                            Log.e("ReaderHostFragment", "Failed to apply highlight decorations", e)
                        }
                    }
                }
                // Phase 6 (M2): TTS sentence highlighting — state-driven (not command).
                // currentSentenceIndex changes every few seconds; using a command would
                // flood the SharedFlow buffer (DROP_OLDEST would drop navigation commands).
                launch {
                    viewModel?.ttsCurrentSentenceIndex
                        ?.collect { index ->
                            if (index >= 0) {
                                // M10: navigate to sentence locator first (paginated mode)
                                // then highlight via JS
                                try {
                                    navigator.evaluateJavascript(
                                        TtsJsScripts.highlightSentence(index)
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("ReaderHostFragment", "TTS highlight JS failed", e)
                                }
                            }
                        }
                }
            }
        }
    }

    // M5 (NEVER #26): Wrap each command in try-catch to prevent crashes
    private suspend fun handleCommand(navigator: EpubNavigatorFragment, command: ReaderCommand) {
        try {
            when (command) {
                is ReaderCommand.NavigateToLocator -> navigator.go(command.locator)
                is ReaderCommand.NavigateToLink -> navigator.go(command.link)
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
                    navigator.evaluateJavascript(TtsJsScripts.EXTRACT_SENTENCES)
                }
                is ReaderCommand.ClearTtsHighlight -> {
                    navigator.evaluateJavascript(TtsJsScripts.CLEAR_TTS_HIGHLIGHT)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ReaderHostFragment", "Command failed: $command", e)
        }
    }

    // -- BridgeCallback (called from WebView thread) --
    // IMPORTANT: Only delegate to ViewModel methods (thread-safe StateFlow updates).
    // MUST NOT access Fragment views, requireContext(), or childFragmentManager.

    override fun onAutoScrollStopped() {
        viewModel?.onAutoScrollStopped()
    }

    override fun onSelectionChanged(text: String) {
        viewModel?.onSelectionChanged(text)
    }

    // Phase 6 (TTS): BridgeCallback — called from WebView thread
    override fun onSentencesExtracted(json: String) {
        viewModel?.onSentencesExtracted(json)
    }

    // -- EpubNavigatorFragment.Listener --

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        // S10: Owned by Fragment only (not VM)
        // M5 (NEVER #26): try-catch for ActivityNotFoundException
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("ReaderHostFragment", "No browser app to open $url")
        }
    }

    // -- Inner FragmentFactory for process-death safety (M5 from Phase 3) --

    private inner class DefaultReaderFragmentFactory : FragmentFactory() {
        override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
            if (className == EpubNavigatorFragment::class.java.name) {
                @Suppress("DEPRECATION")
                return EpubNavigatorFragment::class.java.getDeclaredConstructor().newInstance()
            }
            return super.instantiate(classLoader, className)
        }
    }
}
