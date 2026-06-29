package com.epubreader.app.ui.reader

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubreader.app.R
import com.epubreader.app.core.share.ShareEpubUtil
import com.epubreader.app.core.log.AppLogger
import com.epubreader.app.data.prefs.ThemeMode
import com.epubreader.app.core.tts.TtsPlaybackState
import com.epubreader.app.databinding.FragmentReaderHostContainerBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

private enum class ExportAction { SAVE_TO_FILE, SHARE }

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
    val knowledgeState by viewModel.knowledgeState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val bookFilePath by viewModel.bookFilePath.collectAsStateWithLifecycle()
    val noteEditorState by viewModel.noteEditorState.collectAsStateWithLifecycle()

    // Phase 6: TTS state
    val ttsPanelState by viewModel.ttsPanelState.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val ttsEngineState by viewModel.ttsEngineState.collectAsStateWithLifecycle()
    val ttsPlaybackState by viewModel.ttsPlaybackState.collectAsStateWithLifecycle()

    // Reader settings state
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()

    // Sync system dark mode to ViewModel so Readium WebView matches Compose theme
    // when the user selects "System" theme mode.
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) {
        viewModel.setSystemDarkMode(systemDark)
    }

    // S1 (NEVER #12): derivedStateOf — bookmark icon only recomposes when
    // the derived boolean actually changes, not on every locator emission.
    // S-E: Uses pre-computed href set (no JSON parsing per scroll).
    val isBookmarked by remember {
        derivedStateOf {
            val currentHref = currentLocator?.href?.toString()
                ?.substringBefore('#')?.substringBefore('?')?.trim('/')?.lowercase()
                ?: return@derivedStateOf false
            currentHref in bookmarkedHrefs
        }
    }

    val clipboardManager = LocalClipboardManager.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    // M4: Show toast when settings are locked during TTS
    LaunchedEffect(ttsPanelState.isSettingsLocked) {
        if (ttsPanelState.isSettingsLocked) {
            Toast.makeText(
                context,
                context.getString(R.string.tts_settings_locked),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Track which export action the user requested (Save vs Share)
    var pendingExportAction by remember { mutableStateOf<ExportAction?>(null) }

    // SAF launcher for saving Markdown to a file (Oracle M3: try-catch on write)
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri: Uri? ->
        if (uri != null) {
            val content = (exportState as? ExportState.Ready)?.content
            if (content != null) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                BufferedWriter(OutputStreamWriter(out, StandardCharsets.UTF_8)).use { writer ->
                                    writer.write(content)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Oracle M3: surface error, don't crash
                            viewModel.resetExportState()
                            pendingExportAction = null
                        }
                    }
                    viewModel.resetExportState()
                    pendingExportAction = null
                }
            }
        } else {
            viewModel.resetExportState()
            pendingExportAction = null
        }
    }

    // React to export state changes — launch SAF or ShareSheet when content is ready
    LaunchedEffect(exportState) {
        when (val state = exportState) {
            is ExportState.Ready -> {
                when (pendingExportAction) {
                    ExportAction.SAVE_TO_FILE -> {
                        saveFileLauncher.launch(state.suggestedFileName)
                    }
                    ExportAction.SHARE -> {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, state.content)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                        viewModel.resetExportState()
                        pendingExportAction = null
                    }
                    null -> { /* no action pending */ }
                }
            }
            is ExportState.Error -> {
                viewModel.resetExportState()
                pendingExportAction = null
            }
            else -> { /* Idle, Preparing — no action */ }
        }
    }

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
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            TocDrawer(
                items = uiState.toc,
                onItemClick = { index ->
                    AppLogger.d("ReaderScreen", "TOC item clicked: index=$index, title=${uiState.toc.getOrNull(index)?.title}")
                    viewModel.navigateToTocItem(index)
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Content — fills entire screen, NEVER resized by toolbar visibility.
            // This prevents EPUB CSS column recalculation on toolbar toggle.
            Box(
                modifier = Modifier.fillMaxSize(),
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

            // Top bar — slides in/out from top, overlaid on content.
            AnimatedVisibility(
                visible = uiState.isToolbarVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back arrow (left)
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Spacer pushes remaining icons to the right
                        Spacer(modifier = Modifier.weight(1f))
                        // Bookmark toggle (right)
                        IconButton(
                            onClick = { viewModel.toggleBookmark() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (isBookmarked) Icons.Default.Bookmark
                                else Icons.Default.BookmarkBorder,
                                contentDescription = stringResource(R.string.reader_bookmark),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Share EPUB file (right) — shares the entire .epub via system share sheet
                        IconButton(
                            onClick = { ShareEpubUtil.shareEpub(context, bookFilePath) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.reader_share_epub),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // More (right) — overflow dropdown
                        var showMoreMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.reader_more),
                                modifier = Modifier.size(20.dp)
                            )
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (appPreferences.scroll)
                                                stringResource(R.string.reader_settings_pagination)
                                            else
                                                stringResource(R.string.reader_settings_scroll)
                                        )
                                    },
                                    onClick = {
                                        viewModel.setScrollMode(!appPreferences.scroll)
                                        showMoreMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom bar — slides in/out from bottom, overlaid on content.
            // Mutually exclusive with selection toolbar.
            AnimatedVisibility(
                visible = uiState.isToolbarVisible && !selectionState.isActive,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ReaderBottomBar(
                    onToc = { viewModel.toggleTocDrawer() },
                    onSearch = { viewModel.toggleSearchPanel() },
                    onKnowledge = { viewModel.toggleKnowledgePanel() },
                    onProgress = { viewModel.toggleProgressPanel() },
                    onSettings = { viewModel.toggleSettingsPanel() }
                )
            }

            // Selection toolbar — slides in/out from bottom, overlaid on content.
            // Mutually exclusive with bottom bar.
            AnimatedVisibility(
                visible = selectionState.isActive,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                SelectionToolbar(
                    state = selectionState,
                    onHighlight = { viewModel.requestHighlight() },
                    onNote = { viewModel.requestNote() },
                    onBookmark = { viewModel.requestBookmarkSelection() },
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(selectionState.text))
                    },
                    onDismiss = { viewModel.dismissSelection() }
                )
            }

            // TTS FAB — fades + scales in, positioned above bottom bar.
            // 64dp = 48dp bottom bar height + 16dp margin.
            AnimatedVisibility(
                visible = uiState.isToolbarVisible && !selectionState.isActive,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 64.dp)
            ) {
                Surface(
                    onClick = { viewModel.toggleTtsPlayback() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        when (ttsPlaybackState) {
                            is TtsPlaybackState.Playing -> Icon(
                                Icons.Default.Pause,
                                contentDescription = stringResource(R.string.tts_panel_title),
                                modifier = Modifier.size(22.dp)
                            )
                            is TtsPlaybackState.Paused -> Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.tts_panel_title),
                                modifier = Modifier.size(22.dp)
                            )
                            else -> Text(
                                "听",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
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

        // Knowledge panel overlay (ModalBottomSheet) — Phase 5
        if (uiState.isKnowledgePanelOpen) {
            KnowledgePanel(
                state = knowledgeState,
                exportState = exportState,
                onSaveToFile = {
                    pendingExportAction = ExportAction.SAVE_TO_FILE
                    viewModel.prepareExport()
                },
                onShare = {
                    pendingExportAction = ExportAction.SHARE
                    viewModel.prepareExport()
                },
                onDeleteItem = { viewModel.deleteKnowledgeItem(it) },
                onDismiss = { viewModel.closeKnowledgePanel() }
            )
        }

        // Settings panel overlay (ModalBottomSheet)
        if (uiState.isSettingsPanelOpen) {
            ReaderSettingsPanel(
                fontSize = appPreferences.fontSize,
                fontFamily = appPreferences.fontFamily,
                theme = appPreferences.theme,
                lineSpacing = appPreferences.lineSpacing,
                paragraphSpacing = appPreferences.paragraphSpacing,
                paragraphIndent = appPreferences.paragraphIndent,
                pageMargins = appPreferences.pageMargins,
                scroll = appPreferences.scroll,
                onFontSizeChange = { viewModel.setFontSize(it) },
                onFontFamilyChange = { viewModel.setFontFamily(it) },
                onThemeChange = { viewModel.setTheme(it) },
                onLineSpacingChange = { viewModel.setLineSpacing(it) },
                onParagraphSpacingChange = { viewModel.setParagraphSpacing(it) },
                onParagraphIndentChange = { viewModel.setParagraphIndent(it) },
                onPageMarginsChange = { viewModel.setPageMargins(it) },
                onScrollModeChange = { viewModel.setScrollMode(it) },
                onDismiss = { viewModel.closeSettingsPanel() }
            )
        }

        // Progress panel overlay (ModalBottomSheet)
        if (uiState.isProgressPanelOpen) {
            ProgressPanel(
                progress = (currentLocator?.locations?.totalProgression ?: 0.0).toFloat(),
                onDismiss = { viewModel.closeProgressPanel() }
            )
        }

        // Note editor dialog — Phase 5 (Oracle M4: UI StateFlow driven)
        if (noteEditorState is NoteEditorState.Editing) {
            NoteEditorDialog(
                selectedText = (noteEditorState as NoteEditorState.Editing).selectedText,
                onSave = { content -> viewModel.createNote(content) },
                onDismiss = { viewModel.cancelNoteEditor() }
            )
        }

        // Phase 6: TTS control panel
        if (ttsPanelState.isPanelOpen) {
            TtsControlPanel(
                panelState = ttsPanelState,
                sleepTimerState = sleepTimerState,
                onPlay = {
                    if (viewModel.ttsPlaybackState.value is TtsPlaybackState.Paused) {
                        viewModel.resumeTts()
                    } else {
                        viewModel.startTts()
                    }
                },
                onPause = { viewModel.pauseTts() },
                onStop = { viewModel.stopTts() },
                onSeekBackward = { viewModel.seekTtsBackward() },
                onSeekForward = { viewModel.seekTtsForward() },
                onSpeedChange = { viewModel.setTtsSpeed(it) },
                onPitchChange = { viewModel.setTtsPitch(it) },
                onSleepTimer = { durationMs ->
                    if (durationMs != null) {
                        viewModel.startSleepTimer(durationMs)
                    } else {
                        viewModel.cancelSleepTimer()
                    }
                },
                onSleepTimerEndOfChapter = { viewModel.startSleepTimerEndOfChapter() },
                onDismiss = { viewModel.closeTtsPanel() }
            )
        }

        // Phase 6: Language pack / error dialog (NEVER #28)
        val showTtsErrorDialog = remember(ttsEngineState) {
            ttsEngineState is com.epubreader.app.core.tts.TtsEngineState.LanguageMissing ||
                ttsEngineState is com.epubreader.app.core.tts.TtsEngineState.Error
        }
        if (showTtsErrorDialog) {
            LanguagePackDialog(
                isError = ttsEngineState is com.epubreader.app.core.tts.TtsEngineState.Error,
                onInstall = {
                    val intent = android.content.Intent(
                        android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                onDismiss = { viewModel.stopTts() }
            )
        }
    }
}
