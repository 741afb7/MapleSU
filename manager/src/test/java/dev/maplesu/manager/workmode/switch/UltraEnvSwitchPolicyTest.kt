package dev.maplesu.manager.workmode.switch

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.UltraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 第 2 项：Ultra 环境 × 双模式切换互操作策略矩阵 + 生命周期执行器（host）。
 *
 * 新语义（2026-11-01 / wave4）：
 * - A→B：RUNNING/FAULT/UNKNOWN/NOT_INSTALLED 全部 `Allowed`（写回 stock boot 即停 Ultra 内核）；
 * - B→A：缺 Ultra 资产不阻断，改走安装/修补流程（actions 仅说明性）；
 * - 旧 NO_KEY 观测按未知处理；实际根通道由 preflight 核实；
 * - `beforeSwitch` 不再调 `suspend()`；
 * - `afterVerified`（S-2）改成**方向感知 + 新鲜探针**：A→B 期望 Ultra 已停、B→A 期望 Ultra 可用，
 *   不再"恒 OK"（那是 fail-open），也不再依赖恒失败的 `restore()`。见下方 afterVerified 用例。
 */
class UltraEnvSwitchPolicyTest {

    private fun code(decision: UltraSwitchDecision): String = when (decision) {
        is UltraSwitchDecision.Block -> decision.blocker.code
        is UltraSwitchDecision.MustSuspendUltraFirst -> decision.blocker.code
        is UltraSwitchDecision.MustPrepareUltraRestore -> decision.blocker.code
        is UltraSwitchDecision.Allowed -> ""
    }

    private fun actions(decision: UltraSwitchDecision): List<UltraSwitchAction> =
        (decision as? UltraSwitchDecision.Allowed)?.actions.orEmpty()

    // ------------------------------------------------------------------ 策略矩阵

    @Test
    fun aToBWithUltraRunningIsAllowedBecauseStockBootStopsPro() {
        val decision = UltraEnvSwitchPolicy.evaluate(WorkMode.LITE, WorkMode.KSU, UltraState.RUNNING)
        assertTrue("RUNNING 必须放行（写回 stock boot 即停 Ultra 内核）: $decision",
            decision is UltraSwitchDecision.Allowed)
        assertTrue(UltraSwitchAction.ULTRA_STOP_VIA_STOCK_BOOT in actions(decision))
    }

    @Test
    fun aToBWithUltraNotInstalledIsAllowed() {
        val decision = UltraEnvSwitchPolicy.evaluate(WorkMode.LITE, WorkMode.KSU, UltraState.NOT_INSTALLED)
        assertTrue(decision is UltraSwitchDecision.Allowed)
        assertTrue(actions(decision).isEmpty())
    }

    @Test
    fun aToBNeverBlocksOnFaultOrUnknown() {
        for (state in listOf(UltraState.FAULT, UltraState.UNKNOWN)) {
            val decision = UltraEnvSwitchPolicy.evaluate(WorkMode.LITE, WorkMode.KSU, state)
            assertTrue("$state 不得前置阻断（交给 preflight 实际失败）: $decision",
                decision is UltraSwitchDecision.Allowed)
        }
    }

    @Test
    fun retiredNoKeyObservationUsesTheUnknownPolicy() {
        val decision = UltraEnvSwitchPolicy.evaluate(WorkMode.LITE, WorkMode.KSU, UltraState.NO_KEY)
        assertEquals(UltraEnvSwitchPolicy.evaluate(WorkMode.LITE, WorkMode.KSU, UltraState.UNKNOWN), decision)
    }

    @Test
    fun bToAWithUltraRunningIsAllowedAndRequiresVerify() {
        val decision = UltraEnvSwitchPolicy.evaluate(WorkMode.KSU, WorkMode.LITE, UltraState.RUNNING)
        assertTrue(decision is UltraSwitchDecision.Allowed)
        assertTrue(UltraSwitchAction.RESTORE_ULTRA_AFTER_VERIFY in actions(decision))
    }

    @Test
    fun bToAWithoutUltraIsAllowedAndSchedulesRestore() {
        for (state in listOf(UltraState.NOT_INSTALLED, UltraState.FAULT, UltraState.UNKNOWN, UltraState.NO_KEY)) {
            val decision = UltraEnvSwitchPolicy.evaluate(WorkMode.KSU, WorkMode.LITE, state)
            assertTrue("$state 缺资产不得弹阻断，改走安装/修补流程: $decision",
                decision is UltraSwitchDecision.Allowed)
            assertTrue("$state", UltraSwitchAction.PREPARE_ULTRA_RESTORE_BEFORE_SWITCH in actions(decision))
            assertTrue("$state", UltraSwitchAction.RESTORE_ULTRA_AFTER_VERIFY in actions(decision))
        }
    }

    @Test
    fun bToAWithNoKeyPreparesFirstUltraCredential() {
        val decision = UltraEnvSwitchPolicy.evaluate(WorkMode.KSU, WorkMode.LITE, UltraState.NO_KEY)
        assertTrue(decision is UltraSwitchDecision.Allowed)
        assertTrue(UltraSwitchAction.PREPARE_ULTRA_RESTORE_BEFORE_SWITCH in actions(decision))
        assertTrue(UltraSwitchAction.RESTORE_ULTRA_AFTER_VERIFY in actions(decision))
    }

    @Test
    fun sameModeIsInvalidInput() {
        assertEquals(UltraSwitchCodes.ULTRA_INPUT_INVALID,
            code(UltraEnvSwitchPolicy.evaluate(WorkMode.LITE, WorkMode.LITE, UltraState.RUNNING)))
        assertEquals(UltraSwitchCodes.ULTRA_INPUT_INVALID,
            code(UltraEnvSwitchPolicy.evaluate(WorkMode.KSU, WorkMode.KSU, UltraState.NOT_INSTALLED)))
    }

    @Test
    fun allCrossModeObservationsDelegateToTheRealPreflight() {
        for ((from, to) in listOf(WorkMode.LITE to WorkMode.KSU, WorkMode.KSU to WorkMode.LITE)) {
            for (state in UltraState.entries) {
                assertTrue("$from→$to $state", UltraEnvSwitchPolicy.evaluate(from, to, state) is UltraSwitchDecision.Allowed)
            }
        }
    }

    @Test
    fun policyIsIndependentOfHiddenStateMatrix() {
        // Ultra 判定不读取隐藏（隐藏由 facade 的 HiddenSwitchCoordinator 单独追加阻断码）；
        // P0-1 后两者都不再对 Ultra 状态产生阻断。
        val aToBRunning = UltraEnvSwitchPolicy.evaluate(WorkMode.LITE, WorkMode.KSU, UltraState.RUNNING)
        val bToANotInstalled = UltraEnvSwitchPolicy.evaluate(WorkMode.KSU, WorkMode.LITE, UltraState.NOT_INSTALLED)
        assertTrue(aToBRunning is UltraSwitchDecision.Allowed)
        assertTrue(bToANotInstalled is UltraSwitchDecision.Allowed)
    }

    // ------------------------------------------------------------------ 执行器

    private class FakePort(var state: UltraState = UltraState.RUNNING) : UltraEnvironmentPort {
        var kernelRoot = false
        override fun kernelRootVerified() = kernelRoot
        var suspendResult: HookResult = HookResult.OK
        var restoreResult: HookResult = HookResult.OK
        val calls = mutableListOf<String>()
        override fun state(): UltraState = state
        override fun suspend(): HookResult {
            calls += "suspend"
            return suspendResult
        }
        override fun restore(): HookResult {
            calls += "restore"
            return restoreResult
        }
    }

    @Test
    fun beforeSwitchNeverCallsSuspendForAnyUltraState() {
        for (state in UltraState.entries) {
            val port = FakePort(state)
            val result = UltraEnvSwitchCoordinator(port).beforeSwitch(WorkMode.LITE, WorkMode.KSU)
            assertTrue("$state 不得再调 suspend(): ${port.calls}", port.calls.isEmpty())
            assertTrue("$state delegates to the real preflight: $result", result.ok)
        }
    }

    @Test
    fun beforeSwitchIgnoresUnsupportedSuspendPort() {
        // 生产实现恒返回 ULTRA_SUSPEND_UNSUPPORTED：修复前它会阻断 A→B，现在根本不会被调用。
        val port = object : UltraEnvironmentPort {
            override fun state() = UltraState.RUNNING
            override fun suspend() = HookResult.failed(UltraSwitchCodes.ULTRA_SUSPEND_UNSUPPORTED, "not wired")
            override fun restore() = HookResult.OK
        }
        val result = UltraEnvSwitchCoordinator(port).beforeSwitch(WorkMode.LITE, WorkMode.KSU)
        assertTrue("A→B 不得再被 suspend 未接线挡住: $result", result.ok)
        assertFalse(result.recoveryRequired)
    }

    @Test
    fun coordinatorAllowsAToBWithoutUltraAndSkipsSuspend() {
        val port = FakePort(UltraState.NOT_INSTALLED)
        val result = UltraEnvSwitchCoordinator(port).beforeSwitch(WorkMode.LITE, WorkMode.KSU)
        assertTrue(result.ok)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun coordinatorAllowsBToAWithoutUltraAndDoesNotTouchPort() {
        for (state in listOf(UltraState.NOT_INSTALLED, UltraState.FAULT, UltraState.UNKNOWN)) {
            val port = FakePort(state)
            val result = UltraEnvSwitchCoordinator(port).beforeSwitch(WorkMode.KSU, WorkMode.LITE)
            assertTrue("$state 不得阻断反向切换: $result", result.ok)
            assertTrue("$state: ${port.calls}", port.calls.isEmpty())
        }
    }

    @Test
    fun afterVerifiedSwitchingToKsuBlocksWhenUltraStillRunning() {
        // S-2：A→B 期望 Ultra 已停。仍 RUNNING ⇒ 不得 Verified（写回 stock boot 没生效）。
        val port = FakePort(UltraState.RUNNING)
        val result = UltraEnvSwitchCoordinator(port).afterVerified(WorkMode.KSU)
        assertFalse("Ultra 内核仍在运行不得 Verified: $result", result.ok)
        assertEquals(UltraSwitchCodes.ULTRA_ENV_MUST_SUSPEND, result.code)
        assertFalse("分区已写好、数据没坏 ⇒ 不得要求人工恢复", result.recoveryRequired)
        assertTrue("不得再调 suspend()/restore(): ${port.calls}", port.calls.isEmpty())
    }

    @Test
    fun afterVerifiedSwitchingToKsuVerifiesWhenUltraStopped() {
        // S-2：A→B 的**预期**结果就是 Ultra 不在了（NOT_INSTALLED/FAULT/UNKNOWN/NO_KEY）。
        for (state in listOf(UltraState.NOT_INSTALLED, UltraState.FAULT, UltraState.UNKNOWN, UltraState.NO_KEY)) {
            val port = FakePort(state)
            val result = UltraEnvSwitchCoordinator(port).afterVerified(WorkMode.KSU)
            assertTrue("$state 是 A→B 的预期结果，必须 Verified: $result", result.ok)
            assertTrue(port.calls.isEmpty())
        }
    }

    @Test
    fun afterVerifiedSwitchingToLiteVerifiesWhenUltraRunning() {
        // S-2：B→A 期望 Ultra 可用（Ultra 的根提供方）。
        val port = FakePort(UltraState.RUNNING)
        val result = UltraEnvSwitchCoordinator(port).afterVerified(WorkMode.LITE)
        assertTrue(result.ok)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun switchingToUltraAcceptsTheCurrentKeyChannelWithoutFakingHelperRunning() {
        for (state in listOf(UltraState.UNKNOWN, UltraState.NOT_INSTALLED)) {
            val port = FakePort(state).apply { kernelRoot = true }
            assertTrue("the Ultra kernel itself is live: $state", UltraEnvSwitchCoordinator(port).afterVerified(WorkMode.LITE).ok)
            assertEquals(state, port.state)
            assertTrue(port.calls.isEmpty())
        }
    }

    @Test fun aKernelProofNeverOverridesFaultNoKeyOrNormalSwitchRules() {
        for (state in listOf(UltraState.FAULT, UltraState.NO_KEY)) {
            val port = FakePort(state).apply { kernelRoot = true }
            assertFalse(UltraEnvSwitchCoordinator(port).afterVerified(WorkMode.LITE).ok)
        }
        for (state in UltraState.entries) {
            val port = FakePort(state).apply { kernelRoot = true }
            assertEquals(state != UltraState.RUNNING, UltraEnvSwitchCoordinator(port).afterVerified(WorkMode.KSU).ok)
        }
    }

    @Test
    fun afterVerifiedSwitchingToLiteRequiresRecoveryWhenUltraMissing() {
        // S-2：B→A 后 Ultra 不可用 = Ultra 实际没起来 ⇒ Incomplete + RECOVERY_REQUIRED。
        for (state in listOf(UltraState.NOT_INSTALLED, UltraState.FAULT, UltraState.UNKNOWN, UltraState.NO_KEY)) {
            val port = FakePort(state)
            val result = UltraEnvSwitchCoordinator(port).afterVerified(WorkMode.LITE)
            assertFalse("$state ⇒ Ultra 没有可用 Ultra 环境，不得 Verified: $result", result.ok)
            assertEquals(UltraSwitchCodes.ULTRA_ENV_STATE_UNKNOWN, result.code)
            assertTrue("$state 必须要求人工恢复", result.recoveryRequired)
            assertTrue(port.calls.isEmpty())
        }
    }

    @Test
    fun afterVerifiedUsesTheFreshProbeNotTheCachedState() {
        // 缓存 RUNNING 与新鲜 NOT_INSTALLED 冲突：A→B 必须信新鲜值 → Verified（用缓存会误报失败）。
        assertTrue(
            UltraEnvSwitchCoordinator(FakePort(UltraState.RUNNING), freshProbe = { UltraState.NOT_INSTALLED })
                .afterVerified(WorkMode.KSU).ok,
        )
        // 缓存 NOT_INSTALLED 与新鲜 RUNNING 冲突：B→A 必须信新鲜值 → Verified（用缓存会误判失败）。
        assertTrue(
            UltraEnvSwitchCoordinator(FakePort(UltraState.NOT_INSTALLED), freshProbe = { UltraState.RUNNING })
                .afterVerified(WorkMode.LITE).ok,
        )
        // 反向关键断言：缓存 RUNNING 会让 B→A 误判成功，新鲜 NOT_INSTALLED 必须判失败。
        val failed = UltraEnvSwitchCoordinator(
            FakePort(UltraState.RUNNING), freshProbe = { UltraState.NOT_INSTALLED },
        ).afterVerified(WorkMode.LITE)
        assertFalse("不得用缓存值判成功: $failed", failed.ok)
        assertTrue(failed.recoveryRequired)
    }

    @Test
    fun afterVerifiedFallsBackToPortStateWhenFreshProbeThrows() {
        // 探针抛错不得伪造成成功：退回端口读数，并按方向给出同样严格的结论。
        val throwing: () -> UltraState = { error("probe boom") }
        assertTrue(
            UltraEnvSwitchCoordinator(FakePort(UltraState.NOT_INSTALLED), throwing)
                .afterVerified(WorkMode.KSU).ok,
        )
        assertTrue(
            UltraEnvSwitchCoordinator(FakePort(UltraState.RUNNING), throwing)
                .afterVerified(WorkMode.LITE).ok,
        )
        assertFalse(
            UltraEnvSwitchCoordinator(FakePort(UltraState.FAULT), throwing)
                .afterVerified(WorkMode.LITE).ok,
        )
    }

    @Test
    fun afterVerifiedNeverTouchesSuspendOrRestore() {
        // 生产两个动作恒失败（ProductionUltraEnvironmentPort.kt:37-45）：判据不能依赖它们。
        for (toMode in listOf(WorkMode.LITE, WorkMode.KSU)) {
            for (state in UltraState.entries) {
                val port = FakePort(state).apply {
                    suspendResult = HookResult.failed("ULTRA_SUSPEND_REJECTED", "denied")
                    restoreResult = HookResult.failed("ULTRA_RESTORE_REJECTED", "denied")
                }
                UltraEnvSwitchCoordinator(port).afterVerified(toMode)
                assertTrue("$toMode/$state 不得调 suspend/restore: ${port.calls}", port.calls.isEmpty())
            }
        }
    }
}
