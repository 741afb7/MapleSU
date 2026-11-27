package dev.maplesu.manager.jailbreak

import org.junit.Assert.*
import org.junit.Test

class JailbreakPreparationResultTest {
    private val request = JailbreakRequest("dirtyfrag", "attempt", "boot", rootBackend = JailbreakRequest.BACKEND_HOOK)
    private fun status() = JailbreakStatus(
        method = request.method, stage = JailbreakStatus.STAGE_DAEMON_READY,
        message = "ready", startedAtMillis = 1, updatedAtMillis = 2,
        bootId = request.bootId, attemptId = request.attemptId, rootBackend = request.rootBackend,
    )

    @Test fun preparationFailureOutranksAnEarlierReadyRecord() {
        val result = preparedJailbreakOutcome(request, status().copy(failure = "SU_DIRECTORY_STATUS_WRITE_FAILED"))
        assertEquals(JailbreakStatus.STAGE_FAILED, result.stage)
        assertEquals("SU_DIRECTORY_STATUS_WRITE_FAILED", result.failure)
    }

    @Test fun sparseAdmissionFailureKeepsTheRequestedBackendAndReason() {
        val pending = status().copy(stage = JailbreakStatus.STAGE_IDLE)
        val token = "DIRTYFRAG_GUARD_PRESENT_REBOOT_REQUIRED"
        val failed = pending.advance(JailbreakStatus(
            method = request.method, stage = JailbreakStatus.STAGE_FAILED,
            message = "attempt failed", startedAtMillis = 0, updatedAtMillis = 3,
            bootId = request.bootId, failure = token,
        ))
        val result = preparedJailbreakOutcome(request, failed)
        assertEquals(request.rootBackend, failed.rootBackend)
        assertEquals(token, result.failure)
        assertEquals(JailbreakStatus.STAGE_FAILED, result.stage)
        assertNull(result.attemptId)
    }

    @Test fun aForeignBootCannotCompleteThisRequest() {
        assertNotNull(preparedJailbreakOutcome(request, status().copy(bootId = "old-boot")).failure)
    }

    @Test fun aForeignBackendCannotCompleteThisRequest() {
        assertNotNull(preparedJailbreakOutcome(request, status().copy(rootBackend = JailbreakRequest.BACKEND_KSU)).failure)
    }

    @Test fun automaticSelectionKeepsTheObservedBackend() {
        val automatic = request.copy(rootBackend = null)
        for (backend in listOf(null, JailbreakRequest.BACKEND_IR, JailbreakRequest.BACKEND_KSU)) {
            assertNull(preparedJailbreakOutcome(automatic, status().copy(rootBackend = backend)).failure)
        }
    }

    @Test fun successInvalidatesBeforeSchedulingDetection() {
        val events = mutableListOf<String>()
        val result = preparedJailbreakOutcome(request, status())
        refreshJailbreakEnvironment(result, { events += "invalidate" }, { events += "detect" })
        assertEquals(listOf("invalidate", "detect"), events)
        assertEquals(request.attemptId, result.attemptId)
    }

    @Test fun failedAndCancelledAttemptsLeaveEnvironmentAlone() {
        var calls = 0
        listOf(null, status().copy(failure = "failure"), status().copy(stage = JailbreakStatus.STAGE_CANCELLED)).forEach {
            refreshJailbreakEnvironment(preparedJailbreakOutcome(request, it), { calls++ }, { calls++ }, { calls++ })
        }
        assertEquals(0, calls)
    }

    @Test fun environmentSetupFinishesBeforeDetectionCanExposeModuleActions() {
        val events = mutableListOf<String>()
        refreshJailbreakEnvironment(preparedJailbreakOutcome(request, status()),
            invalidate = { events += "invalidate" }, detect = { events += "detect" },
            prepare = { events += "setup" })
        assertEquals(listOf("setup", "invalidate", "detect"), events)
    }

    @Test fun setupFailureStillRefreshesTheLiveRootObservation() {
        val events = mutableListOf<String>()
        val error = IllegalStateException("SDK_GATEWAY_UNAVAILABLE")
        try {
            refreshJailbreakEnvironment(preparedJailbreakOutcome(request, status()),
                invalidate = { events += "invalidate" }, detect = { events += "detect" },
                prepare = { events += "setup"; throw error })
            fail("Expected setup error")
        } catch (observed: IllegalStateException) {
            assertSame(error, observed)
        }
        assertEquals(listOf("setup", "invalidate", "detect"), events)
    }
}
