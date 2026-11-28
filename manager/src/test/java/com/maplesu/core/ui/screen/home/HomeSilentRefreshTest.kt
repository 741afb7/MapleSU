package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.SuReadiness
import org.junit.Assert.*
import org.junit.Test

class HomeSilentRefreshTest {
    private fun state(mode: WorkMode? = null) = HomeUiState(
        kernelVersion = KernelVersion(6, 6, 89), ksuVersion = null,
        managerUAPIVersion = 1, kernelUAPIVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false,
        isKernelPrBuild = false, requiresNewKernel = false, requiresNewManager = false,
        isRootAvailable = false, isSafeMode = false, isLateLoadMode = false,
        checkUpdateEnabled = false, latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 50201,
        systemInfo = SystemInfo("6.6.89", "5.2.1", "fixture", "fixture", "Unknown", 0),
        environmentMode = mode,
    )

    @Test fun previousBootInstallationIsTheImmediateMainDisplayWithoutAuthorization() {
        for (mode in WorkMode.entries) {
            val first = state().copy(previousEnvironmentMode = mode, environmentChecking = true)
                .withHiddenFacts(HomeHiddenFacts(false, false))
            assertTrue("Historical installation must render before a Root probe", first.showInstalledStatus)
            assertFalse(first.showEnvironmentStatusCard)
            assertNull(first.environmentMode)
            assertFalse(first.isManager)
            assertFalse(first.isRootAvailable)
            assertNull(first.rootReadiness)
        }
    }

    @Test fun automaticFirstCheckDoesNotReplaceTheNormalPageWithAWaitCard() {
        val first = state().copy(environmentChecking = true, litePending = true)
        assertFalse("Automatic detection must keep normal home content", first.showEnvironmentStatusCard)
        assertFalse(first.showLiteChecking)
    }

    @Test fun automaticUnknownTimeoutAndBusyKeepConfirmedContentSilently() {
        for (failure in listOf(null, "MODE_PROBE_TIMEOUT", "MODE_PROBE_BUSY", "MODE_PROBE_FAILED")) {
            val checked = state(WorkMode.LITE).afterEnvironmentCheck(false, true, failure, ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
            assertTrue(checked.showInstalledStatus)
            assertFalse(checked.showEnvironmentStatusCard)
            assertFalse("Automatic checks must stay silent", checked.showEnvironmentRefreshWarning)
            assertFalse(checked.isRootAvailable)
            assertTrue(checked.environmentChecking)
            assertTrue(checked.litePending)
            assertFalse(checked.environmentConfirmedAbsent)
        }
    }

    @Test fun unknownRefreshDoesNotDiscardLastConfirmedDisplay() {
        val previous = state(WorkMode.LITE)
        val unknown = state().afterEnvironmentCheck(false, true, null, ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
            .mergedOver(previous)
        assertTrue(unknown.showInstalledStatus)
        assertFalse(unknown.showEnvironmentStatusCard)
        assertFalse(unknown.isManager)
        assertFalse(unknown.isRootAvailable)
    }

    @Test fun noHistoryUnknownIsNeutralUntilAnExplicitNotInstalledVerdictArrives() {
        val first = state().copy(environmentChecking = true, litePending = true)
        assertTrue(first.environmentUnknown)
        assertFalse(first.environmentConfirmedAbsent)
        val unknown = first.afterEnvironmentCheck(false, true, null, ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
        assertTrue(unknown.environmentUnknown)
        assertTrue(unknown.environmentChecking)
        assertTrue(unknown.litePending)
        val absent = unknown.copy(environmentConfirmedAbsent = true)
            .afterEnvironmentCheck(false, true, null, ModeState.NOT_INSTALLED, knownUltra = false, failureDetail = null)
        assertFalse(absent.environmentUnknown)
        assertTrue(absent.environmentConfirmedAbsent)
        assertFalse(absent.showInstalledStatus)
        assertFalse(absent.environmentChecking)
    }

    @Test fun aConfirmedRemovalDoesNotReinheritHistoryDuringPublication() {
        val previous = state(WorkMode.LITE)
        val removed = state().copy(environmentConfirmedAbsent = true)
            .afterEnvironmentCheck(false, true, null, ModeState.NOT_INSTALLED, knownUltra = false, failureDetail = null)
            .mergedOver(previous)
        assertFalse(removed.showInstalledStatus)
        assertNull(removed.previousEnvironmentMode)
        assertTrue(removed.environmentConfirmedAbsent)
        val localRemoval = state().copy(environmentConfirmedAbsent = true,
            environmentFailure = HomeEnvironmentFailure.TIMED_OUT).mergedOver(previous)
        assertFalse(localRemoval.showInstalledStatus)
    }

    @Test fun aNewConfirmedModeReplacesHistoricalDisplay() {
        val changed = state(WorkMode.KSU).mergedOver(state(WorkMode.LITE))
        assertEquals(WorkMode.KSU, changed.displayEnvironmentMode)
        assertNull(changed.previousEnvironmentMode)
    }

    @Test fun manualProgressSurvivesAutomaticPublicationWithoutChangingTheMainCard() {
        val previous = state(WorkMode.LITE).copy(manualEnvironmentChecking = true)
        val during = state().afterEnvironmentCheck(false, true, "TIMEOUT", ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
            .mergedOver(previous)
        assertTrue(during.manualEnvironmentChecking)
        assertTrue(during.showInstalledStatus)
        assertFalse(during.showEnvironmentRefreshWarning)
        val finished = during.copy(manualEnvironmentChecking = false,
            manualEnvironmentFailure = during.environmentFailure)
        assertTrue(finished.showInstalledStatus)
        assertFalse(finished.showEnvironmentRefreshWarning)
        assertTrue(finished.environmentChecking)
        assertFalse(finished.isManager)
        assertFalse(finished.isRootAvailable)
    }

    @Test fun aSuccessfulObservationClearsAnEarlierManualFailure() {
        val failed = state(WorkMode.LITE).copy(manualEnvironmentFailure = HomeEnvironmentFailure.TIMED_OUT)
        val stillUnknown = state().afterEnvironmentCheck(false, true, null, ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
            .mergedOver(failed)
        assertEquals(HomeEnvironmentFailure.TIMED_OUT, stillUnknown.manualEnvironmentFailure)
        val recovered = state(WorkMode.LITE).afterEnvironmentCheck(true, true, null, ModeState.MODE_A_ACTIVE, knownUltra = false, failureDetail = null)
            .mergedOver(stillUnknown)
        assertNull(recovered.manualEnvironmentFailure)
        assertFalse(recovered.showEnvironmentRefreshWarning)
    }

    @Test fun probeFailureSeparatesCurrentUltraIdentityFromDisplayHistory() {
        val local = state(WorkMode.LITE).copy(liteInstalled = true)
        for (failure in HomeEnvironmentFailure.entries) {
            val detail = "operation=environmentState;sdkError=FIXTURE_${failure.name};sdkErrorCode=7"
            val history = state().afterEnvironmentProbeFailure(local, failure, failureDetail = detail, knownUltra = false)
            assertTrue(history.environmentChecking)
            assertEquals(failure, history.environmentFailure)
            assertNull(history.ultraObservationFailure)
            val current = state().afterEnvironmentProbeFailure(local, failure,
                knownUltra = true, failureDetail = detail)
            assertEquals(WorkMode.LITE, current.environmentMode)
            assertTrue(current.liteInstalled)
            assertFalse(current.environmentChecking)
            assertNull(current.environmentFailure)
            assertEquals(failure, current.ultraObservationFailure)
            assertEquals(detail, current.ultraObservationDetail)
            assertTrue(current.showUltraObservationFailure)
            assertFalse(current.isRootAvailable)
            assertNull(current.rootReadiness)
        }
    }

    @Test fun confirmedUltraFailureOverridesReadyHistoryAndPreservesActualDetail() {
        val ready = state(WorkMode.LITE).copy(liteInstalled = true, environmentRuntimeReady = true,
            isRootAvailable = true, rootReadiness = SuReadiness.USABLE, cachedEnvironmentReady = true)
        val detail = "operation=environmentState;sdkError=FIXTURE_TIMEOUT;sdkErrorCode=7"
        val failed = ready.afterEnvironmentCheck(true, true, "MODE_PROBE_TIMEOUT", ModeState.MODE_A_ACTIVE,
            knownUltra = true, failureDetail = detail).mergedOver(ready)
        assertEquals(WorkMode.LITE, failed.displayEnvironmentMode)
        assertTrue(failed.showInstalledStatus)
        assertFalse(failed.showEnvironmentReady)
        assertFalse(failed.cachedEnvironmentReady)
        assertTrue(failed.showUltraObservationFailure)
        assertEquals(detail, failed.ultraObservationDetail)
        assertNull(failed.environmentFailure)
        assertEquals(SuReadiness.USABLE, failed.rootReadiness)
        val unknown = state().afterEnvironmentCheck(false, true, "MODE_PROBE_BUSY", ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
            .mergedOver(failed)
        assertEquals(detail, unknown.ultraObservationDetail)
        assertTrue(unknown.showUltraObservationFailure)
        assertFalse(unknown.showEnvironmentReady)
        val recovered = ready.afterEnvironmentCheck(true, true, null, ModeState.MODE_A_ACTIVE, knownUltra = true, failureDetail = null)
            .mergedOver(failed)
        assertNull(recovered.ultraObservationFailure)
        assertNull(recovered.ultraObservationDetail)
        assertTrue(recovered.showEnvironmentReady)
        val cached = state().afterEnvironmentCheck(false, true, "MODE_PROBE_TIMEOUT", ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
            .mergedOver(ready)
        assertTrue(cached.environmentChecking)
        assertTrue(cached.showEnvironmentReady)
        assertNull(cached.ultraObservationFailure)
    }

    @Test fun knownHidingAndActiveTransitionsAlwaysClearDisplayHistoryAndCapabilities() {
        for (hidden in listOf(HomeHiddenFacts(true, false),
            HomeHiddenFacts(false, true, transitionPending = true),
            HomeHiddenFacts(false, false, transitionPending = true))) {
            val masked = state(WorkMode.LITE).copy(isManager = true, isRootAvailable = true,
                ultraObservationFailure = HomeEnvironmentFailure.FAILED, ultraObservationDetail = "fixture")
                .withHiddenFacts(hidden)
            assertNull(masked.displayEnvironmentMode)
            assertFalse(masked.showInstalledStatus)
            assertFalse(masked.isManager)
            assertFalse(masked.isRootAvailable)
            assertNull(masked.ultraObservationFailure)
            assertNull(masked.ultraObservationDetail)
            assertFalse(masked.showUltraObservationFailure)
        }
    }

    @Test fun unknownHiddenReadKeepsOnlyDisplayHistoryWithoutGrantingRootOrManager() {
        val visible = state(WorkMode.LITE).copy(isManager = true, isRootAvailable = true, environmentChecking = true)
            .withHiddenFacts(HomeHiddenFacts(false, true))
        assertTrue(visible.showInstalledStatus)
        assertNull(visible.environmentMode)
        assertEquals(WorkMode.LITE, visible.previousEnvironmentMode)
        assertFalse(visible.isManager)
        assertFalse(visible.isRootAvailable)
    }

    @Test fun aVerifiedInstallationDoesNotClaimThatItsRuntimeIsReady() {
        val installed = state(WorkMode.LITE)
        assertTrue(installed.showInstalledStatus)
        assertTrue(installed.showInstalledUnready)
        assertFalse(installed.showEnvironmentReady)
        assertFalse(installed.isRootAvailable)
    }

    @Test fun aRunningSdkStillNeedsTheActualRootVerdictForTheReadyIndicator() {
        val sdkOnly = state(WorkMode.LITE).copy(environmentRuntimeReady = true,
            rootReadiness = SuReadiness.NOT_USABLE)
        assertTrue(sdkOnly.showInstalledUnready)
        assertFalse(sdkOnly.showEnvironmentReady)
    }

    @Test fun rootAloneDoesNotMakeAnUnconfirmedRuntimeReady() {
        val rootOnly = state(WorkMode.LITE).copy(isRootAvailable = true,
            rootReadiness = SuReadiness.USABLE)
        assertTrue(rootOnly.showInstalledUnready)
        assertFalse(rootOnly.showEnvironmentReady)
    }

    @Test fun onlyCurrentRuntimeAndRootObservationsProduceTheReadyIndicator() {
        val running = state(WorkMode.LITE).copy(environmentRuntimeReady = true,
            isRootAvailable = true, rootReadiness = SuReadiness.USABLE)
        assertTrue(running.showEnvironmentReady)
        assertFalse(running.showInstalledUnready)
        assertFalse(running.copy(isSafeMode = true).showEnvironmentReady)
        assertFalse(running.copy(environmentFailure = HomeEnvironmentFailure.TIMED_OUT).showEnvironmentReady)
        val hidden = running.withHiddenFacts(HomeHiddenFacts(true, false))
        assertFalse(hidden.environmentRuntimeReady)
        assertFalse(hidden.showEnvironmentReady)
        assertFalse(hidden.showInstalledStatus)
    }

    @Test fun installationHistoryRemainsVisibleWithoutARuntimeReadyIndicator() {
        val historical = state().copy(previousEnvironmentMode = WorkMode.LITE, environmentChecking = true)
        assertTrue(historical.showInstalledStatus)
        assertTrue(historical.showInstalledUnready)
        assertFalse(historical.showEnvironmentReady)
        assertFalse(historical.environmentRuntimeReady)
    }
}
