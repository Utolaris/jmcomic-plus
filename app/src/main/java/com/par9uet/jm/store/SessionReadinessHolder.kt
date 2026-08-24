package com.par9uet.jm.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 认证会话就绪状态。只描述“已登录身份对应的会话是否已就绪 / 正在后台恢复”，
 * 供需要认证的请求（评论、收藏、收藏夹、历史、签到等）等待真实恢复结果；
 * Home / 搜索等公开接口不感知该状态，也不会被它阻塞。
 */
enum class SessionReadiness {
    /** 尚未确定（一般不会对外暴露给请求层）。 */
    Unknown,

    /** 存在缓存身份，后台会话恢复/验证正在进行中。 */
    Restoring,

    /** 会话已就绪（登录成功、验证通过，或临时失败但保留缓存会话）。 */
    Authenticated,

    /** 无登录身份或已明确登出。 */
    Unauthenticated,
}

/** [UserManager] 与仓库层共享的会话就绪状态容器，避免仓库反向依赖 UserManager。 */
class SessionReadinessHolder {
    private val _state = MutableStateFlow(SessionReadiness.Unknown)
    val state: StateFlow<SessionReadiness> = _state.asStateFlow()

    fun set(value: SessionReadiness) {
        _state.value = value
    }
}

/**
 * Wait until session restoration reaches a terminal authentication state. Authenticated work
 * must never fall through merely because restoration is taking longer than an arbitrary timeout.
 */
suspend fun SessionReadinessHolder.awaitReady(): SessionReadiness {
    val current = state.value
    if (current == SessionReadiness.Authenticated || current == SessionReadiness.Unauthenticated) {
        return current
    }
    return state.first {
        it == SessionReadiness.Authenticated || it == SessionReadiness.Unauthenticated
    }
}

class AuthenticatedSessionRequiredException(
    message: String = "请先登录",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Shared ordering gate for every authenticated Embedded request. */
class AuthenticatedSessionGate(
    private val readinessHolder: SessionReadinessHolder,
) {
    suspend fun <T> run(block: () -> T): T {
        if (readinessHolder.awaitReady() != SessionReadiness.Authenticated) {
            throw AuthenticatedSessionRequiredException()
        }
        coroutineContext.ensureActive()
        val result = block()
        coroutineContext.ensureActive()
        return result
    }
}
