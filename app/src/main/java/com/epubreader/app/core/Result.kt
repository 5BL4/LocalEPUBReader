package com.epubreader.app.core

import kotlin.coroutines.cancellation.CancellationException

sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val cause: Throwable, val message: String? = null) : Result<Nothing>

    companion object {
        /**
         * CancellationException is re-thrown (not swallowed) to preserve structured
         * concurrency. Catching it would break coroutine cancellation control flow.
         */
        inline fun <T> runCatching(block: () -> T): Result<T> = try {
            Success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (c: Throwable) {
            Error(c)
        }

        suspend inline fun <T> runCatchingAsync(block: suspend () -> T): Result<T> = try {
            Success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (c: Throwable) {
            Error(c)
        }
    }
}

fun <T> Result<T>.getOrDefault(default: T): T = when (this) {
    is Result.Success -> data
    is Result.Error -> default
}

fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> data
    is Result.Error -> null
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onFailure(action: (Throwable, String?) -> Unit): Result<T> {
    if (this is Result.Error) action(cause, message)
    return this
}

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Error -> this
}

fun <T> Result<T>.getOrThrow(): T = when (this) {
    is Result.Success -> data
    is Result.Error -> throw cause
}

inline fun <T> Result<T>.fold(
    onSuccess: (T) -> Unit,
    onFailure: (Throwable, String?) -> Unit
): Unit = when (this) {
    is Result.Success -> onSuccess(data)
    is Result.Error -> onFailure(cause, message)
}
