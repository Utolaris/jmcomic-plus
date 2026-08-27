package com.par9uet.jm.data.models

/**
 * Server runtime config payload. The endpoint name (/setting) and the Gson/storage key
 * `remoteSetting` are kept for compatibility; in app code treat this as remote config,
 * not a user setting.
 */
data class RemoteSetting(
    val imgHost: String = "",
)
