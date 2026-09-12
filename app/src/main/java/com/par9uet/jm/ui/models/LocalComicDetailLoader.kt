package com.par9uet.jm.ui.models

import androidx.compose.runtime.staticCompositionLocalOf
import com.par9uet.jm.data.models.Comic

/**
 * 按 id 取漫画详情，由组合根提供一次。
 *
 * 用途是"长按 JM{id} 标签查看详情信息"这类诊断入口。组件原本自己
 * `getKoin().get<ComicRepository>()` 取数，使 `ui/components` 直接依赖 L3/L4；
 * 现在组件只声明需要"一个能按 id 取详情的能力"，实现留在根。
 */
fun interface ComicDetailLoader {
    suspend fun load(comicId: Int): Comic?
}

val LocalComicDetailLoader = staticCompositionLocalOf<ComicDetailLoader?> { null }
