package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.litehidden.ParasiteMarkers
import dev.maplesu.manager.litehidden.ParasitePaths
import dev.maplesu.manager.litehidden.ParasitePolicy
import dev.maplesu.manager.litehidden.ParasiteScripts
import dev.maplesu.manager.litehidden.RecoveryIdentityScripts
import dev.maplesu.manager.litehidden.HostManifestCodec
import dev.maplesu.manager.litehidden.HostManifest
import dev.maplesu.manager.liteweb.LiteHiddenPort
import dev.maplesu.manager.liteweb.LiteHiddenResult
import dev.maplesu.manager.liteweb.LiteWebRestoreStep
import dev.maplesu.manager.liteweb.LiteWebUnhideCodes
import dev.maplesu.manager.liteweb.LiteWebUnhidePort
import dev.maplesu.manager.liteweb.LiteWebUnhideSteps
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * daemon 侧「完整解除寄生」实现（纯 JDK，可注入 fake root 做主机测试）。
 *
 * 为什么必须在 daemon 里做：隐藏生效后 App 的隐藏入口被抑制，`POST /api/activate` 也 501
 *（2026-10-31 真机缺陷 2），用户在产品内没有别的退路。daemon 本身是 root，这几步需要的
 * 动作它都能执行：
 *
 *  1. `restore_host_so`：按 `/data/adb/maplesu/recovery/host/<pkg>` 下每个 `.orig` 把宿主库逐字节写回
 *     （复用 [ParasiteScripts.restore]：tmp + sha 复核 + `mv`，失败即停）；
 *  2. `remove_loader`：复核 `libmaplesu_parasite.so` 与 `parasite-start.sh` 真的不存在
 *     （不把"宿主库还原成功"当成"loader 也没了"）；
 *  3. `revoke_su_auth`：撤销宿主的 su 授权（Ultra 环境 = SDK `remove_su_auth_list` + 回读，
 *     见 [UltraSdkSuAuthRevoke]；没有 SDK 能力时**显式失败**，不谎称已撤销）；
 *  4. `cleanup_stale_backups`（**advisory**，仅在存在陈旧残留时出现）：把没有完整备份的
 *     历史目录改名进 `recovery/host-quarantine-<date>/`（保留证据，绝不删除）；
 *  5. `stop_daemon`：写 `lite_hidden=0` + 回读，然后**先回响应再退出**（[DaemonSelfStop]）。
 *
 * 失败语义（与 App 侧 `ProductionHiddenModeBackend.deactivate` 一致）：
 * - 失败即停，**不执行后面的步骤**（尤其是绝不在撤销授权失败后杀掉 daemon —— 那会让用户
 *   连重试入口都没有）；
 * - 已完成的步骤保留（宿主库已还原就是已还原），不回滚；
 * - 每一步独立 `ok/error`，前端据 `failedStep/code/retryable` 展示，不做"整体成功"的假象；
 * - **advisory 步**（隔离陈旧残留）失败不改写解除结论（宿主库/loader/授权都已处理完），
 *   但仍如实记一步 `ok=false` —— 既不阻塞用户，也不假装清理成功。
 *
 * 备份完整性判定（真机 D2 的修法）：有可解析的 `host.manifest`、≥1 条 `file=` 记录，
 * 且 `.orig` 覆盖全部记录才参与宿主判定；写回前后逐文件核对原始 sha。无清单的历史目录
 * 视为陈旧残留并保留隔离；已有清单却损坏或缺原始库时保留恢复服务，绝不跳过宿主还原。
 * 多份完整备份逐宿主核验并还原；任一步失败都保留备份和恢复服务，成功后统一归档。
 */
class DaemonParasiteUnhide(
    private val shell: DaemonShell,
    private val hiddenState: LiteHiddenPort,
    private val revokeSuAuth: DaemonSuAuthRevoke,
    /** 停自己：true = 已安排在本次响应之后退出。false → 本步失败（不谎报停成功）。 */
    private val selfStop: () -> Boolean,
    private val recoveryRoot: String = ParasitePaths.RECOVERY,
    private val logger: (String) -> Unit = {},
    /** 隔离目录的日期戳（`host-quarantine-<stamp>`）；注入以便主机测试断言固定的目录名。 */
    private val quarantineStamp: () -> String = { LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) },
    /** Full restoration retains retry material until its HTTP response has been flushed. */
    private val clearIdentityOnSuccess: Boolean = true,
) : LiteWebUnhidePort {

    override fun deactivate(): List<LiteWebRestoreStep> {
        val steps = ArrayList<LiteWebRestoreStep>(5)
        val inventory = inspectBackups()
        if (inventory is BackupInventory.Unavailable) {
            steps += fail(
                LiteWebUnhideSteps.RESTORE_HOST_SO,
                LiteWebUnhideCodes.RESTORE_HOST_SO_FAILED,
                inventory.detail,
            )
            return steps
        }
        val found = inventory as BackupInventory.Found
        // A previous session may already be restored while another host is still active.
        // Restore each manifest to its own directory; do not select or discard a backup.
        val hosts: List<String?> = if (found.complete.isEmpty()) listOf(null) else found.complete
        val restored = hosts.map { host ->
            val outcome = restoreHost(host, found)
            if (!outcome.step.ok) {
                steps += outcome.step
                return steps
            }
            outcome
        }
        steps += ok(LiteWebUnhideSteps.RESTORE_HOST_SO, restored.joinToString(";") { it.step.detail })

        val removed = hosts.mapIndexed { index, host ->
            val step = removeLoader(host, restored[index].libraryDir)
            if (!step.ok) {
                steps += step
                return steps
            }
            step
        }
        steps += ok(LiteWebUnhideSteps.REMOVE_LOADER, removed.joinToString(";") { it.detail })

        val revoked = hosts.map { host ->
            val step = revoke(host)
            if (!step.ok) {
                steps += step
                return steps
            }
            step
        }
        steps += ok(LiteWebUnhideSteps.REVOKE_SU_AUTH, revoked.joinToString(";") { it.detail })

        // 4. 隔离陈旧残留（有才做；advisory：失败不阻塞解除，见类头注释）。
        if (found.stale.isNotEmpty()) {
            steps += quarantineStale(found.stale)
        }

        // 5. 停 daemon（最后一步，会杀掉自己；先回响应再退）。
        steps += stopDaemon()
        if (!steps.last().ok) return steps

        // 解除成功才清身份记录；失败保留，重试时 daemon 仍需核对当前安装与撤销目标。
        if (clearIdentityOnSuccess && !removeIdentity()) logger("hidden.unhide identity cleanup failed (retry will reuse it)")
        return steps
    }

    /** Move verified-restored backups out of the active guard tree; failed restores never call this. */
    fun archiveRestoredBackups(): LiteWebRestoreStep {
        val name = "archive_host_backup"
        if (runCatching { hiddenState.currentHidden() }.getOrNull() != false) {
            return fail(name, "RESTORED_BACKUP_ARCHIVE_FAILED", "hidden_not_off")
        }
        val found = inspectBackups() as? BackupInventory.Found
            ?: return fail(name, "RESTORED_BACKUP_ARCHIVE_FAILED", "backup_inventory_unverified")
        if (found.complete.isEmpty()) return ok(name, "no_active_backup")
        val result = runScript(ParasiteScripts.quarantineHostBackups("restored-${quarantineStamp()}", found.complete))
        val moved = result.output.lineSequence().any {
            it.trim().startsWith("${ParasiteMarkers.QUARANTINED} n=${found.complete.size};")
        }
        if (result.code != 0 || !moved) return fail(name, "RESTORED_BACKUP_ARCHIVE_FAILED", failureDetail(result))
        val after = inspectBackups() as? BackupInventory.Found
        if (after == null || after.complete.isNotEmpty()) return fail(name, "RESTORED_BACKUP_ARCHIVE_FAILED", "backup_still_active")
        return ok(name, "archived=${found.complete.size}")
    }

    // ------------------------------------------------------------------ 步骤 1

    private class RestoreOutcome(val step: LiteWebRestoreStep, val libraryDir: String = "")

    /** `host/<pkg>` 一个目录的完整性事实（来自 [ParasiteScripts.listHostBackupFacts]）。 */
    private class BackupFact(
        val name: String,
        val marker: Boolean,
        val manifestBytes: Long,
        val manifestEntries: Int,
        val origFiles: Int,
    ) {
        /**
         * 完整备份 = **可解析的清单**（≥1 条 `file=` 记录）且 `.orig` 数量覆盖全部记录。
         *
         * `BACKUP_COMPLETE` 只作诊断用：真机现场的 4 个陈旧目录既无标记也无清单，而 App 侧
         * 解除只认「标记或清单」。这里以清单为准（清单是「每个库的 orig/patched sha」的载体，
         * 缺它就没有可核对的恢复材料）。
         */
        val complete: Boolean
            get() = manifestBytes > 0 && manifestEntries >= 1 && origFiles >= manifestEntries
    }

    /**
     * 备份目录发现：区分**完整备份**与**陈旧残留**。
     *
     * 旧实现（真机 D2 根因）只数目录名，`>1` 即 `HIDDEN_HOST_BACKUP_AMBIGUOUS` —— 现场 4 个
     * 10-30 遗留的半截目录把 WebUI 一键解除钉死成永久 409。
     */
    private sealed interface BackupInventory {
        /** 完整备份各自绑定宿主清单；[stale] 是可隔离的陈旧残留（不阻塞解除）。 */
        data class Found(
            val complete: List<String>,
            val stale: List<String>,
            /** 残留里「有完成标记但清单已丢」的个数（只进 detail，供真机诊断）。 */
            val staleWithMarker: Int = 0,
        ) : BackupInventory

        data class Unavailable(val detail: String) : BackupInventory
    }

    private fun inspectBackups(): BackupInventory {
        val listing = runScript(ParasiteScripts.listHostBackupFacts())
        if (listing.code != 0) return BackupInventory.Unavailable("backup_list_failed;code=${listing.code}")
        val facts = parseFacts(listing.output)
        if (facts.any { it.manifestBytes > 0 && (it.manifestEntries == 0 || it.origFiles < it.manifestEntries) }) {
            return BackupInventory.Unavailable("original_backup_incomplete")
        }
        val complete = facts.filter { it.complete }.map { it.name }
        val staleFacts = facts.filterNot { it.complete }
        val stale = staleFacts.map { it.name }
        return BackupInventory.Found(complete, stale, staleFacts.count { it.marker })
    }

    /**
     * 解析事实行（只认 `PARASITE_BK|name|marker|bytes|entries|origs`，其余行一律忽略）。
     *
     * 目录名来自 root 的 `ls`，进任何脚本前必须过包名白名单 —— 非法名直接丢弃（不是"猜"）。
     */
    private fun parseFacts(output: String): List<BackupFact> {
        val facts = LinkedHashMap<String, BackupFact>()
        output.lineSequence().forEach { line ->
            val text = line.trim()
            if (!text.startsWith(ParasiteMarkers.BACKUP_FACT)) return@forEach
            val parts = text.removePrefix(ParasiteMarkers.BACKUP_FACT).split('|')
            if (parts.size != 5) return@forEach
            val name = parts[0].trim()
            if (!PACKAGE.matches(name)) return@forEach
            val fact = BackupFact(
                name = name,
                marker = parts[1].trim() == "1",
                manifestBytes = parts[2].trim().toLongOrNull() ?: 0L,
                manifestEntries = parts[3].trim().toIntOrNull() ?: 0,
                origFiles = parts[4].trim().toIntOrNull() ?: 0,
            )
            facts[name] = fact
        }
        return facts.values.toList()
    }

    private fun restoreHost(hostPackage: String?, inventory: BackupInventory.Found): RestoreOutcome {
        if (hostPackage == null) {
            val detail = if (inventory.stale.isEmpty()) {
                "no_backup"
            } else {
                "skipped:no-complete-backup;stale=${inventory.stale.size}" +
                    if (inventory.staleWithMarker > 0) ";marker_only=${inventory.staleWithMarker}" else ""
            }
            return RestoreOutcome(ok(LiteWebUnhideSteps.RESTORE_HOST_SO, detail))
        }
        val libDir = runScript(ParasiteScripts.readLibDir(hostPackage)).output.trim()
        if (!ParasitePolicy.isAbsolutePath(libDir)) {
            return RestoreOutcome(
                fail(
                    LiteWebUnhideSteps.RESTORE_HOST_SO,
                    LiteWebUnhideCodes.RESTORE_HOST_SO_FAILED,
                    "libdir_unreadable",
                ),
            )
        }
        val label = runScript(ParasiteScripts.readLabel(hostPackage)).output.trim()
        val manifest = HostManifestCodec.decode(runScript(ParasiteScripts.readManifest(hostPackage)).output)
        if (manifest == null || manifest.packageName != hostPackage || manifest.libraryDir != libDir ||
            !verifyOriginalFiles(manifest, backup = true)
        ) {
            return RestoreOutcome(fail(LiteWebUnhideSteps.RESTORE_HOST_SO,
                LiteWebUnhideCodes.RESTORE_HOST_SO_FAILED, "original_backup_unverified"))
        }
        val restore = runScript(ParasiteScripts.restore(hostPackage, libDir, label))
        val count = restore.output.lineSequence().count {
            it.startsWith(ParasiteMarkers.RESTORED) || it.startsWith(ParasiteMarkers.RESTORE_SKIP)
        }
        if (restore.code != 0 || !restore.output.contains(ParasiteMarkers.RESTORE_OK) || count < manifest.files.size ||
            !verifyOriginalFiles(manifest, backup = false)
        ) {
            return RestoreOutcome(
                fail(
                    LiteWebUnhideSteps.RESTORE_HOST_SO,
                    LiteWebUnhideCodes.RESTORE_HOST_SO_FAILED,
                    failureDetail(restore),
                ),
            )
        }
        return RestoreOutcome(
            ok(LiteWebUnhideSteps.RESTORE_HOST_SO, "restored=$count;host=$hostPackage"),
            libraryDir = libDir,
        )
    }

    private fun verifyOriginalFiles(manifest: HostManifest, backup: Boolean): Boolean {
        val marker = if (backup) "MAPLESU_RESTORE_ORIGINALS_OK" else "MAPLESU_RESTORE_TARGETS_OK"
        val directory = if (backup) ParasitePaths.hostBackupDir(manifest.packageName) else manifest.libraryDir
        val script = buildString {
            appendLine("D='${directory.replace("'", "'\\''")}'")
            manifest.files.forEach { file ->
                if (!file.originalSha256.matches(Regex("^[0-9a-f]{64}$"))) return false
                val name = file.name + if (backup) ".orig" else ""
                appendLine("N=\$(sha256sum \"\$D/$name\" 2>/dev/null) || exit 1")
                appendLine("N=\${N%% *}; [ \"\$N\" = '${file.originalSha256}' ] || exit 1")
            }
            if (!backup) appendLine("[ ! -e \"\$D/${ParasitePaths.LOADER_NAME}\" ] || exit 1")
            appendLine("echo $marker")
        }
        val verified = runScript(script)
        return verified.code == 0 && verified.output.lineSequence().map(String::trim).count { it == marker } == 1
    }

    // ------------------------------------------------------------------ 步骤 2

    private fun removeLoader(hostPackage: String?, libraryDir: String): LiteWebRestoreStep {
        if (hostPackage == null || libraryDir.isEmpty()) {
            return ok(LiteWebUnhideSteps.REMOVE_LOADER, "no_backup")
        }
        val result = runScript(ParasiteScripts.removeLoader(libraryDir))
        if (result.code != 0 || !result.output.contains(ParasiteMarkers.LOADER_GONE)) {
            return fail(LiteWebUnhideSteps.REMOVE_LOADER, LiteWebUnhideCodes.LOADER_DELETE_FAILED, failureDetail(result))
        }
        return ok(LiteWebUnhideSteps.REMOVE_LOADER, "loader=absent")
    }

    // ------------------------------------------------------------------ 步骤 3

    private fun revoke(hostPackage: String?): LiteWebRestoreStep {
        if (hostPackage == null) {
            return ok(LiteWebUnhideSteps.REVOKE_SU_AUTH, "no_backup")
        }
        val outcome = runCatching { revokeSuAuth.revoke(hostPackage) }
            .getOrElse { DaemonRevokeResult(false, LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED, it.javaClass.simpleName) }
        if (!outcome.ok) {
            val code = outcome.code.ifEmpty { LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED }
            return fail(LiteWebUnhideSteps.REVOKE_SU_AUTH, code, outcome.detail)
        }
        return ok(LiteWebUnhideSteps.REVOKE_SU_AUTH, outcome.detail)
    }

    // ------------------------------------------------------------------ 步骤 4（advisory）

    /**
     * 把陈旧残留改名进 `recovery/host-quarantine-<date>/`：**保留证据、不删除**。
     *
     * 只在「必须做的三步都已 ok」之后执行（用户已经解除成功），失败也**不**改写解除结论；
     * 但本步自己如实 `ok=false`，前端能看见哪一次清理没成功。
     */
    private fun quarantineStale(stale: List<String>): LiteWebRestoreStep {
        val safe = stale.filter { PACKAGE.matches(it) && !it.contains("..") }
        if (safe.isEmpty()) {
            return ok(LiteWebUnhideSteps.CLEANUP_STALE_BACKUPS, "skipped:no_safe_name")
        }
        val stamp = runCatching { quarantineStamp() }.getOrDefault("unknown")
        val result = runScript(ParasiteScripts.quarantineHostBackups(stamp, safe))
        val marker = result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(ParasiteMarkers.QUARANTINED) }
        if (result.code != 0 || marker == null) {
            return fail(
                LiteWebUnhideSteps.CLEANUP_STALE_BACKUPS,
                LiteWebUnhideCodes.STALE_BACKUP_QUARANTINE_FAILED,
                failureDetail(result),
            )
        }
        return ok(
            LiteWebUnhideSteps.CLEANUP_STALE_BACKUPS,
            marker.removePrefix(ParasiteMarkers.QUARANTINED).trim(),
        )
    }

    // ------------------------------------------------------------------ 步骤 5

    /**
     * 停 daemon：先写隐藏位 OFF 并回读（daemon 死后没人能写），再安排"响应之后退出"。
     *
     * 顺序不能反：一旦先退出，`lite_hidden` 仍是 1，App 会继续处于隐藏态（用户以为解除了、
     * 实际没解除 = 又一次假成功）。写到 OFF 但退出失败 → 本步失败，用户可重试（隐藏位已是
     * OFF，重试只会重跑前三步的幂等版本）。
     */
    private fun stopDaemon(): LiteWebRestoreStep {
        val previous = runCatching { hiddenState.currentHidden() }.getOrNull()
        val written = runCatching { hiddenState.setHidden(false, "unhide") }
            .getOrElse { LiteHiddenResult.Failed(LiteWebUnhideCodes.HIDDEN_STATE_OFF_FAILED, it.javaClass.simpleName) }
        val off = runCatching { hiddenState.currentHidden() }.getOrNull()
        if (written !is LiteHiddenResult.Ok || off != false) {
            val detail = when (written) {
                is LiteHiddenResult.Failed -> written.reason
                is LiteHiddenResult.Unavailable -> written.reason
                else -> "readback=${off?.toString() ?: "unknown"}"
            }
            return fail(LiteWebUnhideSteps.STOP_DAEMON, LiteWebUnhideCodes.HIDDEN_STATE_OFF_FAILED, detail)
        }
        if (!runCatching { selfStop() }.getOrDefault(false)) {
            if (previous == true) runCatching { hiddenState.setHidden(true, "unhide_retry") }
            return fail(LiteWebUnhideSteps.STOP_DAEMON, LiteWebUnhideCodes.DAEMON_STOP_FAILED, "exit_not_scheduled")
        }
        logger("hidden.unhide daemon exit scheduled (state=off)")
        return ok(LiteWebUnhideSteps.STOP_DAEMON, "daemon=exit_after_response;state=off")
    }

    // ------------------------------------------------------------------ helpers

    /** 删除「撤销授权身份材料」：只在解除成功后调用；删不掉返回 false（保留以便重试无害）。 */
    private fun removeIdentity(): Boolean = runCatching {
        val result = shell.run(RecoveryIdentityScripts.remove())
        result.code == 0 && result.output.contains(ParasiteMarkers.IDENTITY_GONE)
    }.getOrDefault(false)

    private fun runScript(script: String): DaemonShellResult =
        runCatching { shell.run(script) }.getOrElse { DaemonShellResult(-1, it.javaClass.simpleName) }

    private fun fail(name: String, code: String, detail: String = ""): LiteWebRestoreStep =
        LiteWebRestoreStep(name, false, code, detail)

    private fun ok(name: String, detail: String = ""): LiteWebRestoreStep =
        LiteWebRestoreStep(name, true, detail = detail)

    /** 只把结构化事实带进 detail：脚本输出可能含路径/包名，但绝不含 token。 */
    private fun failureDetail(result: DaemonShellResult): String {
        val marker = result.output.lineSequence()
            .firstOrNull { it.contains(ParasiteMarkers.FAIL) }
            ?.trim()
            .orEmpty()
        return when {
            marker.isNotEmpty() -> marker
            result.code != 0 -> "exit=${result.code}"
            else -> "missing_success_marker"
        }
    }

    private companion object {
        /** 宿主包名白名单（与 `HiddenHostPolicy` / 后端一致；不做通配/路径拼接）。 */
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

/** root 脚本执行结果（不依赖 recovery 包的命令模型，主机测试可直接构造）。 */
data class DaemonShellResult(val code: Int, val output: String)

/** daemon 内的 root 执行入口（生产 = `RuntimeRecoveryCommandRunner` 的等价实现）。 */
fun interface DaemonShell {
    fun run(script: String): DaemonShellResult
}

/** 撤销宿主 su 授权的结果（[code] 只在 [ok] = false 时有意义）。 */
data class DaemonRevokeResult(val ok: Boolean, val code: String = "", val detail: String = "")

/**
 * 撤销宿主 su 授权的能力点。
 *
 * A 模式（Ultra）的授权真相是 SDK 的 `su_auth` 白名单，只有它能撤销；B 模式（KSU）由策略
 * 托管、没有可写白名单（[DaemonUnavailableSuAuthRevoke] 会在 App 侧被替换成显式 `policy_managed`）。
 */
fun interface DaemonSuAuthRevoke {
    fun revoke(hostPackage: String): DaemonRevokeResult
}

/** 没有撤销能力时的诚实实现：显式失败（绝不写"已撤销"）。 */
object DaemonUnavailableSuAuthRevoke : DaemonSuAuthRevoke {
    override fun revoke(hostPackage: String): DaemonRevokeResult = DaemonRevokeResult(
        ok = false,
        code = LiteWebUnhideCodes.SU_AUTH_BACKEND_UNAVAILABLE,
        detail = "no su_auth backend in this daemon",
    )
}

/**
 * 停 daemon 自己：**先回响应再退**。
 *
 * 做法与理由：HTTP 响应由请求线程在 handler 返回后立刻 `write + flush`
 *（`LiteWebServer` 第 155-157 行），因此这里只**排程**一个延迟退出，不阻塞 handler：
 * 1.5s 后 `Runtime.halt(0)` —— 足够内核把响应刷给客户端，又不给前端留一个"点了没反应"的窗口。
 *
 * 不用 `System.exit`：daemon 没有需要优雅收尾的线程/资源，halt 能保证即使有非守护线程
 * 卡住也一定退出（"停 daemon" 不能因为一个卡住的线程而变成又一次假成功）。
 */
object DaemonSelfStop {
    const val DEFAULT_DELAY_MILLIS: Long = 1_500L

    fun schedule(
        delayMillis: Long = DEFAULT_DELAY_MILLIS,
        halt: (Int) -> Unit = { code -> Runtime.getRuntime().halt(code) },
    ): Boolean = runCatching {
        val worker = Thread(
            {
                runCatching { Thread.sleep(delayMillis) }
                runCatching { halt(0) }
            },
            "maplesu-liteweb-self-stop",
        )
        worker.isDaemon = false
        worker.start()
        worker.isAlive
    }.getOrDefault(false)

    /** 当前 daemon 的 pid（`/proc/self` 的规范路径；读不到 → null，不猜）。 */
    fun selfPid(): Int? = runCatching {
        File("/proc/self").canonicalPath.substringAfterLast('/').toIntOrNull()
    }.getOrNull()
}
