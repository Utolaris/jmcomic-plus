package com.par9uet.jm.database.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "favorite_metadata_terms",
    primaryKeys = ["accountId", "albumId", "termType", "normalizedValue"],
    indices = [
        Index(value = ["accountId", "termType", "normalizedValue"]),
        Index(value = ["accountId", "albumId"]),
    ],
)
data class FavoriteMetadataTermEntity(
    val accountId: Int,
    val albumId: Int,
    val termType: String,
    val value: String,
    val normalizedValue: String,
)
