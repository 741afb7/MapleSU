package dev.maplesu.manager.modules

import dev.maplesu.manager.modules.webui.ModuleWebUiSessions

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.os.Process
import android.util.Log
import dev.maplesu.manager.ModuleArchiveInspector.ModuleType
import dev.maplesu.manager.ModuleArchiveInspector.Report
import dev.maplesu.manager.modules.compat.UltraModuleAction
import dev.maplesu.manager.modules.compat.UltraModuleBuildDigest
import dev.maplesu.manager.modules.compat.UltraModuleCatalogPort
import dev.maplesu.manager.modules.compat.UltraModuleCompatibilityGate
import dev.maplesu.manager.modules.compat.UltraModuleDeviceFacts
import dev.maplesu.manager.modules.compat.UltraModuleGateEvidenceLog
import dev.maplesu.manager.modules.compat.UltraModuleHost
import dev.maplesu.manager.modules.compat.UltraModuleInstallRequest
import dev.maplesu.manager.modules.compat.UltraModuleInstallTransaction
import dev.maplesu.manager.modules.compat.UltraModuleJournal
import dev.maplesu.manager.modules.compat.UltraModuleUninstallTransaction
import dev.maplesu.manager.modules.compat.UltraRuntimeModuleHost
import dev.maplesu.manager.modules.compat.confirmedUltraModuleInstall
import dev.maplesu.manager.modules.compat.ultraEnvironmentStateLabel
import dev.maplesu.manager.ultra.model.MapleSuModInstalledItem
import dev.maplesu.manager.ultra.model.MapleSuModPrinter
import dev.maplesu.manager.runtime.ultra.NativeReceipt
import dev.maplesu.manager.runtime.ultra.UltraOutcome
import dev.maplesu.manager.runtime.ultra.UltraPhase
import dev.maplesu.manager.runtime.ultra.UltraReceipt
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Ultra (MapleSU) module backend.
 *
 * Native module enable/disable uses the SDK extension only when the installed helper
 * implements its boot gate. Desired state, runtime state and pending reboot stay separate.
 *
 * What MapleSU adds on top of the reference is what the integration spec requires and the
 * reference lacks: the confirmed bytes are staged in the app-private catalog and the host
 * consumes that staged path (not the picker's temp file), every mutation is journaled with
 * an operationId, read-back comes from the SDK list rather than the call's return value, and
 * a catalog commit only happens after the host confirms the install.
 *
 * ## 安装路径是薄壳（W6-F1）
 *
 * `install()` 只做 `Context` 侧的解析与装配：把 `ModuleArchiveInspector.Report` 翻成
 * `UltraModulePackageIdentity`，然后把事务交给 [UltraModuleInstallTransaction]。**事务体不在这里**
 * —— 它是无 `Context`、全部依赖注入的类，所以 H1 的「门挡住时宿主调用次数为 0」可以在 JVM 上
 * 由**真实事务**断言，而不是复刻一份门序列（W5 复核 S1-1）。
 *
 * 本类保留的接缝装配：包存储（`ModuleCatalog`）、操作日志（`ultra-operations.jsonl`）、证据 sink
 * （`ultra-module-evidence.jsonl`）、设备事实（`UltraModuleDeviceFacts.read`）、时钟
 * （`System.currentTimeMillis`）、构建摘要（管理器 APK 的 SHA-256）、boot id、PID。
 */
class MapleSuModuleBackend(
    context: Context,
    /**
     * 宿主调用接缝（H1/H4）。默认值就是真实实现，所以既有调用点（`KsuCli`、`ModuleViewModel`）
     * 不改也能编译、行为也不变；注入替身只用于测试与后续 wave 的编排验证。
     */
    host: UltraModuleHost = UltraRuntimeModuleHost(UltraSdkCredentials.client(context)),
) {

    private val context = context.applicationContext
    private val catalogRoot = File(this.context.filesDir, "module-catalog")
    private val catalog = ModuleCatalog(catalogRoot)

    /** 会执行模块原生代码的宿主调用都经过它（另见 [gate]）。 */
    private val host: UltraModuleHost = host

    /**
     * 真实宿主背后的客户端：只读能力快照（`capabilities()`）与列表回执（`moduleList()`）仍直接用它。
     *
     * 注入替身时这里会另建一个只读客户端。替身路径（兼容门拦下的请求）不会经过它，所以测试里
     * 不会触发 JNI；代价是「替身 + 真实客户端并存」，收益是 `listReceipt()` 这类回执形状不必
     * 为测试再造一套假 JSON。
     */
    private val client: UltraRuntimeClient = (host as? UltraRuntimeModuleHost)?.client
        ?: UltraSdkCredentials.client(this.context)

    private val operations = UltraOperationLog(dev.maplesu.manager.compat.UltraStateStorage.file(this.context.filesDir, "ultra-operations.jsonl"))

    /** catalog 接缝（`ModuleCatalog` 是纯文件 IO，没有 Android 依赖）。一对一转发，不改异常。 */
    private val moduleCatalogPort = object : UltraModuleCatalogPort {

        override fun stage(source: File, confirmedArchiveSha256: String): String =
            catalog.stage(source, ModuleType.MAPLESU, confirmedArchiveSha256).token

        override fun stagedArchive(token: String): File = catalog.stagedArchive(token)

        override fun commit(token: String) {
            catalog.commit(token)
        }

        override fun recoverCommitted(operationId: String, token: String, moduleId: String,
                                      confirmedArchiveSha256: String): Boolean =
            operations.recoverIfCurrent(operationId, moduleId) {
                catalog.recoverCommitted(token, ModuleType.MAPLESU, moduleId, confirmedArchiveSha256)
            }

        override fun discard(token: String) {
            catalog.discard(token)
        }
    }

    /** §4.3 的故障证据：逐行 JSONL，只 append，不轮转、不覆盖。 */
    private val gateEvidenceFile = dev.maplesu.manager.compat.UltraStateStorage.file(this.context.filesDir, "ultra-module-evidence.jsonl")
    private val gateEvidence = UltraModuleGateEvidenceLog(
        appendLine = { line -> gateEvidenceFile.appendText(line) },
    )

    /** Share the diagnostic APK digest across backend instances for the same installed bytes. */
    private val managerBuildDigest: UltraModuleBuildDigest
        get() = installedManagerBuildDigest(context)

    /**
     * 安装事务（§4.1 门 + stage + descriptor 交叉检查 + 宿主安装 + 回读 + catalog 提交）。
     *
     * **事务体不在这里**：`UltraModuleInstallTransaction` 是无 `Context`、全部依赖注入的类，产品与
     * 测试走同一份代码（W6-F1）。本类只负责把 `Context` 相关的东西解析成接缝：包存储、操作日志、
     * 证据 sink、设备事实、时钟、构建摘要、boot id、PID。
     *
     * 与改前逐项一致的部分：回执的 `phase`/`outcome`/`unknowns`/`cleanupOutcome`、stage/commit/
     * discard 的时机、journal 内容、宿主调用顺序、stdout/stderr 文案。差异只有两处新增（§4.3）：
     * 每条事件带 `deviceTimeIso` 与 `buildDigest`，并且安装阶段从 1 条事件变成 1–2 条。
     */
    private val installTransaction = UltraModuleInstallTransaction(
        host = host,
        catalog = moduleCatalogPort,
        journal = object : UltraModuleJournal {
            override fun append(operationId: String, receipt: UltraReceipt, token: String?) {
                operations.append(operationId, receipt, token)
            }

            override fun appendDurably(operationId: String, receipt: UltraReceipt, token: String?): Result<Unit> =
                operations.appendDurably(receipt, token)
        },
        evidence = gateEvidence::record,
        facts = { UltraModuleDeviceFacts.read(client) },
        clockMillis = System::currentTimeMillis,
        buildDigest = { managerBuildDigest },
        bootId = ::bootId,
        processPid = { Process.myPid() },
        onCommitted = { ModuleWebUiSessions.committed("maplesu:$it") },
    )

    /** Capability flags come from both the linked SDK and the installed helper. */
    data class ModuleCapabilities(
        val bridgeLoaded: Boolean,
        val sdkCompatible: Boolean,
        val environmentReady: Boolean,
        val supportsInstall: Boolean,
        val supportsDevRunOnce: Boolean,
        val supportsUninstall: Boolean,
        val supportsEnableToggle: Boolean = false,
        val supportsImmediateStop: Boolean = false,
        val supportsUpdate: Boolean = true,
        val unsupportedReason: String?,
    )

    fun capabilities(): ModuleCapabilities {
        val caps = client.capabilities()
        return ModuleCapabilities(
            bridgeLoaded = caps.bridgeLoaded,
            sdkCompatible = caps.sdkCompatible,
            environmentReady = caps.ultraEnvironmentReady,
            supportsInstall = caps.ultraEnvironmentReady,
            supportsDevRunOnce = caps.ultraEnvironmentReady,
            supportsUninstall = caps.bridgeLoaded && caps.sdkCompatible &&
                dev.maplesu.manager.runtime.ultra.UltraReadinessRules.moduleRecoveryAvailable(caps.environmentState),
            supportsEnableToggle = caps.supportsModuleToggle,
            supportsUpdate = caps.ultraEnvironmentReady,
            unsupportedReason = when {
                !caps.bridgeLoaded -> caps.failure ?: "系统环境未响应"
                !caps.sdkCompatible -> "系统环境未返回版本信息"
                dev.maplesu.manager.runtime.ultra.UltraReadinessRules.isSafeMode(caps.environmentState) ->
                    "环境处于安全模式；仅支持查看和卸载模块"
                !caps.ultraEnvironmentReady ->
                    "系统环境状态为${ultraEnvironmentStateLabel(caps.environmentState)}；模块操作需要系统环境已就绪"
                else -> null
            },
        )
    }

    fun environmentReceipt(): NativeReceipt = host.environmentState()

    /** `null` means the SDK refused the list; an empty list would wrongly read as "no modules". */
    @JvmOverloads
    fun listItems(required: Boolean = false): List<MapleSuModInstalledItem>? {
        val receipt = client.moduleList()
        check(!required || receipt.succeeded) { "ULTRA_MODULE_LIST_FAILED:${receipt.sdkError}:${receipt.sdkErrorCode}" }
        if (!receipt.succeeded) return null
        return try {
            val items = verifiedModules(receipt)
            try {
                val failures = LinkedHashMap<String, String>()
                settleUninstalls(operations.pending(), items.map { it.id32 }.toSet(), failures)
                if (failures.isNotEmpty()) Log.w(TAG, "MODULE_CATALOG_CLEANUP_PENDING: $failures")
            } catch (error: IOException) {
                Log.w(TAG, UltraOperationLog.READ_FAILED, error)
            }
            items
        } catch (error: Exception) {
            if (required || error is kotlinx.coroutines.CancellationException) throw error
            null
        }
    }

    fun listReceipt(): NativeReceipt = client.moduleList()

    private fun verifiedModules(receipt: NativeReceipt): List<MapleSuModInstalledItem> {
        check(receipt.succeeded) { "ULTRA_MODULE_LIST_FAILED:${receipt.sdkError}:${receipt.sdkErrorCode}" }
        val rows = checkNotNull(receipt.array("modules")) { "ULTRA_MODULE_LIST_INVALID:missing_modules" }
        val items = client.modules(receipt)
        check(items.size == rows.length() && items.all { it.id32.isNotBlank() } &&
            items.map { it.id32 }.distinct().size == items.size) { "ULTRA_MODULE_LIST_INVALID:module_rows" }
        return items
    }

    fun details(item: MapleSuModInstalledItem): String = MapleSuModPrinter.buildModuleMeta(item)

    /**
     * Installs a confirmed archive.
     *
     * Order is fixed: environment check -> **compatibility gate** -> stage confirmed bytes ->
     * host descriptor cross-check -> journal -> host install -> read back the SDK list ->
     * catalog commit. 兼容门（§4.1）必须在 stage 与任何宿主调用之前：判定只读静态身份，
     * 命中「已确认不兼容」时不做 stage、不发任何宿主模块调用。
     *
     * A failure before the host call can discard the staged bytes; a failure after it must
     * not, because the host may have applied something the list cannot see yet.
     *
     * @param action 这次入口按哪个动作判定（见 [UltraModuleAction]）。缺省按 [devRunOnce] 区分
     *   安装与开发单次运行；**更新与市场快捷动作应在各自入口显式传入**，否则回执文案会写成
     *   「安装」。注意：命中条件（id32 + 归档哈希 + 原生载荷哈希）与动作无关，所以即使调用方
     *   没传 `UPDATE`，已确认不兼容的包在更新路径上同样会被拦下。
     */
    fun install(
        source: File,
        report: Report,
        devRunOnce: Boolean,
        stdout: (String) -> Unit,
        stderr: (String) -> Unit,
        action: UltraModuleAction =
            if (devRunOnce) UltraModuleAction.DEV_RUN_ONCE else UltraModuleAction.INSTALL,
    ): UltraReceipt {
        try {
            val failures = LinkedHashMap<String, String>()
            settleCommittedInstalls(operations.pending(), failures)
            if (failures.isNotEmpty()) Log.w(TAG, "MODULE_CATALOG_RECOVERY_PENDING: $failures")
        } catch (error: IOException) {
            Log.w(TAG, UltraOperationLog.READ_FAILED, error)
        }
        return installTransaction.install(
            UltraModuleInstallRequest(
                source = source,
                // `Report` 只在这一行被读取：判定/回执/catalog 要用的字段全部由 identity 承载，
                // 所以事务不需要 `Context`，也不需要能构造 `Report`（它的构造函数是 private）。
                identity = UltraModuleCompatibilityGate.identityFrom(report),
                // 原值：缺失是 null（不是空串），descriptor 交叉检查据此区分两种情况。
                manifestVersion = report.properties["version"],
                devRunOnce = devRunOnce,
                action = action,
            ),
            stdout = stdout,
            stderr = stderr,
        )
    }

    fun setEnabled(id32: String, enabled: Boolean): UltraReceipt =
        dev.maplesu.manager.modules.compat.UltraModuleToggleTransaction(
            host,
            mutationAllowed = {
                dev.maplesu.manager.workmode.ModeRuntime.active == dev.maplesu.manager.workmode.WorkMode.LITE &&
                    !dev.maplesu.manager.workmode.ui.WorkModeSwitchSession.executing
            },
            beforeMutation = { operations.appendDurably(it).getOrThrow() },
        ).setEnabled(id32, enabled).let { receipt ->
            val recorded = operations.appendDurably(receipt)
            recorded.exceptionOrNull()?.let { error -> receipt.copy(unknowns = receipt.unknowns +
                ("operationLog" to (error.message ?: "operation_log_write_failed"))) } ?: receipt
        }

    /** Mirrors the reference delete action: uninstall by id32, then re-read the list. */
    fun uninstall(id32: String): UltraReceipt {
        val operationId = UUID.randomUUID().toString()
        val revision: Result<String?> = try {
            Result.success(catalog.revision(ModuleType.MAPLESU, id32))
        } catch (error: IOException) {
            Result.failure(error)
        }
        val identity = JSONObject().put("catalogRevisionKnown", revision.isSuccess)
        if (revision.isSuccess) identity.put("catalogRevision", revision.getOrNull() ?: JSONObject.NULL)
        val prepared = UltraReceipt.of(operationId, "uninstall_module", null,
            UltraPhase.PREPARED, UltraOutcome.NEEDS_RECONCILIATION, moduleId = id32, data = identity)
        val preparation = operations.appendDurably(prepared)
        if (preparation.isFailure) return prepared.copy(
            outcome = UltraOutcome.FAILED, sdkError = UltraOperationLog.WRITE_FAILED, sdkErrorCode = -1001,
            reason = "The uninstall was not submitted because its operation record could not be saved.",
            unknowns = mapOf("operationLog" to preparation.exceptionOrNull().toString()),
        )
        val receipt = UltraModuleUninstallTransaction(host) {
            revision.getOrThrow()
            if (!catalog.uninstallIfRevision(ModuleType.MAPLESU, it, revision.getOrNull())) {
                throw IOException("MODULE_CATALOG_CHANGED")
            }
        }.uninstall(id32, operationId) {
            ModuleWebUiSessions.committed("maplesu:$it")
        }
        val data = JSONObject(receipt.data?.toString() ?: "{}")
            .put("catalogRevisionKnown", revision.isSuccess)
        if (revision.isSuccess) data.put("catalogRevision", revision.getOrNull() ?: JSONObject.NULL)
        // A native error may follow partial removal; retain its intent without replaying it.
        val result = receipt.copy(data = data,
            outcome = if (receipt.outcome == UltraOutcome.FAILED) UltraOutcome.NEEDS_RECONCILIATION else receipt.outcome)
        val recorded = operations.appendDurably(result)
        return if (recorded.isSuccess) result else result.copy(
            outcome = UltraOutcome.NEEDS_RECONCILIATION,
            unknowns = result.unknowns + ("operationLog" to recorded.exceptionOrNull().toString()),
        )
    }

    /**
     * 底层 SDK 调用：`start_module_web_ui_server_async` 会**由模块把页面拉起来**
     * （真机取证：uid 0 的 `am start -a VIEW http://127.0.0.1:<port>/`），不是被动读端口。
     *
     * ⚠️ 不要从进程启动 / 后台轮询 / 安装回执里调用它。UI 的唯一入口是
     * [dev.maplesu.manager.modules.ModuleWebUiLauncher.open]，那里有显式来源闸门；
     * 之前的自动跳转就是 `SoterLane` 在启动时直接调了这一层造成的。
     */
    fun openWebUi(id32: String): UltraReceipt {
        val operationId = UUID.randomUUID().toString()
        val receipt = host.openModuleWebUI(id32)
        val port = receipt.int("port")
        return UltraReceipt.of(
            operationId = operationId,
            operation = "start_module_web_ui_server_async",
            native = receipt,
            phase = UltraPhase.HOST_CALLED,
            outcome = when {
                !receipt.succeeded -> UltraOutcome.FAILED
                port != null && port > 0 -> UltraOutcome.COMPLETED
                else -> UltraOutcome.NEEDS_RECONCILIATION
            },
            moduleId = id32,
            unknowns = if (port != null && port > 0) emptyMap()
            else mapOf("port" to "the async server returned no port"),
        ).also { operations.append(operationId, it, null) }
    }

    /**
     * Re-reads journaled operations that never reached a terminal phase and compares them with
     * the host list. Cancelling a manager request is not the same as revoking module effect, so
     * this completes matching catalog cleanup without replaying a native mutation.
     */
    fun reconcile(): UltraReceipt {
        val operationId = UUID.randomUUID().toString()
        val pending = try {
            operations.pending()
        } catch (error: IOException) {
            return UltraReceipt.of(operationId, "reconcile",
                NativeReceipt.bridgeFailure("reconcile", UltraOperationLog.READ_FAILED, error.message),
                UltraPhase.PREPARED, UltraOutcome.FAILED)
        }
        val cleanupFailures = LinkedHashMap<String, String>()
        val settled = ArrayList(settleCommittedInstalls(pending, cleanupFailures))
        val receipt = host.moduleList()
        val observed = try {
            Result.success(verifiedModules(receipt))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Result.failure(error)
        }
        observed.getOrNull()?.let {
            settled.addAll(settleUninstalls(pending, it.map { item -> item.id32 }.toSet(), cleanupFailures))
        }
        return UltraReceipt.of(
            operationId = operationId,
            operation = "reconcile",
            native = receipt,
            phase = UltraPhase.BUSINESS_PROBED,
            outcome = if (observed.isSuccess && (pending.isEmpty() || settled.size == pending.size)) UltraOutcome.COMPLETED
            else UltraOutcome.NEEDS_RECONCILIATION,
            data = JSONObject()
                .put("pending", pending.size)
                .put("settled", settled.size)
                .put("cleanupFailures", JSONObject(cleanupFailures as Map<*, *>))
                .put("hostModules", observed.getOrNull()?.size ?: JSONObject.NULL),
            unknowns = if (observed.isFailure) mapOf("moduleList" to
                (observed.exceptionOrNull()?.message ?: "module_list_unverified"))
            else if (pending.size == settled.size) emptyMap()
            else mapOf("pendingOperations" to "${pending.size - settled.size} still unmatched"),
        ).also { operations.append(operationId, it, null) }
    }

    private fun settleCommittedInstalls(pending: List<JSONObject>,
                                       failures: MutableMap<String, String>): List<JSONObject> {
        val settled = ArrayList<JSONObject>()
        pending.filter { entry ->
            entry.optString("operation") in setOf("install_module", "install_module_dev_run_once") &&
                entry.optString("phase") == UltraPhase.HOST_COMMITTED_CATALOG_PENDING.name
        }.forEach { entry ->
            val operationId = entry.optString("operationId")
            val moduleId = entry.optJSONObject("data")?.optString("catalogModuleId")
                ?.takeIf { it.isNotBlank() } ?: entry.optString("moduleId", "")
            val native = NativeReceipt.parse(entry.optString("rawReceipt"))
            val knownCommit = native?.let { confirmedUltraModuleInstall(it, entry.optString("operation")) } == true
            if (!knownCommit || moduleId.isBlank()) {
                failures[operationId] = "host_commit_unverified"
                return@forEach
            }
            try {
                val recovered = moduleCatalogPort.recoverCommitted(operationId,
                    entry.optString("token"), moduleId, entry.optString("packageSha256"))
                if (!recovered) failures[operationId] = "catalog_revision_or_operation_changed"
                else if (!operations.markReconciled(operationId)) {
                    failures[operationId] = "operation_log_write_failed"
                } else settled.add(entry)
            } catch (error: IOException) {
                failures[operationId] = error.message ?: error.javaClass.simpleName
            }
        }
        return settled
    }

    private fun settleUninstalls(pending: List<JSONObject>, present: Set<String>,
                                cleanupFailures: MutableMap<String, String>): List<JSONObject> {
        val absent = pending.filter { entry ->
            val moduleId = entry.optString("moduleId", "")
            // Presence cannot prove which install bytes or desired toggle won.
            // Only a verified absence confirms this recorded uninstall's effect.
            entry.optString("operation") == "uninstall_module" && moduleId.isNotBlank() &&
                moduleId !in present
        }
        val settled = ArrayList<JSONObject>()
        absent.forEach { entry ->
            val moduleId = entry.optString("moduleId")
            try {
                val data = entry.optJSONObject("data")
                val removed = if (data?.optBoolean("catalogRevisionKnown", false) == true &&
                    data.has("catalogRevision")) {
                    val expected = if (data.isNull("catalogRevision")) null else data.optString("catalogRevision")
                    catalog.uninstallIfRevision(ModuleType.MAPLESU, moduleId, expected)
                } else catalog.revision(ModuleType.MAPLESU, moduleId) == null
                if (!removed) cleanupFailures[moduleId] = "catalog_revision_unverified"
                else if (!operations.markReconciled(entry.optString("operationId"))) {
                    cleanupFailures[moduleId] = "operation_log_write_failed"
                } else settled.add(entry)
            } catch (error: IOException) {
                cleanupFailures[moduleId] = error.message ?: error.javaClass.simpleName
            }
        }
        return settled
    }

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unknown")

    companion object {
        private const val TAG = "MapleSUUltraBackend"
        private val buildDigestLock = Any()
        private var buildDigestCache: Pair<Triple<String, Long, Long>, UltraModuleBuildDigest.Known>? = null

        private fun installedManagerBuildDigest(context: Context): UltraModuleBuildDigest {
            val source = runCatching { context.applicationInfo?.sourceDir }.getOrNull()
            if (source.isNullOrBlank()) return UltraModuleBuildDigest.Unavailable(
                UltraModuleBuildDigest.REASON_PACKAGE_INFO_UNAVAILABLE)
            val apk = File(source)
            val identity = Triple(source, apk.length(), apk.lastModified())
            return synchronized(buildDigestLock) {
                buildDigestCache?.takeIf { it.first == identity }?.second
                    ?: UltraModuleBuildDigest.fromApkFile(apk).also {
                        if (it is UltraModuleBuildDigest.Known) buildDigestCache = identity to it
                    }
            }
        }

        /**
         * 兼容门拦下时的稳定错误码：宿主**没有被调用**，所以不能沿用「桥不可用」
         * （`MAPLESU_BRIDGE_ABSENT`）或「SDK 拒绝」。回执、日志与测试按这个码对齐。
         *
         * 值指向 [UltraModuleInstallTransaction.KNOWN_INCOMPATIBLE_SDK_ERROR]（构造被拦回执的地方），
         * 这里只是既有的公开常量名，避免调用点与测试跟着搬家。
         */
        const val KNOWN_INCOMPATIBLE_SDK_ERROR = UltraModuleInstallTransaction.KNOWN_INCOMPATIBLE_SDK_ERROR

        /** 与 [KNOWN_INCOMPATIBLE_SDK_ERROR] 配套的数值码（负数，与桥错误的 -1001 区分）。 */
        const val KNOWN_INCOMPATIBLE_SDK_ERROR_CODE =
            UltraModuleInstallTransaction.KNOWN_INCOMPATIBLE_SDK_ERROR_CODE

    }
}

/** Explicit environment removal survives reboot; normal requests use installation identity. */
object UltraRootKeyStore {
    private const val PREFS = "maplesu-ultra"
    private const val REMOVAL = "environmentRemoval"

    /** Retire former local secrets without activating or pairing an environment. */
    fun clearLegacy(context: Context) {
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        val names = arrayOf("rootKey", "rootKeySuspended", "pendingKernelCredential", "previousInstallCredential",
            "keyVerified", "keyVerifiedFor", "installCredentialTaskId", "installCredentialForKey",
            "installCredentialSourceBoot", "installCredentialProvenance", "installAuthorizationObservedForKey")
        if (names.any(prefs::contains)) {
            val editor = prefs.edit()
            names.forEach(editor::remove)
            check(editor.commit()) { "ULTRA_LEGACY_STORAGE_CLEANUP_FAILED" }
        }
        val exports = context.ultraPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE)
        if (exports.all.isNotEmpty()) check(exports.edit().clear().commit()) { "ULTRA_LEGACY_STORAGE_CLEANUP_FAILED" }
    }

    internal fun captureOwnerMarker(context: Context): () -> Boolean {
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        val names = arrayOf(REMOVAL, "rootKey", "rootKeySuspended", "keyVerified", "keyVerifiedFor",
            "pendingKernelCredential", "previousInstallCredential")
        val captured = names.associateWith { prefs.all[it] }
        return { names.all { prefs.all[it] == captured[it] } }
    }

    /** An explicit uninstall must survive reboot without startup reinstalling it. */
    fun isSuspended(context: Context): Boolean = context
        .ultraPreferences(PREFS, Context.MODE_PRIVATE)
        .contains(REMOVAL)

    @Synchronized
    fun recordRemoval(context: Context, permanent: Boolean) {
        clearLegacy(context)
        check(context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(REMOVAL, if (permanent) "permanent" else "temporary")
            .commit()) { "ULTRA_REMOVAL_STORE_FAILED" }
    }

    fun allowEnvironmentInstall(context: Context) {
        check(context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(REMOVAL).commit()) { "ULTRA_REMOVAL_STORE_FAILED" }
    }
}

/**
 * Append-only JSONL journal for Ultra mutations.
 *
 * The spec needs an operationId per change, the ability to see what was in flight after a
 * process death, and the rule that a lost answer is reconciled rather than replayed. This is
 * a file, not a service: nothing here runs in the background.
 */
internal class UltraOperationLog(private val file: File) {

    companion object {
        const val WRITE_FAILED = "MAPLESU_OPERATION_LOG_WRITE_FAILED"
        const val READ_FAILED = "MAPLESU_OPERATION_LOG_READ_FAILED"
        private val ioLock = Any()
    }

    fun append(operationId: String, receipt: UltraReceipt, token: String?) {
        appendDurably(receipt, token)
            .onFailure { Log.w("MapleSUUltraBackend", "operation log write failed", it) }
    }

    fun appendDurably(receipt: UltraReceipt, token: String? = null): Result<Unit> =
        appendText(receipt.toJson().put("loggedAtMillis", System.currentTimeMillis())
            .put("token", token ?: JSONObject.NULL).toString())

    fun pending(): List<JSONObject> = synchronized(ioLock) {
        val terminal = setOf(
            UltraOutcome.COMPLETED.name,
            UltraOutcome.FAILED.name,
            UltraOutcome.CONDITION_UNMET.name,
        )
        latestEntries().values.filter { entry ->
            !entry.optBoolean("reconciled", false) && entry.optString("operation") in setOf(
                "install_module", "install_module_dev_run_once", "uninstall_module", "set_module_enabled",
            ) && (entry.optString("outcome", "") !in terminal ||
                entry.optString("phase") == UltraPhase.HOST_COMMITTED_CATALOG_PENDING.name)
        }
    }

    fun recoverIfCurrent(operationId: String, moduleId: String, recover: () -> Boolean): Boolean =
        synchronized(ioLock) {
            val mutations = latestEntries(operationId).values.filter { entry ->
                val recordedId = entry.optString("_catalogModuleId")
                val stagedFailure = entry.optString("phase") == UltraPhase.PREPARED.name &&
                    entry.optString("outcome") == UltraOutcome.FAILED.name && entry.isNull("token")
                recordedId == moduleId && !stagedFailure && entry.optString("operation") in setOf(
                    "install_module", "install_module_dev_run_once", "uninstall_module",
                )
            }
            val current = mutations.lastOrNull()
            if (current == null || current.optString("operationId") != operationId || mutations.dropLast(1).any {
                    val boundary = it.optLong("_catalogBoundaryOrder", 0L)
                    boundary == 0L || boundary >= current.optLong("_catalogFirstOrder")
                }) false else recover()
        }

    private fun latestEntries(recoveryOperationId: String? = null): LinkedHashMap<String, JSONObject> {
        val latest = LinkedHashMap<String, JSONObject>()
        var order = 0L
        try {
            Files.newBufferedReader(file.toPath(), Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    order++
                    val entry = runCatching { JSONObject(line) }.getOrNull()
                    val id = entry?.optString("operationId", "").orEmpty()
                    if (entry == null || id.isEmpty()) {
                        if (recoveryOperationId != null) {
                            throw IOException("${READ_FAILED}:unreadable_operation_history")
                        }
                        return@forEach
                    }
                    val previous = latest[id]
                    if (recoveryOperationId != null && entry.optString("operation").isBlank() &&
                        !entry.optBoolean("reconciled", false)) {
                        throw IOException("${READ_FAILED}:incomplete_operation_history")
                    }
                    if (entry.optBoolean("reconciled", false) && !entry.has("operation")) {
                        previous?.put("reconciled", true)
                        if (previous != null && previous.optLong("_catalogBoundaryOrder", 0L) == 0L) {
                            previous.put("_catalogBoundaryOrder", order)
                        }
                    } else {
                        val catalogId = entry.optJSONObject("data")?.optString("catalogModuleId")
                            ?.takeIf { it.isNotBlank() } ?: previous?.optString("_catalogModuleId")
                            ?: entry.optString("moduleId", "")
                        if (recoveryOperationId != null &&
                            entry.optString("operation") in setOf("install_module", "install_module_dev_run_once",
                                "uninstall_module") && catalogId.isBlank()) {
                            throw IOException("${READ_FAILED}:missing_module_history_identity")
                        }
                        val boundary = previous?.optLong("_catalogBoundaryOrder", 0L) ?: 0L
                        val finished = entry.optString("phase") in setOf(
                            UltraPhase.HOST_COMMITTED_CATALOG_PENDING.name, UltraPhase.CATALOG_COMMITTED.name,
                        ) || entry.optString("outcome") in setOf(UltraOutcome.COMPLETED.name,
                            UltraOutcome.FAILED.name, UltraOutcome.CONDITION_UNMET.name)
                        entry.put("reconciled", entry.optBoolean("reconciled", false))
                            .put("_catalogModuleId", catalogId)
                            .put("_catalogFirstOrder", previous?.optLong("_catalogFirstOrder") ?: order)
                            .put("_catalogBoundaryOrder", if (boundary == 0L && finished) order else boundary)
                        latest[id] = entry
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            return latest
        }
        return latest
    }

    fun markReconciled(operationId: String): Boolean {
        if (operationId.isEmpty()) return false
        return appendText(JSONObject().put("operationId", operationId).put("reconciled", true).toString()).isSuccess
    }

    private fun appendText(text: String) = synchronized(ioLock) {
        runCatching {
            FileOutputStream(file, true).use { output ->
                output.write(("\n" + text + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            FileChannel.open(file.absoluteFile.parentFile.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                it.force(true)
            }
        }
    }
}
