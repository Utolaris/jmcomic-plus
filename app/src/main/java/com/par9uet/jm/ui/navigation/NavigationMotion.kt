package com.par9uet.jm.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

object NavigationMotion {
    const val HierarchicalSpringStiffness = 700f
    const val HierarchicalSpringDampingRatio = 1f
    const val BackgroundParallaxFraction = 0.35f
    const val MainTabAnimationDurationMillis = 320

    val HierarchicalSpring: FiniteAnimationSpec<IntOffset> = spring(
        stiffness = HierarchicalSpringStiffness,
        dampingRatio = HierarchicalSpringDampingRatio,
    )

    val MainTabAnimationSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = MainTabAnimationDurationMillis,
        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    )

    fun hierarchicalEnter(): EnterTransition = slideInHorizontally(
        animationSpec = HierarchicalSpring,
        initialOffsetX = { fullWidth -> fullWidth },
    )

    fun hierarchicalExit(): ExitTransition = slideOutHorizontally(
        animationSpec = HierarchicalSpring,
        targetOffsetX = { fullWidth ->
            -(fullWidth * BackgroundParallaxFraction).roundToInt()
        },
    )

    fun hierarchicalPopEnter(): EnterTransition = slideInHorizontally(
        animationSpec = HierarchicalSpring,
        initialOffsetX = { fullWidth ->
            -(fullWidth * BackgroundParallaxFraction).roundToInt()
        },
    )

    fun hierarchicalPopExit(): ExitTransition = slideOutHorizontally(
        animationSpec = HierarchicalSpring,
        targetOffsetX = { fullWidth -> fullWidth },
    )
}
