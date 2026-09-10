package com.par9uet.jm.cache

import org.junit.Assert.*
import org.junit.Test

class CacheMigrationPathsTest {
    @Test fun `same directory and either ancestor direction are rejected`() {
        assertTrue(cacheDocumentTreesOverlap("primary:Comics", "primary:Comics/"))
        assertTrue(cacheDocumentTreesOverlap("primary:Comics", "primary:Comics/new"))
        assertTrue(cacheDocumentTreesOverlap("primary:Comics/old", "primary:Comics"))
        assertTrue(cacheDocumentTreesOverlap("primary:", "primary:Comics"))
    }

    @Test fun `siblings with a shared name prefix and separate volumes are allowed`() {
        assertFalse(cacheDocumentTreesOverlap("primary:Comics", "primary:Comics-new"))
        assertFalse(cacheDocumentTreesOverlap("primary:Comics", "ABCD:Comics"))
    }
}
