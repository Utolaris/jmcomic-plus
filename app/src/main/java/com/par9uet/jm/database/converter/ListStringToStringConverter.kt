package com.par9uet.jm.database.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.par9uet.jm.database.model.DownloadStatus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ListStringToStringConverter : KoinComponent {
    private val gson: Gson by inject()

    @TypeConverter
    fun fromList(list: List<String>): String {
        return gson.toJson(list)
    }

    @TypeConverter
    fun toList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String = status.persistedValue

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.fromPersistedValue(value)
}
