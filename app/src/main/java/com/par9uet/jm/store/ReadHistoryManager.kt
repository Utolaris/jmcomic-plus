package com.par9uet.jm.store

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.storage.ComicReadHistory
import com.par9uet.jm.storage.ReadHistoryStorage
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ReadHistoryManager(
    private val readHistoryStorage: ReadHistoryStorage
) {
    private val _readHistoryState = MutableStateFlow<Map<Int, ComicReadHistory>>(emptyMap())
    val readHistoryState = _readHistoryState.asStateFlow()

    fun historyKey(comic: Comic?, fallbackId: Int): Int {
        return comic?.seriesId
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: comic?.id
            ?: fallbackId
    }

    fun markRead(comic: Comic?, chapterId: Int): Int {
        return markRead(historyKey(comic, chapterId), chapterId)
    }

    @Synchronized
    fun markRead(comicKey: Int, chapterId: Int): Int {
        ensureLoaded()
        val current = _readHistoryState.value.toMutableMap()
        val old = current[comicKey]
        val readIds = (old?.readChapterIds.orEmpty() + chapterId).distinct()
        current[comicKey] = ComicReadHistory(
            lastChapterId = chapterId,
            readChapterIds = readIds,
            lastPageIndex = old?.takeIf { it.lastChapterId == chapterId }?.lastPageIndex ?: 0,
            lastChapterPageCount = old?.takeIf { it.lastChapterId == chapterId }?.lastChapterPageCount ?: 0,
        )
        _readHistoryState.update { current }
        readHistoryStorage.set(current)
        return comicKey
    }

    @Synchronized
    fun saveReadProgress(comicKey: Int, chapterId: Int, pageIndex: Int, pageCount: Int) {
        ensureLoaded()
        val current = _readHistoryState.value.toMutableMap()
        val old = current[comicKey]
        val readIds = (old?.readChapterIds.orEmpty() + chapterId).distinct()
        current[comicKey] = ComicReadHistory(
            lastChapterId = chapterId,
            readChapterIds = readIds,
            lastPageIndex = pageIndex,
            lastChapterPageCount = pageCount,
        )
        _readHistoryState.update { current }
        readHistoryStorage.set(current)
    }

    fun readChapterIds(
        comicKey: Int,
        history: Map<Int, ComicReadHistory> = _readHistoryState.value
    ): Set<Int> {
        return history[comicKey]?.readChapterIds.orEmpty().toSet()
    }

    fun lastReadChapterId(
        comic: Comic,
        history: Map<Int, ComicReadHistory> = _readHistoryState.value
    ): Int? {
        val lastId = history[historyKey(comic, comic.id)]?.lastChapterId?.takeIf { it > 0 }
        if (lastId == null || comic.comicChapterList.isEmpty()) {
            return lastId
        }
        return lastId.takeIf { id -> comic.comicChapterList.any { it.id == id } }
    }

    fun lastReadPageIndex(
        comicKey: Int,
        chapterId: Int,
        history: Map<Int, ComicReadHistory> = _readHistoryState.value
    ): Int {
        val entry = history[comicKey] ?: return 0
        if (entry.lastChapterId != chapterId) return 0
        if (entry.lastChapterPageCount <= 0) return 0
        return entry.lastPageIndex.coerceIn(0, entry.lastChapterPageCount - 1)
    }

    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        _readHistoryState.value = readHistoryStorage.get()
        loaded = true
    }

    suspend fun load() {
        log("加载阅读历史")
        ensureLoaded()
        log("阅读历史已加载")
    }

}
