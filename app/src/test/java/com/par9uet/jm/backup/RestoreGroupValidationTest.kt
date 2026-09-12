package com.par9uet.jm.backup

import com.par9uet.jm.backup.ChapterBackup
import com.par9uet.jm.backup.ComicGroupBackup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGroupValidationTest {
    private fun group(
        id: Int = 10,
        name: String = "漫画",
        chapters: List<ChapterBackup> = listOf(ChapterBackup(1, "第一章", 1)),
    ) = ComicGroupBackup(id = id, name = name, authors = emptyList(), tags = emptyList(), chapters = chapters)

    @Test
    fun `titles with slashes are allowed`() {
        assertTrue(isValidRestoreGroup(group(name = "A/B")))
        assertTrue(isValidRestoreGroup(group(name = "A\\B")))
    }

    @Test
    fun `non-positive comic id is rejected`() {
        assertFalse(isValidRestoreGroup(group(id = 0)))
        assertFalse(isValidRestoreGroup(group(id = -1)))
    }

    @Test
    fun `blank name is rejected`() {
        assertFalse(isValidRestoreGroup(group(name = "")))
        assertFalse(isValidRestoreGroup(group(name = "   ")))
    }

    @Test
    fun `empty chapter list is rejected`() {
        assertFalse(isValidRestoreGroup(group(chapters = emptyList())))
    }

    @Test
    fun `non-positive chapter ids are rejected`() {
        assertFalse(isValidRestoreGroup(group(chapters = listOf(ChapterBackup(0, "章", 1)))))
        assertFalse(isValidRestoreGroup(group(chapters = listOf(ChapterBackup(1, "章", 1), ChapterBackup(-2, "章", 2)))))
    }

    @Test
    fun `valid positive ids and non-empty chapters pass`() {
        assertTrue(isValidRestoreGroup(group()))
        assertTrue(
            isValidRestoreGroup(
                group(chapters = listOf(ChapterBackup(11, "", 20), ChapterBackup(12, "第二章", 10)))
            )
        )
    }
}
