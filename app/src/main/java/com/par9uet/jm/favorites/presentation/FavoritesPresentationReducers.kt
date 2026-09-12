package com.par9uet.jm.favorites.presentation

import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.model.FavoritesModal
import com.par9uet.jm.favorites.model.FavoritesViewportState

/**
 * Pure modal-state reducer. Kept out of the ViewModel so modal navigation rules stay
 * testable without constructing the full VM graph.
 */
internal fun reduceFavoritesModal(
    current: FavoritesModal?,
    intent: FavoritesIntent,
    hasSelection: Boolean = false,
): FavoritesModal? = when (intent) {
    FavoritesIntent.FilterOpened -> FavoritesModal.Filter
    FavoritesIntent.FilterCleared,
    FavoritesIntent.FilterDismissed,
    FavoritesIntent.ModalDismissed,
    FavoritesIntent.FolderManagementDismissed -> null
    FavoritesIntent.MoveSelected -> if (hasSelection) FavoritesModal.Move else current
    FavoritesIntent.UncollectSelected -> if (hasSelection) FavoritesModal.Uncollect else current
    FavoritesIntent.FolderManagementOpened -> FavoritesModal.FolderManagement
    FavoritesIntent.CreateFolderOpened -> FavoritesModal.CreateFolder
    is FavoritesIntent.RenameFolderOpened -> FavoritesModal.RenameFolder(
        folderId = intent.folderId,
        folderName = intent.folderName,
    )
    is FavoritesIntent.DeleteFolderOpened -> FavoritesModal.DeleteFolder(
        folderId = intent.folderId,
        folderName = intent.folderName,
    )
    FavoritesIntent.FolderActionDismissed -> FavoritesModal.FolderManagement
    else -> current
}

internal fun FavoritesViewportState.reset(): FavoritesViewportState =
    FavoritesViewportState(resetGeneration = resetGeneration + 1)

/** Toast copy for batch favorite mutations (move / uncollect). */
internal fun favoriteBatchMessage(succeeded: Int, failed: Int, action: String): String =
    if (failed == 0) "已$action $succeeded 部漫画"
    else "成功 $succeeded 部，失败 $failed 部"
