package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.recovery.LocalRecoveryFs
import dev.maplesu.manager.recovery.RecoveryBundle
import dev.maplesu.manager.recovery.RecoveryCommandResult
import dev.maplesu.manager.recovery.RecoveryCommandRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.maplesu.manager.litehidden.RecoveryIdentity

class DaemonManagerUninstallTest {
    @get:Rule val temp = TemporaryFolder()
    private var calls = 0
    private var hidden: Boolean? = true
    private var ready = true
    private var result = RecoveryCommandResult(0, "MAPLESU_MANAGER_UNINSTALL ABSENT uid=10489 data=retained\n")

    private fun service(validBundle: Boolean = true, recoveryReady: () -> Boolean = { ready }): DaemonManagerUninstall {
        val apk = temp.newFile("source.apk").apply { writeText("manager") }
        val dir = temp.newFolder("recovery")
        val bundle = RecoveryBundle(LocalRecoveryFs(), dir.absolutePath, locationPolicy = { true })
        bundle.prepare("dev.maplesu.manager", 1, apk.absolutePath)
        if (!validBundle) dir.resolve("manager.apk").writeText("tampered")
        return DaemonManagerUninstall(bundle, RecoveryCommandRunner { calls++; result }, { hidden }, recoveryReady = recoveryReady)
    }

    @Test fun hiddenMustBeProvenBeforeAnyUninstallCommand() {
        hidden = null
        assertTrue(service().uninstall().contains("MANAGER_HIDE_NOT_VERIFIED"))
        assertEquals(0, calls)
    }

    @Test fun tamperedRecoveryStopsBeforeUninstall() {
        assertTrue(service(false).uninstall().contains("MANAGER_RECOVERY_NOT_VERIFIED"))
        assertEquals(0, calls)
    }

    @Test fun missingIndependentSdkRecoveryBlocksManagerRemoval() {
        ready = false
        assertTrue(service().uninstall().contains("MANAGER_FULL_RESTORE_UNAVAILABLE"))
        assertEquals(0, calls)
    }

    @Test fun appOwnedDaemonNeverSubmitsTheDestructiveCommand() {
        val isolation = DaemonProcessIsolation({ "0::/uid_10489/pid_100" }, { 200 }) { _, _ -> true }
        assertTrue(service(recoveryReady = isolation::ensure).uninstall().contains("MANAGER_FULL_RESTORE_UNAVAILABLE"))
        assertEquals(0, calls)
    }

    @Test fun exactAbsentAndRetainedDataReceiptIsRequired() {
        assertEquals("""{"ok":true,"state":"absent","data":"retained"}""", service().uninstall())
        assertEquals(1, calls)
    }

    @Test fun transportZeroAndSuccessTextDoNotMeanUninstalled() {
        result = RecoveryCommandResult(0, "Success\n")
        assertTrue(service().uninstall().contains("MANAGER_UNINSTALL_UNVERIFIED"))
    }

    @Test fun conflictingOrDuplicateReceiptsFail() {
        result = RecoveryCommandResult(0, result.output + "MAPLESU_MANAGER_UNINSTALL FAILED\n")
        assertTrue(service().uninstall().contains("MANAGER_UNINSTALL_UNVERIFIED"))
    }

    @Test fun nonzeroCommandFailsEvenWithSuccessReceipt() {
        result = result.copy(code = 1)
        assertTrue(service().uninstall().contains("MANAGER_UNINSTALL_UNVERIFIED"))
    }

    @Test fun preparedReceiptRetainsOriginalUidWhenPostUninstallVerificationFails() {
        val receipt = temp.newFile("prepared.receipt")
        receipt.writeText("MAPLESU_MANAGER_UNINSTALL PREPARED uid=10489 data=retained\n")
        assertEquals(10489, DaemonManagerUninstall.retainedUid(receipt.path))
        receipt.writeText("MAPLESU_MANAGER_UNINSTALL ABSENT uid=10489 data=retained\n")
        assertEquals(10489, DaemonManagerUninstall.retainedUid(receipt.path))
        receipt.writeText("MAPLESU_MANAGER_UNINSTALL PREPARED uid=0 data=retained\n")
        assertEquals(-1, DaemonManagerUninstall.retainedUid(receipt.path))
    }

    @Test fun scriptIsManagerOnlySynchronousAndKeepsData() {
        val script = DaemonManagerUninstall.script("/data/adb/maplesu/recovery/manager-uninstall.receipt")
        assertTrue(script.contains("pm uninstall -k 'dev.maplesu.manager'"))
        assertTrue(script.contains("pm list packages --user 0 'dev.maplesu.manager'"))
        assertTrue(script.contains("stat -c '%u'"))
        assertTrue(script.contains("chmod 0600"))
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("nohup"))
        assertFalse(script.contains("TOKEN"))
    }

    @Test fun freshRecoveryReadinessRequiresProtectedIdentityAndReadableSdk() {
        val identity = requireNotNull(RecoveryIdentity.of("0123456789abcdef", "dev.maplesu.manager", 10489))
        val sdk = DaemonUltraSdkResult.Ready(dev.maplesu.manager.runtime.ultra.UltraRuntimeClient(), "current-task")
        var calls = 0
        assertTrue(DaemonManagerRecoveryReadiness({ identity.toJson() }, { calls++; true }, { sdk }).ready())
        assertEquals(1, calls)
        assertFalse(DaemonManagerRecoveryReadiness({ null }, { calls++; true }, { sdk }).ready())
        assertFalse(DaemonManagerRecoveryReadiness({ "corrupt" }, { calls++; true }, { sdk }).ready())
        assertEquals(1, calls)
        assertFalse(DaemonManagerRecoveryReadiness({ identity.toJson() }, { false }, { sdk }).ready())
        assertFalse(DaemonManagerRecoveryReadiness({ identity.toJson() }, { error("SDK unavailable") },
            { DaemonUltraSdkResult.Unavailable("BACKEND_UNAVAILABLE", "fixture") }).ready())
    }
}
