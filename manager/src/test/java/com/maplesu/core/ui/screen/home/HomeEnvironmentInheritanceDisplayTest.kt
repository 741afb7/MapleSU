package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import com.maplesu.core.ui.component.maple.homeUltraBrandFacts
import com.maplesu.core.ui.component.maple.shouldShowUltraBrand
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import org.junit.Assert.*
import org.junit.Test

class HomeEnvironmentInheritanceDisplayTest {
    private fun state(mode: WorkMode? = null) = HomeUiState(
        kernelVersion = KernelVersion(6, 6, 89), ksuVersion = null,
        managerUAPIVersion = 1, kernelUAPIVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false,
        isKernelPrBuild = false, requiresNewKernel = false, requiresNewManager = false,
        isRootAvailable = false, isSafeMode = false, isLateLoadMode = false,
        checkUpdateEnabled = false, latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 50003,
        systemInfo = SystemInfo("6.6.89", "5.0.3", "device", "fixture", "Unknown", 0),
        environmentMode = mode, environmentChecking = true,
    )

    @Test fun safeModeKeepsUltraBrandAndRecoveryBannerWithoutGrantingRoot() {
        val safe = state(WorkMode.LITE).copy(isSafeMode = true, liteInstalled = true,
            liteRootFailure = true, rootReadiness = dev.maplesu.manager.workmode.detect.SuReadiness.NOT_USABLE)
            .withHiddenFacts(HomeHiddenFacts(false, false))
        assertEquals(WorkMode.LITE, safe.environmentMode)
        assertTrue(safe.showInstalledStatus)
        assertTrue(safe.showSafeModeBanner)
        assertFalse(safe.showLiteRootFailure)
        assertFalse(safe.showRootWarning)
        assertFalse(safe.showEnvironmentStatusCard)
        assertFalse(safe.isRootAvailable)
        assertFalse(safe.copy(isSafeMode = false).showSafeModeBanner)
        assertFalse(safe.copy(environmentMode = WorkMode.KSU).showSafeModeBanner)
        assertFalse(safe.withHiddenFacts(HomeHiddenFacts(true, false)).showSafeModeBanner)
        assertFalse(safe.withHiddenFacts(HomeHiddenFacts(false, true)).showSafeModeBanner)
    }

    @Test fun ultraSafeModeKeepsTheModuleListButHidesInstallation() {
        val modules = com.maplesu.core.ui.screen.module.ModuleUiState(isUltra = true, isSafeMode = true)
        assertTrue(modules.recoveryOnly)
        assertFalse(modules.installButtonVisible)
        assertFalse(modules.copy(isSafeMode = false).recoveryOnly)
        assertFalse(modules.copy(isUltra = false).recoveryOnly)
    }

    @Test fun installedModesRemainVisibleDuringSilentChecksWithoutGrantingRoot() {
        for (mode in WorkMode.entries) {
            val visible = state(mode).withHiddenFacts(HomeHiddenFacts(false, false))
            assertTrue(visible.showInstalledStatus)
            assertFalse(visible.showEnvironmentStatusCard)
            assertFalse(visible.isManager)
            assertFalse(visible.isRootAvailable)
            assertNull(visible.ksuVersion)
        }
    }

    @Test fun missingSuKeepsTheCachedModeWhileTheEnvironmentIsCheckedSilently() {
        for (mode in WorkMode.entries) {
            val checking = state(mode).checkingAfterSuMissing()
            assertEquals(mode, checking.environmentMode)
            assertTrue(checking.showInstalledStatus)
            assertFalse(checking.showEnvironmentStatusCard)
            assertFalse(checking.isRootAvailable)
        }
    }

    @Test fun failedRefreshKeepsTheConfirmedModeVisibleWithoutGrantingRoot() {
        for (mode in WorkMode.entries) {
            for (failure in listOf(null, "MODE_PREPARE_TIMEOUT", "MODE_PROBE_FAILED", "MODE_PROBE_BUSY")) {
                val refreshed = state(mode).withHiddenFacts(HomeHiddenFacts(false, false))
                    .afterEnvironmentCheck(false, true, failure, ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
                assertEquals(mode, refreshed.environmentMode)
                assertTrue(refreshed.showInstalledStatus)
                assertFalse("$mode / $failure must not replace the cached card", refreshed.showEnvironmentStatusCard)
                assertFalse(refreshed.showEnvironmentRefreshWarning)
                assertTrue(refreshed.environmentChecking)
                assertFalse(refreshed.isRootAvailable)
                assertFalse(refreshed.isManager)
                assertNull(refreshed.ksuVersion)
            }
        }
    }

    @Test fun aRefreshWarningNeverExposesAHiddenMode() {
        val failed = state(WorkMode.LITE).afterEnvironmentCheck(false, true, null, ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
        for (hidden in listOf(HomeHiddenFacts(true, false), HomeHiddenFacts(false, true, transitionPending = true))) {
            val projected = failed.withHiddenFacts(hidden)
            assertFalse(projected.showEnvironmentRefreshWarning)
            assertFalse(projected.showInstalledStatus)
            assertNull(projected.environmentMode)
        }
    }

    @Test fun previousBootHistoryIsDisplayOnlyWithoutClaimingCurrentAuthorization() {
        for (mode in WorkMode.entries) {
            val previous = state().copy(previousEnvironmentMode = mode)
                .withHiddenFacts(HomeHiddenFacts(false, false))
                .afterEnvironmentCheck(false, true, null, ModeState.INDETERMINATE, knownUltra = false, failureDetail = null)
            assertEquals(mode, previous.previousEnvironmentMode)
            assertNull(previous.environmentMode)
            assertTrue(previous.showInstalledStatus)
            assertFalse(previous.showEnvironmentRefreshWarning)
            assertFalse(previous.showEnvironmentStatusCard)
            assertFalse(previous.isManager)
            assertFalse(previous.isRootAvailable)
        }
    }

    @Test fun hidingAlsoClearsPreviousBootDisplayHistory() {
        for (hidden in listOf(HomeHiddenFacts(true, false), HomeHiddenFacts(false, true, transitionPending = true))) {
            val masked = state().copy(previousEnvironmentMode = WorkMode.LITE).withHiddenFacts(hidden)
            assertNull(masked.previousEnvironmentMode)
            assertFalse(masked.showInstalledStatus)
        }
    }

    @Test fun missingSuWithoutACachedModeKeepsThePageAvailable() {
        val checking = state().checkingAfterSuMissing()
        assertNull(checking.environmentMode)
        assertFalse(checking.showInstalledStatus)
        assertFalse(checking.showEnvironmentStatusCard)
    }

    @Test fun knownHiddenAlwaysBeatsConfirmationAndSlowVisibleResults() {
        for (mode in WorkMode.entries) {
            val hidden = state(mode).withHiddenFacts(HomeHiddenFacts(true, true))
            assertFalse(hidden.showInstalledStatus)
            assertFalse(hidden.showEnvironmentStatusCard)
            assertFalse(hidden.hiddenConfirming)
            assertNull(hidden.environmentMode)
        }
    }

    @Test fun unknownHiddenStateKeepsDisplayHistoryButDoesNotExposeManagerCapabilities() {
        val checking = state(WorkMode.LITE).copy(isManager = true, ksuVersion = 123)
            .withHiddenFacts(HomeHiddenFacts(false, true))
        assertTrue(checking.showInstalledStatus)
        assertFalse(checking.isManager)
        assertFalse(checking.isRootAvailable)
        assertNull(checking.environmentMode)
        assertFalse(checking.showEnvironmentStatusCard)
    }

    @Test fun completedFailedHiddenReadKeepsEntriesClosedWithoutAnEndlessSpinner() {
        val settled = state(WorkMode.LITE).copy(environmentChecking = false)
            .withHiddenFacts(HomeHiddenFacts(false, true))
        assertFalse(settled.showInstalledStatus)
        assertNull(settled.environmentMode)
        assertNull(settled.displayEnvironmentMode)
        assertFalse(settled.isManager)
        assertFalse(settled.isRootAvailable)
        assertFalse(settled.environmentChecking)
        assertFalse(settled.litePending)
        assertFalse(settled.showLiteChecking)
        assertFalse(settled.showEnvironmentStatusCard)
        assertTrue(settled.environmentNeedsConfirmation)
        assertTrue(settled.hiddenConfirming)
    }

    @Test fun confirmedOffReleasesTheModeOnlyAfterTheHiddenFactsArrive() {
        val ready = state(WorkMode.LITE).copy(hiddenConfirming = true)
            .withHiddenFacts(HomeHiddenFacts(false, false))
        assertFalse(ready.hiddenConfirming)
        assertTrue(ready.showInstalledStatus)
        assertFalse(ready.showEnvironmentStatusCard)
    }

    @Test fun anUnresolvedProbeRetainsIdentityPending() {
        val settled = state().copy(litePending = true).afterEnvironmentCheck(
            modeResolved = false, detectionComplete = false, detectionFailure = null, detectedState = null,
            knownUltra = false, failureDetail = null,
        )
        assertTrue(settled.litePending)
        assertTrue(settled.environmentChecking)
        assertFalse(settled.showEnvironmentStatusCard)
        assertEquals(HomeEnvironmentFailure.UNCONFIRMED, settled.environmentFailure)
    }

    @Test fun recognizedInstalledCarrierClearsUnknownWithoutGrantingManagerOrRoot() {
        val installed = state(WorkMode.LITE).copy(liteInstalled = true,
            environmentFailure = HomeEnvironmentFailure.UNCONFIRMED).afterEnvironmentCheck(
            modeResolved = false, detectionComplete = true, detectionFailure = null,
            detectedState = dev.maplesu.manager.workmode.detect.ModeState.MODE_A_INSTALLED_UNBOUND,
            knownUltra = false, failureDetail = null,
        )
        assertNull(installed.environmentFailure)
        assertFalse(installed.showEnvironmentStatusCard)
        assertTrue(installed.showInstalledStatus)
        assertFalse(installed.isManager)
        assertFalse(installed.isRootAvailable)
        assertNull(installed.ksuVersion)
        assertFalse(installed.environmentChecking)
    }

    @Test fun verifiedInstalledBrandWaitsForHiddenConfirmationNotRuntimeActivation() {
        for (mode in WorkMode.entries) {
            for (hidden in listOf(false, true)) {
                for (confirming in listOf(false, true)) {
                    val facts = homeUltraBrandFacts(HomeWorkModeSwitchState.HIDDEN, hidden, confirming, mode,
                        environmentInstalled = true)
                    assertEquals(mode == WorkMode.LITE && !hidden && !confirming, shouldShowUltraBrand(facts))
                }
            }
        }
    }
}
