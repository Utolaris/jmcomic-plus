package com.par9uet.jm.store

import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.utils.LauncherIdentityApplier

class NoOpLauncherIdentityApplier : LauncherIdentityApplier {
    override fun apply(disguise: LauncherDisguise) = Unit
}
