package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraKernelAbiTest {
    @Test
    fun `execmem kernels adapt the size argument before calling the two argument API`() {
        val boot = File("../MapleSU_Reference/Boots/OnePlus15_6.12/boot.img")
        assumeTrue(boot.isFile)
        val kernel = UltraKernelPlan.readKernel(boot)
        val symbols = UltraKernelKallsymsTable.decode(kernel)
        val plan = UltraKernelSpecGenerator().generate(kernel)
        val adapter = plan.records.singleOrNull { it.role == "module_alloc-adapter" }
        assertNotNull("execmem_alloc(type, size) needs an ABI adapter", adapter)
        adapter!!
        assertEquals(UltraKernelA64.movReg(1, 0), UltraKernelA64.le32Bits(adapter.replacement, 0))
        assertEquals(UltraKernelA64.encodeMovImm(0, 0, sf = 0).single(),
            UltraKernelA64.le32Bits(adapter.replacement, 4))
        assertEquals(symbols.offsetOf("execmem_alloc")!!.toLong(), branchTarget(adapter.offset + 8,
            UltraKernelA64.le32Bits(adapter.replacement, 8)))
        val tail = plan.records.single { it.role == "die+0xD4" }
        assertEquals(adapter.offset, branchTarget(tail.offset + 8,
            UltraKernelA64.le32Bits(tail.replacement, 8)))
    }

    @Test
    fun `module_free is not a one argument module_memfree alias`() {
        val symbols = FakeSymbols(mapOf("module_free" to 64), 512)
        val failure = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelSymbolResolver(symbols).require("module_memfree")
        }
        assertEquals(UltraKernelGenerateCodes.TARGET_SYMBOL_MISSING, failure.code)
    }

    @Test
    fun `an execve prologue that is not replayed is rejected before producing a plan`() {
        val kernel = UltraKernelPlanFixtures.syntheticKernel()
        UltraKernelA64.writeLe32(kernel, UltraKernelPlanFixtures.K22_SYMBOL_BASE.getValue("do_execveat_common"),
            UltraKernelA64.encodeAdr(0, 16, 0).toLong())
        val failure = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelPlanFixtures.recipe().render(FakeSymbols(UltraKernelPlanFixtures.K22_SYMBOL_BASE, kernel.size),
                kernel, UltraKernelPlanFixtures.freshCredential())
        }
        assertEquals(UltraKernelGenerateCodes.SITE, failure.code)
        assertEquals("HOOK_ENTRY_UNSUPPORTED:do_execveat_common", failure.detail)
    }

    @Test
    fun `a clobbered current register is not used to infer a task field`() {
        val kernel = ByteArray(256)
        val code = intArrayOf(UltraKernelA64.mrs(8, UltraKernelA64.MRS_SP_EL0),
            UltraKernelA64.movReg(8, 0), UltraKernelA64.ldAuto("ldr", 8, 8, 0x820, 8), UltraKernelA64.RET)
        code.forEachIndexed { index, word -> UltraKernelA64.writeLe32(kernel, 64 + index * 4, word.toLong()) }
        val failure = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelStructOffsets.resolve(FakeSymbols(mapOf("__arm64_sys_getuid" to 64, "next" to 128), 256),
                kernel, setOf(UltraKernelStructOffsets.CRED), emptyMap())
        }
        assertEquals(UltraKernelGenerateCodes.STRUCT_OFFSET, failure.code)
    }

    @Test
    fun `cred inference requires a pointer load rather than an unrelated word field`() {
        val kernel = ByteArray(256)
        val code = intArrayOf(UltraKernelA64.mrs(8, UltraKernelA64.MRS_SP_EL0),
            UltraKernelA64.ldAuto("ldr", 0, 8, 24, 4), UltraKernelA64.RET)
        code.forEachIndexed { index, word -> UltraKernelA64.writeLe32(kernel, 64 + index * 4, word.toLong()) }
        assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelStructOffsets.resolve(FakeSymbols(mapOf("__arm64_sys_getuid" to 64, "next" to 128), 256),
                kernel, setOf(UltraKernelStructOffsets.CRED), emptyMap())
        }
    }

    @Test
    fun `6_1 cred fields follow the syscall accessors rather than the 6_6 layout`() {
        val boot = File("../Boot_Research/哈/boot.img")
        assumeTrue(boot.isFile)
        val plan = UltraKernelSpecGenerator().generate(boot)
        assertEquals("reply starts at SUID", 12, plan.layout.offset(UltraKernelStructOffsets.SUID))
        assertEquals("reply ends at SGID", 16, plan.layout.offset(UltraKernelStructOffsets.SGID))
        assertEquals("EUID remains separate from the reply", 20, plan.layout.offset(UltraKernelStructOffsets.CRED_EUID))
        for (field in listOf(UltraKernelStructOffsets.SUID, UltraKernelStructOffsets.SGID, UltraKernelStructOffsets.CRED_EUID)) {
            assertEquals("native credential field width", 4, plan.layout.fields.getValue(field).widthBytes)
        }
        val exec = plan.records.single { it.role == "__drm_printfn_coredump+0x0" }
        assertEquals(UltraKernelA64.addImm(17, 17, 12), UltraKernelA64.le32Bits(exec.replacement, 4))
    }

    @Test
    fun `the guide uses a stack path and releases its reference`() {
        val boot = File("../Boot_Research/哈/boot.img")
        assumeTrue(boot.isFile)
        val kernel = UltraKernelPlan.readKernel(boot)
        val symbols = UltraKernelKallsymsTable.decode(kernel)
        val plan = UltraKernelSpecGenerator().generate(kernel)
        val guide = plan.records.single { it.role == "__drm_puts_coredump+0x0" }
        val calls = (0 until guide.length step 4).mapNotNull { offset ->
            val word = UltraKernelA64.le32Bits(guide.replacement, offset)
            if ((word and 0xFC000000.toInt()) == 0x94000000.toInt())
                branchTarget(guide.offset + offset, word) else null
        }
        assertEquals(listOf(symbols.offsetOf("kern_path")!!.toLong(), symbols.offsetOf("path_put")!!.toLong()), calls)
    }

    private fun branchTarget(site: Long, word: Int): Long =
        site + UltraKernelA64.signExtend((word and 0x03FFFFFF).toLong(), 26) * 4
}
