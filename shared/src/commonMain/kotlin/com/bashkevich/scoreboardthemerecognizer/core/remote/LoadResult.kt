package com.bashkevich.scoreboardthemerecognizer.core.remote

import kotlin.coroutines.cancellation.CancellationException

sealed class LoadResult<out S, out E> {
    data class Success<out S>(val result: S) : LoadResult<S, Nothing>()

    data class Error<out E>(val result: E) : LoadResult<Nothing, E>()
}

inline fun <S, E, R> LoadResult<S, E>.mapSuccess(block: (S) -> R): LoadResult<R, E> =
    when (this) {
        is LoadResult.Success -> LoadResult.Success(result = block(this.result))
        is LoadResult.Error -> LoadResult.Error(result = this.result)
    }

inline fun <S, E, R> LoadResult<S, E>.mapError(block: (E) -> R): LoadResult<S, R> =
    when (this) {
        is LoadResult.Success -> LoadResult.Success(result = this.result)
        is LoadResult.Error -> LoadResult.Error(result = block(this.result))
    }

inline fun <S, E, R> LoadResult<S, E>.mapNestedSuccess(
    block: (S) -> LoadResult<R, E>,
): LoadResult<R, E> =
    when (this) {
        is LoadResult.Success -> block(this.result)
        is LoadResult.Error -> LoadResult.Error(result = this.result)
    }

inline fun <S, E> LoadResult<S, E>.doOnSuccess(block: (S) -> Unit): LoadResult<S, E> {
    if (this is LoadResult.Success) {
        block(this.result)
    }
    return this
}

inline fun <S, E> LoadResult<S, E>.doOnError(block: (E) -> Unit): LoadResult<S, E> {
    if (this is LoadResult.Error) {
        block(this.result)
    }
    return this
}

fun <S, E> LoadResult<S, E>.getOrDefault(default: S): S = when (this) {
    is LoadResult.Success -> this.result
    is LoadResult.Error -> default
}

inline fun <S, R> S.runOperationCatching(block: S.() -> R): LoadResult<R, Throwable> {
    return try {
        LoadResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LoadResult.Error(e.toNetworkException() ?: e)
    }
}