package com.par9uet.jm.repository

import coil.network.HttpException
import com.par9uet.jm.retrofit.model.AuthFailure
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.ResponseWrapper
import com.par9uet.jm.utils.logError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

open class BaseRepository {

    suspend fun <T> safeApiCall(apiCall: suspend () -> ResponseWrapper<T>): NetWorkResult<T> {
        return try {
            val response = apiCall()
            if (response.code == 200) {
                response.data?.let { NetWorkResult.Success(it) }
                    ?: NetWorkResult.Error("响应数据为空")
            } else {
                val errMsg = response.errorMsg ?: "未知错误"
                logError(this::class.java.simpleName, "API 返回错误: $errMsg")
                // 透传服务端错误码，供上层区分凭据失效（401）与临时网络错误
                NetWorkResult.Error(errMsg, response.code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleException(e)
        }
    }

    suspend fun safeStringCall(apiCall: suspend () -> String): NetWorkResult<String> {
        return try {
            val response = apiCall()
            NetWorkResult.Success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleException(e)
        }
    }

    protected suspend inline fun <T> runCatchingCancellable(
        crossinline block: suspend () -> T,
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只把普通异常转换为 Result.failure；OOM/LinkageError/StackOverflowError 等
            // JVM 致命错误保持抛出，避免被当成普通网络失败吞掉。
            Result.failure(e)
        }
    }

    private fun handleException(e: Exception): NetWorkResult.Error {
        logError(this::class.java.simpleName, "请求异常: ${e.stackTraceToString()}")
        return when (e) {
            is SocketTimeoutException -> NetWorkResult.Error(
                "网络连接超时",
                authFailure = AuthFailure.TemporaryFailure,
            )
            is ConnectException -> NetWorkResult.Error(
                "网络连接失败",
                authFailure = AuthFailure.TemporaryFailure,
            )
            is UnknownHostException -> NetWorkResult.Error(
                "网络不可用",
                authFailure = AuthFailure.TemporaryFailure,
            )
            is IOException -> NetWorkResult.Error(
                "网络请求失败",
                authFailure = AuthFailure.TemporaryFailure,
            )
            is HttpException -> {
                val errMsg = when (e.response.code) {
                    401 -> "账号或密码错误，请重新输入"
                    else -> "网络错误：${e.response.code}"
                }
                NetWorkResult.Error(errMsg, e.response.code)
            }

            else -> NetWorkResult.Error(
                e.message ?: "未知错误"
            )
        }
    }
}
