package com.par9uet.jm.storage

import com.par9uet.jm.data.models.LocalSetting

/** Distinguishes “no value yet” from “value exists but cannot be read right now”. */
sealed class LocalSettingLoadResult {
    data class Success(val value: LocalSetting) : LocalSettingLoadResult()
    data object Missing : LocalSettingLoadResult()
    data object TemporaryUnavailable : LocalSettingLoadResult()
}

/**
 * Write/read boundary consumed by LocalSettingManager so mutations and their invariants can
 * be tested without Android storage. Temporary Keystore/storage outages must stay distinct
 * from “nothing was ever saved” so the app never treats a missing read as an unlocked default.
 */
interface LocalSettingPersistence {
    fun load(): LocalSettingLoadResult

    fun persist(localSetting: LocalSetting): StorageWriteResult
}
