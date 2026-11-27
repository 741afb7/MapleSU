package dev.maplesu.manager.workmode.detect.probes

import android.content.Context
import android.os.Looper
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.MapleSuRuntime
import dev.maplesu.manager.workmode.detect.LiteCredentialProbe
import dev.maplesu.manager.workmode.detect.LiteHandshakeProbe

/** Read-only owner observation first; explicit phase two verifies the existing runtime. */
class LiteCredentialProbeAdapter(
    context: Context,
    private val readIdentity: () -> Boolean = {
        UltraEnvironmentProbe.currentSnapshot(context.applicationContext).managerOwnerIdentity?.fullUid ==
            android.os.Process.myUid()
    },
    private val isMainThread: () -> Boolean = {
        Looper.myLooper() == Looper.getMainLooper()
    },
    private val probeHandshake: () -> Boolean = {
        MapleSuRuntime.isReady(context.applicationContext)
    },
) : LiteCredentialProbe, LiteHandshakeProbe {

    @Volatile
    private var lastKnownPresent: Boolean? = null

    /** 只读存在性；**绝不**在这里尝试握手（启动期禁止起 root）。 */
    override fun probe(): Pair<Boolean, Boolean?> {
        val present = runCatching { readIdentity() }.getOrDefault(false)
        lastKnownPresent = present
        return present to null
    }

    /** 当前安装身份 → UID0 握手；只允许后台线程 + allowLiteExecutor=true 的阶段二。 */
    override fun handshake(): Boolean? {
        val present = lastKnownPresent
            ?: runCatching { readIdentity() }.getOrDefault(false).also { lastKnownPresent = it }
        if (!present) return LiteProbeRules.evaluate(identityPresent = false, outcome = HandshakeOutcome.NOT_ATTEMPTED).second
        val onMainThread = runCatching { isMainThread() }.getOrDefault(true)
        if (onMainThread) {
            return LiteProbeRules.evaluate(identityPresent = true, outcome = HandshakeOutcome.NOT_ATTEMPTED).second
        }
        val outcome = runCatching { probeHandshake() }.fold(
            onSuccess = { ready -> if (ready) HandshakeOutcome.SUCCEEDED else HandshakeOutcome.FAILED },
            onFailure = { HandshakeOutcome.UNKNOWN },
        )
        return LiteProbeRules.evaluate(identityPresent = true, outcome = outcome).second
    }
}

/** 握手尝试结果（可区分 NOT_ATTEMPTED / SUCCEEDED / FAILED / UNKNOWN）。 */
enum class HandshakeOutcome { NOT_ATTEMPTED, SUCCEEDED, FAILED, UNKNOWN }

/** 纯映射：当前安装身份存在性 + 握手结果 → 端口返回值 `(present, handshakeOk?)`。 */
object LiteProbeRules {
    fun evaluate(identityPresent: Boolean, outcome: HandshakeOutcome): Pair<Boolean, Boolean?> = when {
        !identityPresent -> false to null
        outcome == HandshakeOutcome.SUCCEEDED -> true to true
        outcome == HandshakeOutcome.FAILED -> true to false
        // NOT_ATTEMPTED / UNKNOWN 一律 null：未尝试 ≠ 失败（01 §2.7 handshakeNullIsNotFalse）。
        else -> true to null
    }
}
