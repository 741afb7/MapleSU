package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.liteweb.LiteWebUnhideCodes
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipFile

class UltraSdkSuAuthRevoke(
    private val shell: DaemonShell,
    private val recoveryRoot: String = "/data/adb/maplesu/recovery",
    private val managerPackage: String = "dev.maplesu.manager",
    loadSdk: (String) -> Boolean = UltraSdkLibrary::load,
) : DaemonSuAuthRevoke {
    private val access = DaemonUltraSdkAccess(shell, recoveryRoot, managerPackage, loadSdk)

    override fun revoke(hostPackage: String): DaemonRevokeResult {
        if (hostPackage.isEmpty()) {
            return DaemonRevokeResult(false, LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED, "host_missing")
        }
        val sdk = when (val opened = access.open()) {
            is DaemonUltraSdkResult.Ready -> opened
            is DaemonUltraSdkResult.Unavailable -> return DaemonRevokeResult(
                false,
                LiteWebUnhideCodes.SU_AUTH_BACKEND_UNAVAILABLE,
                opened.detail,
            )
        }
        return runCatching {
            // The durable revoke and its full-UID mirror/readback are one existing
            // authority flow. A user-0 package projection cannot verify other users.
            val revoked = DaemonBootSuperuserAuthority.production(shell, recoveryRoot, managerPackage, sdk.client)
                ?.setAllowed(hostPackage, false) == true
            if (revoked) DaemonRevokeResult(true, detail = "authority=revoked;mirror=verified;id=${sdk.source}")
            else DaemonRevokeResult(false, LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED, "authority_sync_pending")
        }.getOrElse {
            DaemonRevokeResult(false, LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED, it.javaClass.simpleName)
        }
    }
}

sealed interface DaemonUltraSdkResult {
    data class Ready(val client: UltraRuntimeClient, val source: String) : DaemonUltraSdkResult
    data class Unavailable(val code: String, val detail: String) : DaemonUltraSdkResult
}

/** Shared keyless SDK entry. Authority is checked against the current task on every operation. */
class DaemonUltraSdkAccess(
    private val shell: DaemonShell,
    private val recoveryRoot: String = "/data/adb/maplesu/recovery",
    private val managerPackage: String = "dev.maplesu.manager",
    private val loadSdk: (String) -> Boolean = UltraSdkLibrary::load,
    private val bootId: () -> String? = {
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
    },
) {
    @Volatile private var ready: DaemonUltraSdkResult.Ready? = null
    private var readyMaterial: String? = null

    @Synchronized
    fun open(): DaemonUltraSdkResult {
        val currentBoot = bootId()?.takeIf { it.isNotBlank() }
            ?: return invalidate("boot_id_unavailable")
        ready?.takeIf { readyMaterial == currentBoot }?.let { return it }
        val library = ensureSdkLibrary() ?: return invalidate("sdk_library_unavailable")
        if (!runCatching { loadSdk(library) }.getOrDefault(false)) return invalidate("sdk_library_not_loadable")
        val client = UltraRuntimeClient().scoped { bootId() == currentBoot }
        val channel = client.testBasics("Channel")
        if (!channel.succeeded) return invalidate("sdk_channel:${channel.sdkError}")
        return DaemonUltraSdkResult.Ready(client, "current-task").also {
            ready = it
            readyMaterial = currentBoot
        }
    }

    private fun invalidate(detail: String): DaemonUltraSdkResult {
        ready = null
        readyMaterial = null
        return unavailable(detail)
    }

    private fun unavailable(detail: String): DaemonUltraSdkResult.Unavailable {
        System.err.println("MAPLESU_RECOVERY_UNAVAILABLE reason=$detail")
        return DaemonUltraSdkResult.Unavailable("SU_AUTH_BACKEND_UNAVAILABLE", detail)
    }

    internal fun ensureSdkLibrary(classpathApks: List<String> = currentClasspathApks()): String? {
        val dir = File(recoveryRoot, "native")
        val target = File(dir, SDK_LIBRARY)
        val apk = File(recoveryRoot, APK_NAME)
        val candidates = (classpathApks.mapNotNull { path ->
            File(path).takeIf { it.isAbsolute && it.extension == "apk" && it.isFile && it.canRead() }
        } + listOfNotNull(apk.takeIf { it.isFile && it.canRead() })).distinctBy { it.absolutePath }
        // Each process loads SDK bytes from its current packaged source.
        if (candidates.isEmpty()) return null
        for (candidate in candidates) {
            extractSdkLibrary(candidate, dir, target)?.let { return it }
        }
        return null
    }

    private fun currentClasspathApks(): List<String> = buildList {
        DaemonClasspathApk.path()?.let { add(it) }
        addAll(System.getProperty("java.class.path").orEmpty().split(File.pathSeparatorChar))
        addAll(System.getenv("CLASSPATH").orEmpty().split(File.pathSeparatorChar))
    }

    private fun extractSdkLibrary(apk: File, dir: File, target: File): String? {
        if (!apk.isFile || !apk.canRead()) return null
        return runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter {
                        val name = it.name.substringAfterLast('/')
                        !it.isDirectory && it.name == "$LIB_PREFIX$name" && name.endsWith(".so")
                    }
                    // The manager also packages large patch/policy/provider libraries. They must
                    // never consume the extraction budget before the recovery SDK itself.
                    .sortedByDescending { it.name == "$LIB_PREFIX$SDK_LIBRARY" }
                    .toList()
                val sdk = entries.firstOrNull { it.name == "$LIB_PREFIX$SDK_LIBRARY" } ?: return null
                if (sdk.size <= 0 || sdk.size > MAX_LIB_BYTES) return null
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
                var total = 0L
                var sdkExtracted = false
                for (entry in entries) {
                    val name = entry.name.substringAfterLast('/')
                    if (name.isEmpty() || name.contains("..") || name.contains('/')) continue
                    val size = entry.size
                    if (size <= 0 || size > MAX_LIB_BYTES) continue
                    if (total + size > MAX_TOTAL_LIB_BYTES) continue
                    val out = File(dir, name)
                    val temp = File(dir, "$name.tmp")
                    try {
                        zip.getInputStream(entry).use { input ->
                            temp.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var copied = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    copied += read
                                    check(copied <= size) { "sdk_library_size_mismatch" }
                                    output.write(buffer, 0, read)
                                }
                                check(copied == size) { "sdk_library_size_mismatch" }
                            }
                        }
                        Files.move(temp.toPath(), out.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        temp.delete()
                    }
                    total += size
                    if (name == SDK_LIBRARY) sdkExtracted = true
                    runCatching {
                        Files.setPosixFilePermissions(
                            out.toPath(),
                            PosixFilePermissions.fromString("rwx------"),
                        )
                    }
                }
                target.takeIf { sdkExtracted && it.isFile && it.length() > 0 }?.absolutePath
            }
        }.getOrNull()
    }

    private companion object {
        const val APK_NAME = "manager.apk"
        const val SDK_LIBRARY = "libmaplesu_ultra_bridge.so"
        const val LIB_PREFIX = "lib/arm64-v8a/"
        const val MAX_LIB_BYTES: Long = 16L * 1024 * 1024
        const val MAX_TOTAL_LIB_BYTES: Long = 48L * 1024 * 1024
    }
}

object UltraSdkLibrary {
    private const val BRIDGE_CLASS = "dev.maplesu.manager.runtime.ultra.bridge.NativeBridge"

    fun load(absolutePath: String): Boolean {
        val library = File(absolutePath)
        if (!library.isAbsolute || !library.isFile) return false
        return runCatching {
            System.load(library.absolutePath)
            System.setProperty("maplesu.ultra.boot.library", library.absolutePath)
            Class.forName(BRIDGE_CLASS, true, UltraSdkLibrary::class.java.classLoader)
            true
        }.getOrDefault(false)
    }
}

/**
 * 「撤销后回读」的判定（纯函数，主机可测）。
 *
 * <p>三条硬规则（任何一条不满足都**不得**返回成功）：
 *  1. 回执里必须有 `packages` 数组 —— 读不到列表 ≠ 已撤销；
 *  2. 宿主包名**不得**再出现在列表里；
 *  3. 成功回执的 detail 只带 `sdk=ok;remaining=<n>;id=<identity|system>`（不含 key/包名清单）。
 */
internal object SuAuthReadbackPolicy {

    const val READBACK_UNPARSABLE = "readback_unparsable"

    fun verify(
        hostPackage: String,
        packages: List<String>?,
        listReadable: Boolean,
        remaining: Int,
        source: String,
    ): DaemonRevokeResult {
        if (!listReadable || packages == null) {
            return DaemonRevokeResult(false, LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED, READBACK_UNPARSABLE)
        }
        if (packages.any { it == hostPackage }) {
            return DaemonRevokeResult(
                false,
                LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED,
                "still_listed;count=$remaining",
            )
        }
        return DaemonRevokeResult(true, detail = "sdk=ok;remaining=$remaining;id=$source")
    }
}

/** daemon 内的 root 执行（生产实现；测试用 [DaemonShell] 的 fake）。 */
object DaemonRuntimeShell : DaemonShell {
    override fun run(script: String): DaemonShellResult = RuntimeRecoveryCommandRunner.run(script).let {
        DaemonShellResult(it.code, it.output)
    }
}
