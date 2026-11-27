package dev.maplesu.manager.installer

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UltraDirectUpgradeWiringTest {
    private fun source(path: String) = File("src/main/java/$path").readText()

    @Test fun explicitRepairAndInstallCheckBootCarrierBeforeSdkOnlyUpgrade() {
        val upgrade = source("dev/maplesu/manager/runtime/ultra/UltraEnvironmentUpgrade.kt")
        assertTrue(upgrade.indexOf("UltraKernelEnvironmentUpgrade.required(app)") <
            upgrade.indexOf("UltraInstalledIdentityProbe.invalidate()"))
        assertTrue(upgrade.contains("installUltraKernelUpdate(taskId, onTaskSelected = { selectedTask = it }"))
        assertTrue(upgrade.contains("InstallerServiceClient.rebootAfterInstallation(context, selectedTask)"))
        val cli = source("com/maplesu/core/ui/util/KsuCli.kt")
        assertTrue(cli.contains("UltraKernelEnvironmentUpgrade.required(ksuApp)"))
        val kernelUpdate = cli.substringAfter("internal fun installUltraKernelUpdate(")
            .substringBefore("private class InstallBuildStopped")
        val observed = kernelUpdate.indexOf("InstallerServiceClient.observeBootChain(ksuApp)")
        val carrier = kernelUpdate.indexOf("InstallerServiceClient.installedCarrierPartition(ksuApp, observed)")
        val signing = kernelUpdate.indexOf("UltraKernelEnvironmentUpgrade.signingOptions(ksuApp)")
        val install = kernelUpdate.indexOf("val first = install(partition, taskId)")
        assertTrue(observed >= 0 && carrier > observed && signing > carrier && install > signing)
        val startup = source("dev/maplesu/manager/runtime/ultra/UltraEnvironmentAutoSetup.kt")
        assertFalse(startup.contains("UltraKernelEnvironmentUpgrade"))
        assertFalse(startup.contains("installUltraKernelUpdate"))
    }

    @Test fun explicitKernelUpgradeKeepsOnlyTheSigningReceiptForTheCurrentInstalledBoot() {
        val upgrade = source("dev/maplesu/manager/installer/UltraKernelEnvironmentUpgrade.kt")
        assertTrue(upgrade.contains("status.getString(\"partitionSha256\") == currentSha"))
        assertTrue(upgrade.contains("status.getString(\"partition\") == partition"))
        assertTrue(upgrade.contains("status.getString(\"targetMode\") == \"LITE\""))
        assertTrue(upgrade.contains("installedRecord(context, currentSha, partition, snapshot.slotSuffix)?.getString(\"signingMode\")"))
        assertTrue(upgrade.contains("return status.takeIf { installed }"))
        val cli = source("com/maplesu/core/ui/util/KsuCli.kt")
            .substringAfter("internal fun installUltraKernelUpdate(").substringBefore("private class InstallBuildStopped")
        assertTrue(cli.contains("dev.maplesu.manager.installer.UltraKernelEnvironmentUpgrade.signingOptions(ksuApp)"))
        assertTrue(cli.contains("lenovoSigning = signingOptions"))
    }

    @Test fun sdkRouteReturnsBeforeAnyBootTransactionAndDoesNotClaimWrittenBoot() {
        val cli = source("com/maplesu/core/ui/util/KsuCli.kt").substringAfter("fun installBoot(")
        assertTrue("Ultra direct install must select the SDK route before patching the current boot",
            cli.contains("UltraDirectInstallPolicy.dispatch"))
        assertTrue(cli.indexOf("UltraDirectInstallPolicy.dispatch") < cli.indexOf("val targetDecision = InstallTargetDispatch.decide("))
        assertTrue(cli.indexOf("if (environmentResult != null) return environmentResult") < cli.indexOf("return installStockTarget("))
        val task = source("dev/maplesu/manager/installer/UltraDirectEnvironmentUpgrade.kt")
        assertTrue(task.contains("UltraEnvironmentUpgrade.upgrade(context)"))
        assertTrue(task.indexOf("record(context, taskId, \"STARTED\")") < task.indexOf("UltraEnvironmentUpgrade.upgrade(context)"))
        assertTrue(task.contains("FlashOutcome.ENVIRONMENT_UPDATED"))
        assertFalse(task.contains("FlashOutcome.WRITTEN"))
        assertFalse(task.contains("InstallerServiceClient"))
    }

    @Test fun processRecoveryObservesEnvironmentResultBeforeNativeTaskAndNeverReplaysTheSdk() {
        val model = source("dev/maplesu/manager/installer/InstallerFlashViewModel.kt")
        assertTrue(model.indexOf("UltraDirectEnvironmentUpgrade.resume") < model.indexOf("else if (resume && transactional) observe"))
        val task = source("dev/maplesu/manager/installer/UltraDirectEnvironmentUpgrade.kt")
            .substringAfter("fun resume(").substringBefore("private fun completed")
        assertFalse(task.contains("upgrade(context)"))
        assertTrue(task.contains("status == UltraEnvironmentUpgrade.Result.SUBMITTED.name"))
        assertTrue(task.contains("ultra_env_update_interrupted"))
    }

    @Test fun environmentCompletionOffersNeitherBootExportNorNativeTransactionReboot() {
        val screen = source("com/maplesu/core/ui/screen/flash/FlashScreen.kt")
        assertTrue(screen.contains("flashOutcome != FlashOutcome.ENVIRONMENT_UPDATED &&"))
        assertTrue(screen.contains("flashOutcome != FlashOutcome.WRITTEN ||"))
        val status = source("com/maplesu/core/ui/screen/flash/FlashStatusBanner.kt")
        assertTrue(status.contains("FlashOutcome.ENVIRONMENT_UPDATED -> stringResource(R.string.ultra_env_update_complete)"))
        assertTrue(status.contains("state.stage != InstallStage.UPGRADING_ENVIRONMENT"))
    }

    @Test fun allModuleCleanupEntryPointsUseTheSameMigrationPolicy() {
        val screen = source("com/maplesu/core/ui/screen/install/InstallScreen.kt")
        assertEquals(3, Regex("UltraDirectInstallPolicy.requiresModuleChoice").findAll(screen).count())
    }

    @Test fun generationFailureRetainsTheUnderlyingHookReason() {
        val message = source("com/maplesu/core/ui/util/UltraKernelInstall.kt")
            .substringAfter("internal fun ultraGenerateFailureMessage(").substringBefore("Production implementation")
        assertTrue(message.contains("UltraSdkLogging.redact(error.detail)"))
        assertTrue(message.contains("take(200)"))
    }
}
