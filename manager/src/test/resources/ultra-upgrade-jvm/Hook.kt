package dev.maplesu.manager.runtime
import android.content.Context
object HookRootExecutor {
    var active = false
    fun production(context: Context) = this
    fun available() = active
}
