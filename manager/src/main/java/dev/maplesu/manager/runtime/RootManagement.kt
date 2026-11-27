package dev.maplesu.manager.runtime

import android.content.Context
import android.os.Looper
import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.installer.InstallerRootSourceRegistry
import dev.maplesu.manager.modules.ModuleToolDeployment
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode

/**
 * Manager-side privileged operations. Normal uses its registered KSU channel;
 * current Ultra identity and Hook environments use the existing streaming root task,
 * released when the shell closes. Unknown mode preserves its unresolved state.
 */
object RootManagement {
    fun start(context: Context, argv: List<String>, globalMnt: Boolean = false): Process {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MANAGEMENT_BACKGROUND_THREAD_REQUIRED" }
        return startRouted(
            mode = ModeRuntime.active,
            usesSdk = UltraEnvironmentProbe.usesUltraBackend(context),
            usesHook = HookRootExecutor.production(context).available(),
            openSdk = { SdkRootShellClient.start(context, argv, globalMnt) },
            openKsu = { SdkRootShellClient.start(context, argv, globalMnt, RootBackend.KSU) },
            openHook = { SdkRootShellClient.start(context, argv, globalMnt, RootBackend.HOOK) },
        )
    }

    /** Module facts are selected before RootService loses the requesting app identity. */
    internal fun startModule(context: Context, argv: List<String>): Process {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MANAGEMENT_BACKGROUND_THREAD_REQUIRED" }
        if (ModeRuntime.active == WorkMode.KSU) return start(context, argv)
        if (UltraEnvironmentProbe.usesUltraBackend(context) || ModeRuntime.active == WorkMode.LITE) {
            val environment = UltraSdkCredentials.client(context).captureModuleEnvironment()
            return SdkRootShellClient.startModule(context, argv, environment)
        }
        if (HookRootExecutor.production(context).available()) {
            return SdkRootShellClient.startModule(context, argv,
                ModuleTaskEnvironment.captureNonSdk(hook = true), RootBackend.HOOK)
        }
        error("MANAGEMENT_MODE_UNRESOLVED")
    }

    /** Streaming module tasks use the same verified root source as management shells. */
    internal fun startRouted(
        mode: WorkMode?,
        usesSdk: Boolean,
        openSdk: () -> Process,
        openKsu: () -> Process = { error("MANAGEMENT_KSU_UNAVAILABLE") },
        usesHook: Boolean = false,
        openHook: () -> Process = { error("MANAGEMENT_HOOK_UNAVAILABLE") },
    ): Process = when {
        mode == WorkMode.KSU -> openKsu()
        usesSdk -> openSdk()
        usesHook -> openHook()
        mode == WorkMode.LITE -> openSdk()
        else -> error("MANAGEMENT_MODE_UNRESOLVED")
    }

    fun openShell(context: Context, globalMnt: Boolean = false): Shell {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MANAGEMENT_BACKGROUND_THREAD_REQUIRED" }
        return openShellRouted(
            mode = ModeRuntime.active,
            usesSdk = UltraEnvironmentProbe.usesUltraBackend(context),
            usesHook = HookRootExecutor.production(context).available(),
            openSdk = { openProcessShell(context,
                SdkRootShellClient.start(context, listOf("/system/bin/sh"), globalMnt), globalMnt) },
            openKsu = { openKsuShell(context.applicationContext, globalMnt) },
            openHook = { openProcessShell(context,
                SdkRootShellClient.start(context, listOf("/system/bin/sh"), globalMnt, RootBackend.HOOK), globalMnt) },
        )
    }

    /**
     * Root-channel routing (host-testable; no Android calls here).
     *
     * Every backend failure propagates directly. A submitted task is never replayed.
     */
    internal fun openShellRouted(
        mode: WorkMode?,
        usesSdk: Boolean,
        openSdk: () -> Shell,
        openKsu: () -> Shell,
        usesHook: Boolean = false,
        openHook: () -> Shell = { error("MANAGEMENT_HOOK_UNAVAILABLE") },
    ): Shell {
        if (mode != WorkMode.KSU) return when {
            usesSdk -> openSdk()
            usesHook -> openHook()
            mode == WorkMode.LITE -> openSdk()
            else -> error("MANAGEMENT_MODE_UNRESOLVED")
        }
        return openKsu()
    }

    /** Mode B (KernelSU) shell; any failure propagates through the same route. */
    private fun openKsuShell(context: Context, globalMnt: Boolean): Shell {
        val shell = InstallerRootSourceRegistry.require().openShell(context, globalMnt)
        try {
            prepareShell(shell, globalMnt)
            return shell
        } catch (error: Exception) {
            runCatching { shell.close() }
            throw error
        }
    }

    private fun openProcessShell(context: Context, process: Process, globalMnt: Boolean): Shell {
        var shell: Shell? = null
        try {
            shell = Shell.Builder.create().setContext(context.applicationContext).setTimeout(10).build(process)
            check(shell.isRoot && shell.isAlive) { "MANAGEMENT_SHELL_NOT_READY" }
            prepareShell(shell, globalMnt)
            return shell
        } catch (error: Exception) {
            runCatching { shell?.close() }
            runCatching { process.destroyForcibly() }
            listOf(process.outputStream, process.inputStream, process.errorStream).forEach { runCatching { it.close() } }
            throw error
        }
    }

    /** Post-open contract shared by all transports (PATH + global-mount validation). */
    private fun prepareShell(shell: Shell, globalMnt: Boolean) {
        // Mirror KSU `su.rs:318-319` (add_path_to_env(BINARY_DIR)): append the
        // module tool directory so module-facing commands such as `resetprop`
        // resolve MapleSU's own copy. Appended, not prepended, so platform
        // binaries keep priority.
        check(shell.newJob().add(
            "export PATH=\"\$PATH:${ModuleToolDeployment.BINARY_DIR}\"").exec().isSuccess) {
            "MANAGEMENT_SHELL_PATH_FAILED"
        }
        if (globalMnt) {
            val result = shell.newJob().add("readlink /proc/self/ns/mnt", "readlink /proc/1/ns/mnt")
                .to(ArrayList<String>(), null).exec()
            check(result.isSuccess && result.out.size == 2 && result.out[0] == result.out[1]) {
                "MANAGEMENT_GLOBAL_NAMESPACE_INVALID"
            }
        }
    }
}
