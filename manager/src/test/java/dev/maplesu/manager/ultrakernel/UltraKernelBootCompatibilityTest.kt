package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real RAW/LZ4 boot fixtures; offsets were cross-checked with the native symbol decoder. */
@RunWith(Parameterized::class)
class UltraKernelBootCompatibilityTest(
    private val path: String,
    private val kernelSha256: String,
    private val die: Int,
    private val execve: Int,
    private val avc: Int,
) {
    @Test
    fun generatedHooksUseActualFunctionBoundariesAndPreserveTheImageHeader() {
        val boot = File("../$path")
        assumeTrue("boot fixture absent: $path", boot.isFile)
        val kernel = UltraKernelPlan.readKernel(boot)
        assertEquals("fixture identity", kernelSha256, UltraKernelSpec.sha256(kernel))
        val symbols = UltraKernelKallsymsTable.decode(kernel)
        assertEquals(0, symbols.offsetOf("_text"))
        assertEquals(die, symbols.offsetOf("die"))
        assertEquals(execve, symbols.offsetOf("do_execveat_common"))
        assertEquals(avc, symbols.offsetOf("avc_denied"))

        val plan = UltraKernelSpecGenerator().generate(kernel)
        val exec = plan.records.single { it.role == "do_execveat_common+0x0" }
        assertEquals(execve + 4L, exec.offset)
        assertArrayEquals(kernel.copyOfRange(exec.offset.toInt(), exec.offset.toInt() + 4), exec.original)
        assertEquals(UltraKernelA64.PACIA1716,
            UltraKernelA64.le32Bits(plan.apply(kernel).kernel, execve))
        val tail = plan.records.single { it.role == "avc_denied+0x28" }
        assertEquals(UltraKernelA64.RET, UltraKernelA64.le32Bits(tail.original, 0))
        if (path.contains("@llopjky")) {
            val gate = plan.records.single { it.role == "die+0x0" }
            assertTrue("5.10 static-key sites must retain their original code", gate.offset != die.toLong())
        }
        val applied = plan.apply(kernel)
        assertEquals(0, applied.derived.outsideSites)
        val textStart = requireNotNull(symbols.offsetOf("_stext"))
        assertArrayEquals(kernel.copyOfRange(0, textStart), applied.kernel.copyOfRange(0, textStart))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun samples(): Collection<Array<Any>> = listOf(
            arrayOf<Any>("BUG_Fix/11.04/@llopjky/Original_boot.img",
                "34ca7f3412a65fc048878adfdbf54051ced024b39a01f9d112ec264cceb4c821",
                559792, 5731096, 9415616),
            arrayOf<Any>("Boot_Research/ACE5至尊boot.img",
                "eda66d36df4936f7689ff6f7ebffad81726790bff39efbffae32332ac97cc4cd",
                135948, 4079344, 6554344),
            arrayOf<Any>("Boot_Research/._boot/k70uboot.img",
                "63eda65a09019d824e41258b8aa2e53333bfb7178e7fee93a8f86355a9b08fff",
                143080, 3818528, 6275544),
            arrayOf<Any>("Boot_Research/Jeannette/boot.img",
                "ddecb86ddcd2f9ab3e27f9f5f051083af19bf89c7575779161ca6aa942a5129a",
                3398760, 7102428, 1775740),
            arrayOf<Any>("Boot_Research/北/Original.img",
                "e14c763b7fef1a4055e540d0a3ef69dd99525c5dd0d54750fa87991659425d90",
                136488, 4092420, 6554064),
            arrayOf<Any>("Boot_Research/哈/boot.img",
                "791fb1f55b765e3644329d7fd9f4193c8a5e99d2924db61d42bbc9e1cb07de41",
                2217676, 4354056, 386604),
            arrayOf<Any>("Boot_Research/沧/boot.img",
                "e47ec8fecfcaca4fe80ccc020af242d956225e09441db632db2190a89c522aa5",
                184728, 4515336, 7101320),
            arrayOf<Any>("Boot_Research/簌/boot.img",
                "1b79cadcee836a77c7c4d918f53bc24f7646dd216324de8910b00e50473c1bed",
                136348, 4024640, 6512176),
            arrayOf<Any>("MapleSU_Reference/Boots/Oneplus_Ace6_Plus_6.12/boot.img",
                "6679e3fbc59c802e22e35c4ef87eefe5c0e7d0e79e37e294ef58324d457e50fb",
                3420160, 7104720, 1489140),
        )
    }
}

