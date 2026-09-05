package com.par9uet.jm.favorites.data

import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.NetworkErrorKind
import com.par9uet.jm.store.AuthenticatedSessionRequiredException
import io.github.jukomu.jmcomic.api.exception.NetworkException
import io.github.jukomu.jmcomic.api.exception.ParseResponseException
import io.github.jukomu.jmcomic.api.exception.ResponseException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Classify before the sync boundary loses the SDK exception and its nested cause. */
internal fun Throwable.toFavoriteSyncError(): NetWorkResult.Error {
    val causes = generateSequence(this) { it.cause }.take(16).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    val response = causes.filterIsInstance<ResponseException>().firstOrNull()
    val kind = when {
        causes.any { it is AuthenticatedSessionRequiredException } || response?.errorCode == 401 ->
            NetworkErrorKind.Authentication
        causes.any { it is ParseResponseException } -> NetworkErrorKind.Parsing
        response != null -> NetworkErrorKind.Server
        causes.any { it is NetworkException || it is IOException } -> NetworkErrorKind.Network
        else -> NetworkErrorKind.Unknown
    }
    val message = when (kind) {
        NetworkErrorKind.Network -> "网络连接失败，请检查网络后重试"
        NetworkErrorKind.Authentication -> "登录已失效，请重新登录后同步"
        NetworkErrorKind.Server -> {
            val status = response?.errorCode?.takeIf { it > 0 }?.let { "（$it）" }.orEmpty()
            "服务器拒绝了同步请求$status，请稍后重试"
        }
        NetworkErrorKind.Parsing -> "服务器响应解析失败，请稍后重试或更新应用"
        NetworkErrorKind.Unknown -> this.message?.takeIf { it.isNotBlank() } ?: "同步收藏夹失败，请重试"
    }
    return NetWorkResult.Error(message, code = response?.errorCode ?: -1, kind = kind, cause = this)
}
