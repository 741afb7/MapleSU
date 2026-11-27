package dev.maplesu.manager.runtime

import android.content.Context
import java.io.*

class MapleSuCredentialStore(context: Context) {
    fun load(): ByteArray? { loads++; return credential?.copyOf() }
    companion object { var loads = 0; var credential: ByteArray? = null }
}
internal fun requireValidMapleSuCredential(credential: ByteArray) { require(credential.size == 48) }
object ModuleTaskEnvironment {
    fun replace(target: MutableMap<String, String>, entries: Array<String>) {
        target.clear()
        entries.forEach { target[it.substringBefore('=')] = it.substringAfter('=') }
    }
}
internal class NativeSuRequest(val terminals: Int = 0) {
    fun writeTo(output: OutputStream) { output.write(byteArrayOf(11, 12, 13, 14)); output.flush() }
}
internal class NativeSessionProcess : Process() {
    val stdin = ByteArrayOutputStream()
    val control = Control()
    var retired = false
    override fun getOutputStream() = stdin
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor() = 23
    override fun exitValue() = 23
    override fun destroy() { control.close() }
    class Control : AutoCloseable {
        val outputStream = ByteArrayOutputStream()
        val inputStream: InputStream = ByteArrayInputStream(replies)
        var closed = false
        var descriptors = 0
        val timeouts = ArrayList<Int>()
        fun timeout(milliseconds: Int) { timeouts += milliseconds }
        fun receiveTerminal() = FileDescriptor()
        fun sendDescriptors(streams: Array<FileDescriptor>) { descriptors = streams.size }
        override fun close() { closed = true }
    }
    companion object {
        var replies = byteArrayOf('R'.code.toByte())
        var last: NativeSessionProcess? = null
        var argv = emptyList<String>()
        private fun launch(command: List<String>): NativeSessionProcess {
            argv = command
            return NativeSessionProcess().also { last = it }
        }
        fun start(context: Context, command: List<String>) = launch(command)
        fun start(nativeLibraryDir: String, command: List<String>) = launch(command)
        fun startModule(nativeLibraryDir: String, command: List<String>, environment: List<String>) = launch(command)
        fun retireAndAwait(process: NativeSessionProcess): Boolean {
            process.retired = true; process.control.close(); return true
        }
    }
}
object TrustedRuntime {
    enum class Origin { NORMAL_MAPLESU, JAILBREAK }
    data class State(val ready: Boolean = true, val origin: Origin = Origin.JAILBREAK, val attemptId: String? = "attempt")
    fun state(context: Context) = State()
}
object SuEntryMode {
    enum class Mode(val wire: String) { TRADITIONAL_COMPAT("traditional"), COOPERATIVE_MINIMAL("cooperative") }
    fun read(context: Context) = Mode.TRADITIONAL_COMPAT
}
class HookRootExecutor {
    fun available() = true
    companion object { fun production(context: Context) = HookRootExecutor() }
}
object NativeSuDaemon {
    data class Outcome(val stage: String = "ready", val failure: String? = null)
    fun ensure(context: Context) = Unit
    fun reproveHookOutcome(context: Context, scheduleDeployment: Boolean) = Outcome()
}
object NativeSuDiscovery {
    class Broker { val isBinderAlive = true }
    fun peek(uid: Int) = Broker()
}
object NativeSuDirectoryProvider { const val AUTHORITY = "fixture" }
object RootExec {
    data class Result(val ok: Boolean = true, val output: String = "HOOK_SU_LEGACY_CLEAN", val code: Int = 0, val detail: String = "")
    fun executeHook(context: Context, script: String) = Result()
}
object SuAutoWire { fun reconcile(context: Context) = Unit }
data class SuAuditEvent(val backend: String, val event: String, val decision: String, val reason: String? = null, val detail: String? = null)
object SuAuditLog { fun append(context: Context, event: SuAuditEvent) = Unit }
