package com.topjohnwu.magisk.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.model.ColorMode
import kotlin.math.abs

// The "Golden Earth" palette: six anchors (golden yellow #EAB308,
// ochre #B8942A, bronze #857239, earth brown #524A33, dark umber
// #33312A, warm cream #F5F0E6) mapped onto the Material 3 roles. Gold
// cannot carry white text, so onPrimary is the dark umber in both modes;
// the cream is the light background, the umber families the dark one.
private val TanukiLight = lightColorScheme(
    primary = Color(0xFFEAB308),
    onPrimary = Color(0xFF33312A),
    primaryContainer = Color(0xFFFFE08A),
    onPrimaryContainer = Color(0xFF33312A),
    inversePrimary = Color(0xFFB8942A),
    secondary = Color(0xFF857239),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9D9B0),
    onSecondaryContainer = Color(0xFF33312A),
    tertiary = Color(0xFFB8942A),
    onTertiary = Color(0xFF33312A),
    tertiaryContainer = Color(0xFFE9D9B0),
    onTertiaryContainer = Color(0xFF33312A),
    background = Color(0xFFF5F0E6),
    onBackground = Color(0xFF33312A),
    surface = Color(0xFFF5F0E6),
    onSurface = Color(0xFF33312A),
    surfaceVariant = Color(0xFFE8E0D2),
    onSurfaceVariant = Color(0xFF524A33),
    surfaceTint = Color(0xFFEAB308),
    inverseSurface = Color(0xFF33312A),
    inverseOnSurface = Color(0xFFF5F0E6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF857239),
    outlineVariant = Color(0xFFD1C7B7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFAF1),
    surfaceDim = Color(0xFFE2D9C9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0E9DC),
    surfaceContainer = Color(0xFFEAE2D4),
    surfaceContainerHigh = Color(0xFFE4DCCD),
    surfaceContainerHighest = Color(0xFFDED5C6),
)

private val TanukiDark = darkColorScheme(
    primary = Color(0xFFEAB308),
    onPrimary = Color(0xFF33312A),
    primaryContainer = Color(0xFF5B4300),
    onPrimaryContainer = Color(0xFFFFE08A),
    inversePrimary = Color(0xFFB8942A),
    secondary = Color(0xFFB8942A),
    onSecondary = Color(0xFF33312A),
    secondaryContainer = Color(0xFF524A33),
    onSecondaryContainer = Color(0xFFF5F0E6),
    tertiary = Color(0xFFB8942A),
    onTertiary = Color(0xFF33312A),
    tertiaryContainer = Color(0xFF524A33),
    onTertiaryContainer = Color(0xFFF5F0E6),
    background = Color(0xFF33312A),
    onBackground = Color(0xFFF5F0E6),
    surface = Color(0xFF33312A),
    onSurface = Color(0xFFF5F0E6),
    surfaceVariant = Color(0xFF524A33),
    onSurfaceVariant = Color(0xFFE4DCCD),
    surfaceTint = Color(0xFFEAB308),
    inverseSurface = Color(0xFFF5F0E6),
    inverseOnSurface = Color(0xFF33312A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFB8942A),
    outlineVariant = Color(0xFF524A33),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF504E46),
    surfaceDim = Color(0xFF2A2821),
    surfaceContainerLowest = Color(0xFF28261F),
    surfaceContainerLow = Color(0xFF393730),
    surfaceContainer = Color(0xFF403E36),
    surfaceContainerHigh = Color(0xFF44423B),
    surfaceContainerHighest = Color(0xFF4B4942),
)

// Module Remove/Restore button: colorRemove maps to ?colorSecondary, so
// the static themes use secondary/onSecondary (#857239/#FFFFFF 4.70:1
// light, #B8942A/#33312A 4.53:1 dark, both AA). Material You shifts one
// tone off its secondaryContainer. The Module tab replaces the dark
// static pair with the container pair in moduleRemoveColors.
private val AlertMyShift = 0.15f

private fun shiftTone(base: Color, shift: Float): Color =
    lerp(base, if (shift >= 0) Color.White else Color.Black, abs(shift).coerceIn(0f, 1f))

fun removeColors(colorScheme: ColorScheme, isDarkTheme: Boolean): Pair<Color, Color> =
    if (ThemeState.isDynamicActive)
        shiftTone(colorScheme.secondaryContainer, if (isDarkTheme) AlertMyShift else -AlertMyShift) to colorScheme.onSecondaryContainer
    else colorScheme.secondary to colorScheme.onSecondary

// Tonal buttons (Action/WebUI, Patreon/PayPal, Reinstall):
// the secondaryContainer role pair.
fun actionColors(colorScheme: ColorScheme, isDarkTheme: Boolean): Pair<Color, Color> =
    colorScheme.secondaryContainer to colorScheme.onSecondaryContainer

// The module tab flips these two pairs when dynamic color is off and the
// theme is dark: Action/WebUI take the golden secondary pair, and
// Remove/Restore take the container pair. Dynamic color and light mode
// keep the shared defaults.
fun moduleActionColors(colorScheme: ColorScheme, isDarkTheme: Boolean): Pair<Color, Color> =
    if (isDarkTheme && !ThemeState.dynamicColor)
        colorScheme.secondary to colorScheme.onSecondary
    else actionColors(colorScheme, isDarkTheme)

// The other half of the swap above: Remove/Restore take the
// secondaryContainer pair in the dark static theme.
fun moduleRemoveColors(colorScheme: ColorScheme, isDarkTheme: Boolean): Pair<Color, Color> =
    if (isDarkTheme && !ThemeState.dynamicColor)
        colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
    else removeColors(colorScheme, isDarkTheme)

// The module Update button and the Home notice card:
// the tertiaryContainer role pair.
fun updateColors(colorScheme: ColorScheme, isDarkTheme: Boolean): Pair<Color, Color> =
    colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer

// The (+) FABs: the primaryContainer role pair.
fun fabColors(colorScheme: ColorScheme, isDarkTheme: Boolean): Pair<Color, Color> =
    colorScheme.primaryContainer to colorScheme.onPrimaryContainer

// The granted/denied badges of the root-access log tab: the
// primaryContainer / errorContainer role pairs.
fun badgeColors(
    colorScheme: ColorScheme,
    isDarkTheme: Boolean,
    allowed: Boolean
): Pair<Color, Color> =
    if (allowed) {
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer
    } else {
        colorScheme.errorContainer to colorScheme.onErrorContainer
    }

object ThemeState {
    var colorMode by mutableIntStateOf(Config.colorMode)
    var dynamicColor by mutableStateOf(Config.dynamicColor)
    val isDynamicActive get() = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

// Same values as androidx.activity's internal DefaultLightScrim / DefaultDarkScrim
private val LightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Enable edge-to-edge with system bar appearance derived from the app's color mode
 * instead of the system's night mode.
 */
fun ComponentActivity.applySystemBarStyle() {
    val isDark = { res: Resources ->
        val nightMode = res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ColorMode.fromValue(ThemeState.colorMode).isDark(nightMode == Configuration.UI_MODE_NIGHT_YES)
    }
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, isDark
        ),
        navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim, isDark),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

val MagiskShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val MagiskTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Suppress("DEPRECATION")
@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val mode = ColorMode.fromValue(ThemeState.colorMode)
    val isDarkTheme = mode.isDark(isSystemInDarkTheme())

    val colorScheme = when {
        ThemeState.isDynamicActive && isDarkTheme -> dynamicDarkColorScheme(LocalContext.current)
        ThemeState.isDynamicActive -> dynamicLightColorScheme(LocalContext.current)
        isDarkTheme -> TanukiDark
        else -> TanukiLight
    }

    val activity = LocalActivity.current as? ComponentActivity
    if (activity != null) {
        LaunchedEffect(activity, isDarkTheme) {
            activity.applySystemBarStyle()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MagiskShapes,
        typography = MagiskTypography,
        content = content
    )
}
