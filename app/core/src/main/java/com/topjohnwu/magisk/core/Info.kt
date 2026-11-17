package com.topjohnwu.magisk.core

import android.app.KeyguardManager
import android.os.Build
import androidx.lifecycle.MutableLiveData
import com.topjohnwu.magisk.core.ktx.getProperty
import com.topjohnwu.magisk.core.model.UpdateInfo
import com.topjohnwu.magisk.core.repository.NetworkService
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import com.topjohnwu.superuser.ShellUtils.fastCmdResult
import java.io.File
import kotlinx.coroutines.Runnable
import timber.log.Timber

// Revision suffix in the version string ("31.1-6239-tanuki28" or
// "31.1-tanuki28"). A version string without a numeric tanuki suffix
// parses to 0.
private val TANUKI_MINOR = Regex("-tanuki(\\d+)")

fun tanukiMinorOf(version: String): Int =
    TANUKI_MINOR.find(version)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

// Ordering for the update checks: the base version code decides first, then
// the revision suffix, so "31.1-tanuki2" is newer than "31.1-tanuki1" although
// both report 31100. Without the suffix the revision is 0, so a stock tag of
// the same base never outranks a revision of the fork.
fun isOlderVersion(code: Int, version: String, thanCode: Int, thanVersion: String): Boolean {
    if (code != thanCode) return code < thanCode
    return tanukiMinorOf(version) < tanukiMinorOf(thanVersion)
}

object Info {

    private val EMPTY_UPDATE = UpdateInfo()
    var update = EMPTY_UPDATE
        private set

    suspend fun fetchUpdate(svc: NetworkService): UpdateInfo? {
        return if (update === EMPTY_UPDATE) {
            svc.fetchUpdate()?.apply { update = this }
        } else update
    }

    fun resetUpdate() {
        update = EMPTY_UPDATE
    }

    var isRooted = false
    var noDataExec = false
    var patchBootVbmeta = false

    // SuList state of the running daemon; the stored setting applies at boot.
    var sulist = false

    @JvmStatic var env = Env()
        private set
    @JvmStatic var isSAR = false
        private set
    var legacySAR = false
        private set
    var isAB = false
        private set
    var slot = ""
        private set
    var isVendorBoot = false
        private set

    // Zygisk comes from an external provider, so the manager is not
    // necessarily injected; the status is read from the provider and zygote.
    enum class ZygiskStatus { ABSENT, DISABLED, INSTALLED, ACTIVE }

    @JvmField var zygiskStatus = ZygiskStatus.ABSENT

    // Whether a Zygisk provider is actually injecting into processes
    @JvmField var isZygiskEnabled = false

    // Built-in Zygisk injects via the native bridge: the magisk binary is
    // mounted as /system/lib(64)/libzygisk.so, so zygote maps contain it.
    // External providers (ZygiskNext) load through memfd and never match,
    // keeping the toggle's reboot notice built-in specific.
    @JvmField var builtInZygiskActive = false
    @JvmStatic val isFDE get() = crypto == "block"
    @JvmStatic var ramdisk = false
        private set
    private var crypto = ""

    val isEmulator =
        Build.DEVICE.contains("vsoc")
            || getProperty("ro.kernel.qemu", "0") == "1"
            || getProperty("ro.boot.qemu", "0") == "1"

    val isGooglebook get() =
        AppContext.packageManager.hasSystemFeature("com.google.desktop.gms")

    val isPixel get() =
        Build.BRAND.equals("google", ignoreCase = true) && !isGooglebook && !isEmulator

    val isHeadlessSystemUser by lazy {
        getProperty("ro.fw.mu.headless_system_user", "false").toBoolean()
    }

    val isConnected = MutableLiveData(false)

    val showSuperUser: Boolean get() {
        return env.isActive && (Const.USER_ID == 0 || isHeadlessSystemUser
                || Config.suMultiuserMode == Config.Value.MULTIUSER_MODE_USER)
    }

    val isDeviceSecure get() =
        AppContext.getSystemService(KeyguardManager::class.java).isDeviceSecure

    class Env(
        val versionString: String = "",
        val isDebug: Boolean = false,
        code: Int = -1
    ) {
        val versionCode = when {
            code < Const.Version.MIN_VERCODE -> -1
            isRooted -> code
            else -> -1
        }
        val isUnsupported = code > 0 && code < Const.Version.MIN_VERCODE
        val isActive = versionCode > 0
        // The revision suffix reported by the daemon, 0 when the version
        // string has no numeric tanuki suffix.
        val tanukiMinor get() = tanukiMinorOf(versionString)
    }

    fun init(shell: Shell) {
        if (shell.isRoot) {
            val v = runCatching { fastCmd(shell, "magisk -v") }.getOrDefault("").split(":")
            // `magisk -v` goes through the daemon handshake; an empty reply
            // means the channel is degraded even though the shell is root.
            // Keep the previous state instead of overwriting it with "not
            // running".
            if (v.firstOrNull().isNullOrEmpty()) {
                Timber.w("magisk -v empty on a root shell; keeping previous state")
            } else {
                env = Env(
                    v[0], v.size >= 3 && v[2] == "D",
                    runCatching { fastCmd("magisk -V").toInt() }.getOrDefault(-1)
                )
                Config.denyList = fastCmdResult(shell, "magisk magiskhide status")
                sulist = fastCmdResult(shell, "magisk magiskhide sulist")
            }
        }

        // The maps probe is blind to the built-in engine: its loader
        // dlclose()s itself from every app process after specialization,
        // so "zygisk" is gone from the maps by the time the manager reads
        // them. The engine leaves an in-process signal instead:
        // ZYGISK_ENABLED=1 is set for the manager process. The maps probe
        // still covers external providers (ZygiskNext, ReZygisk), whose
        // loader stays mapped.
        val selfInjected = runCatching {
            System.getenv("ZYGISK_ENABLED") == "1" ||
                File("/proc/self/maps").useLines { lines -> lines.any { "zygisk" in it } }
        }.getOrDefault(false)

        // Scan every provider dir instead of taking the first match: the
        // glob enumerates in readdir order, and a disabled residue (a
        // leftover `rezygisk` dir) can shadow the enabled provider
        // (zygisksu) and report DISABLED. An enabled provider wins; DISABLED
        // needs every provider disabled.
        var providerState = ZygiskStatus.ABSENT
        if (shell.isRoot) {
            val states = runCatching {
                Shell.cmd(
                    "for m in /data/adb/modules/*; do " +
                        "case \"\$(basename \$m)\" in zygisksu|rezygisk) " +
                        "[ -e \$m/remove ] && continue; " +
                        "if [ -e \$m/disable ]; then echo DISABLED; else echo INSTALLED; fi;; " +
                        "esac; done"
                ).exec().out
            }.getOrDefault(emptyList())
            providerState = when {
                "INSTALLED" in states -> ZygiskStatus.INSTALLED
                "DISABLED" in states -> ZygiskStatus.DISABLED
                else -> ZygiskStatus.ABSENT
            }
        }

        // Run the liveness probes whenever a provider exists, not only when
        // one is enabled: a live injection wins over a disabled sibling.
        val zygoteInjected = providerState != ZygiskStatus.ABSENT && runCatching {
            Shell.cmd(
                "for p in \$(pidof zygote64) \$(pidof zygote); do " +
                    "grep -qi zygisk /proc/\$p/maps && echo yes; done | head -n 1"
            ).exec().out.isNotEmpty()
        }.getOrDefault(false)

        // ZygiskNext and ReZygisk run the provider as a daemon named
        // zygiskd64/zygiskd32, spawned by the module watchdog at boot. The
        // zygote maps probe can miss it because modules are loaded via memfd.
        // ZygiskNext 1.5.2 closed runs a single zygiskd and ReZygisk's
        // rezygiskd name is observational; a name that matches nothing only
        // costs one pidof iteration.
        val daemonAlive = providerState != ZygiskStatus.ABSENT && !zygoteInjected && runCatching {
            Shell.cmd("pidof zygiskd zygiskd64 zygiskd32 rezygiskd 2>/dev/null || true").exec().out.isNotEmpty()
        }.getOrDefault(false)

        zygiskStatus = when {
            selfInjected || zygoteInjected || daemonAlive -> ZygiskStatus.ACTIVE
            else -> providerState
        }
        isZygiskEnabled = zygiskStatus == ZygiskStatus.ACTIVE

        builtInZygiskActive = shell.isRoot && runCatching {
            Shell.cmd(
                "for p in \$(pidof zygote64) \$(pidof zygote); do " +
                    "grep -qi libzygisk /proc/\$p/maps && echo yes; done | head -n 1"
            ).exec().out.isNotEmpty()
        }.getOrDefault(false)

        val map = mutableMapOf<String, String>()
        val list = object : CallbackList<String>(Runnable::run) {
            override fun onAddElement(e: String) {
                val split = e.split("=")
                if (split.size >= 2) {
                    map[split[0]] = split[1]
                }
            }
        }
        shell.newJob().add("(app_init)").to(list).exec()

        fun getVar(name: String) = map[name] ?: ""
        fun getBool(name: String) = map[name].toBoolean()

        isSAR = getBool("SYSTEM_AS_ROOT")
        ramdisk = getBool("RAMDISKEXIST")
        isAB = getBool("ISAB")
        patchBootVbmeta = getBool("PATCHVBMETAFLAG")
        crypto = getVar("CRYPTOTYPE")
        slot = getVar("SLOT")
        legacySAR = getBool("LEGACYSAR")
        isVendorBoot = getBool("VENDORBOOT")

        // Default presets
        Config.recovery = getBool("RECOVERYMODE")
        Config.keepVerity = getBool("KEEPVERITY")
        Config.keepEnc = getBool("KEEPFORCEENCRYPT")
    }
}
