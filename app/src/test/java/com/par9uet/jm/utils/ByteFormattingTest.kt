package com.par9uet.jm.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormattingTest {
    @Test
    fun `formats byte unit boundaries consistently`() {
        assertEquals("0 B", formatBytes(-1L))
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1023 B", formatBytes(1023L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.0 GB", formatBytes(1024L * 1024L * 1024L))
    }
}
