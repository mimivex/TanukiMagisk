package com.topjohnwu.magisk.core.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexPatcherTest {

    companion object {
        private const val NEEDLE = "Lcom/topjohnwu/magisk"
        private const val REPL = "Lcom/sr/abcd/efghijkl"

        // Sorted by UTF-16 code units; the magisk block sits between
        // squareup and topjohnwu/superuser, as in a real d8 pool.
        private val POOL = listOf(
            "Lcom/squareup/moshi/A;",
            "Lcom/topjohnwu/magisk/A;",
            "Lcom/topjohnwu/magisk/A;Lcom/topjohnwu/magisk/B;",
            "Lcom/topjohnwu/magisk/B;",
            "Lcom/topjohnwu/magisk/É;",
            "Lcom/topjohnwu/superuser/C;",
            "VL",
            "[Lcom/topjohnwu/magisk/A;",
            "com.topjohnwu.magisk",
            "settings_hide"
        )

        private val RENAMED = POOL.map { it.replace(NEEDLE, REPL) }
    }

    @Test
    fun happyPath() {
        val dex = DexBuilder.build(POOL)
        val out = DexPatcher.patch(dex, NEEDLE, REPL)
        assertNotNull("patch rejected a valid rename", out)
        val patched = out!!

        // Byte-for-byte equal to an independently built renamed pool:
        // header, offsets, map and dedup are all preserved unchanged.
        assertArrayEquals(DexBuilder.build(RENAMED), patched)

        val strings = DexBuilder.decodedStrings(patched)
        assertTrue(strings.none { it.contains(NEEDLE) })
        assertTrue("Lcom/topjohnwu/superuser/C;" in strings)
        assertTrue("com.topjohnwu.magisk" in strings)
        assertTrue("[Lcom/sr/abcd/efghijkl/A;" in strings)
        assertTrue("Lcom/sr/abcd/efghijkl/A;Lcom/sr/abcd/efghijkl/B;" in strings)
        assertTrue("Lcom/sr/abcd/efghijkl/É;" in strings)

        assertEquals(
            DexBuilder.stringIdsBytes(dex).toList(),
            DexBuilder.stringIdsBytes(patched).toList()
        )

        assertTrue(DexBuilder.signatureValid(patched))
        assertTrue(DexBuilder.checksumValid(patched))
        assertArrayEquals(dex.copyOfRange(0, 8), patched.copyOfRange(0, 8))
        assertArrayEquals(dex.copyOfRange(32, 0x70), patched.copyOfRange(32, 0x70))
    }

    @Test
    fun dedupOffsetsPreserved() {
        val dex = DexBuilder.build(POOL, duplicateLast = true)
        val out = DexPatcher.patch(dex, NEEDLE, REPL)
        assertNotNull("patch rejected a valid rename", out)
        val patched = out!!

        assertArrayEquals(DexBuilder.build(RENAMED, duplicateLast = true), patched)

        val idsOff = DexBuilder.readU4(patched, 60).toInt()
        val size = DexBuilder.readU4(patched, 56).toInt()
        assertEquals(
            DexBuilder.readU4(patched, idsOff + 4 * (size - 2)),
            DexBuilder.readU4(patched, idsOff + 4 * (size - 1))
        )
    }

    @Test
    fun slotViolationRejected() {
        val dex = DexBuilder.build(POOL)
        // Lands after "Lcom/topjohnwu/superuser/C;" and before
        // "Lcom/squareup/moshi/A;" respectively
        assertNull(DexPatcher.patch(dex, NEEDLE, "Lcom/zz/abcd/efghijkl"))
        assertNull(DexPatcher.patch(dex, NEEDLE, "Lcom/ab/abcd/efghijkl"))

        // A retry with a name that fits the slot succeeds
        var patched: ByteArray? = null
        for (repl in listOf("Lcom/zz/abcd/efghijkl", "Lcom/ab/abcd/efghijkl", REPL)) {
            patched = DexPatcher.patch(dex, NEEDLE, repl)
            if (patched != null) break
        }
        assertNotNull("every attempt was rejected", patched)
    }

    @Test
    fun structuralAnomaliesRejected() {
        val good = DexBuilder.build(POOL)
        val idsOff = DexBuilder.readU4(good, 60).toInt()

        fun corrupted(edit: (ByteArray) -> Unit): ByteArray =
            good.copyOf().also(edit)

        // Wrong magic
        assertNull(DexPatcher.patch(corrupted { it[0] = 'X'.code.toByte() }, NEEDLE, REPL))
        // Version 034 and 042
        assertNull(DexPatcher.patch(corrupted { it[6] = '4'.code.toByte() }, NEEDLE, REPL))
        assertNull(DexPatcher.patch(corrupted {
            it[5] = '4'.code.toByte()
            it[6] = '2'.code.toByte()
        }, NEEDLE, REPL))
        // Truncated file and incoherent file_size
        assertNull(DexPatcher.patch(good.copyOf(good.size - 4), NEEDLE, REPL))
        assertNull(DexPatcher.patch(corrupted {
            DexBuilder.writeU4(it, 32, good.size + 4L)
        }, NEEDLE, REPL))
        // String offset inside the header and past the end of the file
        assertNull(DexPatcher.patch(corrupted {
            DexBuilder.writeU4(it, idsOff, 0)
        }, NEEDLE, REPL))
        assertNull(DexPatcher.patch(corrupted {
            DexBuilder.writeU4(it, idsOff, good.size.toLong())
        }, NEEDLE, REPL))
        // utf16_size above the cap (uleb128 FF FF FF FF 7F)
        assertNull(DexPatcher.patch(corrupted { dex ->
            val p = DexBuilder.readU4(dex, idsOff).toInt()
            for (i in 0 until 4) dex[p + i] = 0xFF.toByte()
            dex[p + 4] = 0x7F
        }, NEEDLE, REPL))
        // Six-byte uleb128
        assertNull(DexPatcher.patch(corrupted { dex ->
            val p = DexBuilder.readU4(dex, idsOff).toInt()
            for (i in 0 until 5) dex[p + i] = 0xFF.toByte()
            dex[p + 5] = 0x01
        }, NEEDLE, REPL))
        // Empty string pool
        assertNull(DexPatcher.patch(corrupted { DexBuilder.writeU4(it, 56, 0) }, NEEDLE, REPL))
        // Pool unsorted in the input
        val unsorted = DexBuilder.build(listOf("zzz", "aaa"))
        assertNull(DexPatcher.patch(unsorted, "Lfoo/bar", "Lbaz/qux"))
    }

    @Test
    fun noOccurrenceIsUntouched() {
        val dex = DexBuilder.build(POOL)
        val out = DexPatcher.patch(dex, "Lfoo/bar", "Lbaz/qux")
        assertNotNull("patch rejected a valid dex", out)
        assertArrayEquals(dex, out!!)
        assertEquals(DexBuilder.decodedStrings(dex), DexBuilder.decodedStrings(out))
    }

    @Test
    fun invalidArgumentsRejected() {
        val dex = DexBuilder.build(POOL)
        // Different lengths, non-ASCII source or replacement, empty source
        assertNull(DexPatcher.patch(dex, NEEDLE, "Lcom/short"))
        assertNull(DexPatcher.patch(dex, "Lcom/töpjohnwu/magisk", REPL))
        assertNull(DexPatcher.patch(dex, NEEDLE, "Lcom/sr/abcd/efghijkö"))
        assertNull(DexPatcher.patch(dex, "", ""))
    }
}
