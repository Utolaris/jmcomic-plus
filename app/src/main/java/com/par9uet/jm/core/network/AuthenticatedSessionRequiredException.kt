package com.par9uet.jm.core.network

/** Thrown when an authenticated request runs without a usable login session. */
class AuthenticatedSessionRequiredException(
    message: String = "请先登录",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
