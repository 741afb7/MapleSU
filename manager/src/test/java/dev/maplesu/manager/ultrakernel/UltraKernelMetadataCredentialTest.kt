package dev.maplesu.manager.ultrakernel

import org.junit.Assert.*
import org.junit.Test

class UltraKernelMetadataCredentialTest {
    @Test fun randomDeviceIdentityIsNotAnSdkMetadataLocator() {
        assertFalse(UltraKernelCredential.of("Aa1".repeat(16)).sdkMetadataValid)
        assertEquals(-9139764, UltraKernelPlanFixtures.credential().metadataDisplacement)
        assertTrue(UltraKernelPlanFixtures.credential().sdkMetadataValid)
    }

    @Test fun oversizedLocatorsNeverWrapIntoValidSmallKernelOffsets() {
        // These encode magnitudes beyond 32 bits, which previously narrowed to +/-4.
        for (value in listOf(
            "AIHAAAAAAAAAAAANAAAAAAAAAAAAAAAAAAAAAAAAAAAEhhSQ",
            "AIHAAAAAAAAAAAAFAAAAAAAAAAAAAAAAAAAAAAAAAAAEhhSQ",
            "AIGAAAAAAAAAAAANAAAAAAAAAAAAAAAAAAAAAAAAAAAEhhSQ",
        )) {
            val credential = UltraKernelCredential.of(value)
            assertNull(credential.metadataDisplacement)
            assertFalse(credential.sdkMetadataValid)
        }
    }

    @Test fun bindingPreservesEntropyAndIsStableAcrossRetries() {
        val seed = UltraKernelCredential.of("Aa1".repeat(16))
        for (delta in listOf(-9188272, -9191328, -4, 4, 134217724, -134217724)) {
            val bound = seed.withMetadataDisplacement(delta)
            assertEquals(delta, bound.metadataDisplacement)
            assertTrue(bound.sdkMetadataValid)
            assertEquals(bound, bound.withMetadataDisplacement(delta))
            assertEquals(seed.copySecret().copyOfRange(16, 42).toList(),
                bound.copySecret().copyOfRange(16, 42).toList())
        }
    }

    @Test fun explicitLegacySdkLocatorMatchesTheFinalPlacedEnvelopeOnTheReportedImage() {
        val boot = java.io.File("../Boot_Research/簌/boot.img")
        org.junit.Assume.assumeTrue(boot.isFile)
        val kernel = UltraKernelPlan.readKernel(boot)
        val plan = legacyPlan(kernel, UltraKernelCredential.of("Aa1".repeat(16)))
        val credential = requireNotNull(plan.credential)
        assertNotNull("SDK metadata delta must be encoded in the generated identity", credential.metadataDisplacement)
        val envelope = plan.records.single { it.role == "free_initmem+0x0" }
        val executor = plan.records.single { it.role == "__drm_printfn_coredump+0x0" }
        val call = executor.replacement.indices.step(4).single { at ->
            UltraKernelA64.le32Bits(executor.replacement, at) == UltraKernelA64.blr(10)
        }
        val metadata = executor.offset + call + credential.metadataDisplacement!!
        assertEquals(envelope.offset + 4, metadata)
        val patched = plan.apply(kernel).kernel
        assertEquals(UltraKernelA64.blr(10), UltraKernelA64.le32Bits(patched, executor.offset.toInt() + call))
        assertEquals(metadata, UltraKernelA64.le32Bits(patched, metadata.toInt()).toLong())
        val spec = UltraKernelSpec.parse(plan.specText(credential))
        assertEquals(credential, UltraKernelCredential.of(spec.key))
        assertTrue(spec.apply(kernel).kernel.contentEquals(patched))
    }

    @Test fun aBoundPlanReencodesNewCredentialsWithoutLosingItsMetadataBinding() {
        val boot = java.io.File("../Boot_Research/簌/boot.img")
        org.junit.Assume.assumeTrue(boot.isFile)
        val plan = legacyPlan(UltraKernelPlan.readKernel(boot), UltraKernelCredential.of("Aa1".repeat(16)))
        val rebound = plan.rebind(UltraKernelCredential.of("Bb2".repeat(16)))
        assertEquals(plan.credential!!.metadataDisplacement, rebound.credential!!.metadataDisplacement)
        assertNotEquals(plan.credential, rebound.credential)
        val path = rebound.credential!!.initPath.toByteArray()
        rebound.slots!!.initPaths.forEach { slot ->
            assertArrayEquals(path, rebound.records[slot.recordIndex].replacement.copyOfRange(
                slot.offsetInRecord, slot.offsetInRecord + path.size))
        }
    }

    private fun legacyPlan(kernel: ByteArray, credential: UltraKernelCredential): UltraKernelPlan {
        val symbols = UltraKernelExecveEntry.resolve(UltraKernelKallsymsTable.decode(kernel), kernel)
        val rendered = UltraKernelRecipe.k22().renderDetailed(symbols, kernel, credential,
            preservedMetadataDisplacement = credential.metadataDisplacement)
        val envelope = rendered.records.single { it.role == "free_initmem+0x0" }
        val executor = rendered.records.single { it.role == "__drm_printfn_coredump+0x0" }
        val displacement = credential.metadataDisplacement ?: Math.toIntExact(
            envelope.site.toLong() + 4 - executor.site - UltraKernelBodies.EXECUTOR_CALL_OFFSET)
        val records = rendered.records.map {
            UltraKernelPlan.PatchRecord(it.role, it.site.toLong(), it.original, it.replacement)
        }
        val plan = UltraKernelPlan.fromRecords(UltraKernelSpec.releaseOf(kernel), records,
            credential = credential, baseKernelSha256 = UltraKernelSpec.sha256(kernel),
            sdkMetadataDisplacement = displacement, layout = rendered.layout).rebind(credential)
        return plan.withDerived(plan.apply(kernel).derived)
    }
}
