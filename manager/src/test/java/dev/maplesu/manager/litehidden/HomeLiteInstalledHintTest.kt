package dev.maplesu.manager.litehidden

import dev.maplesu.manager.workmode.detect.ModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0（2026-10-30 冷启动闪烁）纯 JVM 测试：**持久化 last-known 种子** + **判定中 loading 占位**。
 *
 * 真机现象：进 App 先显示「未安装」，约 0.5s 后才变绿。根因是首帧（`ModeRuntime.active == null`、
 * Ultra 探针未返回）里没有任何证据 → 旧判据只能落到「未安装」。
 *
 * 这里覆盖的判据（全部是 `LiteStartupStatusPolicy` 的纯函数，不碰 Android / JNI / 磁盘）：
 *  - 首帧有种子 → 已安装；无种子 → `litePending`（loading），**不是**未安装；
 *  - 判定落定后按本次结果纠正（过期种子不会把"真没装"永久说成已安装）；
 *  - 种子过期（TTL / 未来时间戳 / 损坏数据）→ 当没有种子；
 *  - KSU 不读 Lite 种子、KSU 行为不变；隐身归一化优先（不认种子、不出 loading、不写痕迹）；
 *  - 失败行条件不变：只有"已安装 + 确实失败"才有那一行（loading / 未安装 / KSU / 隐身都没有）；
 *  - 种子的写/清决策（`hintUpdate` / `hintEvidence`）。
 */
class HomeLiteInstalledHintTest {

    private fun facts(
        liteMode: Boolean = false,
        liteEvidence: Boolean = false,
        ultraRunning: Boolean = false,
        ultraPaired: Boolean = false,
        rootAvailable: Boolean = false,
        probeRoot: Boolean = false,
        stealthOn: Boolean = false,
        ksuMode: Boolean = false,
        rootProbeSettled: Boolean = true,
        detectedState: ModeState? = null,
        lastKnownInstalled: Boolean = false,
        // 默认「判定中」= 冷启动首帧；需要"判定已落定"的用例显式传 true。
        modeSettled: Boolean = false,
        nonLiteInstalled: Boolean = false,
    ) = LiteStartupFacts(
        liteMode = liteMode,
        liteEvidence = liteEvidence,
        ultraEnvironmentRunning = ultraRunning,
        rootAvailable = rootAvailable,
        probeRoot = probeRoot,
        stealthOn = stealthOn,
        ksuMode = ksuMode,
        rootProbeSettled = rootProbeSettled,
        detectedState = detectedState,
        ultraPaired = ultraPaired,
        lastKnownInstalled = lastKnownInstalled,
        modeSettled = modeSettled,
        nonLiteInstalled = nonLiteInstalled,
    )

    // ── 1. 首帧种子 ────────────────────────────────────────────────────────────

    @Test
    fun firstFrameWithSeedShowsInstalled() {
        val status = LiteStartupStatusPolicy.evaluate(facts(lastKnownInstalled = true))
        assertTrue("首帧有 last-known 种子 → 直接已安装（不等 8s awaitActivated）", status.liteInstalled)
        assertTrue("已安装必须补齐 manager 语义", status.forceManager)
        assertTrue("已安装必须补齐 root 可用语义", status.forceRootAvailable)
        assertFalse("有种子就不该再画 loading", status.litePending)
        assertFalse("首帧不判失败", status.liteRootFailure)
    }

    // ── 2. 无种子 → loading，绝不渲染未安装 ────────────────────────────────────

    @Test
    fun firstFrameWithoutSeedIsLoadingNotNotInstalled() {
        val status = LiteStartupStatusPolicy.evaluate(facts())
        assertFalse("没有证据就不能说已安装", status.liteInstalled)
        assertTrue("判定中 → loading 占位（这条替代旧的「未安装」首帧）", status.litePending)
        assertFalse("loading 期不许出现 Lite 失败行", status.liteRootFailure)
        assertFalse(status.forceManager)
        assertFalse(status.forceRootAvailable)
    }

    @Test
    fun pendingNeverForcesManagerOrRootAvailable() {
        // 没有判定结果时 force* 必须为 false：否则会把"未安装"的设备画成已安装。
        val status = LiteStartupStatusPolicy.evaluate(facts(modeSettled = false))
        assertTrue(status.litePending)
        assertFalse(status.forceManager)
        assertFalse(status.forceRootAvailable)
    }

    // ── 3. 判定后纠正（过期种子的唯一正确出口） ────────────────────────────────

    @Test
    fun determinationCorrectsStaleSeed() {
        // 上次装过 Lite（种子在），但这次判定落定后既没有凭据也没有 A 态 → 真未安装。
        val status = LiteStartupStatusPolicy.evaluate(
            facts(lastKnownInstalled = true, modeSettled = true),
        )
        assertFalse("判定落定后种子作废，按本次结果纠正", status.liteInstalled)
        assertFalse("已落定就不该再 loading", status.litePending)
        assertFalse("未安装绝不许出现 Lite 失败行", status.liteRootFailure)
    }

    @Test
    fun determinationKeepsInstalledWhenEvidenceStillThere() {
        val status = LiteStartupStatusPolicy.evaluate(
            facts(lastKnownInstalled = true, liteEvidence = true, modeSettled = true),
        )
        assertTrue(status.liteInstalled)
        assertFalse(status.litePending)
    }

    @Test
    fun determinationTimeoutFallsBackToNotInstalled() {
        // awaitActivated 8s 超时也算落定：没有证据就是未安装（不是永远转圈）。
        val status = LiteStartupStatusPolicy.evaluate(facts(modeSettled = true, nonLiteInstalled = false))
        assertFalse(status.liteInstalled)
        assertFalse(status.litePending)
    }

    // ── 4. 种子过期 / 损坏 ─────────────────────────────────────────────────────

    @Test
    fun expiredSeedIsIgnored() {
        val now = 1_700_000_000_000L
        val expired = HomeLiteInstalledHint(now - HomeLiteInstalledHint.TTL_MS - 1L, HomeLiteHintEvidence.MODE_LITE_ACTIVE)
        assertFalse("超过 TTL → 种子无效", expired.isFresh(now))
        // 调用方（HomeViewModel）过滤后会传 lastKnownInstalled = false → 首帧回到 loading，而不是误报已安装。
        val status = LiteStartupStatusPolicy.evaluate(facts(lastKnownInstalled = false))
        assertFalse(status.liteInstalled)
        assertTrue(status.litePending)
    }

    @Test
    fun seedTtlBoundaryIsInclusive() {
        val now = 1_700_000_000_000L
        val atBoundary = HomeLiteInstalledHint(now - HomeLiteInstalledHint.TTL_MS, HomeLiteHintEvidence.LITE_CREDENTIAL)
        assertTrue("正好 TTL 仍算有效", atBoundary.isFresh(now))
        val justExpired = HomeLiteInstalledHint(now - HomeLiteInstalledHint.TTL_MS - 1L, HomeLiteHintEvidence.LITE_CREDENTIAL)
        assertFalse(justExpired.isFresh(now))
        assertTrue(HomeLiteInstalledHint(now, HomeLiteHintEvidence.LITE_CREDENTIAL).isFresh(now))
    }

    @Test
    fun futureTimestampSeedIsIgnored() {
        // 用户改过系统时间 / 时区 → 种子时间在未来。宁可当过期，也不能让种子永久生效。
        val now = 1_700_000_000_000L
        val fromFuture = HomeLiteInstalledHint(now + 60_000L, HomeLiteHintEvidence.MODE_LITE_ACTIVE)
        assertFalse(fromFuture.isFresh(now))
    }

    @Test
    fun malformedSeedIsRejectedInsteadOfCrashing() {
        val now = 1_700_000_000_000L
        assertNull("没写 installed 标记 → 没有种子", HomeLiteInstalledHint.of(false, now, "MODE_LITE_ACTIVE"))
        assertNull("时间戳非法 → 没有种子", HomeLiteInstalledHint.of(true, 0L, "MODE_LITE_ACTIVE"))
        assertNull("未知证据类型（旧版本/脏数据）→ 没有种子", HomeLiteInstalledHint.of(true, now, "SOMETHING_ELSE"))
        assertNull("证据类型缺失 → 没有种子", HomeLiteInstalledHint.of(true, now, null))

        val valid = HomeLiteInstalledHint.of(true, now, HomeLiteHintEvidence.ULTRA_RUNNING_PAIRED.name)
        assertNotNull(valid)
        assertEquals(HomeLiteHintEvidence.ULTRA_RUNNING_PAIRED, valid!!.evidence)
        assertEquals(now, valid.atMillis)
    }

    // ── 5. KSU 行为不变 ───────────────────────────────────────────────────────

    @Test
    fun ksuModeNeverReadsLiteSeed() {
        val status = LiteStartupStatusPolicy.evaluate(
            facts(ksuMode = true, lastKnownInstalled = true, modeSettled = false, probeRoot = true),
        )
        assertFalse("KSU 模式不认 Lite 种子（残留证据也不翻）", status.liteInstalled)
        assertFalse("KSU 不走 loading 占位", status.litePending)
        assertFalse("KSU 不许出现 Lite 失败行", status.liteRootFailure)
        assertFalse(status.forceManager)
        assertFalse(status.forceRootAvailable)
    }

    @Test
    fun ksuModeIgnoresUltraEvidenceToo() {
        val status = LiteStartupStatusPolicy.evaluate(
            facts(ksuMode = true, ultraRunning = true, ultraPaired = true, probeRoot = true),
        )
        assertFalse("KSU 行为逐字节不变：Ultra 证据也不参与 Lite 判定", status.liteInstalled)
        assertFalse(status.litePending)
    }

    // ── 6. 隐身归一化优先 ─────────────────────────────────────────────────────

    @Test
    fun stealthSuppressesPendingAndFailure() {
        // 既有契约不变：策略层不抹掉"已安装"这个事实（隐身归一化在 UiState 层做，
        // 见 HomeUiState.asStealthKernelNotReady / HomeUiStateLiteStatusTest），
        // 但隐身期**不许**出现 loading 占位，也不许出现失败行 —— 两者都会自曝。
        val status = LiteStartupStatusPolicy.evaluate(
            facts(stealthOn = true, lastKnownInstalled = true, liteMode = true, probeRoot = true),
        )
        assertTrue("策略层保持既有契约：安装事实不在这里被改写", status.liteInstalled)
        assertFalse("隐身伪装：不许画 loading（等于暴露正在判定 Lite）", status.litePending)
        assertFalse("隐身伪装：不许有失败行", status.liteRootFailure)
        assertEquals(
            "隐身不写新痕迹（现有种子保持不动）",
            HomeLiteHintUpdate.NONE,
            LiteStartupStatusPolicy.hintUpdate(facts(stealthOn = true, liteMode = true)),
        )
    }

    @Test
    fun stealthWithoutEvidenceIsNotLoadingEither() {
        // 隐身 + 没有种子/证据：既不能画 loading，也不能说已安装 → 直接是原版「未安装」那一支。
        val status = LiteStartupStatusPolicy.evaluate(facts(stealthOn = true))
        assertFalse(status.litePending)
        assertFalse(status.liteInstalled)
        assertFalse(status.liteRootFailure)
    }

    // ── 7. 既有已安装通道（KSU 认主 / Ultra Running）不进 loading ────────────────

    @Test
    fun nonLiteInstalledChannelSuppressesLoading() {
        val status = LiteStartupStatusPolicy.evaluate(
            facts(nonLiteInstalled = true, modeSettled = false),
        )
        assertFalse(status.liteInstalled)
        assertFalse("KSU 认主 / Ultra Running 已经能直接画已安装，不需要 Lite 判定", status.litePending)
    }

    @Test
    fun ultraRunningPairedCountsAsInstalled() {
        val status = LiteStartupStatusPolicy.evaluate(
            facts(ultraRunning = true, ultraPaired = true, probeRoot = true),
        )
        assertTrue("Ultra 环境 Running 且配对 → 首帧即已安装", status.liteInstalled)
        assertFalse("Ultra 通道可用 ⇒ 不画失败行", status.liteRootFailure)
        assertEquals(
            HomeLiteHintEvidence.ULTRA_RUNNING_PAIRED,
            LiteStartupStatusPolicy.hintEvidence(facts(ultraRunning = true, ultraPaired = true)),
        )
    }

    @Test
    fun ultraRunningWithoutPairingIsNotLiteEvidence() {
        val f = facts(ultraRunning = true, ultraPaired = false, nonLiteInstalled = true)
        assertFalse("只有 Running 没配对 → 不当 Lite 已安装证据", LiteStartupStatusPolicy.evaluate(f).liteInstalled)
        assertNull("也不写种子", LiteStartupStatusPolicy.hintEvidence(f))
    }

    // ── 8. 失败行条件不变（回归） ──────────────────────────────────────────────

    @Test
    fun seedAloneNeverShowsRootFailureLine() {
        // 只有种子（判定没落定）时，即使调用方误传 rootProbeSettled=true，
        // 也不许凭空画「Ultra 启动失败」：种子只允许影响"已安装"，不允许影响"失败行"。
        val status = LiteStartupStatusPolicy.evaluate(
            facts(
                lastKnownInstalled = true,
                modeSettled = false,
                probeRoot = true,
                rootProbeSettled = true,
                rootAvailable = false,
                ultraRunning = false,
            ),
        )
        assertTrue(status.liteInstalled)
        assertFalse("种子不得伪造失败行", status.liteRootFailure)
    }

    @Test
    fun failureLineStillRequiresInstalledAndRealFailure() {
        val failed = LiteStartupStatusPolicy.evaluate(
            facts(liteMode = true, probeRoot = true, rootAvailable = false, ultraRunning = false),
        )
        assertTrue("已安装 + 后台 root 真失败 → 提示行", failed.liteInstalled && failed.liteRootFailure)

        val ok = LiteStartupStatusPolicy.evaluate(
            facts(liteMode = true, probeRoot = true, rootAvailable = true),
        )
        assertFalse("root 正常 → 没有提示行", ok.liteRootFailure)

        val pending = LiteStartupStatusPolicy.evaluate(facts(probeRoot = true))
        assertFalse("loading 期没有提示行", pending.liteRootFailure)
    }

    // ── 9. 种子的写 / 清决策 ──────────────────────────────────────────────────

    @Test
    fun hintIsWrittenOnlyForConclusiveEvidence() {
        assertEquals(
            HomeLiteHintUpdate.WRITE,
            LiteStartupStatusPolicy.hintUpdate(facts(liteMode = true, modeSettled = true)),
        )
        assertEquals(
            HomeLiteHintEvidence.MODE_LITE_ACTIVE,
            LiteStartupStatusPolicy.hintEvidence(facts(liteMode = true)),
        )
        assertEquals(
            HomeLiteHintEvidence.DETECTED_A_ACTIVE,
            LiteStartupStatusPolicy.hintEvidence(facts(detectedState = ModeState.MODE_A_ACTIVE)),
        )
        assertEquals(
            HomeLiteHintEvidence.DETECTED_A_UNBOUND,
            LiteStartupStatusPolicy.hintEvidence(facts(detectedState = ModeState.MODE_A_INSTALLED_UNBOUND)),
        )
        assertEquals(
            HomeLiteHintEvidence.LITE_CREDENTIAL,
            LiteStartupStatusPolicy.hintEvidence(facts(liteEvidence = true)),
        )
        assertEquals(
            "判定中且没证据 → 还轮不到它下结论，不动",
            HomeLiteHintUpdate.NONE,
            LiteStartupStatusPolicy.hintUpdate(facts(modeSettled = false)),
        )
    }

    @Test
    fun hintIsClearedWhenDeterminationSaysNotInstalled() {
        assertEquals(
            "判定落定仍无证据 → 清种子（否则下次冷启动继续误报已安装）",
            HomeLiteHintUpdate.CLEAR,
            LiteStartupStatusPolicy.hintUpdate(facts(modeSettled = true, detectedState = ModeState.NOT_INSTALLED)),
        )
        assertEquals(
            "KSU 明确不是 Lite → 清种子",
            HomeLiteHintUpdate.CLEAR,
            LiteStartupStatusPolicy.hintUpdate(facts(ksuMode = true, modeSettled = true)),
        )
        assertEquals(
            "有证据 → 写，不是清",
            HomeLiteHintUpdate.WRITE,
            LiteStartupStatusPolicy.hintUpdate(facts(liteEvidence = true, modeSettled = true)),
        )
    }

    @Test
    fun anUnknownCompletedRoundDoesNotEraseTheLastConfirmedHint() {
        for (detected in listOf(null, ModeState.INDETERMINATE)) {
            assertEquals(HomeLiteHintUpdate.NONE, LiteStartupStatusPolicy.hintUpdate(
                facts(modeSettled = true, detectedState = detected, lastKnownInstalled = true),
            ))
            assertNull(LiteStartupStatusPolicy.hintEvidence(
                facts(modeSettled = true, detectedState = detected, lastKnownInstalled = true),
            ))
        }
    }
}
