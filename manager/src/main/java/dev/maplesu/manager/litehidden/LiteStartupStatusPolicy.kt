package dev.maplesu.manager.litehidden

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.content.SharedPreferences
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.storage.ModePaths

/**
 * 首页启动状态 P0：Lite 已安装判据 + root 失败提示（纯策略，JVM 可测）。
 *
 * 规则（用户定稿）：
 * - **LITE 模式**（`ModeRuntime.active == LITE`）或本地 Lite 安装证据 → **首帧即已安装/正常**，
 *   不等 Ultra 环境探测/root 获取；之后也**绝不**回退成"未安装"；
 * - 后台 root 探测失败 → `liteRootFailure=true`（只在状态卡图片上方加一行固定提示）；
 * - root 成功 / 重试成功 → 失败行消失；
 * - **KSU 模式行为不变**：即使 no-backup 目录里残留"以前装过 Lite"的凭据文件，
 *   也不能把 KSU 翻成 Lite 已安装（证据只在"未判定"时参与判定）。
 *
 * 另有一条**启动时序**约束（见 [LiteStartupFacts.rootProbeSettled]）：
 * 模式判定是两段式的（阶段二约 2.5s 后才 `activate`），那之前 `ModeRuntime.active == null`，
 * root 探测本身被门控挡住 —— 这一次失败不能算"Lite 启动失败"，否则健康设备每次冷启动
 * 都会闪一行失败提示。等判定落定（激活或超时）后再判，才算真失败。
 *
 * "本地安装证据"有**两个**来源，缺一不可（见 [LiteStartupStatusPolicy.hasLocalLiteEvidence]）：
 *   ① 凭据密文文件（no-backup）存在；
 *   ② 启动期六态判定已经认出 A 态（`MODE_A_ACTIVE` / `MODE_A_INSTALLED_UNBOUND`）。
 * 有 ② 时工作模式页本来就已经显示"已配对 / 已安装（未绑定）"，所以首页必须一致；
 * 否则模式激活没成功（bootId 过期、阶段二失败）时会出现"设置页说已配对、首页永远未安装"，
 * 正是本次 P0 要修的那个不一致。
 *
 * ── 2026-10-30 追加：**闪烁窗口**（用户反馈"进 App 先显示未安装"）────────────────────────
 * 真机日志：`mode=lite state=MODE_A_ACTIVE` 出现在启动后约 0.5s、Ultra 探针约 0.8s 才返回 Running。
 * 首页是在这之前组合出来的首帧，那时 ②③ 都还没有值 → 旧判据只能按"未安装"渲染 → 闪一下。
 * 修法有两条，互相兜底：
 *   ① **持久化 last-known 种子**（[HomeLiteInstalledHint]）：模式/Ultra 结果一旦确定就把
 *      "Lite 已安装"写进 prefs（只写布尔 + 时间戳 + 证据类型，**不写任何凭据/路径**），
 *      下次冷启动首帧同步读回 → 直接"已安装"，不等 8s awaitActivated；
 *   ② **判定中不渲染未安装**（[LiteStartupStatus.litePending]）：没有种子时首帧返回
 *      `litePending=true`，界面画 loading 占位；只有判定真正落定且确实没有证据时才显示未安装。
 * 种子**只在判定未落定的窗口里生效**（`!modeSettled`）：一旦判定落定，就以本次判定为准，
 * 过期/失效的种子不会把"真的没装"永久说成已安装。
 */
data class LiteStartupFacts(
    val liteMode: Boolean,
    val liteEvidence: Boolean,
    val ultraEnvironmentRunning: Boolean,
    val rootAvailable: Boolean,
    val probeRoot: Boolean,
    val stealthOn: Boolean = false,
    /** `ModeRuntime.active == KSU`：KSU 行为必须逐字节不变，Lite 证据不参与判定。 */
    val ksuMode: Boolean = false,
    /**
     * root 结论是否已经等到"模式判定落定"的时机。
     * - false = 启动期未判定窗口内的一次探测，失败只记录、不提示；
     * - true  = 已激活（LITE/KSU）或等待超时后的最终结论。
     */
    val rootProbeSettled: Boolean = true,
    /**
     * 启动期六态判定的结果（`ModeRuntime.latestDetection.state`），null = 还没跑过判定。
     * A 态 = 本机确实装过 Lite 的**本地硬证据**（判定规则里 B/KSU 证据永远优先，
     * 所以 A 态不可能出现在 KSU 机器上）。
     */
    val detectedState: ModeState? = null,
    /**
     * 当前 boot 已观测到 Ultra 运行通道；字段名保留旧调用兼容。
     * 只有「Ultra 环境 Running 且通道已确认」才当已安装证据，与写入种子的判据一致。
     * 这个字段只在 IO 线程（`probeRoot = true`）读，主线程首帧一律 false，不额外读磁盘。
     */
    val ultraPaired: Boolean = false,
    /**
     * 上次已知「Lite 已安装」的持久化种子（调用方已按 TTL 过滤过）。
     * **只在判定未落定时参与**（见 [LiteStartupStatusPolicy.hasInstalledEvidence]）。
     */
    val lastKnownInstalled: Boolean = false,
    /**
     * 模式判定是否已经落定：`activate` 成功（LITE/KSU），或启动等待 `awaitActivated` 超时。
     * - false = 判定中 → 无证据时给 loading 占位，**不得**渲染「未安装」；
     * - true  = 已落定 → 没证据就是真的未安装。
     */
    val modeSettled: Boolean = true,
    /**
     * 既有非 Lite 通道已经确认"已安装"（KSU 认主得到 `ksuVersion`，或 Ultra 环境 Running）。
     * 这类机器本来就会直接画成已安装，**不需要** Lite 判定 → 也就不该出现 loading 占位
     * （否则 KSU 冷启动会从"立刻已安装"退化成"先转 2.5s"）。
     */
    val nonLiteInstalled: Boolean = false,
)

data class LiteStartupStatus(
    val liteInstalled: Boolean,
    val liteRootFailure: Boolean,
    val forceManager: Boolean,
    val forceRootAvailable: Boolean,
    /**
     * 模式判定中、且没有任何已安装证据 → 界面画 loading 占位。
     * **不是**"未安装"：调用方不得把 [litePending] 当未安装渲染。
     */
    val litePending: Boolean = false,
)

/** 种子该怎么更新（纯决策，真正落盘的 [HomeLiteInstalledHintStore] 在 IO 线程执行）。 */
enum class HomeLiteHintUpdate {
    /** 不动：判定还没落定、或隐身（不留新痕迹）。 */
    NONE,

    /** 写"上次已知已安装"。 */
    WRITE,

    /** 判定落定且确实没有 Lite 证据 → 清掉过期种子，避免下次冷启动继续误报已安装。 */
    CLEAR,
}

/**
 * 种子里只保存"证据类型"，**不含**凭据、key、路径、包名或任何可用于反推安装方式的内容。
 *
 * 顺序 = 写种子时的优先级：模式判定 > 六态判定 > 本地凭据 > Ultra 环境。
 */
enum class HomeLiteHintEvidence {
    MODE_LITE_ACTIVE,
    DETECTED_A_ACTIVE,
    DETECTED_A_UNBOUND,
    LITE_CREDENTIAL,
    ULTRA_RUNNING_PAIRED,
}

/**
 * "上次已知 Lite 已安装"种子。
 *
 * - [atMillis] 是**墙钟**时间（跨重启可比），写入与读取都用 `System.currentTimeMillis()`；
 * - [isFresh] 做 TTL 过滤：过期、时间戳非法（≤0）、时钟回拨到未来 → 一律当"没有种子"。
 *   宁可首帧回落到 loading 占位（随后按本次判定纠正），也不能拿很久以前的状态骗人。
 */
data class HomeLiteInstalledHint(
    val atMillis: Long,
    val evidence: HomeLiteHintEvidence,
) {
    fun isFresh(nowMillis: Long, ttlMillis: Long = TTL_MS): Boolean {
        if (atMillis <= 0L) return false
        val age = nowMillis - atMillis
        // age < 0（种子时间在未来，通常是用户手动改时间/时区）→ 当过期，别让种子永久生效。
        return age in 0..ttlMillis
    }

    companion object {
        /** prefs 键（用户要求 key 为 `home_lite_installed_hint`）。 */
        const val PREF_KEY = "home_lite_installed_hint"

        /** 写入时间（墙钟毫秒），用于 TTL。 */
        const val PREF_KEY_AT = "home_lite_installed_hint_at"

        /** 证据类型（[HomeLiteHintEvidence] 的 name）。 */
        const val PREF_KEY_EVIDENCE = "home_lite_installed_hint_evidence"

        /** 种子有效期：30 天。过期后首帧回到 loading 占位，由本次判定决定。 */
        const val TTL_MS = 30L * 24L * 60L * 60L * 1000L

        /**
         * 从 prefs 的原始三元组还原种子；任一字段不合法 → null（当没有种子）。
         * 纯函数，主机测试直接覆盖损坏数据 / 未知证据类型。
         */
        fun of(installed: Boolean, atMillis: Long, evidenceName: String?): HomeLiteInstalledHint? {
            if (!installed || atMillis <= 0L) return null
            val evidence = evidenceName
                ?.let { name -> HomeLiteHintEvidence.values().firstOrNull { it.name == name } }
                ?: return null
            return HomeLiteInstalledHint(atMillis = atMillis, evidence = evidence)
        }
    }
}

/**
 * 种子的落盘门面（Android 侧，调用方必须在 **IO 线程**读写；首帧那一次同步读是唯一例外，
 * 它只是一次很小的 prefs 读，用户明确要求"首帧同步读取"）。
 *
 * 为什么存**中性 prefs**（[ModePaths.NEUTRAL_PREFS]）而不是 A/B 路由后的 `settings`：
 * 冷启动首帧模式还没判定，`SettingsPrefsRouter` 可能按"上次模式/凭据"路由到 `settings_B`
 * 或中性文件 —— 写入与读取会落到不同文件，种子就失效了。中性 prefs 与模式无关，
 * 写入/读取永远是同一个文件，且里面本来就存 `active_mode` 这类中性事实。
 * 种子里没有任何敏感信息（一个布尔 + 时间戳 + 证据枚举名）。
 */
object HomeLiteInstalledHintStore {

    fun prefs(context: Context): SharedPreferences =
        context.ultraPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)

    /** 读种子；不存在 / 损坏 / 过期 → null（当没有种子）。 */
    fun read(context: Context, nowMillis: Long = System.currentTimeMillis()): HomeLiteInstalledHint? {
        val prefs = prefs(context)
        val hint = HomeLiteInstalledHint.of(
            installed = prefs.getBoolean(HomeLiteInstalledHint.PREF_KEY, false),
            atMillis = prefs.getLong(HomeLiteInstalledHint.PREF_KEY_AT, 0L),
            evidenceName = prefs.getString(HomeLiteInstalledHint.PREF_KEY_EVIDENCE, null),
        ) ?: return null
        return hint.takeIf { it.isFresh(nowMillis) }
    }

    fun isInstalled(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean =
        read(context, nowMillis) != null

    fun write(
        context: Context,
        evidence: HomeLiteHintEvidence,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit()
            .putBoolean(HomeLiteInstalledHint.PREF_KEY, true)
            .putLong(HomeLiteInstalledHint.PREF_KEY_AT, nowMillis)
            .putString(HomeLiteInstalledHint.PREF_KEY_EVIDENCE, evidence.name)
            .apply()
    }

    /** 清理种子；没有痕迹时连一次 edit 都不提交（KSU 每次刷新都会走这里）。 */
    fun clear(context: Context) {
        val prefs = prefs(context)
        if (
            !prefs.contains(HomeLiteInstalledHint.PREF_KEY) &&
            !prefs.contains(HomeLiteInstalledHint.PREF_KEY_AT) &&
            !prefs.contains(HomeLiteInstalledHint.PREF_KEY_EVIDENCE)
        ) {
            return
        }
        prefs.edit()
            .remove(HomeLiteInstalledHint.PREF_KEY)
            .remove(HomeLiteInstalledHint.PREF_KEY_AT)
            .remove(HomeLiteInstalledHint.PREF_KEY_EVIDENCE)
            .apply()
    }
}

object LiteStartupStatusPolicy {

    /** 失败提示行的有限重试次数（HomeViewModel 在后台按递增间隔重试）。 */
    const val ROOT_RETRY_ATTEMPTS = 2

    /** 未安装 / KSU / 隐身 / 判定中的公共返回值（全部 false）。 */
    private val NONE = LiteStartupStatus(
        liteInstalled = false,
        liteRootFailure = false,
        forceManager = false,
        forceRootAvailable = false,
        litePending = false,
    )

    /** 六态判定是否已经认出"A 态"（= Lite 装过）。纯函数，供策略与测试共用。 */
    fun detectedLiteState(state: ModeState?): Boolean =
        state == ModeState.MODE_A_ACTIVE || state == ModeState.MODE_A_INSTALLED_UNBOUND

    /**
     * 本地安装证据 = 凭据文件**或**判定已认出 A 态。
     * 只在"不是 KSU"时参与判定（见 [liteInstalled]）。
     */
    fun hasLocalLiteEvidence(facts: LiteStartupFacts): Boolean =
        facts.liteEvidence || detectedLiteState(facts.detectedState)

    /**
     * 已安装证据（不含"模式已判定为 LITE"这一条，KSU 模式下整体不参与）：
     *   ① 本地证据（凭据 / 六态 A 态）；
     *   ② **判定中窗口**里的持久化种子（判定落定后作废，避免把"真没装"永久说成已安装）；
     *   ③ Ultra 环境 Running 且当前通道已确认（只作安装展示证据）。
     */
    fun hasInstalledEvidence(facts: LiteStartupFacts): Boolean =
        hasLocalLiteEvidence(facts) ||
            (!facts.modeSettled && facts.lastKnownInstalled) ||
            (facts.ultraEnvironmentRunning && facts.ultraPaired)

    fun liteInstalled(facts: LiteStartupFacts): Boolean =
        facts.liteMode || (!facts.ksuMode && hasInstalledEvidence(facts))

    /**
     * 是否该画 loading 占位（"模式判定中"）：
     * 判定没落定 + 完全没有已安装证据 + 不是 KSU/隐身 + 也没有别的"已安装"通道。
     */
    fun isPending(facts: LiteStartupFacts): Boolean =
        !facts.stealthOn &&
            !facts.ksuMode &&
            !facts.nonLiteInstalled &&
            !facts.modeSettled &&
            !liteInstalled(facts)

    fun evaluate(facts: LiteStartupFacts): LiteStartupStatus {
        val installed = liteInstalled(facts)
        if (!installed) {
            // KSU / 未判定：沿用原判定。未判定且没有任何证据 → loading 占位（绝不先说"未安装"）。
            // 隐身时 [isPending] 恒为 false：伪装期画"正在判定"等于自曝。
            return NONE.copy(litePending = isPending(facts))
        }
        val rootOk = facts.ultraEnvironmentRunning || facts.rootAvailable
        // 首帧（probeRoot=false）不判失败；未判定窗口内的失败也不算（见类注释）。
        // ⚠️ 还必须 `modeSettled`：否则"只有种子、判定还没落定"的状态被调用方传了
        //    rootProbeSettled=true 时，会凭空画出一行"Lite 启动失败"——种子只允许影响
        //    「已安装」的展示，不允许影响「失败行」。
        val settled = facts.liteMode || (facts.rootProbeSettled && facts.modeSettled)
        val failure = facts.probeRoot && settled && !rootOk && !facts.stealthOn
        return LiteStartupStatus(
            liteInstalled = true,
            liteRootFailure = failure,
            forceManager = true,
            // 已安装态：root 失败也不把页面翻成未安装；失败仅由提示行表达。
            forceRootAvailable = true,
        )
    }

    /** 写种子时记的证据类型；null = 本次没有任何可写种子的证据。 */
    fun hintEvidence(facts: LiteStartupFacts): HomeLiteHintEvidence? = when {
        // 隐身期不写任何新痕迹（现有种子保持不动，反正隐身也不读它）。
        facts.stealthOn -> null
        facts.liteMode -> HomeLiteHintEvidence.MODE_LITE_ACTIVE
        facts.detectedState == ModeState.MODE_A_ACTIVE -> HomeLiteHintEvidence.DETECTED_A_ACTIVE
        facts.detectedState == ModeState.MODE_A_INSTALLED_UNBOUND -> HomeLiteHintEvidence.DETECTED_A_UNBOUND
        facts.liteEvidence -> HomeLiteHintEvidence.LITE_CREDENTIAL
        facts.ultraEnvironmentRunning && facts.ultraPaired -> HomeLiteHintEvidence.ULTRA_RUNNING_PAIRED
        else -> null
    }

    /**
     * 种子维护决策（只在 IO 线程落盘）：
     * - 隐身 → 不动；
     * - 已判定为 KSU → 清（明确不是 Lite，别留过期种子）；
     * - 有证据 → 写（模式/六态/凭据/Ultra 任一确定即写，符合"结果确定时写入"）；
     * - 确定不是 Lite 且没有安装证据 → 清；
     * - 判定中或本轮仍未知 → 不动，已完成不等于已确认未安装。
     */
    fun hintUpdate(facts: LiteStartupFacts): HomeLiteHintUpdate = when {
        facts.stealthOn -> HomeLiteHintUpdate.NONE
        facts.ksuMode -> HomeLiteHintUpdate.CLEAR
        hintEvidence(facts) != null -> HomeLiteHintUpdate.WRITE
        facts.modeSettled && facts.detectedState != null &&
            facts.detectedState != ModeState.INDETERMINATE -> HomeLiteHintUpdate.CLEAR
        else -> HomeLiteHintUpdate.NONE
    }

    /** 只有"Lite 已安装 + 确实失败 + 还有重试额度"才重试；KSU/未安装永远不重试。 */
    fun shouldRetryRoot(liteInstalled: Boolean, liteRootFailure: Boolean, attemptsDone: Int): Boolean =
        liteInstalled && liteRootFailure && attemptsDone < ROOT_RETRY_ATTEMPTS
}
