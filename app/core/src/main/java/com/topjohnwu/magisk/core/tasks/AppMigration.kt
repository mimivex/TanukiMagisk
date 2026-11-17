package com.topjohnwu.magisk.core.tasks

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import com.topjohnwu.magisk.core.AppApkPath
import com.topjohnwu.magisk.core.BuildConfig.APP_PACKAGE_NAME
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.await
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.signing.JarMap
import com.topjohnwu.magisk.core.signing.SignApk
import com.topjohnwu.magisk.core.utils.AXML
import com.topjohnwu.magisk.core.utils.DexPatcher
import com.topjohnwu.magisk.core.utils.Keygen
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.security.SecureRandom

object AppMigration {

    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz"
    private const val ALPHADOTS = "$ALPHA....."
    private const val ANDROID_MANIFEST = "AndroidManifest.xml"
    private const val TEST_PKG_NAME = "com.topjohnwu.magisk.test"

    // The dex class namespace is rewritten with a same-length replacement
    // ("Lcom/topjohnwu/magisk" is 21 bytes), so the generated package is
    // "com." plus exactly 16 characters and no dex offset ever moves.
    private const val PKG_TAIL_LENGTH = 16
    private const val MAX_PKG_ATTEMPTS = 128
    private const val DEX_NAMESPACE = "Lcom/topjohnwu/magisk"
    private const val DEX_NAMESPACE_DOT = "com.topjohnwu.magisk"
    private val DEX_ENTRY = Regex("^classes(\\d+)?\\.dex$")

    // The installer leaves the full manager APK behind; it is what
    // restore has to install when the hidden copy is the only one running.
    private const val MANAGER_APK = "/data/adb/magisk/manager.apk"

    // Some arbitrary limit
    const val MAX_LABEL_LENGTH = 32

    private fun genPackageName(): String {
        val random = SecureRandom()
        while (true) {
            val builder = StringBuilder(PKG_TAIL_LENGTH)
            var prev = 0.toChar()
            for (i in 0 until PKG_TAIL_LENGTH) {
                val next = if (prev == '.' || i == 0 || i == PKG_TAIL_LENGTH - 1) {
                    ALPHA[random.nextInt(ALPHA.length)]
                } else {
                    ALPHADOTS[random.nextInt(ALPHADOTS.length)]
                }
                builder.append(next)
                prev = next
            }
            if (!builder.contains('.')) {
                // Pick a random index and set it as dot
                builder[1 + random.nextInt(PKG_TAIL_LENGTH - 2)] = '.'
            }
            val tail = builder.toString()
            val segments = tail.split('.')
            // Never look like a reserved or vendor package
            if (segments.any { it == "android" || it == "google" }) continue
            if (tail.startsWith("android") || tail.startsWith("google")) continue
            if (tail.contains("topjohnwu")) continue
            return "com.$tail"
        }
    }

    private fun genVersionName(): String {
        val random = SecureRandom()
        return "1.${random.nextInt(100)}.${random.nextInt(100)}"
    }

    private fun patch(
        context: Context,
        apk: File, out: OutputStream,
        pkg: String, label: CharSequence
    ): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, 0) ?: return false
        val info = archive.applicationInfo ?: return false
        val origLabel = info.nonLocalizedLabel.toString()
        val origVersionName = archive.versionName
        try {
            JarMap.open(apk, true).use { jar ->
                val je = jar.getJarEntry(ANDROID_MANIFEST)
                val xml = AXML(jar.getRawData(je))
                val p = xml.patchStrings {
                    when {
                        it.contains(APP_PACKAGE_NAME) -> it.replace(APP_PACKAGE_NAME, pkg)
                        it == origLabel -> label.toString()
                        it == origVersionName -> genVersionName()
                        it.contains(DEX_NAMESPACE_DOT) -> it.replace(DEX_NAMESPACE_DOT, pkg)
                        else -> it
                    }
                }
                if (!p) return false

                // Rename the class namespace in the dex as well: the
                // manifest components and the dex descriptors must agree
                if (!patchDex(jar, pkg)) return false

                // Write apk changes
                jar.getOutputStream(je).use { it.write(xml.bytes) }
                val keys = Keygen()
                SignApk.sign(keys.cert, keys.key, jar, out)
                return true
            }
        } catch (e: Exception) {
            Timber.e(e)
            return false
        }
    }

    private fun patchDex(jar: JarMap, pkg: String): Boolean {
        val renameTo = "L" + pkg.replace('.', '/')
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!DEX_ENTRY.matches(entry.name)) continue
            val dex = DexPatcher.patch(jar.getRawData(entry), DEX_NAMESPACE, renameTo)
                ?: return false
            jar.getOutputStream(entry).use { it.write(dex) }
        }
        return true
    }

    private fun patchTest(apk: File, out: File, pkg: String): Boolean {
        try {
            JarMap.open(apk, true).use { jar ->
                val je = jar.getJarEntry(ANDROID_MANIFEST)
                val xml = AXML(jar.getRawData(je))
                val p = xml.patchStrings {
                    when {
                        it == APP_PACKAGE_NAME -> pkg
                        it == TEST_PKG_NAME -> "$pkg.test"
                        it.contains(DEX_NAMESPACE_DOT) -> it.replace(DEX_NAMESPACE_DOT, pkg)
                        else -> it
                    }
                }
                if (!p) return false

                if (!patchDex(jar, pkg)) return false

                // Write apk changes
                jar.getOutputStream(je).use { it.write(xml.bytes) }
                val keys = Keygen()
                out.outputStream().use { SignApk.sign(keys.cert, keys.key, jar, it) }
                return true
            }
        } catch (e: Exception) {
            Timber.e(e)
            return false
        }
    }

    private fun launchApp(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.putExtra(Const.Key.PREV_CONFIG, Config.toBundle())
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            options.setShareIdentityEnabled(true)
        }
        context.startActivity(intent, options.toBundle())
        if (context is Activity) {
            context.finish()
        }
    }

    private inline fun findWorkingPackage(chosen: String?, patch: (String) -> Boolean): String? {
        // A caller supplied package is deterministic: one attempt is enough
        if (chosen != null)
            return chosen.takeIf(patch)
        repeat(MAX_PKG_ATTEMPTS) {
            val pkg = genPackageName()
            if (patch(pkg)) return pkg
        }
        return null
    }

    suspend fun patchAndHide(context: Context, label: String, pkg: String? = null): Boolean {
        // The rename is length-invariant, so only a 20-char package fits
        val chosen = pkg?.takeIf {
            it.startsWith("com.") && it.length == "com.".length + PKG_TAIL_LENGTH
        }
        // Generate a new random signature and package name if needed
        Config.keyStoreRaw = ""

        // The test APK is only present on development devices
        val testApk = try {
            File(context.packageManager.getApplicationInfo(TEST_PKG_NAME, 0).sourceDir)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val testRepack = File(context.cacheDir, "test.apk")
        val repack = File(context.cacheDir, "patched.apk")

        // Repackage the running app itself: the installer ships no stub,
        // the manifest only needs the package name swapped.
        val apk = File(context.applicationInfo.sourceDir)

        try {
            // Keep the pristine APK, it is the only source a restore can use
            if (!Shell.cmd("cp -f $apk $MANAGER_APK").exec().isSuccess)
                return false

            // Every attempt reopens the pristine APK: a rejected rename
            // only means the random package does not fit the dex slot
            val hidden = findWorkingPackage(chosen) { p ->
                (testApk == null || patchTest(testApk, testRepack, p)) &&
                    repack.outputStream().use { patch(context, apk, it, p, label) }
            } ?: return false

            // Install only after both APKs are fully patched
            if (testApk != null &&
                !Shell.cmd("adb_pm_install $testRepack $hidden.test").exec().isSuccess)
                return false
            if (!Shell.cmd("adb_pm_install $repack $hidden").exec().isSuccess) {
                if (testApk != null)
                    Shell.cmd("pm uninstall $hidden.test").exec()
                return false
            }

            // Install and auto launch app
            Config.suManager = hidden
            Shell.cmd("touch $AppApkPath").exec()
            launchApp(context, hidden)
            return true
        } finally {
            // Keygen rewrites the key while patching: never leave it behind
            Config.keyStoreRaw = ""
            testRepack.delete()
            repack.delete()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun hide(activity: Activity, label: String) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.hide_app_title))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        val success = withContext(Dispatchers.IO) {
            patchAndHide(activity, label)
        }
        if (!success) {
            dialog.dismiss()
            activity.toast(R.string.failure, Toast.LENGTH_LONG)
        }
    }

    suspend fun restoreApp(context: Context): Boolean {
        // An original already installed IS the manager: adopt it
        // instead of reinstalling the stash, which a flash may have
        // wiped. A disabled package falls through.
        val pm = context.packageManager
        if (runCatching { pm.getApplicationInfo(APP_PACKAGE_NAME, 0).enabled }
                .getOrDefault(false)) {
            Config.suManager = ""
            Shell.cmd("touch $AppApkPath").exec()
            launchApp(context, APP_PACKAGE_NAME)
            uninstallSelf(context)
            return true
        }

        val apk = File(MANAGER_APK)
        val cmd = "adb_pm_install $apk $APP_PACKAGE_NAME"
        if (Shell.cmd(cmd).await().isSuccess) {
            Config.suManager = ""
            Shell.cmd("touch $AppApkPath").exec()
            launchApp(context, APP_PACKAGE_NAME)
            uninstallSelf(context)
            return true
        }
        return false
    }

    // The hidden copy removes itself after handing the manager role back.
    // Its process dies in the uninstall, but pm already runs in a root
    // shell as a daemon child and survives (same pattern as the splash).
    private fun uninstallSelf(context: Context) {
        if (context.packageName != APP_PACKAGE_NAME) {
            Shell.cmd("(pm uninstall ${context.packageName})& >/dev/null 2>&1").exec()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun restore(activity: Activity) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.restore_img_msg))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        if (!restoreApp(activity)) {
            activity.toast(R.string.failure, Toast.LENGTH_LONG)
        }
        dialog.dismiss()
    }
}
