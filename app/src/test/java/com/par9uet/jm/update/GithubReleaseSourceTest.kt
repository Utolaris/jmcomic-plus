package com.par9uet.jm.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GithubReleaseSourceTest {
    private val releaseJson = """{
        "tag_name":"v1.12.0", "name":"release", "body":"changes", "assets":[
            {"name":"source.zip", "browser_download_url":"https://example.com/source.zip"},
            {"name":"other.apk", "browser_download_url":"https://example.com/other.apk"},
            {"name":"jm-mobile_v1.12.0_arm64.apk", "browser_download_url":"https://example.com/app.apk"}
        ]
    }"""

    @Test fun `release selects the version matching apk and supplies release page fallback`() {
        val release = parseGithubRelease(releaseJson)
        assertEquals("1.12.0", release.version)
        assertEquals("https://example.com/app.apk", release.downloadUrl)
        assertEquals("https://github.com/Utolaris/jmcomic-plus/releases/tag/v1.12.0", release.url)
        assertEquals("changes", release.body)
    }

    @Test fun `release without apk or version reports actionable errors`() {
        assertEquals("当前 Release 未提供兼容的 APK 安装包", assertThrows(IllegalStateException::class.java) {
            parseGithubRelease("""{"tag_name":"v1.0", "assets":[]}""")
        }.message)
        assertEquals("未读取到 Release 版本号", assertThrows(IllegalStateException::class.java) {
            parseGithubRelease("""{"assets":[]}""")
        }.message)
    }

    @Test fun `version comparison is numeric and tolerates prefixes missing components and build suffix`() {
        assertTrue(compareVersion("1.12.0", "1.9.9") > 0)
        assertTrue(compareVersion("1.2.0", "2.0") < 0)
        assertEquals(0, compareVersion("v1.2", "V1.2.0-nagram"))
    }

    @Test fun `network adapter sets github headers and rejects http errors`() = runTest {
        var code = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("application/vnd.github+json", chain.request().header("Accept"))
            assertEquals("jmcomic-plus-android", chain.request().header("User-Agent"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(releaseJson.toResponseBody()).build()
        }.build()
        val source = GithubReleaseSource(client)
        assertEquals("1.12.0", source.latest().version)
        code = 503
        try {
            source.latest()
            fail("expected HTTP failure")
        } catch (e: IllegalStateException) {
            assertEquals("GitHub 返回 503", e.message)
        }
    }
}
