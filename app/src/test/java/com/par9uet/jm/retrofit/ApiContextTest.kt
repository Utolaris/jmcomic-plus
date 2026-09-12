package com.par9uet.jm.retrofit

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiContextTest {
    @Test
    fun `timestamp is the process-fixed API_TS`() {
        assertEquals(API_TS, ApiContext.getTimestamp())
    }

    @Test
    fun `decrypt key is derived from process timestamp and secret`() {
        assertEquals(
            com.par9uet.jm.utils.md5("${API_TS}$APP_DATA_SECRET"),
            ApiContext.getDataDecryptKey(),
        )
    }
}
