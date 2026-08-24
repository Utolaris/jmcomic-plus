package com.par9uet.jm.ui.screens

import androidx.compose.runtime.Immutable

/**
 * Explicit bottom chrome mode for the comic detail page. UI mode is decided by this state only;
 * text-field focus controls keyboard/IME behavior and never replaces the mode.
 */
internal enum class DetailBottomMode {
    ACTIONS,
    COMMENT,
}

/**
 * Small pure reducer for the detail bottom chrome. Holding it outside the screen keeps the
 * ACTIONS <-> COMMENT transitions unit-testable.
 */
@Immutable
internal data class DetailBottomModeState(
    val mode: DetailBottomMode = DetailBottomMode.ACTIONS,
    val replyTargetId: Int? = null,
) {
    fun enterComment(): DetailBottomModeState =
        DetailBottomModeState(mode = DetailBottomMode.COMMENT, replyTargetId = null)

    fun enterReply(targetId: Int): DetailBottomModeState =
        DetailBottomModeState(mode = DetailBottomMode.COMMENT, replyTargetId = targetId)

    fun cancel(): DetailBottomModeState =
        DetailBottomModeState(mode = DetailBottomMode.ACTIONS, replyTargetId = null)

    fun onBack(): DetailBottomModeState =
        if (mode == DetailBottomMode.COMMENT) cancel() else this
}
