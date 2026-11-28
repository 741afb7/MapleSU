package dev.maplesu.manager.runtime.ultra

import com.maplesu.core.ui.screen.settings.environmentInstallEligible
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UltraEnvironmentInstallReadinessWiringTest {
    private fun source(path: String) = listOf(File("src/main/java/$path"), File("manager/src/main/java/$path"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

    @Test fun explicitInstallDelegatesToTheActualSdkOperationWithoutASeparateRootOrChannelGate() {
        assertTrue(environmentInstallEligible(null, null, false, false))
        assertTrue(environmentInstallEligible(WorkMode.LITE, null, false, false))
        val panel = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
            .substringAfter("class UltraEnvironmentInstallPanel")
        val install = panel.substringAfter("fun install()").substringBefore("internal fun environmentInstallVisible")
        val gate = panel.substringAfter("private fun eligible(").substringBefore("fun refresh(")
        for (precondition in listOf("testBasics(\"Channel\")", "kernelRootVerified", "rootAvailable",
            "UltraRootKeyStore.read(", "HookRootExecutor", "preflight")) {
            assertFalse(precondition, gate.contains(precondition))
            assertFalse(precondition, install.contains(precondition))
        }
        assertTrue(install.contains("if (busy || !eligible()) return"))
        assertTrue(install.contains("withContext(Dispatchers.IO + NonCancellable)"))
        assertTrue(install.contains("UltraEnvironmentUpgrade.installBundled(context)"))
        assertTrue(install.contains("result to UltraEnvironmentUpgrade.lastFailureReason"))
        assertTrue(panel.contains("UltraEnvironmentInstallReadiness.NotChecked"))
        assertFalse(install.contains("UltraEnvironmentInstaller.Intent.InstallToBundledBuild"))
        val upgrade = source("dev/maplesu/manager/runtime/ultra/UltraEnvironmentUpgrade.kt")
        assertTrue(upgrade.contains("fun installBundled(context: Context)"))
        assertTrue(upgrade.contains("submit(context, repairFault = false, fullInstall = true)"))
        assertTrue(upgrade.contains("UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild"))
    }

    @Test fun bothSkinsRetainTheManualActionAndExistingDiagnosticNavigation() {
        val settings = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
        for (skin in listOf("Material", "Miuix")) {
            val home = source("com/maplesu/core/ui/screen/home/Home$skin.kt")
            assertTrue(home.contains("rememberUltraEnvironmentInstallPanel(state.environmentMode, state.environmentConfirmedAbsent)"))
            assertTrue(home.contains("onOpenDiagnostics = actions.onOpenEnvironmentSettings"))
            val card = settings.substringAfter("fun UltraEnvironmentInstallCard$skin(").substringBefore("\n@Composable")
            assertTrue(card.contains("panel.install()"))
            assertTrue(card.contains("installActionLabel(panel)"))
            assertTrue(card.contains("onClick = onOpenDiagnostics"))
            assertFalse(card.contains("onInstallImage"))
        }
    }
}
