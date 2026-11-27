package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Cross-release proof on the real boot samples (read-only, never flashed).
 *
 * One test per supported release asserts full coverage of the target's effective recipe. Optional
 * CFI and allocator adapters change the record count; the public deployment replaces the historical
 * credential record without giving up complete site coverage or native function preservation.
 *
 * `refOrig` is recorded rather than asserted-away: it is the count of sites that still carry the
 * *reference* build's pristine bytes, and it is expected to be low on every non-6.6.89 kernel. Seeing
 * it low while every effective site is generated proves that the code is
 * the kernel's own now, not 6.6.89's bytes moved to a new address.
 *
 * The samples are large workspace artefacts, not test resources, so each test is skipped when its
 * boot image is absent; the pure-JVM tests do not depend on them.
 */
class UltraKernelCrossReleaseTest {

    private val generator = UltraKernelSpecGenerator()

    @Test
    fun `6_6_89 generates current keyless code and preserves native functions`() {
        val plan = generate("6.6.89", "OnePlusAce6_6.6") ?: return
        val evidence = requireNotNull(plan.evidence)
        assertTrue("no optional record may be skipped here", evidence.skippedRoles.isEmpty())
        val exec = plan.records.single { it.role == "do_execveat_common+0x0" }
        assertEquals("native hook offset",
            UltraKernelPlanFixtures.K22_SYMBOL_BASE.getValue("do_execveat_common") + 4L, exec.offset)
        assertEquals(0xD101C3FF.toInt(), UltraKernelA64.le32Bits(exec.original, 0))
        assertTrue("original function must remain callable", plan.records.filter { it.cave != null }
            .all { it.shiftedFrom != null })
    }

    @Test
    fun `6_1_25 generates every applicable keyless site`() {
        val plan = generate("6.1.25", "OnePlus12_6.1") ?: return
        val evidence = requireNotNull(plan.evidence)
        // The kernel is not the reference build: most pristine bytes differ, and that is fine now.
        assertTrue(
            "6.1.25 must not look like the reference build (was ${evidence.referenceOrigMatch})",
            evidence.referenceOrigMatch != evidence.summary,
        )
        assertTrue("a full kernel digest must be derived", (plan.derived?.patchedKernelSha256 ?: "").length == 64)
    }

    @Test
    fun `6_12_23 renders keyless sites at the actual function boundaries`() {
        val plan = generate("6.12.23", "OnePlus15_6.12") ?: return
        // This fixture also has an odd symbol count (125417). The correctly paired die
        // spans 776 bytes; the old padding bug paired it with its shorter successor.
        val gate = plan.records.first { it.role == "die+0x0" }
        assertEquals(UltraKernelBodies.EXECVE_GATE_BYTES, gate.replacement.size)
        assertEquals("original die entry", 0x2D16CL, gate.shiftedFrom)
        assertTrue("the original die remains callable", gate.offset != gate.shiftedFrom)
        val caveBytes = if (plan.records.any { it.role == "request-v2" })
            UltraKernelRequestV2.CAVE_OFFSET + UltraKernelRequestV2.BYTES
        else UltraKernelBodies.EXECVE_GATE_BYTES + 16 + 12
        assertTrue(plan.records.filter { it.cave == "die" }.all {
            it.offset + it.length <= gate.offset + caveBytes
        })
    }

    @Test
    fun `5_10 generates without the CFI record it does not have`() {
        val plan = generate("5.10", "OnePlus10_Pro_5.10") ?: return
        val evidence = requireNotNull(plan.evidence)
        assertEquals("report_cfi_failure is absent on 5.10, so the CFI stub is skipped", listOf("report_cfi_failure+0x0"), evidence.skippedRoles)
        assertTrue(plan.records.none { it.role == "report_cfi_failure+0x0" })
    }

    /**
     * 5.15 has no CFI either, so it takes the same path as 5.10 with the CFI stanza skipped.
     *
     * Unlike 6.12/6.18 it still exports `module_alloc`/`module_memfree` under their original names,
     * so no alias is needed - the alias table only applies where the rename actually happened.
     */
    @Test
    fun `5_15 generates without the CFI record it does not have`() {
        val plan = generate("5.15", "OnePlus11_5.15") ?: return
        val evidence = requireNotNull(plan.evidence)
        assertEquals(listOf("report_cfi_failure+0x0"), evidence.skippedRoles)
        assertTrue(plan.records.none { it.role == "report_cfi_failure+0x0" })
        // Its own build, not the reference one - and the `execve-gate` cave still fits.
        assertTrue(
            "5.15 must not look like the reference build (was ${evidence.referenceOrigMatch})",
            evidence.referenceOrigMatch != evidence.summary,
        )
        assertEquals(UltraKernelBodies.EXECVE_GATE_BYTES, plan.records.first { it.role == "die+0x0" }.replacement.size)
    }

    /**
     * 6.18.21 is the newest release in the sample set and needs no new mechanism, which is the
     * interesting part: its `execmem_alloc` needs the same two-argument adapter as 6.12,
     * while `execmem_free` keeps the one-argument contract. Both accessor shapes are still
     * `mrs sp_el0` + an immediate load - only the displacements moved.
     *
     * The execve hook separately verifies the PACIASP instruction it displaces; BTI on other
     * kernel APIs does not change that entry contract.
     */
    @Test
    fun `6_18_21 generates every applicable keyless site`() {
        val plan = generate("6.18.21", "OnePlus16_6.18") ?: return
        val evidence = requireNotNull(plan.evidence)
        assertTrue(
            "6.18.21 must not look like the reference build (was ${evidence.referenceOrigMatch})",
            evidence.referenceOrigMatch != evidence.summary,
        )
        assertTrue("at least one collapsed cave must move", plan.records.any { it.shiftedFrom != null })
    }

    private fun generate(label: String, directory: String): UltraKernelPlan? {
        val boot = File("../MapleSU_Reference/Boots/$directory/boot.img")
        assumeTrue("$label boot sample not available", boot.isFile)
        val plan = generator.generate(boot)
        assertNotNull("$label must produce a plan", plan)
        assertEquals(UltraKernelPlan.Origin.GENERATED, plan.origin)
        assertEquals(UltraKernelPlan.SupportLevel.EXPERIMENTAL, plan.supportLevel)
        assertTrue("current generation must be keyless", plan.credential == null && plan.slots == null)
        assertTrue("native panic must not be reserved for a credential", plan.records.none { it.role == "panic+0x0" })
        val evidence = requireNotNull(plan.evidence)
        assertEquals("every effective site must be generated", evidence.total, evidence.matching)
        assertEquals("evidence covers the complete plan", plan.records.size, evidence.total)
        assertTrue(evidence.differingRoles.isEmpty())
        assertTrue(evidence.residue.isEmpty())
        val kernel = UltraKernelPlan.readKernel(boot)
        val applied = plan.apply(kernel)
        assertEquals(0, applied.derived.outsideSites)
        assertEquals(UltraKernelSpec.sha256(kernel), plan.baseKernelSha256)
        assertEquals(UltraKernelSpec.sha256(applied.kernel), plan.derived?.patchedKernelSha256)
        val panic = requireNotNull(UltraKernelKallsymsTable.decode(kernel).offsetOf("panic"))
        assertTrue("native panic bytes remain intact", kernel.copyOfRange(panic, panic + UltraKernelCredential.LENGTH)
            .contentEquals(applied.kernel.copyOfRange(panic, panic + UltraKernelCredential.LENGTH)))
        return plan
    }
}
