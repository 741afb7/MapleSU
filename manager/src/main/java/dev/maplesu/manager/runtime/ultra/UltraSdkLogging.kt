package dev.maplesu.manager.runtime.ultra

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/** Idempotent startup writes share a receipt only after the real SDK acknowledged the desired value. */
internal class UltraSdkLogPolicy(private val now: () -> Long) {
    private data class Applied(val identity: String, val enabled: Boolean, val at: Long, val receipt: NativeReceipt)
    private var applied: Applied? = null

    @Synchronized
    fun apply(identity: () -> String?, enabled: Boolean, write: () -> NativeReceipt?): NativeReceipt? {
        val scope = identity()
        val cached = applied
        if (scope != null && cached?.identity == scope && cached.enabled == enabled && now() - cached.at in 0 until 60_000L) {
            val data = JSONObject(cached.receipt.data.toString()).put("cached", true)
            return cached.receipt.copy(data = data, raw = JSONObject(cached.receipt.raw).put("data", data).toString())
        }
        applied = null
        val receipt = write()
        if (scope != null && receipt?.succeeded == true && receipt.data?.opt("requested") == enabled && identity() == scope) {
            applied = Applied(scope, enabled, now(), receipt)
        }
        return receipt
    }

    @Synchronized
    fun invalidate() { applied = null }
}

object UltraSdkLogging {
    private const val TAG = "MapleSUUltraLogging"
    private val policy = UltraSdkLogPolicy(SystemClock::elapsedRealtime)

    /** 只有 48 位纯字母数字、且不是更长十六进制串的一部分才视作疑似 key。 */
    private val KEY_LIKE = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{48}(?![A-Za-z0-9])")

    @Volatile
    private var explicitDiagnostics: Boolean = false

    /** 默认关闭；显式诊断开关（UI 未暴露，仅内部/测试路径）。 */
    fun shouldEnable(): Boolean = explicitDiagnostics

    fun setExplicitDiagnostics(enabled: Boolean) {
        explicitDiagnostics = enabled
        policy.invalidate()
    }

    internal fun invalidate() = policy.invalidate()

    /** 在后台线程应用 SDK 日志开关（JNI）；返回 null 表示 SDK 不可用/失败。 */
    fun apply(client: UltraRuntimeClient): NativeReceipt? =
        runCatching {
            val enabled = shouldEnable()
            policy.apply(client::logPolicyScope, enabled) { client.setLogEnabled(enabled) }
        }.getOrNull()

    /** Apply the default policy through this installation's current task channel. */
    fun ensureDisabled(context: Context): NativeReceipt? = apply(UltraSdkCredentials.client(context))

    /** 脱敏：疑似 48 位 key → `<REDACTED-KEY>`；null 原样返回空。 */
    fun redact(text: String?): String = text?.replace(KEY_LIKE, "<REDACTED-KEY>") ?: ""

    /** 测试/巡检用：文本中是否出现疑似 rootKey。 */
    fun containsKeyLike(text: String?): Boolean = text != null && KEY_LIKE.containsMatchIn(text)

    /** 生产日志入口：先脱敏再打印（error/log 文本都不得携带 key）。 */
    fun logSafely(priority: Int, message: String, throwable: Throwable? = null) {
        runCatching {
            val safe = redact(message)
            if (throwable == null) {
                Log.println(priority, TAG, safe)
            } else {
                Log.println(priority, TAG, "$safe: ${redact(throwable.message)}")
            }
        }
    }
}
