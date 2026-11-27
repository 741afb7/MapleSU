package android.system

import java.io.File
import java.io.FileDescriptor

object Os {
    var uid = 10123
    var closed = 0
    fun getuid() = uid
    fun geteuid() = uid
    fun fcntlInt(descriptor: FileDescriptor, command: Int, value: Int) = 0
    fun close(descriptor: FileDescriptor) { closed++ }
    fun readlink(path: String) = File(path).canonicalPath
    fun stat(path: String) = Stat()
}
class Stat { val st_dev = 0L; val st_ino = 0L }
object OsConstants {
    const val F_SETFD = 2
    const val FD_CLOEXEC = 1
    const val SIGINT = 2
    const val SIGTERM = 15
    const val SIGHUP = 1
    const val SIGQUIT = 3
    const val SIGTSTP = 20
    const val SIGCONT = 18
}
