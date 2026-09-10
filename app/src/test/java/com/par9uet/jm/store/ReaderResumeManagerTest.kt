package com.par9uet.jm.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderResumeManagerTest {

    private class FakePersistence : ReaderResumePersistence {
        var session: ReaderResumeSession? = null
        override fun save(session: ReaderResumeSession) {
            this.session = session
        }

        override fun load(): ReaderResumeSession? = session
        override fun clear() {
            session = null
        }
    }

    private class Clock(@Volatile var now: Long)

    @Test
    fun markAndPeekWithinAge() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.markReading(42, localOnly = false)
        assertEquals(42, manager.peekResumable()?.chapterId)
        assertEquals(false, manager.peekResumable()?.localOnly)
        clock.now += 60_000
        assertEquals(42, manager.peekResumable()?.chapterId)
    }

    @Test
    fun expiredSessionIsNotResumable() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.markReading(7, localOnly = true)
        clock.now += ReaderResumeManager.MAX_RESUME_AGE_MILLIS + 1
        assertNull(manager.peekResumable())
    }

    @Test
    fun clearOnlyRemovesMatchingChapter() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.markReading(10, localOnly = false)
        manager.clearIfChapter(chapterId = 11, localOnly = false)
        assertEquals(10, manager.peekResumable()?.chapterId)
        manager.clearIfChapter(chapterId = 10, localOnly = false)
        assertNull(manager.peekResumable())
    }

    @Test
    fun invalidChapterIdIsIgnored() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.markReading(0, localOnly = false)
        assertNull(manager.peekResumable())
    }
}
