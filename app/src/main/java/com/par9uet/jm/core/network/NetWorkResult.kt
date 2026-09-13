package com.par9uet.jm.core.network

import kotlinx.coroutines.CancellationException

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
 * 转换异常（协议字段畸形等）收成 Parsing 错误，不再穿透分页 `load()`；
 * 协程取消继续抛出。
 */
inline fun <T, R> NetWorkResult<T>.map(transform: (T) -> R): NetWorkResult<R> {
    return when (this) {
        is NetWorkResult.Success -> try {
            NetWorkResult.Success(transform(data))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NetWorkResult.Error(
                message = error.message ?: "响应解析失败",
                kind = NetworkErrorKind.Parsing,
                cause = error,
            )
        }
        is NetWorkResult.Error -> this
    }
}
