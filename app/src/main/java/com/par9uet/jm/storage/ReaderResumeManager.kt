package com.par9uet.jm.storage
import com.google.gson.reflect.TypeToken
import com.par9uet.jm.storage.SecureStorage
import com.par9uet.jm.storage.StorageReadResult

/**
 * Marks the chapter currently open in the reader so a process death while reading can resume.
 * Cleared only on explicit back-out — process kill never runs onDispose, so the mark survives.
 */
data class ReaderResumeSession(
    val chapterId: Int,
    val localOnly: Boolean = false,
    val updatedAtMillis: Long = 0L,
)

interface ReaderResumePersistence {
    fun save(session: ReaderResumeSession)
    fun load(): ReaderResumeSession?
    fun clear()
}

class SecureReaderResumePersistence(
    private val secureStorage: SecureStorage,
) : ReaderResumePersistence {
    companion object {
        private const val STORAGE_KEY = "readerResume"
    }

    override fun save(session: ReaderResumeSession) {
        secureStorage.set(STORAGE_KEY, session)
    }

    override fun load(): ReaderResumeSession? {
        return when (
            val result = secureStorage.get<ReaderResumeSession>(
                STORAGE_KEY,
                object : TypeToken<ReaderResumeSession>() {}.type,
            )
        ) {
            is StorageReadResult.Success -> result.value
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            is StorageReadResult.TemporaryUnavailable,
            -> null
        }
    }

    override fun clear() {
        secureStorage.remove(STORAGE_KEY)
    }
}

class ReaderResumeManager(
    private val persistence: ReaderResumePersistence,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_RESUME_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }

    private val lock = Any()
    private val exitedKeys = mutableSetOf<String>()

    constructor(
        secureStorage: SecureStorage,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(SecureReaderResumePersistence(secureStorage), nowMillis)

    /**
     * Entering the reader: clear any prior exit latch for this chapter and persist a resume mark.
     */
    fun beginReading(chapterId: Int, localOnly: Boolean) {
        if (chapterId <= 0) return
        synchronized(lock) { exitedKeys -= exitKey(chapterId, localOnly) }
        markReading(chapterId, localOnly)
    }

    /**
     * Explicit back-out: drop the resume mark and latch so a later dispose-time
     * [markReading] cannot resurrect it.
     */
    fun endReading(chapterId: Int, localOnly: Boolean) {
        if (chapterId <= 0) return
        synchronized(lock) { exitedKeys += exitKey(chapterId, localOnly) }
        clearIfChapter(chapterId, localOnly)
    }

    fun markReading(chapterId: Int, localOnly: Boolean) {
        if (chapterId <= 0) return
        synchronized(lock) {
            if (exitKey(chapterId, localOnly) in exitedKeys) return
        }
        persistence.save(
            ReaderResumeSession(
                chapterId = chapterId,
                localOnly = localOnly,
                updatedAtMillis = nowMillis(),
            ),
        )
    }

    fun clearIfChapter(chapterId: Int, localOnly: Boolean) {
        val current = persistence.load() ?: return
        if (current.chapterId == chapterId && current.localOnly == localOnly) {
            persistence.clear()
        }
    }

    fun peekResumable(maxAgeMillis: Long = MAX_RESUME_AGE_MILLIS): ReaderResumeSession? {
        val session = persistence.load() ?: return null
        val age = nowMillis() - session.updatedAtMillis
        return session.takeIf { session.chapterId > 0 && age in 0..maxAgeMillis }
    }

    private fun exitKey(chapterId: Int, localOnly: Boolean): String = "$chapterId:$localOnly"
}
