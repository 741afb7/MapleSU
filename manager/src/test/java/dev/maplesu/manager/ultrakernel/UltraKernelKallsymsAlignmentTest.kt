package dev.maplesu.manager.ultrakernel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraKernelKallsymsAlignmentTest {
    @Test
    fun `odd symbol count does not consume the padding before relative base`() {
        val kernel = y700Kernel()
        val symbols = UltraKernelKallsymsTable.decode(kernel)
        // 110447 s32 offsets start at 0x14CE260; their last word is followed by
        // four alignment bytes before the u64 relative base at 0x153A020.
        // Deriving the start from base-count*4 skips the first real offset.
        val expected = mapOf(
            "_text" to 0,
            "_stext" to 0x10000,
            "__do_softirq" to 0x10210,
            "die" to 0x21528,
            "arm64_notify_die" to 0x21A18,
            "free_initmem" to 0x38DD0,
            "do_execveat_common" to 0x3E7204,
            "avc_denied" to 0x6401D0,
            "panic" to 0x1068D60,
        )
        expected.forEach { (name, offset) ->
            assertEquals("symbol address shifted: $name", offset, symbols.offsetOf(name))
        }
    }

    @Test
    fun `Y700 generation hooks the real execve and AVC functions`() {
        val kernel = y700Kernel()
        val plan = UltraKernelSpecGenerator().generate(kernel)
        val exec = plan.records.single { it.role == "do_execveat_common+0x0" }
        assertEquals("hook retains the native PAC/SCS entry", 0x3E7208L, exec.offset)
        assertArrayEquals(kernel.copyOfRange(0x3E7208, 0x3E720C), exec.original)
        assertEquals(UltraKernelA64.PACIA1716,
            UltraKernelA64.le32Bits(plan.apply(kernel).kernel, 0x3E7204))
        val avc = plan.records.single { it.role == "avc_denied+0x28" }
        assertEquals("AVC tail call must replace the real function's return", UltraKernelA64.RET,
            UltraKernelA64.le32Bits(avc.original, 0))
        assertEquals(0, plan.derived!!.outsideSites)
    }

    @Test
    fun `manager flow rebuilds Y700 without patching the neighbouring functions`() {
        val original = y700Kernel()
        val workspace = Files.createTempDirectory("ultrakernel-y700-alignment-").toFile()
        try {
            val flow = UltraKernelFlow(workspace, UltraKernelFileAssets(File("src/main/assets")))
            val prepared = flow.prepare(File("../Boot_Research/北/Original.img"))
            assertTrue(prepared.generated)
            val patched = UltraKernelPlan.readKernel(requireNotNull(prepared.candidate).image)
            for ((start, end) in listOf(0 to 0x10000, 0x3E74C8 to 0x3E74E8,
                0x640250 to 0x64033C, 0x38E44 to 0x38E8C)) {
                assertArrayEquals("neighbouring bytes must remain original at 0x${start.toString(16)}",
                    original.copyOfRange(start, end), patched.copyOfRange(start, end))
            }
            assertEquals(UltraKernelA64.PACIA1716, UltraKernelA64.le32Bits(patched, 0x3E7204))
            val branch = UltraKernelA64.le32Bits(patched, 0x3E7208)
            assertEquals(0x14000000, branch and 0xFC000000.toInt())
            val plan = UltraKernelSpecGenerator().generate(original)
            val target = 0x3E7208L + UltraKernelA64.signExtend((branch and 0x03FFFFFF).toLong(), 26) * 4
            assertEquals("execve must enter the actual save-frame helper", plan.records.single { it.role == "arm64_notify_die+0x0" }.offset + 28, target)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun y700Kernel(): ByteArray {
        val boot = File("../Boot_Research/北/Original.img")
        assumeTrue("Y700 original boot fixture absent", boot.isFile)
        val kernel = UltraKernelPlan.readKernel(boot)
        assertEquals("fixture identity", "e14c763b7fef1a4055e540d0a3ef69dd99525c5dd0d54750fa87991659425d90",
            UltraKernelSpec.sha256(kernel))
        return kernel
    }
}
