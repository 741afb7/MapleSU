package com.maplesu.core.ui.viewmodel

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HomeRefreshSingleFlightTest {
    @Test fun repeatedRequestsDuringModeWaitAndProbeDoNotQueueAnotherRefresh() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        val modeReady = CompletableDeferred<Unit>()
        val probeReady = CompletableDeferred<Unit>()
        val probing = CompletableDeferred<Unit>()
        var waits = 0
        var probes = 0
        var publications = 0
        val first = flight.launch(this) {
            waits++
            modeReady.await()
            probes++
            probing.complete(Unit)
            probeReady.await()
            publications++
        }
        repeat(8) { assertNull(flight.launch(this) { waits++; probes++; publications++ }) }
        yield()
        assertEquals(1, waits)
        modeReady.complete(Unit)
        probing.await()
        repeat(8) { assertNull(flight.launch(this) { waits++; probes++; publications++ }) }
        probeReady.complete(Unit)
        first!!.join()
        assertEquals(1, waits)
        assertEquals(1, probes)
        assertEquals(1, publications)
        flight.launch(this) { publications++ }!!.join()
        assertEquals(2, publications)
    }

    @Test fun cancellationDuringModeWaitReleasesTheClaim() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        val entered = CompletableDeferred<Unit>()
        val first = flight.launch(this) { entered.complete(Unit); awaitCancellation() }
        entered.await()
        first!!.cancelAndJoin()
        assertNotNull(flight.launch(this) {})
    }

    @Test fun aScopeCancelledBeforeLaunchDoesNotKeepTheClaim() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        val scope = CoroutineScope(Job().also { it.cancel() })
        flight.launch(scope) { fail("Cancelled scope must not run the refresh") }!!.join()
        flight.launch(this) {}!!.join()
    }

    @Test fun aFailureBeforeTheCoroutineStartsDoesNotKeepTheClaim() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        val expected = IllegalStateException("scope startup failed")
        val brokenScope = object : CoroutineScope {
            override val coroutineContext: kotlin.coroutines.CoroutineContext
                get() = throw expected
        }
        assertSame(expected, runCatching { flight.launch(brokenScope) {} }.exceptionOrNull())
        val retry = flight.launch(this) {}
        assertNotNull("A failed launch must release its claim before a later retry", retry)
        retry!!.join()
        flight.runAfterCurrent {}
    }

    @Test fun failedRefreshReleasesTheClaim() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        try {
            flight.launch(scope) { error("probe failed") }!!.join()
            flight.launch(this) {}!!.join()
        } finally { scope.cancel() }
    }

    @Test fun manualRetryWaitsForTheActiveSeriesThenOwnsAllOfItsStages() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        val automaticEntered = CompletableDeferred<Unit>()
        val automaticDone = CompletableDeferred<Unit>()
        val inspectionEntered = CompletableDeferred<Unit>()
        val inspectionDone = CompletableDeferred<Unit>()
        val probeEntered = CompletableDeferred<Unit>()
        val probeDone = CompletableDeferred<Unit>()
        val automatic = flight.launch(this) { automaticEntered.complete(Unit); automaticDone.await() }!!
        automaticEntered.await()
        val manual = launch {
            flight.runAfterCurrent {
                inspectionEntered.complete(Unit)
                inspectionDone.await()
                probeEntered.complete(Unit)
                probeDone.await()
            }
        }
        yield()
        assertFalse(inspectionEntered.isCompleted)
        automaticDone.complete(Unit)
        automatic.join()
        inspectionEntered.await()
        assertNull(flight.launch(this) { fail("An automatic retry must not overlap manual inspection") })
        inspectionDone.complete(Unit)
        probeEntered.await()
        assertTrue(manual.isActive)
        assertNull(flight.launch(this) { fail("The claim must cover the entire manual series") })
        probeDone.complete(Unit)
        manual.join()
        flight.launch(this) {}!!.join()
    }

    @Test fun aCancelledManualWaitDoesNotReleaseTheActiveAutomaticClaim() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        val automaticEntered = CompletableDeferred<Unit>()
        val automaticDone = CompletableDeferred<Unit>()
        val automatic = flight.launch(this) { automaticEntered.complete(Unit); automaticDone.await() }!!
        automaticEntered.await()
        val manual = launch { flight.runAfterCurrent { fail("Cancelled waiter must not run") } }
        yield()
        manual.cancelAndJoin()
        assertNull(flight.launch(this) { fail("Automatic work still owns the claim") })
        automaticDone.complete(Unit)
        automatic.join()
        flight.runAfterCurrent {}
    }

    @Test fun manualFailureAndCancellationBothReleaseTheirClaim() = runBlocking {
        val flight = HomeRefreshSingleFlight()
        assertTrue(runCatching { flight.runAfterCurrent { error("manual probe failed") } }.isFailure)
        flight.launch(this) {}!!.join()
        val entered = CompletableDeferred<Unit>()
        val manual = launch { flight.runAfterCurrent { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        manual.cancelAndJoin()
        flight.runAfterCurrent {}
    }
}
