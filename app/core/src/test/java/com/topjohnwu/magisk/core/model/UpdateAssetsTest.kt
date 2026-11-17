package com.topjohnwu.magisk.core.model

import com.topjohnwu.magisk.core.model.UpdateAssets.isUpdateAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAssetsTest {

    private val canonical = listOf(
        "app-release.apk",
        "app-legacy-release.apk",
        "app-debug.apk",
        "app-legacy-debug.apk",
    )

    private fun selected(legacy: Boolean, debug: Boolean) =
        canonical.filter { isUpdateAsset(it, legacy, debug) }

    @Test
    fun eachBuildPicksExactlyOneOfTheFourAssets() {
        assertEquals(listOf("app-release.apk"), selected(legacy = false, debug = false))
        assertEquals(listOf("app-legacy-release.apk"), selected(legacy = true, debug = false))
        assertEquals(listOf("app-debug.apk"), selected(legacy = false, debug = true))
        assertEquals(listOf("app-legacy-debug.apk"), selected(legacy = true, debug = true))
    }

    @Test
    fun legacyNeverReceivesAnAssetWithoutLegacyInItsName() {
        for (debug in listOf(false, true)) {
            assertFalse(isUpdateAsset("app-release.apk", legacy = true, debug = debug))
            assertFalse(isUpdateAsset("app-debug.apk", legacy = true, debug = debug))
        }
    }

    @Test
    fun composeNeverReceivesALegacyAsset() {
        for (debug in listOf(false, true)) {
            assertFalse(isUpdateAsset("app-legacy-release.apk", legacy = false, debug = debug))
            assertFalse(isUpdateAsset("app-legacy-debug.apk", legacy = false, debug = debug))
        }
    }

    @Test
    fun aReleaseBuildNeverReceivesADebugAsset() {
        for (legacy in listOf(false, true)) {
            assertFalse(isUpdateAsset("app-debug.apk", legacy, debug = false))
            assertFalse(isUpdateAsset("app-legacy-debug.apk", legacy, debug = false))
        }
    }

    @Test
    fun aDebugBuildNeverReceivesAReleaseAsset() {
        for (legacy in listOf(false, true)) {
            assertFalse(isUpdateAsset("app-release.apk", legacy, debug = true))
            assertFalse(isUpdateAsset("app-legacy-release.apk", legacy, debug = true))
        }
    }

    @Test
    fun alternativeNamesKeepWorking() {
        assertTrue(isUpdateAsset("Magisk-v31.1-tanuki1.apk", legacy = false, debug = false))
        assertTrue(isUpdateAsset("Magisk-v31.1-tanuki1-legacy.apk", legacy = true, debug = false))
        assertTrue(isUpdateAsset("Magisk-v31.1-tanuki1-debug.apk", legacy = false, debug = true))
        assertTrue(
            isUpdateAsset("Magisk-v31.1-tanuki1-legacy-debug.apk", legacy = true, debug = true)
        )
    }

    @Test
    fun onlyApkFilesAreConsidered() {
        for (legacy in listOf(false, true)) {
            for (debug in listOf(false, true)) {
                assertFalse(isUpdateAsset("Magisk-v31.1-tanuki1.zip", legacy, debug))
                assertFalse(isUpdateAsset("app-release.apk.sha256", legacy, debug))
                assertFalse(isUpdateAsset("app-debug.txt", legacy, debug))
                assertFalse(isUpdateAsset("", legacy, debug))
            }
        }
    }
}
