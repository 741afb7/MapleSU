package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UltraBootAuthorizationTest {
    private fun source(path: String): String = listOf(File(path), File("manager/$path"))
        .first { it.isFile }.readText()

    @Test fun recoveryReidentifiesTheCurrentOwnerWithoutStartingABroker() {
        val recovery = source("src/main/java/dev/maplesu/manager/runtime/ultra/UltraIdentityRecovery.kt")
        assertTrue(recovery.contains("client.queryManagerOwnerIdentity().identity"))
        assertTrue(recovery.contains("UltraEnvironmentAutoSetup.currentBootId() != boot"))
        assertTrue(recovery.contains("managerOwnerIdentity == owner"))
        assertFalse(recovery.contains("app_process"))
        assertFalse(recovery.contains("ProcessBuilder"))
        assertFalse(recovery.contains("backupKey"))
    }

    @Test fun rootServiceRefreshesOnlyWithinVerifiedOwnerAndRemovalScope() {
        val service = source("src/main/java/dev/maplesu/manager/installer/InstallerPartitionService.kt")
            .substringAfter("override fun refreshUltraAuthorization(").substringBefore("override fun ")
        assertTrue(service.contains("callerVerifier.withCallingManager"))
        assertTrue(service.contains("UltraEnvironmentRemoval.isSuspended"))
        assertTrue(service.contains("client.queryManagerOwnerIdentity().identity"))
        assertTrue(service.contains("authorizationRestore.ensure("))
        assertTrue(service.contains("refreshSuAuthList()"))
        assertFalse(service.contains("addSuAuth("))
    }

    @Test fun productionInstallPreparesTheScopedBackendBeforeSubmission() {
        val installer = source("src/main/java/dev/maplesu/manager/runtime/ultra/UltraEnvironmentInstaller.kt")
        assertTrue(installer.contains("client.scoped { checkScope() == null }"))
        assertTrue(installer.contains("prepareInstall = { UltraPolicyBackendDeployment.prepare(context, scopedClient) }"))
        val install = installer.substringAfter("fun install(port: InstallPort,")
        assertTrue(install.indexOf("attachments.prepareInstall()") in 0 until install.indexOf("port.submitInstall()"))
        assertFalse(installer.contains("backupKey("))
    }
}
