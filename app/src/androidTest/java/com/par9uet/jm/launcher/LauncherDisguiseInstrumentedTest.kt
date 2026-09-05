package com.par9uet.jm.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherDisguise.Default))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherDisguise.SystemTools))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(LauncherDisguise.Gallery))
    }

    private fun state(disguise: LauncherDisguise): Int = context.packageManager
        .getComponentEnabledSetting(
            ComponentName(context, context.packageName + disguise.aliasClassName),
        )
}
