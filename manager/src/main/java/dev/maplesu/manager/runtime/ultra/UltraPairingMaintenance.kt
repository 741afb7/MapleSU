package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Coalesces background work without making the pairing receipt wait for root/file operations. */
internal class UltraMaintenanceQueue(private val executor: Executor, private val now: () -> Long) {
    private val pending = mutableSetOf<String>()
    private var completed: String? = null
    private var completedAt = 0L
    private var succeeded = false

    @Synchronized
    fun request(identity: String, eligible: () -> Boolean, work: () -> Boolean): Boolean {
        val age = now() - completedAt
        if (identity in pending || (identity == completed && age >= 0 &&
            age < if (succeeded) 60_000L else 10_000L)) return false
        pending += identity
        try {
            executor.execute {
                val ok = runCatching { eligible() && work() }.getOrDefault(false)
                synchronized(this) {
                    pending -= identity
                    completed = identity
                    completedAt = now()
                    succeeded = ok
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending -= identity
            return false
        }
        return true
    }
}

internal object UltraPairingMaintenance {
    private val queue = UltraMaintenanceQueue(Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "maplesu-ultra-pairing-maintenance").apply { isDaemon = true }
    }, SystemClock::elapsedRealtime)

    fun request(context: Context) {
        val app = context.applicationContext
        val observation = UltraEnvironmentProbe.currentSnapshot(app)
        val owner = observation.managerOwnerIdentity
        val boot = UltraEnvironmentAutoSetup.currentBootId() ?: return
        val scope = UltraAuthorization.captureScope(app) ?: return
        val current = scope.current
        val identity = if (owner != null) "${owner.fullUid}:${owner.epoch}:${owner.hostInstance}:$boot"
            else observation.legacyCredentialFingerprint?.takeIf { it == scope.client.legacyCredentialFingerprint }
                ?.let { "$it:$boot" } ?: return
        queue.request(identity, { current() && !UltraIdentityRecovery.blocked(app) }) work@{
            val client = scope.client
            UltraSdkLogging.apply(client)
            if (!current()) return@work false
            val whitelist = UltraSelfWhitelist.ensureCurrentAuthority(app, client, current)
            if (!whitelist.succeeded || !current()) return@work false
            val restored = dev.maplesu.manager.installer.InstallerServiceClient.withService(app, persistent = true) {
                it.refreshUltraAuthorization()
            }
            current() && restored == "OK"
        }
    }
}
