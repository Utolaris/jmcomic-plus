package com.par9uet.jm.repository.impl

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class EmbeddedSessionCookiesTest {
    private val trusted = listOf("api-a.example", "api-b.example")
    private val avs = Cookie.Builder().name("AVS").value("session").hostOnlyDomain(trusted[0]).secure().build()

    @Test fun restoredAvsSurvivesResponsesWithoutSetCookieAndRotatesOnlyToTrustedHttps() {
        val restored = mergeEmbeddedCookies(listOf(avs), emptyList())
        assertEquals(listOf(avs), restored)
        assertEquals(listOf(avs), embeddedCookiesForRequest(restored, "https://api-b.example/history".toHttpUrl(), trusted))
        for (url in listOf("https://evil.example/history", "https://api-b.example.evil.test/", "http://api-b.example/")) {
            assertTrue(embeddedCookiesForRequest(restored, url.toHttpUrl(), trusted).isEmpty())
        }
    }

    @Test fun expiryPathAndOrdinaryCookieDomainsAreHonored() {
        val ordinary = Cookie.Builder().name("other").value("secret").domain(trusted[0]).build()
        val scoped = Cookie.Builder().name("AVS").value("scoped").domain(trusted[0]).path("/private").build()
        val expired = Cookie.Builder().name("AVS").value("expired").domain(trusted[0]).expiresAt(1).build()
        for (cookie in listOf(ordinary, scoped, expired)) {
            assertTrue(embeddedCookiesForRequest(listOf(cookie), "https://api-b.example/public".toHttpUrl(), trusted).isEmpty())
        }
    }

    @Test fun explicitDeletionAndFlagUpdatesPersist() {
        val deletion = Cookie.Builder().name("AVS").value("").hostOnlyDomain(trusted[0]).expiresAt(1).build()
        assertTrue(mergeEmbeddedCookies(listOf(avs), listOf(deletion)).isEmpty())
        val updated = Cookie.Builder().name("AVS").value("session").hostOnlyDomain(trusted[0]).secure().httpOnly().build()
        assertEquals(listOf(updated), mergeEmbeddedCookies(listOf(avs), listOf(updated)))
        assertNotEquals(avs, updated)
    }
}
