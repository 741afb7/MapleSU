package dev.maplesu.manager.runtime.ultra

import android.content.Context
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession

object Fixture {
    var context = Context()
    var boot: String? = "boot-1"
    var owner: UltraRuntimeClient.ManagerOwnerIdentity? = null
    var snapshotOwner: UltraRuntimeClient.ManagerOwnerIdentity? = null
    var state = "Running"
    var probeState = UltraEnvironmentProbe.State.Running
    var stateOk = true
    var kernelRequired = false
    var onCarrier: (() -> Unit)? = null
    var onKernelObserve: (() -> Unit)? = null
    var onKernelSigning: (() -> Unit)? = null
    var onKernelInstall: (() -> Unit)? = null
    var carrierPartition: String? = "boot_a"
    var kernelOutcome = com.maplesu.core.ui.util.FlashResult()
    val kernelOutcomes = java.util.ArrayDeque<com.maplesu.core.ui.util.FlashResult>()
    val kernelAttempts = mutableListOf<Pair<String, String>>()
    var kernelSnapshot = dev.maplesu.manager.installer.InstallerSnapshot()
    var kernelTaskState = "CANCELLED"
    var onKernelStatus: (() -> Unit)? = null
    var component: dev.maplesu.image.UltraInitBootProducer.Artifact? = null
    var rebootTask: String? = null
    var pairedCaptured: java.io.File? = null
    var onCall: ((String, UltraRuntimeClient) -> Unit)? = null
    var outcome = UltraEnvironmentInstaller.Outcome(true, "OK", verified = true)
    val calls = mutableListOf<String>()
    val clients = mutableListOf<Pair<String, UltraRuntimeClient>>()
    fun verifiedRecord(key: String = "fixture-credential-for-legacy") {
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        context.getSharedPreferences("maplesu-ultra", 0).edit().putString("rootKey", key)
            .putString("keyVerifiedFor", fingerprint).putBoolean("keyVerified", true).commit()
    }
    fun reset() {
        context = Context(); boot = "boot-1"; owner = null; snapshotOwner = null; state = "Running"
        probeState = UltraEnvironmentProbe.State.Running; stateOk = true; kernelRequired = false
        onCarrier = null; onCall = null; calls.clear(); clients.clear()
        onKernelObserve = null; onKernelSigning = null; onKernelInstall = null
        carrierPartition = "boot_a"; kernelOutcome = com.maplesu.core.ui.util.FlashResult()
        kernelOutcomes.clear(); kernelAttempts.clear(); kernelSnapshot = dev.maplesu.manager.installer.InstallerSnapshot()
        kernelTaskState = "CANCELLED"; onKernelStatus = null; component = null; rebootTask = null
        pairedCaptured = null
        outcome = UltraEnvironmentInstaller.Outcome(true, "OK", verified = true)
        ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE)
        LiteImplantQuiesceGate.blocked = false; WorkModeSwitchSession.executing = false
        dev.maplesu.manager.runtime.HookRootExecutor.active = false
        verifiedRecord()
    }
}

data class NativeReceipt(val operation: String, val ok: Boolean = true, val sdkError: String = if (ok) "OK" else "ERR_MODULE_TEST_CHANNEL",
                         val sdkErrorCode: Int = if (ok) 0 else -19999, val fields: Map<String, String> = emptyMap(),
                         val schemaVersion: Int = 1, val bridgeVersion: Int = 1, val backend: String = UltraRuntimeClient.BACKEND) {
    val succeeded get() = ok && sdkError == "OK" && sdkErrorCode == 0
    fun string(key: String) = fields[key]
}
class UltraRuntimeClient private constructor(private val legacy: UltraLegacyCredential?, private val current: () -> Boolean) {
    constructor() : this(null, { true })
    companion object { const val BACKEND = "maplesu-ultra" }
    data class ManagerOwnerIdentity(val fullUid: Int, val epoch: Long, val hostInstance: String)
    data class ManagerOwnerIdentityResult(val receipt: NativeReceipt, val identity: ManagerOwnerIdentity?)
    internal val legacyCredentialFingerprint get() = legacy?.fingerprint
    internal fun withLegacyCredential(record: UltraLegacyCredential) = UltraRuntimeClient(record, current)
    fun scoped(next: () -> Boolean) = UltraRuntimeClient(legacy) { current() && next() }
    fun queryManagerOwnerIdentity(): ManagerOwnerIdentityResult {
        Fixture.calls += "owner"; Fixture.onCall?.invoke("owner", this)
        return ManagerOwnerIdentityResult(NativeReceipt("owner", Fixture.owner != null), Fixture.owner)
    }
    private fun invoke(operation: String, fields: Map<String, String>): NativeReceipt {
        Fixture.calls += operation; Fixture.clients += operation to this
        if (!current()) return NativeReceipt(operation, false, "SDK_IDENTITY_SCOPE_CHANGED", -1001)
        Fixture.onCall?.invoke(operation, this)
        return NativeReceipt(operation, Fixture.stateOk || operation != "state", fields = fields)
    }
    fun environmentState() = invoke("state", mapOf("state" to Fixture.state))
    fun installedEnvironmentVersion() = invoke("installed-version", mapOf("version" to "5.2.1"))
    fun sdkVersion() = invoke("sdk-version", mapOf("version" to "5.2.1"))
    fun runRootCommand(script: String) = invoke("root", emptyMap())
    fun decodeText(value: String?) = value
}
object UltraSdkCredentials {
    fun captureOwnerMarker(context: Context): () -> Boolean {
        val boot = Fixture.boot; val activation = ModeRuntime.activation; val owner = Fixture.snapshotOwner
        val marker = UltraRootKeyStore.captureOwnerMarker(context)
        return { !Thread.currentThread().isInterrupted && boot != null && boot == Fixture.boot &&
            activation === ModeRuntime.activation && marker() && owner == Fixture.snapshotOwner }
    }
    fun client(context: Context): UltraRuntimeClient {
        val current = captureOwnerMarker(context)
        val record = if (Fixture.snapshotOwner == null) UltraLegacyCredential.read(context) else null
        return (if (record == null) UltraRuntimeClient() else UltraRuntimeClient().withLegacyCredential(record)).scoped(current)
    }
    fun client(context: Context, current: () -> Boolean) = client(context).scoped(current)
}
object UltraEnvironmentProbe {
    enum class State { Unknown, Running, Fault, NotInstalled }
    data class Snapshot(val managerOwnerIdentity: UltraRuntimeClient.ManagerOwnerIdentity?)
    fun refresh(context: Context, force: Boolean): State { Fixture.calls += "refresh"; return Fixture.probeState }
    fun currentSnapshot(context: Context = Fixture.context) = Snapshot(Fixture.snapshotOwner)
    fun isKernelRootVerified(context: Context) = false
}
object UltraEnvironmentAutoSetup { fun currentBootId() = Fixture.boot }
object UltraIdleEnvironmentRecovery { fun submittedThisBoot(context: Context) = false }
object UltraSdkBuild { const val BUILD_ID = "maplesu-ultra-5.2.1-fixture"; val HELPER_SHA256 = "a".repeat(64); const val APP_VERSION = "5.2.1" }
enum class Source { Readback, Unknown }
data class InstalledIdentity(val helperSha256: String? = null, val helperPath: String? = null, val helperSize: Long? = null,
                             val buildId: String? = null, val bootId: String? = null, val source: Source = Source.Unknown) {
    val known get() = source == Source.Readback && (helperSha256 != null || buildId != null)
    companion object { fun unknown() = InstalledIdentity() }
}
object UltraInstalledIdentityProbe {
    fun read(context: Context, client: UltraRuntimeClient, force: Boolean = false): InstalledIdentity {
        Fixture.calls += "helper"; Fixture.clients += "helper" to client; Fixture.onCall?.invoke("helper", client)
        return InstalledIdentity.unknown()
    }
    fun invalidate() {}
    fun bundledBuildId() = UltraSdkBuild.BUILD_ID
    fun bundledHelperSha256() = UltraSdkBuild.HELPER_SHA256
    fun persistedAppVersion(context: Context) = "5.2.1"
    fun installedHelperPath(): String? = null
    fun normalizeSha(value: String?) = value?.trim()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}
class UltraInstalledKernelObservation {
    var revision = 0L
    val revisions get() = revision
    fun invalidate() { revision++ }
    fun discard(expected: Long) {}
    fun current(): dev.maplesu.manager.installer.InstalledUltraCarrier? = null
    fun publish(expected: Long, carrier: dev.maplesu.manager.installer.InstalledUltraCarrier, current: () -> Boolean) = current()
}
object UltraSdkLogging { fun redact(value: String?) = value.orEmpty() }
object UltraEnvironmentInstaller {
    enum class Intent { UpgradeToBundledBuild }
    data class Outcome(val ok: Boolean, val sdkError: String, val reason: String? = null, val verified: Boolean = false,
                       val submissionUnknown: Boolean = false, val sdkErrorCode: Int? = null, val detail: String? = null, val buildId: String? = null)
    fun install(context: Context, client: UltraRuntimeClient, intent: Intent): Outcome {
        Fixture.calls += "install"; Fixture.clients += "install" to client; return Fixture.outcome
    }
}
