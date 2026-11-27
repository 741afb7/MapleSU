package dev.maplesu.manager.ultrakernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The GENERATED contract: what a locally generated plan may claim, and what it may not.
 *
 * These cases are the product rule, not an implementation detail: k22 is `verified` because it
 * booted a device, and no amount of local byte matching is allowed to produce that label again.
 */
class UltraKernelSpecGeneratorTest {

    private val generator = UltraKernelSpecGenerator()

    @Test
    fun `the generator uses the k22 recipe`() {
        assertEquals("k22", generator.recipeId)
    }

    @Test
    fun `generation refuses a kernel whose kallsyms cannot be walked`() {
        try {
            generator.generate(UltraKernelPlanFixtures.syntheticKernel())
            fail("a kernel with no kallsyms must not produce a plan")
        } catch (expected: UltraKernelGenerateException) {
            assertEquals(UltraKernelGenerateCodes.KALLSYMS, expected.code)
        }
    }

    @Test
    fun `generation diagnoses missing kallsyms on every target series`() {
        for (release in listOf("5.4.61-device", "6.6.89-device", "6.7.1-device")) {
            val kernel = ByteArray(4096)
            "Linux version $release\n".toByteArray(Charsets.US_ASCII).copyInto(kernel, 0x200)
            try {
                generator.generate(kernel)
                fail("missing target symbols must not produce a plan: $release")
            } catch (expected: UltraKernelGenerateException) {
                assertEquals(release, UltraKernelGenerateCodes.KALLSYMS, expected.code)
            }
        }
    }

    @Test
    fun `a plan that was never applied cannot become an asset`() {
        val rendered = keylessRendered().records
        val records = rendered.map { item ->
            UltraKernelPlan.PatchRecord(
                item.role, item.site.toLong(), item.original, item.replacement,
            )
        }
        val unapplied = UltraKernelPlan.fromRecords(
            UltraKernelPlanFixtures.K22_RELEASE, records,
        )
        assertNull(unapplied.derived)
        try {
            UltraKernelGeneratedAsset.of(unapplied, 4096, 0, "offset|orig|repl\n")
            fail("an asset must never be created from a plan that derived no digest")
        } catch (expected: UltraKernelGenerateException) {
            assertEquals(UltraKernelGenerateCodes.APPLY, expected.code)
        }
    }

    @Test
    fun `a generated asset is experimental and pins no image`() {
        val plan = keylessPlan()
        val specText = plan.specText()
        val asset = UltraKernelGeneratedAsset.of(plan, 4096, 128, specText)

        assertEquals(UltraKernelPatchAsset.VERIFICATION_EXPERIMENTAL, asset.verification)
        assertTrue(asset.experimental)
        assertEquals("$UltraKernelGenerateWarningPrefix:${asset.id}", asset.warning)
        assertEquals("generated", asset.source)
        assertEquals(UltraKernelGeneratedAsset.STATUS, asset.status)
        assertTrue(asset.ready)
        assertFalse(asset.debugOnly)

        // Nothing may be pinned that was not derived: an empty base/stock pin is the honest state,
        // and it switches off the builder's "known image" gate on purpose.
        assertEquals("", asset.knownBaseImageSha256)
        assertEquals("", asset.knownBaseImageBootSha256)
        assertEquals("", asset.specAsset)
        assertEquals(15, asset.patches)
        assertEquals(UltraKernelPlanFixtures.K22_RELEASE, asset.kernelRelease)

        val derived = plan.derived ?: error("fixture plan must be applied")
        assertEquals(derived.patchedKernelSha256, asset.expectedKernelSha256)
        assertEquals(KEYLESS_PATCHED_SHA256, asset.expectedKernelSha256)
        assertNull(plan.credential)
        assertNull(plan.slots)
        assertEquals(UltraKernelPlanFixtures.SYNTHETIC_BASE_SHA256, asset.baseKernelSha256)
        assertEquals(derived, asset.generated)

        // The id becomes a file name (`candidate-<id>.img`), so it must be filesystem safe.
        assertFalse(asset.id.contains(':'))
        assertFalse(asset.id.contains('/'))
        assertTrue(asset.id.startsWith("generated-"))
    }

    @Test
    fun `a generated plan never claims verified even when every site matched`() {
        val plan = keylessPlan()
        val evidence = plan.evidence ?: error("fixture plan carries no evidence")
        assertTrue("the fixture must be a full reference match", evidence.referenceMatch)
        assertEquals("15/15", evidence.summary)
        assertEquals(UltraKernelPlan.Origin.GENERATED, plan.origin)
        assertEquals(UltraKernelPlan.SupportLevel.EXPERIMENTAL, plan.supportLevel)
        assertTrue(plan.experimental)
    }

    /** A partial generated plan must fail before it reaches the image builder. */
    @Test
    fun `a plan missing generated records is refused`() {
        val rendered = keylessRendered().records
        assertEquals(15, rendered.size)
        val match = keylessRendered().effectiveRecipe.generatedCoverage(rendered.take(3))
        assertEquals(14, match.total)
        assertEquals(11, match.differingRoles.size)
        assertEquals(3, match.matching)
        assertFalse("an absent optional CFI site is excluded from missing required roles",
            match.differingRoles.contains("report_cfi_failure+0x0"))

        val evidence = UltraKernelPlan.SiteEvidence(
            match.matching, match.total, match.differingRoles, emptyList(),
        )
        assertFalse("a partial match is not a reference match", evidence.referenceMatch)
        try {
            requireFullSiteCoverage(evidence)
            fail("3/14 required sites must not become a patch plan")
        } catch (expected: UltraKernelGenerateException) {
            assertEquals(UltraKernelGenerateCodes.SITE_COVERAGE, expected.code)
            // UI 按 `SITE_*` 前缀选文案，所以码的形状是契约的一部分。
            assertTrue(expected.code.startsWith("ULTRA_KERNEL_GENERATE_SITE"))
            assertTrue("detail must quantify the coverage", expected.detail.startsWith("3/14 "))
            assertTrue(expected.detail.contains("differing="))
        }

        // The same gate must not reject the reference build, or it would reject k22 itself.
        requireFullSiteCoverage(UltraKernelPlan.SiteEvidence(15, 15, emptyList(), emptyList()))
        // A plan with no record is not a plan either.
        try {
            requireFullSiteCoverage(UltraKernelPlan.SiteEvidence(0, 0, emptyList(), emptyList()))
            fail("an empty plan must be refused")
        } catch (expected: UltraKernelGenerateException) {
            assertEquals(UltraKernelGenerateCodes.SITE_COVERAGE, expected.code)
        }
    }

    @Test
    fun `generated spec text states its origin and support level`() {
        val plan = keylessPlan()
        val text = plan.specText()
        assertTrue(text.contains("#origin=GENERATED"))
        assertTrue(text.contains("#supportLevel=experimental"))
        assertTrue(text.contains("#sites=15/15"))
        assertTrue(text.contains("#kernel=${UltraKernelPlanFixtures.K22_RELEASE}"))

        val parsed = UltraKernelSpec.parse(text)
        assertEquals(UltraKernelPlanFixtures.K22_RELEASE, parsed.kernelRelease)
        assertEquals(15, parsed.patches.size)
        assertTrue("current generated specs carry no historical credential", parsed.key.isEmpty())
        assertFalse(text.contains("#plan.slots=credential+path"))
        assertNull(plan.credential)
        assertNull(plan.slots)
    }

    @Test
    fun `the generated asset passes the same record gates as a shipped one`() {
        val plan = keylessPlan()
        val asset = UltraKernelGeneratedAsset.of(plan, 4096, 0, plan.specText())
        val kernel = UltraKernelPlanFixtures.syntheticKernel()
        val spec = UltraKernelSpec.parse(plan.specText())

        // Every generated original must be present in the base - the gate the builder opens
        // with, and the reason an already-patched base refuses instead of half-patching.
        assertTrue(spec.mismatched(kernel).isEmpty())
        val result = spec.apply(kernel)
        assertEquals(asset.expectedKernelSha256, UltraKernelSpec.sha256(result.kernel))
        assertEquals(asset.generated?.changedBytes, result.differingBytes)
        assertEquals(KEYLESS_CHANGED_BYTES, result.differingBytes)
        val independentlyPatched = kernel.copyOf()
        plan.records.forEach { it.replacement.copyInto(independentlyPatched, it.offset.toInt()) }
        assertTrue(independentlyPatched.contentEquals(result.kernel))
        assertEquals(kernel.indices.count { kernel[it] != independentlyPatched[it] }, result.differingBytes)
        assertEquals(0, result.outsideSites)

        val patched = kernel.copyOf()
        val site = plan.records.first().offset.toInt()
        patched[site] = (patched[site].toInt() xor 1).toByte()
        assertNotNull(spec.mismatched(patched))
        assertEquals(1, spec.mismatched(patched).size)
    }

    private fun keylessRendered(): UltraKernelRecipe.RenderResult {
        val kernel = UltraKernelPlanFixtures.syntheticKernel()
        val deployment = UltraKernelDeploymentNamespace.decode(
            byteArrayOf(0x4d, 0x53, 0x44, 0x4e, 1, 0, 24, 0) + "ABCDEFGHIJKLMNOP".toByteArray())
        return UltraKernelPlanFixtures.recipe().renderDetailed(UltraKernelPlanFixtures.symbols(kernel.size),
            kernel, deploymentNamespace = deployment)
    }

    private fun keylessPlan(): UltraKernelPlan {
        val kernel = UltraKernelPlanFixtures.syntheticKernel()
        val rendered = keylessRendered()
        val coverage = rendered.effectiveRecipe.generatedCoverage(rendered.records)
        val records = rendered.records.map {
            UltraKernelPlan.PatchRecord(it.role, it.site.toLong(), it.original, it.replacement)
        }
        val plan = UltraKernelPlan.fromRecords(UltraKernelPlanFixtures.K22_RELEASE, records,
            evidence = UltraKernelPlan.SiteEvidence(coverage.matching, coverage.total, coverage.differingRoles, emptyList()),
            baseKernelSha256 = UltraKernelSpec.sha256(kernel), layout = rendered.layout)
        return plan.withDerived(plan.apply(kernel).derived)
    }

    private companion object {
        // Fixed public namespace above; this is separate from the legacy credential oracle.
        const val KEYLESS_PATCHED_SHA256 = "c96195a94a5e6677f334df530762bd4888d659e28788195765fa533589e05f98"
        const val KEYLESS_CHANGED_BYTES = 867
        /** `UltraKernelPatchAsset.WARNING_PREFIX` is on the interface, not the generated asset. */
        const val UltraKernelGenerateWarningPrefix = "ULTRA_KERNEL_ASSET_EXPERIMENTAL"
    }
}
