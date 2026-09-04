package com.par9uet.jm.store

interface DownloadWorkScheduler {
    fun enqueue(comicIds: Collection<Int>)
}
