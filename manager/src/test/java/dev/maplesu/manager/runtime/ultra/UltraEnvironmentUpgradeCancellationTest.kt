package dev.maplesu.manager.runtime.ultra

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class UltraEnvironmentUpgradeCancellationTest {
    @Test fun knownUnsubmittedPreparationCancellationReleasesTheGuardAndPropagatesOriginal() {
        val original = CancellationException("preparation cancelled")
        var guarded = true
        assertSame(original, assertThrows(CancellationException::class.java) {
            UltraEnvironmentUpgrade.submitMarked({ throw UltraInstallPreparationCancelled(original) }, { guarded = false })
        })
        assertFalse(guarded)
    }

    @Test fun unknownSubmissionCancellationRetainsTheGuardAndPropagatesOriginal() {
        val original = CancellationException("transport cancelled")
        var guarded = true
        assertSame(original, assertThrows(CancellationException::class.java) {
            UltraEnvironmentUpgrade.submitMarked({ throw original }, { guarded = false })
        })
        assertTrue(guarded)
    }

    @Test fun unknownSubmissionInterruptionRetainsGuardRestoresFlagAndPropagatesOriginal() {
        assertFalse(Thread.currentThread().isInterrupted)
        val original = InterruptedException("transport interrupted")
        var guarded = true
        try {
            assertSame(original, assertThrows(InterruptedException::class.java) {
                UltraEnvironmentUpgrade.submitMarked({ throw original }, { guarded = false })
            })
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(guarded)
        } finally { Thread.interrupted() }
    }
}
