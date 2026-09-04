package com.par9uet.jm.database.model

enum class DownloadStatus(val persistedValue: String) {
    PENDING("pending"),
    DOWNLOADING("downloading"),
    PAUSED("paused"),
    COMPLETE("complete"),
    ERROR("error");

    companion object {
        fun fromPersistedValue(value: String): DownloadStatus =
            entries.firstOrNull { it.persistedValue == value } ?: PENDING
    }
}
