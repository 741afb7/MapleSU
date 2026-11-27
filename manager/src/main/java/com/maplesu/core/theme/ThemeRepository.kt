package com.maplesu.core.theme

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.util.NavIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PreparedTheme(val path: String, val metadata: ThemeMetadata, val warnings: List<String>)

/** All theme commits publish one preferences snapshot pointing at immutable resource files. */
class ThemeRepository(context: Context, private val preferences: android.content.SharedPreferences = AppearancePreferences.prefs(context)) {
    private val context = context.applicationContext
    private val prefs get() = preferences
    private val root get() = File(context.filesDir, "themes").apply { mkdirs() }
    private val journal get() = File(root, "transaction.json")

    suspend fun prepare(uri: Uri, metadata: ThemeMetadata? = null): PreparedTheme {
        var stage: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val candidate = File(root, "staging/${UUID.randomUUID()}").also { stage = it }
                val task = currentCoroutineContext()
                val (raw, files) = requireNotNull(context.contentResolver.openInputStream(uri)) { "Theme stream unavailable" }
                    .use { source ->
                        val input = object : java.io.FilterInputStream(source) {
                            override fun read(): Int { task.ensureActive(); return super.read() }
                            override fun read(bytes: ByteArray, offset: Int, count: Int): Int { task.ensureActive(); return super.read(bytes, offset, count) }
                        }
                        ThemePackageReader.read(input, File(candidate, "assets"))
                    }
                if (raw.has("sukisu.original")) {
                    if (metadata != null) metadata.write(raw)
                    else if (!raw.has("meta_name")) ThemeMetadata(name = "SukiSU theme").write(raw)
                    // Keep the current MapleSU arrangement; a Nova layout is not a MapleSU layout ID.
                    val current = ThemeRuntime.document(context, prefs)
                    current.keys().asSequence().filter { it in setOf("homeLayoutStyle", "statsTopLayout") }.forEach { raw.put(it, current.get(it)) }
                    // Validate both light/dark overlays before showing an import preview.
                    SukisuThemeDocument.select(raw, false); SukisuThemeDocument.select(raw, true)
                }
                val (normalized, warnings) = ThemeDocument.normalize(raw, files)
                durableWrite(File(candidate, "original.json"), raw.toString())
                durableWrite(File(candidate, "applied.json"), normalized.toString())
                PreparedTheme(candidate.absolutePath, ThemeMetadata.read(raw), warnings + ThemeAssetValidation.warnings(files))
            }
        } catch (e: Throwable) {
            // Also covers prompt cancellation when the completed IO result is discarded.
            withContext(NonCancellable + Dispatchers.IO) {
                try { stage?.let { check(!it.exists() || it.deleteRecursively()) { "Theme staging cleanup failed" } } }
                catch (cleanup: Exception) {
                    e.addSuppressed(cleanup)
                    android.util.Log.e("ThemeRepository", "Cancelled theme preparation cleanup failed", cleanup)
                }
            }
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    suspend fun apply(prepared: PreparedTheme, identity: String = ""): Unit = withContext(Dispatchers.IO) {
        writes.withLock {
            recoverLocked()
            val stage = File(prepared.path)
            require(stage.canonicalFile.parentFile == File(root, "staging").canonicalFile) { "Invalid prepared theme" }
            val config = JSONObject(File(stage, "applied.json").readText())
            val id = UUID.randomUUID().toString()
            val target = File(root, "generations/$id")
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            publish(id, move = { check(stage.renameTo(target)) { "Theme resource commit failed" } }) { editor ->
                val values = wallpaperValues(config, File(target, "assets"))
                values.forEach { (key, value) -> put(editor, key, value) }
                ThemeDocument.destinations.values.forEach { editor.remove("theme_nav_override_$it") }
                editor.putString("theme_generation", id).putString("theme_document", config.toString())
                    .putString("theme_identity", identity).putBoolean("theme_modified", false)
                if (config.has("appLanguage")) editor.putString("theme_language", config.getString("appLanguage"))
            }
            collectGenerations()
        }
    }

    suspend fun discard(prepared: PreparedTheme): Unit = withContext(Dispatchers.IO) {
        val stage = File(prepared.path)
        if (stage.canonicalFile.parentFile == File(root, "staging").canonicalFile)
            check(!stage.exists() || stage.deleteRecursively()) { "Theme preview cleanup failed" }
    }

    suspend fun recover(): Unit = withContext(Dispatchers.IO) { writes.withLock { recoverLocked(); collectGenerations() } }

    private fun recoverLocked() {
        if (journal.isFile) {
            val record = JSONObject(journal.readText())
            val id = record.optString("generation")
            require(id.isEmpty() || runCatching { UUID.fromString(id) }.isSuccess)
            if (record.optBoolean("rollback")) restore(record.getJSONObject("before"), recordKeys(record))
            if (id.isNotEmpty() && prefs.getString("theme_generation", "") != id) File(root, "generations/$id").deleteRecursively()
            check(journal.delete())
        }
    }

    /** commit(false) has already changed SharedPreferences in memory. Never infer success from it. */
    private fun publish(generation: String = "", additionalPreferences: Set<String> = emptySet(),
        move: () -> Unit = {}, configure: (android.content.SharedPreferences.Editor) -> Unit) {
        recoverLocked()
        val record = JSONObject().put("generation", generation).put("before", snapshot(additionalPreferences))
            .put("preferences", org.json.JSONArray(additionalPreferences.toList()))
        durableWrite(journal, record.toString())
        try {
            move()
            val editor = prefs.edit().putLong("theme_revision", System.currentTimeMillis())
            configure(editor)
            if (!editor.commit()) {
                // Retain rollback intent if its own persistence fails; startup retries it.
                try { durableWrite(journal, record.put("rollback", true).toString()) }
                finally { restore(record.getJSONObject("before"), additionalPreferences) }
                if (generation.isNotEmpty()) File(root, "generations/$generation").deleteRecursively()
                journal.delete()
                error("Theme preferences commit failed")
            }
            journal.delete()
        } catch (e: Throwable) {
            // Do not replay against the possibly unpersisted in-memory generation pointer here.
            if (journal.isFile && !record.optBoolean("rollback") &&
                prefs.getString("theme_generation", "") != generation) {
                if (generation.isNotEmpty()) File(root, "generations/$generation").deleteRecursively()
                journal.delete()
            }
            throw e
        }
    }

    private fun collectGenerations() {
        val active = prefs.getString("theme_generation", "")
        val referenced = listOf("wallpaper_path", "wallpaper_land_path").mapNotNull { prefs.getString(it, "") }
        File(root, "generations").listFiles()?.filter { dir ->
            dir.name != active && referenced.none { it.startsWith(dir.absolutePath + File.separator) }
        }?.forEach { it.deleteRecursively() }
    }

    private fun recordKeys(record: JSONObject): Set<String> = record.optJSONArray("preferences")?.let { keys ->
        (0 until keys.length()).map { keys.getString(it) }.toSet()
    }.orEmpty()

    private fun snapshot(additionalPreferences: Set<String>) = JSONObject().also { json -> prefs.all.forEach { (key,value) ->
        if (key in additionalPreferences || key in ownedPreferences || key == "theme_language" || key.startsWith("theme_") && key !in independentPreferences) {
            val type = when (value) { is Float -> "float"; is Long -> "long"; is Int -> "int"; is Boolean -> "boolean"; else -> "string" }
            json.put(key, JSONObject().put("type",type).put("value",value))
        }
    } }

    private fun restore(before: JSONObject, additionalPreferences: Set<String> = emptySet()) {
        val e = prefs.edit()
        prefs.all.keys.filter { it in additionalPreferences || it in ownedPreferences || it == "theme_language" || it.startsWith("theme_") && it !in independentPreferences }.forEach(e::remove)
        before.keys().forEach { key -> val entry = before.getJSONObject(key)
            when (entry.getString("type")) {
                "float" -> e.putFloat(key,entry.getDouble("value").toFloat())
                "long" -> e.putLong(key,entry.getLong("value"))
                "int" -> e.putInt(key,entry.getInt("value"))
                "boolean" -> e.putBoolean(key,entry.getBoolean("value"))
                else -> e.putString(key,entry.getString("value"))
            }
        }
        check(e.commit()) { "Theme rollback pending; free storage and restart" }
    }

    suspend fun reset(comic: Boolean = false): Unit = withContext(Dispatchers.IO) {
        writes.withLock {
            recoverLocked()
            val resetKeys = AppearancePreferences.keys - "ui_mode" + AppearancePreferences.INTRO_COMIC
            publish(additionalPreferences = resetKeys) { e ->
                resetKeys.forEach(e::remove)
                ownedPreferences.forEach(e::remove)
                prefs.all.keys.filter { it.startsWith("theme_") && it !in independentPreferences }.forEach(e::remove)
                e.remove("theme_language")
                // Empty overrides also suppress old custom icons after reset.
                ThemeDocument.destinations.values.forEach { e.putString("theme_nav_override_$it", "") }
                com.maplesu.core.ui.util.AppearancePrefsPolicy.comicPreset(comic).forEach { (key, value) -> put(e,key,value) }
                e.putBoolean(AppearancePreferences.INTRO_COMIC, comic)
            }
            collectGenerations()
        }
    }

    internal suspend fun homeLayout(layout: ThemeHomeLayout): Unit = withContext(Dispatchers.IO) {
        writes.withLock {
            recoverLocked()
            val keys = setOf(ThemeHomeLayout.PREFERENCE,com.maplesu.core.ui.util.AppearancePrefsPolicy.HOME_LARGE_CARD)
            val before = prefs.all.filterKeys { it in keys }
            val editor = prefs.edit().putString(ThemeHomeLayout.PREFERENCE,layout.value)
            if (layout != ThemeHomeLayout.Three)
                editor.putBoolean(com.maplesu.core.ui.util.AppearancePrefsPolicy.HOME_LARGE_CARD,layout == ThemeHomeLayout.Large)
            if (!editor.commit()) {
                val restore = prefs.edit()
                keys.forEach { key -> before[key]?.let { put(restore,key,it) } ?: run { restore.remove(key) } }
                restore.commit()
                error("Home layout save failed")
            }
        }
    }

    suspend fun edit(changes: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        writes.withLock {
            recoverLocked()
            val j = ThemeRuntime.document(context, prefs)
            changes.forEach { (k, v) -> j.put(k, v) }
            SukisuThemeDocument.recordEdits(j, changes)
            // Surface registry keys win over aliases. An edit must update both, not leave a stale
            // canonical value overriding the visible control after importing a registry-era theme.
            changes.forEach { (key,value) -> ThemeAliases.canonical[key]?.let { j.put(it,value) } }
            publish { e ->
                e.putString("theme_document", j.toString()).putBoolean("theme_modified", true)
                if (changes.keys.any { it in setOf("isBackgroundEnabled", "isVideoBackgroundEnabled") })
                    e.putBoolean("ui_translucent",j.optBoolean("isBackgroundEnabled"))
                changes["useSystemDynamicColor"]?.let { e.putBoolean("miuix_monet", it as Boolean) }
                if (changes.keys.any { it in setOf("nightModeFollowSys","nightModeEnabled","maplesu.amoled") }) {
                    val mode = if (j.optBoolean("nightModeFollowSys",true)) 0 else if (j.optBoolean("nightModeEnabled",true)) 2 else 1
                    e.putInt("color_mode",if (mode == 2 && j.optBoolean("maplesu.amoled")) 6 else mode)
                }
                (changes["finasu_keyColor"] as? Number)?.let { e.putInt("key_color",it.toInt()) }
                changes["colorStyle"]?.let {
                    e.putString("color_style",it.toString().lowercase().split('_').joinToString("") { word -> word.replaceFirstChar(Char::uppercase) })
                }
                changes["colorStandard"]?.let { e.putString("color_spec",if (it == "M3E_2025") "SPEC_2025" else "SPEC_2021") }
                mapOf("backgroundBlur" to "wallpaper_blur", "backgroundDim" to "wallpaper_dim", "backgroundOpacity" to "ui_translucent_alpha").forEach { (key, pref) ->
                    (changes[key] as? Number)?.let { e.putFloat(pref,it.toFloat()) }
                }
                (changes["wallpaper_use_color"] as? Boolean)?.let { e.putBoolean("wallpaper_seed", it) }
                if (changes.keys.any { it in setOf("isBackgroundEnabled", "isMultiBackgroundEnabled", "isVideoBackgroundEnabled", "wallpaper_use_color") })
                    ThemeRuntime.generation(context,prefs)?.let { generation ->
                        val path = prefs.getString("wallpaper_path","").orEmpty()
                        val manual = path.isNotEmpty() && !path.replace('\\','/').contains("/themes/generations/")
                        wallpaperValues(j,File(generation,"assets")).filterKeys { it in setOf("wallpaper_path","wallpaper_kind","wallpaper_seed","wallpaper_seed_color") }
                            .filterKeys { !manual || it == "wallpaper_seed" }
                            .forEach { (key,value) -> put(e,key,value) }
                    }
            }
        }
    }

    /** Selected resources use the same generation mechanism; never overwrite an active font/image. */
    suspend fun setAsset(base: String, uri: Uri?, extension: String, changes: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        writes.withLock {
            recoverLocked()
            require(base.matches(Regex("[A-Za-z0-9_]+")) && extension.matches(Regex("[a-z0-9]+")))
            val stage = File(root, "staging/${UUID.randomUUID()}")
            try {
                val active = ThemeRuntime.generation(context, prefs)
                if (active != null) active.copyRecursively(stage, overwrite = true) else File(stage, "assets").mkdirs()
                val assets = File(stage, "assets")
                val config = ThemeRuntime.document(context, prefs)
                val audioKey = when (base) { "theme_music" -> "musicFilename"; "theme_sound" -> "soundEffectFilename"; else -> null }
                if (audioKey != null) {
                    val previous = config.optString(audioKey)
                    val otherKey = if (base == "theme_music") "soundEffectFilename" else "musicFilename"
                    if (previous.isNotEmpty() && previous != config.optString(otherKey) &&
                        previous !in setOf(".", "..") && '/' !in previous && '\\' !in previous) File(assets,previous).delete()
                }
                assets.listFiles()?.filter { it.name.substringBefore('.') == base }?.forEach { it.delete() }
                val finalResource = if (uri == null) null else {
                val resource = File(assets, "$base.$extension")
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    FileOutputStream(resource).use { output ->
                        val buffer = ByteArray(65536); var count = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            count += n; require(count <= 1024L * 1024 * 1024) { "Resource too large" }; output.write(buffer, 0, n) }
                        output.fd.sync()
                    }
                }
                require(resource.length() > 0)
                val detected = ThemeAssetValidation.extension(resource, extension)
                val selected = if (detected == extension) resource else File(assets,"$base.$detected").also { check(resource.renameTo(it)) }
                ThemeAssetValidation.validateSelected(base, selected)
                selected
                }
                changes.forEach { (k,v) -> config.put(k,v) }
                if (config.has("sukisu.original")) {
                    val mappings = config.optJSONObject("sukisu.resourceOverrides") ?: JSONObject()
                    // Replace only this slot; other adaptive artwork still follows its own mode.
                    mappings.put(base, finalResource?.name ?: JSONObject.NULL)
                    config.put("sukisu.resourceOverrides", mappings)
                    SukisuThemeDocument.recordEdits(config, changes)
                }
                changes.forEach { (k,v) -> ThemeAliases.canonical[k]?.let { config.put(it,v) } }
                if (base == "theme_music") config.put("musicFilename",finalResource?.name.orEmpty())
                if (base == "theme_sound") config.put("soundEffectFilename",finalResource?.name.orEmpty())
                // Keep the imported, normalized snapshot immutable: export uses it to tell
                // unsupported upstream enum fallbacks from deliberate edits.
                if (!File(stage, "original.json").isFile) durableWrite(File(stage, "original.json"), config.toString())
                if (!File(stage, "applied.json").isFile) durableWrite(File(stage, "applied.json"), config.toString())
                // Preserve manual settings and resource changes; setAsset only owns the document/pointer.
                val id = UUID.randomUUID().toString(); val target = File(root, "generations/$id")
                target.parentFile!!.mkdirs()
                publish(id, move = { check(stage.renameTo(target)) }) { e ->
                e.putString("theme_generation", id).putString("theme_document", config.toString()).putBoolean("theme_modified", true)
                // Repoint references copied from the previous generation before collecting it.
                listOf("wallpaper_path", "wallpaper_land_path").forEach { key ->
                    val path = prefs.getString(key, "").orEmpty()
                    if (active != null && path.startsWith(active.absolutePath + File.separator))
                        e.putString(key, File(target, path.removePrefix(active.absolutePath + File.separator)).absolutePath)
                }
                if (base == "background" || base == "video_background") {
                    wallpaperValues(config, File(target,"assets")).filterKeys { it in setOf("wallpaper_path","wallpaper_kind","ui_translucent","wallpaper_seed_color") }
                        .forEach { (key,value) -> put(e,key,value) }
                } else if (base.startsWith("background_")) {
                    e.putBoolean("ui_translucent", true)
                    put(e,"wallpaper_seed_color",wallpaperValues(config,File(target,"assets")).getValue("wallpaper_seed_color"))
                }
                }
                collectGenerations()
            } catch (e: Throwable) { stage.deleteRecursively(); throw e }
        }
    }

    suspend fun export(metadata: ThemeMetadata): Uri = withContext(Dispatchers.IO) {
        writes.withLock {
            recoverLocked()
            val values = prefs.all
            val generation = ThemeRuntime.generation(context, prefs)
            val json = generation?.let { File(it, "original.json").takeIf(File::isFile)?.readText() }
                ?.let(::JSONObject) ?: JSONObject()
            val effective = ThemeRuntime.document(context, prefs)
            val baseline = generation?.let { File(it,"applied.json").takeIf(File::isFile)?.readText() }?.let(::JSONObject)
            effective.keys().forEach { key ->
                // Keep an unsupported original token when its normalized fallback has not been edited.
                if (!json.has(key) || baseline?.opt(key) != effective.opt(key) || json.opt(key) == baseline.opt(key))
                    json.put(key, effective.get(key))
            }
            val storedMode = values["color_mode"] as? Int ?: 0
            val mode = com.maplesu.core.ui.theme.ColorMode.fromValue(storedMode).toNonMonetMode()
            json.put("nightModeFollowSys", mode == 0).put("nightModeEnabled", mode == 2)
                .put("maplesu.amoled",storedMode == 6)
                .put("finasu_keyColor", values["key_color"] as? Int ?: 0)
                .put("colorStyle", (values["color_style"] as? String ?: "TonalSpot").replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
                .put("colorStandard", if (values["color_spec"] == "SPEC_2021") "MD3_2021" else "M3E_2025")
                .put("backgroundOpacity", (values["ui_translucent_alpha"] as? Float ?: .5f).toDouble())
                .put("backgroundBlur", (values["wallpaper_blur"] as? Float ?: 0f).toDouble())
                .put("backgroundDim", (values["wallpaper_dim"] as? Float ?: .35f).toDouble())
                .put("wallpaper_use_color", values["wallpaper_seed"] as? Boolean ?: false)
            metadata.write(json)
            val files = generation?.let { File(it, "assets").listFiles() }?.filter { it.name != "theme.json" }?.associateBy { it.name }?.toMutableMap() ?: mutableMapOf()
            val wallPath = values["wallpaper_path"] as? String
            val wall = wallPath?.takeIf(String::isNotEmpty)?.let(::File)?.takeIf(File::isFile)
            val video = values["wallpaper_kind"] == "video"
            val manual = wall != null && !wall.absolutePath.replace('\\','/').contains("/themes/generations/")
            if (manual || generation == null) {
                json.put("isBackgroundEnabled", wall != null && effective.optBoolean("isBackgroundEnabled",true))
                if (wall != null) {
                    val base = if (video) "video_background" else "background"
                    // A manual replacement owns this slot across extensions; the old JPG must
                    // not win the resource policy over a newly selected PNG after reimport.
                    files.keys.removeAll { it.substringBefore('.') == base }
                    files["$base.${wall.extension}"] = wall
                }
                json.put("isVideoBackgroundEnabled", video && wall != null)
            }
            val nav = JSONObject(json.optJSONObject("navIcons")?.toString() ?: "{}")
            ThemeDocument.destinations.forEach { (key, slot) ->
                val icon = NavIcons.currentFile(context, slot, prefs)
                if (icon != null) { val name = "nav_icon_$key.${icon.extension.ifBlank { ThemeAssetValidation.extension(icon, "png") }}"; files[name] = icon; nav.put(key, name) }
                else if (prefs.contains("theme_nav_override_$slot")) nav.remove(key)
            }
            val manualNav = ThemeDocument.destinations.values.any { prefs.contains("theme_nav_override_$it") }
            json.put("navIcons", nav).put("navIconCustomEnabled", if (manualNav)
                ThemeDocument.destinations.values.any { NavIcons.currentFile(context, it, prefs) != null } else json.optBoolean("navIconCustomEnabled"))
            if (android.os.Build.VERSION.SDK_INT >= 33) json.put("appLanguage", context.getSystemService(android.app.LocaleManager::class.java).applicationLocales.toLanguageTags())
            else if (prefs.contains("theme_language")) json.put("appLanguage", prefs.getString("theme_language", ""))
            val safeName = metadata.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80).ifBlank { "MapleSU" }
            val content = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$safeName.fpt")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MapleSU/Themes")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, content))
            try {
                requireNotNull(resolver.openOutputStream(uri)).use { FptContainer.write(json, files, it) }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                uri
            } catch (e: Throwable) { resolver.delete(uri, null, null); throw e }
        }
    }

    companion object {
        private val writes = Mutex()
        internal val ownedPreferences = setOf("color_mode", "key_color", "miuix_monet", "color_style", "color_spec",
            "wallpaper_path", "wallpaper_kind", "wallpaper_land_path", "wallpaper_land_kind", "wallpaper_dim", "wallpaper_blur",
            "wallpaper_seed", "wallpaper_seed_color", "ui_translucent", "ui_translucent_alpha")
        private val independentPreferences = setOf("theme_store_mode", "theme_store_intro", "theme_language",
            ThemeStoreSource.PREFERENCE, ThemeStoreSource.CUSTOM_URL)
        private fun wallpaperValues(config: JSONObject, assets: File): Map<String,Any> =
            ThemeDocument.preferenceValues(config,assets).toMutableMap().apply {
                val files = assets.listFiles()?.associateBy { it.name }.orEmpty()
                put("wallpaper_seed_color",ThemeDocument.seedImage(config,files)?.let { com.maplesu.core.ui.util.extractSeedColor(it) } ?: 0)
            }
        private fun durableWrite(file: File, text: String) {
            file.parentFile!!.mkdirs()
            val temp = File(file.parentFile, "${file.name}.part")
            FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(temp.renameTo(file))
        }
        private fun put(e: android.content.SharedPreferences.Editor, k: String, v: Any) {
            when (v) { is String -> e.putString(k,v); is Int -> e.putInt(k,v); is Boolean -> e.putBoolean(k,v); is Float -> e.putFloat(k,v) }
        }
    }
}
