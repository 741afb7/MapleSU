// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ultra 环境严格 ready 判定（2026-10-30 P0 修复）。
 *
 * 缺陷：`UltraRuntimeClient.capabilities()` 曾把 `Fault` 也算 ready，导致模块动作
 * 在环境异常态被放行；探针/UI 只认 `Running`。修复后二者共用
 * [UltraReadinessRules.ultraEnvironmentReady]。
 */
class UltraRuntimeClientReadinessTest {

    @Test
    fun runningIsTheOnlyReadyState() {
        assertTrue(UltraReadinessRules.ultraEnvironmentReady("Running"))
        listOf("Fault", "NotInstalled", "NoKey", "Unknown", "", "running", "RUNNING", " Running ")
            .forEach { state ->
                assertFalse(
                    "state '$state' must not be ready (strict machine vocabulary)",
                    UltraReadinessRules.ultraEnvironmentReady(state),
                )
            }
        assertFalse("absent state (SDK unavailable) must not be ready", UltraReadinessRules.ultraEnvironmentReady(null))
    }

    @Test
    fun faultKeepsOnlyRecoveryCapabilities() {
        assertTrue(UltraReadinessRules.isSafeMode("Fault"))
        assertTrue(UltraReadinessRules.moduleRecoveryAvailable("Fault"))
        assertFalse(UltraReadinessRules.ultraEnvironmentReady("Fault"))
        assertTrue(UltraReadinessRules.moduleRecoveryAvailable("Running"))
        listOf(null, "Running", "NotInstalled", "Unknown", "NoKey", "fault", " Fault ")
            .forEach { assertFalse(UltraReadinessRules.isSafeMode(it)) }
        listOf(null, "NotInstalled", "Unknown", "NoKey", "fault", " Fault ")
            .forEach { assertFalse(UltraReadinessRules.moduleRecoveryAvailable(it)) }
    }

    @Test
    fun faultIsExplicitlyNotReady() {
        // 这是修复的直接回归锚点：Fault 曾返回 true。
        assertFalse(UltraReadinessRules.ultraEnvironmentReady("Fault"))
    }

    @Test
    fun sdkUnavailableMapsToNotReady() {
        // bridge 未加载/抛异常时，state 缺失 → 规则必须 false，而不是"未知就当可用"。
        val unavailable = UltraEvidenceReceipt.unavailable(
            operation = "get_maplesu_environment_state",
            error = "UnsatisfiedLinkError: libmaplesu_ultra_bridge.so",
        )
        assertFalse(unavailable.succeeded)
        assertNull(unavailable.state)
        assertFalse(UltraReadinessRules.ultraEnvironmentReady(unavailable.state))
    }

    @Test
    fun successRequiresOkAndOkErrorCode() {
        val ok = UltraEvidenceReceipt(
            operation = "get_maplesu_environment_state",
            ok = true,
            sdkError = "OK",
            sdkErrorCode = 0,
            state = "Running",
        )
        assertTrue(ok.succeeded)

        val failed = UltraEvidenceReceipt(
            operation = "get_maplesu_environment_state",
            ok = true,
            sdkError = "SDK_ERROR_ENVIRONMENT_NOT_READY",
            state = "Running",
        )
        assertFalse("ok=true with non-OK sdkError is not success", failed.succeeded)
    }
}
