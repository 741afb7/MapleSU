package com.topjohnwu.superuser

open class Shell : AutoCloseable {
    val isAlive = true
    val isRoot = true
    override fun close() = Unit
    fun newJob() = Job()
    class Job {
        fun add(vararg scripts: String) = this
        fun to(out: MutableList<String>?, err: MutableList<String>?) = this
        fun exec() = Result()
    }
    class Result {
        val isSuccess = true
        val code = 0
        val out = listOf("mnt:[1]", "mnt:[1]")
        val err = emptyList<String>()
    }
    class Builder {
        fun setContext(context: android.content.Context) = this
        fun setTimeout(seconds: Int) = this
        fun build(process: Process) = Shell()
        companion object { fun create() = Builder() }
    }
}
