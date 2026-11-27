package dev.maplesu.manager.jailbreak

import android.content.Context
import dev.maplesu.manager.runtime.NativeSuDaemon
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs the production controller with only the Android/service boundary replaced. */
class JailbreakControllerOutcomeTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context() = Context(temp.newFolder())
    private fun ready(request: JailbreakRequest) = JailbreakStatus(
        method = request.method, stage = JailbreakStatus.STAGE_DAEMON_READY,
        message = "ready", startedAtMillis = 1, updatedAtMillis = 2,
        bootId = request.bootId, attemptId = request.attemptId, rootBackend = request.rootBackend,
    )
    private fun run(backend: String?, answer: (Context, JailbreakRequest) -> Unit): JailbreakController.Outcome {
        NativeSuDaemon.prepare = answer
        return JailbreakController.run(context(), JailbreakMethod.DIRTYFRAG, rootBackend = backend)
    }

    @Test fun normalAndLeaseRejectReadyFromAnotherBoot() {
        for (backend in listOf(JailbreakRequest.BACKEND_KSU, null)) {
            val outcome = run(backend) { context, request ->
                ready(request).copy(bootId = "previous-boot").write(context).getOrThrow()
            }
            assertEquals("backend=$backend", JailbreakStatus.STAGE_FAILED, outcome.stage)
            assertEquals("JAILBREAK_ATTEMPT_NOT_CONFIRMED", outcome.failure)
        }
    }

    @Test fun explicitNormalRequestRejectsAReadyUltraBackend() {
        val outcome = run(JailbreakRequest.BACKEND_KSU) { context, request ->
            ready(request).copy(rootBackend = JailbreakRequest.BACKEND_HOOK).write(context).getOrThrow()
        }
        assertEquals(JailbreakStatus.STAGE_FAILED, outcome.stage)
        assertEquals("JAILBREAK_ATTEMPT_NOT_CONFIRMED", outcome.failure)
    }

    @Test fun automaticRouteAcceptsTheActuallySelectedIrBackend() {
        val outcome = run(null) { context, request ->
            ready(request).copy(rootBackend = JailbreakRequest.BACKEND_IR).write(context).getOrThrow()
        }
        assertEquals(JailbreakStatus.STAGE_DAEMON_READY, outcome.stage)
        assertNull(outcome.failure)
    }

    @Test fun missingStatusAndUnsubmittedFailureNeverBorrowAStaleFailureOrCancellation() {
        for (stage in listOf(JailbreakStatus.STAGE_FAILED, JailbreakStatus.STAGE_CANCELLED)) {
            val outcome = run(JailbreakRequest.BACKEND_KSU) { context, request ->
                ready(request).copy(attemptId = "other-attempt", stage = stage,
                    failure = "PREVIOUS_FAILURE").write(context).getOrThrow()
                throw IllegalStateException("CURRENT_REQUEST_NOT_SUBMITTED")
            }
            assertEquals(JailbreakStatus.STAGE_FAILED, outcome.stage)
            assertEquals("CURRENT_REQUEST_NOT_SUBMITTED", outcome.failure)
        }
    }

    @Test fun currentProviderFailureAndCancellationKeepTheirFirstError() {
        for (stage in listOf(JailbreakStatus.STAGE_FAILED, JailbreakStatus.STAGE_CANCELLED)) {
            val outcome = run(JailbreakRequest.BACKEND_KSU) { context, request ->
                ready(request).copy(stage = stage, failure = "CURRENT_PROVIDER_FAILURE")
                    .write(context).getOrThrow()
                throw IllegalStateException("SECONDARY_TRANSPORT_ERROR")
            }
            assertEquals(stage, outcome.stage)
            assertEquals("CURRENT_PROVIDER_FAILURE", outcome.failure)
        }
    }
}
