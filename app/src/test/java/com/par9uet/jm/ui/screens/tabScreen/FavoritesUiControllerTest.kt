package com.par9uet.jm.ui.screens.tabScreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesUiControllerTest {
    @Test
    fun leavingFavoritesClearsAllTransientFlags() {
        val controller = FavoritesUiController()
        controller.enterSearch()
        controller.showFilterDialog()
        controller.showFolderManagement()
        controller.showMoveDialog()
        controller.showDeleteDialog()

        assertTrue(controller.searchActive)
        assertTrue(controller.filterDialogVisible)
        assertTrue(controller.folderManagementVisible)
        assertTrue(controller.moveDialogVisible)
        assertTrue(controller.deleteDialogVisible)

        controller.clearAllTransientUi()

        assertFalse(controller.searchActive)
        assertFalse(controller.filterDialogVisible)
        assertFalse(controller.folderManagementVisible)
        assertFalse(controller.moveDialogVisible)
        assertFalse(controller.deleteDialogVisible)
    }

    @Test
    fun openAndDismissDeleteDialogLeavesCleanState() {
        val controller = FavoritesUiController()
        controller.showDeleteDialog()
        assertTrue(controller.deleteDialogVisible)

        controller.dismissDeleteDialog()
        assertFalse(controller.deleteDialogVisible)
        assertFalse(controller.searchActive)
        assertFalse(controller.moveDialogVisible)
    }
}

