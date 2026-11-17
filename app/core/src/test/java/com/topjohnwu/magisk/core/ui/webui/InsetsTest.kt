package com.topjohnwu.magisk.core.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Test

class InsetsTest {

    @Test
    fun css_hasTheReferenceLayout() {
        val expected = listOf(
            ":root {",
            "\t--safe-area-inset-top: 24px;",
            "\t--safe-area-inset-right: 16px;",
            "\t--safe-area-inset-bottom: 48px;",
            "\t--safe-area-inset-left: 16px;",
            "\t--window-inset-top: var(--safe-area-inset-top, 0px);",
            "\t--window-inset-bottom: var(--safe-area-inset-bottom, 0px);",
            "\t--window-inset-left: var(--safe-area-inset-left, 0px);",
            "\t--window-inset-right: var(--safe-area-inset-right, 0px);",
            "\t--f7-safe-area-top: var(--window-inset-top, 0px) !important;",
            "\t--f7-safe-area-bottom: var(--window-inset-bottom, 0px) !important;",
            "\t--f7-safe-area-left: var(--window-inset-left, 0px) !important;",
            "\t--f7-safe-area-right: var(--window-inset-right, 0px) !important;",
            "}"
        ).joinToString("\n")
        assertEquals(expected, Insets(24, 48, 16, 16).css)
    }

    @Test
    fun css_ofAnEmptyInsetIsStillATypedRule() {
        assertEquals(
            ":root {\n\t--safe-area-inset-top: 0px;\n\t--safe-area-inset-right: 0px;\n" +
                "\t--safe-area-inset-bottom: 0px;\n\t--safe-area-inset-left: 0px;\n" +
                "\t--window-inset-top: var(--safe-area-inset-top, 0px);\n" +
                "\t--window-inset-bottom: var(--safe-area-inset-bottom, 0px);\n" +
                "\t--window-inset-left: var(--safe-area-inset-left, 0px);\n" +
                "\t--window-inset-right: var(--safe-area-inset-right, 0px);\n" +
                "\t--f7-safe-area-top: var(--window-inset-top, 0px) !important;\n" +
                "\t--f7-safe-area-bottom: var(--window-inset-bottom, 0px) !important;\n" +
                "\t--f7-safe-area-left: var(--window-inset-left, 0px) !important;\n" +
                "\t--f7-safe-area-right: var(--window-inset-right, 0px) !important;\n}",
            Insets(0, 0, 0, 0).css
        )
    }

    @Test
    fun js_setsTheFourSafeAreaProperties() {
        assertEquals(
            "(function() { var s = document.documentElement.style;" +
                " s.setProperty('--safe-area-inset-top', '24px');" +
                " s.setProperty('--safe-area-inset-right', '16px');" +
                " s.setProperty('--safe-area-inset-bottom', '48px');" +
                " s.setProperty('--safe-area-inset-left', '16px');})();",
            Insets(24, 48, 16, 16).js
        )
    }
}
