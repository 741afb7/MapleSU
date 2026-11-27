package dev.maplesu.manager.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root-side supervisor for one session.
 *
 * A restricted `su` request is built in two phases. [prepareRootSu] forks the Root
 * child and blocks it on a private pipe before the user command is executed, so the
 * request is fully prepared but has no side effects. [commitNoWait] then sends the
 * single commit byte while the caller still holds the authorization policy lock, and
 * [awaitStarted] waits for the real start outside that lock. Cancellation before the
 * commit withdraws the request: the native side accepts only the commit byte, so
 * anything else - a cancel byte, EOF, the owner dying - means the command never ran.
 *
 * Only the Root owner uses the gate; ordinary callers have no commit handle.
 */
internal class MapleSuSupervisedProcess private constructor(
    val process: Process,
    private val control: NativeSessionProcess.Control,
    private val su: Boolean,
    private val terminals: Boolean,
    private val pendingCommit: Boolean,
) {
    @Volatile private var terminalDescriptor: FileDescriptor? = null
    val terminal: FileDescriptor? get() = terminalDescriptor

    private val controlClosed = AtomicBoolean(false)
    private val committed = AtomicBoolean(false)
    private val writes = Any()
    private val reads = Any()
    private val terminalLock = Any()
    @Volatile private var started = false

    /**
     * The linearization point of admission. This is the only work performed while
     * the policy lock is held: one byte on an already-connected private socketpair.
     * It never forks, waits or reads, so grant/revoke and the global switch are not
     * serialised behind process setup.
     */
    fun commitNoWait() = synchronized(writes) {
        if (!pendingCommit || started) return
        check(!controlClosed.get()) { "MAPLESU_SESSION_CONTROL_CLOSED" }
        if (!committed.compareAndSet(false, true)) return
        control.outputStream.write('G'.code)
        control.outputStream.flush()
    }

    /** Waits for native gate release and any PTY descriptor; waitFor reports exec failures. */
    fun awaitStarted() = synchronized(reads) {
        if (started) return
        check(pendingCommit) { "MAPLESU_SESSION_NOT_GATED" }
        check(committed.get()) { "MAPLESU_SESSION_NOT_COMMITTED" }
        var received: FileDescriptor? = null
        try {
            check(!controlClosed.get()) { "MAPLESU_SESSION_CONTROL_CLOSED" }
            control.timeout(HANDSHAKE_TIMEOUT_MS)
            check(control.inputStream.read() == 'R'.code) { "MAPLESU_SESSION_ROOT_NOT_ACCEPTED" }
            if (terminals) {
                received = control.receiveTerminal()
                Os.fcntlInt(received, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            }
            control.timeout(0)
            synchronized(terminalLock) {
                check(!controlClosed.get()) { "MAPLESU_SESSION_CONTROL_CLOSED" }
                terminalDescriptor = received
                received = null
                started = true
            }
        } catch (failure: Throwable) {
            received?.let { runCatching { Os.close(it) } }
            closeControl()
            throw failure
        }
    }

    private fun adoptTerminal(descriptor: FileDescriptor) {
        terminalDescriptor = descriptor
        started = true
    }

    fun openTerminal(): ParcelFileDescriptor? = synchronized(terminalLock) {
        check(started && !controlClosed.get()) { "MAPLESU_SESSION_CONTROL_CLOSED" }
        terminalDescriptor?.let { ParcelFileDescriptor.dup(it) }
    }

    fun cancel() {
        // EOF withdraws an uncommitted child and cancels a running native group.
        // Shutdown also wakes awaitStarted without waiting for its read lock.
        closeControl()
    }

    fun signal(signal: Int) = synchronized(writes) {
        require(signal in setOf(OsConstants.SIGINT, OsConstants.SIGTERM, OsConstants.SIGHUP,
            OsConstants.SIGQUIT, OsConstants.SIGTSTP, OsConstants.SIGCONT)) { "SU_SIGNAL_INVALID" }
        check(!controlClosed.get()) { "MAPLESU_SESSION_CONTROL_CLOSED" }
        check(su && started)
        val output = DataOutputStream(control.outputStream)
        output.writeByte('S'.code); output.writeInt(signal); output.flush()
    }

    fun resize(window: IntArray) = synchronized(writes) {
        require(window.size == 4 && window.all { it in 0..65535 }) { "SU_WINDOW_INVALID" }
        check(!controlClosed.get()) { "MAPLESU_SESSION_CONTROL_CLOSED" }
        check(su && started && terminalDescriptor != null && window.size == 4)
        val output = DataOutputStream(control.outputStream)
        output.writeByte('W'.code); window.forEach(output::writeInt); output.flush()
    }

    fun closeControl() {
        if (!controlClosed.compareAndSet(false, true)) return
        runCatching { control.close() }
        synchronized(terminalLock) {
            terminalDescriptor?.let { runCatching { Os.close(it) } }
            terminalDescriptor = null
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 20_000

        fun start(context: Context, argv: List<String>, globalMnt: Boolean = false): MapleSuSupervisedProcess =
            startInternal(context, argv, null, emptyArray(), globalMnt = globalMnt)

        fun startRoot(context: Context, argv: List<String>, globalMnt: Boolean = false): MapleSuSupervisedProcess {
            requireRoot()
            return startInternal(context, argv, null, emptyArray(), rootOwner = true, globalMnt = globalMnt)
        }

        fun startRootModule(context: Context, argv: List<String>, environment: List<String>): MapleSuSupervisedProcess {
            requireRoot()
            return startInternal(context, argv, null, emptyArray(), rootOwner = true, moduleEnvironment = environment)
        }

        fun startSu(context: Context, request: NativeSuRequest, descriptors: Array<FileDescriptor>): MapleSuSupervisedProcess =
            startInternal(context, listOf("/system/bin/sh"), request, descriptors)

        /** Prepared, not started: the caller must commit and then await the start. */
        fun prepareRootSu(context: Context, request: NativeSuRequest,
            descriptors: Array<FileDescriptor>,
            onLaunched: ((MapleSuSupervisedProcess) -> Unit)? = null): MapleSuSupervisedProcess {
            requireRoot()
            return startInternal(context, listOf("/system/bin/sh"), request, descriptors,
                rootOwner = true, gated = true, onLaunched = onLaunched)
        }

        fun prepareRootSu(nativeLibraryDir: String, request: NativeSuRequest,
            descriptors: Array<FileDescriptor>,
            onLaunched: ((MapleSuSupervisedProcess) -> Unit)? = null): MapleSuSupervisedProcess {
            requireRoot()
            return startInternal(nativeLibraryDir, listOf("/system/bin/sh"), request,
                descriptors, rootOwner = true, gated = true, onLaunched = onLaunched)
        }

        private fun requireRoot() {
            check(Os.getuid() == 0 && Os.geteuid() == 0) { "SU_ROOT_OWNER_REQUIRED" }
        }

        /** Ordered convenience wrapper: prepare, commit, await. */
        fun startRootSu(context: Context, request: NativeSuRequest,
            descriptors: Array<FileDescriptor>): MapleSuSupervisedProcess =
            prepareRootSu(context, request, descriptors).also { prepared ->
                try {
                    prepared.commitNoWait()
                    prepared.awaitStarted()
                } catch (failure: Throwable) {
                    prepared.cancel()
                    throw failure
                }
            }

        private fun startInternal(context: Context, argv: List<String>, request: NativeSuRequest?,
            descriptors: Array<FileDescriptor>, rootOwner: Boolean = false, globalMnt: Boolean = false,
            gated: Boolean = false, moduleEnvironment: List<String>? = null,
            onLaunched: ((MapleSuSupervisedProcess) -> Unit)? = null): MapleSuSupervisedProcess {
            return startInternal(context.applicationInfo.nativeLibraryDir, argv, request,
                descriptors, rootOwner, globalMnt, gated, context, onLaunched, moduleEnvironment)
        }

        private fun startInternal(nativeLibraryDir: String, argv: List<String>,
            request: NativeSuRequest?, descriptors: Array<FileDescriptor>, rootOwner: Boolean,
            globalMnt: Boolean = false, gated: Boolean = false, context: Context? = null,
            onLaunched: ((MapleSuSupervisedProcess) -> Unit)? = null,
            moduleEnvironment: List<String>? = null): MapleSuSupervisedProcess {
            val terminals = request != null && request.terminals != 0
            var process: NativeSessionProcess? = null
            var control: NativeSessionProcess.Control? = null
            var terminal: FileDescriptor? = null
            var supervised: MapleSuSupervisedProcess? = null
            var accepted = false
            try {
                require(argv.isNotEmpty() && argv.size <= 256 && argv[0].startsWith("/") &&
                    argv.all { '\u0000' !in it } && argv.sumOf { it.length } <= 32_768) {
                    "MAPLESU_REQUEST_ARGUMENTS_INVALID"
                }
                require(request == null || descriptors.size == 3) { "SESSION_STREAMS_INVALID" }
                val executable = File(nativeLibraryDir, "libmaplesu_runtime.so")
                check(executable.isFile && executable.canExecute()) { "MAPLESU_RUNTIME_UNAVAILABLE" }
                val mode = if (rootOwner) {
                    if (request != null) "--root-su" else if (globalMnt) "--root-global-session" else "--root-session"
                } else {
                    if (request != null) "--ultra-su" else if (globalMnt) "--ultra-global-session" else "--ultra-session"
                }
                // Execution environment stays Manager-owned until the root child is forked.
                val command = listOf(executable.absolutePath, mode, "--") + argv
                val launched = if (moduleEnvironment != null) NativeSessionProcess.startModule(nativeLibraryDir, command, moduleEnvironment)
                    else if (rootOwner) NativeSessionProcess.start(nativeLibraryDir, command)
                    else NativeSessionProcess.start(checkNotNull(context), command)
                process = launched
                control = launched.control
                val wrapper = MapleSuSupervisedProcess(launched, launched.control, request != null, terminals,
                    pendingCommit = gated)
                supervised = wrapper
                onLaunched?.invoke(wrapper)
                control.timeout(HANDSHAKE_TIMEOUT_MS)
                control.outputStream.write('A'.code)
                control.outputStream.flush()
                if (request != null) {
                    request.writeTo(process.outputStream)
                    control.sendDescriptors(descriptors)
                }
                if (gated) {
                    // Prepared: the child exists but is blocked before exec. Nothing
                    // the caller asked for has run yet.
                    check(control.inputStream.read() == 'P'.code) { "MAPLESU_SESSION_NOT_PREPARED" }
                    accepted = true
                    return wrapper
                }
                check(control.inputStream.read() == 'R'.code) { "MAPLESU_SESSION_ROOT_NOT_ACCEPTED" }
                if (terminals) {
                    terminal = control.receiveTerminal()
                    Os.fcntlInt(terminal, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
                }
                control.timeout(0)
                terminal?.let { wrapper.adoptTerminal(it) }
                wrapper.started = true
                accepted = true
                return wrapper
            } finally {
                if (!accepted) {
                    terminal?.let { runCatching { Os.close(it) } }
                    if (process != null) NativeSessionProcess.retireAndAwait(process)
                    else runCatching { control?.close() }
                }
            }
        }
    }
}
