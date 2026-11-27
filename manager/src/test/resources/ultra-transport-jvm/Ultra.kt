package dev.maplesu.manager.runtime.ultra

import android.content.Context
import dev.maplesu.manager.workmode.ModeRuntime

class UltraRuntimeClient { data class ManagerOwnerIdentity(val fullUid: Int, val epoch: Long, val hostInstance: String) }
data class UltraBootSdkSession(val bootId: String, val installSubmitted: Boolean = false)
object UltraEnvironmentProbe {
    data class Snapshot(val managerOwnerIdentity: UltraRuntimeClient.ManagerOwnerIdentity?)
    var backend = true
    var owner = UltraRuntimeClient.ManagerOwnerIdentity(10123, 1, "fixture")
    fun usesUltraBackend(context: Context) = backend
    fun currentSnapshot() = Snapshot(owner)
}
object UltraEnvironmentAutoSetup {
    var boot = "boot-one"
    data class Result(val installed: Boolean = true, val reason: String? = null)
    fun currentBootId(): String? = boot
    fun blockedReason(context: Context, bootSession: Boolean): String? = null
    fun ensure(context: Context) = Result()
}
object UltraSdkCredentials {
    var generation = 0
    fun captureOwnerMarker(context: Context): () -> Boolean {
        val boot = UltraEnvironmentAutoSetup.boot
        val activation = ModeRuntime.activation
        val owner = UltraEnvironmentProbe.owner
        val expected = generation
        return { boot == UltraEnvironmentAutoSetup.boot && activation === ModeRuntime.activation &&
            owner == UltraEnvironmentProbe.owner && generation == expected }
    }
}
object UltraSdkSession {
    var session: UltraBootSdkSession? = UltraBootSdkSession("boot-one")
    fun current(context: Context) = session
    fun captureOwnerMarker(context: Context): () -> Boolean {
        val boot = session?.bootId
        return { session?.bootId == boot }
    }
    fun verifyManagerSu(context: Context, session: UltraBootSdkSession, probe: () -> Boolean) = probe()
}
object ProcessSuExec {
    var probe: () -> Boolean = { true }
    data class Result(val ok: Boolean, val output: String)
    fun exec(argv: List<String>, timeout: Int, environment: Any?) = Result(probe(), "0")
}
