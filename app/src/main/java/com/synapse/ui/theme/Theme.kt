package com.synapse.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// =============================================================================
// Synapse Brand Colors
// =============================================================================

// Primary - A warm purple that represents creativity and knowledge
private val SynapsePrimary = Color(0xFF7B5EAE)
private val SynapseOnPrimary = Color(0xFFFFFFFF)
private val SynapsePrimaryContainer = Color(0xFFEBDDFF)
private val SynapseOnPrimaryContainer = Color(0xFF270057)

// Secondary - A complementary teal for accents
private val SynapseSecondary = Color(0xFF625B71)
private val SynapseOnSecondary = Color(0xFFFFFFFF)
private val SynapseSecondaryContainer = Color(0xFFE8DEF8)
private val SynapseOnSecondaryContainer = Color(0xFF1D192B)

// Tertiary - A warm coral for highlights
private val SynapseTertiary = Color(0xFF7D5260)
private val SynapseOnTertiary = Color(0xFFFFFFFF)
private val SynapseTertiaryContainer = Color(0xFFFFD8E4)
private val SynapseOnTertiaryContainer = Color(0xFF31111D)

// Error colors
private val SynapseError = Color(0xFFBA1A1A)
private val SynapseOnError = Color(0xFFFFFFFF)
private val SynapseErrorContainer = Color(0xFFFFDAD6)
private val SynapseOnErrorContainer = Color(0xFF410002)

// Neutral colors - Light theme
private val SynapseBackground = Color(0xFFFFFBFF)
private val SynapseOnBackground = Color(0xFF1C1B1F)
private val SynapseSurface = Color(0xFFFFFBFF)
private val SynapseOnSurface = Color(0xFF1C1B1F)
private val SynapseSurfaceVariant = Color(0xFFE7E0EC)
private val SynapseOnSurfaceVariant = Color(0xFF49454F)
private val SynapseOutline = Color(0xFF79747E)
private val SynapseOutlineVariant = Color(0xFFCAC4D0)
private val SynapseInverseSurface = Color(0xFF313033)
private val SynapseInverseOnSurface = Color(0xFFF4EFF4)
private val SynapseInversePrimary = Color(0xFFD0BCFF)

// =============================================================================
// Dark Theme Colors
// =============================================================================

// Primary - Lighter purple for dark backgrounds
private val SynapsePrimaryDark = Color(0xFFD0BCFF)
private val SynapseOnPrimaryDark = Color(0xFF3E1F6E)
private val SynapsePrimaryContainerDark = Color(0xFF5A3D8A)
private val SynapseOnPrimaryContainerDark = Color(0xFFEBDDFF)

// Secondary
private val SynapseSecondaryDark = Color(0xFFCCC2DC)
private val SynapseOnSecondaryDark = Color(0xFF332D41)
private val SynapseSecondaryContainerDark = Color(0xFF4A4458)
private val SynapseOnSecondaryContainerDark = Color(0xFFE8DEF8)

// Tertiary
private val SynapseTertiaryDark = Color(0xFFEFB8C8)
private val SynapseOnTertiaryDark = Color(0xFF492532)
private val SynapseTertiaryContainerDark = Color(0xFF633B48)
private val SynapseOnTertiaryContainerDark = Color(0xFFFFD8E4)

// Error colors - Dark
private val SynapseErrorDark = Color(0xFFFFB4AB)
private val SynapseOnErrorDark = Color(0xFF690005)
private val SynapseErrorContainerDark = Color(0xFF93000A)
private val SynapseOnErrorContainerDark = Color(0xFFFFDAD6)

// Neutral colors - Dark theme
private val SynapseBackgroundDark = Color(0xFF1C1B1F)
private val SynapseOnBackgroundDark = Color(0xFFE6E1E5)
private val SynapseSurfaceDark = Color(0xFF1C1B1F)
private val SynapseOnSurfaceDark = Color(0xFFE6E1E5)
private val SynapseSurfaceVariantDark = Color(0xFF49454F)
private val SynapseOnSurfaceVariantDark = Color(0xFFCAC4D0)
private val SynapseOutlineDark = Color(0xFF938F99)
private val SynapseOutlineVariantDark = Color(0xFF49454F)
private val SynapseInverseSurfaceDark = Color(0xFFE6E1E5)
private val SynapseInverseOnSurfaceDark = Color(0xFF313033)
private val SynapseInversePrimaryDark = Color(0xFF7B5EAE)

// Scrim color for overlays
private val SynapseScrim = Color(0xFF000000)

// =============================================================================
// Color Schemes
// =============================================================================

private val LightColorScheme = lightColorScheme(
    primary = SynapsePrimary,
    onPrimary = SynapseOnPrimary,
    primaryContainer = SynapsePrimaryContainer,
    onPrimaryContainer = SynapseOnPrimaryContainer,
    secondary = SynapseSecondary,
    onSecondary = SynapseOnSecondary,
    secondaryContainer = SynapseSecondaryContainer,
    onSecondaryContainer = SynapseOnSecondaryContainer,
    tertiary = SynapseTertiary,
    onTertiary = SynapseOnTertiary,
    tertiaryContainer = SynapseTertiaryContainer,
    onTertiaryContainer = SynapseOnTertiaryContainer,
    error = SynapseError,
    onError = SynapseOnError,
    errorContainer = SynapseErrorContainer,
    onErrorContainer = SynapseOnErrorContainer,
    background = SynapseBackground,
    onBackground = SynapseOnBackground,
    surface = SynapseSurface,
    onSurface = SynapseOnSurface,
    surfaceVariant = SynapseSurfaceVariant,
    onSurfaceVariant = SynapseOnSurfaceVariant,
    outline = SynapseOutline,
    outlineVariant = SynapseOutlineVariant,
    inverseSurface = SynapseInverseSurface,
    inverseOnSurface = SynapseInverseOnSurface,
    inversePrimary = SynapseInversePrimary,
    scrim = SynapseScrim
)

private val DarkColorScheme = darkColorScheme(
    primary = SynapsePrimaryDark,
    onPrimary = SynapseOnPrimaryDark,
    primaryContainer = SynapsePrimaryContainerDark,
    onPrimaryContainer = SynapseOnPrimaryContainerDark,
    secondary = SynapseSecondaryDark,
    onSecondary = SynapseOnSecondaryDark,
    secondaryContainer = SynapseSecondaryContainerDark,
    onSecondaryContainer = SynapseOnSecondaryContainerDark,
    tertiary = SynapseTertiaryDark,
    onTertiary = SynapseOnTertiaryDark,
    tertiaryContainer = SynapseTertiaryContainerDark,
    onTertiaryContainer = SynapseOnTertiaryContainerDark,
    error = SynapseErrorDark,
    onError = SynapseOnErrorDark,
    errorContainer = SynapseErrorContainerDark,
    onErrorContainer = SynapseOnErrorContainerDark,
    background = SynapseBackgroundDark,
    onBackground = SynapseOnBackgroundDark,
    surface = SynapseSurfaceDark,
    onSurface = SynapseOnSurfaceDark,
    surfaceVariant = SynapseSurfaceVariantDark,
    onSurfaceVariant = SynapseOnSurfaceVariantDark,
    outline = SynapseOutlineDark,
    outlineVariant = SynapseOutlineVariantDark,
    inverseSurface = SynapseInverseSurfaceDark,
    inverseOnSurface = SynapseInverseOnSurfaceDark,
    inversePrimary = SynapseInversePrimaryDark,
    scrim = SynapseScrim
)

// =============================================================================
// Theme Composable
// =============================================================================

/**
 * Synapse app theme.
 *
 * Uses Material 3 theming with support for:
 * - Light and dark mode (follows system by default)
 * - Dynamic color on Android 12+ (optional)
 * - Edge-to-edge display with proper status bar coloring
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param dynamicColor Whether to use Android 12+ dynamic colors. Defaults to true.
 * @param content The composable content to apply the theme to.
 */
@Composable
fun SynapseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Use dynamic color on Android 12+ if enabled
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Use our custom dark color scheme
        darkTheme -> DarkColorScheme
        // Use our custom light color scheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // Only modify status bar when running in an Activity context
        val activity = view.context as? Activity
        if (activity != null) {
            SideEffect {
                val window = activity.window
                // Make status bar transparent for edge-to-edge
                window.statusBarColor = Color.Transparent.toArgb()
                // Set status bar icon color based on theme
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// =============================================================================
// Extended Color Properties
// =============================================================================

/**
 * Extended color scheme properties for Synapse-specific colors.
 * These can be accessed via MaterialTheme.colorScheme.extended()
 */
object SynapseColors {
    // Capture overlay colors
    val overlayBackground = Color(0x80000000)
    val strokeColor = Color(0xFF7B5EAE)
    val strokeColorDark = Color(0xFFD0BCFF)

    // Status colors
    val successLight = Color(0xFF2E7D32)
    val successDark = Color(0xFF81C784)
    val warningLight = Color(0xFFF57C00)
    val warningDark = Color(0xFFFFB74D)
    val infoLight = Color(0xFF1976D2)
    val infoDark = Color(0xFF64B5F6)

    // Sync status colors
    val syncPending = Color(0xFFFFA726)
    val syncInProgress = Color(0xFF42A5F5)
    val syncSuccess = Color(0xFF66BB6A)
    val syncError = Color(0xFFEF5350)

    /**
     * Get the appropriate stroke color for the current theme.
     */
    @Composable
    fun strokeColor(): Color {
        return if (isSystemInDarkTheme()) strokeColorDark else strokeColor
    }

    /**
     * Get success color for the current theme.
     */
    @Composable
    fun success(): Color {
        return if (isSystemInDarkTheme()) successDark else successLight
    }

    /**
     * Get warning color for the current theme.
     */
    @Composable
    fun warning(): Color {
        return if (isSystemInDarkTheme()) warningDark else warningLight
    }

    /**
     * Get info color for the current theme.
     */
    @Composable
    fun info(): Color {
        return if (isSystemInDarkTheme()) infoDark else infoLight
    }
}
