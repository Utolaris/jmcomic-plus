package com.par9uet.jm.download

interface DownloadWorkScheduler {
    fun enqueue(comicIds: Collection<Int>)
    suspend fun cancel(comicIds: Collection<Int>)
}
