package dev.maplesu.manager.jailbreak

import android.content.Context
import android.os.Binder
import android.os.Looper
import dev.maplesu.manager.runtime.NativeSuDaemon
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * UI-facing entry for the jailbreak path. It performs one trusted preparation in
 * the `:root_directory` process: run the selected provider, authenticate the
 * lease, verify the restoration, launch the existing Root executor from that
 * lease and verify the executor's own identity answer.
 *
 * A returned [Outcome.stage] of `DAEMON_READY` means the trusted process bound the
 * daemon from *this* attempt's lease and the executor answered its identity
 * check; it is not a claim about SU, modules or deployment, which the ordinary
 * Manager paths verify separately.
 */
object JailbreakController {
    data class Outcome(
        val stage: String, val message: String, val failure: String?, val attemptId: String? = null,
    )

    /** One command executed by the root executor that this attempt started. */
    data class RootProbe(
        val rootUid: Int,
        val rootPid: Int,
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
        val error: String? = null,
    ) {
        /** A probe only counts when a real child process exited with a real code. */
        val succeeded: Boolean get() = !timedOut && error == null && exitCode == 0 && rootUid == 0
    }

    private const val OUTPUT_LIMIT = 256 * 1024

    /**
     * Runs one command through the already authenticated Root executor. The
     * deadline covers the whole exchange - draining stdout and stderr *and*
     * waiting for the exit - so a command that never stops writing cannot keep
     * the caller blocked past its timeout.
     */
    fun probe(context: Context, argv: List<String>, timeoutMillis: Int = 10_000): RootProbe {
        check(Looper.myLooper() != Looper.getMainLooper()) { "JAILBREAK_BACKGROUND_THREAD_REQUIRED" }
        require(argv.isNotEmpty() && argv.first().startsWith("/"))
        val management = NativeSuDaemon.management(context)
        val state = management.status()
        val rootUid = state.getInt("rootUid", -1)
        val rootPid = state.getInt("rootPid", -1)
        val task = management.start(argv.toTypedArray(), Binder(), false)
        // S11: stdout, stderr and the exit wait must not share two threads.
        val streams = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "maplesu-root-probe").apply { isDaemon = true }
        }
        val timedOut = AtomicReference(false)
        val failure = AtomicReference<String?>(null)
        var stdout = ""
        var stderr = ""
        try {
            val output = StringBuilder()
            val errors = StringBuilder()
            val outReader = streams.submit { copy(task.openStdout(), output) }
            val errReader = streams.submit { copy(task.openStderr(), errors) }
            val work = streams.submit<Int> {
                try {
                    task.awaitExit(timeoutMillis).getInt("exitCode", -1)
                } catch (error: Exception) {
                    failure.set(error.javaClass.simpleName + ": " + (error.message ?: ""))
                    -1
                } finally {
                    runCatching { outReader.get(2, TimeUnit.SECONDS) }
                    runCatching { errReader.get(2, TimeUnit.SECONDS) }
                }
            }
            val exit = work.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            stdout = synchronized(output) { output.toString() }
            stderr = synchronized(errors) { errors.toString() }
            return RootProbe(rootUid, rootPid, exit, stdout, false, failure.get() ?: stderr.takeIf { it.isNotBlank() && exit != 0 })
        } catch (_: TimeoutException) {
            timedOut.set(true)
            // Closing the task is the cancel path: it terminates the child and
            // closes all three streams, which also releases the readers.
            runCatching { task.close() }
            return RootProbe(rootUid, rootPid, -1, "", true, "JAILBREAK_PROBE_TIMEOUT")
        } catch (error: ExecutionException) {
            return RootProbe(rootUid, rootPid, -1, "", false,
                error.cause?.javaClass?.simpleName ?: "JAILBREAK_PROBE_FAILED")
        } finally {
            runCatching { task.close() }
            streams.shutdownNow()
        }
    }

    private fun copy(descriptor: android.os.ParcelFileDescriptor?, sink: StringBuilder) {
        if (descriptor == null) return
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream: InputStream ->
            stream.bufferedReader().use { reader ->
                val chunk = CharArray(8192)
                var total = 0
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(sink) {
                        if (total < OUTPUT_LIMIT) {
                            val keep = minOf(read, OUTPUT_LIMIT - total)
                            sink.append(chunk, 0, keep)
                            total += keep
                        }
                    }
                }
            }
        }
    }

    // Keep the historical two-argument entry point for already-built callers.
    /**
     * Step-4 (item 25/MF-1): `rootBackend` reaches the provider through the request, and
     * the hook route is prepared - not "managed" - because it has no management binder.
     * Both branches go through the provider, so the hook route's `DAEMON_READY` write
     * point really executes on the product path too.
     */
    @JvmOverloads
    fun run(context: Context, method: JailbreakMethod, softReboot: Boolean = false,
            rootBackend: String? = null): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "JAILBREAK_BACKGROUND_THREAD_REQUIRED" }
        val request = JailbreakRequest.create(method, rootBackend).copy(softReboot = softReboot)
        runCatching { JailbreakDiagnostics.begin(context.filesDir, request) }
            .onFailure { android.util.Log.w("JailbreakDiagnostics", "Attempt log initialization failed", it) }
        val outcome = try {
            runAttempt(context, method, rootBackend, request)
        } catch (error: Exception) {
            Outcome(JailbreakStatus.STAGE_FAILED, error.message ?: error.javaClass.simpleName,
                "JAILBREAK_ATTEMPT_FAILED", request.attemptId)
        }.let { it.copy(attemptId = it.attemptId ?: request.attemptId) }
        runCatching { JailbreakDiagnostics.recordOutcome(context.filesDir, request, outcome) }
            .onFailure { android.util.Log.w("JailbreakDiagnostics", "Caller outcome snapshot failed", it) }
        return outcome
    }

    private fun runAttempt(context: Context, method: JailbreakMethod, rootBackend: String?,
                           request: JailbreakRequest): Outcome {
        /*
         * 2026-11-02: no "session already active" dialog. Instead, a carrier that is
         * still ARMED for this boot is reused (soft reboot), never destroyed by a retry;
         * only when there is no usable carrier is the old record cleared.
         */
        val persisted = JailbreakStatus.read(context)
        if (persisted != null && rootBackend == JailbreakRequest.BACKEND_HOOK &&
            persisted.rootBackend == JailbreakRequest.BACKEND_HOOK &&
            !request.bootId.isNullOrBlank() && persisted.bootId == request.bootId) {
            val reproved = NativeSuDaemon.reproveHookOutcome(context)
            if (reproved != null) {
                return completed(context, reproved)
            }
        }
        if (persisted != null) JailbreakStatus.clear(context)
        return try {
            val prepared = if (rootBackend == JailbreakRequest.BACKEND_HOOK) {
                NativeSuDaemon.prepareHook(context, request)
            } else if (rootBackend == JailbreakRequest.BACKEND_KSU) {
                // KSU has no lease and no management binder: the provider promotes the
                // record to DAEMON_READY itself and answers with data.
                NativeSuDaemon.prepareKsu(context, request)
                null
            } else {
                NativeSuDaemon.management(context, request)
                null
            }
            val observed = prepared ?: JailbreakStatus.read(context)
            // The trusted process writes DAEMON_READY only after the lease of this
            // attempt produced the endpoint. Anything else is reported as what it
            // is instead of being relabelled here.
            completed(context, preparedJailbreakOutcome(request, observed))
        } catch (error: Exception) {
            val observed = matchingJailbreakStatus(request, JailbreakStatus.read(context))
            val reason = observed?.failure
                ?: error.cause?.message
                ?: error.message
                ?: error.javaClass.simpleName
            val cancelled = observed?.stage == JailbreakStatus.STAGE_CANCELLED
            val failed = (observed ?: JailbreakStatus(
                method = method.wireName, stage = JailbreakStatus.STAGE_FAILED,
                message = reason, startedAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(), bootId = DirtyfragAttempt.readBootId(),
                attemptId = request.attemptId,
            )).copy(stage = if (cancelled) JailbreakStatus.STAGE_CANCELLED else JailbreakStatus.STAGE_FAILED,
                message = reason,
                updatedAtMillis = System.currentTimeMillis(), failure = observed?.failure ?: reason)
            /*
             * T3 / C1: this runs in the UI process. The failure travels back to the
             * caller as an Outcome; persisting it is the provider's decision, because
             * only the provider may write `status.json`.
             */
            Outcome(failed.stage, reason, failed.failure)
        }
    }

    private fun completed(context: Context, outcome: Outcome): Outcome {
        runCatching {
            if (outcome.stage == JailbreakStatus.STAGE_DAEMON_READY && outcome.failure == null)
                dev.maplesu.manager.workmode.backend.KsuBackendRegistry.prepareJailbreakManager(context)
        }.onFailure { android.util.Log.w("MapleSUJailbreak", "Normal manager driver handover pending", it) }
        runCatching {
            refreshJailbreakEnvironment(outcome, invalidate = {
                dev.maplesu.manager.workmode.detect.probes.SuReadinessProbeAdapter.invalidateCache()
                dev.maplesu.manager.workmode.detect.probes.EnvironmentCheckProduction.markDirty(
                    context, dev.maplesu.manager.workmode.detect.EnvironmentInvalidation.JAILBREAK_COMPLETED)
            }, detect = {
                dev.maplesu.manager.workmode.boot.ModeBootstrap.retryEnvironmentDetection()
            }, prepare = {
                if (dev.maplesu.manager.runtime.HookRootExecutor.production(context).available()) {
                    val result = dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.ensure(context)
                    android.util.Log.i("MapleSUJailbreak", "Ultra environment setup: $result")
                }
            })
        }.onFailure { android.util.Log.w("MapleSUJailbreak", "environment refresh deferred", it) }
        return outcome
    }

    /** Cancels the running attempt through the trusted process, not through UI state. */
    fun cancel(context: Context): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "JAILBREAK_BACKGROUND_THREAD_REQUIRED" }
        return NativeSuDaemon.cancel(context)
    }

    /**
     * The persisted attempt with current observations in `derived`. History keeps
     * its original stage; records from another boot never probe the old transport.
     */
    fun status(context: Context): JailbreakStatus? {
        val persisted = JailbreakStatus.read(context)
        if (persisted == null || !persisted.isCurrentBoot(DirtyfragAttempt.readBootId())) return persisted
        val probe = NativeSuDaemon.probeStatus(context)
        if (probe == null) return persisted
        if (persisted.stage !in JailbreakStatus.LIVE_CLAIM) return persisted
        /*
         * C2/T5: the probe observes; it never rewrites the stage. Whether the transport
         * still answers is reported in `derived` for the caller to present, and nothing
         * is written to disk.
         */
        return persisted.withDerived(Derived(
            bootChanged = persisted.derived?.bootChanged ?: false,
            ownerAlive = persisted.derived?.ownerAlive,
            carrierLive = probe.getBoolean("leaseLive", false),
        ))
    }

    /** Bounded wait used by tests and by callers that cannot block a UI thread. */
    fun awaitTerminal(context: Context, timeout: Long, unit: TimeUnit): JailbreakStatus? {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            val status = JailbreakStatus.read(context)
            if (status != null && status.isTerminal()) return status
            Thread.sleep(200)
        }
        return JailbreakStatus.read(context)
    }
}
