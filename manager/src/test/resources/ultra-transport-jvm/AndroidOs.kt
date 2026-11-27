package android.os

import java.io.FileDescriptor

class Looper {
    companion object {
        private val main = Looper()
        fun myLooper(): Looper? = null
        fun getMainLooper() = main
    }
}
object SystemClock {
    fun elapsedRealtime() = System.nanoTime() / 1_000_000
    fun sleep(milliseconds: Long) = Thread.sleep(milliseconds)
}
class UserManager { val isUserUnlocked = true }
class ParcelFileDescriptor : AutoCloseable {
    override fun close() = Unit
    companion object { fun dup(descriptor: FileDescriptor) = ParcelFileDescriptor() }
}
class Bundle {
    fun putString(key: String, value: String) = Unit
    fun getInt(key: String, fallback: Int) = fallback
    fun getString(key: String): String? = null
}
