package com.par9uet.jm.data.models

/**
 * 搜索结果页。
 *
 * [redirectComicId] 非空表示服务端把这次查询判定为唯一一篇漫画（`redirect_aid`），
 * 此时 [items] 为空，调用方应直接跳转该漫画而不是继续分页。
 * `redirect_aid` 是搜索结果特有的协议字段，所以单独一个类型，不塞进 [ComicPage]。
 */
data class ComicSearchPage(
    val items: List<Comic>,
    val total: Int,
    val redirectComicId: Int? = null,
)
