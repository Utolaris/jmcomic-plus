package com.par9uet.jm.reader.coordinator

import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.image.classifyImageHostFailure
import com.par9uet.jm.reader.ReaderImageException
import com.par9uet.jm.reader.ReaderImageHostManager
import com.par9uet.jm.reader.ReaderMetrics
import com.par9uet.jm.reader.ReaderRemoteCandidate
import com.par9uet.jm.reader.ReaderRemoteObserver
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** L2 data clerk that maps remote events into metrics and shared host-health state. */
internal class ReaderRemoteTelemetry(
    private val metrics: ReaderMetrics,
    private val imageHostManager: ReaderImageHostManager,
) : ReaderRemoteObserver {
    override fun onRequestStarted(
        url: String,
        candidate: ReaderRemoteCandidate,
        call: Call,
    ) {
        metrics.networkStarted()
    }

    override fun onResponseHeaders(
        url: String,
        candidate: ReaderRemoteCandidate,
        elapsedMillis: Long,
        httpCode: Int,
    ) {
        metrics.responseHeadersReceived(
            elapsedMillis = elapsedMillis,
            primary = candidate == ReaderRemoteCandidate.PRIMARY,
        )
        if (httpCode in 200..299) {
            // Host ranking uses TTFB only; body transfer and decode speed are page-specific.
            imageHostManager.recordLatencySample(url, elapsedMillis)
        }
    }

    override fun onRequestSucceeded(
        url: String,
        candidate: ReaderRemoteCandidate,
        timeToHeadersMillis: Long,
        bodyMillis: Long,
        totalMillis: Long,
    ) {
        metrics.networkFinished(success = true, elapsedMillis = totalMillis)
        metrics.bodyDownloadFinished(bodyMillis)
        url.toHttpUrlOrNull()?.host?.let(metrics::hostSuccess)
    }

    override fun onRequestFailed(
        url: String,
        candidate: ReaderRemoteCandidate,
        totalMillis: Long,
        error: Throwable?,
    ) {
        metrics.networkFinished(success = false, elapsedMillis = totalMillis)
        url.toHttpUrlOrNull()?.host?.let(metrics::hostFailure)
        // Resource failures stay local to this page; only network/host failures cool a CDN.
        val failureKind = when {
            error is ReaderImageException && error.httpCode == null ->
                ImageHostFailureKind.RESOURCE_FAILURE
            else -> classifyImageHostFailure(
                error,
                httpCodeHint = (error as? ReaderImageException)?.httpCode,
            )
        }
        if (failureKind == ImageHostFailureKind.HOST_FAILURE) {
            imageHostManager.recordHostFailure(url)
        }
    }

    override fun onRequestCanceled(
        url: String,
        candidate: ReaderRemoteCandidate,
        totalMillis: Long,
        preempted: Boolean,
    ) {
        metrics.networkCanceled(totalMillis)
    }

    override fun onHedgeSecondaryStarted() = metrics.hedgeSecondaryStarted()

    override fun onHedgeWinner(primary: Boolean, url: String) {
        metrics.hedgeWinner(primary, url.toHttpUrlOrNull()?.host)
    }

    override fun onHedgeLoserCanceled() = metrics.hedgeLoserCanceled()
}
