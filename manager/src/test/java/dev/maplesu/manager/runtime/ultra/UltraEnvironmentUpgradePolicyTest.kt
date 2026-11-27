// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraEnvironmentUpgradePolicyTest {
    private val olderRunning = UltraEnvFacts(running = true, installedVersion = "4.6.5", bundledVersion = "5.0.5")

    private fun decide(facts: UltraEnvFacts) = UltraEnvUpgradePolicy.decide(facts)

    @Test fun versionsCompareNumericallyAndRejectAnythingElse() {
        assertTrue(EnvVersion.parse("4.10.0")!! > EnvVersion.parse("4.9.9")!!)
        assertEquals(EnvVersion(4, 6, 5), EnvVersion.parse(" 4.6.5 "))
        for (bad in listOf(null, "", "4.6", "v4.6.5", "4.6.5.1", "4.6.x", "SDK_ERROR")) {
            assertNull("input=$bad", EnvVersion.parse(bad))
        }
    }

    @Test fun bundledReleaseReadsAsMapleSuUltraVersion() {
        assertEquals("5.0.5", UltraEnvironmentVersion.displayOf("5.0.5"))
        assertEquals("4.6.5", UltraEnvironmentVersion.displayOf("4.6.5"))
        assertEquals("4.6.2", UltraEnvironmentVersion.displayOf("4.6.2"))
        assertNull(UltraEnvironmentVersion.displayOf(" "))
        assertNull(UltraEnvironmentVersion.displayOf(null))
    }

    @Test fun olderRunningEnvironmentIsOfferedTheBundledOne() {
        assertEquals(UltraEnvUpgrade.Available(from = "4.6.5", to = "5.0.5"), decide(olderRunning))
    }

    @Test fun sameOrNewerEnvironmentIsNeverDowngraded() {
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(installedVersion = "5.0.5")))
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(installedVersion = "5.1.0")))
    }

    @Test fun onlyARunningEnvironmentIsOffered() {
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(running = false)))
    }

    @Test fun suspensionAndModeSwitchBlockEverythingIncludingThePendingRestart() {
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(suspended = true)))
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(quiescing = true)))
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(quiescing = true, submittedThisBoot = true)))
    }

    @Test fun unreadableVersionsFailClosed() {
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(installedVersion = null)))
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(bundledVersion = null)))
        assertEquals(UltraEnvUpgrade.None, decide(olderRunning.copy(installedVersion = "garbage")))
        assertEquals(UltraEnvUpgrade.None, decide(UltraEnvFacts()))
    }

    @Test fun aSubmittedUpgradeWaitsForTheRestartInsteadOfAskingAgain() {
        assertEquals(UltraEnvUpgrade.Rebooting("5.0.5"), decide(olderRunning.copy(submittedThisBoot = true)))
        assertEquals(
            UltraEnvUpgrade.Rebooting("5.0.5"),
            decide(olderRunning.copy(installedVersion = "5.0.5", submittedThisBoot = true)),
        )
    }

    // ── 接线守卫（源码级）────────────────────────────────────────────

    private fun upgradeSource(): String {
        val cwd = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val manager = if (File(cwd, "src/main/java").isDirectory) cwd else File(cwd, "manager")
        return File(manager, "src/main/java/dev/maplesu/manager/runtime/ultra/UltraEnvironmentUpgrade.kt").readText()
    }

    @Test fun upgradeGoesThroughTheSingleInstallerAndNeverUninstallsFirst() {
        val source = upgradeSource()
        assertTrue(source.contains("UltraEnvironmentInstaller.install("))
        assertFalse("must not call the client directly", source.contains("installEnvironment("))
        assertFalse("uninstall wipes modules and grants", source.contains("uninstallEnvironment"))
    }

    @Test fun upgradeIsUltraOnlyRecheckedAndMarkedBeforeWriting() {
        val source = upgradeSource()
        assertTrue(source.contains("ModeRuntime.active != WorkMode.LITE"))
        assertTrue(source.contains("UltraEnvUpgradePolicy.decide(readObservation(app, {}, binding).first)"))
        assertTrue(source.indexOf(".commit()") in 0 until source.indexOf("UltraEnvironmentInstaller.install("))
    }
}
