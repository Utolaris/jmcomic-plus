package com.par9uet.jm.ui.models

import androidx.compose.runtime.staticCompositionLocalOf
import com.par9uet.jm.data.models.Comic

/**
 * 打开漫画详情的入口，由组合根提供一次。
 *
 * 列表项组件原本直接 `koinActivityViewModel()` 拿到 `ComicDetailViewModel`，先用列表项
 * 预置详情状态（首帧就能显示封面与标题）再导航——这让通用组件反向依赖 L2 的 ViewModel。
 * 改为把这段编排交给根：`ui/components` 只调用 [ComicDetailOpener.open]，
 * 不 import `ui.viewModel`，也不再自己取 Koin。
 */
fun interface ComicDetailOpener {
    fun open(comic: Comic)
}

val LocalComicDetailOpener = staticCompositionLocalOf<ComicDetailOpener?> { null }
