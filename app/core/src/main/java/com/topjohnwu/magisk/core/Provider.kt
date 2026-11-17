package com.topjohnwu.magisk.core

import android.os.Bundle
import com.topjohnwu.magisk.core.base.BaseProvider
import com.topjohnwu.magisk.core.su.SuCallbackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Provider : BaseProvider() {

    // su log/notify must never run on the binder thread: blocking here stalls
    // the caller's `content call` child (one per su session); when this app is
    // frozen or killed those children pile up as ~150MB orphaned app_process
    // processes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            SuCallbackHandler.LOG, SuCallbackHandler.NOTIFY -> {
                if (extras != null) {
                    // Materialize the parcel-backed Bundle now: the parcel is
                    // recycled once call() returns.
                    val safe = Bundle()
                    safe.putAll(extras)
                    val ctx = context!!
                    scope.launch { SuCallbackHandler.run(ctx, method, safe) }
                }
                Bundle.EMPTY
            }
            else -> Bundle.EMPTY
        }
    }
}
