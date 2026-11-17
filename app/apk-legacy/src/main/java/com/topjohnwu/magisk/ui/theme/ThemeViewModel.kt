package com.topjohnwu.magisk.ui.theme

import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.dialog.DarkThemeDialog
import com.topjohnwu.magisk.view.TappableHeadlineItem

class ThemeViewModel : BaseViewModel(), TappableHeadlineItem.Listener {

    val themeHeadline = TappableHeadlineItem.ThemeMode

    var savedScrollY = 0

    override fun onItemPressed(item: TappableHeadlineItem) = when (item) {
        is TappableHeadlineItem.ThemeMode -> DarkThemeDialog().show()
    }
}
