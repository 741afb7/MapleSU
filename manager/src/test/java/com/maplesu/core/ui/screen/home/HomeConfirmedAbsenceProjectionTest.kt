package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.SuReadiness
import org.junit.Assert.*
import org.junit.Test

class HomeConfirmedAbsenceProjectionTest {
    private fun state() = HomeUiState(
        kernelVersion = KernelVersion(6, 6, 89), ksuVersion = null,
        managerUAPIVersion = 1, kernelUAPIVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false,
        isKernelPrBuild = false, requiresNewKernel = false, requiresNewManager = false,
        isRootAvailable = false, isSafeMode = false, isLateLoadMode = false,
        checkUpdateEnabled = false, latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 50201,
        systemInfo = SystemInfo("6.6.89", "5.2.1", "fixture", "fixture", "Unknown", 0),
    )

    @Test fun onlyCurrentCallerEvidenceEstablishesAbsenceForDetectorCandidates() {
        val cases = listOf(
            "unknown" to null,
            "indeterminate" to ModeState.INDETERMINATE,
            "foreign_root" to ModeState.FOREIGN_SU,
            "unproven_not_installed" to ModeState.NOT_INSTALLED,
        )
        val observations = cases.map { (name, detected) ->
            name to state().afterEnvironmentCheck(
                modeResolved = false, detectionComplete = detected != null,
                detectionFailure = null, detectedState = detected,
                knownUltra = false, failureDetail = null,
            )
        }
        for ((name, actual) in observations) {
            println("R20_ABSENCE name=$name pending=${actual.environmentChecking} absent=${actual.environmentConfirmedAbsent} unknown=${actual.environmentUnknown}")
        }
        // The caller sets this field only after the current session excludes both project identities.
        // A bare foreign detector enum supplies no such evidence; the same enum with proof is absent.
        for (detected in listOf(ModeState.NOT_INSTALLED, ModeState.FOREIGN_SU)) {
            val proven = state().copy(environmentConfirmedAbsent = true).afterEnvironmentCheck(
                modeResolved = false, detectionComplete = true,
                detectionFailure = null, detectedState = detected,
                knownUltra = false, failureDetail = null,
            )
            assertTrue("Current caller-confirmed absence: $detected", proven.environmentConfirmedAbsent)
            assertFalse(proven.environmentChecking)
            assertFalse(proven.litePending)
            assertFalse(proven.environmentUnknown)
        }
        println("R20_ABSENCE_TRUSTED pending=false absent=true count=2")
        val wrong = observations.filter { (_, actual) ->
            !actual.environmentChecking || !actual.litePending || actual.environmentConfirmedAbsent ||
                !actual.environmentUnknown || actual.isRootAvailable || actual.rootReadiness == SuReadiness.NOT_USABLE
        }.map { it.first }
        assertTrue("UNPROVEN_DETECTOR_STATE_MUST_NOT_ESTABLISH_ABSENCE: $wrong", wrong.isEmpty())
    }

    @Test fun aFinishedSessionKeepsHistoryOutOfIdentityAndOffersExplicitConfirmation() {
        val ready = state().copy(environmentMode = WorkMode.LITE, liteInstalled = true,
            environmentRuntimeReady = true, isRootAvailable = true, rootReadiness = SuReadiness.USABLE)
        val observing = state().copy(environmentChecking = true, litePending = true).mergedOver(ready)
        assertNull("History remains presentation only", observing.environmentMode)
        assertEquals(WorkMode.LITE, observing.displayEnvironmentMode)
        assertTrue(observing.showInstalledStatus)
        assertTrue(observing.showEnvironmentReady)
        assertFalse(observing.environmentNeedsConfirmation)

        val finished = state().copy(previousEnvironmentMode = WorkMode.LITE, cachedEnvironmentReady = true,
            ultraBrandVisible = true, ksuVersion = 1, environmentFailure = HomeEnvironmentFailure.TIMED_OUT,
            manualEnvironmentFailure = HomeEnvironmentFailure.TIMED_OUT)
            .mergedOver(observing)
        assertNull(finished.environmentMode)
        assertNull(finished.previousEnvironmentMode)
        assertNull(finished.displayEnvironmentMode)
        assertFalse(finished.cachedEnvironmentReady)
        assertFalse(finished.environmentChecking)
        assertFalse(finished.litePending)
        assertFalse(finished.environmentConfirmedAbsent)
        assertFalse(finished.showInstalledStatus)
        assertFalse(finished.showEnvironmentReady)
        assertTrue(finished.environmentUnknown)
        assertTrue(finished.environmentNeedsConfirmation)
        assertEquals(HomeEnvironmentFailure.TIMED_OUT, finished.environmentFailure)
        assertFalse(finished.showEnvironmentRefreshWarning)

        val component = ready.copy(ultraObservationFailure = HomeEnvironmentFailure.FAILED,
            ultraObservationDetail = "operation=environmentState;sdkError=FIXTURE", cachedEnvironmentReady = true)
        assertTrue(component.showUltraObservationFailure)
        assertFalse(component.environmentNeedsConfirmation)
        assertFalse(component.showEnvironmentReady)
        assertFalse(state().copy(environmentConfirmedAbsent = true).environmentNeedsConfirmation)
        val hidden = component.asLiteHiddenNotInstalled()
        assertFalse(hidden.environmentNeedsConfirmation)
        assertFalse(hidden.showUltraObservationFailure)
    }
}
