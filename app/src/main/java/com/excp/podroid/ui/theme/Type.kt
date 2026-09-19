package com.excp.podroid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Build a Material3 Typography that uses Inter as the base UI font family.
 * Called from PodroidTheme — must be @Composable so we can resolve the
 * asset-backed Inter family via LocalContext (see PodroidTokens.ui()).
 */
@Composable @ReadOnlyComposable
fun buildPodroidTypography(): Typography {
    val ui = PodroidTokens.ui()
    // Default M3 slots, carried forward only to pick up their fontFamily so
    // the app cannot drift to Roboto the first time a screen reaches for a
    // slot we haven't hand-tuned yet.
    val defaults = Typography()
    return Typography(
        displayLarge = TextStyle(
            fontFamily    = ui,
            fontWeight    = FontWeight.Thin,
            fontSize      = PodroidTokens.TypeSize.Display,
            letterSpacing = (-0.02).sp,
            lineHeight    = 34.sp,
        ),
        displayMedium = defaults.displayMedium.copy(fontFamily = ui),
        displaySmall  = defaults.displaySmall.copy(fontFamily = ui),
        headlineLarge  = defaults.headlineLarge.copy(fontFamily = ui),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = ui),
        headlineSmall = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.SemiBold,
            fontSize   = PodroidTokens.TypeSize.Headline,
            lineHeight = 26.sp,
        ),
        titleLarge  = defaults.titleLarge.copy(fontFamily = ui),
        titleMedium = TextStyle(
            fontFamily    = ui,
            fontWeight    = FontWeight.Normal,
            fontSize      = PodroidTokens.TypeSize.Title,
            letterSpacing = (-0.005).sp,
        ),
        titleSmall = defaults.titleSmall.copy(fontFamily = ui),
        bodyLarge  = defaults.bodyLarge.copy(fontFamily = ui),
        bodyMedium = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.Normal,
            fontSize   = PodroidTokens.TypeSize.Body,
            lineHeight = 18.sp,
        ),
        bodySmall = defaults.bodySmall.copy(fontFamily = ui),
        labelLarge = defaults.labelLarge.copy(fontFamily = ui),
        labelMedium = TextStyle(
            fontFamily    = ui,
            fontWeight    = FontWeight.Normal,
            fontSize      = PodroidTokens.TypeSize.Label,
            letterSpacing = 1.4.sp,
        ),
        labelSmall = defaults.labelSmall.copy(fontFamily = ui),
    )
}