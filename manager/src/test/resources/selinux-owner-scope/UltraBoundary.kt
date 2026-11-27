package dev.maplesu.manager.runtime.ultra

import android.content.Context
import com.maplesu.core.ksuApp
import dev.maplesu.manager.workmode.ModeRuntime
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Platform and SDK seams; the checker and Legacy credential verifier are production files. */
object SELinuxBoundary {
    var key: String?
        get() = ksuApp.getSharedPreferences("maplesu-ultra", 0).getString("rootKey", null)
        set(value) {
            val prefs = ksuApp.getSharedPreferences("maplesu-ultra", 0)
            if (value == null) { prefs.values.clear(); return }
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            prefs.edit().putString("rootKey", value).putBoolean("keyVerified", true)
                .putString("keyVerifiedFor", digest).commit()
        }
    @Volatile var boot: String? = "boot-a"
    @Volatile var owner: UltraRuntimeClient.ManagerOwnerIdentity? = null
    @Volatile var liveOwner: UltraRuntimeClient.ManagerOwnerIdentity? = null
    @Volatile var ultra = true
    @Volatile var hook = false
    @Volatile var legacyCalls = 0
    @Volatile var hookCalls = 0
    val hookChecks = AtomicInteger()
    val environmentChecks = AtomicInteger()
    val statusCalls = AtomicInteger()
    val rootCalls = AtomicInteger()
    val ownerCalls = AtomicInteger()
    val submittedKeys = Collections.synchronizedList(mutableListOf<String?>())
    @Volatile var status: () -> NativeReceipt = { NativeReceipt(true, 1, true) }
    @Volatile var root: () -> NativeReceipt = { NativeReceipt(true, result = "Enforcing") }
}

class NativeReceipt(
    val succeeded: Boolean,
    private val enforcing: Int = -1,
    private val readable: Boolean = false,
    private val result: String? = null,
) {
    fun int(name: String): Int? = enforcing.takeIf { name == "selinux" }
    fun bool(name: String): Boolean? = readable.takeIf { name == "selinux_readable" }
    fun string(name: String): String? = result.takeIf { name == "result" }
}

object UltraEnvironmentProbe {
    data class Snapshot(val managerOwnerIdentity: UltraRuntimeClient.ManagerOwnerIdentity?)
    fun usesUltraBackend(context: Any): Boolean {
        SELinuxBoundary.environmentChecks.incrementAndGet()
        return SELinuxBoundary.ultra
    }
    fun isRunning() = SELinuxBoundary.ultra
    fun currentSnapshot() = Snapshot(SELinuxBoundary.owner)
    fun currentSnapshot(context: Any) = currentSnapshot()
}

object UltraEnvironmentAutoSetup { fun currentBootId() = SELinuxBoundary.boot }

class UltraRuntimeClient {
    data class ManagerOwnerIdentity(val fullUid: Int, val epoch: Long, val hostInstance: String)
    data class ManagerOwnerIdentityResult(val identity: ManagerOwnerIdentity?)
    private var identityCurrent: () -> Boolean = { true }
    private var credential: UltraLegacyCredential? = null
    internal val legacyCredentialFingerprint: String? get() = credential?.fingerprint
    fun scoped(isCurrent: () -> Boolean): UltraRuntimeClient = UltraRuntimeClient().also {
        it.credential = credential
        it.identityCurrent = { identityCurrent() && isCurrent() }
    }
    internal fun withLegacyCredential(value: UltraLegacyCredential): UltraRuntimeClient =
        scoped { true }.also { it.credential = value }
    // The real client's systemStatus/localCall also does not enter identityCurrent.
    fun systemStatus(): NativeReceipt {
        SELinuxBoundary.statusCalls.incrementAndGet()
        return SELinuxBoundary.status()
    }
    fun queryManagerOwnerIdentity(): ManagerOwnerIdentityResult {
        SELinuxBoundary.ownerCalls.incrementAndGet()
        return ManagerOwnerIdentityResult(SELinuxBoundary.liveOwner)
    }
    fun runRootCommand(command: String): NativeReceipt {
        check(command == "/system/bin/getenforce")
        if (!identityCurrent()) return NativeReceipt(false)
        if (credential == null && (SELinuxBoundary.owner == null || SELinuxBoundary.owner != SELinuxBoundary.liveOwner))
            return NativeReceipt(false)
        SELinuxBoundary.rootCalls.incrementAndGet()
        SELinuxBoundary.submittedKeys.add(credential?.value)
        return SELinuxBoundary.root()
    }
    fun decodeText(text: String?): String? = text
}

object UltraSdkCredentials {
    fun client(context: Context): UltraRuntimeClient {
        val boot = SELinuxBoundary.boot
        val owner = SELinuxBoundary.owner
        val activation = ModeRuntime.activation
        val prefs = context.getSharedPreferences("maplesu-ultra", 0)
        val removal = prefs.getString("environmentRemoval", null)
        val legacy = if (owner == null) UltraLegacyCredential.read(context) else null
        val source = if (legacy == null) UltraRuntimeClient() else UltraRuntimeClient().withLegacyCredential(legacy)
        return source.scoped {
            SELinuxBoundary.boot == boot && SELinuxBoundary.owner == owner &&
                ModeRuntime.activation === activation && prefs.getString("environmentRemoval", null) == removal
        }
    }
    fun client(context: Context, current: () -> Boolean) = client(context).scoped(current)
}
