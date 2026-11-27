package dev.maplesu.manager.ultrakernel

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UltraKernelCoreTaskCleanupTest {
    private fun reference(complete: Boolean = true, task: Boolean = true, callback: Boolean = true,
        filter: Boolean = true, verified: Boolean = true): JsonObject = buildJsonObject {
        putJsonObject("coreLifecycleAbi") {
            put("kernelMetadataComplete", complete)
            putJsonObject("taskState") { if (task) put("layoutWords", 53) }
            putJsonObject("config") { put("CONFIG_SECCOMP_FILTER", if (filter) "y" else "n") }
            putJsonObject("nativeFunctionAbi") { putJsonObject("seccomp_filter_release") { put("verified", verified) } }
        }
        putJsonObject("suProviderAbi") {
            putJsonObject("callbackOrigins") { if (callback) putJsonObject("su.task.validate") { put("source", "msu_task_state_release_current") } }
        }
    }
    @Test fun `complete checked core and SU facts qualify their shared task cleanup`() {
        assertTrue(UltraKernelManagerClaimPayload.coreTaskCleanupQualified(reference()))
    }
    @Test fun `source record presence alone does not qualify native cleanup`() {
        assertFalse(UltraKernelManagerClaimPayload.coreTaskCleanupQualified(reference(complete = false)))
        assertFalse(UltraKernelManagerClaimPayload.coreTaskCleanupQualified(reference(task = false)))
        assertFalse(UltraKernelManagerClaimPayload.coreTaskCleanupQualified(reference(callback = false)))
    }
    @Test fun `filter release requires verified native body facts`() {
        assertFalse(UltraKernelManagerClaimPayload.coreTaskCleanupQualified(reference(verified = false)))
    }
    @Test fun `disabled filter does not require an unused release helper`() {
        assertTrue(UltraKernelManagerClaimPayload.coreTaskCleanupQualified(reference(filter = false, verified = false)))
    }
    @Test fun `missing core and SU records remain unqualified`() {
        assertFalse(UltraKernelManagerClaimPayload.coreTaskCleanupQualified(buildJsonObject {}))
    }
}
