package com.topjohnwu.magisk.arch

import android.content.Context

/**
 * Class for passing events from ViewModels to Activities/Fragments
 * (see https://medium.com/google-developers/livedata-with-snackbar-navigation-and-other-events-the-singleliveevent-case-ac2622673150)
 */
abstract class ViewEvent {
    /**
     * Sticky events are redelivered to observers that re-attach after
     * activity recreation; the su prompt rebuilds its UI through this.
     * Everything else is delivered exactly once (see
     * [BaseViewModel.consumeEvent]).
     */
    open val isSticky get() = false
}

interface ContextExecutor {
    operator fun invoke(context: Context)
}

interface ActivityExecutor {
    operator fun invoke(activity: UIActivity<*>)
}

interface FragmentExecutor {
    operator fun invoke(fragment: BaseFragment<*>)
}
