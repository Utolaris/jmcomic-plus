package com.par9uet.jm.download

import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.*
import kotlinx.coroutines.flow.Flow

internal class RecordingDownloadDao : DownloadComicDao {
    val tasks = linkedMapOf<Int, DownloadComic>()
    var failure: Exception? = null

    override fun observeCompleteList(): Flow<List<DownloadComic>> = error("Unexpected DAO call")
    override fun observeActiveList(): Flow<List<DownloadComic>> = error("Unexpected DAO call")
    override fun observeErrorList(): Flow<List<DownloadComic>> = error("Unexpected DAO call")
    override suspend fun getAll(): List<DownloadComic> = error("Unexpected DAO call")
    override fun observeByGroupId(groupId: Int): Flow<List<DownloadComic>> = error("Unexpected DAO call")
    override fun observeCompleteByGroupId(groupId: Int): Flow<List<DownloadComic>> = error("Unexpected DAO call")
    override suspend fun getById(comicId: Int): DownloadComic? = tasks[comicId]
    override suspend fun getByGroupId(groupId: Int): List<DownloadComic> = tasks.values
        .filter { it.groupId == groupId || (it.groupId == 0 && it.id == groupId) }
        .sortedBy { it.createTime }
    override suspend fun getCompleteByGroupId(groupId: Int): List<DownloadComic> = error("Unexpected DAO call")
    override suspend fun getExistingIds(ids: List<Int>): List<Int> = ids.filter { it in tasks }
    override fun isExist(comicId: Int): Flow<Boolean> = error("Unexpected DAO call")
    override suspend fun updateCover(updateComicCover: UpdateComicCover) {
        val task = tasks.getValue(updateComicCover.id)
        tasks[task.id] = task.copy(coverPath = updateComicCover.coverPath)
    }
    override suspend fun updateStatus(updateComicStatus: UpdateComicStatus) {
        val task = tasks.getValue(updateComicStatus.id)
        tasks[task.id] = task.copy(status = updateComicStatus.status)
    }
    override suspend fun updateProgress(updateComicProgress: UpdateComicProgress) {
        val task = tasks.getValue(updateComicProgress.id)
        tasks[task.id] = task.copy(progress = updateComicProgress.progress)
    }
    override suspend fun updateZipPath(updateComicZipPath: UpdateComicZipPath) {
        val task = tasks.getValue(updateComicZipPath.id)
        tasks[task.id] = task.copy(zipPath = updateComicZipPath.zipPath)
    }
    override suspend fun insert(task: DownloadComic) {
        failure?.let { throw it }
        tasks[task.id] = task
    }
    override suspend fun update(task: DownloadComic): Unit = error("Unexpected DAO call")
    override suspend fun delete(task: DownloadComic): Unit = error("Unexpected DAO call")
    override suspend fun deleteByIds(ids: List<Int>): Unit = error("Unexpected DAO call")
    override suspend fun updateStatusByIds(ids: List<Int>, status: DownloadStatus) {
        ids.forEach { id -> tasks[id] = tasks.getValue(id).copy(status = status) }
    }
}
