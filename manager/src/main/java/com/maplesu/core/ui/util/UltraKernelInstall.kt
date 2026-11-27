package com.maplesu.core.ui.util

import android.util.Log
import android.os.StatFs
import com.maplesu.core.R
import com.maplesu.core.ksuApp
import com.maplesu.core.ui.screen.install.UltraKernelGenerationPort
import dev.maplesu.manager.ImageRepository
import dev.maplesu.manager.installer.InstallerServiceClient
import dev.maplesu.manager.installer.InstallerSnapshot
import dev.maplesu.manager.installer.BaselineProvisioner
import dev.maplesu.manager.installer.BaselineProvisionOutcome
import dev.maplesu.manager.installer.productionBaselineProvisioner
import dev.maplesu.manager.ultrakernel.UltraKernelFlow
import dev.maplesu.manager.ultrakernel.UltraKernelGenerateCodes
import dev.maplesu.manager.ultrakernel.UltraKernelGenerateException
import dev.maplesu.manager.ultrakernel.UltraKernelPlan
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import dev.maplesu.manager.ultrakernel.KernelSeries
import dev.maplesu.manager.workmode.superuser.DirectRootScriptRunner
import dev.maplesu.manager.workmode.switch.DeviceBootFacts
import java.io.File

/**
 * Ultra candidates are generated from the target kernel through UltraKernelFlow.prepareTarget.
 * Selected files only produce an export; direct installation submits the verified candidate to
 * the existing partition transaction. Explicit historical asset ids share one compatibility
 * boundary in UltraKernelFlow.
 */
private const val TAG_KERNEL = "MapleSUKernel"

/** One selectable variant (S4) — plain data so the confirm screen never touches the catalogue. */
internal data class UltraKernelVariantInfo(
    val id: String,
    val label: String,
    val summary: String,
    val patches: Int,
    val experimental: Boolean,
    val isDefault: Boolean,
)

/**
 * The facts the confirm screen needs (S2 release + S3 support level + S4 variants), produced by
 * a **read-only** probe: nothing here builds a candidate, imports an image or opens a partition.
 */
internal data class UltraKernelFacts(
    val release: String,
    val variants: List<UltraKernelVariantInfo> = emptyList(),
    val rejected: List<String> = emptyList(),
    val defaultId: String? = null,
    /** Stable `ULTRA_KERNEL_*` code when the release is unusable; empty when it is fine. */
    val stableCode: String = "",
    val detail: String = "",
    val knownReleases: List<String> = emptyList(),
    /**
     * Kernel **series** of [release] (`6.6`), or `""` when it cannot be parsed.
     *
     * It exists so step ③ can show a series row ("6.6.x") instead of one exact build, and so a
     * series match can be explained without re-parsing the release string in the UI. It is
     * **not** a support level: nothing enables, disables or diverts a flow based on it.
     */
    val series: String = "",
    /**
     * Release of the **selected asset** when it is not [release] itself (`6.6.89-…` for a
     * `6.6.100-…` boot). `""` = the catalogue answered with an asset of the same release, or
     * nothing was selected.
     *
     * It is reported, never enforced: which asset is attempted is the catalogue's series rule
     * (`KernelSeries` + `UltraKernelAssetOrder`), and whether it fits this kernel is the builder's
     * site/digest decision. A refusal there is a plain "patch failed" - nothing is written.
     */
    val assetRelease: String = "",
) {
    val recognized: Boolean get() = variants.isNotEmpty()

    /** `6.6` -> `6.6.x`; the series row of step ③. Empty when the release is unparseable. */
    val seriesLabel: String get() = if (series.isEmpty()) "" else "$series.x"
}

/** `ULTRA_KERNEL_RELEASE_UNSUPPORTED` and friends, without the payload after the first `:`. */
internal fun ultraKernelStableCode(error: Throwable): String =
    error.message.orEmpty().substringBefore(':').trim()
        .takeIf { it.startsWith("ULTRA_KERNEL_") } ?: "ULTRA_KERNEL_UNKNOWN"

/** Kernel release of a boot image. Read-only; never writes `files/images/imported.img`. */
internal fun ultraKernelReleaseOf(image: File, pairedBoot: File? = null): String {
    if (dev.maplesu.image.BootImageInspector.inspect(image).kernelSize == 0L) {
        val paired = requireNotNull(pairedBoot) { "ULTRA_INIT_PAIRED_BOOT_REQUIRED" }
        check(dev.maplesu.image.BootImageInspector.inspect(paired).kernelSize > 0L) { "ULTRA_INIT_PAIRED_BOOT_REQUIRED" }
        return UltraKernelFlow(ksuApp).releaseOf(paired)
    }
    return UltraKernelFlow(ksuApp).releaseOf(image)
}

/**
 * Kernel **series** of a release string: `6.6.89-android15-8-g7e1f3c083cc6-…` -> `6.6`.
 *
 * Delegates to [KernelSeries] (the patch engine's own parser, in `ultrakernel`) instead of keeping a
 * second opinion: "same series" has to mean exactly one thing, and it is the engine that enforces
 * it on every byte gate. Unparseable input -> `""` (never guessed).
 *
 * The only job this file has with the series is **showing** it (step ③ row, patch log). Which
 * patch set is attempted is decided by `UltraKernelFlow.selectForRelease` / the builder gates - not
 * here.
 */
internal fun ultraKernelSeries(release: String): String = KernelSeries.of(release).orEmpty()

/** One line for a `ULTRA_KERNEL_GENERATE_*` refusal; carries the stable code, never the spec text. */
internal fun ultraGenerateFailureMessage(error: UltraKernelGenerateException): String =
    if (error.code == UltraKernelGenerateCodes.SITE_COVERAGE) {
        ksuApp.getString(
            R.string.install_boot_support_site_coverage,
            error.code + error.detail.takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty(),
        )
    } else {
        val detail = dev.maplesu.manager.runtime.ultra.UltraSdkLogging.redact(error.detail)
            .replace('\n', ' ').replace('\r', ' ').take(200)
        ksuApp.getString(R.string.install_boot_generate_failed,
            error.code + detail.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty())
    }

/** Read-only feasibility uses the same keyless renderer as explicit installation. */
internal class UltraKernelSourcedGeneration(
    private val render: (File) -> UltraKernelPlan,
    private val legacyModuleRequired: (File) -> Boolean = { false },
) : UltraKernelGenerationPort {

    override fun plan(boot: File?, release: String): UltraKernelGenerationPort.GenerationPlan {
        if (boot == null || !boot.isFile) {
            return UltraKernelGenerationPort.GenerationPlan.Available(
                specSha256 = "",
                patches = 0,
                deferred = true,
            )
        }
        return try {
            if (dev.maplesu.image.BootImageInspector.inspect(boot).kernelSize == 0L || legacyModuleRequired(boot)) {
                return UltraKernelGenerationPort.GenerationPlan.Available("", 0, deferred = true)
            }
            val plan = render(boot)
            UltraKernelGenerationPort.GenerationPlan.Available(
                specSha256 = plan.derived?.patchedKernelSha256.orEmpty(),
                patches = plan.records.size,
            )
        } catch (error: UltraKernelGenerateException) {
            UltraKernelGenerationPort.GenerationPlan.Unavailable(error.code, error.detail)
        } catch (error: Exception) {
            UltraKernelGenerationPort.GenerationPlan.Unavailable(
                ultraKernelStableCode(error), error.message.orEmpty())
        }
    }
}

/**
 * The wired-in production port (`InstallBootFlow.generation`).
 *
 * There is deliberately **no** `NotWired` default any more: an unregistered release is either
 * generatable (experimental, shown before the user confirms) or refused with a stable code.
 */
internal object UltraKernelGeneration {
    val port: UltraKernelGenerationPort = UltraKernelSourcedGeneration(
        render = { boot -> UltraKernelFlow(ksuApp).generatePlan(boot) },
        legacyModuleRequired = { boot -> UltraKernelFlow(ksuApp).usesLegacyModule(boot) },
    )
}

/** Target identity only; generation availability comes from the target renderer. */
internal fun ultraKernelFacts(release: String): UltraKernelFacts {
    val trimmed = release.trim()
    return UltraKernelFacts(
        release = trimmed,
        stableCode = if (trimmed.isEmpty()) "ULTRA_KERNEL_BASE_RELEASE_UNREADABLE" else "",
        series = ultraKernelSeries(trimmed),
    )
}

/** Result of "build the patched image from a chosen stock boot" (no partition is opened). */
internal sealed class UltraImageBuild {
    class Candidate(
        val image: File,
        val bootSha256: String,
        val kernelSha256: String,
        /**
         * Kernel release **of the produced image**, read back out of the patched kernel - not the
         * release of the catalogue asset that supplied the records.
         *
         * The two are the same as long as only exact-release assets are used; with a series match
         * (`6.6.100` patched by the `6.6.89` patch set) they differ, and reporting the asset's
         * release here made the caller believe the candidate was built for another kernel ("only
         * saved, not written"). Reporting what the image actually contains is the truthful value:
         * patching in place keeps the base's release string, so this is still the device's own
         * kernel release and the part this flow is about (does this image belong on this device?)
         * is still answered - by the transaction, which compares it with the live snapshot.
         */
        val release: String,
        val variantLabel: String,
        val partition: String = "boot",
    ) : UltraImageBuild()

    class AlreadyPatched(val candidate: Candidate) : UltraImageBuild() {
        val variantLabel: String get() = candidate.variantLabel
    }

    /** No candidate was produced: explicit failure, never a Lite downgrade. */
    class Refused(val code: Int, val message: String) : UltraImageBuild()
}

internal fun ultraDirectInstall(
    observed: InstallerSnapshot,
    taskId: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
    variantId: String? = null,
    onStage: (InstallStage) -> Unit = {},
    baselineProvisioner: BaselineProvisioner = productionBaselineProvisioner(
        context = ksuApp,
        runner = DirectRootScriptRunner(ksuApp),
        deviceFacts = { DeviceBootFacts(observed.slotSuffix, observed.firmware, observed.kernelRelease) },
        freeBytes = { StatFs("/data").availableBytes },
        requiredPartitions = observed.partitions.keys.intersect(setOf("boot", "init_boot")),
    ),
): FlashResult {
    val flow = UltraKernelFlow(ksuApp)
    return try {
        // Durable originals must be published and read back before preparing any flash. The
        // per-transaction backup below remains the current live boot for transaction rollback.
        when (val baseline = baselineProvisioner.ensureBaseline()) {
            is BaselineProvisionOutcome.Ready -> onStdout("原始启动镜像已保存")
            is BaselineProvisionOutcome.Failed -> {
                Log.w(TAG_KERNEL, "Baseline blocked install: ${baseline.code}: ${baseline.detail}")
                val message = "原始启动镜像尚未就绪，请检查当前安装环境后重试。"
                onStderr(message)
                return FlashResult(1, message, false, FlashOutcome.FAILED)
            }
        }
        val backup = flow.backupCurrentBoot()
        val backupSha = UltraKernelSpec.sha256(backup)
        onStdout("当前 boot 备份 sha256 $backupSha")
        onStage(InstallStage.PATCHING)
        val preparation = try {
            flow.prepareTarget(backup, variantId, onStdout)
        } catch (error: UltraKernelGenerateException) {
            val message = ultraGenerateFailureMessage(error)
            onStderr(message)
            return FlashResult(78, message, false, FlashOutcome.FAILED)
        }
        if (preparation.alreadyInstalled) {
            onStdout("镜像已修补，继续安装")
        }
        val variant = preparation.variant
        val candidate = flow.candidateForInstall(backup, preparation)
        onStdout("系统内核：${variant?.label ?: "Ultra ${candidate.partition}"}")
        onStdout("候选镜像 sha256 ${candidate.bootSha256}")
        onStdout("内核 sha256 ${candidate.kernelSha256}")
        onStage(InstallStage.WRITING)
        val installed = InstallerServiceClient.installBoot(ksuApp, candidate.image, observed, backup, taskId)
        val state = installed.getString("state")
        if (state == "REBOOT_REQUIRED" && installed.getBoolean("rebootRequired")) {
            onStage(InstallStage.AWAITING_REBOOT)
            onStdout(ksuApp.getString(R.string.install_image_installed))
            FlashResult(0, "", true, FlashOutcome.WRITTEN)
        } else {
            Log.w(TAG_KERNEL, "System kernel install stopped at state=$state")
            val restored = state == "RESTORED"
            val message = ksuApp.getString(
                if (restored) R.string.install_image_restored else R.string.install_image_recovery_required)
            onStderr(message)
            FlashResult(1, message, restored,
                if (restored) FlashOutcome.RESTORED else FlashOutcome.RECOVERY_REQUIRED)
        }
    } catch (error: Exception) {
        Log.w(TAG_KERNEL, "System kernel direct install failed", error)
        val message = "内核刷写失败：${error.message.orEmpty()}"
        onStderr(message)
        FlashResult(1, message, false, FlashOutcome.FAILED)
    }
}

/** Build from the imported target image; the caller publishes or commits the candidate. */
internal fun ultraImageBuildCandidate(
    repository: ImageRepository,
    sourceSha256: String,
    onStdout: (String) -> Unit,
    variantId: String? = null,
    imageOnly: Boolean = false,
    initBootArtifact: dev.maplesu.image.UltraInitBootProducer.Artifact? = null,
    recoveredDeployment: dev.maplesu.manager.ultrakernel.UltraKernelDeploymentNamespace? = null,
): UltraImageBuild = try {
    repository.withCurrentImage(sourceSha256) { source ->
        val flow = UltraKernelFlow(ksuApp)
        val preparation = try {
            val artifact = if (dev.maplesu.image.BootImageInspector.inspect(source).kernelSize == 0L)
                requireNotNull(initBootArtifact) { "ULTRA_INIT_PAIRED_BOOT_REQUIRED" } else initBootArtifact
            flow.prepareTarget(source, variantId, artifact, recoveredDeployment, onStdout)
        } catch (error: UltraKernelGenerateException) {
            return@withCurrentImage UltraImageBuild.Refused(78, ultraGenerateFailureMessage(error))
        } catch (error: Exception) {
            return@withCurrentImage UltraImageBuild.Refused(
                78, "系统内核镜像生成失败：${error.message.orEmpty()}")
        }
        val variant = preparation.variant
        val candidate = flow.candidateForInstall(source, preparation)
        val label = variant?.label ?: "Ultra ${candidate.partition}"
        onStdout(if (variant == null) "系统内核：$label" else "系统内核：${variant.label}（${variant.patches} 条记录）")
        onStdout("内核 sha256 ${candidate.kernelSha256}")
        val built = UltraImageBuild.Candidate(
            image = candidate.image,
            bootSha256 = candidate.bootSha256,
            kernelSha256 = candidate.kernelSha256,
            // 镜像自己的 release（修补是原地改写，release 串仍然是基座那条），不是资产目录的
            // release：后者只是"补丁集出自哪个目录"，写成 release 会让调用方以为镜像不是本机的。
            release = candidate.kernelRelease,
            variantLabel = label,
            partition = candidate.partition,
        )
        if (preparation.alreadyInstalled) UltraImageBuild.AlreadyPatched(built) else built
    }
} catch (error: Exception) {
    Log.w(TAG_KERNEL, "System kernel image build failed", error)
    UltraImageBuild.Refused(1, "系统内核镜像生成失败：${error.message.orEmpty()}")
}

