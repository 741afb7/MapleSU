package com.maplesu.core.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.maplesu.core.ui.component.WarningLevel
import com.maplesu.core.ui.component.miuix.WarningCard
import dev.maplesu.manager.runtime.ultra.UltraEnvUpgrade

/**
 * 首页环境更新入口 · Miuix；使用统一的黄色提示卡。
 */
@Composable
fun UltraEnvironmentUpdateCardMiuix(
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
