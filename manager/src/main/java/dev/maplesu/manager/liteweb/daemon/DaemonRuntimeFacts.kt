package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.liteweb.LiteWebJson
import dev.maplesu.manager.liteweb.LiteWebMode
import dev.maplesu.manager.liteweb.LiteWebRootBackendNames
import dev.maplesu.manager.liteweb.LiteWebToggleView

/** Observations belong to this daemon process, not the manager's process-local caches. */
enum class DaemonUltraState { RUNNING, NOT_INSTALLED, FAULT, UNKNOWN }

data class DaemonCommittedMode(
    val bootId: String,
    val verifiedBootId: String?,
    val current: String,
    val activeMode: String,
    val hasPendingSwitch: Boolean,
) {
    fun isCurrentNormal(currentBootId: String?): Boolean =
        !currentBootId.isNullOrBlank() && bootId == currentBootId && verifiedBootId == currentBootId &&
            current == "B" && activeMode == "B" && !hasPendingSwitch
}

data class DaemonRuntimeSnapshot(
    val mode: String = "UNKNOWN",
    val rootBackend: String = LiteWebRootBackendNames.UNKNOWN,
    val probe: String = "Unknown",
    val source: String = "unknown",
)

data class DaemonModulesSnapshot(val json: String = "[]", val error: String = "") {
    fun responseJson(): String = if (error.isEmpty()) json else
        "{\"ok\":false,\"error\":${LiteWebJson.quote(error)}}"
}

interface DaemonRuntimeSource {
    fun snapshot(): DaemonRuntimeSnapshot
    fun ultraModules(): DaemonModulesSnapshot
    fun toggleUltraModule(moduleId: String, enabled: Boolean): LiteWebToggleView =
        LiteWebToggleView.Unavailable("ULTRA_MODULE_TOGGLE_UNSUPPORTED")
}

object UnavailableDaemonRuntimeSource : DaemonRuntimeSource {
    override fun snapshot() = DaemonRuntimeSnapshot()
    override fun ultraModules() = DaemonModulesSnapshot(error = "ULTRA_MODULES_UNAVAILABLE")
}

/** SDK identity errors never become permission to read another mode's module directory. */
object DaemonRuntimePolicy {
    fun resolve(
        ultraState: DaemonUltraState,
        committed: DaemonCommittedMode? = null,
        currentBootId: String? = null,
    ): DaemonRuntimeSnapshot = when {
        ultraState == DaemonUltraState.RUNNING -> DaemonRuntimeSnapshot(
            mode = LiteWebMode.LITE,
            rootBackend = LiteWebRootBackendNames.ULTRA_ENV,
            probe = "Running",
            source = "ultra_sdk",
        )
        ultraState == DaemonUltraState.NOT_INSTALLED && committed?.isCurrentNormal(currentBootId) == true ->
            DaemonRuntimeSnapshot(
                mode = LiteWebMode.KSU,
                rootBackend = LiteWebRootBackendNames.KSU,
                probe = "NotInstalled",
                source = "same_boot_committed_mode",
            )
        else -> DaemonRuntimeSnapshot(probe = when (ultraState) {
            DaemonUltraState.NOT_INSTALLED -> "NotInstalled"
            DaemonUltraState.FAULT -> "Fault"
            DaemonUltraState.UNKNOWN -> "Unknown"
            DaemonUltraState.RUNNING -> "Running"
        })
    }
}

/** A decoded SDK row; no private catalog/manager Context is needed after uninstall. */
data class DaemonUltraModule(
    val id32: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val state: String,
    val hasWebUi: Boolean,
    val enabled: Boolean? = null,
    val canToggle: Boolean = false,
    val needsReboot: Boolean = false,
)

object DaemonUltraModuleList {
    fun render(rows: List<DaemonUltraModule>): DaemonModulesSnapshot {
        // Reject ambiguous identities instead of publishing a partially fabricated list.
        if (rows.any { !ID.matches(it.id32) } || rows.map { it.id32 }.distinct().size != rows.size) {
            return DaemonModulesSnapshot(error = "ULTRA_MODULE_LIST_INVALID")
        }
        return DaemonModulesSnapshot(rows.joinToString(prefix = "[", postfix = "]") { item ->
            buildString {
                append("{\"id\":").append(LiteWebJson.quote("maplesu:${item.id32}"))
                append(",\"name\":").append(LiteWebJson.quote(item.name.ifEmpty { item.id32 }))
                append(",\"description\":").append(LiteWebJson.quote(item.description))
                append(",\"version\":").append(LiteWebJson.quote(item.version))
                append(",\"author\":").append(LiteWebJson.quote(item.author))
                append(",\"backend\":\"maplesu_ultra\",\"canToggle\":").append(item.canToggle && item.state != "RemovedPendingReboot")
                append(",\"enabled\":").append(item.enabled ?: (item.state == "Running"))
                append(",\"remove\":").append(item.state == "RemovedPendingReboot")
                append(",\"ultraState\":").append(LiteWebJson.quote(item.state))
                append(",\"runtimeStatus\":").append(LiteWebJson.quote(item.state))
                append(",\"needsReboot\":").append(item.needsReboot)
                // The descriptor is preserved separately; this daemon has no module WebUI launch port.
                append(",\"hasWebUi\":").append(item.hasWebUi)
                append(",\"web\":false,\"canWeb\":false,\"action\":false,\"update\":false")
                append(",\"unsupported\":[")
                if (!item.canToggle || item.state == "RemovedPendingReboot") append("\"toggle\",")
                append("\"stop\",\"in-place-update\",\"webui-launch\"]}")
            }
        })
    }

    private val ID = Regex("^[A-Za-z0-9._-]{1,128}$")
}
