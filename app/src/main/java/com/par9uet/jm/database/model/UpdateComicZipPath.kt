package com.par9uet.jm.database.model

/** Updates the historical zipPath column; the value can also be a directory or document URI. */
data class UpdateComicZipPath(
    val id: Int,
    val zipPath: String,
)