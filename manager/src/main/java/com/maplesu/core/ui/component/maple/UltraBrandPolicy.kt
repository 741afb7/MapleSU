package com.maplesu.core.ui.component.maple

import androidx.compose.runtime.Immutable
import com.maplesu.core.ui.screen.home.HomeHiddenSignal
import com.maplesu.core.ui.screen.home.HomeInstallSignal
import com.maplesu.core.ui.screen.home.HomeWorkModeSwitchBlock
import com.maplesu.core.ui.screen.home.HomeWorkModeSwitchState
import dev.maplesu.manager.workmode.WorkMode

/**
 * 首页 `MapleSU Ultra` 字样的全部输入事实（纯数据，JVM 可测）。
 *
 * [active] 为 null 表示模式还没判定；[installed]、两路隐藏信号的 UNKNOWN 都按"不能确认"处理。
 */
@Immutable
data class UltraBrandFacts(
    val active: WorkMode?,
    val installed: HomeInstallSignal,
    val liteHidden: HomeHiddenSignal = HomeHiddenSignal.OFF,
    val ksuStealth: HomeHiddenSignal = HomeHiddenSignal.OFF,
    val hiddenConfirming: Boolean = false,
)

/**
 * 是否显示 Ultra 字样的唯一裁决（规范 §2.1、§4）。
 *
 * 隐藏/隐身优先：确认窗口内、隐藏生效、隐身开启（含读不到）时一律只显示 `MapleSU`，
 * 不播动画、不留任何模式痕迹。之后才看模式与安装事实：未判定、未安装、Normal 同样不显示。
 */
fun shouldShowUltraBrand(facts: UltraBrandFacts): Boolean = when {
    hidesModeInfo(facts) -> false
    facts.active != WorkMode.LITE -> false
    else -> facts.installed.isInstalled
}

/**
 * 隐藏/隐身优先（规范 §2.1）：确认窗口内、隐藏生效、隐身开启（含读不到）时，
 * 界面不得出现任何模式信息。字样和「关于」小窗的模式介绍共用这一条判据。
 */
fun hidesModeInfo(facts: UltraBrandFacts): Boolean =
    facts.hiddenConfirming || facts.liteHidden.masks || facts.ksuStealth.masks

/**
 * 从首页已有判据折叠出 [UltraBrandFacts]。
 *
 * [HomeWorkModeSwitchState] 已经过隐藏/隐身/安装的同一套裁决：不可见时 `current` 恒为 null，
 * 这里只把它的结论翻译过来，不重新读取任何运行时事实。
 */
fun homeUltraBrandFacts(
    workModeSwitch: HomeWorkModeSwitchState,
    liteHiddenMasking: Boolean,
    hiddenConfirming: Boolean,
    environmentMode: WorkMode? = null,
    environmentInstalled: Boolean = false,
): UltraBrandFacts = UltraBrandFacts(
    active = workModeSwitch.current ?: environmentMode.takeIf { environmentInstalled },
    installed = if (workModeSwitch.visible || environmentInstalled) HomeInstallSignal.INSTALLED else HomeInstallSignal.UNKNOWN,
    liteHidden = HomeHiddenSignal.of(
        liteHiddenMasking || workModeSwitch.blocked == HomeWorkModeSwitchBlock.HIDDEN_ACTIVE,
    ),
    ksuStealth = HomeHiddenSignal.of(
        workModeSwitch.blocked == HomeWorkModeSwitchBlock.STEALTH_MASKED,
    ),
    hiddenConfirming = hiddenConfirming,
)
