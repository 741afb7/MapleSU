package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.os.Looper
import android.util.Log
import dev.maplesu.manager.runtime.HookRootExecutor
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.io.File

/** Boot-local installation state; authorization is always checked by the current task channel. */
internal object UltraSdkSession {
    private const val PREFS = "maplesu-boot-sdk"
    private const val RECORD = "session"
    private const val BOOTSTRAP_SUBMITTED = "bootstrapSubmitted"

    /** Explicit startup path; availability/page reads never initialize a gateway. */
    fun bootstrap(context: Context, bootId: String): UltraBootSdkGateway.Result =
        bootstrap(context, bootId) { true }

    @Synchronized
    internal fun bootstrap(context: Context, bootId: String, isCurrent: () -> Boolean): UltraBootSdkGateway.Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        val hook = HookRootExecutor.production(context)
        val preferences = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        fun allowed() = isCurrent() && UltraEnvironmentAutoSetup.currentBootId() == bootId &&
            !Thread.currentThread().isInterrupted && hook.available() &&
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null
        if (!isCurrent()) return UltraBootSdkGateway.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
        val result = UltraBootSdkGateway.connect(preferences.getString(BOOTSTRAP_SUBMITTED, null) == bootId,
            object : UltraBootSdkGateway.Port {
                override fun current() = allowed()
                override fun query() = gateway(context, bootId, isCurrent)
                override fun markSubmitted() = allowed() && preferences.edit()
                    .putString(BOOTSTRAP_SUBMITTED, bootId).commit()
                override fun start(): String? {
                    if (!allowed()) return "SESSION_CHANGED"
                    val result = hook.run(gatewayCommand(context, start = true))
                    return if (!isCurrent()) "SDK_IDENTITY_SCOPE_CHANGED" else if (result.ok) null else UltraBootSdkGateway.commandFailure(result.code,
                        result.output + "\n" + result.detail)
                }
                override fun bind(receipt: UltraBootSdkGateway.Receipt) =
                    allowed() && bind(context, bootId, receipt.kernelBase, isCurrent)
            })
        return if (isCurrent()) result else result.copy(bound = false, reason = "SDK_IDENTITY_SCOPE_CHANGED")
    }

    private fun gatewayCommand(context: Context, start: Boolean): String {
        val source = File(context.applicationInfo.nativeLibraryDir, "libmaplesu_policy.so")
        val quoted = "'${source.absolutePath.replace("'", "'\\''")}'"
        return "exec $quoted " + if (start) "boot-sdk-start" else "boot-sdk-info"
    }

    private fun gateway(context: Context, bootId: String, isCurrent: () -> Boolean = { true }): UltraBootSdkGateway.Receipt? {
        if (!isCurrent() || Thread.currentThread().isInterrupted || UltraEnvironmentAutoSetup.currentBootId() != bootId ||
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) != null) return null
        val hook = HookRootExecutor.production(context)
        if (!hook.available() || !isCurrent()) return null
        val result = hook.run(gatewayCommand(context, start = false))
        return if (result.ok && isCurrent() && UltraEnvironmentAutoSetup.currentBootId() == bootId)
            UltraBootSdkGateway.parse(result.output, bootId) else null
    }

    fun current(context: Context): UltraBootSdkSession? {
        val saved = UltraBootSdkSession.parse(context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(RECORD, null)) ?: return null
        return saved.takeIf { it.current(UltraEnvironmentAutoSetup.currentBootId()) }
    }

    internal fun captureOwnerMarker(context: Context): () -> Boolean {
        val session = current(context)
        val bootId = UltraEnvironmentAutoSetup.currentBootId()
        val activation = ModeRuntime.activation
        return { UltraEnvironmentAutoSetup.currentBootId() == bootId && ModeRuntime.activation === activation &&
            current(context)?.bootId == session?.bootId }
    }

    fun bind(context: Context, bootId: String, expectedKernelBase: String): Boolean =
        bind(context, bootId, expectedKernelBase) { true }

    @Synchronized
    private fun bind(context: Context, bootId: String, expectedKernelBase: String,
                     isCurrent: () -> Boolean): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        val candidate = UltraBootSdkSession.create(bootId) ?: return false
        fun allowed() = isCurrent() && candidate.current(UltraEnvironmentAutoSetup.currentBootId()) &&
            !Thread.currentThread().isInterrupted && UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null
        if (!allowed()) return false
        val client = UltraRuntimeClient().scoped(::allowed)
        if (!client.testBasics("Channel").succeeded) return false
        val kernelBase = client.testBasics("KernelBase")
        if (!kernelBase.succeeded || !UltraBootSdkGateway.kernelBaseMatches(
            client.decodeText(kernelBase.string("info")), expectedKernelBase) || !allowed()) return false
        val previous = current(context)
        if (previous != null) return previous.bootId == bootId
        return context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(RECORD, candidate.encode()).commit() && allowed()
    }

    /** Read-only probes do not call this. It belongs to an explicit bootstrap operation. */
    fun ensure(context: Context): UltraBootSdkInstall.Result = ensure(context) { true }

    internal fun ensure(context: Context, isCurrent: () -> Boolean): UltraBootSdkInstall.Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        if (!isCurrent()) return UltraBootSdkInstall.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
        val session = current(context) ?: return UltraBootSdkInstall.Result(false, false, "SDK_SESSION_ABSENT")
        return ensure(context, session, isCurrent)
    }

    @Synchronized
    internal fun ensure(context: Context, session: UltraBootSdkSession,
                        isCurrent: () -> Boolean): UltraBootSdkInstall.Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        fun allowed() = isCurrent() && usable(context, session)
        if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
        val hook = HookRootExecutor.production(context).available()
        if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
        if (hook) {
            val gateway = gateway(context, session.bootId, ::allowed)
                ?: return UltraBootSdkInstall.Result(false, false,
                    if (allowed()) "SDK_GATEWAY_UNAVAILABLE" else "SDK_IDENTITY_SCOPE_CHANGED")
            if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
            if (!gateway.ready || gateway.bootId != session.bootId)
                return UltraBootSdkInstall.Result(false, false, "SDK_GATEWAY_IDENTITY_CHANGED")
            if (!gateway.environment)
                return UltraBootSdkInstall.Result(false, false, "SDK_ENVIRONMENT_ABI_UNAVAILABLE")
        } else if (!session.installSubmitted) {
            return UltraBootSdkInstall.Result(false, false, "SDK_GATEWAY_UNAVAILABLE")
        }
        if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_IDENTITY_SCOPE_CHANGED")
        val client = client(context, session).scoped(isCurrent)
        var preparationReason: String? = null
        return UltraBootSdkInstall.ensure(session.installSubmitted, object : UltraBootSdkInstall.Port {
            override fun current() = allowed()
            override fun channelVerified() = client.testBasics("Channel").succeeded
            override fun kernelBaseVerified() = client.testBasics("KernelBase").succeeded
            override fun environmentState(): String? = client.environmentState()
                .takeIf { it.succeeded }?.string("state")
            override fun prepare(): Boolean {
                if (!allowed()) return false
                val failure = UltraPolicyBackendDeployment.prepare(context, client)
                preparationReason = failure?.takeIf {
                    Regex("(?:MODULE_(?:POLICY_)?TOOLS|POLICY_BACKEND)_[A-Z_]+" +
                        "(?::(?:ERR_|MAPLESU_)[A-Z0-9_]{1,64}|:-?[0-9]{1,10}(?::[a-z-]{1,32})?)?")
                        .matches(it)
                }
                return failure == null && allowed()
            }
            override fun markSubmitted(): Boolean = allowed() &&
                context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(RECORD, session.submitted().encode()).commit()
            override fun submit() = client.installEnvironment("HotLoad", "").also { receipt ->
                UltraSdkLogging.logSafely(if (receipt.succeeded) Log.INFO else Log.WARN,
                    "HotLoad operation=${receipt.operation} sdkError=${receipt.sdkError} code=${receipt.sdkErrorCode}")
            }
            override fun rootVerified() = UltraIdentityRecovery.uid(client) == 0
            override fun whitelistVerified(): Boolean {
                val result = UltraSelfWhitelist.ensureCurrentAuthority(context, client, ::allowed)
                return result.succeeded
            }
            override fun managerSuVerified(): Boolean {
                val su = UltraSuChannel()
                return UltraSelfWhitelist.verifyExec(
                    current = ::allowed,
                    probe = { allowed() && su.rootUid() == 0 && allowed() },
                    rootVerified = { UltraIdentityRecovery.uid(client) == 0 },
                    refresh = { UltraSelfWhitelist.ensureCurrentAuthority(context, client, ::allowed).succeeded },
                )
            }
            override fun moduleListVerified(): Boolean {
                val receipt = client.moduleList()
                return receipt.succeeded && receipt.array("modules") != null
            }
        }).let { result ->
            if (!allowed()) result.copy(ready = false, reason = "SDK_IDENTITY_SCOPE_CHANGED")
            else if (result.reason == "HOTLOAD_PREPARATION_FAILED" && preparationReason != null)
                result.copy(reason = "${result.reason}:$preparationReason")
            else result
        }.also { UltraEnvironmentProbe.invalidateAvailability() }
    }

    fun client(context: Context, session: UltraBootSdkSession = requireNotNull(current(context))): UltraRuntimeClient =
        UltraRuntimeClient().scoped { usable(context, session) }

    /** Ordinary-UID failure permits one guarded UID refresh, followed by a fresh exec. */
    fun verifyManagerSu(context: Context, session: UltraBootSdkSession, probe: () -> Boolean): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        val client = client(context, session)
        return UltraSelfWhitelist.verifyExec(
            current = { usable(context, session) && HookRootExecutor.production(context).available() },
            probe = probe,
            rootVerified = { UltraIdentityRecovery.uid(client) == 0 },
            refresh = {
                val refreshed = UltraSelfWhitelist.ensureCurrentAuthority(context, client) {
                    usable(context, session) && HookRootExecutor.production(context).available()
                }
                refreshed.succeeded
            },
        )
    }

    private fun same(context: Context, session: UltraBootSdkSession): Boolean = current(context)?.let {
        it.bootId == session.bootId
    } == true

    private fun usable(context: Context, session: UltraBootSdkSession): Boolean =
        same(context, session) && !Thread.currentThread().isInterrupted &&
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null

    /** Reacquire current task identity; no application-owned credential survives this path. */
    @Synchronized
    fun restore(context: Context): String? {
        if (current(context) != null) return "BOOT_SDK_CURRENT"
        if (UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) != null) return null
        val boot = UltraEnvironmentAutoSetup.currentBootId() ?: return null
        val receipt = gateway(context, boot) ?: return null
        return if (receipt.ready && bind(context, boot, receipt.kernelBase)) "BOOT_SDK_RESTORED" else null
    }

    fun prepareRecovery(context: Context): Boolean =
        current(context)?.let { usable(context, it) && client(context, it).queryManagerOwnerIdentity().succeeded } == true
}

/** Current-installation fences carry only boot, activation and verified owner facts. */
internal object UltraSdkCredentials {
    fun captureOwnerMarker(context: Context): () -> Boolean {
        val app = context.applicationContext
        val boot = UltraEnvironmentAutoSetup.currentBootId()
        val activation = ModeRuntime.activation
        val owner = UltraEnvironmentProbe.currentSnapshot().managerOwnerIdentity
        val removalCurrent = dev.maplesu.manager.modules.UltraRootKeyStore.captureOwnerMarker(app)
        return {
            !Thread.currentThread().isInterrupted && boot != null &&
                UltraEnvironmentAutoSetup.currentBootId() == boot && ModeRuntime.activation === activation &&
                removalCurrent() &&
                UltraEnvironmentProbe.currentSnapshot().managerOwnerIdentity == owner
        }
    }

    fun client(context: Context): UltraRuntimeClient {
        val current = captureOwnerMarker(context)
        val owner = UltraEnvironmentProbe.currentSnapshot(context).managerOwnerIdentity
        val legacy = if (owner == null) UltraLegacyCredential.read(context) else null
        val client = if (legacy == null) UltraRuntimeClient() else UltraRuntimeClient().withLegacyCredential(legacy)
        return client.scoped(current)
    }
    fun client(context: Context, current: () -> Boolean): UltraRuntimeClient = client(context).scoped(current)
    fun hookWithoutSdk(context: Context): Boolean =
        HookRootExecutor.production(context).available() && UltraSdkSession.current(context) == null
    fun suspended(context: Context): Boolean = dev.maplesu.manager.modules.UltraRootKeyStore.isSuspended(context)
}
