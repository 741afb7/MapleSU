package dev.maplesu.manager.workmode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maplesu.core.R
import dev.maplesu.manager.workmode.recovery.RecoveryControl

import androidx.compose.material3.*

@Composable
fun RecoveryScreenMaterial(state: RecoveryUiState, actions: RecoveryActions) {
    WorkModePageMaterial(
        title = stringResource(R.string.recovery_title),
        onBack = actions.onBack, backEnabled = !state.blocksBack,
    ) {
        WorkModeSectionMaterial {
            when {
                state.loading || state.actionRunning -> {
                    Text(style = MaterialTheme.typography.titleMedium, text = stringResource(R.string.recovery_loading))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.unavailable -> Text(style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, text = stringResource(R.string.recovery_unavailable))
                else -> {
                    Text(style = MaterialTheme.typography.titleMedium, text = stringResource(RecoveryStrings.action(state.action)))
                    Text(style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, text = stringResource(RecoveryStrings.next(state.nextStepKey)))
                    state.baselineState?.let { Text(style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, text = stringResource(RecoveryStrings.baseline(it))) }
                    if (state.diagnosticOnly) Text(style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, text = stringResource(R.string.recovery_diagnostic_only))
                    if (state.decision?.allowed?.any { it in setOf(
                        RecoveryControl.REBOOT, RecoveryControl.VERIFY,
                        RecoveryControl.ROLLBACK, RecoveryControl.IMPORT_BASELINE,
                    ) } == true && actions.availableControls.isEmpty()) {
                        Text(style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, text = stringResource(R.string.recovery_actions_unavailable))
                    }
                }
            }
        }
        if (!state.loading && !state.unavailable) {
            val available = actions.availableControls.filter { state.allows(it) }
            if (available.isNotEmpty()) {
                WorkModeSectionMaterial {
                    available.forEach { control ->
                        val label = when (control) {
                            RecoveryControl.REBOOT -> R.string.recovery_reboot
                            RecoveryControl.VERIFY -> R.string.recovery_verify
                            RecoveryControl.ROLLBACK -> R.string.recovery_rollback
                            else -> R.string.recovery_import_baseline
                        }
                        val action = when (control) {
                            RecoveryControl.REBOOT -> actions.onReboot
                            RecoveryControl.VERIFY -> actions.onVerify
                            RecoveryControl.ROLLBACK -> actions.onRollback
                            else -> actions.onImportBaseline
                        }
                        Button(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
                    }
                }
            }
        }
        WorkModeSectionMaterial {
            OutlinedButton(onClick = actions.onOpenDiagnosis, enabled = true, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recovery_open_diagnosis)) }
            OutlinedButton(onClick = actions.onOpenEnvironmentSettings, enabled = !state.blocksBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings)) }
            if (actions.exportAvailable) { OutlinedButton(onClick = actions.onExportDiagnostics, enabled = true, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recovery_export_diagnostics)) } }
            OutlinedButton(onClick = actions.onRefresh, enabled = !state.loading && !state.actionRunning, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recovery_refresh)) }
        }
        val technical = buildList {
            state.status?.let {
                add("switch.id=${state.switchId}; state=${it.stateWire}; stage=${it.stage}; step=${it.step}")
                if (it.code.isNotBlank()) add("error=${it.code}")
                if (it.detail.isNotBlank()) add(it.detail)
            }
            state.decision?.let { decision ->
                add("switch.id=${state.switchId}")
                add("recovery.action=${decision.action.wire}")
                decision.reasons.forEach { add(it.code) }
            }
            state.error?.takeIf(String::isNotBlank)?.let { add(it) }
        }
        if (state.decision?.reasons?.isNotEmpty() == true && !state.unavailable) {
            WorkModeSectionMaterial {
                Text(style = MaterialTheme.typography.titleMedium, text = stringResource(R.string.recovery_reasons))
                state.decision?.reasons.orEmpty().forEach { reason ->
                    Text(style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, text = stringResource(RecoveryStrings.reason(reason)))
                }
            }
        }
        WorkModeTechnicalInfoMaterial(technical)
    }
}
