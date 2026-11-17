package com.topjohnwu.magisk.core.utils

import com.topjohnwu.magisk.core.Info
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber

// libsu caches one MainShell per process and only evicts it when it is dead,
// so a transient failure that falls back to a non-root `sh` leaves every
// later root read failing silently for the process's whole lifetime. Probe
// the cached shell and rebuild it when it is broken.
object RootChannel {

    private const val HEAL_COOLDOWN_MS = 30_000L
    private const val MAX_ATTEMPTS = 3

    private val healMutex = Mutex()
    private var healCooldownUntil = 0L

    // `magisk -V` goes through the daemon handshake, so this is true only
    // when the shell can run the binary AND the daemon accepts its uid.
    private fun channelOk(shell: Shell): Boolean =
        shell.isRoot &&
            runCatching { fastCmd(shell, "magisk -V") }.getOrNull().orEmpty().isNotEmpty()

    // Whether the cached MainShell is usable once this call returns.
    suspend fun ensure(): Boolean = withContext(Dispatchers.IO) {
        val cached = Shell.getCachedShell() ?: return@withContext false
        if (channelOk(cached)) return@withContext true
        // A process that never had root has nothing to heal: rebuilding
        // would only produce another non-root shell.
        if (!cached.isRoot && !Info.isRooted) return@withContext false
        val now = System.currentTimeMillis()
        if (now < healCooldownUntil) return@withContext false
        if (!healMutex.tryLock()) return@withContext false
        try {
            healCooldownUntil = now + HEAL_COOLDOWN_MS
            Timber.w("Degraded root channel (root=${cached.isRoot}); rebuilding")
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    if (!cached.waitAndClose(2, TimeUnit.SECONDS)) {
                        Timber.d("Shell busy, deferring the rebuild")
                        return@withContext false
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Closing the broken shell failed")
                }
                val shell = runCatching { Shell.getShell() }.getOrElse {
                    Timber.w(it, "Shell rebuild failed")
                    return@withContext false
                }
                if (channelOk(shell)) {
                    // getShell() reruns ShellInit, but do not depend on it:
                    // reload the daemon state on the healed shell.
                    Info.init(shell)
                    Timber.d("Root channel rebuilt (attempt $attempt)")
                    return@withContext true
                }
                if (attempt < MAX_ATTEMPTS) delay(500)
            }
            Timber.w("Gave up rebuilding the root channel")
            false
        } finally {
            healMutex.unlock()
        }
    }
}
