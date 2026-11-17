package com.topjohnwu.magisk.ui.settings

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.tasks.AppMigration
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.core.utils.TextHolder
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.databinding.DialogSettingsAppNameBinding
import com.topjohnwu.magisk.databinding.DialogSettingsDownloadPathBinding
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.view.MagiskDialog
import com.topjohnwu.superuser.Shell
import com.topjohnwu.magisk.core.R as CoreR

// --- Customization

object Customization : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_customization.asText()
}

object Language : BaseSettingsItem.Selector() {
    private val names: Array<String> get() = LocaleSetting.available.names
    private val tags: Array<String> get() = LocaleSetting.available.tags

    override var value
        get() = tags.indexOf(Config.locale)
        set(value) {
            Config.locale = tags[value]
        }

    override val title = CoreR.string.language.asText()

    override fun entries(res: Resources) = names
    override fun descriptions(res: Resources) = names
}

object LanguageSystem : BaseSettingsItem.Blank() {
    override val title = CoreR.string.language.asText()
    override val description: TextHolder
        get() {
            val locale = LocaleSetting.instance.appLocale
            return locale?.getDisplayName(locale)?.asText() ?: CoreR.string.system_default.asText()
        }
}

object Theme : BaseSettingsItem.Blank() {
    override val icon = R.drawable.ic_paint
    override val title = CoreR.string.section_theme.asText()
}

// --- App

object AppSettings : BaseSettingsItem.Section() {
    override val title = CoreR.string.home_app_title.asText()
}

object Hide : BaseSettingsItem.Input() {
    override val title = CoreR.string.settings_hide_app_title.asText()
    override val description = CoreR.string.settings_hide_app_summary.asText()
    override var value = ""

    override val inputResult
        get() = if (isError) null else result

    @get:Bindable
    var result = "Settings"
        set(value) = set(value, field, { field = it }, BR.result, BR.error)

    val maxLength
        get() = AppMigration.MAX_LABEL_LENGTH

    @get:Bindable
    val isError
        get() = result.length > maxLength || result.isBlank()

    override fun getView(context: Context) = DialogSettingsAppNameBinding
        .inflate(LayoutInflater.from(context)).also { it.data = this }.root
}

object Restore : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_restore_app_title.asText()
    override val description = CoreR.string.settings_restore_app_summary.asText()

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            MagiskDialog(view.activity).apply {
                setTitle(CoreR.string.settings_restore_app_title)
                setMessage(CoreR.string.restore_app_confirmation)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        handler.onItemAction(view, this@Restore)
                    }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
                setCancelable(true)
                show()
            }
        }
    }
}

object DownloadPath : BaseSettingsItem.Input() {
    override var value
        get() = Config.downloadDir
        set(value) {
            Config.downloadDir = value
            notifyPropertyChanged(BR.description)
        }

    override val title = CoreR.string.settings_download_path_title.asText()
    override val description get() = MediaStoreUtils.fullPath(value).asText()

    override var inputResult: String = value
        set(value) = set(value, field, { field = it }, BR.inputResult, BR.path)

    @get:Bindable
    val path get() = MediaStoreUtils.fullPath(inputResult)

    override fun getView(context: Context) = DialogSettingsDownloadPathBinding
        .inflate(LayoutInflater.from(context)).also { it.data = this }.root
}

object DoHToggle : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_doh_title.asText()
    override val description = CoreR.string.settings_doh_description.asText()
    override var value by Config::doh
}

object SystemlessHosts : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_hosts_title.asText()
    override val description = CoreR.string.settings_hosts_summary.asText()
}

object RandNameToggle : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_random_name_title.asText()
    override val description = CoreR.string.settings_random_name_description.asText()
    override var value by Config::randName
}

// --- Magisk

object Magisk : BaseSettingsItem.Section() {
    override val title = CoreR.string.magisk.asText()
}

object Zygisk : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.zygisk.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_zygisk_summary.asText()

    override var value
        get() = Config.zygisk
        set(value) {
            Config.zygisk = value
            notifyPropertyChanged(BR.description)
        }

    val mismatch get() = value != Info.builtInZygiskActive
}

object DenyList : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_magiskhide_title.asText()
    override val description get() = when {
        // Applied mode, like the config entry (Info.sulist): while SuList is
        // armed the engine enforces SuList, so the label must keep naming
        // what is actually enforced.
        Info.sulist -> CoreR.string.settings_sulist_enforced.asText()
        // The requested state differs from the armed one: applied at reboot.
        Config.magiskHide != Config.denyList -> CoreR.string.reboot_apply_change.asText()
        else -> CoreR.string.settings_magiskhide_summary.asText()
    }

    // The switch shows the REQUESTED state (Config.magiskHide), kept
    // separate from the armed Config.denyList: changes are written to the DB
    // only and applied by the boot-time reader, so they stay revocable until
    // the reboot. SuList forces MagiskHide on.
    private var checked = Config.magiskHide || Config.sulist

    override var value
        get() = checked
        set(value) {
            if (!value && Config.sulist) {
                // SuList forces MagiskHide on; it can only be turned off by
                // disabling SuList (applied at the reboot).
                AppContext.toast(CoreR.string.settings_magiskhide_sulist_locked, Toast.LENGTH_SHORT)
                return
            }
            checked = value
            val v = if (value) 1 else 0
            Shell.cmd("magisk --sqlite 'REPLACE INTO settings (key,value) VALUES(\"magiskhide\",$v)'")
                .submit { result ->
                    if (result.isSuccess) {
                        Config.magiskHide = value
                        if (value != Config.denyList) {
                            AppContext.toast(CoreR.string.reboot_apply_change, Toast.LENGTH_SHORT)
                        }
                        notifyPropertyChanged(BR.description)
                        DenyListConfig.refresh()
                        SuList.notifyPropertyChanged(BR.description)
                        SuList.refresh()
                    } else {
                        checked = !value
                        notifyPropertyChanged(BR.checked)
                    }
                }
        }

    // Always enabled: turning MagiskHide off is refused above while SuList
    // is requested, so the switch needs no lock to stay truthful.
    override fun refresh() {
        isEnabled = true
        checked = Config.magiskHide || Config.sulist
        notifyPropertyChanged(BR.checked)
    }
}

object SuList : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_sulist_title.asText()
    override val description get() =
        if (!Config.magiskHide) CoreR.string.settings_sulist_error_magiskhide.asText()
        else if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_sulist_summary.asText()

    override var value = Config.sulist
        set(value) {
            field = value
            // Enabling SuList forces MagiskHide on in the requested state;
            // both writes land in the DB and are applied by the boot-time
            // reader. The multi-row REPLACE keeps them atomic; the fallback
            // chains them if the applet rejects the multi-row form.
            val setSulist = "magisk --sqlite 'REPLACE INTO settings (key,value) VALUES(\"sulist\",1)'"
            val setMagiskHide = "magisk --sqlite 'REPLACE INTO settings (key,value) VALUES(\"magiskhide\",1)'"
            val command =
                if (value) "magisk --sqlite 'REPLACE INTO settings (key,value) " +
                    "VALUES(\"sulist\",1),(\"magiskhide\",1)' || ($setSulist && $setMagiskHide)"
                else "magisk magiskhide sulist disable"
            Shell.cmd(command).submit { result ->
                if (result.isSuccess) {
                    Config.sulist = value
                    if (value) {
                        Config.magiskHide = true
                        DenyList.notifyPropertyChanged(BR.checked)
                        DenyList.notifyPropertyChanged(BR.description)
                        DenyList.refresh()
                    }
                    notifyPropertyChanged(BR.description)
                    // The config entry follows the applied mode (Info.sulist),
                    // so these refreshes are no-ops until the reboot that
                    // arms the switch.
                    DenyListConfig.notifyPropertyChanged(BR.title)
                    DenyListConfig.notifyPropertyChanged(BR.description)
                } else {
                    field = !value
                    notifyPropertyChanged(BR.checked)
                }
            }
        }

    // The switch is always enabled: enabling SuList auto-enables MagiskHide.
    override fun refresh() {
        isEnabled = true
    }

    val mismatch get() = value != Info.sulist
}

object DenyListConfig : BaseSettingsItem.Blank() {
    // The titles follow the applied mode (Info.sulist); the toggle's
    // description carries the reboot hint.
    override val title get() =
        if (Info.sulist) CoreR.string.settings_sulist_config_title.asText()
        else CoreR.string.settings_hidelist_config_title.asText()
    override val description get() =
        if (Info.sulist) CoreR.string.settings_sulist_config_summary.asText()
        else CoreR.string.settings_hidelist_config_summary.asText()
}

// --- Superuser

object Tapjack : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_tapjack_title.asText()
    override val description = CoreR.string.settings_su_tapjack_summary.asText()
    override var value by Config::suTapjack
}

object Authentication : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_auth_title.asText()
    override var description = CoreR.string.settings_su_auth_summary.asText()
    override var value by Config::suAuth

    override fun refresh() {
        isEnabled = Info.isDeviceSecure
        if (!isEnabled) {
            description = CoreR.string.settings_su_auth_insecure.asText()
        }
    }
}

object Superuser : BaseSettingsItem.Section() {
    override val title = CoreR.string.superuser.asText()
}

object AccessMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.superuser_access.asText()
    override val entryRes = CoreR.array.su_access
    override var value by Config::rootMode
}

object MultiuserMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.multiuser_mode.asText()
    override val entryRes = CoreR.array.multiuser_mode
    override val descriptionRes = CoreR.array.multiuser_summary
    override var value by Config::suMultiuserMode

    override fun refresh() {
        isEnabled = Const.USER_ID == 0
    }
}

object MountNamespaceMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.mount_namespace_mode.asText()
    override val entryRes = CoreR.array.namespace
    override val descriptionRes = CoreR.array.namespace_summary
    override var value by Config::suMntNamespaceMode
}

object AutomaticResponse : BaseSettingsItem.Selector() {
    override val title = CoreR.string.auto_response.asText()
    override val entryRes = CoreR.array.auto_response
    override var value by Config::suAutoResponse
}

object RequestTimeout : BaseSettingsItem.Selector() {
    override val title = CoreR.string.request_timeout.asText()
    override val entryRes = CoreR.array.request_timeout

    private val entryValues = listOf(10, 15, 20, 30, 45, 60)
    override var value = entryValues.indexOfFirst { it == Config.suDefaultTimeout }
        set(value) {
            field = value
            Config.suDefaultTimeout = entryValues[value]
        }
}

object SUNotification : BaseSettingsItem.Selector() {
    override val title = CoreR.string.superuser_notification.asText()
    override val entryRes = CoreR.array.su_notification
    override var value by Config::suNotification
}

object Reauthenticate : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_reauth_title.asText()
    override val description = CoreR.string.settings_su_reauth_summary.asText()
    override var value by Config::suReAuth

    override fun refresh() {
        isEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    }
}

object Restrict : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_restrict_title.asText()
    override val description = CoreR.string.settings_su_restrict_summary.asText()
    override var value by Config::suRestrict
}
