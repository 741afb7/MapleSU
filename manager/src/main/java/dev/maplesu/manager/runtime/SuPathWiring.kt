package dev.maplesu.manager.runtime

import android.content.Context
import android.util.Log
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import dev.maplesu.manager.workmode.superuser.ImplantPreparation
import dev.maplesu.manager.workmode.superuser.LiteImplantCodes
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import dev.maplesu.manager.workmode.superuser.LiteImplantRecorderProvider
import java.io.File

/**
 * Persistent `su` PATH wiring for one authorised application.
 *
 * <p>The selected source SDK patches
 * one of the target's own JNI libraries to carry a `DT_NEEDED` entry for a side library
 * it writes next to it, and that side library prepends the published `su` directory to
 * `PATH` from its constructor. Therefore:
 *
 *  - only processes of the wired application resolve a bare `su`;
 *  - every other application keeps its untouched `PATH` and its untouched libraries;
 *  - nothing is mounted, no socket, property or device node is created.
 *
 * <p>Two properties are enforced here rather than trusted to the caller:
 *
 *  1. **Only a granted identity may be wired.** The grant is re-read from the policy
 *     immediately before wiring, under the same reader that the `su` admission path
 *     uses. An application without an active grant is refused, so an unauthorised app
 *     can never receive the implant even if a caller asks for it.
 *  2. **The install itself is not a granted app and is never wired**, so the manager
 *     cannot end up with its own `PATH` modified.
 *
 * <p>Cost accepted by choosing this route: the wired application's `lib/` directory
 * gains one side library and one of its libraries gains a `DT_NEEDED` entry, so that
 * application can observe the change on itself. Nothing about it is visible to other
 * applications.
 */
object SuPathWiring {
    /** Exit codes of the native helper, mirrored for readable diagnostics. */
    private const val PRECHECK_REJECTED = 66

    /**
     * [reason] is the machine-readable outcome, [detail] is for a human. Callers branch on
     * [reason]: the human text is allowed to change, and a string comparison against it
     * silently disabled the library-free fallback for every WebUI application.
     */
    data class Result(val ok: Boolean, val code: Int, val detail: String, val reason: String = "")

    object Reason {
        const val OK = "OK"
        const val SELF = "SELF"
        const val UNKNOWN_PACKAGE = "UNKNOWN_PACKAGE"
        const val AUTH_NOT_GRANTED = "AUTH_NOT_GRANTED"
        const val AUTH_DISABLED = "AUTH_DISABLED"
        const val AUTH_STATE_UNAVAILABLE = "AUTH_STATE_UNAVAILABLE"
        const val NOT_RUNNING = "NOT_RUNNING"
        const val RUNTIME = "RUNTIME"
        const val IDENTITY = "SDK_IDENTITY_SCOPE_CHANGED"
        const val PUBLICATION = "SU_PUBLICATION_UNAVAILABLE"
        const val CLIENT = "SU_CLIENT_NOT_DELIVERED"
        const val PRECHECK = "PRECHECK"
        /** The package ships no JNI library at all: the case PATH injection exists for. */
        const val NO_CANDIDATES = "NO_CANDIDATES"
        /** A side library is already present: a second implant would stack and can corrupt. */
        const val ALREADY_IMPLANTED = "ALREADY_IMPLANTED"
        /** Libraries exist but none may be patched (all root-owned side libraries). */
        const val NO_PATCHABLE = "NO_PATCHABLE"
        const val IMPLANT = "IMPLANT"
        const val INJECT = "INJECT"
        /** W19：修改前原件备份/登记失败 → 拒绝植入（fail closed）。 */
        const val BACKUP = "IMPLANT_BACKUP_FAILED"

        /** W19：切换静默期，拒绝新的投递/植入。 */
        const val QUIESCING = "IMPLANT_QUIESCING"
    }

    /** A target the upstream precheck accepted, with its dynlib status. */
    data class Candidate(val status: Int, val path: String)

    /**
     * Wires [packageName] so that its own processes resolve a bare `su`.
     *
     * @return the actual helper stage and error, without reusable admission material.
     */
    fun wire(context: Context, packageName: String): Result {
        val application = context.applicationContext
        // W19 写者静默：切换期间拒绝任何新的植入/改动。
        LiteImplantQuiesceGate.blockedCode()?.let { code ->
            return Result(false, 0, code, Reason.QUIESCING)
        }
        if (packageName == application.packageName)
            return Result(false, 0, "refusing to wire the manager itself", Reason.SELF)
        val uid = runCatching {
            application.packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrNull() ?: return Result(false, 0, "unknown package", Reason.UNKNOWN_PACKAGE)

        // Single authority, read immediately before the operation (never a cached UI
        // value), through a short-lived root tool so wiring does not start a resident
        // executor.
        authorizationFailure(application, uid)?.let { return Result(false, 0, it, it) }
        val current = UltraSdkCredentials.captureOwnerMarker(application)
        if (!current()) return Result(false, 0, Reason.IDENTITY, Reason.IDENTITY)
        // Refuse to implant twice. Each implant appends another side library and another
        // DT_NEEDED entry to the target's own library; stacking them grew one test target
        // from 5,368 to 984,089 bytes until its own native code faulted (SIGILL), and grew
        // another application's library to 13.6 MB. A single implant is what upstream does
        // and what this project measured as safe.
        if (sideLibraryCount(application, packageName) > 0)
            return Result(false, 0, "ALREADY_IMPLANTED", Reason.ALREADY_IMPLANTED)

        val cmdline = runningCommandLine(application, packageName)
            ?: return Result(false, 0, "target has no running process", Reason.NOT_RUNNING)
        val executable = File(application.applicationInfo.nativeLibraryDir, "libmaplesu_runtime.so")
        if (!executable.isFile || !executable.canExecute())
            return Result(false, 0, "runtime image unavailable", Reason.RUNTIME)
        // The shared forwarder resolves u/<fullUID>/su to this delivered entry.
        val delivered = SuDelivery.deliveredPath(application, packageName)
            ?: return Result(false, 0, "unknown package", Reason.UNKNOWN_PACKAGE)
        if (!File(delivered).isFile)
            return Result(false, 0, "SU_CLIENT_NOT_DELIVERED", Reason.CLIENT)
        val suDirectory = SuPublication.suDirectory(context)
            ?: return Result(false, 0, Reason.PUBLICATION, Reason.PUBLICATION)
        run {
            Log.i("MapleSU", "SU_WIRING_STAGE package=$packageName stage=precheck cmdline=[$cmdline]")
            val precheck = invoke(executable, current, "--wire-su-precheck", cmdline)
            Log.i("MapleSU", "SU_WIRING_STAGE package=$packageName stage=precheck_done code=${precheck.code}")
            if (precheck.code != 0)
                return Result(false, precheck.code, describe(precheck, "precheck"), Reason.PRECHECK)
            val candidates = precheck.out.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(' ')
                if (separator <= 0) null
                else Candidate(line.substring(0, separator).toIntOrNull() ?: return@mapNotNull null,
                    line.substring(separator + 1))
            }.toList()
            if (candidates.isEmpty())
                return Result(false, PRECHECK_REJECTED,
                    "no eligible JNI library; run the target once and retry", Reason.NO_CANDIDATES)
            // Prefer a library that the target's MAIN process actually has mapped. The
            // precheck reports "running" for any process of the package, so it can choose a
            // library that the main process never loads (MT: it picked libffmpegJNI.so,
            // which only media playback loads) and the PATH then never changes.
            val mapped = mappedLibraries(context, packageName)
            // Never patch our own side libraries: they are root-owned, they are mapped in
            // some process, and patching them just chains one side library onto another
            // without ever changing the target's PATH.
            val usable = candidates.filterNot { rootOwned(context, it.path) }
            val chosen = usable.firstOrNull { it.path in mapped }
                ?: usable.firstOrNull { it.status == RUNNING_STATUS }
                ?: usable.firstOrNull()
                ?: return Result(false, PRECHECK_REJECTED, "no patchable JNI library", Reason.NO_PATCHABLE)
            val chosenDirectory = File(chosen.path).parent
                ?: return Result(false, PRECHECK_REJECTED, "invalid JNI library path", Reason.NO_PATCHABLE)
            Log.i("MapleSU", "SU_WIRING_STAGE package=$packageName stage=choose mapped=${mapped.size} chosen=${chosen.path}")
            // W19：**修改前**备份被 patch 的原库并登记（fail closed：备份失败则不植入）。
            val recorder = LiteImplantRecorderProvider.current()
            var preparation: ImplantPreparation? = null
            if (recorder != null) {
                val deliverSha = SuDelivery.deliveredDigest(application, packageName).orEmpty()
                preparation = runCatching {
                    recorder.beforeWiring(
                        packageName = packageName,
                        uid = uid,
                        targetLibraryPath = chosen.path,
                        deliverPath = delivered,
                        deliverSha256 = deliverSha,
                        createdDirs = listOf(chosenDirectory),
                        now = System.currentTimeMillis(),
                    )
                }.getOrElse {
                    ImplantPreparation.failed(LiteImplantCodes.BACKUP_FAILED, it.javaClass.simpleName)
                }
                if (!preparation.ok) {
                    return Result(false, 0, preparation.code, Reason.BACKUP)
                }
            }
            if (!current()) return Result(false, 0, Reason.IDENTITY, Reason.IDENTITY)
            authorizationFailure(application, uid)?.let { return Result(false, 0, it, it) }
            val implant = invoke(executable, current, "--wire-su-env", cmdline, chosen.path, suDirectory)
            if (implant.code == 0) {
                // The upstream implant creates the side library world-writable and
                // root-owned. Nothing needs to write it after it is installed, so tighten
                // it to the mode the application's own libraries use.
                // The manager does not know the random name of the side library, so tighten
                // whichever root-owned libraries are present: the implant writes them 0777.
                DirectRoot.run(context, "/system/bin/find " + quote(chosenDirectory) +
                    " -maxdepth 1 -name 'lib*' -user 0 -exec /system/bin/chmod 0755 {} + 2>/dev/null")
                if (preparation != null) {
                    runCatching { recorder?.afterWiring(preparation) }
                }
            }
            return if (implant.code == 0) {
                Log.i("MapleSU", "SU_PATH_WIRED package=$packageName uid=$uid lib=${chosen.path}")
                Result(true, 0, chosen.path, Reason.OK)
            } else {
                Result(false, implant.code, describe(implant, "implant"), Reason.IMPLANT)
            }
        }
    }

    private const val RUNNING_STATUS = 1

    /**
     * True when the application ships no native library directory at all.
     *
     * <p>This is the population PATH injection exists for, and it is also the reason the
     * implant can never help: there is no `.so` to patch and no directory to deliver a client
     * into. The check needs no root - a missing directory is visible to this process.
     */
    fun hasNoNativeLibraries(context: Context, packageName: String): Boolean {
        val directory = runCatching {
            context.applicationContext.packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
        }.getOrNull() ?: return true
        return !File(directory).isDirectory
    }

    /** How long the native helper waits for a process that does not exist yet. */
    private const val DEFAULT_INJECT_WAIT_MS = 3_000
    private const val INJECT_ATTEMPTS = 3
    private const val INJECT_RETRY_DELAY_MS = 300L

    private val INJECTED = Regex("INJECTED:(\\d+)")

    private data class Invocation(val code: Int, val out: String, val err: String)

    /**
     * Runs one native helper step.
     *
     * <p>Both streams are drained on their own threads: reading stdout to completion before
     * touching stderr deadlocks as soon as the helper writes enough to stderr, which is
     * exactly how the wiring test appeared to hang. A timeout also turns "stuck" into a
     * result instead of an unending wait.
     */
    private fun invoke(executable: File, current: () -> Boolean, vararg arguments: String): Invocation {
        if (!current()) return Invocation(-1, "", Reason.IDENTITY)
        val process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .redirectErrorStream(false)
            .start()
        try {
            process.outputStream.close()
        } catch (error: java.io.IOException) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
            return Invocation(-1, "", "stdin close failed")
        }
        val out = StringBuffer()
        val err = StringBuffer()
        val outThread = Thread { runCatching { process.inputStream.bufferedReader().use { out.append(it.readText()) } } }
            .apply { isDaemon = true; start() }
        val errThread = Thread { runCatching { process.errorStream.bufferedReader().use { err.append(it.readText()) } } }
            .apply { isDaemon = true; start() }
        try {
            val finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            outThread.join(2_000)
            errThread.join(2_000)
            val code = if (finished) process.exitValue() else -1
            if (code == 0 && !current()) return Invocation(-1, out.toString(), Reason.IDENTITY)
            if (code == 0 && (outThread.isAlive || errThread.isAlive))
                return Invocation(-1, out.toString(), "stream drain incomplete")
            return Invocation(code, out.toString(), err.toString())
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return Invocation(-1, out.toString(), "helper interrupted")
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }


    private fun describe(invocation: Invocation, stage: String): String {
        val detail = invocation.err.lineSequence().firstOrNull { it.isNotBlank() }
            ?: invocation.out.lineSequence().firstOrNull { it.isNotBlank() }
            ?: "no diagnostic"
        return "$stage failed (${invocation.code}): ${detail.take(160)}"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * The command line the upstream precheck matches against: it enumerates processes
     * whose `cmdline` belongs to the target package. The main process uses the package
     * name; a private process is `package:name`, so the main one is preferred.
     */
    internal fun runningCommandLineFor(context: Context, packageName: String): String? =
        runningCommandLine(context, packageName)

    private fun runningCommandLine(context: Context, packageName: String): String? {
        // `/proc/<pid>/cmdline` of another application is denied to an untrusted app, so
        // the read runs as root in one short-lived process. The selection is narrowed in
        // the shell: reading every cmdline and parsing here produced a flood of output for
        // no benefit. NUL separation stays in Kotlin, because a NUL cannot appear in a
        // command line at all.
        val script = "/system/bin/grep -l -a " + quote(packageName) +
            " /proc/[0-9]*/cmdline 2>/dev/null | while read f; do /system/bin/cat \"\$f\"; " +
            "/system/bin/echo; done"
        Log.i("MapleSU", "SU_WIRING_STAGE package=$packageName stage=cmdline_lookup")
        val output = DirectRoot.run(context, script).output
        Log.i("MapleSU", "SU_WIRING_STAGE package=$packageName stage=cmdline_done bytes=${output.length}")
        return output.lineSequence()
            .map { it.replace('\u0000', ' ').trim() }
            .firstOrNull { it == packageName || it.startsWith("$packageName:") || it.startsWith("$packageName ") }
    }

    /**
     * Libraries the target's main process has mapped.
     *
     * <p>The precheck reports "running" for the package as a whole, so it can pick a
     * library only a secondary process loads; patching that one never changes the PATH of
     * the process that runs `su`. Matching against the main process's maps avoids that.
     */
    private fun mappedLibraries(context: Context, packageName: String): Set<String> {
        val script = "p=\$(/system/bin/ps -A -o PID,NAME | /system/bin/grep -E ' " + packageName +
            "$' | /system/bin/head -1 | /system/bin/awk '{print \$1}' 2>/dev/null); " +
            "[ -n \"\$p\" ] && /system/bin/grep -o '/data/app/[^ ]*[.]so' /proc/\$p/maps | /system/bin/sort -u"
        return DirectRoot.run(context, script).output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/data/app/") && it.endsWith(".so") }
            .toSet()
    }

    /** Side libraries are created root-owned; the application's own are not. */
    private fun rootOwned(context: Context, path: String): Boolean {
        val line = DirectRoot.run(context, "/system/bin/stat -c %u '" + path.replace("'", "'\''") + "'").output
        return line.trim().lines().lastOrNull()?.trim() == "0"
    }

    /**
     * PATH injection for applications that have no library to patch.
     *
     * <p>Web UIs and pure-Java utilities ship no `.so` at all, so the implant above cannot
     * reach them: there is nothing to add a DT_NEEDED entry to. Upstream's answer for
     * exactly this case is a ptrace injection into the running process, and that is what
     * this calls. It is transient, so the process keeps the PATH only while it lives.
     *
     * <p>PATH exists only inside a live process. A grant that arrives while the
     * application is still starting used to fail with "no process" and stay failed, so
     * this waits for a process, starts the application when it is closed (the same
     * [ensureRunning] the implant path already used), and retries the call: the native
     * side covers every process of the package and every upstream offset strategy on its
     * own, but a process that is still being forked can refuse the first attempt.
     */
    fun injectPath(context: Context, packageName: String, waitMs: Int = DEFAULT_INJECT_WAIT_MS): Result {
        val application = context.applicationContext
        // W19 写者静默：切换期间拒绝任何新的 PATH 注入。
        LiteImplantQuiesceGate.blockedCode()?.let { code ->
            return Result(false, 0, code, Reason.QUIESCING)
        }
        if (packageName == application.packageName)
            return Result(false, 0, "refusing to wire the manager itself", Reason.SELF)
        val uid = runCatching { application.packageManager.getApplicationInfo(packageName, 0).uid }
            .getOrNull() ?: return Result(false, 0, "unknown package", Reason.UNKNOWN_PACKAGE)
        authorizationFailure(application, uid)?.let { return Result(false, 0, it, it) }
        val current = UltraSdkCredentials.captureOwnerMarker(application)
        if (!current()) return Result(false, 0, Reason.IDENTITY, Reason.IDENTITY)
        val runtime = File(application.applicationInfo.nativeLibraryDir, "libmaplesu_runtime.so")
        if (!runtime.isFile || !runtime.canExecute())
            return Result(false, 0, "runtime image unavailable", Reason.RUNTIME)
        val directory = SuPublication.suDirectory(context)
            ?: return Result(false, 0, Reason.PUBLICATION, Reason.PUBLICATION)
        run {
            if (runningCommandLine(application, packageName) == null &&
                !ensureRunning(application, packageName, waitMs.toLong())) {
                Log.i("MapleSU", "SU_INJECT_PATH package=$packageName ok=false reason=no-process")
                return Result(false, 68, "target has no running process", Reason.NOT_RUNNING)
            }
            var last = Result(false, 69, "injection rejected", Reason.INJECT)
            repeat(INJECT_ATTEMPTS) { attempt ->
                if (attempt > 0) Thread.sleep(INJECT_RETRY_DELAY_MS)
                if (!current()) return Result(false, 0, Reason.IDENTITY, Reason.IDENTITY)
                authorizationFailure(application, uid)?.let { return Result(false, 0, it, it) }
                val attemptResult = invokeInject(runtime, current, packageName, uid, directory, waitMs)
                if (attemptResult.ok) {
                    // W19：瞬态 PATH 注入无持久修改，登记 PATH_INJECTION（originalAbsent）。
                    recordPathInjection(application, packageName)
                    return attemptResult
                }
                last = attemptResult
            }
            return last
        }
    }

    private fun authorizationFailure(context: Context, uid: Int): String? {
        when (DirectPolicy.hasGrantStrict(context, uid)) {
            true -> Unit
            false -> return Reason.AUTH_NOT_GRANTED
            null -> return Reason.AUTH_STATE_UNAVAILABLE
        }
        return when (DirectPolicy.isEnabled(context)) {
            true -> null
            false -> Reason.AUTH_DISABLED
            null -> Reason.AUTH_STATE_UNAVAILABLE
        }
    }

    /** W19：记录瞬态 PATH 注入（无持久修改 → PATH_INJECTION，originalAbsent）。 */
    private fun recordPathInjection(context: Context, packageName: String) {
        val recorder = LiteImplantRecorderProvider.current() ?: return
        val uid = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrNull() ?: return
        val deliverPath = SuDelivery.deliveredPath(context, packageName).orEmpty()
        val deliverSha = runCatching { SuDelivery.deliveredDigest(context, packageName) }.getOrNull().orEmpty()
        runCatching {
            recorder.recordPathInjection(packageName, uid, deliverPath, deliverSha, System.currentTimeMillis())
        }
    }

    private fun invokeInject(
        runtime: File,
        current: () -> Boolean,
        packageName: String,
        uid: Int,
        directory: String,
        waitMs: Int
    ): Result {
        val invocation = invoke(
            runtime, current, "--inject-path", packageName, uid.toString(), directory, waitMs.toString())
        val injected = INJECTED.find(invocation.out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ok = invocation.code == 0 && injected > 0
        android.util.Log.i("MapleSU", "SU_INJECT_PATH package=$packageName ok=$ok count=$injected " +
            "code=${invocation.code} out=[${invocation.out.trim().take(160)}] " +
            "err=[${invocation.err.trim().take(160)}]")
        return Result(
            ok, invocation.code,
            if (ok) "injected:$injected" else firstLine(invocation),
            if (ok) Reason.OK else Reason.INJECT)
    }

    private fun firstLine(invocation: Invocation): String =
        invocation.err.lineSequence().firstOrNull { it.isNotBlank() }
            ?: invocation.out.lineSequence().firstOrNull { it.isNotBlank() }
            ?: "no diagnostic"

    /**
     * Starts a closed application just long enough to wire it.
     *
     * <p>PATH only exists inside a live process, so an application that is closed when it is
     * granted cannot be wired at that moment. Launching it is the only way to complete the
     * grant without asking the user to open the app and come back; the caller decides
     * whether that is acceptable, because a launch is visible.
     *
     * @return true when a process appeared within the window.
     */
    fun ensureRunning(context: Context, packageName: String, timeoutMs: Long = 2_000): Boolean {
        if (runningCommandLine(context, packageName) != null) return true
        val launcher = DirectRoot.run(context,
            "/system/bin/cmd package resolve-activity --brief $packageName | /system/bin/tail -1").output
            .trim().lines().lastOrNull().orEmpty()
        if (!launcher.contains('/')) return false
        // `am` is picky about its caller and about quoting the component; keep the output so
        // a refusal is visible in the log instead of silently leaving the app unwired.
        val started = DirectRoot.run(context,
            "/system/bin/am start -n '$launcher' --activity-single-top 2>&1 | /system/bin/tail -2")
        android.util.Log.i("MapleSU",
            "SU_LAUNCH package=$packageName comp=$launcher out=[${started.output.trim().take(120)}]")
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (runningCommandLine(context, packageName) != null) return true
            Thread.sleep(200)
        }
        return false
    }

    /** Root-owned libraries this project created in the target's library directory. */
    private fun sideLibraryCount(context: Context, packageName: String): Int {
        val directory = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
        }.getOrNull() ?: return 0
        val count = DirectRoot.run(context,
            "/system/bin/find '" + directory + "' -maxdepth 1 -name 'lib*' -user 0 2>/dev/null | /system/bin/wc -l")
            .output.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
        return count
    }
}
