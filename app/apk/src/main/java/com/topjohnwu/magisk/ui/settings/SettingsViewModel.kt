package com.topjohnwu.magisk.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.model.ColorMode
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.core.utils.RootChannel
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.ui.ThemeState
import com.topjohnwu.magisk.ui.navigation.Route
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel : BaseViewModel() {

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun buildInitialState(): SettingsUiState {
        val selectedColorIndex = ColorMode.fromValue(Config.colorMode).ordinal

        val localeTags = LocaleSetting.available.tags.toList()
        val localeNames = LocaleSetting.available.names.toList()
        val selectedLocaleIndex = localeTags.indexOf(Config.locale).coerceAtLeast(0)

        val updateIndex = Config.updateChannelIndex
        val isCustomChannel = updateIndex == Config.Value.CUSTOM_CHANNEL

        return SettingsUiState(
            isZygiskSupported = Const.Version.atLeast_24_0(),
            isSuRestrictSupported = Const.Version.atLeast_30_1(),
            isMultiuserSupported = !Info.isHeadlessSystemUser,
            isMultiuserEditable = Const.USER_ID == 0,
            useLocaleManager = LocaleSetting.useLocaleManager,

            availableLanguages = localeNames,
            availableLanguageTags = localeTags,
            selectedLanguageIndex = selectedLocaleIndex,
            selectedColorModeIndex = selectedColorIndex,
            isDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            dynamicColorEnabled = Config.dynamicColor,

            selectedUpdateChannelIndex = updateIndex,
            isCustomChannel = isCustomChannel,
            customChannelUrl = Config.customChannelUrl,
            dohEnabled = Config.doh,
            checkUpdateEnabled = Config.checkUpdate,
            downloadPath = Config.downloadDir,
            downloadPathDisplay = MediaStoreUtils.fullPath(Config.downloadDir),
            randomPackageNameEnabled = Config.randName,

            zygiskEnabled = Config.zygisk,
            isZygiskPendingReboot = Config.zygisk != Info.builtInZygiskActive,
            denyListEnabled = Config.magiskHide,
            isDenyListPendingReboot = Config.magiskHide != Config.denyList,
            suListEnabled = Config.sulist,

            selectedMultiuserModeIndex = Config.suMultiuserMode,
            selectedNamespaceModeIndex = Config.suMntNamespaceMode,
            selectedNotificationModeIndex = Config.suNotification,
            suRestrictEnabled = Config.suRestrict,
        ).withEnvironment()
    }

    // Device and environment state that can change while the app is in the background
    private fun SettingsUiState.withEnvironment(): SettingsUiState {
        val isMagiskInstalled = Info.env.isActive
        val isSuperUserAvailable = Info.showSuperUser
        return copy(
            hasRootSections = isMagiskInstalled || isSuperUserAvailable,
            isMagiskInstalled = isMagiskInstalled,
            isSuperUserAvailable = isSuperUserAvailable,
            isDeviceSecure = Info.isDeviceSecure,
            // Config.suAuth also depends on whether the device is secure
            suAuthEnabled = Config.suAuth,
            // Turned off when the notification permission is denied on startup
            checkUpdateEnabled = Config.checkUpdate,
            // The lock follows the REQUESTED SuList mode (suListEnabled); the
            // engine refuses a disable while SuList is requested, and the
            // summaries/titles keep following the applied mode below.
            suListArmed = Info.sulist,
        )
    }

    fun refreshEnvironment() {
        _uiState.update { it.withEnvironment() }
        // The cached root shell may have degraded while the app was in the
        // background. Heal it, then re-read the DB-backed toggles that
        // withEnvironment() does not cover.
        viewModelScope.launch {
            if (RootChannel.ensure()) {
                val fresh = withContext(Dispatchers.IO) {
                    _uiState.value.copy(
                        denyListEnabled = Config.magiskHide,
                        isDenyListPendingReboot = Config.magiskHide != Config.denyList,
                        suListEnabled = Config.sulist,
                    ).withEnvironment()
                }
                _uiState.update { fresh }
            } else if (Info.isRooted) {
                showToast(CoreR.string.no_connection)
            }
        }
    }

    fun setColorMode(index: Int) {
        val newMode = ColorMode.entries[index]
        Config.colorMode = newMode.value
        ThemeState.colorMode = newMode.value
        _uiState.update { it.copy(selectedColorModeIndex = index) }
    }

    fun toggleDynamicColor(enabled: Boolean) {
        Config.dynamicColor = enabled
        ThemeState.dynamicColor = enabled
        _uiState.update { it.copy(dynamicColorEnabled = enabled) }
    }

    fun setLanguage(index: Int) {
        val tag = _uiState.value.availableLanguageTags.getOrNull(index) ?: return
        Config.locale = tag
        _uiState.update { it.copy(selectedLanguageIndex = index) }
    }

    fun setUpdateChannel(index: Int) {
        Config.updateChannel = index
        Info.resetUpdate()
        val isCustom = index == Config.Value.CUSTOM_CHANNEL
        val showUrlDialog = isCustom && Config.customChannelUrl.isBlank()
        _uiState.update {
            it.copy(
                selectedUpdateChannelIndex = index,
                isCustomChannel = isCustom,
                activeDialog = if (showUrlDialog) SettingsDialog.CustomChannelUrl("") else it.activeDialog,
            )
        }
    }

    fun openCustomChannelUrlDialog() {
        _uiState.update {
            it.copy(activeDialog = SettingsDialog.CustomChannelUrl(it.customChannelUrl))
        }
    }

    fun saveCustomChannelUrl(url: String) {
        Config.customChannelUrl = url
        Info.resetUpdate()
        _uiState.update {
            it.copy(
                customChannelUrl = url,
                activeDialog = null,
            )
        }
    }

    fun setDoh(enabled: Boolean) {
        Config.doh = enabled
        _uiState.update { it.copy(dohEnabled = enabled) }
    }

    fun setCheckUpdate(enabled: Boolean) {
        Config.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdateEnabled = enabled) }
    }

    fun openDownloadPathDialog() {
        _uiState.update {
            it.copy(activeDialog = SettingsDialog.DownloadPath(it.downloadPath))
        }
    }

    fun saveDownloadPath(path: String) {
        Config.downloadDir = path
        _uiState.update {
            it.copy(
                downloadPath = path,
                downloadPathDisplay = MediaStoreUtils.fullPath(path),
                activeDialog = null,
            )
        }
    }

    fun setRandomPackageName(enabled: Boolean) {
        Config.randName = enabled
        _uiState.update { it.copy(randomPackageNameEnabled = enabled) }
    }

    fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(CoreR.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }

    fun toggleZygisk(enabled: Boolean) {
        _uiState.update {
            it.copy(
                zygiskEnabled = enabled,
                isZygiskPendingReboot = enabled != Info.builtInZygiskActive,
            )
        }
        val value = if (enabled) 1 else 0
        // The setting is read once at post-fs-data, so a write lost on a
        // broken su shell would silently leave the next boot unarmed. Write
        // it explicitly, verify the result and revert the toggle on failure,
        // like the deny and sulist toggles do.
        Shell.cmd("magisk --sqlite 'REPLACE INTO settings (key,value) VALUES(\"zygisk\",$value)'")
            .submit { result ->
                if (result.isSuccess) {
                    Config.zygisk = enabled
                    if (enabled != Info.builtInZygiskActive) {
                        showToast(CoreR.string.reboot_apply_change)
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            zygiskEnabled = !enabled,
                            isZygiskPendingReboot = !enabled != Info.builtInZygiskActive,
                        )
                    }
                }
            }
    }

    fun toggleDenyList(enabled: Boolean) {
        // SuList forces MagiskHide on; refuse the disable while SuList is
        // requested and explain why.
        if (!enabled && _uiState.value.suListEnabled) {
            AppContext.toast(CoreR.string.settings_magiskhide_sulist_locked, Toast.LENGTH_SHORT)
            return
        }
        _uiState.update {
            it.copy(
                denyListEnabled = enabled,
                isDenyListPendingReboot = enabled != Config.denyList,
            )
        }
        val value = if (enabled) 1 else 0
        // Only the requested state is persisted: the daemon reads this key
        // at boot, so the running ptrace monitor is never touched and the
        // choice stays revocable until the reboot.
        Shell.cmd("magisk --sqlite 'REPLACE INTO settings (key,value) VALUES(\"magiskhide\",$value)'")
            .submit { result ->
                if (result.isSuccess) {
                    Config.magiskHide = enabled
                    val pending = enabled != Config.denyList
                    _uiState.update { it.copy(isDenyListPendingReboot = pending) }
                    if (pending) {
                        showToast(CoreR.string.reboot_apply_change)
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            denyListEnabled = !enabled,
                            isDenyListPendingReboot = !enabled != Config.denyList,
                        )
                    }
                }
            }
    }

    fun toggleSuList(enabled: Boolean) {
        _uiState.update { it.copy(suListEnabled = enabled) }
        // Enabling SuList forces MagiskHide on in the requested state; both
        // writes land in the DB and are applied by the boot-time reader.
        // The multi-row REPLACE keeps them atomic; the fallback chains them
        // if the applet rejects the multi-row form.
        val setSulist = "magisk --sqlite 'REPLACE INTO settings (key,value) VALUES(\"sulist\",1)'"
        val setMagiskHide = "magisk --sqlite 'REPLACE INTO settings (key,value) VALUES(\"magiskhide\",1)'"
        val command =
            if (enabled) "magisk --sqlite 'REPLACE INTO settings (key,value) " +
                "VALUES(\"sulist\",1),(\"magiskhide\",1)' || ($setSulist && $setMagiskHide)"
            else "magisk magiskhide sulist disable"
        Shell.cmd(command).submit { result ->
            if (result.isSuccess) {
                Config.sulist = enabled
                if (enabled) Config.magiskHide = true
                _uiState.update {
                    val denyListEnabled = if (enabled) true else it.denyListEnabled
                    it.copy(
                        suListEnabled = enabled,
                        denyListEnabled = denyListEnabled,
                        isDenyListPendingReboot = denyListEnabled != Config.denyList,
                    )
                }
                if (Info.sulist != enabled) {
                    AppContext.toast(CoreR.string.reboot_apply_change, Toast.LENGTH_SHORT)
                }
            } else {
                _uiState.update { it.copy(suListEnabled = !enabled) }
            }
        }
    }

    fun navigateToDenyList() {
        navigateTo(Route.DenyList)
    }

    fun setSuAuth(enabled: Boolean) {
        Config.suAuth = enabled
        _uiState.update { it.copy(suAuthEnabled = enabled) }
    }

    fun setMultiuserMode(index: Int) {
        Config.suMultiuserMode = index
        _uiState.update { it.copy(selectedMultiuserModeIndex = index) }
    }

    fun setNamespaceMode(index: Int) {
        Config.suMntNamespaceMode = index
        _uiState.update { it.copy(selectedNamespaceModeIndex = index) }
    }

    fun setNotificationMode(index: Int) {
        Config.suNotification = index
        _uiState.update { it.copy(selectedNotificationModeIndex = index) }
    }

    fun setSuRestrict(enabled: Boolean) {
        Config.suRestrict = enabled
        _uiState.update { it.copy(suRestrictEnabled = enabled) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = null) }
    }
}
