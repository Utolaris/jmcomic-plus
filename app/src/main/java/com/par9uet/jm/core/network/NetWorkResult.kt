package com.par9uet.jm.core.network

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

/**
 * 只映射成功值，失败原样透传（保留 message / code / authFailure / kind / cause）。
 *
 * 用于仓库层把 wire DTO 就地映射成领域类型：`dataSource.getX().map { it.toDomain() }`。
 */
inline fun <T, R> NetWorkResult<T>.map(transform: (T) -> R): NetWorkResult<R> {
    return when (this) {
        is NetWorkResult.Success -> NetWorkResult.Success(transform(data))
        is NetWorkResult.Error -> this
    }
}
