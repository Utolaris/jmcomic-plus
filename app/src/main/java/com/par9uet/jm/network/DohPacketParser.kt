package com.par9uet.jm.network

import java.net.InetAddress
import java.net.UnknownHostException

internal data class DohDnsRecord(
    val address: InetAddress,
    val ttlSeconds: Long,
)

/** Small, strict parser for the DNS wire responses returned by a DoH endpoint. */
internal object DohPacketParser {
    const val CLASS_IN = 1
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val DNS_HEADER_SIZE = 12

    fun parse(bytes: ByteArray, requestedType: Int): List<DohDnsRecord> {
        if (bytes.size < DNS_HEADER_SIZE) throw UnknownHostException("DoH 响应不完整")
        val flags = bytes.u16(2)
        if ((flags and 0x000f) != 0) {
            throw UnknownHostException("DoH 解析失败，rcode=${flags and 0x000f}")
        }
        val questionCount = bytes.u16(4)
        val answerCount = bytes.u16(6)
        var offset = DNS_HEADER_SIZE
        repeat(questionCount) {
            offset = bytes.skipName(offset)
            offset += 4
            if (offset > bytes.size) throw UnknownHostException("DoH 问题段无效")
        }
        val records = mutableListOf<DohDnsRecord>()
        repeat(answerCount) {
            offset = bytes.skipName(offset)
            if (offset + 10 > bytes.size) throw UnknownHostException("DoH 回答段无效")
            val type = bytes.u16(offset)
            val recordClass = bytes.u16(offset + 2)
            val ttl = bytes.u32(offset + 4)
            val length = bytes.u16(offset + 8)
            offset += 10
            if (offset + length > bytes.size) throw UnknownHostException("DoH 地址数据无效")
            if (recordClass == CLASS_IN && type == requestedType &&
                ((type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16))
            ) {
                records += DohDnsRecord(
                    InetAddress.getByAddress(bytes.copyOfRange(offset, offset + length)),
                    ttl,
                )
            }
            offset += length
        }
        return records
    }

    private fun ByteArray.u16(offset: Int): Int {
        if (offset < 0 || offset + 2 > size) throw UnknownHostException("DoH 响应不完整")
        return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
    }

    private fun ByteArray.u32(offset: Int): Long {
        if (offset < 0 || offset + 4 > size) throw UnknownHostException("DoH 响应不完整")
        return ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)
    }

    private fun ByteArray.skipName(start: Int): Int {
        var offset = start
        while (offset < size) {
            val sizeByte = this[offset].toInt() and 0xff
            when {
                sizeByte == 0 -> return offset + 1
                sizeByte and 0xc0 == 0xc0 -> {
                    if (offset + 1 >= size) throw UnknownHostException("DoH 域名压缩格式无效")
                    return offset + 2
                }
                sizeByte and 0xc0 != 0 || offset + sizeByte >= size ->
                    throw UnknownHostException("DoH 域名压缩格式无效")
                else -> offset += sizeByte + 1
            }
        }
        throw UnknownHostException("DoH 域名超出响应范围")
    }
}
