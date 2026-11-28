package dev.maplesu.manager.installer

import android.content.Context
import com.maplesu.core.R
import com.maplesu.core.ui.util.FlashOutcome
import com.maplesu.core.ui.util.FlashResult
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentUpgrade
import dev.maplesu.manager.runtime.ultra.UltraEnvUpgrade

/** Keeps a direct SDK update distinct from a native boot transaction after process death. */
internal object UltraDirectEnvironmentUpgrade {
    private const val PREFS = "ultra-direct-upgrade"

    fun install(context: Context, taskId: String?): FlashResult {
        record(context, taskId, "STARTED")
        val outcome = UltraEnvironmentUpgrade.installBundled(context)
        val result = when (outcome) {
            UltraEnvironmentUpgrade.Result.SUBMITTED -> completed(context)
            UltraEnvironmentUpgrade.Result.REFUSED -> FlashResult(1,
                context.getString(R.string.ultra_env_update_refused) + " (ULTRA_ENV_UPGRADE_REFUSED)", false,
                FlashOutcome.FAILED)
            UltraEnvironmentUpgrade.Result.FAILED -> FlashResult(1,
                context.getString(R.string.ultra_env_update_failed_detail,
                    UltraEnvironmentUpgrade.persistedFailure(context) ?: "ULTRA_ENV_UPGRADE_FAILED"), false,
                FlashOutcome.FAILED)
        }
        record(context, taskId, outcome.name, result.err)
        return result
    }

    fun unavailable(context: Context, taskId: String?, decision: UltraEnvUpgrade): FlashResult {
        val message = if (decision is UltraEnvUpgrade.Rebooting)
            context.getString(R.string.ultra_env_update_pending_restart)
        else context.getString(R.string.ultra_env_update_refused) + " (ULTRA_ENV_UPGRADE_REFUSED)"
        record(context, taskId, "REFUSED", message)
        return FlashResult(1, message, false, FlashOutcome.FAILED)
    }

    /** Reopening the page observes its saved result; it never replays an uncertain SDK write. */
    fun resume(context: Context, taskId: String): FlashResult? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = prefs.getString(taskId, null) ?: return null
        return if (status == UltraEnvironmentUpgrade.Result.SUBMITTED.name) completed(context)
        else FlashResult(1, prefs.getString("$taskId:error", null)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.ultra_env_update_interrupted), false, FlashOutcome.FAILED)
    }

    private fun completed(context: Context) = FlashResult(0,
        context.getString(R.string.ultra_env_update_complete), true, FlashOutcome.ENVIRONMENT_UPDATED)

    private fun record(context: Context, taskId: String?, state: String, error: String = "") {
        if (taskId == null) return
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(taskId, state).putString("$taskId:error", error).commit()) {
            "ULTRA_ENV_UPGRADE_TASK_SAVE_FAILED"
        }
    }
}
