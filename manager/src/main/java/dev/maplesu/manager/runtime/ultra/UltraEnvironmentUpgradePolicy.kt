// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

/**
 * 环境版本的比较与显示入口（纯 Kotlin，JVM 可测）。
 * SDK 原始版本用于比较与显示，不做版本映射。
 * 外层构建标签与已安装 helper 身份分开：标签变化不等于 helper 已更新。
 */
data class EnvVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<EnvVersion> {
    override fun compareTo(other: EnvVersion): Int =
        compareValuesBy(this, other, EnvVersion::major, EnvVersion::minor, EnvVersion::patch)

    companion object {
        private val FORMAT = Regex("""(\d{1,6})\.(\d{1,6})\.(\d{1,6})""")

        /** 只接受严格的 `a.b.c`；读不出来一律 null，调用方按"不能确认"处理。 */
        fun parse(text: String?): EnvVersion? {
            val match = text?.trim()?.let(FORMAT::matchEntire) ?: return null
            val (major, minor, patch) = match.destructured
            return EnvVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

object UltraEnvironmentVersion {
    /** 原样采用 SDK 返回的环境版本。 */
    fun displayOf(environmentVersion: String?): String? =
        environmentVersion?.trim()?.takeIf { it.isNotEmpty() }

    /** 构建号只作**读取失败时的兜底**：`maplesu-ultra-5.0.5-r1` → `5.0.5`。 */
    private val BUILD_ID = Regex("""maplesu-ultra-(\d{1,6}\.\d{1,6}\.\d{1,6})(?:-[A-Za-z0-9]+)*""")

    /**
     * 带构建身份的显示：先采用 SDK 版本；只有版本读不到时才退回构建号里的三段数字，
     * 再读不到就返回 null。构建号永远不覆盖 SDK 的真实回执。
     */
    fun displayOf(environmentVersion: String?, buildId: String?): String? {
        displayOf(environmentVersion)?.let { return it }
        return buildId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(BUILD_ID::matchEntire)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotEmpty() }
    }
}

/** 首页升级卡的四种样子。版本号都已换算成 MapleSU_Ultra 版本。 */
sealed interface UltraEnvUpgrade {
    data object None : UltraEnvUpgrade

    data class Available(val from: String, val to: String) : UltraEnvUpgrade {
        val isBuildUpdate: Boolean get() = from == to
    }

    /** 本次开机已经提交过升级，等待重启生效。 */
    data class Rebooting(val to: String) : UltraEnvUpgrade

    /**
     * 设备上已装环境的身份**确认不了**（回读失败、或随包身份缺失）。
     *
     * 不是「已是最新」，也不是「百分百可升级」：界面显示「待确认」，但**允许用户主动
     * 重装**——重装会写入可验证身份，这是唯一能走出来的路。写入仍必须走
     * [UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild]，且回读通过才算成功。
     *
     * @param to 随包目标版本（界面文案用；读不到时为 null）
     */
    data class Unverified(val reason: String, val to: String? = null) : UltraEnvUpgrade
}

/**
 * 判定升级需要的全部事实。版本是环境原始版本；默认值即"什么都不能确认"。
 *
 * <p>`identityKnown=false`（默认）时退回 5.0.5 之前的行为：只比三段版本。运行时
 * [UltraEnvironmentUpgrade.readFacts] 会显式置 true —— 真实设备上身份是判定的一部分，
 * 缺哪一侧就报「待确认」。
 */
data class UltraEnvFacts(
    val running: Boolean = false,
    val suspended: Boolean = false,
    val quiescing: Boolean = false,
    val installedVersion: String? = null,
    val bundledVersion: String? = null,
    val submittedThisBoot: Boolean = false,
    val installedBuildId: String? = null,
    val bundledBuildId: String? = null,
    val installedHelperSha256: String? = null,
    val bundledHelperSha256: String? = null,
    val identityKnown: Boolean = false,
    val installedAppVersion: String? = null,
    val bundledAppVersion: String? = null,
    /** A missing read is not a confirmed negative or authority to write. */
    val readUnknown: Boolean = false,
    val observationScope: String? = null,
    /** Current SDK absence receipt plus a live kernel channel; empty facts never establish this. */
    val freshKernelInstall: Boolean = false,
)

/** A display observation; retained facts never replace the upgrade's fresh preflight. */
data class UltraEnvObservation(
    val scope: String,
    val version: String?,
    val upgrade: UltraEnvUpgrade,
    val readUnknown: Boolean,
) {
    companion object {
        fun observe(previous: UltraEnvObservation?, facts: UltraEnvFacts, scope: String?): UltraEnvObservation? {
            if (scope == null || facts.observationScope != scope) return null
            val version = UltraEnvironmentVersion.displayOf(facts.installedVersion, facts.installedBuildId)
            if (facts.readUnknown && !facts.suspended && !facts.quiescing && !facts.submittedThisBoot) {
                return previous?.takeIf { it.scope == scope }?.copy(readUnknown = true)
                    ?: UltraEnvObservation(scope, version, UltraEnvUpgrade.None, true)
            }
            return UltraEnvObservation(scope, version, UltraEnvUpgradePolicy.decide(facts), facts.readUnknown)
        }
    }
}

object UltraEnvUpgradePolicy {
    fun decide(facts: UltraEnvFacts): UltraEnvUpgrade {
        // 模式切换进行中、本机暂停使用：既不提示，也不允许写环境。
        if (facts.quiescing || facts.suspended) return UltraEnvUpgrade.None
        val target = UltraEnvironmentVersion.displayOf(facts.bundledVersion, facts.bundledBuildId)
            ?: return UltraEnvUpgrade.None
        // 本次开机已经提交过：等重启，不再提示第二次（版本回读可能要重启后才变）。
        if (facts.submittedThisBoot) return UltraEnvUpgrade.Rebooting(target)
        if (facts.readUnknown) return UltraEnvUpgrade.None
        if (!facts.running) return UltraEnvUpgrade.None
        // 管理器回退（例如把刚升到 5.0.5 的构建换回 5.0.3）：不自动降级，交用户显式处理。
        val bundledApp = EnvVersion.parse(facts.bundledAppVersion)
        val installedApp = EnvVersion.parse(facts.installedAppVersion)
        if (bundledApp != null && installedApp != null && bundledApp < installedApp) {
            return UltraEnvUpgrade.None
        }
        val current = UltraEnvironmentVersion.displayOf(facts.installedVersion, facts.installedBuildId)
            ?: return UltraEnvUpgrade.None
        val installed = EnvVersion.parse(current) ?: return UltraEnvUpgrade.None
        val bundled = EnvVersion.parse(target) ?: return UltraEnvUpgrade.None
        // 兜底规则（与 5.0.5 之前一致）：上游版本更高就要升级，不需要身份也能判断。
        if (bundled > installed) return UltraEnvUpgrade.Available(from = current, to = target)
        if (installed > bundled) return UltraEnvUpgrade.None
        // 上游版本不更高：只有身份才能说明「同一版本的新构建」。
        if (!facts.identityKnown) return UltraEnvUpgrade.None
        val installedSha = UltraInstalledIdentityProbe.normalizeSha(facts.installedHelperSha256)
        val bundledSha = UltraInstalledIdentityProbe.normalizeSha(facts.bundledHelperSha256)
        val installedBuild = facts.installedBuildId?.trim()?.takeIf { it.isNotEmpty() }
        val bundledBuild = facts.bundledBuildId?.trim()?.takeIf { it.isNotEmpty() }
        if (installedSha != null && bundledSha != null) {
            if (installedSha != bundledSha) return UltraEnvUpgrade.Available(from = current, to = target)
            // Identical installed bytes are not an upgrade just because the APK label changed.
            return UltraEnvUpgrade.None
        }
        if (installedBuild != null && bundledBuild != null) {
            return if (installedBuild != bundledBuild) {
                UltraEnvUpgrade.Available(from = current, to = target)
            } else {
                UltraEnvUpgrade.None
            }
        }
        // 两侧没有任何一条共同的坐标轴可用：确认不了，保持「待确认」；用户仍可点重装。
        val missing = if (installedSha == null && installedBuild == null) "INSTALLED" else "BUNDLED"
        return UltraEnvUpgrade.Unverified(reason = "IDENTITY_${missing}_UNKNOWN", to = target)
    }
}
