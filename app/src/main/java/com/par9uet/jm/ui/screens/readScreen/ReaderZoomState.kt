package com.par9uet.jm.ui.screens.readScreen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val ORIGINAL_SIZE_SNAP_THRESHOLD = 1.01f

@Stable
class ReaderZoomState {
    var scale by mutableStateOf(MIN_ZOOM)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewportWidth by mutableStateOf(0)
    private var viewportHeight by mutableStateOf(0)

    val isZoomed: Boolean
        get() = scale > ORIGINAL_SIZE_SNAP_THRESHOLD || offset != Offset.Zero

    fun updateViewportSize(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        offset = clampOffset(offset, scale)
    }

    fun applyTransform(zoomChange: Float, panChange: Offset) {
        if (!zoomChange.isFinite() || zoomChange <= 0f) return

        val scaled = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val nextScale = if (scaled <= ORIGINAL_SIZE_SNAP_THRESHOLD) MIN_ZOOM else scaled
        scale = nextScale
        offset = if (nextScale == MIN_ZOOM) {
            Offset.Zero
        } else {
            clampOffset(offset + panChange, nextScale)
        }
    }

    fun reset() {
        scale = MIN_ZOOM
        offset = Offset.Zero
    }

    private fun clampOffset(value: Offset, targetScale: Float): Offset {
        if (targetScale <= ORIGINAL_SIZE_SNAP_THRESHOLD || viewportWidth <= 0) {
            return Offset.Zero
        }
        val maxX = max(0f, viewportWidth * (targetScale - MIN_ZOOM) / 2f)
        val maxY = max(0f, viewportHeight * (targetScale - MIN_ZOOM) / 2f)
        return Offset(
            x = value.x.coerceIn(-maxX, maxX),
            y = value.y.coerceIn(-maxY, maxY)
        )
    }
}

internal class ReaderGestureSession(
    private val startedZoomed: Boolean = false
) {
    var sawMultiplePointers: Boolean = false
        private set
    var ownsTransform: Boolean = false
        private set
    private var wasConsumed: Boolean = false

    fun observe(pointerCount: Int, consumed: Boolean = false) {
        if (pointerCount >= 2) {
            sawMultiplePointers = true
            ownsTransform = true
        }
        if (consumed) {
            wasConsumed = true
        }
    }

    fun claimSinglePointerPan() {
        if (startedZoomed) {
            ownsTransform = true
        }
    }

    fun canDispatchTap(distance: Float, maximumDistance: Float): Boolean =
        !sawMultiplePointers && !wasConsumed && distance < maximumDistance
}

internal class ReaderFreePanTracker(
    private val touchSlop: Float
) {
    private var accumulatedPan = Offset.Zero
    private var active = false

    fun add(panChange: Offset): Offset? {
        if (active) return panChange

        accumulatedPan += panChange
        val distance = accumulatedPan.getDistance()
        if (distance <= touchSlop) return null

        active = true
        val overSlopRatio = (distance - touchSlop) / distance
        return accumulatedPan * overSlopRatio
    }
}

internal fun isReaderCenter(position: Offset, size: IntSize): Boolean {
    if (size.width <= 0 || size.height <= 0) return false

    return position.x in size.width / 3f..size.width * 2f / 3f &&
        position.y in size.height / 3f..size.height * 2f / 3f
}

internal fun resetZoomFromCenterDoubleTap(
    zoomState: ReaderZoomState,
    position: Offset,
    size: IntSize
): Boolean {
    if (!zoomState.isZoomed || !isReaderCenter(position, size)) return false

    zoomState.reset()
    return true
}

@Composable
fun rememberReaderZoomState(): ReaderZoomState = remember { ReaderZoomState() }

@Composable
private fun Modifier.readerTransformHandling(zoomState: ReaderZoomState): Modifier {
    val touchSlop = LocalViewConfiguration.current.touchSlop

    return this
        .onSizeChanged { zoomState.updateViewportSize(it.width, it.height) }
        .pointerInput(zoomState, touchSlop) {
            awaitEachGesture {
                awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val session = ReaderGestureSession(startedZoomed = zoomState.isZoomed)
                session.observe(1)
                val freePanTracker = ReaderFreePanTracker(touchSlop)

                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val pressedChanges = event.changes.filter { it.pressed }
                    session.observe(pressedChanges.size)

                    when {
                        pressedChanges.size >= 2 -> {
                            zoomState.applyTransform(
                                zoomChange = event.calculateZoom(),
                                panChange = event.calculatePan()
                            )
                        }

                        pressedChanges.size == 1 && session.sawMultiplePointers -> {
                            if (zoomState.isZoomed) {
                                zoomState.applyTransform(
                                    zoomChange = 1f,
                                    panChange = pressedChanges.first().positionChange()
                                )
                            }
                        }

                        pressedChanges.size == 1 && zoomState.isZoomed -> {
                            val panChange = pressedChanges.first().positionChange()
                            if (session.ownsTransform) {
                                zoomState.applyTransform(zoomChange = 1f, panChange = panChange)
                            } else {
                                freePanTracker.add(panChange)?.let { appliedPan ->
                                    session.claimSinglePointerPan()
                                    zoomState.applyTransform(
                                        zoomChange = 1f,
                                        panChange = appliedPan
                                    )
                                }
                            }
                        }
                    }

                    if (session.ownsTransform) {
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .graphicsLayer(
            scaleX = zoomState.scale,
            scaleY = zoomState.scale,
            translationX = zoomState.offset.x,
            translationY = zoomState.offset.y
        )
}

@Composable
private fun Modifier.readerTapHandling(
    zoomState: ReaderZoomState,
    requireUnconsumedDown: Boolean = true,
    onNormalTap: (position: Offset, size: IntSize) -> Unit,
    onZoomedCenterTap: () -> Unit
): Modifier {
    val currentNormalTap by rememberUpdatedState(onNormalTap)
    val currentZoomedCenterTap by rememberUpdatedState(onZoomedCenterTap)

    return if (zoomState.isZoomed) {
        pointerInput(zoomState.isZoomed) {
            detectTapGestures(
                onDoubleTap = { position ->
                    resetZoomFromCenterDoubleTap(zoomState, position, size)
                },
                onTap = { position ->
                    if (zoomState.isZoomed && isReaderCenter(position, size)) {
                        currentZoomedCenterTap()
                    }
                }
            )
        }
    } else {
        pointerInput(zoomState.isZoomed, requireUnconsumedDown) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = requireUnconsumedDown,
                    pass = PointerEventPass.Final
                )
                var lastPosition = down.position
                val gestureSession = ReaderGestureSession()

                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Final)
                    gestureSession.observe(
                        pointerCount = event.changes.count { it.pressed || it.previousPressed },
                        consumed = event.changes.any { it.isConsumed }
                    )
                    event.changes.firstOrNull { it.id == down.id }?.let { primaryChange ->
                        lastPosition = primaryChange.position
                    }
                } while (event.changes.any { it.pressed })

                if (
                    gestureSession.canDispatchTap(
                        distance = (lastPosition - down.position).getDistance(),
                        maximumDistance = 10.dp.toPx()
                    )
                ) {
                    currentNormalTap(lastPosition, size)
                }
            }
        }
    }
}

/** Canonical reader gesture entry used by every reading mode. */
@Composable
fun Modifier.readerGestures(
    zoomState: ReaderZoomState,
    requireUnconsumedDown: Boolean = true,
    onNormalTap: (position: Offset, size: IntSize) -> Unit,
    onZoomedCenterTap: () -> Unit,
): Modifier = readerTapHandling(
    zoomState = zoomState,
    requireUnconsumedDown = requireUnconsumedDown,
    onNormalTap = onNormalTap,
    onZoomedCenterTap = onZoomedCenterTap,
).readerTransformHandling(zoomState)
