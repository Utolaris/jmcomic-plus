package com.par9uet.jm.retrofit.model

/** The album and scramble identifiers belong to this chapter response and must not be global. */
data class ComicPicListResponse(
    val list: List<String>,
    val __aId: Int,
    val __scrambleId: Int,
    val __speed: String,
)
