package dev.maplesu.manager.runtime.ultra

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Current owner-scoped restore lifetime; decision/revoke callbacks are explicit SDK boundaries. */
class UltraPersistentAuthorizationTest {
    @Test fun sameBootOwnerReplacementReconcilesTheNewScope() {
        val session = UltraBootAuthorizationRestore()
        val owners = listOf(UltraRuntimeClient.ManagerOwnerIdentity(10999, 1, "host"),
            UltraRuntimeClient.ManagerOwnerIdentity(11001, 2, "host"))
        var calls = 0
        for (owner in owners) assertEquals("OK", session.ensure("boot", owner.toString()) { calls++; "OK" })
        assertEquals(2, calls)
    }

    @Test fun sameUidNewInstallationRequiresExplicitScopeInvalidation() {
        val session = UltraBootAuthorizationRestore()
        var calls = 0
        repeat(2) { assertEquals("OK", session.ensure("boot", "owner") { calls++; "OK" }) }
        assertEquals(1, calls)
        session.invalidate()
        assertEquals("OK", session.ensure("boot", "owner") { calls++; "OK" })
        assertEquals(2, calls)
    }

    @Test fun revocationInvalidatesTheEarlierSuccessBeforeAnExplicitReconciliation() {
        val session = UltraBootAuthorizationRestore()
        var grants = 0
        var revokes = 0
        assertEquals("OK", session.ensure("boot", "owner") { grants++; "OK" })
        session.invalidate()
        assertEquals("OK", session.ensure("boot", "owner") { revokes++; "OK" })
        assertEquals(1, grants)
        assertEquals(1, revokes)
    }

    @Test fun unreadableListIsNotCachedAsSuccessfulRestoration() {
        val session = UltraBootAuthorizationRestore { error("No proof of pre-commit failure") }
        var reads = 0
        repeat(2) { assertEquals("MANAGER_AUTH_LIST_UNREADABLE", session.ensure("boot", "owner") {
            reads++; "MANAGER_AUTH_LIST_UNREADABLE"
        }) }
        assertEquals(2, reads)
    }

    @Test fun transientWriteFailureRequiresAnotherExplicitAttemptWithoutACommitProof() {
        val session = UltraBootAuthorizationRestore { error("Ordinary errors do not authorize replay") }
        var writes = 0
        assertEquals("MAPLESU_SDK_BUSY", session.ensure("boot", "owner") { writes++; "MAPLESU_SDK_BUSY" })
        assertEquals(1, writes)
        assertEquals("OK", session.ensure("boot", "owner") { writes++; "OK" })
        assertEquals(2, writes)
    }

    @Test fun failedReadbackNeverConfirmsAPersistentGrant() {
        val session = UltraBootAuthorizationRestore()
        var writes = 0
        repeat(3) { assertEquals("MANAGER_AUTH_READBACK_FAILED", session.ensure("boot", "owner") {
            writes++; "MANAGER_AUTH_READBACK_FAILED"
        }) }
        assertEquals(3, writes)
        assertEquals("OK", session.ensure("boot", "owner") { writes++; "OK" })
        assertEquals(4, writes)
    }

    @Test fun unknownIdentityForTwoThousandPassesDoesNotCreateAGrant() {
        var passes = 0
        var grants = 0
        val results = mutableListOf<String>()
        val loop = UltraBootAuthorizationRestore { delay -> assertEquals(5_000L, delay); passes++ }
        assertEquals("SUSPENDED", loop.maintain({ passes == 2_001 }, {
            if (passes < 2_000) "MANAGER_IDENTITY_PENDING" else { grants++; "OK" }
        }, results::add))
        assertEquals(listOf("MANAGER_IDENTITY_PENDING", "OK"), results)
        assertEquals(1, grants)
    }

    @Test fun changedSignerInvalidatesReadinessUntilAnExplicitCurrentScopeSucceeds() {
        val session = UltraBootAuthorizationRestore()
        var writes = 0
        assertEquals("OK", session.ensure("boot", "owner") { writes++; "OK" })
        session.invalidate()
        assertEquals("MANAGER_IDENTITY_PENDING", session.ensure("boot", "owner") { "MANAGER_IDENTITY_PENDING" })
        assertEquals(1, writes)
        assertEquals("OK", session.ensure("boot", "owner-new") { writes++; "OK" })
        assertEquals(2, writes)
    }

    @Test fun anotherUsersOwnerNeverSharesThePrimaryUsersRestoredScope() {
        val session = UltraBootAuthorizationRestore()
        var calls = 0
        for (uid in listOf(10999, 110999)) {
            val owner = UltraRuntimeClient.ManagerOwnerIdentity(uid, 1, "host")
            assertEquals("OK", session.ensure("boot", owner.toString()) { calls++; "OK" })
        }
        assertEquals(2, calls)
    }

    @Test fun failedRevocationRetainsThePrimaryAndInverseErrorsWithoutReplay() {
        val primary = UltraAuthorizationAttempt.local("READBACK_FAILED")
        val inverse = UltraAuthorizationAttempt.local("REVOKE_FAILED")
        val expected = UltraAuthorizationRestoreResult.identityChanged(primary, inverse)
        val session = UltraBootAuthorizationRestore { error("No replay of a partial mutation") }
        val actual = session.ensureAttempt("boot", "owner") { expected }
        assertSame(primary, actual.primary)
        assertSame(inverse, actual.inverse)
        assertEquals("READBACK_FAILED", actual.primary!!.error)
        assertEquals("MANAGER_IDENTITY_CHANGED:REVOKE_FAILED", actual.status)
    }

    @Test fun scopeChangingDuringGrantKeepsItsReceiptAndDoesNotCacheSuccess() {
        val session = UltraBootAuthorizationRestore()
        val attempt = UltraAuthorizationAttempt.local().withReadback(true)
        var changed = false
        val result = session.ensureAttempt("boot", "owner", { changed }) {
            changed = true
            UltraAuthorizationRestoreResult.primary(attempt)
        }
        assertEquals("SUSPENDED", result.status)
        assertSame(attempt, result.primary)
        changed = false
        var calls = 0
        assertEquals("OK", session.ensure("boot", "owner") { calls++; "OK" })
        assertEquals(1, calls)
    }

    @Test fun scopeRevokedDuringACachedPassIsCheckedBeforeReturningSuccess() {
        val session = UltraBootAuthorizationRestore()
        assertEquals("OK", session.ensure("boot", "owner") { "OK" })
        assertEquals("SUSPENDED", session.ensure("boot", "owner", { true }) { error("revoked scope") })
        var reads = 0
        assertEquals("OK", session.ensure("boot", "owner") { reads++; "OK" })
        assertEquals(1, reads)
    }

    @Test fun stableScopeDoesNotPollOrPeriodicallyRegrant() {
        val session = UltraBootAuthorizationRestore()
        var writes = 0
        repeat(2_000) { assertEquals("OK", session.ensure("boot", "owner") { writes++; "OK" }) }
        assertEquals(1, writes)
    }

    @Test fun unknownBootDoesNotCreateAnUnboundedRestorationCache() {
        val session = UltraBootAuthorizationRestore()
        var writes = 0
        repeat(3) { assertEquals("OK", session.ensure(null, "owner") { writes++; "OK" }) }
        assertEquals(3, writes)
    }

    @Test fun concurrentReadinessRequestsPublishOneStableRestoration() {
        val session = UltraBootAuthorizationRestore()
        var writes = 0
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = pool.invokeAll((1..24).map { Callable {
                session.ensure("boot", "owner") { writes++; "OK" }
            } })
            results.forEach { assertEquals("OK", it.get(2, TimeUnit.SECONDS)) }
            assertEquals(1, writes)
        } finally { pool.shutdownNow() }
    }

    @Test fun lifecycleLoopContinuesAfterSuccessAndReportsOnlyChangedResults() {
        val statuses = listOf("OK", "OK", "MANAGER_IDENTITY_PENDING", "MANAGER_IDENTITY_PENDING",
            "MANAGER_AUTH_LIST_UNREADABLE", "OK")
        var passes = 0
        val delays = mutableListOf<Long>()
        val observed = mutableListOf<String>()
        val loop = UltraBootAuthorizationRestore { delay -> delays += delay; passes++ }
        assertEquals("SUSPENDED", loop.maintain({ passes == statuses.size }, { statuses[passes] }, observed::add))
        assertEquals(List(statuses.size) { 5_000L }, delays)
        assertEquals(listOf("OK", "MANAGER_IDENTITY_PENDING", "MANAGER_AUTH_LIST_UNREADABLE", "OK"), observed)
    }

    @Test fun brokerRetriesOrdinaryErrorsAndExceptionsWithoutUserAction() {
        val expected = listOf("MAPLESU_SDK_BUSY", "MANAGER_AUTH_LIST_UNREADABLE",
            "MANAGER_AUTH_RESTORE_EXCEPTION", "ERR_MODULE_PARAM", "OK")
        var passes = 0
        val observed = mutableListOf<String>()
        val loop = UltraBootAuthorizationRestore { passes++ }
        assertEquals("SUSPENDED", loop.maintain({ passes == expected.size }, {
            if (passes == 2) throw IllegalStateException("PM temporarily unavailable")
            expected[passes]
        }, observed::add))
        assertEquals(expected, observed)
    }

    @Test fun exceptionAfterExplicitInvalidationDoesNotRestoreACachedSuccess() {
        val session = UltraBootAuthorizationRestore()
        var calls = 0
        assertEquals("OK", session.ensure("boot", "owner") { calls++; "OK" })
        session.invalidate()
        assertThrows(IllegalStateException::class.java) {
            session.ensure("boot", "owner") { calls++; throw IllegalStateException("unavailable") }
        }
        assertEquals("OK", session.ensure("boot", "owner") { calls++; "OK" })
        assertEquals(3, calls)
    }

    @Test fun disabledLoopStopsBeforeReadingAndAfterItsPause() {
        var disabled = true
        var reads = 0
        val loop = UltraBootAuthorizationRestore { disabled = true }
        assertEquals("SUSPENDED", loop.maintain({ disabled }, { reads++; "OK" }))
        assertEquals(0, reads)
        disabled = false
        assertEquals("SUSPENDED", loop.maintain({ disabled }, { reads++; "OK" }))
        assertEquals(1, reads)
    }

    @Test fun disabledReceiptStopsWithoutAnotherPause() {
        val loop = UltraBootAuthorizationRestore { error("disabled loop must stop") }
        assertEquals("SUSPENDED", loop.maintain({ false }, { "SUSPENDED" }))
    }

    @Test fun interruptionBeforeWorkOrDuringPausePreservesCancellation() {
        try {
            Thread.currentThread().interrupt()
            assertEquals("MAPLESU_SDK_INTERRUPTED", UltraBootAuthorizationRestore().maintain(
                { error("interrupted scope") }, { error("interrupted write") }))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        try {
            assertEquals("MAPLESU_SDK_INTERRUPTED", UltraBootAuthorizationRestore { throw InterruptedException() }
                .maintain({ false }, { "OK" }))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun interruptionFromSdkRestorationPreservesTheFlag() {
        try {
            assertEquals("MAPLESU_SDK_INTERRUPTED", UltraBootAuthorizationRestore { error("unexpected pause") }
                .maintain({ false }, { throw InterruptedException() }))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun actualSleepingWorkerStopsWhenInterrupted() {
        val started = CountDownLatch(1)
        val result = AtomicReference<String>()
        val interrupted = AtomicReference<Boolean>()
        val worker = Thread {
            result.set(UltraBootAuthorizationRestore().maintain({ false }, { "OK" }, { started.countDown() }))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            worker.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000L)
            assertFalse(worker.isAlive)
            assertEquals("MAPLESU_SDK_INTERRUPTED", result.get())
            assertEquals(true, interrupted.get())
        } finally { worker.interrupt(); worker.join(2_000L) }
    }

    @Test fun cancellationAndErrorsAreNotConvertedIntoBackgroundRetries() {
        for (failure in listOf(CancellationException("cancelled"), AssertionError("failed"))) {
            val loop = UltraBootAuthorizationRestore { error("unexpected retry") }
            val caught = assertThrows(failure.javaClass) { loop.maintain({ false }, { throw failure }) }
            assertSame(failure, caught)
        }
    }
}
