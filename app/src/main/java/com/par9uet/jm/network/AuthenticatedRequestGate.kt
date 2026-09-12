package com.par9uet.jm.network

/**
 * Ordering port for authenticated embedded requests. Implemented by the session layer
 * (AuthenticatedSessionGate: while login restoration is running, requests funnel through
 * UserManager's executor; otherwise they wait for a terminal readiness state) and wired
 * in the composition root, so the network client never orchestrates the session itself.
 */
interface AuthenticatedRequestGate {
    suspend fun <T> run(block: suspend () -> T): T
}
