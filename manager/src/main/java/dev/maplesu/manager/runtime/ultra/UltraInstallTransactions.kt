package dev.maplesu.manager.runtime.ultra

import android.content.Context
import dev.maplesu.manager.compat.ultraPreferences
import dev.maplesu.manager.installer.InstallCandidateImage
import dev.maplesu.manager.ultrakernel.UltraKernelContextAssets
import dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import java.io.File

/** Candidate write identity only; this record grants no SDK capability. */
internal object UltraInstallTransactions {
    const val IMAGE_EXPORT_OPERATION = "image-export"
    private const val PREFS = "ultra-install-transaction"

    @Synchronized
    fun retainCandidate(context: Context, image: File, sha256: String, operationId: String) {
        check(image.isFile && UltraKernelSpec.sha256(image) == sha256) { "ULTRA_KERNEL_CANDIDATE_CHANGED" }
        if (operationId == IMAGE_EXPORT_OPERATION) return
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        val same = prefs.getString("operationId", null) == operationId && prefs.getString("sha256", null) == sha256
        check(prefs.edit().putString("operationId", operationId).putString("sha256", sha256)
            .putString("sourceBoot", UltraEnvironmentAutoSetup.currentBootId())
            .putBoolean("committed", same && prefs.getBoolean("committed", false)).commit()) {
            "ULTRA_INSTALL_TRANSACTION_STORE_FAILED"
        }
    }

    @Synchronized
    fun observe(context: Context, taskId: String?, state: String?, imageSha256: String?, rebootSourceState: String? = null) {
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        if (taskId == null || prefs.getString("operationId", null) != taskId) return
        val effective = if (state == "REBOOT_REQUESTED" && rebootSourceState == "RESTORED") "RESTORED" else state
        when (effective) {
            "REBOOT_REQUIRED", "REBOOT_REQUESTED", "BOOT_IMAGE_VERIFIED" -> {
                check(imageSha256 == prefs.getString("sha256", null)) { "ULTRA_KERNEL_CANDIDATE_CHANGED" }
                check(prefs.edit().putBoolean("committed", true).commit()) { "ULTRA_INSTALL_TRANSACTION_STORE_FAILED" }
            }
            "RESTORED", "ROLLED_BACK", "FAILED_BEFORE_WRITE", "FAILED_PRE_WRITE" ->
                check(prefs.edit().clear().commit()) { "ULTRA_INSTALL_TRANSACTION_STORE_FAILED" }
        }
    }

    fun fingerprint(context: Context, operationId: String? = null): String? {
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        if (operationId != null && prefs.getString("operationId", null) != operationId) return null
        return prefs.getString("sha256", null)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    fun retainSignedIdentity(context: Context, previousImage: File, previousSha256: String,
                             signed: InstallCandidateImage, operationId: String) {
        check(UltraKernelSpec.sha256(previousImage) == previousSha256 &&
            UltraKernelSpec.sha256(signed.image) == signed.sha256) { "ULTRA_KERNEL_CANDIDATE_CHANGED" }
        check(UltraKernelOriginalRecovery(UltraKernelContextAssets(context)).retainSignedCandidate(
            previousImage, signed.image, dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir))) {
            "ULTRA_KERNEL_SIGNED_RECEIPT_REQUIRED"
        }
        retainCandidate(context, signed.image, signed.sha256, operationId)
    }
}
