package dev.maplesu.manager.compat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files

fun Context.ultraPreferences(name: String, mode: Int): SharedPreferences =
    UltraStateStorage.preferences(this, name, mode)

object UltraStateStorage {
    private const val IMPORTS = "maplesu-state-imports"
    private const val IMPORTED = "maplesu.state.imported.v1"

    @Synchronized
    fun preferences(context: Context, name: String, mode: Int): SharedPreferences {
        val app = context.applicationContext ?: context
        val target = app.getSharedPreferences(name, mode)
        val inputName = StoredInputs.preferenceNames[name] ?: return target
        val inputs = app.getSharedPreferences(inputName, mode)
        val registry = app.getSharedPreferences(IMPORTS, Context.MODE_PRIVATE)
        if (registry.all[name] != true) {
            if (target.all[IMPORTED] != true) {
                val current = target.all
                val copied = inputs.all.filterKeys { !current.containsKey(it) }
                val editor = target.edit()
                for ((key, value) in copied) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> {
                            check(value.all { it is String }) { "MAPLESU_STATE_INPUT_TYPE" }
                            editor.putStringSet(key, value.map { it as String }.toSet())
                        }
                        null -> editor.remove(key)
                        else -> error("MAPLESU_STATE_INPUT_TYPE")
                    }
                }
                check(editor.putBoolean(IMPORTED, true).commit()) { "MAPLESU_STATE_INPUT_COMMIT" }
                check(target.getBoolean(IMPORTED, false) && copied.all { (key, value) -> target.all[key] == value }) {
                    "MAPLESU_STATE_INPUT_READBACK"
                }
            }
            check(registry.edit().putBoolean(name, true).commit() && registry.getBoolean(name, false)) {
                "MAPLESU_STATE_INPUT_MARKER"
            }
        }
        if (inputs.all.isNotEmpty() && !inputs.edit().clear().commit()) {
            Log.w("MapleSUState", "Input state cleanup deferred")
        }
        return target
    }

    fun flash(root: File): File = file(root, "ultra-flash")

    @Synchronized
    fun file(root: File, name: String): File {
        val input = when (name) {
            "ultra-flash" -> StoredInputs.FLASH_DIRECTORY
            "ultra-operations.jsonl" -> StoredInputs.OPERATIONS_FILE
            "ultra-module-evidence.jsonl" -> StoredInputs.MODULE_EVIDENCE_FILE
            else -> return File(root, name)
        }
        val target = File(root, name)
        merge(File(root, input), target)
        return target
    }

    private fun merge(input: File, target: File) {
        if (Files.isSymbolicLink(input.toPath()) || Files.isSymbolicLink(target.toPath())) {
            throw IOException("MAPLESU_STATE_INPUT_LINK")
        }
        if (!input.exists()) return
        if (!target.exists() && input.renameTo(target)) return
        if (input.isDirectory && target.isDirectory) {
            val entries = input.listFiles() ?: throw IOException("MAPLESU_STATE_INPUT_READ")
            for (entry in entries) merge(entry, File(target, entry.name))
            if (!input.delete()) throw IOException("MAPLESU_STATE_INPUT_CLEANUP")
            return
        }
        if (input.isFile && target.isFile && equalBytes(input, target)) {
            if (!input.delete()) throw IOException("MAPLESU_STATE_INPUT_CLEANUP")
            return
        }
        throw IOException("MAPLESU_STATE_INPUT_CONFLICT")
    }

    private fun equalBytes(left: File, right: File): Boolean {
        if (left.length() != right.length()) return false
        return left.inputStream().buffered().use { first ->
            right.inputStream().buffered().use inner@{ second ->
                val a = ByteArray(16 * 1024)
                val b = ByteArray(a.size)
                while (true) {
                    val count = first.read(a)
                    if (count < 0) return@inner second.read() == -1
                    var offset = 0
                    while (offset < count) {
                        val n = second.read(b, offset, count - offset)
                        if (n < 0) return@inner false
                        offset += n
                    }
                    if ((0 until count).any { a[it] != b[it] }) return@inner false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        }
    }
}
