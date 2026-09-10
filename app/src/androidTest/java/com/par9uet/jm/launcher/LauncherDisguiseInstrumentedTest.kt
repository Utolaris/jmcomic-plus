package com.par9uet.jm.launcher

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.par9uet.jm.data.models.LauncherDisguise
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LauncherDisguiseInstrumentedTest {
    private lateinit var context: Context
    private lateinit var applier: LauncherDisguiseApplier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        applier = LauncherDisguiseApplier(context)
    }

    @After
    fun tearDown() {
        applier.apply(LauncherDisguise.Default)
    }

    @Test
    fun `switching launcher disguise enables one alias and disables the others`() {
        applier.apply(LauncherDisguise.Gallery)
        assertEquals(setOf(aliasClassName(LauncherDisguise.Gallery)), launcherAliases())

        applier.apply(LauncherDisguise.SystemTools)
        assertEquals(setOf(aliasClassName(LauncherDisguise.SystemTools)), launcherAliases())

        applier.apply(LauncherDisguise.Default)
        assertEquals(setOf(aliasClassName(LauncherDisguise.Default)), launcherAliases())
    }

    private fun aliasClassName(disguise: LauncherDisguise): String =
        launcherAliasComponent(context, disguise).className

    /**
     * Aliases the launcher can actually start. Asserting the recorded setting instead would
     * require an explicit write, which the applier skips when the manifest default already
     * matches the disguise that was asked for.
     */
    private fun launcherAliases(): Set<String> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
        0,
    ).map { it.activityInfo.name }.toSet()
}
