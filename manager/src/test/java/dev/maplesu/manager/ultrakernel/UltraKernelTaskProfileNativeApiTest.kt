package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captured input binds API names, BTF signatures and bounded output arguments together. */
class UltraKernelTaskProfileNativeApiTest {
    private fun image() = File(File(System.getProperty("maplesu.repo", "..")),
        "native-inputs/kernels/android15-6.6-346d51ea/Image").readBytes()

    private fun prove(raw: ByteArray) = UltraKernelTaskProfileCode.prove(raw,
        UltraKernelKallsymsTable.decode(raw), flagsOffset = 0, taskBytes = 4800, stackOffset = 56)

    @Test fun currentNativeApiBoundariesPreserveFlagsThroughCloneAndExec() {
        val evidence = prove(image())
        assertTrue(evidence.boundaries.any { it.contains("copy_thread:104388->get_random_bytes:bounded-output:task+4712:16") })
        assertEquals(5, evidence.boundaries.count { it.contains("arch_setup_new_exec:") && it.contains("bounded-output:") })
        assertTrue(evidence.stores.any { it.contains("arch_dup_task_struct:") && it.contains("memcpy:4800") })
    }

    @Test fun randomOutputCannotOverlapTheTaskFlags() = changedWord(104180, 0x52800008, "CLEAR_FLAGS:")

    @Test fun randomOutputCannotLeaveItsTaskAllocation() = changedWord(104180, 0x52840008, "CLEAR_TASK_RANGE:")

    @Test fun randomOutputLengthMustBeKnown() = changedWord(104384, UltraKernelA64.NOP, "CLEAR_SIZE:")

    @Test fun randomApiRequiresItsActualBtfSignature() {
        val raw = image()
        val symbols = UltraKernelKallsymsTable.decode(raw)
        val base = requireNotNull(symbols.offsetOf("__start_BTF"))
        val strings = base + UltraKernelA64.le32Bits(raw, base + 4) + UltraKernelA64.le32Bits(raw, base + 16)
        val end = strings + UltraKernelA64.le32Bits(raw, base + 20)
        val name = "get_random_bytes\u0000".toByteArray(Charsets.US_ASCII)
        var changed = 0
        for (at in strings until end - name.size) {
            if ((at == strings || raw[at - 1] == 0.toByte()) && name.indices.all { raw[at + it] == name[it] }) {
                raw[at] = 'X'.code.toByte()
                changed++
            }
        }
        assertTrue("The captured BTF must contain the actual random API", changed > 0)
        val error = assertThrows(IllegalStateException::class.java) { prove(raw) }
        assertTrue(error.message, error.message?.startsWith("RANDOM_API_SIGNATURE:") == true)
    }

    private fun changedWord(at: Int, word: Int, reason: String) {
        val raw = image()
        assertEquals(if (at == 104180) 0x52824d08 else 0x52800201, UltraKernelA64.le32Bits(raw, at))
        UltraKernelA64.writeLe32(raw, at, word.toLong())
        val error = assertThrows(IllegalStateException::class.java) { prove(raw) }
        assertTrue(error.message, error.message?.startsWith(reason) == true)
    }
}
