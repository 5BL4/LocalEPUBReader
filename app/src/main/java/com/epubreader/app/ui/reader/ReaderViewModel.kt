package com.epubreader.app.ui.reader

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.epubreader.app.R
import com.epubreader.app.core.AppCoroutineExceptionHandler
import com.epubreader.app.core.AppError
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.StringProvider
import com.epubreader.app.core.export.ExportBookmark
import com.epubreader.app.core.export.ExportHighlight
import com.epubreader.app.core.export.ExportNote
import com.epubreader.app.core.export.ExportRequest
import com.epubreader.app.core.export.ExportTocItem
import com.epubreader.app.core.export.MarkdownExporter
import com.epubreader.app.core.fold
import com.epubreader.app.core.getOrNull
import com.epubreader.app.core.readium.toJsonString
import com.epubreader.app.core.readium.toLocator
import com.epubreader.app.core.tts.TtsController
import com.epubreader.app.core.tts.TtsEngineState
import com.epubreader.app.core.tts.TtsPlaybackState
import com.epubreader.app.core.tts.TtsSentence
import com.epubreader.app.data.local.entity.BookmarkEntity
import com.epubreader.app.data.local.entity.HighlightEntity
import com.epubreader.app.data.local.entity.NoteEntity
import com.epubreader.app.data.local.entity.ReadingProgressEntity
import com.epubreader.app.data.prefs.AppPreferences
import com.epubreader.app.data.prefs.PreferencesRepository
import com.epubreader.app.domain.repository.BookRepository
import com.epubreader.app.domain.repository.BookmarkRepository
import com.epubreader.app.domain.repository.HighlightRepository
import com.epubreader.app.domain.repository.NoteRepository
import com.epubreader.app.domain.repository.ReadingProgressRepository
import com.epubreader.app.navigation.ReaderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.Search
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.LocatorCollection
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val readingProgressRepository: ReadingProgressRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val preferencesRepository: PreferencesRepository,
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
    private val markdownExporter: MarkdownExporter,
    private val ttsController: TtsController,
    private val dispatchers: DispatchersProvider,
    private val stringProvider: StringProvider,
    private val errorChannel: ErrorChannel,
    private val exceptionHandler: AppCoroutineExceptionHandler
) : ViewModel() {

    private val bookUuid: String = runCatching {
        savedStateHandle.toRoute<ReaderRoute>().bookUuid
    }.getOrElse {
        // Fallback: read the raw arg key used by type-safe nav for a String field.
        // If this also fails, the SavedStateHandle is genuinely malformed.
        savedStateHandle.get<String>("bookUuid")
            ?: error("ReaderRoute.bookUuid missing from SavedStateHandle — " +
                "ensure ReaderScreen is only reached via navigate(ReaderRoute(uuid))")
    }

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // M2 (NEVER #12): currentLocator is high-frequency (emits on every scroll).
    // Isolated in its own StateFlow so ReaderScreen's top-level recomposition
    // is not triggered on every locator change.
    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _navigatorFactory = MutableStateFlow<EpubNavigatorFactory?>(null)
    val navigatorFactory: StateFlow<EpubNavigatorFactory?> = _navigatorFactory.asStateFlow()

    // Phase 4: Search state (separate StateFlow — NEVER #12)
    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    // Phase 4: Selection state (separate StateFlow — NEVER #12)
    private val _selectionState = MutableStateFlow(SelectionState())
    val selectionState: StateFlow<SelectionState> = _selectionState.asStateFlow()

    // Phase 4: Bookmarks observed from DB
    val bookmarks: StateFlow<List<BookmarkEntity>> = bookmarkRepository
        .observeBookmarks(bookUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // S-E: Pre-computed set of bookmarked hrefs to avoid JSON parsing on every scroll.
    val bookmarkedHrefs: StateFlow<Set<String>> = bookmarkRepository
        .observeBookmarks(bookUuid)
        .map { list ->
            list.filter { !it.isDeleted }
                .mapNotNull { it.locator.toLocator()?.href?.toString() }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Phase 4: Highlights observed from DB, pre-converted to Decoration list.
    // Exposed as a StateFlow so the Fragment can collect and apply directly —
    // avoids timing issues with SharedFlow commands (replay=0 would lose
    // initial emissions emitted before the Fragment starts collecting).
    val highlightDecorations: StateFlow<List<Decoration>> = highlightRepository
        .observeHighlights(bookUuid)
        .map { highlightList ->
            highlightList.mapNotNull { entity ->
                val locator = entity.locator.toLocator() ?: return@mapNotNull null
                val tint = try {
                    android.graphics.Color.parseColor(entity.color)
                } catch (e: Exception) {
                    HIGHLIGHT_COLOR_DEFAULT
                }
                Decoration(
                    id = entity.uuid,
                    locator = locator,
                    style = Decoration.Style.Highlight(tint = tint)
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Phase 5: Notes observed from DB
    val notes: StateFlow<List<NoteEntity>> = noteRepository
        .observeNotes(bookUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Phase 5: Aggregated knowledge state for the KnowledgePanel.
    // Combines highlights, notes, and bookmarks into a unified KnowledgeItem list.
    val knowledgeState: StateFlow<KnowledgeState> = combine(
        highlightRepository.observeHighlights(bookUuid),
        noteRepository.observeNotes(bookUuid),
        bookmarkRepository.observeBookmarks(bookUuid)
    ) { highlights, notesList, bookmarks ->
        val notesByHighlight = notesList.filter { it.highlightUuid != null }.associateBy { it.highlightUuid }

        val items = mutableListOf<KnowledgeItem>()

        for (h in highlights.filter { !it.isDeleted }) {
            val note = notesByHighlight[h.uuid]
            items.add(
                KnowledgeItem(
                    uuid = h.uuid,
                    type = KnowledgeItemType.HIGHLIGHT,
                    text = h.text,
                    noteText = note?.content,
                    chapterTitle = findChapterTitle(h.locator),
                    color = h.color,
                    createdAt = h.createdAt
                )
            )
        }

        for (n in notesList.filter { !it.isDeleted && it.highlightUuid == null }) {
            items.add(
                KnowledgeItem(
                    uuid = n.uuid,
                    type = KnowledgeItemType.NOTE,
                    text = n.content,
                    chapterTitle = findChapterTitle(n.locator),
                    createdAt = n.createdAt
                )
            )
        }

        for (b in bookmarks.filter { !it.isDeleted }) {
            items.add(
                KnowledgeItem(
                    uuid = b.uuid,
                    type = KnowledgeItemType.BOOKMARK,
                    text = b.label ?: "",
                    chapterTitle = findChapterTitle(b.locator),
                    createdAt = b.createdAt
                )
            )
        }

        items.sortBy { it.createdAt }

        KnowledgeState(
            items = items.toPersistentList(),
            highlightCount = highlights.count { !it.isDeleted },
            noteCount = notesList.count { !it.isDeleted },
            bookmarkCount = bookmarks.count { !it.isDeleted }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KnowledgeState())

    // Phase 5: Export state (Oracle S1 — presentation state in UI layer)
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    // Phase 5: Note editor state (Oracle M4 — UI StateFlow, not Fragment-bound commands)
    private val _noteEditorState = MutableStateFlow<NoteEditorState>(NoteEditorState.Hidden)
    val noteEditorState: StateFlow<NoteEditorState> = _noteEditorState.asStateFlow()

    // M1: Buffered SharedFlow for one-shot commands to Fragment.
    // DROP_OLDEST prevents suspension when no collector is active (e.g., during lifecycle transitions).
    private val _commands = MutableSharedFlow<ReaderCommand>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands = _commands.asSharedFlow()

    // Phase 6: TTS state (separate StateFlows — NEVER #12)
    private val _ttsPanelState = MutableStateFlow(TtsPanelState())
    val ttsPanelState: StateFlow<TtsPanelState> = _ttsPanelState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    /** TTS engine state forwarded from TtsController. */
    val ttsEngineState: StateFlow<TtsEngineState> = ttsController.engineState

    /** TTS playback state forwarded from TtsController. */
    val ttsPlaybackState: StateFlow<TtsPlaybackState> = ttsController.playbackState

    /** Current sentence index (state-driven highlighting — Oracle M2). */
    val ttsCurrentSentenceIndex: StateFlow<Int> = ttsController.currentSentenceIndex

    /** Highlight color for the current TTS sentence. Grey during normal playback, yellow during seeking. */
    private val _ttsHighlightColor = MutableStateFlow(TTS_HIGHLIGHT_GREY)
    val ttsHighlightColor: StateFlow<String> = _ttsHighlightColor.asStateFlow()

    /** Current generation ID (stale-highlight guard — Council M14). */
    val ttsGenerationId: StateFlow<String> = ttsController.generationId

    private var sleepTimerJob: Job? = null
    private var ttsNavigationJob: Job? = null

    /** Flag to distinguish TTS-driven navigation from user-driven (Oracle M7). */
    @Volatile
    private var isTtsNavigating = false

    /** Flag to prevent navigator.go() on initial TTS start (first sentence highlight). */
    @Volatile
    var isTtsStarting = false

    /** Current chapter href for sentence extraction + chapter transition. */
    @Volatile
    private var currentChapterHref: String = ""

    internal var publication: Publication? = null
        private set

    /** Derived EpubPreferences from preferences repository flow. */
    val epubPreferences: StateFlow<EpubPreferences> = preferencesRepository.preferences
        .map { it.toEpubPreferences() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppPreferences().toEpubPreferences()
        )

    /** Raw app preferences (for the settings panel UI). */
    val appPreferences: StateFlow<AppPreferences> = preferencesRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppPreferences()
        )

    /** Initial locator to restore reading position. */
    val initialLocator: Locator?
        get() {
            val saved = _currentLocator.value
            if (saved != null) return saved
            val pub = publication ?: return null
            return pub.readingOrder.firstOrNull()?.let { link ->
                pub.locatorFromLink(link)
            }
        }

    private var saveJob: Job? = null
    private var searchJob: Job? = null

    // S1: Store current TTS sentences for progress saving
    private var currentSentences: List<TtsSentence> = emptyList()

    /** CSS column count for the current chapter (1 = scroll mode, >1 = paginated). */
    var currentColCount: Int = 1
        private set

    /**
     * Returns the Locator for a given sentence index, used for page navigation
     * during TTS playback to ensure the viewport tracks the spoken sentence.
     */
    fun currentSentenceLocator(index: Int): Locator? {
        return currentSentences.getOrNull(index)?.locator
    }

    /**
     * Returns the column (page) index for a given sentence index, used to gate
     * auto-page-turn: only navigate when the sentence crosses a column boundary.
     * Returns -1 if the sentence is not found (caller should treat as "always navigate").
     */
    fun currentSentenceColIndex(index: Int): Int {
        return currentSentences.getOrNull(index)?.colIndex ?: -1
    }

    /**
     * Checks whether a locator's href matches the current chapter,
     * preventing accidental navigation to other resources (e.g., cover page).
     */
    fun isLocatorInCurrentChapter(locator: Locator): Boolean {
        val locHref = locator.href.toString()
            .substringBefore('#').substringBefore('?').trim('/')
        val curHref = currentChapterHref
            .substringBefore('#').substringBefore('?').trim('/')
        if (locHref == curHref) return true
        // Also try filename-only matching
        val locFilename = locHref.substringAfterLast('/')
        val curFilename = curHref.substringAfterLast('/')
        return locFilename == curFilename
    }

    // Parallel lists for navigation (Oracle S3: TocItem is display-only)
    private var tocLinks: List<Link> = emptyList()
    private var searchLocators: List<Locator> = emptyList()

    // M4: Distinguishes highlight vs bookmark when selection is retrieved
    private var pendingSelectionAction: SelectionAction? = null

    // Phase 5 (Oracle M4): Store locator + text for note editor dialog
    private var pendingNoteLocator: Locator? = null
    private var pendingNoteText: String = ""

    private sealed interface SelectionAction {
        object Highlight : SelectionAction
        object Bookmark : SelectionAction
        object Note : SelectionAction
    }

    init {
        viewModelScope.launch(exceptionHandler.handler) {
            openPublication()
        }
        // Highlight decorations are applied via highlightDecorations StateFlow,
        // collected directly by the Fragment (avoids SharedFlow timing issues).

        // Phase 6: Connect TTS controller (Oracle S4: init speed/pitch from prefs)
        viewModelScope.launch(exceptionHandler.handler) {
            val prefs = preferencesRepository.preferences.first()
            ttsController.connect(prefs.ttsRate, prefs.ttsPitch)
        }

        // Phase 6: Observe TTS engine state for language missing / error
        viewModelScope.launch(exceptionHandler.handler) {
            ttsEngineState.collect { state ->
                when (state) {
                    is TtsEngineState.LanguageMissing,
                    is TtsEngineState.Error -> {
                        // UI observes ttsEngineState directly to show dialog
                    }
                    else -> { /* no-op */ }
                }
            }
        }

        // Phase 6: Observe playback state for chapter transition + sleep timer (Oracle M8)
        viewModelScope.launch(exceptionHandler.handler) {
            ttsPlaybackState.collect { state ->
                when (state) {
                    is TtsPlaybackState.Ended -> {
                        // Sleep timer: end-of-chapter mode fires first
                        if (_sleepTimerState.value is SleepTimerState.EndOfChapter) {
                            stopTts()
                            _sleepTimerState.value = SleepTimerState.Inactive
                        } else {
                            handleChapterEnd()
                        }
                    }
                    else -> { /* no-op */ }
                }
            }
        }

        // Phase 6: Observe current sentence index for progress saving (M10) + panel sync
        viewModelScope.launch(exceptionHandler.handler) {
            ttsCurrentSentenceIndex
                .collect { index ->
                    _ttsPanelState.update { it.copy(currentSentence = index) }
                    if (index >= 0) {
                        saveTtsProgress(index)
                    }
                }
        }

        // Phase 6: Sync playback state to panel state
        viewModelScope.launch(exceptionHandler.handler) {
            ttsPlaybackState.collect { state ->
                _ttsPanelState.update { it.copy(playbackState = state) }
            }
        }
    }

    private suspend fun openPublication() {
        withContext(dispatchers.io) {
            val bookResult = bookRepository.getBook(bookUuid)
            bookResult.fold(
                onSuccess = { book ->
                    if (book == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = stringProvider.get(R.string.reader_error_open)
                            )
                        }
                        return@withContext
                    }

                    val file = File(book.filePath)
                    if (!file.exists()) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = stringProvider.get(R.string.reader_error_open)
                            )
                        }
                        return@withContext
                    }

                    val asset = assetRetriever.retrieve(file = file)
                        .getOrElse {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = stringProvider.get(R.string.reader_error_open)
                                )
                            }
                            return@withContext
                        }

                    val pub: Publication = publicationOpener.open(
                        asset = asset,
                        allowUserInteraction = false
                    )
                        .getOrElse {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = stringProvider.get(R.string.reader_error_open)
                                )
                            }
                            return@withContext
                        }

                    publication = pub

                    // M3: load saved locator from reading progress
                    val savedProgress = readingProgressRepository
                        .getProgress("progress_${bookUuid}")
                    val savedLocator = savedProgress.getOrNull()?.locator?.toLocator()

                    if (savedLocator != null) {
                        _currentLocator.value = savedLocator
                    }

                    // Phase 4: Load TOC
                    loadToc(pub)
                    android.util.Log.d("ReaderVM", "TOC loaded: ${tocLinks.size} entries, tocItems=${_uiState.value.toc.size}")

                    // Build EpubNavigatorFactory
                    val factory = EpubNavigatorFactory(pub)
                    android.util.Log.d("ReaderVM", "EpubNavigatorFactory created, initialPrefs scroll=${epubPreferences.value.scroll}")

                    _navigatorFactory.value = factory
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            bookTitle = pub.metadata.title,
                            navigatorFactoryReady = true
                        )
                    }
                },
                onFailure = { cause, _ ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = stringProvider.get(R.string.reader_error_open)
                        )
                    }
                }
            )
        }
    }

    // -- Phase 4: TOC --

    private fun loadToc(pub: Publication) {
        val links = mutableListOf<Link>()
        val items = mutableListOf<TocItem>()
        flattenToc(pub.tableOfContents, 0, links, items)
        tocLinks = links
        _uiState.update { it.copy(toc = items.toPersistentList()) }
    }

    private fun flattenToc(
        links: List<Link>,
        level: Int,
        accLinks: MutableList<Link>,
        accItems: MutableList<TocItem>
    ) {
        for (link in links) {
            val title = link.title ?: link.href.toString()
            accLinks.add(link)
            accItems.add(TocItem(title = title, level = level))
            if (link.children.isNotEmpty()) {
                flattenToc(link.children, level + 1, accLinks, accItems)
            }
        }
    }

    fun toggleTocDrawer() {
        _uiState.update { it.copy(isTocDrawerOpen = !it.isTocDrawerOpen) }
    }

    fun closeTocDrawer() {
        _uiState.update { it.copy(isTocDrawerOpen = false) }
    }

    fun navigateToTocItem(index: Int) {
        val link = tocLinks.getOrNull(index) ?: return
        android.util.Log.d("ReaderVM", "navigateToTocItem index=$index link=${link.href}")
        _commands.tryEmit(ReaderCommand.NavigateToLink(link))
        android.util.Log.d("ReaderVM", "navigateToTocItem emitted NavigateToLink")
        closeTocDrawer()
        hideToolbar()
    }

    // -- Phase 4: Search --

    fun toggleSearchPanel() {
        val willClose = _uiState.value.isSearchPanelOpen
        _uiState.update {
            it.copy(
                isSearchPanelOpen = !it.isSearchPanelOpen,
                // Oracle S3: close knowledge panel when opening search panel
                isKnowledgePanelOpen = if (!willClose) false else it.isKnowledgePanelOpen
            )
        }
        // S-C: Clear search decorations when closing via toggle icon
        if (willClose) {
            clearSearch()
        }
    }

    fun closeSearchPanel() {
        _uiState.update { it.copy(isSearchPanelOpen = false) }
        clearSearch()
    }

    @OptIn(ExperimentalReadiumApi::class, Search::class)
    fun search(query: String) {
        // S-A: Update query immediately so the text field stays in sync during debounce.
        _searchState.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(exceptionHandler.handler) {
            // S4: debounce 300ms to avoid starting a new search per keystroke
            delay(300L)

            _searchState.update {
                it.copy(
                    isSearching = true,
                    results = persistentListOf(),
                    currentIndex = -1,
                    error = null
                )
            }

            if (query.isBlank()) {
                clearSearchResults()
                return@launch
            }

            val pub = publication ?: run {
                _searchState.update {
                    it.copy(isSearching = false, error = stringProvider.get(R.string.reader_search_unavailable))
                }
                return@launch
            }

            // M5 (NEVER #26): wrap search in try-catch
            val iterator = try {
                pub.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchState.update {
                    it.copy(isSearching = false, error = stringProvider.get(R.string.reader_search_error))
                }
                return@launch
            }

            if (iterator == null) {
                _searchState.update {
                    it.copy(isSearching = false, error = stringProvider.get(R.string.reader_search_unavailable))
                }
                return@launch
            }

            try {
                val results = mutableListOf<SearchResult>()
                val locators = mutableListOf<Locator>()

                while (true) {
                    val collection = iterator.next()
                        .getOrElse { // NEVER #14: functional Try handling
                            _searchState.update {
                                it.copy(isSearching = false, error = stringProvider.get(R.string.reader_search_error))
                            }
                            return@launch
                        }

                    if (collection == null) break // end of results

                    for (locator in collection.locators) {
                        val text = locator.text
                        results.add(
                            SearchResult(
                                text = text.highlight ?: "",
                                before = text.before ?: "",
                                after = text.after ?: ""
                            )
                        )
                        locators.add(locator)
                    }
                }

                searchLocators = locators
                _searchState.update {
                    it.copy(isSearching = false, results = results.toPersistentList())
                }

                // Apply search result decorations (underline style)
                val decorations = locators.mapIndexed { index, locator ->
                    Decoration(
                        id = "search-$index",
                        locator = locator,
                        style = Decoration.Style.Underline(tint = SEARCH_HIGHLIGHT_COLOR)
                    )
                }
                _commands.tryEmit(ReaderCommand.ApplyDecorations(decorations, SEARCH_DECORATION_GROUP))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchState.update {
                    it.copy(isSearching = false, error = stringProvider.get(R.string.reader_search_error))
                }
            } finally {
                // S8: close iterator in finally; no separate field to prevent double-close
                try { iterator.close() } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    fun navigateToSearchResult(index: Int) {
        val locator = searchLocators.getOrNull(index) ?: return
        _searchState.update { it.copy(currentIndex = index) }
        _commands.tryEmit(ReaderCommand.NavigateToLocator(locator))
        hideToolbar()
    }

    fun clearSearch() {
        searchJob?.cancel()
        clearSearchResults()
    }

    private fun clearSearchResults() {
        searchLocators = emptyList()
        _searchState.update {
            it.copy(isSearching = false, results = persistentListOf(), currentIndex = -1, error = null)
        }
        _commands.tryEmit(ReaderCommand.ApplyDecorations(emptyList(), SEARCH_DECORATION_GROUP))
    }

    // -- Phase 4: Selection & Highlight --

    /** Called from JS bridge (via Fragment) when text selection changes. */
    fun onSelectionChanged(text: String) {
        if (text.isBlank()) {
            _selectionState.update { it.copy(isActive = false, text = "") }
        } else {
            _selectionState.update { it.copy(isActive = true, text = text) }
        }
    }

    fun dismissSelection() {
        _selectionState.update { it.copy(isActive = false, text = "") }
        _commands.tryEmit(ReaderCommand.ClearSelection)
    }

    fun requestHighlight() {
        pendingSelectionAction = SelectionAction.Highlight
        _commands.tryEmit(ReaderCommand.RequestCurrentSelection)
    }

    fun requestBookmarkSelection() {
        pendingSelectionAction = SelectionAction.Bookmark
        _commands.tryEmit(ReaderCommand.RequestCurrentSelection)
    }

    fun requestNote() {
        pendingSelectionAction = SelectionAction.Note
        _commands.tryEmit(ReaderCommand.RequestCurrentSelection)
    }

    /** Called from Fragment after navigator.currentSelection() completes. */
    fun onSelectionRetrieved(selection: Selection?) {
        val action = pendingSelectionAction
        pendingSelectionAction = null

        if (selection == null || action == null) {
            _commands.tryEmit(ReaderCommand.ClearSelection)
            _selectionState.update { it.copy(isActive = false, text = "") }
            return
        }

        val locator = selection.locator
        val text = locator.text.highlight ?: ""
        val now = System.currentTimeMillis()

        when (action) {
            SelectionAction.Highlight -> {
                viewModelScope.launch(exceptionHandler.handler) {
                    val result = highlightRepository.addHighlight(
                        HighlightEntity(
                            uuid = UUID.randomUUID().toString(),
                            bookUuid = bookUuid,
                            locator = locator.toJsonString(),
                            text = text,
                            color = HIGHLIGHT_COLOR_HEX,
                            createdAt = now,
                            updatedAt = now,
                            isDeleted = false
                        )
                    )
                    result.fold(
                        onSuccess = { /* highlights flow auto-updates decorations */ },
                        onFailure = { cause, _ ->
                            errorChannel.tryEmit(AppError(message = cause.message ?: "Failed to add highlight", cause = cause))
                        }
                    )
                }
            }
            SelectionAction.Bookmark -> {
                viewModelScope.launch(exceptionHandler.handler) {
                    val result = bookmarkRepository.addBookmark(
                        BookmarkEntity(
                            uuid = UUID.randomUUID().toString(),
                            bookUuid = bookUuid,
                            locator = locator.toJsonString(),
                            label = text.take(50),
                            createdAt = now,
                            updatedAt = now,
                            isDeleted = false
                        )
                    )
                    result.fold(
                        onSuccess = { },
                        onFailure = { cause, _ ->
                            errorChannel.tryEmit(AppError(message = cause.message ?: "Failed to add bookmark", cause = cause))
                        }
                    )
                }
            }
            SelectionAction.Note -> {
                // Oracle M4: Show NoteEditorDialog via UI StateFlow, not Fragment commands.
                // Store locator + text for later use in createNote().
                // Do NOT clear selection — defer to dialog save/cancel.
                pendingNoteLocator = locator
                pendingNoteText = text
                _noteEditorState.value = NoteEditorState.Editing(selectedText = text)
                return
            }
        }

        // S7: Clear native selection after creating highlight/bookmark
        _commands.tryEmit(ReaderCommand.ClearSelection)
        _selectionState.update { it.copy(isActive = false, text = "") }
    }

    // -- Phase 5: Note Editor (Oracle M4) --

    /** Called from NoteEditorDialog when user saves a note. */
    fun createNote(content: String) {
        val locator = pendingNoteLocator ?: return
        val text = pendingNoteText
        val now = System.currentTimeMillis()

        viewModelScope.launch(exceptionHandler.handler) {
            // Oracle S2: Create highlight first, then note with highlightUuid.
            // If highlight fails, don't create note (FK constraint).
            val highlightUuid = UUID.randomUUID().toString()
            val highlightResult = highlightRepository.addHighlight(
                HighlightEntity(
                    uuid = highlightUuid,
                    bookUuid = bookUuid,
                    locator = locator.toJsonString(),
                    text = text,
                    color = HIGHLIGHT_COLOR_HEX,
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false
                )
            )
            highlightResult.fold(
                onSuccess = {
                    val noteResult = noteRepository.addNote(
                        NoteEntity(
                            uuid = UUID.randomUUID().toString(),
                            bookUuid = bookUuid,
                            highlightUuid = highlightUuid,
                            locator = locator.toJsonString(),
                            content = content,
                            createdAt = now,
                            updatedAt = now,
                            isDeleted = false
                        )
                    )
                    noteResult.fold(
                        onSuccess = {
                            // Oracle S1: clear pending fields + close dialog only on success
                            _noteEditorState.value = NoteEditorState.Hidden
                            _commands.tryEmit(ReaderCommand.ClearSelection)
                            _selectionState.update { it.copy(isActive = false, text = "") }
                            pendingNoteLocator = null
                            pendingNoteText = ""
                        },
                        onFailure = { cause, _ ->
                            errorChannel.tryEmit(AppError(message = cause.message ?: "Failed to add note", cause = cause))
                        }
                    )
                },
                onFailure = { cause, _ ->
                    errorChannel.tryEmit(AppError(message = cause.message ?: "Failed to add highlight", cause = cause))
                }
            )
        }
    }

    /** Called from NoteEditorDialog when user cancels. */
    fun cancelNoteEditor() {
        _noteEditorState.value = NoteEditorState.Hidden
        _commands.tryEmit(ReaderCommand.ClearSelection)
        _selectionState.update { it.copy(isActive = false, text = "") }
        pendingNoteLocator = null
        pendingNoteText = ""
    }

    // -- Phase 4: Bookmark (chapter-level toggle) --

    fun toggleBookmark() {
        val locator = _currentLocator.value ?: return
        val currentHref = locator.href.toString()
        val now = System.currentTimeMillis()

        // Check if current page (href) is already bookmarked
        val existing = bookmarks.value.find { bookmark ->
            !bookmark.isDeleted && bookmark.locator.toLocator()?.href?.toString() == currentHref
        }

        if (existing != null) {
            viewModelScope.launch(exceptionHandler.handler) {
                bookmarkRepository.softDeleteBookmark(existing.uuid)
            }
        } else {
            viewModelScope.launch(exceptionHandler.handler) {
                bookmarkRepository.addBookmark(
                    BookmarkEntity(
                        uuid = UUID.randomUUID().toString(),
                        bookUuid = bookUuid,
                        locator = locator.toJsonString(),
                        label = locator.title,
                        createdAt = now,
                        updatedAt = now,
                        isDeleted = false
                    )
                )
            }
        }
    }

    // -- Toolbar & Settings --

    /** Toggles the top toolbar visibility (called on center-area tap). */
    fun toggleToolbar() {
        _uiState.update { it.copy(isToolbarVisible = !it.isToolbarVisible) }
    }

    /** Hides the top toolbar. */
    fun hideToolbar() {
        _uiState.update { it.copy(isToolbarVisible = false) }
    }

    fun toggleSettingsPanel() {
        val willOpen = !_uiState.value.isSettingsPanelOpen
        _uiState.update {
            it.copy(
                isSettingsPanelOpen = willOpen,
                isSearchPanelOpen = if (willOpen) false else it.isSearchPanelOpen,
                isKnowledgePanelOpen = if (willOpen) false else it.isKnowledgePanelOpen
            )
        }
    }

    fun closeSettingsPanel() {
        _uiState.update { it.copy(isSettingsPanelOpen = false) }
    }

    /**
     * Called from the JS bridge when the user taps the center of the reading area.
     * Dismisses an active selection instead of toggling the toolbar, and ignores
     * taps while a panel is open.
     */
    fun onCenterTap() {
        if (_selectionState.value.isActive) {
            dismissSelection()
            return
        }
        if (_uiState.value.isSearchPanelOpen ||
            _uiState.value.isKnowledgePanelOpen ||
            _uiState.value.isSettingsPanelOpen ||
            _ttsPanelState.value.isPanelOpen
        ) {
            return
        }
        toggleToolbar()
    }

    // -- Reader settings setters --

    fun setFontSize(value: Float) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setFontSize(value)
        }
    }

    fun setFontFamily(value: String) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setFontFamily(value)
        }
    }

    fun setTheme(value: com.epubreader.app.data.prefs.ThemeMode) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setTheme(value)
        }
    }

    fun setLineSpacing(value: Float) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setLineSpacing(value)
        }
    }

    fun setParagraphSpacing(value: Float) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setParagraphSpacing(value)
        }
    }

    fun setParagraphIndent(value: Float) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setParagraphIndent(value)
        }
    }

    fun setPageMargins(value: Float) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setPageMargins(value)
        }
    }

    fun setScrollMode(value: Boolean) {
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setScrollMode(value)
        }
    }

    // -- Phase 5: Knowledge Panel --

    fun toggleKnowledgePanel() {
        val willOpen = !_uiState.value.isKnowledgePanelOpen
        // Oracle MF1: capture search state BEFORE update (update sets it to false)
        val searchWasOpen = _uiState.value.isSearchPanelOpen
        _uiState.update {
            it.copy(
                isKnowledgePanelOpen = willOpen,
                // Oracle S3: close search panel when opening knowledge panel
                isSearchPanelOpen = if (willOpen) false else it.isSearchPanelOpen
            )
        }
        if (willOpen && searchWasOpen) {
            clearSearch()
        }
    }

    fun closeKnowledgePanel() {
        _uiState.update { it.copy(isKnowledgePanelOpen = false) }
    }

    /** Deletes a highlight, note, or bookmark from the KnowledgePanel. */
    fun deleteKnowledgeItem(item: KnowledgeItem) {
        viewModelScope.launch(exceptionHandler.handler) {
            val result = when (item.type) {
                KnowledgeItemType.HIGHLIGHT -> highlightRepository.softDeleteHighlight(item.uuid)
                KnowledgeItemType.NOTE -> noteRepository.softDeleteNote(item.uuid)
                KnowledgeItemType.BOOKMARK -> bookmarkRepository.softDeleteBookmark(item.uuid)
            }
            result.fold(
                onSuccess = { /* flows auto-update knowledgeState */ },
                onFailure = { cause, _ ->
                    errorChannel.tryEmit(AppError(message = cause.message ?: "Failed to delete", cause = cause))
                }
            )
        }
    }

    // -- Phase 5: Export --

    /** Prepares Markdown export using fresh one-shot queries (D6). */
    fun prepareExport() {
        viewModelScope.launch(exceptionHandler.handler) {
            _exportState.value = ExportState.Preparing
            try {
                // D6: One-shot queries guarantee fresh data (StateFlow.value may be stale)
                val highlights = highlightRepository.getByBook(bookUuid).getOrNull() ?: emptyList()
                val notesList = noteRepository.getByBook(bookUuid).getOrNull() ?: emptyList()
                val bookmarks = bookmarkRepository.getByBook(bookUuid).getOrNull() ?: emptyList()
                val book = bookRepository.getBook(bookUuid).getOrNull()
                val bookTitle = book?.title ?: "Untitled"

                // Build ExportTocItem list from parallel tocLinks + uiState.toc
                val tocItems = _uiState.value.toc
                val exportToc = tocLinks.mapIndexed { index, link ->
                    val title = link.title ?: link.href.toString()
                    val level = tocItems.getOrNull(index)?.level ?: 1
                    ExportTocItem(title = title, href = link.href.toString(), level = level)
                }

                // Map entities to plain export models (Oracle M1 — core/ decoupled from entities)
                val exportHighlights = highlights.map {
                    ExportHighlight(text = it.text, locatorJson = it.locator, color = it.color)
                }
                val exportNotes = notesList.map { n ->
                    val highlightText = n.highlightUuid?.let { hUuid ->
                        highlights.find { it.uuid == hUuid }?.text
                    }
                    ExportNote(content = n.content, highlightText = highlightText, locatorJson = n.locator)
                }
                val exportBookmarks = bookmarks.map {
                    ExportBookmark(label = it.label, locatorJson = it.locator)
                }

                val request = ExportRequest(
                    bookTitle = bookTitle,
                    toc = exportToc,
                    highlights = exportHighlights,
                    notes = exportNotes,
                    bookmarks = exportBookmarks
                )

                val markdown = withContext(dispatchers.default) {
                    markdownExporter.exportToMarkdown(request)
                }

                // Oracle S5: Sanitize filename
                val fileName = sanitizeFileName(bookTitle) + "-annotations.md"
                _exportState.value = ExportState.Ready(content = markdown, suggestedFileName = fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportState.value = ExportState.Error(message = e.message ?: "Export failed")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    // -- Phase 5: Helpers --

    /** Matches a locator JSON href to TOC chapter title (Oracle M2: fragment normalization). */
    private fun findChapterTitle(locatorJson: String?): String? {
        if (locatorJson == null) return null
        val locator = locatorJson.toLocator() ?: return null
        val href = locator.href.toString().substringBefore('#').substringBefore('?').trimEnd('/')
        val link = tocLinks.find {
            it.href.toString().substringBefore('#').substringBefore('?').trimEnd('/') == href
        }
        return link?.title
    }

    /** Oracle S5: Sanitize book title for use as filename. */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
    }

    // -- Existing: Locator & progress --

    fun onLocatorChanged(locator: Locator) {
        _currentLocator.value = locator
        saveJob?.cancel()
        saveJob = viewModelScope.launch(exceptionHandler.handler) {
            delay(1000L) // debounce 1 second
            val now = System.currentTimeMillis()
            readingProgressRepository.saveProgress(
                ReadingProgressEntity(
                    uuid = "progress_${bookUuid}",
                    bookUuid = bookUuid,
                    locator = locator.toJsonString(),
                    progress = locator.locations.totalProgression ?: 0.0,
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false
                )
            )
        }
    }

    // -- Phase 6: TTS --

    /**
     * Smart toggle for the top-bar TTS button.
     * - Idle/Ended: open panel + start TTS
     * - Playing: pause
     * - Paused: resume
     */
    fun toggleTtsPlayback() {
        when (val state = ttsPlaybackState.value) {
            is TtsPlaybackState.Idle, is TtsPlaybackState.Ended -> {
                _ttsPanelState.update { it.copy(isPanelOpen = true) }
                startTts()
            }
            is TtsPlaybackState.Playing -> pauseTts()
            is TtsPlaybackState.Paused -> resumeTts()
        }
    }

    /** Opens the TTS control panel. */
    fun toggleTtsPanel() {
        _ttsPanelState.update { it.copy(isPanelOpen = !it.isPanelOpen) }
    }

    fun closeTtsPanel() {
        _ttsPanelState.update { it.copy(isPanelOpen = false) }
    }

    /**
     * Starts TTS playback: emits ExtractSentences command to Fragment.
     * The Fragment evaluates JS, which calls back [onSentencesExtracted].
     * Locks UserSettings (Council M12).
     */
    fun startTts() {
        Log.i("ReaderVM", "TTS: start requested, emitting ExtractSentences")
        _ttsPanelState.update { it.copy(isSettingsLocked = true) }
        // Ensure currentChapterHref is set from current locator before TTS starts
        if (currentChapterHref.isBlank()) {
            val locator = _currentLocator.value
            if (locator != null) {
                currentChapterHref = locator.href.toString()
                Log.i("ReaderVM", "TTS: initialized currentChapterHref from locator: $currentChapterHref")
            }
        }
        isTtsStarting = true
        // Observe the actual first sentence index change (happens asynchronously
        // in speakCurrentSentence() after engine becomes Ready), then clear the flag.
        // This prevents the Fragment's combine collector from calling navigator.go()
        // on the initial sentence (index 0) which would jump the viewport.
        ttsNavigationJob?.cancel()
        ttsNavigationJob = viewModelScope.launch(exceptionHandler.handler) {
            try {
                withTimeout(5000L) {
                    ttsCurrentSentenceIndex.first { it >= 0 }
                }
                // Fix B: let the Fragment's combine collector process the first sentence
                // index while isTtsStarting is still true, so navigator.go() is suppressed
                // for the initial sentence (avoids viewport jump on TTS start).
                delay(150)
            } catch (_: TimeoutCancellationException) {
                Log.w("ReaderVM", "TTS: timed out waiting for first sentence — clearing isTtsStarting")
            }
            isTtsStarting = false
        }
        _commands.tryEmit(ReaderCommand.ExtractSentences)
    }

    /** Pauses TTS playback. */
    fun pauseTts() {
        ttsController.pause()
    }

    /** Resumes paused TTS playback without re-extracting sentences. */
    fun resumeTts() {
        if (ttsPlaybackState.value !is TtsPlaybackState.Paused) {
            Log.w("ReaderVM", "TTS: resumeTts() called but not paused — ignoring")
            return
        }
        ttsController.resume()
    }

    /**
     * Stops TTS playback and unlocks UserSettings.
     * Called on: explicit user stop, book switch, end of book.
     */
    fun stopTts() {
        ttsController.stop()
        _ttsPanelState.update { it.copy(isSettingsLocked = false, currentSentence = -1, totalSentences = 0, isPanelOpen = false) }
        _ttsHighlightColor.value = TTS_HIGHLIGHT_GREY
        _commands.tryEmit(ReaderCommand.ClearTtsHighlight)
        cancelSleepTimer()
    }

    fun seekTtsBackward() {
        val current = ttsCurrentSentenceIndex.value
        if (current > 0) {
            ttsController.seekToSentence(current - 1)
            // Show yellow highlight briefly to help user locate new position
            _ttsHighlightColor.value = TTS_HIGHLIGHT_YELLOW
            viewModelScope.launch(exceptionHandler.handler) {
                delay(500L)
                _ttsHighlightColor.value = TTS_HIGHLIGHT_GREY
            }
        }
    }

    fun seekTtsForward() {
        val current = ttsCurrentSentenceIndex.value
        val total = _ttsPanelState.value.totalSentences
        if (current < total - 1) {
            ttsController.seekToSentence(current + 1)
            // Show yellow highlight briefly to help user locate new position
            _ttsHighlightColor.value = TTS_HIGHLIGHT_YELLOW
            viewModelScope.launch(exceptionHandler.handler) {
                delay(500L)
                _ttsHighlightColor.value = TTS_HIGHLIGHT_GREY
            }
        }
    }

    fun setTtsSpeed(speed: Float) {
        _ttsPanelState.update { it.copy(speed = speed) }
        ttsController.setSpeed(speed)
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setTtsRate(speed)
        }
    }

    fun setTtsPitch(pitch: Float) {
        _ttsPanelState.update { it.copy(pitch = pitch) }
        ttsController.setPitch(pitch)
        viewModelScope.launch(exceptionHandler.handler) {
            preferencesRepository.setTtsPitch(pitch)
        }
    }

    /**
     * Called from Fragment (BridgeCallback) when JS sentence extraction completes.
     * Parses JSON on Dispatchers.Default (Oracle S5), assembles Locators (Architect M10),
     * and starts playback.
     */
    fun onSentencesExtracted(json: String) {
        Log.i("ReaderVM", "TTS: onSentencesExtracted, json length=${json.length}")
        viewModelScope.launch(exceptionHandler.handler) {
            val (sentences, startIndex) = withContext(dispatchers.default) {
                parseSentencesWithStartIndex(json)
            }
            Log.i("ReaderVM", "TTS: parsed ${sentences.size} sentences, startIndex=$startIndex")
            if (sentences.isEmpty()) {
                errorChannel.tryEmit(
                    AppError("TTS: no sentences extracted from current chapter")
                )
                return@launch
            }

            _ttsPanelState.update {
                it.copy(totalSentences = sentences.size, currentSentence = -1)
            }

            currentSentences = sentences

            val chapterTitle = findChapterTitle(currentChapterHref) ?: ""
            val bookTitle = publication?.metadata?.title ?: ""
            ttsController.play(sentences, chapterTitle, bookTitle, startIndex)
        }
    }

    /**
     * Parses JS-extracted JSON into [TtsSentence] list + the first visible sentence index.
     *
     * Supports two JSON formats:
     * - New (Fix A): {"firstVisibleSentenceId": N, "sentences": [{id, text, href, progression, cssSelector}, ...]}
     * - Legacy: [{id, text, href, progression}, ...]  (bare array — startIndex defaults to 0)
     *
     * Kotlin assembles full Locator using publication.readingOrder (Architect M10).
     * cssSelector (Fix D) is stored in Locator.Locations.otherLocations for precise navigation.
     */
    private fun parseSentencesWithStartIndex(json: String): Pair<List<TtsSentence>, Int> {
        val pub = publication ?: return emptyList<TtsSentence>() to 0
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                // Legacy bare array format — no colCount, default to 1 (scroll mode)
                val array = JSONArray(json)
                currentColCount = 1
                parseSentenceArray(pub, array) to 0
            } else {
                // New object format: {firstVisibleSentenceId, colCount, sentences: [...]}
                val root = JSONObject(json)
                val startIndex = root.optInt("firstVisibleSentenceId", 0)
                    .coerceAtLeast(0)
                currentColCount = root.optInt("colCount", 1)
                val array = root.optJSONArray("sentences") ?: JSONArray()
                parseSentenceArray(pub, array) to startIndex
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Failed to parse sentences JSON", e)
            emptyList<TtsSentence>() to 0
        }
    }

    /** Parses a JSONArray of sentence objects into [TtsSentence] list. */
    private fun parseSentenceArray(pub: Publication, array: JSONArray): List<TtsSentence> {
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val id = obj.getInt("id")
            val text = obj.getString("text")
            val href = obj.optString("href", "")
            val progression = obj.optDouble("progression", 0.0)
            val cssSelector = obj.optString("cssSelector", "").ifBlank { null }
            val colIndex = obj.optInt("colIndex", 0)
            val locator = buildLocator(pub, href, progression, cssSelector, text)
            if (locator == null) {
                Log.w("ReaderVM", "parseSentenceArray: null locator for sentence $id (href='$href', progression=$progression)")
            }
            TtsSentence(id = id, text = text, locator = locator, colIndex = colIndex)
        }
    }

    /** Assembles a Readium Locator from href + progression (Architect guide). */
    private fun buildLocator(pub: Publication, href: String, progression: Double, cssSelector: String? = null, sentenceText: String? = null): Locator? {
        val normalizedHref = href
            .substringBefore('#')
            .substringBefore('?')
            .trim('/')
        // Remove leading path segments to get just the filename for matching
        val filename = normalizedHref.substringAfterLast('/')

        val link = pub.readingOrder.find {
            val linkHref = it.href.toString()
                .substringBefore('#')
                .substringBefore('?')
                .trim('/')
            linkHref == normalizedHref || linkHref == filename ||
                linkHref.endsWith("/$filename") || linkHref.endsWith("\\$filename")
        }
        if (link == null) {
            Log.w("ReaderVM", "buildLocator: no match for href='$href' (normalized='$normalizedHref', filename='$filename') in readingOrder")
        }

        // Use the matched link, or current chapter's link if available, or first reading order entry
        val resolvedLink = link
            ?: pub.readingOrder.find {
                val linkHref = it.href.toString().substringBefore('#').substringBefore('?').trim('/')
                val curHref = currentChapterHref.substringBefore('#').substringBefore('?').trim('/')
                // currentChapterHref may be a full URL; compare by filename as well
                linkHref == curHref ||
                    linkHref.substringAfterLast('/') == curHref.substringAfterLast('/') ||
                    linkHref.endsWith("/${curHref.substringAfterLast('/')}")
            }
            ?: run {
                Log.w("ReaderVM", "buildLocator: all fallbacks exhausted for href='$href', currentChapterHref='$currentChapterHref' — returning null")
                return null
            }

        val baseLocator = pub.locatorFromLink(resolvedLink) ?: return null
        // Fix D: store cssSelector in otherLocations for precise element-level navigation.
        // Readium's navigator.go() can use this to scroll directly to the element,
        // avoiding progression rounding errors in CSS multi-column layout.
        val otherLocations = if (cssSelector != null) {
            baseLocator.locations.otherLocations + ("cssSelector" to cssSelector)
        } else {
            baseLocator.locations.otherLocations
        }
        // Populate text.highlight so Readium's navigator.go() uses the idempotent
        // scrollToLocator path (TextQuoteAnchor) instead of falling through to the
        // non-idempotent progression-based setCurrentItem. When sentenceText is
        // null (legacy extraction format), keep the base locator's text unchanged.
        return baseLocator.copy(
            text = if (sentenceText != null) Locator.Text(highlight = sentenceText) else baseLocator.text,
            locations = baseLocator.locations.copy(
                progression = progression,
                otherLocations = otherLocations
            )
        )
    }

    /**
     * Handles end-of-chapter: navigate to next chapter or stop at book end (Oracle M8).
     * Sets isTtsNavigating to distinguish from user navigation (Oracle M7).
     */
    private fun handleChapterEnd() {
        val pub = publication ?: return
        val currentHref = currentChapterHref
        Log.i("ReaderVM", "TTS: handleChapterEnd currentHref='$currentHref'")
        val nextLink = nextReadingOrderLink(pub, currentHref)

        if (nextLink == null) {
            // End of book
            Log.i("ReaderVM", "TTS reached end of book")
            stopTts()
            return
        }

        Log.i("ReaderVM", "TTS: navigating to next chapter: ${nextLink.href}")
        // Navigate to next chapter
        isTtsNavigating = true
        _commands.tryEmit(ReaderCommand.ClearTtsHighlight)
        _commands.tryEmit(ReaderCommand.NavigateToLink(nextLink))
        // Fragment will detect href change; sentence extraction happens on new chapter load
    }

    /**
     * Finds the next readingOrder link after [currentHref] (Oracle M8).
     * Normalizes leading slashes, fragments, query params, and trailing slashes
     * to match against reading order entries.
     */
    private fun nextReadingOrderLink(pub: Publication, currentHref: String): Link? {
        if (currentHref.isBlank()) return pub.readingOrder.firstOrNull()
        val normalized = currentHref
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .trimStart('/')
        val index = pub.readingOrder.indexOfFirst {
            it.href.toString()
                .substringBefore('#')
                .substringBefore('?')
                .trimEnd('/')
                .trimStart('/') == normalized
        }
        if (index < 0 || index >= pub.readingOrder.size - 1) return null
        return pub.readingOrder[index + 1]
    }

    /**
     * Called by Fragment when href changes (Oracle M7).
     * If TTS is playing and href changed due to user navigation (not TTS-driven),
     * stop TTS and re-extract for the new chapter.
     */
    fun onChapterChanged(href: String) {
        currentChapterHref = href
        if (isTtsNavigating) {
            isTtsNavigating = false
            // Fix C: re-arm isTtsStarting to suppress navigator.go() for the first
            // sentence of the new chapter, letting CSS columns settle before any
            // page navigation. Use generationId to detect the new play() session
            // (the old chapter's sentence index may still be >= 0).
            isTtsStarting = true
            val prevGenId = ttsGenerationId.value
            ttsNavigationJob?.cancel()
            ttsNavigationJob = viewModelScope.launch(exceptionHandler.handler) {
                try {
                    withTimeout(8000L) {
                        // Wait for new generation (play() sets a new genId after extraction)
                        ttsGenerationId.first { it != prevGenId }
                        // Then wait for the first sentence index of the new chapter
                        ttsCurrentSentenceIndex.first { it >= 0 }
                    }
                    delay(150)
                } catch (_: TimeoutCancellationException) {
                    Log.w("ReaderVM", "TTS: chapter transition timed out waiting for first sentence")
                }
                isTtsStarting = false
            }
            // TTS-driven navigation: extract sentences for new chapter
            _commands.tryEmit(ReaderCommand.ExtractSentences)
        } else if (ttsPlaybackState.value is TtsPlaybackState.Playing ||
            ttsPlaybackState.value is TtsPlaybackState.Paused
        ) {
            // User-driven navigation mid-TTS (Oracle M7): stop + re-extract
            android.util.Log.i("ReaderViewModel", "User navigation mid-TTS — re-extracting")
            ttsController.stop()
            _commands.tryEmit(ReaderCommand.ClearTtsHighlight)
            _commands.tryEmit(ReaderCommand.ExtractSentences)
        }
    }

    /** Saves reading progress from current TTS sentence locator (M10). */
    private fun saveTtsProgress(sentenceIndex: Int) {
        val sentence = currentSentences.getOrNull(sentenceIndex)
        val locator = sentence?.locator ?: return
        val pub = publication ?: return

        saveJob?.cancel()
        saveJob = viewModelScope.launch(exceptionHandler.handler) {
            delay(1000L)
            val now = System.currentTimeMillis()
            readingProgressRepository.saveProgress(
                ReadingProgressEntity(
                    uuid = "progress_${bookUuid}",
                    bookUuid = bookUuid,
                    locator = locator.toJsonString(),
                    progress = locator.locations.totalProgression ?: 0.0,
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false
                )
            )
        }
    }

    // -- Phase 6: Sleep Timer (Oracle D7) --

    fun startSleepTimer(durationMs: Long) {
        cancelSleepTimer()
        _sleepTimerState.value = SleepTimerState.Active(durationMs, durationMs)
        sleepTimerJob = viewModelScope.launch(exceptionHandler.handler) {
            val deadline = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                _sleepTimerState.value = SleepTimerState.Active(remaining, durationMs)
                delay(1000L)
            }
            _sleepTimerState.value = SleepTimerState.Inactive
            pauseTts()
        }
    }

    fun startSleepTimerEndOfChapter() {
        _sleepTimerState.value = SleepTimerState.EndOfChapter
        // Fires when TtsPlaybackState.Ended is observed (handled in init block)
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.Inactive
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel() // triggers finally → iterator.close()
        sleepTimerJob?.cancel()
        ttsNavigationJob?.cancel()
        // Architect M3: disconnect only — do NOT stop playback.
        // The service continues in background via foreground notification.
        // stop() is called only on: explicit user stop, book switch, end of book.
        ttsController.disconnect()
        publication?.close()
        publication = null
    }

    companion object {
        private val HIGHLIGHT_COLOR_DEFAULT = android.graphics.Color.YELLOW
        private val SEARCH_HIGHLIGHT_COLOR = android.graphics.Color.RED
        private const val HIGHLIGHT_COLOR_HEX = "#FFFF00"
        private const val HIGHLIGHT_DECORATION_GROUP = "highlights"
        private const val SEARCH_DECORATION_GROUP = "search"
        private const val TTS_HIGHLIGHT_GREY = "rgba(180,180,180,0.55)"
        private const val TTS_HIGHLIGHT_YELLOW = "#FFEB3B"
    }
}
