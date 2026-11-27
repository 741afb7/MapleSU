package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.runtime.ultra.NativeReceipt
import dev.maplesu.manager.runtime.ultra.UltraReceipt
import dev.maplesu.manager.modules.compat.UltraRuntimeModuleHost
import dev.maplesu.manager.liteweb.LiteWebToggleView
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/** SDK facts and verified module control for app_process; does not activate manager ModeRuntime. */
class DaemonUltraRuntimeFacts(
    private val access: DaemonUltraSdkAccess,
    private val readBootId: () -> String? = { boundedText(File("/proc/sys/kernel/random/boot_id"), 128)?.trim() },
    private val readCommittedMode: () -> DaemonCommittedMode? = {
        DaemonSdkFactMapping.committedMode(boundedText(File("/data/adb/maplesu-workmode/control/modes.json"), 256 * 1024))
    },
    private val nowNanos: () -> Long = System::nanoTime,
    private val journal: (UltraReceipt) -> Unit = { receipt ->
        val path = File("/data/adb/maplesu/recovery/ultra-module-operations.jsonl")
        java.io.FileOutputStream(path, true).use {
            it.write((receipt.toJson().toString() + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    },
    private val openSdk: () -> DaemonUltraSdkResult = access::open,
) : DaemonRuntimeSource {
    private var cached = DaemonRuntimeSnapshot()
    private var observedAt: Long? = null

    @Synchronized
    override fun snapshot(): DaemonRuntimeSnapshot {
        val now = nowNanos()
        observedAt?.let { if (now - it in 0 until CACHE_NANOS) return cached }
        val state = runCatching {
            when (val sdk = openSdk()) {
                is DaemonUltraSdkResult.Ready -> DaemonSdkFactMapping.state(sdk.client.environmentState())
                is DaemonUltraSdkResult.Unavailable -> DaemonUltraState.UNKNOWN
            }
        }.getOrDefault(DaemonUltraState.UNKNOWN)
        // Do not even consult Normal metadata until the SDK explicitly reports NotInstalled.
        cached = if (state == DaemonUltraState.NOT_INSTALLED) {
            DaemonRuntimePolicy.resolve(
                state,
                runCatching { readCommittedMode() }.getOrNull(),
                runCatching { readBootId() }.getOrNull(),
            )
        } else {
            DaemonRuntimePolicy.resolve(state)
        }
        observedAt = nowNanos()
        return cached
    }

    override fun ultraModules(): DaemonModulesSnapshot {
        if (snapshot().rootBackend != "ULTRA_ENV") return DaemonModulesSnapshot(error = "ULTRA_ENVIRONMENT_UNAVAILABLE")
        return runCatching {
            when (val sdk = openSdk()) {
                is DaemonUltraSdkResult.Ready -> DaemonSdkFactMapping.modules(sdk.client.moduleList())
                is DaemonUltraSdkResult.Unavailable -> DaemonModulesSnapshot(error = "ULTRA_MODULES_UNAVAILABLE")
            }
        }.getOrElse { DaemonModulesSnapshot(error = "ULTRA_MODULES_UNAVAILABLE") }
    }

    @Synchronized
    override fun toggleUltraModule(moduleId: String, enabled: Boolean): LiteWebToggleView {
        fun allowed(): Boolean {
            observedAt = null // mutations always re-probe the live SDK environment
            return snapshot().rootBackend == "ULTRA_ENV" &&
                runCatching { readCommittedMode()?.hasPendingSwitch != true }.getOrDefault(false)
        }
        return runCatching {
            when (val sdk = openSdk()) {
                is DaemonUltraSdkResult.Ready -> DaemonUltraModuleToggle(
                    UltraRuntimeModuleHost(sdk.client), ::allowed, journal,
                ).setEnabled(moduleId, enabled)
                is DaemonUltraSdkResult.Unavailable -> LiteWebToggleView.Unavailable("ULTRA_MODULES_UNAVAILABLE")
            }
        }.getOrElse { LiteWebToggleView.Failed("MODULE_TOGGLE_FAILED", it.message.orEmpty()) }
    }

    private companion object {
        const val CACHE_NANOS = 1_000_000_000L

        fun boundedText(file: File, maxBytes: Int): String? = runCatching {
            if (!file.isFile || file.length() > maxBytes) return null
            file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) return null
                    out.write(buffer, 0, count)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }
}

/** Receipt projections use exactly one successful SDK list, never a second read or filesystem fallback. */
internal object DaemonSdkFactMapping {
    fun state(receipt: NativeReceipt): DaemonUltraState = when {
        !receipt.succeeded -> DaemonUltraState.UNKNOWN
        receipt.string("state") == "Running" -> DaemonUltraState.RUNNING
        receipt.string("state") == "NotInstalled" -> DaemonUltraState.NOT_INSTALLED
        receipt.string("state") == "Fault" -> DaemonUltraState.FAULT
        else -> DaemonUltraState.UNKNOWN
    }

    fun modules(receipt: NativeReceipt): DaemonModulesSnapshot {
        if (!receipt.succeeded) return DaemonModulesSnapshot(error = "ULTRA_MODULES_UNAVAILABLE")
        val array = receipt.array("modules") ?: return DaemonModulesSnapshot(error = "ULTRA_MODULE_LIST_INVALID")
        if (array.length() > DaemonLiteWebLimits.MAX_MODULES) return DaemonModulesSnapshot(error = "ULTRA_MODULE_LIST_INVALID")
        val rows = ArrayList<DaemonUltraModule>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return DaemonModulesSnapshot(error = "ULTRA_MODULE_LIST_INVALID")
            val desc = item.optJSONObject("desc") ?: return DaemonModulesSnapshot(error = "ULTRA_MODULE_LIST_INVALID")
            fun text(key: String): String = desc.optString(key, "").let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }.take(DaemonLiteWebLimits.MAX_MODULE_TEXT)
            rows += DaemonUltraModule(
                id32 = text("id32"),
                name = text("name"),
                description = text("desc"),
                version = text("ver"),
                author = text("author"),
                state = item.optString("state", "Unknown"),
                hasWebUi = desc.optBoolean("web_ui", false),
                enabled = item.optJSONObject("control")?.let { if (it.opt("enabled") is Boolean) it.getBoolean("enabled") else null },
                canToggle = item.optJSONObject("control")?.let {
                    it.optInt("abiVersion") == 1 && it.opt("supportsEnableToggle") == true &&
                        it.opt("enabled") is Boolean && it.opt("needsReboot") is Boolean
                } == true,
                needsReboot = item.optJSONObject("control")?.optBoolean("needsReboot", false) == true,
            )
        }
        return DaemonUltraModuleList.render(rows)
    }

    fun committedMode(text: String?): DaemonCommittedMode? = runCatching {
        val root = JSONObject(text ?: return null)
        if (root.optInt("schema", 0) != 1) return null
        val boot = root.optJSONObject("boot") ?: return null
        DaemonCommittedMode(
            bootId = root.optString("bootId", ""),
            verifiedBootId = boot.optString("verifiedBootId", "").takeIf { it.isNotEmpty() },
            current = root.optString("current", ""),
            activeMode = boot.optString("activeMode", ""),
            hasPendingSwitch = !root.isNull("pending"),
        )
    }.getOrNull()
}
