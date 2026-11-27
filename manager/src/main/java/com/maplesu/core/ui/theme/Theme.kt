package com.maplesu.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.data.repository.SettingsRepository
import com.maplesu.core.data.repository.SettingsRepositoryImpl
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode

enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5),
    DARK_AMOLED(6);

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }

    val isSystem: Boolean get() = value == 0 || value == 3
    val isDark: Boolean get() = value == 2 || value == 5 || value == 6
    val isAmoled: Boolean get() = value == 6
    val isMonet: Boolean get() = value >= 3

    fun toNonMonetMode(): Int = when (this) {
        MONET_SYSTEM -> 0
        MONET_LIGHT -> 1
        MONET_DARK, DARK_AMOLED -> 2
        else -> value
    }

    fun toMonetMode(): Int = when (this) {
        SYSTEM -> 3
        LIGHT -> 4
        DARK -> 5
        else -> value
    }
}

data class AppSettings(
    val colorMode: ColorMode,
    val keyColor: Int,
    val paletteStyle: PaletteStyle,
    val colorSpec: ColorSpec.SpecVersion,
    val dynamicColor: Boolean? = null,
    val generationMode: String = "custom",
    val contrast: Double = 0.0,
    val wallpaperColor: Int = 0,
) {
    val effectiveKeyColor: Int get() = wallpaperColor.takeIf { it != 0 } ?: keyColor
    val effectiveDynamicColor: Boolean get() = wallpaperColor == 0 && (dynamicColor ?: (keyColor == 0))
}

val PaletteStyle.supportsSpec2025: Boolean
    get() = this == PaletteStyle.TonalSpot ||
            this == PaletteStyle.Neutral ||
            this == PaletteStyle.Vibrant ||
            this == PaletteStyle.Expressive

fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && !style.supportsSpec2025) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

object ThemeController {
    fun getAppSettings(repo: SettingsRepository = SettingsRepositoryImpl(),
        theme: org.json.JSONObject = com.maplesu.core.theme.ThemeRuntime.document(com.maplesu.core.ksuApp)): AppSettings {
        // ⚠️ v0.13.157:隐身时**不再套默认主题**。
        //    原来这里有一段 `if (StealthLook.isPlain()) return 默认配色` —— 已撤:
        //    主题配色(含壁纸主色)不是 root 特征,没 root 的用户也有自己选的配色。
        //    隐身只改"显示什么内容",不改外观。
        val uiMode = repo.uiMode
        var colorModeValue = repo.themeMode

        if (uiMode == "miuix" && !theme.has("colorGenerationMode")) {
            val miuixMonet = repo.miuixMonet
            val colorMode = ColorMode.fromValue(colorModeValue)
            colorModeValue = if (!miuixMonet && colorMode.isMonet) {
                colorMode.toNonMonetMode()
            } else if (miuixMonet && !colorMode.isMonet) {
                colorMode.toMonetMode()
            } else {
                colorModeValue
            }
        }

        val colorMode = ColorMode.fromValue(colorModeValue)

        // 「用壁纸主色」:开启且有壁纸时,用从壁纸里提取出来的主色当种子色
        val wallpaperColor = if (repo.wallpaperSeed && theme.optBoolean("isBackgroundEnabled",true)) repo.wallpaperSeedColor else 0
        val suki = theme.has("sukisu.original")
        val keyColor = if (suki) theme.optInt("finasu_keyColor", repo.keyColor) else repo.keyColor
        val paletteStyleStr = if (suki) theme.optString("colorStyle", "TONAL_SPOT").lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) } else repo.colorStyle
        val paletteStyle = try {
            PaletteStyle.valueOf(paletteStyleStr)
        } catch (_: Exception) {
            PaletteStyle.TonalSpot
        }
        val colorSpecStr = if (suki) { if (theme.optString("colorStandard") == "MD3_2021") "SPEC_2021" else "SPEC_2025" } else repo.colorSpec
        val colorSpec = try {
            ColorSpec.SpecVersion.valueOf(colorSpecStr)
        } catch (_: Exception) {
            ColorSpec.SpecVersion.SPEC_2025
        }

        return AppSettings(colorMode, keyColor, paletteStyle, colorSpec,
            dynamicColor = if (theme.has("useSystemDynamicColor")) theme.optBoolean("useSystemDynamicColor") else null,
            generationMode = theme.optString("colorGenerationMode", "custom"),
            contrast = when (theme.optString("colorContrast")) { "MEDIUM" -> .5; "HIGH" -> 1.0; else -> 0.0 },
            wallpaperColor = wallpaperColor)
    }
}

@Composable
fun KernelSUTheme(
    appSettings: AppSettings = ThemeController.getAppSettings(),
    uiMode: UiMode = LocalUiMode.current,
    content: @Composable () -> Unit
) {
    val imported = com.maplesu.core.theme.ThemeRuntime.observe()
    val effectiveSettings = if (imported.has("sukisu.original")) ThemeController.getAppSettings(theme = imported) else appSettings
    val skinContent = rememberSkinContent(content)
    val wallpaperFrame = com.maplesu.core.theme.rememberThemeWallpaperFrame()
    androidx.compose.runtime.CompositionLocalProvider(com.maplesu.core.theme.LocalThemeWallpaperFrame provides wallpaperFrame) {
        when (uiMode) {
            UiMode.Miuix -> MiuixKernelSUTheme(
                appSettings = effectiveSettings,
                content = skinContent
            )

            UiMode.Material -> MaterialKernelSUTheme(
                appSettings = effectiveSettings,
                content = skinContent
            )
        }
    }
}

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (LocalColorMode.current) {
        1, 4 -> false  // Force light mode
        2, 5, 6 -> true   // Force dark mode
        else -> isSystemInDarkTheme()  // Follow system (0 or default)
    }
}

val LocalColorMode = staticCompositionLocalOf { 0 }

val LocalEnableBlur = staticCompositionLocalOf { false }

val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }

val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

val LocalEnableNavigationBadge = staticCompositionLocalOf { true }

/**
 * 有背景壁纸、且开了「界面透明」时为 true。
 *
 * 顶栏/底栏据此不再绘制自己的模糊遮罩（见 util/BlurExt.kt 的 BlurredBar）：
 * 那样栏区域显示的就是 Scaffold 的半透明 surface，和页面背景完全一致，
 * 壁纸可以一路连续透过去，不会出现一条深色横条。
 *
 * 注意：只影响“背景”，标题/返回/页签等内容与交互一律保留。
 */
val LocalImmersiveBars = staticCompositionLocalOf { false }

/**
 * 「界面扁平化」的运行时状态。
 *
 * ⛔ 2026-10-30 下线：设置页已不再渲染该开关，[MainActivity] 恒传 `false`。
 * 旧键 `flat_home` 只保留兼容读取（读取恒 false，见 `AppearancePrefsPolicy`）。
 * 下面的渲染分支保留给 `LocalFlatUi = true` 的显式覆盖（例如预览），默认永远走非扁平。
 */
val LocalFlatUi = staticCompositionLocalOf { false }

val LocalModuleDescriptionMaxLines = staticCompositionLocalOf { 4 }
