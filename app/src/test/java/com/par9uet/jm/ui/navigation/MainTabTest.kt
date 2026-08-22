package com.par9uet.jm.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabTest {
    @Test
    fun `main tabs keep the required order`() {
        assertEquals(
            listOf(MainTab.Home, MainTab.Collect, MainTab.Settings),
            MainTab.ordered,
        )
    }

    @Test
    fun `direction follows source and target indices`() {
        assertEquals(MainTabDirection.FORWARD, MainTab.Home.directionTo(MainTab.Collect))
        assertEquals(MainTabDirection.BACKWARD, MainTab.Collect.directionTo(MainTab.Home))
        assertEquals(MainTabDirection.FORWARD, MainTab.Collect.directionTo(MainTab.Settings))
        assertEquals(MainTabDirection.BACKWARD, MainTab.Settings.directionTo(MainTab.Collect))
        assertEquals(MainTabDirection.FORWARD, MainTab.Home.directionTo(MainTab.Settings))
        assertEquals(MainTabDirection.BACKWARD, MainTab.Settings.directionTo(MainTab.Home))
        assertEquals(MainTabDirection.NONE, MainTab.Home.directionTo(MainTab.Home))
        assertEquals(MainTabDirection.NONE, MainTab.Collect.directionTo(MainTab.Collect))
        assertEquals(MainTabDirection.NONE, MainTab.Settings.directionTo(MainTab.Settings))
    }
}
