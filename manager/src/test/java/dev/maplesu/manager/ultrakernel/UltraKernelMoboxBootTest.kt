package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The reported 5.15 boot: check bytes the loader and the SDK actually consume. */
class UltraKernelMoboxBootTest {
    @Test
    fun legacyCfiFunctionsKeepTheirOriginalCodeAndLandingPads() {
        val (kernel, symbols, plan) = fixture()
        assertTrue(symbols.offsetOf("__cfi_check") != null)
        val patched = plan.apply(kernel).kernel
        for (cave in plan.records.mapNotNull { it.cave }.distinct()) {
            val base = requireNotNull(symbols.offsetOf(cave))
            val end = requireNotNull(symbols.nextOffset(base))
            assertArrayEquals("original function must remain callable: $cave",
                kernel.copyOfRange(base, end), patched.copyOfRange(base, end))
        }
    }

    @Test
    fun loaderRewriteSitesAndExceptionEntriesStayOutsideAllPatchRecords() {
        val (kernel, symbols, plan) = fixture()
        val patched = plan.apply(kernel).kernel
        for ((start, end) in listOf("__entry_text_start" to "__entry_text_end",
            "__irqentry_text_start" to "__irqentry_text_end")) {
            val lo = requireNotNull(symbols.offsetOf(start))
            val hi = requireNotNull(symbols.offsetOf(end))
            assertArrayEquals(start, kernel.copyOfRange(lo, hi), patched.copyOfRange(lo, hi))
        }
        for ((start, end, stride) in listOf(
            Triple("__start___jump_table", "__stop___jump_table", 16),
            Triple("__alt_instructions", "__alt_instructions_end", 12),
        )) {
            val lo = requireNotNull(symbols.offsetOf(start))
            val hi = requireNotNull(symbols.offsetOf(end))
            for (at in lo until hi step stride) {
                val site = at + UltraKernelA64.le32Bits(kernel, at)
                val length = if (stride == 16) 4 else kernel[at + 10].toInt() and 0xff
                assertArrayEquals("runtime patch at 0x${site.toString(16)}",
                    kernel.copyOfRange(site, site + length), patched.copyOfRange(site, site + length))
                if (stride == 12) {
                    val replacement = at + 4 + UltraKernelA64.le32Bits(kernel, at + 4)
                    val replacementLength = kernel[at + 11].toInt() and 0xff
                    assertArrayEquals("alternative source at 0x${replacement.toString(16)}",
                        kernel.copyOfRange(replacement, replacement + replacementLength),
                        patched.copyOfRange(replacement, replacement + replacementLength))
                }
            }
        }
    }

    @Test
    fun bothSdkEnvelopesDescribeTheLocalAvcAndItsActualReturn() {
        val (kernel, symbols, plan) = fixture()
        val avc = requireNotNull(symbols.offsetOf("avc_denied"))
        val hook = plan.records.single { it.role == "avc_denied+0x28" }
        assertEquals(0x8ac948, avc)
        assertEquals(0x8ac9d0L, hook.offset)
        for ((role, prefix) in listOf("free_initmem+0x0" to 4,
            "__traceiter_kvm_userspace_exit+0x70" to 0)) {
            val bytes = plan.records.single { it.role == role }.replacement
            assertEquals("AVC address in $role", avc, UltraKernelA64.le32Bits(bytes, prefix + 42))
            val count = (bytes[prefix + 46].toInt() and 0xff) or
                ((bytes[prefix + 47].toInt() and 0xff) shl 8)
            assertEquals("SDK return address in $role", hook.offset, avc.toLong() + count * 4 - 4)
        }
        val envelope = plan.records.single { it.role == "free_initmem+0x0" }
        val executor = plan.records.single { it.role == "__drm_printfn_coredump+0x0" }
        val call = executor.replacement.indices.step(4).single { at ->
            UltraKernelA64.le32Bits(executor.replacement, at) == UltraKernelA64.blr(10)
        }
        assertEquals(null, plan.credential)
        assertEquals(null, plan.slots)
        val metadata = envelope.offset + 4
        assertEquals(envelope.offset + 4, metadata)
        val patched = plan.apply(kernel).kernel
        assertEquals(UltraKernelA64.blr(10), UltraKernelA64.le32Bits(patched, executor.offset.toInt() + call))
        assertEquals(metadata, UltraKernelA64.le32Bits(patched, metadata.toInt()).toLong())
    }

    private fun fixture(): Triple<ByteArray, UltraKernelKallsymsTable, UltraKernelPlan> {
        assumeTrue("Mobox boot fixture absent", boot.isFile)
        return decoded
    }

    companion object {
        private val boot = File("../BUG_Fix/11.04/Mobox/Original.img")
        private val decoded by lazy {
            val kernel = UltraKernelPlan.readKernel(boot)
            assertEquals("f5acfde209dbc87f86ed5f0ecff1dc52d3b51638cc30932fd5cab38e7f71afee",
                UltraKernelSpec.sha256(kernel))
            Triple(kernel, UltraKernelKallsymsTable.decode(kernel),
                UltraKernelSpecGenerator().generate(kernel))
        }
    }
}
