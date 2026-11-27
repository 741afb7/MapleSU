package dev.maplesu.manager.ultrakernel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The reported Android 16 boot rewrites PAC into SCS using its original unwind addresses. */
class UltraKernelDynamicScsBootTest {
    @Test
    fun nativePacRemainsAtItsUnwindAddressAndTheFrameInstructionIsReplayed() {
        val (kernel, symbols, plan) = fixture()
        val entry = requireNotNull(symbols.offsetOf("do_execveat_common"))
        val hook = plan.records.single { it.role == "do_execveat_common+0x0" }
        val frame = plan.records.single { it.role == "arm64_notify_die+0x0" }
        val patched = plan.apply(kernel).kernel
        assertEquals(entry + 4L, hook.offset)
        assertEquals(UltraKernelA64.PACIA1716, UltraKernelA64.le32Bits(patched, entry))
        assertEquals(UltraKernelA64.le32Bits(kernel, entry + 4),
            UltraKernelA64.le32Bits(frame.replacement, 20))
        assertEquals(frame.offset + 28, branchTarget(hook.offset, hook.replacement, 0))
        assertEquals(entry + 8L, branchTarget(frame.offset, frame.replacement, 24))
        assertTrue((0 until frame.length step 4).none {
            UltraKernelA64.le32Bits(frame.replacement, it) == UltraKernelA64.PACIA1716
        })
    }

    @Test
    fun bothSdkEnvelopesNameTheActualExecveHookAfterPac() {
        val (_, symbols, plan) = fixture()
        val hook = plan.records.single { it.role == "do_execveat_common+0x0" }
        assertEquals(requireNotNull(symbols.offsetOf("do_execveat_common")) + 4L, hook.offset)
        for ((role, prefix) in listOf("free_initmem+0x0" to 4,
            "__traceiter_kvm_userspace_exit+0x70" to 0)) {
            val metadata = plan.records.single { it.role == role }
            assertEquals(role, hook.offset,
                UltraKernelA64.le32Bits(metadata.replacement, prefix + 36).toLong())
        }
    }

    @Test
    fun realExceptionAndDiagnosticFunctionsKeepTheirCode() {
        val (kernel, symbols, plan) = fixture()
        val patched = plan.apply(kernel).kernel
        for (name in plan.records.mapNotNull { it.cave }.distinct()) {
            val start = requireNotNull(symbols.offsetOf(name))
            val end = requireNotNull(symbols.nextOffset(start))
            assertArrayEquals(name, kernel.copyOfRange(start, end), patched.copyOfRange(start, end))
        }
    }

    @Test
    fun anUnreviewedDisplacedInstructionStopsGeneration() {
        val (kernel, symbols, _) = fixture()
        val changed = kernel.copyOf()
        val entry = requireNotNull(symbols.offsetOf("do_execveat_common"))
        UltraKernelA64.writeLe32(changed, entry + 4, 0x90000000L) // PC-relative ADRP cannot be copied.
        val failure = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelSpecGenerator().generate(changed)
        }
        assertEquals(UltraKernelGenerateCodes.SITE, failure.code)
        assertEquals("HOOK_FRAME_UNSUPPORTED:do_execveat_common", failure.detail)
    }

    @Test
    fun managerFlowBuildsTheSameCorrectedKernelFromTheSelectedOriginal() {
        val (kernel, symbols, _) = fixture()
        val workspace = Files.createTempDirectory("ultrakernel-dynamic-scs-").toFile()
        val before = UltraKernelSpec.sha256(boot)
        try {
            val flow = UltraKernelFlow(workspace, UltraKernelFileAssets(File("src/main/assets")))
            val preparation = flow.prepare(boot)
            assertTrue(preparation.generated)
            assertFalse(preparation.alreadyInstalled)
            val candidate = requireNotNull(preparation.candidate)
            assertEquals(boot.length(), candidate.image.length())
            assertArrayEquals(flow.generatePlan(boot).apply(kernel).kernel, UltraKernelPlan.readKernel(candidate.image))
            val entry = requireNotNull(symbols.offsetOf("do_execveat_common"))
            assertEquals(UltraKernelA64.PACIA1716,
                UltraKernelA64.le32Bits(UltraKernelPlan.readKernel(candidate.image), entry))
            assertEquals(before, UltraKernelSpec.sha256(boot))
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun branchTarget(base: Long, bytes: ByteArray, at: Int): Long {
        val word = UltraKernelA64.le32Bits(bytes, at)
        assertEquals(0x14000000, word and 0xfc000000.toInt())
        return base + at + UltraKernelA64.signExtend((word and 0x03ffffff).toLong(), 26) * 4
    }

    private fun fixture(): Triple<ByteArray, UltraKernelKallsymsTable, UltraKernelPlan> {
        assumeTrue("reported Android 16 boot absent", boot.isFile)
        return decoded
    }

    companion object {
        private val boot = File("../Log_Reference/11.6/Original.img")
        private val decoded by lazy {
            val kernel = UltraKernelPlan.readKernel(boot)
            assertEquals("ddecb86ddcd2f9ab3e27f9f5f051083af19bf89c7575779161ca6aa942a5129a",
                UltraKernelSpec.sha256(kernel))
            val symbols = UltraKernelKallsymsTable.decode(kernel)
            assertTrue(symbols.offsetOf("__pi_scs_patch") != null)
            Triple(kernel, symbols, UltraKernelSpecGenerator().generate(kernel))
        }
    }
}
