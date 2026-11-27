package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proof for the release-indexed asset catalogue.
 *
 * The cases cover the contract the install flow relies on: release identification/matching,
 * the unsupported-release refusal, every offline gate (spec digest, record count, spec release,
 * meta shape/path), the experimental warning field, the optional base-kernel pin, and the legacy
 * k21/k22 manifest compatibility path.
 */
class UltraKernelReleaseCatalogTest {

    private val workspaces = ArrayList<File>()

    @After
    fun cleanup() {
        workspaces.forEach { it.deleteRecursively() }
    }

    private fun flow(assets: UltraKernelAssetSource): UltraKernelFlow {
        val workspace = UltraKernelReleaseFixtures.tempWorkspace("ultrakernel-catalog")
        workspaces += workspace
        return UltraKernelFlow(workspace, assets)
    }

    private fun bootIn(workspace: File, release: String): File =
        File(workspace, "synthetic-boot.img").also {
            it.writeBytes(UltraKernelReleaseFixtures.syntheticBoot(release))
        }

    @Test
    fun releaseDirectoryIsIndexedByKernelRelease() {
        val assets = UltraKernelReleaseFixtures.releaseAssets(UltraKernelReleaseFixtures.meta())
        val catalog = UltraKernelReleaseCatalog.load(assets)

        assertEquals(listOf(UltraKernelReleaseFixtures.RELEASE), catalog.knownReleases())
        val ordered = catalog.assetsFor(UltraKernelReleaseFixtures.RELEASE)
        assertEquals(listOf("k22", "k21"), ordered.map { it.id })
        assertTrue(ordered.first().default)
        assertEquals(16, ordered.first().patches)
        assertEquals(
            "ultra-kernels/releases/${UltraKernelReleaseFixtures.RELEASE}/k22-ultra-6.6.89.spec",
            ordered.first().specAsset,
        )
        assertEquals(UltraKernelReleaseFixtures.K22_KERNEL, ordered.first().expectedKernelSha256)
        assertFalse(ordered.first().experimental)
        assertNull(ordered.first().warning)
    }

    @Test
    fun singleAssetMetaUsesTopLevelFieldsAndDefaultsSpecName() {
        val assets = UltraKernelReleaseFixtures.releaseAssets(
            UltraKernelReleaseFixtures.singleMeta(),
            specs = mapOf("${UltraKernelReleaseFixtures.RELEASE}.spec" to UltraKernelReleaseFixtures.k22Bytes()),
        )
        val asset = UltraKernelReleaseCatalog.load(assets)
            .assetsFor(UltraKernelReleaseFixtures.RELEASE).single()

        assertEquals("one", asset.id)
        assertEquals(15, asset.patches)
        assertEquals("model", asset.source)
        assertEquals("verified", asset.verification)
        assertEquals(
            "ultra-kernels/releases/${UltraKernelReleaseFixtures.RELEASE}/${UltraKernelReleaseFixtures.RELEASE}.spec",
            asset.specAsset,
        )
        assertTrue(asset.default)
    }

    @Test
    fun selectForReleaseReturnsDefaultAssetWithoutFallback() {
        val selection = flow(
            UltraKernelReleaseFixtures.releaseAssets(UltraKernelReleaseFixtures.meta()),
        ).selectForRelease(UltraKernelReleaseFixtures.RELEASE)

        assertNotNull(selection)
        assertEquals("k22", selection!!.primary.id)
        assertFalse(selection.fellBack)
        assertTrue(selection.rejected.isEmpty())
        assertTrue(selection.warnings.isEmpty())
    }

    @Test
    fun explicitSelectionReportsUnsupportedReleaseWithItsKnownReleaseList() {
        val assets = UltraKernelReleaseFixtures.releaseAssets(
            UltraKernelReleaseFixtures.meta(release = UltraKernelReleaseFixtures.OTHER_RELEASE),
            directory = UltraKernelReleaseFixtures.OTHER_RELEASE,
        )
        val error = assertThrows(UltraKernelReleaseUnsupportedException::class.java) {
            flow(assets).selectForRelease("UNREADABLE")
        }
        assertEquals("ULTRA_KERNEL_RELEASE_UNSUPPORTED", error.code)
        assertEquals("UNREADABLE", error.kernelRelease)
        assertTrue(error.knownReleases.contains(UltraKernelReleaseFixtures.OTHER_RELEASE))
        assertTrue(error.message!!.contains(UltraKernelReleaseFixtures.OTHER_RELEASE))
    }

    @Test
    fun noAssetDiagnosesMissingSymbolsBeforeGenerationAndLeavesNoCandidate() {
        val workspace = UltraKernelReleaseFixtures.tempWorkspace("ultrakernel-generate")
        workspaces += workspace
        val flow = UltraKernelFlow(
            workspace,
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(release = UltraKernelReleaseFixtures.OTHER_RELEASE),
                directory = UltraKernelReleaseFixtures.OTHER_RELEASE,
            ),
        )
        val boot = bootIn(workspace, UltraKernelReleaseFixtures.RELEASE)
        val notices = ArrayList<String>()

        // Routing and generation both require target symbols. This input has no kallsyms, so the
        // route check must stop before generation notices or candidate writes, without a Key gate.
        val error = assertThrows(UltraKernelGenerateException::class.java) {
            flow.prepare(boot) { notices += it }
        }
        assertEquals(UltraKernelGenerateCodes.KALLSYMS, error.code)
        assertTrue("routing failed before generation started", notices.isEmpty())
        assertEquals(
            "nothing may be written when generation fails",
            0,
            workspace.listFiles().orEmpty().count { it.name.startsWith("candidate-") },
        )
    }

    @Test
    fun specDigestMismatchFallsBackToK21() {
        val selection = flow(
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(k22Sha = "0".repeat(64)),
            ),
        ).selectForRelease(UltraKernelReleaseFixtures.RELEASE)

        assertNotNull(selection)
        assertEquals("k21", selection!!.primary.id)
        assertTrue(selection.fellBack)
        assertEquals(1, selection.rejected.size)
        assertTrue(selection.rejected.single().startsWith("k22:ULTRA_KERNEL_ASSET_SPEC_DIGEST:k22"))
    }

    @Test
    fun allAssetsRejectedFailsClosed() {
        val flow = flow(
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(
                    k22Sha = "0".repeat(64),
                    includeK21 = false,
                ),
            ),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            flow.selectForRelease(UltraKernelReleaseFixtures.RELEASE)
        }
        assertTrue(error.message!!.startsWith(
            "ULTRA_KERNEL_NO_USABLE_ASSET:${UltraKernelReleaseFixtures.RELEASE}"))
        assertTrue(error.message!!.contains("ULTRA_KERNEL_ASSET_SPEC_DIGEST"))
    }

    @Test
    fun recordCountMismatchIsRefused() {
        val selection = flow(
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(k22Patches = 17),
            ),
        ).selectForRelease(UltraKernelReleaseFixtures.RELEASE)

        assertNotNull(selection)
        assertEquals("k21", selection!!.primary.id)
        assertTrue(selection.rejected.single().contains("ULTRA_KERNEL_ASSET_SPEC_COUNT:k22:16"))
    }

    @Test
    fun specKernelReleaseMismatchIsRefused() {
        val text = String(UltraKernelReleaseFixtures.k22Bytes(), Charsets.UTF_8)
            .replace(
                "#kernel=${UltraKernelReleaseFixtures.RELEASE}",
                "#kernel=${UltraKernelReleaseFixtures.OTHER_RELEASE}",
            )
        val bytes = text.toByteArray()
        val assets = UltraKernelReleaseFixtures.releaseAssets(
            UltraKernelReleaseFixtures.meta(k22Sha = UltraKernelReleaseFixtures.specSha(bytes)),
            specs = mapOf(
                UltraKernelReleaseFixtures.K22_SPEC to bytes,
                UltraKernelReleaseFixtures.K21_SPEC to UltraKernelReleaseFixtures.k21Bytes(),
            ),
        )

        val selection = flow(assets).selectForRelease(UltraKernelReleaseFixtures.RELEASE)
        assertNotNull(selection)
        assertEquals("k21", selection!!.primary.id)
        assertTrue(selection.rejected.single().contains("ULTRA_KERNEL_ASSET_SPEC_RELEASE:k22"))
    }

    @Test
    fun malformedMetaIsReportedAsInvalidReleaseNotUnknown() {
        val flow = flow(UltraKernelReleaseFixtures.releaseAssets("{not-json"))
        val error = assertThrows(IllegalStateException::class.java) {
            flow.selectForRelease(UltraKernelReleaseFixtures.RELEASE)
        }
        assertTrue(error.message!!.startsWith(
            "ULTRA_KERNEL_RELEASE_ASSET_INVALID:${UltraKernelReleaseFixtures.RELEASE}"))
        assertTrue(error.message!!.contains("ULTRA_KERNEL_RELEASE_META_INVALID"))
    }

    @Test
    fun metaReleaseMustMatchDirectoryName() {
        val flow = flow(
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(),
                directory = UltraKernelReleaseFixtures.OTHER_RELEASE,
            ),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            flow.selectForRelease(UltraKernelReleaseFixtures.OTHER_RELEASE)
        }
        assertTrue(error.message!!.contains("ULTRA_KERNEL_RELEASE_META_MISMATCH"))
        assertTrue(error.message!!.contains(UltraKernelReleaseFixtures.OTHER_RELEASE))
    }

    @Test
    fun specPathTraversalIsRefusedAtLoad() {
        val flow = flow(
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(k22Spec = "../escape.spec"),
            ),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            flow.selectForRelease(UltraKernelReleaseFixtures.RELEASE)
        }
        assertTrue(error.message!!.contains("ULTRA_KERNEL_RELEASE_ASSET_SPEC_PATH"))
    }

    @Test
    fun experimentalAssetIsUsableButCarriesAWarning() {
        val selection = flow(
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(verification = "experimental"),
            ),
        ).selectForRelease(UltraKernelReleaseFixtures.RELEASE)

        assertNotNull(selection)
        val asset = selection!!.primary
        assertEquals("experimental", asset.verification)
        assertTrue(asset.experimental)
        assertEquals("ULTRA_KERNEL_ASSET_EXPERIMENTAL:k22", asset.warning)
        assertTrue(selection.warnings.contains("ULTRA_KERNEL_ASSET_EXPERIMENTAL:k22"))
        // Experimental is a warning, not a silent downgrade: the asset still has to build.
        assertEquals(UltraKernelReleaseFixtures.K22_KERNEL, asset.expectedKernelSha256)
    }

    @Test
    fun baseKernelPinIsOptional() {
        val asset = UltraKernelReleaseCatalog.load(
            UltraKernelReleaseFixtures.releaseAssets(
                UltraKernelReleaseFixtures.meta(k22Base = null),
            ),
        ).assetsFor(UltraKernelReleaseFixtures.RELEASE).first { it.id == "k22" }

        assertEquals("", asset.baseKernelSha256)
        assertEquals(UltraKernelReleaseFixtures.K22_KERNEL, asset.expectedKernelSha256)
    }

    @Test
    fun legacyManifestStillLoadsK21AndK22() {
        val assets = UltraKernelReleaseFixtures.legacyAssets()
        val catalog = UltraKernelCatalog.load(assets)

        assertEquals(2, catalog.variants.size)
        val ordered = catalog.orderedForRelease(UltraKernelReleaseFixtures.RELEASE)
        assertEquals(listOf("k22", "k21"), ordered.map { it.id })
        assertTrue(ordered.first().default)
        assertEquals(UltraKernelReleaseFixtures.RELEASE, catalog.knownReleases().single())

        val k21Spec = UltraKernelCatalog.spec(assets, catalog.variant("k21")!!)
        assertEquals(15, k21Spec.patches.size)
        assertEquals(UltraKernelReleaseFixtures.RELEASE, k21Spec.kernelRelease)
    }

    @Test
    fun legacySpecDigestMismatchIsStillRefused() {
        val assets = UltraKernelReleaseFixtures.legacyAssets(
            manifest = UltraKernelReleaseFixtures.manifestText()
                .replace("fef44a1373daf01268b6096a0dbc4b5ee0f96ab3950466c9ac1d43a8b78236cb",
                    "0".repeat(64)),
        )
        val catalog = UltraKernelCatalog.load(assets)

        val error = assertThrows(IllegalStateException::class.java) {
            UltraKernelCatalog.spec(assets, catalog.variant("k21")!!)
        }
        assertTrue(error.message!!.startsWith("ULTRA_KERNEL_SPEC_DIGEST_MISMATCH:k21"))
    }

    @Test
    fun legacyManifestIsUsedForReleaseMissingFromTheReleaseCatalogue() {
        val assets = UltraKernelReleaseFixtures.combinedAssets(
            UltraKernelReleaseFixtures.meta(release = UltraKernelReleaseFixtures.OTHER_RELEASE),
            UltraKernelReleaseFixtures.OTHER_RELEASE,
        )
        val selection = flow(assets).selectForRelease(UltraKernelReleaseFixtures.RELEASE)

        assertNotNull(selection)
        assertEquals(listOf("k22", "k21"), selection!!.usable.map { it.id })
        assertFalse(selection.fellBack)
    }

    @Test
    fun shippedReleaseCopiesTheLegacySpecsByteForByte() {
        val root = UltraKernelReleaseFixtures.assetRoot
        val releaseDir = File(root, "releases/${UltraKernelReleaseFixtures.RELEASE}")

        assertEquals("e87e4388917804ad72ce5a41377eaa78e0f29e26a906f9cd9e496100ed7ce1a1",
            UltraKernelReleaseFixtures.specSha(File(root, "k22-ultra-6.6.89.spec").readBytes()))
        assertEquals("fef44a1373daf01268b6096a0dbc4b5ee0f96ab3950466c9ac1d43a8b78236cb",
            UltraKernelReleaseFixtures.specSha(File(root, "k21-ultra-6.6.89.spec").readBytes()))
        assertArrayEquals(
            File(root, "k22-ultra-6.6.89.spec").readBytes(),
            File(releaseDir, "k22-ultra-6.6.89.spec").readBytes(),
        )
        assertArrayEquals(
            File(root, "k21-ultra-6.6.89.spec").readBytes(),
            File(releaseDir, "k21-ultra-6.6.89.spec").readBytes(),
        )
        val manifest = UltraKernelReleaseFixtures.manifestText()
        assertTrue(manifest.contains("\"id\": \"k22\""))
        assertTrue(manifest.contains("\"id\": \"k21\""))
        assertTrue(File(releaseDir, "meta.json").isFile)
    }
}
