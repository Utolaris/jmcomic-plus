package com.par9uet.jm.network

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DohPacketParserTest {
    @Test
    fun `parses compressed IPv4 answer and keeps ttl`() {
        val response = dnsResponse(
            type = DohPacketParser.TYPE_A,
            address = byteArrayOf(1, 2, 3, 4),
            ttl = 120,
        )

        val records = DohPacketParser.parse(response, DohPacketParser.TYPE_A)

        assertEquals(1, records.size)
        assertEquals("/1.2.3.4", records.single().address.toString())
        assertEquals(120L, records.single().ttlSeconds)
    }

    @Test
    fun `parses IPv6 answer when requested`() {
        val address = InetAddress.getByName("2001:db8::1").address
        val records = DohPacketParser.parse(
            dnsResponse(DohPacketParser.TYPE_AAAA, address, ttl = 30),
            DohPacketParser.TYPE_AAAA,
        )

        assertEquals("2001:db8:0:0:0:0:0:1", records.single().address.hostAddress)
        assertEquals(30L, records.single().ttlSeconds)
    }

    @Test
    fun `ignores answers of another type`() {
        val records = DohPacketParser.parse(
            dnsResponse(DohPacketParser.TYPE_AAAA, InetAddress.getByName("2001:db8::1").address, 30),
            DohPacketParser.TYPE_A,
        )

        assertEquals(emptyList<DohDnsRecord>(), records)
    }

    @Test
    fun `rejects truncated and error responses`() {
        assertThrows(UnknownHostException::class.java) {
            DohPacketParser.parse(byteArrayOf(0, 1, 2), DohPacketParser.TYPE_A)
        }

        val error = dnsResponse(
            type = DohPacketParser.TYPE_A,
            address = byteArrayOf(1, 2, 3, 4),
            ttl = 10,
            flags = 0x8183,
        )
        assertThrows(UnknownHostException::class.java) {
            DohPacketParser.parse(error, DohPacketParser.TYPE_A)
        }
    }

    @Test
    fun `rejects malformed compressed name`() {
        val response = dnsResponse(DohPacketParser.TYPE_A, byteArrayOf(1, 2, 3, 4), 30)
            .copyOf(12 + question("example.com", DohPacketParser.TYPE_A).size + 1)
        assertThrows(UnknownHostException::class.java) {
            DohPacketParser.parse(response, DohPacketParser.TYPE_A)
        }
    }

    private fun dnsResponse(
        type: Int,
        address: ByteArray,
        ttl: Int,
        flags: Int = 0x8180,
    ): ByteArray = ByteArrayOutputStream().apply {
        writeU16(0x1234)
        writeU16(flags)
        writeU16(1)
        writeU16(1)
        writeU16(0)
        writeU16(0)
        write(question("example.com", type))
        write(0xc0)
        write(0x0c)
        writeU16(type)
        writeU16(DohPacketParser.CLASS_IN)
        writeU32(ttl.toLong())
        writeU16(address.size)
        write(address)
    }.toByteArray()

    private fun question(name: String, type: Int): ByteArray = ByteArrayOutputStream().apply {
        name.split('.').forEach { label ->
            write(label.length)
            write(label.toByteArray())
        }
        write(0)
        writeU16(type)
        writeU16(DohPacketParser.CLASS_IN)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value shr 8 and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write((value shr 24).toInt() and 0xff)
        write((value shr 16).toInt() and 0xff)
        write((value shr 8).toInt() and 0xff)
        write(value.toInt() and 0xff)
    }
}
