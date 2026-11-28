package dev.maplesu.manager.runtime.ultra

import java.util.concurrent.CancellationException

/** A never-started installation is distinct from a failed or initializing Unknown boot. */
internal object UltraIdleRecoveryAttempt {
    interface Port {
        fun blockedReason(): String?
        fun alreadySubmitted(): Boolean
        fun idleVerified(): Boolean
        fun helperVerified(): Boolean
        fun channelVerified(): Boolean
        fun markSubmitted(): Boolean
        fun prepare(): String?
        fun submit(): Boolean
        fun running(): Boolean
        fun functionalityVerified(): Boolean
    }

    fun run(port: Port): UltraEnvironmentAutoSetup.Result? {
        var submitted = false
        fun result(reason: String, ready: Boolean = false) =
            UltraEnvironmentAutoSetup.Result(submitted, ready, reason)
        fun blocked() = port.blockedReason() != null
        try {
            if (blocked()) return null
            if (port.alreadySubmitted()) return result("IDLE_RECOVERY_ALREADY_SUBMITTED")
            if (!port.idleVerified() || blocked()) return null
            if (!port.helperVerified() || blocked()) return result("IDLE_HELPER_UNVERIFIED")
            if (!port.channelVerified() || blocked()) return result("IDLE_CHANNEL_UNVERIFIED")
            // Hashing and channel probes may outlive the initial filesystem observation.
            if (!port.idleVerified() || blocked()) return null
            port.prepare()?.let { return result("IDLE_PREPARATION_FAILED:$it") }
            if (blocked() || !port.idleVerified() || blocked()) return result("IDLE_SCOPE_CHANGED")
            if (!port.markSubmitted()) return result("IDLE_GUARD_STORE_FAILED")
            submitted = true
            if (blocked()) return result("IDLE_SCOPE_CHANGED")
            // Keep the durable guard on failure/timeout/process death. Never replay it.
            if (!port.submit()) return result("IDLE_START_FAILED")
            if (blocked() || !port.running()) return result("IDLE_START_UNCONFIRMED")
            if (blocked() || !port.functionalityVerified() || blocked())
                return result("IDLE_FUNCTIONALITY_UNCONFIRMED")
            return result("IDLE_RECOVERED", ready = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return result("IDLE_EXCEPTION:${error.javaClass.simpleName}")
        }
    }

    /** The backend emits a numeric mode or JSON null; coercing optInt accepts null/string. */
    fun effectivePolicyVerified(value: Any?): Boolean =
        value is Number && value.toDouble() in setOf(0.0, 1.0, 3.0)
}
