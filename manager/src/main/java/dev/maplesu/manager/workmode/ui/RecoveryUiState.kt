package dev.maplesu.manager.workmode.ui

import androidx.compose.runtime.Immutable
import dev.maplesu.manager.workmode.recovery.RecoveryAction
import dev.maplesu.manager.workmode.recovery.RecoveryControl
import dev.maplesu.manager.workmode.recovery.RecoveryDecision
import dev.maplesu.manager.workmode.switch.BaselineState

@Immutable
data class RecoveryUiState(
    val switchId: String = "",
    val loading: Boolean = true,
    val decision: RecoveryDecision? = null,
    val baselineState: BaselineState? = null,
    val readFailed: Boolean = false,
    val exportStatus: String? = null,
    val error: String? = null,
    val status: ModeSwitchUiState? = null,
    val bootObserved: Boolean = false,
    val finalizationPending: Boolean = false,
    val actionRunning: Boolean = false,
    val operationUncertain: Boolean = false,
) {
    val unavailable: Boolean get() = !loading && (decision == null && status == null || readFailed)
    val diagnosticOnly: Boolean get() = operationUncertain || decision?.diagnosticOnly == true ||
        status?.let { it.stateWire !in KNOWN_STATES } == true
    val requiresExternalImage: Boolean get() = decision?.requiresExternalImage == true
    val action: RecoveryAction? get() = decision?.action ?: status?.let {
        when {
            it.stateWire !in KNOWN_STATES || operationUncertain -> RecoveryAction.FORBIDDEN
            it.blocksBack -> RecoveryAction.IN_PROGRESS
            RecoveryControl.VERIFY in runtimeControls -> RecoveryAction.AUTO_VERIFY
            RecoveryControl.REBOOT in runtimeControls -> RecoveryAction.RESUME_REBOOT
            it.requiresExternalRecovery -> if (it.canRollback) RecoveryAction.AUTO_ROLLBACK else RecoveryAction.EXTERNAL_RECOVERY
            else -> RecoveryAction.NONE
        }
    }
    val nextStepKey: String? get() = decision?.nextStepKey ?: when (action) {
        RecoveryAction.NONE -> "recovery_next_none"
        RecoveryAction.IN_PROGRESS -> "recovery_next_wait"
        RecoveryAction.RESUME_REBOOT -> "recovery_next_reboot"
        RecoveryAction.AUTO_VERIFY -> "recovery_next_verify"
        RecoveryAction.AUTO_ROLLBACK -> "recovery_next_rollback"
        RecoveryAction.EXTERNAL_RECOVERY -> "recovery_next_external"
        else -> null
    }
    // A failed refresh retains the last running decision and its navigation lock.
    val blocksBack: Boolean get() = actionRunning || status?.blocksBack == true || action == RecoveryAction.IN_PROGRESS
    fun allows(control: RecoveryControl): Boolean =
        !loading && !readFailed && !actionRunning && !operationUncertain &&
            (decision?.allows(control) ?: (control in runtimeControls))
    fun blocks(control: RecoveryControl): Boolean = decision?.blocks(control) ?: (status != null && control !in runtimeControls)

    private val runtimeControls: Set<RecoveryControl> get() = buildSet {
        val current = status ?: return@buildSet
        if (current.blocksBack || current.stateWire !in KNOWN_STATES) return@buildSet
        if (current.canRollback && current.stateWire in setOf("REBOOT_REQUIRED", "VERIFIED", "RECOVERY_REQUIRED")) add(RecoveryControl.ROLLBACK)
        if ((current.rebootRequired || current.canResumeReboot) && !bootObserved &&
            current.stateWire in setOf("REBOOT_REQUIRED", "ROLLED_BACK")) add(RecoveryControl.REBOOT)
        if (current.stateWire == "REBOOT_REQUIRED" && bootObserved ||
            current.stateWire == "VERIFIED" && finalizationPending) add(RecoveryControl.VERIFY)
    }

    private companion object {
        val KNOWN_STATES = setOf("BEGIN", "SNAPSHOT", "PRODUCE", "COMMIT", "REBOOT_REQUIRED", "VERIFYING",
            "ROLLING_BACK", "RECOVERY_REQUIRED", "VERIFIED", "ROLLED_BACK", "FAILED_PRE_WRITE", "CANCELLED")
    }
}
