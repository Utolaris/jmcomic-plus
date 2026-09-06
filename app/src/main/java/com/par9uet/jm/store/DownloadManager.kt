package com.par9uet.jm.store

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.download.molecule.DownloadTaskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal interface BackupTaskScheduler {
    fun downloadComic(comic: Comic)
    fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>)
}

class DownloadManager(
    private val operations: DownloadTaskOperations,
    private val scope: CoroutineScope,
    private val toastManager: ToastManager,
    private val downloadWorkScheduler: DownloadWorkScheduler,
) : BackupTaskScheduler {
    override fun downloadComic(comic: Comic) {
        scope.launch(Dispatchers.IO) {
            val result = operations.downloadComic(comic) ?: return@launch
            toastManager.showAsync(result.message)
            enqueue(result)
        }
    }

    fun downloadComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        submit { operations.downloadComics(comics) }
    }

    override fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>) {
        if (chapters.isEmpty()) return
        submit { operations.downloadChapters(parentComic, chapters) }
    }

    fun retryDownload(comicId: Int) {
        submit { operations.retryDownload(comicId) }
    }

    fun resumeDownloads(comicIds: List<Int>) {
        if (comicIds.isEmpty()) return
        submit { operations.resumeDownloads(comicIds) }
    }

    fun retryGroup(groupId: Int) {
        submit { operations.retryGroup(groupId) }
    }

    fun redownloadGroup(groupId: Int) {
        submit { operations.redownloadGroup(groupId) }
    }

    private fun submit(operation: suspend () -> DownloadTaskResult?) {
        scope.launch(Dispatchers.IO) {
            val result = operation() ?: return@launch
            enqueue(result)
            toastManager.showAsync(result.message)
        }
    }

    private fun enqueue(result: DownloadTaskResult) {
        if (result.comicIds.isNotEmpty()) downloadWorkScheduler.enqueue(result.comicIds)
    }
}
