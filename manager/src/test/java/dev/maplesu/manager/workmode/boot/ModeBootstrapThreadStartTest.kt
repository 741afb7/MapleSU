package dev.maplesu.manager.workmode.boot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test

class ModeBootstrapThreadStartTest {
    @Test fun firstPhaseStartFailureReleasesTheCompletionWithoutRunningTheProbe() {
        val expected = OutOfMemoryError("pthread_create failed")
        val completed = CompletableDeferred<Unit>()
        var cause: Throwable? = null
        var probed = false
        ModeBootstrap.startDetectionThread("failed-phase-one", onStartFailure = {
            cause = it
            completed.complete(Unit)
        }, startThread = { throw expected }) { probed = true }
        assertSame(expected, cause)
        assertTrue(completed.isCompleted)
        assertFalse(probed)
    }

    @Test fun phaseTwoStartFailureReleasesItsClaimForTheNextAttempt() {
        val retry = ModeBootstrapRetryState(3, 750)
        val completion = retry.requestRetry().completion
        assertEquals(1, retry.tryStart())
        var delay: Long? = null
        ModeBootstrap.startDetectionThread("failed-phase-two", onStartFailure = {
            delay = retry.finish(resolved = false)
        }, startThread = { throw OutOfMemoryError("pthread_create failed") }) { fail("Probe was started") }
        assertEquals(750L, delay)
        assertFalse(completion.isCompleted)
        assertEquals(2, retry.tryStart())
        assertEquals(1_500L, retry.finish(resolved = false))
        assertEquals(3, retry.tryStart())
        assertNull(retry.finish(resolved = false))
        assertTrue(completion.isCompleted)
    }

    @Test fun successfulLaunchRunsExactlyOnceWithoutAFailureCallback() {
        val completed = CountDownLatch(1)
        ModeBootstrap.startDetectionThread("successful-launch", onStartFailure = { throw AssertionError(it) }) {
            completed.countDown()
        }
        assertTrue(completed.await(1, TimeUnit.SECONDS))
    }
}
