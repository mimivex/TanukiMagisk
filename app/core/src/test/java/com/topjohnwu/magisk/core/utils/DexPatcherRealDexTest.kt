package com.topjohnwu.magisk.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipFile

/**
 * Gate against a real manager APK (skipped unless -Ptanuki.test.apk=<apk>
 * is passed to the build): patches every dex entry with random names and
 * measures how often the slot constraint accepts them. The hide retries
 * up to 128 times, so a per-package success rate of at least ~10% makes
 * exhaustion statistically unreachable (0.9^128 is about 1e-6).
 */
class DexPatcherRealDexTest {

    companion object {
        private const val NAMESPACE = "Lcom/topjohnwu/magisk"
        private const val TAIL_LENGTH = 16
        private const val ATTEMPTS = 1000
        private const val MIN_RATE = 0.10
        private const val ALPHA = "abcdefghijklmnopqrstuvwxyz"
        private const val ALPHADOTS = "$ALPHA....."
    }

    private fun genPackage(random: SecureRandom): String {
        while (true) {
            val sb = StringBuilder(TAIL_LENGTH)
            var prev = 0.toChar()
            for (i in 0 until TAIL_LENGTH) {
                val next = if (prev == '.' || i == 0 || i == TAIL_LENGTH - 1)
                    ALPHA[random.nextInt(ALPHA.length)]
                else
                    ALPHADOTS[random.nextInt(ALPHADOTS.length)]
                sb.append(next)
                prev = next
            }
            if (!sb.contains('.'))
                sb[1 + random.nextInt(TAIL_LENGTH - 2)] = '.'
            val tail = sb.toString()
            val segments = tail.split('.')
            if (segments.any { it.isEmpty() }) continue
            if (segments.any { it == "android" || it == "google" }) continue
            if (tail.contains("topjohnwu")) continue
            return "com.$tail"
        }
    }

    @Test
    fun renameRealDex() {
        val path = System.getProperty("tanuki.test.apk")
        assumeTrue("Real dex gate disabled, pass -Ptanuki.test.apk=<apk>", path != null)
        val apk = File(path!!)
        assumeTrue("APK not found: $apk", apk.isFile)

        val dexes = mutableListOf<Pair<String, ByteArray>>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (Regex("^classes(\\d+)?\\.dex$").matches(entry.name)) {
                    dexes.add(entry.name to zip.getInputStream(entry).readBytes())
                }
            }
        }
        assertTrue("No dex entries in $apk", dexes.isNotEmpty())

        val random = SecureRandom()
        for ((name, dex) in dexes) {
            var success = 0
            var patched: ByteArray? = null
            var pkg = ""
            val start = System.currentTimeMillis()
            repeat(ATTEMPTS) {
                val candidate = genPackage(random)
                val out = DexPatcher.patch(dex, NAMESPACE, "L" + candidate.replace('.', '/'))
                if (out != null) {
                    success++
                    if (patched == null) {
                        patched = out
                        pkg = candidate
                    }
                }
            }
            val rate = success.toDouble() / ATTEMPTS
            val elapsed = System.currentTimeMillis() - start
            println("$name: ${dex.size} bytes, success rate $rate, ${elapsed}ms")

            assertTrue("$name: success rate $rate below $MIN_RATE", rate >= MIN_RATE)

            val out = patched!!
            val strings = DexBuilder.decodedStrings(out)
            assertTrue("$name: namespace left in the pool", strings.none { it.contains(NAMESPACE) })
            if (DexBuilder.decodedStrings(dex).any { it.contains(NAMESPACE) }) {
                assertTrue("$name: rename not applied", strings.any { it.contains(pkg.replace('.', '/')) })
            }
            assertEquals(
                "$name: string ids moved",
                DexBuilder.stringIdsBytes(dex).toList(),
                DexBuilder.stringIdsBytes(out).toList()
            )
            assertTrue("$name: bad signature", DexBuilder.signatureValid(out))
            assertTrue("$name: bad checksum", DexBuilder.checksumValid(out))
        }
    }
}
