package dev.maplesu.manager.runtime.ultra

import java.util.concurrent.CancellationException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UltraRecoveryCancellationTest {
    private class AutoPort : UltraAutoSetupAttempt.Port {
        var failureAt: String? = null
        var failure: Exception = CancellationException("pass cancelled")
        var submitted = false
        var installs = 0
        var records = 0
        var refreshes = 0
        var outcome = UltraEnvironmentInstaller.Outcome(true, "OK")
        private fun step(name: String) { if (failureAt == name) throw failure }
        override fun blockedReason(): String? = null
        override fun state(): UltraEnvironmentProbe.State {
            step("state")
            return UltraEnvironmentProbe.State.NotInstalled
        }
        override fun alreadySubmitted() = submitted
        override fun markSubmitted(): Boolean { submitted = true; return true }
        override fun clearUnsubmitted() { submitted = false }
        override fun recordAttempt() { records++; step("record") }
        override fun install(): UltraEnvironmentInstaller.Outcome { installs++; return outcome }
        override fun refresh() { refreshes++; step("refresh") }
        fun run() = UltraAutoSetupAttempt.run(this)
    }

    private fun autoCancellationAt(boundary: String) {
        val original = CancellationException("cancel at $boundary")
        val port = AutoPort().apply { failureAt = boundary; failure = original }
        assertSame(original, assertThrows(CancellationException::class.java) { port.run() })
        val installed = boundary == "refresh"
        assertEquals(installed, port.submitted)
        assertEquals(if (installed) 1 else 0, port.installs)
        port.failureAt = null
        if (installed) {
            assertEquals("INSTALL_RECONCILIATION_REQUIRED", port.run().reason)
        } else {
            assertTrue(port.run().installed)
        }
        assertEquals(1, port.installs)
    }

    private fun autoInterruptionAt(boundary: String) {
        assertFalse(Thread.currentThread().isInterrupted)
        val original = InterruptedException("interrupt at $boundary")
        val port = AutoPort().apply { failureAt = boundary; failure = original }
        try {
            assertSame(original, assertThrows(InterruptedException::class.java) { port.run() })
            assertTrue("interruption flag lost at $boundary", Thread.currentThread().isInterrupted)
            assertEquals(boundary == "refresh", port.submitted)
            assertEquals(if (boundary == "refresh") 1 else 0, port.installs)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun autoProbeCancellationEndsThePassBeforeWrites() = autoCancellationAt("state")
    @Test fun autoDiagnosticCancellationEndsThePassBeforeSubmission() = autoCancellationAt("record")
    @Test fun autoRefreshCancellationKeepsTheKnownSubmittedGuard() = autoCancellationAt("refresh")
    @Test fun autoProbeInterruptionEndsThePassAndRestoresTheFlag() = autoInterruptionAt("state")
    @Test fun autoDiagnosticInterruptionEndsThePassAndRestoresTheFlag() = autoInterruptionAt("record")
    @Test fun autoRefreshInterruptionEndsThePassAndRestoresTheFlag() = autoInterruptionAt("refresh")

    @Test fun autoRefreshCancellationReleasesOnlyConfirmedUnsubmittedFailure() {
        for (unknown in listOf(false, true)) {
            val port = AutoPort().apply {
                failureAt = "refresh"
                outcome = UltraEnvironmentInstaller.Outcome(false, "REPLY_LOST", submissionUnknown = unknown)
            }
            assertSame(port.failure, assertThrows(CancellationException::class.java) { port.run() })
            assertEquals(unknown, port.submitted)
            port.failureAt = null
            port.outcome = UltraEnvironmentInstaller.Outcome(true, "OK")
            if (unknown) assertEquals("INSTALL_RECONCILIATION_REQUIRED", port.run().reason)
            else assertTrue(port.run().installed)
            assertEquals(if (unknown) 1 else 2, port.installs)
        }
    }

    @Test fun autoOrdinaryObservationFailuresKeepTheirExistingSemantics() {
        for (boundary in listOf("state", "record", "refresh")) {
            val port = AutoPort().apply {
                failureAt = boundary
                failure = IllegalStateException("ordinary observation failure")
            }
            val result = port.run()
            assertEquals(if (boundary == "state") "PROBE_FAILED" else "INSTALLED", result.reason)
            assertEquals(boundary != "state", result.installed)
        }
    }

    private class IdlePort : UltraIdleRecoveryAttempt.Port {
        var failureAt: String? = null
        var failure = CancellationException("idle pass cancelled")
        var submitted = false
        var submits = 0
        var idleChecks = 0
        private fun <T> step(name: String, value: T): T {
            if (failureAt == name) throw failure
            return value
        }
        override fun blockedReason(): String? = step("blocked", null)
        override fun alreadySubmitted() = step("guard", submitted)
        override fun idleVerified() = step("idle${++idleChecks}", true)
        override fun helperVerified() = step("helper", true)
        override fun channelVerified() = step("channel", true)
        override fun prepare(): String? = step("prepare", null)
        override fun markSubmitted(): Boolean {
            step("mark", Unit)
            submitted = true
            return step("marked", true)
        }
        override fun submit(): Boolean { submits++; return step("submit", true) }
        override fun running() = step("running", true)
        override fun functionalityVerified() = step("functionality", true)
        fun run() = UltraIdleRecoveryAttempt.run(this)
    }

    @Test fun idleCancellationBeforeTheGuardEndsThePassAndAllowsFreshRecovery() {
        for (boundary in listOf("blocked", "guard", "idle1", "helper", "channel", "idle2", "prepare", "idle3", "mark")) {
            val port = IdlePort().apply { failureAt = boundary }
            assertSame(boundary, port.failure, assertThrows(CancellationException::class.java) { port.run() })
            assertFalse(port.submitted)
            assertEquals(0, port.submits)
            port.failureAt = null
            assertTrue(port.run()!!.installed)
            assertEquals(1, port.submits)
        }
    }

    @Test fun idleCancellationAfterTheGuardKeepsTheGuardWithoutReplaying() {
        for (boundary in listOf("marked", "submit", "running", "functionality")) {
            val port = IdlePort().apply { failureAt = boundary }
            assertSame(boundary, port.failure, assertThrows(CancellationException::class.java) { port.run() })
            assertTrue(port.submitted)
            val submittedBeforeRetry = port.submits
            port.failureAt = null
            assertEquals("IDLE_RECOVERY_ALREADY_SUBMITTED", port.run()!!.reason)
            assertEquals(submittedBeforeRetry, port.submits)
        }
    }

    @Test fun queuedAutoRecoveryAfterCancellationRunsWithoutCompletingTheCancelledPass() = runBlocking {
        for (boundary in listOf("state", "record", "refresh")) {
            val owner = SupervisorJob()
            val scope = CoroutineScope(owner + Dispatchers.Unconfined)
            try {
                val release = CompletableDeferred<Unit>()
                val done = CompletableDeferred<Unit>()
                val port = AutoPort().apply { failureAt = boundary }
                var passes = 0
                var completions = 0
                val failures = mutableListOf<Exception>()
                val requests = UltraRecoveryRequests(scope, recover = {
                    if (++passes == 1) {
                        release.await()
                        port.run()
                        completions++
                    } else done.complete(Unit)
                }, failed = { failures += it })
                requests.request()
                requests.request()
                release.complete(Unit)
                withTimeout(2_000L) { done.await() }
                assertEquals(boundary, 2, passes)
                assertEquals("cancelled $boundary pass fell through", 0, completions)
                assertTrue(failures.isEmpty())
            } finally { owner.cancelAndJoin() }
        }
    }

    @Test fun queuedIdleRecoveryAfterCancellationRunsWithoutCompletingTheCancelledPass() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            val done = CompletableDeferred<Unit>()
            val port = IdlePort().apply { failureAt = "prepare" }
            var passes = 0
            var completions = 0
            val failures = mutableListOf<Exception>()
            val requests = UltraRecoveryRequests(scope, recover = {
                if (++passes == 1) {
                    release.await()
                    port.run()
                    completions++
                } else done.complete(Unit)
            }, failed = { failures += it })
            requests.request()
            requests.request()
            release.complete(Unit)
            withTimeout(2_000L) { done.await() }
            assertEquals(2, passes)
            assertEquals("cancelled idle pass fell through", 0, completions)
            assertTrue(failures.isEmpty())
        } finally { owner.cancelAndJoin() }
    }
}
