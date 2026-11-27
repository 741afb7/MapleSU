package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UltraKernelTaskProfileWorkBitTest {
    private val start = 0x1000
    private val tracepoint = 0x3540
    private val funcsOffset = 64
    private val flagsOffset = 88
    private val workBit = 13

    private fun fixture(body: List<Int>, helper: List<Int>? = null): Pair<ByteArray, UltraKernelSymbols> {
        val raw = ByteArray(0x8000)
        val code = listOf(UltraKernelA64.MRS_SP_EL0 or 20,
            0xf9400000.toInt() or ((flagsOffset / 8) shl 10) or (20 shl 5) or 8,
            0x36000000 or (workBit shl 19) or (((0x1080 - (start + 8)) / 4) shl 5) or 8) + body
        code.forEachIndexed { index, word -> UltraKernelA64.writeLe32(raw, start + index * 4, word.toLong()) }
        UltraKernelA64.writeLe32(raw, 0x1080, 0xd65f03c0.toInt().toLong())
        val names = mutableMapOf("syscall_trace_enter" to start, "syscall_trace_enter_end" to 0x1084,
            "__tracepoint_sys_enter" to tracepoint)
        if (helper != null) {
            helper.forEachIndexed { index, word -> UltraKernelA64.writeLe32(raw, 0x2000 + index * 4, word.toLong()) }
            names["trace_sys_enter"] = 0x2000; names["trace_sys_enter_end"] = 0x2000 + helper.size * 4
        }
        val symbols = object : UltraKernelSymbols {
            override val imageSize = raw.size
            override val summary = "work-bit-fixture"
            override fun offsetOf(name: String) = names[name]
            override fun isSymbolStart(value: Int) = value in names.values
            override fun nextOffset(offset: Int) = names.values.filter { it > offset }.minOrNull()
        }
        return raw to symbols
    }

    private fun page(pc: Int = start + 12, register: Int = 9) =
        UltraKernelA64.encodeAdrp(pc.toLong(), (tracepoint + funcsOffset).toLong(), register)

    private fun load(register: Int = 9) = 0xf9400000.toInt() or
        ((((tracepoint + funcsOffset) and 4095) / 8) shl 10) or (register shl 5) or 21

    private fun prove(body: List<Int>): UltraKernelTaskProfileCode.WorkBitEvidence {
        val (raw, symbols) = fixture(body)
        return UltraKernelTaskProfileCode.workBit(raw, symbols, flagsOffset, false, funcsOffset)
    }

    private fun rejected(body: List<Int>) {
        val error = assertThrows(IllegalArgumentException::class.java) { prove(body) }
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TRACE_WORK_BRANCH", error.message)
    }

    @Test fun unrelatedLoadArithmeticAndStorePreserveTheTracepointPageRegister() {
        val evidence = prove(listOf(page(), 0xb9401288.toInt(), 0x11000508, 0xb9001288.toInt(), load(),
            0xd65f03c0.toInt()))
        assertEquals(workBit, evidence.bit)
        assertEquals(start + 28, evidence.traceReference)
    }

    @Test fun nonAdjacentAddUsesTheTrackedPageValue() {
        val add = 0x91000000.toInt() or (((tracepoint and 4095)) shl 10) or (9 shl 5) or 10
        val evidence = prove(listOf(page(), UltraKernelA64.NOP, add, 0xd65f03c0.toInt()))
        assertEquals(workBit, evidence.bit)
        assertEquals(start + 20, evidence.traceReference)
    }

    @Test fun overwritingTheActualPageRegisterDiscardsItsOldAddress() = rejected(
        listOf(page(), UltraKernelA64.movz(9, 0), load(), 0xd65f03c0.toInt()))

    @Test fun aCallDiscardsCallerSavedPageRegisters() = rejected(listOf(page(),
        UltraKernelA64.encodeBl((start + 16).toLong(), 0x2000), load(), 0xd65f03c0.toInt()))

    @Test fun branchPathsKeepTheirOwnRegisterValuesUntilTheMerge() {
        val evidence = prove(listOf(
            UltraKernelA64.encodeCbz(0, (start + 12).toLong(), (start + 32).toLong(), true, false),
            page(start + 16), UltraKernelA64.NOP,
            UltraKernelA64.encodeB((start + 24).toLong(), (start + 40).toLong()), UltraKernelA64.NOP,
            UltraKernelA64.movz(9, 0), UltraKernelA64.NOP, load(), 0xd65f03c0.toInt()))
        assertEquals(workBit, evidence.bit)
        assertEquals(start + 40, evidence.traceReference)
    }

    @Test fun branchesCannotCombineAnAddressFromOnePathWithAUseFromAnother() = rejected(listOf(
        UltraKernelA64.encodeCbz(0, (start + 12).toLong(), (start + 32).toLong(), true, false),
        page(start + 16), UltraKernelA64.movz(9, 0),
        UltraKernelA64.encodeB((start + 24).toLong(), (start + 40).toLong()), UltraKernelA64.NOP,
        UltraKernelA64.movz(10, 0), UltraKernelA64.NOP, load(), 0xd65f03c0.toInt()))

    @Test fun bothWorkBitEdgesReachingTheSameTracepointDoesNotProveTheBit() {
        val (raw, symbols) = fixture(listOf(page(), load(), 0xd65f03c0.toInt()))
        UltraKernelA64.writeLe32(raw, start + 8,
            (0x36000000 or (workBit shl 19) or (1 shl 5) or 8).toLong())
        val error = assertThrows(IllegalArgumentException::class.java) {
            UltraKernelTaskProfileCode.workBit(raw, symbols, flagsOffset, false, funcsOffset)
        }
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TRACE_WORK_BRANCH", error.message)
    }

    @Test fun traceHelperMustReachTheActualTracepointObject() {
        val (raw, symbols) = fixture(listOf(UltraKernelA64.encodeBl((start + 12).toLong(), 0x2000),
            0xd65f03c0.toInt()), listOf(page(0x2000), load(), 0xd65f03c0.toInt()))
        val evidence = UltraKernelTaskProfileCode.workBit(raw, symbols, flagsOffset, false, funcsOffset)
        assertEquals(workBit, evidence.bit)
        assertEquals(0x2004, evidence.traceReference)
    }

    @Test fun helperNameAloneDoesNotProveItsTracepoint() {
        val (raw, symbols) = fixture(listOf(UltraKernelA64.encodeBl((start + 12).toLong(), 0x2000),
            0xd65f03c0.toInt()), listOf(0xd65f03c0.toInt()))
        val error = assertThrows(IllegalArgumentException::class.java) {
            UltraKernelTaskProfileCode.workBit(raw, symbols, flagsOffset, false, funcsOffset)
        }
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TRACE_WORK_BRANCH", error.message)
    }

    @Test fun twoEdgesCallingTheSameTraceHelperDoNotProveTheWorkBit() {
        val (raw, symbols) = fixture(listOf(UltraKernelA64.encodeBl((start + 12).toLong(), 0x2000),
            0xd65f03c0.toInt(), UltraKernelA64.encodeBl((start + 20).toLong(), 0x2000), 0xd65f03c0.toInt()),
            listOf(page(0x2000), load(), 0xd65f03c0.toInt()))
        UltraKernelA64.writeLe32(raw, start + 8,
            (0x36000000 or (workBit shl 19) or (3 shl 5) or 8).toLong())
        val error = assertThrows(IllegalArgumentException::class.java) {
            UltraKernelTaskProfileCode.workBit(raw, symbols, flagsOffset, false, funcsOffset)
        }
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TRACE_WORK_BRANCH", error.message)
    }

    @Test fun actual618TraceWrapperResolvesTheBranchFromItsOwnInstructions() {
        val root = File(System.getProperty("maplesu.repo", ".."))
        val raw = File(root, "native-inputs/kernels/android17-6.18-4f47635b/Image").readBytes()
        val symbols = UltraKernelKallsymsTable.decode(raw)
        val layout = requireNotNull(UltraKernelBtf.abi(raw, symbols,
            setOf("task_struct.thread_info", "thread_info.flags", "tracepoint.funcs")))
        val offset = layout.fields.getValue("task_struct.thread_info").offsetBytes +
            layout.fields.getValue("thread_info.flags").offsetBytes
        val evidence = UltraKernelTaskProfileCode.workBit(raw, symbols, offset, false,
            layout.fields.getValue("tracepoint.funcs").offsetBytes)
        val branch = UltraKernelA64.le32Bits(raw, evidence.branch)
        assertEquals(((branch ushr 19) and 31) or ((branch ushr 26) and 32), evidence.bit)
        val helper = requireNotNull(symbols.offsetOf("trace_sys_enter"))
        assertTrue(evidence.traceReference in helper until requireNotNull(symbols.nextOffset(helper)))
        assertEquals(0xf9400000.toInt(), UltraKernelA64.le32Bits(raw, evidence.traceReference) and 0xffc00000.toInt())
    }

    @Test fun actualK70uImageResolvesItsOwnBtfWorkMemberAndTracepointReference() {
        val root = File(System.getProperty("maplesu.repo", ".."))
        val raw = UltraKernelPlan.readKernel(File(root, "Boot_Research/._boot/k70uboot.img"))
        val symbols = UltraKernelKallsymsTable.decode(raw)
        val layout = requireNotNull(UltraKernelBtf.abi(raw, symbols,
            setOf("task_struct.thread_info", "thread_info.flags", "tracepoint.funcs"), emptySet()))
        val offset = layout.fields.getValue("task_struct.thread_info").offsetBytes +
            layout.fields.getValue("thread_info.flags").offsetBytes
        val funcs = layout.fields.getValue("tracepoint.funcs").offsetBytes
        val evidence = UltraKernelTaskProfileCode.workBit(raw, symbols, offset, false, funcs)
        assertEquals("syscall_trace_enter", evidence.function)
        val word = UltraKernelA64.le32Bits(raw, evidence.branch)
        assertEquals(((word ushr 19) and 31) or ((word ushr 26) and 32), evidence.bit)
        assertTrue(evidence.bit in 0..62)
        assertTrue(evidence.traceReference > evidence.branch)
        assertEquals(0xf9400000.toInt(), UltraKernelA64.le32Bits(raw, evidence.traceReference) and 0xffc00000.toInt())
    }
}
