package dev.maplesu.manager.workmode.ui

import dev.maplesu.manager.workmode.service.ClientStatus
import dev.maplesu.manager.workmode.service.ModeSwitchRootProtocol
import dev.maplesu.manager.workmode.service.SwitchOutcome

/** Existing Facade facts, without inventing the W16 baseline/boot/WAL input set. */
data class RecoveryRuntimeStatus(
    val status: ClientStatus,
    val finalizationPending: Boolean,
    val operationActive: Boolean,
)

data class RecoveryOperationResult(val code: String = "", val detail: String = "") {
    val error: String? get() = listOf(code, detail).filter(String::isNotBlank)
        .joinToString(": ").takeIf(String::isNotBlank)
}

interface RecoveryRuntime {
    suspend fun statusFor(switchId: String): RecoveryRuntimeStatus?
    suspend fun reboot(switchId: String): RecoveryOperationResult
    suspend fun verify(switchId: String): RecoveryOperationResult
    suspend fun rollback(switchId: String): RecoveryOperationResult
}

object ProductionRecoveryRuntime : RecoveryRuntime {
    private fun facade() = checkNotNull(SwitchFacadeProvider.current()) { "SWITCH_SERVICE_NOT_READY" }

    override suspend fun statusFor(switchId: String): RecoveryRuntimeStatus? {
        val facade = facade()
        val status = facade.statusFor(switchId) ?: return null
        return RecoveryRuntimeStatus(status, facade.hasPendingFinalization(status), facade.operationActive)
    }

    override suspend fun reboot(switchId: String): RecoveryOperationResult =
        if (facade().reboot(switchId)) RecoveryOperationResult()
        else RecoveryOperationResult("REBOOT_REQUEST_FAILED")

    override suspend fun verify(switchId: String): RecoveryOperationResult {
        val outcome = facade().retryVerify(switchId)
        val matched = when (outcome) {
            is SwitchOutcome.Verified -> outcome.switchId == switchId && outcome.status.belongsTo(switchId)
            is SwitchOutcome.AwaitingReboot -> outcome.switchId == switchId && outcome.status.belongsTo(switchId)
            is SwitchOutcome.Incomplete -> outcome.switchId == switchId && outcome.status.belongsTo(switchId) && outcome.code != ModeSwitchRootProtocol.UNCERTAIN
            is SwitchOutcome.Failed -> outcome.switchId == switchId && outcome.status.belongsTo(switchId) && outcome.code != ModeSwitchRootProtocol.UNCERTAIN
            is SwitchOutcome.Blocked, is SwitchOutcome.ImagesRequired -> false
        }
        if (matched) synchronized(WorkModeSwitchSession) {
            if (WorkModeSwitchSession.switchId == switchId) WorkModeSwitchSession.finish(outcome)
        }
        return when (outcome) {
            is SwitchOutcome.Verified, is SwitchOutcome.AwaitingReboot -> RecoveryOperationResult()
            is SwitchOutcome.Incomplete -> RecoveryOperationResult(outcome.code.ifBlank { "SWITCH_SERVICE_FAILED" }, outcome.detail)
            is SwitchOutcome.Failed -> RecoveryOperationResult(outcome.code.ifBlank { "SWITCH_SERVICE_FAILED" }, outcome.detail)
            is SwitchOutcome.Blocked -> RecoveryOperationResult(outcome.code.ifBlank { "SWITCH_SERVICE_FAILED" }, outcome.detail)
            is SwitchOutcome.ImagesRequired -> RecoveryOperationResult("BASELINE_MISSING")
        }
    }

    private fun ClientStatus?.belongsTo(switchId: String) = this == null || this.switchId == switchId

    override suspend fun rollback(switchId: String): RecoveryOperationResult {
        val result = facade().rollback(switchId) ?: return RecoveryOperationResult("SWITCH_STATUS_UNAVAILABLE")
        return if (result.ok) RecoveryOperationResult()
        else RecoveryOperationResult(result.code.ifBlank { "SWITCH_SERVICE_FAILED" }, result.detail)
    }
}
