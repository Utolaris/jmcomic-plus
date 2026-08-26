package com.par9uet.jm.favorites.presentation

import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.model.FavoritesModal
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesModalFlowTest {
    @Test
    fun `folder management can reach every folder action modal and return to management`() {
        var modal: FavoritesModal? = reduceFavoritesModal(
            current = null,
            intent = FavoritesIntent.FolderManagementOpened,
        )
        assertEquals(FavoritesModal.FolderManagement, modal)

        modal = reduceFavoritesModal(modal, FavoritesIntent.CreateFolderOpened)
        assertEquals(FavoritesModal.CreateFolder, modal)
        modal = reduceFavoritesModal(modal, FavoritesIntent.FolderActionDismissed)
        assertEquals(FavoritesModal.FolderManagement, modal)

        modal = reduceFavoritesModal(
            modal,
            FavoritesIntent.RenameFolderOpened(folderId = 7, folderName = "Old"),
        )
        assertEquals(FavoritesModal.RenameFolder(7, "Old"), modal)
        modal = reduceFavoritesModal(modal, FavoritesIntent.FolderActionDismissed)
        assertEquals(FavoritesModal.FolderManagement, modal)

        modal = reduceFavoritesModal(
            modal,
            FavoritesIntent.DeleteFolderOpened(folderId = 7, folderName = "Old"),
        )
        assertEquals(FavoritesModal.DeleteFolder(7, "Old"), modal)
        modal = reduceFavoritesModal(modal, FavoritesIntent.FolderActionDismissed)
        assertEquals(FavoritesModal.FolderManagement, modal)

        assertEquals(null, reduceFavoritesModal(modal, FavoritesIntent.FolderManagementDismissed))
    }

    @Test
    fun `selection actions do not open invisible modals without a selection`() {
        assertEquals(
            null,
            reduceFavoritesModal(null, FavoritesIntent.MoveSelected, hasSelection = false),
        )
        assertEquals(
            FavoritesModal.Move,
            reduceFavoritesModal(null, FavoritesIntent.MoveSelected, hasSelection = true),
        )
        assertEquals(
            FavoritesModal.Uncollect,
            reduceFavoritesModal(null, FavoritesIntent.UncollectSelected, hasSelection = true),
        )
    }
}
