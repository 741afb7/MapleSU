package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test

class UltraRecoveryFingerprintTest {




    @Test fun unavailableSdkAndMalformedIdentityPreserveUnknownAndExactFailureDetails() {
        for (available in listOf(false, true)) for (detail in listOf(
            "MAPLESU_SDK_BUSY", "MAPLESU_SDK_INTERRUPTED", "TIMEOUT",
            "operation=query_manager_owner_identity;sdkError=OK;sdkErrorCode=0;identity=invalid_reply",
            "operation=query_manager_owner_identity;sdkError=JNI_NOT_LOADED;sdkErrorCode=-1")) {
            val observation = UltraEnvironmentProbe.Snapshot(detail = detail, sdkChannelAvailable = available)
            val failure = assertThrows(IllegalStateException::class.java) {
                UltraEnvironmentProbe.recoveryFingerprint(observation.state, false,
                    observation.kernelRootVerified, observation.kernelChannelAbsent, observation.detail)
            }
            assertEquals("ULTRA_RECOVERY_PROBE_UNKNOWN:$detail", failure.message)
        }
        val absent = UltraEnvironmentProbe.Snapshot(kernelChannelAbsent = true,
            detail = "operation=query_manager_owner_identity;sdkError=ERR_MODULE_NO_BOOT;sdkErrorCode=-1")
        assertNull(UltraEnvironmentProbe.recoveryFingerprint(absent.state, false,
            absent.kernelRootVerified, absent.kernelChannelAbsent, absent.detail))
    }

    @Test fun noBootIsDefinitiveAbsenceWhereasBusyAndTimeoutRemainUnknown() {
        assertNull(UltraEnvironmentProbe.recoveryFingerprint(UltraEnvironmentProbe.State.Unknown, false,
            kernelChannelAbsent = true))
        for (detail in listOf("MAPLESU_SDK_BUSY", "MAPLESU_SDK_INTERRUPTED", "TIMEOUT", "BAD_KEY")) {
            val unknown = UltraEnvironmentProbe.Snapshot(detail = detail)
            assertFalse(unknown.rootAvailable)
            assertThrows(IllegalStateException::class.java) {
                UltraEnvironmentProbe.recoveryFingerprint(unknown.state, false, unknown.kernelRootVerified, unknown.kernelChannelAbsent)
            }
        }
    }

    @Test fun failedSdkChannelStaysUnknownInsteadOfBeingReportedAsNotInstalled() {
        assertThrows(IllegalStateException::class.java) {
            UltraEnvironmentProbe.recoveryFingerprint(UltraEnvironmentProbe.State.Unknown, false)
        }
    }

    @Test fun failedSdkChannelPreservesTheExactOperationAndErrorForTheResolver() {
        val receipt = requireNotNull(NativeReceipt.parse("""{"schemaVersion":1,"backend":"maplesu_ultra","bridgeVersion":1,"operation":"query_manager_owner_identity","ok":false,"sdkError":"ERR_MODULE_TEST_CHANNEL","sdkErrorCode":-19999}"""))
        val detail = UltraEnvironmentProbe.observationDetail(receipt)
        val error = assertThrows(IllegalStateException::class.java) {
            UltraEnvironmentProbe.recoveryFingerprint(UltraEnvironmentProbe.State.Unknown, false, detail = detail)
        }
        assertEquals("ULTRA_RECOVERY_PROBE_UNKNOWN:operation=query_manager_owner_identity;sdkError=ERR_MODULE_TEST_CHANNEL;sdkErrorCode=-19999",
            error.message)
        assertEquals("$detail;identity=invalid_reply", UltraEnvironmentProbe.observationDetail(receipt, invalidIdentity = true))
    }
    @Test fun onlyLiveRunningOrApplicableFaultIdentifiesUltra() {
        assertEquals("ultra:running", UltraEnvironmentProbe.recoveryFingerprint(UltraEnvironmentProbe.State.Running, false))
        assertEquals("ultra:fault", UltraEnvironmentProbe.recoveryFingerprint(UltraEnvironmentProbe.State.Fault, true))
        for (state in listOf(UltraEnvironmentProbe.State.NotInstalled,
            UltraEnvironmentProbe.State.Fault)) assertNull(UltraEnvironmentProbe.recoveryFingerprint(state, false))
    }

    @Test fun verifiedKernelIdentifiesUltraWithoutChangingTheUnknownHelperState() {
        for (state in listOf(UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NotInstalled)) {
            val verified = UltraEnvironmentProbe.verifyKernelRoot(state, true) { 0 }
            assertTrue(verified)
            assertEquals("ultra:kernel", UltraEnvironmentProbe.recoveryFingerprint(state, false, verified))
            assertEquals(if (state == UltraEnvironmentProbe.State.Unknown) null else false,
                UltraEnvironmentProbe.suEnabledState(state))
        }
    }

    @Test fun noBootBadKeyMissingUidAndFailedReceiptsNeverProveTheKernel() {
        for (uid in listOf(null, -1, 2000)) {
            assertFalse(UltraEnvironmentProbe.verifyKernelRoot(UltraEnvironmentProbe.State.Unknown, true) { uid })
        }
        assertFalse(UltraEnvironmentProbe.verifyKernelRoot(UltraEnvironmentProbe.State.Unknown, true) {
            error("ERR_MODULE_NO_BOOT")
        })
        assertFalse(UltraEnvironmentProbe.verifyKernelRoot(UltraEnvironmentProbe.State.Unknown, false) {
            error("A failed receipt must not start another SDK command")
        })
        for (state in listOf(UltraEnvironmentProbe.State.Fault,
            UltraEnvironmentProbe.State.NoKey)) {
            assertFalse(UltraEnvironmentProbe.verifyKernelRoot(state, true) { error("No probe for $state") })
        }
    }
}
