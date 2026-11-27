package dev.maplesu.manager.runtime.ultra

import java.util.concurrent.CancellationException

/** One durable submission per boot; uncertain submissions are observed, never replayed. */
internal object UltraAutoSetupAttempt {
    interface Port {
        fun blockedReason(): String?
        fun state(): UltraEnvironmentProbe.State
        fun recordAttempt()
        fun alreadySubmitted(): Boolean
        fun markSubmitted(): Boolean
        fun clearUnsubmitted()
        fun install(): UltraEnvironmentInstaller.Outcome
        fun refresh()
    }

    fun run(port: Port, recoverIdle: () -> UltraEnvironmentAutoSetup.Result? = { null }): UltraEnvironmentAutoSetup.Result {
        fun skipped(reason: String) = UltraEnvironmentAutoSetup.Result(false, false, reason)
        port.blockedReason()?.let { return skipped(it) }
        val state = runCatching { port.state() }.getOrElse { return skipped("PROBE_FAILED") }
        when (state) {
            UltraEnvironmentProbe.State.Running -> return skipped("ALREADY_RUNNING")
            UltraEnvironmentProbe.State.NotInstalled -> Unit
            UltraEnvironmentProbe.State.Unknown -> return recoverIdle() ?: skipped("STATE_$state")
            else -> return skipped("STATE_$state")
        }
        if (port.alreadySubmitted()) return skipped("INSTALL_RECONCILIATION_REQUIRED")
        // Probe may block across an installation change, reboot, pause, or mode switch.
        port.blockedReason()?.let { return skipped(it) }
        runCatching { port.recordAttempt() }
        port.blockedReason()?.let { return skipped(it) }
        if (!port.markSubmitted()) return skipped("INSTALL_GUARD_STORE_FAILED")
        port.blockedReason()?.let { return skipped(it) }
        val outcome = try {
            port.install()
        } catch (cancelled: UltraInstallPreparationCancelled) {
            port.clearUnsubmitted()
            throw cancelled.cancellation
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return UltraEnvironmentAutoSetup.Result(true, false, "EXCEPTION:${error.javaClass.simpleName}")
        }
        if (!outcome.ok && !outcome.submissionUnknown) port.clearUnsubmitted()
        runCatching { port.refresh() }
        val installed = outcome.ok && outcome.verified
        return UltraEnvironmentAutoSetup.Result(true, installed, when {
            outcome.submissionUnknown -> "INSTALL_RECONCILIATION_REQUIRED"
            !outcome.ok -> "SDK_ERROR_${outcome.sdkError}"
            !outcome.verified -> "INSTALL_UNVERIFIED"
            else -> "INSTALLED"
        })
    }
}
