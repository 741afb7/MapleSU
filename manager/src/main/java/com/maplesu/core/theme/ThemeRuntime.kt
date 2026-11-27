package com.maplesu.core.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.util.WallpaperPrefs
import org.json.JSONObject
import java.io.File

object ThemeRuntime {
    fun document(context: Context, prefs: android.content.SharedPreferences = AppearancePreferences.prefs(context)): JSONObject = runCatching {
        val config = JSONObject(prefs.getString("theme_document", "{}") ?: "{}")
        val dark = when (prefs.getInt("color_mode", 0)) {
            1, 4 -> false; 2, 5, 6 -> true
            else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        SukisuThemeDocument.select(config, dark)
    }.getOrElse { JSONObject() }

    fun generation(context: Context, prefs: android.content.SharedPreferences = AppearancePreferences.prefs(context)): File? = prefs
        .getString("theme_generation", "")?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
        ?.let { File(context.filesDir, "themes/generations/$it") }

    fun file(context: Context, base: String, prefs: android.content.SharedPreferences = AppearancePreferences.prefs(context)): File? {
        val dir = generation(context, prefs) ?: return null
        val audioKey = when (base) { "theme_music" -> "musicFilename"; "theme_sound" -> "soundEffectFilename"; else -> null }
        if (audioKey != null) {
            val name = document(context, prefs).optString(audioKey)
            if (name.isNotEmpty()) return namedFile(context, name, prefs)
        }
        document(context, prefs).optJSONObject("sukisu.resources")?.optString(base)?.takeIf { it.isNotBlank() }
            ?.let { return namedFile(context, it, prefs) }
        // Deterministic names avoid scanning an asset directory during composition.
        val extensions = when (base) {
            "font" -> listOf("ttf","otf")
            "video_background" -> listOf("mp4","webm","mkv")
            "theme_music", "theme_sound" -> listOf("mp3","wav","ogg","flac","m4a","aac")
            else -> listOf("jpg","png","gif","webp","jpeg")
        }
        return extensions.firstNotNullOfOrNull { File(dir,"assets/$base.$it").takeIf(File::isFile) }
    }

    fun namedFile(context: Context, name: String, prefs: android.content.SharedPreferences = AppearancePreferences.prefs(context)): File? {
        if (name.isEmpty() || name in setOf(".", "..") || '/' in name || '\\' in name) return null
        return generation(context, prefs)?.let { File(it, "assets/$name") }?.takeIf { it.isFile }
    }

    @Composable
    fun observe(): JSONObject {
        val context = LocalContext.current
        val rev = WallpaperPrefs.observe()
        val mode = LocalConfiguration.current.uiMode
        return remember(rev, mode) { document(context) }
    }
}

/** Surface presence, its own switch, and the focus master are deliberately separate. */
internal data class ThemeSurface(
    val base: String, val enabled: Boolean, val opacity: Float, val dim: Float,
    val hideTitle: Boolean = false, val hideSummary: Boolean = false, val hideIcon: Boolean = false,
) {
    companion object {
        fun read(j: JSONObject, base: String, dark: Boolean): ThemeSurface {
            val grid = base == "grid_working_card_background"
            val focus = base.startsWith("focus_card_")
            val id = when {
                grid -> "layout.grid.workCard"
                focus -> "layout.focus.card.${base.removePrefix("focus_card_").removeSuffix("_bg")}"
                else -> "layout.dashboard.hero"
            }
            val prefix = if (grid) "gridWorkingCardBackground" else if (focus) "focusCardBg" else "dashboardCardBg"
            fun flag(field: String, legacy: String, default: Boolean = false) =
                j.optBoolean("surface.$id.$field", j.optBoolean(legacy, default))
            val group = if (focus) "layout.focus" else id
            fun sharedFlag(field: String, legacy: String) = j.optBoolean("surface.$group.$field", j.optBoolean(legacy))
            fun sharedScalar(field: String, legacy: String, default: Double) = j.optDouble("surface.$group.$field", j.optDouble(legacy, default)).toFloat().coerceIn(0f,1f)
            val enabled = when {
                focus -> j.optBoolean("surface.layout.focus.enabled", j.optBoolean("isFocusCardBackgroundEnabled")) &&
                    flag("enabled", "hasFocusCard${base.removePrefix("focus_card_").removeSuffix("_bg").replaceFirstChar(Char::uppercase)}Bg")
                grid -> flag("enabled", "isGridWorkingCardBackgroundEnabled")
                else -> flag("enabled", "isDashboardCardBackgroundEnabled") && j.optBoolean("hasDashboardCardBg", true)
            }
            val opacity = if (sharedFlag("dualOpacity", if (grid) "isGridDualOpacityEnabled" else if (focus) "isFocusCardDualOpacityEnabled" else "isDashboardCardDualOpacityEnabled"))
                sharedScalar(if (dark) "nightOpacity" else "dayOpacity", prefix + if (dark) "NightOpacity" else "DayOpacity", 1.0)
                else sharedScalar("opacity", "${prefix}Opacity", 1.0)
            val dim = if (sharedFlag("dualDim", if (grid) "isGridDualDimEnabled" else if (focus) "isFocusCardDualDimEnabled" else "isDashboardCardDualDimEnabled"))
                sharedScalar(if (dark) "nightDim" else "dayDim", prefix + if (dark) "NightDim" else "DayDim", .3)
                else sharedScalar("dim", "${prefix}Dim", .3)
            return ThemeSurface(base, enabled, opacity, dim,
                j.optBoolean("surface.$id.flag.hideText",j.optBoolean("isGridWorkingCardTextHidden")),
                j.optBoolean("surface.$id.flag.hideMode",j.optBoolean("isGridWorkingCardModeHidden")),
                j.optBoolean("surface.$id.flag.hideIcon",j.optBoolean("isGridWorkingCardCheckHidden")))
        }
    }
}
