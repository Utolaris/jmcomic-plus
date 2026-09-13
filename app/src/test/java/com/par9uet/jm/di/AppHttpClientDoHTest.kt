package com.par9uet.jm.di

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Dns
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R07: every app-owned OkHttpClient that probes or fetches CDN/API hosts must share the
 * DoH resolver. System DNS is only allowed for the documented DoH bootstrap exception.
 */
class AppHttpClientDoHTest {
    private val recordingDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return listOf(InetAddress.getByName("127.0.0.1"))
        }
    }

    @Test
    fun `shared factory sets DoH dns and disables cookies`() {
        val client = createSharedCookielessDohClient(recordingDns)

        assertSame(recordingDns, client.dns)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun `probe client derived from the injected base keeps the shared DNS resolver`() {
        // JmImageHostHealthManager builds probeClient via baseHttpClient.newBuilder().
        // That path must inherit DNS — otherwise init/network-change probes silently
        // fall back to system DNS even when production DI injects the DoH client.
        val base = createSharedCookielessDohClient(recordingDns)
        val probe = base.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        assertSame(recordingDns, probe.dns)
        assertSame(CookieJar.NO_COOKIES, probe.cookieJar)
    }

    @Test
    fun `app module injects the shared DoH client into image host health manager`() {
        val source = readMainSource("di/AppModule.kt")
        assertTrue(
            "AppModule must pass a DoH client into JmImageHostHealthManager",
            source.contains("createSharedCookielessDohClient(get<DohManager>())"),
        )
        assertTrue(
            "AppModule must name the health-manager baseHttpClient argument explicitly",
            Regex(
                """JmImageHostHealthManager\([\s\S]*?baseHttpClient\s*=\s*createSharedCookielessDohClient"""
            ).containsMatchIn(source),
        )
    }

    @Test
    fun `every OkHttpClient construction site is listed in the AppModule inventory`() {
        val inventory = readMainSource("di/AppModule.kt")
        val constructionSites = findOkHttpClientConstructionSites()
        val notDocumented = constructionSites.filterNot { site ->
            inventory.contains(site.relativePath)
        }
        assertTrue(
            "OkHttpClient construction sites missing from AppModule inventory: $notDocumented\n" +
                "Update the inventory table when adding a new client.",
            notDocumented.isEmpty(),
        )
        // The only allowed system-DNS client is the DoH bootstrap.
        assertTrue(
            "Inventory must mark the bootstrap client as the system-DNS exception",
            inventory.contains("intentional system-DNS exception"),
        )
    }

    private data class ConstructionSite(val relativePath: String)

    private fun findOkHttpClientConstructionSites(): List<ConstructionSite> {
        val root = sourceRoot()
        val sites = mutableListOf<ConstructionSite>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { path ->
                    val text = Files.readString(path)
                    // Constructing OkHttpClient.Builder() or OkHttpClient() — not merely
                    // taking a client as a constructor parameter.
                    val constructs = Regex("""OkHttpClient(\.Builder)?\s*\(\s*\)""")
                        .containsMatchIn(text)
                    if (!constructs) return@forEach
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    sites += ConstructionSite(relative)
                }
        }
        return sites
    }

    private fun readMainSource(relative: String): String =
        Files.readString(sourceRoot().resolve(relative))

    private fun sourceRoot(): Path = sequenceOf(
        Path.of("src/main/java/com/par9uet/jm"),
        Path.of("app/src/main/java/com/par9uet/jm"),
    ).first(Files::exists)
}
