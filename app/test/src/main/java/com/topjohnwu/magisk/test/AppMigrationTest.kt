package com.topjohnwu.magisk.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.annotation.Keep
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives a full hide/restore cycle. This class runs in the test APK
 * process (self-target instrumentation) so it survives the manager
 * being uninstalled; the [AppTestRunner] instrumentation targeting the
 * manager runs [Environment] inside the manager process.
 */
@Keep
@RunWith(AndroidJUnit4::class)
class AppMigrationTest {

    companion object {
        // This file ships in the test APK, which cannot see :core
        private const val MANAGER_PKG = "io.github.mimivex.tanuki"
        private const val TEST_PKG = "com.topjohnwu.magisk.test"
        private const val HANDOFF_FILE = "/data/local/tmp/tanuki_test_pkg"
        private const val RECEIVER_TIMEOUT = 20L
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.context
    private val uiAutomation get() = instrumentation.uiAutomation
    private val registeredReceivers = mutableListOf<BroadcastReceiver>()

    class PackageRemoveMonitor(
        context: Context,
        private val packageName: String
    ) : BroadcastReceiver() {

        val latch = CountDownLatch(1)

        init {
            val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED)
            filter.addDataScheme("package")
            context.registerReceiver(this, filter)
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_PACKAGE_REMOVED)
                return
            val data = intent.data ?: return
            val pkg = data.schemeSpecificPart
            if (pkg == packageName) latch.countDown()
        }
    }

    @After
    fun tearDown() {
        registeredReceivers.forEach(context::unregisterReceiver)
    }

    private fun shell(cmd: String): String {
        val pfd = uiAutomation.executeShellCommand(cmd)
        return AutoCloseInputStream(pfd).reader().use { it.readText() }
    }

    // The manager process dies with the uninstall, so the phase is
    // observed through the package broadcasts, never through the output
    private fun instrument(pkg: String, clazz: String): String =
        shell("am instrument -w --user 0 -e class $clazz $pkg/.AppTestRunner")

    @Test
    fun testHideAndRestore() {
        // Hide phase: runs inside the manager process
        val removeMonitor = PackageRemoveMonitor(context, MANAGER_PKG)
        registeredReceivers.add(removeMonitor)
        val hideOut = instrument(TEST_PKG, ".Environment#setupAppHide")
        assertTrue(
            "$MANAGER_PKG uninstallation failed, inst out: $hideOut",
            removeMonitor.latch.await(RECEIVER_TIMEOUT, TimeUnit.SECONDS)
        )

        // The process that generated the package is gone: it hands the
        // name over through a file readable from this process
        val hidden = shell("cat $HANDOFF_FILE").trim()
        assertTrue(
            "Invalid hidden package '$hidden', inst out: $hideOut",
            hidden.length == 20 && hidden.startsWith("com.") &&
                Regex("[a-z.]+").matches(hidden.substring(4)) &&
                !hidden.contains("..") && !hidden.endsWith(".")
        )

        try {
            // Restore phase: the renamed test APK targets the hidden copy
            val restoreMonitor = PackageRemoveMonitor(context, hidden)
            registeredReceivers.add(restoreMonitor)
            val restoreOut = instrument("$hidden.test", ".Environment#setupAppRestore")
            assertTrue(
                "$hidden uninstallation failed, inst out: $restoreOut",
                restoreMonitor.latch.await(RECEIVER_TIMEOUT, TimeUnit.SECONDS)
            )

            val packages = shell("pm list packages")
                .lineSequence().map { it.trim() }.toList()
            assertTrue("manager not restored, inst out: $restoreOut",
                "package:$MANAGER_PKG" in packages)
            assertTrue("hidden copy left installed, inst out: $restoreOut",
                "package:$hidden" !in packages)
        } finally {
            // Leave no renamed test APK and no handoff file behind
            shell("rm -f $HANDOFF_FILE")
            shell("pm uninstall $hidden.test")
        }
    }
}
