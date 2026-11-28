package com.maplesu.core.ui.screen.settings

import com.maplesu.core.R
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstallReadiness
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UltraEnvironmentInstallFailureTest {
    @Test fun upgradeRequiresAProtectedInstalledCarrierAndAnActualUpdate() {
        for (mode in listOf(null, dev.maplesu.manager.workmode.WorkMode.LITE)) {
            assertFalse(installedUltraUpgradeVisible(mode, false, true, false, false))
            assertFalse(installedUltraUpgradeVisible(mode, true, false, false, false))
            assertTrue(installedUltraUpgradeVisible(mode, true, true, false, false))
            assertFalse(installedUltraUpgradeVisible(mode, true, true, true, false))
            assertFalse(installedUltraUpgradeVisible(mode, true, true, false, true))
        }
        assertFalse(installedUltraUpgradeVisible(dev.maplesu.manager.workmode.WorkMode.KSU,
            true, true, false, false))
    }

    @Test fun explicitInstallationEntryDoesNotDependOnHistoricalEnvironmentFacts() {
        for (state in UltraEnvironmentProbe.State.entries) {
            assertTrue(environmentInstallVisible(true, state, false))
            assertTrue(environmentInstallVisible(true, state, true))
            assertFalse(environmentInstallVisible(false, state, true))
        }
    }

    @Test fun uncheckedSummaryOffersAnSdkRequestWithoutClaimingAReadyChannel() {
        assertEquals(R.string.ultra_sdk_install_summary, environmentReadinessSummaryResId(UltraEnvironmentInstallReadiness.NotChecked))
        assertEquals(R.string.ultra_sdk_install_summary, environmentReadinessSummaryResId(UltraEnvironmentInstallReadiness.Ready))
        assertEquals(R.string.ui_d_env_install_kernel_channel, environmentReadinessSummaryResId(UltraEnvironmentInstallReadiness.NeedsImage))
        assertEquals(R.string.ui_d_env_install_failed, environmentReadinessSummaryResId(UltraEnvironmentInstallReadiness.Unavailable))
    }

    @Test
    fun `missing kernel channel has a specific recovery message`() {
        assertEquals(
            R.string.ui_d_env_install_kernel_channel,
            environmentInstallFailureResId("ERR_MODULE_NO_BOOT"),
        )
    }

    @Test
    fun `retired credential errors retain the generic failure message`() {
        assertEquals(
            R.string.ui_d_env_install_failed,
            environmentInstallFailureResId("ERR_MODULE_ROOT_KEY"),
        )
    }

    @Test
    fun `unknown failures keep the generic message`() {
        assertEquals(
            R.string.ui_d_env_install_failed,
            environmentInstallFailureResId("ERR_MODULE_OTHER"),
        )
    }

    private fun source() = listOf(
        java.io.File("src/main/java/com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt"),
        java.io.File("manager/src/main/java/com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt"),
    ).first { it.isFile }.readText().replace("\r\n", "\n")

    @Test fun diagnosticsPreserveTheOriginalRootAndSdkResultsAndMarkHistorySeparately() {
        val root = "operation=root; sdkCode=-19914; childExit=256\r\nERR_READ_CHILD_ERRCODE"
        val component = "operation=get_maplesu_environment_state; ready=false"
        val history = "SDK:POLICY_BACKEND_PREPARATION_FAILED:MODULE_TOOLS_TRANSPORT"
        assertEquals("$root\n$component\nlastFailure=$history",
            environmentDiagnosticDetail(root, component, history))
        assertEquals(root, environmentDiagnosticDetail(root, root))
        assertEquals("lastFailure=$history", environmentDiagnosticDetail(null, " ", history))
        assertNull(environmentDiagnosticDetail(null, "\n", " "))
    }

    @Test fun observationAndCompletionKeepTheLatestFailureUntilAnExplicitRetryStarts() {
        val panel = source().substringAfter("class UltraEnvironmentInstallPanel")
        val refresh = panel.substringAfter("fun refresh(").substringBefore("fun install()")
        assertFalse(refresh.contains("message = null"))
        assertFalse(refresh.contains("detail = null"))
        assertTrue(refresh.contains("visible && message == null"))
        assertTrue(refresh.contains("UltraEnvironmentUpgrade.persistedFailure(context)"))
        val install = panel.substringAfter("fun install()").substringBefore("internal fun environmentInstallVisible")
        val start = install.substringBefore("scope.launch")
        assertTrue(start.contains("message = null"))
        assertTrue(start.contains("detail = null"))
        assertTrue(install.contains("result to UltraEnvironmentUpgrade.lastFailureReason"))
        assertTrue(install.contains("message = summary"))
        assertTrue(install.contains("detail = failureReason.takeUnless"))
        val completion = install.substringAfter("finally {")
        assertTrue(completion.contains("refresh(homeMode, homeConfirmedAbsent)"))
        assertFalse(completion.contains("message = null"))
        assertFalse(completion.contains("detail = null"))
    }

    @Test fun homeShowsShortResultsAndBothSkinsRouteOriginalDetailsToExistingSettings() {
        val code = source()
        val summary = code.substringAfter("private fun installSummary(").substringBefore("@Composable")
        assertTrue(summary.contains("panel.message"))
        assertTrue(summary.contains("panel.needsConfirmation"))
        assertFalse(summary.contains("panel.detail"))
        for (name in listOf("Miuix", "Material")) {
            val card = code.substringAfter("fun UltraEnvironmentInstallCard$name(").substringBefore("\n@Composable")
            assertTrue(card.contains("onOpenDiagnostics: () -> Unit"))
            assertTrue(card.contains("!panel.detail.isNullOrBlank()"))
            assertTrue(card.contains("R.string.work_mode_open_diagnosis"))
            assertTrue(card.contains("onClick = onOpenDiagnostics"))
            assertTrue(card.contains("panel.install()"))
            assertTrue(card.contains("enabled = !panel.busy"))
            assertFalse(card.contains("R.string.home_environment_check_retry"))
        }
        val observation = code.substringAfter("internal suspend fun observeDiagnostic()").substringBefore("private fun captureObservation()")
        assertTrue(observation.contains("currentDiagnosticObservation()"))
        assertTrue(observation.contains("environmentDiagnosticDetail(observation.rootProbeDetail, observation.detail,"))
        assertTrue(observation.contains("UltraEnvironmentUpgrade.persistedFailure(context)"))
        assertEquals(2, code.split("panel.detail?.takeIf(String::isNotBlank)").size - 1)
    }

    @Test fun anUnknownOwnerlessSettingsTargetCanReadOnlyItsSavedInstallFailure() {
        val policy = dev.maplesu.manager.workmode.ui.SettingsModePolicy.forMode(null)
        val snapshot = UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Unknown, managerOwnerIdentity = null)
        assertFalse(policy.shows(dev.maplesu.manager.workmode.ui.SettingsSection.ULTRA_ULTRA_ENV))
        assertFalse(policy.mayRead(dev.maplesu.manager.workmode.ui.SettingsDataSource.ULTRA_ENVIRONMENT))
        assertNull(snapshot.managerOwnerIdentity)
        val original = "INSTALL_FIXTURE_ERR:original detail\r\nchildExit=256"
        var reads = 0
        val detail = savedUltraInstallFailureDiagnostic(enabled = true, mode = policy.mode,
            hidden = false, pending = false) { reads++; original }
        assertEquals(1, reads)
        assertEquals("lastFailure=$original", detail)
        assertNull(policy.mode)
        assertFalse(snapshot.rootAvailable)
    }

    @Test fun disabledResolvedHiddenAndPendingDiagnosticTargetsDoNotEvenReadSavedFailure() {
        fun assertClosed(enabled: Boolean = true, mode: dev.maplesu.manager.workmode.WorkMode? = null,
            hidden: Boolean = false, pending: Boolean = false) {
            var reads = 0
            assertNull(savedUltraInstallFailureDiagnostic(enabled, mode, hidden, pending) {
                reads++
                "INSTALL_FIXTURE_ERR:original detail"
            })
            assertEquals(0, reads)
        }
        // The caller disables this path for appearance pages and either existing environment panel.
        assertClosed(enabled = false)
        assertClosed(mode = dev.maplesu.manager.workmode.WorkMode.KSU)
        assertClosed(mode = dev.maplesu.manager.workmode.WorkMode.LITE)
        assertClosed(hidden = true)
        assertClosed(pending = true)
        assertNull(savedUltraInstallFailureDiagnostic(true, null, false, false) { null })
        assertNull(savedUltraInstallFailureDiagnostic(true, null, false, false) { " \n" })
    }

    @Test fun bothSettingsSkinsConsumeSavedFailureOutsideTheOwnerGatedPanels() {
        for (skin in listOf("Material", "Miuix")) {
            val path = "com/maplesu/core/ui/screen/settings/Settings$skin.kt"
            val code = listOf(java.io.File("src/main/java/$path"), java.io.File("manager/src/main/java/$path"))
                .first { it.isFile }.readText().replace("\r\n", "\n")
            assertTrue(code.contains("val ultraPanel = fullUltraPanel ?: diagnosticPanel"))
            assertTrue(code.contains("val savedInstallFailure = rememberSavedUltraInstallFailureDiagnostic("))
            assertTrue(code.contains("enabled = !appearanceOnly && ultraPanel == null && modePolicy.mode == null && !uiState.hiddenMasked"))
            assertTrue(code.contains("SavedUltraInstallFailureDiagnostic$skin(detail)"))
            assertTrue(code.contains("modePolicy.shows(SettingsSection.ULTRA_ULTRA_ENV) && !uiState.hiddenMasked"))
        }
        val code = source()
        val saved = code.substringAfter("internal fun rememberSavedUltraInstallFailureDiagnostic(")
            .substringBefore("internal fun SavedUltraInstallFailureDiagnosticMiuix(")
        assertTrue(saved.contains("UltraEnvironmentUpgrade.persistedFailure(context)"))
        assertTrue(saved.contains("HiddenFactsSignal.revision.collectAsStateWithLifecycle()"))
        assertTrue(saved.contains("WorkModeSwitchSession.pendingFlow.collectAsStateWithLifecycle()"))
        assertTrue(saved.contains("HiddenStartupConfirm.masks("))
        assertTrue(saved.contains("HiddenStartupConfirm.isPending(context)"))
        assertTrue(saved.contains("LifecycleResumeEffect(context, diagnosticKey)"))
        for (operation in listOf("UltraEnvironmentProbe.currentSnapshot", "UltraEnvironmentProbe.refresh",
            "UltraSdkCredentials", "HookRootExecutor", "readVersion(", "repairEnvironment(", "installBundled(")) {
            assertFalse(operation, saved.contains(operation))
        }
        assertTrue(code.contains("val owner = observation.managerOwnerIdentity ?: return@runCatching null"))
    }
}
