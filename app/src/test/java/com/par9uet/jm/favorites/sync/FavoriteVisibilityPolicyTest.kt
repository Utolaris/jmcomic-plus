package com.par9uet.jm.favorites.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteVisibilityPolicyTest {
    @Test
    fun `initial favorites entry waits for authentication and fires once`() {
        val policy = FavoriteVisibilityPolicy()

        assertFalse(policy.onVisibilityChanged(true))
        assertTrue(policy.onAuthenticationChanged(true))
        assertFalse(policy.onAuthenticationChanged(true))
        assertFalse(policy.onVisibilityChanged(true))
    }

    @Test
    fun `reentering favorites after leaving fires another eligible entry`() {
        val policy = FavoriteVisibilityPolicy()

        policy.onAuthenticationChanged(true)
        assertTrue(policy.onVisibilityChanged(true))
        assertFalse(policy.onVisibilityChanged(true))
        assertFalse(policy.onVisibilityChanged(false))
        assertTrue(policy.onVisibilityChanged(true))
    }

    @Test
    fun `login while favorites is visible fires once`() {
        val policy = FavoriteVisibilityPolicy()

        assertFalse(policy.onVisibilityChanged(true))
        assertTrue(policy.onAuthenticationChanged(true))
        assertFalse(policy.onAuthenticationChanged(true))
        assertFalse(policy.onAuthenticationChanged(false))
        assertTrue(policy.onAuthenticationChanged(true))
    }
}
