package com.par9uet.jm.repository.impl

import com.par9uet.jm.store.AuthenticatedSessionGate
import com.par9uet.jm.store.AuthenticatedSessionRequiredException
import io.github.jukomu.jmcomic.api.exception.ParseResponseException
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

    suspend fun <T> withClient(block: (JmApiClient) -> T): T? = sessionGate.run {
        try {
            block(clientProvider())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ParseResponseException) {
            // JMComic-Api-Java 1.1.8 compatibility workaround: postComment/replyToComment call
            // the protected getLoggedInUserName() AFTER the server accepted the comment POST.
            // A restored-cookie session (no in-process login) has no cached username, so the
            // library throws before returning even though the comment was created remotely.
            // The parser wraps that IllegalStateException into a ParseResponseException whose
            // cause message starts with "Username is required". Retrying here would duplicate
            // the remote comment, so we treat this exact shape as success and rebuild the
            // response locally. HTTP auth failures surface as ResponseException instead, so
            // they never match this path.
            if (error.isUpstreamCommentUsernameMappingFailure()) {
                null
            } else {
                throw error
            }
        } catch (error: ResponseException) {
            if (error.isAuthenticationFailure()) {
                throw AuthenticatedSessionRequiredException("登录会话已失效，请重新登录", error)
            }
            throw error
        }
    }
}

/**
 * Matches ONLY the verified 1.1.8 post-success username exception produced by
 * ApiParser.parseCommentSubmitResult <- AbstractJmClient.getLoggedInUserName():
 * an IllegalStateException with message "Username is required for this operation. Please login
 * first." wrapped by ParseResponseException("Failed to parse comment submit result API JSON").
 */
internal fun ParseResponseException.isUpstreamCommentUsernameMappingFailure(): Boolean =
    message == "Failed to parse comment submit result API JSON" &&
        cause is IllegalStateException &&
        cause?.message == "Username is required for this operation. Please login first."

private fun ResponseException.isAuthenticationFailure(): Boolean {
    val detail = message.orEmpty()
    return errorCode == 401 ||
        detail.contains("登入") ||
        detail.contains("登录") ||
        detail.contains("login", ignoreCase = true)
}
