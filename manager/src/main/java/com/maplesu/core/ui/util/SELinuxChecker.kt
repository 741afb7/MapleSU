package com.maplesu.core.ui.util

import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import java.io.File

/** Same ordinary sh/getenforce path and result semantics as the fixed KernelSU baseline. */
fun getSELinuxStatusRaw(): String {
    val shell = Shell.Builder.create().build("sh")
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = shell.use {
        it.newJob().add("getenforce").to(stdout, stderr).exec()
    }
    val output = stdout.joinToString("\n").trim()
    if (result.isSuccess) {
        return when (output) {
            "Enforcing", "Permissive", "Disabled" -> output
            else -> "Unknown"
        }
    }
    // KernelSU compatibility: this is a denied-read inference, not a successful enforce read.
    return if (stderr.joinToString("\n").trim().endsWith("Permission denied")) "Enforcing" else "Unknown"
}

internal fun getSELinuxStatusLocal(
    readEnforce: () -> String = { File("/sys/fs/selinux/enforce").readText() },
): String = resolveSELinuxStatus(readEnforce) { "" }

/** Local-file seam retained for existing callers; the public display getter always uses sh. */
internal fun getSELinuxStatusRaw(readEnforce: () -> String): String = getSELinuxStatusLocal(readEnforce)

// Compatibility observations are not inputs to the ordinary-shell display getter.
private const val ULTRA_STATUS_TTL_MS = 5_000L
internal class UltraSELinuxObservationToken internal constructor(
    internal val bootId: String,
    internal val revision: Long,
)
private data class UltraSELinuxObservation(
    val value: String,
    val bootId: String,
    val observedAt: Long,
    val revision: Long,
)
private val statusLock = Any()
private var ultraStatusCache: UltraSELinuxObservation? = null
private var ultraStatusRevision = 0L
private fun observationBootId(): String? = runCatching { ProcBootIdSource.read().takeIf { it.isNotBlank() } }.getOrNull()

internal fun captureUltraSELinuxObservation(): UltraSELinuxObservationToken? {
    val boot = observationBootId() ?: return null
    return synchronized(statusLock) { UltraSELinuxObservationToken(boot, ultraStatusRevision) }
}

internal fun invalidateUltraSELinuxStatus() {
    synchronized(statusLock) {
        ultraStatusRevision++
        ultraStatusCache = null
    }
}

/** Existing non-UI consumers may reuse a recent observation; the UI reads ordinary sh directly. */
fun peekCachedSELinuxStatus(): String? {
    val boot = observationBootId() ?: return null
    val now = android.os.SystemClock.elapsedRealtime()
    return synchronized(statusLock) {
        ultraStatusCache?.takeIf {
            it.bootId == boot && it.revision == ultraStatusRevision && now - it.observedAt in 0 until ULTRA_STATUS_TTL_MS
        }?.value
    }
}

internal fun rememberUltraSELinuxStatus(value: String, token: UltraSELinuxObservationToken?): Boolean {
    if (token == null || value !in setOf("Enforcing", "Permissive", "Disabled") ||
        observationBootId() != token.bootId) return false
    return synchronized(statusLock) {
        if (ultraStatusRevision != token.revision) false else {
            ultraStatusCache = UltraSELinuxObservation(value, token.bootId, android.os.SystemClock.elapsedRealtime(), token.revision)
            true
        }
    }
}

internal fun resolveSELinuxStatus(readEnforce: () -> String, queryStatus: () -> String): String {
    when (runCatching(readEnforce).getOrNull()?.trim()) {
        "1" -> return "Enforcing"
        "0" -> return "Permissive"
    }
    return when (val status = runCatching(queryStatus).getOrDefault("").trim()) {
        "Enforcing", "Permissive", "Disabled" -> status
        else -> "Unknown"
    }
}
