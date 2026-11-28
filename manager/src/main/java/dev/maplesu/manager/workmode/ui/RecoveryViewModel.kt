package dev.maplesu.manager.workmode.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.maplesu.manager.workmode.recovery.RecoveryDecisionEngine
import dev.maplesu.manager.workmode.recovery.RecoveryInput
import dev.maplesu.manager.workmode.recovery.RecoveryControl
import dev.maplesu.manager.workmode.recovery.RecoveryDecision
import dev.maplesu.manager.workmode.service.ModeSwitchRootProtocol
import dev.maplesu.manager.workmode.switch.BaselineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

fun interface RecoveryFactsSource {
    /** null means unreadable facts, not an empty recovery queue. */
    fun load(switchId: String): RecoveryInput?
}

object UnavailableRecoveryFactsSource : RecoveryFactsSource {
    override fun load(switchId: String): RecoveryInput? = null
}

class RecoveryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()
    private data class Attachment(val id: String, val source: RecoveryFactsSource, val runtime: RecoveryRuntime?)
    private data class Read(
        val decision: RecoveryDecision? = null,
        val baseline: BaselineState? = null,
        val runtime: RecoveryRuntimeStatus? = null,
    )
    private var attached: Attachment? = null
    private var readVersion = 0L
    private var refreshJob: Job? = null
    private var actionJob: Job? = null

    fun attach(switchId: String, source: RecoveryFactsSource = UnavailableRecoveryFactsSource, runtime: RecoveryRuntime? = null) {
        val old = attached
        if (old?.id != switchId || old.source !== source || old.runtime !== runtime) {
            attached = Attachment(switchId, source, runtime)
            refreshJob?.cancel()
            actionJob?.cancel()
            _uiState.value = RecoveryUiState(switchId = switchId)
        }
        refresh()
    }

    fun setSource(source: RecoveryFactsSource) {
        attached?.let { attach(it.id, source) }
    }

    fun refresh() {
        if (_uiState.value.actionRunning) return
        val request = attached ?: return
        val version = ++readVersion
        refreshJob?.cancel()
        _uiState.update { it.copy(loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                val facts = read(request)
                if (current(request, version)) applyRead(request, facts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(request, version)) _uiState.update { it.copy(loading = false, readFailed = true, error = failure.message.orEmpty()) }
            } finally {
                if (current(request, version) && _uiState.value.loading) {
                    _uiState.update { it.copy(loading = false, readFailed = true) }
                }
            }
        }
    }

    private fun current(request: Attachment, version: Long) = attached === request && readVersion == version

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Recovery operation interrupted", interrupted)
        }
    }

    private suspend fun read(request: Attachment): Read? = io {
        if (request.runtime != null) request.runtime.statusFor(request.id)?.let { Read(runtime = it) }
        else request.source.load(request.id)?.let { Read(RecoveryDecisionEngine.decide(it), it.baselineState) }
    }

    private fun applyRead(request: Attachment, read: Read?, error: String? = null) {
        val facts = read?.runtime
        if (read == null || facts != null && facts.status.switchId != request.id) {
            _uiState.update { it.copy(loading = false, readFailed = true, error = error) }
            return
        }
        _uiState.update { previous ->
            val ownsSession = WorkModeSwitchSession.switchId == request.id
            val projected = facts?.let {
                projectModeSwitchStatus(previous.status ?: ModeSwitchUiState(switchId = request.id), it.status, request.id,
                    facadeExecuting = it.operationActive || ownsSession && WorkModeSwitchSession.executing,
                    completionFailureCode = WorkModeSwitchSession.failureCode.takeIf { ownsSession }.orEmpty(),
                    completionFailureDetail = WorkModeSwitchSession.failureDetail.takeIf { ownsSession }.orEmpty(),
                    knownPending = ownsSession && WorkModeSwitchSession.pending,
                )
            }
            previous.copy(loading = false, readFailed = false, error = error,
                decision = read.decision, baselineState = read.baseline, status = projected,
                bootObserved = facts?.status?.bootObserved == true, finalizationPending = facts?.finalizationPending == true)
        }
    }

    fun reboot() = perform(RecoveryControl.REBOOT) { runtime, id -> runtime.reboot(id) }
    fun verify() = perform(RecoveryControl.VERIFY) { runtime, id -> runtime.verify(id) }
    fun rollback() = perform(RecoveryControl.ROLLBACK) { runtime, id -> runtime.rollback(id) }

    private fun perform(control: RecoveryControl, action: suspend (RecoveryRuntime, String) -> RecoveryOperationResult) {
        val request = attached ?: return
        val runtime = request.runtime ?: return
        if (!_uiState.value.allows(control)) return
        val version = ++readVersion
        refreshJob?.cancel()
        _uiState.update { it.copy(actionRunning = true, error = null) }
        actionJob = viewModelScope.launch {
            var readCompleted = false
            var actionError: String? = null
            try {
                val result = io { action(runtime, request.id) }
                if (!current(request, version)) return@launch
                actionError = result.error
                if (result.code == ModeSwitchRootProtocol.UNCERTAIN) _uiState.update { it.copy(operationUncertain = true) }
                val facts = read(request)
                if (!current(request, version)) return@launch
                applyRead(request, facts, result.error)
                readCompleted = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(request, version)) _uiState.update { it.copy(readFailed = true,
                    error = listOfNotNull(actionError, failure.message?.takeIf(String::isNotBlank)).joinToString("\n")) }
            } finally {
                if (current(request, version)) _uiState.update {
                    it.copy(actionRunning = false, loading = false, readFailed = it.readFailed || !readCompleted)
                }
            }
        }
    }

    fun setExportStatus(status: String) { _uiState.update { it.copy(exportStatus = status) } }
    fun clearError() { _uiState.update { it.copy(error = null) } }
}
