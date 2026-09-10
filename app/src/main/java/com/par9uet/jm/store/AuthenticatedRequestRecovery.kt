package com.par9uet.jm.store

import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.NetworkErrorKind
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** One recovery and one retry, always outside the operation's bound remote gate. */
suspend fun <T> withAuthenticationRecovery(
    isCurrent: () -> Boolean,
    recover: suspend () -> NetWorkResult<Unit>?,
    request: suspend () -> NetWorkResult<T>,
): NetWorkResult<T> {
    val result = request()
    coroutineContext.ensureActive()
    if (result !is NetWorkResult.Error || result.kind != NetworkErrorKind.Authentication || !isCurrent()) {
        return result
    }
    val recovery = recover()
    coroutineContext.ensureActive()
    return when (recovery) {
        is NetWorkResult.Success -> if (isCurrent()) request() else result
        is NetWorkResult.Error -> recovery
        null -> result
    }
}

/** Keeps structured recovery errors intact across the throwing SDK boundary. */
class SessionRecoveryException(val error: NetWorkResult.Error) :
    IllegalStateException(error.message, error.cause)

/** Registered by UserManager without creating a repository -> UserManager DI cycle. */
interface AuthenticatedRequestExecutor {
    suspend fun <T> execute(block: suspend () -> T): T
}

/** Nested SDK calls must leave recovery to the owner after it releases the session gate. */
internal object BoundAuthenticatedRequest : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BoundAuthenticatedRequest>
}
