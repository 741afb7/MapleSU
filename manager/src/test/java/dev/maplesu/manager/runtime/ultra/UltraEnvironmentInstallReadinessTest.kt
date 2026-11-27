package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test

class UltraEnvironmentInstallReadinessTest {
    private fun channel(error: String = "OK", ok: Boolean = error == "OK") = NativeReceipt(
        1, "maplesu_ultra", 1, "test_maplesu_basics", ok, error, 0, null, null, "",
    )

    @Test fun onlyASuccessfulLiveChannelPermitsTheEnvironmentAction() {
        assertEquals(UltraEnvironmentInstallReadiness.Ready, UltraEnvironmentInstallReadiness.from(channel()))
        assertEquals(UltraEnvironmentInstallReadiness.Unavailable, UltraEnvironmentInstallReadiness.from(channel(ok = false)))
    }

    @Test fun missingBootAndFailedChannelLeadToImageRecovery() {
        for (error in listOf("ERR_MODULE_NO_BOOT", "ERR_MODULE_TEST_CHANNEL")) {
            val readiness = UltraEnvironmentInstallReadiness.from(channel(error))
            assertEquals(UltraEnvironmentInstallReadiness.NeedsImage, readiness)
            assertTrue(readiness.needsImage)
        }
    }

    @Test fun savedButMismatchedCredentialsNeverPermitAnEnvironmentInstall() {
        val readiness = UltraEnvironmentInstallReadiness.from(channel("ERR_MODULE_ROOT_KEY"))
        assertEquals(UltraEnvironmentInstallReadiness.Unavailable, readiness)
        assertFalse(readiness.needsImage)
    }

    @Test fun bridgeFailuresAndUnknownErrorsRequireAnotherObservation() {
        for (error in listOf("MAPLESU_BRIDGE_ABSENT", "ERR_READ_EOF", "FUTURE_ERROR")) {
            val readiness = UltraEnvironmentInstallReadiness.from(channel(error))
            assertEquals(UltraEnvironmentInstallReadiness.Unavailable, readiness)
            assertFalse(readiness.needsImage)
        }
        assertFalse(UltraEnvironmentInstallReadiness.Ready.needsImage)
    }
}
