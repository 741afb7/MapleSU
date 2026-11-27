package com.maplesu.core.theme

import org.json.JSONObject

/** The user's home arrangement is independent of the imported FPT layout and of the UI skin. */
internal enum class ThemeHomeLayout(val value: String) {
    Large("large"), Small("small"), Three("three");

    companion object {
        const val PREFERENCE = "home_card_layout"

        fun read(stored: String?, largeCard: Boolean): ThemeHomeLayout =
            entries.find { it.value == stored } ?: if (largeCard) Large else Small

        /** Adapt only the upstream status surface; other layout-specific cards stay in the FPT. */
        fun statusResource(j: JSONObject): String = when {
            j.optJSONObject("sukisu.resources")?.has("grid_working_card_background") == true -> "grid_working_card_background"
            else -> when (j.optString("homeLayoutStyle")) {
            "kernelsu" -> "grid_working_card_background"
            "focus" -> "focus_card_kernel_bg"
            "dashboard_ui" -> "dashboard_card_bg"
            "stats" -> if (j.optString("statsTopLayout") == "grid") "grid_working_card_background" else ""
            else -> ""
            }
        }
    }
}
