package dev.maplesu.manager.liteweb.daemon

import android.util.Log
import dev.maplesu.manager.litehidden.HostManifestCodec
import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.liteweb.LiteWebSuApplication
import dev.maplesu.manager.liteweb.LiteWebSuCatalog
import dev.maplesu.manager.liteweb.LiteWebSuLog
import dev.maplesu.manager.liteweb.LiteWebSuMutation
import dev.maplesu.manager.liteweb.LiteWebSuResult
import dev.maplesu.manager.liteweb.LiteWebSuSdk
import dev.maplesu.manager.liteweb.LiteWebSuSnapshot
import dev.maplesu.manager.liteweb.LiteWebSuperuserManager
import dev.maplesu.manager.liteweb.LiteWebSuperuserPort
import dev.maplesu.manager.runtime.ultra.NativeReceipt
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSdkLogging
import dev.maplesu.manager.runtime.ultra.authorizationDiagnosticSummary
import dev.maplesu.manager.runtime.ultra.receiptErrorName
import java.io.File

/** Uses only Ultra's SDK. PackageManager commands supply display identities, never grant decisions. */
class DaemonSuperuserPort(
    private val access: DaemonUltraSdkAccess,
    private val shell: DaemonShell,
    private val recoveryRoot: String = "/data/adb/maplesu/recovery",
    private val managerPackage: String = "dev.maplesu.manager",
) : LiteWebSuperuserPort {
    private var cachedCatalog: Pair<Long, LiteWebSuCatalog>? = null
    private val manager = LiteWebSuperuserManager(
        sdk = DaemonSuSdk(access, shell, recoveryRoot, managerPackage), catalog = ::catalog, protectedPackages = ::protectedPackages,
        mutationLock = SDK_MUTATIONS,
    )

    override fun snapshot(): LiteWebSuResult<LiteWebSuSnapshot> = manager.snapshot()
    override fun setAllowed(packageName: String, allowed: Boolean): LiteWebSuResult<LiteWebSuMutation> =
        manager.setAllowed(packageName, allowed)
    override fun log(): LiteWebSuResult<LiteWebSuLog> = manager.log()
    override fun clearLog(): LiteWebSuResult<Unit> = manager.clearLog()
    override fun setLogging(enabled: Boolean): LiteWebSuResult<Boolean> = manager.setLogging(enabled)

    @Synchronized
    private fun catalog(): LiteWebSuCatalog {
        val now = System.nanoTime()
        cachedCatalog?.let { (at, value) -> if (now - at < 1_000_000_000L) return value }
        val value = readCatalog()
        if (value.available) cachedCatalog = now to value
        return value
    }

    private fun readCatalog(): LiteWebSuCatalog {
        // --user all returns full UIDs, including work profiles. Never synthesize a user UID.
        val all = shell.run("/system/bin/pm list packages -U --user all 2>/dev/null")
        val parsed = if (all.code == 0) DaemonSuPackageCatalog.parse(all.output) else null
        if (parsed != null) return LiteWebSuCatalog(parsed, available = true, complete = true)
        val current = shell.run("/system/bin/pm list packages -U 2>/dev/null")
        val fallback = if (current.code == 0) DaemonSuPackageCatalog.parse(current.output) else null
        return if (fallback != null) LiteWebSuCatalog(fallback, available = true, complete = false)
            else LiteWebSuCatalog()
    }

    private fun protectedPackages(): Set<String> {
        val result = linkedSetOf(managerPackage)
        val identity = File(recoveryRoot, "identity.json")
        if (identity.isFile && identity.length() <= 4096) {
            RecoveryIdentity.parse(identity.readText())?.packageName?.let { result += it }
        }
        val hostRoot = File(recoveryRoot, "host")
        if (!hostRoot.exists()) return result
        check(hostRoot.isDirectory && hostRoot.canRead())
        val children = checkNotNull(hostRoot.listFiles())
        for (host in children) {
            if (!host.isDirectory || !LiteWebSuperuserManager.validPackage(host.name)) continue
            // A surviving host directory can still contain the active loader after interrupted setup.
            result += host.name
            val file = File(host, "host.manifest")
            if (file.isFile && file.length() <= 256 * 1024) {
                HostManifestCodec.decode(file.readText())?.packageName?.let { result += it }
            }
        }
        return result
    }

    private companion object {
        val SDK_MUTATIONS = Any()
    }
}

/** Strict pm output parser: malformed or truncated catalogs are unknown, never an empty success. */
internal object DaemonSuPackageCatalog {
    private val line = Regex("^package:([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*)\\s+uid:(\\d+)$")
    fun parse(output: String): List<LiteWebSuApplication>? {
        if (output.length > 2 * 1024 * 1024) return null
        val result = linkedSetOf<LiteWebSuApplication>()
        for (raw in output.lineSequence()) {
            if (raw.isBlank()) continue
            val match = line.matchEntire(raw.trim()) ?: return null
            val uid = match.groupValues[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            result += LiteWebSuApplication(match.groupValues[1], uid)
            if (result.size > 10_000) return null
        }
        return result.toList()
    }
}

private class DaemonSuSdk(
    private val access: DaemonUltraSdkAccess,
    private val shell: DaemonShell,
    private val recoveryRoot: String,
    private val managerPackage: String,
) : LiteWebSuSdk {
    @Volatile private var bootAuthority = false
    override val retainsCommittedDecisions: Boolean get() = bootAuthority

    private fun authority(client: UltraRuntimeClient) =
        DaemonBootSuperuserAuthority.production(shell, recoveryRoot, managerPackage, client)

    private fun <T> withClient(block: (UltraRuntimeClient) -> LiteWebSuResult<T>): LiteWebSuResult<T> =
        when (val opened = access.open()) {
            is DaemonUltraSdkResult.Unavailable -> LiteWebSuResult.Failed(opened.code, unavailable = true)
            is DaemonUltraSdkResult.Ready -> runCatching {
                bootAuthority = opened.source == "boot-session" || opened.source == "current-task"
                val environment = opened.client.environmentState()
                if (!environment.succeeded || environment.string("state") != "Running") {
                    UltraSdkLogging.logSafely(Log.WARN, "ultra-su-environment ${environment.authorizationDiagnosticSummary()}")
                    LiteWebSuResult.Failed("SU_AUTH_ULTRA_NOT_RUNNING", unavailable = true)
                } else block(opened.client)
            }
                .getOrElse {
                    UltraSdkLogging.logSafely(Log.WARN, "ultra-su-sdk-exception ${it.javaClass.simpleName}")
                    LiteWebSuResult.Failed("SU_AUTH_SDK_EXCEPTION", unavailable = true)
                }
        }

    override fun packages(): LiteWebSuResult<Set<String>> = withClient { client ->
        if (bootAuthority) {
            val packages = authority(client)?.packages()
                ?: return@withClient LiteWebSuResult.Failed("SU_AUTH_AUTHORITY_UNAVAILABLE", unavailable = true)
            return@withClient LiteWebSuResult.Ok(packages)
        }
        val snapshot = client.suAuthSnapshot()
        if (!snapshot.receipt.succeeded) return@withClient failed(snapshot.receipt)
        if (snapshot.receipt.array("packages")?.length() != snapshot.packages.size ||
            snapshot.packages.any { !LiteWebSuperuserManager.validPackage(it) }) {
            return@withClient LiteWebSuResult.Failed("SU_AUTH_LIST_UNREADABLE", unavailable = true)
        }
        LiteWebSuResult.Ok(snapshot.packages.toSet())
    }

    override fun setAllowed(packageName: String, allowed: Boolean): LiteWebSuResult<Unit> = withClient { client ->
        if (bootAuthority) {
            return@withClient if (authority(client)?.setAllowed(packageName, allowed) == true) LiteWebSuResult.Ok(Unit)
            else LiteWebSuResult.Failed("SU_AUTH_AUTHORITY_SYNC_PENDING", rollback = "pending-apply")
        }
        outcome(if (allowed) client.addSuAuth(packageName) else client.removeSuAuth(packageName))
    }

    override fun loggingEnabled(): LiteWebSuResult<Boolean> = withClient { client ->
        val receipt = client.isLogEnabled()
        if (!receipt.succeeded) failed(receipt) else receipt.bool("enabled")?.let { LiteWebSuResult.Ok(it) }
            ?: LiteWebSuResult.Failed("SU_LOGGING_UNREADABLE", unavailable = true)
    }

    override fun setLogging(enabled: Boolean): LiteWebSuResult<Unit> = withClient { outcome(it.setLogEnabled(enabled)) }
    override fun clearLog(): LiteWebSuResult<Unit> = withClient { outcome(it.clearLog()) }
    override fun log(): LiteWebSuResult<String> = withClient { client ->
        val receipt = client.readLog()
        if (!receipt.succeeded) return@withClient failed(receipt)
        // An empty log is valid; a missing field is not. Decode only the SDK's URL field.
        val raw = receipt.data?.opt("log") as? String
            ?: return@withClient LiteWebSuResult.Failed("SU_LOG_UNREADABLE", unavailable = true)
        LiteWebSuResult.Ok(UltraSdkLogging.redact(client.decodeText(raw)))
    }

    private fun outcome(receipt: NativeReceipt): LiteWebSuResult<Unit> =
        if (receipt.succeeded) LiteWebSuResult.Ok(Unit) else failed(receipt)

    private fun failed(receipt: NativeReceipt): LiteWebSuResult.Failed {
        val code = receiptErrorName(receipt.succeeded, receipt.sdkError)
            .takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,127}")) } ?: "SU_AUTH_SDK_FAILED"
        UltraSdkLogging.logSafely(Log.WARN, "ultra-su-sdk-failed ${receipt.authorizationDiagnosticSummary()}")
        return LiteWebSuResult.Failed(code, unavailable = true)
    }
}
