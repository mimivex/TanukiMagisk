package com.topjohnwu.magisk.ui.module

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.download.Subject
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.model.module.OnlineModule
import com.topjohnwu.magisk.ui.flash.FlashUtils
import com.topjohnwu.magisk.ui.navigation.Route
import com.topjohnwu.magisk.utils.asFlow
import com.topjohnwu.magisk.view.Notifications
import com.topjohnwu.magisk.core.R as CoreR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

data class ModuleItem(
    val module: LocalModule,
    val isEnabled: Boolean = module.enable,
    val isRemoved: Boolean = module.remove,
    val showUpdate: Boolean = module.updateInfo != null,
    // Enable state at the first load of this session. Modules are only
    // applied at boot, so the reboot notice shows while the switch differs from
    // this; a constructor param so copy() preserves it across UI updates.
    val originalEnabled: Boolean = module.enable,
) {
    // Computed on read, so toggling back to the original state clears
    // the reboot notice; a stored latch would keep it forever.
    val isToggled get() = isEnabled != originalEnabled
    // No built-in Zygisk: a Zygisk module needs an external provider.
    val noticeRes: Int? = when {
        !module.isZygisk -> null
        Info.zygiskStatus == Info.ZygiskStatus.ACTIVE -> null
        Info.zygiskStatus == Info.ZygiskStatus.ABSENT -> CoreR.string.zygisk_provider_missing
        Info.zygiskStatus == Info.ZygiskStatus.DISABLED -> CoreR.string.zygisk_provider_disabled
        else -> CoreR.string.zygisk_provider_not_injected
    }
    val showNotice = noticeRes != null

    // The notice does not gate the action button.
    val showAction = module.hasAction
    val showWebUI = module.hasWebUI
    val isUpdated = module.updated
    val updateReady get() = module.outdated && !isRemoved && isEnabled
    val iconOnlyActions get() = showAction && showWebUI && showUpdate && updateReady
}

@Parcelize
class OnlineModuleSubject(
    override val module: OnlineModule,
    override val autoLaunch: Boolean,
    override val notifyId: Int = Notifications.nextId(),
) : Subject.Module() {
    override fun pendingIntent(context: Context) = FlashUtils.installIntent(context, file, notifyId)
}

class ModuleViewModel : AsyncLoadViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val modules: List<ModuleItem> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            Info.isConnected.asFlow().collect {
                startLoading()
            }
        }
    }

    override suspend fun doLoadWork() {
        // Keep showing the current list while refreshing: loading is only true until the first
        // load finishes, so a refresh doesn't swap the list for a spinner and lose its scroll
        val moduleLoaded = Info.env.isActive &&
            withContext(Dispatchers.IO) { LocalModule.loaded() }
        if (moduleLoaded) {
            val previous = _uiState.value.modules.associateBy { it.module.base.name }
            val modules = withContext(Dispatchers.Default) {
                LocalModule.installed().map { module ->
                    // Keep the update info of the same module version until it is fetched
                    // again, so update buttons don't disappear on every refresh
                    previous[module.base.name]?.module
                        ?.takeIf { it.versionCode == module.versionCode }
                        ?.let {
                            module.updateInfo = it.updateInfo
                            module.outdated = it.outdated
                        }
                    ModuleItem(
                        module,
                        originalEnabled = previous[module.base.name]?.originalEnabled ?: module.enable
                    )
                }
            }
            _uiState.update { it.copy(loading = false, modules = modules) }
            loadUpdateInfo()
        } else {
            _uiState.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadUpdateInfo() {
        withContext(Dispatchers.IO) {
            _uiState.update { state ->
                state.copy(
                    modules = state.modules.map { item ->
                        if (item.module.fetch()) {
                            item.copy(showUpdate = item.module.updateInfo != null)
                        } else {
                            item
                        }
                    }
                )
            }
        }
    }

    fun confirmLocalInstall(uri: Uri) {
        navigateTo(Route.Flash(Const.Value.FLASH_ZIP, uri.toString()))
    }

    fun runAction(dir: String, name: String) {
        navigateTo(Route.Action(dir, name))
    }

    fun toggleEnabled(item: ModuleItem) {
        val newEnabled = !item.isEnabled
        item.module.enable = newEnabled
        _uiState.update { state ->
            state.copy(
                modules = state.modules.map {
                    if (it.module.base.name == item.module.base.name)
                        it.copy(isEnabled = newEnabled)
                    else it
                }
            )
        }
    }

    fun toggleRemove(item: ModuleItem) {
        val newRemoved = !item.isRemoved
        item.module.remove = newRemoved
        _uiState.update { state ->
            state.copy(
                modules = state.modules.map {
                    if (it.module.base.name == item.module.base.name) it.copy(isRemoved = newRemoved) else it
                }
            )
        }
    }
}
