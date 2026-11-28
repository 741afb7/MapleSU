package dev.maplesu.manager.workmode.boot

import dev.maplesu.manager.workmode.ModeRuntime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class ModeBootstrapRetryAwaitTest {
    private fun field(name: String) = ModeBootstrap::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun aManualRetryWaitsForItsQueuedAttemptInsteadOfTheLastCompletedRound() = runBlocking {
        val started = field("started").get(null) as AtomicBoolean
        val scheduled = field("secondPhaseScheduled").get(null) as AtomicBoolean
        val manager = field("managerProcess")
        val retry = field("secondPhaseRetry").get(null) as ModeBootstrapRetryState
        val wasStarted = started.get()
        val wasScheduled = scheduled.get()
        val wasManager = manager.getBoolean(null)
        ModeRuntime.resetForTest()
        try {
            started.set(true)
            manager.setBoolean(null, true)
            // Hold the existing timer so the request is definitely queued, not executing.
            scheduled.set(true)
            ModeRuntime.finishDetection()
            val requested: Any? = ModeBootstrap.retryEnvironmentDetection()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                if (requested is Deferred<*>) requested.await()
                else ModeRuntime.awaitDetectionSettled(500)
                true
            }
            assertFalse("manual retry reused the preceding round's completed signal", waiting.isCompleted)
            assertNull(ModeRuntime.active)
            assertNotNull(retry.tryStart())
            ModeRuntime.beginDetection()
            ModeRuntime.finishDetection()
            retry.finish(resolved = false)
            assertFalse((requested as Deferred<*>).isCompleted)
            repeat(2) { retry.tryStart(); retry.finish(resolved = false) }
            assertTrue(requested.isCompleted)
            assertNotNull(withTimeoutOrNull(50) { waiting.await() })
        } finally {
            retry.finish(resolved = true)
            started.set(wasStarted)
            scheduled.set(wasScheduled)
            manager.setBoolean(null, wasManager)
            ModeRuntime.resetForTest()
        }
    }

    @Test fun aRejectedScheduleFinishesItsOwnRequestWithAnExplicitFailure() {
        val started = field("started").get(null) as AtomicBoolean
        val scheduled = field("secondPhaseScheduled").get(null) as AtomicBoolean
        val manager = field("managerProcess")
        val retry = field("secondPhaseRetry").get(null) as ModeBootstrapRetryState
        val wasStarted = started.get()
        val wasScheduled = scheduled.get()
        val wasManager = manager.getBoolean(null)
        ModeRuntime.resetForTest()
        try {
            started.set(true)
            manager.setBoolean(null, true)
            scheduled.set(true)
            val completion = ModeBootstrap.retryEnvironmentDetection()!!
            assertFalse(completion.isCompleted)
            scheduled.set(false)
            var posts = 0
            ModeBootstrap.schedulePhaseTwo(0) { _, _ -> posts++; false }
            assertEquals(1, posts)
            assertFalse(scheduled.get())
            assertTrue(completion.isCompleted)
            assertTrue(ModeRuntime.detectionComplete)
            assertEquals("MODE_PHASE_TWO_SCHEDULE_FAILED", ModeRuntime.detectionFailure)
            assertNull(ModeRuntime.active)
        } finally {
            retry.finish(resolved = true)
            started.set(wasStarted)
            scheduled.set(wasScheduled)
            manager.setBoolean(null, wasManager)
            ModeRuntime.resetForTest()
        }
    }

    @Test fun startupAndAuxiliaryProcessesDoNotQueueManualDetection() {
        val started = field("started").get(null) as AtomicBoolean
        val manager = field("managerProcess")
        val wasStarted = started.get()
        val wasManager = manager.getBoolean(null)
        try {
            started.set(false)
            manager.setBoolean(null, true)
            assertNull(ModeBootstrap.retryEnvironmentDetection())
            started.set(true)
            manager.setBoolean(null, false)
            assertNull(ModeBootstrap.retryEnvironmentDetection())
        } finally {
            started.set(wasStarted)
            manager.setBoolean(null, wasManager)
        }
    }
}
