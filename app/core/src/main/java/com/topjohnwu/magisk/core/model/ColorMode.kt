package com.topjohnwu.magisk.core.model

enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun fromValue(value: Int): ColorMode = when (value) {
            1, 4 -> LIGHT
            2, 5 -> DARK
            else -> SYSTEM
        }
    }
}
