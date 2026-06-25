package com.epubreader.app.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.epubreader.app.R
import com.epubreader.app.core.AppCoroutineExceptionHandler
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.StringProvider
import com.epubreader.app.core.fold
import com.epubreader.app.core.getOrNull
import com.epubreader.app.core.readium.toJsonString
import com.epubreader.app.core.readium.toLocator
import com.epubreader.app.data.local.entity.ReadingProgressEntity
import com.epubreader.app.data.prefs.PreferencesRepository
import com.epubreader.app.domain.repository.BookRepository
import com.epubreader.app.domain.repository.ReadingProgressRepository
import com.epubreader.app.navigation.ReaderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val readingProgressRepository: ReadingProgressRepository,
    private val preferencesRepository: PreferencesRepository,
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
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

    private var publication: Publication? = null

    /** Derived EpubPreferences from preferences repository flow. */
    val epubPreferences: StateFlow<EpubPreferences> = preferencesRepository.preferences
        .map { it.toEpubPreferences() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = com.epubreader.app.data.prefs.AppPreferences().toEpubPreferences()
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

    init {
        viewModelScope.launch(exceptionHandler.handler) {
            openPublication()
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

                    // Retrieve asset from file, then open publication (v3.3.0 API)
                    val asset = assetRetriever.retrieve(file = file)
                        .getOrElse { error ->
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
                        .getOrElse { error ->
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
        publication?.close()
        publication = null
    }
}
