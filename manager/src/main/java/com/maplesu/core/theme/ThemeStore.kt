package com.maplesu.core.theme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class StoreTheme(
    val id: String, val name: String, val author: String, val version: String,
    val description: String, val type: String, val source: String,
    val preview: String, val download: String, val sha256: String = "",
    val mirrors: List<String> = emptyList(),
) {
    val downloadSources: List<String> get() = (listOf(download)+mirrors).distinct()
    fun metadata() = ThemeMetadata(name, type, version, author, description)
    internal fun json() = JSONObject().put("id", id).put("name", name).put("author", author)
        .put("version", version).put("description", description).put("type", type).put("source", source)
        .put("preview_url", preview).put("download_url", download).put("download_sha256", sha256)
        .put("download_mirrors",JSONArray(mirrors))
    companion object {
        internal fun read(j: JSONObject): StoreTheme {
            val id = j.get("id").toString(); val url = normalizeStoreUrl(j.getString("download_url"))
            require(id.isNotBlank() && url.startsWith("https://")) { "Invalid theme entry" }
            return StoreTheme(id, j.getString("name"), j.optString("author"), j.optString("version"),
                j.optString("description"), j.optString("type", "phone"), j.optString("source"),
                normalizeStoreUrl(j.optString("preview_url")), url, j.optString("download_sha256").lowercase(),
                j.optJSONArray("download_mirrors")?.let { array -> (0 until array.length()).mapNotNull {
                    normalizeStoreUrl(array.optString(it)).takeIf { candidate -> candidate.toHttpUrlOrNull()?.isHttps == true }
                }.distinct() }.orEmpty())
        }

        private fun normalizeStoreUrl(value: String): String {
            val trimmed = value.trim()
            val url = trimmed.toHttpUrlOrNull() ?: return trimmed
            if (url.scheme != "http" || !(url.host == "folk.mysqil.com" || url.host.endsWith(".folk.mysqil.com"))) return trimmed
            return url.newBuilder().scheme("https").port(if (url.port == 80) 443 else url.port).build().toString()
        }
    }
}

class ThemeStore internal constructor(context: Context, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS)
    .followSslRedirects(false).build()) {
    private val context = context.applicationContext
    private val root = File(context.filesDir, "themes/store").apply { mkdirs() }
    // A custom authentication header must never follow a redirect to a different origin.
    private val credentialClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val access = SukisuThemeAccess(object : SukisuAccessStore {
        private val credentials = this@ThemeStore.context.getSharedPreferences("theme_sukisu_access", Context.MODE_PRIVATE)
        override fun get(key: String) = credentials.getString(key, null)
        override fun put(key: String, value: String) = credentials.edit().putString(key, value).commit()
    })
    private val prefs get() = com.maplesu.core.ui.util.AppearancePreferences.prefs(context)
    fun source(): ThemeStoreSource = runCatching { ThemeStoreSource.resolve(
        prefs.getString(ThemeStoreSource.PREFERENCE,null),prefs.getString(ThemeStoreSource.CUSTOM_URL,"").orEmpty())
    }.getOrDefault(ThemeStoreSource.Fina)

    suspend fun source(value: ThemeStoreSource): Unit = withContext(Dispatchers.IO) {
        val checked = ThemeStoreSource.resolve(value.id,value.url)
        val editor = prefs.edit().putString(ThemeStoreSource.PREFERENCE,checked.id)
        if (checked.id == "custom") editor.putString(ThemeStoreSource.CUSTOM_URL,checked.url)
        check(editor.commit()) { "Theme source preferences commit failed" }
    }
    private fun key(theme: StoreTheme) = digest("${theme.id}\n${theme.version}\n${theme.download}\n${theme.sha256}".toByteArray())
    internal fun identity(theme: StoreTheme) = "${theme.id}@${theme.version}:${key(theme)}"
    fun packageFile(theme: StoreTheme) = File(root, "${key(theme)}.fpt")
    fun samePackage(a: StoreTheme, b: StoreTheme) = key(a) == key(b)

    private suspend fun <T> response(request: Request, block: suspend (Response) -> T): T {
        val base = SukisuThemeAccess.baseFor(request.url) ?: return rawResponse(request, block = block)
        suspend fun credential(rejectedToken: String? = null) = access.token(base, rejectedToken) { accessRequest ->
            rawResponse(accessRequest, credentials = true) {
                it.code to readSmall(it.body.byteStream(), 64 * 1024).toString(Charsets.UTF_8)
            }
        }
        val token = credential()
        return rawResponse(request.newBuilder().header(SukisuThemeAccess.HEADER, token).build(), credentials = true) {
            if (it.code == 401) {
                it.close()
                val fresh = credential(token)
                rawResponse(request.newBuilder().header(SukisuThemeAccess.HEADER, fresh).build(), credentials = true, block = block)
            } else block(it)
        }
    }

    private suspend fun <T> rawResponse(request: Request, credentials: Boolean = false,
        block: suspend (Response) -> T): T = coroutineScope {
        val call = (if (credentials) credentialClient else client).newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { response ->
            check(response.request.url.isHttps) { "HTTPS required" }
            block(response)
        } } finally { cancellation.cancel() }
    }

    suspend fun index(refresh: Boolean = true, source: ThemeStoreSource = source()): List<StoreTheme> = withContext(Dispatchers.IO) {
        val index = File(root,"catalogs/${digest(source.url.toByteArray())}.json")
        if (!refresh) {
            val cached = if (index.isFile) index else File(root,"index.json").takeIf { source == ThemeStoreSource.Fina && it.isFile }
            return@withContext cached?.let { parse(it.readText(),source.url) }.orEmpty()
        }
        var failure: Exception? = null
        for (url in source.urls) {
            currentCoroutineContext().ensureActive()
            try {
                val (text,origin) = response(Request.Builder().url(url).build()) {
                    check(it.isSuccessful) { "HTTP ${it.code}" }
                    readSmall(it.body.byteStream(), 8 * 1024 * 1024).toString(Charsets.UTF_8) to it.request.url.toString()
                }
                val parsed = parse(text,origin)
                // Store absolute URLs so a mirror's relative package paths stay correct offline.
                val cache = JSONArray(parsed.map { it.json() }).toString()
                currentCoroutineContext().ensureActive()
                check(index.parentFile!!.mkdirs() || index.parentFile!!.isDirectory)
                val temp = File(index.parentFile,"${index.name}.part")
                temp.writeText(cache); check(temp.renameTo(index))
                return@withContext parsed
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        throw requireNotNull(failure)
    }

    suspend fun local(): List<StoreTheme> = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.extension == "json" && it.name != "index.json" && !it.name.endsWith(".partial.json") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { runCatching { StoreTheme.read(JSONObject(it.readText())) }.getOrNull() }
            ?.filter { packageFile(it).isFile }?.distinctBy(::key)?.sortedBy { it.name.lowercase() }.orEmpty()
    }

    suspend fun delete(theme: StoreTheme): Unit = withContext(Dispatchers.IO) {
        check(!packageFile(theme).exists() || packageFile(theme).delete())
        File(root, "${key(theme)}.json").delete()
    }

    suspend fun paused(): StoreTheme? = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.name.endsWith(".partial.json") }?.sortedByDescending { it.lastModified() }
            ?.firstNotNullOfOrNull { file -> runCatching {
                StoreTheme.read(JSONObject(file.readText()).getJSONObject("theme"))
                    .takeIf { File(root,"${key(it)}.part").isFile }
            }.getOrNull() }
    }

    suspend fun download(theme: StoreTheme, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val previousUrl = runCatching { JSONObject(File(root,"${key(theme)}.partial.json").readText()).optString("url") }.getOrNull()
        val sources = theme.downloadSources.let { if (previousUrl in it) listOf(previousUrl!!)+it.filterNot { url -> url == previousUrl } else it }
        var failure: Exception? = null
        for (url in sources) {
            currentCoroutineContext().ensureActive()
            try { return@withContext downloadFrom(theme,url,progress) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure=e }
        }
        throw requireNotNull(failure)
    }

    private suspend fun downloadFrom(theme: StoreTheme, url: String, progress: (Long, Long) -> Unit): File {
        val key = key(theme)
        val partial = File(root, "$key.part")
        val state = File(root, "$key.partial.json")
        var resume = runCatching { JSONObject(state.readText()) }.getOrNull()
        if (resume?.optString("url") != url || resume?.optString("sha256") != theme.sha256) {
            partial.delete(); state.delete(); resume = null
        }
        val offset = partial.takeIf { it.isFile }?.length() ?: 0L
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (offset > 0L) {
            request.header("Range", "bytes=$offset-")
            (resume?.optString("etag")?.takeIf { it.isNotBlank() } ?: resume?.optString("lastModified")?.takeIf { it.isNotBlank() })
                ?.let { request.header("If-Range", it) }
        }
        response(request.build()) { response ->
            if (response.code == 416) { partial.delete(); state.delete() }
            check(response.code == 200 || response.code == 206) { "HTTP ${response.code}" }
            val range = response.header("Content-Range")
            val match = range?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it) }
            val append = response.code == 206 && offset > 0
            if (response.code == 206) {
                if (match == null || match.groupValues[1].toLong() != offset) {
                    partial.delete(); state.delete(); error("Invalid Content-Range; retry from start")
                }
                val previous = resume?.optString("etag").orEmpty()
                if (previous.isNotEmpty() && response.header("ETag") != previous) {
                    partial.delete(); state.delete(); error("Download identity changed; retry from start")
                }
                val previousDate = resume?.optString("lastModified").orEmpty()
                if (previous.isEmpty() && previousDate.isNotEmpty() && response.header("Last-Modified") != previousDate) {
                    partial.delete(); state.delete(); error("Download identity changed; retry from start")
                }
            }
            val start = if (append) offset else 0L
            val rangeTotal = match?.groupValues?.get(3)?.takeIf { it != "*" }?.toLongOrNull()
            val total = rangeTotal ?: response.body.contentLength().let { if (it < 0) -1 else start + it }
            require(total <= 1024L * 1024 * 1024 + 32L * 1024 * 1024) { "Theme too large" }
            state.writeText(JSONObject().put("url", url).put("sha256", theme.sha256).put("theme",theme.json())
                .put("etag", response.header("ETag").orEmpty()).put("lastModified",response.header("Last-Modified").orEmpty()).toString())
            response.body.byteStream().use { input -> FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(65536); var done = start
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    done += n; require(done <= 1024L * 1024 * 1024 + 32L * 1024 * 1024)
                    output.write(buffer, 0, n); progress(done, total)
                }
                output.fd.sync()
                check(total < 0 || done == total) { "Incomplete download; resume available" }
            } }
        }
        if (theme.sha256.isNotEmpty()) {
            require(theme.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
            val actual = partial.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; md.update(buffer,0,n) }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (actual != theme.sha256) { partial.delete(); state.delete(); error("SHA-256 mismatch") }
        }
        // Validate the actual container before publishing a library item. Applying remains separate.
        val repo = ThemeRepository(context)
        val prepared = try { repo.prepare(android.net.Uri.fromFile(partial), theme.metadata()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { partial.delete(); state.delete(); throw e }
        repo.discard(prepared)
        currentCoroutineContext().ensureActive()
        val target = packageFile(theme)
        if (target.exists()) check(target.delete()) { "Unable to replace cached package" }
        check(partial.renameTo(target))
        try {
            val metadata = File(root, "$key.json.part")
            FileOutputStream(metadata).use { it.write(theme.json().toString().toByteArray()); it.fd.sync() }
            check(metadata.renameTo(File(root, "$key.json")))
        } catch (e: Exception) { target.delete(); throw e }
        state.delete()
        return target
    }

    suspend fun discardDownload(theme: StoreTheme): Unit = withContext(Dispatchers.IO) {
        File(root, "${key(theme)}.part").delete(); File(root, "${key(theme)}.partial.json").delete()
    }

    suspend fun preview(url: String, cachedOnly: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        val cache = File(context.cacheDir, "theme_previews").apply { mkdirs() }
        val file = File(cache, digest(url.toByteArray()))
        if (file.isFile) return@withContext file
        if (cachedOnly) return@withContext null
        try {
            response(Request.Builder().url(url).build()) {
                check(it.isSuccessful)
                val bytes = readSmall(it.body.byteStream(), 16 * 1024 * 1024)
                val part = File(cache, "${file.name}.part"); part.writeBytes(bytes); check(part.renameTo(file))
            }
            // Bound disposable previews without ever collecting installed theme resources.
            var size = cache.listFiles()?.sumOf { it.length() } ?: 0L
            cache.listFiles()?.sortedBy { it.lastModified() }?.filter { it != file }?.forEach {
                if (size > 96L * 1024 * 1024) { val length = it.length(); if (it.delete()) size -= length }
            }
            file
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    companion object {
        /** A browser has no access to private store credentials; keep protected downloads in-app. */
        internal fun browserDownload(theme: StoreTheme, mode: String): String? = theme.download.takeIf {
            mode == "compat" && it.toHttpUrlOrNull()?.let(SukisuThemeAccess::baseFor) == null
        }

        internal fun parse(text: String, indexUrl: String? = null): List<StoreTheme> {
            val base = indexUrl?.toHttpUrlOrNull()
            val array = if (text.trimStart().startsWith("{")) {
                val catalog = JSONObject(text)
                require(catalog.getInt("schema") == 1) { "Unsupported SukiSU catalog schema" }
                check(!catalog.optBoolean("maintenance")) { "SukiSU theme store maintenance" }
                val themes = catalog.getJSONArray("themes")
                JSONArray().apply { for (i in 0 until themes.length()) {
                    val item = themes.getJSONObject(i)
                    val format = item.optInt("formatVersion", 2)
                    if (format !in 1..5) continue
                    val origin = requireNotNull(base) { "SukiSU catalog origin is missing" }
                    fun asset(field: String): String {
                        val path = item.optString(field)
                        if (path.isBlank()) return ""
                        val resolved = requireNotNull(origin.resolve(path)) { "Invalid SukiSU resource path" }
                        require(resolved.isHttps && resolved.host == origin.host && resolved.port == origin.port &&
                            resolved.username.isEmpty() && resolved.password.isEmpty() && resolved.fragment == null) { "Invalid SukiSU resource origin" }
                        return resolved.toString()
                    }
                    val download = asset("path")
                    require(item.getString("sha256").matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SukiSU SHA-256" }
                    val mirrors = if (SukisuThemeAccess.baseFor(origin) != null) ThemeStoreSource.SUKI_MIRRORS
                        .mapNotNull { it.toHttpUrlOrNull()?.resolve(item.getString("path"))?.toString() }
                        .filter { it != download } else emptyList()
                    put(JSONObject().put("id", item.getString("id")).put("name", item.getString("name"))
                        .put("author", item.optString("author")).put("version", item.opt("version").toString())
                        .put("description", item.optString("description")).put("type", "phone").put("source", "sukisu")
                        .put("download_url", download).put("preview_url", asset("previewPath"))
                        .put("download_sha256", item.getString("sha256")).put("download_mirrors", JSONArray(mirrors)))
                } }
            } else JSONArray(text)
            fun resolve(value: String) = if (value.isBlank()) value else base?.resolve(value)?.toString() ?: value
            val entries = (0 until array.length()).mapNotNull { runCatching {
                val entry = JSONObject(array.getJSONObject(it).toString())
                listOf("download_url","preview_url").forEach { field ->
                    if (entry.has(field)) entry.put(field,resolve(entry.getString(field)))
                }
                entry.optJSONArray("download_mirrors")?.let { urls ->
                    entry.put("download_mirrors",JSONArray((0 until urls.length()).map { resolve(urls.getString(it)) }))
                }
                StoreTheme.read(entry)
            }.getOrNull() }
            check(array.length() == 0 || entries.isNotEmpty()) { "Theme index has no supported entries" }
            return entries.distinctBy { it.id }
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun readSmall(input: java.io.InputStream, limit: Int): ByteArray = input.use {
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384); var total = 0
            while (true) { val n = it.read(buffer); if (n < 0) break; total += n; require(total <= limit); out.write(buffer,0,n) }; out.toByteArray()
        }
    }
}
