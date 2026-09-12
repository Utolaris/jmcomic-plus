package com.par9uet.jm.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController

/**
 * L1 导航基础设施：把 Activity 范围的 `NavHostController` 传给任意层级的 UI 组件。
 *
 * 放在 `ui/navigation` 而不是 `ui/screens`，是为了让 `ui/components`、`ui/glass` 这类
 * 支撑层不必反向 import 具体页面包（`ui.screens`）。提供方仍是 `ui/screens/AppScreen`。
 */
val LocalMainNavController = staticCompositionLocalOf<NavHostController> {
    error("none")
}
