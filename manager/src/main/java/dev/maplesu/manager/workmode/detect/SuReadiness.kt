// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect

/**
 * §5 C6 的**第二事实源**：su 到底能不能用，由一次真实子进程的退出码与 stdout 回答。
 *
 * 背景（2026-11-02 实机）：DirtyFrag×Normal / ×Ultra 激活成功后只要重启一次，管理器首页
 * 就显示「未安装」；同一时刻 `su -c 'id -u'` 返回 `0`、`kernelsu` / `maplesu_l3` 仍在
 * `/proc/modules` 里。旧实现用 `File("/system/bin/su").exists()` 当唯一证据 —— 那既证明
 * 不了"有 su"（自有 su 发布在 `/data/<16hex>/su` 之类的位置），也证明不了"没有 su"。
 *
 * 本文件只有**纯模型与纯判定**（无 Android import），整张真值表可在宿主 JVM 驱动；
 * 真正起进程的适配器在 `probes/SuReadinessProbeAdapter.kt`。
 *
 * 三态语义（用户明确要求）：
 * - [SuReadiness.USABLE]：子进程真的起来了，退出码 0，stdout 的 uid 是 0；
 * - [SuReadiness.NOT_USABLE]：子进程真的起来了，但退出码 != 0（或 stdout 说 uid != 0）；
 * - [SuReadiness.UNKNOWN]：起不来 / 超时 / 结果缺失。**UNKNOWN 绝不回落成"未安装"**。
 *
 * `File.exists` 只允许出现在适配器的"枚举候选"一步；它不是任何一条结论的证据。
 */
enum class SuReadiness {
    USABLE,
    NOT_USABLE,
    UNKNOWN,
}

/**
 * 一次候选探测的**观察值**。
 *
 * @param source 稳定标识（日志/证据用），例如 `maplesu-su:/data/xxxx/su`、`ksu-su:/system/bin/su`；
 * @param readiness 本次候选自己的三态结论；
 * @param exitCode 真实退出码；null = 没观察到（起不来/超时）；
 * @param uid 从 stdout 解析出的 uid；null = stdout 里没有可解析的 uid；
 * @param detail 具名细节（`timeout` / `start_failed:<Class>` / stderr 片段），不含凭据。
 */
data class SuProbeAttempt(
    val source: String,
    val readiness: SuReadiness,
    val exitCode: Int? = null,
    val uid: Int? = null,
    val detail: String = "",
)

/** 一轮探测的汇总；[attempts] 保留每个候选的观察值，便于日志与设备侧取证。 */
data class SuProbeReport(
    val readiness: SuReadiness,
    val attempts: List<SuProbeAttempt> = emptyList(),
) {
    /** 唯一被证明可用的候选；null = 没有任何候选给出 uid 0。 */
    val usableSource: String?
        get() = attempts.firstOrNull { it.readiness == SuReadiness.USABLE }?.source

    /** 单行诊断（不含路径以外的敏感值；路径本身在日志里可接受）。 */
    val describe: String
        get() = "su_readiness=" + readiness.name +
            " usable=" + (usableSource ?: "NONE") +
            " attempts=[" + attempts.joinToString(",") { it.source + ":" + it.readiness.name } + "]"
}

/** 探测端口（生产 = 真子进程；测试 = 固定观察值）。 */
fun interface SuReadinessProbe {
    fun probe(): SuProbeReport
}

/**
 * 纯判定：把"一个子进程的观察值"翻译成三态，再合成整轮结论。
 *
 * 这里刻意不接收布尔 `suPresent`：旧接口把一个"读不到"折叠成 false，正是本次缺陷的根因。
 */
object SuProbeDecision {

    const val SOURCE_L3_GATE = "l3-gate"
    const val SOURCE_MAPLESU_SU = "maplesu-su"
    const val SOURCE_KSU_SU = "ksu-su"
    const val SOURCE_KSU_DEBUG_SU = "ksu-debug-su"

    /**
     * 单个候选的三态判定。
     *
     * 只有"进程真的跑完 + 退出码 0 + stdout 的 uid 是 0"才算 [SuReadiness.USABLE]；
     * 退出码非 0 是 [SuReadiness.NOT_USABLE]；起不来/超时/没有退出码一律 [SuReadiness.UNKNOWN]。
     */
    fun classify(
        launched: Boolean,
        timedOut: Boolean,
        exitCode: Int?,
        stdout: String?,
    ): SuReadiness = when {
        !launched -> SuReadiness.UNKNOWN
        timedOut -> SuReadiness.UNKNOWN
        exitCode == null -> SuReadiness.UNKNOWN
        exitCode != 0 -> SuReadiness.NOT_USABLE
        parseUid(stdout) == 0 -> SuReadiness.USABLE
        else -> SuReadiness.NOT_USABLE
    }

    /**
     * 从 stdout 里取**最后一个纯数字行**作为 uid。
     *
     * `su -c 'id -u'` 只打印一行；`ksud debug su` 这类交互 shell 可能先打印提示再打印 uid，
     * 所以取最后一个可解析行，而不是要求整段输出逐字等于 `0`。
     * 解析不到 = null（调用方按 NOT_USABLE 处理：进程跑完却没有 uid 证据）。
     */
    fun parseUid(stdout: String?): Int? = stdout
        ?.lineSequence()
        ?.map { it.trim() }
        ?.lastOrNull { line -> line.isNotEmpty() && line.toIntOrNull() != null }
        ?.toIntOrNull()

    /**
     * 多个候选合成一轮结论：
     * 任一 USABLE ⇒ USABLE（最短证据优先）；
     * 否则任一 UNKNOWN ⇒ UNKNOWN（"读不到"不得被当成"没有"）；
     * 否则（全部跑过且都不是 root，或根本没有候选）⇒ NOT_USABLE。
     */
    fun combine(outcomes: List<SuReadiness>): SuReadiness = when {
        outcomes.any { it == SuReadiness.USABLE } -> SuReadiness.USABLE
        outcomes.any { it == SuReadiness.UNKNOWN } -> SuReadiness.UNKNOWN
        else -> SuReadiness.NOT_USABLE
    }

    fun report(attempts: List<SuProbeAttempt>): SuProbeReport =
        SuProbeReport(combine(attempts.map { it.readiness }), attempts)
}

/** 首帧不信任跨 boot 的未安装记录；已确认模式继续由已有展示逻辑继承。 */
object EnvironmentHomeProjection {

    data class Facts(
        val snapshot: EnvironmentSnapshot?,
        val currentBootId: String,
        /** 本次真实探针结论；null = 本次没有探测（首帧 / 不需要探）。 */
        val liveReadiness: SuReadiness? = null,
    )

    data class Result(
        val confirmedNotInstalled: Boolean,
        val bootChanged: Boolean,
        /** Historical installation cards may only be inherited within their recorded boot. */
        val cachedMode: dev.maplesu.manager.workmode.WorkMode?,
        /** A previous boot's mode is display history, never a current installation verdict. */
        val previousMode: dev.maplesu.manager.workmode.WorkMode?,
    )

    fun decide(facts: Facts): Result {
        val snapshot = facts.snapshot
        val bootChanged = snapshot != null &&
            snapshot.bootId.isNotBlank() &&
            facts.currentBootId.isNotBlank() &&
            snapshot.bootId != facts.currentBootId
        val confirmedNotInstalled = snapshot != null &&
            snapshot.mode == null &&
            !snapshot.dirty &&
            facts.currentBootId.isNotBlank() && snapshot.bootId == facts.currentBootId &&
            // 本次实探说 USABLE = 当场推翻了"未安装"；绝不因为旧记录继续渲染未安装。
            facts.liveReadiness != SuReadiness.USABLE
        val cachedMode = snapshot?.mode?.takeIf {
            facts.currentBootId.isNotBlank() && snapshot.bootId == facts.currentBootId
        }
        return Result(confirmedNotInstalled, bootChanged, cachedMode,
            snapshot?.mode?.takeIf { bootChanged })
    }
}
