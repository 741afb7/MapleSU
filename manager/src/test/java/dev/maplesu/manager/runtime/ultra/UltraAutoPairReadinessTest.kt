package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class UltraAutoPairReadinessTest {
    @Test fun sdkRootWithoutActualManagerSuDoesNotPair() {
        var verified = 0
        val result = UltraAutoPair.complete(
            UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
            current = { true }, 
            verifyManagerSu = { verified++; false }, maintain = {})
        assertFalse(result.paired)
        assertEquals("MANAGER_SU_UNVERIFIED", result.reason)
        assertEquals(1, verified)
    }

    @Test fun ownerSessionPairsWithoutWritingPermanentKeyOrRunningMaintenance() {
        val result = UltraAutoPair.complete(
            UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
            current = { true }, 
            verifyManagerSu = { true }, maintain = {})
        assertTrue(result.paired)
    }

    @Test fun revocationDuringManagerSuVerificationDiscardsTheResult() {
        var current = true
        val result = UltraAutoPair.complete(
            UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
            current = { current }, 
            verifyManagerSu = { current = false; true }, maintain = { fail("revoked session") })
        assertFalse(result.paired)
        assertEquals("SDK_IDENTITY_SCOPE_CHANGED", result.reason)
    }

    @Test fun runningHelperWithoutRootNeverStartsAuthorizationOrManagerSu() {
        val result = UltraAutoPair.complete(
            UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running),
            current = { fail("no Root proof"); false }, 
            verifyManagerSu = { fail("no Root proof"); true }, maintain = { fail() })
        assertFalse(result.paired)
        assertEquals("SDK_ROOT_UNVERIFIED", result.reason)
    }

    @Test fun managerSuFailureDoesNotPublishReadinessOrRunMaintenance() {
        val result = UltraAutoPair.complete(
            UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
            current = { true }, verifyManagerSu = { error("SU failed") }, maintain = { fail() })
        assertFalse(result.paired)
        assertEquals("MANAGER_SU_UNVERIFIED", result.reason)
    }

    @Test fun managerSuCancellationPropagatesWithoutMaintenance() {
        val cancelled = CancellationException("cancelled")
        val observed = assertThrows(CancellationException::class.java) {
            UltraAutoPair.complete(
                UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
                current = { true }, verifyManagerSu = { throw cancelled }, maintain = { fail() })
        }
        assertSame(cancelled, observed)
    }

    @Test fun managerSuInterruptionRetainsTheInterruptedState() {
        try {
            val result = UltraAutoPair.complete(
                UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
                current = { !Thread.currentThread().isInterrupted },
                verifyManagerSu = { throw InterruptedException() }, maintain = { fail() })
            assertFalse(result.paired)
            assertEquals("SDK_IDENTITY_SCOPE_CHANGED", result.reason)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun anInterruptedThreadNeverStartsManagerSu() {
        try {
            Thread.currentThread().interrupt()
            val result = UltraAutoPair.complete(
                UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
                current = { true }, verifyManagerSu = { fail("interrupted"); true }, maintain = { fail() })
            assertFalse(result.paired)
            assertEquals("MANAGER_SU_UNVERIFIED", result.reason)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
