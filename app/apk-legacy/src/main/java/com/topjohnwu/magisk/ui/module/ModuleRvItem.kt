package com.topjohnwu.magisk.ui.module

import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.utils.TextHolder
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.databinding.DiffItem
import com.topjohnwu.magisk.databinding.ItemWrapper
import com.topjohnwu.magisk.databinding.ObservableRvItem
import com.topjohnwu.magisk.databinding.RvItem
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.core.R as CoreR

object InstallModule : RvItem(), DiffItem<InstallModule> {
    override val layoutRes = R.layout.item_module_download
}

class LocalModuleRvItem(
    override val item: LocalModule,
    // Enable state at the first load of this session; see isEnabled.
    private val originalEnabled: Boolean = item.enable
) : ObservableRvItem(), DiffItem<LocalModuleRvItem>, ItemWrapper<LocalModule> {

    override val layoutRes = R.layout.item_module_md2

    val showNotice: Boolean
    val showAction: Boolean
    val showWebUI: Boolean
    val noticeText: TextHolder

    init {
        // No built-in Zygisk: a Zygisk module needs an external provider.
        val noticeRes = when {
            !item.isZygisk -> null
            Info.zygiskStatus == Info.ZygiskStatus.ACTIVE -> null
            Info.zygiskStatus == Info.ZygiskStatus.ABSENT -> CoreR.string.zygisk_provider_missing
            Info.zygiskStatus == Info.ZygiskStatus.DISABLED -> CoreR.string.zygisk_provider_disabled
            else -> CoreR.string.zygisk_provider_not_injected
        }
        showNotice = noticeRes != null
        // The notice does not gate the action button.
        showAction = item.hasAction
        showWebUI = item.hasWebUI
        noticeText = noticeRes?.asText() ?: TextHolder.EMPTY
    }

    @get:Bindable
    var isEnabled = item.enable
        set(value) = set(value, field, { field = it }, BR.enabled, BR.updateReady) {
            item.enable = value
            // Modules are only applied at boot, so a toggle needs the
            // reboot notice as much as an update does; toggling back to the
            // original state clears it. Remove stays silent.
            toggled = value != originalEnabled
        }

    @get:Bindable
    var toggled = false
        private set(value) = set(value, field, { field = it }, BR.toggled)

    @get:Bindable
    var isRemoved = item.remove
        set(value) = set(value, field, { field = it }, BR.removed, BR.updateReady) {
            item.remove = value
        }

    @get:Bindable
    val showUpdate get() = item.updateInfo != null

    @get:Bindable
    val updateReady get() = item.outdated && !isRemoved && isEnabled

    val isUpdated = item.updated

    fun fetchedUpdateInfo() {
        notifyPropertyChanged(BR.showUpdate)
        notifyPropertyChanged(BR.updateReady)
    }

    fun delete() {
        isRemoved = !isRemoved
    }

    override fun itemSameAs(other: LocalModuleRvItem): Boolean = item.id == other.item.id
}
