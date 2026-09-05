package com.par9uet.jm.retrofit.model

enum class AuthFailure {
    InvalidCredentials,
    TemporaryFailure,
    Cancelled,
    Unknown,
}

enum class NetworkErrorKind {
    Network,
    Authentication,
    Server,
    Parsing,
    Unknown,
}

sealed class NetWorkResult<out T> {
    data class Success<T>(val data: T) : NetWorkResult<T>()
    data class Error(
        val message: String,
        val code: Int = -1,
        val authFailure: AuthFailure? = null,
        val kind: NetworkErrorKind = NetworkErrorKind.Unknown,
        val cause: Throwable? = null,
    ) : NetWorkResult<Nothing>()
}

fun <T> NetWorkResult<T>.getOrThrow(): T {
    return when (this) {
        is NetWorkResult.Success -> data
        is NetWorkResult.Error -> throw RuntimeException(message)
    }
}
