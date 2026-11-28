package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient.ManagerOwnerIdentity
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.SuReadiness
import org.junit.Assert.*
import org.junit.Test

class HomeEnvironmentHealthProjectionTest {
    private val owner = ManagerOwnerIdentity(10498, 21L, "host-r21")

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

    private fun health(
        state: UltraEnvironmentProbe.State = UltraEnvironmentProbe.State.Running,
        root: Boolean = true,
        observedOwner: ManagerOwnerIdentity? = owner,
        detail: String? = "operation=get_maplesu_environment_state;sdkError=OK;sdkErrorCode=0",
        rootDetail: String? = null,
        legacy: String? = null,
    ) = UltraEnvironmentProbe.Snapshot(
        state = state, detail = detail, kernelRootVerified = root,
        managerOwnerIdentity = observedOwner, modeActivation = ModeActivation.Unresolved,
        sdkChannelAvailable = true, legacyCredentialFingerprint = legacy,
        rootProbeDetail = rootDetail,
    )

    private fun assertInstalledFailure(current: HomeUiState, detail: String) {
        assertEquals(WorkMode.LITE, current.environmentMode)
        assertTrue(current.liteInstalled)
        assertTrue(current.showInstalledStatus)
        assertFalse(current.environmentUnknown)
        assertFalse(current.environmentConfirmedAbsent)
        assertFalse(current.environmentChecking)
        assertFalse(current.sdkRootAvailable)
        assertFalse(current.environmentRuntimeReady)
        assertFalse(current.showEnvironmentReady)
        assertTrue(current.showUltraObservationFailure)
        assertNull(current.environmentFailure)
        assertEquals(detail, current.ultraObservationDetail)
    }

    @Test fun currentOwnerWithReceiptChildFailureKeepsUltraAndTheRootReceipt() {
        val raw = "operation=probe_manager_owner_root;sdkError=ERR_READ_CHILD_ERRCODE;sdkErrorCode=11;phase=11;child=256"
        val current = state().afterUltraEnvironmentHealth(
            health(UltraEnvironmentProbe.State.Unknown, root = false, rootDetail = raw), owner, true,
        )
        assertInstalledFailure(current, raw)
        assertEquals(HomeEnvironmentFailure.FAILED, current.ultraObservationFailure)
        assertFalse(current.isRootAvailable)
        assertNull(current.rootReadiness)
    }

    @Test fun identityConfirmedBeforeScopeFailureDoesNotBecomeUnknown() {
        val raw = "SDK_IDENTITY_SCOPE_CHANGED"
        val current = state().afterUltraEnvironmentHealth(
            health(UltraEnvironmentProbe.State.Unknown, root = false, observedOwner = null, detail = raw), owner, true,
        )
        assertInstalledFailure(current, raw)
        assertEquals(HomeEnvironmentFailure.FAILED, current.ultraObservationFailure)
    }

    @Test fun identityConfirmedBeforeSdkBusyKeepsItsInstallation() {
        val raw = "MAPLESU_SDK_BUSY"
        val current = state().afterUltraEnvironmentHealth(
            health(UltraEnvironmentProbe.State.Unknown, root = false, observedOwner = null, detail = raw), owner, true,
        )
        assertInstalledFailure(current, raw)
        assertEquals(HomeEnvironmentFailure.BUSY, current.ultraObservationFailure)
    }

    @Test fun runningEnvironmentWithoutRootProofStillShowsTheRootFailure() {
        val raw = "operation=probe_manager_owner_root;sdkError=ULTRA_ROOT_FAILED;sdkErrorCode=11"
        val current = state().afterUltraEnvironmentHealth(health(root = false, rootDetail = raw), owner, true)
        assertInstalledFailure(current, raw)
        assertEquals(HomeEnvironmentFailure.FAILED, current.ultraObservationFailure)
    }

    @Test fun freshSdkRootProofIsIndependentOfTheOrdinarySuVerdict() {
        val current = state().afterUltraEnvironmentHealth(health(), owner, true)
        assertTrue(current.sdkRootAvailable)
        assertTrue(current.environmentRuntimeReady)
        assertTrue(current.showEnvironmentReady)
        assertFalse(current.isRootAvailable)
        assertNull(current.rootReadiness)
        assertTrue(current.copy(rootReadiness = SuReadiness.NOT_USABLE).showEnvironmentReady)
    }

    @Test fun ownerMatchIncludesTheFullUidEpochAndHostInstance() {
        for (different in listOf(
            owner.copy(fullUid = owner.fullUid + 100000),
            owner.copy(epoch = owner.epoch + 1),
            owner.copy(hostInstance = "another-host"),
        )) {
            val current = state().afterUltraEnvironmentHealth(health(observedOwner = different), owner, true)
            assertFalse("A different full owner must not reuse the proof: $different", current.sdkRootAvailable)
            assertFalse(current.environmentRuntimeReady)
            assertFalse(current.showEnvironmentReady)
        }
    }

    @Test fun aFreshRootProofIsRevokedWhenItsScopeIsNoLongerCurrent() {
        val current = state().afterUltraEnvironmentHealth(health(), owner, false)
        assertFalse(current.sdkRootAvailable)
        assertFalse(current.environmentRuntimeReady)
        assertFalse(current.showEnvironmentReady)
        assertEquals("SDK_IDENTITY_SCOPE_CHANGED", current.ultraObservationDetail)
    }

    @Test fun aHealthFailureClearsThePreviousSdkProofAndReadyPresentation() {
        val previous = state().afterUltraEnvironmentHealth(health(), owner, true)
        val raw = "MAPLESU_SDK_BUSY"
        val current = previous.afterUltraEnvironmentHealth(
            health(UltraEnvironmentProbe.State.Unknown, root = false, observedOwner = null, detail = raw), owner, true,
        ).mergedOver(previous)
        assertInstalledFailure(current, raw)
        assertFalse(current.cachedEnvironmentReady)
    }

    @Test fun unknownIdentityDoesNotGainInstallationFromSdkOrBrandFields() {
        val current = state().copy(environmentRuntimeReady = true, sdkRootAvailable = true,
            ultraBrandVisible = true, isManager = true)
        assertTrue(current.environmentUnknown)
        assertFalse(current.showInstalledStatus)
        assertFalse(current.showEnvironmentReady)
    }

    @Test fun hidingAndSafeModeRevokeTheSdkRootProof() {
        val current = state().afterUltraEnvironmentHealth(health(), owner, true)
        for (hidden in listOf(HomeHiddenFacts(true, false), HomeHiddenFacts(false, true),
            HomeHiddenFacts(false, false, true))) {
            val masked = current.withHiddenFacts(hidden)
            assertFalse(masked.sdkRootAvailable)
            assertFalse(masked.showEnvironmentReady)
        }
        val safe = current.withUltraSafety(true, WorkMode.LITE)
        assertFalse(safe.sdkRootAvailable)
        assertFalse(safe.showEnvironmentReady)
    }

    @Test fun normalModeDoesNotConsumeAnUltraSdkProof() {
        val normal = state(WorkMode.KSU).copy(environmentRuntimeReady = true, sdkRootAvailable = true)
        assertFalse(normal.showEnvironmentReady)
        assertFalse(normal.withUltraSafety(false, WorkMode.KSU).sdkRootAvailable)
        val ordinary = normal.copy(isRootAvailable = true, rootReadiness = SuReadiness.USABLE,
            sdkRootAvailable = false)
        assertTrue(ordinary.showEnvironmentReady)
    }

    @Test fun legacyRootProofUsesItsFreshCredentialScopeWithoutRequiringAModernOwner() {
        val legacy = health(observedOwner = null, legacy = "legacy-current")
        val current = state().afterUltraEnvironmentHealth(legacy, null, true)
        assertTrue(current.sdkRootAvailable)
        assertTrue(current.showEnvironmentReady)
        val unbound = state().afterUltraEnvironmentHealth(legacy.copy(legacyCredentialFingerprint = null), null, true)
        assertFalse(unbound.sdkRootAvailable)
        assertFalse(unbound.showEnvironmentReady)
    }

    @Test fun environmentOkWithAnUnknownRuntimeDoesNotReplaceTheUnconfirmedHealth() {
        val observation = health(UltraEnvironmentProbe.State.Unknown, root = false)
        val current = state().afterUltraEnvironmentHealth(observation, owner, true)
        assertEquals(HomeEnvironmentFailure.UNCONFIRMED, current.ultraObservationFailure)
        assertFalse(current.sdkRootAvailable)
        assertFalse(current.showEnvironmentReady)
        assertTrue(current.showInstalledStatus)
    }
}
