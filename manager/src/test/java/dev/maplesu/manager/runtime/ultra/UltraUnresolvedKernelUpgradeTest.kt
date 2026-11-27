package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import org.junit.Assert.*
import org.junit.Test

class UltraUnresolvedKernelUpgradeTest {
    @Test fun explicitUnresolvedUpgradeProvesCarrierBeforeCheckingItsVersion() {
        val calls = mutableListOf<String>()
        assertNull(UltraEnvironmentUpgrade.unresolvedKernelPreflight(null,
            carrier = { calls += "carrier"; "boot" },
            required = { calls += "required"; true }))
        assertEquals(listOf("carrier", "required"), calls)
    }

    @Test fun absentCarrierDoesNotUseTheOperationalBootFallback() {
        assertEquals("PREFLIGHT_ULTRA_CARRIER_UNPROVEN",
            UltraEnvironmentUpgrade.unresolvedKernelPreflight(null,
                carrier = { null }, required = { fail("unproven carrier must not check the boot fallback"); true }))
    }

    @Test fun aCurrentCarrierDoesNotTurnUnknownOwnerIntoAnSdkInstall() {
        assertEquals("PREFLIGHT_KERNEL_CURRENT_OWNER_UNCONFIRMED",
            UltraEnvironmentUpgrade.unresolvedKernelPreflight(null, { "init_boot" }, { false }))
    }

    @Test fun everyResolvedModeStopsBeforeIndependentRootOrCarrierReads() {
        for (mode in WorkMode.entries) {
            assertEquals("PREFLIGHT_MODE_NOT_UNRESOLVED",
                UltraEnvironmentUpgrade.unresolvedKernelPreflight(mode,
                    { fail("resolved mode=$mode must not probe this path"); null },
                    { fail("resolved mode=$mode must not probe this path"); false }))
        }
    }

    @Test fun authenticationAndImageReadFailuresPropagateWithoutRetry() {
        for (failAtCarrier in listOf(true, false)) {
            val expected = IllegalStateException(if (failAtCarrier) "INSTALLER_ROOT_REQUIRED" else "INSTALLER_IMAGE_CHANGED")
            var carrierCalls = 0
            var requiredCalls = 0
            try {
                UltraEnvironmentUpgrade.unresolvedKernelPreflight(null, {
                    carrierCalls++
                    if (failAtCarrier) throw expected
                    "boot"
                }, {
                    requiredCalls++
                    throw expected
                })
                fail("real failures must reach the submission failure result")
            } catch (actual: IllegalStateException) {
                assertSame(expected, actual)
            }
            assertEquals(1, carrierCalls)
            assertEquals(if (failAtCarrier) 0 else 1, requiredCalls)
        }
    }

    private fun active(mode: WorkMode = WorkMode.LITE) = ModeActivation.Active(
        mode, "boot", ActivationSource.BOOT_DETECTION, ModeState.MODE_A_ACTIVE, 1L)

    @Test fun pendingAndLiteScopesRemainBoundToTheExactActivationInstance() {
        for (expected in listOf(ModeActivation.Unresolved, active())) {
            assertNull(scope(expected, expected))
            assertEquals("KERNEL_PREFLIGHT_ACTIVATION_CHANGED", scope(expected, active(WorkMode.KSU)))
        }
        val original = active()
        assertEquals(original, original.copy())
        assertEquals("KERNEL_PREFLIGHT_ACTIVATION_CHANGED", scope(original, original.copy()))
        val otherMode = active(WorkMode.KSU)
        assertEquals("KERNEL_PREFLIGHT_MODE_NOT_LITE", scope(otherMode, otherMode))
    }

    @Test fun bootAndExistingLifecycleGuardsStillStopBeforeCommit() {
        val activation = ModeActivation.Unresolved
        assertEquals("KERNEL_PREFLIGHT_BOOT_UNAVAILABLE", scope(activation, activation, boot = null))
        assertEquals("KERNEL_PREFLIGHT_BOOT_CHANGED", scope(activation, activation, boot = "new"))
        assertEquals("KERNEL_PREFLIGHT_HOOK_ACTIVE", scope(activation, activation, hook = true))
        assertEquals("KERNEL_PREFLIGHT_QUIESCING", scope(activation, activation, quiescing = true))
        assertEquals("KERNEL_PREFLIGHT_SWITCH_PENDING", scope(activation, activation, switching = true))
        assertEquals("KERNEL_PREFLIGHT_SUSPENDED", scope(activation, activation, suspended = true))
    }

    private fun scope(expected: ModeActivation, actual: ModeActivation, boot: String? = "boot",
                      hook: Boolean = false, quiescing: Boolean = false,
                      switching: Boolean = false, suspended: Boolean = false) =
        UltraEnvironmentUpgrade.kernelUpdateScopeFailure(expected, actual, "boot", boot,
            hook, quiescing, switching, suspended)
}
