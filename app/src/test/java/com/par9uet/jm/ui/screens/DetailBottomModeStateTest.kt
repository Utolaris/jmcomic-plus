package com.par9uet.jm.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailBottomModeStateTest {
    @Test
    fun initialModeIsActions() {
        assertEquals(DetailBottomMode.ACTIONS, DetailBottomModeState().mode)
        assertNull(DetailBottomModeState().replyTargetId)
    }

    @Test
    fun topCommentButtonEntersCommentMode() {
        val state = DetailBottomModeState().enterComment()
        assertEquals(DetailBottomMode.COMMENT, state.mode)
        assertNull(state.replyTargetId)
    }

    @Test
    fun cancelReturnsToActions() {
        val state = DetailBottomModeState().enterComment().cancel()
        assertEquals(DetailBottomMode.ACTIONS, state.mode)
        assertNull(state.replyTargetId)
    }

    @Test
    fun replyEntersCommentModeWithTarget() {
        val state = DetailBottomModeState().enterReply(42)
        assertEquals(DetailBottomMode.COMMENT, state.mode)
        assertEquals(42, state.replyTargetId)
    }

    @Test
    fun sendSuccessReturnsToActionsAndClearsReply() {
        val state = DetailBottomModeState().enterReply(7).cancel()
        assertEquals(DetailBottomMode.ACTIONS, state.mode)
        assertNull(state.replyTargetId)
    }

    @Test
    fun sendFailureKeepsCommentModeAndReply() {
        val state = DetailBottomModeState().enterReply(7)
        assertEquals(DetailBottomMode.COMMENT, state.mode)
        assertEquals(7, state.replyTargetId)
    }

    @Test
    fun systemBackInCommentReturnsToActions() {
        val state = DetailBottomModeState().enterReply(3).onBack()
        assertEquals(DetailBottomMode.ACTIONS, state.mode)
        assertNull(state.replyTargetId)
    }

    @Test
    fun systemBackInActionsKeepsActionsAllowingNavigation() {
        assertEquals(DetailBottomMode.ACTIONS, DetailBottomModeState().onBack().mode)
    }
}
