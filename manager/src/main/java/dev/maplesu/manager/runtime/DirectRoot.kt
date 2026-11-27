package dev.maplesu.manager.runtime

import android.content.Context
import java.io.File

/**
 * One short-lived privileged operation, with no resident service behind it.
 *
 * <p>Every Manager operation that needs root (delivery, withdrawal, a status read) runs
 * as a single `libmaplesu_runtime.so --ultra` process: the process admits itself with
 * the current SDK task identity, runs one
 * fixed script and exits. Nothing is left running, no Binder endpoint is created and no
 * libsu connection is held - so an operation cannot be broken by, and cannot itself keep
 * alive, the resident chain that this migration is removing.
 *
 * <p>Admission does not consume stdin. A failed operation is returned directly.
 */
object DirectRoot {
    data class Result(val code: Int, val output: String)

    fun run(context: Context, script: String): Result {
        val application = context.applicationContext
        val runtime = File(application.applicationInfo.nativeLibraryDir, "libmaplesu_runtime.so")
        if (!runtime.isFile || !runtime.canExecute()) return Result(-1, "runtime image unavailable")
        var process: Process? = null
        var completed = false
        try {
            val running = ProcessBuilder(runtime.absolutePath, "--ultra", "--",
                "/system/bin/sh", "-c", script).redirectErrorStream(true).start()
            process = running
            running.outputStream.use { input ->
                input.flush()
            }
            val output = running.inputStream.bufferedReader().use { it.readText() }
            val code = running.waitFor()
            completed = true
            return Result(code, output)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return Result(-1, "direct operation failed: ${error.javaClass.simpleName}")
        } finally {
            if (!completed) runCatching { process?.destroyForcibly() }
            runCatching { process?.outputStream?.close() }
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
        }
    }
}
