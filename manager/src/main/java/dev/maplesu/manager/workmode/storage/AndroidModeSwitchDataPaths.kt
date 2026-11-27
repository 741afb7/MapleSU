package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import dev.maplesu.manager.workmode.WorkMode
import java.io.File

/** Construct in the manager process, then freeze both direction lists before switching. */
class AndroidModeSwitchDataPaths(
    context: Context,
    registeredExternal: Map<WorkMode, List<String>> = emptyMap(),
) : ModeSwitchDataPaths {
    private val paths: ModeSwitchDataPaths

    init {
        val app = context.applicationContext ?: context
        val helper = dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe.installedHelperPath()
        paths = run {
            val previous = File(app.filesDir, "published-directory").takeIf { it.isFile }
                ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            val runtimeName = app.ultraPreferences("runtime_deployment", Context.MODE_PRIVATE)
                .getString("directory_name", null)?.takeIf { it.isNotBlank() }
            ModeSwitchDataPaths.fromLocal(
                ultraDirectory = helper?.let { File(it).parent },
                previousPublication = previous,
                runtimeDirectory = runtimeName?.let { "/data/$it" },
                registeredExternal = registeredExternal,
            )
        }
    }

    override fun entries(mode: WorkMode): List<MoveEntry> = paths.entries(mode)
}
