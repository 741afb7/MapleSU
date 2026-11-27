package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test

class UltraProbeProtectionTest {
    private val probe = UltraEnvironmentProbe

    @Test fun currentSignedOwnerRetainsFaultWithoutRootAndRejectsUnboundForeignRoot() {
        val owner = UltraRuntimeClient.ManagerOwnerIdentity(android.os.Process.myUid(), 7, "ab".repeat(16))
        val fault = UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Fault,
            managerOwnerIdentity = owner, sdkChannelAvailable = true)
        val scopedFault = probe.scopeOwnerSnapshot(fault, owner, "boot", "boot", false)
        assertEquals(UltraEnvironmentProbe.State.Fault, scopedFault.state)
        assertFalse(scopedFault.rootAvailable)
        assertEquals("ultra:fault", probe.recoveryFingerprint(scopedFault.state, true))
        for (state in listOf(UltraEnvironmentProbe.State.Running, UltraEnvironmentProbe.State.Fault)) {
            val foreign = UltraEnvironmentProbe.Snapshot(state, kernelRootVerified = true,
                sdkChannelAvailable = true)
            val scoped = probe.scopeOwnerSnapshot(foreign, null, "boot", "boot", false)
            assertEquals(UltraEnvironmentProbe.State.Unknown, scoped.state)
            assertFalse(scoped.rootAvailable)
            assertFalse(scoped.sdkChannelAvailable)
            assertThrows(IllegalStateException::class.java) {
                probe.recoveryFingerprint(scoped.state, false, scoped.kernelRootVerified,
                    scoped.kernelChannelAbsent, scoped.detail)
            }
        }
    }

    @Test fun staleEpochHostAndBootCannotReuseAnAuthenticatedUltraObservation() {
        val owner = UltraRuntimeClient.ManagerOwnerIdentity(android.os.Process.myUid(), 7, "ab".repeat(16))
        val running = UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,
            kernelRootVerified = true, managerOwnerIdentity = owner, sdkChannelAvailable = true)
        for (different in listOf(owner.copy(epoch = 8), owner.copy(hostInstance = "cd".repeat(16)),
            owner.copy(fullUid = owner.fullUid + 100000))) {
            assertEquals(UltraEnvironmentProbe.State.Unknown,
                probe.scopeOwnerSnapshot(running, different, "boot", "boot", false).state)
        }
        assertEquals(UltraEnvironmentProbe.State.Unknown,
            probe.scopeOwnerSnapshot(running, owner, "new", "old", false).state)
    }

    @Test fun runningStatusRequiresIndependentKernelRootProof() {
        assertFalse(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running).rootAvailable)
        assertTrue(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true).rootAvailable)
        assertTrue(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Unknown, kernelRootVerified = true).rootAvailable)
        assertTrue(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.NotInstalled, kernelRootVerified = true).rootAvailable)
        assertFalse(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.NoKey, kernelRootVerified = true).rootAvailable)
    }

    @Test fun faultKeepsItsProtectionWithoutGrantingKernelRoot() {
        assertFalse(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Fault, kernelRootVerified = true).rootAvailable)
        assertFalse(probe.verifyKernelRoot(UltraEnvironmentProbe.State.Fault, true) {
            fail("protected environment must not submit a root verification")
            0
        })
    }

    @Test fun faultUsesTheExistingShortCacheTtl() {
        assertTrue(probe.canReuse(UltraEnvironmentProbe.State.Fault, 0))
        assertTrue(probe.canReuse(UltraEnvironmentProbe.State.Fault, 2_999))
        assertFalse(probe.canReuse(UltraEnvironmentProbe.State.Fault, 3_000))
        assertFalse(probe.canReuse(UltraEnvironmentProbe.State.Fault, -1))
    }

    @Test fun invalidationRetainsFaultUntilFreshEvidenceButExpiresItsTimestamp() {
        val previous = probe.currentSnapshot()
        try {
            probe.publishSnapshot(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Fault, observedAtElapsedMs = 5))
            probe.invalidateAvailability()
            val invalidated = probe.currentSnapshot()
            assertEquals(UltraEnvironmentProbe.State.Fault, invalidated.state)
            assertEquals(-1L, invalidated.observedAtElapsedMs)
            assertFalse(invalidated.rootAvailable)
        } finally { probe.publishSnapshot(previous) }
    }

    @Test fun freshSdkEvidenceCanReplaceFaultAfterRepair() {
        assertEquals(UltraEnvironmentProbe.State.Running,
            probe.stateAfterAdmission(UltraEnvironmentProbe.State.Fault, UltraEnvironmentProbe.State.Running, "OK", true))
    }

    @Test fun absentChangedBootOrSuspendedOwnerDiscardsOldProtection() {
        val fault = UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Fault)
        for ((boot, observed, suspended) in listOf(Triple("new", "old", false),
            Triple(null, "old", false), Triple("boot", "boot", true), Triple("boot", "boot", false))) {
            assertEquals(UltraEnvironmentProbe.State.Unknown,
                probe.scopeOwnerSnapshot(fault, null, boot, observed, suspended).state)
        }
    }

    @Test fun failedOwnerObservationRetainsDiagnosticsWithoutRetainingCapabilities() {
        val detail = "operation=query_manager_owner_identity;sdkError=ERR_MODULE_TEST_CHANNEL;sdkErrorCode=-19999"
        val failed = UltraEnvironmentProbe.Snapshot(detail = detail, observedAtElapsedMs = 100,
            kernelRootVerified = true, sdkChannelAvailable = true)
        val scoped = probe.scopeOwnerSnapshot(failed, null, "boot", "boot", false)
        assertEquals(detail, scoped.detail)
        assertEquals(UltraEnvironmentProbe.State.Unknown, scoped.state)
        assertNull(scoped.managerOwnerIdentity)
        assertFalse(scoped.rootAvailable)
        assertFalse(scoped.sdkChannelAvailable)
        for ((boot, observed, suspended) in listOf(Triple("new", "old", false),
            Triple(null, "boot", false), Triple("boot", "boot", true))) {
            assertNull(probe.scopeOwnerSnapshot(failed, null, boot, observed, suspended).detail)
        }
    }
}
