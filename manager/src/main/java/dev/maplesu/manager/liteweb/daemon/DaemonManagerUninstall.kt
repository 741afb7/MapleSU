package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.recovery.RecoveryBundle
import dev.maplesu.manager.recovery.RecoveryCommandRunner
import dev.maplesu.manager.recovery.RecoveryPaths
import java.io.File
import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient

/** Fixed manager-only operation. Runs in the recovery daemon, never the app being removed. */
class DaemonManagerUninstall(
    private val bundle: RecoveryBundle,
    private val runner: RecoveryCommandRunner,
    private val hidden: () -> Boolean?,
    private val receiptPath: String = RECEIPT_PATH,
    private val recoveryReady: () -> Boolean = { false },
    private val logger: (String) -> Unit = {},
) {
    @Synchronized
    fun uninstall(): String {
        if (hidden() != true) return failure("MANAGER_HIDE_NOT_VERIFIED")
        val verified = runCatching { bundle.verify() }.getOrNull()
        if (verified?.ok != true || verified.manifest?.packageName != PACKAGE) {
            return failure("MANAGER_RECOVERY_NOT_VERIFIED")
        }
        if (!runCatching(recoveryReady).getOrDefault(false)) return failure("MANAGER_FULL_RESTORE_UNAVAILABLE")
        val result = runCatching { runner.run(script(receiptPath)) }.getOrNull()
            ?: return failure("MANAGER_UNINSTALL_FAILED")
        val lines = result.output.lineSequence().map(String::trim).filter { it.startsWith(MARKER) }.toList()
        val success = result.code == 0 && lines.size == 1 && RECEIPT.matches(lines.single())
        return if (success) """{"ok":true,"state":"absent","data":"retained"}"""
        else failure("MANAGER_UNINSTALL_UNVERIFIED")
    }

    private fun failure(code: String): String {
        logger("MAPLESU_MANAGER_UNINSTALL code=$code")
        return """{"ok":false,"code":"$code"}"""
    }

    companion object {
        const val PACKAGE = "dev.maplesu.manager"
        const val RECEIPT_PATH = "${RecoveryPaths.DIR}/manager-uninstall.receipt"
        const val MARKER = "MAPLESU_MANAGER_UNINSTALL"
        val RECEIPT = Regex("^$MARKER ABSENT uid=([0-9]+) data=retained$")
        private val RETAINED_UID = Regex("^$MARKER (?:PREPARED|ABSENT) uid=([0-9]+) data=retained$")

        internal fun script(receiptPath: String): String {
            require(receiptPath.startsWith('/') && !receiptPath.contains('\''))
            return """
                umask 077
                fail() { printf '%s\n' '$MARKER FAILED'; exit 1; }
                DATA='/data/user/0/$PACKAGE'
                RECEIPT='$receiptPath'
                save_receipt() {
                  printf '%s\n' "${'$'}VALUE" > "${'$'}RECEIPT.tmp" && chmod 0600 "${'$'}RECEIPT.tmp" && mv -f "${'$'}RECEIPT.tmp" "${'$'}RECEIPT" || fail
                  [ "${'$'}(cat "${'$'}RECEIPT")" = "${'$'}VALUE" ] || fail
                }
                PACKAGES=${'$'}(pm list packages --user 0 '$PACKAGE' 2>/dev/null) || fail
                if ! printf '%s\n' "${'$'}PACKAGES" | grep -qx 'package:$PACKAGE'; then
                  [ -r "${'$'}RECEIPT" ] || fail
                  OLD=${'$'}(cat "${'$'}RECEIPT") || fail
                  UID_SAVED=${'$'}{OLD#*uid=}; UID_SAVED=${'$'}{UID_SAVED%% *}
                  case "${'$'}UID_SAVED" in ''|*[!0-9]*) fail ;; esac
                  [ "${'$'}UID_SAVED" -ge 10000 ] && [ "${'$'}UID_SAVED" -lt 100000 ] || fail
                  VALUE='$MARKER ABSENT uid='"${'$'}UID_SAVED"' data=retained'
                  PREPARED='$MARKER PREPARED uid='"${'$'}UID_SAVED"' data=retained'
                  [ "${'$'}OLD" = "${'$'}VALUE" ] || [ "${'$'}OLD" = "${'$'}PREPARED" ] || fail
                  [ -d "${'$'}DATA" ] && [ "${'$'}(stat -c '%u' "${'$'}DATA" 2>/dev/null)" = "${'$'}UID_SAVED" ] || fail
                  save_receipt
                  printf '%s\n' "${'$'}VALUE"
                  exit 0
                fi
                [ -d "${'$'}DATA" ] || fail
                UID_SAVED=${'$'}(stat -c '%u' "${'$'}DATA" 2>/dev/null) || fail
                case "${'$'}UID_SAVED" in ''|*[!0-9]*) fail ;; esac
                [ "${'$'}UID_SAVED" -ge 10000 ] && [ "${'$'}UID_SAVED" -lt 100000 ] || fail
                VALUE='$MARKER PREPARED uid='"${'$'}UID_SAVED"' data=retained'
                save_receipt
                RESULT=${'$'}(pm uninstall -k '$PACKAGE' 2>&1) || fail
                printf '%s\n' "${'$'}RESULT" | grep -qx 'Success' || fail
                PACKAGES=${'$'}(pm list packages --user 0 '$PACKAGE' 2>/dev/null) || fail
                if printf '%s\n' "${'$'}PACKAGES" | grep -qx 'package:$PACKAGE'; then fail; fi
                [ -d "${'$'}DATA" ] && [ "${'$'}(stat -c '%u' "${'$'}DATA" 2>/dev/null)" = "${'$'}UID_SAVED" ] || fail
                VALUE='$MARKER ABSENT uid='"${'$'}UID_SAVED"' data=retained'
                save_receipt
                printf '%s\n' "${'$'}VALUE"
            """.trimIndent()
        }

        fun retainedUid(receiptPath: String = RECEIPT_PATH): Int? {
            val receipt = File(receiptPath)
            if (!receipt.exists()) return null
            return runCatching {
                RETAINED_UID.matchEntire(receipt.readText().trim())?.groupValues?.get(1)?.toIntOrNull()
            }.getOrNull()?.takeIf { it in 10000..99999 } ?: -1
        }
    }
}

/** Validate the protected installation identity and current SDK authorization backend. */
class DaemonManagerRecoveryReadiness(
    private val identityText: () -> String? = { File("${RecoveryPaths.DIR}/identity.json").readText() },
    private val sdkReady: (UltraRuntimeClient) -> Boolean = { client ->
        val state = client.environmentState()
        val auth = client.suAuthSnapshot()
        state.succeeded && state.string("state") == "Running" &&
            auth.receipt.succeeded && auth.receipt.array("packages") != null
    },
    private val sdkAccess: () -> DaemonUltraSdkResult = { DaemonUltraSdkAccess(DaemonRuntimeShell).open() },
) {
    fun ready(): Boolean = runCatching {
        val identity = identityText()?.let(RecoveryIdentity::parse) ?: return false
        if (identity.packageName != DaemonManagerUninstall.PACKAGE || identity.uid !in 10000..99999) return false
        val sdk = sdkAccess() as? DaemonUltraSdkResult.Ready ?: return false
        sdkReady(sdk.client)
    }.getOrDefault(false)
}
