package com.par9uet.jm.ui.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val PullDownSearchThreshold = 72.dp
private const val PULL_TRIGGER_CONFIRMATION_MILLIS = 130L

internal enum class PullDownActionPhase {
    IDLE,
    PULLING,
    ARMED,
    TRIGGERING,
}

/** Observable release-triggered pull state. It never owns or consumes scroll input. */
internal class PullDownActionState(
    private val threshold: Float,
) {
    var distance by mutableFloatStateOf(0f)
        private set
    var phase by mutableStateOf(PullDownActionPhase.IDLE)
        private set
    val progress: Float get() = (distance / threshold).coerceIn(0f, 1f)
    val isArmed: Boolean get() = phase == PullDownActionPhase.ARMED
    val isTriggering: Boolean get() = phase == PullDownActionPhase.TRIGGERING

    private var triggerClaimed = false
    private var armedHapticSent = false

    /** Returns true only on the first PULLING -> ARMED crossing in a gesture. */
    fun onPull(deltaY: Float, isAtTop: Boolean): Boolean {
        if (phase == PullDownActionPhase.TRIGGERING) return false
        if (!isAtTop) {
            reset()
            return false
        }
        distance = (distance + deltaY).coerceAtLeast(0f)
        phase = when {
            distance <= 0f -> PullDownActionPhase.IDLE
            distance >= threshold -> PullDownActionPhase.ARMED
            else -> PullDownActionPhase.PULLING
        }
        val shouldHaptic = phase == PullDownActionPhase.ARMED && !armedHapticSent
        if (shouldHaptic) armedHapticSent = true
        return shouldHaptic
    }

    fun release(): Boolean {
        val shouldTrigger = phase == PullDownActionPhase.ARMED && !triggerClaimed
        if (shouldTrigger) {
            triggerClaimed = true
            phase = PullDownActionPhase.TRIGGERING
            distance = threshold
        } else if (phase != PullDownActionPhase.TRIGGERING) {
            reset()
        }
        return shouldTrigger
    }

    fun completeTrigger() = reset()

    fun reset() {
        distance = 0f
        phase = PullDownActionPhase.IDLE
        triggerClaimed = false
        armedHapticSent = false
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
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    fun scheduleTriggerIfArmed() {
        if (!enabled || !state.release()) return
        coroutineScope.launch {
            delay(PULL_TRIGGER_CONFIRMATION_MILLIS)
            state.completeTrigger()
            currentOnTrigger.value()
        }
    }
    val connection = remember(state, enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled) {
                    state.reset()
                } else if (source == NestedScrollSource.UserInput &&
                    (available.y < 0f || !currentIsAtTop.value())
                ) {
                    if (state.onPull(available.y, currentIsAtTop.value())) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (enabled && source == NestedScrollSource.UserInput && available.y > 0f) {
                    if (state.onPull(available.y, currentIsAtTop.value())) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                scheduleTriggerIfArmed()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                scheduleTriggerIfArmed()
                if (!state.isTriggering) state.reset()
                return Velocity.Zero
            }
        }
    }
    nestedScroll(connection)
}
