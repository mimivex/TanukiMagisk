package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Synthesizes the `internal/colors.css` a module WebUI imports.
 *
 * The variables, their order and the roles they map to are the ones
 * KsuWebUIStandalone emits, so a page styled against that client renders the
 * same here. From Android 12 the values are the system's dynamic colors
 * (`android.R.color.system_<role>_light|dark`), which follow the wallpaper;
 * below that, or for a role the platform does not expose, the static
 * Material 3 palette is used instead.
 */
object WebUIColors {

    private const val ANDROID_PACKAGE = "android"

    internal class Role(
        val css: String,
        val framework: String,
        val light: Int,
        val dark: Int
    )

    internal val ROLES = listOf(
        Role("primary", "primary", 0xFF6750A4.toInt(), 0xFFD0BCFF.toInt()),
        Role("onPrimary", "on_primary", 0xFFFFFFFF.toInt(), 0xFF381E72.toInt()),
        Role("primaryContainer", "primary_container", 0xFFEADDFF.toInt(), 0xFF4F378B.toInt()),
        Role("onPrimaryContainer", "on_primary_container", 0xFF21005D.toInt(), 0xFFEADDFF.toInt()),
        Role("inversePrimary", "inverse_primary", 0xFFD0BCFF.toInt(), 0xFF6750A4.toInt()),
        Role("secondary", "secondary", 0xFF625B71.toInt(), 0xFFCCC2DC.toInt()),
        Role("onSecondary", "on_secondary", 0xFFFFFFFF.toInt(), 0xFF332D41.toInt()),
        Role("secondaryContainer", "secondary_container", 0xFFE8DEF8.toInt(), 0xFF4A4458.toInt()),
        Role("onSecondaryContainer", "on_secondary_container", 0xFF1D192B.toInt(), 0xFFE8DEF8.toInt()),
        Role("tertiary", "tertiary", 0xFF7D5260.toInt(), 0xFFEFB8C8.toInt()),
        Role("onTertiary", "on_tertiary", 0xFFFFFFFF.toInt(), 0xFF492532.toInt()),
        Role("tertiaryContainer", "tertiary_container", 0xFFFFD8E4.toInt(), 0xFF633B48.toInt()),
        Role("onTertiaryContainer", "on_tertiary_container", 0xFF31111D.toInt(), 0xFFFFD8E4.toInt()),
        Role("background", "surface", 0xFFFEF7FF.toInt(), 0xFF141218.toInt()),
        Role("onBackground", "on_background", 0xFF1D1B20.toInt(), 0xFFE6E0E9.toInt()),
        Role("surface", "surface", 0xFFFEF7FF.toInt(), 0xFF141218.toInt()),
        Role("tonalSurface", "surface_container", 0xFFF3EDF7.toInt(), 0xFF211F26.toInt()),
        Role("onSurface", "on_surface", 0xFF1D1B20.toInt(), 0xFFE6E0E9.toInt()),
        Role("surfaceVariant", "surface_variant", 0xFFE7E0EC.toInt(), 0xFF49454F.toInt()),
        Role("onSurfaceVariant", "on_surface_variant", 0xFF49454F.toInt(), 0xFFCAC4D0.toInt()),
        Role("surfaceTint", "primary", 0xFF6750A4.toInt(), 0xFFD0BCFF.toInt()),
        Role("inverseSurface", "inverse_surface", 0xFF313033.toInt(), 0xFFE6E0E9.toInt()),
        Role("inverseOnSurface", "inverse_on_surface", 0xFFF4EFF4.toInt(), 0xFF313033.toInt()),
        Role("error", "on_error_container", 0xFF410E0B.toInt(), 0xFFF9DEDC.toInt()),
        Role("onError", "on_error", 0xFFFFFFFF.toInt(), 0xFF601410.toInt()),
        Role("errorContainer", "error_container", 0xFFF9DEDC.toInt(), 0xFF8C1D18.toInt()),
        Role("onErrorContainer", "on_error_container", 0xFF410E0B.toInt(), 0xFFF9DEDC.toInt()),
        Role("outline", "outline", 0xFF79747E.toInt(), 0xFF938F99.toInt()),
        Role("outlineVariant", "outline_variant", 0xFFCAC4D0.toInt(), 0xFF49454F.toInt()),
        Role("scrim", "primary", 0xFF6750A4.toInt(), 0xFFD0BCFF.toInt()),
        Role("surfaceBright", "surface_bright", 0xFFFEF7FF.toInt(), 0xFF3B383E.toInt()),
        Role("surfaceDim", "surface_dim", 0xFFDED8E1.toInt(), 0xFF141218.toInt()),
        Role("surfaceContainer", "surface_container", 0xFFF3EDF7.toInt(), 0xFF211F26.toInt()),
        Role(
            "surfaceContainerHigh", "surface_container_high",
            0xFFECE6F0.toInt(), 0xFF2B2930.toInt()
        ),
        Role(
            "surfaceContainerHighest", "surface_container_highest",
            0xFFE6E0E9.toInt(), 0xFF36343B.toInt()
        ),
        Role(
            "surfaceContainerLow", "surface_container_low",
            0xFFF7F2FA.toInt(), 0xFF1D1B20.toInt()
        ),
        Role(
            "surfaceContainerLowest", "surface_container_lowest",
            0xFFFFFFFF.toInt(), 0xFF0F0D13.toInt()
        ),
        Role(
            "filledTonalButtonContentColor", "on_primary_container",
            0xFF21005D.toInt(), 0xFFEADDFF.toInt()
        ),
        Role(
            "filledTonalButtonContainerColor", "secondary_container",
            0xFFE8DEF8.toInt(), 0xFF4A4458.toInt()
        ),
        Role(
            "filledTonalButtonDisabledContentColor", "on_surface_variant",
            0xFF49454F.toInt(), 0xFFCAC4D0.toInt()
        ),
        Role(
            "filledTonalButtonDisabledContainerColor", "surface_variant",
            0xFFE7E0EC.toInt(), 0xFF49454F.toInt()
        ),
        Role(
            "filledCardContentColor", "on_primary_container",
            0xFF21005D.toInt(), 0xFFEADDFF.toInt()
        ),
        Role(
            "filledCardContainerColor", "primary_container",
            0xFFEADDFF.toInt(), 0xFF4F378B.toInt()
        ),
        Role(
            "filledCardDisabledContentColor", "on_surface_variant",
            0xFF49454F.toInt(), 0xFFCAC4D0.toInt()
        ),
        Role(
            "filledCardDisabledContainerColor", "surface_variant",
            0xFFE7E0EC.toInt(), 0xFF49454F.toInt()
        )
    )

    /** The platform color a [role] reads in the current night mode. */
    internal fun frameworkColorName(role: Role, night: Boolean): String =
        "system_${role.framework}_${if (night) "dark" else "light"}"

    fun synthesizeCss(context: Context): String {
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val palette = ArrayList<Pair<String, Int>>(ROLES.size)
        for (role in ROLES) {
            palette.add(role.css to resolve(context, role, night, dynamic))
        }
        return toCss(palette)
    }

    private fun resolve(context: Context, role: Role, night: Boolean, dynamic: Boolean): Int {
        val fallback = if (night) role.dark else role.light
        if (!dynamic) {
            return fallback
        }
        val id = context.resources.getIdentifier(
            frameworkColorName(role, night), "color", ANDROID_PACKAGE
        )
        if (id == 0) {
            return fallback
        }
        return runCatching { context.getColor(id) }.getOrDefault(fallback)
    }

    /** The static Material 3 palette, in the order [synthesizeCss] emits it. */
    fun baselinePalette(night: Boolean): List<Pair<String, Int>> =
        ROLES.map { it.css to (if (night) it.dark else it.light) }

    fun colorToCss(color: Int): String = String.format(
        Locale.ROOT, "#%06X%02X", color and 0xFFFFFF, (color ushr 24) and 0xFF
    )

    fun toCss(palette: List<Pair<String, Int>>): String = buildString {
        append(":root {\n")
        for ((name, color) in palette) {
            append("  --$name: ${colorToCss(color)};\n")
        }
        append("}\n")
    }
}
