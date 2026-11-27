package dev.maplesu.manager.runtime

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Readiness means that the selected task transport opened an executor as UID 0. */
object MapleSuRuntime {
    const val API_VERSION = 1
    private data class Observation(val ready: Boolean = false, val observedAt: Long = -1,
        val current: () -> Boolean = { true })
    private var observation by mutableStateOf(Observation())
    private val probing = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "maplesu-runtime-probe").apply { isDaemon = true }
    }

    /** UI getters read cached state; process creation stays off the main thread. */
    fun isReady(context: Context): Boolean {
        val previous = observation
        if (previous.current() && previous.observedAt >= 0 &&
            SystemClock.elapsedRealtime() - previous.observedAt < 3_000) {
            return previous.ready
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (probing.compareAndSet(false, true)) {
                executor.execute {
                    try { probe(context.applicationContext) } finally { probing.set(false) }
                }
            }
            return previous.current() && previous.ready
        }
        return probe(context.applicationContext)
    }

    /** A cached successful probe never substitutes for authenticating each newly created shell. */
    fun openShell(context: Context, globalMnt: Boolean = false): Shell {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MAPLESU_BACKGROUND_THREAD_REQUIRED" }
        val current = captureCurrent(context)
        val shell = try {
            MapleSuCommandExecutor(context).openShell(globalMnt)
        } catch (error: Exception) {
            if (!globalMnt) observation = Observation(false, SystemClock.elapsedRealtime(), current)
            throw error
        }
        // Authentication is independent of optional namespace and client deployment capabilities.
        observation = Observation(true, SystemClock.elapsedRealtime(), current)
        try {
            if (globalMnt) {
                val result = shell.newJob().add(
                    "/system/bin/readlink /proc/self/ns/mnt",
                    "/system/bin/readlink /proc/1/ns/mnt",
                ).to(ArrayList<String>(), null).exec()
                check(result.isSuccess && result.out.size == 2 &&
                    result.out[0].matches(Regex("mnt:\\[[0-9]+]")) &&
                    result.out[0] == result.out[1]) { "MAPLESU_GLOBAL_NAMESPACE_UNAVAILABLE" }
            }
            // Mirror KSU `su.rs:318-319`: append the module tool directory so
            // module-facing commands resolve MapleSU's own tools. Appended, so
            // platform binaries keep priority.
            val path = shell.newJob().add(
                "export PATH=\"\$PATH:${dev.maplesu.manager.modules.ModuleToolDeployment.BINARY_DIR}\"").exec()
            check(path.isSuccess) { "MAPLESU_SHELL_PATH_FAILED" }
            return shell
        } catch (error: Exception) {
            runCatching { shell.close() }
            throw error
        }
    }

    fun invalidate() { observation = Observation() }

    fun refresh(context: Context): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MAPLESU_BACKGROUND_THREAD_REQUIRED" }
        return probe(context.applicationContext)
    }

    /**
     * Readiness is decided by one short-lived direct-admission process, not by a libsu
     * shell.
     *
     * <p>Opening a shell binds libsu's `RootService`, which is a root process held for as
     * long as the Manager lives. Probing on the first UI frame therefore used to create
     * exactly the idle executor the non-resident requirements forbid. The direct process
     * admits itself through the selected transport, prints one line and exits.
     */
    private fun probe(context: Context): Boolean {
        val current = captureCurrent(context)
        // Owner discovery has its own existing SDK observation path. A readiness
        // read must not create a privileged probe for an unobserved installation.
        if (!UltraEnvironmentProbe.usesUltraBackend(context)) {
            observation = Observation(false, SystemClock.elapsedRealtime(), current)
            return false
        }
        val result = runCatching { DirectRoot.run(context, "/system/bin/id -u") }
            .getOrDefault(DirectRoot.Result(-1, ""))
        val ready = current() && result.code == 0 && result.output.trim().lines().lastOrNull()?.trim() == "0"
        observation = Observation(ready, SystemClock.elapsedRealtime(), current)
        if (ready) RuntimeDeployment.start(context)
        return ready
    }

    private fun captureCurrent(context: Context): () -> Boolean = UltraSdkCredentials.captureOwnerMarker(context)
}
