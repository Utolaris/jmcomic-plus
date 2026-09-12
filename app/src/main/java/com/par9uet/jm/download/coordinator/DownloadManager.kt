package com.par9uet.jm.download.coordinator

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.download.DownloadWorkScheduler
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.download.molecule.DownloadTaskResult
import com.par9uet.jm.core.ToastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadManager(
    private val operations: DownloadTaskOperations,
    private val scope: CoroutineScope,
    private val toastManager: ToastManager,
    private val downloadWorkScheduler: DownloadWorkScheduler,
    private val coordinator: DownloadExecutionControl,
) {
    private val mutations = Mutex()

    fun downloadComic(comic: Comic) {
        scope.launch(Dispatchers.IO) {
            mutations.withLock {
                val result = operations.downloadComic(comic) ?: return@launch
                toastManager.showAsync(result.message)
                enqueue(result)
            }
        }
    }

    fun downloadComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        submit { operations.downloadComics(comics) }
    }

    fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>) {
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
        submit { redownloadGroupTasks(groupId) }
    }

    fun redownloadDownloads(comicIds: Collection<Int>) {
        scope.launch(Dispatchers.IO) {
            mutations.withLock {
                operations.groupIdsForTasks(comicIds).forEach { groupId ->
                    redownloadGroupTasks(groupId)?.let { result ->
                        enqueue(result)
                        toastManager.showAsync(result.message)
                    }
                }
            }
        }
    }

    private suspend fun redownloadGroupTasks(groupId: Int): DownloadTaskResult? {
        val ids = operations.groupTaskIds(groupId)
        return coordinator.withStoppedDownloads(ids) {
            downloadWorkScheduler.cancel(ids)
            operations.redownloadGroup(groupId)
        }
    }

    suspend fun pauseDownloads(ids: Collection<Int>) = mutations.withLock {
        coordinator.withStoppedDownloads(ids) {
            downloadWorkScheduler.cancel(ids)
            operations.pauseDownloads(ids)
        }
    }

    suspend fun deleteDownloads(ids: Collection<Int>) = mutations.withLock {
        coordinator.withStoppedDownloads(ids) {
            downloadWorkScheduler.cancel(ids)
            operations.deleteDownloads(ids)
        }
    }

    suspend fun clearDownloadedCache(deleteFiles: suspend () -> Unit) = mutations.withLock {
        val ids = operations.allTaskIds()
        coordinator.withStoppedDownloads(ids) {
            downloadWorkScheduler.cancel(ids)
            // If file removal fails, no missing cache remains advertised as readable.
            operations.invalidateDownloads(ids)
            deleteFiles()
            operations.deleteDownloads(ids)
        }
    }

    private fun submit(operation: suspend () -> DownloadTaskResult?) {
        scope.launch(Dispatchers.IO) {
            mutations.withLock {
                val result = operation() ?: return@launch
                enqueue(result)
                toastManager.showAsync(result.message)
            }
        }
    }

    private fun enqueue(result: DownloadTaskResult) {
        if (result.comicIds.isNotEmpty()) downloadWorkScheduler.enqueue(result.comicIds)
    }
}
