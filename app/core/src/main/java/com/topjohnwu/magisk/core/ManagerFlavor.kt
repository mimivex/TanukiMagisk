package com.topjohnwu.magisk.core

import android.content.pm.PackageManager

// The two managers share :core, the package name and the version, so the
// installed APK is the only thing that tells them apart: the legacy module
// sets this manifest flag, the Compose module leaves it out. The key avoids
// the package strings AppMigration rewrites in a hidden manager's manifest.
object ManagerFlavor {
    private const val LEGACY_KEY = "tanuki_legacy_manager"

    val isLegacy: Boolean by lazy {
        runCatching {
            AppContext.packageManager
                .getApplicationInfo(AppContext.packageName, PackageManager.GET_META_DATA)
                .metaData?.getBoolean(LEGACY_KEY) ?: false
        }.getOrDefault(false)
    }
}
