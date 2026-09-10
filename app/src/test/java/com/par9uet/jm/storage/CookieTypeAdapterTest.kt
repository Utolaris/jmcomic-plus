package com.par9uet.jm.storage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieTypeAdapterTest {

    private val adapter = CookieTypeAdapter()
    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapter(Cookie::class.java, adapter)
        .create()

    private fun cookie(
        name: String = "AVS",
        value: String = "token",
        domain: String = "18comic.org",
        path: String = "/",
        expiresAt: Long = System.currentTimeMillis() + 86_400_000L,
        secure: Boolean = true,
        httpOnly: Boolean = true,
        hostOnly: Boolean = true,
        persistent: Boolean = true,
    ): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(path)
        if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (persistent) builder.expiresAt(expiresAt)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }

    @Test
    fun roundTripPreservesStableFields() {
        val original = cookie()
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, Cookie::class.java)
        assertEquals(original.name, restored.name)
        assertEquals(original.value, restored.value)
        assertEquals(original.domain, restored.domain)
        assertEquals(original.path, restored.path)
        assertEquals(original.expiresAt, restored.expiresAt)
        assertEquals(original.secure, restored.secure)
        assertEquals(original.httpOnly, restored.httpOnly)
        assertEquals(original.hostOnly, restored.hostOnly)
        assertEquals(original.persistent, restored.persistent)
    }

    @Test
    fun missingOptionalFlagsFallBackToSafeDefaults() {
        val json = JsonParser.parseString(
            """{"name":"AVS","value":"abc","domain":"18comic.org","path":"/"}"""
        ).asJsonObject
        val restored = gson.fromJson(json, Cookie::class.java)
        assertEquals("AVS", restored.name)
        assertTrue(restored.hostOnly)
        assertTrue(restored.persistent)
        assertFalse(restored.secure)
        assertTrue(restored.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun invalidNameIsRejected() {
        val json = JsonObject().apply {
            addProperty("name", "bad name")
            addProperty("value", "x")
            addProperty("domain", "18comic.org")
            addProperty("path", "/")
        }
        assertTrue(runCatching { gson.fromJson(json, Cookie::class.java) }.isFailure)
    }
}
