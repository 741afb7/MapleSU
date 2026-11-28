package com.maplesu.core.ui.screen.home

import androidx.compose.runtime.Immutable
import dev.maplesu.manager.workmode.ui.WorkModeEntryPolicy

/** 隐藏事实（只来自内存 + 中性 prefs，不起 root、不碰 JNI）。 */
@Immutable
data class HomeHiddenFacts(
    /** Lite 隐藏生效（`LiteHiddenRuntime.isMaskingLite()`）。 */
    val masking: Boolean,
    /** 隐藏/寄生仍在确认中（`HiddenStartupConfirm.current()`）。 */
    val confirming: Boolean,
    /** A real hide/unhide transaction keeps all historical display hidden. */
    val transitionPending: Boolean = false,
) {
    /** 隐藏生效或确认窗口内：任何管理器专属入口都不许出现。 */
    val suppressEntries: Boolean get() = masking || confirming || transitionPending
}

/**
 * 首页入口可见性的 ViewModel 快照。组合期只读它，不再读 JNI / prefs / 文件。
 *
 * [workModeEntry] / [workModeSwitch] 只有在 [hidden] 明确放行时才来自真实读取，
 * 其余情况一律是"不可见"。不变量 §2.1 因此由构造保证，而不是靠调用方记得判断。
 */
@Immutable
data class HomeEntryFacts(
    val hidden: HomeHiddenFacts,
    val workModeEntry: WorkModeEntryPolicy,
    val workModeSwitch: HomeWorkModeSwitchState,
) {
    companion object {
        /**
         * 拿到事实之前的快照：入口全部不可见。
         *
         * 只有 [hidden] 是真值（读内存与 prefs 种子，首帧状态卡要靠它决定是否走「未安装」伪装）；
         * 入口要等 IO 线程读完才放出来，所以"没拿到事实"天然等于"不露入口"。
         */
        fun beforeFirstRead(hidden: HomeHiddenFacts): HomeEntryFacts = HomeEntryFacts(
            hidden = hidden,
            workModeEntry = WorkModeEntryPolicy.NONE,
            workModeSwitch = HomeWorkModeSwitchState.HIDDEN,
        )
    }
}

/**
 * 入口事实的唯一组合点（纯函数，主机可测）。
 *
 * 隐藏时**不调用** [entry] / [switch]：既省掉一次文件与 JNI 读取，也保证隐藏优先。
 */
fun homeEntryFacts(
    hidden: HomeHiddenFacts,
    entry: () -> WorkModeEntryPolicy,
    switch: () -> HomeWorkModeSwitchState,
): HomeEntryFacts = if (hidden.suppressEntries) {
    HomeEntryFacts.beforeFirstRead(hidden)
} else {
    HomeEntryFacts(hidden = hidden, workModeEntry = entry(), workModeSwitch = switch())
}
