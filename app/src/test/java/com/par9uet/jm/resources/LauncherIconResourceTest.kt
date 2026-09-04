package com.par9uet.jm.resources

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {
    @Test
    fun `adaptive launcher icons keep the release resource qualifier`() {
        val resourceRoot = resourceRoot()
        val adaptiveIconRoot = resourceRoot.resolve("mipmap-anydpi-v26")

        mapOf(
            "ic_launcher.xml" to "ic_launcher",
            "ic_launcher_round.xml" to "ic_launcher",
            "logo.xml" to "logo",
            "logo_round.xml" to "logo",
        ).forEach { (fileName, resourceName) ->
            val adaptiveIcon = adaptiveIconRoot.resolve(fileName)
            assertTrue(
                "$fileName must remain an Android 8+ adaptive icon",
                Files.isRegularFile(adaptiveIcon),
            )
            assertFalse(
                "$fileName must not shadow the release icon from an unqualified directory",
                Files.exists(resourceRoot.resolve("mipmap-anydpi/$fileName")),
            )

            val xml = Files.readString(adaptiveIcon)
            assertTrue(
                "$fileName must use the original full-size foreground",
                xml.contains("@mipmap/${resourceName}_foreground"),
            )
        }

        assertFalse(
            "the launcher must not add another inset around the release foreground",
            Files.exists(resourceRoot.resolve("drawable/logo_foreground_full_bleed.xml")),
        )
    }

    private fun resourceRoot(): Path = sequenceOf(
        Path.of("src/main/res"),
        Path.of("app/src/main/res"),
    ).first(Files::exists)
}
