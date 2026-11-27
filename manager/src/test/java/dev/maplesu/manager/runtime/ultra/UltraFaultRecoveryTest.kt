package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test

class UltraFaultRecoveryTest {
    private val fault = UltraEnvFacts(
        installedVersion = "5.1.0", bundledVersion = "5.1.0",
        installedAppVersion = "5.1.0", bundledAppVersion = "5.1.2",
    )

    @Test fun emptyModuleEnvironmentWithFaultHasAnExplicitRecoveryRoute() {
        assertEquals(UltraEnvUpgrade.None, UltraEnvUpgradePolicy.decide(fault))
        assertTrue(UltraEnvironmentUpgrade.faultRepairAllowed("Fault", fault))
    }

    @Test fun faultCanBeRepairedEvenWhenTheInstalledHelperAlreadyMatches() {
        assertTrue(UltraEnvironmentUpgrade.faultRepairAllowed("Fault",
            fault.copy(installedHelperSha256 = "a".repeat(64), bundledHelperSha256 = "a".repeat(64))))
    }

    @Test fun unexpectedStateNeverPermitsFaultRecovery() {
        for (state in listOf(null, "Unknown", "NotInstalled", "Running", "NoKey")) {
            assertFalse(UltraEnvironmentUpgrade.faultRepairAllowed(state, fault))
        }
    }

    @Test fun unconfirmedStateNeedsCompletedBootProofAndReadbackIdentity() {
        val installed = fault.copy(installedHelperSha256 = "a".repeat(64))
        assertTrue(UltraEnvironmentUpgrade.faultRepairAllowed("Unknown", installed, completedBoot = true))
        assertFalse(UltraEnvironmentUpgrade.faultRepairAllowed("Unknown", installed))
        assertFalse(UltraEnvironmentUpgrade.faultRepairAllowed("Unknown", fault, completedBoot = true))
        for (facts in listOf(installed.copy(suspended = true), installed.copy(quiescing = true),
            installed.copy(submittedThisBoot = true), installed.copy(running = true),
            installed.copy(installedVersion = null), installed.copy(installedVersion = "5.2.0"),
            installed.copy(installedHelperSha256 = "unknown"))) {
            assertFalse(UltraEnvironmentUpgrade.faultRepairAllowed("Unknown", facts, completedBoot = true))
        }
    }

    @Test fun completedBootProofIsBoundToTheCurrentBootAndExactResponse() {
        val boot = "01234567-89ab-cdef-0123-456789abcdef"
        assertTrue(UltraEnvironmentUpgrade.completedBootRepairConfirmed(boot, "MAPLESU_COMPLETED_BOOT:$boot\n"))
        for (output in listOf(null, "", "MAPLESU_COMPLETED_BOOT:old", "error\nMAPLESU_COMPLETED_BOOT:$boot")) {
            assertFalse(UltraEnvironmentUpgrade.completedBootRepairConfirmed(boot, output))
        }
        val script = UltraEnvironmentUpgrade.completedBootRepairScript("/data/Abcd0123456789ef/init", boot)
        assertTrue(script.contains("sys.boot_completed"))
        assertTrue(script.contains("/proc/sys/kernel/random/boot_id"))
        assertTrue(script.contains("safe_flag\")\" = 2"))
        assertTrue(script.contains("\"\$work\" != '$boot'"))
        assertFalse(script.contains("rm "))
        assertFalse(script.contains("reboot"))
    }

    @Test fun recoveryKeepsSuspensionSwitchAndPerBootWriteGuards() {
        for (facts in listOf(fault.copy(suspended = true), fault.copy(quiescing = true),
            fault.copy(submittedThisBoot = true), fault.copy(running = true))) {
            assertFalse(UltraEnvironmentUpgrade.faultRepairAllowed("Fault", facts))
        }
    }

    @Test fun recoveryDoesNotDowngradeOrGuessUnreadableVersions() {
        for (facts in listOf(fault.copy(installedVersion = "5.2.0"),
            fault.copy(installedAppVersion = "5.1.3"), fault.copy(installedVersion = null),
            fault.copy(bundledVersion = "broken"), fault.copy(installedAppVersion = "broken"),
            fault.copy(bundledAppVersion = "broken"))) {
            assertFalse(UltraEnvironmentUpgrade.faultRepairAllowed("Fault", facts))
        }
    }

    @Test fun recoveryUsesPreparationAndVerifiedSubmissionWithoutReusingFault() {
        val events = mutableListOf<String>()
        val port = object : UltraEnvironmentInstaller.InstallPort {
            override fun environmentState(): NativeReceipt = error("Recovery must submit the bundled payload")
            override fun submitInstall(): NativeReceipt {
                events += "submit"
                return NativeReceipt.parse("""{"backend":"maplesu_ultra","ok":true,"sdkError":"OK","sdkErrorCode":0}""")!!
            }
        }
        val result = UltraEnvironmentUpgrade.submitMarked(install = {
            UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
                UltraEnvironmentInstaller.Attachments(
                    prepareInstall = { events += "prepare"; null },
                    whitelist = { events += "whitelist"; UltraSelfWhitelist.Result(true, false, true, false, true) },
                    verify = { events += "verify"; IdentityObservation(InstalledIdentity.unknown(),
                        IdentityVerification.passed("test-build")) },
                ))
        }, clear = { error("A submitted recovery must keep the per-boot marker") })
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.resultOf(result))
        assertEquals(listOf("prepare", "submit", "verify", "whitelist"), events)
    }
}
