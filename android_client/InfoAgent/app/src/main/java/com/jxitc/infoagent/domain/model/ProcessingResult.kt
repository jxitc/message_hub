package com.jxitc.infoagent.domain.model

sealed class ProcessingResult<out T> {
    data class Success<T>(val data: T) : ProcessingResult<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : ProcessingResult<Nothing>()
    data object Loading : ProcessingResult<Nothing>()
}

inline fun <T> ProcessingResult<T>.onSuccess(action: (value: T) -> Unit): ProcessingResult<T> {
    if (this is ProcessingResult.Success) action(data)
    return this
}

inline fun <T> ProcessingResult<T>.onError(action: (message: String, throwable: Throwable?) -> Unit): ProcessingResult<T> {
    if (this is ProcessingResult.Error) action(message, throwable)
    return this
}

inline fun <T> ProcessingResult<T>.onLoading(action: () -> Unit): ProcessingResult<T> {
    if (this is ProcessingResult.Loading) action()
    return this
}