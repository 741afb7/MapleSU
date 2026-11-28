package dev.maplesu.manager.workmode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maplesu.core.R
import dev.maplesu.manager.workmode.recovery.RecoveryControl

import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
fun RecoveryScreenMiuix(state: RecoveryUiState, actions: RecoveryActions) {
    WorkModePageMiuix(
        title = stringResource(R.string.recovery_title),
        onBack = actions.onBack, backEnabled = !state.blocksBack,
    ) {
        WorkModeSectionMiuix {
            when {
                state.loading || state.actionRunning -> {
                    Text(fontSize = 18.sp, color = colorScheme.onSurface, text = stringResource(R.string.recovery_loading))
                    InfiniteProgressIndicator(size = 24.dp)
                }
                state.unavailable -> Text(fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, text = stringResource(R.string.recovery_unavailable))
                else -> {
                    Text(fontSize = 18.sp, color = colorScheme.onSurface, text = stringResource(RecoveryStrings.action(state.action)))
                    Text(fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, text = stringResource(RecoveryStrings.next(state.nextStepKey)))
                    state.baselineState?.let { Text(fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, text = stringResource(RecoveryStrings.baseline(it))) }
                    if (state.diagnosticOnly) Text(fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, text = stringResource(R.string.recovery_diagnostic_only))
                    if (state.decision?.allowed?.any { it in setOf(
                        RecoveryControl.REBOOT, RecoveryControl.VERIFY,
                        RecoveryControl.ROLLBACK, RecoveryControl.IMPORT_BASELINE,
                    ) } == true && actions.availableControls.isEmpty()) {
                        Text(fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, text = stringResource(R.string.recovery_actions_unavailable))
                    }
                }
            }
        }
        if (!state.loading && !state.unavailable) {
            val available = actions.availableControls.filter { state.allows(it) }
            if (available.isNotEmpty()) {
                WorkModeSectionMiuix {
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
                        TextButton(text = stringResource(label), onClick = action, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        WorkModeSectionMiuix {
            TextButton(text = stringResource(R.string.recovery_open_diagnosis), onClick = actions.onOpenDiagnosis, enabled = true, modifier = Modifier.fillMaxWidth())
            TextButton(text = stringResource(R.string.settings), onClick = actions.onOpenEnvironmentSettings, enabled = !state.blocksBack, modifier = Modifier.fillMaxWidth())
            if (actions.exportAvailable) { TextButton(text = stringResource(R.string.recovery_export_diagnostics), onClick = actions.onExportDiagnostics, enabled = true, modifier = Modifier.fillMaxWidth()) }
            TextButton(text = stringResource(R.string.recovery_refresh), onClick = actions.onRefresh, enabled = !state.loading && !state.actionRunning, modifier = Modifier.fillMaxWidth())
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
            WorkModeSectionMiuix {
                Text(fontSize = 18.sp, color = colorScheme.onSurface, text = stringResource(R.string.recovery_reasons))
                state.decision?.reasons.orEmpty().forEach { reason ->
                    Text(fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, text = stringResource(RecoveryStrings.reason(reason)))
                }
            }
        }
        WorkModeTechnicalInfoMiuix(technical)
    }
}
