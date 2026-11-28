package com.maplesu.core.ui.screen.home

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guard the production callers shared by Material, Miuix and bottom navigation. */
class HomeRuntimeRecognitionWiringTest {
    private fun source(path: String): String = listOf(File("src/main/java/$path"), File("manager/src/main/java/$path"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

    @Test fun anObservedUltraIdentityStillNeedsCurrentSdkRootProofForReady() {
        val adapter = source("dev/maplesu/manager/workmode/detect/probes/EnvironmentProbesAdapter.kt")
        assertFalse(adapter.contains("UltraKeyPairedStore.isPaired("))
        assertFalse(adapter.contains("MapleSuCredentialStore("))
        assertTrue(adapter.contains("UltraEnvironmentProbe.observeFreshIdentity("))
        assertTrue(adapter.contains("observed.managerOwnerIdentity"))
        val collection = source("com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
            .substringAfter("private suspend fun collectEnvironment(")
            .substringBefore("private suspend fun refreshInformation(")
        assertTrue(collection.contains("if (knownUltra) UltraEnvironmentProbe.observeFresh(ksuApp) else null"))
        assertTrue(collection.contains("projected.afterUltraEnvironmentHealth("))
        assertTrue(collection.contains("expectedOwner = evidence?.ultraOwnerIdentity"))
        val health = source("com/maplesu/core/ui/screen/home/HomeUiState.kt")
            .substringAfter("internal fun HomeUiState.afterUltraEnvironmentHealth(")
            .substringBefore("fun HomeUiState.mergedOver(")
        assertTrue(health.contains("!scopeCurrent || !sameIdentity || observation.state != UltraEnvironmentProbe.State.Running"))
        assertTrue(health.contains("!observation.rootAvailable"))
        assertTrue(health.contains("sdkRootAvailable = healthy && scopeCurrent && sameIdentity && observation.rootAvailable"))
    }

    @Test fun navigationUsesTheSameInstalledProjectionAsTheHomeCard() {
        val activity = source("com/maplesu/core/ui/MainActivity.kt")
        assertTrue(activity.contains("hasInstalledVersion = homeUiState.showInstalledStatus"))
    }

    @Test fun normalAndHiddenModesCloseTheEntryWithoutAddingARootGateForManualSdkInstallation() {
        val panel = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
            .substringAfter("class UltraEnvironmentInstallPanel")
        val eligible = panel.substringAfter("private fun eligible(").substringBefore("fun refresh(")
        assertTrue(eligible.contains("environmentInstallEligible(mode, ModeRuntime.active,"))
        assertTrue(eligible.contains("HiddenStartupConfirm.masking(context)"))
        assertTrue(eligible.contains("HiddenStartupConfirm.isPending(context)"))
        for (precondition in listOf("HookRootExecutor", "UltraRootKeyStore.read(", "UltraEnvironmentProbe.refresh(",
            "kernelRootVerified", "sdkRootAvailable", "== WorkMode.LITE")) {
            assertFalse(precondition, eligible.contains(precondition))
        }
        assertTrue(panel.contains("if (busy || !eligible()) return"))
        val policy = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentInstallEligibility.kt")
        assertTrue(policy.contains("mode != WorkMode.KSU && activeMode != WorkMode.KSU && !hidden && !transitionPending"))
    }

    @Test fun modeAndSameModeSdkChangesRefreshTheInstallCardInBothThemes() {
        for (theme in listOf("HomeMaterial.kt", "HomeMiuix.kt")) {
            assertTrue(source("com/maplesu/core/ui/screen/home/$theme")
                .contains("rememberUltraEnvironmentInstallPanel(state.environmentMode, state.environmentConfirmedAbsent)"))
        }
        val remember = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
            .substringAfter("fun rememberUltraEnvironmentInstallPanel(")
            .substringBefore("@Composable")
        assertTrue(remember.contains("UltraEnvironmentProbe.observations.collectAsStateWithLifecycle("))
        assertTrue(remember.contains("initialValue = UltraEnvironmentProbe.currentSnapshot(appContext)"))
        assertTrue(remember.contains("HiddenFactsSignal.revision.collectAsStateWithLifecycle()"))
        assertTrue(remember.contains("LaunchedEffect(panel, mode, confirmedAbsent, observation.state, observation.kernelChannelAbsent, hiddenRevision)"))
        assertTrue(remember.contains("panel.refresh(mode, confirmedAbsent)"))
        assertFalse(remember.contains("observedAtWallClockMs"))
    }

    @Test fun newerPanelRefreshAndInstallationCancelOlderObservations() {
        val panel = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
            .substringAfter("class UltraEnvironmentInstallPanel")
        val refresh = panel.substringAfter("fun refresh(").substringBefore("fun install()")
        assertTrue(refresh.contains("refreshJob?.cancel()"))
        assertTrue(refresh.contains("refreshJob = scope.launch"))
        val install = panel.substringAfter("fun install()").substringBefore("internal fun environmentInstallVisible")
        assertTrue(install.indexOf("refreshJob?.cancel()") in 0 until install.indexOf("busy = true"))
    }

    @Test fun busyInstallationRetainsLatestHomeFactsAndItsActualSdkResult() {
        val panel = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
            .substringAfter("class UltraEnvironmentInstallPanel")
        val refresh = panel.substringAfter("fun refresh(").substringBefore("fun install()")
        assertTrue(refresh.indexOf("homeMode = mode") in 0 until refresh.indexOf("if (busy) return"))
        assertTrue(refresh.indexOf("homeConfirmedAbsent = confirmedAbsent") in 0 until refresh.indexOf("if (busy) return"))
        assertTrue(refresh.contains("if (mode == WorkMode.KSU || ModeRuntime.active == WorkMode.KSU) visible = false"))
        val install = panel.substringAfter("fun install()").substringBefore("internal fun environmentInstallVisible")
        assertTrue(install.contains("withContext(Dispatchers.IO + NonCancellable)"))
        assertTrue(install.contains("val result = UltraEnvironmentUpgrade.installBundled(context)"))
        assertTrue(install.contains("result to UltraEnvironmentUpgrade.lastFailureReason"))
        assertFalse(install.contains("UltraEnvironmentInstaller.Intent"))
        assertFalse(install.contains("preflight"))
        val completion = install.substringAfter("finally {")
        assertTrue(completion.contains("busy = false"))
        assertTrue(completion.contains("refresh(homeMode, homeConfirmedAbsent)"))
        assertFalse(completion.contains("message = null"))
        assertFalse(completion.contains("detail = null"))
        assertFalse(refresh.contains("message = null"))
        assertFalse(refresh.contains("detail = null"))
    }
}
