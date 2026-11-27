package com.maplesu.core.theme

import android.graphics.BitmapFactory
import android.graphics.Typeface
import java.io.File

internal object ThemeAssetValidation {
    /** Keep the original encoding: an OTF/GIF/WebM must not be exported with a fake extension. */
    fun extension(file: File, fallback: String): String {
        val bytes = file.inputStream().use { it.readNBytes(32) }
        fun text(start: Int, count: Int) = if (bytes.size >= start+count) String(bytes,start,count,Charsets.ISO_8859_1) else ""
        return when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "jpg"
            text(1,3) == "PNG" -> "png"
            text(0,3) == "GIF" -> "gif"
            text(0,4) == "RIFF" && text(8,4) == "WEBP" -> "webp"
            text(0,4) == "OTTO" -> "otf"
            bytes.take(4) == listOf<Byte>(0,1,0,0) -> "ttf"
            text(0,4) == "RIFF" && text(8,4) == "WAVE" -> "wav"
            text(0,4) == "OggS" -> "ogg"
            text(0,4) == "fLaC" -> "flac"
            bytes.size >= 4 && bytes.take(4) == listOf(0x1a.toByte(),0x45.toByte(),0xdf.toByte(),0xa3.toByte()) -> "webm"
            text(4,4) == "ftyp" -> if (fallback in listOf("mp3","wav")) "m4a" else "mp4"
            else -> fallback
        }
    }

    fun validateSelected(base: String, file: File) {
        if (base == "font") { Typeface.createFromFile(file); return }
        if (base == "video_background" || base in setOf("theme_music","theme_sound")) {
            android.media.MediaMetadataRetriever().use { it.setDataSource(file.absolutePath) }; return
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.absolutePath,options)
        require(options.outWidth>0 && options.outHeight>0) { "Image decode failed" }
    }

    fun warnings(files: Map<String,File>): List<String> = files.mapNotNull { (name,file) ->
        val base = name.substringBefore('.')
        val sukiImage = name == "background" || name == "card_background" ||
            name.startsWith("background_light") || name.startsWith("background_dark") ||
            name.startsWith("card_background_") || name.startsWith("card_sticker_") || name.startsWith("bottom_bar_icon_")
        if (base == "font" || sukiImage || name.substringAfterLast('.') in setOf("png","jpg","jpeg","gif","webp")) {
            runCatching { validateSelected(base,file) }.exceptionOrNull()?.let { "$name: decode failed; default renderer fallback" }
        } else null
    }
}

/** Explicit aliases keep canonical Fina surface configuration and legacy exporters synchronized. */
internal object ThemeAliases {
    val canonical = buildMap {
        fun add(id: String, vararg fields: Pair<String,String>) { fields.forEach { (legacy,field) -> put(legacy,"surface.$id.$field") } }
        add("layout.grid.workCard", "isGridWorkingCardBackgroundEnabled" to "enabled",
            "gridWorkingCardBackgroundOpacity" to "opacity", "gridWorkingCardBackgroundDim" to "dim",
            "isGridDualOpacityEnabled" to "dualOpacity", "gridWorkingCardBackgroundDayOpacity" to "dayOpacity",
            "gridWorkingCardBackgroundNightOpacity" to "nightOpacity", "isGridWorkingCardCheckHidden" to "flag.hideIcon",
            "isGridDualDimEnabled" to "dualDim", "gridWorkingCardBackgroundDayDim" to "dayDim", "gridWorkingCardBackgroundNightDim" to "nightDim",
            "isGridWorkingCardTextHidden" to "flag.hideText", "isGridWorkingCardModeHidden" to "flag.hideMode")
        for ((id,prefix,stem) in listOf(Triple("layout.focus","focusCardBg","FocusCard"),Triple("layout.dashboard.hero","dashboardCardBg","DashboardCard"))) {
            add(id,"is${stem}BackgroundEnabled" to "enabled", "${prefix}Opacity" to "opacity", "${prefix}Dim" to "dim",
                "is${stem}DualOpacityEnabled" to "dualOpacity", "${prefix}DayOpacity" to "dayOpacity", "${prefix}NightOpacity" to "nightOpacity",
                "is${stem}DualDimEnabled" to "dualDim", "${prefix}DayDim" to "dayDim", "${prefix}NightDim" to "nightDim")
        }
        listOf("kernel","app","device","storage").forEach { put("hasFocusCard${it.replaceFirstChar(Char::uppercase)}Bg","surface.layout.focus.card.$it.enabled") }
    }
    fun boolean(json: org.json.JSONObject, key: String, default: Boolean = false) = json.optBoolean(canonical[key] ?: key, json.optBoolean(key, default))
    fun number(json: org.json.JSONObject, key: String, default: Double) = json.optDouble(canonical[key] ?: key, json.optDouble(key, default)).toFloat()
}
