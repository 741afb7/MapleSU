package dev.maplesu.manager.runtime.ultra

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Log
import dev.maplesu.manager.compat.ultraPreferences
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession

/** Re-identifies the signed current installation through the resident owner channel. */
internal object UltraIdentityRecovery {
    internal fun blocked(context: Context, preparingUpgrade: Boolean = false, freshIdentity: Boolean = false): Boolean =
        Thread.currentThread().isInterrupted || LiteImplantQuiesceGate.isBlocked() ||
            UltraRootKeyStore.isSuspended(context) || WorkModeSwitchSession.executing ||
            (!freshIdentity && (ModeRuntime.active == WorkMode.KSU ||
                ModeRuntime.latestDetection?.trace?.firstOrNull { it.id == "S1" }?.ok == true))

    @Synchronized
    fun recover(context: Context, freshIdentity: Boolean = false): String {
        val boot = UltraEnvironmentAutoSetup.currentBootId()
        val activation = ModeRuntime.activation
        val sourceCurrent = if (freshIdentity) UltraRootKeyStore.captureOwnerMarker(context) else null
        fun current() = !blocked(context, freshIdentity = freshIdentity) && (!freshIdentity ||
            ModeRuntime.activation === activation && UltraEnvironmentAutoSetup.currentBootId() == boot && sourceCurrent?.invoke() == true)
        fun observe(): UltraEnvironmentProbe.Snapshot = if (freshIdentity) {
            UltraEnvironmentProbe.observeFresh(context)
        } else {
            UltraEnvironmentProbe.refresh(context, force = true)
            UltraEnvironmentProbe.currentSnapshot(context)
        }
        fun record(result: String): String {
            runCatching {
                context.ultraPreferences("ultra-identity-recovery", Context.MODE_PRIVATE).edit()
                    .putString("boot", boot).putString("result", result)
                    .putLong("at", System.currentTimeMillis()).apply()
            }.onFailure { Log.w("MapleSUUltraIdentity", "Identity recovery diagnostics failed", it) }
            return result
        }
        return try {
            if (!current()) return record("RECOVERY_SUSPENDED")
            if (boot == null) return record("RECOVERY_BOOT_UNAVAILABLE")
            val client = UltraRuntimeClient()
            val owner = client.queryManagerOwnerIdentity().identity
            if (!current()) return record("RECOVERY_SCOPE_CHANGED")
            val legacy = if (owner == null) UltraLegacyCredential.read(context) else null
            if (legacy != null) {
                val observed = observe()
                if (!current()) return record("RECOVERY_SCOPE_CHANGED")
                if (observed.legacyCredentialFingerprint == legacy.fingerprint && observed.kernelRootVerified)
                    return record("RECOVERY_LOCAL_KEY_VERIFIED")
            }
            if (owner == null) {
                // This protected recovery endpoint is a source, not a replay of a failed business request.
                val candidate = client.claimManagerIdentity()
                if (!current()) return record("RECOVERY_SCOPE_CHANGED")
                if (candidate != null) {
                    val candidateActivation = ModeRuntime.activation
                    val candidateCurrent = UltraRootKeyStore.captureOwnerMarker(context)
                    fun claimCurrent() = current() && ModeRuntime.activation === candidateActivation &&
                        UltraEnvironmentAutoSetup.currentBootId() == boot && candidateCurrent()
                    val legacyClient = UltraRuntimeClient().withLegacyCredential(candidate).scoped(::claimCurrent)
                    if (claimCurrent() && uid(legacyClient) == 0 && candidate.storeVerified(context, ::claimCurrent)) {
                        val observed = observe()
                        if (!claimCurrent()) return record("RECOVERY_SCOPE_CHANGED")
                        return record(if (observed.legacyCredentialFingerprint == candidate.fingerprint && observed.kernelRootVerified)
                            "RECOVERY_LEGACY_RECOVERED" else "RECOVERY_LEGACY_IDENTITY_PENDING")
                    }
                    return record("RECOVERY_LEGACY_VERIFICATION_FAILED")
                }
                return record(UltraSdkSession.restore(context) ?: "RECOVERY_IDENTITY_PENDING")
            }
            if (!current() || UltraEnvironmentAutoSetup.currentBootId() != boot) return record("RECOVERY_SCOPE_CHANGED")
            val observed = observe()
            record(if (current() && observed.managerOwnerIdentity == owner)
                "RECOVERY_OWNER_VERIFIED" else "RECOVERY_IDENTITY_PENDING")
        } catch (error: Exception) {
            record("RECOVERY_EXCEPTION:${error.javaClass.simpleName}")
            throw error
        }
    }

    /** A delegated caller receives identity facts only, never a reusable credential. */
    internal fun readForRoot(managerUid: Int, withBuild: Boolean): Bundle {
        check(Process.myUid() == 0 && managerUid % 100_000 in 10_000..19_999)
        return Bundle().apply {
            putInt("callerUid", managerUid)
            putBoolean("identityRequiresCallerQuery", true)
            if (withBuild) putString("buildId", UltraSdkBuild.BUILD_ID)
        }
    }

    internal fun uid(client: UltraRuntimeClient): Int? = client.runRootCommand("/system/bin/id -u").let {
        if (it.succeeded) client.decodeText(it.string("result"))?.trim()?.toIntOrNull() else null
    }
}
