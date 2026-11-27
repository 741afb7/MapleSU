package dev.maplesu.manager.ultrakernel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraKernelPatchedBaselineTest {
    private val sample = File("../evidence/ultra-reinstall-pairing-20261104/baseline-boot.img")
    private val assets = UltraKernelFileAssets(File("src/main/assets"))

    @Test fun oldPinnedPatchRecoversWithoutAppDataAndBuildsTheCurrentVariant() {
        assumeTrue(sample.isFile)
        val workspace = Files.createTempDirectory("maplesu-old-baseline-").toFile()
        try {
            val flow = UltraKernelFlow(workspace, assets)
            val before = UltraKernelSpec.sha256(sample)
            val notices = mutableListOf<String>()
            val selected = requireNotNull(flow.selectForRelease(flow.releaseOf(sample)))
                .let { it.copy(usable = it.usable.filter { asset -> asset.id == "k22" }) }
            val prepared = flow.prepare(sample, selected, notices::add)
            val candidate = requireNotNull(prepared.candidate)
            assertFalse(prepared.alreadyInstalled)
            assertEquals("c07fd486b563c370d2294f580be02fb5b24feaf4966932d3111d6a61b219f243",
                candidate.baseBootSha256)
            assertTrue(prepared.generated)
            val generated = prepared.variant as UltraKernelGeneratedAsset
            assertNull(generated.plan.credential)
            assertEquals(generated.plan.derived!!.patchedKernelSha256, candidate.kernelSha256)
            assertNotEquals(before, candidate.bootSha256)
            assertEquals(before, UltraKernelSpec.sha256(sample))
            assertTrue(notices.any { it.contains("已识别当前 Ultra") })
        } finally { workspace.deleteRecursively() }
    }

    @Test fun previousLocalCacheTemplateRecoversAndUpgradesWithoutAppData() {
        val pristine = File("../MapleSU_Reference/Boots/OnePlusAce6_6.6/boot.img")
        assumeTrue(pristine.isFile)
        val workspace = Files.createTempDirectory("maplesu-ialluis-upgrade-").toFile()
        try {
            val archived = object : UltraKernelAssetSource {
                private fun path(value: String) = value.replaceFirst("ultra-kernels/", "ultra-kernels/recovery/ic-iallu/")
                override fun list(directory: String) = assets.list(path(directory))
                override fun read(path: String) = assets.read(this.path(path))
            }
            val release = UltraKernelPlanFixtures.K22_RELEASE
            val oldAsset = UltraKernelReleaseCatalog.load(archived).assetsFor(release).single { it.id == "k22" }
            val oldSpec = UltraKernelSpec.parse(archived.read(oldAsset.specAsset).toString(Charsets.UTF_8))
            val old = UltraKernelBuilder(File(workspace, "old")).generate(pristine, oldAsset, oldSpec)
            assertEquals("2557d5069d962d43dbaf26a0bd6507b95970766f915f4c9f7e4eae58b775815d", old.bootSha256)
            assertEquals(0xD508751F.toInt(), UltraKernelA64.le32Bits(oldSpec.patches[9].replacement, 52))
            val restored = UltraKernelOriginalRecovery(assets).recoverPinned(old.image, File(workspace, "restored"))
            assertNotNull(restored)
            assertEquals(UltraKernelOriginalRecovery.Source.PINNED_SPEC_REVERSE, restored!!.source)
            assertEquals(UltraKernelReleaseFixtures.KNOWN_BASE_IMAGE, UltraKernelSpec.sha256(restored.image))
            val next = UltraKernelReleaseCatalog.load(assets).assetsFor(release).single { it.id == "k22" }
            val spec = UltraKernelSpec.parse(assets.read(next.specAsset).toString(Charsets.UTF_8))
            val publications = spec.patches.flatMap { patch ->
                val code = patch.replacement
                (12 until code.size - 3 step 4)
                    .filter { UltraKernelA64.le32Bits(code, it) == UltraKernelA64.blr(10) }
                    .map { call -> (call - 12 until call step 4).map { UltraKernelA64.le32Bits(code, it) } }
            }
            assertEquals(listOf(listOf(UltraKernelA64.icIalluis(), UltraKernelA64.DSB_ISH, UltraKernelA64.ISB)),
                publications)
            val upgraded = UltraKernelBuilder(File(workspace, "next")).generate(restored.image, next, spec)
            assertEquals(UltraKernelPlanFixtures.K22_BOOT_SHA256, upgraded.bootSha256)
            assertEquals(UltraKernelPlanFixtures.K22_KERNEL_SHA256, upgraded.kernelSha256)
            assertNotEquals(old.bootSha256, upgraded.bootSha256)
            assertNotNull(UltraKernelOriginalRecovery(assets).recoverPinned(upgraded.image, File(workspace, "next-restored")))
        } finally { workspace.deleteRecursively() }
    }

    @Test fun changedPatchedImageNeverUsesPinnedRecovery() {
        assumeTrue(sample.isFile)
        val workspace = Files.createTempDirectory("maplesu-altered-baseline-").toFile()
        try {
            val changed = File(workspace, "changed.img")
            sample.copyTo(changed)
            java.io.RandomAccessFile(changed, "rw").use { file ->
                file.seek(file.length() - 1)
                file.write(1)
            }
            assertNull(UltraKernelOriginalRecovery(assets).recoverPinned(changed, File(workspace, "recovered")))
        } finally { workspace.deleteRecursively() }
    }
}
