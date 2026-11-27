package com.maplesu.core.theme

import org.json.JSONObject
import java.io.File
import java.util.Locale

data class ThemeMetadata(
    val name: String = "MapleSU", val type: String = "phone", val version: String = "1",
    val author: String = "", val description: String = "",
) {
    fun write(json: JSONObject) {
        json.put("meta_name", name).put("meta_type", type).put("meta_version", version)
            .put("meta_author", author).put("meta_description", description)
    }
    companion object {
        fun read(j: JSONObject) = ThemeMetadata(j.optString("meta_name", "MapleSU"),
            j.optString("meta_type", "phone"), j.optString("meta_version", "1"),
            j.optString("meta_author"), j.optString("meta_description"))
    }
}

/** The normalized view is separate from the original, so future keys and enums round-trip. */
internal object ThemeDocument {
    val colors = linkedMapOf(
        "indigo" to 0xFF4355B9, "blue" to 0xFF0061A4, "light_blue" to 0xFF006493,
        "cyan" to 0xFF006876, "teal" to 0xFF006A60, "green" to 0xFF006E1A,
        "light_green" to 0xFF006C48, "lime" to 0xFF5B6300, "yellow" to 0xFF695F00,
        "amber" to 0xFF785900, "orange" to 0xFF8B5000, "deep_orange" to 0xFFB02F00,
        "red" to 0xFFBB1614, "pink" to 0xFFBC004B, "purple" to 0xFF9A25AE,
        "deep_purple" to 0xFF6F43C0, "brown" to 0xFF9A4522, "blue_grey" to 0xFF00668A,
        "sakura" to 0xFF9B404F, "ink_wash" to 0xFF424242,
    ).mapValues { it.value.toInt() }
    val layouts = listOf("circle", "kernelsu", "focus", "dashboard_ui", "stats")
    val destinations = mapOf("Home" to "home", "SuperUser" to "superuser", "AModule" to "module", "Settings" to "setting")
    val defaults = mapOf<String, Any>(
        "customColor" to "indigo", "homeLayoutStyle" to "circle", "statsTopLayout" to "list",
        "nightModeEnabled" to true, "nightModeFollowSys" to true, "useSystemDynamicColor" to true,
        "colorGenerationMode" to "classic", "colorStandard" to "MD3_2021",
        "colorStyle" to "TONAL_SPOT", "colorContrast" to "STANDARD", "isListWorkingCardModeHidden" to false,
    )

    fun normalize(original: JSONObject, files: Map<String, File>): Pair<JSONObject, List<String>> {
        val j = JSONObject(original.toString())
        val warnings = mutableListOf<String>()
        original.optJSONArray("sukisu.warnings")?.let { values ->
            for (i in 0 until values.length()) warnings += values.getString(i)
        }
        defaults.forEach { (k, v) -> if (!j.has(k)) j.put(k, v) }
        // A package owns its navigation artwork even when it supplies none. Otherwise a
        // configuration-only import would silently revive icons from before that import.
        if (!j.has("navIcons")) j.put("navIcons", JSONObject())
        if (!j.has("navIconCustomEnabled")) j.put("navIconCustomEnabled", false)
        j.keys().asSequence().toList().forEach { k ->
            if (k.startsWith("is") || k.endsWith("Enabled") || k in setOf("wallpaper_use_color", "nightModeFollowSys", "useSystemDynamicColor")) {
                when (val v = j.opt(k)) {
                    is Boolean -> Unit
                    is String -> if (v.equals("true", true) || v.equals("false", true)) j.put(k, v.toBoolean())
                        else { j.remove(k); warnings += "$k: invalid boolean" }
                    else -> { j.remove(k); warnings += "$k: invalid boolean" }
                }
            }
            else if (k.endsWith("Opacity") || k.endsWith("Dim") || k.endsWith("Volume") ||
                k.endsWith(".opacity") || k.endsWith(".dim")) {
                val v = j.optDouble(k, Double.NaN)
                if (!v.isFinite()) { j.remove(k); warnings += "$k: invalid number" }
                else j.put(k, v.coerceIn(0.0, 1.0))
            }
        }
        j.put("backgroundBlur", j.optDouble("backgroundBlur", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0)
        if (j.optString("homeLayoutStyle") in listOf("default", "sign")) {
            warnings += "${j.optString("homeLayoutStyle")} → circle"
            j.put("homeLayoutStyle", "circle")
        } else if (j.optString("homeLayoutStyle") !in layouts) {
            warnings += "homeLayoutStyle: preserved; circle fallback"
            j.put("homeLayoutStyle", "circle")
        }
        if (j.has("appLanguage")) {
            val tags = j.optString("appLanguage")
            if (tags.isNotEmpty() && !tags.split(',').all { runCatching { Locale.Builder().setLanguageTag(it).build() }.isSuccess }) {
                warnings += "appLanguage: invalid; current language retained"; j.remove("appLanguage")
            }
        }
        val nav = j.optJSONObject("navIcons")
        nav?.keys()?.forEach { if (it !in destinations) warnings += "navIcons.$it: resource preserved; no matching page" }
        if (files.keys.any { it.startsWith("background_kernel.") }) warnings += "background_kernel: plugin page resource preserved"
        listOf("isVideoBackgroundEnabled" to "video_background", "isAdvancedTitleStyleEnabled" to "title_image").forEach { (enabled, base) ->
            if (j.optBoolean(enabled) && ThemeResourcePolicy.select(base, files) == null) warnings += "$base: resource missing"
        }
        return j to warnings
    }

    fun preferenceValues(j: JSONObject, directory: File): Map<String, Any> {
        val files = directory.listFiles()?.associateBy { it.name }.orEmpty()
        val video = ThemeResourcePolicy.select("video_background", files)?.takeIf { j.optBoolean("isBackgroundEnabled") && j.optBoolean("isVideoBackgroundEnabled") }
        val image = ThemeResourcePolicy.select("background", files, j)?.takeIf { j.optBoolean("isBackgroundEnabled") }
        val wall = video ?: image
        val mode = if (j.optBoolean("nightModeFollowSys", true)) 0 else if (j.optBoolean("nightModeEnabled", true)) 2 else 1
        val style = j.optString("colorStyle", "TONAL_SPOT").lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        return mapOf(
            "color_mode" to if (mode == 2 && j.optBoolean("maplesu.amoled")) 6 else mode,
            "key_color" to j.optInt("finasu_keyColor", j.optInt("folksu_keyColor", colors[j.optString("customColor")] ?: colors.getValue("indigo"))),
            "miuix_monet" to j.optBoolean("useSystemDynamicColor", true),
            "color_style" to style, "color_spec" to if (j.optString("colorStandard") == "M3E_2025") "SPEC_2025" else "SPEC_2021",
            "wallpaper_path" to (wall?.absolutePath ?: ""), "wallpaper_kind" to if (video != null) "video" else if (image != null) "image" else "none",
            "wallpaper_land_path" to "", "wallpaper_land_kind" to "none",
            "wallpaper_dim" to j.optDouble("backgroundDim", .2).toFloat(), "wallpaper_blur" to j.optDouble("backgroundBlur", 0.0).toFloat(),
            "wallpaper_seed" to j.optBoolean("wallpaper_use_color"), "wallpaper_seed_color" to 0,
            "ui_translucent" to (j.optBoolean("isBackgroundEnabled") || video != null),
            "ui_translucent_alpha" to j.optDouble("backgroundOpacity", .5).toFloat(),
        )
    }

    // FinaSU keeps the accent stable across pages: multi-background mode always uses Home.
    fun seedImage(j: JSONObject, files: Map<String, File>): File? = if (!j.optBoolean("isBackgroundEnabled")) null
        else ThemeResourcePolicy.select(if (j.optBoolean("isMultiBackgroundEnabled")) "background_home" else "background",files,j)
}
