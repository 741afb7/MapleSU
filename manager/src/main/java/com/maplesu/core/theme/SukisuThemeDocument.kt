package com.maplesu.core.theme

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SukiSU-Nova format 1..5 -> MapleSU's existing immutable theme generations. */
internal object SukisuThemeDocument {
    private val cardSlots = listOf("grid_working_card_background", "dashboard_card_bg",
        "focus_card_kernel_bg", "focus_card_app_bg", "focus_card_device_bg", "focus_card_storage_bg")

    fun convert(raw: JSONObject, files: Map<String, File>): JSONObject {
        val version = raw.optInt("version", 2)
        require(version in 1..5) { "Unsupported SukiSU theme format: $version" }
        val adaptive = raw.optBoolean("adaptive")
        require(!adaptive || version >= 3) { "Adaptive SukiSU theme requires format 3" }
        require(!raw.has("appearance") || raw.isNull("appearance") || version >= 4) { "Appearance overlay requires format 4" }
        val variants = listOfNotNull(raw, raw.optJSONObject("light"), raw.optJSONObject("dark"))
        require(version >= 5 || variants.none { it.optString("homeLayout") == "immersive" }) { "Immersive SukiSU layout requires format 5" }
        val appearance = raw.optJSONObject("appearance")
        require(appearance == null || appearance.optInt("schema", 1) == 1) { "Unsupported SukiSU appearance schema" }
        val warnings = mutableListOf<String>()
        val j = variant(raw, "", files)
        if (adaptive) {
            val light = raw.optJSONObject("light") ?: error("SukiSU light variant is missing")
            val dark = raw.optJSONObject("dark") ?: error("SukiSU dark variant is missing")
            val day = variant(light, "_light", files)
            val night = variant(dark, "_dark", files)
            day.keys().forEach { j.put(it, day.get(it)) }
            // Initial preferences enable transparency when either half carries a background.
            j.put("isBackgroundEnabled", day.optBoolean("isBackgroundEnabled") || night.optBoolean("isBackgroundEnabled"))
            j.put("sukisu.light", day)
                .put("sukisu.dark", night)
                .put("nightModeFollowSys", true).put("sukisu.adaptive", true)
        }
        val nav = JSONObject()
        raw.optJSONArray("bottomBarIcons")?.let { icons ->
            for (i in 0 until icons.length()) {
                val slot = icons.getString(i)
                val page = ThemeDocument.destinations.entries.firstOrNull { it.value == slot }?.key
                val resource = "bottom_bar_icon_$slot"
                if (page != null && resource in files) nav.put(page, resource)
                else warnings += "$resource: no matching image/page; original retained"
            }
        }
        j.put("navIcons", nav).put("navIconCustomEnabled", nav.length() > 0)
        val allVariants = listOfNotNull(raw, raw.optJSONObject("light"), raw.optJSONObject("dark"))
        if (files.keys.any { it.startsWith("card_sticker_") } || allVariants.any {
                it.has("homeLayout") || it.has("homeStatusScale") || it.has("homeCardImageStyle") })
            warnings += "SukiSU home layouts/stickers are retained; artwork adapts to your MapleSU home arrangement"
        if (appearance?.optJSONObject("image") != null)
            warnings += "SukiSU sharpen/saturation/contrast/brightness effects are retained; MapleSU uses the original image"
        j.put("sukisu.original", JSONObject(raw.toString())).put("sukisu.warnings", JSONArray(warnings))
        return j
    }

    private fun variant(raw: JSONObject, suffix: String, files: Map<String, File>): JSONObject {
        val mode = raw.optInt("themeMode", 0)
        require(mode in 0..6) { "Invalid SukiSU color mode" }
        val j = JSONObject().put("nightModeFollowSys", mode == 0 || mode == 3)
            .put("nightModeEnabled", mode in setOf(2, 5, 6)).put("maplesu.amoled", mode == 6)
            .put("useSystemDynamicColor", raw.optBoolean("miuixMonet", mode in 3..5))
            .put("finasu_keyColor", raw.optInt("keyColor", 0)).put("colorGenerationMode", "custom")
            .put("colorStyle", raw.optString("colorStyle", "TonalSpot")
                .replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
            .put("colorStandard", if (raw.optString("colorSpec") == "SPEC_2021") "MD3_2021" else "M3E_2025")
            .put("backgroundOpacity", percent(raw, "cardBackgroundAlphaPercent", 100))
            .put("backgroundDim", percent(raw, "customBackgroundScrimPercent", 0))
            .put("backgroundBlur", percent(raw, "customBackgroundBlurPercent", 0) * 100)
        val resources = JSONObject()
        val background = "background$suffix"
        val card = "card_background$suffix"
        val hasBackground = raw.optBoolean("hasBackground", background in files) && background in files
        val hasCard = raw.optBoolean("hasCardBackground", card in files) && card in files
        j.put("isBackgroundEnabled", hasBackground)
        if (hasBackground) resources.put("background", background)
        if (hasCard) {
            cardSlots.forEach { resources.put(it, card) }
            j.put("isGridWorkingCardBackgroundEnabled", true).put("isDashboardCardBackgroundEnabled", true)
                .put("hasDashboardCardBg", true).put("isFocusCardBackgroundEnabled", true)
            listOf("Kernel", "App", "Device", "Storage").forEach { j.put("hasFocusCard${it}Bg", true) }
            j.put("gridWorkingCardBackgroundOpacity", 1.0).put("dashboardCardBgOpacity", 1.0).put("focusCardBgOpacity", 1.0)
                .put("gridWorkingCardBackgroundDim", 0.0).put("dashboardCardBgDim", 0.0).put("focusCardBgDim", 0.0)
        }
        j.put("sukisu.resources", resources)
        return j
    }

    private fun percent(raw: JSONObject, key: String, default: Int): Double {
        val value = raw.optDouble(key, default.toDouble())
        require(value.isFinite()) { "Invalid SukiSU $key" }
        return value.coerceIn(0.0, 100.0) / 100
    }

    /** Apply a complete adaptive half on each configuration change, then explicit MapleSU edits. */
    fun select(config: JSONObject, dark: Boolean): JSONObject {
        if (!config.has("sukisu.original")) return config
        val selected = JSONObject(config.toString())
        if (config.optBoolean("sukisu.adaptive"))
            config.optJSONObject(if (dark) "sukisu.dark" else "sukisu.light")?.let { variant ->
                variant.keys().forEach { selected.put(it, variant.get(it)) }
                // Adaptive packages follow MapleSU's selected mode, never force the variant's mode.
                selected.put("nightModeFollowSys", config.optBoolean("nightModeFollowSys", true))
            }
        val appearance = config.getJSONObject("sukisu.original").optJSONObject("appearance")?.optJSONObject("colors")
        val tokens = JSONObject()
        listOfNotNull(appearance?.optJSONObject("shared"), appearance?.optJSONObject(if (dark) "dark" else "light"))
            .forEach { section -> section.keys().asSequence().filter { it in setOf("accent", "onAccent", "textPrimary", "textSecondary", "textDisabled", "surface", "card", "outline", "error") }.forEach { key ->
                if (!section.isNull(key)) {
                    val color = section.getString(key).trim()
                    require(color.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "Invalid SukiSU appearance color" }
                    tokens.put(key, color)
                }
            } }
        selected.put("sukisu.colors", tokens)
        config.optJSONObject("sukisu.resourceOverrides")?.let { overrides ->
            val resources = JSONObject(selected.optJSONObject("sukisu.resources")?.toString() ?: "{}")
            overrides.keys().forEach { key ->
                if (overrides.isNull(key)) resources.remove(key) else resources.put(key, overrides.getString(key))
            }
            selected.put("sukisu.resources", resources)
        }
        config.optJSONObject("sukisu.overrides")?.let { edits -> edits.keys().forEach { selected.put(it, edits.get(it)) } }
        return selected
    }

    fun recordEdits(config: JSONObject, changes: Map<String, Any>) {
        if (!config.has("sukisu.original")) return
        val edits = config.optJSONObject("sukisu.overrides") ?: JSONObject()
        changes.forEach { (key, value) -> edits.put(key, value) }
        config.put("sukisu.overrides", edits)
    }
}
