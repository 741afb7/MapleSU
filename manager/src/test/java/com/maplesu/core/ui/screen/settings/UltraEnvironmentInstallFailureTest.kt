package com.maplesu.core.ui.screen.settings

import com.maplesu.core.R
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstallReadiness
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test fun installationRequiresAnActualMissingEnvironmentOrKernelReceipt() {
        for (state in UltraEnvironmentProbe.State.entries) {
            assertEquals(state == UltraEnvironmentProbe.State.NotInstalled,
                environmentInstallVisible(true, state, false))
            assertFalse(environmentInstallVisible(false, state, true))
        }
        assertTrue(environmentInstallVisible(true, UltraEnvironmentProbe.State.Unknown, true))
        assertFalse(environmentInstallVisible(true, UltraEnvironmentProbe.State.Unknown, false))
        assertFalse(environmentInstallVisible(true, UltraEnvironmentProbe.State.NoKey, false))
    }

    @Test fun uncheckedSummaryOffersAnSdkRequestWithoutClaimingAReadyChannel() {
        assertEquals(R.string.ui_d_env_install_summary, environmentReadinessSummaryResId(UltraEnvironmentInstallReadiness.NotChecked))
        assertEquals(R.string.ui_d_env_install_summary, environmentReadinessSummaryResId(UltraEnvironmentInstallReadiness.Ready))
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
}
