package dev.maplesu.manager.runtime

import android.content.Context
import dev.maplesu.manager.runtime.ultra.ProcessSuExec
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup
import dev.maplesu.manager.runtime.ultra.UltraBootSdkSession
import dev.maplesu.manager.runtime.ultra.UltraSdkSession
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials

/** Hook clients use the SDK's existing UID-scoped exec redirect, without a global su mount. */
internal object HookSuCompatibility {
    const val STANDARD_PATH = "/system/bin/su"
    private const val CLEAN = "HOOK_SU_LEGACY_CLEAN"

    fun publish(context: Context, mode: SuEntryMode.Mode, prepareEnvironment: Boolean = true): RuntimeDeployment.Status {
        val app = context.applicationContext
        var sdkSession: UltraBootSdkSession? = null
        var sessionCurrent: (() -> Boolean)? = null
        check(HookRootExecutor.production(app).available()) { "SU_GATE_NOT_ARMED" }
        ensurePublication(
            cleanup = {
                val result = RootExec.executeHook(app, cleanupScript())
                check(result.ok && result.output.lineSequence().any { it == CLEAN }) {
                    "HOOK_SU_CLEANUP_FAILED:${result.code}:${result.detail.take(160)}"
                }
            },
            prepare = {
                ensureBroker(
                    published = { brokerReady(app) },
                    recover = {
                        // Re-publish the current gate, without running another exploit.
                        val outcome = NativeSuDaemon.reproveHookOutcome(app, scheduleDeployment = false)
                        check(outcome?.stage == dev.maplesu.manager.jailbreak.JailbreakStatus.STAGE_DAEMON_READY &&
                            outcome.failure == null) { outcome?.failure ?: "SU_EXECUTOR_NOT_PUBLISHED" }
                    },
                )
                if (mode == SuEntryMode.Mode.COOPERATIVE_MINIMAL) null else {
                    val result = if (prepareEnvironment) UltraEnvironmentAutoSetup.ensure(app) else null
                    sdkSession = UltraSdkSession.current(app)
                    val bootCurrent = UltraSdkSession.captureOwnerMarker(app)
                    val ownerCurrent = UltraSdkCredentials.captureOwnerMarker(app)
                    sessionCurrent = { bootCurrent() && ownerCurrent() && SuEntryMode.read(app) == mode }
                    result?.takeUnless { it.installed }?.reason
                }
            },
            sdkCurrent = if (mode == SuEntryMode.Mode.COOPERATIVE_MINIMAL) null else ({
                val expected = sdkSession
                val observed = UltraSdkSession.current(app)
                expected != null && observed != null && expected.bootId == observed.bootId &&
                    sessionCurrent?.invoke() == true && HookRootExecutor.production(app).available() &&
                    UltraEnvironmentAutoSetup.blockedReason(app, bootSession = true) == null
            }),
            probe = {
                if (mode == SuEntryMode.Mode.COOPERATIVE_MINIMAL) true else {
                    // The ordinary manager UID must reach the SDK redirect itself.
                    val session = checkNotNull(sdkSession) { "SDK_SESSION_ABSENT" }
                    UltraSdkSession.verifyManagerSu(app, session) {
                        val result = ProcessSuExec.exec(listOf(STANDARD_PATH, "-c", "/system/bin/id -u"), 5_000, null)
                        result.ok && result.output.trim() == "0"
                    }
                }
            },
        )
        if (mode == SuEntryMode.Mode.COOPERATIVE_MINIMAL) {
            return RuntimeDeployment.Status(standardPath = STANDARD_PATH.takeIf { traditionalReady(app) },
                brokerReady = true)
        }
        SuAuditLog.append(app, SuAuditEvent(backend = "hook", event = "entry",
            decision = "ready", detail = "uid-redirect:$STANDARD_PATH"))
        return RuntimeDeployment.Status(standardPath = STANDARD_PATH, brokerReady = true,
            entryCurrent = checkNotNull(sessionCurrent))
    }

    /** Verify the cooperative endpoint independently of SDK/traditional-entry preparation. */
    fun brokerReady(context: Context): Boolean {
        val app = context.applicationContext
        return runCatching { NativeSuDiscovery.peek(app.applicationInfo.uid).isBinderAlive }.getOrDefault(false)
    }

    /** A live publication needs no recovery; one failed recovery never starts a new attempt. */
    internal fun ensureBroker(published: () -> Boolean, recover: () -> Unit) {
        if (published()) return
        recover()
        check(published()) { "SU_EXECUTOR_NOT_PUBLISHED" }
    }

    internal fun ensurePublication(prepare: () -> String?, cleanup: () -> Unit, probe: () -> Boolean,
        sdkCurrent: (() -> Boolean)? = null) {
        // Legacy mounts must disappear even when SDK preparation later fails.
        cleanup()
        val error = prepare()
        // Module enumeration and unrelated grant synchronization do not decide SU readiness.
        check(if (sdkCurrent == null) error == null else sdkCurrent()) { error ?: "SDK_SESSION_ABSENT" }
        check(probe()) { "HOOK_SU_EXEC_UNVERIFIED" }
        check(sdkCurrent?.invoke() != false) { "SDK_SESSION_CHANGED" }
    }

    /** Receipt is published only after an ordinary-UID exec, and expires with its boot session. */
    fun traditionalReady(context: Context): Boolean {
        val status = RuntimeDeployment.status()
        return status.brokerReady && status.error == null && status.standardPath == STANDARD_PATH &&
            status.entryCurrent?.invoke() == true &&
            UltraSdkSession.current(context) != null
    }

    /** Remove only our previous overlays, including copies inherited by existing apps. */
    internal fun cleanupScript(): String {
        val cleanup = """
            set -eu
            while :; do
                point=${'$'}(/system/bin/awk '/ - overlay maplesu-su / { print ${'$'}5; exit }' /proc/self/mountinfo)
                [ -n "${'$'}point" ] || break
                case "${'$'}point" in
                    /system/bin|/system/xbin|/product/bin|/system_ext/bin) /system/bin/umount -l "${'$'}point" ;;
                    *) echo HOOK_SU_UNKNOWN_LEGACY_MOUNT >&2; exit 74 ;;
                esac
            done
        """.trimIndent()
        return """
            set -eu
            cleanup=${quote(cleanup)}
            pids="1 ${'$'}(/system/bin/ps -A -o PID | /system/bin/awk 'NR > 1 { print ${'$'}1 }') ${'$'}${'$'}"
            seen=' '
            failed=0
            for pid in ${'$'}pids; do
                ns=${'$'}(/system/bin/readlink /proc/${'$'}pid/ns/mnt 2>/dev/null || true)
                [ -n "${'$'}ns" ] || continue
                case "${'$'}seen" in *" ${'$'}ns "*) continue ;; esac
                seen="${'$'}seen${'$'}ns "
                # Most namespaces have nothing to remove; do not start a shell in them.
                if ! /system/bin/grep -q ' - overlay maplesu-su ' /proc/${'$'}pid/mountinfo 2>/dev/null; then continue; fi
                if ! /system/bin/nsenter -t "${'$'}pid" -m -- /system/bin/sh -c "${'$'}cleanup"; then
                    if [ -d /proc/${'$'}pid ]; then
                        echo "HOOK_SU_NAMESPACE_CLEANUP_FAILED pid=${'$'}pid" >&2
                        failed=1
                    fi
                fi
            done
            [ "${'$'}failed" = 0 ] || exit 75
            echo $CLEAN
        """.trimIndent()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
