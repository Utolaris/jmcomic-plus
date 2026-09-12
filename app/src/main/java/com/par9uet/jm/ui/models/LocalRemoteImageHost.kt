package com.par9uet.jm.ui.models

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 远端图片主机，App 级环境值。
 *
 * 封面与头像的 URL 都由"远端主机 + 相对路径"拼出，因此几乎每个看图的地方都要它。
 * 早期做法是让每个组件/页面各自 `getKoin().get<RemoteConfigPreferences>()`，
 * 结果 `ui/components` 与多张页面都直接依赖 `storage`（L4），同一份配置被读了很多遍。
 * 现在由 `App` 读取一次并在此提供，消费方只读环境值，不注入持久化端口。
 */
val LocalRemoteImageHost = staticCompositionLocalOf { "" }
