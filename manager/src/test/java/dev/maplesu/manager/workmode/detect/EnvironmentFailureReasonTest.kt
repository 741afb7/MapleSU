package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.EnvironmentProbeRunner
import dev.maplesu.manager.workmode.WorkMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EnvironmentFailureReasonTest {
    private val previous = EnvironmentSnapshot(WorkMode.LITE, false, "ultra:old", "old-boot", 1)
    private val unknownProbes = object : EnvironmentProbes {
        override fun suPresent() = false
        override fun ultraFingerprint(): String? = null
        override fun ksuNormal(): Boolean? = null
    }

    @Test fun unavailableIdentityDoesNotClaimADeadline() {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        val result = EnvironmentResolver(unknownProbes, store).resolve("current-boot")
        assertEquals(EnvironmentVerdict.UNKNOWN, result.verdict)
        assertEquals("ENV_UNKNOWN", result.code)
        assertEquals(EnvironmentCheckDetail.KSU_PROBE_UNAVAILABLE, result.detail)
        assertEquals(previous, store.snapshot())
        assertFalse(result.terminal)
    }

    @Test fun immediateWorkerFailureUsesActualElapsedTimeAndPreservesTheSnapshot() = runBlocking {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        var now = 10L
        val runner = EnvironmentCheckRunner(EnvironmentResolver(unknownProbes, store), store,
            BootIdSource { "current-boot" }, Clock { now }, probeRunner = EnvironmentProbeRunner(
                startWorker = { now = 37L; throw OutOfMemoryError("pthread_create failed") },
            ))
        val result = runner.check()
        assertEquals(EnvironmentVerdict.UNKNOWN, result.verdict)
        assertEquals("ENV_UNKNOWN_PROBE_FAILED", result.code)
        assertEquals("PROBE_FAILED:pthread_create failed", result.detail)
        assertEquals(27L, result.elapsedMillis)
        assertEquals(previous, store.snapshot())
        assertFalse(runner.busy)
    }

    @Test fun deadlineAndOccupiedWorkerHaveDifferentCodes() = runBlocking {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val probes = object : EnvironmentProbes {
            override fun suPresent() = false
            override fun ultraFingerprint(): String? = null
            override fun ksuNormal(): Boolean? {
                try {
                    while (true) {
                        try { release.await(); return null } catch (_: InterruptedException) { }
                    }
                } finally { exited.countDown() }
            }
        }
        val runner = EnvironmentCheckRunner(EnvironmentResolver(probes, store), store,
            BootIdSource { "current-boot" }, timeoutMillis = 50)
        try {
            val timeout = runner.check()
            assertEquals("ENV_UNKNOWN_TIMEOUT", timeout.code)
            assertEquals(EnvironmentCheckDetail.DEADLINE_EXCEEDED, timeout.detail)
            val busy = runner.check()
            assertEquals("ENV_UNKNOWN_BUSY", busy.code)
            assertEquals(EnvironmentCheckDetail.PROBE_STILL_RUNNING, busy.detail)
            assertEquals(previous, store.snapshot())
        } finally { release.countDown() }
        assertTrue(exited.await(1, TimeUnit.SECONDS))
    }
}
