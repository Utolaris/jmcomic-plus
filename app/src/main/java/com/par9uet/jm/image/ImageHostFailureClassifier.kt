package com.par9uet.jm.image

import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 图片 CDN 失败分类。
 *
 * HOST_FAILURE：有足够证据表明是主机/网络层故障（DNS、连接、超时、TLS、5xx、429），
 * 可以对整个 CDN 做临时冷却。
 *
 * RESOURCE_FAILURE：只说明这一个资源有问题（404/410、其它 4xx、解码失败、内容断言），
 * 只应对该资源切换到下一个 CDN 候选，不应全局惩罚主机。
 *
 * CANCELLED：用户导航/协程取消，任何情况下都不惩罚主机。
 *
 * UNKNOWN：证据不足，保守处理——不全局惩罚主机（资源级回退仍照常进行）。
 */
internal enum class ImageHostFailureKind {
    HOST_FAILURE,
    RESOURCE_FAILURE,
    CANCELLED,
    UNKNOWN,
}

/** 429 / 5xx 说明 CDN 侧限流或网关故障，主机级冷却；404/410/其它 4xx 只影响单个资源。 */
internal fun classifyHttpCodeFailure(httpCode: Int): ImageHostFailureKind = when (httpCode) {
    429 -> ImageHostFailureKind.HOST_FAILURE
    in 500..599 -> ImageHostFailureKind.HOST_FAILURE
    in 400..499 -> ImageHostFailureKind.RESOURCE_FAILURE
    else -> ImageHostFailureKind.UNKNOWN
}

/** 沿 cause 链查找取消异常，避免包装后的取消被当成普通失败继续回退。 */
internal fun Throwable.cancellationExceptionOrNull(): CancellationException? {
    val visited = HashSet<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is CancellationException) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.isCancellation(): Boolean = cancellationExceptionOrNull() != null

/**
 * 沿 cause 链分类一次图片加载失败。
 *
 * @param throwable 失败异常（Coil 可能包装底层异常，需检查 cause 链）
 * @param httpCodeHint 调用方已知的 HTTP 状态码（例如 Reader 自抛的异常携带的 code）
 */
internal fun classifyImageHostFailure(
    throwable: Throwable?,
    httpCodeHint: Int? = null,
): ImageHostFailureKind {
    // 取消优先级最高：即使包装异常同时携带了 HTTP 提示，也绝不惩罚主机。
    if (throwable?.isCancellation() == true) return ImageHostFailureKind.CANCELLED
    if (httpCodeHint != null) return classifyHttpCodeFailure(httpCodeHint)
    if (throwable == null) return ImageHostFailureKind.UNKNOWN

    val visited = HashSet<Throwable>()
    var current: Throwable? = throwable
    while (current != null && visited.add(current)) {
        val className = current::class.java.simpleName.lowercase()
        val message = current.message?.lowercase().orEmpty()
        when (current) {
            is coil.network.HttpException ->
                return classifyHttpCodeFailure(current.response.code)
            is retrofit2.HttpException ->
                return classifyHttpCodeFailure(current.code())
        }
        // Coil/平台解码器的异常类型并不统一，且部分实现继承 IOException；
        // 因此必须在普通 IOException 网络分类之前识别解码/位图内容错误。
        if (
            "decode" in className ||
            "decoder" in className ||
            "bitmap" in className ||
            "bitmapfactory" in message ||
            "decode" in message ||
            "decoder" in message ||
            "unsupported image" in message
        ) {
            return ImageHostFailureKind.RESOURCE_FAILURE
        }
        when (current) {
            is UnknownHostException,
            is ConnectException,
            is SocketTimeoutException,
            is NoRouteToHostException,
            is SSLException,
            is ProtocolException,
            is SocketException,
            is InterruptedIOException,
            -> return ImageHostFailureKind.HOST_FAILURE
            // 单个响应被截断的 EOF 证据不足，按资源级错误保守处理。
            is EOFException -> return ImageHostFailureKind.RESOURCE_FAILURE
        }
        current = current.cause
    }
    return ImageHostFailureKind.UNKNOWN
}
