package com.par9uet.jm.ui.state

import java.util.concurrent.atomic.AtomicBoolean

internal class CommentSubmissionGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}
