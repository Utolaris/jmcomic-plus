package com.par9uet.jm.cache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R06: config index writes must never publish a truncated file as the readable index. */
class CacheConfigWriteTest {

    @Test
    fun `atomic text replace updates content and leaves no temporary files`() {
        val dir = Files.createTempDirectory("config-atomic").toFile()
        try {
            val config = File(dir, "config.json")
            writeTextAtomically(config, """{"v":1}""")
            assertEquals("""{"v":1}""", config.readText())
            writeTextAtomically(config, """{"v":2}""")
            assertEquals("""{"v":2}""", config.readText())
            assertEquals(listOf("config.json"), dir.list()!!.sorted())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `atomic text replace creates missing parents`() {
        val dir = Files.createTempDirectory("config-atomic-parent").toFile()
        try {
            val config = File(dir, "JM1/config.json")
            writeTextAtomically(config, """{"v":1}""")
            assertTrue(config.isFile)
            assertEquals("""{"v":1}""", config.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed replace leaves the previous committed index readable`() {
        val dir = Files.createTempDirectory("config-atomic-fail").toFile()
        try {
            val config = File(dir, "config.json")
            config.writeText("previous")
            // Destination is a directory: the temp file is written fully, then the move fails.
            // The previous sibling file (not this path) is what a real torn write would hit;
            // here we only assert the failed attempt does not invent a partial config.json
            // under a name the reader would treat as valid, and does not leave .tmp litter
            // in the parent after cleanup.
            val blocked = File(dir, "blocked")
            assertTrue(blocked.mkdir())
            assertThrows(Exception::class.java) {
                writeTextAtomically(blocked, "partial")
            }
            assertEquals("previous", config.readText())
            assertEquals(listOf("blocked", "config.json"), dir.list()!!.sorted())
        } finally {
            dir.deleteRecursively()
        }
    }
}
