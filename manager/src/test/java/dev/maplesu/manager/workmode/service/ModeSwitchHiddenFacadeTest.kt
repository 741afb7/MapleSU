package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.UltraState
import dev.maplesu.manager.runtime.ultra.UltraStartupWarmup
import dev.maplesu.manager.workmode.ModeCapabilities
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.DetectionRules
import dev.maplesu.manager.workmode.detect.DetectionFacts
import dev.maplesu.manager.workmode.detect.LiteFacts
import dev.maplesu.manager.workmode.detect.LocalFacts
import dev.maplesu.manager.workmode.ui.SwitchBlockerCodes
import dev.maplesu.manager.workmode.ui.WorkModeEvent
import dev.maplesu.manager.workmode.ui.WorkModeSheetNotice
import dev.maplesu.manager.workmode.ui.WorkModeUiState
import dev.maplesu.manager.workmode.ui.availabilityFor
import dev.maplesu.manager.workmode.ui.reduce
import dev.maplesu.manager.workmode.ui.workModeSheetNotice
import dev.maplesu.manager.workmode.switch.BatteryFacts
import dev.maplesu.manager.workmode.switch.UltraEnvironmentPort
import dev.maplesu.manager.workmode.switch.UltraEnvSwitchCoordinator
import dev.maplesu.manager.workmode.switch.BeginInputs
import dev.maplesu.manager.workmode.switch.CandidateResult
import dev.maplesu.manager.workmode.switch.GatewayOutcome
import dev.maplesu.manager.workmode.switch.HiddenRecoveryFacts
import dev.maplesu.manager.workmode.switch.HiddenRecoveryFactsProvider
import dev.maplesu.manager.workmode.switch.HiddenState
import dev.maplesu.manager.workmode.switch.HiddenStateController
import dev.maplesu.manager.workmode.switch.HiddenStateStore
import dev.maplesu.manager.workmode.switch.HiddenSwitchCodes
import dev.maplesu.manager.workmode.switch.HiddenSwitchCoordinator
import dev.maplesu.manager.workmode.switch.HookResult
import dev.maplesu.manager.workmode.switch.ModeSwitchSnapshots
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import dev.maplesu.manager.workmode.switch.UltraSwitchCodes
import dev.maplesu.manager.workmode.switch.SuperuserReconciler
import dev.maplesu.manager.workmode.switch.SwitchCandidateProducer
import dev.maplesu.manager.workmode.switch.SwitchDeviceFacts
import dev.maplesu.manager.workmode.switch.SwitchQuiesceHooks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * H5 门面接线：uiSupport 暴露二次确认、startSwitch(confirmHidden)、
 * 切后目标隐藏默认 OFF / 切回恢复 / 恢复通道失效 fail-closed。
 */
class ModeSwitchHiddenFacadeTest {

    private class FakeController : HiddenStateController {
        val states = mutableMapOf(WorkMode.LITE to HiddenState.OFF, WorkMode.KSU to HiddenState.OFF)
        val calls = mutableListOf<String>()
        var failOn = false
        override fun state(mode: WorkMode): HiddenState = states.getValue(mode)
        override fun setOff(mode: WorkMode): HookResult {
            calls += "off:${mode.suffix}"; states[mode] = HiddenState.OFF; return HookResult.OK
        }
        override fun setOn(mode: WorkMode): HookResult {
            calls += "on:${mode.suffix}"
            if (failOn) return HookResult.failed("HIDDEN_SET_FAILED", "injected")
            states[mode] = HiddenState.ON; return HookResult.OK
        }
    }

    private class FakeStore : HiddenStateStore {
        val saved = mutableMapOf<WorkMode, HiddenState>()
        override fun saved(mode: WorkMode): HiddenState = saved[mode] ?: HiddenState.OFF
        override fun save(mode: WorkMode, state: HiddenState) { saved[mode] = state }
    }

    private class Harness {
        val temp: File = TestFixtures.tempDir("hidden-facade").toFile()
        val client = HappyClient()
        val controller = FakeController()
        val store = FakeStore()
        var recovery = HiddenRecoveryFacts(secretCodeConfigured = true, secretCodeVerified = true)
        val coordinator = HiddenSwitchCoordinator(controller, store, HiddenRecoveryFactsProvider { recovery })
        val environment = Env(temp)
        var ultra: UltraEnvSwitchCoordinator? = null
        var credentialFingerprint: String? = "b".repeat(64)
        val facade: ModeSwitchFacade by lazy {
            ModeSwitchFacade(
                client = client,
                environment = environment,
                producers = mapOf(WorkMode.LITE to Producer(), WorkMode.KSU to Producer()),
                workRoot = File(temp, "work"),
                snapshots = Snapshots(),
                hooks = ReadyHooks,
                superuser = SuperuserReconciler { HookResult.OK },
                hidden = coordinator,
                ultra = ultra,
                ultraCredentialFingerprint = { credentialFingerprint },
            )
        }
    }

    @Test
    fun uiSupportExposesHiddenConfirmationWhenSourceHiddenOn() = runBlocking {
        val h = Harness()
        h.controller.states[WorkMode.LITE] = HiddenState.ON
        val support = h.facade.uiSupport(WorkMode.KSU)
        assertTrue(support.supported)
        assertTrue(support.requiresHiddenConfirmation)
    }

    @Test
    fun startSwitchWithoutConfirmationIsBlockedAndDoesNotTouchService() = runBlocking {
        val h = Harness()
        h.controller.states[WorkMode.LITE] = HiddenState.ON
        val outcome = h.facade.startSwitch(WorkMode.KSU)
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.Blocked)
        assertEquals(HiddenSwitchCodes.HIDDEN_SWITCH_CONFIRMATION_REQUIRED,
            (outcome as SwitchOutcome.Blocked).code)
        assertFalse(h.client.calls.contains("begin"))
        assertEquals(HiddenState.ON, h.controller.state(WorkMode.LITE))
    }

    @Test
    fun startSwitchWithConfirmationUnhidesSourceThenSwitches() = runBlocking {
        val h = Harness()
        h.controller.states[WorkMode.LITE] = HiddenState.ON
        val outcome = h.facade.startSwitch(WorkMode.KSU, confirmHidden = true)
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.AwaitingReboot)
        assertEquals(HiddenState.OFF, h.controller.state(WorkMode.LITE))
        assertEquals(HiddenState.ON, h.store.saved[WorkMode.LITE])
        assertTrue(h.client.calls.contains("begin"))
    }

    @Test
    fun missingRecoveryChannelBlocksBeforeAnySwitchStep() = runBlocking {
        val h = Harness()
        h.controller.states[WorkMode.LITE] = HiddenState.ON
        h.recovery = HiddenRecoveryFacts()
        val outcome = h.facade.startSwitch(WorkMode.KSU, confirmHidden = true)
        assertTrue(outcome is SwitchOutcome.Blocked)
        assertEquals(HiddenSwitchCodes.HIDDEN_RECOVERY_NOT_CONFIGURED, (outcome as SwitchOutcome.Blocked).code)
        assertTrue(h.client.calls.isEmpty())
        assertEquals(HiddenState.ON, h.controller.state(WorkMode.LITE))
    }

    @Test
    fun verifiedSwitchResetsTargetHiddenToOffByDefault() = runBlocking {
        val h = Harness()
        h.client.currentStatus = HappyClient.rebootRequired()
        val outcome = h.facade.reopenAfterReboot()
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.Verified)
        assertEquals(HiddenState.OFF, h.controller.state(WorkMode.KSU))
        assertTrue(h.controller.calls.contains("off:B"))
    }

    @Test
    fun switchBackRestoresSavedHiddenWhenRecoveryStillUsable() = runBlocking {
        val h = Harness()
        h.store.save(WorkMode.LITE, HiddenState.ON)
        h.client.currentStatus = HappyClient.rebootRequired(toMode = "LITE", fromMode = "KSU")
        val outcome = h.facade.reopenAfterReboot()
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.Verified)
        assertEquals(HiddenState.ON, h.controller.state(WorkMode.LITE))
        assertTrue(h.controller.calls.contains("on:A"))
    }

    @Test
    fun switchBackFailsClosedWhenRecoveryChannelLostAfterSwitch() = runBlocking {
        val h = Harness()
        h.store.save(WorkMode.LITE, HiddenState.ON)
        h.client.currentStatus = HappyClient.rebootRequired(toMode = "LITE", fromMode = "KSU")
        h.recovery = HiddenRecoveryFacts() // 切换后通道失效
        val outcome = h.facade.reopenAfterReboot()
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.Incomplete)
        assertEquals(HiddenSwitchCodes.HIDDEN_RESTORE_CHANNEL_LOST,
            (outcome as SwitchOutcome.Incomplete).code)
        assertEquals(HiddenState.OFF, h.controller.state(WorkMode.LITE))
    }

    // ------------------------------------------------------------------ P0 Ultra 环境互操作

    private class FakeUltraPort(
        var state: UltraState = UltraState.RUNNING,
        var suspendResult: HookResult = HookResult.OK,
        var restoreResult: HookResult = HookResult.OK,
    ) : UltraEnvironmentPort {
        val calls = mutableListOf<String>()
        override fun state(): UltraState = state
        override fun suspend(): HookResult {
            calls += "suspend"; return suspendResult
        }
        override fun restore(): HookResult {
            calls += "restore"; return restoreResult
        }
    }

    @Test
    fun aToBProceedsWhileUltraEnvironmentRunning() = runBlocking {
        // P0-1 第 2 项：RUNNING 不再前置阻断（写回 stock boot 即停 Ultra 内核），也不再调 suspend()。
        val h = Harness()
        val port = FakeUltraPort(UltraState.RUNNING)
        h.ultra = UltraEnvSwitchCoordinator(port)
        val outcome = h.facade.startSwitch(WorkMode.KSU)
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.AwaitingReboot)
        assertTrue("必须走到 begin", h.client.calls.contains("begin"))
        assertTrue("必须走到 commit", h.client.calls.contains("commit"))
        assertFalse("不得再调 suspend(): ${port.calls}", port.calls.contains("suspend"))
    }

    @Test
    fun aToBProceedsWhenUltraEnvironmentNotInstalled() = runBlocking {
        val h = Harness()
        h.ultra = UltraEnvSwitchCoordinator(FakeUltraPort(UltraState.NOT_INSTALLED))
        val outcome = h.facade.startSwitch(WorkMode.KSU)
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.AwaitingReboot)
    }

    @Test
    fun bToAProceedsWhenUltraAssetsMissing() = runBlocking {
        // 缺 Ultra 资产不弹阻断：走安装/修补流程，切换本身照常执行。
        val h = Harness()
        h.environment.active = WorkMode.KSU
        val port = FakeUltraPort(UltraState.NOT_INSTALLED)
        h.ultra = UltraEnvSwitchCoordinator(port)
        val outcome = h.facade.startSwitch(WorkMode.LITE)
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.AwaitingReboot)
        assertTrue("必须走到 commit", h.client.calls.contains("commit"))
        assertTrue("beforeSwitch 不得碰 Ultra 端口: ${port.calls}", port.calls.isEmpty())
    }

    @Test
    fun firstNormalInstallWithoutUltraKeyDoesNotBecomeUnknownEnvironment() = runBlocking {
        val h = Harness()
        h.environment.active = WorkMode.KSU
        h.environment.normalKernelOnly = true
        h.environment.kernelRelease = "6.6.118-android15-8-gb9cc6ec16bc8-abogki536571621-4k"
        h.ultra = UltraEnvSwitchCoordinator(FakeUltraPort(UltraState.NO_KEY))

        val availability = h.facade.availabilityFor(WorkMode.LITE, masked = false)
        val ui = WorkModeUiState()
            .reduce(WorkModeEvent.Detected(ModeState.MODE_B_KERNEL_ONLY, ModeCapabilities.forMode(WorkMode.KSU),
                WorkMode.KSU, masked = false, switchSupported = availability.canSwitch))
            .reduce(WorkModeEvent.Availability(availability))
        val notice = workModeSheetNotice(WorkMode.LITE, ui)

        assertFalse("Normal first-install must not show the unknown Ultra environment blocker: $notice",
            notice is WorkModeSheetNotice.Failed && notice.code == SwitchBlockerCodes.ULTRA_ENV_STATE_UNKNOWN)
        assertTrue("Normal root must be allowed to prepare the first Ultra credential: $availability",
            availability.canSwitch)
        assertFalse("Availability uses current local facts before the first root transaction", h.client.calls.contains("preflight"))
        assertFalse("Opening the sheet must not begin a transaction", h.client.calls.contains("begin"))
        assertEquals(ModeState.MODE_B_KERNEL_ONLY, ui.detection)
    }

    @Test
    fun bToAWithoutUltraKeyStillRunsCandidateAndCommit() = runBlocking {
        val h = Harness()
        h.environment.active = WorkMode.KSU
        h.ultra = UltraEnvSwitchCoordinator(FakeUltraPort(UltraState.NO_KEY))
        val outcome = h.facade.startSwitch(WorkMode.LITE)
        assertTrue("first Ultra setup must reach its real producer: $outcome", outcome is SwitchOutcome.AwaitingReboot)
        assertTrue(h.client.calls.contains("submitCandidate"))
        assertTrue(h.client.calls.contains("commit"))
    }

    @Test
    fun bToAWithoutRetainedCredentialDoesNotSubmitOrCommit() = runBlocking {
        val h = Harness()
        h.environment.active = WorkMode.KSU
        h.credentialFingerprint = null
        h.ultra = UltraEnvSwitchCoordinator(FakeUltraPort(UltraState.NO_KEY))
        val outcome = h.facade.startSwitch(WorkMode.LITE)
        assertTrue("missing candidate identity must stop before partition commit: $outcome", outcome is SwitchOutcome.Failed)
        assertEquals("ULTRA_KERNEL_INSTALL_IDENTITY_MISSING", (outcome as SwitchOutcome.Failed).code)
        assertFalse(h.client.calls.contains("submitCandidate"))
        assertFalse(h.client.calls.contains("commit"))
        assertEquals(null, ModeSwitchProgress.postBootUltraRecovery(File(h.temp, "work"), "boot-2", "b".repeat(64)))
    }

    @Test
    fun committedFirstUltraSwitchCanSetupThenPairAndRetryRealDetectionAfterBoot() = runBlocking {
        val h = Harness()
        h.environment.active = WorkMode.KSU
        h.credentialFingerprint = "b".repeat(64)
        h.ultra = UltraEnvSwitchCoordinator(FakeUltraPort(UltraState.NO_KEY))
        val outcome = h.facade.startSwitch(WorkMode.LITE)
        assertTrue(outcome is SwitchOutcome.AwaitingReboot)
        val switchId = (outcome as SwitchOutcome.AwaitingReboot).switchId
        val work = File(h.temp, "work")
        assertEquals("Only the post-commit receipt may prepare first Ultra boot", switchId,
            ModeSwitchProgress.postBootUltraRecovery(work, "boot-2", "b".repeat(64)))
        assertEquals(null, ModeSwitchProgress.postBootUltraRecovery(work, "boot-1", "b".repeat(64)))
        assertEquals(null, ModeSwitchProgress.postBootUltraRecovery(work, "boot-2", "c".repeat(64)))

        val calls = mutableListOf<String>()
        var ultraState = UltraState.NOT_INSTALLED
        fun detect() = DetectionRules.decide(DetectionFacts(
            local = LocalFacts("boot-2", managerFirstInstallAgeMs = Long.MAX_VALUE),
            lite = LiteFacts(ultraState = ultraState, ultraKeyPresent = true)))
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, detect().state)
        var detected = detect().state
        val result = UltraStartupWarmup.run(UltraStartupWarmup.Input(
            activeMode = null, detectionSettled = true, ksuResponded = false,
            pendingInstall = false, suspended = false, quiescing = false,
            switchPending = true, conflictingRoot = false,
            postBootUltraSwitchRecovery = ModeSwitchProgress.postBootUltraRecovery(work, "boot-2", "b".repeat(64)) == switchId,
        ), setupEnvironment = { calls += "setup"; ultraState = UltraState.RUNNING },
            pairEnvironment = { calls += "pair"; ultraState == UltraState.RUNNING },
            retryDetection = { calls += "detect"; detected = detect().state })
        assertTrue(result.ran)
        assertEquals(listOf("setup", "pair", "detect"), calls)
        assertEquals(ModeState.MODE_A_ACTIVE, detected)
    }

    @Test
    fun bToAWithRunningUltraVerifiesWithoutRestore() = runBlocking {
        val h = Harness()
        h.environment.active = WorkMode.KSU
        val port = FakeUltraPort(UltraState.RUNNING)
        h.ultra = UltraEnvSwitchCoordinator(port)
        h.client.currentStatus = HappyClient.rebootRequired(toMode = "LITE", fromMode = "KSU")
        val outcome = h.facade.reopenAfterReboot()
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.Verified)
        assertFalse(port.calls.contains("restore"))
    }

    @Test
    fun bToAMissingUltraAfterVerifyIsIncompleteAndNeedsRecovery() = runBlocking {
        // S-2：Normal→Ultra 之后 Ultra 不可用 ⇒ Ultra 实际没起来 ⇒ 不得静默 Verified。
        val h = Harness()
        h.environment.active = WorkMode.KSU
        val port = FakeUltraPort(UltraState.FAULT)
        h.ultra = UltraEnvSwitchCoordinator(port)
        h.client.currentStatus = HappyClient.rebootRequired(toMode = "LITE", fromMode = "KSU")
        val outcome = h.facade.reopenAfterReboot()
        assertTrue("不得把缺 Ultra 环境的 Ultra 判成成功: $outcome", outcome is SwitchOutcome.Incomplete)
        val incomplete = outcome as SwitchOutcome.Incomplete
        assertEquals(UltraSwitchCodes.ULTRA_ENV_STATE_UNKNOWN, incomplete.code)
        assertTrue(
            "必须显式标明需要人工恢复: ${incomplete.detail}",
            incomplete.detail.startsWith("RECOVERY_REQUIRED:"),
        )
        assertFalse("不得再调 restore()", port.calls.contains("restore"))
    }

    @Test
    fun aToBWithUltraStillRunningAfterVerifyIsIncompleteButNotRecovery() = runBlocking {
        // S-2：Ultra→Normal 后 Ultra 仍在跑 ⇒ 写回 stock boot 没生效 ⇒ 不得 Verified；
        // 但分区已写好、数据没坏 ⇒ 不得 RECOVERY_REQUIRED。
        val h = Harness()
        val port = FakeUltraPort(UltraState.RUNNING)
        h.ultra = UltraEnvSwitchCoordinator(port)
        h.client.currentStatus = HappyClient.rebootRequired(toMode = "KSU", fromMode = "LITE")
        val outcome = h.facade.reopenAfterReboot()
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.Incomplete)
        val incomplete = outcome as SwitchOutcome.Incomplete
        assertEquals(UltraSwitchCodes.ULTRA_ENV_MUST_SUSPEND, incomplete.code)
        assertFalse(
            "数据没坏，不得要求人工恢复: ${incomplete.detail}",
            incomplete.detail.startsWith("RECOVERY_REQUIRED:"),
        )
    }

    @Test
    fun aToBWithUltraStoppedAfterVerifyIsVerified() = runBlocking {
        // S-2：A→B 的**预期**结果（Ultra 已不在）必须是 Verified。
        val h = Harness()
        val port = FakeUltraPort(UltraState.NOT_INSTALLED)
        h.ultra = UltraEnvSwitchCoordinator(port)
        h.client.currentStatus = HappyClient.rebootRequired(toMode = "KSU", fromMode = "LITE")
        val outcome = h.facade.reopenAfterReboot()
        assertTrue("unexpected: $outcome", outcome is SwitchOutcome.Verified)
        assertTrue("不得碰 suspend/restore: ${port.calls}", port.calls.isEmpty())
    }

    // ------------------------------------------------------------------ fakes

    private class Env(private val temp: File) : ModeSwitchAppEnvironment {
        private val dir = File(temp, "mirror").apply { mkdirs() }
        var active: WorkMode = WorkMode.LITE
        var normalKernelOnly = false
        var kernelRelease = "5.10.198-android13-8-g"
        override fun activeMode(): WorkMode? = active
        override fun modeStateName(): String? =
            if (active == WorkMode.LITE) "MODE_A_ACTIVE" else if (normalKernelOnly) "MODE_B_KERNEL_ONLY" else "MODE_B_ACTIVE"
        override fun bootId(): String = "boot-1"
        override fun deviceFacts(): SwitchDeviceFacts =
            SwitchDeviceFacts("_a", "fw", kernelRelease, "Enforcing")
        override fun battery(): BatteryFacts = BatteryFacts(80, false)
        override fun freeBytes(): Long = 1L shl 40
        override fun beginInputs(): BeginInputs {
            val su = File(dir, "su.json").apply { writeText("{}") }
            val settings = File(dir, "settings.json").apply { writeText("{}") }
            return BeginInputs(su, settings)
        }
    }

    private class Producer : SwitchCandidateProducer {
        override fun produce(input: dev.maplesu.manager.workmode.switch.CandidateInput): CandidateResult {
            val image = File(input.workDir, "candidate.bin")
            image.writeBytes(ByteArray(4096) { 'C'.code.toByte() })
            return CandidateResult(image, Hashes.sha256(image.readBytes()), image.length())
        }
    }

    private class Snapshots : ModeSwitchSnapshots {
        override suspend fun preSwitchSnapshot(request: ModeSwitchRequest) = GatewayOutcome(true, "")
        override suspend fun restoreAfterRollback(request: ModeSwitchRequest) = GatewayOutcome(true, "")
    }

    private object ReadyHooks : SwitchQuiesceHooks {
        override fun quiesce(fromMode: WorkMode) = HookResult.OK
        override fun restoreAfterFailure(fromMode: WorkMode) = HookResult.OK
        override fun withdrawLiteImplants() = HookResult.OK
        override fun implantRestoreReady() = true
        override fun restoreProducerSideEffects() = HookResult.OK
    }

    private class HappyClient : ModeSwitchClient {
        val calls = mutableListOf<String>()
        var currentStatus: ClientStatus? = null

        override suspend fun preflight(switchId: String, planFields: Map<String, String>): ClientResult {
            calls += "preflight"; return ClientResult(true)
        }
        override suspend fun begin(switchId: String, planFields: Map<String, String>,
                                   superuserJson: File, settingsMirrorJson: File): ClientResult {
            calls += "begin"
            currentStatus = snapshot(switchId, planFields["toMode"] ?: "KSU")
            return ClientResult(true, status = currentStatus)
        }
        override suspend fun snapshot(switchId: String): ClientResult {
            calls += "snapshot"; return ClientResult(true, status = currentStatus)
        }
        override suspend fun exportImage(switchId: String, kind: String, partition: String,
                                         destination: File): Boolean {
            destination.parentFile?.mkdirs()
            destination.writeBytes(ByteArray(4096) { 'I'.code.toByte() })
            return true
        }
        override suspend fun submitCandidate(switchId: String, partition: String,
                                             candidate: File, candidateSha256: String): ClientResult {
            calls += "submitCandidate"; return ClientResult(true)
        }
        override suspend fun commit(switchId: String): ClientResult {
            calls += "commit"
            currentStatus = rebootRequired()
            return ClientResult(true, status = currentStatus)
        }
        override suspend fun rollback(switchId: String) = ClientResult(true)
        override suspend fun abort(switchId: String) = ClientResult(true)
        override fun status(switchId: String): ClientStatus? = currentStatus
        override suspend fun verify(switchId: String) = ClientResult(true, status = currentStatus)
        override suspend fun markRebootRequested(switchId: String): Boolean {
            calls += "markReboot"; return true
        }
        override suspend fun exportLog(switchId: String, destination: File): File? = destination

        companion object {
            fun snapshot(switchId: String, toMode: String) = ClientStatus(
                switchId, "SNAPSHOT", "S2", "snapshot", "LITE", toMode, "", "", "", false, false,
                false, false, 0, partitions(),
            )

            fun rebootRequired(toMode: String = "KSU", fromMode: String = "LITE") = ClientStatus(
                UUID.randomUUID().toString(), "REBOOT_REQUIRED", "S4", "4g", fromMode, toMode,
                "", "", "a".repeat(64), true, false, true, false, 0, partitions(),
            )

            private fun partitions() = listOf(
                ClientPartition("boot", "RESTORE_BASELINE", 4096,
                    "p".repeat(64), "t".repeat(64), "", "PENDING"),
                ClientPartition("init_boot", "WRITE_CANDIDATE", 4096,
                    "q".repeat(64), "u".repeat(64), "", "PENDING"),
            )
        }
    }
}
