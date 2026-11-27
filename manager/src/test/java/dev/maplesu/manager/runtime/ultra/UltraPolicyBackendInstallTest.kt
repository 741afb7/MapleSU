package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraPolicyBackendInstallTest {
    private fun state(value: String) = NativeReceipt.parse(
        """{"backend":"maplesu_ultra","ok":true,"sdkError":"OK","sdkErrorCode":0,"data":{"state":"$value"}}""")!!
    private fun failed() = NativeReceipt.parse("""{"backend":"maplesu_ultra","ok":false,"sdkError":"FIXTURE_SUBMIT","sdkErrorCode":-1001}""")!!
    private fun attachments(events: MutableList<String>, failure: () -> String? = { null }) =
        UltraEnvironmentInstaller.Attachments(
            whitelist = { UltraSelfWhitelist.Result(true, true, true, true, true) },
            prepareInstall = { events += "backend"; failure() },
        )

    @Test fun upgradePublishesBackendBeforeSubmittingTheRebootCapableSdk() {
        val events = mutableListOf<String>()
        val port = object : UltraEnvironmentInstaller.InstallPort {
            override fun environmentState(): NativeReceipt = error("explicit upgrade needs no extra probe")
            override fun submitInstall(): NativeReceipt { events += "submit"; return failed() }
        }
        UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild, attachments(events))
        assertEquals(listOf("backend", "submit"), events)
    }

    @Test fun failedOrInterruptedPublicationDoesNotSubmitANewHelper() {
        for (throwFailure in listOf(false, true)) {
            val events = mutableListOf<String>()
            val port = object : UltraEnvironmentInstaller.InstallPort {
                override fun environmentState() = state("NotInstalled")
                override fun submitInstall(): NativeReceipt = error("publication must finish first")
            }
            val result = UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.ReuseRunning,
                attachments(events) { if (throwFailure) error("interrupted") else "UNVERIFIED_BACKEND" })
            assertFalse(result.ok)
            assertEquals("POLICY_BACKEND_PREPARATION_FAILED", result.sdkError)
            assertEquals(listOf("backend"), events)
        }
    }

    @Test fun cancelledBackendPreparationKeepsTheOriginalCancellationAndNeverSubmits() {
        val events = mutableListOf<String>()
        val cancelled = java.util.concurrent.CancellationException("publication cancelled")
        val port = object : UltraEnvironmentInstaller.InstallPort {
            override fun environmentState() = state("NotInstalled")
            override fun submitInstall(): NativeReceipt = error("cancelled preparation must not submit")
        }
        val observed = assertThrows(java.util.concurrent.CancellationException::class.java) {
            UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.ReuseRunning,
                attachments(events) { throw cancelled })
        }
        assertTrue(observed is UltraInstallPreparationCancelled)
        assertSame(cancelled, observed.cause)
        assertEquals(listOf("backend"), events)
    }

    @Test fun failedToolsPreflightNeverFirstReplacesTheCurrentIdentity() {
        for (intent in listOf(UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            UltraEnvironmentInstaller.Intent.RefreshToBundledBuild)) {
            val events = mutableListOf<String>()
            val port = object : UltraEnvironmentInstaller.InstallPort {
                override fun environmentState(): NativeReceipt = error("explicit install")
                override fun submitInstall(): NativeReceipt = error("preflight failed")
            }
            val result = UltraEnvironmentInstaller.install(port, intent,
                attachments(events) { "MODULE_TOOLS_TRANSPORT:FIXTURE_EXIT" })
            assertFalse(result.ok)
            assertEquals("POLICY_BACKEND_PREPARATION_FAILED", result.sdkError)
            assertEquals(listOf("backend"), events)
        }
    }

    @Test fun firstInstallationPreparesBackendBeforeSubmission() {
        val events = mutableListOf<String>()
        val port = object : UltraEnvironmentInstaller.InstallPort {
            override fun environmentState() = state("NotInstalled")
            override fun submitInstall(): NativeReceipt { events += "submit"; return failed() }
        }
        UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.ReuseRunning, attachments(events))
        assertEquals(listOf("backend", "submit"), events)
    }

    @Test fun failedObservationDoesNotPublishABackend() {
        val events = mutableListOf<String>()
        val port = object : UltraEnvironmentInstaller.InstallPort {
            override fun environmentState() = state("Unknown")
            override fun submitInstall(): NativeReceipt = error("unknown state must preserve installation")
        }
        assertFalse(UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.ReuseRunning, attachments(events)).ok)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun reusingRunningEnvironmentDoesNotPublishOrSubmitAnything() {
        val events = mutableListOf<String>()
        val port = object : UltraEnvironmentInstaller.InstallPort {
            override fun environmentState() = state("Running")
            override fun submitInstall(): NativeReceipt = error("Running must be reused")
        }
        assertTrue(UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.ReuseRunning, attachments(events)).ok)
        assertEquals(emptyList<String>(), events)
    }
}
