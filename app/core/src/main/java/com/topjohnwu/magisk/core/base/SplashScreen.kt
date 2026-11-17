package com.topjohnwu.magisk.core.base

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import com.topjohnwu.magisk.core.BuildConfig.APP_PACKAGE_NAME
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.JobService
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.ktx.reflectField
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.view.Notifications
import com.topjohnwu.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell

interface SplashScreenHost : IActivityExtension {
    val splashController: SplashController<*>

    fun onCreateUi(savedInstanceState: Bundle?)
}

class SplashController<T>(private val activity: T)
    where T: ComponentActivity, T: SplashScreenHost {

    companion object {
        private var splashShown = false
    }

    private var shouldCreateUiOnResume = false

    fun preOnCreate() {}

    fun onCreate(savedInstanceState: Bundle?) {
        // Re-creating the activity (e.g. a theme change) must not reinstall
        // the splash screen: it would be dismissed on the very next frame
        // and flash the splash background.
        if (!splashShown) {
            val splashScreen = activity.installSplashScreen()
            splashScreen.setKeepOnScreenCondition { !splashShown }
        }

        if (splashShown) {
            doCreateUi(savedInstanceState)
        } else {
            Shell.getShell(Shell.EXECUTOR) {
                activity.initializeApp()
                activity.runOnUiThread {
                    splashShown = true
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        doCreateUi(savedInstanceState)
                    } else {
                        shouldCreateUiOnResume = true
                    }
                }
            }
        }
    }

    fun onResume() {
        if (shouldCreateUiOnResume) {
            doCreateUi(null)
        }
    }

    private fun doCreateUi(savedInstanceState: Bundle?) {
        shouldCreateUiOnResume = false
        activity.onCreateUi(savedInstanceState)
    }

    private fun T.initializeApp() {
        val prevConfig = intent.getBundleExtra(Const.Key.PREV_CONFIG)
        Config.init(prevConfig)

        // Classic hide/unhide semantics (upstream final and K27): the hidden
        // copy always removes the original it was cloned from, and the
        // original launched by a restore always removes the hidden copy
        // that launched it (system-provided referrer, not a forgeable extra).
        if (packageName != APP_PACKAGE_NAME) {
            runCatching {
                // Hidden, remove <original> if it exists as it could be malware
                packageManager.getApplicationInfo(APP_PACKAGE_NAME, 0)
                Shell.cmd("(pm uninstall $APP_PACKAGE_NAME)& >/dev/null 2>&1").exec()
            }
        } else {
            if (Config.suManager.isNotEmpty()) {
                Config.suManager = ""
            }
            if (prevConfig != null) {
                launchedPackage?.takeIf { it != APP_PACKAGE_NAME }?.let {
                    Shell.cmd("(pm uninstall $it)& >/dev/null 2>&1").exec()
                }
            }
        }

        Notifications.setup()
        JobService.schedule(this)
        Shortcuts.setupDynamic(this)

        // Pre-fetch network services
        ServiceLocator.networkService

        // Wait for root service
        RootUtils.Connection.await()
    }
}

@Suppress("DEPRECATION")
private val Activity.launchedPackage: String?
    get() = if (Build.VERSION.SDK_INT >= 34) {
        launchedFromPackage
    } else {
        Activity::class.java.reflectField("mReferrer").get(this) as String?
    }
