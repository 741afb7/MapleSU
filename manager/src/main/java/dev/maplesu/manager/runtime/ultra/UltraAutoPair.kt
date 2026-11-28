package dev.maplesu.manager.runtime.ultra

import android.content.Context
import android.os.Looper
import java.util.concurrent.CancellationException

/** Reconciles actual SDK and standard-SU readiness; it stores no pairing secret. */
object UltraAutoPair {
    data class Result(val paired: Boolean, val state: UltraEnvironmentProbe.State, val reason: String)

    fun ensure(context: Context, maintenance: Boolean = true): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ULTRA_READINESS_BACKGROUND_THREAD_REQUIRED" }
        val app = context.applicationContext
        if (UltraIdentityRecovery.blocked(app)) return Result(false, UltraEnvironmentProbe.State.Unknown, "RECOVERY_SUSPENDED")
        UltraEnvironmentProbe.refresh(app, force = false)
        val observation = UltraEnvironmentProbe.currentSnapshot(app)
        val owner = observation.managerOwnerIdentity
        if (owner == null && observation.legacyCredentialFingerprint == null)
            return Result(false, observation.state, "INSTALL_IDENTITY_PENDING")
        rootFailure(observation)?.let { return it }
        val current = UltraSdkCredentials.captureOwnerMarker(app)
        val scope = (if (owner != null) UltraAuthorization.captureOwnerScope(app, owner, current)
            else UltraAuthorization.captureScope(app)?.takeIf {
                it.client.legacyCredentialFingerprint == observation.legacyCredentialFingerprint
            }) ?: return Result(false, observation.state, "SDK_IDENTITY_SCOPE_CHANGED")
        return complete(observation, scope.current, verifyManagerSu = {
            val su = UltraSuChannel(timeoutMillis = 700L)
            UltraSelfWhitelist.verifyExec(current = scope.current, probe = { su.rootUid() == 0 },
                rootVerified = { UltraEnvironmentProbe.hasCurrentRootProof(app) },
                refresh = {
                    if (owner != null) UltraSelfWhitelist.ensureOwner(app, owner, scope.current).succeeded
                    else UltraSelfWhitelist.ensureCurrentAuthority(app, scope.client, scope.current).succeeded
                })
        }, maintain = { if (maintenance) UltraPairingMaintenance.request(app) })
    }

    internal fun complete(observation: UltraEnvironmentProbe.Snapshot, current: () -> Boolean,
                          verifyManagerSu: () -> Boolean, maintain: () -> Unit): Result {
        rootFailure(observation)?.let { return it }
        if (!current()) return Result(false, UltraEnvironmentProbe.State.Unknown, "SDK_IDENTITY_SCOPE_CHANGED")
        val verified = try {
            !Thread.currentThread().isInterrupted && verifyManagerSu()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
        if (!current()) return Result(false, UltraEnvironmentProbe.State.Unknown, "SDK_IDENTITY_SCOPE_CHANGED")
        if (verified) maintain()
        return if (current()) Result(verified, observation.state, if (verified) "RUNNING" else "MANAGER_SU_UNVERIFIED")
            else Result(false, UltraEnvironmentProbe.State.Unknown, "SDK_IDENTITY_SCOPE_CHANGED")
    }

    private fun rootFailure(observation: UltraEnvironmentProbe.Snapshot): Result? {
        if (observation.rootAvailable) return null
        val detail = observation.rootProbeDetail?.takeIf(String::isNotBlank)?.let { UltraSdkLogging.redact(it) }
        return Result(false, observation.state, "SDK_ROOT_UNVERIFIED" + detail?.let { ":$it" }.orEmpty())
    }
}
