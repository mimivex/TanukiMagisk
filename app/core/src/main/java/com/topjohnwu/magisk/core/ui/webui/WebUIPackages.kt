package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class WebUIPackageInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val appLabel: String,
    val isSystem: Boolean,
    val uid: Int
)

/**
 * The app list behind `ksu.listPackages` and `ksu.getPackagesInfo`.
 *
 * KsuWebUIStandalone answers these from `AppList`, which asks a root
 * service over AIDL; the manager already declares `QUERY_ALL_PACKAGES`,
 * so the same data comes from `PackageManager` in-process.
 *
 * The list is cached for the lifetime of the WebUI activity: the first
 * call walks every installed package (~100-300 ms) on the WebView's
 * JavaBridge thread, later calls reuse the result.
 */
object WebUIPackages {

    @Volatile
    private var cache: List<WebUIPackageInfo>? = null

    fun getApps(context: Context): List<WebUIPackageInfo> {
        cache?.let { return it }
        val pm = context.packageManager
        val apps = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            .mapNotNull { pkg ->
                val app = pkg.applicationInfo ?: return@mapNotNull null
                runCatching {
                    WebUIPackageInfo(
                        packageName = pkg.packageName,
                        versionName = pkg.versionName,
                        versionCode = PackageInfoCompat.getLongVersionCode(pkg),
                        appLabel = pm.getApplicationLabel(app).toString(),
                        isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        uid = app.uid
                    )
                }.onFailure {
                    Timber.w(it, "Skipping %s", pkg.packageName)
                }.getOrNull()
            }
        cache = apps
        return apps
    }

    fun clearCache() {
        cache = null
    }

    /**
     * `type` is `"system"`, `"user"` or anything else (including null and
     * the empty string) for every package, as `ksu.listPackages` defines
     * it. The result is sorted, like the reference.
     */
    fun filterNames(apps: List<WebUIPackageInfo>, type: String?): List<String> =
        apps.filter { app ->
            when (type?.lowercase()) {
                "system" -> app.isSystem
                "user" -> !app.isSystem
                else -> true
            }
        }.map { it.packageName }.sorted()

    /**
     * `ksu.getPackagesInfo` output: one object per requested package, in
     * the requested order, with the reference's field set; unknown names
     * come back as an error object instead of being dropped.
     */
    fun buildPackagesInfoJson(
        requested: List<String>,
        apps: List<WebUIPackageInfo>
    ): JSONArray {
        val appMap = apps.associateBy { it.packageName }
        val result = JSONArray()
        for (name in requested) {
            val app = appMap[name]
            val obj = JSONObject()
            if (app != null) {
                obj.put("packageName", app.packageName)
                obj.put("versionName", app.versionName ?: "")
                obj.put("versionCode", app.versionCode)
                obj.put("appLabel", app.appLabel)
                obj.put("isSystem", app.isSystem)
                obj.put("uid", app.uid)
            } else {
                obj.put("packageName", name)
                obj.put("error", "Package not found or inaccessible")
            }
            result.put(obj)
        }
        return result
    }
}
