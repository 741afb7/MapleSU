package com.topjohnwu.superuser

object LocalShellBoundary {
    var success = true
    var stdout = listOf("Enforcing")
    var stderr = emptyList<String>()
    var starts = 0
    var closes = 0
    var program: String? = null
    var command: String? = null
    var failure: RuntimeException? = null
    fun reset() {
        success=true; stdout=listOf("Enforcing"); stderr=emptyList()
        starts=0; closes=0; program=null; command=null; failure=null
    }
}
class Shell : AutoCloseable {
    class Builder {
        companion object { fun create() = Builder() }
        fun build(program: String): Shell {
            LocalShellBoundary.failure?.let { throw it }
            LocalShellBoundary.starts++
            LocalShellBoundary.program=program
            return Shell()
        }
    }
    fun newJob() = Job()
    class Job {
        private var out: MutableList<String>? = null
        private var err: MutableList<String>? = null
        fun add(command: String): Job = apply { LocalShellBoundary.command=command }
        fun to(output: MutableList<String>, errors: MutableList<String>): Job = apply { out=output; err=errors }
        fun exec(): Result {
            out?.addAll(LocalShellBoundary.stdout); err?.addAll(LocalShellBoundary.stderr)
            return Result(LocalShellBoundary.success)
        }
    }
    class Result(val isSuccess: Boolean)
    override fun close() { LocalShellBoundary.closes++ }
}
