// Adapted from SukiSU-Nova 1439545 (GPL-3.0); see THIRD_PARTY.md.
package com.maplesu.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import top.yukonga.miuix.kmp.theme.Colors

private fun JSONObject.themeColor(key: String): Color? {
    val hex = optString(key).removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color((if (hex.length == 6) value or 0xff000000L else value).toInt())
}

/**
 * Applies the semantic overlay to the generated Miuix palette. Only provided tokens are
 * replaced, so an overlay that fixes secondary text keeps the rest of the generated scheme.
 *
 * Keep this mapping in one place: Miuix role names are library details and may move between
 * releases, while the JSON token names are part of the theme package format.
 */
fun Colors.withSukisuColors(document: JSONObject): Colors {
    val tokens = document.optJSONObject("sukisu.colors") ?: return this
    val accent = tokens.themeColor("accent")
    val onAccent = tokens.themeColor("onAccent")
    val textPrimary = tokens.themeColor("textPrimary")
    val textSecondary = tokens.themeColor("textSecondary")
    val textDisabled = tokens.themeColor("textDisabled")
    val surfaceColor = tokens.themeColor("surface")
    val card = tokens.themeColor("card")
    val outlineColor = tokens.themeColor("outline")
    val errorColor = tokens.themeColor("error")

    return copy(
        primary = accent ?: primary,
        primaryVariant = accent ?: primaryVariant,
        onPrimary = onAccent ?: onPrimary,
        onPrimaryVariant = onAccent ?: onPrimaryVariant,
        error = errorColor ?: error,
        onBackground = textPrimary ?: onBackground,
        onBackgroundVariant = textSecondary ?: onBackgroundVariant,
        onSurface = textPrimary ?: onSurface,
        onSurfaceSecondary = textPrimary ?: onSurfaceSecondary,
        onSurfaceContainer = textPrimary ?: onSurfaceContainer,
        onSurfaceContainerVariant = textPrimary ?: onSurfaceContainerVariant,
        onSurfaceContainerHigh = textPrimary ?: onSurfaceContainerHigh,
        onSurfaceContainerHighest = textPrimary ?: onSurfaceContainerHighest,
        onSurfaceVariantSummary = textSecondary ?: onSurfaceVariantSummary,
        onSurfaceVariantActions = textSecondary ?: onSurfaceVariantActions,
        disabledOnSurface = textDisabled ?: disabledOnSurface,
        disabledOnSecondary = textDisabled ?: disabledOnSecondary,
        disabledOnSecondaryVariant = textDisabled ?: disabledOnSecondaryVariant,
        surface = surfaceColor ?: surface,
        background = surfaceColor ?: background,
        surfaceContainer = card ?: surfaceContainer,
        surfaceContainerHigh = card ?: surfaceContainerHigh,
        surfaceContainerHighest = card ?: surfaceContainerHighest,
        outline = outlineColor ?: outline,
        dividerLine = outlineColor ?: dividerLine,
    )
}

/**
 * The Material 3 counterpart. Material has no disabled text role (components lower the alpha
 * themselves), so `textDisabled` intentionally has no Material mapping.
 */
fun ColorScheme.withSukisuColors(document: JSONObject): ColorScheme {
    val tokens = document.optJSONObject("sukisu.colors") ?: return this
    val accent = tokens.themeColor("accent")
    val onAccent = tokens.themeColor("onAccent")
    val textPrimary = tokens.themeColor("textPrimary")
    val textSecondary = tokens.themeColor("textSecondary")
    val surfaceColor = tokens.themeColor("surface")
    val card = tokens.themeColor("card")
    val outlineColor = tokens.themeColor("outline")
    val errorColor = tokens.themeColor("error")

    return copy(
        primary = accent ?: primary,
        onPrimary = onAccent ?: onPrimary,
        error = errorColor ?: error,
        onBackground = textPrimary ?: onBackground,
        onSurface = textPrimary ?: onSurface,
        onSurfaceVariant = textSecondary ?: onSurfaceVariant,
        surface = surfaceColor ?: surface,
        background = surfaceColor ?: background,
        surfaceContainer = card ?: surfaceContainer,
        surfaceContainerLow = card ?: surfaceContainerLow,
        surfaceContainerHigh = card ?: surfaceContainerHigh,
        surfaceContainerHighest = card ?: surfaceContainerHighest,
        outline = outlineColor ?: outline,
        outlineVariant = outlineColor ?: outlineVariant,
    )
}
