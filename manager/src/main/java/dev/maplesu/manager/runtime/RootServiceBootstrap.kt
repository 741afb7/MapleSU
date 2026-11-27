package dev.maplesu.manager.runtime

/** Root-service startup is separate from runtime pairing and from the service's mutations. */
internal object RootServiceBootstrap {
    internal data class Result(val code: Int, val output: String, val detail: String = "",
                               val stderr: String = "", val cause: Throwable? = null) {
        val ok: Boolean get() = code == 0
        constructor(result: RootExecResult) : this(
            if (result.ok) 0 else result.code.takeUnless { it == 0 } ?: -1, result.output, result.detail)
    }
    internal class Failure(val result: Result, val source: String, val phase: String, reason: String) :
        IllegalStateException(reason, result.cause)
    internal data class Candidate(val source: String, val run: (String) -> Result)

    /** A binding attempt owns only service establishment; its caller invokes the service action. */
    internal data class Binding<T>(val script: () -> String?, val await: () -> T, val close: () -> Unit)

    fun <T> connect(candidates: List<Candidate>, bind: () -> Binding<T>,
                    onAttempt: (String, String, Result) -> Unit = { _, _, _ -> }): T {
        var failed: Failure? = null
        for (candidate in candidates) {
            checkInterrupted()
            var binding: Binding<T>? = null
            var connected = false
            var phase = "bind"
            try {
                binding = bind()
                val script = binding.script()
                checkInterrupted()
                if (script != null) {
                    phase = "launch"
                    val result = run(candidate, script)
                    onAttempt(candidate.source, phase, result)
                    checkInterrupted()
                    if (!result.ok) throw Failure(result, candidate.source, phase,
                        if (result.code == 13) "INSTALLER_ROOT_REQUIRED" else "INSTALLER_SERVICE_LAUNCH_FAILED")
                }
                phase = "connect"
                val value = binding.await()
                checkInterrupted()
                onAttempt(candidate.source, phase, Result(0, ""))
                connected = true
                return value
            } catch (error: Exception) {
                rethrowCancellation(error)
                val failure = error as? Failure ?: Failure(
                    Result(-1, "", "ROOT_SERVICE_${phase.uppercase()}_FAILED:${error.javaClass.simpleName}", cause = error),
                    candidate.source, phase, "INSTALLER_SERVICE_${phase.uppercase()}_FAILED")
                if (error !is Failure) onAttempt(candidate.source, phase, failure.result)
                // A later non-root path must not hide a root source's connection failure.
                if (failed == null || failure.message != "INSTALLER_ROOT_REQUIRED") failed = failure
            } finally {
                if (!connected) binding?.close()
            }
        }
        throw failed ?: Failure(Result(-1, ""), "", "launch", "INSTALLER_ROOT_SOURCES_EXHAUSTED")
    }

    private const val READY = "MAPLESU_ROOT_SERVICE_READY"
    private const val PROBE = "/system/bin/service check activity | /system/bin/grep -q ': found' || exit 14; " +
        "printf '$READY\\n'"

    // UID 0 in the kernel domain cannot look up framework Binder services. Transition only
    // that child; Magisk/KSU and other already working root domains keep their own policy.
    fun command(script: String): String = """
        [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 13
        case "${'$'}(/system/bin/cat /proc/self/attr/current 2>/dev/null)" in
            u:r:kernel:s0) exec /system/bin/toybox runcon u:r:shell:s0 /system/bin/sh -c ${quote(script)} ;;
            *) exec /system/bin/sh -c ${quote(script)} ;;
        esac
    """.trimIndent()

    /** Try every read-only probe. Dispatch the official launch once, through the verified source. */
    fun launch(script: String, candidates: List<Candidate>,
               onAttempt: (String, String, Result) -> Unit = { _, _, _ -> }): Result {
        var failed: Failure? = null
        for (candidate in candidates) {
            checkInterrupted()
            val probe = run(candidate, PROBE)
            onAttempt(candidate.source, "probe", probe)
            checkInterrupted()
            if (!probe.ok || probe.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim() != READY) {
                val reason = when (probe.code) {
                    13 -> "INSTALLER_ROOT_REQUIRED"
                    14 -> "INSTALLER_SERVICE_FRAMEWORK_UNAVAILABLE"
                    else -> "INSTALLER_ROOT_SOURCES_EXHAUSTED"
                }
                // Keep evidence of verified root even if a later source has no permission.
                if (failed?.message != "INSTALLER_SERVICE_FRAMEWORK_UNAVAILABLE" &&
                    (reason != "INSTALLER_ROOT_REQUIRED" || failed == null || failed.message == reason)) {
                    failed = Failure(probe, candidate.source, "probe", reason)
                }
                continue
            }
            val result = run(candidate, script)
            onAttempt(candidate.source, "launch", result)
            checkInterrupted()
            if (!result.ok) throw Failure(result, candidate.source, "launch", "INSTALLER_SERVICE_LAUNCH_FAILED")
            return result
        }
        throw failed ?: Failure(Result(-1, ""), "", "probe", "INSTALLER_ROOT_SOURCES_EXHAUSTED")
    }

    private fun run(candidate: Candidate, script: String): Result = try {
        candidate.run(command(script)).also {
            if (it.detail in setOf("EXEC_INTERRUPTED", "ROOT_EXECUTION_INTERRUPTED")) {
                Thread.currentThread().interrupt()
                throw InterruptedException(it.detail)
            }
        }
    } catch (error: Exception) {
        rethrowCancellation(error)
        Result(-1, "", "ROOT_SERVICE_SOURCE_FAILED:${error.javaClass.simpleName}", cause = error)
    }

    private fun rethrowCancellation(error: Exception) {
        val cancelled = generateSequence<Throwable>(error) { it.cause }.take(8).firstOrNull {
            it is InterruptedException || it is java.util.concurrent.CancellationException
        }
        if (cancelled is InterruptedException) Thread.currentThread().interrupt()
        if (cancelled != null) throw cancelled
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("ROOT_EXECUTION_INTERRUPTED")
    }

    private fun quote(script: String) = "'" + script.replace("'", "'\\''") + "'"
}
