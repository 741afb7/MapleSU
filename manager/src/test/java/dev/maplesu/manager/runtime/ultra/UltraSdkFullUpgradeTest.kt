package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.installer.*
import dev.maplesu.manager.workmode.WorkMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UltraSdkFullUpgradeTest {
    private val request = UltraDirectInstallRequest(WorkMode.LITE, InstallTargetMode.ULTRA,
        InstallSourceKind.CURRENT_PARTITION, "boot", environmentWasRunning = true)

    @Test fun explicitInstallDoesNotRequireOldVersionOrHelperRecords() {
        for (facts in listOf(UltraEnvFacts(), UltraEnvFacts(readUnknown = true),
            UltraEnvFacts(running = true, installedVersion = "5.2.1", bundledVersion = "5.2.1"))) {
            var calls = 0
            val result = UltraDirectInstallPolicy.dispatch(request, { facts },
                { calls++; "INSTALL" }, { "UNAVAILABLE" }, { "PATCH" })
            assertEquals("Explicit installation must reach the installer", "INSTALL", result)
            assertEquals(1, calls)
        }
    }

    @Test fun readFailureDoesNotBlockAnExplicitInstallation() {
        var calls = 0
        val result = UltraDirectInstallPolicy.dispatch(request,
            { error("OLD_INSTALL_RECORD_UNREADABLE") }, { calls++; "INSTALL" },
            { "UNAVAILABLE" }, { "PATCH" })
        assertEquals("INSTALL", result)
        assertEquals(1, calls)
    }

    @Test fun fullUpgradeInitializesAndMigratesAuthorityBeforeSelfGrant() {
        val order = mutableListOf<String>()
        val result = UltraEnvironmentInstaller.install(port(order),
            UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            UltraEnvironmentInstaller.Attachments(
                whitelist = { order += "grant"; grant(true) },
                initializeAuthority = { order += "authority"; null }))
        assertTrue(result.verified)
        assertEquals(listOf("install", "authority", "grant"), order)
    }

    @Test fun upgradeDoesNotReportCompleteWithPendingAuthorization() {
        val result = UltraEnvironmentInstaller.install(port(mutableListOf()),
            UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            UltraEnvironmentInstaller.Attachments(whitelist = { grant(false) }))
        assertTrue(result.ok)
        assertFalse("Written files do not establish working authorization", result.verified)
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.resultOf(result))
    }

    @Test fun upgradeDoesNotGrantOverFailedAuthorityMigration() {
        var grantCalls = 0
        val result = UltraEnvironmentInstaller.install(port(mutableListOf()),
            UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            UltraEnvironmentInstaller.Attachments(
                whitelist = { grantCalls++; grant(true) },
                initializeAuthority = { "AUTH_MIGRATION_PENDING" }))
        assertTrue(result.ok)
        assertFalse(result.verified)
        assertEquals(0, grantCalls)
    }

    @Test fun aConfirmedConcurrentChangeStillStopsInstallation() {
        for (facts in listOf(UltraEnvFacts(quiescing = true), UltraEnvFacts(suspended = true),
            UltraEnvFacts(submittedThisBoot = true, bundledVersion = "5.2.1"))) {
            assertEquals("WAIT", UltraDirectInstallPolicy.dispatch(request, { facts },
                { error("unexpected write") }, { "WAIT" }, { error("unexpected patch") }))
        }
    }

    @Test fun aLegacyChannelWithoutReadableVersionsDoesNotClaimAnUpgrade() {
        val facts = UltraEnvFacts(bundledVersion = "5.2.1", readUnknown = true, observationScope = "current")
        assertEquals(UltraEnvUpgrade.None, UltraEnvUpgradePolicy.decide(facts))
        assertTrue(UltraEnvObservation.observe(null, facts, "current")!!.readUnknown)
    }

    @Test fun anUnconfirmedReadOrCurrentProtocolNeverClaimsAnUpgrade() {
        assertEquals(UltraEnvUpgrade.None,
            UltraEnvUpgradePolicy.decide(UltraEnvFacts(bundledVersion = "5.2.1", readUnknown = true)))
        assertEquals(UltraEnvUpgrade.None, UltraEnvUpgradePolicy.decide(UltraEnvFacts(
            running = true, installedVersion = "5.2.1", bundledVersion = "5.2.1",
            identityKnown = true, installedHelperSha256 = "a".repeat(64), bundledHelperSha256 = "a".repeat(64))))
    }

    @Test fun rebootContinuationNeverReplaysAnAlreadySubmittedStage() {
        assertEquals("NONE", UltraEnvironmentUpgrade.fullInstallContinuation(null, null, "boot-1"))
        assertEquals("WAIT", UltraEnvironmentUpgrade.fullInstallContinuation("boot-1", null, "boot-1"))
        assertEquals("INSTALL", UltraEnvironmentUpgrade.fullInstallContinuation("boot-1", null, "boot-2"))
        assertEquals("WAIT", UltraEnvironmentUpgrade.fullInstallContinuation("boot-1", "boot-2", "boot-2"))
        assertEquals("VERIFY", UltraEnvironmentUpgrade.fullInstallContinuation("boot-1", "boot-2", "boot-3"))
        assertEquals("VERIFY", UltraEnvironmentUpgrade.fullInstallContinuation(null, "boot-2", "boot-3"))
    }

    @Test fun damagedHistoricalMarkersDoNotBlockCurrentEnvironmentRecovery() {
        for (invalid in listOf(null, "", " ", 1, false, setOf("boot-1"))) {
            assertEquals("NONE", UltraEnvironmentUpgrade.fullInstallContinuation(invalid, invalid, "boot-2"))
            assertEquals("INSTALL", UltraEnvironmentUpgrade.fullInstallContinuation("boot-1", invalid, "boot-2"))
            assertEquals("VERIFY", UltraEnvironmentUpgrade.fullInstallContinuation(invalid, "boot-1", "boot-2"))
        }
    }

    private fun port(order: MutableList<String>) = object : UltraEnvironmentInstaller.InstallPort {
        override fun environmentState(): NativeReceipt = error("Explicit upgrade must not require old state")
        override fun submitInstall(): NativeReceipt {
            order += "install"
            return NativeReceipt(1, "maplesu_ultra", 1, "install_maplesu_environment", true,
                "OK", 0, null, JSONObject(), "{}")
        }
    }

    private fun grant(ok: Boolean) = UltraSelfWhitelist.Result(ok, false, ok, true, ok,
        if (ok) null else "SU_AUTH_APPLY_PENDING")
}
