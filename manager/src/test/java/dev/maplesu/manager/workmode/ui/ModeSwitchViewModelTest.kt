package dev.maplesu.manager.workmode.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import dev.maplesu.manager.installer.BaselineProvisionOutcome
import dev.maplesu.manager.installer.BaselineProvisioner
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ClientResult
import dev.maplesu.manager.workmode.service.ClientStatus
import dev.maplesu.manager.workmode.service.ModeSwitchAppEnvironment
import dev.maplesu.manager.workmode.service.ModeSwitchClient
import dev.maplesu.manager.workmode.service.ModeSwitchFacade
import dev.maplesu.manager.workmode.service.ModeSwitchRootProtocol
import dev.maplesu.manager.workmode.service.ModeSwitchSigningContext
import dev.maplesu.manager.workmode.service.SwitchOutcome
import dev.maplesu.manager.workmode.switch.BatteryFacts
import dev.maplesu.manager.workmode.switch.BeginInputs
import dev.maplesu.manager.workmode.switch.HookResult
import dev.maplesu.manager.workmode.switch.GatewayOutcome
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import dev.maplesu.manager.workmode.switch.ModeSwitchSnapshots
import dev.maplesu.manager.workmode.switch.HiddenRecoveryFacts
import dev.maplesu.manager.workmode.switch.HiddenRecoveryFactsProvider
import dev.maplesu.manager.workmode.switch.HiddenState
import dev.maplesu.manager.workmode.switch.HiddenStateController
import dev.maplesu.manager.workmode.switch.HiddenStateStore
import dev.maplesu.manager.workmode.switch.HiddenSwitchCoordinator
import dev.maplesu.manager.workmode.switch.SwitchDeviceFacts
import dev.maplesu.manager.workmode.switch.SwitchQuiesceHooks
import dev.maplesu.manager.workmode.switch.NoOpSwitchQuiesceHooks
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModeSwitchViewModelTest {
    private val store = ViewModelStore()
    private val directories = mutableListOf<File>()

    @Before fun setUp() {
        ModeRuntime.resetForTest()
        WorkModeSwitchSession.finish(SwitchOutcome.Blocked("", "", emptyList()))
    }

    @After fun tearDown() {
        store.clear()
        ModeRuntime.resetForTest()
        WorkModeSwitchSession.finish(SwitchOutcome.Blocked("", "", emptyList()))
        directories.forEach { it.deleteRecursively() }
    }

    private fun keep(model: ModeSwitchViewModel): ModeSwitchViewModel = model.also {
        // Transfer lifecycle's own closeable scope before the first launch; store.clear cancels it.
        val scopeKey = "androidx.lifecycle.viewmodel.internal.ViewModelCoroutineScope.JOB_KEY"
        val owner = object : ViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {}
        it.addCloseable(scopeKey, requireNotNull(owner.getCloseable<AutoCloseable>(scopeKey)))
        store.put(UUID.randomUUID().toString(), it)
    }

    private suspend fun finished(model: ModeSwitchViewModel) = withTimeout(5_000) {
        while (model.uiState.value.actionRunning || model.uiState.value.phase in
            setOf(ModeSwitchPhase.RUNNING, ModeSwitchPhase.ATTACHING)) delay(5)
    }

    @Test fun selectionSynchronouslyProducesTheRequestedRouteWithoutStartingTheService() {
        val model = WorkModeViewModel()
        model.onConfirmSwitch(WorkMode.KSU)
        val accepted = model.uiState.value
        assertEquals(WorkModePhase.RUNNING, accepted.phase)
        assertEquals(WorkMode.KSU, accepted.target)
        assertEquals(accepted.runningSwitchId, UUID.fromString(accepted.progressToOpen).toString())
        assertFalse(WorkModeSwitchSession.executing)
        model.onConfirmSwitch(WorkMode.LITE)
        assertEquals(accepted, model.uiState.value)
    }

    @Test fun missingServiceFailsOnTheOpenPageAndCanBeRetriedWithoutLockingBack() = runBlocking {
        var providerCalls = 0
        val model = keep(ModeSwitchViewModel { providerCalls++; null })
        val id = UUID.randomUUID().toString()
        model.attach(id, WorkMode.KSU)
        finished(model)
        assertEquals("SWITCH_SERVICE_NOT_READY", model.uiState.value.code)
        assertFalse(model.uiState.value.blocksBack)
        assertTrue(model.uiState.value.canRetry)
        assertFalse(model.uiState.value.canAbort)
        assertFalse(WorkModeSwitchSession.pending)
        model.attach(id, WorkMode.KSU)
        assertEquals(1, providerCalls)
        model.retry()
        finished(model)
        assertEquals(2, providerCalls)
    }

    @Test fun backendRejectionIsDisplayedWithoutAServiceRecord() = runBlocking {
        val harness = harness().also { it.environment.active = null }
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(UUID.randomUUID().toString(), WorkMode.KSU)
        finished(model)
        assertEquals(ModeSwitchPhase.FAILED, model.uiState.value.phase)
        assertTrue(model.uiState.value.code.isNotBlank())
        assertFalse(model.uiState.value.blocksBack)
        assertTrue(model.uiState.value.canRetry)
        assertEquals(0, harness.client.statusCalls)
        assertEquals(0, harness.client.mutations)
    }

    @Test fun signingDefaultsAreRequestedByExecutionOnTheOpenProgressPage() = runBlocking {
        val harness = harness()
        harness.environment.inputs = BeginInputs(
            File(harness.directory, "superuser.json").apply { writeText("{}") },
            File(harness.directory, "settings.json").apply { writeText("{}") },
        )
        harness.client.beginResult = ClientResult(false, "BEGIN_STOPPED")
        var calls = 0
        harness.defaultSigning = { request ->
            calls++
            assertEquals("init_boot", request.targetPartition)
            assertEquals(1, harness.leaseCalls)
            assertEquals(0, harness.client.beginCalls)
            null
        }
        val model = keep(ModeSwitchViewModel { harness.facade })
        assertEquals(0, calls)
        model.attach(UUID.randomUUID().toString(), WorkMode.KSU)
        finished(model)
        assertEquals(1, calls)
        assertEquals("BEGIN_STOPPED", model.uiState.value.code)
    }

    @Test fun sourceRecoveryFailureRequestsImagesOnTheProgressPageAndCancellationUnlocksIt() = runBlocking {
        val harness = harness()
        val id = UUID.randomUUID().toString()
        harness.client.preflightResult = ClientResult(true)
        harness.client.beginResult = ClientResult(true)
        harness.client.snapshotResult = ClientResult(false, "SOURCE_RECOVERY_UNAVAILABLE")
        harness.client.currentStatus = status(id, "BEGIN")
        harness.client.abortResult = ClientResult(true, status = status(id, "CANCELLED"))
        harness.environment.inputs = BeginInputs(
            File(harness.directory, "superuser.json").apply { writeText("{}") },
            File(harness.directory, "settings.json").apply { writeText("{}") },
        )
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(id, WorkMode.KSU)
        finished(model)
        assertEquals(model.uiState.value.toString(), SwitchOutcome.ImagesRequired(WorkMode.KSU, false), model.imageRequest.value)
        assertFalse(model.uiState.value.blocksBack)
        assertFalse(WorkModeSwitchSession.pending)
        model.continueWithImages(null, null)
        assertEquals(0, harness.environment.provisions)
        assertNotNull(model.imageRequest.value)
        model.cancelImageSelection()
        assertNull(model.imageRequest.value)
        assertNull(model.imageError.value)
        assertEquals("CANCELLED", model.uiState.value.stateWire)
        assertTrue(model.uiState.value.canRetry)
        assertFalse(model.uiState.value.canAbort)
        assertFalse(model.uiState.value.blocksBack)
        assertEquals(0, harness.client.mutations)
        assertEquals(1, harness.client.beginCalls)
        assertEquals(1, harness.client.abortCalls)
        assertTrue(harness.client.statusCalls > 0)
    }

    @Test fun generatedTaskIdWithoutAServiceRecordRetriesTheStartOperation() = runBlocking {
        val harness = harness()
        harness.environment.provision = { BaselineProvisionOutcome.Ready("b1", true, "OEM_PACKAGE") }
        harness.client.preflightResult = ClientResult(true)
        harness.leaseResult = HookResult.failed("SWITCH_LEASE_FAILED", "test lease")
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(UUID.randomUUID().toString(), WorkMode.KSU)
        finished(model)

        assertEquals("SWITCH_LEASE_FAILED", model.uiState.value.code)
        assertFalse(model.uiState.value.hasServiceRecord)
        assertFalse(model.uiState.value.blocksBack)
        assertFalse(model.uiState.value.canAbort)
        assertFalse(WorkModeSwitchSession.pending)
        assertTrue(model.uiState.value.canRetry)
        assertEquals(1, harness.leaseCalls)
        model.retry()
        finished(model)
        assertEquals(2, harness.leaseCalls)
        assertEquals(0, harness.client.preflightCalls)
        assertEquals(0, harness.client.mutations)
    }

    @Test fun confirmedSwitchUsesTheHiddenCoordinatorWithoutRequestingAnotherConfirmation() = runBlocking {
        val harness = harness()
        var hidden = HiddenState.ON
        var saved = HiddenState.OFF
        var unhideCalls = 0
        harness.hidden = HiddenSwitchCoordinator(
            object : HiddenStateController {
                override fun state(mode: WorkMode) = if (mode == WorkMode.LITE) hidden else HiddenState.OFF
                override fun setOff(mode: WorkMode): HookResult {
                    assertEquals(WorkMode.LITE, mode)
                    unhideCalls++
                    hidden = HiddenState.OFF
                    return HookResult.OK
                }
                override fun setOn(mode: WorkMode): HookResult = error("unexpected rehide")
            },
            object : HiddenStateStore {
                override fun saved(mode: WorkMode) = if (mode == WorkMode.LITE) saved else HiddenState.OFF
                override fun save(mode: WorkMode, state: HiddenState) {
                    assertEquals(WorkMode.LITE, mode)
                    saved = state
                }
            },
            HiddenRecoveryFactsProvider {
                HiddenRecoveryFacts(secretCodeConfigured = true, secretCodeVerified = true)
            },
        )
        harness.client.preflightResult = ClientResult(true)
        harness.leaseResult = HookResult.failed("TEST_LEASE_STOP")
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(UUID.randomUUID().toString(), WorkMode.KSU)
        finished(model)

        assertEquals("TEST_LEASE_STOP", model.uiState.value.code)
        assertEquals(1, harness.leaseCalls)
        assertEquals(1, unhideCalls)
        assertEquals(HiddenState.OFF, hidden)
        assertEquals(HiddenState.ON, saved)
        assertFalse(model.uiState.value.hasServiceRecord)
        assertFalse(model.uiState.value.blocksBack)
        assertEquals(0, harness.client.mutations)
    }

    @Test fun explicitRecoveryBeforeAServiceRecordDoesNotBecomeAnAutomaticRestart() = runBlocking {
        val harness = harness()
        harness.environment.provision = { BaselineProvisionOutcome.Ready("b1", true, "OEM_PACKAGE") }
        harness.client.preflightResult = ClientResult(true)
        harness.leaseResult = HookResult.failed("SWITCH_LEASE_FAILED", "RECOVERY_REQUIRED:test lease")
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(UUID.randomUUID().toString(), WorkMode.KSU)
        finished(model)

        assertEquals(ModeSwitchPhase.NEEDS_RECOVERY, model.uiState.value.phase)
        assertTrue(model.uiState.value.requiresExternalRecovery)
        assertFalse(model.uiState.value.canRetry)
        assertFalse(model.uiState.value.canAbort)
        assertFalse(model.uiState.value.blocksBack)
        model.retry()
        assertEquals(1, harness.leaseCalls)
        assertEquals(0, harness.client.mutations)
    }

    @Test fun missingStatusAfterBeginDoesNotRestartTheWriteOperation() = runBlocking {
        val harness = harness()
        harness.environment.provision = { BaselineProvisionOutcome.Ready("b1", true, "OEM_PACKAGE") }
        harness.client.preflightResult = ClientResult(true)
        harness.client.beginResult = ClientResult(true)
        harness.client.snapshotResult = ClientResult(false, "TEST_SNAPSHOT_FAILED")
        harness.environment.inputs = BeginInputs(
            File(harness.directory, "superuser.json").apply { writeText("{}") },
            File(harness.directory, "settings.json").apply { writeText("{}") },
        )
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(UUID.randomUUID().toString(), WorkMode.KSU)
        finished(model)

        assertEquals("TEST_SNAPSHOT_FAILED", model.uiState.value.code)
        assertTrue(model.uiState.value.hasServiceRecord)
        assertTrue(WorkModeSwitchSession.pending)
        assertTrue(model.uiState.value.canRetry)
        assertEquals(1, harness.client.beginCalls)
        model.retry()
        finished(model)
        assertEquals(1, harness.client.beginCalls)
        assertEquals(0, harness.client.preflightCalls)
        assertTrue(WorkModeSwitchSession.pending)
    }

    @Test fun uncertainBeginKeepsTheTaskIdAndRetriesVerificationInsteadOfRestarting() = runBlocking {
        val harness = harness()
        harness.client.preflightResult = ClientResult(true)
        harness.client.beginResult = ClientResult(false, ModeSwitchRootProtocol.UNCERTAIN)
        harness.environment.inputs = BeginInputs(
            File(harness.directory, "superuser.json").apply { writeText("{}") },
            File(harness.directory, "settings.json").apply { writeText("{}") },
        )
        val id = UUID.randomUUID().toString()
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(id, WorkMode.KSU)
        finished(model)

        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, model.uiState.value.code)
        assertEquals(id, WorkModeSwitchSession.switchId)
        assertTrue(WorkModeSwitchSession.pending)
        assertFalse(model.uiState.value.hasServiceRecord)
        assertFalse(model.uiState.value.canAbort)
        assertTrue(model.uiState.value.canRetry)
        repeat(2) {
            val reads = harness.client.statusCalls
            model.retry()
            finished(model)
            assertTrue(harness.client.statusCalls > reads)
            assertEquals(1, harness.client.beginCalls)
            assertEquals(0, harness.client.preflightCalls)
            assertEquals(id, WorkModeSwitchSession.switchId)
            assertTrue(WorkModeSwitchSession.pending)
            assertFalse(model.uiState.value.hasServiceRecord)
            assertFalse(model.uiState.value.canAbort)
        }
    }

    @Test fun configurationReentryDoesNotSubmitAgainOrTreatIntermediateStatusAsCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val harness = harness()
        harness.client.preflightResult = ClientResult(true)
        harness.acquireLease = {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            HookResult.failed("TEST_LEASE_STOP")
        }
        val id = UUID.randomUUID().toString()
        val model = keep(ModeSwitchViewModel { harness.facade })
        try {
            model.attach(id, WorkMode.KSU)
            withTimeout(5_000) { entered.await() }
            model.attach(id, WorkMode.KSU)
            harness.client.currentStatus = status(id, "REBOOT_REQUIRED")
            model.refresh()
            assertEquals(ModeSwitchPhase.RUNNING, model.uiState.value.phase)
            assertTrue(model.uiState.value.blocksBack)
            assertEquals(id, WorkModeSwitchSession.switchId)
            assertTrue(WorkModeSwitchSession.executing)
            assertEquals(1, harness.leaseCalls)
            assertEquals(0, harness.environment.provisions)
            assertEquals(0, harness.client.statusCalls)
        } finally {
            release.countDown()
        }
        finished(model)
        assertEquals(ModeSwitchPhase.FAILED, model.uiState.value.phase)
        assertEquals("TEST_LEASE_STOP", model.uiState.value.code)
        assertNull(model.imageRequest.value)
    }

    @Test fun restoredRouteObservesTheExistingTaskWithoutResubmitting() = runBlocking {
        val id = UUID.randomUUID().toString()
        val harness = harness()
        harness.client.currentStatus = status(id, "VERIFIED")
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(id)
        finished(model)
        assertEquals(ModeSwitchPhase.SUCCEEDED, model.uiState.value.phase)
        assertFalse(model.uiState.value.blocksBack)
        assertTrue(model.uiState.value.hasServiceRecord)
        assertEquals(0, harness.environment.provisions)
        assertEquals(0, harness.client.mutations)
        assertTrue(harness.client.statusCalls > 0)
    }

    @Test fun restoredRouteWithoutARecordDoesNotRestartOrLockNavigation() = runBlocking {
        val harness = harness()
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(UUID.randomUUID().toString())
        finished(model)
        assertEquals(ModeSwitchPhase.MISSING, model.uiState.value.phase)
        assertFalse(model.uiState.value.blocksBack)
        assertEquals(0, harness.environment.provisions)
        assertEquals(0, harness.client.mutations)
    }

    @Test fun anotherRequestsExecutionDoesNotOverrideACompletedRecord() = runBlocking {
        val id = UUID.randomUUID().toString()
        val harness = harness()
        harness.client.currentStatus = status(id, "VERIFIED")
        WorkModeSwitchSession.begin("another-request")
        val model = keep(ModeSwitchViewModel { harness.facade })
        model.attach(id)
        finished(model)
        assertEquals(ModeSwitchPhase.SUCCEEDED, model.uiState.value.phase)
        assertEquals("another-request", WorkModeSwitchSession.switchId)
        assertTrue(WorkModeSwitchSession.executing)
        assertEquals(0, harness.client.mutations)
    }

    private fun status(id: String, state: String) = ClientStatus(id, state, "S4", "4g", "LITE", "KSU",
        "", "", "", state == "REBOOT_REQUIRED", false, false, false, 0, emptyList())

    private fun harness(): Harness {
        val directory = Files.createTempDirectory("mode-entry-").toFile().also(directories::add)
        return Harness(directory)
    }

    private class Harness(val directory: File) {
        val client = Client()
        val environment = Environment()
        var leaseCalls = 0
        var leaseResult = HookResult.OK
        var acquireLease: () -> HookResult = { leaseResult }
        var hidden: HiddenSwitchCoordinator? = null
        var defaultSigning: ((ModeSwitchRequest) -> ModeSwitchSigningContext?)? = null
        val facade by lazy { ModeSwitchFacade(client, environment, emptyMap(), directory,
            snapshots = object : ModeSwitchSnapshots {
                override suspend fun preSwitchSnapshot(request: ModeSwitchRequest) = GatewayOutcome(true, "SNAPSHOT")
                override suspend fun restoreAfterRollback(request: ModeSwitchRequest) = GatewayOutcome(true, "RESTORED")
            },
            hooks = object : SwitchQuiesceHooks by NoOpSwitchQuiesceHooks {
                override fun implantRestoreReady() = true
            }, acquireLease = { leaseCalls++; acquireLease() }, hidden = hidden,
            defaultSigning = { defaultSigning?.invoke(it) }) }
    }

    private class Environment : ModeSwitchAppEnvironment {
        var active: WorkMode? = WorkMode.LITE
        var provisions = 0
        var inputs = BeginInputs(null, null)
        var provision: () -> BaselineProvisionOutcome = { BaselineProvisionOutcome.Failed("BASELINE_MISSING", "") }
        override fun activeMode() = active
        override fun modeStateName() = "MODE_A_ACTIVE"
        override fun bootId() = "boot-1"
        override fun deviceFacts() = SwitchDeviceFacts("_a", "fw", "6.6.89-android15-8", "Enforcing")
        override fun battery() = BatteryFacts(80, false)
        override fun freeBytes() = 1L shl 40
        override fun beginInputs() = inputs
        override fun provisioner() = BaselineProvisioner { provisions++; provision() }
    }

    private class Client : ModeSwitchClient {
        var currentStatus: ClientStatus? = null
        var statusCalls = 0
        var mutations = 0
        var preflightCalls = 0
        var preflightResult: ClientResult? = null
        var beginCalls = 0
        var beginResult: ClientResult? = null
        var snapshotResult: ClientResult? = null
        var abortCalls = 0
        var abortResult: ClientResult? = null
        private fun mutation(): ClientResult { mutations++; error("unexpected service mutation") }
        override fun status(switchId: String): ClientStatus? { statusCalls++; return currentStatus }
        override suspend fun preflight(switchId: String, planFields: Map<String, String>): ClientResult {
            preflightCalls++
            return preflightResult ?: mutation()
        }
        override suspend fun begin(switchId: String, planFields: Map<String, String>, superuserJson: File, settingsMirrorJson: File): ClientResult {
            beginCalls++
            return beginResult ?: mutation()
        }
        override suspend fun snapshot(switchId: String) = snapshotResult ?: mutation()
        override suspend fun submitCandidate(switchId: String, partition: String, candidate: File, candidateSha256: String) = mutation()
        override suspend fun commit(switchId: String) = mutation()
        override suspend fun rollback(switchId: String) = mutation()
        override suspend fun abort(switchId: String): ClientResult {
            abortCalls++
            return abortResult?.also { currentStatus = null } ?: mutation()
        }
        override suspend fun verify(switchId: String) = mutation()
        override suspend fun exportImage(switchId: String, kind: String, partition: String, destination: File): Boolean = error("unexpected export")
        override suspend fun markRebootRequested(switchId: String): Boolean = error("unexpected reboot marker")
        override suspend fun exportLog(switchId: String, destination: File): File? = error("unexpected log export")
    }
}
