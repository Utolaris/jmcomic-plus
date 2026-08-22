package com.par9uet.jm.store

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteOrderingTest {
    @Test
    fun `syncing a non-zero folder never rewrites the global all-favorites order`() {
        val order = resolveGlobalOrderAfterScopeSync(
            scopeFolderId = 5,
            scopeIndex = 0,
            existingGlobalOrder = 70,
        )
        assertEquals(70, order)
    }

    @Test
    fun `authoritative all-favorites sync adopts the remote index`() {
        assertEquals(
            70,
            resolveGlobalOrderAfterScopeSync(
                scopeFolderId = FAVORITE_SCOPE_ALL,
                scopeIndex = 70,
                existingGlobalOrder = 70,
            ),
        )
        assertEquals(
            71,
            resolveGlobalOrderAfterScopeSync(
                scopeFolderId = FAVORITE_SCOPE_ALL,
                scopeIndex = 71,
                existingGlobalOrder = 70,
            ),
        )
    }

    @Test
    fun `local move appends after known folder items instead of placing first`() {
        // X is moved into an empty folder: it is at position 0 only because the folder is empty.
        assertEquals(0, temporaryFolderOrder(existingFolderAlbumIds = emptyList()))
        // X is moved into a folder that already knows [A, B, C]: it appends at the end.
        assertEquals(3, temporaryFolderOrder(existingFolderAlbumIds = listOf(1, 2, 3)))
    }
}
