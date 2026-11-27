package dev.maplesu.manager.ultrakernel

import android.content.Context
import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.KernelCompression
import dev.maplesu.image.UltraInitBootProducer
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Target-specific, keyless production. Historical assets are read only for recovery. */
internal class UltraKernelFlow internal constructor(
    private val workspace: File,
    private val assets: UltraKernelAssetSource,
    private val vivoVrFilter: Boolean = false,
    private val managerClaimAssets: UltraKernelAssetSource? = null,
    private val moduleArtifact: (File) -> UltraInitBootProducer.Artifact? = { null },
) {
    internal constructor(context: Context) : this(
        dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir),
        UltraKernelContextAssets(context),
        listOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND).any {
            it.equals("vivo", true) || it.equals("iqoo", true)
        },
        UltraKernelContextAssets(context),
        { source ->
            if (BootImageInspector.inspect(source).kernelSize == 0L) initBootArtifact(context)
            else artifactForKernelBoot(context.filesDir, { path -> context.assets.open(path) }, source,
                UltraKernelContextAssets(context))
        },
    )

    private val builder get() = UltraKernelBuilder(workspace)
    private val freshSpecGenerator by lazy {
        UltraKernelSpecGenerator(vivoVrFilter = vivoVrFilter, managerClaimAssets = managerClaimAssets)
    }
    private fun specGenerator(deployment: UltraKernelDeploymentNamespace?): UltraKernelSpecGenerator {
        if (deployment != null) synchronized(this) { selectedDeploymentNamespace = deployment }
        val retained = deployment ?: synchronized(this) { selectedDeploymentNamespace }
        return if (retained == null) freshSpecGenerator else UltraKernelSpecGenerator(
            vivoVrFilter = vivoVrFilter, managerClaimAssets = managerClaimAssets, deploymentNamespace = retained)
    }
    private var selectedDeploymentNamespace: UltraKernelDeploymentNamespace? = null

    @Synchronized
    private fun withInitBootDeployment(base: File, artifact: UltraInitBootProducer.Artifact,
        recoveredDeployment: UltraKernelDeploymentNamespace?): UltraInitBootProducer.Artifact {
        val retained = UltraInitBootProducer.readDeploymentNamespace(base)?.let(UltraKernelDeploymentNamespace::decode)
        check(retained == null || recoveredDeployment == null ||
            retained.record().contentEquals(recoveredDeployment.record())) { "ULTRA_INIT_BOOT_DEPLOYMENT_NAMESPACE_CONFLICT" }
        (retained ?: recoveredDeployment)?.let { selectedDeploymentNamespace = it }
        if (!artifact.supportsDeploymentNamespace) {
            check(retained != null || selectedDeploymentNamespace == null) { "ULTRA_KERNEL_DEPLOYMENT_SUPPORT_MISSING" }
            return artifact
        }
        val deployment = retained ?: selectedDeploymentNamespace ?: UltraKernelDeploymentNamespace.create().also {
            selectedDeploymentNamespace = it
        }
        return artifact.withDeploymentNamespace(deployment.record())
    }

    companion object {
        internal fun artifactForKernelBoot(filesDir: File, open: (String) -> InputStream, source: File,
            claimAssets: UltraKernelAssetSource? = null): UltraInitBootProducer.Artifact? {
            val kmi = dev.maplesu.image.KernelIdentity.kmiOf(UltraKernelPlan.readKernel(source))
            return if (kmi.matches(Regex("android[0-9]+-[0-9]+\\.[0-9]+")))
                UltraInitBootCatalog.resolveKmi(filesDir, open, kmi)
            else UltraInitBootCatalog.resolve(filesDir, open, source, claimAssets)
        }

        /** Current-device default; an offline image for another device supplies its selected KMI. */
        fun initBootArtifact(context: Context): UltraInitBootProducer.Artifact? {
            val kmi = dev.maplesu.image.KernelIdentity.kmiOfRelease(android.system.Os.uname().release)
            if (!kmi.matches(Regex("android[0-9]+-[0-9]+\\.[0-9]+"))) return null
            return initBootArtifact(context, kmi)
        }

        fun initBootArtifact(context: Context, pairedBoot: File): UltraInitBootProducer.Artifact? =
            artifactForKernelBoot(context.filesDir, { path -> context.assets.open(path) }, pairedBoot,
                UltraKernelContextAssets(context))

        fun availableInitBootKmis(context: Context): List<String> =
            UltraInitBootCatalog.availableKmis(context.filesDir) { path -> context.assets.open(path) }

        fun initBootArtifact(context: Context, kmi: String): UltraInitBootProducer.Artifact? =
            UltraInitBootCatalog.resolveKmi(context.filesDir, { path -> context.assets.open(path) }, kmi)

        /** Compare actual payload bytes and the verified public deployment record. */
        fun initBootUpdateRequired(image: File, artifact: UltraInitBootProducer.Artifact): Boolean {
            check(UltraInitBootProducer.recognizes(image)) { "ULTRA_KERNEL_INIT_BOOT_CANDIDATE_UNRECOGNIZED" }
            val ramdisk = dev.maplesu.image.RamdiskInspector.inspect(image)
            check(!ramdisk.malformed) { "ULTRA_KERNEL_INIT_BOOT_RAMDISK_INVALID" }
            return artifact.credentialMode != "keyless" ||
                ramdisk.entries.singleOrNull { it.path == "init" }?.sha256 != artifact.handoffSha256 ||
                ramdisk.entries.singleOrNull { it.path == ".maplesu_ultra/bridge.ko" }?.sha256 != artifact.bridgeSha256 ||
                ramdisk.entries.any { it.path == ".maplesu_ultra/credential" } ||
                UltraInitBootProducer.readDeploymentNamespace(image) == null
        }
    }

    /**
     * Ordered, pre-validated assets for [release]; `null` means "this kernel build has no pinned
     * asset". Standard preparation still generates for the actual kernel. A malformed declared asset
     * throws (fail closed) instead of silently downgrading to another release.
     */
    fun selectForRelease(release: String): UltraKernelSelection? = select(release, loadCatalogs())

    /** Source target ids from current and older callers share the target generator. */
    fun prepareTarget(
        base: File,
        variantId: String? = null,
        onNotice: (String) -> Unit = {},
    ): Preparation = prepareTarget(base, variantId, null, onNotice)

    fun prepareTarget(
        base: File,
        variantId: String?,
        initBootArtifact: UltraInitBootProducer.Artifact?,
        onNotice: (String) -> Unit = {},
    ): Preparation = prepareTarget(base, variantId, initBootArtifact, null, onNotice)

    internal fun prepareTarget(
        base: File,
        variantId: String?,
        initBootArtifact: UltraInitBootProducer.Artifact?,
        recoveredDeployment: UltraKernelDeploymentNamespace?,
        onNotice: (String) -> Unit = {},
    ): Preparation {
        requireBase(base)
        val carrier = BootImageInspector.inspect(base)
        val legacyModule = initBootArtifact?.isKmiComponent != true && carrier.kernelSize > 0L &&
            UltraKernelLegacyModuleRoute.required(UltraKernelPlan.readKernel(base))
        val selectedArtifact = initBootArtifact ?: if (carrier.kernelSize == 0L || legacyModule) moduleArtifact(base) else null
        if (carrier.kernelSize == 0L || legacyModule || selectedArtifact?.isKmiComponent == true) {
            check(variantId == null || variantId == "generated" || carrier.kernelSize > 0L && variantId == freshSpecGenerator.recipeId) {
                "ULTRA_KERNEL_INIT_BOOT_VARIANT_UNSUPPORTED"
            }
            val artifactSource = requireNotNull(selectedArtifact ?: if (carrier.ramdiskSize == 0L) moduleArtifact(base) else null) {
                "ULTRA_KERNEL_INIT_BOOT_ARTIFACT_REQUIRED:select-KMI-and-packaged-component"
            }
            val recoveryWork = if (artifactSource.isKmiComponent && carrier.kernelSize > 0L)
                File(workspace, "component-recovery-${java.util.UUID.randomUUID()}") else null
            try {
                val recovered = recoveryWork?.let { recoverModuleBase(base, it) }
                val moduleBase = recovered?.image ?: base
                val retainedDeployment = recovered?.deploymentNamespace
                check(retainedDeployment == null || recoveredDeployment == null ||
                    retainedDeployment.record().contentEquals(recoveredDeployment.record())) {
                    "ULTRA_INIT_BOOT_DEPLOYMENT_NAMESPACE_CONFLICT"
                }
                if (recovered != null) onNotice("已恢复当前 Ultra 镜像，正在准备模块桥接")
                val artifact = if (artifactSource.isKmiComponent && carrier.kernelSize == 0L) artifactSource
                else UltraInitBootTargetResolver.resolve(artifactSource,
                    if (carrier.kernelSize > 0L) moduleBase else requireNotNull(artifactSource.targetBoot) { "ULTRA_INIT_PAIRED_BOOT_REQUIRED" },
                    managerClaimAssets ?: assets)
                val deploymentArtifact = withInitBootDeployment(base, artifact, retainedDeployment ?: recoveredDeployment)
                check(deploymentArtifact.credentialMode == "keyless") { "ULTRA_KERNEL_KEYLESS_COMPONENT_REQUIRED" }
                val candidate = builder.generateInitBoot(moduleBase, deploymentArtifact,
                    installationInput = base, installationInputSha256 = carrier.sha256,
                    normalizationSource = recovered?.source)
                return Preparation(null, candidate, candidate.bootSha256 == candidate.baseBootSha256,
                    verification = "experimental", experimental = true, planOrigin = when {
                        artifactSource.isKmiComponent && carrier.kernelSize > 0L -> "KMI_BOOT_MODULE"
                        artifactSource.isKmiComponent -> "INIT_BOOT_KMI_COMPONENT"
                        legacyModule -> "LEGACY_CFI_MODULE"
                        else -> "INIT_BOOT_BRIDGE"
                    })
            } finally { recoveryWork?.deleteRecursively() }
        }
        if (recoveredDeployment != null) synchronized(this) { selectedDeploymentNamespace = recoveredDeployment }
        if (variantId == null || variantId == "generated" || variantId == UltraKernelSpecGenerator().recipeId) {
            val release = releaseOf(base)
            check(release.isNotEmpty()) { "ULTRA_KERNEL_BASE_RELEASE_UNREADABLE" }
            prepareRetainedGenerated(base, onNotice)?.let { return it }
            return prepareGenerated(base, release, onNotice)
        }
        val selection = selectForRelease(releaseOf(base))
        return prepare(base, requireNotNull(withChosenVariant(selection, variantId, onNotice)),
            onNotice)
    }

    /** Only the target's actual legacy CFI contract keeps the default kernel route on modules. */
    fun usesLegacyModule(base: File): Boolean {
        requireBase(base)
        return BootImageInspector.inspect(base).kernelSize > 0L &&
            UltraKernelLegacyModuleRoute.required(UltraKernelPlan.readKernel(base))
    }

    /** Generate against the verified input; normal retries retain the existing namespace. */
    fun prepare(base: File, onNotice: (String) -> Unit = {}): Preparation =
        prepareTarget(base, "generated", null, onNotice)

    /** A chosen historical payload is recoverable but is never silently reinstalled. */
    fun prepare(base: File, selection: UltraKernelSelection, onNotice: (String) -> Unit): Preparation {
        check(selection.usable.any { it.id == freshSpecGenerator.recipeId }) {
            "ULTRA_KERNEL_LEGACY_ASSET_READ_ONLY"
        }
        return prepare(base, onNotice)
    }

    private fun prepareRetainedGenerated(base: File, onNotice: (String) -> Unit): Preparation? {
        val recovery = UltraKernelOriginalRecovery(assets)
        val recovered = recovery.recover(base, workspace, emptyList(),
            File(workspace, "generated-recovery"), generatedOnly = true)
            ?: recovery.recoverPinned(base, File(workspace, "pinned-recovery")) ?: return null
        onNotice("已识别当前 Ultra 镜像，正在准备更新")
        return prepareGenerated(recovered.image, recovered.kernelRelease,
            onNotice, installed = base, deploymentNamespace = recovered.deploymentNamespace)
    }

    private fun recoverModuleBase(base: File, output: File): UltraKernelOriginalRecovery.Recovered? {
        val recovery = UltraKernelOriginalRecovery(assets)
        fun restore(input: File, directory: File): UltraKernelOriginalRecovery.Recovered? {
            if (!UltraInitBootProducer.recognizes(input))
                recovery.recoverImageBoundExactInput(input, directory)?.let { return it }
            return recovery.recover(input, workspace, emptyList(), directory)
                ?: recovery.recover(input, workspace, emptyList(), directory, generatedOnly = true)
        }
        val restored = restore(base, File(output, "carrier")) ?: return null
        if (!UltraInitBootProducer.recognizes(base)) return restored
        // A module's first recorded input can itself be an older raw patch.
        val raw = restore(restored.image, File(output, "raw")) ?: return restored
        check(restored.deploymentNamespace == null || raw.deploymentNamespace == null ||
            restored.deploymentNamespace.record().contentEquals(raw.deploymentNamespace.record())) {
            "ULTRA_INIT_BOOT_DEPLOYMENT_NAMESPACE_CONFLICT"
        }
        return UltraKernelOriginalRecovery.Recovered(raw.image, raw.source, raw.kernelRelease,
            raw.deploymentNamespace ?: restored.deploymentNamespace)
    }

    /**
     * Generate a plan for [base] without building anything.
     *
     * Exists so a caller can show the origin, the site evidence and the derived digests *before*
     * deciding to write a 100 MiB candidate. The result is always
     * [UltraKernelPlan.SupportLevel.EXPERIMENTAL]; nothing here writes a partition.
     */
    fun generatePlan(base: File): UltraKernelPlan {
        requireBase(base)
        val recovery = UltraKernelOriginalRecovery(assets)
        val recovered = recovery.generatedKernelWithDeployment(base, workspace)
        val raw = recovered?.kernel ?: UltraKernelPlan.readKernel(base)
        return specGenerator(recovered?.deploymentNamespace).generate(raw)
    }

    /**
     * Generate for this target and send the result through the existing builder.
     */
    private fun prepareGenerated(
        base: File,
        release: String,
        onNotice: (String) -> Unit,
        installed: File? = null,
        deploymentNamespace: UltraKernelDeploymentNamespace? = null,
    ): Preparation {
        onNotice("正在根据目标内核生成补丁：$release")
        val kernel = UltraKernelPlan.readKernel(base)
        val plan = specGenerator(deploymentNamespace).generate(kernel)
        val evidence = plan.evidence
        evidence?.let { evidence ->
            onNotice(
                "已生成 ${plan.records.size} 条记录，站点与参考构建一致 ${evidence.summary}" +
                    if (evidence.referenceMatch) "" else "（该 release 的代码体未经验证）",
            )
        }
        plan.derived?.let { derived ->
            onNotice("内核摘要现算：${derived.patchedKernelSha256.take(16)}…，改动 ${derived.changedBytes} B")
        }
        val report = BootImageInspector.inspect(base)
        val specText = plan.specText()
        val asset = UltraKernelGeneratedAsset.of(
            plan = plan,
            imageSizeBytes = report.fileSize,
            ramdiskSize = report.ramdiskSize,
            specText = specText,
        )
        val spec = UltraKernelSpec.parse(specText)
        val inspection = builder.inspectBase(installed ?: base, asset, spec)
        if (inspection.kernelSha256 == asset.expectedKernelSha256 || inspection.alreadyPatched) {
            return Preparation(
                asset, null, true,
                planOrigin = UltraKernelPlan.Origin.GENERATED.name,
                siteMatch = evidence?.summary.orEmpty(),
                referenceMatch = evidence?.referenceMatch ?: false,
            )
        }
        return Preparation(
            asset, builder.generate(base, asset, spec), false,
            planOrigin = UltraKernelPlan.Origin.GENERATED.name,
            siteMatch = evidence?.summary.orEmpty(),
            referenceMatch = evidence?.referenceMatch ?: false,
        )
    }

    /** Kernel release of a boot image, read without touching any partition. */
    fun releaseOf(image: File): String {
        val report = BootImageInspector.inspect(image)
        check(report.kernelSize in 1L..KernelCompression.MAX_KERNEL_BYTES.toLong()) {
            "ULTRA_KERNEL_BASE_NO_KERNEL"
        }
        val container = ByteArray(report.kernelSize.toInt())
        RandomAccessFile(image, "r").use { input ->
            input.seek(report.kernelOffset)
            input.readFully(container)
        }
        val raw = KernelCompression.decode(container, report.compression, KernelCompression.MAX_KERNEL_BYTES)
        return UltraKernelSpec.releaseOf(raw)
    }

    /** Byte-exact copy of the boot image the installer service just dumped (`images/imported.img`). */
    fun backupCurrentBoot(): File {
        val filesDir = workspace.parentFile ?: File(".")
        val imported = File(File(filesDir, "images"), "imported.img")
        check(imported.isFile) { "ULTRA_KERNEL_BASE_UNAVAILABLE" }
        check(workspace.isDirectory || workspace.mkdirs()) { "ULTRA_KERNEL_WORKSPACE" }
        val backup = File(workspace, "boot-current.img")
        val pending = File(workspace, "boot-current.pending")
        imported.inputStream().use { input ->
            pending.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            }
        }
        check(pending.renameTo(backup)) { "ULTRA_KERNEL_BACKUP_FAILED" }
        return backup
    }

    fun inspect(base: File, asset: UltraKernelPatchAsset): UltraKernelBuilder.BaseInspection =
        builder.inspectBase(base, asset, specFor(asset))

    fun build(base: File, asset: UltraKernelPatchAsset): UltraKernelBuilder.Candidate {
        val spec = specFor(asset)
        check(spec.key.isEmpty()) { "ULTRA_KERNEL_LEGACY_ASSET_READ_ONLY" }
        return builder.generate(base, asset, spec)
    }

    /** An already-patched source still needs committing when migrating from another root. */
    fun candidateForInstall(base: File, preparation: Preparation): UltraKernelBuilder.Candidate {
        preparation.candidate?.let { return it }
        check(preparation.alreadyInstalled) { "ULTRA_KERNEL_CANDIDATE_MISSING" }
        val asset = requireNotNull(preparation.variant)
        val spec = if (asset is UltraKernelGeneratedAsset) {
            UltraKernelSpec.parse(asset.plan.specText())
        } else specFor(asset)
        val inspection = builder.inspectBase(base, asset, spec)
        check(inspection.alreadyPatched || inspection.kernelSha256 == asset.expectedKernelSha256) {
            "ULTRA_KERNEL_CANDIDATE_CHANGED"
        }
        return UltraKernelBuilder.Candidate(
            image = base, bootSha256 = inspection.bootSha256, baseBootSha256 = inspection.bootSha256,
            kernelSha256 = inspection.kernelSha256, kernelRelease = inspection.kernelRelease,
            patches = asset.patches, differingBytes = 0,
            knownImage = inspection.bootSha256 == asset.knownBaseImageBootSha256,
            verification = asset.verification, source = asset.source, strictBase = false,
            expectedPinned = asset.generated == null && inspection.kernelSha256 == asset.expectedKernelSha256,
        )
    }

    internal class Preparation(
        val variant: UltraKernelPatchAsset?,
        val candidate: UltraKernelBuilder.Candidate?,
        val alreadyInstalled: Boolean,
        val verification: String = variant?.verification.orEmpty(),
        val warning: String? = variant?.warning,
        val experimental: Boolean = variant?.experimental ?: false,
        /** `GENERATED` when this preparation came from the on-device recipe renderer, else `""`. */
        val planOrigin: String = "",
        /** `"18/18"`: records that still carried the reference build's pristine bytes. */
        val siteMatch: String = "",
        /** True only when every site matched the reference build; never promotes [supportLevel]. */
        val referenceMatch: Boolean = false,
    ) {
        /** True when no shipped asset produced this candidate; the UI must warn unconditionally. */
        val generated: Boolean get() = planOrigin == UltraKernelPlan.Origin.GENERATED.name

        /**
         * What this preparation may claim. A generated one is `EXPERIMENTAL` even when
         * [referenceMatch] is true - matching a reference build is not the same as having booted.
         */
        val supportLevel: String
            get() = if (generated || planOrigin == "LEGACY_CFI_MODULE" || candidate?.partition == "init_boot") {
                UltraKernelPlan.SupportLevel.EXPERIMENTAL.name
            } else {
                UltraKernelPlan.SupportLevel.ASSET_DECIDES.name
            }
    }

    private class Catalogs(
        val releases: UltraKernelReleaseCatalog,
        val legacy: UltraKernelCatalog,
    )

    private fun loadCatalogs(): Catalogs = Catalogs(
        releases = UltraKernelReleaseCatalog.load(assets),
        legacy = UltraKernelCatalog.loadOrEmpty(assets),
    )

    private fun knownReleases(catalogs: Catalogs): List<String> =
        (catalogs.releases.knownReleases() + catalogs.legacy.knownReleases()).distinct().sorted()

    /**
     * Resolves [release] to the assets of its kernel **series** (`6.6.50` -> the `6.6` assets),
     * legacy manifest last, and `null` when the series ships nothing (the caller may generate).
     *
     * A shipped asset is authoritative and is never refused because of its series: the tier list
     * below only guards the **generation hand-off**, i.e. the case where nothing exists to try.
     * There, and only there, two refusals happen - both explicit, neither a "support level":
     *
     *  - a release that does not parse as `digits.digits…` (empty, `AMBIGUOUS`) is refused with
     *    `ULTRA_KERNEL_RELEASE_UNSUPPORTED`, because no series can be derived and guessing one would
     *    be the silent downgrade this class exists to prevent;
     *  - a parsed series the recipe has no tier for (`6.7`, `7.0`) is refused with
     *    `ULTRA_KERNEL_SERIES_MISMATCH:<release>:known=[…]` - the user's rule is "同系列不同 release
     *    才套用，未知系列不许猜".
     */
    private fun select(release: String, catalogs: Catalogs): UltraKernelSelection? {
        val releaseAssets = catalogs.releases.assetsFor(release)
        if (releaseAssets.isNotEmpty()) {
            val selection = selectUsable(releaseAssets) { asset ->
                try {
                    loadReleaseSpec(assets, asset)
                    null
                } catch (error: Exception) {
                    error.message ?: error.javaClass.simpleName
                }
            }
            check(selection.usable.isNotEmpty()) {
                "ULTRA_KERNEL_NO_USABLE_ASSET:$release:${selection.rejected.joinToString(" | ")}"
            }
            return selection
        }
        // A declared-but-broken release directory must not fall through to the legacy manifest:
        // that would silently ship a different patch set than the one reviewed for this release.
        catalogs.releases.rejection(release)?.let { reason ->
            throw IllegalStateException("ULTRA_KERNEL_RELEASE_ASSET_INVALID:$release:$reason")
        }

        val ordered = catalogs.legacy.orderedForRelease(release)
        if (ordered.isNotEmpty()) {
            val selection = selectUsable(ordered) { variant ->
                try {
                    UltraKernelCatalog.spec(assets, variant)
                    null
                } catch (error: Exception) {
                    error.message ?: error.javaClass.simpleName
                }
            }
            check(selection.usable.isNotEmpty()) {
                "ULTRA_KERNEL_NO_USABLE_VARIANT:${selection.rejected.joinToString(" | ")}"
            }
            return selection
        }
        // Nothing to try for this release: the caller's next step would be on-device generation.
        // Refuse a series nobody has rendered instead of letting the recipe guess at it.
        if (KernelSeries.of(release) == null) {
            throw UltraKernelReleaseUnsupportedException(
                release.trim().ifEmpty { KernelSeries.UNREADABLE },
                knownReleases(catalogs),
            )
        }
        KernelSeries.requireSupported(release)
        return null
    }

    private fun specFor(asset: UltraKernelPatchAsset): UltraKernelSpec = when (asset) {
        is UltraKernelReleaseAsset -> loadReleaseSpec(assets, asset)
        is UltraKernelVariant -> UltraKernelCatalog.spec(assets, asset)
        else -> throw IllegalStateException("ULTRA_KERNEL_ASSET_UNKNOWN:${asset.id}")
    }

    private fun requireBase(base: File) {
        check(base.isFile && base.length() in 1..UltraKernelCatalog.MAX_IMAGE_BYTES) {
            "ULTRA_KERNEL_BASE_INVALID"
        }
    }
}

/** Preserve only an explicitly chosen historical asset at the external compatibility boundary. */
internal fun withChosenVariant(
    selection: UltraKernelSelection?,
    variantId: String?,
    onNotice: (String) -> Unit,
): UltraKernelSelection? {
    if (variantId == null) return selection
    val chosen = selection?.usable?.firstOrNull { it.id == variantId }
        ?: error("ULTRA_KERNEL_VARIANT_UNAVAILABLE:$variantId")
    onNotice("已按你的选择使用「${chosen.label}」")
    return UltraKernelSelection(listOf(chosen), selection.rejected, chosen.id)
}
