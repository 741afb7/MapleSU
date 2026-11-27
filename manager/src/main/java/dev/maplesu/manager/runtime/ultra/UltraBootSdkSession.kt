package dev.maplesu.manager.runtime.ultra

import org.json.JSONObject
import java.util.concurrent.CancellationException

/** A non-secret installation observation scoped to one kernel boot. */
internal data class UltraBootSdkSession private constructor(
    val bootId: String,
    val installSubmitted: Boolean,
) {
    fun current(currentBootId: String?): Boolean = currentBootId == bootId
    fun submitted(): UltraBootSdkSession = copy(installSubmitted = true)
    fun encode(): String = JSONObject().put("v", 2).put("bootId", bootId)
        .put("installSubmitted", installSubmitted).toString()

    companion object {
        private val BOOT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        fun create(bootId: String?, installSubmitted: Boolean = false): UltraBootSdkSession? =
            bootId?.takeIf(BOOT_ID::matches)?.let { UltraBootSdkSession(it, installSubmitted) }
        fun parse(text: String?): UltraBootSdkSession? = runCatching {
            if (text.isNullOrEmpty() || text.length > 1024) return null
            val record = JSONObject(text)
            if (record.getInt("v") != 2) return null
            create(record.getString("bootId"), record.getBoolean("installSubmitted"))
        }.getOrNull()
    }
}

/** HotLoad has one durable submission. A lost reply is reconciled through observations only. */
internal object UltraBootSdkInstall {
    interface Port {
        fun current(): Boolean
        fun channelVerified(): Boolean
        fun kernelBaseVerified(): Boolean
        fun environmentState(): String?
        fun prepare(): Boolean
        fun markSubmitted(): Boolean
        fun submit(): NativeReceipt
        fun rootVerified(): Boolean
        fun whitelistVerified(): Boolean
        fun managerSuVerified(): Boolean
        fun moduleListVerified(): Boolean
    }

    /** Fixed receipt fields only; raw SDK details can contain deployment paths. */
    data class Submission(val succeeded: Boolean, val operation: String, val sdkError: String, val sdkErrorCode: Int)

    data class Result(val ready: Boolean, val submitted: Boolean, val reason: String, val submission: Submission? = null)

    fun ensure(alreadySubmitted: Boolean, port: Port): Result {
        var submitted = false
        var submission: Submission? = null
        fun failed(reason: String) = Result(false, submitted, reason, submission)
        return try {
            if (!port.current()) return failed("SESSION_CHANGED")
            if (!port.channelVerified()) return failed("SDK_CHANNEL_UNVERIFIED")
            if (!port.current()) return failed("SESSION_CHANGED")
            if (!port.kernelBaseVerified()) return failed("SDK_METADATA_UNVERIFIED")
            if (!port.current()) return failed("SESSION_CHANGED")
            when (port.environmentState()) {
                "Running" -> Unit
                "NotInstalled" -> {
                    if (alreadySubmitted) return failed("HOTLOAD_RECONCILIATION_REQUIRED")
                    if (!port.current()) return failed("SESSION_CHANGED")
                    if (!port.prepare()) return failed("HOTLOAD_PREPARATION_FAILED")
                    if (!port.current()) return failed("SESSION_CHANGED")
                    if (!port.markSubmitted()) return failed("HOTLOAD_GUARD_STORE_FAILED")
                    if (!port.current()) return failed("SESSION_CHANGED")
                    submitted = true
                    // A throwable may follow a successful submission. It grants no retry.
                    try {
                        val receipt = port.submit()
                        submission = Submission(receipt.succeeded, receipt.operation, receipt.sdkError, receipt.sdkErrorCode)
                    } catch (error: Exception) {
                        if (error is InterruptedException) Thread.currentThread().interrupt()
                    }
                    if (!port.current()) return failed("SESSION_CHANGED")
                    if (port.environmentState() != "Running") {
                        val detail = submission?.takeUnless { it.succeeded }?.let {
                            ":${it.operation}:${it.sdkError}:${it.sdkErrorCode}"
                        }.orEmpty()
                        return failed("HOTLOAD_RECONCILIATION_REQUIRED$detail")
                    }
                }
                "Fault" -> return failed("ENVIRONMENT_FAULT")
                else -> return failed("ENVIRONMENT_STATE_UNKNOWN")
            }
            if (!port.current()) return failed("SESSION_CHANGED")
            if (!port.rootVerified()) return failed("SDK_ROOT_UNVERIFIED")
            if (!port.current()) return failed("SESSION_CHANGED")
            if (!port.whitelistVerified()) return failed("SELF_WHITELIST_UNVERIFIED")
            if (!port.current()) return failed("SESSION_CHANGED")
            if (!port.managerSuVerified()) return failed("MANAGER_SU_UNVERIFIED")
            if (!port.current()) return failed("SESSION_CHANGED")
            if (!port.moduleListVerified()) return failed("MODULE_LIST_UNVERIFIED")
            if (!port.current()) return failed("SESSION_CHANGED")
            Result(true, submitted, "RUNNING_VERIFIED", submission)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failed("SDK_OBSERVATION_FAILED")
        }
    }
}
