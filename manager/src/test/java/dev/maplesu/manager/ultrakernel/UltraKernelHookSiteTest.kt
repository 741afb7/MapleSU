package dev.maplesu.manager.ultrakernel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraKernelHookSiteTest {
    @Test
    fun `framed AVC hooks use the actual return instruction rather than the reference delta`() {
        val kernel = ByteArray(256)
        val base = 64
        // A live stack load at the old +0x28; the target's unwind ends at +0x48.
        UltraKernelA64.writeLe32(kernel, base + 0x28, 0xF9400BAB)
        UltraKernelA64.writeLe32(kernel, base + 0x44, 0xD50323BF)
        UltraKernelA64.writeLe32(kernel, base + 0x48, UltraKernelA64.RET.toLong())
        val allocation = UltraKernelCaveAllocator.allocate(avcRecipe(),
            FakeSymbols(mapOf("avc_denied" to base, "next" to 192), kernel.size), kernel)
        assertEquals(base + 0x48, allocation.siteByRole.getValue("avc_denied+0x28"))
    }

    @Test
    fun `a missing return never falls back to overwriting an arbitrary instruction`() {
        val failure = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelCaveAllocator.allocate(avcRecipe(),
                FakeSymbols(mapOf("avc_denied" to 64, "next" to 192), 256), ByteArray(256))
        }
        assertEquals(UltraKernelGenerateCodes.SITE, failure.code)
        assertEquals("HOOK_RETURN_NOT_FOUND:avc_denied", failure.detail)
    }

    @Test
    fun `6_1_141 denial hook replaces the return after its frame and PAC unwind`() {
        val boot = bootFixture()
        assumeTrue("boot fixture absent", boot.isFile)
        val plan = UltraKernelSpecGenerator().generate(boot)
        val hook = plan.records.single { it.role == "avc_denied+0x28" }
        assertEquals("hook must replace RET, not a live frame load", UltraKernelA64.RET,
            UltraKernelA64.le32Bits(hook.original, 0))
        assertEquals("deny return follows the kernel's own unwind", 0x5E674L, hook.offset)
    }

    @Test
    fun `manager catalogue entry rebuilds 6_1_141 with the corrected hook and intact headers`() {
        val boot = bootFixture()
        assumeTrue("boot fixture absent", boot.isFile)
        val workspace = Files.createTempDirectory("ultrakernel-avc-return-").toFile()
        try {
            val flow = UltraKernelFlow(workspace, UltraKernelFileAssets(File("src/main/assets")))
            val prepared = flow.prepare(boot)
            assertTrue(prepared.generated)
            val candidate = requireNotNull(prepared.candidate)
            val original = UltraKernelPlan.readKernel(boot)
            val patched = UltraKernelPlan.readKernel(candidate.image)
            assertArrayEquals(original.copyOfRange(0, 0x10000), patched.copyOfRange(0, 0x10000))
            assertEquals("the old stack load must remain intact", UltraKernelA64.le32Bits(original, 0x5E654),
                UltraKernelA64.le32Bits(patched, 0x5E654))
            val branch = UltraKernelA64.le32Bits(patched, 0x5E674)
            assertEquals(0x14000000, branch and 0xFC000000.toInt())
            val plan = UltraKernelSpecGenerator().generate(original)
            val target = 0x5E674L + UltraKernelA64.signExtend((branch and 0x03FFFFFF).toLong(), 26) * 4
            assertEquals("AVC must tail-call the relocated helper", plan.records.single { it.role == "drm_dev_printk+0x0" }.offset + 36, target)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun avcRecipe() = UltraKernelRecipe.parse("""{
        "schema":1,"id":"avc-return","records":[{
            "role":"avc_denied+0x28","site":{"symbol":"avc_denied","delta":40},
            "length":4,"space":"symbol"
        }]
    }""")

    private fun bootFixture() = File(System.getProperty("maplesu.ultrakernel.haBoot", "../Boot_Research/哈/boot.img"))
}
