package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.runtime.ultra.UltraStartupWarmup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KsuProbeTraceTest {
    private fun detect(probe: KsuKernelProbe): DetectionResult = runBlocking {
        DefaultModeDetector(ksuProbe = probe, bootIdSource = BootIdSource { "current-boot" })
            .detect(DetectionRequest(DetectionOrigin.STARTUP, allowRootProbe = false, allowLiteExecutor = false))
    }

    @Test fun probeFailureRemainsUnknownForBothKernelAndManagerEvidence() {
        val result = detect(KsuKernelProbe { error("probe failed") })
        assertNull(result.trace.single { it.id == "S1" }.ok)
        assertNull(result.trace.single { it.id == "S2" }.ok)
    }

    @Test fun explicitNonResponseRemainsNegativeEvidence() {
        val result = detect(KsuKernelProbe { KsuFacts(responded = false, isManager = false) })
        assertEquals(false, result.trace.single { it.id == "S1" }.ok)
        assertEquals(false, result.trace.single { it.id == "S2" }.ok)
    }

    @Test fun respondingManagerRemainsPositiveEvidence() {
        val result = detect(KsuKernelProbe { KsuFacts(responded = true, version = 1, isManager = true) })
        assertEquals(true, result.trace.single { it.id == "S1" }.ok)
        assertEquals(true, result.trace.single { it.id == "S2" }.ok)
    }

    @Test fun failedProbeDoesNotUnlockPendingInstallWarmup() {
        val result = detect(KsuKernelProbe { error("probe failed") })
        var called = false
        val warmup = UltraStartupWarmup.run(
            UltraStartupWarmup.Input(
                activeMode = null, detectionSettled = true,
                ksuResponded = result.trace.single { it.id == "S1" }.ok,
                pendingInstall = true, suspended = false, quiescing = false,
                switchPending = false, conflictingRoot = false,
            ),
            setupEnvironment = { called = true }, pairEnvironment = { called = true; true },
            retryDetection = { called = true },
        )
        assertFalse(warmup.ran)
        assertFalse(called)
    }
}
