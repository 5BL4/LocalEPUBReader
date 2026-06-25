package com.epubreader.app.ui.reader

import android.os.Bundle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.util.AbsoluteUrl

private const val NAVIGATOR_TAG = "epub_navigator"

@AndroidEntryPoint
class ReaderHostFragment : Fragment(), EpubNavigatorFragment.Listener {

    private var viewModel: ReaderViewModel? = null
    private var navigatorAdded = false
    private var locatorCollectionJob: Job? = null

    /**
     * Stores the ViewModel reference. Safe to call multiple times (idempotent).
     * If the view is already created, starts collectors immediately.
     */
    fun bind(vm: ReaderViewModel) {
        if (viewModel != null) return
        viewModel = vm

        // If onViewCreated already ran, start collectors now
        if (view != null) {
            startCollectors()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set a factory that can instantiate EpubNavigatorFragment before super.onCreate()
        // so fragment restoration after process death works (M5).
        val existingFactory = viewModel?.navigatorFactory?.value
        childFragmentManager.fragmentFactory = if (existingFactory != null) {
            existingFactory.createFragmentFactory(
                initialLocator = viewModel?.initialLocator,
                listener = this,
                configuration = EpubNavigatorFragment.Configuration(
                    servedAssets = listOf("fonts/.*")
                )
            )
        } else {
            DefaultReaderFragmentFactory()
        }
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_reader_host, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // If factory was already ready before view creation, set it up synchronously
        val factory = viewModel?.navigatorFactory?.value
        if (factory != null && !navigatorAdded) {
            setupNavigator(factory)
        }

        // Start flow collectors if bind() was already called
        if (viewModel != null) {
            startCollectors()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locatorCollectionJob?.cancel()
        locatorCollectionJob = null
        navigatorAdded = false
    }

    /**
     * Starts collecting navigatorFactory and epubPreferences flows
     * using viewLifecycleOwner.lifecycleScope. Only safe to call after
     * onViewCreated() has run (viewLifecycleOwner is initialized).
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

        // Replace fragment factory with the Readium one so it configures navigator properly
        childFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = viewModel?.initialLocator,
            listener = this,
            configuration = EpubNavigatorFragment.Configuration(
                servedAssets = listOf("fonts/.*")
            )
        )

        // Check if navigator fragment already exists (restored from saved state)
        val existing = childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
        if (existing != null) {
            setupNavigatorObserver(existing)
            navigatorAdded = true
            return
        }

        // Instantiate navigator fragment using the factory
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
        locatorCollectionJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collectLatest { locator ->
                    viewModel?.onLocatorChanged(locator)
                }
            }
        }
    }

    // -- EpubNavigatorFragment.Listener --

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        // Phase 4: handle external links (e.g., open in browser)
    }

    // -- Inner FragmentFactory for process-death safety (M5) --

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
