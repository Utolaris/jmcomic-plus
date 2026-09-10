package com.par9uet.jm.store

import com.google.gson.reflect.TypeToken
import com.par9uet.jm.storage.SecureStorage

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
        return runCatching {
            secureStorage.get<ReaderResumeSession>(
                STORAGE_KEY,
                object : TypeToken<ReaderResumeSession>() {}.type,
            )
        }.getOrNull()
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

    constructor(
        secureStorage: SecureStorage,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(SecureReaderResumePersistence(secureStorage), nowMillis)

    fun markReading(chapterId: Int, localOnly: Boolean) {
        if (chapterId <= 0) return
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
}
