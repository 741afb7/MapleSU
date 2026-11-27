package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.liteweb.LiteWebModuleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonRuntimeFactsTest {
    private val normal = DaemonCommittedMode("boot-now", "boot-now", "B", "B", false)

    @Test fun runningSdkWinsOverSameBootNormalMetadata() {
        val resolved = DaemonRuntimePolicy.resolve(DaemonUltraState.RUNNING, normal, "boot-now")
        assertEquals("LITE", resolved.mode)
        assertEquals("ULTRA_ENV", resolved.rootBackend)
        assertEquals("ultra_sdk", resolved.source)
    }

    @Test fun sdkErrorsNeverFallBackToSameBootNormal() {
        for (state in listOf(DaemonUltraState.UNKNOWN, DaemonUltraState.FAULT)) {
            assertEquals(state.name, "UNKNOWN", DaemonRuntimePolicy.resolve(state, normal, "boot-now").mode)
        }
    }

    @Test fun explicitNotInstalledAndCommittedSameBootCanReadNormal() {
        val resolved = DaemonRuntimePolicy.resolve(DaemonUltraState.NOT_INSTALLED, normal, "boot-now")
        assertEquals("KSU", resolved.mode)
        assertEquals("same_boot_committed_mode", resolved.source)
    }

    @Test fun oldBootPendingOrUnverifiedMetadataNeverSelectsNormal() {
        for (metadata in listOf(
            normal.copy(bootId = "old"), normal.copy(verifiedBootId = "old"),
            normal.copy(verifiedBootId = null), normal.copy(current = "A"),
            normal.copy(activeMode = "A"), normal.copy(hasPendingSwitch = true),
        )) {
            assertEquals("UNKNOWN", DaemonRuntimePolicy.resolve(DaemonUltraState.NOT_INSTALLED, metadata, "boot-now").mode)
        }
        assertEquals("UNKNOWN", DaemonRuntimePolicy.resolve(DaemonUltraState.NOT_INSTALLED, normal, null).mode)
        assertEquals("UNKNOWN", DaemonRuntimePolicy.resolve(DaemonUltraState.NOT_INSTALLED, null, "boot-now").mode)
    }

    @Test fun sdkRowsPreserveIdentityStateAndDoNotAdvertiseUnsupportedToggle() {
        val row = DaemonUltraModule("tee", "TEE \"test\"", "desc", "6.0", "author", "Running", true)
        val result = DaemonUltraModuleList.render(listOf(row, row.copy(id32 = "other", state = "RemovedPendingReboot")))
        assertEquals("", result.error)
        assertEquals(2, LiteWebModuleList.count(result.json).count)
        assertTrue(result.json, result.json.contains("\"id\":\"maplesu:tee\""))
        assertTrue(result.json, result.json.contains("\"canToggle\":false"))
        assertTrue(result.json, result.json.contains("\"enabled\":true,\"remove\":false"))
        assertTrue(result.json, result.json.contains("\"enabled\":false,\"remove\":true"))
        assertTrue(result.json, result.json.contains("\"hasWebUi\":true,\"web\":false"))
        assertTrue(result.json, result.json.contains("TEE \\\"test\\\""))
    }

    @Test fun desiredEnablementIsSeparateFromRunningState() {
        val row = DaemonUltraModule("a".repeat(32), "test", "", "", "", "Running", false,
            enabled = false, canToggle = true, needsReboot = true)
        val result = DaemonUltraModuleList.render(listOf(row)).json
        assertTrue(result, result.contains("\"enabled\":false"))
        assertTrue(result, result.contains("\"runtimeStatus\":\"Running\""))
        assertTrue(result, result.contains("\"canToggle\":true"))
        assertTrue(result, result.contains("\"needsReboot\":true"))
        assertFalse(result, result.contains("\"toggle\""))
        assertTrue(DaemonUltraModuleList.render(listOf(row.copy(state = "RemovedPendingReboot"))).json.contains("\"canToggle\":false"))
    }

    @Test fun invalidOrDuplicateSdkIdentityIsAReadError() {
        val row = DaemonUltraModule("tee", "TEE", "", "", "", "Running", false)
        for (rows in listOf(listOf(row, row), listOf(row.copy(id32 = "../normal")))) {
            val result = DaemonUltraModuleList.render(rows)
            assertEquals("ULTRA_MODULE_LIST_INVALID", result.error)
            assertTrue(result.responseJson().contains("\"ok\":false"))
            assertFalse(result.responseJson().contains("\"modules\""))
        }
    }
}
