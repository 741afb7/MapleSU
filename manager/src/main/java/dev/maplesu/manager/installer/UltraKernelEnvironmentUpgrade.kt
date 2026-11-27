package dev.maplesu.manager.installer

import android.content.Context
import android.os.Build
import android.os.Bundle
import dev.maplesu.manager.ultrakernel.UltraKernelContextAssets
import dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery
import dev.maplesu.manager.ultrakernel.UltraKernelPlan
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import dev.maplesu.manager.ultrakernel.UltraKernelUpdateRequirement
import dev.maplesu.manager.ultrakernel.UltraKernelVivoFilter
import java.io.File

internal data class InstalledUltraCarrier(
    val bootId: String,
    val partition: String,
    val slotSuffix: String,
    val partitionSha256: String,
    val kernelRelease: String,
    val updateRequired: Boolean,
)

/** Invoked by explicit checks/repair/upgrade only; startup never opens partitions here. */
internal object UltraKernelEnvironmentUpgrade {
    /** Installed bytes are an observation; they do not grant the current SDK owner or SU access. */
    fun observeInstalled(context: Context): InstalledUltraCarrier? {
        val bootId = dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.currentBootId()
            ?: error("ULTRA_KERNEL_BOOT_ID_UNAVAILABLE")
        val observed = InstallerServiceClient.observeBootChain(context)
        val partition = InstallerServiceClient.installedCarrierPartition(context, observed) ?: return null
        val captured = InstallerServiceClient.analyzeCurrentPartition(context, partition)
        fun same(actual: InstallerSnapshot) {
            check(actual.slotSuffix == observed.slotSuffix && actual.kernelRelease == observed.kernelRelease &&
                actual.firmware == observed.firmware && actual.partitions == observed.partitions) {
                "INSTALLER_DEVICE_CHANGED"
            }
        }
        same(captured)
        val image = File(context.filesDir, "images/imported.img")
        val carrier = observed.partitions.getValue(partition)
        check(image.length() == carrier.size && UltraKernelSpec.sha256(image) == carrier.sha256) {
            "INSTALLER_IMAGE_CHANGED"
        }
        val updateRequired = required(context, partition)
        check(image.length() == carrier.size && UltraKernelSpec.sha256(image) == carrier.sha256) {
            "INSTALLER_IMAGE_CHANGED"
        }
        same(InstallerServiceClient.observeBootChain(context))
        check(dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.currentBootId() == bootId) {
            "INSTALLER_DEVICE_CHANGED"
        }
        return InstalledUltraCarrier(bootId, partition, observed.slotSuffix, carrier.sha256,
            observed.kernelRelease, updateRequired)
    }

    fun required(context: Context): Boolean {
        val (partition, _) = InstallerServiceClient.analyzeCurrentCarrier(context)
        return required(context, partition)
    }

    private fun required(context: Context, partition: String): Boolean {
        if (partition == "init_boot") {
            val artifact = dev.maplesu.manager.ultrakernel.UltraKernelFlow.initBootArtifact(context)
                ?: error("ULTRA_KERNEL_INIT_BOOT_ARTIFACT_REQUIRED:select-KMI-and-packaged-component")
            val image = File(context.filesDir, "images/imported.img")
            return dev.maplesu.manager.ultrakernel.UltraKernelFlow.initBootUpdateRequired(
                image, artifact)
        }
        val image = File(context.filesDir, "images/imported.img")
        if (dev.maplesu.image.UltraInitBootProducer.recognizes(image)) {
            val artifact = dev.maplesu.manager.ultrakernel.UltraKernelFlow.initBootArtifact(context, image)
                ?: error("ULTRA_KERNEL_MODULE_CARRIER_ARTIFACT_MISSING")
            return dev.maplesu.manager.ultrakernel.UltraKernelFlow.initBootUpdateRequired(
                image, artifact)
        }
        val recovery = UltraKernelOriginalRecovery(UltraKernelContextAssets(context))
        val receipts = dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir)
        var spec = recovery.installedSpec(image, receipts)
        if (spec == null && installedRecord(context, UltraKernelSpec.sha256(image))
                ?.getString("signingMode") in setOf("CHAINED", "AOSP") &&
            recovery.retainPreviouslySignedCandidate(image, receipts)) {
            spec = recovery.installedSpec(image, receipts)
        }
        if (spec == null && recovery.archivedKernelUpdateRequired(image)) return true
        if (UltraKernelUpdateRequirement.required(spec)) return true
        return VivoCompatibility.deviceSupported() && !UltraKernelVivoFilter.present(UltraKernelPlan.readKernel(image))
    }

    fun signingOptions(context: Context): LenovoSigningOptions {
        val (partition, snapshot) = InstallerServiceClient.analyzeCurrentCarrier(context)
        val currentSha = snapshot.partitions.getValue(partition).sha256
        return kernelUpdateSigningOptions(Build.FINGERPRINT,
            installedRecord(context, currentSha, partition, snapshot.slotSuffix)?.getString("signingMode"))
    }

    private fun installedRecord(context: Context, currentSha: String, partition: String = "boot",
                                slot: String? = null): Bundle? {
        val status = InstallerServiceClient.installationStatus(context)
        val installed = status.getString("operation") == "INSTALL" &&
            status.getString("partition") == partition && status.getString("targetMode") == "LITE" &&
            status.getString("state") in setOf("REBOOT_REQUIRED", "REBOOT_REQUESTED", "BOOT_IMAGE_VERIFIED", "VERIFIED", "BOOT_VERIFIED") &&
            (slot == null || status.getString("slotSuffix") == slot) &&
            !status.getBoolean("operationActive") && status.getString("partitionSha256") == currentSha
        return status.takeIf { installed }
    }
}

/** A matching installed receipt keeps its choice; otherwise use the install page's default. */
internal fun kernelUpdateSigningOptions(fingerprint: String?, installedMode: String?): LenovoSigningOptions =
    when (installedMode) {
        null -> LenovoSigningOptions(enabled = lenovoFingerprint(fingerprint))
        "" -> LenovoSigningOptions()
        "CHAINED" -> LenovoSigningOptions(enabled = true)
        "AOSP" -> LenovoSigningOptions(enabled = true, aosp = true)
        "NON_CHAINED" -> LenovoSigningOptions(enabled = true)
        else -> error("LENOVO_AVB_INSTALLED_MODE_INVALID")
    }
