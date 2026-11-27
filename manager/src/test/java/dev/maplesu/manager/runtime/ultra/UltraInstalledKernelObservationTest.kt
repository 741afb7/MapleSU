package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.installer.InstalledUltraCarrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UltraInstalledKernelObservationTest {
    private val carrier = InstalledUltraCarrier("boot", "boot", "_a", "a".repeat(64), "6.6", true)

    @Test fun sameScopeReadKeepsTheExactVerifiedFactWithoutNewWork() {
        val cache = UltraInstalledKernelObservation()
        assertTrue(cache.publish(cache.revision, carrier) { true })
        repeat(3) { assertEquals(carrier, cache.current()) }
        assertEquals(0L, cache.revision)
        assertEquals(1L, cache.revisions.value)
    }

    @Test fun installInvalidationRejectsAReadThatStartedBeforeTheMutation() {
        val cache = UltraInstalledKernelObservation()
        val started = cache.revision
        cache.invalidate()
        assertFalse(cache.publish(started, carrier) { true })
        assertNull(cache.current())
    }

    @Test fun aNewReadAfterInvalidationCanPublishOnlyItsNewScope() {
        val cache = UltraInstalledKernelObservation()
        assertTrue(cache.publish(cache.revision, carrier) { true })
        cache.invalidate()
        assertNull(cache.current())
        val next = carrier.copy(partitionSha256 = "b".repeat(64), updateRequired = false)
        assertTrue(cache.publish(cache.revision, next) { true })
        assertEquals(next, cache.current())
    }

    @Test fun callerScopeChangeRevokesTheCachedFact() {
        val cache = UltraInstalledKernelObservation()
        var current = true
        assertTrue(cache.publish(cache.revision, carrier) { current })
        current = false
        assertNull(cache.current())
    }

    @Test fun aScopeChangeBeforePublicationNeverAddsAnInstalledFact() {
        val cache = UltraInstalledKernelObservation()
        assertFalse(cache.publish(cache.revision, carrier) { false })
        assertNull(cache.current())
    }

    @Test fun staleAbsenceDoesNotEraseANewerInstalledObservation() {
        val cache = UltraInstalledKernelObservation()
        val before = cache.revision
        cache.invalidate()
        assertTrue(cache.publish(cache.revision, carrier) { true })
        assertFalse(cache.discard(before))
        assertEquals(carrier, cache.current())
        assertTrue(cache.discard(cache.revision))
        assertNull(cache.current())
    }

    @Test fun installationDuringAnInFlightReadRejectsItsLateResult() {
        val cache = UltraInstalledKernelObservation()
        val reading = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var published = true
        val worker = Thread {
            val captured = cache.revision
            reading.countDown()
            if (completed.await(2, TimeUnit.SECONDS)) {
                published = cache.publish(captured, carrier) { true }
            }
        }
        worker.start()
        assertTrue(reading.await(2, TimeUnit.SECONDS))
        cache.invalidate()
        completed.countDown()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertFalse(published)
        assertNull(cache.current())
    }
}
