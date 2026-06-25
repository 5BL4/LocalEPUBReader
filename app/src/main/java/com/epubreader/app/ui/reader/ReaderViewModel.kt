package com.epubreader.app.ui.reader

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
    private val dispatchers: DispatchersProvider,
    private val stringProvider: StringProvider,
    private val errorChannel: ErrorChannel,
    private val exceptionHandler: AppCoroutineExceptionHandler
) : ViewModel() {

    private val bookUuid: String = savedStateHandle.toRoute<ReaderRoute>().bookUuid

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

    private var publication: Publication? = null

    /** Derived EpubPreferences from preferences repository flow. */
    val epubPreferences: StateFlow<EpubPreferences> = preferencesRepository.preferences
        .map { it.toEpubPreferences() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppPreferences().toEpubPreferences()
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

                    // Build EpubNavigatorFactory
                    val factory = EpubNavigatorFactory(pub)

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
        _commands.tryEmit(ReaderCommand.NavigateToLink(link))
        closeTocDrawer()
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

    // -- Phase 4: Auto-scroll (M2: state-driven, no command) --

    fun toggleAutoScroll() {
        _uiState.update { it.copy(isAutoScrollActive = !it.isAutoScrollActive) }
    }

    /** Called from JS bridge (via Fragment) when touch stops auto-scroll. */
    fun onAutoScrollStopped() {
        _uiState.update { it.copy(isAutoScrollActive = false) }
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

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel() // triggers finally → iterator.close()
        publication?.close()
        publication = null
    }

    companion object {
        private val HIGHLIGHT_COLOR_DEFAULT = android.graphics.Color.YELLOW
        private val SEARCH_HIGHLIGHT_COLOR = android.graphics.Color.RED
        private const val HIGHLIGHT_COLOR_HEX = "#FFFF00"
        private const val HIGHLIGHT_DECORATION_GROUP = "highlights"
        private const val SEARCH_DECORATION_GROUP = "search"
    }
}
