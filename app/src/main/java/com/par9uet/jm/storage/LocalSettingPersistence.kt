package com.par9uet.jm.storage

import com.par9uet.jm.data.models.LocalSetting

/**
 * Write/read boundary consumed by LocalSettingManager so mutations and their invariants can
 * be tested without Android storage. The production implementation keeps the single encrypted
 * JSON document and its legacy migration behavior unchanged.
 */
interface LocalSettingPersistence {
    /** Returns the persisted settings, or null when nothing was stored yet. */
    fun load(): LocalSetting?

    fun persist(localSetting: LocalSetting)
}
