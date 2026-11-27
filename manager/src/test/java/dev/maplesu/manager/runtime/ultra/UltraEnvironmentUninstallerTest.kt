package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test

class UltraEnvironmentUninstallerTest {
    private fun receipt(ok: Boolean) = NativeReceipt(1, "maplesu_ultra", 1, "uninstall", ok,
        if (ok) "OK" else "SDK_FAILED", if (ok) 0 else -1, null, null, "")

    @Test fun temporaryRemovalKeepsImagesAndPersistsIntentBeforeSdkReboot() {
        val events = mutableListOf<String>()
        val result = UltraEnvironmentUninstaller.uninstall(false,
            restoreStock = { error("temporary removal must retain boot") },
            persistRemoval = { events += "persist" },
            removeSdk = { events += "sdk"; receipt(true) })
        assertTrue(result.ok)
        assertFalse(result.stockRestored)
        assertEquals(listOf("persist", "sdk"), events)
    }

    @Test fun committedRemovalInvalidatesTheCarrierObservationBeforeSdkTeardown() {
        val before = UltraEnvironmentUpgrade.installedKernelRevision.value
        UltraEnvironmentUninstaller.uninstall(false,
            restoreStock = { error("temporary removal must retain boot") },
            persistRemoval = { assertEquals(before, UltraEnvironmentUpgrade.installedKernelRevision.value) },
            removeSdk = {
                assertEquals(before + 1, UltraEnvironmentUpgrade.installedKernelRevision.value)
                receipt(true)
            })
        assertEquals(before + 1, UltraEnvironmentUpgrade.installedKernelRevision.value)
    }

    @Test fun failedStockRestorationDoesNotInvalidateTheCarrierObservation() {
        val before = UltraEnvironmentUpgrade.installedKernelRevision.value
        val result = UltraEnvironmentUninstaller.uninstall(true,
            restoreStock = { UltraEnvironmentUninstaller.Restoration(false, false) },
            persistRemoval = { error("failed restoration must preserve the installed environment") },
            removeSdk = { error("failed restoration must preserve the installed environment") })
        assertFalse(result.ok)
        assertEquals(before, UltraEnvironmentUpgrade.installedKernelRevision.value)
    }

    @Test fun permanentRemovalRestoresImagesAndRebootsWithoutDependingOnSdkRemoval() {
        val events = mutableListOf<String>()
        val result = UltraEnvironmentUninstaller.uninstall(true,
            restoreStock = { events += "restore"; UltraEnvironmentUninstaller.Restoration(true, true) },
            persistRemoval = { events += "disable" },
            removeSdk = { error("restored stock images do not require SDK removal") },
            rebootStock = { events += "reboot-stock"; true })
        assertTrue(result.ok)
        assertTrue(result.stockRestored)
        assertEquals(listOf("restore", "disable", "reboot-stock"), events)
    }

    @Test fun failedStockRestorationRetainsEnvironmentAndNeverRunsSdkTeardown() {
        for (throws in listOf(false, true)) {
            val events = mutableListOf<String>()
            val result = UltraEnvironmentUninstaller.uninstall(true,
                restoreStock = { if (throws) error("missing backup") else UltraEnvironmentUninstaller.Restoration(false, false) },
                persistRemoval = { events += "persist" },
                removeSdk = { events += "sdk"; receipt(true) },
                rebootStock = { events += "reboot"; true })
            assertFalse(result.ok)
            assertFalse(result.stockRestored)
            assertEquals("ULTRA_STOCK_RESTORE_FAILED", result.sdkError)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun restoredImagesRemainVisibleWhenTheRebootRequestFails() {
        val events = mutableListOf<String>()
        val result = UltraEnvironmentUninstaller.uninstall(true,
            restoreStock = { UltraEnvironmentUninstaller.Restoration(true, true) }, persistRemoval = { events += "persist" },
            removeSdk = { events += "sdk"; receipt(true) }, rebootStock = { false })
        assertFalse(result.ok)
        assertTrue(result.stockRestored)
        assertEquals("ULTRA_STOCK_REBOOT_FAILED", result.sdkError)
        assertEquals(listOf("persist"), events)
    }

    @Test fun sdkFailureRemainsVisible() {
        val result = UltraEnvironmentUninstaller.uninstall(false, { UltraEnvironmentUninstaller.Restoration(false, false) }, {}, { receipt(false) })
        assertFalse(result.ok)
        assertEquals("SDK_FAILED", result.sdkError)
    }

    @Test fun ownedRemovalPersistsIntentAfterVerifiedWriteAndDoesNotClaimStock() {
        val events = mutableListOf<String>()
        val result = UltraEnvironmentUninstaller.uninstall(true,
            restoreStock = { events += "remove-owned"; UltraEnvironmentUninstaller.Restoration(true, false) },
            persistRemoval = { events += "persist" },
            removeSdk = { error("the image write removed the startup entry") },
            rebootStock = { events += "reboot"; true })
        assertTrue(result.ok)
        assertFalse(result.stockRestored)
        assertEquals(listOf("remove-owned", "persist", "reboot"), events)
    }

    @Test fun failedOwnedWriteDoesNotDisableTheEnvironmentOrTearDownTheSdk() {
        val events = mutableListOf<String>()
        val result = UltraEnvironmentUninstaller.uninstall(true,
            restoreStock = { UltraEnvironmentUninstaller.Restoration(false, false) },
            persistRemoval = { events += "persist" }, removeSdk = { events += "sdk"; receipt(true) },
            rebootStock = { events += "reboot"; true })
        assertFalse(result.ok)
        assertFalse(result.stockRestored)
        assertTrue(events.isEmpty())
    }

    @Test fun removedOwnedWritesRemainDistinctWhenRebootRequestFails() {
        val result = UltraEnvironmentUninstaller.uninstall(true,
            restoreStock = { UltraEnvironmentUninstaller.Restoration(true, false) }, persistRemoval = {},
            removeSdk = { error("unexpected teardown") }, rebootStock = { false })
        assertFalse(result.ok)
        assertFalse(result.stockRestored)
        assertEquals("ULTRA_STOCK_REBOOT_FAILED", result.sdkError)
    }
}
