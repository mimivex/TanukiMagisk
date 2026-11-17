package com.topjohnwu.magisk.core.model

// One release carries an APK per manager flavor and build type: each
// installed manager must pick its own and nothing else.
object UpdateAssets {
    fun isUpdateAsset(name: String, legacy: Boolean, debug: Boolean): Boolean {
        if (!name.endsWith(".apk")) return false
        if (name.contains("legacy") != legacy) return false
        return name.contains("debug") == debug
    }
}
