package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

/**
 * Window insets in dp, as the module WebUI sees them.
 *
 * A page opts into edge-to-edge by importing `internal/insets.css`; from then
 * on the values below are refreshed on every inset change and pushed to the
 * page with [js].
 */
data class Insets(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
) {
    val css
        get() = buildString {
            appendLine(":root {")
            appendLine("\t--safe-area-inset-top: ${top}px;")
            appendLine("\t--safe-area-inset-right: ${right}px;")
            appendLine("\t--safe-area-inset-bottom: ${bottom}px;")
            appendLine("\t--safe-area-inset-left: ${left}px;")
            appendLine("\t--window-inset-top: var(--safe-area-inset-top, 0px);")
            appendLine("\t--window-inset-bottom: var(--safe-area-inset-bottom, 0px);")
            appendLine("\t--window-inset-left: var(--safe-area-inset-left, 0px);")
            appendLine("\t--window-inset-right: var(--safe-area-inset-right, 0px);")
            appendLine("\t--f7-safe-area-top: var(--window-inset-top, 0px) !important;")
            appendLine("\t--f7-safe-area-bottom: var(--window-inset-bottom, 0px) !important;")
            appendLine("\t--f7-safe-area-left: var(--window-inset-left, 0px) !important;")
            appendLine("\t--f7-safe-area-right: var(--window-inset-right, 0px) !important;")
            append("}")
        }

    val js
        get() = buildString {
            append("(function() {")
            append(" var s = document.documentElement.style;")
            append(" s.setProperty('--safe-area-inset-top', '${top}px');")
            append(" s.setProperty('--safe-area-inset-right', '${right}px');")
            append(" s.setProperty('--safe-area-inset-bottom', '${bottom}px');")
            append(" s.setProperty('--safe-area-inset-left', '${left}px');")
            append("})();")
        }
}
