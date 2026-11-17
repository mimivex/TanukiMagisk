package com.topjohnwu.magisk.core

import com.topjohnwu.magisk.core.model.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VersionOrderingTest {

    private fun release(tag: String) = Release(
        tag = tag,
        name = tag,
        prerelease = false,
        assets = emptyList(),
        body = "",
        createdTime = Instant.EPOCH
    )

    @Test
    fun tanukiMinor_readsTheRevisionSuffix() {
        assertEquals(1, tanukiMinorOf("31.1-tanuki1"))
        assertEquals(10, tanukiMinorOf("31.1-tanuki10"))
        assertEquals(28, tanukiMinorOf("31.1-6239-tanuki28"))
        assertEquals(0, tanukiMinorOf("31.1"))
        assertEquals(0, tanukiMinorOf(""))
    }

    @Test
    fun versionCode_ignoresTheRevisionSuffix() {
        assertEquals(31100, release("v31.1-tanuki1").versionCode)
        assertEquals(31100, release("v31.1-tanuki2").versionCode)
        assertEquals(31100, release("v31.1-6239-tanuki2").versionCode)
        assertEquals(31100, release("v31.1").versionCode)
        assertEquals(31200, release("v31.2").versionCode)
        assertEquals(31100, release("canary-31100").versionCode)
    }

    @Test
    fun aLaterRevisionOfTheSameBaseIsNewer() {
        assertFalse(isOlderVersion(31100, "31.1-tanuki2", 31100, "31.1-tanuki1"))
        assertTrue(isOlderVersion(31100, "31.1-tanuki1", 31100, "31.1-tanuki2"))
        assertFalse(isOlderVersion(31100, "31.1-tanuki10", 31100, "31.1-tanuki9"))
    }

    @Test
    fun theSameBuildIsNotNewer() {
        assertFalse(isOlderVersion(31100, "31.1-tanuki1", 31100, "31.1-tanuki1"))
        assertFalse(isOlderVersion(31100, "31.1", 31100, "31.1"))
    }

    @Test
    fun theBaseCodeDecidesBeforeTheRevision() {
        assertFalse(isOlderVersion(31200, "31.2", 31100, "31.1-tanuki9"))
        assertTrue(isOlderVersion(31100, "31.1-tanuki9", 31200, "31.2"))
    }

    @Test
    fun aStockTagNeverOutranksAForkRevisionOfTheSameBase() {
        // Release without the suffix, daemon on a fork revision
        assertTrue(isOlderVersion(31100, "31.1", 31100, "31.1-tanuki1"))
        // Release on a fork revision, daemon on the stock build
        assertFalse(isOlderVersion(31100, "31.1-tanuki1", 31100, "31.1"))
    }

    @Test
    fun anInactiveDaemonNeverCountsAsNewer() {
        assertFalse(isOlderVersion(31100, "31.1-tanuki1", -1, ""))
        assertFalse(isOlderVersion(-1, "", -1, ""))
    }
}
