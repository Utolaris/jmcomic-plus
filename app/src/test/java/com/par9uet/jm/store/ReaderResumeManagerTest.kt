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
        manager.beginReading(42, localOnly = false)
        assertEquals(42, manager.peekResumable()?.chapterId)
        assertEquals(false, manager.peekResumable()?.localOnly)
        clock.now += 60_000
        assertEquals(42, manager.peekResumable()?.chapterId)
    }

    @Test
    fun expiredSessionIsNotResumable() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.beginReading(7, localOnly = true)
        clock.now += ReaderResumeManager.MAX_RESUME_AGE_MILLIS + 1
        assertNull(manager.peekResumable())
    }

    @Test
    fun clearOnlyRemovesMatchingChapter() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.beginReading(10, localOnly = false)
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

    @Test
    fun explicitBackThenDisposeTimeMarkDoesNotResurrectResume() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.beginReading(55, localOnly = false)
        assertEquals(55, manager.peekResumable()?.chapterId)

        // Back: latch exit and clear the durable mark.
        manager.endReading(55, localOnly = false)
        assertNull(manager.peekResumable())

        // popBackStack -> onDispose still saves progress and may call markReading.
        manager.markReading(55, localOnly = false)
        assertNull(manager.peekResumable())
    }

    @Test
    fun reenterAfterBackRestoresResumeMark() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.beginReading(9, localOnly = true)
        manager.endReading(9, localOnly = true)
        assertNull(manager.peekResumable())

        manager.beginReading(9, localOnly = true)
        assertEquals(9, manager.peekResumable()?.chapterId)
        assertEquals(true, manager.peekResumable()?.localOnly)
    }

    @Test
    fun backgroundCheckpointStillRefreshesMarkWhenNotExited() {
        val clock = Clock(1_000_000L)
        val manager = ReaderResumeManager(FakePersistence()) { clock.now }
        manager.beginReading(3, localOnly = false)
        clock.now += 5_000
        manager.markReading(3, localOnly = false)
        assertEquals(1_005_000L, manager.peekResumable()?.updatedAtMillis)
    }
}
