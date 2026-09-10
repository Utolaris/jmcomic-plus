package com.par9uet.jm.download.coordinator

/** Holds new writers until cancellation, joining and the supplied mutation have completed. */
interface DownloadExecutionControl {
    suspend fun <T> withStoppedDownloads(comicIds: Collection<Int>, block: suspend () -> T): T
}
