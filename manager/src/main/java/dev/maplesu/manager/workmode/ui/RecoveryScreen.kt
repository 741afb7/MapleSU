package dev.maplesu.manager.workmode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import dev.maplesu.manager.workmode.recovery.RecoveryControl

@Immutable
data class RecoveryActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onReboot: () -> Unit,
    val onVerify: () -> Unit,
    val onRollback: () -> Unit,
    val onImportBaseline: () -> Unit,
    val onExportDiagnostics: () -> Unit,
    val onOpenDiagnosis: () -> Unit,
    val onOpenEnvironmentSettings: () -> Unit,
    val availableControls: Set<RecoveryControl> = emptySet(),
    val exportAvailable: Boolean = false,
)

@Composable
fun RecoveryScreen(
    switchId: String,
    source: RecoveryFactsSource? = null,
    onReboot: (() -> Unit)? = null,
    onVerify: (() -> Unit)? = null,
    onRollback: (() -> Unit)? = null,
    onImportBaseline: (() -> Unit)? = null,
    onExportDiagnostics: (() -> Unit)? = null,
    onOpenDiagnosis: (() -> Unit)? = null,
    onOpenEnvironmentSettings: (() -> Unit)? = null,
    onBackEnabledChange: (Boolean) -> Unit = {},
    runtime: RecoveryRuntime = ProductionRecoveryRuntime,
) {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<RecoveryViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(switchId, source, runtime) {
        viewModel.attach(switchId, source ?: UnavailableRecoveryFactsSource, runtime.takeIf { source == null })
    }
    SideEffect { onBackEnabledChange(!state.blocksBack) }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.blocksBack, onBackCompleted = {},
    )
    val actions = RecoveryActions(
        onBack = { if (!state.blocksBack) navigator.pop() },
        onRefresh = viewModel::refresh,
        onReboot = { onReboot?.invoke() ?: viewModel.reboot() },
        onVerify = { onVerify?.invoke() ?: viewModel.verify() },
        onRollback = { onRollback?.invoke() ?: viewModel.rollback() },
        onImportBaseline = { onImportBaseline?.invoke() },
        onExportDiagnostics = { onExportDiagnostics?.invoke() },
        onOpenDiagnosis = { onOpenDiagnosis?.invoke() ?: navigator.push(Route.Diagnosis(switchId)) },
        onOpenEnvironmentSettings = { onOpenEnvironmentSettings?.invoke() ?: navigator.push(Route.Settings) },
        availableControls = buildSet {
            if (onReboot != null || source == null) add(RecoveryControl.REBOOT)
            if (onVerify != null || source == null) add(RecoveryControl.VERIFY)
            if (onRollback != null || source == null) add(RecoveryControl.ROLLBACK)
            if (onImportBaseline != null) add(RecoveryControl.IMPORT_BASELINE)
        },
        exportAvailable = onExportDiagnostics != null,
    )
    when (LocalUiMode.current) {
        UiMode.Miuix -> RecoveryScreenMiuix(state, actions)
        UiMode.Material -> RecoveryScreenMaterial(state, actions)
    }
}
