package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HistorySearchStorage(
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val STORAGE_KEY = "historySearch"
    }

    private var _state = MutableStateFlow<List<String>?>(null)
    val state = _state.asStateFlow()

    fun set(list: List<String>) {
        when (secureStorage.set(STORAGE_KEY, list)) {
            is StorageWriteResult.Success -> _state.update { list }
            is StorageWriteResult.TemporaryUnavailable -> Unit
        }
    }

    fun get(): List<String> {
        _state.value?.let { return it }
        return when (
            val result = secureStorage.get<List<String>>(
                STORAGE_KEY,
                object : TypeToken<List<String>>() {}.type,
            )
        ) {
            is StorageReadResult.Success -> result.value.also { _state.value = it }
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> emptyList<String>().also { _state.value = it }
            is StorageReadResult.TemporaryUnavailable -> emptyList()
        }
    }

    fun remove() {
        _state.update {
            listOf()
        }
        secureStorage.remove(STORAGE_KEY)
    }
}
