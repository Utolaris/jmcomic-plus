package com.par9uet.jm.data.models

/**
 * 一个章节的图片列表。
 *
 * `albumId` / `scrambleId` / `speed` 属于**本次章节响应**，不能提升为全局状态——
 * 不同章节的加扰参数不同，混用会把后一个章节的参数套到前一个章节的图上。
 */
data class ComicPageList(
    val urls: List<String>,
    val albumId: Int,
    val scrambleId: Int,
    val speed: String,
)
