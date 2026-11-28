package dev.maplesu.manager.workmode.ui

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ClientStatus

/** Unknown states and failed reads are not evidence that writing has stopped. */
internal fun projectModeSwitchStatus(
    previous: ModeSwitchUiState,
    status: ClientStatus?,
    switchId: String,
    facadeExecuting: Boolean = false,
    completionFailureCode: String = "",
    completionFailureDetail: String = "",
    knownPending: Boolean = false,
): ModeSwitchUiState {
    if (status == null || status.switchId != switchId) {
        // A route alone is not a running fact. Once execution was observed (or this process
        // owns a pending operation), missing reads retain the navigation lock indefinitely.
        return if (previous.phase == ModeSwitchPhase.ATTACHING && !facadeExecuting && !knownPending)
            previous.copy(phase = ModeSwitchPhase.MISSING) else previous
    }
    val recovery = status.state == "RECOVERY_REQUIRED"
    val phase = when {
        recovery -> ModeSwitchPhase.NEEDS_RECOVERY
        facadeExecuting -> ModeSwitchPhase.RUNNING
        status.state in setOf("ROLLED_BACK", "FAILED_PRE_WRITE", "CANCELLED") -> ModeSwitchPhase.FAILED
        status.state in setOf("VERIFIED", "REBOOT_REQUIRED") ->
            if (completionFailureCode.isNotBlank()) ModeSwitchPhase.FAILED else ModeSwitchPhase.SUCCEEDED
        else -> ModeSwitchPhase.RUNNING
    }
    return ModeSwitchUiState(
        switchId = switchId,
        phase = phase,
        stateWire = status.state,
        stage = status.stage,
        step = status.step,
        from = WorkMode.fromWire(status.fromMode),
        to = WorkMode.fromWire(status.toMode),
        code = completionFailureCode.ifBlank { status.error },
        detail = completionFailureDetail.ifBlank { status.errorDetail },
        pending = status.pending,
        rebootRequired = status.state in setOf("REBOOT_REQUIRED", "ROLLED_BACK") && status.rebootRequired,
        canRollback = status.canRollback,
        canResumeReboot = status.canResumeReboot,
        requiresExternalRecovery = recovery,
        verifyFailures = status.verifyFailures,
        actionRunning = facadeExecuting,
        hasServiceRecord = true,
    )
}
