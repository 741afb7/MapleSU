package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.BootImageRebuilder
import dev.maplesu.image.KernelCompression
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The series rule (2026-11-01): 「6.6.89 匹配 6.6，只要是 6.6 前缀就可以修补刷写；失败再不刷就好了；
 * 不需要校验。」
 *
 * A release is matched by its `major.minor` **series**, never by full equality, so
 *
 *  - `6.6.50-android15-…` is answered by the shipped `6.6.89` asset and by the `6.6` recipe tier;
 *  - `6.1.60`, `6.12.30`, `6.18.5`, `5.15.99`, `5.10.77` hit their own tier and render 16/16/18/17/17
 *    records on the real boot samples;
 *  - a generated target is evaluated from its own semantics even outside the catalogue's series;
 *    catalogue selection retains `ULTRA_KERNEL_SERIES_MISMATCH` / `RELEASE_UNSUPPORTED`;
 *  - `6.6.89` stays byte-identical to k22 (zero regression).
 *
 * The image-level cases rewrite only the **release token inside the decoded kernel** (same length or
 * shorter, NUL padded) so the series rule is exercised on real `kallsyms`/code bodies without ever
 * writing to a device or to the sample. Samples are large workspace artefacts: each test is skipped
 * when its boot image is absent, and the pure-JVM cases never need one.
 */
class UltraKernelSeriesMatchTest {

    private val credential = UltraKernelPlanFixtures.freshCredential()
    private val generator = UltraKernelSpecGenerator()
    private val assetsRoot = File("src/main/assets")
    private val ace6 = File("../MapleSU_Reference/Boots/OnePlusAce6_6.6/boot.img")
    private val workspaces = ArrayList<File>()

    @After
    fun cleanup() {
        workspaces.forEach { it.deleteRecursively() }
    }

    private fun workspace(): File =
        UltraKernelReleaseFixtures.tempWorkspace("ultrakernel-series").also { workspaces += it }

    // -----------------------------------------------------------------------------------------
    // 1. The parser: major.minor only, never guessed.
    // -----------------------------------------------------------------------------------------

    @Test
    fun seriesIsTheMajorMinorPrefixAndNothingElse() {
        assertEquals("6.6", KernelSeries.of("6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k"))
        assertEquals("6.6", KernelSeries.of("6.6.50-android15-8"))
        assertEquals("6.12", KernelSeries.of("6.12.30-android16-5"))
        assertEquals("5.10", KernelSeries.of("5.10.77-android12-9"))
        assertEquals("10.10", KernelSeries.of("10.10.1"))
        // Leading zeros in a component are normalised, so `6.06` and `6.6` are the same series.
        assertEquals("6.6", KernelSeries.of("6.06.1"))
        // Never guessed: unreadable banners, AMBIGUOUS, the printk `%s` placeholder and `v6.6.1`
        // have no series, and every gate treats that as a mismatch.
        assertNull(KernelSeries.of(""))
        assertNull(KernelSeries.of("   "))
        assertNull(KernelSeries.of("AMBIGUOUS"))
        assertNull(KernelSeries.of("%s"))
        assertNull(KernelSeries.of("v6.6.1"))
        assertNull(KernelSeries.of("6"))
    }

    @Test
    fun seriesEqualityNeedsBothSidesToParse() {
        assertTrue(KernelSeries.same("6.6.89-x", "6.6.50-y"))
        assertTrue(KernelSeries.same("6.6", "6.6.89-x"))
        assertFalse(KernelSeries.same("6.6.89-x", "6.12.23-y"))
        assertFalse(KernelSeries.same("", "6.6.89-x"))
        assertFalse(KernelSeries.same("6", "6.6"))
        // A cross-release match is the narrow case the builder may derive a digest for.
        assertTrue(KernelSeries.isCrossRelease("6.6.50-x", "6.6.89-y"))
        assertFalse(KernelSeries.isCrossRelease("6.6.89-y", "6.6.89-y"))
        assertFalse(KernelSeries.isCrossRelease("6.12.23-x", "6.6.89-y"))
    }

    @Test
    fun releasesSortByNumericComponentSoNewestMeansNewest() {
        assertTrue(KernelSeries.compare("6.6.9", "6.6.89") < 0)
        assertTrue(KernelSeries.compare("6.6.89", "6.6.100") < 0)
        assertTrue(KernelSeries.compare("6.6.100", "6.10.1") < 0)
        assertEquals(0, KernelSeries.compare("6.6", "6.6"))
        assertEquals("6.6.100", KernelSeries.newest(listOf("6.6.9", "6.6.89", "6.6.100")))
        assertNull(KernelSeries.newest(emptyList()))
    }

    // -----------------------------------------------------------------------------------------
    // 2. The appliers: a same-series base is applied, another series is refused.
    // -----------------------------------------------------------------------------------------

    @Test
    fun specApplierAcceptsASameSeriesReleaseAndRecordsTheRealOne() {
        val spec = UltraKernelPlanFixtures.shippedSpec()
        val kernel = renamedKernel(UltraKernelPlanFixtures.syntheticKernel(), "6.6.50-android15-8")
        assertEquals("6.6.50-android15-8", UltraKernelSpec.releaseOf(kernel))

        val result = spec.apply(kernel)
        assertEquals(874, result.differingBytes)
        assertEquals(0, result.outsideSites)
        // The *plan* keeps the real release: nothing rewrites 6.6.50 to "6.6".
        assertEquals(UltraKernelPlanFixtures.K22_RELEASE, spec.kernelRelease)
    }

    @Test
    fun specApplierRefusesAnotherSeriesWithTheSeriesInTheMessage() {
        val spec = UltraKernelPlanFixtures.shippedSpec()
        val kernel = renamedKernel(UltraKernelPlanFixtures.syntheticKernel(), "6.12.30-android16-5")
        val error = assertThrows(IllegalStateException::class.java) { spec.apply(kernel) }
        assertEquals(
            "ULTRA_KERNEL_RELEASE_MISMATCH:6.12:6.6:6.12.30-android16-5:${UltraKernelPlanFixtures.K22_RELEASE}",
            error.message,
        )
    }

    @Test
    fun planApplierFollowsTheSameSeriesRule() {
        val plan = UltraKernelPlanFixtures.k22Plan()
        val same = renamedKernel(UltraKernelPlanFixtures.syntheticKernel(), "6.6.50-android15-8")
        // The applied records change the same 840 bytes; the resulting digest differs only because the
        // banner itself differs, which is exactly why the digest is derived instead of pinned.
        val applied = plan.apply(same)
        assertEquals(plan.derived?.changedBytes, applied.derived.changedBytes)
        assertEquals(0, applied.derived.outsideSites)
        assertEquals(64, applied.derived.patchedKernelSha256.length)
        assertTrue(applied.derived.patchedKernelSha256 != plan.derived?.patchedKernelSha256)

        val other = renamedKernel(UltraKernelPlanFixtures.syntheticKernel(), "5.15.99-android13-8")
        val error = assertThrows(IllegalStateException::class.java) { plan.apply(other) }
        assertTrue(
            "the code name is kept so existing catch points keep working: ${error.message}",
            error.message!!.startsWith("ULTRA_KERNEL_PLAN_RELEASE_MISMATCH:5.15:6.6:"),
        )
    }

    // -----------------------------------------------------------------------------------------
    // 3. The catalogues: series selection, default-then-newest, unknown series refused.
    // -----------------------------------------------------------------------------------------

    @Test
    fun aSameSeriesReleaseHitsTheSixSixAssetTier() {
        val flow = flow(UltraKernelReleaseFixtures.releaseAssets(UltraKernelReleaseFixtures.meta()))
        val selection = flow.selectForRelease("6.6.50-android15-8")

        assertNotNull("6.6.50 must be answered by the shipped 6.6 assets", selection)
        assertEquals("k22", selection!!.primary.id)
        assertEquals(listOf("k22", "k21"), selection.usable.map { it.id })
        // The asset still reports the release it was measured on - only the match is by series.
        assertEquals(UltraKernelReleaseFixtures.RELEASE, selection.primary.kernelRelease)
    }

    @Test
    fun aSeriesWithNoAssetIsNotInvented() {
        val flow = flow(UltraKernelReleaseFixtures.releaseAssets(UltraKernelReleaseFixtures.meta()))
        // 6.6 ships an asset; 6.12 ships none, so the caller falls through to generation instead of
        // being handed the 6.6 patch set.
        assertNull(flow.selectForRelease("6.12.30-android16-5"))
    }

    @Test
    fun aShippedAssetIsHonouredEvenForASeriesTheRecipeDoesNotRender() {
        // The tier list only guards the *generation* hand-off: a reviewed asset is authoritative, so
        // shipping one for a series the recipe cannot render must still be usable. Only "nothing to
        // try" is refused as an unknown series.
        val release = "6.7.1-android18-0"
        val text = String(UltraKernelReleaseFixtures.k22Bytes(), Charsets.UTF_8).replace(
            "#kernel=${UltraKernelReleaseFixtures.RELEASE}",
            "#kernel=$release",
        )
        val bytes = text.toByteArray()
        val assets = UltraKernelReleaseFixtures.releaseAssets(
            UltraKernelReleaseFixtures.meta(release = release, k22Sha = UltraKernelReleaseFixtures.specSha(bytes)),
            directory = release,
            specs = mapOf(UltraKernelReleaseFixtures.K22_SPEC to bytes),
        )

        val selection = flow(assets).selectForRelease(release)
        assertNotNull("a shipped 6.7 asset must not be refused by the tier list", selection)
        assertEquals("k22", selection!!.primary.id)
        // 6.6 and 5.x never apply: the digits are parsed, so `6.7.1` cannot be mistaken for `6.6`.
        assertFalse(KernelSeries.same(release, UltraKernelReleaseFixtures.RELEASE))
    }

    @Test
    fun anUnknownSeriesIsRefusedExplicitlyInsteadOfGuessed() {
        val flow = flow(UltraKernelReleaseFixtures.releaseAssets(UltraKernelReleaseFixtures.meta()))

        val mismatch = assertThrows(IllegalStateException::class.java) {
            flow.selectForRelease("6.7.1-android16-5-gabc")
        }
        assertTrue(mismatch.message!!.startsWith("ULTRA_KERNEL_SERIES_MISMATCH:6.7.1-android16-5-gabc"))
        assertTrue(mismatch.message!!.contains("known=[5.10,5.15,6.1,6.6,6.12,6.18]"))

        val unreadable = assertThrows(UltraKernelReleaseUnsupportedException::class.java) {
            flow.selectForRelease("")
        }
        assertEquals("ULTRA_KERNEL_RELEASE_UNSUPPORTED", unreadable.code)
        assertEquals("unreadable", unreadable.kernelRelease)
        assertTrue(unreadable.message!!.startsWith("ULTRA_KERNEL_RELEASE_UNSUPPORTED:unreadable:known=["))
    }

    @Test
    fun anAssetWhoseSpecBelongsToAnotherSeriesIsStillRefused() {
        // Same 6.6.89 directory, but the spec inside says 6.12: a digest-equal asset is not enough,
        // the series must agree. This is what keeps a series match from becoming "any spec goes".
        val text = String(UltraKernelReleaseFixtures.k22Bytes(), Charsets.UTF_8).replace(
            "#kernel=${UltraKernelReleaseFixtures.RELEASE}",
            "#kernel=6.12.23-android16-5-g188c695beb6d-ab14367805-4k",
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
        assertTrue(
            "a spec of another series must be the reason: ${selection.rejected}",
            selection.rejected.single().startsWith(
                "k22:ULTRA_KERNEL_ASSET_SPEC_RELEASE:k22:6.12:6.6:",
            ),
        )
    }

    @Test
    fun theLegacyManifestIsAlsoMatchedBySeries() {
        // The pre-release-index manifest is still consulted for 6.6; a 6.6.50 boot must find it,
        // and a 6.12 boot must not (different series is never a fallback).
        val catalog = UltraKernelCatalog.load(UltraKernelReleaseFixtures.legacyAssets())
        assertEquals(
            listOf("k22", "k21"),
            catalog.orderedForRelease("6.6.50-android15-8").map { it.id },
        )
        assertTrue(catalog.orderedForRelease("6.12.30-android16-5").isEmpty())
        // The manifest's own pinned release is untouched: ordering changed, the fact did not.
        assertEquals(listOf(UltraKernelReleaseFixtures.RELEASE), catalog.knownReleases())
    }

    @Test
    fun theDefaultAssetWinsInsideASeriesAndNewestBreaksTheTie() {
        // Neither directory is `default`: the newest release of the series comes first.
        val newest = UltraKernelReleaseCatalog.load(
            twoReleaseAssets(
                "6.6.50-android15-8" to false,
                "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k" to false,
            ),
        ).assetsFor("6.6.1-android15-8")
        assertEquals(2, newest.size)
        assertEquals(
            "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k",
            newest.first().kernelRelease,
        )

        // `default` beats newest - the user's rule is 「优选取 isDefault，其次取系列内最新」.
        val catalog = UltraKernelReleaseCatalog.load(
            twoReleaseAssets(
                "6.6.50-android15-8" to true,
                "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k" to false,
            ),
        )
        val byDefault = catalog.assetsFor("6.6.1-android15-8")
        assertEquals("6.6.50-android15-8", byDefault.first().kernelRelease)
        assertEquals(
            "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k",
            byDefault.last().kernelRelease,
        )
        // `asset(id)` resolves inside the series too, so an id from another patch level still works.
        assertEquals("k22", catalog.asset("6.6.1-android15-8", "k22")!!.id)
        assertNull(catalog.asset("6.12.1-android16-5", "k22"))
    }

    // -----------------------------------------------------------------------------------------
    // 4. The six series on the real boot samples (read-only; banner rewritten in memory only).
    // -----------------------------------------------------------------------------------------

    @Test
    fun everySeriesRendersItsOwnTierOnARealKernel() {
        val samples = listOf(
            SeriesSample("6.6", "OnePlusAce6_6.6", "6.6.50-android15-8", 16, false),
            SeriesSample("6.1", "OnePlus12_6.1", "6.1.60-android14-11", 16, false),
            SeriesSample("6.12", "OnePlus15_6.12", "6.12.30-android16-5", 17, false),
            SeriesSample("6.18", "OnePlus16_6.18", "6.18.5-android17-5", 17, false),
            SeriesSample("5.15", "OnePlus11_5.15", "5.15.99-android13-8", 15, true),
            SeriesSample("5.10", "OnePlus10_Pro_5.10", "5.10.77-android12-9", 15, true),
        )
        var checked = 0
        for (sample in samples) {
            val boot = File("../MapleSU_Reference/Boots", "${sample.directory}/boot.img")
            assumeTrue("${sample.series} boot sample not available", boot.isFile)
            val kernel = renamedKernel(
                UltraKernelPlan.readKernel(boot),
                sample.requested,
            )
            assertEquals(sample.requested, UltraKernelSpec.releaseOf(kernel))
            assertEquals(sample.series, KernelSeries.of(sample.requested))

            val plan = generator.generate(kernel)
            assertEquals(
                "${sample.series}: the plan must keep the real release, not the series",
                sample.requested,
                plan.kernelRelease,
            )
            assertNull(plan.credential)
            assertNull(plan.slots)
            val symbols = UltraKernelKallsymsTable.decode(kernel)
            val requestV2 = symbols.offsetOf("copy_from_user_nofault") != null &&
                symbols.offsetOf("copy_to_user_nofault") != null
            val expectedRecords = sample.records - 1 + if (requestV2) 1 else 0
            assertEquals(requestV2, plan.records.any { it.role == "request-v2" })
            assertEquals(expectedRecords, plan.records.size)
            val evidence = requireNotNull(plan.evidence)
            assertEquals(
                "${sample.series}: every record this kernel hosts must be generated",
                "$expectedRecords/$expectedRecords",
                evidence.summary,
            )
            if (sample.cfiSkipped) {
                // 5.10/5.15 do not export `report_cfi_failure`; its absence is the kernel's shape,
                // not a failed generation, and it is reported rather than counted as missing.
                assertEquals(listOf("report_cfi_failure+0x0"), evidence.skippedRoles)
            } else {
                assertTrue("${sample.series}: no optional record may be skipped", evidence.skippedRoles.isEmpty())
            }
            assertEquals(UltraKernelPlan.Origin.GENERATED, plan.origin)
            assertEquals(UltraKernelPlan.SupportLevel.EXPERIMENTAL, plan.supportLevel)
            assertEquals(64, plan.derived?.patchedKernelSha256?.length)
            checked++
        }
        assertTrue("no boot sample was available; the six-series run proved nothing", checked > 0)
    }

    @Test
    fun anUnknownSeriesUsesTheTargetFactsWhileTheCatalogueKeepsItsSeriesContract() {
        assumeTrue("6.6.89 boot sample not available", ace6.isFile)
        val original = UltraKernelPlan.readKernel(ace6)
        val baseline = generator.generate(original)
        val kernel = renamedKernel(original, "6.7.1-android18-0")
        val generated = generator.generate(kernel)
        assertEquals("6.7.1-android18-0", generated.kernelRelease)
        assertEquals(UltraKernelPlan.SupportLevel.EXPERIMENTAL, generated.supportLevel)
        assertEquals(baseline.layout.fields, generated.layout.fields)
        assertEquals(baseline.records.size, generated.records.size)
        baseline.records.zip(generated.records).forEach { (expected, actual) ->
            assertEquals(expected.role, actual.role)
            assertEquals(expected.offset, actual.offset)
            assertTrue(expected.original.contentEquals(actual.original))
            assertTrue(expected.replacement.contentEquals(actual.replacement))
        }

        // An explicit catalogue selection retains its separate asset contract.
        val flow = flow(UltraKernelReleaseFixtures.releaseAssets(UltraKernelReleaseFixtures.meta()))
        val refused = assertThrows(IllegalStateException::class.java) {
            flow.selectForRelease("6.7.1-android18-0")
        }
        assertTrue(refused.message!!.startsWith("ULTRA_KERNEL_SERIES_MISMATCH:6.7.1-android18-0"))
    }

    @Test
    fun historicalPackagedK22KeepsItsPinnedBytesAndDigest() {
        assumeTrue("6.6.89 boot sample not available", ace6.isFile)
        val kernel = UltraKernelPlan.readKernel(ace6)
        val spec = UltraKernelPlanFixtures.shippedSpec()
        val plan = UltraKernelPlan.fromSpec(spec, credential = UltraKernelPlanFixtures.activeCredential())
        assertEquals(UltraKernelPlan.Origin.PINNED_ASSET, plan.origin)
        assertEquals(16, plan.records.size)
        assertEquals(UltraKernelPlanFixtures.activeCredential(), plan.credential)
        assertTrue(plan.rebindable)
        val result = plan.apply(kernel)
        assertEquals(UltraKernelPlanFixtures.CORRECTED_K22_KERNEL_SHA256, result.derived.patchedKernelSha256)
        assertEquals(874, result.derived.changedBytes)
        assertEquals(0, result.derived.outsideSites)
        assertEquals(UltraKernelPlanFixtures.K22_RELEASE, plan.kernelRelease)
    }

    // -----------------------------------------------------------------------------------------
    // 5. The flow end to end: a 6.6.50 boot is patched with the 6.6.89 asset on a real image.
    // -----------------------------------------------------------------------------------------

    @Test
    fun aSameSeriesReleaseIsRegeneratedAndTheReceiptNamesTheActualBase() {
        assumeTrue("6.6.89 boot sample not available", ace6.isFile)
        val workspace = workspace()
        // A 6.6.50 boot: the real 6.6 kernel with only its release token renamed, rebuilt into a
        // boot image so the flow sees the release it would see on a device.
        val kernel = renamedKernel(UltraKernelPlan.readKernel(ace6), "6.6.50-android15-8")
        val boot = File(workspace, "boot-6.6.50.img")
        writeBootWithKernel(ace6, kernel, boot)

        val flow = UltraKernelFlow(workspace, UltraKernelFileAssets(assetsRoot))
        assertEquals("6.6.50-android15-8", flow.releaseOf(boot))

        val selection = requireNotNull(flow.selectForRelease("6.6.50-android15-8"))
        assertEquals("the 6.6 tier answers a 6.6.50 boot", "k22", selection.primary.id)

        val notices = ArrayList<String>()
        val preparation = flow.prepare(boot) { notices += it }
        assertTrue(preparation.generated)
        val candidate = requireNotNull(preparation.candidate)
        assertEquals("the candidate records the real release", "6.6.50-android15-8", candidate.kernelRelease)
        assertFalse(
            "the asset's pinned kernel digest belongs to 6.6.89, so this build derives its own",
            candidate.expectedPinned,
        )
        assertEquals(16, candidate.patches)
        val generated = preparation.variant as UltraKernelGeneratedAsset
        assertNull(generated.plan.credential)
        assertTrue(generated.plan.records.any { it.role == "request-v2" })
        assertTrue(
            "the substitution must be announced, never silent: $notices",
            notices.any { it.contains("根据目标内核生成") },
        )

        val receiptFile = File(workspace, "candidate-${requireNotNull(preparation.variant).id}.json")
        val receipt = Json.parseToJsonElement(receiptFile.readText()).jsonObject
        assertTrue(receipt.getValue("readbackVerified").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("seriesTemplate").jsonPrimitive.boolean)
        assertEquals("GENERATED", receipt.getValue("planOrigin").jsonPrimitive.content)
        assertFalse(receipt.getValue("expectedKernelPinned").jsonPrimitive.boolean)
        assertEquals("6.6.50-android15-8", receipt.getValue("kernelRelease").jsonPrimitive.content)
        assertEquals(
            "6.6.50-android15-8",
            receipt.getValue("assetKernelRelease").jsonPrimitive.content,
        )
        assertEquals(
            candidate.kernelSha256,
            receipt.getValue("patchedKernelSha256").jsonPrimitive.content,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private class SeriesSample(
        val series: String,
        val directory: String,
        val requested: String,
        val records: Int,
        val cfiSkipped: Boolean,
    )

    private fun flow(assets: UltraKernelAssetSource): UltraKernelFlow =
        UltraKernelFlow(workspace(), assets)

    /**
     * A catalogue with two directories of one series, each shipping the shipped k22 spec.
     *
     * The k22 spec still says `#kernel=6.6.89-…` inside the `6.6.50` directory, which is exactly the
     * point: the asset gate compares series, so a `6.6.50` asset may carry a `6.6.89` spec.
     */
    private fun twoReleaseAssets(
        first: Pair<String, Boolean>,
        second: Pair<String, Boolean>,
    ): UltraKernelAssetSource {
        val files = LinkedHashMap<String, ByteArray>()
        val spec = UltraKernelReleaseFixtures.k22Bytes()
        for ((release, isDefault) in listOf(first, second)) {
            val meta = """
                {"schema":1,"kernelRelease":"$release","source":"sample","verification":"verified",
                 "id":"k22","spec":"${UltraKernelReleaseFixtures.K22_SPEC}",
                 "specSha256":"${UltraKernelReleaseFixtures.specSha(spec)}","patchCount":18,
                 "baseKernelSha256":"${UltraKernelReleaseFixtures.BASE_KERNEL}",
                 "expectedKernelSha256":"${UltraKernelReleaseFixtures.K22_KERNEL}",
                 "expectedImageSize":${UltraKernelReleaseFixtures.IMAGE_SIZE},"ramdiskSize":0,
                 "default":$isDefault}
            """.trimIndent().replace("\n", "")
            files["ultra-kernels/releases/$release/meta.json"] = meta.toByteArray()
            files["ultra-kernels/releases/$release/${UltraKernelReleaseFixtures.K22_SPEC}"] = spec
        }
        return UltraKernelMemoryAssets(files)
    }

    /**
     * Rewrites the release token a kernel banner advertises, in place.
     *
     * The replacement is NUL padded to the old token's length, so the kernel image keeps its exact
     * size and every offset (it is never written to a partition; the sample stays untouched). Null
     * is a legal terminator for the banner scanner, so `releaseOf` sees exactly [replacement].
     */
    private fun renamedKernel(kernel: ByteArray, replacement: String): ByteArray {
        val current = UltraKernelSpec.releaseOf(kernel)
        check(current.isNotEmpty() && current != "AMBIGUOUS") {
            "the sample must carry a readable release, was '$current'"
        }
        val from = current.toByteArray(Charsets.US_ASCII)
        val to = replacement.toByteArray(Charsets.US_ASCII)
        check(to.size <= from.size) { "$replacement does not fit in $current" }
        val output = kernel.copyOf()
        var hits = 0
        var index = 0
        while (true) {
            val at = indexOf(output, from, index)
            if (at < 0) break
            to.copyInto(output, at)
            output.fill(0, at + to.size, at + from.size)
            index = at + from.size
            hits++
        }
        check(hits > 0) { "the release token of '$current' is not in the kernel" }
        return output
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        for (start in from..haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return -1
    }

    /** Rebuilds [source] around a modified raw [kernel], exactly the way the builder does. */
    private fun writeBootWithKernel(source: File, kernel: ByteArray, target: File) {
        val report = BootImageInspector.inspect(source)
        val container = File(target.parentFile, "kernel-modified.img")
        try {
            container.writeBytes(KernelCompression.encode(kernel, report.compression))
            BootImageRebuilder.rebuildV4(source, container, target)
        } finally {
            container.delete()
        }
        assertTrue("the rebuilt boot must be inspectable", target.isFile && target.length() > 0)
    }
}
