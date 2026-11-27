package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.ModeRuntime

/** Explicit startup work only; environment/page probes remain read-only. */
internal object UltraIdleEnvironmentRecovery {
    private const val PREFS = "ultra-idle-recovery"
    private const val SUBMITTED = "submittedBoot"

    fun ensure(context: Context, bootId: String): UltraEnvironmentAutoSetup.Result? =
        ensure(context, bootId) { true }

    internal fun ensure(context: Context, bootId: String,
                        isCurrent: () -> Boolean): UltraEnvironmentAutoSetup.Result? =
        synchronized(UltraEnvironmentUpgrade) {
            if (!isCurrent()) UltraEnvironmentAutoSetup.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
            else ensureExclusive(context, bootId, isCurrent)
        }

    internal fun submittedThisBoot(context: Context): Boolean {
        val bootId = UltraEnvironmentAutoSetup.currentBootId() ?: return false
        return context.ultraPreferences(PREFS, Context.MODE_PRIVATE).getString(SUBMITTED, null) == bootId
    }

    private fun ensureExclusive(context: Context, bootId: String,
                                isCurrent: () -> Boolean): UltraEnvironmentAutoSetup.Result? {
        // A temporary SDK has its own durable HotLoad owner and submission history.
        val session = UltraSdkSession.current(context)
        if (!isCurrent()) return UltraEnvironmentAutoSetup.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
        if (session != null) return null
        val helper = UltraInstalledIdentityProbe.installedHelperPath() ?: return null
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        fun allowed() = isCurrent() && current(context, bootId)
        val client = UltraSdkCredentials.client(context, ::allowed)
        val result = UltraIdleRecoveryAttempt.run(object : UltraIdleRecoveryAttempt.Port {
            override fun blockedReason() = when {
                !isCurrent() -> "SDK_IDENTITY_SCOPE_CHANGED"
                !allowed() -> "SCOPE_CHANGED"
                else -> null
            }
            override fun alreadySubmitted() = prefs.getString(SUBMITTED, null) == bootId ||
                context.ultraPreferences("ultra-auto-setup", Context.MODE_PRIVATE)
                    .getString("submittedBootId", null) == bootId ||
                context.ultraPreferences("ultra-env-upgrade", Context.MODE_PRIVATE)
                    .getString("submittedBootId", null) == bootId
            override fun idleVerified(): Boolean {
                val receipt = client.runRootCommand(evidenceScript(helper, bootId))
                return allowed() && receipt.succeeded && client.decodeText(receipt.string("result"))?.trim() ==
                    "MAPLESU_IDLE_BOOT:$bootId"
            }
            override fun helperVerified(): Boolean {
                val observation = UltraInstalledIdentityProbe.readAndVerify(context, client)
                val expectedSha = UltraInstalledIdentityProbe.bundledHelperSha256() ?: return false
                // Do not use a build label or persisted identity to start older helper bytes.
                return allowed() && observation.verification.verified && observation.identity.source == Source.Readback &&
                    observation.identity.bootId == bootId && observation.identity.helperPath == helper &&
                    UltraInstalledIdentityProbe.normalizeSha(observation.identity.helperSha256) == expectedSha &&
                    observation.identity.helperSize == UltraInstalledIdentityProbe.bundledHelperSizeBytes()
            }
            override fun channelVerified() = allowed() && UltraIdentityRecovery.uid(client) == 0 && allowed()
            override fun markSubmitted() = allowed() &&
                prefs.edit().putString(SUBMITTED, bootId).commit() && allowed()
            override fun prepare(): String? {
                if (!allowed()) return "SDK_IDENTITY_SCOPE_CHANGED"
                val failure = UltraPolicyBackendDeployment.prepare(context, client)
                return if (allowed()) failure else "SDK_IDENTITY_SCOPE_CHANGED"
            }
            override fun submit() = client.installEnvironment("HotLoad", "").succeeded
            override fun running() = allowed() &&
                UltraEnvironmentProbe.refresh(context, force = true) == UltraEnvironmentProbe.State.Running && allowed()
            override fun functionalityVerified(): Boolean {
                if (UltraIdentityRecovery.uid(client) != 0 || !allowed()) return false
                if (!UltraSelfWhitelist.onEnvironmentInstalled(context, client).succeeded ||
                    !allowed() || UltraSuChannel().rootUid() != 0 || !allowed()) return false
                val modules = client.moduleList()
                val policy = client.modulePolicySettings()
                val effective = policy.data?.opt("effective")
                return allowed() && modules.succeeded && modules.array("modules") != null && policy.succeeded &&
                    UltraIdleRecoveryAttempt.effectivePolicyVerified(effective) &&
                    client.environmentState().let { it.succeeded && it.string("state") == "Running" } && allowed()
            }
        })
        return if (isCurrent()) result else result?.copy(reason = "SDK_IDENTITY_SCOPE_CHANGED")
            ?: UltraEnvironmentAutoSetup.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
    }

    private fun current(context: Context, bootId: String): Boolean =
        !Thread.currentThread().isInterrupted && ModeRuntime.active == WorkMode.LITE &&
            UltraEnvironmentAutoSetup.currentBootId() == bootId &&
            UltraSdkSession.current(context) == null && !UltraSdkCredentials.hookWithoutSdk(context) &&
            UltraEnvironmentAutoSetup.blockedReason(context) == null

    /** Empty markers alone are insufficient: require the untouched installer sentinel too. */
    internal fun evidenceScript(helper: String, bootId: String): String {
        require(helper.matches(Regex("/data/[A-Za-z0-9]{16}/init")))
        require(bootId.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        return """
            [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 0
            [ "${'$'}(/system/bin/getprop sys.boot_completed)" = 1 ] || exit 0
            [ "${'$'}(head -c 65 /proc/sys/kernel/random/boot_id)" = '$bootId' ] || exit 0
            read up rest < /proc/uptime || exit 0
            [ "${'$'}{up%%.*}" -ge 120 ] 2>/dev/null || exit 0
            h='$helper'
            parent=${'$'}{h%/*}
            [ -d /data ] && [ ! -L /data ] || exit 0
            case "${'$'}(stat -c %u /data)" in 0|1000) ;; *) exit 0;; esac
            data_mode=${'$'}(stat -c %a /data) || exit 0
            [ "${'$'}((8#${'$'}data_mode & 8#2))" -eq 0 ] || exit 0
            [ -d "${'$'}parent" ] && [ ! -L "${'$'}parent" ] && [ "${'$'}(stat -c %u "${'$'}parent")" = 0 ] || exit 0
            owned_file() {
                [ -f "${'$'}1" ] && [ ! -L "${'$'}1" ] && [ -r "${'$'}1" ] &&
                    [ "${'$'}(stat -c '%u:%h' "${'$'}1")" = '0:1' ]
            }
            owned_file "${'$'}h" || exit 0
            owned_file "${'$'}h.safe_flag" && [ ! -s "${'$'}h.safe_flag" ] || exit 0
            for suffix in boot_fail_protect boot_session.work boot_session.duplicate boot_session.completed; do
                f="${'$'}h.${'$'}suffix"
                if [ -e "${'$'}f" ] || [ -L "${'$'}f" ]; then
                    owned_file "${'$'}f" && [ ! -s "${'$'}f" ] || exit 0
                fi
            done
            owned_file "${'$'}h.log" || exit 0
            [ "${'$'}(wc -c < "${'$'}h.log")" -le 256 ] 2>/dev/null || exit 0
            [ "${'$'}(cat "${'$'}h.log")" = '[FATAL] autorun_bootstrap: NEVER EXECUTED' ] || exit 0
            for exe in /proc/[0-9]*/exe; do
                target=${'$'}(readlink "${'$'}exe" 2>/dev/null) || continue
                case "${'$'}target" in "${'$'}h"|"${'$'}h (deleted)") exit 0;; esac
            done
            [ "${'$'}(head -c 65 /proc/sys/kernel/random/boot_id)" = '$bootId' ] || exit 0
            echo 'MAPLESU_IDLE_BOOT:$bootId'
        """.trimIndent()
    }
}
