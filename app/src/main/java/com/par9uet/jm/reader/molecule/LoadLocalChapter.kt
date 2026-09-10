package com.par9uet.jm.reader.molecule

import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.reader.atom.LocalChapterFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalChapterContent(
    val groupId: Int,
    val chapters: List<ComicChapter>,
    val imagePaths: List<String>,
)

class LoadLocalChapter(
    private val downloads: DownloadComicDao,
    private val files: LocalChapterFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(comicId: Int): LocalChapterContent = withContext(ioDispatcher) {
        val task = downloads.getById(comicId)
        val groupId = task?.groupId?.takeIf { it != 0 } ?: comicId
        val chapters = downloads.getCompleteByGroupId(groupId)
        LocalChapterContent(
            groupId = groupId,
            chapters = chapters.mapIndexed { index, chapter ->
                ComicChapter(chapter.id, chapter.chapterName.ifBlank {
                    if (chapters.size > 1) "第 ${index + 1} 章" else chapter.name
                })
            },
            imagePaths = files.images(comicId, task),
        )
    }
}
