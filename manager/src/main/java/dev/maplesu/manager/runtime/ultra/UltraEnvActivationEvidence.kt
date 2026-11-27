// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Ultra 环境「激活证据」采集器（2026-10-30）。
 *
 * 背景：用户报告 Ultra 环境已完整接入并成功激活，但工作区没有真机 Ultra `Running` 的归档证据。
 * 本类只做**只读采集**：把当前进程能看到的 Ultra SDK 回执/探针快照/环境目录清单写成一份
 * 可归档、可回读校验的 JSON；**不启动进程、不调用任何写接口、不记录安装身份本体**。
 *
 * 严格判定：
 * `strictReady = 环境回执 state == "Running" && 已安装版本回读成功且非空 && 同次 Root 凭据证明通过`。
 * `Fault`/`NotInstalled`/`NoKey`/`Unknown`/SDK 不可用**都不算激活**。
 *
 * 安全边界：
 * - `ownerIdentity` 只记录 `present: true/false`；目录路径只记录模板 `/data/<deployment>/`，
 *   不写实际部署路径；
 * - SDK 原始回执里理论上可能回显输入，写入前按 [MAX_RAW_CHARS] 截断，但采集器本身不把
 *   安装身份传给任何回执字段；
 * - 输出文件位于 App 私有 `filesDir/`，文件名含采集时间戳；写完立刻读回并比对内容，
 *   读回不一致 → `EVIDENCE_READBACK_MISMATCH`。
 *
 * UI 接线（r5-ui/集成轮）建议：设置页/诊断页按钮在 IO 线程调 `collect()`，成功后把
 * `Result.file` 交给分享/复制；失败按 `Result.failure` 显示。
 */
class UltraEnvActivationEvidence private constructor(
    private val port: UltraEvidencePort,
    private val snapshot: () -> UltraProbeSnapshot,
    private val ownerIdentityPresent: () -> Boolean,
    private val dataDirProbe: () -> UltraDataDirProbe,
    private val outputDir: () -> File,
    private val currentMode: () -> String?,
    private val clock: () -> Long,
) {

    /** 采集结果。[ok] 只表示"证据文件已写出并回读一致"，**不代表已激活**；激活看 [strictReady]。 */
    data class Result(
        val ok: Boolean,
        val strictReady: Boolean,
        val file: File?,
        val json: String?,
        val sha256: String?,
        val failure: String?,
    )

    fun collect(): Result {
        val sdkVersion = safe("get_sdk_version") { port.sdkVersion() }
        val environmentState = safe("get_maplesu_environment_state") { port.environmentState() }
        val installedVersion = safe("get_installed_maplesu_environment_version") {
            port.installedEnvironmentVersion()
        }
        val testRoot = safe("get_root_status_report") { port.testRoot() }
        val probe = runCatching { snapshot() }.getOrElse {
            UltraProbeSnapshot(
                state = "Unknown",
                detail = "snapshot-unavailable: ${it.javaClass.simpleName}: ${it.message}",
                observedAtElapsedMs = -1L,
                observedAtWallClockMs = 0L,
            )
        }
        val ownerPresent = runCatching { ownerIdentityPresent() }.getOrDefault(false)
        val dataDir = runCatching { dataDirProbe() }.getOrElse {
            UltraDataDirProbe(
                pathTemplate = PATH_TEMPLATE,
                exists = false,
                readable = false,
                entries = emptyList(),
                error = "PROBE_FAILED: ${it.javaClass.simpleName}: ${it.message}",
            )
        }
        val modeName = runCatching { currentMode() }.getOrNull()
        val now = clock()

        val stateRunning = environmentState.succeeded &&
            environmentState.state == UltraReadinessRules.STATE_RUNNING
        val versionReadbackOk = installedVersion.succeeded &&
            !installedVersion.version.isNullOrEmpty()
        val testRootOk = testRoot.succeeded && testRoot.rootVerified == true
        val strictReady = stateRunning && versionReadbackOk && testRootOk

        val json = renderJson(
            now = now,
            modeName = modeName,
            port = port,
            sdkVersion = sdkVersion,
            environmentState = environmentState,
            installedVersion = installedVersion,
            testRoot = testRoot,
            probe = probe,
            stateRunning = stateRunning,
            versionReadbackOk = versionReadbackOk,
            testRootOk = testRootOk,
            strictReady = strictReady,
            ownerPresent = ownerPresent,
            dataDir = dataDir,
        )

        val dir = runCatching { outputDir() }.getOrElse {
            return Result(
                false, strictReady, null, json, null,
                "EVIDENCE_DIR_UNAVAILABLE: ${it.javaClass.simpleName}: ${it.message}",
            )
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Result(false, strictReady, null, json, null, "EVIDENCE_DIR_UNAVAILABLE: $dir")
        }
        val file = File(dir, "$FILE_PREFIX$now.json")
        try {
            writeFsync(file, json)
        } catch (error: Throwable) {
            return Result(
                false, strictReady, file, json, null,
                "EVIDENCE_WRITE_FAILED: ${error.javaClass.simpleName}: ${error.message}",
            )
        }
        val readBack = runCatching { file.readText() }.getOrNull()
            ?: return Result(false, strictReady, file, json, null, "EVIDENCE_READBACK_FAILED")
        val sha = sha256(json)
        val identical = readBack == json
        return Result(
            ok = identical,
            strictReady = strictReady,
            file = file,
            json = json,
            sha256 = sha,
            failure = if (identical) null else "EVIDENCE_READBACK_MISMATCH: ${file.name}",
        )
    }

    private fun safe(operation: String, block: () -> UltraEvidenceReceipt): UltraEvidenceReceipt = try {
        block()
    } catch (error: Throwable) {
        UltraEvidenceReceipt.unavailable(
            operation,
            "${error.javaClass.simpleName}: ${error.message}",
            port.bridgeLoaded,
        )
    }

    // ---------------------------------------------------------------- JSON

    private fun renderJson(
        now: Long,
        modeName: String?,
        port: UltraEvidencePort,
        sdkVersion: UltraEvidenceReceipt,
        environmentState: UltraEvidenceReceipt,
        installedVersion: UltraEvidenceReceipt,
        testRoot: UltraEvidenceReceipt,
        probe: UltraProbeSnapshot,
        stateRunning: Boolean,
        versionReadbackOk: Boolean,
        testRootOk: Boolean,
        strictReady: Boolean,
        ownerPresent: Boolean,
        dataDir: UltraDataDirProbe,
    ): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
        append("  \"kind\": \"").append(KIND).append("\",\n")
        append("  \"collectedAtEpochMs\": ").append(now).append(",\n")
        append("  \"collectedAtUtc\": \"").append(utc(now)).append("\",\n")
        append("  \"currentMode\": ").append(str(modeName)).append(",\n")
        append("  \"bridge\": {\n")
        append("    \"loaded\": ").append(port.bridgeLoaded).append(",\n")
        append("    \"backend\": \"").append(UltraRuntimeClient.BACKEND).append("\",\n")
        append("    \"sdkVersion\": ").append(str(sdkVersion.version)).append("\n")
        append("  },\n")
        append("  \"receipts\": {\n")
        append("    \"sdkVersion\": ").append(receiptJson(sdkVersion)).append(",\n")
        append("    \"environmentState\": ").append(receiptJson(environmentState)).append(",\n")
        append("    \"installedEnvironmentVersion\": ").append(receiptJson(installedVersion)).append(",\n")
        append("    \"testRoot\": ").append(receiptJson(testRoot)).append("\n")
        append("  },\n")
        append("  \"probeSnapshot\": {\n")
        append("    \"state\": \"").append(esc(probe.state)).append("\",\n")
        append("    \"detail\": ").append(str(probe.detail)).append(",\n")
        append("    \"observedAtElapsedMs\": ").append(probe.observedAtElapsedMs).append(",\n")
        append("    \"observedAtWallClockMs\": ").append(probe.observedAtWallClockMs).append(",\n")
        append("    \"ageMs\": ").append(ageMs(now, probe.observedAtWallClockMs)).append("\n")
        append("  },\n")
        append("  \"activation\": {\n")
        append("    \"strictReady\": ").append(strictReady).append(",\n")
        append("    \"verdict\": \"").append(if (strictReady) "ACTIVATED" else "NOT_ACTIVATED").append("\",\n")
        append("    \"criteria\": {\n")
        append("      \"stateRunning\": ").append(stateRunning).append(",\n")
        append("      \"versionReadbackOk\": ").append(versionReadbackOk).append(",\n")
        append("      \"testRootOk\": ").append(testRootOk).append("\n")
        append("    },\n")
        append("    \"note\": \"").append(ACTIVATION_NOTE).append("\"\n")
        append("  },\n")
        append("  \"ownerIdentity\": {\n")
        append("    \"present\": ").append(ownerPresent).append(",\n")
        append("    \"policy\": \"value is never read into, serialized by, or logged from this evidence\"\n")
        append("  },\n")
        append("  \"dataDir\": {\n")
        append("    \"pathTemplate\": \"").append(dataDir.pathTemplate).append("\",\n")
        append("    \"exists\": ").append(dataDir.exists).append(",\n")
        append("    \"readable\": ").append(dataDir.readable).append(",\n")
        append("    \"entries\": ").append(strArray(dataDir.entries)).append(",\n")
        append("    \"error\": ").append(str(dataDir.error)).append("\n")
        append("  }\n")
        append("}\n")
    }

    private fun receiptJson(receipt: UltraEvidenceReceipt): String = buildString {
        append("{")
        append("\"operation\": \"").append(esc(receipt.operation)).append("\", ")
        append("\"bridgeLoaded\": ").append(receipt.bridgeLoaded).append(", ")
        append("\"ok\": ").append(receipt.ok).append(", ")
        append("\"succeeded\": ").append(receipt.succeeded).append(", ")
        append("\"sdkError\": \"").append(esc(receipt.sdkError)).append("\", ")
        append("\"sdkErrorCode\": ").append(receipt.sdkErrorCode).append(", ")
        append("\"reason\": ").append(str(receipt.reason)).append(", ")
        append("\"state\": ").append(str(receipt.state)).append(", ")
        append("\"version\": ").append(str(receipt.version)).append(", ")
        append("\"rootVerified\": ").append(receipt.rootVerified).append(", ")
        append("\"callError\": ").append(str(receipt.callError)).append(", ")
        append("\"raw\": \"").append(esc(truncate(receipt.raw))).append("\"")
        append("}")
    }

    private fun ageMs(now: Long, observedAtWallClockMs: Long): String =
        if (observedAtWallClockMs <= 0L) "null" else (now - observedAtWallClockMs).toString()

    private fun str(value: String?): String = if (value == null) "null" else "\"${esc(value)}\""

    private fun strArray(values: List<String>): String =
        values.joinToString(", ", "[", "]") { "\"${esc(it)}\"" }

    private fun truncate(raw: String): String =
        if (raw.length <= MAX_RAW_CHARS) raw else raw.take(MAX_RAW_CHARS) + TRUNCATED_MARK

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "ultra-env-activation-evidence"
        const val FILE_PREFIX = "ultra-env-activation-"
        const val PATH_TEMPLATE = "/data/<deployment>/"

        private const val MAX_ENTRIES = 64
        private const val MAX_RAW_CHARS = 16_384
        private const val TRUNCATED_MARK = "…(truncated)"
        private const val ACTIVATION_NOTE =
            "strictReady = environment state Running AND installed version readback OK " +
                "AND same-call Root credential proof verified; Fault/NotInstalled/NoKey/Unknown/bridge-unavailable are not activation"

        private val UTC_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

        /** 生产装配：真实 SDK 客户端 + 探针缓存 + App 私有 filesDir。 */
        fun forContext(
            context: Context,
            currentMode: () -> String? = { null },
        ): UltraEnvActivationEvidence {
            val app = context.applicationContext
            val client = UltraSdkCredentials.client(app)
            val port = object : UltraEvidencePort {
                override val bridgeLoaded: Boolean get() = client.bridgeLoaded

                override fun sdkVersion(): UltraEvidenceReceipt =
                    client.sdkVersion().toEvidence("get_sdk_version", client.bridgeLoaded)

                override fun environmentState(): UltraEvidenceReceipt =
                    client.environmentState().toEvidence(
                        "get_maplesu_environment_state", client.bridgeLoaded,
                    )

                override fun installedEnvironmentVersion(): UltraEvidenceReceipt =
                    client.installedEnvironmentVersion().toEvidence(
                        "get_installed_maplesu_environment_version", client.bridgeLoaded,
                    )

                override fun testRoot(): UltraEvidenceReceipt =
                    client.testRoot().toEvidence("get_root_status_report", client.bridgeLoaded)
            }
            return UltraEnvActivationEvidence(
                port = port,
                snapshot = {
                    val current = UltraEnvironmentProbe.currentSnapshot(app)
                    UltraProbeSnapshot(
                        state = current.state.name,
                        detail = current.detail,
                        observedAtElapsedMs = current.observedAtElapsedMs,
                        observedAtWallClockMs = current.observedAtWallClockMs,
                    )
                },
                ownerIdentityPresent = { UltraEnvironmentProbe.currentSnapshot(app).managerOwnerIdentity != null },
                dataDirProbe = { probeDataDir(UltraInstalledIdentityProbe.installedHelperPath()) },
                outputDir = { app.filesDir },
                currentMode = currentMode,
                clock = { System.currentTimeMillis() },
            )
        }

        /** 单测装配（注入全部副作用；不接触 Android/JNI）。 */
        internal fun forTest(
            port: UltraEvidencePort,
            snapshot: () -> UltraProbeSnapshot = {
                UltraProbeSnapshot("Unknown", null, -1L, 0L)
            },
            ownerIdentityPresent: () -> Boolean = { false },
            dataDirProbe: () -> UltraDataDirProbe = {
                UltraDataDirProbe(PATH_TEMPLATE, false, false, emptyList(), null)
            },
            outputDir: () -> File,
            currentMode: () -> String? = { null },
            clock: () -> Long = { 1_700_000_000_000L },
        ): UltraEnvActivationEvidence = UltraEnvActivationEvidence(
            port = port,
            snapshot = snapshot,
            ownerIdentityPresent = ownerIdentityPresent,
            dataDirProbe = dataDirProbe,
            outputDir = outputDir,
            currentMode = currentMode,
            clock = clock,
        )

        private fun NativeReceipt.toEvidence(
            operation: String,
            bridgeLoaded: Boolean,
        ): UltraEvidenceReceipt = UltraEvidenceReceipt(
            operation = operation,
            bridgeLoaded = bridgeLoaded,
            ok = ok,
            sdkError = sdkError,
            sdkErrorCode = sdkErrorCode,
            reason = reason,
            state = string("state"),
            version = string("version"),
            rootVerified = when (this.operation) {
                "get_root_status_report" -> rootStatusReportVerified(this)
                "probe_manager_owner_root" -> parseManagerOwnerRoot(this).verified
                else -> null
            },
            raw = raw,
        )

        private fun probeDataDir(helperPath: String?): UltraDataDirProbe {
            val dir = helperPath?.takeIf { it.matches(Regex("/data/[A-Za-z0-9]{16}/init")) }?.let { File(it).parentFile }
                ?: return UltraDataDirProbe(PATH_TEMPLATE, false, false, emptyList(), "DEPLOYMENT_NOT_OBSERVED")
            val exists = runCatching { dir.exists() }.getOrDefault(false)
            if (!exists) {
                return UltraDataDirProbe(PATH_TEMPLATE, false, false, emptyList(), "NOT_PRESENT_OR_UNREADABLE")
            }
            val children = runCatching { dir.listFiles() }.getOrNull()
                ?: return UltraDataDirProbe(PATH_TEMPLATE, true, false, emptyList(), "LIST_DENIED")
            val entries = children.sortedBy { it.name }.take(MAX_ENTRIES).map { child ->
                val type = if (child.isDirectory) "dir" else "file"
                "$type ${child.name} size=${if (child.isFile) child.length() else -1}"
            }
            return UltraDataDirProbe(PATH_TEMPLATE, true, true, entries, null)
        }

        private fun writeFsync(file: File, text: String) {
            FileOutputStream(file).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun utc(epochMs: Long): String =
            UTC_FORMATTER.format(Instant.ofEpochMilli(epochMs))

        private fun esc(value: String): String = buildString {
            for (char in value) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
    }
}

/** 一条只读 SDK 回执的证据投影；不依赖 `org.json`，因此 JVM 单测可构造。 */
data class UltraEvidenceReceipt(
    val operation: String,
    val bridgeLoaded: Boolean = false,
    val ok: Boolean = false,
    val sdkError: String = "MAPLESU_BRIDGE_UNAVAILABLE",
    val sdkErrorCode: Int = Int.MIN_VALUE,
    val reason: String? = null,
    val state: String? = null,
    val version: String? = null,
    val raw: String = "",
    val callError: String? = null,
    val rootVerified: Boolean? = null,
) {
    val succeeded: Boolean get() = ok && sdkError == "OK" && sdkErrorCode == 0

    companion object {
        /** 调用本身失败（bridge 未加载/异常）：不是 SDK 语义失败，但同样不构成证据。 */
        fun unavailable(
            operation: String,
            error: String,
            bridgeLoaded: Boolean = false,
        ): UltraEvidenceReceipt = UltraEvidenceReceipt(
            operation = operation,
            bridgeLoaded = bridgeLoaded,
            ok = false,
            sdkError = "MAPLESU_EVIDENCE_CALL_FAILED",
            sdkErrorCode = Int.MIN_VALUE,
            raw = "",
            callError = error,
        )
    }
}

/** 证据采集端口（生产 = [UltraRuntimeClient]；测试 = 假回执）。 */
interface UltraEvidencePort {
    val bridgeLoaded: Boolean
    fun sdkVersion(): UltraEvidenceReceipt
    fun environmentState(): UltraEvidenceReceipt
    fun installedEnvironmentVersion(): UltraEvidenceReceipt
    fun testRoot(): UltraEvidenceReceipt
}

/** 探针缓存快照的证据投影（不依赖 Android 类型）。 */
data class UltraProbeSnapshot(
    val state: String,
    val detail: String?,
    val observedAtElapsedMs: Long,
    val observedAtWallClockMs: Long,
)

/** `/data/<deployment>/` 只读探测结果：只记路径模板，不记实际路径。 */
data class UltraDataDirProbe(
    val pathTemplate: String,
    val exists: Boolean,
    val readable: Boolean,
    val entries: List<String>,
    val error: String?,
)
