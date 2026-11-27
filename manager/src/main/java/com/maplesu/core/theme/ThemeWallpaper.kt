// Adapted from FinaSU a839ce2 WallpaperTheme/FolkWallpaperTokens (GPL-3.0).
package com.maplesu.core.theme

import android.graphics.BitmapFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.util.WallpaperStore
import com.maplesu.core.ui.util.WallpaperPrefs
import com.maplesu.core.ui.util.rememberWallpaperSlot
import java.io.File

internal val LocalThemeWallpaperPage = staticCompositionLocalOf { 0 }
internal val LocalThemeWallpaperStyle = staticCompositionLocalOf<ThemeWallpaperStyle?> { null }
internal val LocalThemeWallpaperFrame = staticCompositionLocalOf<ThemeWallpaperFrame?> { null }

internal data class ThemeWallpaperStyle(val opacity: Float, val darkNeutral: Boolean, val dim: Float) {
    // Keep body text readable even over the brightest/darkest parts of a wallpaper.
    val group: Float get() = opacity.coerceIn(0f,1f).let { it+(1f-it)*.28f }
        .coerceAtLeast(if (darkNeutral) .76f else .64f)
    val raised: Float get() = (group+.12f).coerceAtMost(1f)
    val chrome: Float get() = .1f+.9f*opacity.coerceIn(0f,1f)
    val selection: Float get() = .45f+.55f*opacity.coerceIn(0f,1f)
    val overlay: Float get() = .96f
}

internal data class ThemeBackgroundSelection(val image: File? = null, val video: File? = null, val manual: Boolean = false) {
    val active: Boolean get() = image != null || video != null || manual
}

internal data class ThemeWallpaperFrame(val selection: ThemeBackgroundSelection, val photo: ThemePhoto?)

@Composable
internal fun rememberThemeWallpaperFrame(page: Int = LocalThemeWallpaperPage.current): ThemeWallpaperFrame {
    val selection = rememberThemeBackgroundSelection(page)
    return ThemeWallpaperFrame(selection, rememberThemePhoto(selection.image))
}

@Composable
internal fun rememberThemeBackgroundSelection(page: Int = LocalThemeWallpaperPage.current): ThemeBackgroundSelection {
    val context = LocalContext.current
    val j = ThemeRuntime.observe()
    val generation = ThemeRuntime.generation(context)
    val orientation = rememberWallpaperSlot()
    val revision = WallpaperPrefs.observe()
    val storeVersion = WallpaperStore.version
    if (j.has("isBackgroundEnabled") && !j.optBoolean("isBackgroundEnabled")) return ThemeBackgroundSelection()
    if (generation == null || !j.has("isBackgroundEnabled")) return remember(orientation,revision,storeVersion) {
        ThemeBackgroundSelection(manual=WallpaperStore.effectiveFile(orientation) != null)
    }
    val nav = LocalNavigator.current
    val route = nav.current()
    val slot = when (route) {
        Route.Main -> page
        Route.Home -> 0
        Route.SuperUser -> 1
        Route.Module -> 2
        Route.Settings, Route.Appearance, Route.AppearanceImages, Route.Wallpaper, Route.ThemeStore,
        Route.MyThemes, Route.ThemeFont, Route.ThemeLayout, Route.ThemeMedia, Route.ColorPalette -> 3
        else -> when (nav.backStack.dropLast(1).lastOrNull { it in listOf(Route.Home,Route.SuperUser,Route.Module,Route.Settings) }) {
            Route.SuperUser -> 1; Route.Module -> 2; Route.Settings -> 3; else -> page
        }
    }
    return remember(j,generation,orientation,slot,revision,storeVersion) {
        val legacy = WallpaperStore.effectiveFile(orientation)
        val manual = legacy != null && !legacy.absolutePath.replace('\\','/').contains("/themes/generations/")
        val base = if (j.optBoolean("isMultiBackgroundEnabled"))
            listOf("background_home","background_superuser","background_system_module","background_settings")[slot.coerceIn(0,3)] else "background"
        val image = ThemeRuntime.file(context,base).takeIf { base != "background" || !manual }
        val video = if (!manual && j.optBoolean("isVideoBackgroundEnabled")) ThemeRuntime.file(context,"video_background") else null
        ThemeBackgroundSelection(image,video,manual=manual && image == null && video == null)
    }
}

@Composable
internal fun rememberThemeWallpaperStyle(dark: Boolean, frame: ThemeWallpaperFrame): ThemeWallpaperStyle? {
    val context = LocalContext.current
    val j = ThemeRuntime.observe()
    if (!j.optBoolean("isBackgroundEnabled")) return null
    val selection = frame.selection
    // Manual wallpapers retain MapleSU's established appearance; FPT owns only its own materials.
    if (!selection.active || selection.manual) return null
    val luminance = frame.photo?.luminance ?: -1f
    if (selection.image != null && luminance < 0f && selection.video == null) return null
    val dim = if (j.optBoolean("isDualBackgroundDimEnabled")) j.optDouble(if (dark) "backgroundNightDim" else "backgroundDayDim",.2).toFloat()
        else if (j.has("sukisu.original")) j.optDouble("backgroundDim",0.0).toFloat() else AppearancePreferences.prefs(context).getFloat("wallpaper_dim",.2f)
    val opacity = if (AppearancePreferences.prefs(context).getBoolean("ui_translucent",true))
        j.optDouble("backgroundOpacity",.5).toFloat().coerceIn(0f,1f) else 1f
    return ThemeWallpaperStyle(opacity,
        useThemeDarkNeutral(dark,luminance),dim.coerceIn(0f,1f))
}

internal fun useThemeDarkNeutral(dark: Boolean, luminance: Float): Boolean = dark || luminance >= 0f && luminance < .5f

internal fun imageLuminance(file: File): Float = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeFile(file.absolutePath,bounds)
    var sample = 1
    while (maxOf(bounds.outWidth,bounds.outHeight)/sample > 32) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return -1f
    try {
        var total = 0.0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
            total += androidx.core.graphics.ColorUtils.calculateLuminance(bitmap.getPixel(x,y))
        (total/(bitmap.width*bitmap.height)).toFloat()
    } finally { bitmap.recycle() }
}.getOrDefault(-1f)

/** Neutral roles follow the current image; accent roles keep the fixed Home seed. */
internal fun ColorScheme.withThemeNeutral(neutral: ColorScheme): ColorScheme = copy(
    onBackground=neutral.onBackground,onSurface=neutral.onSurface,onSurfaceVariant=neutral.onSurfaceVariant,
    surface=neutral.surface,surfaceDim=neutral.surfaceDim,surfaceBright=neutral.surfaceBright,
    surfaceContainer=neutral.surfaceContainer,surfaceContainerLow=neutral.surfaceContainerLow,
    surfaceContainerLowest=neutral.surfaceContainerLowest,surfaceContainerHigh=neutral.surfaceContainerHigh,
    surfaceContainerHighest=neutral.surfaceContainerHighest,surfaceVariant=neutral.surfaceVariant,
    outline=neutral.outline,outlineVariant=neutral.outlineVariant,
    inverseSurface=neutral.inverseSurface,inverseOnSurface=neutral.inverseOnSurface,
)

internal fun ColorScheme.withThemeWallpaper(style: ThemeWallpaperStyle): ColorScheme = copy(
    onSurfaceVariant=if (style.opacity < .65f) onSurface else onSurfaceVariant,
    background=Color.Transparent,surface=surface.copy(alpha=style.chrome),surfaceDim=surfaceDim.copy(alpha=style.group),
    surfaceBright=surfaceBright.copy(alpha=style.group),surfaceVariant=surfaceVariant.copy(alpha=style.group),
    surfaceContainer=surfaceContainer.copy(alpha=style.group),surfaceContainerLowest=surfaceContainerLowest.copy(alpha=style.group),
    surfaceContainerLow=surfaceContainerLow.copy(alpha=style.group),surfaceContainerHigh=surfaceContainerHigh.copy(alpha=style.raised),
    surfaceContainerHighest=surfaceContainerHighest.copy(alpha=style.overlay),
)

