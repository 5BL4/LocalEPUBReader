package com.epubreader.app.ui.reader

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubreader.app.R
import com.epubreader.app.databinding.FragmentReaderHostContainerBinding
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookUuid: String,
    onBack: () -> Unit
) {
    val viewModel: ReaderViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val selectionState by viewModel.selectionState.collectAsStateWithLifecycle()
    val bookmarkedHrefs by viewModel.bookmarkedHrefs.collectAsStateWithLifecycle()
    val currentLocator by viewModel.currentLocator.collectAsStateWithLifecycle()

    // S1 (NEVER #12): derivedStateOf — bookmark icon only recomposes when
    // the derived boolean actually changes, not on every locator emission.
    // S-E: Uses pre-computed href set (no JSON parsing per scroll).
    val isBookmarked by remember {
        derivedStateOf {
            val currentHref = currentLocator?.href?.toString() ?: return@derivedStateOf false
            currentHref in bookmarkedHrefs
        }
    }

    val clipboardManager = LocalClipboardManager.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Sync drawer state with VM state (bidirectional)
    LaunchedEffect(uiState.isTocDrawerOpen) {
        if (uiState.isTocDrawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed && uiState.isTocDrawerOpen) {
            viewModel.closeTocDrawer()
        }
    }

    // S5: Edge-to-Edge immersive reader
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.let {
            val window = it.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.let {
                val window = it.window
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TocDrawer(
                items = uiState.toc,
                onItemClick = { index ->
                    viewModel.navigateToTocItem(index)
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.bookTitle
                                ?: stringResource(R.string.reader_loading)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleTocDrawer() }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.reader_toc)
                            )
                        }
                        IconButton(onClick = { viewModel.toggleSearchPanel() }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.reader_search)
                            )
                        }
                        IconButton(onClick = { viewModel.toggleBookmark() }) {
                            Icon(
                                if (isBookmarked) Icons.Default.Bookmark
                                else Icons.Default.BookmarkBorder,
                                contentDescription = stringResource(R.string.reader_bookmark)
                            )
                        }
                        IconButton(onClick = { viewModel.toggleAutoScroll() }) {
                            Icon(
                                if (uiState.isAutoScrollActive) Icons.Default.Pause
                                else Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.reader_auto_scroll)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                SelectionToolbar(
                    state = selectionState,
                    onHighlight = { viewModel.requestHighlight() },
                    onBookmark = { viewModel.requestBookmarkSelection() },
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(selectionState.text))
                    },
                    onDismiss = { viewModel.dismissSelection() }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator()
                    }
                    uiState.error != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = uiState.error ?: "",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(onClick = onBack) {
                                Text(stringResource(R.string.reader_retry))
                            }
                        }
                    }
                    uiState.navigatorFactoryReady -> {
                        AndroidViewBinding(
                            factory = { inflater, parent, attachToParent ->
                                FragmentReaderHostContainerBinding.inflate(inflater, parent, attachToParent)
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = {
                                val fragment = this.readerHostContainer.getFragment() as? ReaderHostFragment
                                fragment?.bind(viewModel)
                            }
                        )
                    }
                }
            }
        }

        // Search panel overlay (ModalBottomSheet)
        if (uiState.isSearchPanelOpen) {
            SearchPanel(
                state = searchState,
                onQueryChange = { viewModel.search(it) },
                onResultClick = { viewModel.navigateToSearchResult(it) },
                onClose = { viewModel.closeSearchPanel() }
            )
        }
    }
}
