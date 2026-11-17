package com.topjohnwu.magisk.core.utils

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Builds a minimal but structurally valid dex containing only a string
 * pool (header, string ids, string data, map list), plus small readers
 * the tests use to inspect results. Test code only: no shipping path
 * includes this file.
 */
object DexBuilder {

    const val HEADER_SIZE = 0x70

    fun build(strings: List<String>, duplicateLast: Boolean = false): ByteArray {
        val ids = strings.size + if (duplicateLast) 1 else 0
        require(strings.isNotEmpty())

        val idsOff = HEADER_SIZE
        val dataOff = idsOff + ids * 4

        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((i, str) in strings.withIndex()) {
            // string_data_off is relative to the start of the file
            offsets[i] = dataOff + data.size()
            writeUleb128(data, str.length)
            data.write(mutf8(str))
            data.write(0)
        }
        while ((dataOff + data.size()) % 4 != 0) data.write(0)

        val mapOff = dataOff + data.size()
        writeU4(data, 4) // map list size
        writeMapItem(data, 0x0000, 1, 0)
        writeMapItem(data, 0x0001, ids, idsOff)
        writeMapItem(data, 0x2002, strings.size, dataOff)
        writeMapItem(data, 0x1000, 1, mapOff)

        val dataBytes = data.toByteArray()
        val dex = ByteArray(dataOff + dataBytes.size)
        byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)
            .copyInto(dex, 0) // dex\n035
        writeU4(dex, 32, dex.size.toLong())
        writeU4(dex, 36, HEADER_SIZE.toLong())
        writeU4(dex, 40, 0x12345678) // endian tag
        writeU4(dex, 52, mapOff.toLong())
        writeU4(dex, 56, ids.toLong())
        writeU4(dex, 60, idsOff.toLong())
        writeU4(dex, 104, dataBytes.size.toLong())
        writeU4(dex, 108, dataOff.toLong())
        for (i in 0 until ids) {
            val offset = offsets[if (i < strings.size) i else strings.size - 1]
            writeU4(dex, idsOff + 4 * i, offset.toLong())
        }
        dataBytes.copyInto(dex, dataOff)

        val signature = MessageDigest.getInstance("SHA-1").run {
            update(dex, 32, dex.size - 32)
            digest()
        }
        System.arraycopy(signature, 0, dex, 12, signature.size)
        writeU4(dex, 8, checksum(dex))
        return dex
    }

    fun decodedStrings(dex: ByteArray): List<String> {
        val idsOff = readU4(dex, 60).toInt()
        val size = readU4(dex, 56).toInt()
        return (0 until size).map { i ->
            var pos = readU4(dex, idsOff + i * 4).toInt()
            var utf16 = 0
            var shift = 0
            while (true) {
                val b = dex[pos++].toInt() and 0xFF
                utf16 = utf16 or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val sb = StringBuilder(utf16)
            while (dex[pos] != 0.toByte()) {
                val a = dex[pos].toInt() and 0xFF
                when {
                    a < 0x80 -> {
                        sb.append(a.toChar())
                        pos += 1
                    }
                    a and 0xE0 == 0xC0 -> {
                        sb.append((((a and 0x1F) shl 6) or (dex[pos + 1].toInt() and 0x3F)).toChar())
                        pos += 2
                    }
                    else -> {
                        sb.append(
                            (((a and 0x0F) shl 12) or
                                ((dex[pos + 1].toInt() and 0x3F) shl 6) or
                                (dex[pos + 2].toInt() and 0x3F)).toChar()
                        )
                        pos += 3
                    }
                }
            }
            sb.toString()
        }
    }

    fun stringIdsBytes(dex: ByteArray): ByteArray {
        val off = readU4(dex, 60).toInt()
        val size = readU4(dex, 56).toInt()
        return dex.copyOfRange(off, off + size * 4)
    }

    fun signatureValid(dex: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-1").run {
            update(dex, 32, dex.size - 32)
            digest()
        }
        return digest.contentEquals(dex.copyOfRange(12, 32))
    }

    fun checksumValid(dex: ByteArray): Boolean = readU4(dex, 8) == checksum(dex)

    private fun checksum(dex: ByteArray): Long =
        Adler32().apply { update(dex, 12, dex.size - 12) }.value

    fun readU4(dex: ByteArray, off: Int): Long =
        (dex[off].toLong() and 0xFF) or
            ((dex[off + 1].toLong() and 0xFF) shl 8) or
            ((dex[off + 2].toLong() and 0xFF) shl 16) or
            ((dex[off + 3].toLong() and 0xFF) shl 24)

    fun writeU4(dex: ByteArray, off: Int, value: Long) {
        dex[off] = value.toByte()
        dex[off + 1] = (value ushr 8).toByte()
        dex[off + 2] = (value ushr 16).toByte()
        dex[off + 3] = (value ushr 24).toByte()
    }

    private fun writeU4(out: ByteArrayOutputStream, value: Long) {
        out.write(value.toInt() and 0xFF)
        out.write((value ushr 8).toInt() and 0xFF)
        out.write((value ushr 16).toInt() and 0xFF)
        out.write((value ushr 24).toInt() and 0xFF)
    }

    private fun writeMapItem(out: ByteArrayOutputStream, type: Int, size: Int, offset: Int) {
        writeU4(out, type.toLong())
        writeU4(out, size.toLong())
        writeU4(out, offset.toLong())
    }

    private fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

    private fun mutf8(str: String): ByteArray {
        val out = ByteArrayOutputStream(str.length)
        for (c in str) {
            val v = c.code
            when {
                v == 0 -> {
                    out.write(0xC0)
                    out.write(0x80)
                }
                v < 0x80 -> out.write(v)
                v < 0x800 -> {
                    out.write(0xC0 or (v ushr 6))
                    out.write(0x80 or (v and 0x3F))
                }
                else -> {
                    out.write(0xE0 or (v ushr 12))
                    out.write(0x80 or ((v ushr 6) and 0x3F))
                    out.write(0x80 or (v and 0x3F))
                }
            }
        }
        return out.toByteArray()
    }
}
