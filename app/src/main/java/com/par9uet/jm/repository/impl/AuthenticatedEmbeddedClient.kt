package com.par9uet.jm.repository.impl

import com.par9uet.jm.store.AuthenticatedSessionGate
import com.par9uet.jm.store.AuthenticatedSessionRequiredException
import com.par9uet.jm.store.SessionReadinessHolder
import io.github.jukomu.jmcomic.api.exception.ResponseException
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import kotlinx.coroutines.CancellationException

/** The single entry point for requests that require an authenticated Embedded session. */
class AuthenticatedEmbeddedClient(
    embeddedClientManager: EmbeddedClientManager,
    sessionReadinessHolder: SessionReadinessHolder,
) {
    private val clientProvider = embeddedClientManager::getClient
    private val sessionGate = AuthenticatedSessionGate(sessionReadinessHolder)

    suspend fun <T> withClient(block: (JmApiClient) -> T): T = sessionGate.run {
        try {
            block(clientProvider())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ResponseException) {
            if (error.isAuthenticationFailure()) {
                throw AuthenticatedSessionRequiredException("登录会话已失效，请重新登录", error)
            }
            throw error
        }
    }
}

private fun ResponseException.isAuthenticationFailure(): Boolean {
    val detail = message.orEmpty()
    return errorCode == 401 ||
        detail.contains("登入") ||
        detail.contains("登录") ||
        detail.contains("login", ignoreCase = true)
}
