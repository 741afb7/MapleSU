package dev.maplesu.manager.workmode.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.maplesu.manager.workmode.control.schema.SwitchHeadView
import dev.maplesu.manager.workmode.recovery.RecoveryControl
import dev.maplesu.manager.workmode.recovery.RecoveryInput
import dev.maplesu.manager.workmode.service.ClientStatus
import dev.maplesu.manager.workmode.service.ModeSwitchRootProtocol
import dev.maplesu.manager.workmode.service.SwitchOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
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

class RecoveryViewModelTest {
    private val store = ViewModelStore()
    private var models = 0

    @Before fun setUp() { WorkModeSwitchSession.finish(SwitchOutcome.Blocked("", "", emptyList())) }
    @After fun tearDown() { store.clear(); WorkModeSwitchSession.finish(SwitchOutcome.Blocked("", "", emptyList())) }

    private fun model(): RecoveryViewModel = RecoveryViewModel().also {
        val key = "androidx.lifecycle.viewmodel.internal.ViewModelCoroutineScope.JOB_KEY"
        val owner = object : ViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {}
        it.addCloseable(key, requireNotNull(owner.getCloseable<AutoCloseable>(key)))
        store.put("recovery-${models++}", it)
    }

    private suspend fun idle(model: RecoveryViewModel) = withTimeout(3_000) {
        while (model.uiState.value.loading || model.uiState.value.actionRunning) delay(2)
    }

    private fun status(state: String = "REBOOT_REQUIRED", id: String = "task-a", boot: Boolean = true,
                       rollback: Boolean = false, reboot: Boolean = false) = ClientStatus(
        id, state, "S5", "S5", "lite", "ksu", "", "", "", reboot, boot, rollback, reboot,
        0, emptyList(),
    )

    private class Runtime(initial: ClientStatus?) : RecoveryRuntime {
        var snapshot = initial?.let { RecoveryRuntimeStatus(it, false, false) }
        val reads = mutableListOf<String>()
        val actions = mutableListOf<Pair<String, String>>()
        var read: (suspend (String) -> RecoveryRuntimeStatus?)? = null
        var verifyResult: suspend () -> RecoveryOperationResult = { RecoveryOperationResult() }
        override suspend fun statusFor(switchId: String): RecoveryRuntimeStatus? {
            synchronized(reads) { reads += switchId }
            val reader = read
            return if (reader != null) reader(switchId) else snapshot
        }
        override suspend fun verify(switchId: String): RecoveryOperationResult {
            synchronized(actions) { actions += "verify" to switchId }; return verifyResult()
        }
        override suspend fun reboot(switchId: String): RecoveryOperationResult {
            synchronized(actions) { actions += "reboot" to switchId }; return RecoveryOperationResult()
        }
        override suspend fun rollback(switchId: String): RecoveryOperationResult {
            synchronized(actions) { actions += "rollback" to switchId }; return RecoveryOperationResult()
        }
    }

    @Test fun exactAttachRefreshAndRecreationOnlyReadAndLeaveMissingFactsUnknown() = runBlocking {
        val runtime = Runtime(status())
        repeat(2) {
            val model = model()
            model.attach("task-a", runtime = runtime); idle(model)
            model.refresh(); idle(model)
            assertNull(model.uiState.value.baselineState)
            assertNull(model.uiState.value.decision)
            assertFalse(model.uiState.value.unavailable)
        }
        assertEquals(List(4) { "task-a" }, runtime.reads)
        assertTrue(runtime.actions.isEmpty())
    }

    @Test fun existingStatusFlagsEnableOnlyMatchingRecoveryActions() = runBlocking {
        val cases = listOf(
            status(boot = false, reboot = true, rollback = true) to setOf(RecoveryControl.REBOOT, RecoveryControl.ROLLBACK),
            status() to setOf(RecoveryControl.VERIFY),
            status("ROLLED_BACK", boot = false, reboot = true) to setOf(RecoveryControl.REBOOT),
            status("RECOVERY_REQUIRED", rollback = true) to setOf(RecoveryControl.ROLLBACK),
            status("VERIFIED") to emptySet(),
            status("VERIFYING") to emptySet(),
        )
        for ((record, controls) in cases) {
            val runtime = Runtime(record)
            val model = model()
            model.attach("task-a", runtime = runtime); idle(model)
            assertEquals(controls, RecoveryControl.entries.filter { model.uiState.value.allows(it) }.toSet())
            assertNull(model.uiState.value.baselineState)
        }
        val runtime = Runtime(status("VERIFIED")).apply { snapshot = RecoveryRuntimeStatus(status("VERIFIED"), true, false) }
        val model = model()
        model.attach("task-a", runtime = runtime); idle(model)
        assertTrue(model.uiState.value.allows(RecoveryControl.VERIFY))
    }

    @Test fun missingForeignAndFailedReadsRetainKnownRunningStateUntilAnExactTerminalRead() = runBlocking {
        val runtime = Runtime(status("COMMIT"))
        val model = model()
        model.attach("task-a", runtime = runtime); idle(model)
        assertTrue(model.uiState.value.blocksBack)
        for (foreign in listOf<ClientStatus?>(null, status(id = "task-b"))) {
            runtime.snapshot = foreign?.let { RecoveryRuntimeStatus(it, false, false) }
            model.refresh(); idle(model)
            assertTrue(model.uiState.value.readFailed)
            assertTrue(model.uiState.value.blocksBack)
            assertEquals("COMMIT", model.uiState.value.status?.stateWire)
            assertFalse(RecoveryControl.entries.any(model.uiState.value::allows))
        }
        runtime.read = { throw IllegalStateException("read offline") }
        model.refresh(); idle(model)
        assertEquals("read offline", model.uiState.value.error)
        assertTrue(model.uiState.value.blocksBack)
        runtime.read = null
        runtime.snapshot = RecoveryRuntimeStatus(status("CANCELLED"), false, false)
        model.refresh(); idle(model)
        assertFalse(model.uiState.value.blocksBack)
        assertFalse(model.uiState.value.readFailed)
    }

    @Test fun userActionSuppressesDuplicateClicksAndOrdinaryFailureCanRetryFromFreshStatus() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = Runtime(status()).apply {
            verifyResult = { entered.complete(Unit); release.await(); RecoveryOperationResult("VERIFY_FAILED", "try again") }
        }
        val model = model()
        model.attach("task-a", runtime = runtime); idle(model)
        model.verify(); withTimeout(3_000) { entered.await() }
        model.verify(); model.rollback(); model.reboot(); model.refresh()
        assertTrue(model.uiState.value.actionRunning)
        assertTrue(model.uiState.value.blocksBack)
        assertFalse(RecoveryControl.entries.any(model.uiState.value::allows))
        release.complete(Unit); idle(model)
        assertEquals("VERIFY_FAILED: try again", model.uiState.value.error)
        assertTrue(model.uiState.value.allows(RecoveryControl.VERIFY))
        runtime.verifyResult = { runtime.snapshot = RecoveryRuntimeStatus(status("VERIFIED"), false, false); RecoveryOperationResult() }
        model.verify(); idle(model)
        assertNull(model.uiState.value.error)
        assertEquals(listOf("verify" to "task-a", "verify" to "task-a"), runtime.actions)
        assertEquals(List(3) { "task-a" }, runtime.reads)
        assertFalse(model.uiState.value.allows(RecoveryControl.VERIFY))
    }

    @Test fun cancellationAndInterruptedReadsFinishLoadingAndCanBeRefreshed() = runBlocking {
        for (failure in listOf(CancellationException("cancelled read"), InterruptedException("interrupted read"))) {
            val runtime = Runtime(status()).apply { read = { throw failure } }
            val model = model()
            model.attach("task-a", runtime = runtime); idle(model)
            assertTrue(model.uiState.value.readFailed)
            assertNull(model.uiState.value.error)
            assertFalse(model.uiState.value.loading)
            runtime.read = null
            model.refresh(); idle(model)
            assertTrue(model.uiState.value.allows(RecoveryControl.VERIFY))
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun cancelledRefreshRetainsTheObservedRunningNavigationLock() = runBlocking {
        val runtime = Runtime(status("COMMIT"))
        val model = model()
        model.attach("task-a", runtime = runtime); idle(model)
        runtime.read = { throw CancellationException("read cancelled") }
        model.refresh(); idle(model)
        assertTrue(model.uiState.value.blocksBack)
        assertTrue(model.uiState.value.unavailable)
        assertFalse(RecoveryControl.entries.any(model.uiState.value::allows))
    }

    @Test fun lateOrdinaryFailureFromCancelledSourceDoesNotOverwriteTheNewAttachment() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val oldSource = RecoveryFactsSource {
            entered.countDown()
            try { check(release.await(3, TimeUnit.SECONDS)); throw IllegalStateException("old source failed") }
            finally { finished.countDown() }
        }
        val newSource = RecoveryFactsSource { id ->
            RecoveryInput(head = SwitchHeadView(1, id, "CANCELLED", "lite", "ksu", "", false, 0, "", "", 0))
        }
        val model = model()
        try {
            model.attach("task-a", oldSource)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            model.attach("task-b", newSource); idle(model)
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            delay(30)
            assertEquals("task-b", model.uiState.value.switchId)
            assertEquals("task-b", model.uiState.value.decision?.switchId)
            assertFalse(model.uiState.value.readFailed)
            assertNull(model.uiState.value.error)
        } finally { release.countDown() }
    }

    @Test fun uncertainSubmissionRetainsOnlyRefreshAndDiagnosisAcrossFreshReads() = runBlocking {
        val runtime = Runtime(status()).apply { verifyResult = { RecoveryOperationResult(ModeSwitchRootProtocol.UNCERTAIN) } }
        val model = model()
        model.attach("task-a", runtime = runtime); idle(model)
        model.verify(); idle(model)
        assertTrue(model.uiState.value.operationUncertain)
        assertTrue(model.uiState.value.diagnosticOnly)
        model.refresh(); idle(model)
        model.verify(); model.rollback(); model.reboot()
        assertFalse(RecoveryControl.entries.any(model.uiState.value::allows))
        assertEquals(listOf("verify" to "task-a"), runtime.actions)
        assertEquals(List(3) { "task-a" }, runtime.reads)
    }

    @Test fun terminalAndUnknownStatesNeverReplayWrites() = runBlocking {
        for (state in listOf("FAILED_PRE_WRITE", "CANCELLED", "VERIFIED", "ROLLED_BACK", "FUTURE_STATE")) {
            val runtime = Runtime(status(state, rollback = true))
            val model = model()
            model.attach("task-a", runtime = runtime); idle(model)
            // VERIFIED may truthfully permit rollback; this case removes that service capability.
            if (state == "VERIFIED") {
                runtime.snapshot = RecoveryRuntimeStatus(status(state), false, false)
                model.refresh(); idle(model)
            }
            model.verify(); model.reboot()
            model.rollback()
            assertTrue(runtime.actions.isEmpty())
            if (state == "FUTURE_STATE") { assertTrue(model.uiState.value.blocksBack); assertTrue(model.uiState.value.diagnosticOnly) }
        }
    }
}
