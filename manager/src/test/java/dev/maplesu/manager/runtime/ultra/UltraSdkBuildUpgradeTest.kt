// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import java.io.File
import dev.maplesu.manager.installer.InstallSourceKind
import dev.maplesu.manager.installer.InstallTargetMode
import dev.maplesu.manager.installer.UltraDirectInstallPolicy
import dev.maplesu.manager.installer.UltraDirectInstallRequest
import dev.maplesu.manager.workmode.WorkMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.0.5 构建身份 / 升级链路（纯 JVM：无 Android Context、无 JNI、无设备）。
 *
 * 覆盖三件此前会「假成功」的事：
 *
 *  1. **同一上游版本的新构建**要能被判成可升级（旧逻辑只比三段版本，永远 None）；
 *  2. **Running 不再是升级的短路理由**：`Intent.UpgradeToBundledBuild` 必须真的提交载荷；
 *  3. **回读哈希不符不许报成功**：SDK 回执 ok 只说明调用成功，设备上是不是这一版只能靠
 *     归一化 key 槽后的 helper 摘要证明。
 *
 * <p>另有一条用户口径：身份读不到（[UltraEnvUpgrade.Unverified]）时**允许用户点升级**
 * （重装即写入可验证身份），但「算成功」的标准不放宽 —— 回读不通过仍然 FAILED；而
 * quiescing / 暂停 / 未运行 / 管理器降级 四类仍然零写调用。
 */
class UltraSdkBuildUpgradeTest {

    private val oldHelper = "a".repeat(64)
    private val newHelper = "b".repeat(64)
    private val newBuild = "maplesu-ultra-5.0.5-r1"

    /** 同一上游版本 5.0.5：设备上是 r0，随包是 r1。 */
    private val sameUpstreamNewBuild = UltraEnvFacts(
        running = true,
        installedVersion = "5.0.5",
        bundledVersion = "5.0.5",
        installedBuildId = "maplesu-ultra-5.0.5-r0",
        bundledBuildId = newBuild,
        installedHelperSha256 = oldHelper,
        bundledHelperSha256 = newHelper,
        identityKnown = true,
    )

    private fun decide(facts: UltraEnvFacts) = UltraEnvUpgradePolicy.decide(facts)

    @Test fun directInstallBuildUpdateUsesActualInstallerAndRequiresIdentityReadback() {
        for (verified in listOf(true, false)) {
            val port = FakePort()
            val persisted = mutableListOf<InstalledIdentity>()
            val result = UltraDirectInstallPolicy.dispatch(
                UltraDirectInstallRequest(WorkMode.LITE, InstallTargetMode.ULTRA,
                    InstallSourceKind.CURRENT_PARTITION, "boot"),
                facts = { sameUpstreamNewBuild },
                upgrade = {
                    UltraEnvironmentUpgrade.resultOf(UltraEnvironmentInstaller.install(port,
                        UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
                        attachments(verification = IdentityVerification(verified,
                            if (verified) null else "HELPER_MISMATCH", newBuild), persisted = persisted)))
                },
                unavailable = { error("unexpected pending update") },
                patchBoot = { error("ULTRA_KERNEL_GENERATE_SITE:HOOK_ENTRY_UNSUPPORTED:do_execveat_common") },
            )
            assertEquals(if (verified) UltraEnvironmentUpgrade.Result.SUBMITTED else UltraEnvironmentUpgrade.Result.FAILED, result)
            assertEquals(1, port.installEnvironmentCalls)
            assertEquals(if (verified) 1 else 0, persisted.size)
        }
    }

    /** 一条「回读成功、且上次安装就是 r1」的身份记录。 */
    private val readbackRecord = InstalledIdentity(
        helperSha256 = newHelper,
        helperPath = "/data/adb/maplesu/init",
        helperSize = 1163328L,
        buildId = newBuild,
        bootId = "boot-1",
        source = Source.Readback,
    )

    // ── a) 同一上游版本、旧构建 → Available ────────────────────────────────

    @Test
    fun sameUpstreamVersionWithOlderBuildIsOffered() {
        assertEquals(
            UltraEnvUpgrade.Available(from = "5.0.5", to = "5.0.5"),
            decide(sameUpstreamNewBuild),
        )
    }

    // ── b) 完全相同 → None ───────────────────────────────────────────────

    @Test
    fun identicalBuildIsNotOfferedAgain() {
        val same = sameUpstreamNewBuild.copy(
            installedBuildId = newBuild,
            installedHelperSha256 = newHelper,
        )
        assertEquals(UltraEnvUpgrade.None, decide(same))
    }

    @Test
    fun identicalInstalledHelperIsNotAnUpgradeJustBecauseTheApkBuildLabelChanged() {
        assertEquals(UltraEnvUpgrade.None,
            decide(sameUpstreamNewBuild.copy(installedHelperSha256 = newHelper)))
    }

    @Test
    fun newerInstalledSdkIsNotDowngradedByAnOlderHelper() {
        assertEquals(UltraEnvUpgrade.None,
            decide(sameUpstreamNewBuild.copy(installedVersion = "5.0.6")))
    }

    @Test
    fun freshInstallationAlsoReadsBackAndPersistsItsIdentity() {
        val persisted = mutableListOf<InstalledIdentity>()
        val outcome = UltraEnvironmentInstaller.install(FakePort("NotInstalled"),
            UltraEnvironmentInstaller.Intent.ReuseRunning, attachments(persisted = persisted))
        assertTrue(outcome.ok)
        assertTrue(outcome.verified)
        assertEquals("New installs need the same readback as upgrades", 1, persisted.size)
    }

    // ── c) installed 身份未知 → Unverified（绝不能变成 Available）──────────

    @Test
    fun unknownInstalledIdentityIsUnverifiedAndNeverAvailable() {
        val unknown = sameUpstreamNewBuild.copy(installedBuildId = null, installedHelperSha256 = null)
        val decision = decide(unknown)
        assertTrue("expected Unverified but was $decision", decision is UltraEnvUpgrade.Unverified)
        assertFalse("identity unknown must not be read as an upgrade", decision is UltraEnvUpgrade.Available)
        assertEquals("IDENTITY_INSTALLED_UNKNOWN", (decision as UltraEnvUpgrade.Unverified).reason)
    }

    // ── d) 管理器回退 → None（禁止自动降级）──────────────────────────────

    @Test
    fun managerVersionRegressionIsNeverOffered() {
        val regressed = sameUpstreamNewBuild.copy(
            bundledAppVersion = "5.0.3",
            installedAppVersion = "5.0.4",
        )
        assertEquals(UltraEnvUpgrade.None, decide(regressed))
    }

    // ── e) 暂停 / quiescing → None ──────────────────────────────────────

    @Test
    fun quiescingOrSuspendedBlocksEverything() {
        assertEquals(UltraEnvUpgrade.None, decide(sameUpstreamNewBuild.copy(quiescing = true)))
        assertEquals(UltraEnvUpgrade.None, decide(sameUpstreamNewBuild.copy(suspended = true)))
    }

    // ── f) Running 下：Reuse 复用 vs Upgrade 真的提交一次 ─────────────────

    @Test
    fun upgradeIntentSubmitsEvenWhenRunningWhileReuseDoesNot() {
        val reusePort = FakePort()
        val reused = UltraEnvironmentInstaller.install(
            reusePort,
            UltraEnvironmentInstaller.Intent.ReuseRunning,
            attachments(),
        )
        assertEquals("ReuseRunning 必须复用 Running，不得写设备", 0, reusePort.installEnvironmentCalls)
        assertTrue(reused.ok)

        val upgradePort = FakePort()
        val persisted = mutableListOf<InstalledIdentity>()
        val upgraded = UltraEnvironmentInstaller.install(
            upgradePort,
            UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            attachments(persisted = persisted),
        )
        assertEquals(
            "UpgradeToBundledBuild 在 Running 下也必须真的调用 installEnvironment 一次",
            1,
            upgradePort.installEnvironmentCalls,
        )
        assertTrue(upgraded.ok)
        assertTrue("核对通过时 verified 必须为 true", upgraded.verified)
        assertEquals("校验通过要把随包构建号盖上再落盘", newBuild, persisted.single().buildId)
    }

    // ── g) 升级后回读哈希不符 → verified=false 且结果为 FAILED ─────────────

    @Test
    fun mismatchedReadbackIsNotSuccessAndUpgradeFails() {
        val persisted = mutableListOf<InstalledIdentity>()
        val outcome = UltraEnvironmentInstaller.install(
            FakePort(),
            UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            attachments(
                verification = IdentityVerification(
                    verified = false,
                    reason = "HELPER_SHA_MISMATCH",
                    buildId = "maplesu-ultra-5.0.5-r0",
                ),
                persisted = persisted,
            ),
        )
        assertTrue("SDK 安装本身成功（ok 只由回执决定）", outcome.ok)
        assertFalse("回读哈希不符不得伪报成功", outcome.verified)
        assertTrue("detail 必须写明原因，实际=${outcome.detail}", outcome.detail.orEmpty().contains("HELPER_SHA_MISMATCH"))
        assertTrue("核对没通过不许落持久化身份", persisted.isEmpty())
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.resultOf(outcome))
        assertTrue(
            "失败原因要留给 UI，实际=${UltraEnvironmentUpgrade.failureReasonOf(outcome)}",
            UltraEnvironmentUpgrade.failureReasonOf(outcome).orEmpty().contains("UNVERIFIED"),
        )
    }

    // ── Unverified（身份确认不了）也必须能点一次升级 ────────────────────

    @Test
    fun unverifiedIdentityStillAllowsTheReinstallPath() {
        val unknown = sameUpstreamNewBuild.copy(installedBuildId = null, installedHelperSha256 = null)
        val decision = decide(unknown)
        assertTrue("身份读不到必须是 Unverified，实际=$decision", decision is UltraEnvUpgrade.Unverified)
        assertEquals("5.0.5", (decision as UltraEnvUpgrade.Unverified).to)
        assertTrue("待确认必须允许用户点一次重装", UltraEnvironmentUpgrade.upgradeAllowed(decision))

        val port = FakePort()
        val outcome = UltraEnvironmentInstaller.install(
            port,
            UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            attachments(),
        )
        assertEquals("Unverified 走到安装路径时必须真的提交一次载荷", 1, port.installEnvironmentCalls)
        assertTrue(outcome.ok)
    }

    @Test
    fun refusalClassesStillProduceNoWriteCall() {
        val blocked = listOf(
            "quiescing" to sameUpstreamNewBuild.copy(quiescing = true),
            "suspended" to sameUpstreamNewBuild.copy(suspended = true),
            "not running" to sameUpstreamNewBuild.copy(running = false),
            "manager downgrade" to sameUpstreamNewBuild.copy(
                bundledAppVersion = "5.0.3",
                installedAppVersion = "5.0.4",
            ),
        )
        blocked.forEach { (label, facts) ->
            val decision = decide(facts)
            assertEquals("$label must stay None", UltraEnvUpgrade.None, decision)
            assertFalse("$label must not write", UltraEnvironmentUpgrade.upgradeAllowed(decision))
        }
        // 本次开机已经提交过：等重启，不允许第二次写。
        assertFalse(UltraEnvironmentUpgrade.upgradeAllowed(UltraEnvUpgrade.Rebooting("5.0.5")))
    }

    @Test
    fun upgradeWiringKeepsTheSingleVerifiedSubmitPath() {
        val upgrade = source("UltraEnvironmentUpgrade.kt")
        assertTrue(
            "upgrade() 必须走升级意图（Running 不得短路）",
            upgrade.contains("Intent.UpgradeToBundledBuild"),
        )
        val guard = upgrade.indexOf("if (!fullInstall && !upgradeAllowed(UltraEnvUpgradePolicy.decide(readObservation(app, {}, binding).first)))")
        val submit = upgrade.indexOf("UltraEnvironmentInstaller.install(")
        assertTrue("拒绝判定必须在写设备之前（guard=$guard submit=$submit）", guard >= 0 && guard < submit)
        assertTrue(
            "只有 verified 才算 SUBMITTED",
            upgrade.contains("fun resultOf(outcome: UltraEnvironmentInstaller.Outcome?): Result"),
        )
        val panel = uiSource("UltraEnvironmentPanel.kt")
        assertTrue(
            "显式升级独立于旧身份与版本记录",
            panel.contains("UltraEnvironmentUpgrade.installBundled(context)"),
        )
    }

    // ── h) 本次开机已提交 → Rebooting ────────────────────────────────────

    @Test
    fun submittedThisBootWaitsForTheRestart() {
        assertEquals(
            UltraEnvUpgrade.Rebooting("5.0.5"),
            decide(sameUpstreamNewBuild.copy(submittedThisBoot = true)),
        )
        assertEquals(
            UltraEnvUpgrade.Rebooting("5.0.5"),
            decide(
                UltraEnvFacts(
                    running = true,
                    installedVersion = "5.0.5",
                    bundledVersion = "5.0.5",
                    submittedThisBoot = true,
                ),
            ),
        )
    }

    // ── i) 代次失效：安装成功必须递增客户端环境代次（生产路径）────────────

    @Test
    fun installSuccessBumpsTheEnvironmentGenerationOnTheProductionPath() {
        val installer = source("UltraEnvironmentInstaller.kt")
        assertTrue(
            "生产适配必须真的调用 install_maplesu_environment（Boot 模式）",
            installer.contains("client.installEnvironment(") &&
                installer.contains("if (intent == Intent.RefreshToBundledBuild) \"Refresh\" else \"Boot\", \"\""),
        )
        val client = source("UltraRuntimeClient.kt")
        val installCall = client.indexOf("NativeBridge.installEnvironment(")
        assertTrue("installEnvironment 必须走 NativeBridge.installEnvironment", installCall >= 0)
        val bump = client.indexOf(
            "if (receipt.succeeded) synchronized(lock) { generation += 1 }",
            installCall,
        )
        assertTrue(
            "安装成功必须递增 environmentGeneration（UltraRuntimeClient.installEnvironment）",
            bump > installCall,
        )
    }

    // ── 身份回读：typed 回执 / 解析 / 核对（纯函数）─────────────────────



    @Test
    fun readbackReceiptRequiresFullBytesAndSuccessfulRootProof() {
        val path = "/data/0123456789abcdef/init"
        assertNull(UltraInstalledIdentityProbe.parseReadback(null, path))
        for ((field, value) in listOf(
            "bytesRead" to 1_163_327L, "complete" to false, "rootVerified" to false,
            "path" to "$path/extra", "path" to "relative/path",
            "sha256" to "C".repeat(64), "sha256" to "prefix${"c".repeat(64)}",
            "sha256" to "${"c".repeat(64)}suffix", "sha256" to "g".repeat(64),
        )) {
            val receipt = readbackReceipt { getJSONObject("data").put(field, value) }
            assertNull(field, UltraInstalledIdentityProbe.parseReadback(receipt, path))
        }
        assertNull(UltraInstalledIdentityProbe.parseReadback(readbackReceipt(), "/data/xxxxxxxxxxxxxxxx/init"))
        assertNull(UltraInstalledIdentityProbe.parseReadback(readbackReceipt(), "/data/adb/maplesu/init"))
    }

    @Test
    fun readbackReceiptChecksSlotBoundsWithoutOverflow() {
        val path = "/data/0123456789abcdef/init"
        for ((size, offset) in listOf(4096L to 0L, 8192L to 0L)) {
            val receipt = readbackReceipt {
                getJSONObject("data").put("size", size).put("bytesRead", size).put("normalizedOffset", offset)
            }
            assertEquals(size, UltraInstalledIdentityProbe.parseReadback(receipt, path)?.size)
        }
        for ((size, offset) in listOf(4095L to 0L, 8192L to 4097L,
            9_007_199_254_740_992L to 0L, 8192L to Long.MAX_VALUE)) {
            val receipt = readbackReceipt {
                getJSONObject("data").put("size", size).put("bytesRead", size).put("normalizedOffset", offset)
            }
            assertNull("size=$size offset=$offset", UltraInstalledIdentityProbe.parseReadback(receipt, path))
        }
    }

    @Test
    fun readbackReceiptRejectsCoercedFieldsAndUntrustedRawEnvelope() {
        val path = "/data/0123456789abcdef/init"
        for ((field, value) in listOf(
            "abiVersion" to "1", "complete" to "true", "rootVerified" to "true",
            "size" to "1163328", "size" to 1_163_328.5, "bytesRead" to "1163328",
            "normalizedOffset" to "0",
        )) {
            val receipt = readbackReceipt { getJSONObject("data").put(field, value) }
            assertNull(field, UltraInstalledIdentityProbe.parseReadback(receipt, path))
        }
        for ((field, value) in listOf(
            "schemaVersion" to "1", "bridgeVersion" to "1", "bridgeVersion" to 2,
            "backend" to "other", "operation" to "run_root_cmd", "ok" to "true",
            "ok" to false, "sdkError" to "FAILED", "sdkErrorCode" to "0",
        )) {
            val receipt = readbackReceipt { put(field, value) }
            assertNull(field, UltraInstalledIdentityProbe.parseReadback(receipt, path))
        }
        val success = readbackReceipt()
        val floatingSize = success.raw.replace("\"size\":1163328", "\"size\":1163328.0")
        assertNull(UltraInstalledIdentityProbe.parseReadback(success.copy(raw = floatingSize), path))
        for (field in listOf("rootVerified", "complete", "sha256", "bytesRead")) {
            val receipt = readbackReceipt { getJSONObject("data").remove(field) }
            assertNull(field, UltraInstalledIdentityProbe.parseReadback(receipt, path))
        }
        assertNull(UltraInstalledIdentityProbe.parseReadback(success.copy(raw = "{}"), path))
        assertNull(UltraInstalledIdentityProbe.parseReadback(success.copy(raw = "not json"), path))
        assertNull(UltraInstalledIdentityProbe.parseReadback(success.copy(ok = false), path))
    }

    @Test
    fun ownerReadbackRequiresAnAuthenticatedResolvedNamespace() {
        val path = "/data/0123456789abcdef/init"
        fun ownerReceipt(change: JSONObject.() -> Unit = {}) = readbackReceipt {
            getJSONObject("data").put("ownerMode", true).put("namespaceRequested", true).put("namespaceResolved", true)
                .put("ownerFullUid", 12345).put("ownerEpoch", 1L)
                .put("ownerHostLow", "0000000000000001").put("ownerHostHigh", "0000000000000000")
                .put("deployment", JSONObject().put("attempted", true).put("replyObserved", true).put("completed", true)
                    .put("error", "0").put("command", 27).put("status", 0).put("effects", 0)
                    .put("result", "0000000000000018").put("handle", "0000000000000000"))
                .apply(change)
        }
        assertEquals(path, UltraInstalledIdentityProbe.parseOwnerReadback(ownerReceipt(), 12345)?.path)
        for ((field, value) in listOf("ownerMode" to false, "namespaceRequested" to false, "namespaceResolved" to false,
            "namespaceResolved" to "true", "path" to "/data/adb/maplesu/init", "rootVerified" to false,
            "ownerEpoch" to 0, "ownerFullUid" to -1, "ownerHostLow" to "0000000000000000")) {
            assertNull(field, UltraInstalledIdentityProbe.parseOwnerReadback(ownerReceipt { put(field, value) }, 12345))
        }
        for ((field, value) in listOf("completed" to false, "error" to "-13", "effects" to 1,
            "result" to "0000000000000000", "command" to 26)) {
            val invalid = ownerReceipt { getJSONObject("deployment").put(field, value) }
            assertNull(field, UltraInstalledIdentityProbe.parseOwnerReadback(invalid, 12345))
        }
        assertNull(UltraInstalledIdentityProbe.parseOwnerReadback(readbackReceipt(), 12345))
    }

    private fun readbackReceipt(change: JSONObject.() -> Unit = {}): NativeReceipt =
        checkNotNull(NativeReceipt.parse(JSONObject()
            .put("schemaVersion", 1).put("backend", UltraRuntimeClient.BACKEND).put("bridgeVersion", 1)
            .put("operation", "read_installed_helper").put("ok", true).put("sdkError", "OK").put("sdkErrorCode", 0)
            .put("data", JSONObject().put("abiVersion", 1).put("complete", true).put("rootVerified", true)
                .put("sha256", "c".repeat(64)).put("path", "/data/0123456789abcdef/init")
                .put("size", 1_163_328L).put("bytesRead", 1_163_328L).put("normalizedOffset", 0L))
            .apply(change).toString()))

    @Test
    fun persistedIdentityNeverProvesAnUpgrade() {
        val sha = "d".repeat(64)
        val persisted = InstalledIdentity(
            helperSha256 = sha,
            helperPath = "/data/adb/maplesu/init",
            helperSize = 1163328L,
            buildId = newBuild,
            bootId = "boot-1",
            source = Source.Persisted,
        )
        val stale = UltraInstalledIdentityProbe.verifyAgainstBundled(
            persisted,
            bundledSha = sha,
            bundledBuildId = newBuild,
        )
        assertFalse("持久化身份只能说明上次装过，不能证明这次升级生效", stale.verified)
        assertEquals("IDENTITY_PERSISTED_ONLY", stale.reason)

        val readback = persisted.copy(source = Source.Readback)
        assertTrue(
            UltraInstalledIdentityProbe.verifyAgainstBundled(readback, bundledSha = sha, bundledBuildId = null).verified,
        )
        val mismatch = UltraInstalledIdentityProbe.verifyAgainstBundled(
            readback,
            bundledSha = "e".repeat(64),
            bundledBuildId = null,
        )
        assertFalse(mismatch.verified)
        assertEquals("HELPER_SHA_MISMATCH", mismatch.reason)
    }

    @Test
    fun matchingHelperShaAloneCannotProveTheInstalledBuild() {
        // helper 字节在不同构建间可能完全相同（本次 5.0.5 的 m052 payload 就是继承的），
        // 所以「摘要等于随包 helper」不能反推「设备上是这一版」——只有安装记录能。
        assertNull(UltraInstalledIdentityProbe.buildIdFor(newHelper, null))
        assertNull(
            "记录里的摘要不同就不能借用构建号",
            UltraInstalledIdentityProbe.buildIdFor(oldHelper, readbackRecord),
        )
        assertEquals(
            newBuild,
            UltraInstalledIdentityProbe.buildIdFor(newHelper, readbackRecord),
        )
    }

    @Test
    fun sdkReportedVersionIsShownVerbatimWithoutAnyMappingTable() {
        // 外层构建标签不得覆盖已读到的 SDK 环境版本。
        assertEquals("5.0.5", UltraEnvironmentVersion.displayOf("5.0.5", "maplesu-ultra-5.0.5-r1"))
        assertEquals("4.6.5", UltraEnvironmentVersion.displayOf("4.6.5", "maplesu-ultra-5.0.5-r1"))
        assertEquals("4.6.5", UltraEnvironmentVersion.displayOf(" 4.6.5 ", "maplesu-ultra-5.0.5-r1"))
        assertEquals("4.6.5", UltraEnvironmentVersion.displayOf("4.6.5", null))
        assertEquals("4.6.5", UltraEnvironmentVersion.displayOf("4.6.5", "not-a-build-id"))
        assertEquals("4.6.2", UltraEnvironmentVersion.displayOf("4.6.2", "maplesu-ultra-5.0.5-r1"))
        // 构建号只在版本读不到时兜底
        assertEquals("5.0.5", UltraEnvironmentVersion.displayOf(null, "maplesu-ultra-5.0.5-r12"))
        assertNull(UltraEnvironmentVersion.displayOf(null, null))
    }

    @Test fun buildVersionFallbackStillOffersTheSdkUpgrade() {
        assertEquals(UltraEnvUpgrade.Available("5.0.5", "5.0.5"),
            decide(sameUpstreamNewBuild.copy(installedVersion = null, bundledVersion = null)))
    }

    @Test fun explicitUpgradeSkipsTheRedundantRunningStateQuery() {
        val port = FakePort()
        UltraEnvironmentInstaller.install(port, UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild, attachments())
        assertEquals(0, port.environmentStateCalls)
        assertEquals(1, port.installEnvironmentCalls)
    }

    @Test fun helperReadbackAndIdentityCommitPrecedeSlowPostInstallAttachments() {
        val order = mutableListOf<String>()
        val base = attachments()
        val outcome = UltraEnvironmentInstaller.install(FakePort(),
            UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            UltraEnvironmentInstaller.Attachments(
                whitelist = { order += "whitelist"; base.whitelist() },

                verify = { order += "verify"; base.verify() },
                persist = { order += "persist" },
                refreshProbe = { order += "refresh" },
            ))
        assertTrue(outcome.ok && outcome.verified)
        assertEquals(listOf("verify", "persist", "whitelist", "refresh"), order)
    }

    @Test fun aSubmittedButUnverifiedInstallKeepsItsMarkerAgainstDuplicateWrites() {
        var marker = true
        val unverified = UltraEnvironmentInstaller.Outcome(true, "OK", verified = false)
        assertEquals(unverified, UltraEnvironmentUpgrade.submitMarked({ unverified }, { marker = false }))
        assertTrue(marker)
    }

    @Test fun anInterruptedInstallKeepsItsMarkerBecauseTheWriteMayHaveCompleted() {
        var marker = true
        val failed = UltraEnvironmentUpgrade.submitMarked({ throw IllegalStateException("lost connection") }, { marker = false })
        assertEquals("EXCEPTION", failed?.sdkError)
        assertTrue(failed?.reason.orEmpty().contains("lost connection"))
        assertTrue(failed?.submissionUnknown == true)
        assertTrue(marker)
        UltraEnvironmentUpgrade.submitMarked({ UltraEnvironmentInstaller.Outcome(false, "EXCEPTION") }, { marker = false })
        assertTrue(marker)
    }

    @Test fun aTransportFailureAfterSubmissionKeepsTheMarkerAndTheOriginalReason() {
        var marker = true
        val failure = UltraEnvironmentInstaller.Outcome(false, "MAPLESU_BRIDGE_UNPARSEABLE",
            reason = "partial native receipt", sdkErrorCode = -1001, submissionUnknown = true)
        assertEquals(failure, UltraEnvironmentUpgrade.submitMarked({ failure }, { marker = false }))
        assertTrue(marker)
        val diagnostic = UltraEnvironmentUpgrade.failureReasonOf(failure).orEmpty()
        assertTrue(diagnostic.contains("partial native receipt"))
        assertTrue(diagnostic.contains("-1001"))
    }

    @Test fun installerDistinguishesAdmissionRejectionFromAnUnfinishedNativeWrite() {
        for ((error, completed, uncertain) in listOf(
            Triple("MAPLESU_SDK_BUSY", false, false),
            Triple("MAPLESU_BRIDGE_UNPARSEABLE", false, true),
            Triple("ERR_MODULE_IO", false, true),
            Triple("ERR_MODULE_IO", true, true),
        )) {
            val receipt = NativeReceipt(1, UltraRuntimeClient.BACKEND, 1,
                "install_maplesu_environment", false, error, -1, "original failure", null, "{}", completed)
            val port = object : UltraEnvironmentInstaller.InstallPort {
                override fun environmentState(): NativeReceipt = throw IllegalStateException("Explicit update must not query state")
                override fun submitInstall() = receipt
            }
            val outcome = UltraEnvironmentInstaller.install(port,
                UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild, attachments())
            assertFalse(outcome.ok)
            assertFalse("A failed SDK receipt has no verified installation readback", outcome.verified)
            assertEquals(error, outcome.sdkError)
            assertEquals("original failure", outcome.reason)
            assertEquals(uncertain, outcome.submissionUnknown)
            var marker = true
            UltraEnvironmentUpgrade.submitMarked({ outcome }, { marker = false })
            assertEquals(uncertain, marker)
        }
    }



    @Test fun ownerUpgradeObservationsAreBoundToTheFullIdentityTuple() {
        val owner = UltraRuntimeClient.ManagerOwnerIdentity(112345, 3, "00000000000000010000000000000002")
        val identity = UltraEnvironmentUpgrade.observationIdentity(owner)
        assertTrue(identity != null)
        assertNull(UltraEnvironmentUpgrade.observationIdentity(null))
        for (changed in listOf(owner.copy(fullUid = 12345), owner.copy(epoch = 4),
            owner.copy(hostInstance = "00000000000000010000000000000003"))) {
            assertFalse(identity == UltraEnvironmentUpgrade.observationIdentity(changed))
        }

    }

    @Test fun explicitInstallationReportsAuthorityFailureAfterVerifiedHelperWithoutGrantingAroundIt() {
        val events = mutableListOf<String>()
        val base = attachments()
        val outcome = UltraEnvironmentInstaller.install(FakePort(),
            UltraEnvironmentInstaller.Intent.InstallToBundledBuild,
            UltraEnvironmentInstaller.Attachments(
                whitelist = { events += "whitelist"; base.whitelist() },

                verify = { events += "verify"; base.verify() },
                persist = { events += "persist" },
                initializeAuthority = { events += "authority"; "AUTH_DISABLED" },
            ))
        assertTrue(outcome.ok)
        assertFalse(outcome.verified)
        assertEquals("UNVERIFIED:AUTHORITY:AUTH_DISABLED", outcome.detail)
        assertEquals(listOf("verify", "persist", "authority"), events)
    }

    @Test fun fullUpgradeInitializesOnlyAnAbsentAuthorityAndReuseDoesNotRunMigration() {
        for (intent in listOf(UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
            UltraEnvironmentInstaller.Intent.ReuseRunning)) {
            val base = attachments()
            var checks = 0
            val outcome = UltraEnvironmentInstaller.install(FakePort(), intent,
                UltraEnvironmentInstaller.Attachments(base.whitelist,
                    verify = base.verify, persist = base.persist,
                    initializeAuthority = { checks++; null }))
            assertTrue(outcome.ok && outcome.verified)
            assertEquals(if (intent == UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild) 1 else 0, checks)
        }
    }

    @Test fun anUnverifiedHelperNeverInitializesADurableAuthority() {
        val base = attachments(IdentityVerification(false, "HELPER_MISMATCH", null))
        val outcome = UltraEnvironmentInstaller.install(FakePort(),
            UltraEnvironmentInstaller.Intent.InstallToBundledBuild,
            UltraEnvironmentInstaller.Attachments(base.whitelist,
                verify = base.verify, persist = base.persist,
                initializeAuthority = { error("The helper was not verified") }))
        assertTrue(outcome.ok)
        assertFalse(outcome.verified)
        assertEquals("UNVERIFIED:HELPER_MISMATCH", outcome.detail)
    }

    @Test fun aDefinitiveSdkRejectionAllowsRetryAndSuccessfulReadbackKeepsTheMarker() {
        var marker = true
        UltraEnvironmentUpgrade.submitMarked({ UltraEnvironmentInstaller.Outcome(false, "NOT_INSTALLED") }, { marker = false })
        assertFalse(marker)
        marker = true
        UltraEnvironmentUpgrade.submitMarked({ UltraEnvironmentInstaller.Outcome(true, "OK", verified = true) }, { marker = false })
        assertTrue(marker)
    }

    @Test fun durableFailureDiagnosticsRedactCredentialsAndPrivateSdkPaths() {
        val key = "A".repeat(48)
        val outcome = UltraEnvironmentInstaller.Outcome(false, "IO", reason =
            "$key /data/abcdefghijklmnop/file\nsecond line")
        val reason = UltraEnvironmentUpgrade.diagnosticReasonOf(outcome)
        assertFalse(reason.contains(key))
        assertFalse(reason.contains("abcdefghijklmnop"))
        assertFalse(reason.contains('\n'))
        assertTrue(reason.contains("SDK:IO"))
        assertTrue(reason.contains("<REDACTED-KEY>"))
        assertTrue(reason.contains("/data/<private>/file"))
    }

    // ── fakes ────────────────────────────────────────────────────────────

    /** 假 SDK 安装面：只数调用次数，并回答固定的 Running 状态。 */
    private class FakePort(private val state: String = "Running") : UltraEnvironmentInstaller.InstallPort {
        var environmentStateCalls = 0
        var installEnvironmentCalls = 0

        override fun environmentState(): NativeReceipt {
            environmentStateCalls += 1
            return ultraReceipt("get_maplesu_environment_state", JSONObject().put("state", state))
        }

        override fun submitInstall(): NativeReceipt {
            installEnvironmentCalls += 1
            return ultraReceipt("install_maplesu_environment", JSONObject().put("state", "Running"))
        }
    }

    private fun attachments(
        verification: IdentityVerification = IdentityVerification(verified = true, reason = null, buildId = newBuild),
        persisted: MutableList<InstalledIdentity> = mutableListOf(),
    ): UltraEnvironmentInstaller.Attachments = UltraEnvironmentInstaller.Attachments(
        whitelist = {
            UltraSelfWhitelist.Result(
                inList = true,
                added = false,
                alreadyPresent = true,
                attempted = false,
                listVerified = true,
            )
        },

        verify = {
            IdentityObservation(
                // 回读本身反查不到构建号（磁盘上没有该字段）：构建号只能由 verified 结论盖章。
                InstalledIdentity(
                    helperSha256 = newHelper,
                    helperPath = "/data/adb/maplesu/init",
                    helperSize = 1163328L,
                    buildId = null,
                    bootId = "boot-1",
                    source = Source.Readback,
                ),
                verification,
            )
        },
        persist = { identity -> persisted += identity },
        refreshProbe = {},
    )

    private fun source(name: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val manager = if (File(cwd, "src/main/java").isDirectory) cwd else File(cwd, "manager")
        return File(manager, "src/main/java/dev/maplesu/manager/runtime/ultra/$name").readText()
    }

    private fun uiSource(name: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val manager = if (File(cwd, "src/main/java").isDirectory) cwd else File(cwd, "manager")
        return File(manager, "src/main/java/com/maplesu/core/ui/screen/settings/$name").readText()
    }
}

/**
 * 回执工厂放在**文件级**：`FakePort` 是 static 嵌套类，不能把外部类实例当接收者。
 */
private fun ultraReceipt(operation: String, data: JSONObject? = null): NativeReceipt = NativeReceipt(
    schemaVersion = 1,
    backend = "maplesu_ultra",
    bridgeVersion = 1,
    operation = operation,
    ok = true,
    sdkError = "OK",
    sdkErrorCode = 0,
    reason = null,
    data = data,
    raw = "{}",
)
