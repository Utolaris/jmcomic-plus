package com.par9uet.jm.data.models

/**
 * 一页漫画列表。
 *
 * [total] 是服务端声明的总条数；为 `null` 表示该接口不返回 total（例如 watch_list 历史列表），
 * 调用方需改用 [items] 的条数推断末页。两种情形的末页判据不同，所以这里保留可空，
 * 而不是用一个"看起来有值"的默认值把它抹平。
 */
data class ComicPage(
    val items: List<Comic>,
    val total: Int? = null,
)
