package com.maplesu.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.ui.webui.MonetColorsProvider
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import com.maplesu.core.theme.*
import com.materialkolor.PaletteStyle

@Composable
fun MiuixKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val colorStyle = appSettings.paletteStyle
    val colorSpec = appSettings.colorSpec
    val wallpaperFrame = LocalThemeWallpaperFrame.current ?: rememberThemeWallpaperFrame()
    val wallpaperStyle = rememberThemeWallpaperStyle(darkTheme, wallpaperFrame)

    val miuixPaletteStyle = try {
        ThemePaletteStyle.valueOf(colorStyle.name)
    } catch (_: Exception) {
        ThemePaletteStyle.TonalSpot
    }

    val miuixColorSpec = if (colorSpec.effectiveFor(colorStyle) == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }

    val resolvedKeyColor: Color? = when {
        appSettings.effectiveKeyColor != 0 -> Color(appSettings.effectiveKeyColor)
        appSettings.colorMode.isMonet ->
            if (darkTheme) dynamicDarkColorScheme(context).primary
            else dynamicLightColorScheme(context).primary

        else -> null
    }

    val controller = ThemeController(
        when (appSettings.colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.MonetDark
        },
        keyColor = resolvedKeyColor,
        isDark = darkTheme,
        paletteStyle = miuixPaletteStyle,
        colorSpec = miuixColorSpec,
    )

    MiuixTheme(
        controller = controller,
        content = {
            val systemBarDark = wallpaperStyle?.darkNeutral ?: darkTheme
            LaunchedEffect(systemBarDark) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !systemBarDark
                    isAppearanceLightNavigationBars = !systemBarDark
                }
            }
            MonetColorsProvider.UpdateCss()
            // 有壁纸且开了"界面透明"时,把 Miuix 的大面积容器色变半透明
            // ⚠️ v0.13.157:隐身时**照常半透明**(原来这里有 `!StealthLook.isPlain()` 的"素颜"判断,已撤)。
            val wallpaperRev = com.maplesu.core.ui.util.WallpaperPrefs.observe()
            // 分朝向:横屏没单独设壁纸时用的是竖屏那张 —— 判断要按当前朝向,转屏时重算
            val wallpaperSlot = com.maplesu.core.ui.util.rememberWallpaperSlot()
            val backgroundSelection = wallpaperFrame.selection
            val surfaceAlpha = androidx.compose.runtime.remember(wallpaperRev, wallpaperSlot, backgroundSelection) {
                val r = com.maplesu.core.data.repository.SettingsRepositoryImpl()
                if ((!ThemeRuntime.document(context).has("isBackgroundEnabled") || backgroundSelection.manual) && com.maplesu.core.ui.util.WallpaperStore.hasWallpaper(wallpaperSlot) && r.uiTranslucent) {
                    r.uiTranslucentAlpha.coerceIn(0.02f, 1f)
                } else 1f
            }
            val imported = com.maplesu.core.theme.ThemeRuntime.observe()
            val native = MiuixTheme.colorScheme
            val common = rememberKernelSUColorScheme(
                seedColor = if (appSettings.effectiveDynamicColor) Color.Unspecified else Color(appSettings.effectiveKeyColor),
                isDark = darkTheme, isAmoled = appSettings.colorMode.isAmoled,
                paletteStyle = colorStyle, colorSpec = colorSpec,
                contrastLevel = appSettings.contrast, generationMode = appSettings.generationMode,
            )
            val neutral = rememberKernelSUColorScheme(
                seedColor=if (appSettings.effectiveDynamicColor) Color.Unspecified else Color(appSettings.effectiveKeyColor),
                isDark=wallpaperStyle?.darkNeutral ?: darkTheme,isAmoled=false,paletteStyle=PaletteStyle.Neutral,
                colorSpec=colorSpec,contrastLevel=appSettings.contrast,generationMode=appSettings.generationMode,
            )
            val adapted = if (wallpaperStyle != null && wallpaperStyle.darkNeutral != darkTheme) common.withThemeNeutral(neutral) else common
            val baseScheme = if (!imported.has("colorGenerationMode") && wallpaperStyle == null) native else native.copy(
                primary = common.primary, onPrimary = common.onPrimary,
                primaryVariant = common.primaryContainer, onPrimaryVariant = common.onPrimaryContainer,
                primaryContainer = common.primaryContainer, onPrimaryContainer = common.onPrimaryContainer,
                secondary = common.secondary, onSecondary = common.onSecondary,
                secondaryContainer = common.secondaryContainer, onSecondaryContainer = common.onSecondaryContainer,
                tertiaryContainer = common.tertiaryContainer, onTertiaryContainer = common.onTertiaryContainer,
                background = adapted.background, onBackground = adapted.onBackground,
                surface = adapted.surface, onSurface = adapted.onSurface, surfaceVariant = adapted.surfaceVariant,
                onSurfaceVariantSummary = adapted.onSurfaceVariant, onSurfaceSecondary = adapted.onSurfaceVariant,
                surfaceContainer = adapted.surfaceContainer, onSurfaceContainer = adapted.onSurface,
                surfaceContainerHigh = adapted.surfaceContainerHigh, onSurfaceContainerHigh = adapted.onSurface,
                surfaceContainerHighest = adapted.surfaceContainerHighest, onSurfaceContainerHighest = adapted.onSurface,
                error = common.error, onError = common.onError, errorContainer = common.errorContainer, onErrorContainer = common.onErrorContainer,
                outline = adapted.outline, dividerLine = adapted.outlineVariant,
            )
            val scheme = baseScheme.withSukisuColors(imported)
            val fonts = MiuixTheme.textStyles.withFamily(themeFontFamily())

            // Miuix 有公开重载 MiuixTheme(colorScheme: Colors, ...),
            // 直接把半透明版本传进去即可 —— 不需要反射,R8 也混淆不掉。
            if (wallpaperStyle != null) {
                val style = wallpaperStyle
                MiuixTheme(scheme.copy(
                    onSurfaceVariantSummary=if (style.opacity < .65f) scheme.onSurface else scheme.onSurfaceVariantSummary,
                    onSurfaceSecondary=if (style.opacity < .65f) scheme.onSurface else scheme.onSurfaceSecondary,
                    background=Color.Transparent,surface=scheme.surface.copy(alpha=style.chrome),
                    surfaceVariant=scheme.surfaceVariant.copy(alpha=style.group),
                    surfaceContainer=scheme.surfaceContainer.copy(alpha=style.group),
                    surfaceContainerHigh=scheme.surfaceContainerHigh.copy(alpha=style.raised),
                    surfaceContainerHighest=scheme.surfaceContainerHighest.copy(alpha=style.overlay),
                ),textStyles=fonts) {
                    CompositionLocalProvider(LocalContentColor provides scheme.onBackground,
                        LocalThemeWallpaperStyle provides style,LocalThemeWallpaperFrame provides wallpaperFrame,
                        LocalImmersiveBars provides true) { content() }
                }
            } else if (surfaceAlpha < 1f) {
                MiuixTheme(scheme.translucent(surfaceAlpha), textStyles = fonts) {
                    CompositionLocalProvider(
                        LocalContentColor provides scheme.onBackground,
                        LocalThemeWallpaperFrame provides wallpaperFrame,
                        // 有壁纸:让顶栏/底栏融进壁纸(不画自己的遮罩)
                        LocalImmersiveBars provides true,
                    ) {
                        content()
                    }
                }
            } else {
                MiuixTheme(scheme, textStyles = fonts) {
                CompositionLocalProvider(
                    LocalContentColor provides scheme.onBackground,
                    LocalThemeWallpaperFrame provides wallpaperFrame,
                ) {
                    content()
                }
                }
            }
        }
    )
}


/** 把 Miuix 的大面积容器色变成半透明(卡片比页面背景更实一点,保证可读) */
private fun Colors.translucent(alpha: Float): Colors {
    // 卡片比页面背景"实"一点,保证字看得清;但别太实,否则壁纸透不过来。
    // 0.45 → 0.28:同样可见度下卡片更通透(可见度拉满时 0.505 → 0.35)。
    val card = (alpha + (1f - alpha) * 0.28f).coerceAtMost(1f)
    return copy(
        background = background.copy(alpha = alpha),
        surface = surface.copy(alpha = alpha),
        surfaceVariant = surfaceVariant.copy(alpha = card),
        surfaceContainer = surfaceContainer.copy(alpha = card),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = card),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = card),
    )
}
