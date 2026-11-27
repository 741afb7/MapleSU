package com.maplesu.core.theme

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

internal interface SukisuAccessStore {
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
}

/** Official anonymous device access/refresh protocol; credentials never enter theme exports. */
internal class SukisuThemeAccess(private val storage: SukisuAccessStore,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    companion object {
        private val rotation = Mutex()
        const val HEADER = "X-Sukisu-Theme-Token"

        fun baseFor(url: HttpUrl): String? = ThemeStoreSource.SUKI_MIRRORS.asSequence()
            .mapNotNull { it.toHttpUrlOrNull()?.resolve("/") }
            .firstOrNull { it.scheme == url.scheme && it.host == url.host && it.port == url.port }
            ?.toString()
    }

    suspend fun token(base: String, rejectedToken: String? = null,
        exchange: suspend (Request) -> Pair<Int, String>): String = rotation.withLock {
        require(baseFor(requireNotNull(base.toHttpUrlOrNull())) == base) { "Unknown SukiSU store" }
        val key = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
            .joinToString("") { "%02x".format(it) }
        var saved = storage.get(key)?.let { runCatching { JSONObject(it) }.getOrNull() }
        check(saved?.optBoolean("revoked") != true) { "SukiSU device access revoked" }
        val at = now()
        if (saved != null && saved.optLong("refreshExpiresAt") <= at) saved = null
        // A late 401 may belong to a request sent before another request already rotated.
        // Reuse that newer token instead of rotating once for every rejected old request.
        if (saved != null && saved.optLong("expiresAt") > at &&
            (if (rejectedToken != null) saved.optString("accessToken") != rejectedToken
                else saved.optLong("refreshAfter") > at + 15))
            return@withLock saved.getString("accessToken")
        val rotating = saved != null
        val payload = if (rotating) JSONObject().put("refreshToken", saved!!.getString("refreshToken"))
            else JSONObject().put("name", "MapleSU Android")
        val request = Request.Builder().url(base + if (rotating) "client/refresh" else "client/access")
            .header("X-Requested-With", "SukiSUThemeAdmin").header("Cache-Control", "no-store")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val (code, body) = exchange(request)
        if (code == 401 || code == 403) {
            check(storage.put(key, (saved ?: JSONObject()).put("revoked", true).toString())) { "Device credential commit failed" }
            error("SukiSU device access HTTP $code")
        }
        check(code in 200..299) { "SukiSU device access HTTP $code" }
        val value = runCatching { JSONObject(body) }.getOrNull() ?: error("Invalid SukiSU device credential response")
        val token = value.optString("accessToken")
        require(token.matches(Regex("sa_[a-f0-9]{64}")) &&
            value.optString("refreshToken").matches(Regex("sr_[a-f0-9]{64}"))) { "Invalid SukiSU device credential" }
        val expires = value.optLong("expiresAt")
        val refreshAfter = value.optLong("refreshAfter", expires - 120)
        require(expires > at && value.optLong("refreshExpiresAt") > expires && refreshAfter < expires) { "Expired SukiSU device credential" }
        value.put("refreshAfter", refreshAfter)
        check(storage.put(key, value.toString())) { "Device credential commit failed" }
        token
    }
}
