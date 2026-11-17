package com.topjohnwu.magisk.core.repository

import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Config.Value.BETA_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.CUSTOM_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.CANARY_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.DEFAULT_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.STABLE_CHANNEL
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ManagerFlavor
import com.topjohnwu.magisk.core.data.GithubApiServices
import com.topjohnwu.magisk.core.data.RawUrl
import com.topjohnwu.magisk.core.isOlderVersion
import com.topjohnwu.magisk.core.ktx.dateFormat
import com.topjohnwu.magisk.core.model.GooglebookRecoveryJson
import com.topjohnwu.magisk.core.model.Release
import com.topjohnwu.magisk.core.model.ReleaseAssets
import com.topjohnwu.magisk.core.model.UpdateAssets
import com.topjohnwu.magisk.core.model.UpdateInfo
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException

class NetworkService(
    private val raw: RawUrl,
    private val api: GithubApiServices,
) {
    suspend fun fetchUpdate() = safe {
        var info = when (Config.updateChannel) {
            DEFAULT_CHANNEL -> if (BuildConfig.DEBUG) fetchCanaryUpdate() else fetchStableUpdate()
            STABLE_CHANNEL -> fetchStableUpdate()
            BETA_CHANNEL -> fetchBetaUpdate()
            CANARY_CHANNEL -> fetchCanaryUpdate()
            CUSTOM_CHANNEL -> fetchCustomUpdate(Config.customChannelUrl)
            else -> throw IllegalArgumentException()
        }
        if (isOlderVersion(info.versionCode, info.version, Info.env.versionCode, Info.env.versionString) &&
            Config.updateChannel == DEFAULT_CHANNEL &&
            !BuildConfig.DEBUG
        ) {
            Config.updateChannel = BETA_CHANNEL
            info = fetchBetaUpdate()
        }
        info
    }

    suspend fun fetchUpdate(versionCode: Int, version: String) = safe {
        // The exact build first: several revisions share a version code, and
        // each one has its own changelog. A build that predates the first
        // release of its revision falls back to any release of the code.
        findRelease { it.versionCode == versionCode && it.tag.drop(1) == version }?.asInfo()
            ?: findRelease { it.versionCode == versionCode }?.asInfo()
    }

    suspend fun fetchGooglebookRecovery() = safe {
        raw.fetchGooglebookRecoveryJson(Const.Url.GOOGLEBOOK_RECOVERY_URL)
    }

    suspend fun fetchPixelFactoryImages() = safe {
        raw.fetchPixelFactoryImages(Const.Url.PIXEL_FACTORY_IMAGES_URL)
    }

    // Keep going through all release pages until we find a match
    private suspend inline fun findRelease(predicate: (Release) -> Boolean): Release? {
        var page = 1
        while (true) {
            val response = api.fetchReleases(page = page)
            val releases = response.body() ?: throw HttpException(response)
            // Remove all non Magisk releases
            releases.removeAll { it.tag[0] != 'v' && !it.tag.startsWith("canary") }
            // Make sure it's sorted correctly
            releases.sortByDescending { it.createdTime }
            releases.find(predicate)?.let { return it }
            if (response.headers()["link"]?.contains("rel=\"next\"", ignoreCase = true) == true) {
                page += 1
            } else {
                return null
            }
        }
    }

    private inline fun Release?.asInfo(
        selector: (ReleaseAssets) -> Boolean = {
            // Default selector picks the non-debug APK
            it.name.run { endsWith(".apk") && !contains("debug") }
        }): UpdateInfo {
        return if (this == null) UpdateInfo()
        else if (tag[0] == 'v') asPublicInfo(selector)
        else asCanaryInfo(selector)
    }

    private inline fun Release.asPublicInfo(selector: (ReleaseAssets) -> Boolean): UpdateInfo {
        val version = tag.drop(1)
        val date = dateFormat.format(createdTime)
        return UpdateInfo(
            version = version,
            versionCode = versionCode,
            link = assets.find(selector)!!.url,
            note = "## $date $name\n\n$body"
        )
    }

    private inline fun Release.asCanaryInfo(selector: (ReleaseAssets) -> Boolean): UpdateInfo {
        return UpdateInfo(
            version = name.substring(8, 16),
            versionCode = versionCode,
            link = assets.find(selector)!!.url,
            note = "## $name\n\n$body"
        )
    }

    // Version number: debug == beta >= stable

    // One release carries an APK per flavor: the channel picks the release
    // (and, as upstream, release vs debug), the installed manager picks its own.
    private fun updateAsset(debug: Boolean): (ReleaseAssets) -> Boolean {
        val legacy = ManagerFlavor.isLegacy
        return { UpdateAssets.isUpdateAsset(it.name, legacy, debug) }
    }

    // Find the latest non-prerelease
    private suspend fun fetchStableUpdate() =
        api.fetchLatestRelease().asInfo(updateAsset(debug = false))

    // Find the latest release, regardless whether it's prerelease
    private suspend fun fetchBetaUpdate() =
        findRelease { true }.asInfo(updateAsset(debug = false))

    private suspend fun fetchCanaryUpdate() =
        findRelease { true }.asInfo(updateAsset(debug = true))

    private suspend fun fetchCustomUpdate(url: String): UpdateInfo {
        val info = raw.fetchUpdateJson(url).magisk
        return info.let { it.copy(note = raw.fetchString(it.note)) }
    }

    private inline fun <T> safe(factory: () -> T): T? {
        return try {
            if (Info.isConnected.value == true)
                factory()
            else
                null
        } catch (e: HttpException) {
            // A 404 on the update endpoints means "nothing published yet"
            // (empty or private repo). Keep upstream's error level, but log
            // one short line instead of the full stack every refresh.
            if (e.code() == 404) {
                Timber.e("update check: HTTP 404, no release on this channel")
                null
            } else {
                Timber.e(e)
                null
            }
        } catch (e: Exception) {
            Timber.e(e)
            null
        }
    }

    private inline fun <T> wrap(factory: () -> T): T {
        return try {
            factory()
        } catch (e: HttpException) {
            throw IOException(e)
        }
    }

    // Fetch files
    suspend fun fetchFile(url: String) = wrap { raw.fetchFile(url) }
    suspend fun fetchString(url: String) = wrap { raw.fetchString(url) }
    suspend fun fetchModuleJson(url: String) = wrap { raw.fetchModuleJson(url) }
}
