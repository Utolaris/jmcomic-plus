package com.par9uet.jm.download

import com.par9uet.jm.download.export.calculateSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSampleSizeTest {
    @Test
    fun longImagesStayWithinDecodeLimitInEitherOrientation() {
        for (dimension in listOf(1, 2000, 2001, 4000, 4001, 16000, 64000, Int.MAX_VALUE)) {
            val sample = calculateSampleSize(1, dimension)
            assertEquals(sample, calculateSampleSize(dimension, 1))
            assertTrue(dimension.toLong() <= 2000L * sample)
            assertEquals(0, sample and (sample - 1))
            if (sample > 1) assertTrue(dimension.toLong() > 2000L * (sample / 2))
        }
        assertEquals(8, calculateSampleSize(1000, 16000))
        assertEquals(32, calculateSampleSize(1000, 64000))
    }
}
