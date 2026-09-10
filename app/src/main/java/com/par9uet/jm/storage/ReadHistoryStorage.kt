package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ComicReadHistory(
    val lastChapterId: Int = 0,
    val readChapterIds: List<Int> = emptyList(),
    val lastPageIndex: Int = 0,
    val lastChapterPageCount: Int = 0,
)

class ReadHistoryStorage(
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val STORAGE_KEY = "comicReadHistory"
    }

    private val _state = MutableStateFlow<Map<Int, ComicReadHistory>?>(null)
    val state = _state.asStateFlow()

    fun set(history: Map<Int, ComicReadHistory>) {
        when (secureStorage.set(STORAGE_KEY, history)) {
            is StorageWriteResult.Success -> _state.update { history }
            is StorageWriteResult.TemporaryUnavailable -> Unit
        }
    }

    fun get(): Map<Int, ComicReadHistory> {
        _state.value?.let { return it }
        return when (
            val result = secureStorage.get<Map<Int, ComicReadHistory>>(
                STORAGE_KEY,
                object : TypeToken<Map<Int, ComicReadHistory>>() {}.type
            )
        ) {
            is StorageReadResult.Success -> result.value.also { _state.value = it }
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> emptyMap<Int, ComicReadHistory>().also { _state.value = it }
            is StorageReadResult.TemporaryUnavailable -> emptyMap()
        }
    }

    fun remove() {
        _state.update { emptyMap() }
        secureStorage.remove(STORAGE_KEY)
    }
}
