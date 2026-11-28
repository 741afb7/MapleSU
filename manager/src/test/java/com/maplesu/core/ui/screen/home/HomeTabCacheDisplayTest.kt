package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.SuReadiness
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HomeTabCacheDisplayTest {
    private fun state(mode: WorkMode? = null) = HomeUiState(
        kernelVersion = KernelVersion(6, 6, 89), ksuVersion = null,
        managerUAPIVersion = 1, kernelUAPIVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false,
        isKernelPrBuild = false, requiresNewKernel = false, requiresNewManager = false,
        isRootAvailable = false, isSafeMode = false, isLateLoadMode = false,
        checkUpdateEnabled = false, latestVersionInfo = LatestVersionInfo(), currentManagerVersionCode = 50201,
        systemInfo = SystemInfo("6.6.89", "5.2.1", "fixture", "fixture", "Unknown", 0),
        environmentMode = mode,
    )

    private fun ready() = state(WorkMode.LITE).copy(environmentRuntimeReady = true, sdkRootAvailable = true,
        isManager = true, isRootAvailable = true, rootReadiness = SuReadiness.USABLE,
        ksuVersion = 50201, kernelUAPIVersion = 7)

    private fun source(name: String) = listOf(File("src/main/java/$name"), File("manager/src/main/java/$name"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

    @Test fun automaticUnknownKeepsOnlyTheConfirmedDisplayWhileIdentityRemainsUnresolved() {
        val previous = ready()
        val current = state().afterEnvironmentCheck(false, true, null, ModeState.INDETERMINATE)
            .mergedOver(previous)
        assertNull(current.environmentMode)
        assertEquals(previous.displayEnvironmentMode, current.displayEnvironmentMode)
        assertTrue(current.environmentChecking)
        assertFalse(current.sdkRootAvailable)
        assertTrue(current.showEnvironmentReady)
        assertFalse(current.showEnvironmentStatusCard)
        assertFalse(current.showEnvironmentRefreshWarning)
        assertFalse(current.environmentRuntimeReady)
        assertFalse(current.isManager)
        assertFalse(current.isRootAvailable)
        assertNull(current.rootReadiness)
    }

    @Test fun automaticTimeoutKeepsDisplayMetadataWithoutReusingRootOrManager() {
        val previous = ready()
        val current = previous.afterEnvironmentProbeFailure(state(WorkMode.LITE), HomeEnvironmentFailure.TIMED_OUT)
            .mergedOver(previous)
        assertTrue(current.showEnvironmentReady)
        assertEquals(7, current.kernelUAPIVersion)
        assertEquals(50201, current.ksuVersion)
        assertFalse(current.isManager)
        assertFalse(current.isRootAvailable)
        assertNull(current.rootReadiness)
    }

    @Test fun automaticBusyDoesNotReplaceTheKnownCardWithAConfirmationState() {
        val previous = ready()
        val current = state().afterEnvironmentCheck(false, true, "MODE_PROBE_BUSY", ModeState.INDETERMINATE)
            .mergedOver(previous)
        assertTrue(current.showEnvironmentReady)
        assertFalse(current.showInstalledUnready)
        assertEquals(WorkMode.LITE, current.displayEnvironmentMode)
        assertFalse(current.isManager)
    }

    @Test fun pendingSdkAndUnknownRootObservationsKeepTheLastConfirmedDisplay() {
        val previous = ready()
        val current = state().copy(environmentChecking = true, litePending = true,
            rootReadiness = SuReadiness.UNKNOWN).mergedOver(previous)
        assertNull(current.environmentMode)
        assertEquals(WorkMode.LITE, current.displayEnvironmentMode)
        assertTrue(current.cachedEnvironmentReady)
        assertFalse(current.sdkRootAvailable)
        assertTrue(current.showEnvironmentReady)
        assertFalse(current.isRootAvailable)
        assertFalse(current.environmentRuntimeReady)
    }

    @Test fun aRunningSdkWithoutFreshRootProofKeepsUltraIdentityWithoutReady() {
        for (readiness in listOf(null, SuReadiness.UNKNOWN)) {
            val current = state(WorkMode.LITE).copy(environmentRuntimeReady = true,
                rootReadiness = readiness)
                .afterEnvironmentCheck(true, true, null, ModeState.MODE_A_ACTIVE)
                .mergedOver(ready())
            assertEquals(WorkMode.LITE, current.environmentMode)
            assertTrue(current.showInstalledStatus)
            assertFalse(current.sdkRootAvailable)
            assertFalse(current.showEnvironmentReady)
            assertTrue(current.showInstalledUnready)
            assertFalse(current.isRootAvailable)
            assertFalse(current.isManager)
            assertEquals(readiness, current.rootReadiness)
            assertTrue(current.environmentRuntimeReady)
        }
    }

    @Test fun noCacheKeepsTheOrdinaryActionCardWithoutInventingAnInstallation() {
        val first = state().copy(environmentChecking = true, litePending = true).mergedOver(state())
        assertTrue(first.environmentUnknown)
        assertFalse(first.showInstalledStatus)
        assertFalse(first.showEnvironmentReady)
        assertFalse(first.showEnvironmentStatusCard)
    }

    @Test fun actualRootFailureAndRemovalReplaceTheCachedDisplay() {
        val previous = ready()
        val failed = state(WorkMode.LITE).copy(rootReadiness = SuReadiness.NOT_USABLE).mergedOver(previous)
        assertFalse(failed.showEnvironmentReady)
        assertTrue(failed.showInstalledStatus)
        val removed = state().copy(environmentConfirmedAbsent = true)
            .afterEnvironmentCheck(false, true, null, ModeState.NOT_INSTALLED)
            .mergedOver(previous)
        assertFalse(removed.showInstalledStatus)
        assertFalse(removed.showEnvironmentReady)
        assertNull(removed.displayEnvironmentMode)
    }

    @Test fun actualModeChangeDoesNotReuseTheOldModesReadyBadgeOrVersion() {
        val changed = state(WorkMode.KSU).mergedOver(ready())
        assertEquals(WorkMode.KSU, changed.displayEnvironmentMode)
        assertFalse(changed.showEnvironmentReady)
        assertNull(changed.kernelUAPIVersion)
    }

    @Test fun hidingAndSafetyImmediatelyInvalidateTheCachedReadyDisplay() {
        val previous = ready()
        val cached = state().afterEnvironmentCheck(false, true, "TIMEOUT", ModeState.INDETERMINATE)
            .mergedOver(previous)
        for (hidden in listOf(HomeHiddenFacts(true, false), HomeHiddenFacts(false, true, true))) {
            assertFalse(cached.withHiddenFacts(hidden).showInstalledStatus)
            assertFalse(cached.withHiddenFacts(hidden).showEnvironmentReady)
        }
        assertFalse(cached.withUltraSafety(true, WorkMode.LITE).showEnvironmentReady)
    }

    @Test fun explicitRetryUsesCachedDisplayOnlyWhilePendingAndKeepsConfirmedUltraHealthFailures() {
        val previous = ready().copy(manualEnvironmentChecking = true)
        val during = state().afterEnvironmentCheck(false, true, "TIMEOUT", ModeState.INDETERMINATE)
            .mergedOver(previous)
        assertTrue(during.manualEnvironmentChecking)
        assertTrue(during.showEnvironmentReady)
        assertNull(during.environmentMode)
        assertFalse(during.sdkRootAvailable)

        // The session's final UNKNOWN frame clears presentation history; it grants no identity.
        val unknownFinished = during.copy(environmentChecking = false, litePending = false,
            manualEnvironmentChecking = false, manualEnvironmentFailure = null,
            previousEnvironmentMode = null, cachedEnvironmentReady = false)
        assertTrue(unknownFinished.environmentNeedsConfirmation)
        assertFalse(unknownFinished.showInstalledStatus)
        assertFalse(unknownFinished.showEnvironmentReady)
        assertFalse(unknownFinished.showEnvironmentRefreshWarning)

        // A current identity fact survives a failed health read; the original component result stays visible.
        val finished = during.afterEnvironmentProbeFailure(state(WorkMode.LITE),
            HomeEnvironmentFailure.TIMED_OUT, knownUltra = true, failureDetail = "SDK_ROOT_TIMEOUT:original")
            .copy(manualEnvironmentChecking = false, manualEnvironmentFailure = null,
                previousEnvironmentMode = null, cachedEnvironmentReady = false)
        assertEquals(WorkMode.LITE, finished.environmentMode)
        assertTrue(finished.showInstalledStatus)
        assertTrue(finished.showInstalledUnready)
        assertTrue(finished.showUltraObservationFailure)
        assertEquals("SDK_ROOT_TIMEOUT:original", finished.ultraObservationDetail)
        assertFalse(finished.showEnvironmentReady)
        assertFalse(finished.sdkRootAvailable)
        assertFalse(finished.showEnvironmentRefreshWarning)
        assertFalse(finished.isRootAvailable)
        val settlement = source("com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
            .substringAfter("private suspend fun finishEnvironmentSession(")
            .substringBefore("private suspend fun checkEnvironmentPresence(")
        assertTrue(settlement.contains("environmentChecking = false, litePending = false"))
        assertTrue(settlement.contains("manualEnvironmentChecking = false, manualEnvironmentFailure = null"))
        assertTrue(settlement.contains("previousEnvironmentMode = null, cachedEnvironmentReady = false"))
    }

    @Test fun allThreeThemesShareRuntimeStatusWithoutRepeatingTheInstalledIdentity() {
        val theme = source("com/maplesu/core/ui/screen/home/HomeThemeLayouts.kt")
        val summary = theme.substringAfter("internal fun homeInstalledStatusSummary(")
            .substringBefore("internal fun rememberThemeHomeLayout()")
        assertFalse(summary.contains("home_environment_previous_status"))
        assertTrue(summary.contains("home_environment_installed_unready_summary"))
        assertFalse(summary.contains("dev.maplesu.manager.workmode.WorkMode"))
        assertFalse(summary.contains("ProgressIndicator"))
        for (name in listOf("HomeMaterial", "HomeMiuix", "HomeThemeLayouts")) {
            assertTrue(source("com/maplesu/core/ui/screen/home/$name.kt").contains("homeInstalledStatusSummary(state"))
        }
    }

    @Test fun ordinaryTabReturnsNeverSetExplicitRetryProgress() {
        val page = source("com/maplesu/core/ui/screen/home/HomeScreen.kt")
        assertFalse(page.contains("manualEnvironmentChecking"))
        assertFalse(page.contains("work_mode_analyzing"))
        val vm = source("com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
        val foreground = vm.substringAfter("fun refreshOnForeground() {")
            .substringBefore("fun refresh()")
        assertFalse(foreground.contains("manualEnvironmentChecking"))
        assertFalse(foreground.contains("copy(environmentChecking = true"))
    }
}
