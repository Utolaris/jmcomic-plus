package com.par9uet.jm.ui.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

internal val PullDownSearchThreshold = 72.dp

/** Pure release-triggered pull state. It never owns or consumes scroll input. */
internal class PullDownActionState(
    private val threshold: Float,
) {
    var distance: Float = 0f
        private set
    var isArmed: Boolean = false
        private set
    private var triggeredInGesture: Boolean = false

    fun onPull(deltaY: Float, isAtTop: Boolean) {
        if (!isAtTop) {
            distance = 0f
            isArmed = false
            return
        }
        if (triggeredInGesture) return
        distance = (distance + deltaY).coerceAtLeast(0f)
        isArmed = distance >= threshold
    }

    fun release(): Boolean {
        val shouldTrigger = isArmed && !triggeredInGesture
        if (shouldTrigger) triggeredInGesture = true
        distance = 0f
        isArmed = false
        return shouldTrigger
    }

    fun reset() {
        distance = 0f
        isArmed = false
        triggeredInGesture = false
    }
}

@Composable
internal fun rememberPullDownActionState(
    threshold: Dp = PullDownSearchThreshold,
): PullDownActionState {
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    return remember(thresholdPx) { PullDownActionState(thresholdPx) }
}

/**
 * Observes unconsumed vertical overscroll after a child has reached its top edge. Returning zero
 * from every callback keeps horizontal pager and normal vertical scrolling ownership unchanged.
 */
internal fun Modifier.pullDownToAction(
    state: PullDownActionState,
    enabled: Boolean = true,
    isAtTop: () -> Boolean,
    onTrigger: () -> Unit,
): Modifier = composed {
    val currentIsAtTop = rememberUpdatedState(isAtTop)
    val currentOnTrigger = rememberUpdatedState(onTrigger)
    val connection = remember(state, enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled) {
                    state.reset()
                } else if (source == NestedScrollSource.UserInput &&
                    (available.y < 0f || !currentIsAtTop.value())
                ) {
                    state.onPull(available.y, currentIsAtTop.value())
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (enabled && source == NestedScrollSource.UserInput && available.y > 0f) {
                    state.onPull(available.y, currentIsAtTop.value())
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (enabled && state.release()) {
                    currentOnTrigger.value()
                    state.reset()
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (enabled && state.release()) currentOnTrigger.value()
                state.reset()
                return Velocity.Zero
            }
        }
    }
    nestedScroll(connection)
}
