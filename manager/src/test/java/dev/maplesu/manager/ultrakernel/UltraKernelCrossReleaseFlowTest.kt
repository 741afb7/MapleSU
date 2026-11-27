package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.BootImageInspector
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Replays the app's catalogue-first entry on the reported 6.6.118 image, without flashing. */
class UltraKernelCrossReleaseFlowTest {
    private val sample = File("../Boot_Research/簌/boot.img")
    private val assets = UltraKernelFileAssets(File("src/main/assets"))
    private val workspaces = ArrayList<File>()

    @After
    fun cleanup() {
        workspaces.forEach { it.deleteRecursively() }
    }

    private fun flow(): UltraKernelFlow {
        assumeTrue("reported 6.6.118 boot sample not available", sample.isFile)
        val workspace = Files.createTempDirectory("ultrakernel-cross-release-").toFile()
        workspaces += workspace
        return UltraKernelFlow(workspace, assets)
    }

    @Test
    fun differentBuildOfTheSameSeriesGeneratesFromTheActualImage() {
        val flow = flow()
        val before = UltraKernelSpec.sha256(sample)
        val selection = requireNotNull(flow.selectForRelease(flow.releaseOf(sample)))
        assertTrue(selection.usable.all { !flow.inspect(sample, it).pristine })

        val preparation = flow.prepare(sample)
        assertFalse(preparation.alreadyInstalled)
        assertTrue(preparation.generated)
        assertEquals("EXPERIMENTAL", preparation.supportLevel)
        assertEquals(flow.generatePlan(sample).evidence?.summary, preparation.siteMatch)
        assertTrue(flow.generatePlan(sample).credential == null)
        val candidate = requireNotNull(preparation.candidate)
        assertEquals(sample.length(), candidate.image.length())
        assertEquals(flow.releaseOf(sample), candidate.kernelRelease)
        assertEquals(candidate.bootSha256, BootImageInspector.inspect(candidate.image).sha256)
        assertEquals(flow.generatePlan(sample).derived?.patchedKernelSha256,
            candidate.kernelSha256)
        assertEquals(before, UltraKernelSpec.sha256(sample))
    }

    @Test
    fun aPinnedK22SelectionUsesCurrentKeylessGeneration() {
        val (flow, selection) = pinnedFixture()
        val prepared = flow.prepare(sample, selection) {}
        assertTrue(flow.generatePlan(sample).credential == null)
        assertTrue(prepared.generated)
        val candidate = requireNotNull(prepared.candidate)
        assertEquals(candidate.bootSha256, BootImageInspector.inspect(candidate.image).sha256)
        val again = flow.prepare(candidate.image, selection) {}
        assertTrue(again.alreadyInstalled)
        assertTrue(flow.generatePlan(candidate.image).credential == null)
    }

    @Test
    fun explicitPinnedBuildKeepsItsOutputDigestFailure() {
        val (flow, selection) = pinnedFixture()
        val asset = (selection.primary as UltraKernelReleaseAsset).copy(expectedKernelSha256 = "0".repeat(64))
        assertThrows(IllegalStateException::class.java) {
            flow.build(sample, asset)
        }
    }

    @Test
    fun aPreparationCallbackFailureNeverFallsBackToAnotherAsset() {
        val (flow, selection) = pinnedFixture()
        val fallback = (selection.primary as UltraKernelReleaseAsset).copy(id = "k21")
        val failure = assertThrows(IllegalStateException::class.java) {
            flow.prepare(sample, selection.copy(usable = selection.usable + fallback)) {
                error("fixture-preparation-notice-failed")
            }
        }
        assertEquals("fixture-preparation-notice-failed", failure.message)
    }

    private fun pinnedFixture(): Pair<UltraKernelFlow, UltraKernelSelection> {
        val base = flow()
        val plan = base.generatePlan(sample)
        val spec = plan.specText(plan.credential).toByteArray()
        val report = BootImageInspector.inspect(sample)
        val source = object : UltraKernelAssetSource by assets {
            override fun read(path: String) = if (path == "fixture.spec") spec else assets.read(path)
        }
        val selection = requireNotNull(base.selectForRelease(base.releaseOf(sample)))
        val asset = (selection.primary as UltraKernelReleaseAsset).copy(
            id = "k22", specAsset = "fixture.spec", specSha256 = UltraKernelSpec.sha256(spec),
            kernelRelease = plan.kernelRelease, baseKernelSha256 = plan.baseKernelSha256,
            expectedKernelSha256 = requireNotNull(plan.derived).patchedKernelSha256,
            knownBaseImageSha256 = "", knownBaseImageBootSha256 = "",
            patches = plan.records.size, imageSizeBytes = report.fileSize, ramdiskSize = report.ramdiskSize,
        )
        val workspace = Files.createTempDirectory("ultrakernel-pinned-identity-").toFile()
        workspaces += workspace
        return UltraKernelFlow(workspace, source) to selection.copy(usable = listOf(asset))
    }

    @Test
    fun defaultGenerationHasNoCredentialPrerequisiteAndKeepsInput() {
        val flow = flow()
        val before = sample.readBytes()
        val prepared = flow.prepare(sample)
        assertTrue(prepared.generated)
        assertNotNull(prepared.candidate)
        assertTrue(flow.generatePlan(sample).credential == null)
        org.junit.Assert.assertArrayEquals(before, sample.readBytes())
    }

    @Test
    fun assetOnlyEntryStillRejectsTheMismatchingStaticRecords() {
        val flow = flow()
        val selection = requireNotNull(flow.selectForRelease(flow.releaseOf(sample)))
        val failure = assertThrows(IllegalStateException::class.java) {
            flow.prepare(sample, selection.copy(usable = selection.usable.filter { it.id == "k21" })) {}
        }
        assertEquals("ULTRA_KERNEL_LEGACY_ASSET_READ_ONLY", failure.message)
        assertNotNull(selection.usable.firstOrNull { it.id == "k22" })
    }

    @Test
    fun explicitlySelectedK22UsesTheSameGeneratedPathAsTheApp() {
        val flow = flow()
        val selection = requireNotNull(flow.selectForRelease(flow.releaseOf(sample)))
        val chosen = selection.copy(usable = selection.usable.filter { it.id == "k22" })
        val preparation = flow.prepare(sample, chosen) {}
        assertTrue(preparation.generated)
        assertNotNull(preparation.candidate)
        assertTrue(flow.generatePlan(sample).slots == null)
    }

    @Test
    fun explicitlySelectedK21DoesNotSilentlyBecomeTheK22Recipe() {
        val flow = flow()
        val selection = requireNotNull(flow.selectForRelease(flow.releaseOf(sample)))
        val chosen = selection.copy(usable = selection.usable.filter { it.id == "k21" })
        val failure = assertThrows(IllegalStateException::class.java) {
            flow.prepare(sample, chosen) {}
        }
        assertEquals("ULTRA_KERNEL_LEGACY_ASSET_READ_ONLY", failure.message)
    }

    @Test
    fun aRejectedSiblingDoesNotBlockIndependentK22Generation() {
        val flow = flow()
        val selection = requireNotNull(flow.selectForRelease(flow.releaseOf(sample))).copy(
            rejected = listOf("fixture:ULTRA_KERNEL_SPEC_DIGEST_MISMATCH"),
        )
        val notices = ArrayList<String>()
        val preparation = flow.prepare(sample, selection, notices::add)
        assertTrue(preparation.generated)
        assertNotNull(preparation.candidate)
        assertTrue(flow.generatePlan(sample).credential == null)
        // The public prepare(selection) boundary chooses the current generator;
        // stale catalog rejection records do not become generated output facts.
        assertTrue(notices.any { it.contains(flow.releaseOf(sample)) })
    }

    @Test
    fun aRejectedSiblingDoesNotChangeKeylessIdentityOnRetry() {
        val (flow, selected) = pinnedFixture()
        val selection = selected.copy(rejected = listOf("fixture:ULTRA_KERNEL_SPEC_DIGEST_MISMATCH"))
        val preparation = flow.prepare(sample, selection) {}
        assertTrue(preparation.generated)
        assertTrue(flow.generatePlan(sample).credential == null)
        val candidate = requireNotNull(preparation.candidate)
        val again = flow.prepare(candidate.image, selection) {}
        assertTrue(again.alreadyInstalled)
        assertTrue(flow.generatePlan(candidate.image).credential == null)
    }
}
