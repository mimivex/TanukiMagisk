package com.topjohnwu.magisk.core.utils

import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Renames a slash-form namespace inside every string of a dex file.
 *
 * The replacement is required to have the same byte length as the needle,
 * so no offset in the format moves: only the renamed bytes, the signature
 * and the checksum are rewritten. The one format invariant that depends on
 * content is the sort order of the string pool (ART binary searches over
 * it); it is verified after the rename and the caller retries with a
 * different replacement when the requested name does not fit the slot.
 *
 * Any structural anomaly yields null (fail closed).
 */
object DexPatcher {

    private const val HEADER_SIZE = 0x70
    private const val ENDIAN_CONSTANT = 0x12345678
    private const val MAX_STRING_IDS = 1_000_000L
    private const val MAX_UTF16_UNITS = 0x40000000L

    fun patch(dex: ByteArray, from: String, to: String): ByteArray? {
        if (from.isEmpty() || from.length != to.length) return null
        if (from.any { it.code > 0x7F } || to.any { it.code > 0x7F }) return null

        val size = dex.size
        if (size < HEADER_SIZE) return null

        // "dex\n" magic and a supported version: 035-039 or 040-041
        if (dex[0] != 0x64.toByte() || dex[1] != 0x65.toByte() ||
            dex[2] != 0x78.toByte() || dex[3] != 0x0A.toByte()) return null
        if (dex[4].toInt() != 0x30 || dex[7].toInt() != 0) return null
        val versionOk = when (dex[5].toInt()) {
            0x33 -> dex[6].toInt() in 0x35..0x39
            0x34 -> dex[6].toInt() in 0x30..0x31
            else -> false
        }
        if (!versionOk) return null

        if (readU4(dex, 40) != ENDIAN_CONSTANT.toLong()) return null
        if (readU4(dex, 36) != HEADER_SIZE.toLong()) return null
        if (readU4(dex, 32) != size.toLong()) return null

        val stringIdsSize = readU4(dex, 56)
        val stringIdsOff = readU4(dex, 60)
        val dataSize = readU4(dex, 104)
        val dataOff = readU4(dex, 108)
        if (stringIdsSize < 1 || stringIdsSize > MAX_STRING_IDS) return null
        if (stringIdsOff < HEADER_SIZE || dataOff < HEADER_SIZE) return null
        if (stringIdsOff + 4 * stringIdsSize > dataOff) return null
        if (dataOff + dataSize > size) return null

        val count = stringIdsSize.toInt()
        val idsOff = stringIdsOff.toInt()
        val needle = from.toByteArray(Charsets.US_ASCII)
        val replacement = to.toByteArray(Charsets.US_ASCII)

        // First pass: validate every string item and locate the occurrences
        // inside the bodies. A 0x00 byte can only be the terminator and the
        // needle is pure ASCII, so a match never spans two items. String
        // data offsets are relative to the start of the file.
        val utf16Sizes = IntArray(count)
        val occurrences = ArrayList<Int>()
        for (i in 0 until count) {
            val item = locateString(dex, idsOff, size, i) ?: return null
            utf16Sizes[i] = item.utf16
            var idx = item.bodyStart
            while (true) {
                idx = indexOf(dex, needle, idx, item.bodyEnd)
                if (idx < 0) break
                occurrences.add(idx)
                idx += needle.size
            }
        }

        val out = dex.copyOf()
        for (idx in occurrences) System.arraycopy(replacement, 0, out, idx, replacement.size)

        // The pool must stay sorted by UTF-16 code units: verify every
        // adjacent pair on the renamed copy and reject the rename if any
        // pair is out of order (the caller retries with another name).
        var prev: String? = null
        for (i in 0 until count) {
            val item = locateString(out, idsOff, size, i) ?: return null
            if (item.utf16 != utf16Sizes[i]) return null
            val str = decodeMutf8(out, item) ?: return null
            if (prev != null && prev.compareTo(str) > 0) return null
            prev = str
        }

        // Signature first, then checksum: the checksum covers the signature
        val signature = MessageDigest.getInstance("SHA-1").run {
            update(out, 32, out.size - 32)
            digest()
        }
        System.arraycopy(signature, 0, out, 12, signature.size)
        val checksum = Adler32().apply { update(out, 12, out.size - 12) }.value.toInt()
        out[8] = checksum.toByte()
        out[9] = (checksum ushr 8).toByte()
        out[10] = (checksum ushr 16).toByte()
        out[11] = (checksum ushr 24).toByte()
        return out
    }

    private class StringItem(val utf16: Int, val bodyStart: Int, val bodyEnd: Int)

    private fun locateString(dex: ByteArray, idsOff: Int, size: Int, id: Int): StringItem? {
        val offset = readU4(dex, idsOff + 4 * id)
        if (offset < HEADER_SIZE || offset >= size) return null
        var pos = offset.toInt()

        // uleb128 utf16_size: at most 5 bytes, never rewritten
        var utf16 = 0L
        var shift = 0
        var consumed = 0
        while (true) {
            if (pos >= size) return null
            consumed++
            if (consumed > 5) return null
            val b = dex[pos++].toInt() and 0xFF
            utf16 = utf16 or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (utf16 > MAX_UTF16_UNITS) return null

        val bodyStart = pos
        while (pos < size && dex[pos] != 0.toByte()) pos++
        if (pos >= size) return null
        return StringItem(utf16.toInt(), bodyStart, pos)
    }

    private fun decodeMutf8(dex: ByteArray, item: StringItem): String? {
        val sb = StringBuilder(item.utf16)
        var i = item.bodyStart
        while (i < item.bodyEnd) {
            val a = dex[i].toInt() and 0xFF
            when {
                a < 0x80 -> {
                    sb.append(a.toChar())
                    i += 1
                }
                a and 0xE0 == 0xC0 -> {
                    if (i + 1 >= item.bodyEnd) return null
                    val b = dex[i + 1].toInt() and 0xFF
                    if (b and 0xC0 != 0x80) return null
                    sb.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                    i += 2
                }
                a and 0xF0 == 0xE0 -> {
                    if (i + 2 >= item.bodyEnd) return null
                    val b = dex[i + 1].toInt() and 0xFF
                    val c = dex[i + 2].toInt() and 0xFF
                    if (b and 0xC0 != 0x80 || c and 0xC0 != 0x80) return null
                    sb.append((((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar())
                    i += 3
                }
                else -> return null
            }
        }
        if (sb.length != item.utf16) return null
        return sb.toString()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        val last = to - needle.size
        var i = from
        while (i <= last) {
            if (hay[i] == needle[0]) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun readU4(dex: ByteArray, off: Int): Long =
        (dex[off].toLong() and 0xFF) or
            ((dex[off + 1].toLong() and 0xFF) shl 8) or
            ((dex[off + 2].toLong() and 0xFF) shl 16) or
            ((dex[off + 3].toLong() and 0xFF) shl 24)
}
