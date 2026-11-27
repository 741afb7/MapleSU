package com.maplesu.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maplesu.core.ui.theme.isInDarkTheme
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.util.LegacyWallpaperHost

@Composable
fun ThemeBackground(page: Int, legacy: @Composable () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val j = ThemeRuntime.observe()
    val prefs = AppearancePreferences.prefs(context)
    if (!j.has("isBackgroundEnabled")) { legacy(); return }
    val frame = LocalThemeWallpaperFrame.current ?: rememberThemeWallpaperFrame(page)
    val selection = frame.selection
    val image = selection.image
    val video = selection.video
    var failed by remember(video?.absolutePath) { mutableStateOf(false) }
    val dark = isInDarkTheme()
    val dim = LocalThemeWallpaperStyle.current?.dim ?: if (j.optBoolean("isDualBackgroundDimEnabled")) j.optDouble(if (dark) "backgroundNightDim" else "backgroundDayDim",.2).toFloat()
        else if (j.has("sukisu.original")) j.optDouble("backgroundDim",0.0).toFloat() else prefs.getFloat("wallpaper_dim",.2f)
    val blur = if (j.has("sukisu.original")) j.optDouble("backgroundBlur",0.0).toFloat() else prefs.getFloat("wallpaper_blur",0f)
    val imageOrder = remember { java.util.concurrent.atomic.AtomicInteger() }
    val imageLayer = remember(frame.photo) { imageOrder.incrementAndGet().toFloat() }
    Box(Modifier.fillMaxSize()) {
        // A decoded frame may arrive after its palette. Keep a matching opaque fallback below it.
        if (image != null || video != null) {
            val color = when (com.maplesu.core.ui.LocalUiMode.current) {
                com.maplesu.core.ui.UiMode.Miuix -> top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
                com.maplesu.core.ui.UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.surface
            }
            Box(Modifier.matchParentSize().background(color.copy(alpha=1f)))
        }
        // A legacy wallpaper remains the fallback underneath per-page FPT surfaces.
        if (selection.manual) LegacyWallpaperHost { }
        if (video != null && !failed) com.maplesu.core.ui.util.VideoLayer(video.absolutePath, volume = j.optDouble("videoVolume",0.0).toFloat()) { failed = true }
        else if (image != null && frame.photo != null) AnimatedContent(targetState = frame.photo,
            modifier = Modifier.matchParentSize().blur(blur.dp, BlurredEdgeTreatment.Unbounded),
            transitionSpec = {
                (fadeIn(tween(220)) togetherWith ExitTransition.KeepUntilTransitionsFinished)
                    .apply { targetContentZIndex=imageLayer }
            },
            label = "theme-wallpaper") { photo ->
            ThemePhotoImage(photo, Modifier.fillMaxSize())
        }
        if (video != null && !failed || image != null) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=dim.coerceIn(0f,1f))))
        content()
    }
}
