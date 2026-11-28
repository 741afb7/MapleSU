package dev.maplesu.manager.installer

import dev.maplesu.manager.runtime.ultra.UltraEnvUpgrade
import dev.maplesu.manager.runtime.ultra.UltraEnvFacts
import dev.maplesu.manager.workmode.WorkMode

/** Only an ordinary, same-mode direct install can update the running environment. */
internal data class UltraDirectInstallRequest(
    val activeMode: WorkMode?,
    val target: InstallTargetMode,
    val source: InstallSourceKind,
    val partition: String?,
    val defaultKernel: Boolean = true,
    val signing: Boolean = false,
    val bootOptions: Boolean = false,
    val environmentWasRunning: Boolean = false,
    val forceRepatch: Boolean = false,
)

internal object UltraDirectInstallPolicy {
    fun eligible(request: UltraDirectInstallRequest): Boolean =
        request.activeMode == WorkMode.LITE && request.target == InstallTargetMode.ULTRA &&
            request.source == InstallSourceKind.CURRENT_PARTITION && request.partition in setOf("boot", "init_boot") &&
            request.defaultKernel && !request.signing && !request.bootOptions && !request.forceRepatch

    fun requiresModuleChoice(target: InstallTargetMode, activeMode: WorkMode?): Boolean =
        target == InstallTargetMode.ULTRA && activeMode != WorkMode.LITE

    /** A rejected or interrupted environment update must never fall through to boot patching. */
    fun <T> dispatch(request: UltraDirectInstallRequest, facts: () -> UltraEnvFacts,
                     upgrade: () -> T, unavailable: (UltraEnvUpgrade) -> T, patchBoot: () -> T): T {
        if (eligible(request)) {
            val current = try { facts() } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
                if (error is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                UltraEnvFacts(readUnknown = true)
            }
            if (current.suspended || current.quiescing) return unavailable(UltraEnvUpgrade.None)
            if (current.submittedThisBoot) return unavailable(UltraEnvUpgrade.Rebooting(
                current.bundledVersion ?: current.installedVersion ?: ""))
            // Explicit installation reaches the installer even when old metadata is absent.
            // The installer owns live authentication, carrier replacement and readback.
            return upgrade()
        }
        return patchBoot()
    }
}
