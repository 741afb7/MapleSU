package dev.maplesu.manager.runtime.ultra

import android.content.Context
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode

/** Startup delegates to the existing observation queue; no secret restoration or reinstall. */
data class UltraSelfHealOutcome(val attempted: Boolean, val action: String, val environment: String? = null,
                                val failure: String? = null) {
    val summary: String get() = "attempted=$attempted action=$action env=$environment failure=$failure"
}

object UltraSelfHeal {
    const val TAG = "MapleSUUltraSelfHeal"
    const val PREFS = "maplesu-ultra-selfheal"
    const val KEY_LAST_FAILURE_AT = "lastFailureAtMillis"
    const val KEY_FAILURE_COUNT = "failureCount"
    const val KEY_LAST_OUTCOME = "lastOutcome"
    const val KEY_LAST_OUTCOME_AT = "lastOutcomeAtMillis"
    const val MODE_WAIT_MILLIS = 12_000L

    @Volatile var lastOutcome: UltraSelfHealOutcome? = null
        private set

    fun runOnStartup(context: Context, modeWaitMillis: Long = MODE_WAIT_MILLIS) {
        if (UltraIdentityRecovery.blocked(context)) {
            lastOutcome = UltraSelfHealOutcome(false, "DEFERRED", failure = "IDENTITY_PENDING")
            return
        }
        UltraEnvironmentProbe.requestRefresh(context)
        lastOutcome = UltraSelfHealOutcome(true, "OBSERVATION_REQUESTED")
    }

    fun awaitModeResolved(timeoutMillis: Long, pollMillis: Long = 200L,
                          sleeper: (Long) -> Unit = { Thread.sleep(it) },
                          modeProvider: () -> WorkMode? = { ModeRuntime.active }): WorkMode? {
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(0L)
        var resolved = runCatching(modeProvider).getOrNull()
        while (resolved == null && System.currentTimeMillis() < deadline) {
            try { sleeper(pollMillis) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
            resolved = runCatching(modeProvider).getOrNull()
        }
        return resolved
    }

    internal fun resetForTest() { lastOutcome = null }
}
