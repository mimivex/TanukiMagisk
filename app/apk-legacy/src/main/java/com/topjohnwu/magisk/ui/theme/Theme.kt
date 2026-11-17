package com.topjohnwu.magisk.ui.theme

import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config

enum class Theme(
    val themeName: String,
    val themeRes: Int
) {

    Tanuki(
        themeName = "Tanuki",
        themeRes = R.style.ThemeFoundationMD2_Tanuki
    ),
    PiplupAmoled(
        themeName = "AMOLED",
        themeRes = R.style.ThemeFoundationMD2_Amoled
    ),
    Rayquaza(
        themeName = "Rayquaza",
        themeRes = R.style.ThemeFoundationMD2_Rayquaza
    ),
    Zapdos(
        themeName = "Zapdos",
        themeRes = R.style.ThemeFoundationMD2_Zapdos
    ),
    Charmeleon(
        themeName = "Charmeleon",
        themeRes = R.style.ThemeFoundationMD2_Charmeleon
    ),
    Mew(
        themeName = "Mew",
        themeRes = R.style.ThemeFoundationMD2_Mew
    ),
    Salamence(
        themeName = "Salamence",
        themeRes = R.style.ThemeFoundationMD2_Salamence
    ),
    Fraxure(
        themeName = "Fraxure (Legacy)",
        themeRes = R.style.ThemeFoundationMD2_Fraxure
    ),
    Piplup(
        themeName = "Piplup",
        themeRes = R.style.ThemeFoundationMD2_Piplup
    ),
    Kitsune(
        themeName = "Kitsune",
        themeRes = R.style.ThemeFoundationMD2_Kitsune
    );

    val isSelected get() = this == selected

    companion object {
        val selected get() = values().getOrNull(Config.themeOrdinal) ?: Tanuki
    }

}

fun interface ThemeClickListener {
    fun onThemeClick(theme: Theme)
}
