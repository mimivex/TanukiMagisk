package com.topjohnwu.magisk.core.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUIColorsTest {

    private val lightCss = listOf(
        ":root {",
        "  --primary: #6750A4FF;",
        "  --onPrimary: #FFFFFFFF;",
        "  --primaryContainer: #EADDFFFF;",
        "  --onPrimaryContainer: #21005DFF;",
        "  --inversePrimary: #D0BCFFFF;",
        "  --secondary: #625B71FF;",
        "  --onSecondary: #FFFFFFFF;",
        "  --secondaryContainer: #E8DEF8FF;",
        "  --onSecondaryContainer: #1D192BFF;",
        "  --tertiary: #7D5260FF;",
        "  --onTertiary: #FFFFFFFF;",
        "  --tertiaryContainer: #FFD8E4FF;",
        "  --onTertiaryContainer: #31111DFF;",
        "  --background: #FEF7FFFF;",
        "  --onBackground: #1D1B20FF;",
        "  --surface: #FEF7FFFF;",
        "  --tonalSurface: #F3EDF7FF;",
        "  --onSurface: #1D1B20FF;",
        "  --surfaceVariant: #E7E0ECFF;",
        "  --onSurfaceVariant: #49454FFF;",
        "  --surfaceTint: #6750A4FF;",
        "  --inverseSurface: #313033FF;",
        "  --inverseOnSurface: #F4EFF4FF;",
        "  --error: #410E0BFF;",
        "  --onError: #FFFFFFFF;",
        "  --errorContainer: #F9DEDCFF;",
        "  --onErrorContainer: #410E0BFF;",
        "  --outline: #79747EFF;",
        "  --outlineVariant: #CAC4D0FF;",
        "  --scrim: #6750A4FF;",
        "  --surfaceBright: #FEF7FFFF;",
        "  --surfaceDim: #DED8E1FF;",
        "  --surfaceContainer: #F3EDF7FF;",
        "  --surfaceContainerHigh: #ECE6F0FF;",
        "  --surfaceContainerHighest: #E6E0E9FF;",
        "  --surfaceContainerLow: #F7F2FAFF;",
        "  --surfaceContainerLowest: #FFFFFFFF;",
        "  --filledTonalButtonContentColor: #21005DFF;",
        "  --filledTonalButtonContainerColor: #E8DEF8FF;",
        "  --filledTonalButtonDisabledContentColor: #49454FFF;",
        "  --filledTonalButtonDisabledContainerColor: #E7E0ECFF;",
        "  --filledCardContentColor: #21005DFF;",
        "  --filledCardContainerColor: #EADDFFFF;",
        "  --filledCardDisabledContentColor: #49454FFF;",
        "  --filledCardDisabledContainerColor: #E7E0ECFF;",
        "}"
    ).joinToString("\n") + "\n"

    private val darkCss = listOf(
        ":root {",
        "  --primary: #D0BCFFFF;",
        "  --onPrimary: #381E72FF;",
        "  --primaryContainer: #4F378BFF;",
        "  --onPrimaryContainer: #EADDFFFF;",
        "  --inversePrimary: #6750A4FF;",
        "  --secondary: #CCC2DCFF;",
        "  --onSecondary: #332D41FF;",
        "  --secondaryContainer: #4A4458FF;",
        "  --onSecondaryContainer: #E8DEF8FF;",
        "  --tertiary: #EFB8C8FF;",
        "  --onTertiary: #492532FF;",
        "  --tertiaryContainer: #633B48FF;",
        "  --onTertiaryContainer: #FFD8E4FF;",
        "  --background: #141218FF;",
        "  --onBackground: #E6E0E9FF;",
        "  --surface: #141218FF;",
        "  --tonalSurface: #211F26FF;",
        "  --onSurface: #E6E0E9FF;",
        "  --surfaceVariant: #49454FFF;",
        "  --onSurfaceVariant: #CAC4D0FF;",
        "  --surfaceTint: #D0BCFFFF;",
        "  --inverseSurface: #E6E0E9FF;",
        "  --inverseOnSurface: #313033FF;",
        "  --error: #F9DEDCFF;",
        "  --onError: #601410FF;",
        "  --errorContainer: #8C1D18FF;",
        "  --onErrorContainer: #F9DEDCFF;",
        "  --outline: #938F99FF;",
        "  --outlineVariant: #49454FFF;",
        "  --scrim: #D0BCFFFF;",
        "  --surfaceBright: #3B383EFF;",
        "  --surfaceDim: #141218FF;",
        "  --surfaceContainer: #211F26FF;",
        "  --surfaceContainerHigh: #2B2930FF;",
        "  --surfaceContainerHighest: #36343BFF;",
        "  --surfaceContainerLow: #1D1B20FF;",
        "  --surfaceContainerLowest: #0F0D13FF;",
        "  --filledTonalButtonContentColor: #EADDFFFF;",
        "  --filledTonalButtonContainerColor: #4A4458FF;",
        "  --filledTonalButtonDisabledContentColor: #CAC4D0FF;",
        "  --filledTonalButtonDisabledContainerColor: #49454FFF;",
        "  --filledCardContentColor: #EADDFFFF;",
        "  --filledCardContainerColor: #4F378BFF;",
        "  --filledCardDisabledContentColor: #CAC4D0FF;",
        "  --filledCardDisabledContainerColor: #49454FFF;",
        "}"
    ).joinToString("\n") + "\n"

    @Test
    fun colorToCss_writesArgbAsRgba() {
        assertEquals("#6750A4FF", WebUIColors.colorToCss(0xFF6750A4.toInt()))
        assertEquals("#FFFFFF00", WebUIColors.colorToCss(0x00FFFFFF))
        assertEquals("#000000FF", WebUIColors.colorToCss(0xFF000000.toInt()))
    }

    @Test
    fun toCss_wrapsThePaletteInARootRule() {
        val palette = listOf("a" to 0xFF112233.toInt(), "b" to 0xFF445566.toInt())
        assertEquals(":root {\n  --a: #112233FF;\n  --b: #445566FF;\n}\n", WebUIColors.toCss(palette))
    }

    @Test
    fun baselinePalette_lightMatchesTheStaticMaterialPalette() {
        assertEquals(lightCss, WebUIColors.toCss(WebUIColors.baselinePalette(false)))
    }

    @Test
    fun baselinePalette_darkMatchesTheStaticMaterialPalette() {
        assertEquals(darkCss, WebUIColors.toCss(WebUIColors.baselinePalette(true)))
    }

    @Test
    fun baselinePalette_hasTheFortyFiveReferenceVariablesInOrder() {
        val names = WebUIColors.baselinePalette(false).map { it.first }
        assertEquals(45, names.size)
        assertEquals(
            listOf(
                "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
                "inversePrimary", "secondary", "onSecondary", "secondaryContainer",
                "onSecondaryContainer", "tertiary", "onTertiary", "tertiaryContainer",
                "onTertiaryContainer", "background", "onBackground", "surface",
                "tonalSurface", "onSurface", "surfaceVariant", "onSurfaceVariant",
                "surfaceTint", "inverseSurface", "inverseOnSurface", "error",
                "onError", "errorContainer", "onErrorContainer", "outline",
                "outlineVariant", "scrim", "surfaceBright", "surfaceDim",
                "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
                "surfaceContainerLow", "surfaceContainerLowest",
                "filledTonalButtonContentColor", "filledTonalButtonContainerColor",
                "filledTonalButtonDisabledContentColor", "filledTonalButtonDisabledContainerColor",
                "filledCardContentColor", "filledCardContainerColor",
                "filledCardDisabledContentColor", "filledCardDisabledContainerColor"
            ),
            names
        )
    }

    @Test
    fun baselinePalette_keepsTheReferenceRoleAliases() {
        val light = WebUIColors.baselinePalette(false).toMap()
        fun assertAlias(alias: String, target: String) {
            assertEquals(alias, light[target], light[alias])
        }
        assertAlias("background", "surface")
        assertAlias("error", "onErrorContainer")
        assertAlias("surfaceTint", "primary")
        assertAlias("scrim", "primary")
        assertAlias("filledTonalButtonContentColor", "onPrimaryContainer")
        assertAlias("filledTonalButtonContainerColor", "secondaryContainer")
        assertAlias("filledCardContainerColor", "primaryContainer")
        assertAlias("filledCardDisabledContainerColor", "surfaceVariant")
    }

    @Test
    fun frameworkColorName_asksForTheSystemDynamicRole() {
        val roles = WebUIColors.ROLES
        assertEquals("system_primary_light", WebUIColors.frameworkColorName(roles[0], false))
        assertEquals("system_primary_dark", WebUIColors.frameworkColorName(roles[0], true))
        assertEquals("system_on_error_container_light", WebUIColors.frameworkColorName(roles[23], false))
        assertEquals("system_surface_container_lowest_dark", WebUIColors.frameworkColorName(roles[36], true))
        assertTrue(roles.all { it.framework.matches(Regex("[a-z_]+")) })
    }
}
