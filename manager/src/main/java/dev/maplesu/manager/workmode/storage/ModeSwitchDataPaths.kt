package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.WorkMode

/** Known paths only. No directory enumeration or root operations are performed here. */
fun interface ModeSwitchDataPaths {
    fun entries(mode: WorkMode): List<MoveEntry>

    companion object {
        fun fromLocal(
            ultraDirectory: String? = null,
            previousPublication: String? = null,
            runtimeDirectory: String? = null,
            registeredExternal: Map<WorkMode, List<String>> = emptyMap(),
        ): ModeSwitchDataPaths {
            val ultra = linkedSetOf<String>()
            ultraDirectory?.let { directory ->
                require(PRIVATE_DIRECTORY.matches(directory)) { "MOVE_ULTRA_PATH_INVALID" }
                ultra += directory
            }
            for (path in listOfNotNull(previousPublication, runtimeDirectory)) {
                require(PRIVATE_DIRECTORY.matches(path)) { "MOVE_PUBLISHED_PATH_INVALID" }
                ultra += path
            }
            val entries = WorkMode.entries.associateWith { mode ->
                val paths = if (mode == WorkMode.LITE) ultra.toList() else emptyList()
                val external = registeredExternal[mode].orEmpty().distinct()
                require(external.all { path ->
                    (path.startsWith("/data/adb/") || path.startsWith("/metadata/")) &&
                        path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
                        !MoveSet.isNeverMove(path) &&
                        MoveSet.NEVER_MOVE.none { it.startsWith("$path/") } &&
                        MoveSet.moveSetFor(mode).none {
                            it.livePath == path || path.startsWith("${it.livePath}/") || it.livePath.startsWith("$path/")
                        } && external.none { it != path && it.startsWith("$path/") }
                }) { "MOVE_EXTERNAL_PATH_INVALID" }
                MoveSet.dynamicEntries(credentialDirectories = paths, registeredExternal = external, owner = mode)
            }
            return ModeSwitchDataPaths { mode -> entries.getValue(mode) }
        }

        private val DIRECTORY_NAME = Regex("[A-Za-z0-9_-]{16}")
        private val PRIVATE_DIRECTORY = Regex("/data/[A-Za-z0-9_-]{16}")
    }
}
