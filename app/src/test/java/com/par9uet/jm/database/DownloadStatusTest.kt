package com.par9uet.jm.database

import com.par9uet.jm.database.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStatusTest {
    @Test
    fun `persisted download states round trip without changing existing database values`() {
        DownloadStatus.entries.forEach { status ->
            assertEquals(status, DownloadStatus.fromPersistedValue(status.persistedValue))
        }
    }

    @Test
    fun `unknown legacy state safely returns to pending`() {
        assertEquals(DownloadStatus.PENDING, DownloadStatus.fromPersistedValue("unknown"))
    }
}
