package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test

class UltraProbeRecoveryTest {
    private val probe = UltraEnvironmentProbe

    @Test fun helperReadFailureDoesNotSuppressIndependentKernelRootEvidence() {
        var commands = 0
        assertTrue(probe.verifyKernelRoot(UltraEnvironmentProbe.State.Unknown, false) { commands++; 0 })
        assertEquals(1, commands)
        assertFalse(probe.verifyKernelRoot(UltraEnvironmentProbe.State.Unknown, false) { 2000 })
        assertFalse(probe.verifyKernelRoot(UltraEnvironmentProbe.State.Unknown, false) { null })
    }

    @Test fun rootProofExpiresAndNeverSubstitutesHelperOrStandardSuReadiness() {
        val root = UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Unknown,
            observedAtElapsedMs = 100, kernelRootVerified = true)
        assertTrue(probe.rootProofCurrent(root, 100))
        assertTrue(probe.rootProofCurrent(root, 3099))
        assertFalse(probe.rootProofCurrent(root, 3100))
        assertFalse(probe.rootProofCurrent(root, 99))
        assertFalse(probe.rootProofCurrent(root.copy(observedAtElapsedMs = -1), 100))
        assertFalse(probe.rootProofCurrent(root.copy(kernelRootVerified = false), 100))
        assertFalse(probe.rootProofCurrent(root.copy(state = UltraEnvironmentProbe.State.Fault), 100))
        assertFalse(probe.rootProofCurrent(root.copy(state = UltraEnvironmentProbe.State.NoKey), 100))
        assertFalse(root.sdkChannelAvailable)
        assertEquals(UltraEnvironmentProbe.State.Unknown, root.state)
    }



    @Test fun aResolvedKernelObservationIsReusedOnlyWithinItsShortTtl() {
        assertTrue(probe.canReuse(UltraEnvironmentProbe.State.Unknown, 0, kernelResolved = true))
        assertTrue(probe.canReuse(UltraEnvironmentProbe.State.Unknown, 2_999, kernelResolved = true))
        assertFalse(probe.canReuse(UltraEnvironmentProbe.State.Unknown, 3_000, kernelResolved = true))
        assertFalse(probe.canReuse(UltraEnvironmentProbe.State.Unknown, -1, kernelResolved = true))
    }



















    @Test fun anUnknownObservationDoesNotHideACompletedInitializationBehindTheCache() {
        assertFalse(probe.canReuse(UltraEnvironmentProbe.State.Unknown, 0))
        assertFalse(probe.canReuse(UltraEnvironmentProbe.State.Unknown, 2_999))
        for (state in UltraEnvironmentProbe.State.entries.filter { it != UltraEnvironmentProbe.State.Unknown }) {
            assertTrue(probe.canReuse(state, 0))
            assertTrue(probe.canReuse(state, 2_999))
            assertFalse(probe.canReuse(state, 3_000))
            assertFalse(probe.canReuse(state, -1))
        }
    }
}
