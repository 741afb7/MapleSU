package dev.maplesu.manager.workmode.boot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedStartupPolicyTest {
    @Test
    fun verifiedExistingIdentityCanStartAfterFirstFrame() {
        assertTrue(PairedStartupPolicy.shouldExpedite(false, false))
    }

    @Test
    fun newIdentityCanDetectWithoutAPairingSecret() {
        assertTrue(PairedStartupPolicy.shouldExpedite(false, false))
    }

    @Test
    fun unresolvedStartupDoesNotRequireALocalKey() {
        assertTrue(PairedStartupPolicy.shouldExpedite(modeResolved = false, suspended = false))
    }

    @Test
    fun suspendedEnvironmentDoesNotResumeImplicitly() {
        assertFalse(PairedStartupPolicy.shouldExpedite(false, true))
    }

    @Test
    fun resolvedModeDoesNotNeedAnotherDetection() {
        assertFalse(PairedStartupPolicy.shouldExpedite(true, false))
    }

    @Test
    fun resolvedNormalDoesNotCallUltraSdk() {
        assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(true, false) {
            error("resolved mode must not probe")
        })
    }

    @Test
    fun existingOrUnknownKsuEvidenceDoesNotCallUltraSdk() {
        for (responded in listOf(true, null)) {
            assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(false, responded) {
                error("KSU must be excluded first")
            })
        }
    }

    @Test
    fun stalePairingWithUnknownOrStoppedEnvironmentDoesNotProveRunning() {
        var probes = 0
        assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(false, false) {
            probes++
            false
        })
        assertTrue(probes == 1)
    }

    @Test
    fun onlyActualRunningResultIsReportedAsReady() {
        var probes = 0
        assertTrue(PairedStartupPolicy.refreshEnvironmentIfEligible(false, false) {
            probes++
            true
        })
        assertTrue(probes == 1)
    }

    @Test
    fun sdkFailureFallsBackWithoutPretendingReady() {
        assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(false, false) {
            error("SDK_UNAVAILABLE")
        })
    }
}
