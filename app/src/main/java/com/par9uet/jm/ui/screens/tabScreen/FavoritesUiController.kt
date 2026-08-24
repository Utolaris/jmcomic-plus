package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.par9uet.jm.data.models.Comic

internal enum class FavoritesToolbarMode {
    NORMAL,
    SEARCH,
    SELECTION,
}

internal fun resolveFavoritesToolbarMode(
    searchActive: Boolean,
    selectedCount: Int,
): FavoritesToolbarMode = when {
    selectedCount > 0 -> FavoritesToolbarMode.SELECTION
    searchActive -> FavoritesToolbarMode.SEARCH
    else -> FavoritesToolbarMode.NORMAL
}

/** Stable bridge between the one Favorites paging composition and its overlay toolbar. */
@Stable
internal class FavoritesUiController {
    var searchActive by mutableStateOf(false)
        private set
    var filterDialogVisible by mutableStateOf(false)
        private set
    var folderManagementVisible by mutableStateOf(false)
        private set
    var moveDialogVisible by mutableStateOf(false)
        private set
    var deleteDialogVisible by mutableStateOf(false)
        private set

    private var selectedComicsProvider: (() -> List<Comic>)? = null

    fun enterSearch() {
        searchActive = true
    }

    fun exitSearch() {
        searchActive = false
    }

    fun showFilterDialog() {
        filterDialogVisible = true
    }

    fun dismissFilterDialog() {
        filterDialogVisible = false
    }

    fun showFolderManagement() {
        folderManagementVisible = true
    }

    fun dismissFolderManagement() {
        folderManagementVisible = false
    }

    fun showMoveDialog() {
        moveDialogVisible = true
    }

    fun dismissMoveDialog() {
        moveDialogVisible = false
    }

    fun showDeleteDialog() {
        deleteDialogVisible = true
    }

    fun dismissDeleteDialog() {
        deleteDialogVisible = false
    }

    /** Clears every transient UI flag when the Favorites root tab is left. */
    fun clearAllTransientUi() {
        searchActive = false
        filterDialogVisible = false
        folderManagementVisible = false
        moveDialogVisible = false
        deleteDialogVisible = false
    }

    fun bindSelectedComics(provider: () -> List<Comic>) {
        selectedComicsProvider = provider
    }

    fun unbindSelectedComics(provider: () -> List<Comic>) {
        if (selectedComicsProvider === provider) selectedComicsProvider = null
    }

    fun selectedComics(): List<Comic> = selectedComicsProvider?.invoke().orEmpty()
}

@Composable
internal fun rememberFavoritesUiController(): FavoritesUiController =
    remember { FavoritesUiController() }
