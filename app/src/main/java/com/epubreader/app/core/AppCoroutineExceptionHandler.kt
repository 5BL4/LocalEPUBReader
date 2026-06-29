package com.epubreader.app.core

import com.epubreader.app.core.log.AppLogger
import com.epubreader.app.R
import kotlinx.coroutines.CoroutineExceptionHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backstop coroutine exception handler. Logs and emits to [ErrorChannel].
 * PRIMARY guard is local try-catch in repositories (NEVER #26); this is the safety net for
 * any exception that escapes a viewModelScope.launch child coroutine.
 */
@Singleton
class AppCoroutineExceptionHandler @Inject constructor(
    private val errorChannel: ErrorChannel,
    private val stringProvider: StringProvider
) {
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e("EpubReader", "Uncaught coroutine exception", throwable)
        errorChannel.tryEmit(AppError(throwable.message ?: stringProvider.get(R.string.error_unexpected), throwable))
    }
}
