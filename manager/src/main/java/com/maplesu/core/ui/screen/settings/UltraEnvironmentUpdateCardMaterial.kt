package com.maplesu.core.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maplesu.core.R
import com.maplesu.core.ui.component.WarningLevel
import com.maplesu.core.ui.component.dialog.rememberConfirmDialog
import com.maplesu.core.ui.component.material.WarningCard
import dev.maplesu.manager.runtime.ultra.UltraEnvUpgrade

/** Both home skins share the manual check and the existing confirmation/write flow. */
@Composable
internal fun rememberUltraEnvironmentUpdateClick(panel: UltraEnvironmentUpdatePanel): () -> Unit {
    var checked by remember { mutableStateOf<UltraEnvUpgrade?>(null) }
    val title = stringResource(R.string.ultra_env_update_confirm_title)
    val confirm = stringResource(R.string.ultra_env_update_confirm)
    val message = ultraUpdateConfirmMessage(checked)
    val dialog = rememberConfirmDialog(onConfirm = { panel.startUpgrade() })
    LaunchedEffect(checked) {
        if (message != null) dialog.showConfirm(title = title, content = message, confirm = confirm)
        checked = null
    }
    return { panel.checkForUpgrade { checked = it } }
}

/**
 * 首页环境更新入口 · Material；使用统一的黄色提示卡。
 */
@Composable
fun UltraEnvironmentUpdateCardMaterial(
    panel: UltraEnvironmentUpdatePanel,
    modifier: Modifier = Modifier,
) {
    val upgrade = panel.upgrade
    val onClick = rememberUltraEnvironmentUpdateClick(panel)
    val enabled = !panel.busy && !panel.checking

    AnimatedVisibility(
        visible = upgrade is UltraEnvUpgrade.Rebooting || panel.allowed &&
            (panel.busy || !panel.readUnknown && upgrade is UltraEnvUpgrade.Available),
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        WarningCard(
            message = ultraUpdateMessage(upgrade, panel.busy),
            modifier = modifier,
            level = WarningLevel.Warning,
            onClick = if (enabled && upgrade is UltraEnvUpgrade.Available) onClick else null,
        )
    }
}
