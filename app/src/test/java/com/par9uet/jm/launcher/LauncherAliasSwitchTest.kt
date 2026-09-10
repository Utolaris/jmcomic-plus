package com.par9uet.jm.launcher

import com.par9uet.jm.data.models.LauncherDisguise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The alias swap must never leave the app with no launcher entry at all. */
class LauncherAliasSwitchTest {
    private val calls = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    @Test
    fun `target alias is enabled before the others are disabled`() {
        val enabled = switchLauncherAlias(
            disguise = LauncherDisguise.Gallery,
            // Every alias needs a write, so the recorded order shows the whole swap.
            isEnabled = { it != LauncherDisguise.Gallery },
            setEnabled = { item, value -> calls += "${item.id}=$value" },
            onFailure = { failures += it },
        )

        assertTrue(enabled)
        assertEquals(
            listOf("gallery=true", "default=false", "system_tools=false"),
            calls,
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a failed enable keeps the current entry untouched`() {
        val enabled = switchLauncherAlias(
            disguise = LauncherDisguise.Gallery,
            isEnabled = { it == LauncherDisguise.Default },
            setEnabled = { item, value ->
                if (item == LauncherDisguise.Gallery) error("PackageManager 拒绝启用")
                calls += "${item.id}=$value"
            },
            onFailure = { failures += it },
        )

        assertFalse(enabled)
        assertTrue("当前入口不能被关闭：$calls", calls.isEmpty())
        assertEquals(1, failures.size)
    }

    @Test
    fun `an already enabled target is not written again`() {
        val enabled = switchLauncherAlias(
            disguise = LauncherDisguise.Gallery,
            isEnabled = { it == LauncherDisguise.Gallery || it == LauncherDisguise.Default },
            setEnabled = { item, value -> calls += "${item.id}=$value" },
            onFailure = { failures += it },
        )

        assertTrue(enabled)
        // system_tools is already disabled, so only the default entry has to go.
        assertEquals(listOf("default=false"), calls)
    }

    @Test
    fun `a failed disable still reports the switch as applied`() {
        val enabled = switchLauncherAlias(
            disguise = LauncherDisguise.SystemTools,
            isEnabled = { it == LauncherDisguise.Default },
            setEnabled = { item, value ->
                if (item == LauncherDisguise.Default) error("PackageManager 拒绝关闭")
                calls += "${item.id}=$value"
            },
            onFailure = { failures += it },
        )

        assertTrue(enabled)
        assertEquals(listOf("system_tools=true"), calls)
        assertEquals(1, failures.size)
    }
}
