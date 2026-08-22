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
    fun `local move uses MAX(remoteOrder) + 1 instead of count`() {
        // Empty folder: max = -1 -> next = 0.
        assertEquals(0, nextTemporaryRemoteOrder(-1))
        // Dense orders 0,1,2 -> next = 3.
        assertEquals(3, nextTemporaryRemoteOrder(2))
        // Sparse orders 0,1,5,8 -> next = 9 (count would have produced 4).
        assertEquals(9, nextTemporaryRemoteOrder(8))
        // Sparse orders 0,1,3 -> next = 4 (count would have duplicated 3).
        assertEquals(4, nextTemporaryRemoteOrder(3))
    }
}
