package dev.maplesu.manager.liteweb

import android.content.Context
import android.provider.Settings
import dev.maplesu.manager.litehidden.ParasiteMarkers
import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.litehidden.RecoveryIdentityScripts
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.runtime.RootExec

/** 一次 ULTRA_ENV daemon 启动尝试的结果（不伪造：未确认监听即 started=false）。 */
data class LiteWebDaemonStart(
    val started: Boolean,
    val port: Int? = null,
    val code: String = "",
    val detail: String = "",
) {
    companion object {
        fun unsupported(detail: String): LiteWebDaemonStart = LiteWebDaemonStart(
            started = false,
            code = LiteWebBackendPolicy.ULTRA_ENV_DAEMON_UNSUPPORTED,
            detail = detail,
        )
    }
}

/** ULTRA_ENV 下启动独立 daemon 的策略点（可注入 fake 做主机测试）。 */
fun interface UltraEnvLiteWebDaemon {
    fun start(context: Context): LiteWebDaemonStart

    companion object {
        fun default(): UltraEnvLiteWebDaemon = UltraEnvLiteWebDaemon { context ->
            RootCmdUltraEnvLiteWebDaemon(execute = { RootExec.execute(context, it) }).start(context)
        }
    }
}

/**
 * 经既有根通道写入当前 App 身份，再以 `app_process` 启动 daemon。
 * 受保护身份用于核对安装归属；SDK 按 daemon 当前任务逐次核权。
 * 启动脚本先用配置中的 token 完成 HTTP 探活，再回传同一配置的端口。
 */
class RootCmdUltraEnvLiteWebDaemon(
    private val execute: (String) -> RootExecResult = { script -> RootExec.execute(script) },
) : UltraEnvLiteWebDaemon {

    override fun start(context: Context): LiteWebDaemonStart {
        val apk = runCatching { context.applicationInfo.sourceDir }.getOrNull()
            ?: return LiteWebDaemonStart.unsupported("apk path unavailable")
        val identity = runCatching {
            RecoveryIdentity.of(
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
                context.packageName,
                context.applicationInfo.uid,
            )
        }.getOrNull()
        return startForApk(apk, identity)
    }

    /** 主机测试入口：不依赖 Android Context，只验证脚本/探测/失败语义。 */
    internal fun startForApk(apk: String, identity: RecoveryIdentity?): LiteWebDaemonStart {
        if (identity?.isValid != true) return LiteWebDaemonStart.unsupported("app identity unavailable")
        val script = buildString {
            // 原子写入并复核 root-only 权限；脚本失败会 exit，绝不继续启动无身份的 daemon。
            appendLine(RecoveryIdentityScripts.write(identity))
            appendLine(RecoveryWebUiScripts.resume())
            appendLine("CONF=/data/adb/maplesu/recovery/webui.conf")
            appendLine("MODE=\$(sed -n 's/^workMode=//p' \"\$CONF\" 2>/dev/null)")
            appendLine("if [ -z \"\$MODE\" ]; then printf '\\nworkMode=LITE\\n' >> \"\$CONF\" || exit 1; elif [ \"\$MODE\" != 'LITE' ]; then exit 1; fi")
            // Shared launch protocol: only the lock owner publishes a PID, and HTTP must be ready.
            appendLine("(")
            appendLine(dev.maplesu.manager.litehidden.ParasiteScripts.daemonStart(apk))
            appendLine(") || exit 1")
            appendLine("PORT=\$(sed -n 's/^webuiPort=//p;s/^port=//p' \"\$CONF\" 2>/dev/null | head -n 1)")
            appendLine("case \"\$PORT\" in ''|*[!0-9]*) exit 1;; esac")
            appendLine("[ \"\$PORT\" -ge 1024 ] && [ \"\$PORT\" -le 65535 ] || exit 1")
            appendLine("echo \"LITEWEB_DAEMON_STARTED port=\$PORT\"")
        }
        val result = runCatching { execute(script) }.getOrNull()
            ?: return LiteWebDaemonStart.unsupported("root backend unavailable")
        if (!result.ok) {
            return LiteWebDaemonStart.unsupported("root backend rejected: ${result.detail.ifEmpty { "code=${result.code}" }}")
        }
        if (!result.output.lineSequence().any { it.trim() == ParasiteMarkers.IDENTITY_OK }) {
            return LiteWebDaemonStart.unsupported("daemon identity preparation failed")
        }
        val receipts = result.output.lineSequence().map(String::trim)
            .filter { it.startsWith("LITEWEB_DAEMON_STARTED") }.toList()
        val port = receipts.singleOrNull()?.let { STARTED.matchEntire(it) }
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: return LiteWebDaemonStart.unsupported("authenticated daemon port receipt invalid")
        return LiteWebDaemonStart(started = true, port = port)
    }

    private companion object {
        val STARTED = Regex("LITEWEB_DAEMON_STARTED port=([0-9]{4,5})")
    }
}

