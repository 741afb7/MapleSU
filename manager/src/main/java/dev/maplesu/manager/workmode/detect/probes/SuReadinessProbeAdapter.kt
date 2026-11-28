// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect.probes

import android.content.Context
import android.util.Log
import dev.maplesu.manager.jailbreak.JailbreakStatus
import dev.maplesu.manager.runtime.NativeSu
import dev.maplesu.manager.runtime.SuGate
import dev.maplesu.manager.runtime.SuPublication
import dev.maplesu.manager.runtime.SuRootCandidates
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import dev.maplesu.manager.workmode.detect.SuProbeAttempt
import dev.maplesu.manager.workmode.detect.SuProbeDecision
import dev.maplesu.manager.workmode.detect.SuProbeReport
import dev.maplesu.manager.workmode.detect.SuReadiness
import dev.maplesu.manager.workmode.detect.SuReadinessCache
import dev.maplesu.manager.workmode.detect.SuReadinessProbe
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * §5 C6 的设备侧探针：**枚举候选 → 真的把每个候选执行一次 → 只认退出码与 stdout 的 uid**。
 *
 * 候选来源（用户指定）：
 * 1. **MapleSU 自有 su**：凭据派生的 `/data/<16hex>/su`（[SuPublication.suPath]）、
 *    上次发布的目录、历史 `/dev/maplesu/bin/su`；
 * 2. **L3 gate**：`JailbreakStatus` 记录绑定到**本 boot** 且 `SuGate` 处于 ARMED 时，
 *    经 [NativeSu.executeScript] 起 `sh -c 'id -u'`；
 * 3. **KSU**：`/system/bin/su` 等既有落点，以及 APK 自带的 `<nativeLibraryDir>/libksud_ksu.so
 *    debug su`（stdin 写 `id -u` 后关掉 stdin，让它执行完退出）。
 *
 * 纪律：
 * - 已知 su 路径逐个实探，APK 自带 daemon 只在打包存在时加入；任何一条结论都来自真实子进程
 *   （起不来 / 超时 = UNKNOWN，文件可见性不裁决 Root）；
 * - 总预算默认 [DEFAULT_TIMEOUT_MILLIS]（≤1500ms，用户要求）：第一个 USABLE 立即短路；
 * - 结果是进程级缓存 [CACHE_WINDOW_MILLIS]：一次刷新里 adapter / resolver / 首页三处
 *   读的是同一份结论，避免在同一轮里重复起进程把 8s 预算烧光。
 */
class SuReadinessProbeAdapter(private val context: Context) : SuReadinessProbe {

    private val appContext: Context = context.applicationContext

    override fun probe(): SuProbeReport = probe(DEFAULT_TIMEOUT_MILLIS)

    fun probe(totalBudgetMillis: Long = DEFAULT_TIMEOUT_MILLIS): SuProbeReport {
        val budget = totalBudgetMillis.coerceIn(1L, MAX_TIMEOUT_MILLIS)
        return cache.read(budget) {
            runProbes(budget).also { Log.i(TAG, "su probe ${it.describe}") }
        }
    }

    internal fun probeFresh(totalBudgetMillis: Long = DEFAULT_TIMEOUT_MILLIS): SuProbeReport =
        runProbes(totalBudgetMillis.coerceIn(1L, MAX_TIMEOUT_MILLIS))
            .also { Log.i(TAG, "fresh su probe ${it.describe}") }

    private fun runProbes(totalBudgetMillis: Long): SuProbeReport {
        val deadline = System.nanoTime() + totalBudgetMillis * 1_000_000L
        val attempts = mutableListOf<SuProbeAttempt>()
        val candidates = candidates()

        // ① L3 gate：只有记录绑定本 boot 且 ARMED 才可能起进程；否则不是一次可执行尝试。
        gateAttempt(remainingMillis(deadline) / (candidates.size + 1))?.let { attempts += it }

        // ② 其余候选：已知路径不按文件可见性裁剪，证据来自执行结果。
        if (attempts.none { it.readiness == SuReadiness.USABLE }) {
            for ((index, candidate) in candidates.withIndex()) {
                val remaining = remainingMillis(deadline)
                if (remaining <= 0L) break
                val budget = (remaining / (candidates.size - index)).coerceAtLeast(1L)
                val attempt = runCandidate(candidate, budget)
                attempts += attempt
                if (attempt.readiness == SuReadiness.USABLE) break
            }
        }
        return SuProbeDecision.report(attempts)
    }

    /** L3 gate 探针：`SuGate` 未 ARMED / 没有本 boot 记录 = 本次不做可执行尝试（返回 null）。 */
    private fun gateAttempt(budgetMillis: Long): SuProbeAttempt? {
        if (budgetMillis <= 0L) return null
        val record = runCatching { JailbreakStatus.read(appContext) }.getOrNull() ?: return null
        val attemptId = record.attemptId?.takeIf { it.isNotBlank() } ?: return null
        val bootId = runCatching { ProcBootIdSource.read() }.getOrNull().orEmpty()
        if (bootId.isBlank()) return null
        val gate = SuGate.read(appContext, attemptId, bootId)
        if (!gate.armed) return null
        return try {
            val result = NativeSu.executeScript(
                gate,
                "id -u",
                File(appContext.filesDir, NativeSu.FALLBACK_NAME),
                budgetMillis.toInt(),
            )
            SuProbeAttempt(
                source = SuProbeDecision.SOURCE_L3_GATE,
                readiness = SuProbeDecision.classify(true, false, result.code, result.out),
                exitCode = result.code,
                uid = SuProbeDecision.parseUid(result.out),
                detail = result.err.take(MAX_DETAIL_CHARS),
            )
        } catch (error: Throwable) {
            if (error is Error) throw error
            SuProbeAttempt(
                source = SuProbeDecision.SOURCE_L3_GATE,
                readiness = SuReadiness.UNKNOWN,
                detail = "native_unavailable:" + error.javaClass.simpleName,
            )
        }
    }

    private fun candidates(): List<Candidate> = SuRootCandidates.all(appContext).map {
        Candidate(it.source, it.argv, stdin = "id -u\n")
    }

    private fun runCandidate(candidate: Candidate, budgetMillis: Long): SuProbeAttempt {
        val process = try {
            ProcessBuilder(candidate.argv).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return SuProbeAttempt(
                source = candidate.source,
                readiness = SuReadiness.UNKNOWN,
                detail = "start_failed:" + error.javaClass.simpleName,
            )
        }
        return try {
            runCatching {
                if (candidate.stdin != null) {
                    process.outputStream.use { stream ->
                        stream.write(candidate.stdin.toByteArray())
                        stream.flush()
                    }
                } else {
                    process.outputStream.close()
                }
            }
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { stream ->
                        var line = stream.readLine()
                        while (line != null && output.length < MAX_OUTPUT_CHARS) {
                            output.append(line).append('\n')
                            line = stream.readLine()
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "maplesu-su-probe-reader"
                start()
            }
            val finished = process.waitFor(budgetMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                return SuProbeAttempt(
                    source = candidate.source,
                    readiness = SuReadiness.UNKNOWN,
                    detail = "timeout",
                )
            }
            reader.join(READER_JOIN_MILLIS)
            val text = output.toString()
            val exit = process.exitValue()
            SuProbeAttempt(
                source = candidate.source,
                readiness = SuProbeDecision.classify(true, false, exit, text),
                exitCode = exit,
                uid = SuProbeDecision.parseUid(text),
            )
        } catch (error: Throwable) {
            if (error is Error) throw error
            runCatching { process.destroyForcibly() }
            SuProbeAttempt(
                source = candidate.source,
                readiness = SuReadiness.UNKNOWN,
                detail = "run_failed:" + error.javaClass.simpleName,
            )
        }
    }

    private fun remainingMillis(deadline: Long): Long =
        (deadline - System.nanoTime()) / 1_000_000L

    private data class Candidate(
        val source: String,
        val argv: List<String>,
        val stdin: String? = null,
    )

    companion object {
        private const val TAG = "MapleSUSuProbe"

        /** 用户要求：默认超时 ≤1500ms；这是**整轮**预算，不是每个候选。 */
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L

        /** 调用方可以再压预算（例如 resolver 的剩余窗口），但不得超过默认上限。 */
        const val MAX_TIMEOUT_MILLIS = 1_500L

        /** 一次刷新内复用同一份结论（adapter / resolver / 首页三处）。 */
        const val CACHE_WINDOW_MILLIS = 2_000L

        private const val READER_JOIN_MILLIS = 100L
        private const val MAX_OUTPUT_CHARS = 4_096
        private const val MAX_DETAIL_CHARS = 160

        /** MapleSU 的历史发布路径（仅枚举；真实与否由执行结果回答）。 */
        const val LEGACY_MAPLESU_SU = SuRootCandidates.LEGACY_MAPLESU_SU

        /** KSU / Magisk 的既有落点（只用于枚举候选）。 */
        val KSU_SU_PATHS = SuRootCandidates.KSU_SU_PATHS

        private val cache = SuReadinessCache()

        /** A verified handover invalidates an earlier negative SU observation. */
        internal fun invalidateCache() {
            cache.invalidate()
        }

        internal fun resetCacheForTest() {
            invalidateCache()
        }
    }
}
