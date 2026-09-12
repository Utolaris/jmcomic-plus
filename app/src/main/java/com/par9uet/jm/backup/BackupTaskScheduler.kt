package com.par9uet.jm.backup

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter

/** Backup's own narrow port for re-queueing restored downloads. Bound in DI to DownloadManager. */
interface BackupTaskScheduler {
    fun downloadComic(comic: Comic)
    fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>)
}
