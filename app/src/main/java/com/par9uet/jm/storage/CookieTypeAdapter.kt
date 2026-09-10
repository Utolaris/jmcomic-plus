package com.par9uet.jm.storage

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import okhttp3.Cookie

/** Stable wire fields; never reflect into OkHttp's private implementation or bypass Builder. */
class CookieTypeAdapter : TypeAdapter<Cookie>() {
    override fun write(out: JsonWriter, value: Cookie) {
        out.beginObject()
        out.name("name").value(value.name)
        out.name("value").value(value.value)
        out.name("domain").value(value.domain)
        out.name("path").value(value.path)
        out.name("expiresAt").value(value.expiresAt)
        out.name("secure").value(value.secure)
        out.name("httpOnly").value(value.httpOnly)
        out.name("hostOnly").value(value.hostOnly)
        out.name("persistent").value(value.persistent)
        out.endObject()
    }

    override fun read(reader: JsonReader): Cookie {
        try {
            // These names also accept cookies written by the former Gson reflection serializer.
            val json = JsonParser.parseReader(reader).asJsonObject
            val name = json.get("name").asString
            val value = json.get("value").asString
            require(name.isNotEmpty() && name.all { it.code in 33..126 && it !in "()<>@,;:\\\"/[]?={}" })
            require(value.all { it.code in 32..126 && it != ';' })
            fun flag(member: String, default: Boolean): Boolean =
                json.get(member)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

            return Cookie.Builder().apply {
                name(name)
                value(value)
                val domain = json.get("domain").asString
                if (flag("hostOnly", true)) hostOnlyDomain(domain) else domain(domain)
                path(json.get("path").asString)
                if (flag("persistent", true)) {
                    expiresAt(json.get("expiresAt")?.takeUnless { it.isJsonNull }?.asLong
                        ?: (System.currentTimeMillis() + 24L * 60 * 60 * 1000))
                }
                if (flag("secure", false)) secure()
                if (flag("httpOnly", false)) httpOnly()
            }.build()
        } catch (error: Exception) {
            throw JsonParseException("Invalid stored cookie", error)
        }
    }
}
