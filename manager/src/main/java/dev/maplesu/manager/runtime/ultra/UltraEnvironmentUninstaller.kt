package dev.maplesu.manager.runtime.ultra

import android.content.Context
import dev.maplesu.manager.installer.InstallerServiceClient
import dev.maplesu.manager.installer.ImageRestorationKind
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.workmode.ModeRuntime
import java.util.UUID

/** Explicit settings removal. Inhibit auto setup through an explicit removal marker. */
internal object UltraEnvironmentUninstaller {
    data class Outcome(val ok: Boolean, val stockRestored: Boolean, val sdkError: String,
                       val receipt: NativeReceipt? = null) {
        val sdkErrorCode: Int? get() = receipt?.sdkErrorCode
        val reason: String? get() = receipt?.reason

        override fun toString(): String = "Outcome(ok=$ok, stockRestored=$stockRestored, " +
            "sdkError=${UltraSdkLogging.redact(sdkError)}, sdkErrorCode=$sdkErrorCode)"
    }
    data class Restoration(val completed: Boolean, val stockRestored: Boolean)

    fun uninstall(context: Context, permanent: Boolean): Outcome {
        val app = context.applicationContext
        val boot = UltraEnvironmentAutoSetup.currentBootId()
        val activation = ModeRuntime.activation
        val sourceCurrent = UltraSdkCredentials.captureOwnerMarker(app)
        val owner = UltraEnvironmentProbe.currentSnapshot(app).managerOwnerIdentity
        val legacy = if (owner == null) UltraLegacyCredential.read(app) else null
        var sourceBound = sourceCurrent()
        var removalCurrent: (() -> Boolean)? = null
        // Bind the source before this transaction's removal marker retires ordinary clients.
        val selected = if (legacy == null) UltraRuntimeClient() else UltraRuntimeClient().withLegacyCredential(legacy)
        val client = selected.scoped {
            sourceBound && !Thread.currentThread().isInterrupted && boot != null &&
                UltraEnvironmentAutoSetup.currentBootId() == boot && ModeRuntime.activation === activation &&
                (removalCurrent ?: sourceCurrent)()
        }
        val taskId = UUID.randomUUID().toString()
        return uninstall(permanent,
            restoreStock = {
                val receipt = InstallerServiceClient.restoreStockImages(app, taskId)
                Restoration(receipt.getString("operation") == "RESTORE_STOCK" && receipt.getString("error").isNullOrEmpty() &&
                    receipt.getString("state") in setOf("REBOOT_REQUIRED", "REBOOT_REQUESTED", "BOOT_IMAGE_VERIFIED"),
                    ImageRestorationKind.fromWire(receipt.getString("restorationKind")) == ImageRestorationKind.EXACT_STOCK)
            },
            persistRemoval = {
                sourceBound = sourceBound && sourceCurrent()
                UltraRootKeyStore.recordRemoval(app, permanent)
                removalCurrent = UltraRootKeyStore.captureOwnerMarker(app)
                UltraInstalledIdentityProbe.clear(app)
                UltraEnvironmentProbe.invalidateAvailability()
            },
            removeSdk = client::uninstallEnvironment,
            rebootStock = { InstallerServiceClient.rebootAfterInstallation(app, taskId) },
        )
    }

    /** Persist intent before SDK teardown: a successful SDK call schedules reboot in 3 seconds. */
    internal fun uninstall(permanent: Boolean, restoreStock: () -> Restoration,
                           persistRemoval: () -> Unit, removeSdk: () -> NativeReceipt,
                           rebootStock: () -> Boolean = { true }): Outcome {
        val restoration = if (permanent) runCatching { restoreStock() }.getOrDefault(Restoration(false, false))
            else Restoration(false, false)
        if (permanent && !restoration.completed) return Outcome(false, false, "ULTRA_STOCK_RESTORE_FAILED")
        persistRemoval()
        UltraEnvironmentUpgrade.invalidateObservation()
        if (restoration.completed) {
            val rebooted = rebootStock()
            return Outcome(rebooted, restoration.stockRestored, if (rebooted) "OK" else "ULTRA_STOCK_REBOOT_FAILED")
        }
        val receipt = removeSdk()
        return Outcome(receipt.succeeded, false,
            receiptErrorName(receipt.succeeded, receipt.sdkError), receipt)
    }
}
